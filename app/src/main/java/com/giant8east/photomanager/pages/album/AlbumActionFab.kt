/** 相册多选操作 FAB，复用通用 ExpandableActionFab，交互对齐 EhViewer 下载页的批量操作入口。 */
package com.giant8east.photomanager.pages.album

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.pages.common.DialogHost
import com.giant8east.photomanager.pages.common.ExpandableActionFab
import com.giant8east.photomanager.pages.common.ExpandableFabAction
import com.giant8east.photomanager.pages.common.rememberDialogStack
import com.giant8east.photomanager.ui.theme.Teal

@Composable
internal fun AlbumActionFab(
    selection: AlbumSelectionState,
    dragState: AlbumDragState,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    val albumIds = viewModel.albums.map { it.id }.toSet()
    val hasSelection = selection.selectedIds.isNotEmpty()
    val isExpanded = selection.isSelectionMode
    val selectedAlbums = viewModel.albums.filter { it.id in selection.selectedIds }
    // 方案 A：选中项只要有未隐藏就全部隐藏；仅当全部都已隐藏时切为取消隐藏
    val allSelectedHidden = selectedAlbums.isNotEmpty() && selectedAlbums.all { it.isHidden }
    val hasPendingOrder = dragState.hasPendingOrder

    val dialogs = rememberDialogStack()
    // 退出多选前先确认未保存的拖拽顺序，避免用户以为已经生效。
    val requestExit: () -> Unit = {
        if (dragState.hasPendingOrder) {
            dialogs.show {
                AlbumSaveOrderDialog(dragState = dragState, selection = selection)
            }
        } else {
            selection.clear()
        }
    }

    BackHandler(enabled = isExpanded) { requestExit() }

    ExpandableActionFab(
        isExpanded = isExpanded,
        onMainClick = {
            if (isExpanded) requestExit() else selection.enter()
        },
        actions = listOf(
            ExpandableFabAction(
                iconRes = R.drawable.ic_eh_check_all,
                onClick = { selection.selectAll(albumIds) }
            ),
            ExpandableFabAction(
                iconRes = R.drawable.ic_eh_delete,
                onClick = {
                    if (hasSelection) {
                        dialogs.show { AlbumDeleteDialog(selection = selection) }
                    }
                }
            ),
            ExpandableFabAction(
                iconRes = R.drawable.ic_eh_folder_move,
                onClick = {
                    if (hasSelection) {
                        dialogs.show { AlbumMoveDialog(selection = selection) }
                    }
                }
            ),
            ExpandableFabAction(
                imageVector = if (allSelectedHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                onClick = {
                    if (hasSelection) {
                        viewModel.setHidden(selection.selectedIds, hidden = !allSelectedHidden)
                    }
                }
            ),
            ExpandableFabAction(
                imageVector = Icons.Filled.Save,
                containerColor = if (hasPendingOrder) Teal else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (hasPendingOrder) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                onClick = {
                    if (hasPendingOrder) {
                        dragState.pendingOrder()?.let(viewModel::reorderAlbums)
                    }
                }
            )
        )
    )
    DialogHost(dialogs)
}
