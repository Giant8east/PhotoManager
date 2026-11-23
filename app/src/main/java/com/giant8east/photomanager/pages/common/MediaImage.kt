/** 通用图片展示组件：统一占位底色、加载/失败兜底与 200ms 淡入，并组合出缩略图与头图两种语义样式。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.BitmapFactoryDecoder
import coil.request.ImageRequest
import coil.size.Precision
import com.giant8east.photomanager.data.media.LocalAlbumCoverInterceptor

private const val CROSSFADE_DURATION_MILLIS = 200

/**
 * 图片底座：统一提供占位底色、加载中/失败（含空路径）兜底插槽与淡入效果。
 * 在 Success 同一帧同步计算 [contentScaleFor]，避免先 Fit 再切 Crop 导致的“从小到大缩放跳变”。
 */
@Composable
fun AppMediaImage(
    request: ImageRequest?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScaleFor: (width: Int, height: Int) -> ContentScale = { _, _ -> ContentScale.Fit },
    loadingPlaceholder: @Composable BoxScope.() -> Unit = { DefaultLoadingPlaceholder() },
    errorPlaceholder: @Composable BoxScope.() -> Unit = { DefaultErrorPlaceholder() }
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (request == null) {
            errorPlaceholder()
            return@Box
        }
        val painter = rememberAsyncImagePainter(model = request)
        val state = painter.state
        // 直接从当前成功帧的 drawable 同步算出最终 ContentScale，首帧即为目标比例，无缩放跳变
        val resolvedScale = if (state is AsyncImagePainter.State.Success) {
            val drawable = state.result.drawable
            contentScaleFor(drawable.intrinsicWidth, drawable.intrinsicHeight)
        } else {
            ContentScale.Fit
        }

        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.fillMaxSize(),
            contentScale = resolvedScale
        )

        when (state) {
            is AsyncImagePainter.State.Loading,
            is AsyncImagePainter.State.Empty -> loadingPlaceholder()
            is AsyncImagePainter.State.Error -> errorPlaceholder()
            is AsyncImagePainter.State.Success -> Unit
        }
    }
}

/**
 * 缩略图组合组件（用于相册列表封面与详情画廊网格）：
 * 强制静态首帧解码 + 封顶尺寸 + INEXACT 快速降采样 + 200ms 淡入。
 */
@Composable
fun ThumbnailImage(
    uri: String,
    decodeWidthPx: Int,
    decodeHeightPx: Int,
    modifier: Modifier = Modifier,
    localAlbumPath: String = "",
    cacheVersionKey: Any? = null,
    memoryCacheKey: String? = null,
    contentScaleFor: (width: Int, height: Int) -> ContentScale = ::cardCoverScale
) {
    val context = LocalContext.current
    val request = remember(
        uri,
        localAlbumPath,
        cacheVersionKey,
        memoryCacheKey,
        decodeWidthPx,
        decodeHeightPx
    ) {
        if (uri.isEmpty() && localAlbumPath.isEmpty()) {
            null
        } else {
            val builder = ImageRequest.Builder(context)
                .data(uri)
                .decoderFactory(BitmapFactoryDecoder.Factory()) // 强制按静态 Bitmap 解码，只取第一帧
                .size(decodeWidthPx, decodeHeightPx)
                .precision(Precision.INEXACT)
                .crossfade(CROSSFADE_DURATION_MILLIS)
            if (localAlbumPath.isNotEmpty()) {
                builder.setParameter(LocalAlbumCoverInterceptor.ALBUM_PATH_PARAM, localAlbumPath)
            }
            if (memoryCacheKey != null) {
                builder.memoryCacheKey(memoryCacheKey)
            }
            builder.build()
        }
    }
    AppMediaImage(
        request = request,
        modifier = modifier,
        contentScaleFor = contentScaleFor
    )
}

/**
 * 头图组合组件（用于详情页顶部 Hero 封面）：
 * 走全局 ImageDecoderDecoder 支持动图播放，保留完整比例 Fit，同样具备占位底色、兜底图标与 200ms 淡入。
 */
@Composable
fun HeroCoverImage(
    uri: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val request = remember(uri) {
        if (uri.isEmpty()) {
            null
        } else {
            ImageRequest.Builder(context)
                .data(uri)
                .memoryCacheKey("$uri|detail-cover-animated")
                .crossfade(CROSSFADE_DURATION_MILLIS)
                .build()
        }
    }
    AppMediaImage(
        request = request,
        modifier = modifier,
        contentScaleFor = { _, _ -> ContentScale.Fit }
    )
}

/** 列表卡片封面比例策略：常规比例裁切铺满，超长图/超宽图完整居中显示。 */
fun cardCoverScale(width: Int, height: Int): ContentScale {
    if (width <= 0 || height <= 0) return ContentScale.Fit
    val aspect = width.toFloat() / height
    return if (aspect > 1f / 3f && aspect < 4f / 3f) ContentScale.Crop else ContentScale.Fit
}

@Composable
private fun DefaultLoadingPlaceholder() {
    Icon(
        imageVector = Icons.Outlined.Image,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.size(28.dp)
    )
}

@Composable
private fun DefaultErrorPlaceholder() {
    Icon(
        imageVector = Icons.Outlined.BrokenImage,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.size(28.dp)
    )
}
