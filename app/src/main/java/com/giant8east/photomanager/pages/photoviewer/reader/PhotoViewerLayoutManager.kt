/** 横向分页和连续纵向布局共同实现的阅读交互边界，不持有业务数据。 */
package com.giant8east.photomanager.pages.photoviewer.reader

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.giant8east.photomanager.data.database.PhotoEntity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import com.giant8east.photomanager.data.settings.PhotoViewerSettings
import com.giant8east.photomanager.pages.photoviewer.edge.PhotoViewerEdge
import com.giant8east.photomanager.pages.photoviewer.edge.PhotoViewerEdgeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 手势位移与减速判定共用的像素容差。 */
internal const val Epsilon = 0.01f

/** 缩放与越界反馈的动画时长，保持两种阅读布局节奏一致。 */
internal const val ZoomMillis = 300

/** 主动越界时拉出的深度占视口深度的比例。 */
private const val BoundaryPullRatio = 1f / 7f

/** 统一阅读布局能力与公共状态；距离分配、排列及动画策略由具体布局实现。 */
internal abstract class PhotoViewerLayoutManager(scope: CoroutineScope) {
    abstract val currentPage: Int
    abstract val settledPage: Int
    abstract val pageCount: Int
    /** 连续纵向阅读才有的能力，分页布局使用默认值，避免调用方向下转型。 */
    open val isContinuous: Boolean get() = false
    open val currentItemOffset: Int get() = 0
    open val unscaledItemOffset: Int get() = 0
    abstract fun isPageVisible(index: Int): Boolean
    abstract fun resizeViewport(size: IntSize)
    abstract fun onDown(position: Offset, touchSlop: Float)
    abstract fun onDrag(delta: Offset)
    abstract fun onScale(focus: Offset, zoom: Float, pan: Offset)
    abstract fun onDoubleTap(focus: Offset)
    abstract suspend fun goToPage(index: Int)
    abstract suspend fun nextPage()
    abstract suspend fun previousPage()
    abstract fun dispose()
    @Composable abstract fun Content(settings: PhotoViewerSettings, pageContent: @Composable (Int) -> Unit)

    val edges = PhotoViewerEdge.entries.associateWith { PhotoViewerEdgeEffect(scope) }
    protected val images = mutableMapOf<Int, PhotoTransform>()
    protected var releaseVelocity = Velocity.Zero
    protected var zoomAnimation: Job? = null
    protected var edgeAnimation: Job? = null

    fun attachImage(index: Int, transform: PhotoTransform) { images[index] = transform }

    fun detachImage(index: Int, transform: PhotoTransform) {
        if (images[index] !== transform) return
        onImageDetached(index)
        images.remove(index)
    }

    /** 单页报告解码尺寸；横向固定页高无需重新排列。 */
    open fun onImageSizeChanged(index: Int, photo: PhotoEntity, size: Size) {}

    protected open fun onImageDetached(index: Int) {}

    fun onPosition(position: Offset) { edges.values.forEach { it.moveTo(position) } }

    fun onUp(velocity: Velocity) {
        releaseVelocity = velocity
        closeInputs()
    }

    /** 关闭当前手势输入通道，结束上一段手势会话。 */
    protected abstract fun closeInputs()

    /** 取消输入与公共动画并清零释放速度；具体布局再补充自己的手势任务。 */
    protected fun cancelAnimations() {
        closeInputs()
        zoomAnimation?.cancel()
        edgeAnimation?.cancel()
        releaseVelocity = Velocity.Zero
    }

    protected fun releaseEdges() { edges.values.forEach { it.release() } }

    protected fun retractAxis(delta: Float, leading: PhotoViewerEdge, trailing: PhotoViewerEdge): Float = when {
        delta < 0f -> delta + edges.getValue(leading).retract(-delta)
        delta > 0f -> delta - edges.getValue(trailing).retract(delta)
        else -> delta
    }
}

/** 模拟一次主动越界拉动后释放；从当前位移起算，避免首帧反向跳动。 */
internal fun PhotoViewerEdgeEffect.simulatePull(
    scope: CoroutineScope,
    center: Offset,
    depth: Int
): Job {
    moveTo(center)
    begin()
    val start = distance
    return scope.launch {
        animate(
            start,
            maxOf(start, depth * BoundaryPullRatio),
            animationSpec = tween(ZoomMillis)
        ) { value, _ ->
            pull(value - distance)
        }
        release()
    }
}
