/** 远端相册详情页：直接复用本地详情页正文，只把封面与图片换成对方的在线地址。 */
package com.giant8east.photomanager.pages.lan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.PhotoEntity
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

    PageContent(viewModel.state, onRetry = viewModel::retry) { content ->
        // 远端相册映射成本地实体：contentUri/coverPath 直接是对方的媒体地址，Coil 与阅读器都能照常加载。
        // token 由 OkHttp 拦截器按 host:port 注入，所以这里就是干净的 URL。
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
                // 远端相册没有本地目录：刷新与收藏都不可用，但布局与本地保持一致。
                onRefreshDirectory = { onFinished -> onFinished(true) },
                onToggleFavorite = {},
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
}
