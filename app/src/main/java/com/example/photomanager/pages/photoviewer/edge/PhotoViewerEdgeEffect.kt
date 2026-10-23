/** 保存圆弧拉动状态与释放动画，不判断相册边界或移动页面。 */
package com.example.photomanager.pages.photoviewer.edge

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

internal enum class PhotoViewerEdge {
    LEFT, RIGHT, TOP, BOTTOM;

    val isVertical get() = this == TOP || this == BOTTOM
    val isTrailing get() = this == RIGHT || this == BOTTOM
}

internal class PhotoViewerEdgeEffect(private val scope: CoroutineScope) {
    var distance by mutableFloatStateOf(0f)
        private set
    var shrink by mutableFloatStateOf(0f)
        private set
    var touchPosition by mutableStateOf(Offset.Zero)
    private var releaseAnimation: Job? = null

    /** 新拉动接管时取消旧回缩，避免两个动画争用状态。 */
    fun begin() {
        releaseAnimation?.cancel()
        distance = 0f
        shrink = 1f
    }

    fun pull(delta: Float) {
        releaseAnimation?.cancel()
        distance = (distance + delta).coerceAtLeast(0f)
        shrink = 1f
    }

    /** 反向距离先抵消当前边缘拉动，返回实际消费量，不影响另一个轴。 */
    fun retract(amount: Float): Float {
        val consumed = min(amount, distance)
        if (consumed > 0f) pull(-consumed)
        return consumed
    }

    /** 从当前显示比例连续回缩，松手时不重置圆弧。 */
    fun release() {
        if (distance == 0f) return
        releaseAnimation?.cancel()
        releaseAnimation = scope.launch {
            animate(shrink, 0f, animationSpec = tween(600, easing = EdgeDecelerate)) { value, _ ->
                shrink = value
            }
            distance = 0f
        }
    }

    fun dispose() { releaseAnimation?.cancel() }
}

private val EdgeDecelerate = Easing { fraction -> 1f - (1f - fraction) * (1f - fraction) }
