/** Wi-Fi 地址与网络变化的来源：注册 Wi-Fi 专用回调，用 StateFlow 暴露当前的 IPv4。 */
package com.giant8east.photomanager.lan.platform

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 当前 Wi-Fi IPv4 的三种状态。
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
 * 只做转发：系统说 Wi-Fi 上线、地址变化或断开，就更新 [ipv4]；
 * 要不要开共享、要不要因此关共享，由 `LanShareController` 按业务判断，这里不写业务条件。
 *
 * 用 Wi-Fi 专用的 [NetworkRequest] 而不是默认网络回调：开着 VPN 时默认网络是 tun0，
 * 此时连上 Wi-Fi 不会触发默认网络回调，而我们只关心 Wi-Fi 网卡上的地址。
 * 同一个回调也负责取地址，因此不再需要枚举全部网络（`getAllNetworks` 自 API 31 已弃用）。
 */
@Singleton
class AndroidNetworkStatus @Inject constructor(
    @ApplicationContext context: Context
) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)

    private val mutableIpv4 = MutableStateFlow<WifiAddress>(WifiAddress.Unknown)

    /** 当前 Wi-Fi IPv4 的状态流；共享开关与地址变化都订阅它。 */
    val ipv4: StateFlow<WifiAddress> = mutableIpv4.asStateFlow()

    init {
        val connectivity = manager
        if (connectivity == null) {
            // 系统常驻服务在真实设备上取不到只可能是测试环境；当作"确认没有地址"，
            // 否则状态会永远停在 Unknown，开关一直等下去。
            mutableIpv4.value = WifiAddress.Absent
        } else {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            // 回调固定投递到主线程：注册时机可能在无 Looper 的后台线程（开关在 Default 调度器执行）
            connectivity.registerNetworkCallback(
                request,
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = update(network)

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties
                    ) = update(network)

                    override fun onLost(network: Network) {
                        mutableIpv4.value = WifiAddress.Absent
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
     * 有 Wi-Fi 的设备回调通常在几十毫秒内就到，真等超时说明确实没有连 Wi-Fi。
     */
    suspend fun awaitAddress(): String? {
        val state = withTimeoutOrNull(PROBE_TIMEOUT_MILLIS.milliseconds) { ipv4.first { it !is WifiAddress.Unknown } }
        return if (state is WifiAddress.Present) state.value else null
    }

    /** 重新读一次这条网络的链路地址：地址与状态一起更新，订阅方不会读到旧地址。 */
    private fun update(network: Network) {
        val properties = manager?.getLinkProperties(network)
        mutableIpv4.value = if (properties != null) ipv4Of(properties) else WifiAddress.Absent
    }

    /** 只要第一个非回环 IPv4：IPv6 链路本地地址拼不进对端用的 `http://ip:port`。 */
    private fun ipv4Of(linkProperties: LinkProperties): WifiAddress {
        val value = linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
        return if (value != null) WifiAddress.Present(value) else WifiAddress.Absent
    }

    // companion object：Kotlin 放"类级常量/静态成员"的地方，等价于 Java 的 static final
    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 300L
    }
}
