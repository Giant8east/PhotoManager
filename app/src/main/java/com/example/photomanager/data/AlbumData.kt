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
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /** 转义 SQLite LIKE 模式中的特殊通配符。 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 将关键词转换为 LIKE 的包含匹配模式。 */
    private fun searchPattern(keyword: String): String = "%${escapeLike(keyword)}%"

    /** 生成指定规范路径的子目录精确前缀（区分大小写）。 */
    private fun childPathPrefix(normalizedPath: String): String =
        if (normalizedPath == "/") "/" else "$normalizedPath/"

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

    /** 仅删除指定精确路径的相册记录，关联图片由外键级联清理。 */
    suspend fun deleteAlbumRecord(path: String) {
        val normalized = DirectoryScanner.normalizePath(path)
        database.albumDao().deleteAlbumByPath(normalized)
    }

    /** 删除指定扫描目录及其子目录对应的相册记录。 */
    suspend fun deleteAlbumsUnderPath(path: String) {
        val normalized = DirectoryScanner.normalizePath(path)
        database.withTransaction {
            database.albumDao().deleteAlbumsUnderPath(normalized, childPathPrefix(normalized))
        }
    }

    /** 持续观察相册的完整图片索引。 */
    fun observePhotos(albumId: Long): Flow<List<PhotoEntity>> =
        database.photoDao().observePhotosQuery(albumId).distinctUntilChanged()

    /** 持续观察单个相册实体。 */
    fun observeAlbum(albumId: Long): Flow<AlbumEntity?> =
        database.albumDao().observeAlbumByIdQuery(albumId).distinctUntilChanged()

    /** 遍历同步整个根目录，并对数据库已有但未在本次遍历中发现的旧路径进行补充对账。 */
    suspend fun syncDirectory(rootPath: String) = withContext(Dispatchers.IO) {
        val normalizedRoot = DirectoryScanner.normalizePath(rootPath)
        val remainingPaths = database.albumDao()
            .queryAlbumPathsUnderPath(normalizedRoot, childPathPrefix(normalizedRoot))
            .toMutableSet()
        val pending = ArrayDeque<String>()
        pending.add(normalizedRoot)

        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val currentPath = pending.removeFirst()
            val scanned = refreshDirectory(currentPath, knownPaths = remainingPaths)
            remainingPaths.remove(currentPath)
            if (scanned != null) {
                pending.addAll(scanned.children.map { DirectoryScanner.normalizePath(it.path) })
            }
        }

        for (stalePath in remainingPaths) {
            currentCoroutineContext().ensureActive()
            refreshDirectory(stalePath, knownPaths = remainingPaths)
        }
    }

    /** 单相册刷新入口：规范化一次路径后，复用同步使用的单目录处理。 */
    suspend fun refreshAlbum(path: String): ScannedAlbum? = withContext(Dispatchers.IO) {
        refreshDirectory(DirectoryScanner.normalizePath(path))
    }

    /** 路径已由入口或入队时规范化；扫描与记录清理沿用同一条路径。 */
    private suspend fun refreshDirectory(path: String, knownPaths: Set<String>? = null): ScannedAlbum? {
        val scanned = directoryScanner.scanAlbum(File(path))
        if (scanned == null || scanned.photos.isEmpty()) {
            if (knownPaths == null || path in knownPaths) {
                database.albumDao().deleteAlbumByPath(path)
            }
        } else {
            saveScannedAlbum(scanned)
        }
        return scanned
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

    /** 先删除真实本地目录及其文件，再清理 Room 相册记录。 */
    suspend fun delete(albums: List<AlbumEntity>) = withContext(Dispatchers.IO) {
        val directories = albums.map { album ->
            album to File(DirectoryScanner.normalizePath(album.albumPath))
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
