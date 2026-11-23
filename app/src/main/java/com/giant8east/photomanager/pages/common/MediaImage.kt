/** 通用图片展示组件：对齐 EhViewer LoadImageView（透明加载态、仅失败显示兜底、内存命中直出、磁盘/网络 200ms 淡入）。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.decode.BitmapFactoryDecoder
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.size.Precision
import com.giant8east.photomanager.data.media.LocalAlbumCoverInterceptor

private const val FADE_IN_DURATION_MILLIS = 200

/** 列表卡片封面与详情头图共享的内存缓存键（本地无 .thumb 或远端封面共用同一键）。 */
fun albumCoverCacheKey(coverUri: String, versionKey: Any?): String = "cover|$coverUri|$versionKey"

/**
 * 图片底座：对齐 EhViewer LoadImageView，加载中保持干净透明，只在失败/空路径时显示兜底图标；
 * 命中内存缓存或持有 placeholderMemoryCacheKey 占位图时直接 1f 显示不闪烁，磁盘/网络新加载做 200ms 纯 GPU 淡入。
 */
@Composable
fun AppMediaImage(
    request: ImageRequest?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    contentScaleFor: (width: Int, height: Int) -> ContentScale = { _, _ -> ContentScale.Fit },
    loadingPlaceholder: @Composable BoxScope.() -> Unit = {},
    errorPlaceholder: @Composable BoxScope.() -> Unit = { DefaultErrorPlaceholder() }
) {
    Box(
        modifier = modifier,
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

        // 若加载阶段已由 Coil placeholderMemoryCacheKey 命中列表封面，全程保持 alpha=1f 无缝升级原图/动图
        var hadPlaceholder by remember(request) { mutableStateOf(false) }
        if (state is AsyncImagePainter.State.Loading && state.painter != null) {
            hadPlaceholder = true
        }
        val immediateVisible = hadPlaceholder ||
            (state is AsyncImagePainter.State.Success && state.result.dataSource == DataSource.MEMORY_CACHE)

        val alphaAnim = remember(request) { Animatable(0f) }
        LaunchedEffect(request, state) {
            if (state is AsyncImagePainter.State.Success) {
                if (immediateVisible) {
                    alphaAnim.snapTo(1f)
                } else if (alphaAnim.value < 1f) {
                    alphaAnim.animateTo(1f, tween(FADE_IN_DURATION_MILLIS))
                }
            }
        }

        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (immediateVisible) 1f else alphaAnim.value
                },
            contentScale = resolvedScale
        )

        when (state) {
            is AsyncImagePainter.State.Loading,
            is AsyncImagePainter.State.Empty -> if (state !is AsyncImagePainter.State.Loading || state.painter == null) {
                loadingPlaceholder()
            }
            is AsyncImagePainter.State.Error -> errorPlaceholder()
            is AsyncImagePainter.State.Success -> Unit
        }
    }
}

/**
 * 缩略图组合组件（用于相册列表封面与详情画廊网格）：
 * 强制静态首帧解码 + 封顶尺寸 + INEXACT 快速降采样 + 200ms 透明淡入。
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
 * 先复用列表卡片已缓存的封面作为 placeholderMemoryCacheKey（进场第 0 帧即有图不闪），
 * 再走全局 ImageDecoderDecoder 无缝升级为原图或动图。
 */
@Composable
fun HeroCoverImage(
    uri: String,
    modifier: Modifier = Modifier,
    localAlbumPath: String = "",
    coverVersionKey: Any? = null
) {
    val context = LocalContext.current
    val request = remember(uri, localAlbumPath, coverVersionKey) {
        if (uri.isEmpty()) {
            null
        } else {
            val placeholderKey = if (localAlbumPath.isNotEmpty()) {
                LocalAlbumCoverInterceptor.cachedCoverKey(localAlbumPath)
                    ?: albumCoverCacheKey(coverUri = uri, versionKey = coverVersionKey)
            } else {
                albumCoverCacheKey(coverUri = uri, versionKey = coverVersionKey)
            }
            ImageRequest.Builder(context)
                .data(uri)
                .placeholderMemoryCacheKey(placeholderKey)
                .memoryCacheKey("$uri|detail-cover-animated")
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
private fun DefaultErrorPlaceholder() {
    Icon(
        imageVector = Icons.Outlined.BrokenImage,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.size(28.dp)
    )
}
