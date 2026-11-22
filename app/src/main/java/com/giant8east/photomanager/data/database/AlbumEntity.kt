/** 相册数据库实体，保存目录相册的展示信息、路径和所属分组。 */
package com.giant8east.photomanager.data.database

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
    /** 收藏视图的自定义排序值，与 [sortOrder] 各自独立，收藏时取当前时间追加到末尾。 */
    val favoriteSortOrder: Long = 0,
    /** 相册所属分组；null 表示默认相册。 */
    val groupId: Long? = null,
    /** 收藏标记，独立于分组，不与 groupId 互斥。 */
    val isFavorite: Boolean = false,
    /** 当前相册本层目录是否存在 `.nomedia` 文件（仅控制本机系统图库隐藏）。 */
    val isHidden: Boolean = false,
    /** 相册来源设备名：空串表示本地相册，非空表示从局域网设备下载。 */
    val sourceName: String = "",
    /** 局域网下载来源设备 ID；本地相册为 null。 */
    val remoteDeviceId: String? = null,
    /** 局域网下载来源相册 ID；本地相册为 null。 */
    val remoteAlbumId: Long? = null
)
