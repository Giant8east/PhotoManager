/** 主机侧"记住的设备"的展示类型：只带界面需要的字段，不含数据库实体。 */
package com.giant8east.photomanager.lan.server

/**
 * 白名单条目在协议层的轻量形态。
 *
 * 单独的类是因为 `lan` 不能依赖 `data.database` 的实体：谁把实体挪走、改字段，
 * 都不该顺带改到这个只用于展示的类型。
 */
data class LanTrustedDevice(
    val deviceId: String,
    val name: String,
    /** 最近一次配对或访问的时间戳（毫秒），列表按它倒序。 */
    val lastSeenAt: Long
)
