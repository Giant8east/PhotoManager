/** 相册数据入口：查询观察、分组/排序/隐藏等写操作与相册记录移除；扫描同步见 AlbumSyncData。 */
package com.giant8east.photomanager.data.album

import android.content.Context
import android.media.MediaScannerConnection
import com.giant8east.photomanager.data.database.AlbumDao
import com.giant8east.photomanager.data.database.AlbumDetailRecord
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.GroupDao
import com.giant8east.photomanager.data.database.PhotoDao
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.media.PhotoFormat
import com.giant8east.photomanager.model.AlbumQuery
import com.giant8east.photomanager.util.LocalPaths
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class AlbumData @Inject constructor(
    @ApplicationContext private val context: Context,
    private val albumDao: AlbumDao,
    private val groupDao: GroupDao,
    private val photoDao: PhotoDao
) {
    /** 构造相册查询并持续观察数据库变化。 */
    fun observeAlbums(query: AlbumQuery): Flow<List<AlbumEntity>> =
        albumDao.observeAlbumQuery(AlbumQueryBuilder.build(query))

    /** 持续观察未分组相册数量。 */
    fun observeUngroupedAlbumCount(): Flow<Int> =
        groupDao.observeUngroupedAlbumCount()

    /** 持续观察已收藏的相册数量。 */
    fun observeFavoriteCount(): Flow<Int> = albumDao.observeFavoriteCountQuery()

    /** 持续观察已同步的相册总数，供本地导入页展示。 */
    fun observeValidAlbumCount(): Flow<Int> = albumDao.observeValidAlbumCount()

    /** 持续观察相册的完整图片索引。 */
    fun observePhotos(albumId: Long): Flow<List<PhotoEntity>> =
        photoDao.observePhotosQuery(albumId).distinctUntilChanged()

    /** 持续观察相册及其完整图片索引，单个事务查询保证相册与图片来自同一快照。 */
    fun observeAlbumDetail(albumId: Long): Flow<AlbumDetailRecord?> =
        albumDao.observeAlbumDetailQuery(albumId)
            .map { record -> record?.copy(photos = record.photos.sortedBy { it.name }) }
            .distinctUntilChanged()

    /** 批量移动相册到目标分组；null 表示默认相册。 */
    suspend fun move(albums: List<AlbumEntity>, groupId: Long?) =
        albumDao.updateAlbums(albums.map { it.copy(groupId = groupId) })

    /**
     * 批量隐藏或取消隐藏相册（仅操作每个相册本层目录的 `.nomedia` 文件，不向上碰父目录）。
     * 文件操作后主动通知系统 MediaStore 刷新，避免系统图库延迟数分钟才生效。
     */
    suspend fun setHidden(albums: List<AlbumEntity>, hidden: Boolean) = withContext(Dispatchers.IO) {
        if (albums.isEmpty()) return@withContext
        val pathsToScan = ArrayList<String>()
        for (album in albums) {
            val dir = File(album.albumPath)
            val noMedia = File(dir, NO_MEDIA_FILE_NAME)
            if (hidden) {
                if (!noMedia.exists()) {
                    check(noMedia.createNewFile()) { "Failed to create ${noMedia.path}" }
                }
                pathsToScan += noMedia.path
                pathsToScan += dir.path
            } else {
                if (noMedia.exists()) {
                    check(noMedia.delete()) { "Failed to delete ${noMedia.path}" }
                }
                // 取消隐藏时把本层图片路径交给 MediaScanner，让系统相册尽快重新收进图片
                dir.listFiles()?.forEach { entry ->
                    if (entry.isFile && PhotoFormat.fromFileName(entry.name) != PhotoFormat.OTHER) {
                        pathsToScan += entry.path
                    }
                }
            }
        }
        albumDao.updateAlbumsHidden(albums.map { it.id }, hidden)
        if (pathsToScan.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, pathsToScan.toTypedArray(), null, null)
        }
    }

    /**
     * 保存当前视图的自定义顺序：分组/未分组视图写 [AlbumEntity.sortOrder]，
     * 收藏视图写 [AlbumEntity.favoriteSortOrder]，两种列表因此各自独立。
     */
    suspend fun reorder(albums: List<AlbumEntity>, favoritesView: Boolean) = albumDao
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
        if (favorite) {
            albumDao.favoriteAlbum(albumId, System.currentTimeMillis())
        } else {
            albumDao.unfavoriteAlbum(albumId)
        }
    }

    /** 保存相册最近一次浏览到的图片位置。 */
    suspend fun updateReadingProgress(albumId: Long, photoIndex: Int) =
        albumDao.updateAlbumReadingProgress(albumId, photoIndex)

    /** 移除指定扫描目录及其子目录对应的相册记录；只动索引，磁盘文件由删除任务负责。 */
    suspend fun unindexAlbumsUnderPath(path: String) = withContext(Dispatchers.IO) {
        val normalized = LocalPaths.normalize(path)
        albumDao.deleteAlbumsUnderPath(
            normalized,
            LocalPaths.childPrefix(normalized)
        )
    }

    private companion object {
        const val NO_MEDIA_FILE_NAME = ".nomedia"
    }
}
