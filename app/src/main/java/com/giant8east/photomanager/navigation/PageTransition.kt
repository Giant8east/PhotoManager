package com.giant8east.photomanager.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith

/** 页面只声明转场策略，同一策略负责其进入和返回动画。 */
interface PageTransition {
    fun create(scope: AnimatedContentTransitionScope<*>, isPush: Boolean): ContentTransform
}

/** 保留现有的 300 毫秒水平转场。 */
object HorizontalPageTransition : PageTransition {
    override fun create(scope: AnimatedContentTransitionScope<*>, isPush: Boolean): ContentTransform =
        with(scope) {
            val direction = if (isPush) AnimatedContentTransitionScope.SlideDirection.Left
            else AnimatedContentTransitionScope.SlideDirection.Right
            slideIntoContainer(direction, tween(300)) togetherWith
                slideOutOfContainer(direction, tween(300))
        }
}
