/** 本地目录扫描器：扫描单层图片或遍历目录树；路径运算与 SAF 解析见 util/LocalPaths 与 SafPathResolver。 */
package com.giant8east.photomanager.data.scan

import android.content.Context
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.media.PhotoFormat
import com.giant8east.photomanager.util.LocalPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** 单次目录扫描的结果；[photos] 是本层图片，[children] 是磁盘上的子目录，[isHidden] 表示本层存在 `.nomedia`。 */
internal data class ScannedAlbum(
    val path: String,
    val name: String,
    val modifiedAt: String?,
    val photos: List<PhotoEntity>,
    val totalSizeBytes: Long,
    val children: List<File>,
    val isHidden: Boolean
)

class DirectoryScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
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
        var hasNoMedia = false

        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            if (entry.isDirectory) {
                children += entry
            } else if (entry.isFile && entry.name == NO_MEDIA_FILE_NAME) {
                hasNoMedia = true
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
            children = children.sortedBy { it.name },
            isHidden = hasNoMedia
        )
    }

    /**
     * 深度优先收集 [rootPath] 以及它下面所有子目录的规范路径，目录不存在时返回空列表。
     *
     * 只读目录名，不读文件大小或修改时间，所以比逐个扫描便宜得多；
     * 同步入口拿这份清单决定要扫哪些文件夹，也用它反过来找出数据库里多出来的陈旧记录。
     * 用显式栈而不是递归，避免很深的目录树把调用栈撑爆；[visited] 兜住软链接成环。
     */
    suspend fun collectDirectories(rootPath: String): List<String> = withContext(Dispatchers.IO) {
        val root = File(rootPath)
        if (!root.isDirectory) return@withContext emptyList()

        val collected = ArrayList<String>()
        val visited = HashSet<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val current = stack.removeLast()
            val normalized = LocalPaths.normalize(current.path)
            if (!visited.add(normalized)) continue
            collected.add(normalized)
            // 目录存在但读不到列表时必须中止整次同步，不能用 .orEmpty() 跳过——
            // 否则下游 unindexVanished 会把这棵子树当成"磁盘已删"清掉相册记录
            val entries = current.listFiles()
                ?: error(context.getString(R.string.directory_read_failed, normalized))
            for (child in entries) {
                if (child.isDirectory) stack.addLast(child)
            }
        }
        collected
    }

    private companion object {
        const val NO_MEDIA_FILE_NAME = ".nomedia"

        /** 扫描只按扩展名识别类型，与删除器共用 [PhotoFormat] 作为唯一口径，不预解码或过滤零字节、损坏及透明图片。 */
        fun isImageFile(file: File): Boolean =
            PhotoFormat.fromFileName(file.name) != PhotoFormat.OTHER

        fun formatEpochMillis(millis: Long): String? =
            millis.takeIf { it > 0L }?.let(Instant::ofEpochMilli)?.toString()
    }
}
