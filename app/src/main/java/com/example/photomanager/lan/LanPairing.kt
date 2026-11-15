/** 配对状态机：白名单免确认、陌生设备等待主机批准。 */
package com.example.photomanager.lan

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 配对流程：
 * - 白名单设备：直接发 token（静默续期，用户无感）；
 * - 陌生设备：生成待确认请求，主机 [approve] 后发 token，[reject] 则拒绝；
 * - 60 秒未处理算超时，结果保留 [resultTtlMillis] 供客户端重复轮询。
 *
 * 并发：所有状态转换在一把 [Mutex] 内完成。
 * 这里不能用 `synchronized`——[approve] 里的 `whitelist.add` 是可挂起操作，
 * 在挂起函数里持锁会阻塞线程，而 `Mutex` 只挂起协程。
 *
 * [clock] 可注入，测试里推进时间即可验证超时，不需要 sleep。
 */
class LanPairing(
    private val tokens: LanTokenStore,
    private val whitelist: WhitelistStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pairTimeoutMillis: Long = DEFAULT_PAIR_TIMEOUT_MILLIS,
    private val resultTtlMillis: Long = DEFAULT_RESULT_TTL_MILLIS
) {
    /** 等待主机确认的请求；`createdAt` 用于判断 60 秒等待是否超时。 */
    data class Pending(
        val requestId: String,
        val deviceId: String,
        val name: String,
        val createdAt: Long
    )

    /**
     * 配对结果；路由层负责映射成 HTTP 状态码与业务错误码。
     *
     * [Issued] 与 [Waiting] 带每次不同的数据，所以是 `data class`（每次都创建新实例）；
     * 其余结果没有字段，用 `data object` 表示"只有一种"，全局一个实例即可。
     */
    sealed interface Outcome {
        /** 白名单命中，已经签发 token。 */
        data class Issued(val token: String) : Outcome

        /** 需要主机确认；客户端拿这个 id 轮询。 */
        data class Waiting(val requestId: String) : Outcome

        /** 主机点了拒绝。 */
        data object Rejected : Outcome

        /** 超过 60 秒没有处理。 */
        data object Expired : Outcome

        /** 没有这个 requestId，或结果已过 5 分钟领取窗口。 */
        data object Unknown : Outcome
    }

    /** 已完成结果 + 产生时间，用来做过期清理（客户端可能重复轮询，不能读完就删）。 */
    private class StoredResult(val outcome: Outcome, val createdAt: Long)

    private val mutex = Mutex()
    private val pending = mutableMapOf<String, Pending>()   // 还没有响应的请求
    private val results = mutableMapOf<String, StoredResult>()

    private val mutableRequests = MutableStateFlow<List<Pending>>(emptyList())
    val requests: StateFlow<List<Pending>> = mutableRequests.asStateFlow()  // 给 UI 看的只读快照

    /**
     * 发起配对：服务端唯一的配对入口，路由的 `POST /v1/pair` 直接调用它。
     *
     * 返回值就是给客户端的结论：
     * - [Outcome.Issued]：白名单命中，已签发 token；
     * - [Outcome.Waiting]：需要主机确认，客户端用 requestId 轮询。
     * （拒绝/超时不在这里产生，而是由 [approve]/[reject]/[expireLocked] 写进 results。）
     *
     * 同一设备重复提交时会复用已有的待确认请求，避免主机弹窗被刷屏。
     *
     * 语法糖：`mutex.withLock { ... }` 等价于
     * `mutex.lock(); try { ... } finally { mutex.unlock() }`；
     * `return@withLock x` 表示从 withLock 的 lambda 返回 x，而不是从整个函数返回。
     */
    suspend fun submit(request: LanPairRequestDto): Outcome = mutex.withLock {
        val now = clock()
        // 先清理超时请求：否则下面的"复用"可能拿到一条已经过期的 pending
        expireLocked(now)

        // 白名单：之前勾选过"记住"，直接静默发 token
        if (whitelist.contains(request.deviceId)) {
            return@withLock issueLocked(request.deviceId)
        }

        // 陌生设备：同一设备重复提交（客户端重试）时复用已有请求
        val existing = pending.values.firstOrNull { it.deviceId == request.deviceId }
        if (existing != null) return@withLock Outcome.Waiting(existing.requestId)

        // requestId 是未鉴权轮询接口的唯一凭证，必须随机且不可枚举
        val requestId = UUID.randomUUID().toString()
        pending[requestId] = Pending(requestId, request.deviceId, request.name, now)
        publish()
        Outcome.Waiting(requestId)
    }

    /**
     * 查询配对结果，客户端用 `GET /v1/pair/{requestId}` 轮询。
     *
     * 顺序：已有结果 → 仍在等待 → 未知。
     * 结果优先是为了支持"响应丢了、客户端再问一次"：批准后 pending 已删除，
     * 但 results 里还保留着同一个 [Outcome.Issued]。
     **/
    suspend fun poll(requestId: String): Outcome = mutex.withLock {
        expireLocked(clock())
        results[requestId]?.outcome
            ?: pending[requestId]?.let { Outcome.Waiting(it.requestId) }
            ?: Outcome.Unknown
    }

    /**
     * 主机点"允许"。由 Android 弹窗在用户确认后直接调用，不经过 HTTP。
     *
     * [remember] 为 true 时把设备写进白名单，之后它再配对就直接发 token。
     *
     * 两个顺序约束：
     * - 先 [expireLocked]：超过 60 秒的请求不会被批准，而是得到 [Outcome.Expired]；
     * - 先写白名单、再移除 pending：写库抛异常时请求还在，主机可以重试，
     *   客户端轮询也不会落进"两边都没有"的 [Outcome.Unknown]。
     *
     * 没有返回值：结论写进 results，由客户端下一次 [poll] 取走。
     */
    suspend fun approve(requestId: String, remember: Boolean) = mutex.withLock {
        val now = clock()
        expireLocked(now)

        // 拿不到就说明已处理/已超时；`?: return@withLock` 等价于 if (...) return
        val entry = pending[requestId] ?: return@withLock
        if (remember) whitelist.add(entry.deviceId, entry.name)

        pending.remove(requestId)
        results[requestId] = StoredResult(issueLocked(entry.deviceId), now)
        publish()
    }

    /**
     * 主机点"拒绝"。
     *
     * 只对仍在等待的请求生效：已批准、已超时或 id 不存在的调用会被静默忽略，
     * 因为那些情况已经有结论（或本来就不该有）。
     */
    suspend fun reject(requestId: String) = mutex.withLock {
        val now = clock()
        expireLocked(now)
        if (pending.remove(requestId) == null) return@withLock
        results[requestId] = StoredResult(Outcome.Rejected, now)
        publish()
    }

    /**
     * 关闭共享时调用：清空待确认请求与已完成结果，
     * 避免下次开启共享时还能查到上一次的 requestId。
     * token 由 [LanTokenStore.clear] 单独清理。
     */
    suspend fun clear() = mutex.withLock {
        pending.clear()
        results.clear()
        publish()
    }

    /**
     * 签发 token 并包装成结果；调用方必须已持有 [mutex]（后缀 Locked 表示这一点）。
     *
     * 签发与写结果在同一次锁内完成，客户端不会看到"token 已发、结果还没写"的中间态。
     */
    private fun issueLocked(deviceId: String): Outcome.Issued =
        Outcome.Issued(tokens.issue(deviceId).token)

    /**
     * 统一的过期处理，必须在 [mutex] 内调用：
     * 1. 等待超过 [pairTimeoutMillis] 的请求 → 移出 pending，写入 [Outcome.Expired]
     *    （这样即使主机之后才点"允许"，[approve] 也找不到它，不会签发 token）；
     * 2. 已完成结果超过 [resultTtlMillis] → 删除（领取窗口结束，客户端不再需要）。
     *
     * 只有真的删了东西才刷新 UI 快照，避免无意义的发射。
     */
    private fun expireLocked(now: Long) {
        val expired = pending.values.filter { now - it.createdAt > pairTimeoutMillis }
        for ((requestId) in expired) {
            pending.remove(requestId)
            results[requestId] = StoredResult(Outcome.Expired, now)
        }

        val staleResults = results.entries
            .filter { now - it.value.createdAt > resultTtlMillis }
            .map { it.key }
        staleResults.forEach { results.remove(it) }

        if (expired.isNotEmpty() || staleResults.isNotEmpty()) publish()
    }

    /**
     * 把 pending 的排序快照写进 StateFlow，供 Android 弹窗订阅。
     *
     * 每次都创建新 List：StateFlow 用相等性判断"值有没有变"，
     * 原地修改再赋同一个引用会被当成没变化，界面不会刷新。
     * 调用方都持有 [mutex]，所以这里读到的 pending 是一致的。
     */
    private fun publish() {
        mutableRequests.value = pending.values.sortedBy { it.createdAt }
    }

    companion object {
        // const val 是编译期常量，只能写在 companion object / 顶层 / object 里；
        // 不会为每个 LanPairing 实例各生成一份字段
        const val DEFAULT_PAIR_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_RESULT_TTL_MILLIS = 5 * 60_000L
    }
}
