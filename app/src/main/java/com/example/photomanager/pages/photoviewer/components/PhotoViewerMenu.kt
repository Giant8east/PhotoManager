/** 图片浏览设置菜单，负责临时编辑阅读配置并提交用户确认的结果。 */
package com.example.photomanager.pages.photoviewer.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.photomanager.R
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
        title = { Text(stringResource(R.string.viewer_settings)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())) {
                SettingMenuButton(
                    stringResource(R.string.viewer_screen_orientation),
                    editingSettings.screenOrientation,
                    ScreenOrientation.entries
                ) { editingSettings = editingSettings.copy(screenOrientation = it) }
                SettingMenuButton(
                    stringResource(R.string.viewer_reading_direction),
                    editingSettings.readingDirection,
                    ReadingDirection.entries
                ) { editingSettings = editingSettings.copy(readingDirection = it) }
                if (editingSettings.readingDirection == ReadingDirection.TOP_TO_BOTTOM) {
                    Text(stringResource(R.string.viewer_continuous_reading_hint), modifier = Modifier.padding(vertical = 12.dp))
                } else {
                    SettingMenuButton(
                        stringResource(R.string.viewer_page_scale),
                        editingSettings.pageScale,
                        PageScale.entries
                    ) { editingSettings = editingSettings.copy(pageScale = it) }
                }
                Text(stringResource(R.string.viewer_auto_page_interval, editingSettings.autoPageSeconds))
                Slider(
                    value = editingSettings.autoPageSeconds.toFloat(),
                    onValueChange = {
                        editingSettings = editingSettings.copy(autoPageSeconds = it.toInt())
                    },
                    valueRange = 1f..60f,
                    steps = 58
                )
                SettingSwitch(stringResource(R.string.viewer_keep_screen_on), editingSettings.keepScreenOn) {
                    editingSettings = editingSettings.copy(keepScreenOn = it)
                }
                SettingSwitch(stringResource(R.string.viewer_show_clock), editingSettings.showClock) {
                    editingSettings = editingSettings.copy(showClock = it)
                }
                SettingSwitch(stringResource(R.string.viewer_show_progress), editingSettings.showProgress) {
                    editingSettings = editingSettings.copy(showProgress = it)
                }
                SettingSwitch(stringResource(R.string.viewer_show_battery), editingSettings.showBattery) {
                    editingSettings = editingSettings.copy(showBattery = it)
                }
                SettingSwitch(stringResource(R.string.viewer_show_page_gap), editingSettings.showPageGap) {
                    editingSettings = editingSettings.copy(showPageGap = it)
                }
                SettingSwitch(stringResource(R.string.viewer_volume_key_paging), editingSettings.volumeKeyPaging) {
                    editingSettings = editingSettings.copy(volumeKeyPaging = it)
                }
                SettingSwitch(stringResource(R.string.viewer_full_screen), editingSettings.fullScreen) {
                    editingSettings = editingSettings.copy(fullScreen = it)
                }
                SettingSwitch(
                    stringResource(R.string.viewer_custom_brightness),
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
        confirmButton = { TextButton(onClick = { onConfirm(editingSettings) }) { Text(stringResource(R.string.viewer_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.viewer_cancel)) } }
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
                Text(stringResource(value.titleRes))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.titleRes)) },
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
