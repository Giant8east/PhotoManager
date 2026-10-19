package com.example.photomanager.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.photomanager.ui.theme.LocalStatusBarColor

/** 每个导航条目独占完整窗口，背景、安全区和正文作为整体参与转场。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageContainer(useSafeArea: Boolean, content: @Composable () -> Unit) {
    // 计算避让范围
    val safeInsets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    // 所有页面始终占满导航窗口
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部安全区背景色
        if (useSafeArea) {
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .windowInsetsTopHeight(safeInsets)
                    .background(LocalStatusBarColor.current)
            )
        }
        Box(
            // 给页面上下添加安全区 padding
            if (useSafeArea) Modifier.fillMaxSize().windowInsetsPadding(safeInsets)
            else Modifier.fillMaxSize()
        ) { content() }
    }
}
