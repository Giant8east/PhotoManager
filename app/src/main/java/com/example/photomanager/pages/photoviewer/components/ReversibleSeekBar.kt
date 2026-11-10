/** 阅读页整数进度条：纯 Compose 绘制和触摸映射，避免 AndroidView 互操作在 Compose 1.7 中的指针问题。 */
package com.example.photomanager.pages.photoviewer.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.photomanager.ui.theme.Teal
import kotlin.math.roundToInt

/** 轨道两端留白，保证首尾页圆点完整显示。 */
private val TrackInset = 10.dp
/** 轨道线宽与圆点半径。 */
private val TrackThickness = 2.dp
private val ThumbRadius = 6.dp
private val TrackColor = Color(0x4DFFFFFF)

/**
 * 阅读页码进度条。[draggedPage] 是拖动中的预览页码，null 表示未在拖动；
 * 拖动状态由调用方持有，本组件不保存状态，绘制与触摸共用同一套位置换算。
 */
@Composable
internal fun ReadingSeekBar(
    currentPage: Int,
    pageCount: Int,
    reversed: Boolean,
    draggedPage: Int?,
    onDraggedPageChanged: (Int?) -> Unit,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dragPreview by rememberUpdatedState(onDraggedPageChanged)
    val pageSelected by rememberUpdatedState(onPageSelected)
    val displayedPage = draggedPage ?: currentPage

    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(pageCount, reversed) {
                if (pageCount <= 1) return@pointerInput
                awaitEachGesture {
                    // 手势作用域自带 Density 和实时尺寸，无需额外保存宽度状态。
                    val width = size.width.toFloat()
                    val inset = TrackInset.toPx()
                    fun pageAt(x: Float): Int =
                        (progressOfX(x, width, inset, reversed) * (pageCount - 1)).roundToInt()

                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var page = pageAt(down.position.x)
                    dragPreview(page)
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            change.consume()
                            val next = pageAt(change.position.x)
                            if (next != page) {
                                page = next
                                dragPreview(page)
                            }
                        }
                        pageSelected(page)
                    } finally {
                        // 松手或手势被打断都清掉预览，否则上层会一直停在拖动中。
                        dragPreview(null)
                    }
                }
            }
    ) {
        val inset = TrackInset.toPx()
        val endX = size.width - inset
        if (endX <= inset) return@Canvas
        val centerY = size.height / 2f
        val progress = if (pageCount <= 1) 0f else displayedPage.toFloat() / (pageCount - 1)
        val thumbX = xOfProgress(progress, size.width, inset, reversed)
        val stroke = TrackThickness.toPx()

        drawLine(TrackColor, Offset(inset, centerY), Offset(endX, centerY), stroke)
        drawLine(
            Teal,
            Offset(xOfProgress(0f, size.width, inset, reversed), centerY),
            Offset(thumbX, centerY),
            stroke
        )
        drawCircle(Teal, ThumbRadius.toPx(), Offset(thumbX, centerY))
    }
}

/** 阅读进度换算为轨道横坐标；反向阅读只在这里翻转一次。 */
private fun xOfProgress(progress: Float, width: Float, inset: Float, reversed: Boolean): Float {
    val fraction = progress.coerceIn(0f, 1f)
    val visible = if (reversed) 1f - fraction else fraction
    return inset + visible * (width - inset * 2).coerceAtLeast(1f)
}

/** 触摸横坐标换算为阅读进度，是 [xOfProgress] 的逆运算。 */
private fun progressOfX(x: Float, width: Float, inset: Float, reversed: Boolean): Float {
    val visible = ((x - inset) / (width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return if (reversed) 1f - visible else visible
}
