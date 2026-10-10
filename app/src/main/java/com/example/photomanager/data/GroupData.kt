/** 分组数据入口，负责分组查询、创建、重命名和删除。 */
package com.example.photomanager.data

import com.example.photomanager.data.database.GroupEntity
import com.example.photomanager.data.database.PhotoDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import javax.inject.Inject

class GroupData @Inject constructor(
    private val database: PhotoDatabase
) {
    /** 持续观察分组列表。 */
    val groups: Flow<List<GroupEntity>> = database.groupDao().observeGroupQuery()

    /** 创建一个分组记录。 */
    suspend fun createGroup(name: String) {
        database.groupDao().insertGroup(
            GroupEntity(name = name, createdAt = Instant.now().toString())
        )
    }

    /** 修改分组名称。 */
    suspend fun renameGroup(group: GroupEntity, name: String) {
        database.groupDao().updateGroup(group.copy(name = name))
    }

    /** 删除分组，并将相册移出该分组。 */
    suspend fun deleteGroup(groupId: Long) {
        database.groupDao().deleteGroup(groupId)
    }
}
