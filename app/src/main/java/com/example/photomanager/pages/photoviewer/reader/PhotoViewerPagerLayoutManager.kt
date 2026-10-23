/** 排列阅读页面，分配拖动距离，管理落页、缩放和主动越界拉动。 */
package com.example.photomanager.pages.photoviewer.reader

import com.example.photomanager.pages.photoviewer.edge.PhotoViewerEdgeEffect
import com.example.photomanager.pages.photoviewer.edge.PhotoViewerEdge

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.ReadingDirection
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** 只有对应方向确实不能继续滚动时，剩余距离才属于相册越界。 */
internal fun isReaderBoundary(
    remaining: Float,
    canScrollForward: Boolean,
    canScrollBackward: Boolean
): Boolean =
    (remaining > 0.01f && !canScrollForward) || (remaining < -0.01f && !canScrollBackward)

/** 使用连续页位置判定方向，跨过半页导致 currentPage 改变时也不会反向弹回。 */
internal fun readerTargetPage(
    startPage: Int,
    position: Float,
    threshold: Float,
    pageCount: Int
): Int {
    val distance = position - startPage
    val target = when {
        distance > threshold -> startPage + 1
        distance < -threshold -> startPage - 1
        else -> startPage
    }
    return target.coerceIn(0, pageCount - 1)
}

@OptIn(ExperimentalFoundationApi::class)
internal class PhotoViewerPagerLayoutManager(
    private val pagerState: PagerState,
    private val readingDirection: ReadingDirection,
    private val animationScope: CoroutineScope,
    private val onEndReached: () -> Unit
) {
    private val isVertical = readingDirection == ReadingDirection.TOP_TO_BOTTOM
    private var viewportSize = IntSize.Zero
    val edges = PhotoViewerEdge.entries.associateWith { PhotoViewerEdgeEffect(animationScope) }
    private var isPulling = false
    private var edgeAnimation: Job? = null

    private val images = mutableMapOf<Int, PhotoTransform>()
    private var gestureJob: Job? = null
    private var zoomAnimation: Job? = null

    fun attachImage(index: Int, transform: PhotoTransform) {
        images[index] = transform
    }

    fun detachImage(index: Int, transform: PhotoTransform) {
        if (images[index] === transform) images.remove(index)
    }

    /** 落页完成后旧页已离屏，直接复原，避免依赖布局可见性通知的时序。 */
    private fun resetInactiveImages() {
        images.forEach { (index, image) ->
            if (index != pagerState.currentPage) image.reset()
        }
    }

    private fun axisDelta(delta: Offset) = if (isVertical) delta.y else delta.x
    private val scrollDirection get() = if (readingDirection == ReadingDirection.RIGHT_TO_LEFT) 1f else -1f

    /** 同一段距离按边缘收回、图片平移、页面滚动的顺序消费，不重复分发。 */
    private fun dragImageAndPage(scrollScope: ScrollScope, delta: Offset) {
        var available = Offset(
            retractAxis(delta.x, PhotoViewerEdge.LEFT, PhotoViewerEdge.RIGHT),
            retractAxis(delta.y, PhotoViewerEdge.TOP, PhotoViewerEdge.BOTTOM)
        )
        val image = images[pagerState.currentPage]
        val isPageAligned = abs(pagerState.currentPageOffsetFraction) < 0.0001f
        if (isPageAligned) {
            available -= image?.pan(available) ?: Offset.Zero
        }
        val requested = axisDelta(available) * scrollDirection
        val consumed = scrollScope.scrollBy(requested)
        val remaining = requested - consumed
        if (isReaderBoundary(
                remaining,
                pagerState.canScrollForward,
                pagerState.canScrollBackward
            )
        ) {
            val physicalRemaining = remaining * scrollDirection
            pullEdges(if (isVertical) Offset(0f, physicalRemaining) else Offset(physicalRemaining, 0f))
        }
        // 非阅读轴只反馈确实能平移的图片边界，不让适配后留白区域产生虚假越界。
        if (isPageAligned && abs(pagerState.currentPageOffsetFraction) < 0.0001f) {
            if (isVertical && image?.canPanHorizontally == true) pullEdges(Offset(available.x, 0f))
            if (!isVertical && image?.canPanVertically == true) pullEdges(Offset(0f, available.y))
        }
    }

    /** 自定义手势没有 Pager 原生位移记录，松手不能依赖其默认吸附判定。 */
    private suspend fun settlePage(scrollScope: ScrollScope, startPage: Int, touchSlop: Float) {
        val pageStep = pagerState.layoutInfo.pageSize + pagerState.layoutInfo.pageSpacing
        if (pageStep == 0) return
        val position = pagerState.currentPage + pagerState.currentPageOffsetFraction
        // 参考 EhViewer 以页面间隔为阈值；无间隔时仍保留一个触摸阈值，避免轻触误翻页。
        val threshold = maxOf(touchSlop, pagerState.layoutInfo.pageSpacing.toFloat()) / pageStep
        val target = readerTargetPage(startPage, position, threshold, pageCount)
        val distance = (target - position) * pageStep
        if (abs(distance) < 0.01f) return
        var previous = 0f
        animate(0f, distance, animationSpec = tween(220)) { value, _ ->
            scrollScope.scrollBy(value - previous)
            previous = value
        }
    }

    fun onDoubleTap(focus: Offset) {
        val image = images[pagerState.currentPage] ?: return
        zoomAnimation?.cancel()
        val startScale = image.scale
        val targetScale = image.nextDoubleTapScale()
        zoomAnimation = animationScope.launch {
            animate(startScale, targetScale, animationSpec = tween(300, easing = LinearOutSlowInEasing)) { scale, _ ->
                image.zoomTo(scale, focus)
            }
        }
    }

    private fun retractAxis(delta: Float, leading: PhotoViewerEdge, trailing: PhotoViewerEdge): Float =
        when {
            delta < 0f -> delta + edges.getValue(leading).retract(-delta)
            delta > 0f -> delta - edges.getValue(trailing).retract(delta)
            else -> delta
        }

    /** 正向手指位移拉出左/上边缘，负向拉出右/下边缘，两个轴可同时生效。 */
    private fun pullEdges(delta: Offset) {
        if (delta.x > 0.01f) edges.getValue(PhotoViewerEdge.LEFT).pull(delta.x)
        if (delta.x < -0.01f) edges.getValue(PhotoViewerEdge.RIGHT).pull(-delta.x)
        if (delta.y > 0.01f) edges.getValue(PhotoViewerEdge.TOP).pull(delta.y)
        if (delta.y < -0.01f) edges.getValue(PhotoViewerEdge.BOTTOM).pull(-delta.y)
    }

    fun onPosition(position: Offset) {
        edges.values.forEach { it.touchPosition = position }
    }

    /** 手指接管时取消模拟拉动或回缩，避免多个任务争用反馈状态。 */
    private fun beginDrag() {
        edgeAnimation?.cancel()
        edges.values.forEach { it.begin() }
        isPulling = true
    }

    private fun releaseEdge() {
        if (!isPulling) return
        isPulling = false
        edges.values.forEach { it.release() }
    }

    /** 模拟 300ms 拉动后释放，与手势使用相同的距离和回缩机制。 */
    private fun pullBoundary(isEnd: Boolean) {
        if (isPulling) return
        edgeAnimation?.cancel()
        val isTrailingEdge = isEnd != (readingDirection == ReadingDirection.RIGHT_TO_LEFT)
        val depth = if (isVertical) viewportSize.height else viewportSize.width
        val direction = if (isVertical) {
            if (isTrailingEdge) PhotoViewerEdge.BOTTOM else PhotoViewerEdge.TOP
        } else {
            if (isTrailingEdge) PhotoViewerEdge.RIGHT else PhotoViewerEdge.LEFT
        }
        val edge = edges.getValue(direction)
        val targetDistance = depth / 7f
        edge.touchPosition = Offset(viewportSize.width / 2f, viewportSize.height / 2f)
        edge.begin()
        edgeAnimation = animationScope.launch {
            animate(0f, targetDistance, animationSpec = tween(300)) { value, _ ->
                edge.pull(value - edge.distance)
            }
            edge.release()
        }
    }

    val currentPage get() = pagerState.currentPage
    val settledPage get() = pagerState.settledPage
    val pageCount get() = pagerState.pageCount

    /** 组合中的相邻页未必可见，只有页面矩形与视口相交才算进入。 */
    fun isPageVisible(index: Int): Boolean {
        val layout = pagerState.layoutInfo
        return layout.visiblePagesInfo.any {
            it.index == index && it.offset < layout.viewportEndOffset &&
                it.offset + layout.pageSize > layout.viewportStartOffset
        }
    }

    @Composable
    fun Content(settings: PhotoViewerSettings, pageContent: @Composable (Int) -> Unit) {
        val pageSpacing = if (settings.showPageGap) 12.dp else 0.dp
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            if (isVertical) {
                VerticalPager(
                    state = pagerState, beyondBoundsPageCount = 1,
                    pageSpacing = pageSpacing, userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize()
                ) { pageContent(it) }
            } else {
                HorizontalPager(
                    state = pagerState, beyondBoundsPageCount = 1,
                    reverseLayout = readingDirection == ReadingDirection.RIGHT_TO_LEFT,
                    pageSpacing = pageSpacing, userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize()
                ) { pageContent(it) }
            }
        }
    }

    fun resizeViewport(size: IntSize) { viewportSize = size }

    /** 主动越界时反馈但不移动页码；播放状态由页面通过回调处理。 */
    suspend fun goToPage(index: Int) {
        if (index !in 0 until pagerState.pageCount) {
            val isEnd = index >= pagerState.pageCount
            pullBoundary(isEnd)
            if (isEnd) onEndReached()
            return
        }
        zoomAnimation?.cancel()
        pagerState.scrollToPage(index)
        resetInactiveImages()
    }

    suspend fun nextPage() = goToPage(pagerState.currentPage + 1)

    suspend fun previousPage() = goToPage(pagerState.currentPage - 1)

    private var dragDeltas: Channel<Offset>? = null

    /** 整段手势共用一个滚动会话，避免逐帧抢占 Pager 导致抖动。 */
    fun onDown(position: Offset, touchSlop: Float) {
        gestureJob?.cancel()
        zoomAnimation?.cancel()
        beginDrag()
        onPosition(position)
        val deltas = Channel<Offset>(Channel.UNLIMITED)
        dragDeltas = deltas
        val startPage = pagerState.currentPage
        gestureJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pagerState.scroll(MutatePriority.UserInput) {
                for (delta in deltas) dragImageAndPage(this, delta)
                releaseEdge()
                settlePage(this, startPage, touchSlop)
            }
            resetInactiveImages()
        }
    }

    fun onDrag(delta: Offset) { dragDeltas?.trySend(delta) }

    fun onScale(focus: Offset, zoom: Float, pan: Offset) {
        images[pagerState.currentPage]?.let { it.zoomTo(it.scale * zoom, focus, pan) }
    }

    fun onUp() {
        dragDeltas?.close()
        dragDeltas = null
    }

    fun dispose() {
        dragDeltas?.close()
        gestureJob?.cancel()
        zoomAnimation?.cancel()
        edgeAnimation?.cancel()
        edges.values.forEach { it.dispose() }
    }
}
