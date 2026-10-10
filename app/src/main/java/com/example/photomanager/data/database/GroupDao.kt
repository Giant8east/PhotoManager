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

    @Query("SELECT * FROM groups ORDER BY name ASC")
    fun observeGroupQuery(): Flow<List<GroupEntity>>

    @Update
    suspend fun updateGroup(group: GroupEntity)

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: Long)
}
