/** 横向分页和连续纵向布局共同实现的阅读交互边界，不持有业务数据。 */
package com.example.photomanager.pages.photoviewer.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.example.photomanager.data.database.PhotoEntity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.pages.photoviewer.edge.PhotoViewerEdge
import com.example.photomanager.pages.photoviewer.edge.PhotoViewerEdgeEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel

/** 统一阅读布局能力与公共状态；距离分配、排列及动画策略由具体布局实现。 */
internal abstract class PhotoViewerLayoutManager<Input>(scope: CoroutineScope) {
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
    protected var inputs: Channel<Input>? = null
    protected var releaseVelocity = Velocity.Zero

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

    protected fun closeInputs() {
        inputs?.close()
        inputs = null
    }

    protected fun retractAxis(delta: Float, leading: PhotoViewerEdge, trailing: PhotoViewerEdge): Float = when {
        delta < 0f -> delta + edges.getValue(leading).retract(-delta)
        delta > 0f -> delta - edges.getValue(trailing).retract(delta)
        else -> delta
    }
}
