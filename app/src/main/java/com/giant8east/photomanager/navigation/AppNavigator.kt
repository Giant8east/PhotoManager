/** 应用轻量导航器，维护保活 Composable 页面栈并提供统一滑动转场。 */
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

    private enum class TransitionKind { PUSH, POP }

    private val pages = mutableStateListOf<PageEntry>() // 保活页面栈：被覆盖的底页留在组合树中，返回时不重建
    private var nextPageId = 0L
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
        val activeKind = transitionKind
        val running = activeKind != null

        DisposableEffect(this) {
            onDispose {
                pages.forEach { it.viewModelStore.clear() }
                exitingPage?.viewModelStore?.clear()
            }
        }

        // 唯一转场驱动协程：入栈/出栈共用 300ms 滑动进度，收尾统一清理已出栈页面的 ViewModelStore
        LaunchedEffect(transitionToken) {
            if (transitionKind == null) return@LaunchedEffect
            progress.snapTo(0f)
            progress.animateTo(1f, tween(TRANSITION_DURATION_MILLIS))
            val popped = exitingPage
            exitingPage = null
            transitionKind = null
            popped?.viewModelStore?.clear()
            if (pendingPop) {
                pendingPop = false
                pop()
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { containerWidthPx = it.width }
        ) {
            val topIndex = pages.lastIndex
            // 按栈底到栈顶顺序渲染，保证顶页的 BackHandler 与绘制层级始终在最上层
            pages.forEachIndexed { index, page ->
                key(page.id) {
                    val isTop = index == topIndex
                    val isUnderneath = index == topIndex - 1
                    PageSlot(
                        page = page,
                        parentOwner = parentOwner,
                        isTransitioning = running,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val w = containerWidthPx.toFloat()
                                when {
                                    activeKind == TransitionKind.PUSH && isTop -> {
                                        translationX = (1f - progress.value) * w
                                        alpha = 1f
                                    }
                                    activeKind == TransitionKind.PUSH && isUnderneath -> {
                                        translationX = -progress.value * w
                                        alpha = 1f
                                    }
                                    activeKind == TransitionKind.POP && isTop -> {
                                        translationX = -(1f - progress.value) * w
                                        alpha = 1f
                                    }
                                    isTop -> {
                                        translationX = 0f
                                        alpha = 1f
                                    }
                                    else -> {
                                        // 被覆盖的底页移出视口并置透明：保持组合树存活（返回零重建），
                                        // 同时避免底层 AndroidView（如 DrawerLayout）拦截顶页触摸手势。
                                        translationX = -w
                                        alpha = 0f
                                    }
                                }
                            }
                    )
                }
            }

            // 正在出栈的页面叠在最顶层向右滑出，动画结束即移除并销毁 ViewModelStore
            val popped = exitingPage
            if (popped != null) {
                key(popped.id) {
                    PageSlot(
                        page = popped,
                        parentOwner = parentOwner,
                        isTransitioning = running,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                translationX = progress.value * containerWidthPx.toFloat()
                                alpha = 1f
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
        Box(modifier) {
            CompositionLocalProvider(
                LocalViewModelStoreOwner provides owner,
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
