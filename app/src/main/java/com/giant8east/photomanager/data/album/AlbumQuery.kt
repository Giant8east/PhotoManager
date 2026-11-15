/** 相册列表的查询条件：分组、关键词、排序，以及共享视图的"全部分组"标记。 */
package com.giant8east.photomanager.data.album

data class AlbumQuery(
    val groupId: Long?,
    val keyword: String,
    val sortField: AlbumSortField,
    val isDescending: Boolean,
    /** 只返回已收藏相册；与 [groupId] 互斥，由 ViewModel 保证。 */
    val favoritesOnly: Boolean = false,
    /**
     * 共享视图：忽略分组、收藏与关键词，返回全部有图相册。
     * 与 [favoritesOnly]、[groupId] 同时为默认值时，表示"未分组视图"。
     */
    val allGroups: Boolean = false
)
