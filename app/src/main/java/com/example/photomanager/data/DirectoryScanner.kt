/** 本地目录扫描与路径归一化边界：将选择器 URI 或本地路径统一转换为规范绝对路径并扫描单层图片。 */
package com.example.photomanager.data

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Paths
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class ScannedAlbum(
    val path: String,
    val name: String,
    val modifiedAt: String?,
    val photos: List<PhotoEntity>,
    val totalSizeBytes: Long,
    val children: List<File>
)

@Singleton
class DirectoryScanner @Inject constructor() {
    /** 将系统目录选择器返回的 URI 解析为规范化绝对路径。 */
    fun resolveDirectoryPath(uri: Uri): String {
        if (uri.scheme == "file") return normalizePath(requireNotNull(uri.path) { "无效目录路径" })
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val parts = docId.split(":", limit = 2)
        val volume = parts[0]
        val relative = parts.getOrElse(1) { "" }.trimStart('/')
        val baseDir = if (volume.equals("primary", ignoreCase = true)) {
            Environment.getExternalStorageDirectory().path
        } else {
            "/storage/$volume"
        }
        return normalizePath(if (relative.isEmpty()) baseDir else "$baseDir/$relative")
    }

    /** 扫描单个本地目录，跳过隐藏项，分离直接包含的图片与子目录。 */
    suspend fun scanAlbum(directory: File): ScannedAlbum {
        val canonicalDir = directory.canonicalFile
        check(canonicalDir.isDirectory) { "目录不存在或不可用：${canonicalDir.path}" }
        val entries = canonicalDir.listFiles() ?: error("无法读取目录内容：${canonicalDir.path}")
        val children = mutableListOf<File>()
        val photos = mutableListOf<PhotoEntity>()
        var totalSize = 0L

        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (entry.name.startsWith(".")) continue
            if (entry.isDirectory) {
                children += entry
            } else if (entry.isFile && isImageFile(entry)) {
                val size = entry.length().coerceAtLeast(0L)
                totalSize += size
                photos += PhotoEntity(
                    albumId = 0,
                    name = entry.name,
                    contentUri = normalizePath(entry.path),
                    modifiedAt = formatEpochMillis(entry.lastModified()),
                    sizeBytes = size
                )
            }
        }
        return ScannedAlbum(
            path = normalizePath(canonicalDir.path),
            name = canonicalDir.name.ifEmpty { canonicalDir.path },
            modifiedAt = formatEpochMillis(canonicalDir.lastModified()),
            photos = photos.sortedBy { it.name },
            totalSizeBytes = totalSize,
            children = children.sortedBy { it.name }
        )
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif"
        )

        /** 归一化本地路径，消除冗余分隔符、`.`、`..` 与末尾斜杠。 */
        fun normalizePath(rawPath: String): String =
            runCatching { File(rawPath).canonicalPath }
                .getOrElse { Paths.get(rawPath).toAbsolutePath().normalize().toString() }

        /** 判断两个路径是否为同一目录或父子目录关系。 */
        fun isSameOrSubPath(parent: String, child: String): Boolean {
            val p = normalizePath(parent)
            val c = normalizePath(child)
            return c == p || (p == "/" && c.startsWith("/")) || c.startsWith("$p/")
        }

        private fun isImageFile(file: File): Boolean {
            val ext = file.extension.lowercase()
            if (ext.isEmpty()) return false
            if (ext in IMAGE_EXTENSIONS) return true
            return runCatching {
                MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)?.startsWith("image/") == true
            }.getOrDefault(false)
        }

        private fun formatEpochMillis(millis: Long): String? =
            millis.takeIf { it > 0L }?.let(Instant::ofEpochMilli)?.toString()
    }
}
