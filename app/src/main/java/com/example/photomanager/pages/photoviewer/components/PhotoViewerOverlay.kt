/** 阅读页顶部信息层，分别定位电量、页码和时间，并按实际刘海范围避让。 */
package com.example.photomanager.pages.photoviewer.components

import android.content.Context
import android.graphics.Rect
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.photomanager.data.PhotoViewerSettings
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun PhotoViewerOverlay(settings: PhotoViewerSettings, currentPage: Int, pageCount: Int) {
    val currentTime = if (settings.showClock) rememberCurrentTime().value else ""
    val battery = if (settings.showBattery) rememberBatteryStatus().value else null
    val textStyle = TextStyle(
        color = Color.White.copy(alpha = 0.7f),
        fontSize = 14.sp,
        shadow = Shadow(Color.Black, blurRadius = 2f)
    )
    PhotoViewerHeaderLayout(
        fullScreen = settings.fullScreen,
        battery = {
            battery?.let { BatteryIndicator(it, textStyle) }
        },
        progress = {
            if (settings.showProgress) Text("${currentPage + 1}/$pageCount", style = textStyle, maxLines = 1)
        },
        clock = {
            if (settings.showClock) Text(currentTime, style = textStyle, maxLines = 1)
        }
    )
}

/** 首次组合即提供真实值，避免空内容引起首次布局变化。 */
@Composable
private fun rememberCurrentTime(): State<String> {
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initialTime = remember(formatter) { formatter.format(Date()) }
    return produceState(initialValue = initialTime, key1 = formatter, key2 = lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = formatter.format(Date())
                delay(1_000L.milliseconds)
            }
        }
    }
}

@Composable
private fun rememberBatteryStatus(): State<BatteryStatus> {
    val context = LocalContext.current
    val batteryManager = remember(context) {
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val initialBattery = remember(batteryManager) {
        batteryManager.readBatteryStatus()
    }
    return produceState(initialValue = initialBattery, key1 = batteryManager, key2 = lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                value = batteryManager.readBatteryStatus()
                delay(1_000L.milliseconds)
            }
        }
    }
}

private data class BatteryStatus(val level: Int, val isCharging: Boolean)

/** 保留每秒主动读取，充电状态与真实电量一起更新。 */
private fun BatteryManager.readBatteryStatus() = BatteryStatus(
    level = getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
    isCharging = getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
)

@Composable
private fun BatteryIndicator(battery: BatteryStatus, textStyle: TextStyle) {
    val isAnimating = battery.isCharging && battery.level < 100
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val animatedLevel by produceState(initialValue = 0, key1 = isAnimating, key2 = lifecycle) {
        value = 0
        if (isAnimating) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    value = if (value >= 100) 0 else value + 2
                    delay(200L.milliseconds)
                }
            }
        }
    }
    // 动画仅改变图标填充，百分比始终显示系统真实电量。
    val fillLevel = if (isAnimating) animatedLevel else battery.level
    val isLowBattery = battery.level <= 15 && !battery.isCharging
    val color = if (isLowBattery) Color(0xFFF44336) else textStyle.color
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(20.dp, 14.dp)) {
            val top = size.height * 0.2f
            val height = size.height * 0.6f
            val width = size.width * 0.85f
            val border = 1.dp.toPx()
            drawRect(color, Offset(border, top), Size(width - border, height), style = Stroke(border))
            drawRect(color, Offset(width, top + height * 0.3f), Size(size.width - width, height * 0.4f))
            drawRect(color, Offset(border * 2, top + border * 2),
                Size((width - border * 4) * fillLevel / 100f, (height - border * 4).coerceAtLeast(0f)))
        }
        Text("${battery.level}%", style = textStyle.copy(color = color), maxLines = 1)
    }
}

/** 三个固定槽位独立测量，隐藏其中一项不改变其余项的对齐方式。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoViewerHeaderLayout(
    fullScreen: Boolean,
    battery: @Composable () -> Unit,
    progress: @Composable () -> Unit,
    clock: @Composable () -> Unit
) {
    val view = LocalView.current
    val statusBars = WindowInsets.statusBarsIgnoringVisibility
    val cutoutInsets = WindowInsets.displayCutout
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Box { battery() }
            Box { progress() }
            Box { clock() }
        }
    ) { measurables, constraints ->
        val margin = 12.dp.roundToPx()
        // 读取 Insets 使系统栏和旋转变化触发布局；全屏时不整行添加顶部安全区。
        val cutoutTop = cutoutInsets.getTop(this)
        val top = margin + if (fullScreen) 0 else statusBars.getTop(this)
        val width = constraints.maxWidth
        val children = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val bounds = calculateHeaderBounds(
            width, top, margin, cutoutTop,
            children[0], children[1], children[2],
            view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
        )
        layout(width, bounds.maxOf { it.bottom }) {
            children.forEachIndexed { index, child ->
                child.place(bounds[index].left, bounds[index].top)
            }
        }
    }
}

/** 按最终窗口坐标避让刘海，导航动画的平移不参与计算。 */
private fun calculateHeaderBounds(
    width: Int,
    top: Int,
    margin: Int,
    cutoutTop: Int,
    battery: Placeable,
    progress: Placeable,
    clock: Placeable,
    notches: List<Rect>
): List<Rect> {
    val children = listOf(battery, progress, clock)
    val x = intArrayOf(margin, (width - progress.width) / 2, width - margin - clock.width)
    val y = IntArray(3) { top }
    // 页面最终铺满窗口，直接按窗口原点计算；导航平移不参与内部避让。

    fun intersectingNotches(index: Int): List<Rect> {
        val child = children[index]
        val rect = Rect(
            x[index], top,
            x[index] + child.width, top + child.height
        )
        return notches.filter { Rect.intersects(rect, it) }
    }
    val progressNotches = intersectingNotches(1)
    if (progressNotches.isNotEmpty()) {
        // 中间信息被刘海遮挡时，各项仅按自己的遮挡情况下移。
        for (index in children.indices) {
            val overlaps = intersectingNotches(index)
            if (overlaps.isNotEmpty()) {
                y[index] = top + maxOf(
                    overlaps.maxOf { it.bottom }, cutoutTop
                )
            }
        }
    } else {
        // 与 GalleryHeader 一致：左右避让量共同定义可用区域，页码在其中重新居中。
        val leftPadding = intersectingNotches(0).maxOfOrNull { it.right } ?: 0
        val rightPadding = intersectingNotches(2).maxOfOrNull { width - it.left } ?: 0
        x[0] = leftPadding + margin
        x[1] = leftPadding + (width - leftPadding - rightPadding - progress.width) / 2
        x[2] = width - rightPadding - margin - clock.width
    }
    return children.mapIndexed { index, child ->
        Rect(x[index], y[index], x[index] + child.width, y[index] + child.height)
    }
}
