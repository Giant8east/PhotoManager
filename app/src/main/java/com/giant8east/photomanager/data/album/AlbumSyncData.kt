/** 相册同步入口：目录扫描、差分持久化与阅读进度对齐，只负责"把文件系统状态写进数据库"。 */
package com.giant8east.photomanager.data.album

import androidx.room.withTransaction
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.PhotoDatabase
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.scan.DirectoryScanner
import com.giant8east.photomanager.data.scan.ScannedAlbum
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Singleton
class AlbumSyncData @Inject constructor(
    private val database: PhotoDatabase,
    private val directoryScanner: DirectoryScanner
) {
    /** 遍历同步整个根目录；目录扫描最多并行 [MAX_CONCURRENT_DIRECTORIES] 个。 */
    suspend fun syncDirectory(rootPath: String) = withContext(Dispatchers.IO) {
        val normalizedRoot = DirectoryScanner.normalizePath(rootPath)
        val remainingPaths = ConcurrentHashMap.newKeySet<String>().apply {
            addAll(
                database.albumDao().queryAlbumPathsUnderPath(
                    normalizedRoot,
                    DirectoryScanner.childPathPrefix(normalizedRoot)
                )
            )
        }
        val visitedPaths = ConcurrentHashMap.newKeySet<String>()    // 保证各自的单次操作线程安全
        val limiter = Semaphore(MAX_CONCURRENT_DIRECTORIES)

        coroutineScope {
            val scope = this

            fun schedule(path: String) {
                if (!visitedPaths.add(path)) return
                scope.launch {
                    limiter.withPermit {
                        currentCoroutineContext().ensureActive()
                        val scanned = scanAndWrite(path)
                        remainingPaths.remove(path)
                        scanned?.children?.forEach { child ->
                            schedule(DirectoryScanner.normalizePath(child.path))
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
                        scanAndWrite(stalePath)
                    }
                }
            }
        }
    }

    /** 单相册刷新入口：返回刷新后目录是否仍有图片。 */
    suspend fun refreshAlbum(path: String): Boolean = scanFolder(path)

    /**
     * 扫描单个文件夹并把结果写进数据库；返回该文件夹是否仍有图片。
     *
     * 调度器的扫描任务只走这个入口，所以"文件夹里没有图片就删掉记录"只有这里在写。
     */
    suspend fun scanFolder(path: String): Boolean =
        scanAndWrite(path)?.photos?.isNotEmpty() == true

    /**
     * 扫描单个目录并写库，返回扫描结果；`null` 表示目录里已经没有图片、记录已删除。
     *
     * 返回 null 时调用方不该再派生它的子目录：目录本身都不算相册了。
     */
    private suspend fun scanAndWrite(path: String): ScannedAlbum? = withContext(Dispatchers.IO) {
        val normalized = DirectoryScanner.normalizePath(path)
        val scanned = directoryScanner.scanAlbum(File(normalized))
        if (scanned == null || scanned.photos.isEmpty()) {
            database.albumDao().deleteAlbumByPath(normalized)
            null
        } else {
            saveScannedAlbum(scanned)
            scanned
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
