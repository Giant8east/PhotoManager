/** 白名单接口：记录"记住过"的设备；正式实现是 data/lan 的 AllowedDeviceData，测试用内存实现。 */
package com.giant8east.photomanager.lan.host

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
