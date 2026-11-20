/** mDNS 广播的 Android 实现：把"这台设备在这里"翻译成 NsdManager 注册。 */
package com.giant8east.photomanager.lan.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.LAN_SERVICE_TYPE
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 注册/注销 `_photomanager._tcp` 服务。
 *
 * 注册是异步的，但调用方必须知道结果：没注册上等于对端发现不到这台设备，
 * 服务端还在监听也只是"看不见的自己"。所以 [announce] 是挂起函数，等系统回调后再返回。
 *
 * 并发契约：内部只有一个可变的 [listener] 字段，不加锁。所有调用都必须发生在
 * `LanShareController` 持有其 mutex 的上下文里（[announce] 与 [stop] 都是），
 * 否则两个调用会同时改这个字段。
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
            // 实例名带端口：NsdManager 只按"实例名"通知上下线，同一个名字底下 SRV 变了它一个字都不说。
            // 共享每次开启都绑新端口，所以"端口变了"必须表现成"换了一个实例名"，对端才会收到新的 found。
            // 设备名按 UTF-8 字节截到 40：DNS 单个标签上限 63 字节，要给 "PhotoManager-" 前缀和 "-端口" 留位置。
            serviceName = "PhotoManager-${truncateUtf8(device.name, MAX_SERVICE_NAME_BYTES)}-${device.port}"
            serviceType = LAN_SERVICE_TYPE
            // 端口必须写在 SRV 记录里：只写 TXT 的话系统会抛 "The port is missing"
            port = device.port
            // TXT 只放 SRV 里没有的信息：地址由 A 记录给、端口由 SRV 给，再抄一份只会多一个会不一致的来源。
            setAttribute("name", device.name)
            setAttribute("version", device.version)
            device.deviceId?.let { setAttribute("deviceId", it) }
        }

        // 挂起等回调：成功/失败/同步抛异常三条路都必须恢复协程，否则 announce 永远不返回。
        // 外面再套一层超时，兜的是"系统一个回调都不给"：mdnsd 卡死时 registerService 不会回调
        // （AOSP netd 有同样的记录：resolveService 永不回调，只能杀进程恢复），而本函数是在
        // controller 的 mutex 里被调用的，挂住就等于共享开关两个方向都卡死。
        // 超时按"没广播出去"处理，走调用方已有的 AdvertiseFailed / StartFailed 分支。
        // withTimeoutOrNull 超时返回 null，`?: false` 把它变成"没注册上"
        return withTimeoutOrNull(ANNOUNCE_TIMEOUT_MILLIS.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val registrationListener = object : NsdManager.RegistrationListener {
                    // 超时之后的回调仍可能到达：对已取消的 continuation 调 resume 会被忽略，
                    // 不会抛异常，所以这里不需要再判断 isActive
                    override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                        Log.i(TAG, "registered: ${serviceInfo.serviceName} port=${info.port}")
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
                    // 同步抛异常（这个 listener 已注册过、参数不合法、注册数超限等）同样是没广播出去。
                    // 顺手把 listener 复位：这个监听器从没注册成功，不能让下次 stop() 去注销一个不存在的它。
                    listener = null
                    Log.w(TAG, "register threw: ${error.message}")
                    continuation.resume(false)
                }
            }
        } ?: false
    }

    /** 没有注册过就什么都不做，所以重复调用是安全的。 */
    fun stop() {
        val current = listener ?: return
        listener = null
        // 注销失败只影响系统侧记录（它会自行过期），本地状态已经复位，这里只留排查线索
        runCatching { nsdManager?.unregisterService(current) }
            .onFailure { Log.w(TAG, "unregister threw: ${it.message}") }
    }

    /**
     * 按 UTF-8 字节截断，给实例名用：中文一个字符占 3 字节，按字符数截会突破 DNS 标签 63 字节上限。
     * 逐字符累加、装不下就停，避免把多字节字符切成半个（切了会变成乱码替换符）。
     * 代理对（emoji）会被拆成两个 char；本项目的设备名来自 Build.MODEL，不会出现。
     */
    private fun truncateUtf8(text: String, maxBytes: Int): String {
        var used = 0
        val kept = StringBuilder()
        for (char in text) {
            val size = char.toString().toByteArray(Charsets.UTF_8).size
            if (used + size > maxBytes) break
            used += size
            kept.append(char)
        }
        return kept.toString()
    }

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        /**
         * 等注册回调的上限。5 秒远大于正常回调耗时（实测几十毫秒），只为兜住"系统不回调"，
         * 所以宁可给宽一点：误判超时会把一次本来能成功的广播说成失败。
         */
        const val ANNOUNCE_TIMEOUT_MILLIS = 5_000L

        /** 实例名里设备名允许占的 UTF-8 字节数（63 字节标签 − "PhotoManager-" − "-端口"）。 */
        const val MAX_SERVICE_NAME_BYTES = 40

        const val TAG = "MdnsAnnouncer"
    }
}
