/** 设置数据入口，持久化规范化绝对路径形式的扫描目录并提供有效相册统计。 */
package com.example.photomanager.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.photomanager.data.database.PhotoDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingStore by preferencesDataStore("settings")

data class SettingDirectory(val path: String) {
    val uri: String get() = path
    val name: String get() = File(path).name.ifEmpty { path }
}

@Singleton
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
    fun resolvePath(uri: Uri): String = directoryScanner.resolveDirectoryPath(uri)

    /** 添加扫描目录；若已在现有父目录范围内则忽略，若包含已有子目录则自动收敛为当前父目录。 */
    suspend fun addDirectory(rawPath: String): String {
        val newPath = DirectoryScanner.normalizePath(rawPath)
        context.settingStore.edit { preferences ->
            val currentPaths = preferences[directoryKey].orEmpty()
            if (currentPaths.any { DirectoryScanner.isSameOrSubPath(it, newPath) }) return@edit
            preferences[directoryKey] = currentPaths
                .filterNot { DirectoryScanner.isSameOrSubPath(newPath, it) }
                .toSet() + newPath
        }
        return newPath
    }

    suspend fun removeDirectory(path: String) {
        val normalized = DirectoryScanner.normalizePath(path)
        context.settingStore.edit { preferences ->
            preferences[directoryKey] = preferences[directoryKey].orEmpty() - normalized
        }
    }

    suspend fun contains(path: String): Boolean {
        val normalized = DirectoryScanner.normalizePath(path)
        return directories.first().any { DirectoryScanner.isSameOrSubPath(it.path, normalized) }
    }
}
