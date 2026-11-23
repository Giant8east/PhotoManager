package com.giant8east.photomanager.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import com.giant8east.photomanager.ui.theme.LocalStatusBarColor

/** 每个导航条目独占完整窗口，背景、安全区和正文作为整体参与转场。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageContainer(useSafeArea: Boolean, content: @Composable () -> Unit) {
    // 获取不随系统栏临时显隐而消失的系统栏 Insets，合并刘海缺口避让范围
    val rawInsets = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val orientation = LocalConfiguration.current.orientation

    // 触发：Android 12 进出全屏阅读页切换系统栏时，ROM 可能瞬时回传 0 高度 insets 导致页面上下跳动；
    // 按屏幕方向记住已测得的非零安全区，忽略显隐过渡期间的瞬时 0 抖动。
    var stableTop by remember(orientation) { mutableIntStateOf(0) }
    var stableBottom by remember(orientation) { mutableIntStateOf(0) }
    var stableLeft by remember(orientation) { mutableIntStateOf(0) }
    var stableRight by remember(orientation) { mutableIntStateOf(0) }

    val topPx = rawInsets.getTop(density)
    val bottomPx = rawInsets.getBottom(density)
    val leftPx = rawInsets.getLeft(density, layoutDirection)
    val rightPx = rawInsets.getRight(density, layoutDirection)
    if (topPx > 0) stableTop = topPx
    if (bottomPx > 0) stableBottom = bottomPx
    if (leftPx > 0) stableLeft = leftPx
    if (rightPx > 0) stableRight = rightPx

    val safeInsets = WindowInsets(
        left = if (leftPx > 0) leftPx else stableLeft,
        top = if (topPx > 0) topPx else stableTop,
        right = if (rightPx > 0) rightPx else stableRight,
        bottom = if (bottomPx > 0) bottomPx else stableBottom
    )

    // 所有页面始终占满导航窗口
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 普通页面顶部安全区单独绘制主题次要色
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
