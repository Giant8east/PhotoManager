/** AlbumDragState 的下标平移、快照保存与丢弃测试。 */
package com.giant8east.photomanager.pages.album

import com.giant8east.photomanager.data.database.AlbumEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDragStateTest {
    private fun album(id: Long) = AlbumEntity(
        id = id,
        name = "album$id",
        totalSizeBytes = 0,
        coverPath = "/album$id/cover.jpg",
        albumPath = "/album$id"
    )

    private val a = album(1)
    private val b = album(2)
    private val c = album(3)

    private fun stateOf(vararg albums: AlbumEntity) = AlbumDragState().apply {
        onAlbumsChanged(albums.toList())
        startDrag()
    }

    @Test
    fun `交换第一二项时按锚点平移`() {
        val state = stateOf(a, b, c)
        // LazyList 下标 1、2 对应数据下标 0、1。
        state.move(fromIndex = 1, toIndex = 2)
        assertEquals(listOf(b, a, c), state.displayed(listOf(a, b, c)))
    }

    @Test
    fun `向下拖动跨过多项`() {
        val state = stateOf(a, b, c)
        state.move(fromIndex = 1, toIndex = 3)
        assertEquals(listOf(b, c, a), state.displayed(listOf(a, b, c)))
    }

    @Test
    fun `拖到列表首项之上不改变顺序`() {
        val state = stateOf(a, b, c)
        // to.index = 0 即锚点本身，平移后为 -1，应被夹回 0 并与 from 相同。
        state.move(fromIndex = 1, toIndex = 0)
        assertEquals(listOf(a, b, c), state.displayed(listOf(a, b, c)))
    }

    @Test
    fun `拖到最后一项之后夹到末尾`() {
        val state = stateOf(a, b, c)
        state.move(fromIndex = 1, toIndex = 4)
        assertEquals(listOf(b, c, a), state.displayed(listOf(a, b, c)))
    }

    @Test
    fun `松手只产生待保存快照且不随数据源回流消失`() {
        val state = stateOf(a, b, c)
        state.move(fromIndex = 1, toIndex = 2)
        state.stopDrag()
        assertTrue(state.hasPendingOrder)
        assertEquals(listOf(b, a, c), state.pendingOrder())
        // 数据源还是旧顺序（拖拽尚未写库），快照必须保留。
        state.onAlbumsChanged(listOf(a, b, c))
        assertEquals(listOf(b, a, c), state.displayed(listOf(a, b, c)))
    }

    @Test
    fun `保存后数据源顺序与快照一致时自动清空快照`() {
        val state = stateOf(a, b, c)
        state.move(fromIndex = 1, toIndex = 2)
        state.stopDrag()
        val saved = listOf(b, a, c)
        state.onAlbumsChanged(saved)
        assertFalse(state.hasPendingOrder)
        assertEquals(saved, state.displayed(saved))
    }

    @Test
    fun `相册集合变化时丢弃失效的快照`() {
        val state = stateOf(a, b, c)
        state.move(fromIndex = 1, toIndex = 2)
        state.stopDrag()
        // 删掉了 c，旧顺序不再可保存。
        state.onAlbumsChanged(listOf(a, b))
        assertFalse(state.hasPendingOrder)
        assertNull(state.pendingOrder())
        assertEquals(listOf(a, b), state.displayed(listOf(a, b)))
    }

    @Test
    fun `放弃快照回到数据源顺序`() {
        val state = stateOf(a, b, c)
        state.move(fromIndex = 1, toIndex = 2)
        state.stopDrag()
        state.discard()
        assertFalse(state.hasPendingOrder)
        assertEquals(listOf(a, b, c), state.displayed(listOf(a, b, c)))
    }

    @Test
    fun `再次拖拽从上一次快照开始`() {
        val state = stateOf(a, b, c)
        state.move(fromIndex = 1, toIndex = 2)
        state.stopDrag()
        state.startDrag()
        state.move(fromIndex = 1, toIndex = 3)
        assertEquals(listOf(a, c, b), state.displayed(listOf(a, b, c)))
    }
}
