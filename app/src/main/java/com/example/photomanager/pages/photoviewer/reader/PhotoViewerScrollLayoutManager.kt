/** 连续纵向阅读：按图片比例排列、整列缩放与横移，不使用逐页吸附。 */
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.database.PhotoEntity
import com.example.photomanager.pages.photoviewer.edge.PhotoViewerEdge
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.roundToInt

/** 顶边参考线归属第一张仍占据可见区域的图片，间隔不单独占用页码。 */
internal fun continuousPageIndex(
    visible: List<Pair<Int, Int>>, fallback: Int, isAtEnd: Boolean = false
): Int = if (isAtEnd) visible.lastOrNull { it.second > 0 }?.first ?: fallback
else visible.firstOrNull { it.second > 0 }?.first ?: fallback

internal fun continuousImageHeight(width: Int, aspectRatio: Float, zoom: Float): Int =
    (width * zoom / aspectRatio).roundToInt().coerceAtLeast(1)

@OptIn(ExperimentalFoundationApi::class)
internal class PhotoViewerScrollLayoutManager(
    private val listState: LazyListState,
    private val photos: () -> List<PhotoEntity>,
    private val scope: CoroutineScope,
    private val onEndReached: () -> Unit,
    private val minimumVelocity: Float,
    private val maximumVelocity: Float
) : PhotoViewerLayoutManager<PhotoViewerScrollLayoutManager.Input>(scope) {
    private data class PageGeometry(val photo: PhotoEntity, val ratio: Float)
    private data class ReadingAnchor(val index: Int, val top: Int)

    private val geometries = mutableStateMapOf<Int, PageGeometry>()
    private var viewport by mutableStateOf(IntSize.Zero)
    private var zoom by mutableFloatStateOf(1f)
    private var offsetX by mutableFloatStateOf(0f)
    private var settledIndex by mutableIntStateOf(listState.firstVisibleItemIndex)
    private var gapPixels = 0f
    private var isScrollingBackward = false
    private var gesture: Job? = null
    private var zoomAnimation: Job? = null
    private var edgeAnimation: Job? = null
    private val navigation = Mutex()
    private var remeasurement: Remeasurement? = null
    private val measurement = object : RemeasurementModifier {
        override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
            this@PhotoViewerScrollLayoutManager.remeasurement = remeasurement
        }
    }

    /** 连续阅读布局接收到的一次手势输入,给不同输入一个共同类型 */
    sealed interface Input {
        /** 拖动，只有位移 */
        data class Drag(val delta: Offset) : Input
        /** 缩放，包含双指焦点位置、本次相对缩放倍率、双指移动产生的平移量 */
        data class Scale(val focus: Offset, val factor: Float, val pan: Offset) : Input
    }

    override val pageCount get() = photos().size

    /** 按完整图片索引取记录，顺序与详情页保持一致。 */
    private fun photoAt(index: Int) = photos()[index]
    override val currentPage
        get() = continuousPageIndex(
            listState.layoutInfo.visibleItemsInfo.map { it.index to (it.offset + it.size) },
            listState.firstVisibleItemIndex,
            listState.layoutInfo.visibleItemsInfo.isNotEmpty() && !listState.canScrollForward
        )
    override val settledPage get() = settledIndex
    val currentItemOffset
        get() = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == currentPage }?.let { (-it.offset).coerceAtLeast(0) }
            ?: listState.firstVisibleItemScrollOffset
    val unscaledItemOffset get() = (currentItemOffset / zoom).roundToInt()

    override fun isPageVisible(index: Int): Boolean = listState.layoutInfo.visibleItemsInfo.any {
        it.index == index && it.offset + it.size > 0 && it.offset < viewport.height
    }

    @Composable
    override fun Content(settings: PhotoViewerSettings, pageContent: @Composable (Int) -> Unit) {
        val density = LocalDensity.current
        val gap = if (settings.showPageGap) 12.dp else 0.dp
        // 把页面间隔换算成像素，供缩放计算使用
        SideEffect { gapPixels = with(density) { gap.toPx() } }
        // 持续观察滚动何时停下来
        LaunchedEffect(this) {
            snapshotFlow { if (!listState.isScrollInProgress) currentPage else null }
                .filterNotNull().collect { index ->
                    settledIndex = index
                    images.forEach { (page, image) -> if (!isPageVisible(page)) image.reset() }
                }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 首次测量就使用真实视口高度，避免 1px 占位让列表回填并丢失初始索引。
            val placeholderHeight = constraints.maxHeight.coerceAtLeast(1)
            LazyColumn(
                state = listState, userScrollEnabled = false,
                modifier = Modifier.fillMaxSize().then(measurement),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                items(pageCount, key = { photoAt(it).id }) { index ->
                    Box(
                        Modifier.layout { measurable, constraints ->
                            val width = constraints.maxWidth
                            val scaledWidth = (width * zoom).roundToInt().coerceAtLeast(1)
                            // 在测量时读取尺寸，成功回报后可立即重测，不等待下一轮重组。
                            val height = pageHeight(index, IntSize(width, placeholderHeight))
                            val child =
                                measurable.measure(Constraints.fixed(scaledWidth, height))
                            layout(width, height) {
                                child.place(
                                    ((width - scaledWidth) / 2f + offsetX).roundToInt(),
                                    0
                                )
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        pageContent(index)
                    }
                }
            }
        }
    
    }

    /** 只接收当前记录的有效解码尺寸，不再独立读取文件头。 */
    override fun onImageSizeChanged(index: Int, photo: PhotoEntity, size: Size) {
        if (photoAt(index) != photo || !size.width.isFinite() || !size.height.isFinite() ||
            size.width <= 0f || size.height <= 0f) return
        val geometry = PageGeometry(photo, size.width / size.height)
        if (geometries[index] == geometry) return
        val anchor = captureAnchor()
        geometries[index] = geometry
        anchor?.let(::restoreAnchor)
    }

    /** 向前面的图片滚动时跳过不稳定的占位项，其余情况保持首个可见项。 */
    private fun captureAnchor(): ReadingAnchor? {
        val visible = listState.layoutInfo.visibleItemsInfo.filter {
            it.offset + it.size > 0 && it.offset < listState.layoutInfo.viewportEndOffset
        }
        val anchor = if (isScrollingBackward) {
            visible.firstOrNull { ratioFor(it.index) != null } ?: visible.firstOrNull()
        } else visible.firstOrNull()
        return anchor?.let { ReadingAnchor(it.index, it.offset) }
    }

    /** 同步恢复锚点的屏幕位置，不启动会抢占手势或惯性的滚动任务。 */
    private fun restoreAnchor(anchor: ReadingAnchor) {
        remeasurement?.forceRemeasure()
        val top = pageTop(anchor.index) ?: return
        listState.dispatchRawDelta((top - anchor.top).toFloat())
    }

    /** 优先使用真实布局坐标；锚点被挤出可见区时才按统一高度反算。 */
    private fun pageTop(index: Int): Int? {
        val layout = listState.layoutInfo
        layout.visibleItemsInfo.firstOrNull { it.index == index }?.let { return it.offset }
        val first = layout.visibleItemsInfo.firstOrNull() ?: return null
        val range = minOf(first.index, index) until maxOf(first.index, index)
        val distance = range.sumOf { pageHeight(it, layout.viewportSize) + gapPixels.roundToInt() }
        return first.offset + if (index >= first.index) distance else -distance
    }

    /** 列表测量和锚点反算共用高度，未知尺寸维持一屏占位。 */
    private fun pageHeight(index: Int, viewport: IntSize): Int =
        ratioFor(index)?.let { continuousImageHeight(viewport.width, it, zoom) }
            ?: viewport.height.coerceAtLeast(1)

    /** 未解码成功的项维持视口高度占位，失败与重试均由单页展示。 */
    private fun ratioFor(index: Int): Float? =
        geometries[index]?.takeIf { it.photo == photoAt(index) }?.ratio

    override fun resizeViewport(size: IntSize) {
        if (viewport != size) {
            cancelMotion()
            val index = currentPage
            val offset = currentItemOffset
            val oldWidth = viewport.width
            viewport = size
            offsetX = clampX(offsetX)
            if (oldWidth > 0 && size.width != oldWidth) {
                zoomAnimation = scope.launch {
                    listState.scrollToItem(
                        index,
                        (offset * size.width.toFloat() / oldWidth).roundToInt()
                    )
                }
            }
        }
    }


    override fun onDown(position: Offset, touchSlop: Float) {
        cancelMotion()
        edges.values.forEach { it.begin() }
        onPosition(position)
        releaseVelocity = Velocity.Zero
        val queue = Channel<Input>(Channel.UNLIMITED)
        inputs = queue
        gesture = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            listState.scroll(MutatePriority.UserInput) {
                for (input in queue) when (input) {
                    is Input.Drag -> drag(this, input.delta)
                    is Input.Scale -> scale(this, input.focus, zoom * input.factor, input.pan)
                }
                edges.values.forEach { it.release() }
                coroutineScope {
                    if (zoom > 1f) launch { flingAxis(releaseVelocity.x, false, this@scroll) }
                    launch { flingAxis(releaseVelocity.y, true, this@scroll) }
                }
            }
        }
    }

    override fun onDrag(delta: Offset) {
        inputs?.trySend(Input.Drag(delta))
    }

    override fun onScale(focus: Offset, zoom: Float, pan: Offset) {
        inputs?.trySend(Input.Scale(focus, zoom, pan))
    }

    /** 先收回已有边缘，再分配整列横移与纵向滚动，剩余纵向距离触发越界。 */
    private fun drag(scroll: ScrollScope, delta: Offset) {
        val x = retractAxis(delta.x, PhotoViewerEdge.LEFT, PhotoViewerEdge.RIGHT)
        val y = retractAxis(delta.y, PhotoViewerEdge.TOP, PhotoViewerEdge.BOTTOM)
        if (zoom > 1f) {
            // 与 EhViewer 连续布局一致：允许横移，但不显示左右越界反馈。
            // val previousX = offsetX
            offsetX = clampX(offsetX + x)
            // val remainingX = x - (offsetX - previousX)
            // val edge = if (remainingX > 0) PhotoViewerEdge.LEFT else PhotoViewerEdge.RIGHT
            // if (abs(remainingX) > 0.01f) edges.getValue(edge).pull(abs(remainingX))
        }
        if (y != 0f) isScrollingBackward = y > 0f
        val consumed = scroll.scrollBy(-y)
        pull(y + consumed)
    }

    /** 将未消费的纵向距离交给顶部或底部边缘。 */
    private fun pull(remaining: Float) {
        if (abs(remaining) <= 0.01f) return
        edges.getValue(verticalEdgeFor(remaining)).pull(abs(remaining))
    }

    /** 手指向下拉对应顶部，向上拉对应底部。 */
    private fun verticalEdgeFor(delta: Float) =
        if (delta > 0) PhotoViewerEdge.TOP else PhotoViewerEdge.BOTTOM

    /** 按整列缩放后的宽度限制横移，防止列边缘移入视口造成额外留白。 */
    private fun clampX(x: Float): Float {
        val limit = viewport.width * (zoom - 1f) / 2f
        return x.coerceIn(-limit, limit)
    }

    /** 整列等比缩放，补偿参考线前的图片高度变化，焦点所在内容尽量留在原位。 */
    private fun scale(scroll: ScrollScope, focus: Offset, target: Float, pan: Offset) {
        val next = target.coerceIn(1f, 2f)
        val factor = next / zoom
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
        val gapsBeforeFocus =
            listState.layoutInfo.visibleItemsInfo.count { it.offset < focus.y }.minus(1)
                .coerceAtLeast(0)
        val contentBeforeFocus = focus.y - (first?.offset ?: 0) - gapPixels * gapsBeforeFocus
        val oldLeft = viewport.width * (1f - zoom) / 2f + offsetX
        zoom = next
        offsetX =
            clampX(focus.x - (focus.x - oldLeft) * factor + pan.x - viewport.width * (1f - next) / 2f)
        scroll.scrollBy(contentBeforeFocus * (factor - 1f) - pan.y)
    }

    override fun onDoubleTap(focus: Offset) {
        cancelMotion()
        val start = zoom
        val target = if (zoom < 1.99f) 2f else 1f
        zoomAnimation = scope.launch {
            listState.scroll {
                animate(
                    start,
                    target,
                    animationSpec = tween(300, easing = LinearOutSlowInEasing)
                ) { value, _ ->
                    scale(this, focus, value, Offset.Zero)
                }
            }
        }
    }

    /** 单轴执行减速惯性，撞边后停止该轴；仅纵向撞边产生速度反馈。 */
    private suspend fun flingAxis(speed: Float, vertical: Boolean, scroll: ScrollScope) {
        if (abs(speed) < minimumVelocity) return
        val initial = speed.coerceIn(-maximumVelocity, maximumVelocity)
        var previous = 0f
        AnimationState(
            initialValue = 0f,
            initialVelocity = initial
        ).animateDecay(exponentialDecay()) {
            val delta = value - previous
            previous = value
            if (vertical && delta != 0f) isScrollingBackward = delta > 0f
            val consumed = if (vertical) -scroll.scrollBy(-delta) else {
                val oldX = offsetX
                offsetX = clampX(offsetX + delta)
                offsetX - oldX
            }
            if (abs(delta - consumed) > 0.01f) {
                if (vertical) {
                    edges.getValue(verticalEdgeFor(initial))
                        .absorb(abs(initial), viewport.height.toFloat())
                }
                // 连续阅读的水平惯性仍在边界停止，不显示水平撞边反馈。
                // else {
                //     val edge = if (initial > 0) PhotoViewerEdge.LEFT else PhotoViewerEdge.RIGHT
                //     edges.getValue(edge).absorb(abs(initial), viewport.width.toFloat())
                // }
                cancelAnimation()
            }
        }
    }

    override suspend fun goToPage(index: Int) = navigation.withLock { navigate(index) }

    /** 停止旧交互并直接定位目标项，由单页异步加载后更新高度；保留整列缩放，越过末页时通知停止播放。 */
    private suspend fun navigate(index: Int) {
        cancelMotion()
        edges.values.forEach { it.release() }
        if (index !in 0 until pageCount) {
            boundary(index >= pageCount)
            if (index >= pageCount) onEndReached()
            return
        }
        listState.scrollToItem(index)
        settledIndex = index
    }

    override suspend fun nextPage() = navigation.withLock { navigate(currentPage + 1) }
    override suspend fun previousPage() = navigation.withLock { navigate(currentPage - 1) }

    /** 为主动越界模拟一次纵向拉动，随后交由边缘效果自然回缩。 */
    private fun boundary(isEnd: Boolean) {
        edgeAnimation?.cancel()
        val edge = edges.getValue(if (isEnd) PhotoViewerEdge.BOTTOM else PhotoViewerEdge.TOP)
        edge.moveTo(Offset(viewport.width / 2f, viewport.height / 2f))
        edge.begin()
        val start = edge.distance
        edgeAnimation = scope.launch {
            animate(
                start,
                maxOf(start, viewport.height / 7f),
                animationSpec = tween(300)
            ) { value, _ ->
                edge.pull(value - edge.distance)
            }
            edge.release()
        }
    }

    /** 取消旧输入、手势惯性和动画并清零释放速度，不重置整列缩放与位置。 */
    private fun cancelMotion() {
        closeInputs()
        gesture?.cancel()
        zoomAnimation?.cancel()
        edgeAnimation?.cancel()
        releaseVelocity = Velocity.Zero
        isScrollingBackward = false
    }

    override fun dispose() {
        cancelMotion()
        edges.values.forEach { it.dispose() }
    }
}
