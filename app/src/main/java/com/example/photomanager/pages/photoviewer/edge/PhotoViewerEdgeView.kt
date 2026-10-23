/** 消费边缘效果状态并绘制圆弧，不判断边界或启动动画。 */
package com.example.photomanager.pages.photoviewer.edge

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import kotlin.math.cos
import kotlin.math.sqrt

@Composable
internal fun PhotoViewerEdgeView(
    modifier: Modifier,
    effects: Map<PhotoViewerEdge, PhotoViewerEdgeEffect>
) {
    Canvas(modifier) {
        effects.forEach { (edge, effect) ->
            val isVertical = edge.isVertical
            val distance = effect.distance
            val shrink = effect.shrink
            val touchPosition = effect.touchPosition
            val edgeLength = if (isVertical) size.width else size.height
            val viewportDepth = if (isVertical) size.height else size.width
            val pullFraction = distance / viewportDepth
            if (pullFraction == 0f || shrink == 0f) return@forEach
            val displacement = (if (isVertical) touchPosition.x / size.width
                else touchPosition.y / size.height).coerceIn(0f, 1f)
            val alongEdge = edgeLength / 2f + edgeLength * (displacement - 0.5f) / 2f
            // GLEdgeEffect 的圆半径、圆弓高度及基础缩放，ANGLE = PI / 6。
            val radius = edgeLength * 1.5f
            val arcHeight = radius * (1f - cos(Math.PI / 6).toFloat())
            val baseScale = (viewportDepth / edgeLength).coerceAtMost(1f)
            val glowScale = ((1f - 1f / sqrt(pullFraction * arcHeight) - 0.3f) / 0.7f)
                .coerceIn(0f, 1f) * baseScale * shrink
            if (glowScale == 0f) return@forEach
            val ellipseDepth = radius * glowScale
            val visibleDepth = arcHeight * glowScale
            val color = Color(0x339E9E9E).copy(alpha = (0.2f * effect.alpha).coerceIn(0f, 1f))
            // 大圆的大部分位于视口外，只裁出中间宽、两端窄的圆弧。
            clipRect {
                if (isVertical) {
                    val isBottom = edge.isTrailing
                    val centerY = if (isBottom) size.height + ellipseDepth - visibleDepth
                    else visibleDepth - ellipseDepth
                    drawOval(
                        color,
                        Offset(alongEdge - radius, centerY - ellipseDepth),
                        Size(radius * 2f, ellipseDepth * 2f)
                    )
                } else {
                    val isRightEdge = edge.isTrailing
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
}
