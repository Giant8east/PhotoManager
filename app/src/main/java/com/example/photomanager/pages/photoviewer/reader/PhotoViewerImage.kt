/** 保存单张图片的变换并显示图片；手势统一由固定阅读视口接收。 */
package com.example.photomanager.pages.photoviewer.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.SizeResolver
import kotlinx.coroutines.flow.first
import com.example.photomanager.data.PageScale
import com.example.photomanager.data.database.PhotoEntity

/** 与 EhViewer 一致，限制相对于图片尺寸的实际比例。 */
private const val MinScale = 0.1f
private const val MaxScale = 10f

/** 负责计算图片应该多大、在哪里 */
internal class PhotoTransform(private val contentScale: ContentScale) {
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
        if (hasImageGeometry) contentScale.computeScaleFactor(imageSize, viewport).scaleX else 1f

    /** 档位按实际比例排序，返回值换算为绘制使用的相对倍数。 */
    fun nextDoubleTapScale(): Float {
        if (!hasImageGeometry) return scale
        val widthScale = viewport.width / imageSize.width
        val heightScale = viewport.height / imageSize.height
        val candidates = floatArrayOf(1f, widthScale, heightScale, maxOf(widthScale, heightScale) * 2f)
            .map { it.coerceIn(MinScale, MaxScale) }.sorted()
        val actualScale = scale * initialScale
        val target = candidates.firstOrNull { actualScale < it - 0.01f } ?: candidates.first()
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

    private fun constrain(position: Offset, zoom: Float): Offset {
        if (imageSize.width <= 0f || imageSize.height <= 0f || viewport == Size.Zero) return Offset.Zero
        val initialScale = contentScale.computeScaleFactor(imageSize, viewport)
        val limitX = ((imageSize.width * initialScale.scaleX * zoom - viewport.width) / 2f).coerceAtLeast(0f)
        val limitY = ((imageSize.height * initialScale.scaleY * zoom - viewport.height) / 2f).coerceAtLeast(0f)
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

    /** 先变换完整图片，再求可见交集；源区域始终相对于完整解码图片。 */
    fun drawRegion(): PhotoDrawRegion? {
        if (!hasImageGeometry) return null
        val factor = contentScale.computeScaleFactor(imageSize, viewport)
        val width = imageSize.width * factor.scaleX * scale
        val height = imageSize.height * factor.scaleY * scale
        val left = (viewport.width - width) / 2f + offset.x
        val top = (viewport.height - height) / 2f + offset.y
        val image = Rect(left, top, left + width, top + height)
        val visible = image.intersect(Rect(Offset.Zero, viewport))
        if (visible.isEmpty) return null
        val source = Rect(
            (visible.left - left) / width * imageSize.width,
            (visible.top - top) / height * imageSize.height,
            (visible.right - left) / width * imageSize.width,
            (visible.bottom - top) / height * imageSize.height
        )
        return PhotoDrawRegion(image, source, visible)
    }

}

internal data class PhotoDrawRegion(val image: Rect, val source: Rect, val destination: Rect)

@Composable
internal fun PhotoViewerImage(
    photo: PhotoEntity,
    pageScale: PageScale,
    viewerPager: PhotoViewerPager,
    index: Int
) {
    val contentScale = when (pageScale) {
        PageScale.ORIGINAL -> ContentScale.None
        PageScale.FIT_WIDTH -> ContentScale.FillWidth
        PageScale.FIT_HEIGHT -> ContentScale.FillHeight
        PageScale.FIT_SCREEN -> ContentScale.Fit
    }
    val transform = remember(photo.id, pageScale) { PhotoTransform(contentScale) }
    val context = LocalContext.current
    val request = remember(photo.contentUri, transform, context) {
        ImageRequest.Builder(context).data(photo.contentUri)
            .size(SizeResolver {
                val viewport = snapshotFlow { transform.viewport }
                    .first { it.width > 0f && it.height > 0f }
                coil.size.Size(viewport.width.toInt(), viewport.height.toInt())
            }).build()
    }
    val painter = rememberAsyncImagePainter(
        model = request,
        contentScale = contentScale,
        onSuccess = { transform.resizeImage(it.painter.intrinsicSize) }
    )
    // 缓存命中也从成功状态同步几何；状态在组合阶段读取，不等待额外用户操作。
    val loadedPainter = (painter.state as? AsyncImagePainter.State.Success)?.painter
    SideEffect { loadedPainter?.let { transform.resizeImage(it.intrinsicSize) } }
    DisposableEffect(viewerPager, index, transform) {
        viewerPager.attachImage(index, transform)
        onDispose { viewerPager.detachImage(index, transform) }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = photo.name }
            .onSizeChanged { transform.resizeViewport(Size(it.width.toFloat(), it.height.toFloat())) }
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
