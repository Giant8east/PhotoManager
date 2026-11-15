/** 局域网共享的只读快照：一次性查询，不订阅数据库变化，只暴露给协议层。 */
package com.giant8east.photomanager.data.album

import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.GroupWithAlbumCount
import com.giant8east.photomanager.data.database.PhotoDatabase
import com.giant8east.photomanager.data.database.PhotoEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 与 [AlbumData] 分开的原因：协议层只需要读，不应该拿到 move、delete 这类写能力。
 */
@Singleton
class AlbumShareData @Inject constructor(
    private val database: PhotoDatabase
) {
    /**
     * 共享视图的相册快照：跨全部分组，排序复用与本地列表相同的 SQL 构建。
     * [sortField] 已由协议层把线上枚举转换成本地枚举。
     */
    suspend fun albums(sortField: AlbumSortField, descending: Boolean): List<AlbumEntity> =
        database.albumDao().queryAlbums(
            AlbumQueryBuilder.build(
                AlbumQuery(
                    groupId = null,
                    keyword = "",
                    sortField = sortField,
                    isDescending = descending,
                    allGroups = true
                )
            )
        )

    suspend fun albumPhotos(albumId: Long): List<PhotoEntity> =
        database.photoDao().queryPhotosByAlbumId(albumId)

    suspend fun photo(photoId: Long): PhotoEntity? =
        database.photoDao().queryPhotoById(photoId)

    /** 封面取文件名排序后的第一张，与本地相册卡片一致。 */
    suspend fun albumCoverPhoto(albumId: Long): PhotoEntity? =
        database.photoDao().queryAlbumCoverPhoto(albumId)

    suspend fun groups(): List<GroupWithAlbumCount> =
        database.groupDao().queryGroupWithAlbumCounts()

    suspend fun ungroupedAlbumCount(): Int =
        database.groupDao().queryUngroupedAlbumCount()
}
