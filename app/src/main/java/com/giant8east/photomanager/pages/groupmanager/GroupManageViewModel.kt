/** 分组管理页状态与动作：通过 PageState 暴露分组列表，写操作串行排队避免互相取消。 */
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
import com.giant8east.photomanager.data.database.GroupEntity
import com.giant8east.photomanager.data.group.GroupData
import com.giant8east.photomanager.ui.state.PageState
import com.giant8east.photomanager.ui.state.asPageStates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class GroupManageViewModel @Inject constructor(
    private val groupData: GroupData,
    @ApplicationContext private val context: Context
) : ViewModel() {
    var state by mutableStateOf<PageState<List<GroupEntity>>>(PageState.Loading)
        private set

    private var observeJob: Job? = null
    // 写操作（增/改/删/拖拽排序）用 Mutex 串行排队，不取消上一次已发出的数据库事务
    private val mutationMutex = Mutex()

    init {
        observeGroups()
    }

    fun retry() = observeGroups()

    private fun observeGroups() {
        observeJob?.cancel()
        state = PageState.Loading
        observeJob = viewModelScope.launch {
            groupData.groups
                .asPageStates(R.string.page_albums_load_failed) { PageState.Content(it) }
                .collect { state = it }
        }
    }

    fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        performMutation { groupData.createGroup(trimmed) }
    }

    fun isNameAvailable(name: String, excludeGroupId: Long?): Boolean {
        val trimmed = name.trim()
        val currentGroups = (state as? PageState.Content)?.value.orEmpty()
        return currentGroups.none { group ->
            group.id != excludeGroupId && group.name == trimmed
        }
    }

    fun renameGroup(group: GroupEntity, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        performMutation { groupData.renameGroup(group, trimmed) }
    }

    fun deleteGroup(group: GroupEntity) = performMutation {
        groupData.deleteGroup(group.id)
    }

    fun moveGroup(fromIndex: Int, toIndex: Int, onFailed: () -> Unit = {}) = performMutation(
        onFailed = onFailed
    ) {
        groupData.moveGroup(fromIndex, toIndex)
    }

    private fun performMutation(
        onFailed: () -> Unit = {},
        action: suspend () -> Unit
    ) {
        viewModelScope.launch {
            mutationMutex.withLock {
                try {
                    action()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Group operation failed", error)
                    showError(R.string.album_operation_failed)
                    onFailed()
                }
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
