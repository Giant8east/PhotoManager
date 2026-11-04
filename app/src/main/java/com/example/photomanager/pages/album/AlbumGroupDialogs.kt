/** 相册页的分组编辑、分组删除、分组选择和相册删除确认弹窗。 */
package com.example.photomanager.pages.album

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.photomanager.R
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.data.database.GroupEntity
import com.example.photomanager.ui.theme.appColors

@Composable
internal fun GroupEditDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        showError = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.album_group_name_hint)) }
                )
                if (showError) {
                    Text(
                        stringResource(R.string.album_group_name_required),
                        modifier = Modifier.padding(top = 8.dp),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank()) showError = true
                    else onConfirm(name.trim())
                }
            ) {
                Text(stringResource(R.string.album_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.album_cancel))
            }
        }
    )
}

@Composable
internal fun GroupDeleteDialog(
    group: GroupEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_group_delete)) },
        text = {
            Text(
                stringResource(R.string.album_group_delete_message, group.name)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.album_action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.album_cancel))
            }
        }
    )
}

@Composable
internal fun GroupPickerDialog(
    groups: List<GroupEntity>,
    selectedGroupId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_action_move_group)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    TextButton(
                        onClick = { onSelect(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (selectedGroupId == null) {
                                "✓ ${stringResource(R.string.album_group_ungrouped)}"
                            } else {
                                stringResource(R.string.album_group_ungrouped)
                            }
                        )
                    }
                }
                items(groups, key = { it.id }) { group ->
                    TextButton(
                        onClick = { onSelect(group.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (group.id == selectedGroupId) "✓ ${group.name}" else group.name
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.album_cancel))
            }
        }
    )
}

@Composable
internal fun AlbumDeleteConfirmDialog(
    album: AlbumEntity,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(4.dp),
        containerColor = appColors.dialogBackground,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.album_delete_title)) },
        text = {
            Text(stringResource(R.string.album_delete_message, album.name))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.album_action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.album_cancel))
            }
        }
    )
}
