/** 保存单张图片的变换并显示图片；手势统一由固定阅读视口接收。 */
package com.example.photomanager.pages.photoviewer.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import coil.compose.AsyncImage
import com.example.photomanager.data.PageScale
import com.example.photomanager.data.database.PhotoEntity

/** 比例相对于设置指定的初始显示大小，不是原图像素比例。 */
private const val MinScale = 0.1f
private const val MaxScale = 8f

/** 负责计算图片应该多大、在哪里 */
internal class PhotoTransform(private val contentScale: ContentScale) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var viewport by mutableStateOf(Size.Zero)
        private set
    private var imageSize = Size.Zero

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
        val nextScale = target.coerceIn(MinScale, MaxScale)
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

    fun restore(progress: Float, startScale: Float, startOffset: Offset) {
        scale = startScale + (1f - startScale) * progress
        offset = constrain(startOffset * (1f - progress), scale)
    }
}

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
    var intrinsicSize by remember(photo.id) { mutableStateOf(Size.Zero) }
    SideEffect { transform.resizeImage(intrinsicSize) }
    DisposableEffect(viewerPager, index, transform) {
        viewerPager.attachImage(index, transform)
        onDispose { viewerPager.detachImage(index, transform) }
    }

    AsyncImage(
        model = photo.contentUri,
        contentDescription = photo.name,
        contentScale = contentScale,
        onSuccess = { intrinsicSize = it.painter.intrinsicSize },
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { transform.resizeViewport(Size(it.width.toFloat(), it.height.toFloat())) }
            .graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.offset.x
                translationY = transform.offset.y
            }
    )
}
