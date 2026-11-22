/** 局域网共享的只读快照：一次性查询，不订阅数据库变化，只暴露给协议层。 */
package com.giant8east.photomanager.data.album

import com.giant8east.photomanager.data.database.AlbumDao
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.GroupDao
import com.giant8east.photomanager.data.database.GroupWithAlbumCount
import com.giant8east.photomanager.data.database.PhotoDao
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.model.AlbumQuery
import com.giant8east.photomanager.model.AlbumSortField
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 与 [AlbumData] 分开的原因：协议层只需要读，不应该拿到 move、delete 这类写能力。
 */
@Singleton
class AlbumShareData @Inject constructor(
    private val albumDao: AlbumDao,
    private val groupDao: GroupDao,
    private val photoDao: PhotoDao
) {
    /**
     * 共享视图的相册快照：一次取某个分组（[groupId] 为 null 表示未分组）。
     * [sortField] 已由协议层把线上枚举转换成本地枚举；
     * [keyword] 非空时走"全局搜索"分支（忽略分组），与本地搜索行为一致。
     */
    suspend fun albums(
        sortField: AlbumSortField,
        descending: Boolean,
        keyword: String,
        groupId: Long?
    ): List<AlbumEntity> =
        albumDao.queryAlbums(
            AlbumQueryBuilder.build(
                AlbumQuery(
                    groupId = groupId,
                    keyword = keyword,
                    sortField = sortField,
                    isDescending = descending
                )
            )
        )

    suspend fun album(albumId: Long): AlbumEntity? =
        albumDao.queryAlbumById(albumId)

    suspend fun albumPhotos(albumId: Long): List<PhotoEntity> =
        photoDao.queryPhotosByAlbumId(albumId)

    suspend fun photo(photoId: Long): PhotoEntity? =
        photoDao.queryPhotoById(photoId)

    suspend fun groups(): List<GroupWithAlbumCount> =
        groupDao.queryGroupWithAlbumCounts()

    suspend fun ungroupedAlbumCount(): Int =
        groupDao.queryUngroupedAlbumCount()
}
