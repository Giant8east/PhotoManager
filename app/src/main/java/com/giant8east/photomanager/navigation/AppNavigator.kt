/** 应用轻量导航器，维护 Composable 页面栈并提供统一跳转入口。 */
package com.giant8east.photomanager.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/** 指示当前页面是否处于导航入场/退场滑动动画中，供重型列表或缩略图网格延后首帧挂载。 */
val LocalIsNavTransitioning = compositionLocalOf { false }

class AppNavigator(
    // 构造函数接收一个起始页面
    private val initialPage: @Composable (AppNavigator) -> Unit
) {
    // 一个页面栈条目
    private class PageEntry(
        val id: Long,   // 导航条目的唯一身份
        val useSafeArea: Boolean,   // 为 false 时绘制到完整窗口，不使用普通页面的安全区 padding
        val content: @Composable () -> Unit
    ) {
        val viewModelStore = ViewModelStore()
    }

    private val pages = mutableStateListOf<PageEntry>() // 页面栈，类型是 Compose 能观察到变化的可变列表
    private var nextPageId = 0L
    // 是否正在执行页面转场：入栈/出栈时先标记，由外部唯一 transition.isRunning 同步复位（无动画 0x 时不会死锁）
    private var isTransitioning = false
    private var pendingPop = false

    init {
        pages += PageEntry(nextPageId++, true) { initialPage(this) }
    }

    fun pushTo(
        useSafeArea: Boolean = true,
        page: @Composable () -> Unit
    ) {
        if (isTransitioning) return
        isTransitioning = true
        pages += PageEntry(nextPageId++, useSafeArea, page)
    }

    fun pop() {
        if (isTransitioning) {
            if (pages.size > 1) pendingPop = true
            return
        }
        if (pages.size <= 1) return
        isTransitioning = true
        pages.removeAt(pages.lastIndex)
    }

    @Composable
    fun render() {
        val currentPage = pages.last()
        val parentOwner = checkNotNull(LocalViewModelStoreOwner.current)
        val saveableStateHolder = rememberSaveableStateHolder()
        DisposableEffect(this) {
            onDispose { pages.forEach { it.viewModelStore.clear() } }
        }

        val transition = updateTransition(targetState = currentPage, label = "nav")
        // 首帧组合时 transition.isRunning 尚未置 true，必须同时检查 currentState != targetState，
        // 否则新页面首帧会误判为“不在转场中”，导致延后挂载失效。
        val running = transition.isRunning || transition.currentState != transition.targetState
        isTransitioning = running
        // 唯一收尾监听放在 AnimatedContent 外部，避免转场期间新旧两页并发收集竞态
        LaunchedEffect(running) {
            if (!running && pendingPop) {
                pendingPop = false
                pop()
            }
        }

        Box(Modifier.fillMaxSize()) {
            transition.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                contentKey = { it.id },
                transitionSpec = {
                    val isPush = targetState.id > initialState.id
                    val direction = if (isPush) AnimatedContentTransitionScope.SlideDirection.Left
                    else AnimatedContentTransitionScope.SlideDirection.Right
                    slideIntoContainer(direction, tween(TRANSITION_DURATION_MILLIS)) togetherWith
                        slideOutOfContainer(direction, tween(TRANSITION_DURATION_MILLIS))
                }
            ) { page ->
                val owner = remember(page, parentOwner) {
                    object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                        override val viewModelStore = page.viewModelStore
                        override val defaultViewModelProviderFactory =
                            (parentOwner as HasDefaultViewModelProviderFactory).defaultViewModelProviderFactory
                        override val defaultViewModelCreationExtras =
                            (parentOwner as HasDefaultViewModelProviderFactory).defaultViewModelCreationExtras
                    }
                }
                DisposableEffect(page) {
                    // 入栈覆盖时保留实例，出栈转场结束后才清理任务。
                    onDispose {
                        if (page !in pages) {
                            page.viewModelStore.clear()
                            saveableStateHolder.removeState(page.id)
                        }
                    }
                }
                CompositionLocalProvider(
                    LocalViewModelStoreOwner provides owner,
                    LocalIsNavTransitioning provides running
                ) {
                    // 页面离开 composition 时保存 rememberSaveable 状态，
                    // 返回该页面时恢复 LazyListState 的滚动位置。
                    saveableStateHolder.SaveableStateProvider(page.id) {
                        PageContainer(useSafeArea = page.useSafeArea) { page.content() }
                    }
                }
            }
            if (running) {
                // 放在 AnimatedContent 之后的唯一顶层事件遮罩，只消费指针事件、不出水波纹。
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        }
                )
            }
        }
    }

    private companion object {
        const val TRANSITION_DURATION_MILLIS = 300
    }
}
