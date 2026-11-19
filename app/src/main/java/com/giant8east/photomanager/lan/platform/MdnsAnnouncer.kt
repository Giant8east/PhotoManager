/** mDNS 广播的 Android 实现：把"这台设备在这里"翻译成 NsdManager 注册。 */
package com.giant8east.photomanager.lan.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.giant8east.photomanager.lan.guest.LanDevice
import com.giant8east.photomanager.lan.protocol.LAN_SERVICE_TYPE
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 注册/注销 `_photomanager._tcp` 服务。
 *
 * 注册是异步的，但调用方必须知道结果：没注册上等于对端发现不到这台设备，
 * 服务端还在监听也只是"看不见的自己"。所以 [announce] 是挂起函数，等系统回调后再返回。
 */
@Singleton
class MdnsAnnouncer @Inject constructor(
    @ApplicationContext context: Context
) {
    // 环境：系统给不给 NSD 服务，构造时取一次，之后不变，所以是 val
    private val nsdManager = context.getSystemService(NsdManager::class.java)

    // 状态：当前注册着的那次监听器；null 表示没注册。注销时需要它
    private var listener: NsdManager.RegistrationListener? = null

    /**
     * 注册（或重新注册）这台设备的服务，等系统给出结果。
     *
     * @return true 已注册；false 没注册上（回调失败或 [NsdManager.registerService] 同步抛异常）。
     */
    suspend fun announce(device: LanDevice): Boolean {
        val manager = nsdManager ?: return false
        // 同一服务类型下同名只能注册一次，IP 变了也必须重新注册，所以先注销旧的
        stop()

        val info = NsdServiceInfo().apply {
            // 服务名只是局域网里的招牌，用设备名更好认；重名时系统会自动加后缀
            serviceName = "PhotoManager-${device.name}"
            serviceType = LAN_SERVICE_TYPE
            setAttribute("name", device.name)
            setAttribute("ip", device.ip)
            setAttribute("port", device.port.toString())
            setAttribute("version", device.version)
            device.deviceId?.let { setAttribute("deviceId", it) }
        }

        // 挂起等回调：成功/失败/同步抛异常三条路都必须恢复协程，否则 announce 永远不返回
        return suspendCancellableCoroutine { continuation ->
            val registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    continuation.resume(true)
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "register failed: ${serviceInfo.serviceName} code=$errorCode")
                    continuation.resume(false)
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "unregister failed: ${serviceInfo.serviceName} code=$errorCode")
                }
            }
            listener = registrationListener
            try {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            } catch (error: Exception) {
                // 同步抛异常（这个 listener 已注册过、参数不合法、注册数超限等）同样是没广播出去
                Log.w(TAG, "register threw: ${error.message}")
                continuation.resume(false)
            }
        }
    }

    /** 没有注册过就什么都不做，所以重复调用是安全的。 */
    fun stop() {
        val current = listener ?: return
        listener = null
        // 注销失败只影响系统侧记录（它会自行过期），本地状态已经复位，日志留个痕迹即可
        runCatching { nsdManager?.unregisterService(current) }
    }

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        const val TAG = "MdnsAnnouncer"
    }
}
