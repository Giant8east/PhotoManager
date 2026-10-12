package com.example.photomanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import com.example.photomanager.ui.theme.PhotoManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.album.AlbumPage

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoManagerTheme {
                val navigator = remember { AppNavigator() }
                if (navigator.pagesEmpty()) navigator.pushTo { AlbumPage(navigator) }
                navigator.render()
            }
        }
    }
}
