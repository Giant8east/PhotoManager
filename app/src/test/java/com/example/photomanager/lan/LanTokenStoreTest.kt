/** token 仓库测试：滑动过期、撤销、重复签发，全部用可注入时钟推进，不 sleep。 */
package com.example.photomanager.lan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

class LanTokenStoreTest {
    private var now = 0L
    private val timeout = 2 * 60 * 60 * 1000L
    private val store = LanTokenStore(clock = { now }, idleTimeoutMillis = timeout)

    /** issue 返回 IssuedToken，测试里只关心 token 字符串 */
    private fun issue(deviceId: String): String = store.issue(deviceId).token

    @Test
    fun `刚签发的 token 可用`() {
        val token = issue("dev-1")
        assertEquals("dev-1", store.resolve(token))
    }

    @Test
    fun `每次使用都会顺延过期时间`() {
        val token = issue("dev-1")

        now += timeout - 1
        assertEquals("dev-1", store.resolve(token))   // 差 1ms 过期，命中并续期

        now += timeout - 1
        assertEquals("dev-1", store.resolve(token))   // 距上次使用仍不足 2h
    }

    @Test
    fun `闲置超过阈值后失效`() {
        val token = issue("dev-1")
        now += timeout + 1
        assertNull(store.resolve(token))
    }

    @Test
    fun `撤销后立即失效`() {
        val token = issue("dev-1")
        store.revoke("dev-1")
        assertNull(store.resolve(token))
    }

    @Test
    fun `clear 清空全部`() {
        val first = issue("dev-1")
        val second = issue("dev-2")
        store.clear()
        assertNull(store.resolve(first))
        assertNull(store.resolve(second))
    }

    @Test
    fun `重复签发让旧 token 失效`() {
        val first = issue("dev-1")
        val second = issue("dev-1")
        assertNull(store.resolve(first))
        assertEquals("dev-1", store.resolve(second))
    }

    @Test
    fun `过期 token 不影响同一设备的新 token`() {
        val old = issue("dev-1")
        now += timeout + 1
        assertNull(store.resolve(old))   // 触发过期清理

        // 过期清理不能破坏反向表：新 token 仍必须能被 revoke 掉
        val fresh = issue("dev-1")
        assertEquals("dev-1", store.resolve(fresh))
        store.revoke("dev-1")
        assertNull(store.resolve(fresh))
    }

    @Test
    fun `并发签发同一设备只保留一个有效 token`() = runBlocking {
        // 50 个协程同时签发，写路径加锁后两张表必须保持一致
        val issued = (1..50).map {
            async(Dispatchers.Default) { issue("dev-1") }
        }.awaitAll()

        val valid = issued.filter { token -> store.resolve(token) != null }
        assertEquals(1, valid.size)

        // 反向表也一致：revoke 能失效最终那个 token
        store.revoke("dev-1")
        assertNull(store.resolve(valid.single()))
    }

    @Test
    fun `签发新 token 时顺带清理过期条目`() {
        issue("dev-1")
        issue("dev-2")
        assertEquals(2, store.tokenCount())

        now += timeout + 1
        issue("dev-3")                    // 触发清理
        assertEquals(1, store.tokenCount())     // 只剩刚签发的 dev-3
    }

    @Test
    fun `未过期的条目不会被清理`() {
        issue("dev-1")
        now += timeout - 1
        issue("dev-2")
        assertEquals(2, store.tokenCount())
    }

    @Test
    fun `刚被使用过的条目不会被清理`() {
        val active = issue("dev-1")
        now += timeout - 1
        assertEquals("dev-1", store.resolve(active))   // 续期

        now += 1                                        // 距上次使用只有 1ms
        issue("dev-2")                            // 触发清理
        assertEquals("dev-1", store.resolve(active))    // 仍有效
    }
}
