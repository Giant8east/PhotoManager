/** 协议层数据桥：把本地 Room 快照映射成局域网 DTO，排序语义与本地列表共用一套。 */
package com.example.photomanager.lan.server

import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.AlbumListSettingsData
import com.example.photomanager.data.AlbumSortField
import com.example.photomanager.data.PhotoFormat
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.data.database.PhotoEntity
import com.example.photomanager.lan.LanAlbumDto
import com.example.photomanager.lan.LanAlbumListDto
import com.example.photomanager.lan.LanGroupDto
import com.example.photomanager.lan.LanGroupListDto
import com.example.photomanager.lan.LanPhotoDto
import com.example.photomanager.lan.LanSortField
import com.example.photomanager.lan.MediaFile
import com.example.photomanager.lan.ShareDataSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class ShareGateway @Inject constructor(
    private val albumData: AlbumData,
    private val settingsData: AlbumListSettingsData
) : ShareDataSource {

    /**
     * 相册列表：请求参数为 null 时使用宿主当前的排序设置。
     * 响应同时带回"宿主默认排序"与"本次实际排序"，让远端能初始化/恢复默认。
     */
    override suspend fun albums(sortField: LanSortField?, descending: Boolean?): LanAlbumListDto {
        val hostSettings = settingsData.observeSettings().first()
        val defaultSort = hostSettings.sortField.toLanSort()
        val appliedSort = sortField ?: defaultSort
        val appliedDescending = descending ?: hostSettings.isDescending

        val albums = albumData.shareAlbums(appliedSort.toAlbumSort(), appliedDescending)
        return LanAlbumListDto(
            albums = albums.map { album -> album.toDto() },
            defaultSortField = defaultSort,
            defaultSortDescending = hostSettings.isDescending,
            sortField = appliedSort,
            sortDescending = appliedDescending
        )
    }

    override suspend fun groups(): LanGroupListDto {
        val groups = albumData.shareGroups()
        return LanGroupListDto(
            groups = groups.map { item ->
                LanGroupDto(id = item.group.id, name = item.group.name, albumCount = item.albumCount)
            },
            ungroupedCount = albumData.shareUngroupedAlbumCount()
        )
    }

    override suspend fun photos(albumId: Long): List<LanPhotoDto> =
        albumData.shareAlbumPhotos(albumId).map { photo -> photo.toDto() }

    /** 媒体只暴露路径/文件名/大小；读取由路由的 respondFile 负责。 */
    override suspend fun mediaFile(photoId: Long): MediaFile? =
        albumData.sharePhoto(photoId)?.let { photo ->
            MediaFile(path = photo.contentUri, name = photo.name, sizeBytes = photo.sizeBytes)
        }

    /**
     * 封面需要在 DTO 里给出 `/v1/media/{id}`，所以额外查一次首图。
     * N+1 对几十个相册可接受；V2 可以改成一条聚合查询。
     */
    private suspend fun AlbumEntity.toDto(): LanAlbumDto {
        val cover = albumData.shareAlbumCoverPhoto(id)
        return LanAlbumDto(
            id = id,
            name = name,
            photoCount = photoCount,
            sizeBytes = totalSizeBytes,
            groupId = groupId,
            coverUrl = cover?.let { photo -> "/v1/media/${photo.id}" },
            modifiedAt = modifiedAt
        )
    }

    private fun PhotoEntity.toDto(): LanPhotoDto = LanPhotoDto(
        id = id,
        name = name,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        // OTHER 没有可用的扩展名，协议里用 null 表示"未知格式"
        format = format.takeIf { it != PhotoFormat.OTHER }?.name?.lowercase(),
        mediaUrl = "/v1/media/$id"
    )

    private fun AlbumSortField.toLanSort(): LanSortField = when (this) {
        AlbumSortField.CUSTOM -> LanSortField.CUSTOM
        AlbumSortField.NAME -> LanSortField.NAME
        AlbumSortField.MODIFIED_AT -> LanSortField.MODIFIED
        AlbumSortField.PHOTO_COUNT -> LanSortField.PHOTO_COUNT
        AlbumSortField.FILE_SIZE -> LanSortField.FILE_SIZE
    }

    private fun LanSortField.toAlbumSort(): AlbumSortField = when (this) {
        LanSortField.CUSTOM -> AlbumSortField.CUSTOM
        LanSortField.NAME -> AlbumSortField.NAME
        LanSortField.MODIFIED -> AlbumSortField.MODIFIED_AT
        LanSortField.PHOTO_COUNT -> AlbumSortField.PHOTO_COUNT
        LanSortField.FILE_SIZE -> AlbumSortField.FILE_SIZE
    }
}
