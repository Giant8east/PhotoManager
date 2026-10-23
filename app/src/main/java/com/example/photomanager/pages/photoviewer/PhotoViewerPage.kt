/** 图片浏览页面，组装阅读内容、悬浮信息与系统能力，并集中处理主动翻页。 */
package com.example.photomanager.pages.photoviewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.data.database.PhotoEntity
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.ui.theme.PhotoViewerBackground
import kotlinx.coroutines.launch

@Composable
fun PhotoViewerPage(
    navigator: AppNavigator,
    albumId: Long,
    initialIndex: Int,
    viewModel: PhotoViewerViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }
    LaunchedEffect(albumId) { viewModel.load(albumId) }
    val settings by viewModel.settings.collectAsState()
    // 根容器始终占满窗口并绘制阅读底色；配置未到达前不按默认值猜测窗口状态。
    Box(
        Modifier
            .fillMaxSize()
            .background(PhotoViewerBackground),
        contentAlignment = Alignment.Center
    ) {
        settings?.let { loadedSettings ->
            val photos = viewModel.photos
            if (photos == null) {
                CircularProgressIndicator()
            } else {
                PhotoViewerReader(viewModel, photos, initialIndex, loadedSettings)
            }
        }
    }
}

@Composable
private fun PhotoViewerReader(
    viewModel: PhotoViewerViewModel,
    photos: List<PhotoEntity>,
    initialIndex: Int,
    settings: PhotoViewerSettings
) {
    val pagerScope = rememberCoroutineScope()
    var isMenuVisible by remember { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    val viewerPager = rememberPhotoViewerPager(initialIndex, photos.size, settings.readingDirection) {
        isPlaying = false
    }

    val systemController = rememberPhotoViewerSystem(
        settings = settings,
        onNextPage = { pagerScope.launch { viewerPager.nextPage() } },
        onPreviousPage = { pagerScope.launch { viewerPager.previousPage() } }
    )

    LaunchedEffect(areControlsVisible, settings.fullScreen, isMenuVisible) {
        if (settings.fullScreen) {
            if (areControlsVisible || isMenuVisible) systemController.showSystemBars()
            else systemController.hideSystemBars()
        }
    }

    // 翻页落定后才记录阅读进度，拖动过程中经过的中间页不写入。
    LaunchedEffect(viewerPager, viewModel) {
        snapshotFlow { viewerPager.settledPage }.collect { index ->
            viewModel.moveTo(index)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(settings.fullScreen, systemController) {
                detectTapGestures(
                    onTap = { areControlsVisible = !areControlsVisible },
                    onLongPress = { isMenuVisible = true }
                )
            }
    ) {
        viewerPager.Content(settings) { index ->
            PhotoViewerImage(photos[index], settings.pageScale)
        }
        PhotoViewerControls(
            currentPage = viewerPager.currentPage,
            pageCount = viewerPager.pageCount,
            intervalSeconds = settings.autoPageSeconds,
            readingDirection = settings.readingDirection,
            isVisible = areControlsVisible,
            isPlaying = isPlaying,
            onPlayClick = { isPlaying = !isPlaying },
            onVisibilityChanged = { areControlsVisible = it },
            isMenuVisible = isMenuVisible,
            onNextPage = { viewerPager.nextPage() },
            onPageSelected = { index -> pagerScope.launch { viewerPager.goToPage(index) } }
        )
        PhotoViewerOverlay(
            settings = settings,
            currentPage = viewerPager.currentPage,
            pageCount = viewerPager.pageCount
        )
    }
    if (isMenuVisible) {
        PhotoViewerMenu(
            settings = settings,
            onConfirm = { viewModel.saveSettings(it); isMenuVisible = false },
            onDismiss = { isMenuVisible = false }
        )
    }
}
