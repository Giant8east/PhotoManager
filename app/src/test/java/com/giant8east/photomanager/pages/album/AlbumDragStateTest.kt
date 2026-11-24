package com.giant8east.photomanager.pages.album

import com.giant8east.photomanager.data.database.AlbumEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDragStateTest {

    private fun album(
        id: Long,
        isFavorite: Boolean = false,
        isHidden: Boolean = false,
        photoCount: Int = 10
    ) = AlbumEntity(
        id = id,
        name = "Album $id",
        modifiedAt = "2026-11-24",
        photoCount = photoCount,
        totalSizeBytes = 1024L * id,
        coverPath = "/tmp/$id/1.jpg",
        albumPath = "/tmp/$id",
        isFavorite = isFavorite,
        isHidden = isHidden
    )

    @Test
    fun displayedReflectsFreshEntityFieldsWhileSnapshotActive() {
        val state = AlbumDragState()
        val initial = listOf(album(1), album(2), album(3))
        state.onAlbumsChanged(initial)

        state.startDrag()
        assertTrue(state.move(0, 2))
        state.stopDrag()

        assertTrue(state.hasPendingOrder)
        // 后台更新了相册 1 的收藏与隐藏状态，快照应保留拖拽顺序但映射出最新实体字段
        val updated = listOf(
            album(1, isFavorite = true, isHidden = true, photoCount = 99),
            album(2),
            album(3)
        )
        state.onAlbumsChanged(updated)

        val shown = state.displayed(updated)
        assertEquals(listOf(2L, 3L, 1L), shown.map { it.id })
        assertTrue(shown.last().isFavorite)
        assertTrue(shown.last().isHidden)
        assertEquals(99, shown.last().photoCount)
    }

    @Test
    fun discardClearsSnapshotAndReturnsToSourceOrder() {
        val state = AlbumDragState()
        val initial = listOf(album(1), album(2), album(3))
        state.onAlbumsChanged(initial)

        state.startDrag()
        state.move(0, 2)
        state.stopDrag()
        assertTrue(state.hasPendingOrder)

        state.discard()
        assertFalse(state.hasPendingOrder)
        assertNull(state.pendingOrder())
        assertEquals(listOf(1L, 2L, 3L), state.displayed(initial).map { it.id })
    }

    @Test
    fun onAlbumsChangedClearsSnapshotWhenDbMatchesPendingOrder() {
        val state = AlbumDragState()
        val initial = listOf(album(1), album(2), album(3))
        state.onAlbumsChanged(initial)

        state.startDrag()
        state.move(0, 2)
        state.stopDrag()
        assertTrue(state.hasPendingOrder)

        // Room 回流与快照一致的顺序后自动清空快照
        val saved = listOf(album(2), album(3), album(1))
        state.onAlbumsChanged(saved)
        assertFalse(state.hasPendingOrder)
    }

    @Test
    fun onAlbumsChangedKeepsSnapshotWhenDbReemitsOldOrderAfterFailure() {
        val state = AlbumDragState()
        val initial = listOf(album(1), album(2), album(3))
        state.onAlbumsChanged(initial)

        state.startDrag()
        state.move(0, 2)
        state.stopDrag()

        // 写库失败后 Room 再次发回旧顺序，快照不应被误判为已同步
        state.onAlbumsChanged(initial)
        assertTrue(state.hasPendingOrder)
        assertEquals(listOf(2L, 3L, 1L), state.displayed(initial).map { it.id })
    }
}
