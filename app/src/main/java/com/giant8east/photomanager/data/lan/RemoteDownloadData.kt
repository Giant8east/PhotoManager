/** 远程下载目录配置：访客把对端相册下载到本机时的落地目录（V2 使用，先持久化好）。 */
package com.giant8east.photomanager.data.lan

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.giant8east.photomanager.util.SafPathResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.remoteDownloadStore by preferencesDataStore("remote_download")

/**
 * 单独一个 DataStore 文件，不与扫描目录混在一起：
 * 它属于"访客下载"这条线，随 V2 一起演进，改名或迁移时不会牵连扫描配置。
 */
@Singleton
class RemoteDownloadData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safPathResolver: SafPathResolver
) {
    /**
     * 用户没选过目录时的默认落地位置：`Pictures/photomanager`。
     *
     * 这里要的是**用户可见的公共目录路径**（本 App 已有 MANAGE_EXTERNAL_STORAGE），
     * 而 `getExternalStoragePublicDirectory` 自 API 29 起被标记弃用、推荐改用 MediaStore/SAF——
     * 那两个给的是 Uri，拿不到可拼文件路径的目录，所以这里仍用它。
     */
    @Suppress("DEPRECATION")
    private val defaultPath: String = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        DIRECTORY_NAME
    ).absolutePath

    /** 选过就用选的，否则用默认目录。 */
    suspend fun getPath(): String {
        val preferences = context.remoteDownloadStore.data.first()
        // 等价于 if (preferences[KEY_PATH] != null) preferences[KEY_PATH]!! else defaultPath
        return preferences[KEY_PATH] ?: defaultPath
    }

    suspend fun setPath(path: String) {
        context.remoteDownloadStore.edit { it[KEY_PATH] = path }
    }

    /** 把 SAF 返回的 Uri 解析成绝对路径，规则与扫描目录完全一致。 */
    suspend fun resolvePath(uri: Uri): String = safPathResolver.resolveDirectoryPath(uri)

    private companion object {
        const val DIRECTORY_NAME = "photomanager"
        val KEY_PATH = stringPreferencesKey("remote_download_path")
    }
}
