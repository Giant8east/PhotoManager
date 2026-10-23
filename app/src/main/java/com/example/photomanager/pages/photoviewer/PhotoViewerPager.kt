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

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

private val EdgeDecelerate = Easing { fraction -> 1f - (1f - fraction) * (1f - fraction) }

@OptIn(ExperimentalFoundationApi::class)
internal class PhotoViewerPager(
    private val pagerState: PagerState,
    private val readingDirection: ReadingDirection,
    private val animationScope: CoroutineScope,
    private val onEndReached: () -> Unit
) {
    private val isVertical = readingDirection == ReadingDirection.TOP_TO_BOTTOM
    private var viewportSize = IntSize.Zero
    private var edgeDistance by mutableFloatStateOf(0f)
    private var edgeShrink by mutableFloatStateOf(0f)
    private var touchPosition by mutableStateOf(Offset.Zero)
    private var isPulling = false
    private var edgeAnimation: Job? = null

    private val edgeConnection = object : NestedScrollConnection {
        /** 反向拖动先收回边缘，再将剩余距离交给 Pager。 */
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.Drag || edgeDistance == 0f) return Offset.Zero
            val delta = if (isVertical) available.y else available.x
            if (delta * edgeDistance >= 0f) return Offset.Zero
            val consumed = if (abs(delta) <= abs(edgeDistance)) delta else -edgeDistance
            pullEdge(consumed)
            return axisOffset(consumed)
        }

        /** 手势与主动越界共用同一套拉动状态。 */
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.Drag) return Offset.Zero
            val delta = if (isVertical) available.y else available.x
            if (delta == 0f) return Offset.Zero
            pullEdge(delta)
            return axisOffset(delta)
        }

        /** 只释放边缘，不消耗 Pager 的惯性速度。 */
        override suspend fun onPreFling(available: Velocity): Velocity {
            releaseEdge()
            return Velocity.Zero
        }
    }

    private fun axisOffset(distance: Float) =
        if (isVertical) Offset(0f, distance) else Offset(distance, 0f)

    private fun pullEdge(delta: Float) {
        edgeDistance += delta
        edgeShrink = 1f
    }

    /** 手指接管时取消模拟拉动或回缩，避免多个任务争用反馈状态。 */
    private fun beginDrag() {
        edgeAnimation?.cancel()
        edgeDistance = 0f
        edgeShrink = 1f
        isPulling = true
    }

    private fun releaseEdge() {
        if (!isPulling) return
        isPulling = false
        edgeAnimation = animationScope.launch { recedeEdge() }
    }

    private suspend fun recedeEdge() {
        animate(edgeShrink, 0f, animationSpec = tween(600, easing = EdgeDecelerate)) { value, _ ->
            edgeShrink = value
        }
        edgeDistance = 0f
    }

    /** 模拟 300ms 拉动后释放，与手势使用相同的距离和回缩机制。 */
    private fun pullBoundary(isEnd: Boolean) {
        if (isPulling) return
        edgeAnimation?.cancel()
        val isTrailingEdge = isEnd != (readingDirection == ReadingDirection.RIGHT_TO_LEFT)
        val depth = if (isVertical) viewportSize.height else viewportSize.width
        val targetDistance = depth / 7f * if (isTrailingEdge) -1f else 1f
        touchPosition = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        edgeDistance = 0f
        edgeShrink = 1f
        edgeAnimation = animationScope.launch {
            animate(0f, targetDistance, animationSpec = tween(300)) { value, _ ->
                pullEdge(value - edgeDistance)
            }
            recedeEdge()
        }
    }

    val currentPage get() = pagerState.currentPage
    val settledPage get() = pagerState.settledPage
    val pageCount get() = pagerState.pageCount

    /** 根据阅读方向选择横向或纵向 Pager，配置朝向、页面间隔、相邻页预加载、
     * 处理图片区域的安全区避让、连接手势越界状态、叠加边缘反馈绘制 */
    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Content(settings: PhotoViewerSettings, pageContent: @Composable (Int) -> Unit) {
        val imageInsets = if (settings.fullScreen) WindowInsets(0, 0, 0, 0)
        else WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
        val modifier = Modifier.fillMaxSize().windowInsetsPadding(imageInsets).clipToBounds()
            .onSizeChanged { viewportSize = it }.bindGestures()

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
            PhotoViewerBoundaryFeedback(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(imageInsets),
                isVertical = isVertical,
                distance = edgeDistance,
                shrink = edgeShrink,
                touchPosition = touchPosition
            )
        }
    }

    /** 主动越界时反馈但不移动页码；播放状态由页面通过回调处理。 */
    suspend fun goToPage(index: Int) {
        if (index !in 0 until pagerState.pageCount) {
            val isEnd = index >= pagerState.pageCount
            pullBoundary(isEnd)
            if (isEnd) onEndReached()
            return
        }
        pagerState.scrollToPage(index)
    }

    suspend fun nextPage() = goToPage(pagerState.currentPage + 1)

    suspend fun previousPage() = goToPage(pagerState.currentPage - 1)

    /** 未消费的拖动交给边缘状态；触点观察不消费 Pager 的手势事件。 */
    private fun Modifier.bindGestures(): Modifier = this // Modifier 的扩展函数
        .nestedScroll(edgeConnection)
        .pointerInput(this@PhotoViewerPager) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                touchPosition = down.position
                beginDrag()
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.firstOrNull()?.let { touchPosition = it.position }
                    } while (event.changes.any { it.pressed })
                } finally {
                    releaseEdge()
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
    val animationScope = rememberCoroutineScope()
    val currentOnEndReached by rememberUpdatedState(onEndReached)
    return remember(pagerState, readingDirection) {
        PhotoViewerPager(pagerState, readingDirection, animationScope) { currentOnEndReached() }
    }
}
