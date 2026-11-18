/** 服务端的数据来源抽象：路由只依赖这个接口，不直接依赖 Room 或 Android。 */
package com.giant8east.photomanager.lan.host

import com.giant8east.photomanager.lan.protocol.LanAlbumListDto
import com.giant8east.photomanager.lan.protocol.LanGroupListDto
import com.giant8east.photomanager.lan.protocol.LanPhotoDto
import com.giant8east.photomanager.lan.protocol.LanSortField

/**
 * 一张待发送的媒体文件。
 *
 * 只暴露路径、文件名和大小，避免把 Android 的 Uri / ContentResolver 带进协议层；
 * 读取文件内容由路由层用 `respondFile` 完成。
 */
data class MediaFile(
    val path: String,
    val name: String,
    val sizeBytes: Long
)

/**
 * 共享数据的只读快照接口。
 *
 * 语义是"一次性查询"，不是持续订阅：每个 HTTP 请求调用一次，
 * 由 Android 侧用 `AlbumData` 的快照查询实现，测试里可以用内存假数据实现。
 */
interface ShareDataSource {
    /**
     * 按 [AlbumListRequest] 返回一页相册快照。
     *
     * 请求里的 `sortField`/`descending` 为 null 表示"使用宿主默认排序"：
     * 由实现方读取宿主的排序设置补齐，这样路由层不需要知道宿主的设置存在哪里。
     */
    suspend fun albums(request: AlbumListRequest): LanAlbumListDto

    suspend fun groups(): LanGroupListDto

    suspend fun photos(albumId: Long): List<LanPhotoDto>

    /** 图片不存在时返回 null，路由据此返回 404。 */
    suspend fun mediaFile(photoId: Long): MediaFile?
}

/**
 * 相册列表的一次查询。
 *
 * 参数有六个，铺成函数参数在调用点就分不清谁是谁；收成一个对象后，
 * 测试也能整体断言"路由把参数解析成了什么"。
 *
 * 语义约定：
 * - [groupId] 为 null 表示"未分组"，与服务端本地首页的默认视图一致；
 * - [query] 非空时做全局搜索，忽略 [groupId]（与本地搜索行为一致）；
 * - [offset] 从 0 开始，[limit] 由调用方按 `MAX_PAGE_LIMIT` 截断后再传进来。
 */
data class AlbumListRequest(
    val sortField: LanSortField?,
    val descending: Boolean?,
    val query: String?,
    val groupId: Long?,
    val offset: Int,
    val limit: Int
)
