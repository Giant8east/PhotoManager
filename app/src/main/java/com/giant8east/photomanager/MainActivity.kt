package com.giant8east.photomanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.giant8east.photomanager.pages.photoviewer.system.VolumeKeyHost
import com.giant8east.photomanager.ui.theme.LocalStatusBarColor
import com.giant8east.photomanager.ui.theme.PhotoManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.core.net.toUri

@AndroidEntryPoint
class MainActivity : ComponentActivity(), VolumeKeyHost {
    private var hasStorageAccess by mutableStateOf(false)
    private var volumeKeyHandler: ((KeyEvent) -> Boolean)? = null

    override fun setVolumeKeyHandler(handler: ((KeyEvent) -> Boolean)?) {
        volumeKeyHandler = handler
    }

    // 按下物理按键时执行
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeyHandler?.invoke(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    // 松开物理按键时执行
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKeyHandler?.invoke(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateStorageAccess()
        enableEdgeToEdge()
        // 触发：Android 12 上 enableEdgeToEdge() 保持 cutoutMode=DEFAULT(0)，
        // 若阅读页运行时动态切换 SHORT_EDGES(1) 与 DEFAULT(0)，退出恢复时状态栏尚未完全显出，
        // WindowManager 会将内容区临时缩窄 114px（刘海高度）再弹回导致页面上下抖动。
        // 启动时永久固定 SHORT_EDGES，普通页继续由 PageContainer(useSafeArea=true) 避让刘海与状态栏。
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        // 让系统栏背景色透明，底色由各页面容器绘制并随页面一起转场。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        setContent {
            PhotoManagerTheme {
                val colors = MaterialTheme.colorScheme
                val statusBarColor = LocalStatusBarColor.current
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = statusBarColor.luminance() > 0.5f
                        isAppearanceLightNavigationBars = colors.background.luminance() > 0.5f
                    }
                }
                if (hasStorageAccess) {
                    // 根页面交回桌面，保留当前任务。
                    BackHandler { moveTaskToBack(true) }
                    // 分组抽屉位于导航宿主外，详情页不会承载抽屉状态。
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        AppRoot()
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxSize().background(colors.background)
                            .safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.storage_access_required_title),
                            color = colors.onBackground,
                            style = MaterialTheme.typography.titleLarge
                        )
                        TextButton(onClick = ::openAllFilesAccessSettings) {
                            Text(stringResource(R.string.storage_access_grant))
                        }
                    }
                }
            }
        }
        // 仅在首次创建时自动打开，未授权返回后由按钮再次发起，避免循环跳转。
        if (!hasStorageAccess) openAllFilesAccessSettings()
    }

    override fun onResume() {
        super.onResume()
        updateStorageAccess()
    }

    private fun updateStorageAccess() {
        hasStorageAccess = Environment.isExternalStorageManager()
    }

    /**
     * 部分 ROM 拒绝应用专属的“所有文件访问”页面，回退到全局管理页面。
     */
    private fun openAllFilesAccessSettings() {
        val appSpecificIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:$packageName".toUri()
        )
        if (startActivityQuietly(appSpecificIntent)) return

        startActivityQuietly(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    private fun startActivityQuietly(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
