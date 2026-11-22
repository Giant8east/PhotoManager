/**
 * 相册列表的查询条件：分组、关键词、排序。
 *
 * 放在 `model` 而不是 `data` 的原因：它是 ViewModel 与数据层**共用的同一套业务词汇**
 * （界面传下条件、数据层拼 SQL），本身零依赖——不碰 Android、不碰 Room、不碰 SQL。
 * 一旦它带着 Android 或数据库类型，两个层就都得穿过某一层的实现细节，所以那种情况必须搬回对应层。
 */
package com.giant8east.photomanager.model

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
