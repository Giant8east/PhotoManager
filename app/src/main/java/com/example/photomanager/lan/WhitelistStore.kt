/** 白名单抽象：记录"记住过"的设备，M3 换成 Room 后接口不变。 */
package com.example.photomanager.lan

import java.util.concurrent.ConcurrentHashMap

/**
 * 白名单存储。
 *
 * 判定依据是 `deviceId`：它只是客户端自己生成的标识，
 * 不是强身份——同一网段内知道这个值的人也能免确认。
 * 我们接受这个风险（局域网、只读、随时可撤销），换取简单。
 */
interface WhitelistStore {
    suspend fun contains(deviceId: String): Boolean

    suspend fun add(deviceId: String, name: String)

    suspend fun remove(deviceId: String)
}

/** M2 的内存实现：进程存活期间有效；M3 会换成 Room 持久化。 */
class InMemoryWhitelist : WhitelistStore {
    // deviceId -> name；值暂时只用于展示，M3 会换成带时间戳的 Room 实体
    private val entries = ConcurrentHashMap<String, String>()

    override suspend fun contains(deviceId: String): Boolean = entries.containsKey(deviceId)

    override suspend fun add(deviceId: String, name: String) {
        entries[deviceId] = name
    }

    override suspend fun remove(deviceId: String) {
        entries.remove(deviceId)
    }
}
