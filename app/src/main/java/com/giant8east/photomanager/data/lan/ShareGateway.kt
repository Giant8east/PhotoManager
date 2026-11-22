/** 协议层数据桥：把本地 Room 快照映射成局域网 DTO，排序语义与本地列表共用一套。 */
package com.giant8east.photomanager.data.lan

import com.giant8east.photomanager.data.album.AlbumShareData
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.media.PhotoFormat
import com.giant8east.photomanager.data.media.ThumbGenerator
import com.giant8east.photomanager.data.settings.AlbumListSettingsData
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanAlbumListDto
import com.giant8east.photomanager.lan.protocol.LanGroupDto
import com.giant8east.photomanager.lan.protocol.LanGroupListDto
import com.giant8east.photomanager.lan.protocol.LanPhotoDto
import com.giant8east.photomanager.lan.protocol.albumCoverPath
import com.giant8east.photomanager.lan.protocol.mediaPath
import com.giant8east.photomanager.lan.server.AlbumListRequest
import com.giant8east.photomanager.lan.server.MediaFile
import com.giant8east.photomanager.lan.server.ShareDataSource
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class ShareGateway @Inject constructor(
    private val shareData: AlbumShareData,
    private val settingsData: AlbumListSettingsData,
    private val thumbGenerator: ThumbGenerator
) : ShareDataSource {

    /**
     * 相册列表：请求参数为 null 时使用宿主当前的排序设置。
     * 响应同时带回"宿主默认排序"与"本次实际排序"，让远端能初始化/恢复默认。
     */
    override suspend fun albums(request: AlbumListRequest): LanAlbumListDto {
        val hostSettings = settingsData.observeSettings().first()
        val appliedSort = request.sortField ?: hostSettings.sortField
        val appliedDescending = request.descending ?: hostSettings.isDescending

        val matched = shareData.albums(
            sortField = appliedSort,
            descending = appliedDescending,
            keyword = request.query.orEmpty(),
            groupId = request.groupId
        )
        val page = matched.page(request.offset, request.limit)
        return LanAlbumListDto(
            albums = page.map { album -> album.toDto() },
            defaultSortField = hostSettings.sortField,
            defaultSortDescending = hostSettings.isDescending,
            sortField = appliedSort,
            sortDescending = appliedDescending,
            offset = request.offset,
            limit = request.limit,
            total = matched.size,
            hasMore = request.offset + page.size < matched.size
        )
    }

    /** 取一页：偏移越过末尾返回空页，长度不足一页时只返回剩下的部分。 */
    private fun <T> List<T>.page(offset: Int, limit: Int): List<T> =
        if (offset >= size) emptyList() else subList(offset, minOf(offset + limit, size))

    override suspend fun groups(): LanGroupListDto {
        val groups = shareData.groups()
        return LanGroupListDto(
            groups = groups.map { item ->
                LanGroupDto(id = item.group.id, name = item.group.name, albumCount = item.albumCount)
            },
            ungroupedCount = shareData.ungroupedAlbumCount()
        )
    }

    override suspend fun photos(albumId: Long): List<LanPhotoDto> =
        shareData.albumPhotos(albumId).map { photo -> photo.toDto() }

    /** 媒体只暴露路径/文件名/大小；读取由路由的 respondFile 负责。 */
    override suspend fun mediaFile(photoId: Long): MediaFile? =
        shareData.photo(photoId)?.let { photo ->
            MediaFile(path = photo.contentUri, name = photo.name, sizeBytes = photo.sizeBytes)
        }

    /** 优先返回相册目录下的 .thumb 压缩图；老相册尚未生成时平滑回退首张原图。 */
    override suspend fun albumCoverFile(albumId: Long): File? {
        val album = shareData.album(albumId) ?: return null
        val thumb = thumbGenerator.thumbFile(album.albumPath)
        if (thumb.isFile) return thumb
        val original = File(album.coverPath)
        return if (original.isFile) original else null
    }

    override suspend fun mediaPreviewBytes(photoId: Long): ByteArray? {
        val photo = shareData.photo(photoId) ?: return null
        return thumbGenerator.encodePreviewBytes(File(photo.contentUri))
    }

    /** 封面直接指向 `/v1/albums/{id}/cover`，不再逐条查 photos 表（消除 N+1 查询）。 */
    private fun AlbumEntity.toDto(): LanAlbumDto = LanAlbumDto(
        id = id,
        name = name,
        photoCount = photoCount,
        sizeBytes = totalSizeBytes,
        groupId = groupId,
        coverUrl = if (photoCount > 0) albumCoverPath(id) else null,
        modifiedAt = modifiedAt
    )

    private fun PhotoEntity.toDto(): LanPhotoDto = LanPhotoDto(
        id = id,
        name = name,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        // OTHER 没有可用的扩展名，协议里用 null 表示"未知格式"
        format = format.takeIf { it != PhotoFormat.OTHER }?.name?.lowercase(),
        mediaUrl = mediaPath(id)
    )
}
