package com.example.photomanager

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.album.AlbumPage
import com.example.photomanager.pages.photoviewer.system.PhotoViewerSystemController
import com.example.photomanager.ui.theme.LocalStatusBarColor
import com.example.photomanager.ui.theme.PhotoManagerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var hasStorageAccess by mutableStateOf(false)
    var photoViewerSystemController: PhotoViewerSystemController? = null    // 阅读页控制器

    // 按下物理按键时执行
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (photoViewerSystemController?.handleVolumeKey(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    // 松开物理按键时执行
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (photoViewerSystemController?.handleVolumeKey(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateStorageAccess()
        enableEdgeToEdge()
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
                    // 创建导航器时，把 AlbumPage 作为根页面放进去,构造时初始化
                    val navigator = remember {
                        AppNavigator { navigator -> AlbumPage(navigator) }
                    }
                    // 页面内的返回处理优先；根页面交回桌面，保留当前任务。
                    BackHandler { moveTaskToBack(true) }
                    // 导航宿主始终占满窗口，具体页面在自身条目内部处理安全区。
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        Box(Modifier.fillMaxSize()) { navigator.render() }
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
            Uri.parse("package:$packageName")
        )
        if (startActivityQuietly(appSpecificIntent)) return

        startActivityQuietly(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
    }

    private fun startActivityQuietly(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (ignored: ActivityNotFoundException) {
        false
    } catch (ignored: SecurityException) {
        false
    }
}
