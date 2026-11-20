/** 局域网共享的开关与生命周期：持有 token/配对状态、Ktor 引擎、mDNS 与前台服务。 */
package com.giant8east.photomanager.lan.server

import android.util.Log
import com.giant8east.photomanager.lan.AndroidNetworkStatus
import com.giant8east.photomanager.lan.WifiAddress
import com.giant8east.photomanager.lan.protocol.DeviceInfoDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.LanIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
    private val identity: LanIdentity,
    // 只依赖端口：实现（ShareGateway / AllowedDeviceData）由 DI 的 LanModule 绑定
    private val dataSource: ShareDataSource,
    private val allowedDevices: WhitelistStore,
    private val backgroundHost: ServiceBackgroundHost,
    private val announcer: MdnsAnnouncer,
    private val networkStatus: AndroidNetworkStatus,
    // 热点侧地址没有系统回调可用，只能靠枚举网卡算（见 HotspotAddress 的注释）
    private val hotspot: HotspotAddress
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val tokens = LanTokenStore()
    private val pairing = LanPairing(tokens, allowedDevices)

    private var server: LanServer? = null

    /** 共享期间的地址看门狗：热点开关没有回调，只能定时看。只在共享期间存在。 */
    private var addressJob: Job? = null

    /** 上一次广播出去的 IP：地址没变就不要重复注册 mDNS。 */
    private var publishedAddress: String? = null

    private val mutableState = MutableStateFlow(LanShareState())
    val state: StateFlow<LanShareState> = mutableState.asStateFlow()

    // replay = 0：事件不重放；extraBufferCapacity = 1 让 tryEmit 在"没人订阅"时也能立即返回
    private val mutableEvents = MutableSharedFlow<LanShareEvent>(extraBufferCapacity = 1)

    /** 一次性提示事件（如 Wi-Fi 掉线导致共享被自动关闭），页面收到后弹 Toast。 */
    val events: SharedFlow<LanShareEvent> = mutableEvents.asSharedFlow()

    /** 待主机确认的配对请求，M5 的弹窗订阅它。 */
    val pairingRequests: StateFlow<List<LanPairing.Pending>> = pairing.requests

    /** 已记住的设备：列表由 DAO 的 Flow 推过来，增删只走 approvePairing / revokeDevice。 */
    val trustedDevices: StateFlow<List<LanTrustedDevice>> =
        allowedDevices.observeTrusted()
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * 开关入口：返回本次操作的结果，[LanShareResult] 里的三种失败
     * （[LanShareResult.NoWifiAddress] / [LanShareResult.StartFailed] / [LanShareResult.AdvertiseFailed]）
     * 都由 UI 负责提示用户，共享保持关闭。
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
        // 顺序：连着的 Wi-Fi（STA）→ 热点接口。只有热点没有 Wi-Fi 时也能共享，
        // 这时对端连的就是热点网段里的我们。
        val ip = networkStatus.awaitAddress() ?: hotspot.current()
            ?: return LanShareResult.NoWifiAddress

        // 先拉起前台服务，保证进程在绑定端口期间有前台优先级
        try {
            backgroundHost.start()
        } catch (error: Exception) {
            Log.w(TAG, "failed to start foreground service: ${error.message}")
            return LanShareResult.StartFailed
        }
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
        } catch (cancelled: CancellationException) {
            // 协程取消必须继续抛出：收掉刚拉起的服务就够了，不能把它说成"启动失败"
            backgroundHost.stop()
            throw cancelled
        } catch (error: Exception) {
            // 绑定失败／拿不到实际端口：把刚拉起的服务收掉，避免留下一个"共享已开启"的通知。
            // 原因不外抛——UI 已经在这个返回值的 when 里等着，抛出去只会变成崩溃；
            // 原始异常信息进日志，用户看到的是 lan_share_start_failed 那行提示。
            Log.w(TAG, "failed to start LAN server: ${error.message}")
            backgroundHost.stop()
            return LanShareResult.StartFailed
        }

        server = created
        // 广播失败等于对端发现不到这台设备：把刚启动的东西全部收掉，保持关闭
        if (!publishLocked(port, ip)) {
            stopLocked()
            return LanShareResult.AdvertiseFailed
        }
        startAddressMonitorLocked()
        return LanShareResult.Started
    }

    /**
     * 共享期间的地址看门狗。
     *
     * 为什么需要它：Wi-Fi 有 `ConnectivityManager` 回调，但热点没有（`SoftApCallback` 是 SystemApi），
     * 所以热点开关、热点地址变化只能定时探。两件事都靠它：
     * - 地址变了（比如 Wi-Fi 断了但热点还在）→ 换地址重新广播，正在连着的对端不受影响；
     * - 地址没了（Wi-Fi 与热点都没有）→ 关共享并提示，不留在"开着但谁也连不上"的假状态。
     */
    private fun startAddressMonitorLocked() {
        addressJob?.cancel()
        addressJob = scope.launch {
            var missingTicks = 0
            while (isActive) {
                delay(ADDRESS_POLL_INTERVAL_MILLIS.milliseconds)
                val current = currentAddress()
                val keepRunning = mutex.withLock {
                    val running = server ?: return@withLock false
                    if (current == null) {
                        // 切网时地址会短暂全无，连续几轮都探不到才真的关
                        missingTicks++
                        if (missingTicks < ADDRESS_LOST_TICKS) return@withLock true
                        // 下面会停共享；先把引用摘掉，否则 cancel 会取消到自己，
                        // 导致 stopLocked 在 pairing.clear() 那个挂起点上中断，留下半状态
                        addressJob = null
                        stopLocked()
                        mutableEvents.tryEmit(LanShareEvent.WifiLost)
                        return@withLock false
                    }
                    missingTicks = 0
                    if (current == publishedAddress) return@withLock true
                    if (publishLocked(running.port, current)) return@withLock true
                    addressJob = null
                    stopLocked()
                    mutableEvents.tryEmit(LanShareEvent.AdvertiseFailed)
                    return@withLock false
                }
                if (!keepRunning) return@launch
            }
        }
    }

    /**
     * 本机"对端该用的地址"：优先连着的 Wi-Fi（STA），其次热点。
     *
     * 两者取数方式不同是刻意的：STA 用 `ConnectivityManager` 的结果（网络一消失立刻算没有，不会滞后），
     * 热点只能用网卡枚举（网卡上的地址会比"热点已关"晚消失一会儿，所以只信 `isUp` + 有 IPv4）。
     */
    private fun currentAddress(): String? =
        (networkStatus.ipv4.value as? WifiAddress.Present)?.value ?: hotspot.current()

    private suspend fun stopLocked(): LanShareResult {
        // 看门狗先撤。它自己触发关闭时已经把 addressJob 置空，所以这里不会取消到它自己。
        addressJob?.cancel()
        addressJob = null
        publishedAddress = null
        // 这两步都没有异常出口：announcer.stop() 内部判空 + runCatching；server.stop() 是 Ktor 的
        // shutdownServer（complete / join / destroyApplication），dispose 的异常在库内部就被吞掉了
        announcer.stop()
        server?.stop()
        server = null
        // 关闭共享后所有 token 立即失效、待确认请求作废：这是"开关"的业务语义，不属于引擎
        tokens.clear()
        backgroundHost.stop()
        mutableState.value = LanShareState()
        // 唯一的挂起点（Mutex.lock 可取消）必须留到最后：用户点关闭后马上返回会取消
        // viewModelScope，取消只可能断在这一行，此时通知与开关状态已经复位，不会留下
        // "通知还在、开关还亮、实际谁也连不上"的半状态
        pairing.clear()
        return LanShareResult.Closed
    }

    /**
     * 广播地址并刷新状态：端口不变，只有 IP 会随网络切换。
     *
     * @return 注册是否成功；失败时**不改状态**，由调用方决定回滚还是关闭。
     */
    private suspend fun publishLocked(port: Int, ip: String): Boolean {
        val device = LanDevice(
            name = identity.deviceName,
            ip = ip,
            port = port,
            deviceId = identity.deviceId,
            version = identity.version
        )
        val registered = announcer.announce(device)
        if (!registered) return false
        publishedAddress = ip
        mutableState.value = LanShareState(
            isRunning = true,
            address = "http://$ip:$port"
        )
        return true
    }

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        /**
         * 地址轮询间隔。热点没有系统回调，只能自己看；3 秒的代价是 getifaddrs 一次系统调用，
         * 而收益是"关热点后最多 3 秒内反映到状态"。
         */
        const val ADDRESS_POLL_INTERVAL_MILLIS = 3_000L

        /** 连续几轮探不到地址才关共享：切网时地址会短暂全无，一轮就关会误伤漫游。 */
        const val ADDRESS_LOST_TICKS = 2

        /** logcat 过滤器标签：adb logcat -s LanShareController */
        const val TAG = "LanShareController"
    }
}
