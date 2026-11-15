/** 测试用假数据源：路由测试只关心协议，不需要真实数据库。 */
package com.giant8east.photomanager.lan

/** 内存实现：记录最近一次查询参数，方便断言路由是否正确传参。 */
internal class FakeShareDataSource(
    private val albums: List<LanAlbumDto> = emptyList(),
    private val defaultSort: LanSortField = LanSortField.CUSTOM,
    private val defaultDescending: Boolean = false,
    private val groups: List<LanGroupDto> = emptyList(),
    private val ungroupedCount: Int = 0,
    private val photosByAlbum: Map<Long, List<LanPhotoDto>> = emptyMap(),
    private val media: Map<Long, MediaFile> = emptyMap()
) : ShareDataSource {
    var lastSort: LanSortField? = null
        private set
    var lastDescending: Boolean? = null
        private set
    var lastQuery: String? = null
        private set

    override suspend fun albums(
        sortField: LanSortField?,
        descending: Boolean?,
        query: String?
    ): LanAlbumListDto {
        lastSort = sortField
        lastDescending = descending
        lastQuery = query
        // 假数据源也做一次最小过滤，让"搜索只返回命中项"的断言有意义
        val matched = if (query.isNullOrEmpty()) {
            albums
        } else {
            albums.filter { it.name.contains(query, ignoreCase = true) }
        }
        return LanAlbumListDto(
            albums = matched,
            defaultSortField = defaultSort,
            defaultSortDescending = defaultDescending,
            sortField = sortField ?: defaultSort,
            sortDescending = descending ?: defaultDescending
        )
    }

    override suspend fun groups(): LanGroupListDto =
        LanGroupListDto(groups = groups, ungroupedCount = ungroupedCount)

    override suspend fun photos(albumId: Long): List<LanPhotoDto> =
        photosByAlbum[albumId].orEmpty()

    override suspend fun mediaFile(photoId: Long): MediaFile? = media[photoId]
}
