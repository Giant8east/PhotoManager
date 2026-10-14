/** 图片浏览页系统能力控制器，统一管理窗口状态和音量键翻页。 */
package com.example.photomanager.pages.photoviewer

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.photomanager.data.*

class PhotoViewerSystemController(private val activity: Activity) {
    private val window = activity.window
    private val originalBrightness = window.attributes.screenBrightness
    private var originalFlags = window.attributes.flags
    private var onNext: (() -> Unit)? = null
    private var onPrevious: (() -> Unit)? = null
    private var volumeKeyPagingEnabled = false

    fun apply(settings: PhotoViewerSettings, next: () -> Unit, previous: () -> Unit) {
        volumeKeyPagingEnabled = settings.volumeKeyPaging
        onNext = next
        onPrevious = previous
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            screenBrightness = if (settings.customBrightnessEnabled) settings.customBrightness
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (settings.fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = when (settings.screenOrientation) {
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!volumeKeyPagingEnabled || event.action != KeyEvent.ACTION_DOWN) return false
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { onNext?.invoke(); true }
            KeyEvent.KEYCODE_VOLUME_UP -> { onPrevious?.invoke(); true }
            else -> false
        }
    }

    fun restore() {
        volumeKeyPagingEnabled = false
        onNext = null
        onPrevious = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
