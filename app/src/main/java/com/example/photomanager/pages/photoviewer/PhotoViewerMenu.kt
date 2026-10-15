/** 图片浏览设置菜单，负责临时编辑阅读配置并提交用户确认的结果。 */
package com.example.photomanager.pages.photoviewer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.*

@Composable
fun PhotoViewerMenu(
    settings: PhotoViewerSettings,
    onConfirm: (PhotoViewerSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var editingSettings by remember(settings) { mutableStateOf(settings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("阅读设置") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())) {
                SettingMenuButton(
                    "屏幕方向",
                    editingSettings.screenOrientation,
                    ScreenOrientation.entries
                ) { editingSettings = editingSettings.copy(screenOrientation = it) }
                SettingMenuButton(
                    "阅读方向",
                    editingSettings.readingDirection,
                    ReadingDirection.entries
                ) { editingSettings = editingSettings.copy(readingDirection = it) }
                SettingMenuButton(
                    "页面缩放",
                    editingSettings.pageScale,
                    PageScale.entries
                ) { editingSettings = editingSettings.copy(pageScale = it) }
                SettingSwitch("自动翻页", editingSettings.autoPageEnabled) {
                    editingSettings = editingSettings.copy(autoPageEnabled = it)
                }
                Text("自动翻页间隔（秒）：${editingSettings.autoPageSeconds}")
                Slider(
                    value = editingSettings.autoPageSeconds.toFloat(),
                    onValueChange = {
                        editingSettings = editingSettings.copy(autoPageSeconds = it.toInt())
                    },
                    valueRange = 1f..60f,
                    steps = 58
                )
                SettingSwitch("屏幕常亮", editingSettings.keepScreenOn) {
                    editingSettings = editingSettings.copy(keepScreenOn = it)
                }
                SettingSwitch("显示时钟", editingSettings.showClock) {
                    editingSettings = editingSettings.copy(showClock = it)
                }
                SettingSwitch("显示进度", editingSettings.showProgress) {
                    editingSettings = editingSettings.copy(showProgress = it)
                }
                SettingSwitch("显示电量", editingSettings.showBattery) {
                    editingSettings = editingSettings.copy(showBattery = it)
                }
                SettingSwitch("显示页面间隔", editingSettings.showPageGap) {
                    editingSettings = editingSettings.copy(showPageGap = it)
                }
                SettingSwitch("使用音量键翻页", editingSettings.volumeKeyPaging) {
                    editingSettings = editingSettings.copy(volumeKeyPaging = it)
                }
                SettingSwitch("全屏", editingSettings.fullScreen) {
                    editingSettings = editingSettings.copy(fullScreen = it)
                }
                SettingSwitch(
                    "自定义屏幕亮度",
                    editingSettings.customBrightnessEnabled
                ) { editingSettings = editingSettings.copy(customBrightnessEnabled = it) }
                if (editingSettings.customBrightnessEnabled) {
                    Slider(
                        value = editingSettings.customBrightness,
                        onValueChange = {
                            editingSettings = editingSettings.copy(customBrightness = it)
                        },
                        valueRange = 0.1f..1f
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(editingSettings) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun <T : ViewerSettingOption> SettingMenuButton(
    label: String,
    value: T,
    options: List<T>,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label)
                Text(value.title)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.title) },
                    onClick = { onSelected(option); expanded = false })
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
