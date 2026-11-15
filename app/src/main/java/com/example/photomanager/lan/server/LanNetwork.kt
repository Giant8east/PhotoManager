/** 局域网地址工具：只关心当前 Wi-Fi 的 IPv4，供 mDNS TXT 与界面展示使用。 */
package com.example.photomanager.lan.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

object LanNetwork {
    /**
     * 返回当前默认网络的 Wi-Fi IPv4；没有 Wi-Fi 或拿不到地址时返回 null。
     *
     * 只选 IPv4：mDNS TXT 里写 IPv6 链路本地地址对端往往解析不了，
     * 而局域网共享场景下 IPv4 足够。
     */
    fun wifiIpv4(context: Context): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities = manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val linkProperties = manager.getLinkProperties(network) ?: return null
        return linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }
}
