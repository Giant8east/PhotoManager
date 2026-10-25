/** 连续纵向阅读：按图片比例排列、整列缩放与横移，不使用逐页吸附。 */
package com.example.photomanager.pages.photoviewer.reader

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.PhotoMetadataReader
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
    private val metadata: PhotoMetadataReader,
    private val scope: CoroutineScope,
    private val onEndReached: () -> Unit,
    private val minimumVelocity: Float,
    private val maximumVelocity: Float
) : PhotoViewerLayoutManager<PhotoViewerScrollLayoutManager.Input>(scope) {
    private data class PageGeometry(val photo: PhotoEntity, val ratio: Float)
    private val geometries = mutableStateMapOf<Int, PageGeometry>()
    private var viewport by mutableStateOf(IntSize.Zero)
    private var zoom by mutableFloatStateOf(1f)
    private var offsetX by mutableFloatStateOf(0f)
    private var settledIndex by mutableIntStateOf(listState.firstVisibleItemIndex)
    private var gapPixels = 0f
    private var gesture: Job? = null
    private var zoomAnimation: Job? = null
    private var edgeAnimation: Job? = null
    private val navigation = Mutex()

    sealed interface Input {
        data class Drag(val delta: Offset) : Input
        data class Scale(val focus: Offset, val factor: Float, val pan: Offset) : Input
    }

    override val pageCount get() = photos().size
    private fun photoAt(index: Int) = photos()[index]
    override val currentPage get() = continuousPageIndex(
        listState.layoutInfo.visibleItemsInfo.map { it.index to (it.offset + it.size) },
        listState.firstVisibleItemIndex,
        listState.layoutInfo.visibleItemsInfo.isNotEmpty() && !listState.canScrollForward
    )
    override val settledPage get() = settledIndex
    val currentItemOffset get() = listState.layoutInfo.visibleItemsInfo
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
        SideEffect { gapPixels = with(density) { gap.toPx() } }
        LaunchedEffect(this) {
            snapshotFlow { if (!listState.isScrollInProgress) currentPage else null }
                .filterNotNull().collect { index ->
                    settledIndex = index
                    images.forEach { (page, image) -> if (!isPageVisible(page)) image.reset() }
                }
        }
        // 预读附近文件头；图片请求仍由单页容器持有，不解码整个相册。
        LaunchedEffect(currentPage, pageCount) {
            for (index in maxOf(0, currentPage - 2)..minOf(pageCount - 1, currentPage + 3)) {
                loadRatio(index)
            }
        }
        CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // 首次测量就使用真实视口高度，避免 1px 占位让列表回填并丢失初始索引。
                val placeholderHeight = constraints.maxHeight.coerceAtLeast(1)
                LazyColumn(
                    state = listState, userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    items(pageCount, key = { photoAt(it).id }) { index ->
                        val photo = photoAt(index)
                        LaunchedEffect(photo.contentUri, photo.modifiedAt, photo.sizeBytes) { loadRatio(index) }
                        val ratio = ratioFor(index)
                        Box(
                            Modifier.layout { measurable, constraints ->
                                val width = constraints.maxWidth
                                val scaledWidth = (width * zoom).roundToInt().coerceAtLeast(1)
                                val height = ratio?.let { continuousImageHeight(width, it, zoom) }
                                    ?: placeholderHeight
                                val child = measurable.measure(Constraints.fixed(scaledWidth, height))
                                layout(width, height) {
                                    child.place(((width - scaledWidth) / 2f + offsetX).roundToInt(), 0)
                                }
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            // 文件头确定比例后才创建图片请求，解码完成不再改变列表项高度。
                            if (ratio != null) pageContent(index) else CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadRatio(index: Int) {
        if (ratioFor(index) != null) return
        val photo = photoAt(index)
        val ratio = metadata.readSize(photo).aspectRatio
        geometries[index] = PageGeometry(photo, ratio)
    }

    private fun ratioFor(index: Int): Float? {
        val photo = photoAt(index)
        return geometries[index]?.takeIf { it.photo == photo }?.ratio
            ?: metadata.cachedSize(photo)?.aspectRatio
    }

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
                    listState.scrollToItem(index, (offset * size.width.toFloat() / oldWidth).roundToInt())
                }
            }
        }
    }


    override fun onDown(position: Offset, touchSlop: Float) {
        cancelMotion()
        edgeAnimation?.cancel()
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

    override fun onDrag(delta: Offset) { inputs?.trySend(Input.Drag(delta)) }
    override fun onScale(focus: Offset, zoom: Float, pan: Offset) {
        inputs?.trySend(Input.Scale(focus, zoom, pan))
    }
    private fun drag(scroll: ScrollScope, delta: Offset) {
        val x = retractAxis(delta.x, PhotoViewerEdge.LEFT, PhotoViewerEdge.RIGHT)
        val y = retractAxis(delta.y, PhotoViewerEdge.TOP, PhotoViewerEdge.BOTTOM)
        if (zoom > 1f) {
            // 与 EhViewer 连续布局一致：允许横移，但不显示左右越界反馈。
            // val previousX = offsetX
            offsetX = clampX(offsetX + x)
            // pull(x - (offsetX - previousX), false)
        }
        val consumed = scroll.scrollBy(-y)
        pull(y + consumed, true)
    }

    private fun pull(remaining: Float, vertical: Boolean) {
        if (abs(remaining) <= 0.01f) return
        edges.getValue(edgeFor(remaining, vertical)).pull(abs(remaining))
    }

    private fun edgeFor(delta: Float, vertical: Boolean) = if (vertical) {
        if (delta > 0) PhotoViewerEdge.TOP else PhotoViewerEdge.BOTTOM
    } else {
        if (delta > 0) PhotoViewerEdge.LEFT else PhotoViewerEdge.RIGHT
    }

    private fun clampX(x: Float): Float {
        val limit = viewport.width * (zoom - 1f) / 2f
        return x.coerceIn(-limit, limit)
    }

    /** 整列等比缩放，补偿参考线前的图片高度变化，焦点所在内容尽量留在原位。 */
    private fun scale(scroll: ScrollScope, focus: Offset, target: Float, pan: Offset) {
        val next = target.coerceIn(1f, 2f)
        val factor = next / zoom
        val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
        val gapsBeforeFocus = listState.layoutInfo.visibleItemsInfo.count { it.offset < focus.y }.minus(1).coerceAtLeast(0)
        val contentBeforeFocus = focus.y - (first?.offset ?: 0) - gapPixels * gapsBeforeFocus
        val oldLeft = viewport.width * (1f - zoom) / 2f + offsetX
        zoom = next
        offsetX = clampX(focus.x - (focus.x - oldLeft) * factor + pan.x - viewport.width * (1f - next) / 2f)
        scroll.scrollBy(contentBeforeFocus * (factor - 1f) - pan.y)
    }

    override fun onDoubleTap(focus: Offset) {
        cancelMotion()
        val start = zoom
        val target = if (zoom < 1.99f) 2f else 1f
        zoomAnimation = scope.launch {
            listState.scroll {
                animate(start, target, animationSpec = tween(300, easing = LinearOutSlowInEasing)) { value, _ ->
                    scale(this, focus, value, Offset.Zero)
                }
            }
        }
    }

    private suspend fun flingAxis(speed: Float, vertical: Boolean, scroll: ScrollScope) {
        if (abs(speed) < minimumVelocity) return
        val initial = speed.coerceIn(-maximumVelocity, maximumVelocity)
        var previous = 0f
        AnimationState(initialValue = 0f, initialVelocity = initial).animateDecay(exponentialDecay()) {
            val delta = value - previous
            previous = value
            val consumed = if (vertical) -scroll.scrollBy(-delta) else {
                val oldX = offsetX
                offsetX = clampX(offsetX + delta)
                offsetX - oldX
            }
            if (abs(delta - consumed) > 0.01f) {
                if (vertical) {
                    edges.getValue(edgeFor(initial, true)).absorb(abs(initial), viewport.height.toFloat())
                }
                // 连续阅读的水平惯性仍在边界停止，不显示水平撞边反馈。
                // else edges.getValue(edgeFor(initial, false)).absorb(abs(initial), viewport.width.toFloat())
                cancelAnimation()
            }
        }
    }

    override suspend fun goToPage(index: Int) = navigation.withLock { navigate(index) }

    private suspend fun navigate(index: Int) {
        cancelMotion()
        edges.values.forEach { it.release() }
        if (index !in 0 until pageCount) {
            boundary(index >= pageCount)
            if (index >= pageCount) onEndReached()
            return
        }
        val wasPrepared = ratioFor(index) != null
        loadRatio(index)
        if (!wasPrepared) {
            // 让新比例先完成测量；到达末尾时由列表自然限制位置，不添加尾部空白。
            withFrameNanos { }
            withFrameNanos { }
        }
        listState.scrollToItem(index)
        settledIndex = index
    }
    override suspend fun nextPage() = navigation.withLock { navigate(currentPage + 1) }
    override suspend fun previousPage() = navigation.withLock { navigate(currentPage - 1) }

    private fun boundary(isEnd: Boolean) {
        edgeAnimation?.cancel()
        val edge = edges.getValue(if (isEnd) PhotoViewerEdge.BOTTOM else PhotoViewerEdge.TOP)
        edge.moveTo(Offset(viewport.width / 2f, viewport.height / 2f))
        edge.begin()
        val start = edge.distance
        edgeAnimation = scope.launch {
            animate(start, maxOf(start, viewport.height / 7f), animationSpec = tween(300)) { value, _ ->
                edge.pull(value - edge.distance)
            }
            edge.release()
        }
    }

    private fun cancelMotion() {
        closeInputs()
        gesture?.cancel()
        zoomAnimation?.cancel()
    }

    override fun dispose() {
        cancelMotion()
        edgeAnimation?.cancel()
        edges.values.forEach { it.dispose() }
    }
}
