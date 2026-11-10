/** 本地目录扫描与路径归一化边界：将选择器 URI 或本地路径统一转换为规范绝对路径并扫描单层图片。 */
package com.example.photomanager.data

import android.content.Context
import com.example.photomanager.R
import dagger.hilt.android.qualifiers.ApplicationContext
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import javax.inject.Inject

internal data class ScannedAlbum(
    val path: String,
    val name: String,
    val modifiedAt: String?,
    val photos: List<PhotoEntity>,
    val totalSizeBytes: Long,
    val children: List<File>
)

class DirectoryScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * 将系统目录选择器返回的 URI 解析为规范化绝对路径。
     * 只接受本地外部存储提供者；Downloads、云盘等 provider 的 docId 不是真实文件路径。
     */
    suspend fun resolveDirectoryPath(uri: Uri): String = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") {
            return@withContext normalizePath(
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
        normalizePath(if (relative.isEmpty()) baseDir else "$baseDir/$relative")
    }

    /** 扫描已规范化绝对路径的单个目录，包含隐藏项；明确不存在时返回 null。 */
    internal suspend fun scanAlbum(directory: File): ScannedAlbum? {
        val attributes = try {
            withContext(Dispatchers.IO) {
                Files.readAttributes(directory.toPath(), BasicFileAttributes::class.java)
            }
        } catch (_: NoSuchFileException) {
            return null
        }
        check(attributes.isDirectory) { context.getString(R.string.path_not_directory, directory.path) }
        val dirPath = directory.path
        val dirName = directory.name.ifEmpty { dirPath }
        val entries = directory.listFiles() ?: error(context.getString(R.string.directory_read_failed, dirPath))
        val children = mutableListOf<File>()
        val photos = mutableListOf<PhotoEntity>()
        var totalSize = 0L

        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (entry.isDirectory) {
                children += entry
            } else if (entry.isFile && isImageFile(entry)) {
                val size = entry.length()
                totalSize += size
                val photoPath = if (dirPath == "/") "/${entry.name}" else "$dirPath/${entry.name}"
                photos += PhotoEntity(
                    albumId = 0,
                    name = entry.name,
                    contentUri = photoPath,
                    modifiedAt = formatEpochMillis(entry.lastModified()),
                    sizeBytes = size
                )
            }
        }
        return ScannedAlbum(
            path = dirPath,
            name = dirName,
            modifiedAt = formatEpochMillis(attributes.lastModifiedTime().toMillis()),
            photos = photos.sortedBy { it.name },
            totalSizeBytes = totalSize,
            children = children.sortedBy { it.name }
        )
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

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
            val separator = File.separator
            return c == p ||
                (p == separator && c.startsWith(separator)) ||
                c.startsWith("$p$separator")
        }

        /** 扫描只按扩展名识别类型，不预解码或过滤零字节、损坏及透明图片。 */
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
