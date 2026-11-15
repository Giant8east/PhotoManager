/** 相册数据入口：查询观察、分组/排序等写操作与相册记录删除；扫描同步见 AlbumSyncData。 */
package com.giant8east.photomanager.data.album

import androidx.room.withTransaction
import com.giant8east.photomanager.data.database.AlbumDetailRecord
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.PhotoDatabase
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.delete.DeletionGate
import com.giant8east.photomanager.data.scan.DirectoryScanner
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class AlbumData @Inject constructor(
    private val database: PhotoDatabase,
    private val deletionGate: DeletionGate
) {
    /** 构造相册查询并持续观察数据库变化。 */
    fun observeAlbums(query: AlbumQuery): Flow<List<AlbumEntity>> =
        database.albumDao().observeAlbumQuery(AlbumQueryBuilder.build(query))

    /** 持续观察未分组相册数量。 */
    fun observeUngroupedAlbumCount(): Flow<Int> =
        database.groupDao().observeUngroupedAlbumCount()

    /** 持续观察已收藏且仍有图片的相册数量。 */
    fun observeFavoriteCount(): Flow<Int> = database.albumDao().observeFavoriteCountQuery()

    /** 持续观察相册的完整图片索引。 */
    fun observePhotos(albumId: Long): Flow<List<PhotoEntity>> =
        database.photoDao().observePhotosQuery(albumId).distinctUntilChanged()

    /** 持续观察相册及其完整图片索引，单个事务查询保证相册与图片来自同一快照。 */
    fun observeAlbumDetail(albumId: Long): Flow<AlbumDetailRecord?> =
        database.albumDao().observeAlbumDetailQuery(albumId)
            .map { record -> record?.copy(photos = record.photos.sortedBy { it.name }) }
            .distinctUntilChanged()

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
            database.albumDao().deleteAlbumsUnderPath(
                normalized,
                DirectoryScanner.childPathPrefix(normalized)
            )
        }
    }
}
