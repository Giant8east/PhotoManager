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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AlbumData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: PhotoDatabase,
    private val directoryScanner: DirectoryScanner
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

    /** 创建分页相册数据流，分页规则与普通查询保持一致。 */
    fun pageAlbums(
        groupId: Long?,
        keyword: String,
        sortField: AlbumSortField,
        isDescending: Boolean
    ): Flow<PagingData<AlbumEntity>> {
        val direction = if (isDescending) "DESC" else "ASC"
        val nullOrder = if (sortField == AlbumSortField.MODIFIED_AT) {
            "julianday(modifiedAt) IS NULL ASC, "
        } else ""
        val sql = """
            SELECT * FROM albums
            WHERE (? IS NULL OR groupId = ?)
              AND name LIKE ? ESCAPE '\' COLLATE NOCASE
            ORDER BY $nullOrder${sortField.sqlExpression} $direction, albumPath ASC
        """.trimIndent()
        return Pager(
            config = PagingConfig(pageSize = 30, prefetchDistance = 10, enablePlaceholders = false),
            pagingSourceFactory = {
                database.albumDao().queryAlbumPage(
                    SimpleSQLiteQuery(sql, arrayOf(groupId, groupId, searchPattern(keyword)))
                )
            }
        ).flow
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

    /** 保存相册最近一次浏览到的图片位置。 */
    suspend fun updateReadingProgress(albumId: Long, photoIndex: Int) =
        database.albumDao().updateAlbumReadingProgress(albumId, photoIndex)


    /** 删除指定扫描目录及其子目录对应的相册记录。 */
    suspend fun deleteAlbumsUnderPath(path: String) {
        database.withTransaction {
            database.albumDao().deleteAlbumsUnderPath("$path%")
        }
    }

    /** 持续观察相册的完整图片索引，仅列表实际变化时通知上层。 */
    fun observePhotos(albumId: Long): Flow<List<PhotoEntity>> =
        database.photoDao().observePhotosQuery(albumId)

    /** 详情只读取已加载分页，阅读页仍保留完整图片索引。 */
    fun pagePhotos(albumId: Long): Flow<PagingData<PhotoEntity>> = Pager(
        PagingConfig(pageSize = 30, initialLoadSize = 30, prefetchDistance = 6, enablePlaceholders = false),
        pagingSourceFactory = { database.photoDao().queryPhotoPage(albumId) }
    ).flow

    /** 持续观察某一个相册 */
    fun observeAlbum(albumId: Long) = database.albumDao().observeAlbumByIdQuery(albumId)

    /** 同步树中每个相册；扫描细节由 DirectoryScanner 负责。 */
    suspend fun syncDirectory(rootUri: Uri) = withContext(Dispatchers.IO) {
        val pending = ArrayDeque<Uri>()
        pending.add(rootUri)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val scanned = directoryScanner.scanAlbum(pending.removeFirst())
            if (scanned.photos.isNotEmpty()) saveScannedAlbum(scanned)
            pending.addAll(scanned.children)
        }
    }
    /** 在同一事务中确定新增或更新，避免重复导入创建第二条相册记录。 */
    private suspend fun saveScannedAlbum(scanned: ScannedAlbum) = database.withTransaction {
        val existing = database.albumDao().queryAlbumByPath(scanned.path)
        if (existing == null) {
            insertScannedAlbum(scanned)
        } else {
            updateScannedAlbum(existing, scanned)
        }
    }

    /** 新相册由数据库生成 ID，再用该 ID 关联图片。 */
    private suspend fun insertScannedAlbum(scanned: ScannedAlbum) {
        val albumId = database.albumDao().insertAlbum(
            AlbumEntity(
                name = scanned.name,
                albumPath = scanned.path,
                modifiedAt = scanned.modifiedAt,
                photoCount = scanned.photos.size,
                coverPath = scanned.photos.firstOrNull()?.contentUri,
                totalSizeBytes = scanned.totalSizeBytes,
                sortOrder = System.currentTimeMillis()
            )
        )
        database.photoDao().insertPhotos(scanned.photos.map { it.copy(albumId = albumId) })
    }

    /** 摘要变化保留进度；图片内容或容量变化才替换索引并清零进度。 */
    private suspend fun updateScannedAlbum(existing: AlbumEntity, scanned: ScannedAlbum) {
        val photoDao = database.photoDao()
        val previous = photoDao.queryPhotosByAlbumId(existing.id)
        val contentChanged = existing.totalSizeBytes != scanned.totalSizeBytes ||
            previous.size != scanned.photos.size ||
            previous.zip(scanned.photos).any { (old, new) -> !samePhotoFile(old, new) }
        val updated = existing.copy(
            name = scanned.name,
            modifiedAt = scanned.modifiedAt,
            photoCount = scanned.photos.size,
            coverPath = scanned.photos.firstOrNull()?.contentUri,
            totalSizeBytes = scanned.totalSizeBytes,
            lastViewedPhotoIndex = if (contentChanged) 0 else existing.lastViewedPhotoIndex
        )
        if (updated != existing) database.albumDao().updateAlbums(listOf(updated))
        if (!contentChanged) return
        photoDao.deletePhotosByAlbumId(existing.id)
        photoDao.insertPhotos(scanned.photos.map { it.copy(albumId = existing.id) })
    }

    /** 比较文件身份和扫描元数据，不比较数据库 ID 或解码得到的尺寸。 */
    private fun samePhotoFile(old: PhotoEntity, new: PhotoEntity): Boolean =
        old.contentUri == new.contentUri && old.name == new.name &&
            old.sizeBytes == new.sizeBytes && old.modifiedAt == new.modifiedAt

    /** 仅刷新当前相册，空目录移出相册库，不递归同步子目录。 */
    suspend fun refreshAlbum(album: AlbumEntity): Boolean = withContext(Dispatchers.IO) {
        val scanned = directoryScanner.scanAlbum(Uri.parse(album.albumPath))
        if (scanned.photos.isEmpty()) {
            database.albumDao().deleteAlbum(album.id)
            false
        } else {
            saveScannedAlbum(scanned)
            true
        }
    }

    /** 先删除 SAF 授权目录及其文件，再清理 Room 相册记录。 */
    suspend fun delete(albums: List<AlbumEntity>) = withContext(Dispatchers.IO) {
        val documents = albums.map { album ->
            val tree = Uri.parse(album.albumPath)
            require(DocumentsContract.isTreeUri(tree)) { "此相册尚未获得目录删除授权" }
            val documentId = if (DocumentsContract.isDocumentUri(context, tree)) {
                DocumentsContract.getDocumentId(tree)
            } else {
                DocumentsContract.getTreeDocumentId(tree)
            }
            tree to DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        }
        documents.forEach { (tree, document) ->
            check(
                DocumentsContract.deleteDocument(
                    context.contentResolver,
                    document
                )
            ) { "目录删除失败：${tree}" }
        }
        database.withTransaction {
            albums.forEach { database.albumDao().deleteAlbum(it.id) }
        }
    }
}

