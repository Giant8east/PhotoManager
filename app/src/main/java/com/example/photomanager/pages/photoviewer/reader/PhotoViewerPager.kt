/** 阅读交互入口，组装固定视口、手势识别、页面布局与边缘绘制。 */
package com.example.photomanager.pages.photoviewer.reader

import com.example.photomanager.pages.photoviewer.edge.PhotoViewerEdgeView

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.ReadingDirection
import kotlinx.coroutines.CoroutineScope

@OptIn(ExperimentalFoundationApi::class)
internal class PhotoViewerPager(
    pagerState: PagerState,
    readingDirection: ReadingDirection,
    animationScope: CoroutineScope,
    onEndReached: () -> Unit
) {
    private val layout = PhotoViewerPagerLayoutManager(pagerState, readingDirection, animationScope, onEndReached)
    private val isVertical = readingDirection == ReadingDirection.TOP_TO_BOTTOM
    private val gestures = PhotoViewerGestureRecognizer(
        layout::onDown, { layout.edge.touchPosition = it }, layout::onDrag,
        layout::onScale, layout::onUp, layout::onDoubleTap
    )
    val currentPage get() = layout.currentPage
    val settledPage get() = layout.settledPage
    val pageCount get() = layout.pageCount
    fun attachImage(index: Int, transform: PhotoTransform) = layout.attachImage(index, transform)
    fun detachImage(index: Int, transform: PhotoTransform) = layout.detachImage(index, transform)
    suspend fun goToPage(index: Int) = layout.goToPage(index)
    suspend fun nextPage() = layout.nextPage()
    suspend fun previousPage() = layout.previousPage()
    fun dispose() = layout.dispose()

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Content(
        settings: PhotoViewerSettings,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
        pageContent: @Composable (Int) -> Unit
    ) {
        val imageInsets = if (settings.fullScreen) WindowInsets(0, 0, 0, 0)
        else WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        val currentOnTap by rememberUpdatedState(onTap)
        val currentOnLongPress by rememberUpdatedState(onLongPress)
        val tap = remember { { currentOnTap() } }
        val longPress = remember { { currentOnLongPress() } }
        val viewport = with(gestures) {
            Modifier.fillMaxSize().windowInsetsPadding(imageInsets).clipToBounds()
                .onSizeChanged(layout::resizeViewport).recognize(tap, longPress)
        }
        Box(viewport) {
            layout.Content(settings, pageContent)
            PhotoViewerEdgeView(Modifier.fillMaxSize(), isVertical, layout.edge)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberPhotoViewerPager(
    initialIndex: Int,
    pageCount: Int,
    readingDirection: ReadingDirection,
    onEndReached: () -> Unit
): PhotoViewerPager {
    val pagerState = rememberPagerState(initialPage = initialIndex, pageCount = { pageCount })
    val animationScope = rememberCoroutineScope()
    val currentOnEndReached by rememberUpdatedState(onEndReached)
    val viewerPager = remember(pagerState, readingDirection) {
        PhotoViewerPager(pagerState, readingDirection, animationScope) { currentOnEndReached() }
    }
    DisposableEffect(viewerPager) {
        onDispose { viewerPager.dispose() }
    }
    return viewerPager
}
