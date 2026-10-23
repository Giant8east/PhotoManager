/** 主动翻页越界时绘制 EhViewer 式圆弧反馈，不缩放或拉伸图片。 */
package com.example.photomanager.pages.photoviewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.ReadingDirection
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.abs

private val EdgeDecelerate = Easing { fraction -> 1f - (1f - fraction) * (1f - fraction) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhotoViewerBoundaryFeedback(
    settings: PhotoViewerSettings,
    edgeState: PhotoViewerEdgeState
) {
    val pull = remember { Animatable(0f) }
    val release = remember { Animatable(1f) }
    val dragRelease = remember(edgeState) { Animatable(0f) }
    var releasedDistance by remember(edgeState) { mutableFloatStateOf(0f) }
    LaunchedEffect(edgeState, edgeState.isPulling) {
        if (edgeState.isPulling) {
            dragRelease.snapTo(0f)
        } else {
            releasedDistance = edgeState.distance
            dragRelease.snapTo(1f)
            dragRelease.animateTo(0f, tween(600, easing = EdgeDecelerate))
        }
    }
    // 与 OverScroller 一致：300ms 模拟拉出，释放后以减速曲线回缩 600ms。
    LaunchedEffect(edgeState, edgeState.pulse) {
        if (edgeState.pulse == 0) return@LaunchedEffect
        pull.snapTo(0f)
        release.snapTo(1f)
        pull.animateTo(1f / 7f, tween(300))
        release.animateTo(0f, tween(600, easing = EdgeDecelerate))
    }
    val imageInsets = if (settings.fullScreen) WindowInsets(0, 0, 0, 0)
        else WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    Canvas(Modifier.fillMaxSize().windowInsetsPadding(imageInsets)) {
        val isVertical = settings.readingDirection == ReadingDirection.TOP_TO_BOTTOM
        val edgeLength = if (isVertical) size.width else size.height
        val viewportDepth = if (isVertical) size.height else size.width
        val dragDistance = if (edgeState.isPulling) edgeState.distance else releasedDistance
        val isGesture = dragDistance != 0f && (edgeState.isPulling || dragRelease.value > 0f)
        val pullFraction = if (isGesture) abs(dragDistance) / viewportDepth else pull.value
        val shrink = if (isGesture) {
            if (edgeState.isPulling) 1f else dragRelease.value
        } else release.value
        if (pullFraction == 0f || shrink == 0f) return@Canvas
        val gestureDisplacement = if (isVertical) edgeState.touchPosition.x / size.width
            else edgeState.touchPosition.y / size.height
        val displacement = if (isGesture) gestureDisplacement.coerceIn(0f, 1f) else 0.5f
        val alongEdge = edgeLength / 2f + edgeLength * (displacement - 0.5f) / 2f
        // GLEdgeEffect 的圆半径、圆弓高度及基础缩放，ANGLE = PI / 6。
        val radius = edgeLength * 1.5f
        val arcHeight = radius * (1f - cos(Math.PI / 6).toFloat())
        val baseScale = (viewportDepth / edgeLength).coerceAtMost(1f)
        val glowScale = ((1f - 1f / sqrt(pullFraction * arcHeight) - 0.3f) / 0.7f)
            .coerceIn(0f, 1f) * baseScale * shrink
        if (glowScale == 0f) return@Canvas
        val ellipseDepth = radius * glowScale
        val visibleDepth = arcHeight * glowScale
        val color = Color(0x339E9E9E)
        // 大圆的大部分位于视口外，只裁出中间宽、两端窄的圆弧。
        clipRect {
            if (isVertical) {
                val isBottom = if (isGesture) dragDistance < 0f else edgeState.isEndBoundary
                val centerY = if (isBottom) size.height + ellipseDepth - visibleDepth
                    else visibleDepth - ellipseDepth
                drawOval(
                    color,
                    Offset(alongEdge - radius, centerY - ellipseDepth),
                    Size(radius * 2f, ellipseDepth * 2f)
                )
            } else {
                val isRightEdge = if (isGesture) dragDistance < 0f else
                    edgeState.isEndBoundary != (settings.readingDirection == ReadingDirection.RIGHT_TO_LEFT)
                val centerX = if (isRightEdge) size.width + ellipseDepth - visibleDepth
                    else visibleDepth - ellipseDepth
                drawOval(
                    color,
                    Offset(centerX - ellipseDepth, alongEdge - radius),
                    Size(ellipseDepth * 2f, radius * 2f)
                )
            }
        }
    }
}
