/** 热点（SoftAP）侧的本机 IPv4 探针：只枚举网卡，不碰任何系统服务。 */
package com.giant8east.photomanager.lan.server

import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 热点侧的本机地址。
 *
 * 为什么必须自己算：热点接口由系统 Tethering 模块持有，不是 App 能看见的 `Network`
 * （`TetheringManager` 与 `WifiManager.SoftApCallback` 都是 `@SystemApi`，
 * `SoftApConfiguration` 也不含 IP），所以既没有回调也没有现成 API，只能枚举网卡按名字认。
 *
 * 接口名由厂商决定（本机 vivo 实测为 `ap0`）。认不出来就返回 null，调用方按"没有热点地址"处理——
 * 猜一个地址出来会变成"对端看得见、连不上"，比老实说没有更糟。
 */
@Singleton
class HotspotAddress @Inject constructor() {

    /**
     * 当前热点侧的 IPv4；没有热点、或接口名不在已知列表里时返回 null。
     *
     * 枚举网卡是系统边界，失败只可能是权限或内核异常：当作"没有热点地址"处理，
     * 用户侧后果与"热点没开"一致（共享仍可以用 Wi-Fi 地址），因此只留日志不上抛。
     */
    fun current(): String? = runCatching { probe() }
        .onFailure { Log.w(TAG, "failed to enumerate interfaces: ${it.message}") }
        .getOrNull()

    private fun probe(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (nic in interfaces) {
            if (!nic.isUp) continue
            if (!isHotspotInterface(nic.name)) continue
            val ipv4 = nic.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
            if (ipv4 != null) return ipv4.hostAddress
        }
        return null
    }

    // 已知的热点接口名：ap0 / ap1（AOSP 与 vivo）、swlan0、softap0。
    // 刻意不认 wlan0（那是 STA 客户端），也不认 wlan1（部分机型的第二块 STA 就叫这个名字）。
    private fun isHotspotInterface(name: String): Boolean =
        name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")

    private companion object {
        /** logcat 过滤器标签：adb logcat -s HotspotAddress */
        const val TAG = "HotspotAddress"
    }
}
