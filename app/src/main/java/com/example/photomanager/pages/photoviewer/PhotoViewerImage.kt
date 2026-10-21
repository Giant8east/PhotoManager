/** 单张图片视口，负责缩放与平移；变换状态按 photo.id 隔离，切换图片即复位。 */
package com.example.photomanager.pages.photoviewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.example.photomanager.data.PageScale
import com.example.photomanager.data.database.PhotoEntity

/** 当前阶段的缩放区间，上限与边界限制待接入分块解码后再按原图像素确定。 */
private const val MinScale = 1f
private const val MaxScale = 8f

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoViewerImage(
    photo: PhotoEntity,
    pageScale: PageScale
) {
    var scale by remember(photo.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(photo.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(photo.id) { mutableFloatStateOf(0f) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(MinScale, MaxScale)
        if (scale > MinScale) {
            offsetX += pan.x
            offsetY += pan.y
        } else {
            offsetX = 0f
            offsetY = 0f
        }
    }
    val contentScale = when (pageScale) {
        PageScale.ORIGINAL -> ContentScale.None
        PageScale.FIT_WIDTH -> ContentScale.FillWidth
        PageScale.FIT_HEIGHT -> ContentScale.FillHeight
        PageScale.FIT_SCREEN -> ContentScale.Fit
    }
    AsyncImage(
        model = photo.contentUri,
        contentDescription = photo.name,
        modifier = Modifier
            .fillMaxSize()
            // 未放大时不消费单指手势，交给 Pager 翻页。
            .transformable(state = transformState, canPan = { scale > MinScale })
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
        contentScale = contentScale
    )
}
