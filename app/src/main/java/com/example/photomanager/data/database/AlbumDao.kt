/** 相册数据库访问对象，提供主页相册查询和自定义排序更新。 */
package com.example.photomanager.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.room.Update
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {
    @RawQuery(observedEntities = [AlbumEntity::class])
    fun observeAlbums(query: SupportSQLiteQuery): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums WHERE albumPath = :path LIMIT 1")
    suspend fun findByPath(path: String): AlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(album: AlbumEntity): Long

    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun delete(id: Long)

    @Update
    suspend fun updateAlbums(albums: List<AlbumEntity>)
}
