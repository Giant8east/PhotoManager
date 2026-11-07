/** 应用入口：只负责创建导航器并指定首页，具体页面持有自己的状态。 */
package com.example.photomanager

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.album.AlbumPage

@Composable
fun AppRoot() {
    val navigator = remember {
        AppNavigator { navigator ->
            AlbumPage(navigator)
        }
    }

    Box(Modifier.fillMaxSize()) {
        navigator.render()
    }
}
