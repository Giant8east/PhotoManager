/** 会话 token 仓库：内存实现，滑动过期 + 关闭共享即清空。 */
package com.giant8east.photomanager.lan.host.server

import java.security.SecureRandom

/** 签发结果：token 与它当前的截止时间（滑动过期，使用中会顺延）。 */
data class IssuedToken(
    val token: String,
    val expiresAt: Long
)

/**
 * 一个 deviceId 对应一个 token，反向也维护 token → 设备，校验是 O(1)。
 *
 * 过期策略：**滑动空闲过期**——每个 token 存"截止时间" `expiresAt`，
 * 每次 [resolve] 成功都会把截止时间往后推；超过 [idleTimeoutMillis] 没有使用才失效。
 * 活跃浏览永远不会过期，被嗅探到的闲置 token 会自动死掉。
 *
 * 并发策略：所有方法共用一把锁，内部用普通 [HashMap]。
 * 读写都在锁内完成，`resolve` 的续期与 `purgeExpired` 的删除不会互相踩；
 * 局域网每秒请求量很低，锁竞争可以忽略，换来的是简单且可验证的正确性。
 *
 * [clock] 可注入，测试里推进时间即可验证过期，不需要 sleep。
 */
class LanTokenStore(
    private val clock: () -> Long = System::currentTimeMillis,
    val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS
) {
    /**
     * 一条凭证记录：只存"什么时候过期"，不存"是否有效"。
     *
     * 为什么不存 `isValid: Boolean`：时间一直在走，没有任何东西会去把它改成 false，
     * 除非给每个 token 起一个定时器。存截止时间、访问时比较，是所有 TTL 系统的通用做法。
     */
    private class Entry(val deviceId: String, var expiresAt: Long) {
        /** 是否已过期；判断依赖当前时间，所以由调用方传入 now。 */
        fun isExpired(now: Long): Boolean = now >= expiresAt
    }

    private val byToken = HashMap<String, Entry>()
    private val byDevice = HashMap<String, String>()
    private val random = SecureRandom()

    // 唯一的一把锁：issue/resolve/revoke/clear 全部串行执行。
    // 同一线程内重复进入是允许的（synchronized 可重入），
    // 所以 issue 里调用 purgeExpired 不需要单独加锁。
    private val lock = Any()

    /** 为设备签发新 token；重复签发会让旧 token 立即失效，并顺带清理已过期的条目。 */
    fun issue(deviceId: String): IssuedToken = synchronized(lock) {
        val now = clock()
        // 过期判断本身是惰性的（resolve 时触发），这里在签发时顺带扫一遍，
        // 避免长期共享、很多设备配对过之后过期条目一直滞留在 map 里
        purgeExpired(now)

        val token = randomToken()
        // put 返回旧值；?.let 等价于 if (旧值 != null) byToken.remove(旧值)
        byDevice.put(deviceId, token)?.let(byToken::remove)
        // 语法糖：map[key] = value 等价于 put(key, value)
        byToken[token] = Entry(deviceId, expiresAt = now + idleTimeoutMillis)
        IssuedToken(token, now + idleTimeoutMillis)
    }

    /** 仅供测试与诊断：当前登记的 token 数量。 */
    internal fun tokenCount(): Int = synchronized(lock) { byToken.size }

    /**
     * token 有效时返回所属设备；过期则删除并返回 null。
     *
     * 注意：这不是只读方法——命中后要刷新 `expiresAt`（滑动续期），
     * 过期时还要删两张表，所以同样放在锁内。
     */
    fun resolve(token: String): String? = synchronized(lock) {
        val entry = byToken[token] ?: return@synchronized null
        // 等价于 if (entry == null) return null（返回到 synchronized 的 lambda 之外）

        val now = clock()
        if (entry.isExpired(now)) {
            byToken.remove(token)
            // 锁内可以确定反向表指向的就是这个 token：
            // issue 换 token 时会同时删掉旧的 byToken 条目，不会出现两条并存
            byDevice.remove(entry.deviceId)
            return@synchronized null
        }
        // 滑动续期：把截止时间往后推
        entry.expiresAt = now + idleTimeoutMillis
        entry.deviceId
    }

    /** 撤销设备：删除记录后其 token 立刻无法通过校验。 */
    fun revoke(deviceId: String) {
        synchronized(lock) {
            // remove 返回被删掉的旧值；非空时才去删反向表
            byDevice.remove(deviceId)?.let(byToken::remove)
        }
    }

    fun clear() {
        synchronized(lock) {
            byToken.clear()
            byDevice.clear()
        }
    }

    /** 32 字节随机数的十六进制表示，避免可预测 token。 */
    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    /**
     * 清掉已过期的条目；调用方必须已经持有 [lock]。
     *
     * 用普通 HashMap 的迭代器删除：`iterator.remove()` 是安全且不会漏项的，
     * 不需要先复制 key 列表（那样会多一次分配和逐条查表）。
     */
    private fun purgeExpired(now: Long) {
        val iterator = byToken.entries.iterator()
        while (iterator.hasNext()) {
            // 只需要 value；key 由 iterator.remove() 负责删，不用单独取出来
            val entry = iterator.next().value
            if (entry.isExpired(now)) {
                iterator.remove()
                // 锁内且 issue 保证两表同步，这里删的就是这台设备的当前映射
                byDevice.remove(entry.deviceId)
            }
        }
    }

    // 只有 companion object（或顶层、object）里才能写 const val；
    // const val 是编译期常量，不会为每个实例生成字段
    private companion object {
        const val TOKEN_BYTES = 32
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 2 * 60 * 60 * 1000L
    }
}
