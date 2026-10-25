/** 图片数据库访问对象，提供相册完整图片索引查询和同步写入。 */
package com.example.photomanager.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE albumId = :albumId ORDER BY name ASC")
    fun observePhotosQuery(albumId: Long): Flow<List<PhotoEntity>>

    @Query("DELETE FROM photos WHERE albumId = :albumId")
    suspend fun deletePhotosByAlbumId(albumId: Long)

    @Insert
    suspend fun insertPhotos(photos: List<PhotoEntity>)
}
