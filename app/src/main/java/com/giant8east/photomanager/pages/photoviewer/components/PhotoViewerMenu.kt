/** 图片浏览设置菜单，负责临时编辑阅读配置并提交用户确认的结果。 */
package com.giant8east.photomanager.pages.photoviewer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.*
import com.giant8east.photomanager.data.settings.PageScale
import com.giant8east.photomanager.data.settings.PhotoViewerSettings
import com.giant8east.photomanager.data.settings.ReadingDirection
import com.giant8east.photomanager.data.settings.ScreenOrientation
import com.giant8east.photomanager.data.settings.ViewerSettingOption
import kotlin.math.roundToInt

/** Slider 的 thumb 与 track 槽统一高度，两个槽的内容都在槽内垂直居中，避免圆点相对轨道偏移。 */
private val SliderSlotHeight = 16.dp

@OptIn(ExperimentalMaterial3Api::class)
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
                // 与阅读页进度条同一种外观：2dp 细轨道加 12dp 圆点，只替换 thumb 和 track 两个槽。
                Slider(
                    value = editingSettings.autoPageSeconds.toFloat(),
                    onValueChange = {
                        editingSettings = editingSettings.copy(
                            autoPageSeconds = it.roundToInt()
                        )
                    },
                    valueRange = 1f..60f,
                    thumb = {
                        // 与 track 槽保持同一高度，圆点在槽内居中，两者中线才会重合。
                        Box(Modifier.height(SliderSlotHeight), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        }
                    },
                    track = { state ->
                        val range = state.valueRange
                        val span = range.endInclusive - range.start
                        val fraction =
                            if (span <= 0f) 0f else ((state.value - range.start) / span).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(SliderSlotHeight),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f))
                            ) {
                                Box(
                                    Modifier
                                        .align(
                                            if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
                                                Alignment.CenterEnd
                                            } else {
                                                Alignment.CenterStart
                                            }
                                        )
                                        .fillMaxWidth(fraction)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.primary)
                                )
                            }
                        }
                    }
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
