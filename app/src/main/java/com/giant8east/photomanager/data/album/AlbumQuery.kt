/** 相册列表的查询条件：分组、关键词、排序。 */
package com.giant8east.photomanager.data.album

data class AlbumQuery(
    /** null 表示未分组视图；与 [favoritesOnly] 互斥，由 ViewModel 保证。 */
    val groupId: Long?,
    /** 非空时做全局搜索：忽略分组与收藏筛选。 */
    val keyword: String,
    val sortField: AlbumSortField,
    val isDescending: Boolean,
    /** 只返回已收藏相册；该视图跨分组。 */
    val favoritesOnly: Boolean = false
)