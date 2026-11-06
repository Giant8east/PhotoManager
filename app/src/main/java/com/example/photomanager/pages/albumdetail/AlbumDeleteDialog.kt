/** 相册详情删除确认弹窗，封装删除文案、提交中和失败提示。 */
package com.example.photomanager.pages.albumdetail

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.R
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.common.AsyncConfirmDialog

@Composable
internal fun AlbumDeleteDialog(
    album: AlbumEntity,
    navigator: AppNavigator,
    viewModel: AlbumDetailViewModel = hiltViewModel<
        AlbumDetailViewModel,
        AlbumDetailViewModel.Factory
        >(
        creationCallback = {
            it.create(album.id, album.albumPath)
        }
    )
) {
    val context = LocalContext.current
    AsyncConfirmDialog(
        title = stringResource(R.string.album_detail_delete_title),
        message = stringResource(R.string.album_detail_delete_message, album.name),
        confirmLabel = stringResource(R.string.album_detail_delete),
        loadingMessage = stringResource(R.string.album_detail_deleting),
        onConfirm = { viewModel.delete(album) },
        onSuccess = {
            Toast.makeText(
                context,
                R.string.album_delete_success,
                Toast.LENGTH_SHORT
            ).show()
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
