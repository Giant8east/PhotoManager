/** 本机身份：服务端广播自己、客户端配对时报自己，两边用的是同一份数据。 */
package com.giant8east.photomanager.lan.protocol

/**
 * `deviceId` 首次启动生成后持久化；白名单与配对记录都按它认人。
 * 数据从哪来（SharedPreferences、Build、BuildConfig）由 data 层负责，协议层只认这三个值。
 */
data class LanIdentity(
    val deviceId: String,
    val deviceName: String,
    val version: String
)
