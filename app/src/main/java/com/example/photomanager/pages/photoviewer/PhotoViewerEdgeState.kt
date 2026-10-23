package com.example.photomanager.pages.photoviewer

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/** 阅读边缘状态，统一接收主动越界通知与 Pager 未消费的拖动距离。 */
internal class PhotoViewerEdgeState(val isVertical: Boolean) : NestedScrollConnection {
    var pulse by mutableIntStateOf(0)
        private set
    var isEndBoundary by mutableStateOf(true)
        private set

    /** 主动翻页只通知越界方向，不在这里判断相册索引或修改播放状态。 */
    fun showBoundary(isEnd: Boolean) {
        isEndBoundary = isEnd
        pulse++
    }

    var distance by mutableFloatStateOf(0f)
        private set
    var isPulling by mutableStateOf(false)
        private set
    var touchPosition by mutableStateOf(Offset.Zero)

    fun begin() {
        distance = 0f
        isPulling = true
    }

    fun release() { isPulling = false }


    /** 反向拖动时先收回已拉出的边缘距离，剩余拖动再交给 Pager。 */
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.Drag || distance == 0f) return Offset.Zero
        val delta = if (isVertical) available.y else available.x
        if (delta * distance >= 0f) return Offset.Zero
        val consumed = if (abs(delta) <= abs(distance)) delta else -distance
        distance += consumed
        return if (isVertical) Offset(0f, consumed) else Offset(consumed, 0f)
    }

    /** 接收 Pager 未消费的拖动距离，用于拉出边缘反馈。 */
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.Drag) return Offset.Zero
        val delta = if (isVertical) available.y else available.x
        if (delta == 0f) return Offset.Zero
        distance += delta
        return if (isVertical) Offset(0f, delta) else Offset(delta, 0f)
    }

    /** 松手开始惯性滚动前释放边缘反馈，不消耗 Pager 的滚动速度。 */
    override suspend fun onPreFling(available: Velocity): Velocity {
        release()
        return Velocity.Zero
    }
}

