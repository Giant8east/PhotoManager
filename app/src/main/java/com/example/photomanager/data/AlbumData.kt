/** 相册数据入口，基于规范化本地文件路径负责目录扫描同步、差分持久化、排序查询与目录删除。 */
package com.example.photomanager.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.data.database.PhotoDatabase
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

class AlbumData @Inject constructor(
    private val database: PhotoDatabase,
    private val directoryScanner: DirectoryScanner
) {
    /** 构造相册查询并持续观察数据库变化。 */
    fun observeAlbums(
        groupId: Long?,
        keyword: String,
        sortField: AlbumSortField,
        isDescending: Boolean
    ): Flow<List<AlbumEntity>> =
        database.albumDao().observeAlbumQuery(buildAlbumQuery(groupId, keyword, sortField, isDescending))

    /** 创建分页相册数据流，与普通查询共用同一 SQL 规则。 */
    fun pageAlbums(
        groupId: Long?,
        keyword: String,
        sortField: AlbumSortField,
        isDescending: Boolean
    ): Flow<PagingData<AlbumEntity>> = Pager(
        config = PagingConfig(pageSize = 30, prefetchDistance = 10, enablePlaceholders = false),
        pagingSourceFactory = {
            database.albumDao().queryAlbumPage(buildAlbumQuery(groupId, keyword, sortField, isDescending))
        }
    ).flow

    private fun buildAlbumQuery(
        groupId: Long?,
        keyword: String,
        sortField: AlbumSortField,
        isDescending: Boolean
    ): SimpleSQLiteQuery {
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
        return SimpleSQLiteQuery(sql, arrayOf(groupId, groupId, searchPattern(keyword)))
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
        val normalized = DirectoryScanner.normalizePath(path)
        database.withTransaction {
            database.albumDao().deleteAlbumsUnderPath(normalized, "$normalized/%")
        }
    }

    /** 持续观察相册的完整图片索引。 */
    fun observePhotos(albumId: Long): Flow<List<PhotoEntity>> =
        database.photoDao().observePhotosQuery(albumId)

    /** 详情分页读取相册图片，阅读页仍使用完整图片索引。 */
    fun pagePhotos(albumId: Long): Flow<PagingData<PhotoEntity>> = Pager(
        PagingConfig(pageSize = 30, initialLoadSize = 30, prefetchDistance = 6, enablePlaceholders = false),
        pagingSourceFactory = { database.photoDao().queryPhotoPage(albumId) }
    ).flow

    /** 持续观察单个相册实体。 */
    fun observeAlbum(albumId: Long): Flow<AlbumEntity?> =
        database.albumDao().observeAlbumByIdQuery(albumId)

    /** 递归同步根目录下所有含图片的相册。 */
    suspend fun syncDirectory(rootPath: String) = withContext(Dispatchers.IO) {
        val rootDir = File(DirectoryScanner.normalizePath(rootPath))
        check(rootDir.isDirectory) { "扫描目录不存在或不可访问：${rootDir.path}" }
        val pending = ArrayDeque<File>()
        pending.add(rootDir)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val currentDir = pending.removeFirst()
            val scanned = directoryScanner.scanAlbum(currentDir)
            if (scanned.photos.isNotEmpty()) {
                saveScannedAlbum(scanned)
            } else {
                database.albumDao().queryAlbumByPath(scanned.path)?.let { emptyExisting ->
                    database.albumDao().deleteAlbum(emptyExisting.id)
                }
            }
            pending.addAll(scanned.children)
        }
    }

    /** 在同一事务中确定新增或差分更新，避免重复导入创建第二条相册记录并保持图片 ID 稳定。 */
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

    /** 按唯一文件路径差分同步图片记录（新增插入、缺失删除、变更更新），并按锚点对齐阅读进度。 */
    private suspend fun updateScannedAlbum(existing: AlbumEntity, scanned: ScannedAlbum) {
        val photoDao = database.photoDao()
        val previous = photoDao.queryPhotosByAlbumId(existing.id)
        val previousByPath = previous.associateBy { it.contentUri }
        val scannedByPath = scanned.photos.associateBy { it.contentUri }

        val removedIds = previous.filter { it.contentUri !in scannedByPath }.map { it.id }
        val addedPhotos = scanned.photos
            .filter { it.contentUri !in previousByPath }
            .map { it.copy(albumId = existing.id) }
        val modifiedPhotos = scanned.photos.mapNotNull { newPhoto ->
            val oldPhoto = previousByPath[newPhoto.contentUri] ?: return@mapNotNull null
            if (samePhotoMetadata(oldPhoto, newPhoto)) null
            else newPhoto.copy(id = oldPhoto.id, albumId = existing.id, width = oldPhoto.width, height = oldPhoto.height)
        }

        if (removedIds.isNotEmpty()) photoDao.deletePhotosByIds(removedIds)
        if (modifiedPhotos.isNotEmpty()) photoDao.updatePhotos(modifiedPhotos)
        if (addedPhotos.isNotEmpty()) photoDao.insertPhotos(addedPhotos)

        val updatedProgress = resolveUpdatedProgress(
            previous = previous,
            scanned = scanned.photos,
            oldIndex = existing.lastViewedPhotoIndex
        )
        val updatedAlbum = existing.copy(
            name = scanned.name,
            modifiedAt = scanned.modifiedAt,
            photoCount = scanned.photos.size,
            coverPath = scanned.photos.firstOrNull()?.contentUri,
            totalSizeBytes = scanned.totalSizeBytes,
            lastViewedPhotoIndex = updatedProgress
        )
        if (updatedAlbum != existing) {
            database.albumDao().updateAlbums(listOf(updatedAlbum))
        }
    }

    /** 仅刷新当前相册，空目录移出相册库，不递归同步子目录。 */
    suspend fun refreshAlbum(album: AlbumEntity): Boolean = withContext(Dispatchers.IO) {
        val targetDir = File(DirectoryScanner.normalizePath(album.albumPath))
        val scanned = directoryScanner.scanAlbum(targetDir)
        if (scanned.photos.isEmpty()) {
            database.albumDao().deleteAlbum(album.id)
            false
        } else {
            saveScannedAlbum(scanned)
            true
        }
    }

    /** 先删除真实本地目录及其文件，再清理 Room 相册记录。 */
    suspend fun delete(albums: List<AlbumEntity>) = withContext(Dispatchers.IO) {
        val directories = albums.map { album ->
            album to File(DirectoryScanner.normalizePath(album.albumPath)).canonicalFile
        }
        directories.forEach { (_, dir) ->
            if (dir.exists()) {
                check(dir.deleteRecursively()) { "目录删除失败：${dir.path}" }
            }
        }
        database.withTransaction {
            albums.forEach { database.albumDao().deleteAlbum(it.id) }
        }
    }

    companion object {
        /** 比较文件元数据是否一致，保留数据库已有 ID 和已解码宽高。 */
        internal fun samePhotoMetadata(old: PhotoEntity, new: PhotoEntity): Boolean =
            old.name == new.name && old.sizeBytes == new.sizeBytes && old.modifiedAt == new.modifiedAt

        /**
         * 根据旧图片列表与旧阅读进度，计算新列表中最匹配的阅读下标：
         * 1. 若原阅读图片仍存在，直接跟随该图片到新下标；
         * 2. 若原阅读图片被移除，按文件名顺序停留在原位置紧邻的图片。
         */
        internal fun resolveUpdatedProgress(
            previous: List<PhotoEntity>,
            scanned: List<PhotoEntity>,
            oldIndex: Int
        ): Int {
            if (scanned.isEmpty()) return 0
            val anchor = previous.getOrNull(oldIndex) ?: return oldIndex.coerceIn(0, scanned.lastIndex)
            val exactIndex = scanned.indexOfFirst { it.contentUri == anchor.contentUri }
            if (exactIndex >= 0) return exactIndex
            val neighborIndex = scanned.indexOfFirst { it.name >= anchor.name }
            return if (neighborIndex >= 0) neighborIndex else scanned.lastIndex
        }
    }
}
