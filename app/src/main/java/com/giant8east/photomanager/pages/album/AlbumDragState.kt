/** 相册列表拖拽顺序的状态持有者：拖拽结果先作为快照保留，保存前不写数据库。 */
package com.giant8east.photomanager.pages.album

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.giant8east.photomanager.data.database.AlbumEntity

/**
 * 列表渲染顺序的唯一出口：[displayed] 在拖拽中返回本地副本，松手后返回未保存的快照，
 * 其余情况直接返回数据源。
 */
@Stable
internal class AlbumDragState {
    private val dragged = mutableStateListOf<AlbumEntity>()
    private var source: List<AlbumEntity> = emptyList()
    private var isDragging by mutableStateOf(false)
    private var snapshot by mutableStateOf<List<AlbumEntity>?>(null)

    /** 是否有尚未保存为默认排序的拖拽结果。 */
    val hasPendingOrder: Boolean get() = snapshot != null

    /** 未保存的拖拽结果；没有时返回 null。 */
    fun pendingOrder(): List<AlbumEntity>? = snapshot

    /**
     * 数据源变化：相册集合改变（删除、同步）时旧顺序失效直接丢弃；
     * 顺序与快照一致说明已写入数据库，快照也随之清掉；其余变化保留快照。
     */
    fun onAlbumsChanged(albums: List<AlbumEntity>) {
        source = albums
        val pending = snapshot ?: return
        if (pending.map { it.id }.toSet() != albums.map { it.id }.toSet()) {
            snapshot = null
        } else if (pending.map { it.id } == albums.map { it.id }) {
            snapshot = null
        }
    }

    /** 当前应渲染的顺序。 */
    fun displayed(albums: List<AlbumEntity>): List<AlbumEntity> = when {
        isDragging -> dragged
        snapshot != null -> snapshot.orEmpty()
        else -> albums
    }

    /** 从当前显示顺序开始一次拖拽，避免用还没回流的数据源做起点。 */
    fun startDrag() {
        dragged.clear()
        dragged.addAll(displayed(source))
        isDragging = true
    }

    /** 结束拖拽：结果只成为待保存快照，不写入数据库。 */
    fun stopDrag() {
        isDragging = false
        snapshot = dragged.toList()
    }

    /** 放弃未保存的顺序，回到数据源顺序。 */
    fun discard() {
        snapshot = null
    }

    /**
     * 处理一次位置交换，返回是否真实发生换位。
     */
    fun move(fromIndex: Int, toIndex: Int): Boolean {
        if (dragged.isEmpty()) return false
        val target = toIndex.coerceIn(0, dragged.lastIndex)
        if (fromIndex in dragged.indices && fromIndex != target) {
            dragged.add(target, dragged.removeAt(fromIndex))
            return true
        }
        return false
    }
}
