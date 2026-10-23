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
        distance += delta
        shrink = 1f
    }

    /** 从当前显示比例连续回缩，松手时不重置圆弧。 */
    fun release() {
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
