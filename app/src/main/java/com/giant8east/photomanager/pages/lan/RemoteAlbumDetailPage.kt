/** 远端相册详情页：直接复用本地详情页正文，只把封面与图片换成对方的在线地址。 */
package com.giant8east.photomanager.pages.lan

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.lan.LanAlbumDto
import com.giant8east.photomanager.lan.LanDevice
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.albumdetail.AlbumDetailContent
import com.giant8east.photomanager.pages.albumdetail.AlbumDetailUiState
import com.giant8east.photomanager.pages.photoviewer.PhotoViewerPage
import com.giant8east.photomanager.ui.state.PageContent

@Composable
fun RemoteAlbumDetailPage(
    navigator: AppNavigator,
    device: LanDevice,
    album: LanAlbumDto,
    viewModel: RemoteAlbumDetailViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }
    LaunchedEffect(device, album.id) { viewModel.load(device, album.id) }

    PageContent(viewModel.state, onRetry = viewModel::retry) { content ->
        val token = viewModel.token
        // 远端相册映射成本地实体：contentUri/coverPath 直接是对方的媒体地址，Coil 与阅读器都能照常加载。
        val remoteAlbum = remember(album, token, device.address) {
            AlbumEntity(
                id = album.id,
                name = album.name,
                modifiedAt = album.modifiedAt,
                photoCount = album.photoCount,
                totalSizeBytes = album.sizeBytes,
                coverPath = album.coverUrl?.let { "http://${device.address}$it?token=$token" }.orEmpty(),
                albumPath = ""
            )
        }
        val remotePhotos = remember(content.photos, token, device.address) {
            content.photos.map { photo ->
                PhotoEntity(
                    id = photo.id,
                    albumId = album.id,
                    name = photo.name,
                    contentUri = "http://${device.address}${photo.mediaUrl}?token=$token",
                    sizeBytes = photo.sizeBytes
                )
            }
        }
        AlbumDetailContent(
            navigator = navigator,
            content = AlbumDetailUiState(album = remoteAlbum, photos = remotePhotos),
            // 远端相册没有本地目录：刷新与收藏都不可用，但布局与本地保持一致。
            onRefreshDirectory = { onFinished -> onFinished(true) },
            onToggleFavorite = {},
            onOpenViewer = { index ->
                navigator.pushTo(useSafeArea = false) {
                    PhotoViewerPage(navigator, remotePhotos, index)
                }
            },
            actionsEnabled = false
        )
    }
}
