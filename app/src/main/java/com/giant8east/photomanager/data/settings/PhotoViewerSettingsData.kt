/** 阅读配置对象的 JSON 存储入口，业务层直接读写整份配置。 */
package com.giant8east.photomanager.data.settings

import android.annotation.SuppressLint
import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PhotoViewerSettings(
    val screenOrientation: ScreenOrientation = ScreenOrientation.SYSTEM,
    val readingDirection: ReadingDirection = ReadingDirection.RIGHT_TO_LEFT,
    val pageScale: PageScale = PageScale.FIT_SCREEN,
    val autoPageSeconds: Int = 3,
    val keepScreenOn: Boolean = false,
    val showClock: Boolean = true,
    val showProgress: Boolean = true,
    val showBattery: Boolean = true,
    val showPageGap: Boolean = true,
    val fullScreen: Boolean = true,
    val volumeKeyPaging: Boolean = false,
    val customBrightnessEnabled: Boolean = false,
    val customBrightness: Float = 1f
)

private object PhotoViewerSettingsSerializer : Serializer<PhotoViewerSettings> {
    override val defaultValue = PhotoViewerSettings()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun readFrom(input: InputStream): PhotoViewerSettings = try {
        json.decodeFromString<PhotoViewerSettings>(input.readBytes().toString(Charsets.UTF_8))
            .sanitized()
    } catch (error: SerializationException) {
        throw CorruptionException("Invalid reader settings format", error)
    }

    override suspend fun writeTo(t: PhotoViewerSettings, output: OutputStream) {
        output.write(json.encodeToString(t).toByteArray(Charsets.UTF_8))
    }
}

/** 归一化旧版本或手工修改过的配置，保证 UI 控件和窗口参数都在合法范围内。 */
private fun PhotoViewerSettings.sanitized(): PhotoViewerSettings = copy(
    autoPageSeconds = autoPageSeconds.coerceIn(1, 60),
    customBrightness = customBrightness.coerceIn(0.1f, 1f)
)

// 扩展属性context.viewerSettingsStore是当前文件声明的扩展
// by表示属性如何创建、如何获取，交给 dataStore() 提供的委托对象管理
private val Context.viewerSettingsStore by dataStore(
    fileName = "photo_viewer_settings.json",
    serializer = PhotoViewerSettingsSerializer,
    // 仅在配置无法反序列化时重建默认文件，普通读写错误仍交由 DataStore 抛出。
    corruptionHandler = ReplaceFileCorruptionHandler { PhotoViewerSettings() }
)

@Singleton
class PhotoViewerSettingsData @Inject constructor(
    @ApplicationContext private val context: Context // 使用应用级 Context
) {
    /** 页面直接订阅 DataStore 的缓存数据，读取失败可向页面传递并重新订阅。 */
    fun observeSettings() = context.viewerSettingsStore.data

    suspend fun save(settings: PhotoViewerSettings) {
        context.viewerSettingsStore.updateData { settings.sanitized() }
    }
}
