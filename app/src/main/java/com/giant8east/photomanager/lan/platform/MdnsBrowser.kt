/** mDNS 浏览：把同一局域网里正在共享的设备变成"实例名 → 设备"的可订阅表。 */
package com.giant8east.photomanager.lan.platform

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.giant8east.photomanager.lan.guest.LanDevice
import com.giant8east.photomanager.lan.protocol.LAN_SERVICE_TYPE
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * 只做发现，不做连接：解析出 `ip:port` 后交给上层订阅。
 *
 * 自己的设备也会广播、也会被自己发现，所以按 TXT 里的 `deviceId` 与本机 [selfDeviceId]
 * 比较，相同就丢弃（否则列表里会冒出自己，点它等于连自己）。
 *
 * 系统限制：`resolveService` 同一时刻只能有一个在跑，第二个会走
 * `onResolveFailed(FAILURE_ALREADY_ACTIVE)`。所以解析请求先进 [resolveQueue]，
 * 由 [resolveJob] 里那条 `for` 循环一个个取出来等结果——**循环天然串行**，
 * 不需要再手工维护"当前有没有一个在解析"这种标志。
 */
class MdnsBrowser(context: Context, private val selfDeviceId: String) {
    private val nsdManager = context.getSystemService(NsdManager::class.java)

    // 当前发现的设备表：key 是 mDNS 实例名（onServiceLost 只给这个名字），value 是解析出的设备。
    // 用 StateFlow 而不是普通 Map：结果本身可被订阅，写一次下游自动跟随，不需要手动"发布"。
    private val mutableServices = MutableStateFlow<Map<String, LanDevice>>(emptyMap())
    val services: StateFlow<Map<String, LanDevice>> = mutableServices.asStateFlow()

    // 无界队列；onServiceFound 由系统一条线程顺序投递，一轮发现通常不超过几十条请求，不会堆满
    // 用 Channel 而不是 List：它自带挂起等待（循环取不到就挂起）与关闭语义，不需要手工同步。
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    // 解析循环自己活在一个 scope 里；浏览期间 resolveJob 非空，它同时就是"是否在浏览"的标志
    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var resolveJob: Job? = null

    // 要注销时交给系统的句柄；null 表示还没成功注册过（没有可注销的会话）
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        val listener = object : NsdManager.DiscoveryListener {  // 给注册调用一个类型非空的引用
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Discovery failed: Error code:$errorCode")
                stop()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Service discovery started")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // trySend 不阻塞：队列无界（UNLIMITED），只在通道已关闭时失败
                resolveQueue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                mutableServices.update { it - serviceInfo.serviceName }
            }
        }
        discoveryListener = listener
        try {
            nsdManager.discoverServices(LAN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            // 注册失败（系统 NSD 不可用等）：别把一个没注册成功的句柄留给 stop() 去注销
            discoveryListener = null
            return
        }

        // 唯一的消费者：`for` 会等上一个 resolveOnce 返回后才取下一个，这就是串行化本身
        resolveJob = resolveScope.launch {
            for (serviceInfo in resolveQueue) {
                val resolved = resolveOnce(serviceInfo) ?: continue    // 解析失败就丢弃，下次 found 再来
                val entry = parseDeviceFromResolvedService(resolved) ?: continue  // 自己 / 无地址 / 端口无效
                mutableServices.update { it + entry }
            }
        }
    }

    fun stop() {
        // cancel 幂等，且在途解析的回调看到 continuation 已取消就会丢弃结果，不会把设备"复活"
        resolveJob?.cancel()
        resolveJob = null

        // 先复位字段再注销：注销失败也不影响"已停止"这个状态
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (_: Exception) {
                // 会话在系统侧可能已经失效（比如网络断开会自动结束），注销失败不影响本地状态
            }
        }

        mutableServices.update { emptyMap() }
        // 丢掉上一轮积压的解析请求，避免下次 start() 处理旧会话的数据
        while (resolveQueue.tryReceive().isSuccess) {
            // 空循环：tryReceive 取到就丢
        }
    }

    /**
     * 把一次解析的结果转成要显示的条目；不该显示时返回 null。
     *
     * 返回 Pair 而不是只返回 [LanDevice]：写上层的 Map 需要 key（mDNS 实例名）。
     */
    private fun parseDeviceFromResolvedService(serviceInfo: NsdServiceInfo): Pair<String, LanDevice>? {
        val host = serviceInfo.host?.hostAddress ?: return null
        // TXT 里的设备身份；与本机相同即"自己"，不是对面那台手机
        val peerDeviceId = serviceInfo.attributes?.get("deviceId")?.decodeToString()
        if (peerDeviceId == selfDeviceId) return null

        val name = serviceInfo.attributes?.get("name")?.decodeToString()
            ?: serviceInfo.serviceName
        val device = LanDevice(
            name = name,
            ip = host,
            port = serviceInfo.port,
            deviceId = peerDeviceId,
            // 老版本对端不带 version，缺就是空串（"不知道"），不是错误
            version = serviceInfo.attributes?.get("version")?.decodeToString() ?: ""
        )
        return Pair(serviceInfo.serviceName, device)
    }

    /**
     * 发起一次解析并挂起等结果；失败（回调失败 / 同步抛异常 / 超时）返回 null。
     *
     * `suspendCancellableCoroutine` 把"回调式 API"转成"挂起函数"：谁调用它谁就被挂住，
     * 协程被取消时 continuation 也随之失效，回调里用 `isActive` 判断就不会往死掉的协程投递。
     *
     * 外面套一层超时是因为系统**有时根本不回调**（服务在解析途中消失、网络接口不匹配、
     * 个别 ROM 的 NSD 实现异常）：没有它，那条 `for` 循环会永久停在第一条上，后面排队等死。
     */
    private suspend fun resolveOnce(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
        withTimeoutOrNull(RESOLVE_TIMEOUT_MILLIS.milliseconds) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "resolve failed: ${serviceInfo.serviceName} code=$errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(serviceInfo)
                    }
                }
                // 部分机型在服务刚出现时解析会失败，丢弃即可，下次 onServiceFound 再来一遍
                try {
                    nsdManager.resolveService(serviceInfo, listener)
                } catch (_: Exception) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        const val TAG = "MdnsBrowser"

        /** 单次解析的等待上限；局域网正常是几十毫秒，3 秒还没结果说明系统卡住了。 */
        const val RESOLVE_TIMEOUT_MILLIS = 3_000L
    }
}
