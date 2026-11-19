/** 访客侧的会话表：记录"哪台设备的 token 是什么"，供 HTTP 拦截器按目标地址注入请求头。 */
package com.giant8east.photomanager.lan.guest

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 为什么需要这张表：token 只走请求头，而 Coil 与 Retrofit 拿到的只是 URL，
 * 它们不知道 URL 对应哪台设备、用哪个 token。拦截器在这里按 `host:port` 反查。
 */
@Singleton
class LanSessionRegistry @Inject constructor() {
    private val tokens = ConcurrentHashMap<String, String>()

    /** 配对成功后登记；同一台设备重新配对会覆盖旧 token。 */
    fun put(address: String, token: String) {
        tokens[address] = token
    }

    fun tokenFor(address: String): String? = tokens[address]

    /** 401 或撤销时清掉，下一次请求会重新走配对。 */
    fun remove(address: String): String? = tokens.remove(address)

    fun clear() = tokens.clear()
}
