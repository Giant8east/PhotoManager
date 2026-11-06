/** 相册数据库访问对象，提供主页相册查询和自定义排序更新。 */
package com.example.photomanager.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @Transaction
    @Query("SELECT * FROM albums WHERE id = :albumId")
    fun observeAlbumDetailQuery(albumId: Long): Flow<AlbumDetailRecord?>

    @RawQuery(observedEntities = [AlbumEntity::class])
    fun observeAlbumQuery(query: SupportSQLiteQuery): Flow<List<AlbumEntity>>

    @Query("SELECT COUNT(*) FROM albums WHERE photoCount > 0")
    fun observeValidAlbumCount(): Flow<Int>

    @Query("SELECT id FROM albums")
    fun observeAlbumIds(): Flow<List<Long>>

    @Query("SELECT * FROM albums WHERE id IN (:ids)")
    suspend fun queryAlbumsByIds(ids: List<Long>): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE albumPath = :path LIMIT 1")
    suspend fun queryAlbumByPath(path: String): AlbumEntity?

    @Query("SELECT albumPath FROM albums WHERE albumPath = :path OR substr(albumPath, 1, length(:childPrefix)) = :childPrefix")
    suspend fun queryAlbumPathsUnderPath(path: String, childPrefix: String): List<String>

    @Insert
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteAlbum(id: Long)

    @Query("DELETE FROM albums WHERE albumPath = :path")
    suspend fun deleteAlbumByPath(path: String)

    @Query("DELETE FROM albums WHERE albumPath = :path OR substr(albumPath, 1, length(:childPrefix)) = :childPrefix")
    suspend fun deleteAlbumsUnderPath(path: String, childPrefix: String)

    @Update
    suspend fun updateAlbums(albums: List<AlbumEntity>)

    @Query("UPDATE albums SET lastViewedPhotoIndex = :photoIndex WHERE id = :albumId")
    suspend fun updateAlbumReadingProgress(albumId: Long, photoIndex: Int)
}
