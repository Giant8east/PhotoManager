package com.example.photomanager

import android.os.Bundle
import android.os.Build
import android.graphics.Color
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.album.AlbumPage
import com.example.photomanager.pages.photoviewer.PhotoViewerSystemController
import com.example.photomanager.ui.theme.PhotoManagerTheme
import com.example.photomanager.ui.theme.LocalStatusBarColor
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 让系统栏背景色透明，底色由各页面容器绘制并随页面一起转场。
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
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
                // 创建导航器时，把 AlbumPage 作为根页面放进去,构造时初始化
                val navigator = remember {
                    AppNavigator { navigator -> AlbumPage(navigator) }
                }
                // 页面内的返回处理优先；根页面交回桌面，保留当前任务。
                BackHandler { moveTaskToBack(true) }
                // 导航宿主始终占满窗口，具体页面在自身条目内部处理安全区。
                Box(Modifier.fillMaxSize()) { navigator.render() }
            }
        }
    }
}
