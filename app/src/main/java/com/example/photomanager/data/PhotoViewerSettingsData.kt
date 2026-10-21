/** 阅读配置对象的 JSON 存储入口，业务层直接读写整份配置。 */
package com.example.photomanager.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class PhotoViewerSettings(
    val screenOrientation: ScreenOrientation = ScreenOrientation.SYSTEM,
    val readingDirection: ReadingDirection = ReadingDirection.RIGHT_TO_LEFT,
    val pageScale: PageScale = PageScale.FIT_SCREEN,
    val autoPageEnabled: Boolean = false,
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
    } catch (error: SerializationException) {
        throw CorruptionException("阅读配置格式错误", error)
    }

    override suspend fun writeTo(t: PhotoViewerSettings, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(json.encodeToString(t).toByteArray(Charsets.UTF_8))
        }
    }
}

// 扩展属性context.viewerSettingsStore是当前文件声明的扩展
// by表示属性如何创建、如何获取，交给 dataStore() 提供的委托对象管理
private val Context.viewerSettingsStore by dataStore(
    fileName = "photo_viewer_settings.json",
    serializer = PhotoViewerSettingsSerializer
)

@Singleton
class PhotoViewerSettingsData @Inject constructor(
    @ApplicationContext private val context: Context // 使用应用级 Context
) {
    /** 应用启动后持续持有阅读配置，页面进入时直接读取当前值。 */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 首个磁盘值到达前为 null，调用方据此区分未就绪和用户真实配置，不按默认值先应用窗口。 */
    val settings: StateFlow<PhotoViewerSettings?> = context.viewerSettingsStore.data.stateIn(
        scope = applicationScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    suspend fun save(settings: PhotoViewerSettings) {
        context.viewerSettingsStore.updateData { settings }
    }
}
