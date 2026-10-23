/** 组合 Pager 与边缘反馈，统一主动翻页和手势越界的连接。 */
package com.example.photomanager.pages.photoviewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.ReadingDirection

import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalFoundationApi::class)
internal class PhotoViewerPager(
    private val pagerState: PagerState,
    private val readingDirection: ReadingDirection,
    private val onEndReached: () -> Unit
) {
    private val edgeState = PhotoViewerEdgeState(readingDirection == ReadingDirection.TOP_TO_BOTTOM)

    val currentPage get() = pagerState.currentPage
    val settledPage get() = pagerState.settledPage
    val pageCount get() = pagerState.pageCount

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Content(settings: PhotoViewerSettings, pageContent: @Composable (Int) -> Unit) {
        val imageInsets = if (settings.fullScreen) WindowInsets(0, 0, 0, 0)
        else WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        val modifier = bindGestures(
            Modifier.fillMaxSize().windowInsetsPadding(imageInsets).clipToBounds()
        )
        val pageSpacing = if (settings.showPageGap) 12.dp else 0.dp
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                if (readingDirection == ReadingDirection.TOP_TO_BOTTOM) {
                    VerticalPager( // 垂直浏览布局
                        state = pagerState,
                        beyondBoundsPageCount = 1,
                        pageSpacing = pageSpacing,
                        modifier = modifier
                    ) { index -> pageContent(index) }
                } else {
                    HorizontalPager(    // 水平浏览布局
                        state = pagerState,
                        beyondBoundsPageCount = 1,
                        reverseLayout = readingDirection == ReadingDirection.RIGHT_TO_LEFT,
                        pageSpacing = pageSpacing,
                        modifier = modifier
                    ) { index -> pageContent(index) }
                }
            }
            PhotoViewerBoundaryFeedback(settings, edgeState)
        }
    }

    /** 主动越界时反馈但不移动页码；播放状态由页面通过回调处理。 */
    suspend fun goToPage(index: Int) {
        if (index !in 0 until pagerState.pageCount) {
            val isEnd = index >= pagerState.pageCount
            edgeState.showBoundary(isEnd)
            if (isEnd) onEndReached()
            return
        }
        pagerState.scrollToPage(index)
    }

    suspend fun nextPage() = goToPage(pagerState.currentPage + 1)

    suspend fun previousPage() = goToPage(pagerState.currentPage - 1)

    /** 未消费的拖动交给边缘状态；触点观察不消费 Pager 的手势事件。 */
    private fun bindGestures(modifier: Modifier): Modifier = modifier
        .nestedScroll(edgeState)
        .pointerInput(edgeState) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                edgeState.touchPosition = down.position
                edgeState.begin()
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.let { edgeState.touchPosition = it.position }
                    } while (event.changes.any { it.pressed })
                } finally {
                    edgeState.release()
                }
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
    val currentOnEndReached by rememberUpdatedState(onEndReached)
    return remember(pagerState, readingDirection) {
        PhotoViewerPager(pagerState, readingDirection) { currentOnEndReached() }
    }
}
