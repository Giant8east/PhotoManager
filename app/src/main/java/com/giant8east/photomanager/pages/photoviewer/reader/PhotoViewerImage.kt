/** 图片几何计算与可见区域绘制，不管理请求或加载状态。 */
package com.giant8east.photomanager.pages.photoviewer.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import com.giant8east.photomanager.data.settings.PageScale

/** 与 EhViewer 一致，限制相对于图片尺寸的实际比例。 */
private const val MinScale = 0.1f
private const val MaxScale = 10f

/** 双击档位比较时视为同一档的比例容差。 */
private const val ScaleEpsilon = 0.01f

/** 负责计算图片应该多大、在哪里 */
internal class PhotoTransform(private val pageScale: PageScale) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(Size.Zero)
        private set
    private var imageSize by mutableStateOf(Size.Zero)

    private val hasImageGeometry get() = imageSize.width > 0f && imageSize.height > 0f &&
        viewport.width > 0f && viewport.height > 0f
    private val initialScale get() =
        if (!hasImageGeometry) 1f else {
            val widthScale = viewport.width / imageSize.width
            val heightScale = viewport.height / imageSize.height
            when (pageScale) {
                PageScale.ORIGINAL -> 1f
                PageScale.FIT_WIDTH -> widthScale
                PageScale.FIT_HEIGHT -> heightScale
                PageScale.FIT_SCREEN -> minOf(widthScale, heightScale)
            }.coerceIn(MinScale, MaxScale)
        }

    private val actualScale get() = initialScale * scale

    val canPanHorizontally get() = hasImageGeometry &&
        imageSize.width * actualScale > viewport.width + Epsilon
    val canPanVertically get() = hasImageGeometry &&
        imageSize.height * actualScale > viewport.height + Epsilon

    /** 档位按实际比例排序，返回值换算为绘制使用的相对倍数。 */
    fun nextDoubleTapScale(): Float {
        if (!hasImageGeometry) return scale
        val widthScale = viewport.width / imageSize.width
        val heightScale = viewport.height / imageSize.height
        val candidates = floatArrayOf(1f, widthScale, heightScale, maxOf(widthScale, heightScale) * 2f)
            .map { it.coerceIn(MinScale, MaxScale) }.sorted()
        val target = candidates.firstOrNull { actualScale < it - ScaleEpsilon } ?: candidates.first()
        return target / initialScale
    }

    fun resizeViewport(size: Size) {
        viewport = size
        offset = constrain(offset, scale)
    }

    fun resizeImage(size: Size) {
        imageSize = size
        offset = constrain(offset, scale)
    }

    /** 页面完全离开视口后恢复初始显示，保留解码尺寸供再次进入使用。 */
    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    private fun constrain(position: Offset, zoom: Float): Offset {
        if (imageSize.width <= 0f || imageSize.height <= 0f || viewport == Size.Zero) return Offset.Zero
        val limitX = ((imageSize.width * initialScale * zoom - viewport.width) / 2f).coerceAtLeast(0f)
        val limitY = ((imageSize.height * initialScale * zoom - viewport.height) / 2f).coerceAtLeast(0f)
        return Offset(
            if (limitX == 0f) 0f else position.x.coerceIn(-limitX, limitX),
            if (limitY == 0f) 0f else position.y.coerceIn(-limitY, limitY)
        )
    }

    /** 保持焦点下的图片位置，再按缩放后的实际图片矩形校正边界。 */
    fun zoomTo(target: Float, focus: Offset, pan: Offset = Offset.Zero) {
        val initial = initialScale
        val nextScale = target.coerceIn(MinScale / initial, MaxScale / initial)
        val relativeFocus = focus - Offset(viewport.width / 2f, viewport.height / 2f)
        offset = constrain(relativeFocus - (relativeFocus - offset) * (nextScale / scale) + pan, nextScale)
        scale = nextScale
    }

    /** 返回图片实际消费的距离，剩余部分交给 Pager。 */
    fun pan(delta: Offset): Offset {
        val previous = offset
        offset = constrain(offset + delta, scale)
        return offset - previous
    }

    /** 先变换完整图片，再求可见交集；绘制只需完整图片矩形与裁剪区域。 */
    fun drawRegion(): PhotoDrawRegion? {
        if (!hasImageGeometry) return null
        val width = imageSize.width * actualScale
        val height = imageSize.height * actualScale
        val left = (viewport.width - width) / 2f + offset.x
        val top = (viewport.height - height) / 2f + offset.y
        val image = Rect(left, top, left + width, top + height)
        val visible = image.intersect(Rect(Offset.Zero, viewport))
        if (visible.isEmpty) return null
        return PhotoDrawRegion(image, visible)
    }

}

internal data class PhotoDrawRegion(val image: Rect, val destination: Rect)

@Composable
internal fun PhotoViewerImage(painter: Painter, transform: PhotoTransform) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
    ) {
        val region = transform.drawRegion() ?: return@Canvas
        val visible = region.destination
        // 通用 Painter 没有源矩形 API，以等价的完整绘制变换和交集裁剪保留动图能力。
        clipRect(visible.left, visible.top, visible.right, visible.bottom) {
            translate(region.image.left, region.image.top) {
                with(painter) { draw(region.image.size) }
            }
        }
    }
}
