/** 应用轻量导航器，维护保活 Composable 页面栈（类似 Flutter Overlay + Offstage）并提供统一滑动转场。 */
package com.giant8east.photomanager.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/** 指示当前页面是否处于导航入场/退场滑动动画中，供重型列表或缩略图网格延后首帧挂载。 */
val LocalIsNavTransitioning = compositionLocalOf { false }

class AppNavigator(
    // 构造函数接收一个起始页面
    private val initialPage: @Composable (AppNavigator) -> Unit
) {
    // 一个页面栈条目
    private class PageEntry(
        val id: Long, // 导航条目的唯一身份
        val useSafeArea: Boolean, // 为 false 时绘制到完整窗口，不使用普通页面的安全区 padding
        val content: @Composable () -> Unit
    ) {
        val viewModelStore = ViewModelStore()
    }

    private enum class TransitionKind { PUSH, POP }

    // 保活页面栈：被覆盖的底页保留在组合树中不销毁，返回时无需重建整页 UI
    private val pages = mutableStateListOf<PageEntry>()
    private var nextPageId = 0L
    // 出栈退场期间临时托住正在滑出的页面，动画结束后才从组合树移除并清理 ViewModelStore
    private var exitingPage by mutableStateOf<PageEntry?>(null)
    private var transitionKind by mutableStateOf<TransitionKind?>(null)
    private var transitionToken by mutableIntStateOf(0)
    private var pendingPop = false

    init {
        pages += PageEntry(nextPageId++, true) { initialPage(this) }
    }

    fun pushTo(
        useSafeArea: Boolean = true,
        page: @Composable () -> Unit
    ) {
        if (transitionKind != null) return
        pages += PageEntry(nextPageId++, useSafeArea, page)
        transitionKind = TransitionKind.PUSH
        transitionToken++
    }

    fun pop() {
        if (transitionKind != null) {
            if (pages.size > 1) pendingPop = true
            return
        }
        if (pages.size <= 1) return
        exitingPage = pages.removeAt(pages.lastIndex)
        transitionKind = TransitionKind.POP
        transitionToken++
    }

    @Composable
    fun render() {
        val parentOwner = checkNotNull(LocalViewModelStoreOwner.current)
        var containerWidthPx by remember { mutableIntStateOf(0) }
        val progress = remember { Animatable(1f) }
        // 同步记录 LaunchedEffect 已完成 snapTo(0f) 的转场序号；
        // 在 LaunchedEffect 执行前的第 0 帧，appliedToken != transitionToken，直接同步按 0f 计算位移，彻底避免闪一帧旧值。
        var appliedToken by remember { mutableIntStateOf(0) }

        DisposableEffect(this) {
            onDispose {
                pages.forEach { it.viewModelStore.clear() }
                exitingPage?.viewModelStore?.clear()
            }
        }

        LaunchedEffect(transitionToken) {
            val kind = transitionKind ?: return@LaunchedEffect
            progress.snapTo(0f)
            appliedToken = transitionToken
            progress.animateTo(1f, tween(TRANSITION_DURATION_MILLIS))
            if (kind == TransitionKind.POP) {
                val popped = exitingPage
                exitingPage = null
                popped?.viewModelStore?.clear()
            }
            transitionKind = null
            if (pendingPop) {
                pendingPop = false
                pop()
            }
        }

        val kind = transitionKind
        val running = kind != null
        val topIndex = pages.lastIndex
        val token = transitionToken
        val popped = exitingPage
        // 将活跃栈与退场页合并到同一个循环渲染：pop() 时退场页仍留在同一调用位置和同一 key(page.id) 下，
        // Compose 不会销毁重建页面，确保滑出期间头图、网格与滚动位置完整保留，等 300ms 动画结束再真正销毁。
        val renderedPages = if (popped != null) pages + popped else pages

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { containerWidthPx = it.width }
        ) {
            renderedPages.forEachIndexed { index, page ->
                key(page.id) {
                    val isExiting = popped != null && page === popped
                    val isTop = !isExiting && index == topIndex
                    val isPreviousDuringPush = !isExiting && kind == TransitionKind.PUSH && index == topIndex - 1
                    val isVisible = isExiting || isTop || isPreviousDuringPush

                    PageSlot(
                        page = page,
                        parentOwner = parentOwner,
                        isVisible = isVisible,
                        isTransitioning = running && isVisible,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val width = containerWidthPx.toFloat()
                                val p = if (appliedToken == token) progress.value else 0f
                                when {
                                    isExiting -> {
                                        translationX = p * width
                                        alpha = 1f
                                    }
                                    isTop && kind == TransitionKind.PUSH -> {
                                        translationX = (1f - p) * width
                                        alpha = 1f
                                    }
                                    isPreviousDuringPush -> {
                                        translationX = -p * width
                                        alpha = 1f
                                    }
                                    isTop && kind == TransitionKind.POP -> {
                                        translationX = -(1f - p) * width
                                        alpha = 1f
                                    }
                                    isTop -> {
                                        translationX = 0f
                                        alpha = 1f
                                    }
                                    else -> {
                                        // 被完全遮住的底页移出视口并隐藏绘制：既保活 UI 树，又防止底层 AndroidView 拦截边缘手势
                                        translationX = -width
                                        alpha = 0f
                                    }
                                }
                            }
                    )
                }
            }

            if (running) {
                // 转场期间唯一顶层事件遮罩，只消费指针事件、不出水波纹。
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

    @Composable
    private fun PageSlot(
        page: PageEntry,
        parentOwner: ViewModelStoreOwner,
        isVisible: Boolean,
        isTransitioning: Boolean,
        modifier: Modifier
    ) {
        val owner = remember(page, parentOwner) {
            object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = page.viewModelStore
                override val defaultViewModelProviderFactory =
                    (parentOwner as HasDefaultViewModelProviderFactory).defaultViewModelProviderFactory
                override val defaultViewModelCreationExtras =
                    (parentOwner as HasDefaultViewModelProviderFactory).defaultViewModelCreationExtras
            }
        }
        val pageLifecycleOwner = rememberPageLifecycleOwner(isVisible = isVisible)
        Box(modifier) {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
                LocalLifecycleOwner provides pageLifecycleOwner,
                LocalIsNavTransitioning provides isTransitioning
            ) {
                PageContainer(useSafeArea = page.useSafeArea) { page.content() }
            }
        }
    }

    private companion object {
        const val TRANSITION_DURATION_MILLIS = 300
    }
}

/**
 * 为保活栈中的每一页提供独立生命周期：
 * 页面可见时跟随宿主 Activity 生命周期；被覆盖时自动降级到 CREATED，避免底页继续维持前台订阅（如 SSE 心跳）。
 */
@Composable
private fun rememberPageLifecycleOwner(isVisible: Boolean): LifecycleOwner {
    val parentLifecycle = LocalLifecycleOwner.current.lifecycle
    val pageOwner = remember { PageLifecycleOwner() }

    DisposableEffect(parentLifecycle, isVisible) {
        fun syncState() {
            val parentState = parentLifecycle.currentState
            val targetState = if (isVisible) {
                parentState
            } else {
                minOf(parentState, Lifecycle.State.CREATED)
            }
            if (targetState != Lifecycle.State.DESTROYED) {
                pageOwner.moveTo(targetState)
            }
        }

        val observer = LifecycleEventObserver { _, _ -> syncState() }
        parentLifecycle.addObserver(observer)
        syncState()
        onDispose {
            parentLifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(pageOwner) {
        onDispose { pageOwner.moveTo(Lifecycle.State.DESTROYED) }
    }

    return pageOwner
}

private class PageLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun moveTo(state: Lifecycle.State) {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = state
        }
    }
}
