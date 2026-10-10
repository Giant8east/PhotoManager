/** 相册数据入口，负责目录读取、排序、持久化和目录删除。 */
package com.example.photomanager.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.room.withTransaction
import com.example.photomanager.data.database.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AlbumData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: PhotoDatabase
) {
    /** 构造相册查询并持续观察数据库变化。 */
    fun observeAlbums(
        groupId: Long?,
        keyword: String,
        sortField: AlbumSortField,
        isDescending: Boolean
    ): Flow<List<AlbumEntity>> {
        val direction = if (isDescending) "DESC" else "ASC"
        // 未知修改时间始终放在最后，同值相册按路径保持稳定顺序。
        val nullOrder = if (sortField == AlbumSortField.MODIFIED_AT) {
            "julianday(modifiedAt) IS NULL ASC, "
        } else ""
        val sql = """
            SELECT * FROM albums
            WHERE (? IS NULL OR groupId = ?)
              AND name LIKE ? ESCAPE '\' COLLATE NOCASE
            ORDER BY $nullOrder${sortField.sqlExpression} $direction, albumPath ASC
        """.trimIndent()
        val query = SimpleSQLiteQuery(sql, arrayOf(groupId, groupId, searchPattern(keyword)))
        return database.albumDao().observeAlbumQuery(query)
    }

    /** 将关键词转换为 LIKE 的包含匹配模式。 */
    private fun searchPattern(keyword: String): String {
        val escaped = keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    /** 将 ISO 时间格式化为页面显示文本。 */
    fun displayTime(modifiedAt: String?): String = modifiedAt?.let {
        DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault())
            .format(Instant.parse(it))
    } ?: "修改时间未知"

    /** 批量移动相册到目标分组。 */
    suspend fun move(albums: List<AlbumEntity>, groupId: Long?) =
        database.albumDao().updateAlbums(albums.map { it.copy(groupId = groupId) })

    /** 持久化相册的自定义顺序。 */
    suspend fun reorder(albums: List<AlbumEntity>) = database.albumDao()
        .updateAlbums(albums.mapIndexed { index, album -> album.copy(sortOrder = index.toLong()) })

    /** 递归同步根目录及子目录；空目录跳过，数据库只写入新增或变化的相册。 */
    suspend fun syncDirectory(rootUri: Uri) = withContext(Dispatchers.IO) {
        val scannedAlbums = scanDirectories(rootUri)
        database.withTransaction {
            val albumDao = database.albumDao()
            val existingAlbums = albumDao.queryAllAlbums().associateBy { it.albumPath }
            val newAlbums = scannedAlbums.mapNotNull { scanned ->
                val existing = existingAlbums[scanned.path]
                if (existing == null) {
                    AlbumEntity(
                        name = scanned.name,
                        modifiedAt = scanned.modifiedAt,
                        photoCount = scanned.imageUris.size,
                        coverPath = scanned.imageUris.firstOrNull(),
                        albumPath = scanned.path,
                        sortOrder = System.currentTimeMillis()
                    )
                } else if (
                    existing.name != scanned.name ||
                    existing.modifiedAt != scanned.modifiedAt ||
                    existing.photoCount != scanned.imageUris.size ||
                    existing.coverPath != scanned.imageUris.firstOrNull()
                ) {
                    albumDao.updateAlbums(
                        listOf(
                            existing.copy(
                                name = scanned.name,
                                modifiedAt = scanned.modifiedAt,
                                photoCount = scanned.imageUris.size,
                                coverPath = scanned.imageUris.firstOrNull()
                            )
                        )
                    )
                    null
                } else null
            }
            if (newAlbums.isNotEmpty()) albumDao.insertAlbums(newAlbums)
        }
    }

    /** 表示一次目录扫描得到的相册信息。 */
    private data class ScannedAlbum(
        val path: String,
        val name: String,
        val modifiedAt: String?,
        val imageUris: List<String>
    )

    /** 递归扫描目录并收集含图片的目录。 */
    private fun scanDirectories(rootUri: Uri): List<ScannedAlbum> {
        val result = mutableListOf<ScannedAlbum>()
        val pending = ArrayDeque<Uri>()
        pending.add(rootUri)
        val resolver = context.contentResolver
        while (pending.isNotEmpty()) {
            val directoryUri = pending.removeFirst()
            val documentId = DocumentsContract.getTreeDocumentId(directoryUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(directoryUri, documentId)
            val metadata = resolver.query(
                documentUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
                ), null, null, null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) null else cursor.getString(0) to
                        if (cursor.isNull(1)) null else cursor.getLong(1)
                            .takeIf { it > 0 }?.let(Instant::ofEpochMilli)?.toString()
            } ?: continue
            val childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(directoryUri, documentId)
            val images = mutableListOf<String>()
            val childDirectories = mutableListOf<String>()
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ), null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val childUri =
                        DocumentsContract.buildDocumentUriUsingTree(directoryUri, childId)
                    if (cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        childDirectories += childId
                    } else if (cursor.getString(1)?.startsWith("image/") == true) {
                        images += childUri.toString()
                    }
                }
            }
            if (images.isNotEmpty()) result += ScannedAlbum(
                directoryUri.toString(), metadata.first, metadata.second, images.sorted()
            )
            childDirectories.forEach { childId ->
                pending.add(DocumentsContract.buildDocumentUriUsingTree(directoryUri, childId))
            }
        }
        return result
    }

    /** 导入单个目录并写入相册记录。 */
    suspend fun importDirectory(tree: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val document = DocumentsContract.buildDocumentUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val directory = resolver.query(
            document,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val modifiedAt = if (cursor.isNull(1)) null else cursor.getLong(1)
                .takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }
            cursor.getString(0) to modifiedAt
        } ?: error("目录不可用")
        val (name, modifiedAt) = directory
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree)
        )
        val images = mutableListOf<String>()
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null,
            null,
            null
        )?.use { cursor ->
            val records = mutableListOf<Pair<String, String>>()
            while (cursor.moveToNext()) if (cursor.getString(1)
                    ?.startsWith("image/") == true
            ) records += cursor.getString(2) to DocumentsContract.buildDocumentUriUsingTree(
                tree,
                cursor.getString(0)
            ).toString()
            images += records.sortedBy { it.first }.map { it.second }
        }
        val albumPath = tree.toString()
        database.withTransaction {
            val albumDao = database.albumDao()
            val existing = albumDao.queryAlbumByPath(albumPath)
            if (existing == null) {
                albumDao.insertAlbum(
                    AlbumEntity(
                        name = name,
                        modifiedAt = modifiedAt,
                        albumPath = albumPath,
                        coverPath = images.firstOrNull(),
                        photoCount = images.size,
                        sortOrder = System.currentTimeMillis()
                    )
                )
            } else {
                albumDao.updateAlbums(
                    listOf(
                        existing.copy(
                            name = name,
                            modifiedAt = modifiedAt,
                            coverPath = images.firstOrNull(),
                            photoCount = images.size
                        )
                    )
                )
            }
        }
    }

    /** 删除真实目录并清理相册记录。 */
    suspend fun delete(albums: List<AlbumEntity>) = withContext(Dispatchers.IO) {
        for (album in albums) {
            val tree = Uri.parse(album.albumPath)
            require(DocumentsContract.isTreeUri(tree)) { "此相册尚未获得目录删除授权" }
            val document = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree)
            )
            check(
                DocumentsContract.deleteDocument(
                    context.contentResolver,
                    document
                )
            ) { "目录删除失败：${album.name}" }
            database.albumDao().deleteAlbum(album.id)
        }
    }
}
