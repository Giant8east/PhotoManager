/** 相册列表排序配置，使用 DataStore 持久化。 */
package com.giant8east.photomanager.data.settings

import android.annotation.SuppressLint
import com.giant8east.photomanager.model.AlbumSortField

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
data class AlbumListSettings(
    val sortField: AlbumSortField = AlbumSortField.CUSTOM,
    val isDescending: Boolean = false
)

private object AlbumListSettingsSerializer : Serializer<AlbumListSettings> {
    override val defaultValue = AlbumListSettings()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun readFrom(input: InputStream): AlbumListSettings = try {
        json.decodeFromString<AlbumListSettings>(input.readBytes().toString(Charsets.UTF_8))
    } catch (error: SerializationException) {
        throw CorruptionException("Invalid album list settings format", error)
    }

    override suspend fun writeTo(t: AlbumListSettings, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(json.encodeToString(t).toByteArray(Charsets.UTF_8))
        }
    }
}

private val Context.albumListSettingsStore by dataStore(
    fileName = "album_list_settings.json",
    serializer = AlbumListSettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { AlbumListSettings() }
)

@Singleton
class AlbumListSettingsData @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun observeSettings(): Flow<AlbumListSettings> = context.albumListSettingsStore.data

    suspend fun save(settings: AlbumListSettings) {
        context.albumListSettingsStore.updateData { settings }
    }
}
