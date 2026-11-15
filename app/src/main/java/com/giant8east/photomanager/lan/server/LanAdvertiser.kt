/** mDNS 广告：把本机共享服务注册为 `_photomanager._tcp`，供同网段设备发现。 */
package com.giant8east.photomanager.lan.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * 注册/注销服务。TXT 里带 `name/ip/port/version/deviceId`：
 * 对端优先用 TXT 的 ip 而不是 resolve 出来的地址，避免拿到 IPv6 链路本地地址。
 *
 * 注册是异步的：调用 [register] 后立即返回，失败只记录 listener 回调，
 * 不影响服务端本身继续监听。
 */
object LanAdvertiser {
    private var nsdManager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    fun register(
        context: Context,
        serviceName: String,
        deviceName: String,
        ip: String,
        port: Int,
        version: String,
        deviceId: String
    ) {
        unregister(context)

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = SERVICE_TYPE
            setAttribute("name", deviceName)
            setAttribute("ip", ip)
            setAttribute("port", port.toString())
            setAttribute("version", version)
            setAttribute("deviceId", deviceId)
        }

        val manager = context.getSystemService(NsdManager::class.java) ?: return
        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdManager = manager
        listener = registrationListener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    fun unregister(context: Context) {
        val manager = nsdManager ?: return
        val current = listener ?: return
        runCatching { manager.unregisterService(current) }
        nsdManager = null
        listener = null
    }

    private const val SERVICE_TYPE = "_photomanager._tcp"
}
