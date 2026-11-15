/** 局域网共享的开关与生命周期：持有 token/配对状态、Ktor 引擎、mDNS 与前台服务。 */
package com.example.photomanager.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import com.example.photomanager.data.AllowedDeviceData
import com.example.photomanager.lan.server.LanAdvertiser
import com.example.photomanager.lan.server.LanNetwork
import com.example.photomanager.lan.server.LanShareService
import com.example.photomanager.lan.server.ShareGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 单例控制器：UI 只调用 [setEnabled] 并订阅 [state]。
 *
 * 引擎是"短命对象"——每次开启都新建 [LanServer]，关闭时丢弃；
 * 控制器用 [mutex] 串行化开关，避免重复 bind。
 */
@Singleton
class LanShareController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataSource: ShareGateway,
    private val allowedDevices: AllowedDeviceData
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val tokens = LanTokenStore()
    private val pairing = LanPairing(tokens, allowedDevices)

    private var server: LanServer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val mutableState = MutableStateFlow(LanShareState())
    val state: StateFlow<LanShareState> = mutableState.asStateFlow()

    /** 待主机确认的配对请求，M5 的弹窗订阅它。 */
    val pairingRequests: StateFlow<List<LanPairing.Pending>> = pairing.requests

    /** 设备标识：首次生成后持久化，避免每次重启变成"新设备"。 */
    private val deviceId: String by lazy {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) {
            existing
        } else {
            UUID.randomUUID().toString().also { created ->
                prefs.edit().putString(KEY_DEVICE_ID, created).apply()
            }
        }
    }

    private val versionName: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }

    /** 开关入口；非挂起，内部用应用级协程串行执行，方便 UI 直接调用。 */
    fun setEnabled(enabled: Boolean) {
        scope.launch {
            mutex.withLock {
                if (enabled) startLocked() else stopLocked()
            }
        }
    }

    suspend fun approvePairing(requestId: String, remember: Boolean) {
        pairing.approve(requestId, remember)
    }

    suspend fun rejectPairing(requestId: String) {
        pairing.reject(requestId)
    }

    /** 主机撤销设备：删白名单 + 让旧 token 立即失效。 */
    suspend fun revokeDevice(deviceId: String) {
        allowedDevices.remove(deviceId)
        server?.revokeDevice(deviceId)
    }

    private suspend fun startLocked() {
        if (server != null) return

        // 先拉起前台服务，保证进程在绑定端口期间有前台优先级
        LanShareService.start(context)
        val created = LanServer(
            dataSource = dataSource,
            pairing = pairing,
            tokens = tokens,
            deviceInfo = { DeviceInfoDto(deviceId = deviceId, name = Build.MODEL, version = versionName) }
        )
        val port = try {
            created.start()
        } catch (error: Exception) {
            // 绑定失败：把刚拉起的服务收掉，避免留下一个"共享已开启"的通知
            LanShareService.stop(context)
            throw error
        }

        server = created
        registerNetworkCallback()
        publishLocked(port)
    }

    private suspend fun stopLocked() {
        LanAdvertiser.unregister(context)
        unregisterNetworkCallback()
        server?.stop()
        server = null
        LanShareService.stop(context)
        mutableState.value = LanShareState()
    }

    /** 根据当前 Wi-Fi 地址刷新 mDNS 与状态；端口不变，只有 IP 会随网络切换。 */
    private suspend fun publishLocked(port: Int) {
        val ip = LanNetwork.wifiIpv4(context)
        if (ip != null) {
            LanAdvertiser.register(
                context = context,
                serviceName = "PhotoManager-${deviceId.take(8)}",
                deviceName = Build.MODEL,
                ip = ip,
                port = port,
                version = versionName,
                deviceId = deviceId
            )
        }
        mutableState.value = LanShareState(
            isRunning = true,
            address = ip?.let { "http://$it:$port" }
        )
    }

    /** Wi-Fi 切换/重连时 IP 会变：重新广播，避免对端继续用旧地址。 */
    private fun refreshAdvertisement() {
        scope.launch {
            mutex.withLock {
                val current = server ?: return@withLock
                publishLocked(current.port)
            }
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refreshAdvertisement()
            override fun onLost(network: Network) = refreshAdvertisement()
        }
        manager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        context.getSystemService(ConnectivityManager::class.java)
            ?.unregisterNetworkCallback(callback)
        networkCallback = null
    }

    private companion object {
        const val PREFS_NAME = "lan_share"
        const val KEY_DEVICE_ID = "device_id"
    }
}
