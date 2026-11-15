/** 服务端的数据来源抽象：路由只依赖这个接口，不直接依赖 Room 或 Android。 */
package com.giant8east.photomanager.lan

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
     * 按指定字段与方向返回相册快照。
     *
     * 参数为 null 表示"使用宿主默认排序"：由实现方读取宿主的排序设置补齐，
     * 这样路由层不需要知道宿主的设置存在哪里。
     * 返回值同时携带宿主默认排序（`defaultSort*`）与本次实际排序（`sort*`）。
     */
    suspend fun albums(sortField: LanSortField?, descending: Boolean?): LanAlbumListDto

    suspend fun groups(): LanGroupListDto

    suspend fun photos(albumId: Long): List<LanPhotoDto>

    /** 图片不存在时返回 null，路由据此返回 404。 */
    suspend fun mediaFile(photoId: Long): MediaFile?
}
