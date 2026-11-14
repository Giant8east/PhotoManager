/** 分组数据库访问对象，提供主页侧边栏的分组列表。 */
package com.example.photomanager.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert
    suspend fun insertGroup(group: GroupEntity): Long

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, name ASC")
    fun observeGroupQuery(): Flow<List<GroupEntity>>

    @Query(
        """
        SELECT g.*, COUNT(a.id) AS albumCount
        FROM `groups` AS g
        LEFT JOIN albums AS a ON a.groupId = g.id
        GROUP BY g.id
        ORDER BY g.sortOrder ASC, g.name ASC
        """
    )
    fun observeGroupCountQuery(): Flow<List<GroupWithAlbumCount>>

    @Query("SELECT COUNT(*) FROM albums WHERE groupId IS NULL")
    fun observeUngroupedAlbumCount(): Flow<Int>

    /** 共享视图的一次性分组统计 */
    @Query(
        """
        SELECT g.*, COUNT(a.id) AS albumCount
        FROM `groups` AS g
        LEFT JOIN albums AS a ON a.groupId = g.id
        GROUP BY g.id
        ORDER BY g.sortOrder ASC, g.name ASC
        """
    )
    suspend fun queryGroupWithAlbumCounts(): List<GroupWithAlbumCount>

    @Query("SELECT COUNT(*) FROM albums WHERE groupId IS NULL")
    suspend fun queryUngroupedAlbumCount(): Int

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, name ASC")
    suspend fun queryGroups(): List<GroupEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM groups")
    suspend fun queryMaxSortOrder(): Long

    @Update
    suspend fun updateGroup(group: GroupEntity)

    @Update
    suspend fun updateGroups(groups: List<GroupEntity>)

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)
}
