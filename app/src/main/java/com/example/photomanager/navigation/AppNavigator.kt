/** 应用轻量导航器，维护 Composable 页面栈并提供统一跳转入口。 */
package com.example.photomanager.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

class AppNavigator {
    private class PageEntry(val id: Long, val content: @Composable () -> Unit)

    private val pages = mutableStateListOf<PageEntry>()
    private var nextPageId = 0L

    fun pushTo(page: @Composable () -> Unit) {
        pages += PageEntry(nextPageId++, page)
    }

    fun pop() {
        if (pages.size > 1) pages.removeLast()
    }

    fun pagesEmpty(): Boolean = pages.isEmpty()

    @Composable
    fun render() {
        val currentPage = pages.lastOrNull() ?: return
        AnimatedContent(
            targetState = currentPage,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.id },
            transitionSpec = {
                // 使用页面身份判断方向，返回后再次入栈也能正确识别。
                val direction = if (targetState.id > initialState.id) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                }
                slideIntoContainer(direction, tween(300)) togetherWith
                    slideOutOfContainer(direction, tween(300))
            },
            label = "页面转场"
        ) { page ->
            page.content()
        }
    }
}
