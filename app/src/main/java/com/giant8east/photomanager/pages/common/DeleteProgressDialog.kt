/** 删除长任务的共享进度弹窗，展示当前相册、已删除文件数和失败项。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.DeleteResult
import com.giant8east.photomanager.data.DeleteState

@Composable
internal fun DeleteProgressDialog(
    state: DeleteState,
    onRetry: () -> Unit,
    onClose: () -> Unit
) {
    AppDialog(
        title = stringResource(R.string.album_delete_title),
        buttons = if (!state.isRunning && state.result != null) {
            { _ ->
                if (state.result is DeleteResult.Partial ||
                    state.result is DeleteResult.Failure
                ) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.page_retry))
                    }
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.album_cancel))
                }
            }
        } else {
            null
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.currentAlbumName?.let { name ->
                Text(
                    stringResource(R.string.delete_progress_current, name),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    stringResource(
                        R.string.delete_progress_files,
                        state.deletedFiles
                    )
                )
            }
            if (state.failedFiles > 0) {
                Text(
                    stringResource(
                        R.string.delete_progress_failed,
                        state.failedFiles
                    ),
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.failureMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
            }
            if (!state.isRunning && state.result is DeleteResult.Partial) {
                Text(stringResource(R.string.delete_partial_message))
            }
        }
    }
}
