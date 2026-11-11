/** 相册列表查询参数，集中承载分组、收藏、关键词和排序条件，避免查询入口重复展开同一组参数。 */
package com.example.photomanager.data

data class AlbumQuery(
    val groupId: Long?,
    val keyword: String,
    val sortField: AlbumSortField,
    val isDescending: Boolean,
    /** 只返回已收藏相册；与 [groupId] 互斥，由 ViewModel 保证。 */
    val favoritesOnly: Boolean = false
)
