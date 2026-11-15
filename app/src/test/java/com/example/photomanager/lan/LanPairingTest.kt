/** 配对状态机测试：等待/批准/拒绝/超时、白名单免确认、结果领取窗口。 */
package com.example.photomanager.lan

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPairingTest {
    private var now = 0L
    private val tokens = LanTokenStore(clock = { now })
    private val whitelist = InMemoryWhitelist()
    private val pairing = LanPairing(
        tokens = tokens,
        whitelist = whitelist,
        clock = { now },
        pairTimeoutMillis = 60_000,
        resultTtlMillis = 5 * 60_000
    )

    private fun request(deviceId: String = "dev-1") =
        LanPairRequestDto(deviceId = deviceId, name = "Guest")

    @Test
    fun `陌生设备进入等待`() = runBlocking {
        val outcome = pairing.submit(request())
        assertTrue(outcome is LanPairing.Outcome.Waiting)
        assertEquals(1, pairing.requests.value.size)
    }

    @Test
    fun `批准后发放 token 并写入白名单`() = runBlocking {
        val waiting = pairing.submit(request()) as LanPairing.Outcome.Waiting
        pairing.approve(waiting.requestId, remember = true)

        val issued = pairing.poll(waiting.requestId) as LanPairing.Outcome.Issued
        assertEquals("dev-1", tokens.resolve(issued.token))
        assertTrue(whitelist.contains("dev-1"))
        assertTrue(pairing.requests.value.isEmpty())
    }

    @Test
    fun `批准但不记住不写白名单`() = runBlocking {
        val waiting = pairing.submit(request()) as LanPairing.Outcome.Waiting
        pairing.approve(waiting.requestId, remember = false)

        assertTrue(pairing.poll(waiting.requestId) is LanPairing.Outcome.Issued)
        assertTrue(!whitelist.contains("dev-1"))
    }

    @Test
    fun `白名单设备直接发放`() = runBlocking {
        whitelist.add("dev-1", "Guest")
        assertTrue(pairing.submit(request()) is LanPairing.Outcome.Issued)
    }

    @Test
    fun `拒绝后返回 Rejected`() = runBlocking {
        val waiting = pairing.submit(request()) as LanPairing.Outcome.Waiting
        pairing.reject(waiting.requestId)
        assertTrue(pairing.poll(waiting.requestId) is LanPairing.Outcome.Rejected)
    }

    @Test
    fun `超过等待窗口返回 Expired`() = runBlocking {
        val waiting = pairing.submit(request()) as LanPairing.Outcome.Waiting
        now += 60_001
        assertTrue(pairing.poll(waiting.requestId) is LanPairing.Outcome.Expired)
        // 结果保留，重复轮询仍然拿到 Expired
        assertTrue(pairing.poll(waiting.requestId) is LanPairing.Outcome.Expired)
    }

    @Test
    fun `批准已超时的请求不会签发 token`() = runBlocking {
        val waiting = pairing.submit(request()) as LanPairing.Outcome.Waiting
        now += 60_001

        // 即使主机此时才点"允许"，也应该按超时处理
        pairing.approve(waiting.requestId, remember = true)

        assertTrue(pairing.poll(waiting.requestId) is LanPairing.Outcome.Expired)
        assertTrue(!whitelist.contains("dev-1"))
    }

    @Test
    fun `超过领取窗口后结果被清理`() = runBlocking {
        val waiting = pairing.submit(request()) as LanPairing.Outcome.Waiting
        pairing.approve(waiting.requestId, remember = false)
        assertTrue(pairing.poll(waiting.requestId) is LanPairing.Outcome.Issued)

        // 再推进超过结果保留时间，下一次查询会先清理
        now += 5 * 60_000 + 1
        assertTrue(pairing.poll(waiting.requestId) is LanPairing.Outcome.Unknown)
    }

    @Test
    fun `未知 requestId 返回 Unknown`() = runBlocking {
        assertTrue(pairing.poll("not-exists") is LanPairing.Outcome.Unknown)
    }

    @Test
    fun `同一设备重复提交复用等待请求`() = runBlocking {
        val first = pairing.submit(request()) as LanPairing.Outcome.Waiting
        val second = pairing.submit(request()) as LanPairing.Outcome.Waiting
        assertEquals(first.requestId, second.requestId)
        assertEquals(1, pairing.requests.value.size)
    }

    @Test
    fun `并发提交同一设备只产生一个等待请求`() = runBlocking {
        val results = (1..20).map { async { pairing.submit(request()) } }.awaitAll()
        val requestIds = results.filterIsInstance<LanPairing.Outcome.Waiting>()
            .map { it.requestId }
            .toSet()
        assertEquals(1, requestIds.size)
        assertEquals(1, pairing.requests.value.size)
    }

    @Test
    fun `两台设备同时配对互不影响`() = runBlocking {
        val first = pairing.submit(request(deviceId = "dev-a")) as LanPairing.Outcome.Waiting
        val second = pairing.submit(request(deviceId = "dev-b")) as LanPairing.Outcome.Waiting
        assertEquals(2, pairing.requests.value.size)

        // 只批准第一台，第二台必须仍在等待
        pairing.approve(first.requestId, remember = false)
        assertTrue(pairing.poll(first.requestId) is LanPairing.Outcome.Issued)
        assertTrue(pairing.poll(second.requestId) is LanPairing.Outcome.Waiting)

        // 再批准第二台，两个 token 各自归属正确的设备
        pairing.approve(second.requestId, remember = false)
        val issuedA = pairing.poll(first.requestId) as LanPairing.Outcome.Issued
        val issuedB = pairing.poll(second.requestId) as LanPairing.Outcome.Issued
        assertEquals("dev-a", tokens.resolve(issuedA.token))
        assertEquals("dev-b", tokens.resolve(issuedB.token))
    }

    @Test
    fun `并发提交两台设备各自产生独立请求`() = runBlocking {
        val outcomes = listOf("dev-a", "dev-b").map { deviceId ->
            async { pairing.submit(request(deviceId = deviceId)) }
        }.awaitAll()

        val requestIds = outcomes.filterIsInstance<LanPairing.Outcome.Waiting>()
            .map { it.requestId }
            .toSet()
        assertEquals(2, requestIds.size)
        assertEquals(2, pairing.requests.value.size)
    }

    @Test
    fun `已超时的请求会被清理出待确认列表`() = runBlocking {
        pairing.submit(request(deviceId = "dev-1"))
        now += 60_001
        // 再次提交会先清理过期请求，再创建新的
        val next = pairing.submit(request(deviceId = "dev-1")) as LanPairing.Outcome.Waiting
        assertEquals(1, pairing.requests.value.size)
        assertTrue(pairing.poll(next.requestId) is LanPairing.Outcome.Waiting)
    }
}
