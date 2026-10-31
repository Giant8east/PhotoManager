/** 图片浏览页面，组装阅读内容、悬浮信息与系统能力，并集中处理主动翻页。 */
package com.example.photomanager.pages.photoviewer

import com.example.photomanager.pages.photoviewer.components.PhotoViewerControls
import com.example.photomanager.pages.photoviewer.components.PhotoViewerMenu
import com.example.photomanager.pages.photoviewer.components.PhotoViewerOverlay
import com.example.photomanager.pages.photoviewer.reader.PhotoViewerPageView
import com.example.photomanager.pages.photoviewer.reader.rememberPhotoViewerPager
import com.example.photomanager.pages.photoviewer.system.rememberPhotoViewerSystem

import com.example.photomanager.ui.state.PageContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.data.database.PhotoEntity
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.ui.theme.PhotoViewerBackground
import com.example.photomanager.ui.theme.PhotoManagerTheme
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
    // 根容器始终占满窗口并绘制阅读底色；配置未到达前不按默认值猜测窗口状态。
    PhotoManagerTheme(darkTheme = true) {
        Box(
            Modifier
                .fillMaxSize()
                .background(PhotoViewerBackground),
            contentAlignment = Alignment.Center
        ) {
            PageContent(viewModel.state, viewModel::retry) { content ->
                PhotoViewerReader(viewModel, content.photos, initialIndex, content.settings)
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
    val viewerPager = rememberPhotoViewerPager(initialIndex, photos, settings.readingDirection) {
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
    Box(Modifier.fillMaxSize()) {
        viewerPager.Content(
            settings,
            onTap = { areControlsVisible = !areControlsVisible },
            onLongPress = { isMenuVisible = true }
        ) { index ->
            PhotoViewerPageView(
                photos[index], settings.pageScale,
                viewerPager = viewerPager,
                index = index
            )
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
