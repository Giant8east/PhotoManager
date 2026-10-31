/** 相册列表查询参数，集中承载分组、关键词和排序条件，避免查询入口重复展开同一组参数。 */
package com.example.photomanager.data

data class AlbumQuery(
    val groupId: Long?,
    val keyword: String,
    val sortField: AlbumSortField,
    val isDescending: Boolean
)
