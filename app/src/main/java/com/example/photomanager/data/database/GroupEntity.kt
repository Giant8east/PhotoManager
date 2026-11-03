/** 分组数据库实体，保存相册分组的基本信息。 */
package com.example.photomanager.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 分组在侧边栏中的显示顺序，数值越小越靠前。 */
    val sortOrder: Long = 0
)
