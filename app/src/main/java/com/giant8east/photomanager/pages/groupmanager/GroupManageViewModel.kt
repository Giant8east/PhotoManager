/** 分组管理页状态与动作：观察分组并处理新增、重命名、删除和拖动排序。 */
package com.giant8east.photomanager.pages.groupmanager

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.group.GroupData
import com.giant8east.photomanager.data.database.GroupEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GroupManageViewModel @Inject constructor(
    private val groupData: GroupData,
    @ApplicationContext private val context: Context
) : ViewModel() {
    var groups by mutableStateOf<List<GroupEntity>>(emptyList())
        private set

    private var operationJob: Job? = null

    init {
        viewModelScope.launch {
            groupData.groups
                .catch { error ->
                    if (error is CancellationException) throw error
                    showError(R.string.page_albums_load_failed)
                }
                .collect { groups = it }
        }
    }

    fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        perform { groupData.createGroup(trimmed) }
    }

    fun isNameAvailable(name: String, excludeGroupId: Long?): Boolean {
        val trimmed = name.trim()
        return groups.none { group ->
            group.id != excludeGroupId && group.name == trimmed
        }
    }

    fun renameGroup(group: GroupEntity, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        perform { groupData.renameGroup(group, trimmed) }
    }

    fun deleteGroup(group: GroupEntity) = perform {
        groupData.deleteGroup(group.id)
    }

    fun moveGroup(fromIndex: Int, toIndex: Int) = perform {
        groupData.moveGroup(fromIndex, toIndex)
    }

    private fun perform(action: suspend () -> Unit) {
        operationJob?.cancel()
        operationJob = viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Group operation failed", error)
                showError(R.string.album_operation_failed)
            }
        }
    }

    private fun showError(@StringRes messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "GroupManageViewModel"
    }
}
