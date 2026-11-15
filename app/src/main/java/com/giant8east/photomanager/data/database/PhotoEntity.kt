/** 图片数据库实体，保存图片与所属相册的关联及媒体基本信息。 */
package com.giant8east.photomanager.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.giant8east.photomanager.data.media.PhotoFormat

@Entity(
    tableName = "photos",
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("albumId"), Index(value = ["contentUri"], unique = true)]
)
data class PhotoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val albumId: Long,
    val name: String,
    val contentUri: String,
    val modifiedAt: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val sizeBytes: Long = 0
) {
    /** 由文件名派生的格式，不持久化。 */
    val format: PhotoFormat get() = PhotoFormat.fromFileName(name)
}
