/** 图片浏览页面，组装阅读内容、悬浮信息与系统能力，并集中处理主动翻页。 */
package com.example.photomanager.pages.photoviewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.data.database.PhotoEntity
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.ui.theme.PhotoViewerBackground
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun PhotoViewerReader(
    viewModel: PhotoViewerViewModel,
    photos: List<PhotoEntity>,
    initialIndex: Int,
    settings: PhotoViewerSettings
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { photos.size }
    )
    val pagerScope = rememberCoroutineScope()
    var isMenuVisible by remember { mutableStateOf(false) }

    // 所有主动翻页入口共用这里的边界判断；Pager 的单指滑动也由同一页数范围约束。
    suspend fun goToPage(index: Int) {
        pagerState.scrollToPage(index.coerceIn(0, pagerState.pageCount - 1))
    }

    suspend fun goToNextPage() = goToPage(pagerState.currentPage + 1)
    suspend fun goToPreviousPage() = goToPage(pagerState.currentPage - 1)

    val systemController = rememberPhotoViewerSystem(
        settings = settings,
        onNextPage = { pagerScope.launch { goToNextPage() } },
        onPreviousPage = { pagerScope.launch { goToPreviousPage() } }
    )

    // 翻页落定后才记录阅读进度，拖动过程中经过的中间页不写入。
    LaunchedEffect(pagerState, viewModel) {
        snapshotFlow { pagerState.settledPage }.collect(viewModel::moveTo)
    }
    // 阅读器创建时图片记录已经就绪，按配置和当前页重新计时。
    LaunchedEffect(
        settings.autoPageEnabled,
        settings.autoPageSeconds,
        isMenuVisible,
        pagerState.currentPage,
        pagerState.pageCount
    ) {
        if (!settings.autoPageEnabled || isMenuVisible) {
            return@LaunchedEffect
        }
        if (pagerState.currentPage >= pagerState.pageCount - 1) {
            // 自动翻页到达末页后关闭开关，并保存到阅读配置。
            viewModel.saveSettings(settings.copy(autoPageEnabled = false))
            return@LaunchedEffect
        }
        delay(settings.autoPageSeconds * 1000L)
        goToNextPage()
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(settings.fullScreen, systemController) {
                detectTapGestures(
                    onTap = { if (settings.fullScreen) systemController.showSystemBars() },
                    onLongPress = { isMenuVisible = true }
                )
            }
    ) {
        // 仅图片视口避让，页面背景和参与转场的根容器始终占满窗口。
        val imageInsets = if (settings.fullScreen) WindowInsets(0, 0, 0, 0)
        else WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        PhotoViewerContent(
            photos = photos,
            pagerState = pagerState,
            settings = settings,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(imageInsets)
                .clipToBounds()
        )
        PhotoViewerOverlay(
            settings = settings,
            batteryLevel = viewModel.batteryLevel,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(
                    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                )
                .padding(12.dp)
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
