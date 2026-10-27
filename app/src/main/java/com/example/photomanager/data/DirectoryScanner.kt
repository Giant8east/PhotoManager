/** SAF 目录读取边界：把 URI、提供器返回值和单层图片记录归一化为同步输入。 */
package com.example.photomanager.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.photomanager.data.database.PhotoEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import javax.inject.Inject

data class ScannedAlbum(
    val path: String,
    val name: String,
    val modifiedAt: String?,
    val photos: List<PhotoEntity>,
    val totalSizeBytes: Long?,
    val children: List<Uri>
)

class DirectoryScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun scanAlbum(directory: Uri): ScannedAlbum {
        val id = if (DocumentsContract.isDocumentUri(context, directory))
            DocumentsContract.getDocumentId(directory)
        else DocumentsContract.getTreeDocumentId(directory)
        val document = DocumentsContract.buildDocumentUriUsingTree(directory, id)
        val metadata = queryMetadata(document)
        val children = mutableListOf<Uri>()
        val photos = mutableListOf<PhotoEntity>()
        var totalSize = 0L
        var sizeKnown = true
        val cursor = context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(directory, id),
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ), null, null, null
        ) ?: error("无法读取目录内容")
        cursor.use {
            while (it.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val childUri = DocumentsContract.buildDocumentUriUsingTree(directory, it.getString(0))
                if (it.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    children += childUri
                    continue
                }
                if (it.getString(1)?.startsWith("image/") != true) continue
                val bytes = if (it.isNull(3)) {
                    sizeKnown = false
                    0L
                } else it.getLong(3).coerceAtLeast(0L)
                totalSize += bytes
                photos += PhotoEntity(
                    albumId = 0,
                    name = it.getString(2),
                    contentUri = childUri.toString(),
                    modifiedAt = nullableTime(it, 4),
                    sizeBytes = bytes
                )
            }
        }
        return ScannedAlbum(
            path = directory.toString(), name = metadata.first, modifiedAt = metadata.second,
            photos = photos.sortedBy { it.name },
            totalSizeBytes = totalSize.takeIf { sizeKnown }, children = children
        )
    }

    private fun queryMetadata(document: Uri): Pair<String, String?> {
        val cursor = context.contentResolver.query(
            document,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
            ), null, null, null
        ) ?: error("目录不可用")
        cursor.use {
            check(it.moveToFirst()) { "目录不可用" }
            return it.getString(0) to nullableTime(it, 1)
        }
    }

    private fun nullableTime(cursor: android.database.Cursor, column: Int): String? =
        if (cursor.isNull(column)) null else cursor.getLong(column)
            .takeIf { it > 0 }?.let(Instant::ofEpochMilli)?.toString()
}
