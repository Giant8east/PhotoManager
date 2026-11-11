/** 相册数据入口，基于规范化本地文件路径负责目录扫描同步、差分持久化、排序查询与目录删除。 */
package com.example.photomanager.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.photomanager.data.database.AlbumDetailRecord
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.data.database.PhotoDatabase
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlbumData @Inject constructor(
    private val database: PhotoDatabase,
    private val directoryScanner: DirectoryScanner,
    private val deletionGate: DeletionGate
) {
    /** 构造相册查询并持续观察数据库变化。 */
    fun observeAlbums(query: AlbumQuery): Flow<List<AlbumEntity>> =
        database.albumDao().observeAlbumQuery(buildAlbumQuery(query))

    /** 持续观察未分组相册数量。 */
    fun observeUngroupedAlbumCount(): Flow<Int> =
        database.groupDao().observeUngroupedAlbumCount()

    /** 将查询条件转换为排序与筛选 SQL；列名来自枚举，不拼接外部输入。 */
    private fun buildAlbumQuery(query: AlbumQuery): SimpleSQLiteQuery {
        val direction = if (query.isDescending) "DESC" else "ASC"
        // 未知修改时间始终放在最后，同值相册按路径保持稳定顺序。
        val nullOrder = if (query.sortField == AlbumSortField.MODIFIED_AT) {
            "julianday(modifiedAt) IS NULL ASC, "
        } else ""
        val filterClause: String
        val filterArgs: Array<Any?>
        when {
            // 收藏视图跨分组，不再叠加分组条件。
            query.favoritesOnly -> {
                filterClause = "AND isFavorite = 1"
                filterArgs = emptyArray()
            }
            query.groupId == null -> {
                filterClause = "AND groupId IS NULL"
                filterArgs = emptyArray()
            }
            else -> {
                filterClause = "AND groupId = ?"
                filterArgs = arrayOf(query.groupId)
            }
        }
        // 自定义排序是视图级的：收藏视图用自己的列，避免和分组序号互相覆盖。
        val orderExpression = if (
            query.sortField == AlbumSortField.CUSTOM && query.favoritesOnly
        ) {
            "favoriteSortOrder"
        } else {
            query.sortField.sqlExpression
        }
        val sql = """
            SELECT * FROM albums
            WHERE name LIKE ? ESCAPE '\' COLLATE NOCASE
            $filterClause
            ORDER BY $nullOrder$orderExpression $direction, albumPath ASC
        """.trimIndent()
        return SimpleSQLiteQuery(
            sql,
            arrayOf(searchPattern(query.keyword), *filterArgs)
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

    /**
     * 保存当前视图的自定义顺序：分组/未分组视图写 [AlbumEntity.sortOrder]，
     * 收藏视图写 [AlbumEntity.favoriteSortOrder]，两种列表因此各自独立。
     */
    suspend fun reorder(albums: List<AlbumEntity>, favoritesView: Boolean) = database.albumDao()
        .updateAlbums(
            albums.mapIndexed { index, album ->
                if (favoritesView) {
                    album.copy(favoriteSortOrder = index.toLong())
                } else {
                    album.copy(sortOrder = index.toLong())
                }
            }
        )

    /** 切换收藏状态；收藏时取当前时间作为收藏视图排序值，追加到列表末尾。 */
    suspend fun setFavorite(albumId: Long, favorite: Boolean) {
        val dao = database.albumDao()
        if (favorite) {
            dao.favoriteAlbum(albumId, System.currentTimeMillis())
        } else {
            dao.unfavoriteAlbum(albumId)
        }
    }

    /** 持续观察已收藏且仍有图片的相册数量。 */
    fun observeFavoriteCount(): Flow<Int> = database.albumDao().observeFavoriteCountQuery()

    /** 保存相册最近一次浏览到的图片位置。 */
    suspend fun updateReadingProgress(albumId: Long, photoIndex: Int) =
        database.albumDao().updateAlbumReadingProgress(albumId, photoIndex)

    /** 删除指定扫描目录下由调用方确认的相册记录；文件删除由更高层协调器负责。 */
    suspend fun deleteAlbumsByIds(ids: List<Long>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            ids.forEach { database.albumDao().deleteAlbum(it) }
        }
    }

    /** 删除指定扫描目录及其子目录对应的相册记录。 */
    suspend fun deleteAlbumsUnderPath(path: String) = withContext(Dispatchers.IO) {
        val normalized = DirectoryScanner.normalizePath(path)
        val ticket = deletionGate.beginRefresh(normalized) ?: return@withContext
        deletionGate.commit(ticket) {
            database.albumDao().deleteAlbumsUnderPath(normalized, childPathPrefix(normalized))
        }
    }

    /** 持续观察相册的完整图片索引。 */
    fun observePhotos(albumId: Long): Flow<List<PhotoEntity>> =
        database.photoDao().observePhotosQuery(albumId).distinctUntilChanged()

    /** 持续观察相册及其完整图片索引，单个事务查询保证相册与图片来自同一快照。 */
    fun observeAlbumDetail(albumId: Long): Flow<AlbumDetailRecord?> =
        database.albumDao().observeAlbumDetailQuery(albumId)
            .map { record -> record?.copy(photos = record.photos.sortedBy { it.name }) }
            .distinctUntilChanged()

    /** 遍历同步整个根目录；目录扫描最多并行 [MAX_CONCURRENT_DIRECTORIES] 个。 */
    suspend fun syncDirectory(rootPath: String) = withContext(Dispatchers.IO) {
        val normalizedRoot = DirectoryScanner.normalizePath(rootPath)
        val remainingPaths = ConcurrentHashMap.newKeySet<String>().apply {
            addAll(
                database.albumDao().queryAlbumPathsUnderPath(
                    normalizedRoot,
                    childPathPrefix(normalizedRoot)
                )
            )
        }
        val visitedPaths = ConcurrentHashMap.newKeySet<String>()
        val limiter = Semaphore(MAX_CONCURRENT_DIRECTORIES)

        coroutineScope {
            val scope = this

            fun schedule(path: String) {
                if (!visitedPaths.add(path)) return
                scope.launch {
                    limiter.withPermit {
                        currentCoroutineContext().ensureActive()
                        val outcome = refreshDirectory(path)
                        remainingPaths.remove(path)
                        if (outcome is RefreshOutcome.Scanned) {
                            outcome.album?.children?.forEach { child ->
                                schedule(DirectoryScanner.normalizePath(child.path))
                            }
                        }
                    }
                }
            }

            schedule(normalizedRoot)
        }

        coroutineScope {
            remainingPaths.forEach { stalePath ->
                launch {
                    limiter.withPermit {
                        currentCoroutineContext().ensureActive()
                        refreshDirectory(stalePath)
                    }
                }
            }
        }
    }

    /** 单相册刷新入口：返回刷新后目录是否仍有图片；删除中的路径不产生数据库写入。 */
    suspend fun refreshAlbum(path: String): Boolean = withContext(Dispatchers.IO) {
        when (val outcome = refreshDirectory(DirectoryScanner.normalizePath(path))) {
            is RefreshOutcome.Scanned -> outcome.album?.photos?.isNotEmpty() == true
            RefreshOutcome.Skipped -> true
        }
    }

    /** 扫描单个目录并提交；删除屏障命中时返回 Skipped，不删除也不写入记录。 */
    private suspend fun refreshDirectory(path: String): RefreshOutcome {
        val normalized = DirectoryScanner.normalizePath(path)
        val ticket = deletionGate.beginRefresh(normalized) ?: return RefreshOutcome.Skipped
        val scanned = directoryScanner.scanAlbum(File(normalized))
        val committed = deletionGate.commit(ticket) {
            if (scanned == null || scanned.photos.isEmpty()) {
                database.albumDao().deleteAlbumByPath(normalized)
            } else {
                saveScannedAlbum(scanned)
            }
        }
        return if (committed) RefreshOutcome.Scanned(scanned) else RefreshOutcome.Skipped
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

    /** 扫描完成但被删除屏障拒绝写入时的内部结果。 */
    private sealed interface RefreshOutcome {
        data class Scanned(val album: ScannedAlbum?) : RefreshOutcome
        data object Skipped : RefreshOutcome
    }

    companion object {
        /** 同时进行的目录扫描上限，避免大量子目录同时占用 IO 和 Room 事务。 */
        private const val MAX_CONCURRENT_DIRECTORIES = 4

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
