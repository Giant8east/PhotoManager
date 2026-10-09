/** 相册数据库访问对象，提供主页相册查询和自定义排序更新。 */
package com.example.photomanager.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Update
import androidx.room.Insert

import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @RawQuery(observedEntities = [AlbumEntity::class])
    fun observeAlbumQuery(query: SupportSQLiteQuery): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums")
    suspend fun queryAllAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE albumPath = :path LIMIT 1")
    suspend fun queryAlbumByPath(path: String): AlbumEntity?

    @Insert
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun deleteAlbum(id: Long)

    @Update
    suspend fun updateAlbums(albums: List<AlbumEntity>)
}
