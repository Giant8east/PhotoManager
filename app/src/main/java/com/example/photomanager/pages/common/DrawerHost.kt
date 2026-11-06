/** 可复用的右侧抽屉宿主：使用 EhViewer DrawerLayout，具体内容由页面提供。 */
package com.example.photomanager.pages.common

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

                val drawer = ComposeView(context).apply {
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
                    drawer,
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
            val drawer = view.getChildAt(2)
            val params = drawer.layoutParams as DrawerLayout.LayoutParams
            if (params.width != widthPx) {
                params.width = widthPx
                drawer.layoutParams = params
            }
        }
    )
}
