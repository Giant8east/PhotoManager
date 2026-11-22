/** 单个文件夹的扫描与持久化：把磁盘上的一个目录对齐成数据库里的相册与图片记录。 */
package com.giant8east.photomanager.data.album

import androidx.room.withTransaction
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.PhotoDatabase
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.media.ThumbGenerator
import com.giant8east.photomanager.data.scan.DirectoryScanner
import com.giant8east.photomanager.data.scan.ScannedAlbum
import com.giant8east.photomanager.util.LocalPaths
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 调度器的扫描任务只走这里，所以"文件夹里没有图片就删掉记录"只有这一处在写库。
 *
 * 它不管并发、不管排队，也不管"哪些目录该扫"：只做一件事——读一个目录，把差异写进索引。
 */
@Singleton
class AlbumIndexer @Inject constructor(
    private val database: PhotoDatabase,
    private val directoryScanner: DirectoryScanner,
    private val thumbGenerator: ThumbGenerator
) {
    /**
     * 扫描单个文件夹并写库；返回这一层是否还有图片，也就是它自己算不算一个相册。
     *
     * 目录不存在时扫描返回 null：删掉这条记录即可；
     * "磁盘上已经不存在、记录还在"的后代记录由同步入口在整棵树扫完后统一清理（见 [AlbumSyncData]）。
     * 目录存在但读不了时会抛异常，交给调度器记成一次失败，避免权限抖动把记录误删。
     */
    suspend fun scanAndWrite(path: String): Boolean = withContext(Dispatchers.IO) {
        val normalized = LocalPaths.normalize(path)
        val scanned = directoryScanner.scanAlbum(File(normalized))
        val hasPhotos = scanned != null && scanned.photos.isNotEmpty()
        if (hasPhotos) {
            val firstPhotoPath = scanned.photos.first().contentUri
            val existing = database.albumDao().queryAlbumByPath(normalized)
            // 首图换人时强制重建 .thumb，防止旧 .thumb 时间戳更新导致删掉首图后封面不更新
            val coverChanged = existing != null && existing.coverPath != firstPhotoPath
            thumbGenerator.ensureAlbumThumb(
                albumPath = normalized,
                firstPhotoPath = firstPhotoPath,
                forceRebuild = coverChanged
            )
            saveScannedAlbum(scanned)
        } else {
            database.albumDao().deleteAlbumByPath(normalized)
        }
        hasPhotos
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
            else newPhoto.copy(
                id = oldPhoto.id,
                albumId = existing.id
                // 文件内容已变更，旧 width/height 失效，回落 newPhoto 默认值 0
            )
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

    private companion object {
        /** 比较文件元数据是否一致，保留数据库已有 ID 与图片记录。 */
        fun samePhotoMetadata(old: PhotoEntity, new: PhotoEntity): Boolean =
            old.sizeBytes == new.sizeBytes && old.modifiedAt == new.modifiedAt

        /**
         * 根据旧图片列表与旧阅读进度，计算新列表中最匹配的阅读下标：
         * 1. 若原阅读图片仍存在，直接跟随该图片到新下标；
         * 2. 若原阅读图片被移除，按文件名顺序停留在原位置紧邻的图片。
         */
        fun resolveUpdatedProgress(
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
