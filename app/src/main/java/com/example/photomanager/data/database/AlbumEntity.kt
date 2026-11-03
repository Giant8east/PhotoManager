/** 相册数据库实体，保存目录相册的展示信息、路径和所属分组。 */
package com.example.photomanager.data.database

import androidx.room.ColumnInfo
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
            onDelete = ForeignKey.SET_DEFAULT
        )
    ],
    indices = [Index("groupId"), Index(value = ["albumPath"], unique = true)]
)
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    /** 真实目录修改时间，使用 UTC ISO 8601 字符串；无法读取时为空。 */
    val modifiedAt: String? = null,
    val photoCount: Int = 0,
    /** 当前目录直接包含图片的总字节数；由同步结果持久化。 */
    val totalSizeBytes: Long,
    /** 上次浏览到的图片下标，从 0 开始。 */
    val lastViewedPhotoIndex: Int = 0,
    /** 首张图片的内容 URI；相册至少包含一张图片。 */
    val coverPath: String,
    val albumPath: String,
    /** 自定义排序值，数值越小越靠前。 */
    val sortOrder: Long = 0,
    /** 相册所属分组；0 是默认分组。 */
    @ColumnInfo(defaultValue = "0")
    val groupId: Long = 0
)
