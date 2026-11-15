/** 相册页的分组编辑、分组删除、分组选择和相册删除确认弹窗。 */
package com.giant8east.photomanager.pages.groupmanager

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.GroupEntity
import com.giant8east.photomanager.pages.common.AppDialog

@Composable
internal fun GroupCreateDialog(
    viewModel: GroupManageViewModel = hiltViewModel()
) {
    GroupNameDialog(
        title = stringResource(R.string.album_group_add),
        initialName = "",
        excludeGroupId = null,
        onSubmit = viewModel::createGroup,
        viewModel = viewModel
    )
}

@Composable
internal fun GroupRenameDialog(
    group: GroupEntity,
    viewModel: GroupManageViewModel = hiltViewModel()
) {
    GroupNameDialog(
        title = stringResource(R.string.album_group_rename),
        initialName = group.name,
        excludeGroupId = group.id,
        onSubmit = { name -> viewModel.renameGroup(group, name) },
        viewModel = viewModel
    )
}

@Composable
private fun GroupNameDialog(
    title: String,
    initialName: String,
    excludeGroupId: Long?,
    onSubmit: (String) -> Unit,
    viewModel: GroupManageViewModel
) {
    var name by remember { mutableStateOf(initialName) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    AppDialog(
        title = title,
        buttons = { close ->
            TextButton(
                onClick = {
                    when {
                        name.isEmpty() -> {
                            errorRes = R.string.album_group_name_required
                        }
                        !viewModel.isNameAvailable(name, excludeGroupId) -> {
                            errorRes = R.string.album_group_name_duplicate
                        }
                        else -> {
                            onSubmit(name)
                            close()
                        }
                    }
                }
            ) {
                Text(stringResource(R.string.album_confirm))
            }
        }
    ) {
        Column {
            TextField(
                value = name,
                onValueChange = {
                    name = it
                    errorRes = null
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = errorRes != null,
                label = { Text(stringResource(R.string.album_group_name_hint)) },
                trailingIcon = if (errorRes != null) {
                    {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                } else null,
                supportingText = errorRes?.let { messageRes ->
                    {
                        Text(
                            text = stringResource(messageRes),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    errorContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
                    errorIndicatorColor = MaterialTheme.colorScheme.error
                )
            )
        }
    }
}

@Composable
internal fun GroupDeleteDialog(
    group: GroupEntity,
    viewModel: GroupManageViewModel = hiltViewModel()
) {
    AppDialog(
        title = stringResource(R.string.album_group_delete),
        buttons = { close ->
            TextButton(
                onClick = {
                    viewModel.deleteGroup(group)
                    close()
                }
            ) {
                Text(stringResource(R.string.album_action_delete))
            }
        }
    ) {
        Text(
            stringResource(R.string.album_group_delete_message, group.name)
        )
    }
}
