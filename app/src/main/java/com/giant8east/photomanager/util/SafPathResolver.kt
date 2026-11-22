/** 系统目录选择器（SAF）URI 解析器：将树形 URI 转换为本地规范绝对路径。 */
package com.giant8east.photomanager.util

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.giant8east.photomanager.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SafPathResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 将系统目录选择器返回的 URI 解析为规范化绝对路径。
     * 只接受本地外部存储提供者；Downloads、云盘等 provider 的 docId 不是真实文件路径。
     */
    suspend fun resolveDirectoryPath(uri: Uri): String = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            return@withContext LocalPaths.normalize(
                requireNotNull(uri.path) { context.getString(R.string.invalid_directory_path) }
            )
        }
        check(uri.authority == EXTERNAL_STORAGE_AUTHORITY) {
            context.getString(R.string.unsupported_directory_provider)
        }
        check(DocumentsContract.isTreeUri(uri)) {
            context.getString(R.string.invalid_directory_path)
        }
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank()) {
            context.getString(R.string.unsupported_directory_provider)
        }
        val volume = parts[0]
        check(!volume.equals("home", ignoreCase = true)) {
            context.getString(R.string.unsupported_directory_provider)
        }
        val relative = parts[1].trimStart('/')
        val baseDir = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().path
        } else {
            "/storage/$volume"
        }
        LocalPaths.normalize(if (relative.isEmpty()) baseDir else "$baseDir/$relative")
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
