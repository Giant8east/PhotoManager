/** 图片浏览页系统能力控制器，统一管理窗口状态和音量键翻页。 */
package com.example.photomanager.pages.photoviewer

import android.app.Activity
import android.annotation.SuppressLint
import android.view.KeyEvent
import android.view.WindowManager
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.photomanager.data.*

// 构造函数接收Activity
class PhotoViewerSystemController(private val activity: Activity) {
    private val window = activity.window
    private val originalBrightness = window.attributes.screenBrightness // 原始系统亮度
    private val originalOrientation = activity.requestedOrientation // 原始屏幕方向
    private val originalCutoutMode = window.attributes.layoutInDisplayCutoutMode  // 原始布局
    private var onNext: (() -> Unit)? = null
    private var onPrevious: (() -> Unit)? = null
    private var volumeKeyPagingEnabled = false  // 是否启用音量键翻页

    /** 应用阅读页的屏幕常亮、亮度、全屏、方向和音量键配置。 */
    // 恢复值直接来自系统窗口属性，属于合法枚举
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

        // 获取系统栏控制器
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        // 设置全屏后的系统栏行为
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // 刘海屏区域
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                if (settings.fullScreen)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else originalCutoutMode
        }
        // 显示或隐藏系统栏
        if (settings.fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
        // 设置屏幕方向
        val orientation = when (settings.screenOrientation) {
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // 只有当前方向不一致时才修改
        if (activity.requestedOrientation != orientation)
            activity.requestedOrientation = orientation
    }

    /** 将启用状态下的音量键转换为上一张或下一张。
     *  接收 MainActivity 转交过来的按键事件
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!volumeKeyPagingEnabled) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN && event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) return false
        // 长按由系统重复发送 ACTION_DOWN，每次均翻页；松开只消费事件。
        if (event.action != KeyEvent.ACTION_DOWN) return true
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                onNext?.invoke()
                true
            }

            KeyEvent.KEYCODE_VOLUME_UP -> {
                onPrevious?.invoke()
                true
            }

            else -> false
        }
    }

    /** 离开阅读页时关闭常亮，并恢复进入前的其他窗口配置。 */
    fun restore() {
        volumeKeyPagingEnabled = false
        onNext = null
        onPrevious = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = originalBrightness }
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = originalCutoutMode
        }
        activity.requestedOrientation = originalOrientation
    }

    /** 单击阅读页面时同时显示顶部状态栏和底部导航栏。 */
    fun showSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}
