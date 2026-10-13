/** 设置数据入口，持久化用户授权的扫描目录并提供有效相册统计。 */
package com.example.photomanager.data

import android.content.Context
import android.content.Intent
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
    fun persistDirectoryPermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
    }
    val directories: Flow<List<SettingDirectory>> = context.settingStore.data.map { preferences ->
        preferences[directoryKey].orEmpty().sorted().map(::SettingDirectory)
    }
    val validAlbumCount: Flow<Int> = database.albumDao().observeValidAlbumCount()
    suspend fun addDirectory(uri: Uri) {
        val newPath = uri.toString()
        context.settingStore.edit { preferences ->
            val currentPaths = preferences[directoryKey].orEmpty()
            if (currentPaths.any { isParentPath(it, newPath) }) return@edit
            preferences[directoryKey] = currentPaths
                .filterNot { isParentPath(newPath, it) }
                .toSet() + newPath
        }
    }
    suspend fun removeDirectory(uri: String) { context.settingStore.edit { it[directoryKey] = it[directoryKey].orEmpty() - uri } }
    suspend fun contains(uri: Uri): Boolean = directories.first().any { it.uri == uri.toString() }

    /** 判断两个 SAF 路径是否存在父子目录关系。 */
    private fun isParentPath(parent: String, child: String): Boolean =
        child == parent || child.startsWith("$parent/") || child.startsWith("$parent%2F", ignoreCase = true)
}
