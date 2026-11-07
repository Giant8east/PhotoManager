/** 应用轻量导航器，维护 Composable 页面栈并提供统一跳转入口。 */
package com.example.photomanager.navigation

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
class AppNavigator(
    // 构造函数接收一个起始页面
    private val initialPage: @Composable (AppNavigator) -> Unit
) {
    // 一个页面栈条目
    private class PageEntry(
        val id: Long,   // 导航条目的唯一身份
        val useSafeArea: Boolean,   // 为false时绘制到完整窗口，不使用普通页面的安全区 padding
        val transition: PageTransition,
        val content: @Composable () -> Unit
    ) {
        val viewModelStore = ViewModelStore()
    }

    private val pages = mutableStateListOf<PageEntry>() // 页面栈,类型是 Compose 能观察到变化的可变列表
    private var nextPageId = 0L
    private var isTransitioning by mutableStateOf(false)
    private var pendingPop = false

    init {
        pages += PageEntry(nextPageId++, true, HorizontalPageTransition) { initialPage(this) }
    }

    fun pushTo(
        useSafeArea: Boolean = true,
        transition: PageTransition = HorizontalPageTransition,
        page: @Composable () -> Unit
    ) {
        if (isTransitioning) return
        isTransitioning = true
        pages += PageEntry(nextPageId++, useSafeArea, transition, page)
    }

    fun pop() {
        if (isTransitioning) {
            pendingPop = true
            return
        }
        if (pages.size <= 1) return
        isTransitioning = true
        pages.removeAt(pages.lastIndex)
    }

    @SuppressLint("ReturnFromAwaitPointerEventScope")
    @Composable
    fun render() {
        val currentPage = pages.last()
        val parentOwner = checkNotNull(LocalViewModelStoreOwner.current)
        DisposableEffect(this) {
            onDispose { pages.forEach { it.viewModelStore.clear() } }
        }
        AnimatedContent(
            targetState = currentPage,
            modifier = Modifier.fillMaxSize(),
            contentKey = { it.id },
            transitionSpec = {
                val isPush = targetState.id > initialState.id
                val transition = if (isPush) targetState.transition else initialState.transition
                transition.create(this, isPush)
            }
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                LaunchedEffect(transition) {
                    var sawRunning = false
                    snapshotFlow { transition.currentState != transition.targetState }
                        .collect { running ->
                            if (running) sawRunning = true
                            if (sawRunning && !running) {
                                isTransitioning = false
                                if (pendingPop) {
                                    pendingPop = false
                                    pop()
                                }
                            }
                        }
                }

                val owner = remember(page, parentOwner) {
                    object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                        override val viewModelStore = page.viewModelStore
                        override val defaultViewModelProviderFactory =
                            (parentOwner as HasDefaultViewModelProviderFactory).defaultViewModelProviderFactory
                        override val defaultViewModelCreationExtras = (parentOwner as HasDefaultViewModelProviderFactory).defaultViewModelCreationExtras
                    }
                }
                DisposableEffect(page) {
                    // 入栈覆盖时保留实例，出栈转场结束后才清理任务。
                    onDispose { if (page !in pages) page.viewModelStore.clear() }
                }
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    PageContainer(useSafeArea = page.useSafeArea) { page.content() }
                }
                if (transition.currentState != transition.targetState) {
                    // 只消费指针事件，不使用 clickable，避免转场期间出现水波纹。
                    Box(Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    })
                }
            }
        }
    }
}
