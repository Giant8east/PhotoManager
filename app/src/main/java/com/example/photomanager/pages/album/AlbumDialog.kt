/** 相册多选的批量删除与移动分组弹窗。 */
package com.example.photomanager.pages.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.R
import com.example.photomanager.pages.common.AppDialog
import com.example.photomanager.pages.common.AppLazyColumnScrollbar
import com.example.photomanager.ui.theme.appColors

@Composable
internal fun AlbumDeleteDialog(
    selection: AlbumSelectionState,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    AppDialog(
        title = stringResource(R.string.album_delete_title),
        buttons = { close ->
            TextButton(
                onClick = {
                    viewModel.delete(selection.selectedIds)
                    selection.clear()
                    close()
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = appColors.actionText
                )
            ) { Text(stringResource(R.string.album_action_delete)) }
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
}

@Composable
internal fun AlbumMoveDialog(
    selection: AlbumSelectionState,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    AppDialog(
        title = stringResource(R.string.album_action_move)
    ) { close ->
        val listState = rememberLazyListState()
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
                items(viewModel.groups, key = { it.id }) { group ->
                    MoveRow(
                        label = group.name,
                        onClick = {
                            viewModel.move(selection.selectedIds, group.id)
                            selection.clear()
                            close()
                        }
                    )
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
            .padding(vertical = 16.dp),
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp
        )
    )
}
