/** 相册多选的批量删除与移动分组弹窗。 */
package com.giant8east.photomanager.pages.album

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.DeleteResult
import com.giant8east.photomanager.pages.common.AppDialog
import com.giant8east.photomanager.pages.common.AppLazyColumnScrollbar
import com.giant8east.photomanager.pages.common.DeleteProgressDialog
import com.giant8east.photomanager.pages.common.LocalDialogStack
import com.giant8east.photomanager.ui.theme.appColors

@Composable
internal fun AlbumDeleteDialog(
    selection: AlbumSelectionState,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val dialogs = LocalDialogStack.current
    val deleteState by viewModel.deleteState.collectAsState()
    var started by remember { mutableStateOf(false) }

    if (!started) {
        AppDialog(
            title = stringResource(R.string.album_delete_title),
            buttons = { close ->
                TextButton(
                    onClick = {
                        started = true
                        viewModel.delete(selection.selectedIds)
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = appColors.actionText
                    )
                ) { Text(stringResource(R.string.album_action_delete)) }
                TextButton(onClick = close) {
                    Text(stringResource(R.string.album_cancel))
                }
            }
        ) {
            Text(
                stringResource(
                    R.string.album_delete_selected_message,
                    selection.selectedIds.size
                ),
                style = MaterialTheme.typography.bodyLarge.copy(
                    letterSpacing = 0.sp
                )
            )
        }
    } else {
        DeleteProgressDialog(
            state = deleteState,
            onRetry = { viewModel.delete(selection.selectedIds) },
            onClose = {
                selection.clear()
                dialogs.pop()
            }
        )
    }

    LaunchedEffect(started, deleteState.result) {
        if (!started) return@LaunchedEffect
        if (deleteState.result is DeleteResult.Success) {
            selection.clear()
            Toast.makeText(context, R.string.album_delete_success, Toast.LENGTH_SHORT).show()
            dialogs.pop()
        }
    }
}

@Composable
internal fun AlbumMoveDialog(
    selection: AlbumSelectionState,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    AppDialog(
        title = stringResource(R.string.album_action_move),
        contentPadding = PaddingValues(0.dp),
        contentMaxHeight = Dp.Unspecified
    ) { close ->
        val listState = rememberLazyListState()
        Column(Modifier.fillMaxWidth()) {
            if (listState.canScrollBackward) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            AppLazyColumnScrollbar(state = listState) {
                LazyColumn(Modifier.fillMaxWidth(), state = listState) {
                    item {
                        MoveRow(
                            label = stringResource(R.string.album_group_default_short),
                            onClick = {
                                viewModel.move(selection.selectedIds, null)
                                selection.clear()
                                close()
                            }
                        )
                    }
                    items(viewModel.groupsWithCounts, key = { it.group.id }) { groupWithCount ->
                        MoveRow(
                            label = groupWithCount.group.name,
                            onClick = {
                                viewModel.move(selection.selectedIds, groupWithCount.group.id)
                                selection.clear()
                                close()
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveRow(
    label: String,
    onClick: () -> Unit
) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp
        )
    )
}

/** 退出多选时确认是否把拖拽结果保存为默认排序。 */
@Composable
internal fun AlbumSaveOrderDialog(
    dragState: AlbumDragState,
    selection: AlbumSelectionState,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    AppDialog(
        title = stringResource(R.string.album_save_order_title),
        buttons = { close ->
            TextButton(
                onClick = {
                    // 只提交顺序，不在这里丢弃快照：数据库回流且顺序一致后会自动清空。
                    dragState.pendingOrder()?.let(viewModel::reorderAlbums)
                    selection.clear()
                    close()
                },
                colors = ButtonDefaults.textButtonColors(contentColor = appColors.actionText)
            ) { Text(stringResource(R.string.album_save_order_confirm)) }
            TextButton(
                onClick = {
                    dragState.discard()
                    selection.clear()
                    close()
                }
            ) { Text(stringResource(R.string.album_save_order_discard)) }
        }
    ) {
        Text(
            stringResource(R.string.album_save_order_message),
            style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp)
        )
    }
}
