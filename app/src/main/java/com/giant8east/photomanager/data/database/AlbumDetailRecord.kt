/** 相册及其完整图片的事务快照，供详情页一次读取，避免两条独立 Flow 的瞬时不一致。 */
package com.giant8east.photomanager.data.database

import androidx.room.Embedded
import androidx.room.Relation

data class AlbumDetailRecord(
    @Embedded val album: AlbumEntity,
    @Relation(parentColumn = "id", entityColumn = "albumId")
    val photos: List<PhotoEntity>
)
