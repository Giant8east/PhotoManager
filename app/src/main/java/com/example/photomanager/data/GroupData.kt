/** 分组数据入口，负责分组查询、创建、重命名、排序和删除。 */
package com.example.photomanager.data

import androidx.room.withTransaction
import com.example.photomanager.data.database.GroupEntity
import com.example.photomanager.data.database.PhotoDatabase
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GroupData @Inject constructor(
    private val database: PhotoDatabase
) {
    /** 持续观察分组列表。 */
    val groups: Flow<List<GroupEntity>> = database.groupDao().observeGroupQuery()

    /** 创建一个分组记录，追加到当前顺序末尾。 */
    suspend fun createGroup(name: String) {
        database.withTransaction {
            val nextOrder = database.groupDao().queryMaxSortOrder() + 1
            database.groupDao().insertGroup(
                GroupEntity(
                    name = name,
                    sortOrder = nextOrder
                )
            )
        }
    }

    /** 修改分组名称。 */
    suspend fun renameGroup(group: GroupEntity, name: String) {
        database.groupDao().updateGroup(group.copy(name = name))
    }

    /** 按列表位置移动分组，并重排 sortOrder。 */
    suspend fun moveGroup(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        database.withTransaction {
            val groups = database.groupDao().queryGroups()
            if (fromIndex !in groups.indices || toIndex !in groups.indices) return@withTransaction

            val reordered = groups.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
            database.groupDao().updateGroups(
                reordered.mapIndexed { index, group ->
                    group.copy(sortOrder = index.toLong())
                }
            )
        }
    }

    /** 删除分组，并将相册移出该分组。 */
    suspend fun deleteGroup(groupId: Long) {
        database.groupDao().deleteGroup(groupId)
    }
}
