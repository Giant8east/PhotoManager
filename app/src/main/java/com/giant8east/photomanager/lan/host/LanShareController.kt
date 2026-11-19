/** 局域网共享的开关与生命周期：持有 token/配对状态、Ktor 引擎、mDNS 与前台服务。 */
package com.giant8east.photomanager.lan.host

import com.giant8east.photomanager.lan.guest.LanDevice
import com.giant8east.photomanager.lan.host.server.LanPairing
import com.giant8east.photomanager.lan.host.server.LanServer
import com.giant8east.photomanager.lan.host.server.LanTokenStore
import com.giant8east.photomanager.lan.platform.AndroidLanIdentity
import com.giant8east.photomanager.lan.platform.AndroidNetworkStatus
import com.giant8east.photomanager.lan.platform.MdnsAnnouncer
import com.giant8east.photomanager.lan.platform.ServiceBackgroundHost
import com.giant8east.photomanager.lan.platform.WifiAddress
import com.giant8east.photomanager.lan.protocol.DeviceInfoDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

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

    // replay = 0：事件不重放；extraBufferCapacity = 1 让 tryEmit 在"没人订阅"时也能立即返回
    private val mutableEvents = MutableSharedFlow<LanShareEvent>(extraBufferCapacity = 1)

    /** 一次性提示事件（如 Wi-Fi 掉线导致共享被自动关闭），页面收到后弹 Toast。 */
    val events: SharedFlow<LanShareEvent> = mutableEvents.asSharedFlow()

    /** 待主机确认的配对请求，M5 的弹窗订阅它。 */
    val pairingRequests: StateFlow<List<LanPairing.Pending>> = pairing.requests

    init {
        // 全程盯着 Wi-Fi 地址：共享期间地址变了要重新广播，地址没了要主动关共享，
        // 否则开关会停在"开着但谁也发现不到"的假状态。共享关闭时 server 为 null，这里什么都不做。
        scope.launch {
            networkStatus.ipv4.collect { address ->
                when (address) {
                    is WifiAddress.Present ->
                        mutex.withLock {
                            val current = server ?: return@withLock
                            // 换网后重新广播；失败说明这台设备又"看不见"了，关掉并提示
                            if (!publishLocked(current.port, address.value)) {
                                stopLocked()
                                mutableEvents.tryEmit(LanShareEvent.AdvertiseFailed)
                            }
                        }

                    WifiAddress.Absent -> {
                        // Wi-Fi 切换/重连时系统会先报旧网络 onLost、再报新网络 onAvailable，
                        // 中间会短暂的空窗；给一个宽限期，只有空窗持续下去才真的关。
                        delay(WIFI_LOSS_GRACE_MILLIS.milliseconds)
                        mutex.withLock {
                            if (server != null && networkStatus.ipv4.value !is WifiAddress.Present) {
                                stopLocked()
                                // 只有"本来开着、被我们关掉"才通知，用户的主动开关不在这里发事件
                                mutableEvents.tryEmit(LanShareEvent.WifiLost)
                            }
                        }
                    }

                    WifiAddress.Unknown -> Unit
                }
            }
        }
    }

    /**
     * 开关入口：返回本次操作的结果，[LanShareResult.NoWifiAddress] 时 UI 负责提示用户。
     * 引擎的启动与停止会阻塞线程，所以统一切到 Default 调度器，不占用调用方（UI 主线程或前台服务）的线程。
     */
    suspend fun setEnabled(enabled: Boolean): LanShareResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (enabled) startLocked() else stopLocked()
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

    private suspend fun startLocked(): LanShareResult {
        if (server != null) return LanShareResult.Started

        // 没有可用 IPv4 就没法广播地址，UI 也只能显示一个空地址。所以在拉起任何东西之前先确认，
        // 失败时直接返回"保持关闭"，不需要回滚已经启动的服务或引擎。
        val ip = networkStatus.awaitAddress() ?: return LanShareResult.NoWifiAddress

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
        // 广播失败等于对端发现不到这台设备：把刚启动的东西全部收掉，保持关闭
        if (!publishLocked(port, ip)) {
            stopLocked()
            return LanShareResult.AdvertiseFailed
        }
        return LanShareResult.Started
    }

    private suspend fun stopLocked(): LanShareResult {
        announcer.stop()
        server?.stop()
        server = null
        // 关闭共享后所有 token 立即失效、待确认请求作废：这是"开关"的业务语义，不属于引擎
        tokens.clear()
        pairing.clear()
        backgroundHost.stop()
        mutableState.value = LanShareState()
        return LanShareResult.Closed
    }

    /**
     * 广播地址并刷新状态：端口不变，只有 IP 会随网络切换。
     *
     * @return 注册是否成功；失败时**不改状态**，由调用方决定回滚还是关闭。
     */
    private suspend fun publishLocked(port: Int, ip: String): Boolean {
        val registered = announcer.announce(
            LanDevice(
                name = identity.deviceName,
                ip = ip,
                port = port,
                deviceId = identity.deviceId,
                version = identity.version
            )
        )
        if (!registered) return false
        mutableState.value = LanShareState(
            isRunning = true,
            address = "http://$ip:$port"
        )
        return true
    }

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        /** Wi-Fi 空窗的宽限期：切网时先 onLost 再 onAvailable，太快关共享会误伤漫游。 */
        const val WIFI_LOSS_GRACE_MILLIS = 2_000L
    }
}
