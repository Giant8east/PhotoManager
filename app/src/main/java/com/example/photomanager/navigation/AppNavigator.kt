/** 应用轻量导航器，维护 Composable 页面栈并提供统一跳转入口。 */
package com.example.photomanager.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf

class AppNavigator {
    private val pages = mutableStateListOf<@Composable () -> Unit>()

    fun pushTo(page: @Composable () -> Unit) {
        pages += page
    }

    fun pop() {
        if (pages.size > 1) pages.removeLast()
    }

    fun pagesEmpty(): Boolean = pages.isEmpty()

    @Composable
    fun render() {
        pages.lastOrNull()?.invoke()
    }
}
