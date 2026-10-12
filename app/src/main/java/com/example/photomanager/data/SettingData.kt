/** 设置数据入口，持久化用户授权的扫描目录并提供有效相册统计。 */
package com.example.photomanager.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.photomanager.data.database.PhotoDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingStore by preferencesDataStore("settings")
data class SettingDirectory(val uri: String) {
    val name: String get() = Uri.parse(uri).lastPathSegment?.substringAfterLast(':') ?: uri
}
class SettingData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: PhotoDatabase
) {
    private val directoryKey = stringSetPreferencesKey("scan_directories")
    val directories: Flow<List<SettingDirectory>> = context.settingStore.data.map { preferences ->
        preferences[directoryKey].orEmpty().sorted().map(::SettingDirectory)
    }
    val validAlbumCount: Flow<Int> = database.albumDao().observeValidAlbumCount()
    suspend fun addDirectory(uri: Uri) { context.settingStore.edit { it[directoryKey] = it[directoryKey].orEmpty() + uri.toString() } }
    suspend fun removeDirectory(uri: String) { context.settingStore.edit { it[directoryKey] = it[directoryKey].orEmpty() - uri } }
    suspend fun contains(uri: Uri): Boolean = directories.first().any { it.uri == uri.toString() }
}
