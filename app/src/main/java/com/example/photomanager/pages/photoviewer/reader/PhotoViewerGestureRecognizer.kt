/** 在固定视口识别点击、长按、拖动与缩放，只报告操作而不决定页面行为。 */
package com.example.photomanager.pages.photoviewer.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity

internal class PhotoViewerGestureRecognizer(
    private val onDown: (Offset, Float) -> Unit,
    private val onPosition: (Offset) -> Unit,
    private val onDrag: (Offset) -> Unit,
    private val onScale: (Offset, Float, Offset) -> Unit,
    private val onUp: (Velocity) -> Unit,
    private val onDoubleTap: (Offset) -> Unit
) {
    fun Modifier.recognize(onTap: () -> Unit, onLongPress: () -> Unit): Modifier =
        pointerInput(this@PhotoViewerGestureRecognizer, onTap, onLongPress) {
            detectTapGestures(
                onTap = { onTap() },
                onLongPress = { onLongPress() },
                onDoubleTap = onDoubleTap
            )
        }.pointerInput(this@PhotoViewerGestureRecognizer) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                onDown(down.position, viewConfiguration.touchSlop)
                var hasMultiplePointers = false
                var isDragging = false
                var accumulatedPan = Offset.Zero
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                var releaseVelocity = Velocity.Zero
                try {
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) break
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                        tracker.addPosition(pointer.uptimeMillis, pointer.position)
                        val pressed = event.changes.filter { it.pressed }
                        // 双指操作后剩余的单指也不参与翻页，直到全部松手。
                        if (pressed.size > 1) hasMultiplePointers = true
                        onPosition(event.changes.first().position)
                        val pan = event.calculatePan()
                        if (hasMultiplePointers) {
                            if (pressed.size > 1) {
                                onScale(
                                    event.calculateCentroid(useCurrent = false),
                                    event.calculateZoom(),
                                    pan
                                )
                            }
                            event.changes.forEach { it.consume() }
                        } else {
                            accumulatedPan += pan
                            var drag = pan
                            if (!isDragging && accumulatedPan.getDistance() > viewConfiguration.touchSlop) {
                                isDragging = true
                                drag =
                                    accumulatedPan * (1f - viewConfiguration.touchSlop / accumulatedPan.getDistance())
                            }
                            if (isDragging) {
                                onDrag(drag)
                                event.changes.filter { it.positionChanged() }
                                    .forEach { it.consume() }
                            }
                        }
                        if (event.changes.none { it.pressed } && isDragging && !hasMultiplePointers) {
                            releaseVelocity = tracker.calculateVelocity()
                        }
                    } while (event.changes.any { it.pressed })
                } finally {
                    onUp(releaseVelocity)
                }
            }
        }
}
