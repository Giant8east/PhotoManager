/** 阅读页顶部信息层，每秒读取并显示系统时间与当前电量。 */
package com.example.photomanager.pages.photoviewer

import android.content.Context
import android.os.BatteryManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.PhotoViewerSettings
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoViewerOverlay(settings: PhotoViewerSettings) {
    val currentTime by produceState(initialValue = "") {
        while (true) {
            value = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            delay(1_000L)
        }
    }
    val context = LocalContext.current
    val batteryLevel by produceState(initialValue = -1, key1 = context) {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        while (true) {
            value = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            delay(1_000L)
        }
    }
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout))
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (settings.showClock) Text(currentTime, color = Color.White)
            if (settings.showBattery && batteryLevel >= 0) Text("电量 $batteryLevel%", color = Color.White)
        }
    }
}