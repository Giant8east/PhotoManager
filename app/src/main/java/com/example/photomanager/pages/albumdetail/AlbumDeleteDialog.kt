/** 相册详情删除确认弹窗，封装删除文案、提交中和失败提示。 */
package com.example.photomanager.pages.albumdetail

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.photomanager.R
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.common.AsyncConfirmDialog
import com.example.photomanager.pages.common.DialogStack

@Composable
internal fun AlbumDeleteDialog(
    album: AlbumEntity,
    viewModel: AlbumDetailViewModel,
    navigator: AppNavigator,
    dialogs: DialogStack
) {
    val context = LocalContext.current
    AsyncConfirmDialog(
        title = stringResource(R.string.album_detail_delete_title),
        message = stringResource(R.string.album_detail_delete_message, album.name),
        confirmLabel = stringResource(R.string.album_detail_delete),
        cancelLabel = stringResource(R.string.album_detail_cancel),
        loadingMessage = stringResource(R.string.album_detail_deleting),
        onDismiss = { dialogs.pop() },
        onConfirm = { viewModel.delete(album) },
        onSuccess = {
            Toast.makeText(
                context,
                R.string.album_delete_success,
                Toast.LENGTH_SHORT
            ).show()
            dialogs.pop()
            navigator.pop()
        },
        onError = {
            Toast.makeText(
                context,
                context.getString(R.string.album_detail_delete_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    )
}
