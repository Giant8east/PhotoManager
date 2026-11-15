/** 相册主页的页面级多选状态，只服务于当前页面的交互，不进入 ViewModel。 */
package com.giant8east.photomanager.pages.album

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

@Stable
internal class AlbumSelectionState {
    var isSelectionMode by mutableStateOf(false)
        private set

    var selectedIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** 进入多选模式；传入相册 ID 时同时选中长按的相册。 */
    fun enter(albumId: Long? = null) {
        isSelectionMode = true
        if (albumId != null) selectedIds = selectedIds + albumId
    }

    /** 切换指定相册的选中状态；非多选模式下忽略。 */
    fun toggle(albumId: Long) {
        selectedIds = if (albumId in selectedIds) {
            selectedIds - albumId
        } else {
            selectedIds + albumId
        }
    }

    /** 在全部选中和全部取消之间切换；取消全部后仍保持多选模式。 */
    fun selectAll(availableIds: Set<Long>) {
        if (!isSelectionMode) return
        selectedIds = if (isAllSelected(availableIds)) emptySet() else availableIds
    }

    /** 只保留当前列表中仍存在的选中项；选中项为空时退出多选模式。 */
    fun retain(availableIds: Set<Long>) {
        selectedIds = selectedIds intersect availableIds
        if (selectedIds.isEmpty()) isSelectionMode = false
    }

    /** 清空选中项并退出多选模式。 */
    fun clear() {
        selectedIds = emptySet()
        isSelectionMode = false
    }

    /** 当前列表非空且所有相册都已被选中时返回 true。 */
    fun isAllSelected(availableIds: Set<Long>): Boolean =
        availableIds.isNotEmpty() && availableIds.all { it in selectedIds }
}
