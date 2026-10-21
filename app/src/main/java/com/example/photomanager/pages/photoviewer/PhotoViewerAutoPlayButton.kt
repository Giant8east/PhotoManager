/** 自动播放按钮，自维护播放状态与计时，通过页面提供的动作翻页。 */
package com.example.photomanager.pages.photoviewer

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.res.painterResource
import com.example.photomanager.R
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoViewerAutoPlayButton(
    currentPage: Int,
    pageCount: Int,
    intervalSeconds: Int,
    isMenuVisible: Boolean,
    onNextPage: suspend () -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    val nextPage by rememberUpdatedState(onNextPage)

    LaunchedEffect(isPlaying, currentPage, pageCount, intervalSeconds, isMenuVisible) {
        if (!isPlaying || isMenuVisible) return@LaunchedEffect
        if (currentPage == pageCount - 1) {
            isPlaying = false
            return@LaunchedEffect
        }
        delay(intervalSeconds * 1000L)
        nextPage()
    }

    Box(Modifier.fillMaxSize()) {
        FloatingActionButton(
            onClick = { isPlaying = !isPlaying },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    WindowInsets.systemBarsIgnoringVisibility
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                )
                .padding(16.dp)
                // 尺寸放在外边距和安全区之后，只约束按钮本体。
                .size(36.dp),
            shape = CircleShape,
            containerColor = Color(0xFFBDBDBD),
            contentColor = Color(0xFF303030)
        ) {
            if (isPlaying) {
                Icon(painterResource(R.drawable.ic_pause), contentDescription = "暂停自动播放")
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = "开始自动播放")
            }
        }
    }
}
