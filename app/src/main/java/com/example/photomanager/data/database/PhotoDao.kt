/** 图片数据库访问对象，提供按相册查询、分页和同步写入。 */
package com.example.photomanager.data.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoDao {
    @Query("SELECT * FROM photos WHERE albumId = :albumId ORDER BY name ASC")
    fun observePhotos(albumId: Long): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE albumId = :albumId ORDER BY name ASC")
    fun queryPhotoPage(albumId: Long): PagingSource<Int, PhotoEntity>

    @Query("DELETE FROM photos WHERE albumId = :albumId")
    suspend fun deletePhotosByAlbumId(albumId: Long)

    @Insert
    suspend fun insertPhotos(photos: List<PhotoEntity>)
}
