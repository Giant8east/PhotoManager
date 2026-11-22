/** 局域网共享的开关与生命周期：持有 token/配对状态、Ktor 引擎、mDNS 与前台服务。 */
package com.giant8east.photomanager.lan.server

import android.util.Log
import com.giant8east.photomanager.lan.LocalAddress
import com.giant8east.photomanager.lan.protocol.DeviceInfoDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.LanIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
    // 地址（Wi-Fi / 热点、优先级、变化观察）整件事都在它里面，控制器只消费结果
    private val localAddress: LocalAddress
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val tokens = LanTokenStore()
    private val pairing = LanPairing(tokens, allowedDevices)

    private var server: LanServer? = null

    /** 地址观察协程：只在共享期间存在，负责"地址变了重新广播、没了关共享"。 */
    private var addressJob: Job? = null

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
     * （[LanShareResult.NoAddress] / [LanShareResult.StartFailed] / [LanShareResult.AdvertiseFailed]）
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
        // 顺序由 LocalAddress 决定：连着的 Wi-Fi（STA）优先，其次热点——只有热点没有 Wi-Fi 时也能共享。
        val ip = localAddress.awaitAddress() ?: return LanShareResult.NoAddress

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
     * 共享期间订阅地址变化。
     *
     * 两种变化都只影响"要不要继续开着"：
     * - 地址变了（比如 Wi-Fi 断了但热点还在）→ 用新地址重新广播，正在连着的对端不受影响；
     * - 地址没了（`null`）→ 关共享并提示，不留在"开着但谁也连不上"的假状态。
     *
     * 什么时候算"变了"、什么时候算"没了"由 [LocalAddress.watch] 判定（含切网去抖），这里只处理结果。
     */
    private fun startAddressMonitorLocked() {
        addressJob?.cancel()
        addressJob = scope.launch {
            localAddress.watch().collect { current ->
                if (current == null) {
                    closeFromWatcher(LanShareEvent.AddressLost)
                    return@collect
                }
                val failed = mutex.withLock {
                    val running = server ?: return@withLock false
                    !publishLocked(running.port, current)
                }
                if (failed) closeFromWatcher(LanShareEvent.AdvertiseFailed)
            }
        }
    }

    /**
     * 由地址观察触发的关闭：另起一个协程去拿锁收尾，避免观察协程在 collect 里取消自己。
     */
    private fun closeFromWatcher(reason: LanShareEvent) {
        scope.launch {
            mutex.withLock {
                if (server == null) return@withLock
                stopLocked()
                mutableEvents.tryEmit(reason)
            }
        }
    }

    private suspend fun stopLocked(): LanShareResult = withContext(NonCancellable) {
        // 清理段不可取消：即使调用方（如页面返回取消 viewModelScope）中途取消，
        // 也能保证 mDNS、引擎、token、配对记录、前台服务与 UI 状态全部原子复位，不留半状态。
        addressJob?.cancel()
        addressJob = null
        announcer.stop()
        server?.stop()
        server = null
        tokens.clear()
        pairing.clear()
        backgroundHost.stop()
        mutableState.value = LanShareState()
        LanShareResult.Closed
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
        mutableState.value = LanShareState(
            isRunning = true,
            address = "http://$ip:$port"
        )
        return true
    }

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        /** logcat 过滤器标签：adb logcat -s LanShareController */
        const val TAG = "LanShareController"
    }
}
