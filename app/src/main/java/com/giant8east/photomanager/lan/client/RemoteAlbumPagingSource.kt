/** 远端相册页的 PagingSource：一个 offset/limit 窗口就是 Paging 的一页。 */
package com.giant8east.photomanager.lan.client

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.giant8east.photomanager.model.AlbumSortField
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.MAX_PAGE_LIMIT
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/**
 * 一次远端浏览的固定查询条件。
 *
 * 换分组、换设备都等于"换一个查询"，因此不修改旧 PagingSource，
 * 而是让 ViewModel 新建一个实例——PagingSource 本身设计为不可变。
 */
data class RemoteAlbumQuery(
    val device: LanDevice,
    val groupId: Long?,
    val query: String? = null,
    /** 排序字段，总是显式下发；默认 [AlbumSortField.CUSTOM]（对方的手动顺序）。 */
    val sortField: AlbumSortField,
    /** 方向跟着字段一起发给服务端；CUSTOM 时服务端同样按方向排（desc 会把手动顺序倒过来），只是 UI 不为 CUSTOM 提供方向控件。 */
    val descending: Boolean = false
)

internal class RemoteAlbumPagingSource(
    private val lanClient: LanClient,
    private val query: RemoteAlbumQuery,
    // 局域网响应通常只需十几毫秒，给下拉刷新与触底翻页留出可见的加载动画停留时长
    private val refreshDelayMillis: Long = 0L,
    private val appendDelayMillis: Long = 0L
) : PagingSource<Int, LanAlbumDto>() {

    /** 刷新时从头开始：远端列表没有可复用的锚点 id。 */
    override fun getRefreshKey(state: PagingState<Int, LanAlbumDto>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, LanAlbumDto> {
        val offset = params.key ?: 0
        return try {
            val page = lanClient.albums(
                device = query.device,
                sortField = query.sortField,
                descending = query.descending,
                query = query.query,
                groupId = query.groupId,
                offset = offset,
                // Paging 的首屏 loadSize 可能大于 pageSize，服务端上限是 MAX_PAGE_LIMIT
                limit = params.loadSize.coerceAtMost(MAX_PAGE_LIMIT)
            )
            val holdMillis = when (params) {
                is LoadParams.Refresh -> refreshDelayMillis
                is LoadParams.Append -> appendDelayMillis
                is LoadParams.Prepend -> 0L
            }
            if (holdMillis > 0L) delay(holdMillis)
            LoadResult.Page(
                data = page.albums,
                // 只往下翻：远端是按 offset 顺序切的窗口，没有稳定的上一页锚点
                prevKey = null,
                // 下一页从"本次 offset + 本页条数"继续；没有更多就结束
                nextKey = if (page.hasMore) offset + page.albums.size else null
            )
        } catch (cancelled: CancellationException) {
            // 协程取消不是失败：必须继续抛，否则 Paging 会把取消当错误页显示
            throw cancelled
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }
}