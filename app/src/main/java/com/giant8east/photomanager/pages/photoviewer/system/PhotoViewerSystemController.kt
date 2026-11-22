/** 图片浏览页系统能力控制器，按窗口能力拆分方法，并提供与 Composition 的绑定入口。 */
package com.giant8east.photomanager.pages.photoviewer.system

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.giant8east.photomanager.data.settings.PhotoViewerSettings
import com.giant8east.photomanager.data.settings.ScreenOrientation

/** 宿主 Activity 暴露给阅读页的音量键接管口，避免子模块直接强转依赖具体 MainActivity。 */
interface VolumeKeyHost {
    fun setVolumeKeyHandler(handler: ((KeyEvent) -> Boolean)?)
}

/**
 * 创建并绑定阅读页的系统能力控制器：配置变化时统一应用窗口设置，离开页面时恢复窗口。
 * 翻页回调只在进入页面时绑定一次，不随每次配置变化重复注册。
 */
@Composable
fun rememberPhotoViewerSystem(
    settings: PhotoViewerSettings,
    onNextPage: () -> Unit,
    onPreviousPage: () -> Unit
): PhotoViewerSystemController {
    val context = LocalContext.current
    val activity = context as? Activity
    val volumeKeyHost = context as? VolumeKeyHost
    val controller = remember(activity) { PhotoViewerSystemController(activity) }
    val latestOnNextPage by rememberUpdatedState(onNextPage)
    val latestOnPreviousPage by rememberUpdatedState(onPreviousPage)
    DisposableEffect(controller, volumeKeyHost) {
        controller.bindPaging({ latestOnNextPage() }, { latestOnPreviousPage() })
        // 等价于 { event -> controller.handleVolumeKey(event) }
        volumeKeyHost?.setVolumeKeyHandler(controller::handleVolumeKey)
        onDispose {
            controller.restore()
            volumeKeyHost?.setVolumeKeyHandler(null)
        }
    }
    LaunchedEffect(controller, settings) { controller.apply(settings) }
    return controller
}

class PhotoViewerSystemController(private val activity: Activity?) {
    private val window = activity?.window
    private val originalBrightness = window?.attributes?.screenBrightness
        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE // 原始系统亮度
    private val originalOrientation = activity?.requestedOrientation
        ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED // 原始屏幕方向
    private val originalCutoutMode = window?.attributes?.layoutInDisplayCutoutMode
        ?: WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT  // 原始布局
    private var onNextPage: (() -> Unit)? = null
    private var onPreviousPage: (() -> Unit)? = null
    private var volumeKeyPagingEnabled = false  // 是否启用音量键翻页

    /** 绑定页面提供的翻页动作，控制器只负责把按键映射成这两个动作。 */
    fun bindPaging(onNextPage: () -> Unit, onPreviousPage: () -> Unit) {
        this.onNextPage = onNextPage
        this.onPreviousPage = onPreviousPage
    }

    /** 统一应用阅读页的窗口和按键设置。 */
    fun apply(settings: PhotoViewerSettings) {
        volumeKeyPagingEnabled = settings.volumeKeyPaging
        applyKeepScreenOn(settings.keepScreenOn)
        applyBrightness(settings.customBrightnessEnabled, settings.customBrightness)
        applyCutoutMode(settings.fullScreen)
        applySystemBars(settings.fullScreen)
        applyOrientation(settings.screenOrientation)
    }

    /** 保持屏幕常亮 */
    private fun applyKeepScreenOn(enabled: Boolean) {
        val w = window ?: return
        if (enabled) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** 关闭自定义亮度后交回系统控制。 */
    private fun applyBrightness(customEnabled: Boolean, brightness: Float) {
        val w = window ?: return
        w.attributes = w.attributes.apply {
            screenBrightness = if (customEnabled) brightness
            else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    /** 全屏时允许内容绘制到刘海区域，非全屏恢复进入页面前的模式。 */
    private fun applyCutoutMode(fullScreen: Boolean) {
        val w = window ?: return
        w.attributes = w.attributes.apply {
            layoutInDisplayCutoutMode =
                if (fullScreen) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                else originalCutoutMode
        }
    }

    /** 全屏隐藏系统栏，并保留下拉临时显示的行为。 */
    private fun applySystemBars(fullScreen: Boolean) {
        val w = window ?: return
        val controller = WindowCompat.getInsetsController(w, w.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullScreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    /** 将屏幕方向配置映射为窗口请求的朝向。 */
    private fun applyOrientation(screenOrientation: ScreenOrientation) {
        val host = activity ?: return
        val orientation = when (screenOrientation) {
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            ScreenOrientation.SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        // 只有当前方向不一致时才修改，避免重复触发方向变更。
        if (host.requestedOrientation != orientation) {
            host.requestedOrientation = orientation
        }
    }

    /** 将启用状态下的音量键转换为上一张或下一张，接收宿主转交的按键事件。 */
    fun handleVolumeKey(event: KeyEvent): Boolean {
        if (!volumeKeyPagingEnabled) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_UP
        ) return false
        // 长按由系统重复发送 ACTION_DOWN，每次均翻页；松开只消费事件。
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> onNextPage?.invoke()
            KeyEvent.KEYCODE_VOLUME_UP -> onPreviousPage?.invoke()
        }
        return true
    }

    /** 离开阅读页时关闭常亮，并恢复阅读页修改过的窗口配置。 */
    fun restore() {
        volumeKeyPagingEnabled = false
        onNextPage = null
        onPreviousPage = null
        val w = window ?: return
        w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        w.attributes = w.attributes.apply {
            screenBrightness = originalBrightness
            layoutInDisplayCutoutMode = originalCutoutMode
        }
        // 退出后不再需要阅读页的临时系统栏行为，恢复应用默认值。
        WindowCompat.getInsetsController(w, w.decorView).systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        activity?.requestedOrientation = originalOrientation
        showSystemBars()
    }

    /** 控制栏收起时恢复全屏，不改变持久化配置。 */
    fun hideSystemBars() {
        val w = window ?: return
        WindowCompat.getInsetsController(w, w.decorView)
            .hide(WindowInsetsCompat.Type.systemBars())
    }

    /** 单击阅读页面时同时显示顶部状态栏和底部导航栏。 */
    fun showSystemBars() {
        val w = window ?: return
        WindowCompat.getInsetsController(w, w.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}
