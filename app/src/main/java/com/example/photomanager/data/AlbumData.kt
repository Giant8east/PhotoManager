/** 相册数据入口，基于规范化本地文件路径负责目录扫描同步、差分持久化、排序查询与目录删除。 */
package com.example.photomanager.data

import android.content.Context
import com.example.photomanager.R
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.photomanager.data.database.AlbumDetailRecord
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.data.database.PhotoDatabase
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

class AlbumData @Inject constructor(
    private val database: PhotoDatabase,
    private val directoryScanner: DirectoryScanner,
    @ApplicationContext private val context: Context
) {
    /** 构造相册查询并持续观察数据库变化。 */
    fun observeAlbums(query: AlbumQuery): Flow<List<AlbumEntity>> =
        database.albumDao().observeAlbumQuery(buildAlbumQuery(query))

    /** 将查询条件转换为排序与筛选 SQL；列名来自枚举，不拼接外部输入。 */
    private fun buildAlbumQuery(query: AlbumQuery): SimpleSQLiteQuery {
        val direction = if (query.isDescending) "DESC" else "ASC"
        // 未知修改时间始终放在最后，同值相册按路径保持稳定顺序。
        val nullOrder = if (query.sortField == AlbumSortField.MODIFIED_AT) {
            "julianday(modifiedAt) IS NULL ASC, "
        } else ""
        val groupClause = when (query.groupFilter) {
            AlbumGroupFilter.All -> ""
            AlbumGroupFilter.Ungrouped -> "AND groupId IS NULL"
            is AlbumGroupFilter.Group -> "AND groupId = ?"
        }
        val groupArgs: Array<Any?> = when (val filter = query.groupFilter) {
            AlbumGroupFilter.All, AlbumGroupFilter.Ungrouped -> emptyArray()
            is AlbumGroupFilter.Group -> arrayOf(filter.id)
        }
        val sql = """
            SELECT * FROM albums
            WHERE name LIKE ? ESCAPE '\' COLLATE NOCASE
            $groupClause
            ORDER BY $nullOrder${query.sortField.sqlExpression} $direction, albumPath ASC
        """.trimIndent()
        return SimpleSQLiteQuery(
            sql,
            arrayOf<Any?>(searchPattern(query.keyword), *groupArgs)
        )
    }

    /** 转义 SQLite LIKE 模式中的特殊通配符。 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 将关键词转换为 LIKE 的包含匹配模式。 */
    private fun searchPattern(keyword: String): String = "%${escapeLike(keyword)}%"

    /** 生成指定规范路径的子目录精确前缀（区分大小写）。 */
    private fun childPathPrefix(normalizedPath: String): String =
        if (normalizedPath == "/") "/" else "$normalizedPath/"

    /** 批量移动相册到目标分组；null 表示默认相册。 */
    suspend fun move(albums: List<AlbumEntity>, groupId: Long?) =
        database.albumDao().updateAlbums(albums.map { it.copy(groupId = groupId) })

    /** 持久化相册的自定义顺序。 */
    suspend fun reorder(albums: List<AlbumEntity>) = database.albumDao()
        .updateAlbums(albums.mapIndexed { index, album -> album.copy(sortOrder = index.toLong()) })

    /** 保存相册最近一次浏览到的图片位置。 */
    suspend fun updateReadingProgress(albumId: Long, photoIndex: Int) =
        database.albumDao().updateAlbumReadingProgress(albumId, photoIndex)

    /** 删除指定扫描目录及其子目录对应的相册记录。 */
    suspend fun deleteAlbumsUnderPath(path: String) = withContext(Dispatchers.IO) {
        val normalized = DirectoryScanner.normalizePath(path)
        database.albumDao().deleteAlbumsUnderPath(normalized, childPathPrefix(normalized))
    }

    /** 持续观察相册的完整图片索引。 */
    fun observePhotos(albumId: Long): Flow<List<PhotoEntity>> =
        database.photoDao().observePhotosQuery(albumId).distinctUntilChanged()

    /** 持续观察相册及其完整图片索引，单个事务查询保证相册与图片来自同一快照。 */
    fun observeAlbumDetail(albumId: Long): Flow<AlbumDetailRecord?> =
        database.albumDao().observeAlbumDetailQuery(albumId)
            .map { record -> record?.copy(photos = record.photos.sortedBy { it.name }) }
            .distinctUntilChanged()

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
            val scanned = refreshDirectory(currentPath)
            remainingPaths.remove(currentPath)
            if (scanned != null) {
                pending.addAll(scanned.children.map { DirectoryScanner.normalizePath(it.path) })
            }
        }

        for (stalePath in remainingPaths) {
            currentCoroutineContext().ensureActive()
            refreshDirectory(stalePath)
        }
    }

    /** 单相册刷新入口：返回刷新后目录是否仍有图片，扫描细节不跨层暴露。 */
    suspend fun refreshAlbum(path: String): Boolean = withContext(Dispatchers.IO) {
        refreshDirectory(DirectoryScanner.normalizePath(path))?.photos?.isNotEmpty() == true
    }

    /** 扫描单个目录；目录不存在或没有图片时清理对应记录。 */
    private suspend fun refreshDirectory(path: String): ScannedAlbum? {
        val scanned = directoryScanner.scanAlbum(File(path))
        if (scanned == null || scanned.photos.isEmpty()) {
            database.albumDao().deleteAlbumByPath(path)
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
                coverPath = scanned.photos.first().contentUri,
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
            coverPath = scanned.photos.first().contentUri,
            totalSizeBytes = scanned.totalSizeBytes,
            lastViewedPhotoIndex = updatedProgress
        )
        if (updatedAlbum != existing) {
            database.albumDao().updateAlbums(listOf(updatedAlbum))
        }
    }

    /** 先删除真实本地目录及其文件，再清理 Room 相册记录。 */
    suspend fun delete(albums: List<AlbumEntity>) = withContext(Dispatchers.IO) {
        albums.forEach { album ->
            val directory = File(DirectoryScanner.normalizePath(album.albumPath))
            if (directory.exists()) {
                check(directory.deleteRecursively()) {
                    context.getString(R.string.directory_delete_failed, directory.path)
                }
            }
        }
        database.withTransaction {
            albums.forEach { database.albumDao().deleteAlbum(it.id) }
        }
    }

    companion object {
        /** 比较文件元数据是否一致，保留数据库已有 ID 与图片记录。 */
        internal fun samePhotoMetadata(old: PhotoEntity, new: PhotoEntity): Boolean =
            old.sizeBytes == new.sizeBytes && old.modifiedAt == new.modifiedAt

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
