/** 设置数据入口，持久化规范化绝对路径形式的扫描目录并提供有效相册统计。 */
package com.example.photomanager.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.photomanager.data.database.PhotoDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private val Context.settingStore by preferencesDataStore("settings")

data class SettingDirectory(val path: String) {
    val name: String get() = File(path).name.ifEmpty { path }
}

class SettingData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: PhotoDatabase,
    private val directoryScanner: DirectoryScanner
) {
    private val directoryKey = stringSetPreferencesKey("scan_directories")

    val directories: Flow<List<SettingDirectory>> = context.settingStore.data.map { preferences ->
        preferences[directoryKey].orEmpty().sorted().map(::SettingDirectory)
    }

    val validAlbumCount: Flow<Int> = database.albumDao().observeValidAlbumCount()

    /** 将系统选择器返回的 URI 解析为规范化绝对路径。 */
    suspend fun resolvePath(uri: Uri): String = directoryScanner.resolveDirectoryPath(uri)

    /** 添加扫描目录；若已在现有父目录范围内则返回 false，否则合并被覆盖的子目录并返回 true。 */
    suspend fun addDirectory(rawPath: String): Boolean = withContext(Dispatchers.IO) {
        val newPath = DirectoryScanner.normalizePath(rawPath)
        var changed = false
        context.settingStore.edit { preferences ->
            val currentPaths = preferences[directoryKey].orEmpty()
            if (currentPaths.any { DirectoryScanner.isSameOrSubPath(it, newPath) }) return@edit
            preferences[directoryKey] = currentPaths
                .filterNot { DirectoryScanner.isSameOrSubPath(newPath, it) }
                .toSet() + newPath
            changed = true
        }
        changed
    }

    suspend fun removeDirectory(path: String) = withContext(Dispatchers.IO) {
        val normalized = DirectoryScanner.normalizePath(path)
        context.settingStore.edit { preferences ->
            preferences[directoryKey] = preferences[directoryKey].orEmpty() - normalized
        }
    }
}
