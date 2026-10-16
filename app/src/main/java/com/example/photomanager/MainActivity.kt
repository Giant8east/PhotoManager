package com.example.photomanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.example.photomanager.ui.theme.PhotoManagerTheme
import com.example.photomanager.ui.theme.photoManagerSafeAreaColor
import dagger.hilt.android.AndroidEntryPoint
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.album.AlbumPage
import com.example.photomanager.pages.photoviewer.PhotoViewerSystemController
import android.view.KeyEvent

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    var photoViewerSystemController: PhotoViewerSystemController? = null    // 阅读页控制器
    var isViewerFullScreen by mutableStateOf(false) // 全屏阅读模式可变状态

    // 按下物理按键时执行
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (photoViewerSystemController?.dispatchKeyEvent(event) == true) return true
        return super.onKeyDown(keyCode, event)
    }

    // 松开物理按键时执行
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (photoViewerSystemController?.dispatchKeyEvent(event) == true) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoManagerTheme {
                val navigator = remember { AppNavigator() }
                if (navigator.pagesEmpty()) navigator.pushTo { AlbumPage(navigator) }
                // 根布局统一避让系统栏和屏幕缺口，并消费 Insets，防止子页面重复留白。
                Surface(
                    Modifier
                        .fillMaxSize()
                        .background(photoManagerSafeAreaColor())
                        .then(if (isViewerFullScreen) Modifier else Modifier.safeDrawingPadding())
                ) {
                    navigator.render()
                }
            }
        }
    }
}
