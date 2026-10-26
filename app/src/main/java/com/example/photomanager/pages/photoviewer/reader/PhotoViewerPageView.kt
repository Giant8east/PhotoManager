/** 单页容器持有图片请求、加载展示和变换状态；预加载不依赖页面是否可见。 */
package com.example.photomanager.pages.photoviewer.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Scale
import android.graphics.drawable.Animatable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.example.photomanager.data.PageScale
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.flow.first

/** 解码预算不随长图项高度或整列缩放增加；单张请求最多约四百万像素。 */
internal fun readerDecodeSize(viewport: Size, screen: IntSize): IntSize = IntSize(
    minOf(viewport.width.toInt(), screen.width, 2048).coerceAtLeast(1),
    minOf(viewport.height.toInt(), screen.height, 2048).coerceAtLeast(1)
)

@Composable
internal fun PhotoViewerPageView(
    photo: PhotoEntity,
    pageScale: PageScale,
    viewerPager: PhotoViewerPager,
    index: Int
) {
    // 适配方式
    val effectivePageScale = if (viewerPager.isContinuous) PageScale.FIT_WIDTH else pageScale
    // 保存这一页的变换
    val transform = remember(photo.id, effectivePageScale) { PhotoTransform(effectivePageScale) }
    var retryCount by remember(photo) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val request = remember(photo, transform, context) {
            ImageRequest.Builder(context).data(photo.contentUri)
                .scale(Scale.FIT)
                .memoryCacheKey("${photo.contentUri}|${photo.modifiedAt}|${photo.sizeBytes}|reader")
                .size {
                    // 等待页面完成测量，观察 viewport，等它第一次出现有效宽高，获取视口宽高
                    val viewport = snapshotFlow { transform.viewport }
                        .first { it.width > 0f && it.height > 0f }
                    // 连续阅读的长图项可高于屏幕，请求仍以屏幕预算采样，不整张原尺寸解码。
                    val metrics = context.resources.displayMetrics
                    val size = readerDecodeSize(
                        viewport,
                        IntSize(metrics.widthPixels, metrics.heightPixels)
                    )
                    coil.size.Size(size.width, size.height)
                }.build()
        }
    // 重试重建 Painter 并重新执行同一请求，不影响相邻页预加载。
    val painter = key(retryCount) {
        rememberAsyncImagePainter(
            model = request,
            contentScale = ContentScale.Fit
        )
    }
    val imageState = painter.state
    val success = imageState as? AsyncImagePainter.State.Success
    val imageSize = success?.painter?.intrinsicSize
    // 成功结果或适配对象变化时同步一次，缓存命中也走同一入口。
    LaunchedEffect(transform, imageSize) {
        imageSize?.let { transform.resizeImage(it) }
    }
    // 向阅读器注册当前图片
    DisposableEffect(viewerPager, index, transform) {
        viewerPager.attachImage(index, transform)
        onDispose { viewerPager.detachImage(index, transform) }
    }
    // 确保状态在组合阶段被读取，不用by委托
    val isVisible = remember(viewerPager, index) {
        derivedStateOf { viewerPager.isPageVisible(index) }
    }.value
    // 检查解码结果是否支持播放控制
    val animation = success?.result?.drawable as? Animatable
    if (animation != null) {
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(animation, lifecycle, isVisible) {
            fun updatePlayback() {
                // 支持Scroll布局下多个gif同时播放
                val shouldPlay = isVisible &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                if (shouldPlay) animation.start()
                else animation.stop()
            }

            val observer = LifecycleEventObserver { _, _ -> updatePlayback() }
            lifecycle.addObserver(observer)
            updatePlayback()
            onDispose {
                lifecycle.removeObserver(observer)
                animation.stop()
            }
        }
    }
    // 在屏幕外准备好初始状态，避免重入首帧先画旧缩放再跳回；部分可见时不重置。
    SideEffect {
        if (!isVisible) transform.reset()
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged {
                transform.resizeViewport(
                    Size(
                        it.width.toFloat(),
                        it.height.toFloat()
                    )
                )
            }
            .semantics { contentDescription = photo.name },
        contentAlignment = Alignment.Center
    ) {
        when (imageState) {
            is AsyncImagePainter.State.Success -> PhotoViewerImage(painter, transform)
            is AsyncImagePainter.State.Error -> Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("图片加载失败", color = Color.White)
                Text("第 ${index + 1} 张", color = Color.White)
                TextButton(onClick = { retryCount++ }) { Text("重试", color = Color.White) }
            }

            else -> CircularProgressIndicator()
        }
    }
}
