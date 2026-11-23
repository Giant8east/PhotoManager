package com.giant8east.photomanager.pages.album

import com.giant8east.photomanager.data.database.AlbumEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDragStateTest {

    private fun album(id: Long, favorite: Boolean = false, hidden: Boolean = false) = AlbumEntity(
        id = id,
        name = "Album $id",
        albumPath = "/albums/$id",
        coverPath = "/albums/$id/0.jpg",
        totalSizeBytes = 0L,
        isFavorite = favorite,
        isHidden = hidden
    )

    @Test
    fun `snapshot keeps custom order while reflecting updated entity fields from DB`() {
        val state = AlbumDragState()
        val initial = listOf(album(1), album(2), album(3))
        state.onAlbumsChanged(initial)

        state.startDrag()
        assertTrue(state.move(0, 2))
        state.stopDrag()

        // 拖拽后快照顺序为 [2, 3, 1]
        assertTrue(state.hasPendingOrder)
        assertEquals(listOf(2L, 3L, 1L), state.displayed(initial).map { it.id })

        // 数据库更新了 id=2 的收藏与隐藏状态（同集合、旧顺序），展示必须拿到最新字段且维持拖拽顺序
        val updated = listOf(album(1), album(2, favorite = true, hidden = true), album(3))
        state.onAlbumsChanged(updated)
        val shown = state.displayed(updated)
        assertEquals(listOf(2L, 3L, 1L), shown.map { it.id })
        assertTrue(shown.first().isFavorite)
        assertTrue(shown.first().isHidden)
        assertTrue(state.pendingOrder()!!.first().isFavorite)
    }

    @Test
    fun `discard clears snapshot so switching sort order takes effect immediately`() {
        val state = AlbumDragState()
        val initial = listOf(album(1), album(2), album(3))
        state.onAlbumsChanged(initial)

        state.startDrag()
        state.move(0, 2)
        state.stopDrag()
        assertTrue(state.hasPendingOrder)

        // 模拟切换排序：调用 discard() 后按新数据源顺序展示
        state.discard()
        val resorted = listOf(album(3), album(2), album(1))
        state.onAlbumsChanged(resorted)
        assertFalse(state.hasPendingOrder)
        assertNull(state.pendingOrder())
        assertEquals(listOf(3L, 2L, 1L), state.displayed(resorted).map { it.id })
    }

    @Test
    fun `snapshot clears automatically when DB confirms same order or album set changes`() {
        val state = AlbumDragState()
        val initial = listOf(album(1), album(2), album(3))
        state.onAlbumsChanged(initial)

        state.startDrag()
        state.move(0, 2)
        state.stopDrag()
        assertTrue(state.hasPendingOrder)

        // DB 回流了与快照一致的新顺序 -> 自动清空快照
        state.onAlbumsChanged(listOf(album(2), album(3), album(1)))
        assertFalse(state.hasPendingOrder)

        // 再次拖拽后，若相册被删除导致集合变化 -> 自动清空快照
        state.startDrag()
        state.move(0, 1)
        state.stopDrag()
        assertTrue(state.hasPendingOrder)
        state.onAlbumsChanged(listOf(album(2), album(3)))
        assertFalse(state.hasPendingOrder)
    }
}
