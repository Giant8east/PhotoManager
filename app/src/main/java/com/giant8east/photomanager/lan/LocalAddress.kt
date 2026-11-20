/**
 * 本机地址的唯一来源，两端共用：
 * 分享方用它决定"对外广播哪个地址、什么时候要重新广播或关共享"，访客方用它判断"本机 Wi-Fi 是不是断了"。
 */
package com.giant8east.photomanager.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Wi-Fi（STA）IPv4 的三种状态。
 *
 * 用一个类型而不是 `String?`，是因为 null 会同时承载两种相反的含义：
 * [Unknown] 是"进程刚启动、回调还没到"，值得再等一下；[Absent] 是"确认没有可用地址"，可以直接下结论。
 */
sealed interface WifiAddress {
    /** 回调还没送达，暂时不知道（刚启动或系统没有回调）。 */
    data object Unknown : WifiAddress

    /** 确认不可用：没连 Wi-Fi、Wi-Fi 已断开，或地址还没分配好。 */
    data object Absent : WifiAddress

    /** 有一个可用的 IPv4。 */
    data class Present(val value: String) : WifiAddress
}

/**
 * 回答"本机对端能用哪个地址"。
 *
 * 为什么是**一个类里两个来源**而不是一个接口加两个实现：这两个来源不是"同一件事的两种实现"，
 * 而是性质不同的两份输入——
 * - Wi-Fi（STA）由系统回调推过来：异步、可能永远不回，所以要能表达"还不知道"（[WifiAddress.Unknown]）；
 * - 热点只能我们自己扫网卡：同步、一定有结论，`null` 就只表示"没有"。
 * 抽成 `interface { suspend fun get(): String? }` 会把这两种语义压平成同一种 null，反而丢信息；
 * 而"谁优先"只是一行 `?:`，不需要多态。
 *
 * 两个来源的取数方式为什么不同：STA 用 [ConnectivityManager]（网络一消失立刻算没有），
 * 热点只能用 [NetworkInterface] 枚举（网卡上的地址会比"热点已关"晚消失一会儿，所以只信 `isUp` + 有 IPv4）。
 */
@Singleton
class LocalAddress @Inject constructor(
    @ApplicationContext context: Context
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val mutableWifi = MutableStateFlow<WifiAddress>(WifiAddress.Unknown)

    /** 当前 Wi-Fi IPv4 的状态：访客侧用它判断"本机 Wi-Fi 是不是断了"。 */
    val ipv4: StateFlow<WifiAddress> = mutableWifi.asStateFlow()

    /**
     * 每条 Wi-Fi 网络各自的地址。
     *
     * 必须按网络记账：`NetworkRequest.addTransportType(TRANSPORT_WIFI)` 匹配的是**所有** Wi-Fi 网络
     * （双 WLAN 加速、Wi-Fi Direct 的 p2p0、双频并发），一条断掉不代表本机没有地址了。
     * 只记一个"当前地址"的话，副网络消失就会把主网络还活着的地址抹掉。
     *
     * 只在 [lock] 内读写：回调投递在主线程，而 [currentAddress] / [ipv4] 会被任意线程调用。
     */
    private val wifiAddresses = LinkedHashMap<Network, String>()

    private val lock = Any()

    init {
        val connectivity = manager
        if (connectivity == null) {
            // 系统常驻服务在真实设备上取不到只可能是测试环境；当作"确认没有地址"，
            // 否则状态会永远停在 Unknown，开关一直等下去。
            mutableWifi.value = WifiAddress.Absent
        } else {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            // 回调固定投递到主线程：注册时机可能在无 Looper 的后台线程（开关在 Default 调度器执行）
            connectivity.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        // 这个回调只给网络句柄，地址要自己问一次
                        update(network, manager.getLinkProperties(network))
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties
                    ) {
                        // 地址已经随回调递过来了，不用再问系统一次
                        update(network, linkProperties)
                    }

                    override fun onLost(network: Network) {
                        // 只划掉这一条网络，剩下的还活着就还是"有地址"
                        val state = synchronized(lock) {
                            wifiAddresses.remove(network)
                            currentWifiLocked()
                        }
                        mutableWifi.value = state
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }
    }

    /**
     * 等一个确定的答案再返回，null 表示没有可用地址。
     *
     * 没有 Wi-Fi 时系统根本不会回调，状态会一直停在 [Unknown]，所以这里给一个很短的超时：
     * 有 Wi-Fi 的设备回调通常在几十毫秒内就到，真等超时说明确实没连 Wi-Fi。
     */
    suspend fun awaitAddress(): String? {
        val state = withTimeoutOrNull(PROBE_TIMEOUT_MILLIS.milliseconds) {
            ipv4.first { it !is WifiAddress.Unknown }
        }
        val wifi = (state as? WifiAddress.Present)?.value
        return wifi ?: currentHotspot()
    }

    /** 立即读一次：优先连着的 Wi-Fi（STA），其次热点。 */
    fun currentAddress(): String? =
        (ipv4.value as? WifiAddress.Present)?.value ?: currentHotspot()

    /**
     * 共享期间观察地址变化：每 [WATCH_INTERVAL_MILLIS] 读一次，只在值变化时发。
     *
     * 为什么必须轮询：Wi-Fi 有系统回调，但热点没有（`SoftApCallback` 是 SystemApi），
     * 热点开关、热点地址变化只能自己看。
     *
     * 连续 [MISSING_TICKS_BEFORE_LOST] 次读不到地址才发 null：切网时地址会短暂全无，
     * 一次就报会让调用方（分享方）误关共享。
     *
     * 起点取当前值，所以"共享刚建立、地址没变"不会立刻发一帧。
     */
    fun watch(): Flow<String?> = flow {
        var last = currentAddress()
        var missingTicks = 0
        while (true) {
            delay(WATCH_INTERVAL_MILLIS.milliseconds)
            val current = currentAddress()
            if (current == null) {
                missingTicks++
                if (missingTicks < MISSING_TICKS_BEFORE_LOST) continue
            } else {
                missingTicks = 0
            }
            if (current == last) continue
            last = current
            emit(current)
        }
    }

    /** 地址与状态一起更新，订阅方不会读到旧地址。 */
    private fun update(network: Network, properties: LinkProperties?) {
        val value = properties?.let(::firstIpv4)
        val state = synchronized(lock) {
            if (value == null) wifiAddresses.remove(network) else wifiAddresses[network] = value
            currentWifiLocked()
        }
        mutableWifi.value = state
    }

    /** 由记账表推出现在报给外部的状态；调用方必须持有 [lock]。 */
    private fun currentWifiLocked(): WifiAddress {
        val value = wifiAddresses.values.firstOrNull() ?: return WifiAddress.Absent
        return WifiAddress.Present(value)
    }

    /** 只要第一个非回环 IPv4：IPv6 链路本地地址拼不进对端用的 `http://ip:port`。 */
    private fun firstIpv4(linkProperties: LinkProperties): String? = linkProperties.linkAddresses
        .map { it.address }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { !it.isLoopbackAddress }
        ?.hostAddress

    /**
     * 扫网卡找热点地址。
     *
     * 为什么只能按名字认：`TetheringManager` 与 `SoftApCallback` 都是 `@SystemApi`，
     * `SoftApConfiguration` 的公开 getter 也不含 IP，热点地址没有任何公开 API 能问。
     * 认不出来返回 null（等同于"没有热点"），不猜。
     */
    private fun currentHotspot(): String? =
        runCatching { probeHotspot() }
            .onFailure {
                // 枚举网卡是系统边界：失败按"没有热点地址"处理，后果与"热点没开"相同
                Log.w(TAG, "failed to enumerate interfaces: ${it.message}")
            }
            .getOrNull()

    private fun probeHotspot(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nic in interfaces) {
            if (!nic.isUp) continue
            if (!isHotspotInterface(nic.name)) continue
            val address = nic.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
            if (address != null) return address.hostAddress
        }
        return null
    }

    /**
     * 热点网卡名。`ap0` 是 AOSP 与多数厂商的默认名，`swlan`/`softap` 是少数实现；
     * 刻意不认 `wlan0`/`wlan1`——那是 STA，混淆了两边都会错。
     */
    private fun isHotspotInterface(name: String): Boolean =
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        /** 等系统回调的宽限；超过这个时间就当"确实没有 Wi-Fi"。 */
        const val PROBE_TIMEOUT_MILLIS = 300L

        /**
         * 地址轮询间隔。热点没有系统回调，只能自己看；3 秒的代价是 getifaddrs 一次系统调用，
         * 而收益是"关热点后最多 3 秒内反映到状态"。
         */
        const val WATCH_INTERVAL_MILLIS = 3_000L

        /** 连续几轮探不到地址才算真的没了：切网时地址会短暂全无。 */
        const val MISSING_TICKS_BEFORE_LOST = 2

        /** logcat 过滤器标签：adb logcat -s LocalAddress */
        const val TAG = "LocalAddress"
    }
}
