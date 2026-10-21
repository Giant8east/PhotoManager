/** 阅读页悬浮信息层，显示时钟和电量；两者的定时与数据收集都随自身显隐自动起停。 */
package com.example.photomanager.pages.photoviewer

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.UNKNOWN_BATTERY_LEVEL
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 时钟只显示到分钟，刷新周期与显示精度保持一致。 */
private val ClockFormatter = DateTimeFormatter.ofPattern("HH:mm")

@Composable
fun PhotoViewerOverlay(
    settings: PhotoViewerSettings,
    batteryLevel: Flow<Int>,
    modifier: Modifier = Modifier
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (settings.showClock) ViewerClock()
        if (settings.showBattery) ViewerBattery(batteryLevel)
    }
}

@Composable
private fun ViewerClock() {
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            val now = LocalTime.now()
            currentTime = now
            // 对齐到下一分钟刷新，既不每秒唤醒，也不会出现跨分钟的滞后。
            delay(60_000L - now.second * 1000L - now.nano / 1_000_000L)
        }
    }
    Text(currentTime.format(ClockFormatter), color = Color.White)
}

@Composable
private fun ViewerBattery(batteryLevel: Flow<Int>) {
    // 组合进入时开始收集、离开时取消，广播注册与注销由 Data 层随收集生命周期配对。
    val level by batteryLevel.collectAsState(initial = UNKNOWN_BATTERY_LEVEL)
    if (level >= 0) Text("电量 $level%", color = Color.White)
}
