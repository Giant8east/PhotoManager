/** 局域网共享的对外状态：设置页开关与通知都消费它。 */
package com.giant8east.photomanager.lan.host

/**
 * [isRunning] 表示服务端已监听；[address] 是给其他设备展示的 `http://ip:port`。
 * 开启时没有可用地址就不会进入运行状态（见 [LanShareResult.NoWifiAddress]）；
 * 运行期间 Wi-Fi 断开导致地址消失时 [address] 会变回 null。
 */
data class LanShareState(
    val isRunning: Boolean = false,
    val address: String? = null
)

/** 开关一次操作的结果；UI 只在 [NoWifiAddress] 时需要提示用户，其余情况看 [LanShareState]。 */
sealed interface LanShareResult {
    /** 已开始监听并广播地址。 */
    data object Started : LanShareResult

    /** 已关闭，token 与待确认的配对请求都已作废。 */
    data object Stopped : LanShareResult

    /** 没有可用的 Wi-Fi IPv4 地址，共享保持关闭（没有启动引擎、也没有拉起前台服务）。 */
    data object NoWifiAddress : LanShareResult
}
