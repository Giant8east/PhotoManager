/** 原生整数进度条，反向阅读时同时镜像绘制与触摸坐标。 */
package com.example.photomanager.pages.photoviewer.components

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.SeekBar
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

// 当前应用使用平台 Material 主题，因此直接继承平台 SeekBar，无须增加 AppCompat 依赖。
internal class ReversibleSeekBar(context: Context) : SeekBar(context) {
    var reversed = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var isTracking = false
        private set
    var onPreviewPageChanged: (Int) -> Unit = {}
    var onPageSelected: (Int) -> Unit = {}
    var onTrackingChanged: (Boolean) -> Unit = {}
    private var isCancelled = false

    init {
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        val density = resources.displayMetrics.density
        fun pixels(dp: Int) = (dp * density).roundToInt()
        val teal = Color.rgb(0, 150, 136)
        val thumbColors = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0x61009688, teal)
        )
        val backgroundTrack = GradientDrawable().apply { setColor(0x4DFFFFFF) }
        val activeTrack = ClipDrawable(GradientDrawable().apply { setColor(teal) }, Gravity.LEFT, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf(backgroundTrack, activeTrack)).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.progress)
            for (index in 0..1) {
                setLayerHeight(index, pixels(2))
                setLayerGravity(index, Gravity.CENTER_VERTICAL or Gravity.FILL_HORIZONTAL)
            }
        }
        thumb = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(pixels(12), pixels(12))
            setColor(thumbColors)
        }
        thumbOffset = pixels(6)
        splitTrack = false
        background = null
        setPadding(pixels(10), 0, pixels(10), 0)
        setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isTracking = true
                onPreviewPageChanged(progress)
                onTrackingChanged(true)
            }
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                onPreviewPageChanged(progress)
                // 无触摸跟踪的用户变化来自无障碍或键盘，直接提交。
                if (!isTracking) onPageSelected(progress)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isTracking = false
                if (!isCancelled) onPageSelected(progress)
                onTrackingChanged(false)
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        val checkpoint = canvas.save()
        if (reversed) canvas.scale(-1f, 1f, width / 2f, height / 2f)
        super.onDraw(canvas)
        canvas.restoreToCount(checkpoint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        isCancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
        if (!reversed) return super.onTouchEvent(event)
        val mirrored = MotionEvent.obtain(event)
        return try {
            mirrored.setLocation(width - event.x, event.y)
            super.onTouchEvent(mirrored)
        } finally { mirrored.recycle() }
    }
}

@Composable
internal fun ReadingSeekBar(
    currentPage: Int,
    pageCount: Int,
    reversed: Boolean,
    onPreviewPageChanged: (Int) -> Unit,
    onTrackingChanged: (Boolean) -> Unit,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val preview by rememberUpdatedState(onPreviewPageChanged)
    val tracking by rememberUpdatedState(onTrackingChanged)
    val selected by rememberUpdatedState(onPageSelected)
    AndroidView(
        factory = { context ->
            ReversibleSeekBar(context).apply {
                this.onPreviewPageChanged = { preview(it) }
                this.onTrackingChanged = { tracking(it) }
                this.onPageSelected = { selected(it) }
            }
        },
        modifier = modifier.height(48.dp),
        onRelease = { view ->
            if (view.isTracking) tracking(false)
            view.setOnSeekBarChangeListener(null)
            view.onPreviewPageChanged = {}
            view.onTrackingChanged = {}
            view.onPageSelected = {}
        },
        update = { view ->
            // AndroidView 可能继承 Compose 的 RTL；方向只由本控件镜像一次。
            view.layoutDirection = View.LAYOUT_DIRECTION_LTR
            view.reversed = reversed
            view.max = pageCount - 1
            view.isEnabled = pageCount > 1
            if (!view.isTracking) view.progress = currentPage
        }
    )
}
