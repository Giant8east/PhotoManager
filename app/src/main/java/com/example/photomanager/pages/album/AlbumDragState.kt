/** 相册列表拖拽顺序的状态持有者，统一"数据源 / 拖拽中本地副本 / 松手待回流"三个来源。 */
package com.example.photomanager.pages.album

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.example.photomanager.data.database.AlbumEntity

/**
 * 列表渲染顺序的唯一出口：[displayed] 在拖拽中返回本地副本，松手后返回待回流顺序，
 * 其余情况直接返回数据源。列表首项是固定 key 的锚点占位，[move] 负责把 LazyList
 * 下标平移成数据下标并处理边界。
 */
@Stable
internal class AlbumDragState {
    private val dragged = mutableStateListOf<AlbumEntity>()
    private var source: List<AlbumEntity> = emptyList()
    private var isDragging by mutableStateOf(false)
    private var pendingOrder by mutableStateOf<List<AlbumEntity>?>(null)

    /** 数据源变化（数据库回流）：清掉待回流顺序，交还给数据源。 */
    fun onAlbumsChanged(albums: List<AlbumEntity>) {
        source = albums
        pendingOrder = null
    }

    /** 当前应渲染的顺序。 */
    fun displayed(albums: List<AlbumEntity>): List<AlbumEntity> = when {
        isDragging -> dragged
        pendingOrder != null -> pendingOrder.orEmpty()
        else -> albums
    }

    /** 从当前显示顺序开始一次拖拽，避免用还没回流的数据源做起点。 */
    fun startDrag() {
        dragged.clear()
        dragged.addAll(displayed(source))
        isDragging = true
    }

    /** 结束拖拽并保留结果直到数据源回流，返回用户确认后的顺序。 */
    fun stopDrag(): List<AlbumEntity> {
        isDragging = false
        val order = dragged.toList()
        pendingOrder = order
        return order
    }

    /**
     * 处理一次位置交换。[fromIndex]/[toIndex] 是 LazyList 的项下标（首项为锚点），
     * 这里统一平移 -1 并夹到合法范围；位置未变化时不产生任何数据写入。
     */
    fun move(fromIndex: Int, toIndex: Int) {
        if (dragged.isEmpty()) return
        val from = fromIndex - 1
        val to = (toIndex - 1).coerceIn(0, dragged.lastIndex)
        if (from in dragged.indices && from != to) {
            dragged.add(to, dragged.removeAt(from))
        }
    }
}
