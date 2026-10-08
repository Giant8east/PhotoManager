/** 相册数据库实体，保存目录相册的展示信息、路径和所属分组。 */
package com.example.photomanager.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "albums",
    foreignKeys = [
        ForeignKey(
            entity = GroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["groupId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("groupId"), Index(value = ["albumPath"], unique = true)]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 真实目录创建时间，使用 UTC ISO 8601 字符串；无法读取时为空。 */
    val createdAt: String? = null,
    val photoCount: Int = 0,
    val coverPath: String? = null,
    val albumPath: String,
    /** 相册所属分组；为空表示尚未分组。 */
    val groupId: Long? = null
)
