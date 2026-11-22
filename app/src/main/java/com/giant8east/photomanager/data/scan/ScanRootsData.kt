/** 扫描根目录集合：持久化用户选择的目录，并提供子树合并后的当前根目录列表。 */
package com.giant8east.photomanager.data.scan

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.giant8east.photomanager.util.LocalPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// DataStore 文件名沿用历史的 "settings"：改名等于换文件，旧配置会读不到，需要额外迁移
private val Context.settingStore by preferencesDataStore("settings")

/** 一个扫描根目录；[path] 是规范化绝对路径，[name] 只用于界面展示。 */
data class ScanRoot(val path: String) {
    val name: String get() = File(path).name.ifEmpty { path }
}

@Singleton
class ScanRootsData @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val directoryKey = stringSetPreferencesKey("scan_directories")

    /** 当前根目录，按路径排序，避免顺序抖动导致列表重排。 */
    val roots: Flow<List<ScanRoot>> = context.settingStore.data.map { preferences ->
        preferences[directoryKey].orEmpty().sorted().map(::ScanRoot)
    }

    /** 添加扫描根；已被现有根覆盖时返回 false，否则吞并它覆盖的子根并返回 true。 */
    suspend fun addRoot(rawPath: String): Boolean = withContext(Dispatchers.IO) {
        val newPath = LocalPaths.normalize(rawPath)
        var changed = false
        context.settingStore.edit { preferences ->
            val currentPaths = preferences[directoryKey].orEmpty()
            if (currentPaths.any { LocalPaths.isSameOrSubPath(it, newPath) }) return@edit
            preferences[directoryKey] = currentPaths
                .filterNot { LocalPaths.isSameOrSubPath(newPath, it) }
                .toSet() + newPath
            changed = true
        }
        changed
    }

    /** 移除一个扫描根；只改配置，索引记录由调用方交给调度器清理。 */
    suspend fun removeRoot(path: String) = withContext(Dispatchers.IO) {
        val normalized = LocalPaths.normalize(path)
        context.settingStore.edit { preferences ->
            preferences[directoryKey] = preferences[directoryKey].orEmpty() - normalized
        }
    }
}
