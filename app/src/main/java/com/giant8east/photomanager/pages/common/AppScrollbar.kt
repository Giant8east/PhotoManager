/** 全 App 统一的 LazyColumn 侧边滚动条样式。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import my.nanihadesuka.compose.LazyColumnScrollbar
import my.nanihadesuka.compose.ScrollbarSettings

@Composable
fun AppLazyColumnScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val thumbColor = MaterialTheme.colorScheme.primary
    val canScroll = state.canScrollForward || state.canScrollBackward
    LazyColumnScrollbar(
        state = state,
        modifier = modifier,
        settings = ScrollbarSettings.Default.copy(
            enabled = canScroll,
            thumbThickness = 4.dp,
            scrollbarPadding = 2.dp,
            thumbShape = RoundedCornerShape(2.dp),
            thumbSelectedColor = thumbColor,
            thumbUnselectedColor = thumbColor.copy(alpha = 0.65f),
            hideDelayMillis = 800,
            durationAnimationMillis = 250
        ),
        content = content
    )
}
