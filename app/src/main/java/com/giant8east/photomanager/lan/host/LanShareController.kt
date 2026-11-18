/** 局域网共享的开关与生命周期：持有 token/配对状态、Ktor 引擎、mDNS 与前台服务。 */
package com.giant8east.photomanager.lan.host

import com.giant8east.photomanager.lan.host.server.LanPairing
import com.giant8east.photomanager.lan.host.server.LanServer
import com.giant8east.photomanager.lan.host.server.LanTokenStore
import com.giant8east.photomanager.lan.platform.AndroidLanIdentity
import com.giant8east.photomanager.lan.platform.AndroidNetworkStatus
import com.giant8east.photomanager.lan.platform.MdnsAnnouncer
import com.giant8east.photomanager.lan.platform.ServiceBackgroundHost
import com.giant8east.photomanager.lan.protocol.DeviceInfoDto
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
    // 本机身份单独一个类：分享方要用它报设备名，访客方配对时也要报同一个 deviceId
    private val identity: AndroidLanIdentity,
    // 只依赖端口：实现（ShareGateway / AllowedDeviceData）由 DI 的 LanModule 绑定
    private val dataSource: ShareDataSource,
    private val allowedDevices: WhitelistStore,
    private val backgroundHost: ServiceBackgroundHost,
    private val announcer: MdnsAnnouncer,
    private val networkStatus: AndroidNetworkStatus
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val tokens = LanTokenStore()
    private val pairing = LanPairing(tokens, allowedDevices)

    private var server: LanServer? = null

    private val mutableState = MutableStateFlow(LanShareState())
    val state: StateFlow<LanShareState> = mutableState.asStateFlow()

    /** 待主机确认的配对请求，M5 的弹窗订阅它。 */
    val pairingRequests: StateFlow<List<LanPairing.Pending>> = pairing.requests

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
        tokens.revoke(deviceId)
    }

    private suspend fun startLocked() {
        if (server != null) return

        // 先拉起前台服务，保证进程在绑定端口期间有前台优先级
        backgroundHost.start()
        val created = LanServer(
            dataSource = dataSource,
            pairing = pairing,
            tokens = tokens,
            deviceInfo = {
                DeviceInfoDto(
                    deviceId = identity.deviceId,
                    name = identity.deviceName,
                    version = identity.version
                )
            }
        )
        val port = try {
            created.start()
        } catch (error: Exception) {
            // 绑定失败：把刚拉起的服务收掉，避免留下一个"共享已开启"的通知
            backgroundHost.stop()
            throw error
        }

        server = created
        networkStatus.start { refreshAdvertisement() }
        publishLocked(port)
    }

    private suspend fun stopLocked() {
        announcer.stop()
        networkStatus.stop()
        server?.stop()
        server = null
        // 关闭共享后所有 token 立即失效、待确认请求作废：这是"开关"的业务语义，不属于引擎
        tokens.clear()
        pairing.clear()
        backgroundHost.stop()
        mutableState.value = LanShareState()
    }

    /** 根据当前 Wi-Fi 地址刷新 mDNS 与状态；端口不变，只有 IP 会随网络切换。 */
    private suspend fun publishLocked(port: Int) {
        val ip = networkStatus.currentAddress()
        if (ip != null) {
            announcer.announce(
                LanAdvertisement(
                    serviceName = "PhotoManager-${identity.deviceId.take(8)}",
                    deviceName = identity.deviceName,
                    ip = ip,
                    port = port,
                    version = identity.version,
                    deviceId = identity.deviceId
                )
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
}
