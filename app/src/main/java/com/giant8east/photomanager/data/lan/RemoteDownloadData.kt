/** 远程下载目录配置：访客把对端相册下载到本机时的落地目录（V2 使用，先持久化好）。 */
package com.giant8east.photomanager.data.lan

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.giant8east.photomanager.data.scan.DirectoryScanner
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.remoteDownloadStore by preferencesDataStore("remote_download")

/**
 * 单独一个 DataStore 文件，不与扫描目录混在一起：
 * 它属于"访客下载"这条线，随 V2 一起演进，改名或迁移时不会牵连扫描配置。
 */
@Singleton
class RemoteDownloadData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val directoryScanner: DirectoryScanner
) {
    /** 空串表示尚未选择目录。 */
    val path: Flow<String> = context.remoteDownloadStore.data.map { it[KEY_PATH].orEmpty() }

    suspend fun setPath(path: String) {
        context.remoteDownloadStore.edit { it[KEY_PATH] = path }
    }

    /** 把 SAF 返回的 Uri 解析成绝对路径，规则与扫描目录完全一致。 */
    suspend fun resolvePath(uri: Uri): String = directoryScanner.resolveDirectoryPath(uri)

    private companion object {
        val KEY_PATH = stringPreferencesKey("remote_download_path")
    }
}
