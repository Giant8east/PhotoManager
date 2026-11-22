/** 可复用的右侧抽屉宿主：使用 EhViewer DrawerLayout，具体内容由页面提供。 */
package com.giant8east.photomanager.pages.common

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hippo.drawerlayout.DrawerLayout

private class InflatableDrawerLayout(context: Context) : DrawerLayout(context, null, 0) {
    fun finishInflate() = onFinishInflate()
}

/**
 * 可复用的右侧抽屉宿主：使用 EhViewer DrawerLayout，具体内容由页面提供。
 *
 * 注意：本组件通过 `AndroidView` 嵌套独立 `ComposeView`，`AppNavigator` 的 `SaveableStateProvider`
 * 不会穿透该边界；调用页面必须把 `rememberLazyListState()` 等需跨导航保留的滚动状态提升到本组件之外。
 */
@SuppressLint("RtlHardcoded")
@Composable
fun DrawerHost(
    gesturesEnabled: Boolean,
    drawerWidth: Dp = 280.dp,
    drawerContent: @Composable (closeDrawer: () -> Unit) -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val drawerLayout = remember(context) { InflatableDrawerLayout(context) }
    // 直接持有抽屉 ComposeView 引用，避免在 update 里用 getChildAt(2) 依赖 DrawerLayout 内部子视图顺序
    val drawerView = remember(drawerLayout) { ComposeView(context) }
    val compositionContext = rememberCompositionContext()
    val currentContent = rememberUpdatedState(content)
    val currentDrawerContent = rememberUpdatedState(drawerContent)
    var isDrawerOpen by remember(drawerLayout) {
        mutableStateOf(drawerLayout.isDrawerOpen(Gravity.RIGHT))
    }
    val width = minOf(drawerWidth, LocalConfiguration.current.screenWidthDp.dp * 0.8f)
    val widthPx = with(LocalDensity.current) { width.roundToPx() }

    BackHandler(enabled = isDrawerOpen) {
        drawerLayout.closeDrawer(Gravity.RIGHT)
    }

    AndroidView(
        factory = {
            drawerLayout.apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR

                val page = ComposeView(context).apply {
                    setParentCompositionContext(compositionContext)
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                    )
                    setContent { currentContent.value() }
                }
                addView(
                    page,
                    DrawerLayout.LayoutParams(
                        DrawerLayout.LayoutParams.MATCH_PARENT,
                        DrawerLayout.LayoutParams.MATCH_PARENT
                    )
                )

                drawerView.apply {
                    setParentCompositionContext(compositionContext)
                    setViewCompositionStrategy(
                        ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
                    )
                    setContent {
                        currentDrawerContent.value {
                            drawerLayout.closeDrawer(Gravity.RIGHT)
                        }
                    }
                }
                addView(
                    drawerView,
                    DrawerLayout.LayoutParams(
                        widthPx,
                        DrawerLayout.LayoutParams.MATCH_PARENT,
                        Gravity.RIGHT
                    )
                )

                setDrawerListener(object : DrawerLayout.DrawerListener {
                    override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                        isDrawerOpen = slideOffset > 0f
                    }

                    override fun onDrawerOpened(drawerView: View) {
                        isDrawerOpen = true
                    }

                    override fun onDrawerClosed(drawerView: View) {
                        isDrawerOpen = false
                    }

                    override fun onDrawerStateChanged(drawerView: View, newState: Int) = Unit
                })

                finishInflate()
            }
        },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            view.setDrawerLockMode(
                if (gesturesEnabled) DrawerLayout.LOCK_MODE_UNLOCKED
                else DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
                Gravity.RIGHT
            )
            val params = drawerView.layoutParams as DrawerLayout.LayoutParams
            if (params.width != widthPx) {
                params.width = widthPx
                drawerView.layoutParams = params
            }
        }
    )
}
