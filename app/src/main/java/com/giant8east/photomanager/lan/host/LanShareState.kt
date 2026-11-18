/** 局域网共享的对外状态：设置页开关与通知都消费它。 */
package com.giant8east.photomanager.lan.host

/**
 * [isRunning] 表示服务端已监听；[address] 是给其他设备展示的 `http://ip:port`。
 * 启动失败或还在绑定端口时 [address] 为 null。
 */
data class LanShareState(
    val isRunning: Boolean = false,
    val address: String? = null
)
