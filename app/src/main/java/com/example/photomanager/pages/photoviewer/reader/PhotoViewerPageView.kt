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
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.photomanager.data.PageScale
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.flow.first

@Composable
internal fun PhotoViewerPageView(
    photo: PhotoEntity,
    pageScale: PageScale,
    viewerPager: PhotoViewerPager,
    index: Int
) {
    // 适配方式
    val contentScale = when (pageScale) {
        PageScale.ORIGINAL -> ContentScale.None
        PageScale.FIT_WIDTH -> ContentScale.FillWidth
        PageScale.FIT_HEIGHT -> ContentScale.FillHeight
        PageScale.FIT_SCREEN -> ContentScale.Fit
    }
    // 保存这一页的变换
    val transform = remember(photo.id, pageScale) { PhotoTransform(contentScale) }
    var retryCount by remember(photo.id, photo.contentUri) { mutableIntStateOf(0) }
    val context = LocalContext.current
    val request = remember(photo.contentUri, transform, context) {
        ImageRequest.Builder(context).data(photo.contentUri)
            .size {
                val viewport = snapshotFlow { transform.viewport }
                    .first { it.width > 0f && it.height > 0f }
                coil.size.Size(viewport.width.toInt(), viewport.height.toInt())
            }.build()
    }
    // 重试只重建当前页请求；离开可见区域不取消相邻页的预加载。
    val painter = key(retryCount) {
        rememberAsyncImagePainter(
            model = request,
            contentScale = contentScale,
            onSuccess = { transform.resizeImage(it.painter.intrinsicSize) }
        )
    }
    // 读取加载状态并同步成功结果。
    val imageState = painter.state  // Coil 提供的状态
    val loadedPainter = (imageState as? AsyncImagePainter.State.Success)?.painter
    // 在组合成功提交后，同步已加载的尺寸
    SideEffect { loadedPainter?.let { transform.resizeImage(it.intrinsicSize) } }
    // 向阅读器注册当前图片
    DisposableEffect(viewerPager, index, transform) {
        viewerPager.attachImage(index, transform)
        onDispose { viewerPager.detachImage(index, transform) }
    }
    val isVisible by remember(viewerPager, index) {
        derivedStateOf { viewerPager.isPageVisible(index) }
    }
    // 在屏幕外准备好初始状态，避免重入首帧先画旧缩放再跳回；部分可见时不重置。
    SideEffect {
        if (!isVisible) transform.reset()
    }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { transform.resizeViewport(Size(it.width.toFloat(), it.height.toFloat())) }
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
            else -> CircularProgressIndicator(color = Color.White)
        }
    }
}
