/** 局域网白名单实体：记录被主机勾选"记住"的访客设备。 */
package com.example.photomanager.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 白名单按 `deviceId` 唯一；只保存展示需要的名称与时间戳。
 *
 * 不保存 token 或 secret：token 只存在服务端内存，关闭共享即失效；
 * 白名单的语义只是"下次免确认"，不是凭证仓库。
 */
@Entity(tableName = "allowed_devices")
data class AllowedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    /** 首次批准时间。 */
    val pairedAt: Long,
    /** 最近一次配对/访问时间，用于列表排序。 */
    val lastSeenAt: Long,
    /** 是否自动放行；当前所有记录都为 true，保留字段便于以后区分"仅本次"。 */
    val autoApprove: Boolean = true
)
