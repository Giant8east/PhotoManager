/** 分组数据库实体，保存相册分组的基本信息。 */
package com.example.photomanager.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 分组创建时间，使用 UTC ISO 8601 字符串；无法读取时为空。 */
    val createdAt: String? = null,
    val name: String
)
