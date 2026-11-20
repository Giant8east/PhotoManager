/** 一台对端设备：主机广播自己、访客接收别人，两边用的是同一个类型。 */
package com.giant8east.photomanager.lan.protocol

import kotlinx.serialization.Serializable

/**
 * 对端设备的全部已知信息，正好等于 mDNS TXT 里带的那几个字段。
 *
 * 广播（主机）和发现（访客）只是同一个概念的出入口：
 * 主机用 [ip] 与 [port] 填 NsdServiceInfo，访客从 NsdServiceInfo 解析出这两个值。
 */
@Serializable
data class LanDevice(
    val name: String,
    val ip: String,
    val port: Int,
    val deviceId: String? = null,
    val version: String = ""
) {
    /** 可直接拼进 URL 的 `ip:port`；派生属性，不参与 equals/hashCode。 */
    val address: String get() = "$ip:$port"
}
