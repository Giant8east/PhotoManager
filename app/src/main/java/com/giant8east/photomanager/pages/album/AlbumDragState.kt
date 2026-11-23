/** 相册列表拖拽顺序的状态持有者：仅保存未落库的 ID 顺序快照，实体字段始终从最新数据源映射。 */
package com.giant8east.photomanager.pages.album

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.giant8east.photomanager.data.database.AlbumEntity

/**
 * 列表渲染顺序的唯一出口：[displayed] 在拖拽中返回本地副本，松手后按未保存的 ID 快照映射最新实体，
 * 其余情况直接返回数据源。
 */
@Stable
internal class AlbumDragState {
    private val dragged = mutableStateListOf<AlbumEntity>()
    private var source: List<AlbumEntity> = emptyList()
    private var isDragging by mutableStateOf(false)
    // 仅保存 ID 顺序，避免快照长期持有旧 AlbumEntity 导致收藏、隐藏、页数等字段陈旧
    private var snapshotIds by mutableStateOf<List<Long>?>(null)

    /** 是否有尚未保存为默认排序的拖拽结果。 */
    val hasPendingOrder: Boolean get() = snapshotIds != null

    /** 未保存的拖拽结果（映射为当前最新实体）；没有时返回 null。 */
    fun pendingOrder(): List<AlbumEntity>? =
        // 等价于 if (snapshotIds != null) resolveOrder(snapshotIds, source) else null
        snapshotIds?.let { resolveOrder(it, source) }

    /**
     * 数据源变化：相册集合改变（删除、同步、切分组）时旧顺序失效直接丢弃；
     * 顺序与快照一致说明已写入数据库，快照也随之清掉；其余变化保留快照。
     */
    fun onAlbumsChanged(albums: List<AlbumEntity>) {
        source = albums
        val pending = snapshotIds ?: return
        val incomingIds = albums.map { it.id }
        if (pending.toSet() != incomingIds.toSet() || pending == incomingIds) {
            snapshotIds = null
        }
    }

    /** 当前应渲染的顺序。 */
    fun displayed(albums: List<AlbumEntity>): List<AlbumEntity> = when {
        isDragging -> dragged
        snapshotIds != null -> resolveOrder(snapshotIds.orEmpty(), albums)
        else -> albums
    }

    /** 从当前显示顺序开始一次拖拽，避免用还没回流的数据源做起点。 */
    fun startDrag() {
        dragged.clear()
        dragged.addAll(displayed(source))
        isDragging = true
    }

    /** 结束拖拽：只记录 ID 顺序快照，不写入数据库。 */
    fun stopDrag() {
        isDragging = false
        snapshotIds = dragged.map { it.id }
    }

    /** 放弃未保存的顺序（例如切换排序/分组/搜索词时），回到数据源顺序。 */
    fun discard() {
        isDragging = false
        dragged.clear()
        snapshotIds = null
    }

    /** 处理一次位置交换，返回是否真实发生换位。 */
    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (dragged.isEmpty()) return false
        val target = toIndex.coerceIn(0, dragged.lastIndex)
        if (fromIndex in dragged.indices && fromIndex != target) {
            dragged.add(target, dragged.removeAt(fromIndex))
            return true
        }
        return false
    }

    private fun resolveOrder(ids: List<Long>, albums: List<AlbumEntity>): List<AlbumEntity> {
        val byId = albums.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }
}
