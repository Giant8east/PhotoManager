/** 远端相册详情页：复用本地详情布局，支持 LRU 缓存、下拉刷新与下载/已下载删除。 */
package com.giant8east.photomanager.pages.lan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.lan.RemoteAlbumKey
import com.giant8east.photomanager.data.lan.RemoteDownloadStatus
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.PREVIEW_QUERY_PARAM
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.albumdetail.AlbumDetailContent
import com.giant8east.photomanager.pages.albumdetail.AlbumDetailUiState
import com.giant8east.photomanager.pages.photoviewer.PhotoViewerPage
import com.giant8east.photomanager.ui.state.PageContent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAlbumDetailPage(
    navigator: AppNavigator,
    device: LanDevice,
    album: LanAlbumDto,
    viewModel: RemoteAlbumDetailViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }
    LaunchedEffect(device, album) { viewModel.load(device, album) }

    val downloadTasks by viewModel.downloadTasks.collectAsState()
    val downloadStatus = downloadTasks[RemoteAlbumKey.of(device, album.id)]?.status
    val isDownloaded = viewModel.downloadedAlbum != null
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val downloadButtonLabel = when (downloadStatus) {
        is RemoteDownloadStatus.Queued -> stringResource(R.string.lan_download_queued)
        is RemoteDownloadStatus.Downloading -> stringResource(
            R.string.lan_download_progress_short,
            downloadStatus.finished,
            downloadStatus.total
        )
        else -> if (isDownloaded) {
            stringResource(R.string.lan_download_completed_badge)
        } else {
            stringResource(R.string.lan_download)
        }
    }

    PageContent(viewModel.state, onRetry = viewModel::retry) { content ->
        val remoteAlbum = remember(album, device.address) {
            AlbumEntity(
                id = album.id,
                name = album.name,
                modifiedAt = album.modifiedAt,
                photoCount = album.photoCount,
                totalSizeBytes = album.sizeBytes,
                coverPath = album.coverUrl?.let { "http://${device.address}$it" }.orEmpty(),
                albumPath = ""
            )
        }
        val remotePhotos = remember(content.photos, device.address) {
            content.photos.map { photo ->
                PhotoEntity(
                    id = photo.id,
                    albumId = album.id,
                    name = photo.name,
                    contentUri = "http://${device.address}${photo.mediaUrl}",
                    sizeBytes = photo.sizeBytes
                )
            }
        }
        val animatedPhotoIds = remember(content.photos) {
            content.photos
                .filter { it.format == "gif" || it.format == "animated_webp" }
                .map { it.id }
                .toSet()
        }
        PullToRefreshBox(
            isRefreshing = viewModel.isRefreshing,
            // 等价于 onRefresh = { viewModel.refresh() }
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize()
        ) {
            AlbumDetailContent(
                navigator = navigator,
                content = AlbumDetailUiState(album = remoteAlbum, photos = remotePhotos),
                sourceLabel = device.name,
                onRefreshDirectory = { onFinished -> onFinished(true) },
                onToggleFavorite = {},
                primaryActionLabel = downloadButtonLabel,
                primaryActionEnabled = true,
                onPrimaryAction = {
                    val isActive = downloadStatus is RemoteDownloadStatus.Queued ||
                        downloadStatus is RemoteDownloadStatus.Downloading
                    if (!isActive && isDownloaded) {
                        showDeleteConfirm = true
                    } else {
                        viewModel.toggleDownload()
                    }
                },
                onOpenViewer = { index ->
                    navigator.pushTo(useSafeArea = false) {
                        PhotoViewerPage(navigator, remotePhotos, index)
                    }
                },
                actionsEnabled = false,
                // 详情页网格请求降采样预览图，点进阅读器再加载完整原图（对标 EhViewer）
                thumbnailUriFor = { photo -> "${photo.contentUri}?$PREVIEW_QUERY_PARAM=true" },
                isAnimatedFor = { photo -> photo.id in animatedPhotoIds }
            )
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.lan_delete_downloaded_title)) },
            text = { Text(stringResource(R.string.lan_delete_downloaded_message, album.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteDownloaded()
                    }
                ) {
                    Text(stringResource(R.string.viewer_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.viewer_cancel))
                }
            }
        )
    }
}
