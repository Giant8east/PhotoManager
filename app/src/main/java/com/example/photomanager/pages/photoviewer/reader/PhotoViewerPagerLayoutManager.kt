/** 排列阅读页面，分配拖动距离，管理落页、缩放和主动越界拉动。 */
package com.example.photomanager.pages.photoviewer.reader

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.ReadingDirection
import com.example.photomanager.pages.photoviewer.edge.PhotoViewerEdge
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
    private val minimumFlingVelocity: Float = 50f,
    private val maximumFlingVelocity: Float = 8000f,
    private val onEndReached: () -> Unit
) : PhotoViewerLayoutManager<Offset>(animationScope) {
    private var viewportSize = IntSize.Zero
    private var isPulling = false
    private var edgeAnimation: Job? = null

    private var gestureJob: Job? = null
    private var zoomAnimation: Job? = null
    private var flingJob: Job? = null

    override fun onImageDetached(index: Int) {
        if (index == pagerState.currentPage) flingJob?.cancel()
    }

    private val scrollDirection get() = if (readingDirection == ReadingDirection.RIGHT_TO_LEFT) 1f else -1f

    private val isPageAligned get() = abs(pagerState.currentPageOffsetFraction) < 0.0001f

    /** 同一段距离按边缘收回、图片平移、页面滚动的顺序消费，不重复分发。 */
    private fun dragImageAndPage(scrollScope: ScrollScope, delta: Offset) {
        var available = Offset(
            retractAxis(delta.x, PhotoViewerEdge.LEFT, PhotoViewerEdge.RIGHT),
            retractAxis(delta.y, PhotoViewerEdge.TOP, PhotoViewerEdge.BOTTOM)
        )
        val image = images[pagerState.currentPage]
        if (isPageAligned) {
            available -= image?.pan(available) ?: Offset.Zero
        }
        val requested = available.x * scrollDirection
        val consumed = scrollScope.scrollBy(requested)
        val remaining = requested - consumed
        if (isReaderBoundary(
                remaining,
                pagerState.canScrollForward,
                pagerState.canScrollBackward
            )
        ) {
            val physicalRemaining = remaining * scrollDirection
            pullEdges(Offset(physicalRemaining, 0f))
        }
        // 非阅读轴只反馈确实能平移的图片边界，不让适配后留白区域产生虚假越界。
        if (isPageAligned && image?.canPanVertically == true) {
            pullEdges(Offset(0f, available.y))
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

    override fun onDoubleTap(focus: Offset) {
        flingJob?.cancel()
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

    /** 正向手指位移拉出左/上边缘，负向拉出右/下边缘，两个轴可同时生效。 */
    private fun pullEdges(delta: Offset) {
        if (delta.x > 0.01f) edges.getValue(PhotoViewerEdge.LEFT).pull(delta.x)
        if (delta.x < -0.01f) edges.getValue(PhotoViewerEdge.RIGHT).pull(-delta.x)
        if (delta.y > 0.01f) edges.getValue(PhotoViewerEdge.TOP).pull(delta.y)
        if (delta.y < -0.01f) edges.getValue(PhotoViewerEdge.BOTTOM).pull(-delta.y)
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
        val depth = viewportSize.width
        val direction = if (isTrailingEdge) PhotoViewerEdge.RIGHT else PhotoViewerEdge.LEFT
        val edge = edges.getValue(direction)
        val targetDistance = depth / 7f
        edge.moveTo(Offset(viewportSize.width / 2f, viewportSize.height / 2f))
        edge.begin()
        edgeAnimation = animationScope.launch {
            animate(0f, targetDistance, animationSpec = tween(300)) { value, _ ->
                edge.pull(value - edge.distance)
            }
            edge.release()
        }
    }

    override val currentPage get() = pagerState.currentPage
    override val settledPage get() = pagerState.settledPage
    override val pageCount get() = pagerState.pageCount

    /** 组合中的相邻页未必可见，只有页面矩形与视口相交才算进入。 */
    override fun isPageVisible(index: Int): Boolean {
        val layout = pagerState.layoutInfo
        return layout.visiblePagesInfo.any {
            it.index == index && it.offset < layout.viewportEndOffset &&
                it.offset + layout.pageSize > layout.viewportStartOffset
        }
    }

    @Composable
    override fun Content(settings: PhotoViewerSettings, pageContent: @Composable (Int) -> Unit) {
        val pageSpacing = if (settings.showPageGap) 12.dp else 0.dp
        HorizontalPager(
            state = pagerState, beyondBoundsPageCount = 1,
            reverseLayout = readingDirection == ReadingDirection.RIGHT_TO_LEFT,
            pageSpacing = pageSpacing, userScrollEnabled = false,
            modifier = Modifier.fillMaxSize()
        ) { pageContent(it) }
    }

    override fun resizeViewport(size: IntSize) {
        if (viewportSize != size) flingJob?.cancel()
        viewportSize = size
    }

    /** 主动越界时反馈但不移动页码；播放状态由页面通过回调处理。 */
    override suspend fun goToPage(index: Int) {
        if (index == pagerState.currentPage) return
        cancelMotion()
        if (index !in 0 until pagerState.pageCount) {
            val isEnd = index >= pagerState.pageCount
            pullBoundary(isEnd)
            if (isEnd) onEndReached()
            return
        }
        pagerState.scrollToPage(index)
    }

    /** 跳页接管旧手势和动画；停止边缘拉动后只释放效果，不清空其回缩状态。 */
    private fun cancelMotion() {
        closeInputs()
        gestureJob?.cancel()
        flingJob?.cancel()
        zoomAnimation?.cancel()
        edgeAnimation?.cancel()
        releaseVelocity = Velocity.Zero
        isPulling = false
        edges.values.forEach { it.release() }
    }

    override suspend fun nextPage() = goToPage(pagerState.currentPage + 1)

    override suspend fun previousPage() = goToPage(pagerState.currentPage - 1)

    /** 整段手势共用一个滚动会话，避免逐帧抢占 Pager 导致抖动。 */
    override fun onDown(position: Offset, touchSlop: Float) {
        flingJob?.cancel()
        releaseVelocity = Velocity.Zero
        gestureJob?.cancel()
        zoomAnimation?.cancel()
        beginDrag()
        onPosition(position)
        val deltas = Channel<Offset>(Channel.UNLIMITED)
        inputs = deltas
        val startPage = pagerState.currentPage
        gestureJob = animationScope.launch(start = CoroutineStart.UNDISPATCHED) {
            pagerState.scroll(MutatePriority.UserInput) {
                for (delta in deltas) dragImageAndPage(this, delta)
                releaseEdge()
                val canFling = pagerState.currentPage == startPage && isPageAligned
                settlePage(this, startPage, touchSlop)
                if (canFling) startImageFling(releaseVelocity)
            }
        }
    }

    override fun onDrag(delta: Offset) { inputs?.trySend(delta) }

    override fun onScale(focus: Offset, zoom: Float, pan: Offset) {
        flingJob?.cancel()
        images[pagerState.currentPage]?.let { it.zoomTo(it.scale * zoom, focus, pan) }
    }

    override fun dispose() {
        flingJob?.cancel()
        inputs?.close()
        gestureJob?.cancel()
        zoomAnimation?.cancel()
        edgeAnimation?.cancel()
        edges.values.forEach { it.dispose() }
        images.clear()
    }

    /** 图片惯性不分发给 Pager；一轴撞边后停止，另一轴继续减速。 */
    private fun startImageFling(velocity: Velocity) {
        val image = images[pagerState.currentPage] ?: return
        flingJob = animationScope.launch {
            coroutineScope {
                if (image.canPanHorizontally) launch { flingAxis(image, velocity.x, false) }
                if (image.canPanVertically) launch { flingAxis(image, velocity.y, true) }
            }
        }
    }

    private suspend fun flingAxis(image: PhotoTransform, speed: Float, isVerticalAxis: Boolean) {
        if (abs(speed) < minimumFlingVelocity) return
        val initialVelocity = speed.coerceIn(-maximumFlingVelocity, maximumFlingVelocity)
        var previous = 0f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateDecay(
            exponentialDecay()
        ) {
            val delta = value - previous
            previous = value
            val consumed = image.pan(if (isVerticalAxis) Offset(0f, delta) else Offset(delta, 0f))
            val actual = if (isVerticalAxis) consumed.y else consumed.x
            if (abs(delta - actual) > 0.01f) {
                // 参考 PageFling：阅读轴有相邻页时不播放撞边反馈，也不惯性翻页。
                val isReadingAxis = !isVerticalAxis
                val logicalDirection = initialVelocity * scrollDirection
                if (!isReadingAxis || isReaderBoundary(
                        logicalDirection, pagerState.canScrollForward, pagerState.canScrollBackward
                    )) {
                    val edge = if (isVerticalAxis) {
                        if (initialVelocity > 0) PhotoViewerEdge.TOP else PhotoViewerEdge.BOTTOM
                    } else {
                        if (initialVelocity > 0) PhotoViewerEdge.LEFT else PhotoViewerEdge.RIGHT
                    }
                    val depth = if (isVerticalAxis) viewportSize.height else viewportSize.width
                    edges.getValue(edge).absorb(abs(initialVelocity), depth.toFloat())
                }
                cancelAnimation()
            }
        }
    }
}
