/** 阅读互动组件，统一进度定位、自动播放及控制栏显隐。 */
package com.example.photomanager.pages.photoviewer.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.example.photomanager.R
import com.example.photomanager.data.ReadingDirection
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoViewerControls(
    currentPage: Int,
    pageCount: Int,
    intervalSeconds: Int,
    readingDirection: ReadingDirection,
    isMenuVisible: Boolean,
    isVisible: Boolean,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onVisibilityChanged: (Boolean) -> Unit,
    onNextPage: suspend () -> Unit,
    onPageSelected: (Int) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var draggedPage by remember { mutableIntStateOf(0) }
    val nextPage by rememberUpdatedState(onNextPage)
    val changeVisibility by rememberUpdatedState(onVisibilityChanged)

    // 自动播放
    LaunchedEffect(isPlaying, intervalSeconds, isMenuVisible, isDragging) {
        if (!isPlaying || isMenuVisible || isDragging) return@LaunchedEffect
        while (isActive) {
            delay(intervalSeconds * 1000L)
            nextPage()
        }
    }
    // 自动隐藏控制组件
    LaunchedEffect(isVisible, isDragging) {
        if (!isVisible || isDragging) return@LaunchedEffect
        delay(3000L)
        changeVisibility(false)
    }

    Column(
        Modifier.fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
                    .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
            )
            // 在安全区内侧裁剪，阻止滑出动画绘制到底部留白中。
            .clipToBounds(),
        verticalArrangement = Arrangement.Bottom
    ) {
        AnimatedVisibility(
            visible = isVisible,
            modifier = Modifier.align(Alignment.End),
            enter = slideInHorizontally(tween(150)) { it },
            exit = slideOutHorizontally(tween(150)) { it }
        ) {
            FloatingActionButton(
                onClick = { onPlayClick() },
                modifier = Modifier.padding(16.dp).size(36.dp),
                shape = CircleShape,
                containerColor = Color(0xFFBDBDBD),
                contentColor = Color(0xFF303030)
            ) {
                if (isPlaying) Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.viewer_pause_auto_play))
                else Icon(Icons.Default.PlayArrow, stringResource(R.string.viewer_start_auto_play))
            }
        }
        AnimatedVisibility(
            visible = isVisible,
            enter = slideInVertically(tween(150)) { it },
            exit = slideOutVertically(tween(150)) { it }
        ) {
            val direction = if (readingDirection == ReadingDirection.RIGHT_TO_LEFT)
                LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                // 至少保留参考布局的 32dp，并为长页码及系统字体缩放预留完整宽度。
                val pageNumberWidth = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (pageCount.toString().length * 8).sp.toDp() + 8.dp
                }.coerceAtLeast(32.dp)
                Row(
                    Modifier.fillMaxWidth().height(48.dp).background(Color(0xFF424242))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.viewer_page_number, (if (isDragging) draggedPage else currentPage) + 1),
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(pageNumberWidth)
                    )
                    ReadingSeekBar(
                        currentPage = currentPage,
                        pageCount = pageCount,
                        reversed = readingDirection == ReadingDirection.RIGHT_TO_LEFT,
                        onPreviewPageChanged = { draggedPage = it },
                        onTrackingChanged = { isDragging = it },
                        onPageSelected = onPageSelected,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.viewer_page_number, pageCount),
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(pageNumberWidth)
                    )
                }
            }
        }
    }
}
