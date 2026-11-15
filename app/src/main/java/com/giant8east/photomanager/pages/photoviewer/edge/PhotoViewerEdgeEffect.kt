/** 保存圆弧拉动状态与释放动画，不判断相册边界或移动页面。 */
package com.giant8east.photomanager.pages.photoviewer.edge

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.abs

internal enum class PhotoViewerEdge {
    LEFT, RIGHT, TOP, BOTTOM;

    val isVertical get() = this == TOP || this == BOTTOM
    val isTrailing get() = this == RIGHT || this == BOTTOM
}

internal enum class PhotoViewerEdgePhase { IDLE, PULL, PULL_DECAY, ABSORB, RECEDE }

internal class PhotoViewerEdgeEffect(private val scope: CoroutineScope) {
    var phase by mutableStateOf(PhotoViewerEdgePhase.IDLE)
        private set
    var distance by mutableFloatStateOf(0f)
        private set
    var shrink by mutableFloatStateOf(0f)
        private set
    var touchPosition by mutableStateOf(Offset.Zero)
        private set
    var alpha by mutableFloatStateOf(1f)
        private set
    private var releaseAnimation: Job? = null
    private var positionAnimation: Job? = null
    private var targetPosition = Offset.Zero

    /** 手指接管时保留当前外观，后续反向拖动仍可收回尚未消失的边缘。 */
    fun begin() {
        releaseAnimation?.cancel()
        if (distance > 0f) {
            phase = PhotoViewerEdgePhase.PULL
            scheduleDecay()
        } else {
            finish()
        }
    }

    fun pull(delta: Float) {
        releaseAnimation?.cancel()
        distance = (distance + delta).coerceAtLeast(0f)
        if (distance == 0f) {
            finish()
            return
        }
        // 接管衰减或回缩时逐步恢复强度，不在首帧突然恢复满幅圆弧。
        val recovery = (delta / 60f).coerceIn(0f, 1f)
        if (phase == PhotoViewerEdgePhase.IDLE) {
            shrink = 1f
            alpha = 1f
        } else {
            shrink += (1f - shrink) * recovery
            alpha += (1f - alpha) * recovery
        }
        phase = PhotoViewerEdgePhase.PULL
        scheduleDecay()
    }

    private fun scheduleDecay() {
        releaseAnimation = scope.launch {
            delay(167)
            phase = PhotoViewerEdgePhase.PULL_DECAY
            fade(2000)
            recede()
        }
    }

    /** 撞边按保存的起始速度增强圆弧，再接回缩，不伪装成一次手指拉动。 */
    fun absorb(velocity: Float, viewportDepth: Float) {
        releaseAnimation?.cancel()
        phase = PhotoViewerEdgePhase.ABSORB
        val strength = (abs(velocity) / 8000f).coerceIn(0f, 1f)
        val targetDistance = viewportDepth * (0.08f + 0.16f * strength)
        val startDistance = distance
        val startScale = shrink
        val startAlpha = alpha
        releaseAnimation = scope.launch {
            animate(0f, 1f, animationSpec = tween((100 + 100 * strength).toInt())) { progress, _ ->
                distance = startDistance + (maxOf(startDistance, targetDistance) - startDistance) * progress
                shrink = startScale + (1f - startScale) * progress
                alpha = startAlpha + (1f + strength - startAlpha) * progress
            }
            recede()
        }
    }

    /** 反向距离先抵消当前边缘拉动，返回实际消费量，不影响另一个轴。 */
    fun retract(amount: Float): Float {
        val consumed = min(amount, distance)
        if (consumed > 0f) pull(-consumed)
        return consumed
    }

    /** 从当前显示比例连续回缩，松手时不重置圆弧。 */
    fun release() {
        if (phase != PhotoViewerEdgePhase.PULL && phase != PhotoViewerEdgePhase.PULL_DECAY) return
        releaseAnimation?.cancel()
        phase = PhotoViewerEdgePhase.RECEDE
        releaseAnimation = scope.launch {
            recede()
        }
    }

    private suspend fun recede() {
        phase = PhotoViewerEdgePhase.RECEDE
        if (shrink > 0f && alpha > 0f) fade(600)
        finish()
    }

    private suspend fun fade(durationMillis: Int) {
        val startScale = shrink
        val startAlpha = alpha
        animate(1f, 0f, animationSpec = tween(durationMillis, easing = EdgeDecelerate)) { value, _ ->
            shrink = startScale * value
            alpha = startAlpha * value
        }
    }

    /** 触点沿边平滑追随；不可见时直接定位，避免初次拉出从旧位置滑来。 */
    fun moveTo(position: Offset) {
        targetPosition = position
        if (phase == PhotoViewerEdgePhase.IDLE) {
            positionAnimation?.cancel()
            touchPosition = position
        } else if (positionAnimation?.isActive != true) {
            positionAnimation = scope.launch {
                var previousTime = 0L
                while ((targetPosition - touchPosition).getDistance() > 0.1f) {
                    val time = withFrameNanos { it }
                    // 按时间折算追随量，60Hz 时每帧靠近一半；输入只更新目标，不重启动画。
                    val elapsed = if (previousTime == 0L) 16.666667f else (time - previousTime) / 1_000_000f
                    previousTime = time
                    val fraction = 1f - Math.pow(0.5, (elapsed / 16.666667f).toDouble()).toFloat()
                    touchPosition += (targetPosition - touchPosition) * fraction
                }
                touchPosition = targetPosition
            }
        }
    }

    private fun finish() {
        phase = PhotoViewerEdgePhase.IDLE
        distance = 0f
        shrink = 1f
        alpha = 1f
    }

    fun dispose() {
        releaseAnimation?.cancel()
        positionAnimation?.cancel()
        finish()
    }
}

private val EdgeDecelerate = Easing { fraction -> 1f - (1f - fraction) * (1f - fraction) }
