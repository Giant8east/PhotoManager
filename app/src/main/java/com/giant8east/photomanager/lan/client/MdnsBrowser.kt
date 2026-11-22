/** mDNS 浏览：把同一局域网里正在共享的设备变成"实例名 → 设备"的可订阅表。 */
package com.giant8east.photomanager.lan.client

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.LanIdentity
import com.giant8east.photomanager.lan.protocol.LAN_SERVICE_TYPE
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
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
 *
 * 生命周期是整个进程（`@Singleton`，`PhotoManagerApplication` 跟着 `ProcessLifecycleOwner`
 * 在前台时 [start]、退到后台时 [stop]），不跟着某个页面。它是"局域网里现在有哪些设备"的唯一真相：
 * 只要开着，就能收到设备的上下线；所以用户在浏览别人相册时，也还在听。
 */
@Singleton
class MdnsBrowser @Inject constructor(
    @ApplicationContext context: Context,
    identity: LanIdentity
) {
    // 自己的身份：列表里要过滤掉自己（自己也会广播、也会被自己发现）
    private val selfDeviceId = identity.deviceId

    private val nsdManager = context.getSystemService(NsdManager::class.java)

    // 系统只在持有 MulticastLock 时把组播帧交给应用（Manifest 已声明 CHANGE_WIFI_MULTICAST_STATE）。
    // 不持锁时 Wi-Fi 芯片可能直接丢弃别人发来的 mDNS 包，表现就是"能广播、收不到"。
    private val multicastLock = context.applicationContext
        .getSystemService(WifiManager::class.java)
        ?.createMulticastLock(TAG)

    // 当前发现的设备表：key 是 mDNS 实例名（onServiceLost 只给这个名字），value 是解析出的设备。
    // 用 StateFlow 而不是普通 Map：结果本身可被订阅，写一次下游自动跟随，不需要手动"发布"。
    private val mutableServices = MutableStateFlow<Map<String, LanDevice>>(emptyMap())
    val services: StateFlow<Map<String, LanDevice>> = mutableServices.asStateFlow()

    // 无界队列；onServiceFound 由系统一条线程顺序投递，一轮发现通常不超过几十条请求，不会堆满
    // 用 Channel 而不是 List：它自带挂起等待（循环取不到就挂起）与关闭语义，不需要手工同步。
    private val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    // "系统说现在还在这网络里"的实例名。解析是排队的，等轮到某条时它可能早就不在了
    // （onServiceLost 已经来过），只有这张集合能回答"它还该不该出现在列表里"。
    // onServiceLost 在系统线程回调、解析循环在 Default 线程，所以用并发集合。
    private val activeNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // 解析循环自己活在一个 scope 里。resolveJob 只是"循环在跑"的句柄，不能单独代表"正在浏览"：
    // 注册是异步的，listener 已经交出、循环还没起来的那一小段也算浏览中（见 start() 的双条件守卫）。
    private val resolveScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var resolveJob: Job? = null

    // 看门狗：列表空着、又长时间没有任何回调时，主动重开一次会话。
    // 专门对付"系统会话已经坏了但没人知道"——后台→前台时那次 discoverServices 失败，
    // onStart 已经走过，再没有第二次机会，只能靠它自己重来。
    private var watchdogJob: Job? = null

    // 连续失败次数，用来算退避间隔；注册成功后归零
    private var startAttempt = 0

    // 最近一次收到系统回调的时刻（开机以后的毫秒数，不受改系统时间影响）。
    // onServiceFound/onServiceLost/onDiscoveryStarted 都会刷新它，看门狗拿"现在 - 它"当静默时长。
    @Volatile
    private var lastEventAt = 0L

    // 要注销时交给系统的句柄；null 表示还没成功注册过（没有可注销的会话）
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    fun start() {
        // 已经在浏览就直接返回：重复 start 会让第一个 listener 再也注销不掉
        // （字段被覆盖）、两条循环抢同一个队列、multicastLock 计数变成 2 而 stop 只 release 一次。
        // 两个条件都要看：注册是异步的，listener 已交出但解析循环还没起来（或反过来）都算"在浏览中"
        if (discoveryListener != null || resolveJob != null) return
        val listener = object : NsdManager.DiscoveryListener {  // 给注册调用一个类型非空的引用
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // 后台→前台的快速切换里，系统旧的发现会话常常还没停干净，
                // 这一次注册就会失败（FAILURE_ALREADY_ACTIVE）。如果只是 stop()，
                // 那么此刻 App 已经在前台、ProcessLifecycleOwner.onStart 不会再触发，
                // 就再也没人调 start()——表现为"列表永久空着，必须杀进程"。
                Log.w(TAG, "Discovery failed: Error code:$errorCode")
                scheduleStartRetry()
            }

            // 停不掉说明系统侧那条会话可能还活着，下一条 start() 就可能撞 ALREADY_ACTIVE；
            // 本地状态已经复位，这里只留线索（重试交给 scheduleStartRetry）
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Discovery stop failed: Error code:$errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "Service discovery started")
                startAttempt = 0                                   // 真的起来了，退避计数清零
                lastEventAt = SystemClock.elapsedRealtime()
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Discovery stopped: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "found: ${serviceInfo.serviceName}")
                lastEventAt = SystemClock.elapsedRealtime()
                activeNames.add(serviceInfo.serviceName)
                // trySend 不阻塞：队列无界（UNLIMITED），只在通道已关闭时失败
                resolveQueue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // 两种丢失：已经解析出来进了表（要删表项），或还在解析队列里排队（删 activeNames 就行）
                lastEventAt = SystemClock.elapsedRealtime()
                activeNames.remove(serviceInfo.serviceName)
                mutableServices.update { it - serviceInfo.serviceName }
            }
        }
        discoveryListener = listener
        if (multicastLock?.isHeld == false) multicastLock.acquire()
        try {
            nsdManager.discoverServices(LAN_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (error: Exception) {
            // 注册是同步失败的（系统 NSD 不可用、服务类型非法等）：和异步失败走同一条路——
            // 先摘掉这个从未注册成功的句柄（免得 releaseDiscovery() 去注销一个幽灵），
            // 再由 scheduleStartRetry() 复位组播锁与队列、按退避重试。
            // 只 return 不重试的话，此刻 App 已在前台，onStart 不会再触发，列表就永久空着。
            Log.w(TAG, "discovery start threw: ${error.message}")
            discoveryListener = null
            scheduleStartRetry()
            return
        }

        // 唯一的消费者：`for` 会等上一个 resolveOnce 返回后才取下一个，这就是串行化本身
        resolveJob = resolveScope.launch {
            for (serviceInfo in resolveQueue) {
                val resolved = resolveOnce(serviceInfo) ?: continue    // 解析失败就丢弃，下次 found 再来
                // 排队或解析期间对端关掉了共享：这条已经不该显示，别把它加回表里
                // （加回去就再也没有 lost 回调来删它，会永久残留成一个点不开的假设备）
                if (serviceInfo.serviceName !in activeNames) continue
                val entry = parseDeviceFromResolvedService(resolved) ?: continue  // 自己 / 无地址 / 端口无效
                // 系统 mdnsd 会把异常退出/覆盖安装设备的旧记录缓存 120 秒，刷新也会原样回推；
                // 入表前做一次极轻量 TCP 探活，端口已关（Connection refused）立刻丢弃幽灵设备。
                if (!isPortReachable(entry.second.ip, entry.second.port)) {
                    Log.d(TAG, "dropping stale mdns entry (unreachable): ${entry.first} ${entry.second.address}")
                    continue
                }
                // 同一台设备重启共享后端口变了、实例名也跟着变，而旧实例的 lost 要等 TTL（约 120 秒）才到。
                // 所以按 deviceId 先把旧条目挤掉，列表里只留最新那条；
                // 对端没带 deviceId（老版本）时无从判断是否同一台，原样追加，不误删。
                val deviceId = entry.second.deviceId
                mutableServices.update { entries ->
                    if (deviceId == null) entries + entry
                    else entries.filterValues { it.deviceId != deviceId } + entry
                }
            }
        }

        startWatchdog()
    }

    fun stop() {
        // 看门狗先停，否则它可能在关掉之后又把会话拉起来
        watchdogJob?.cancel()
        watchdogJob = null
        startAttempt = 0
        releaseDiscovery()
    }

    /**
     * 用户点"刷新"：丢掉当前会话重新开一次。
     *
     * 系统 NSD 没有"自定义查询"接口，我们没法只说一句"有人吗"；但重开一次发现会话
     * 本身就是一次全新的查询——网段里所有应答者会被再问一遍，本地那张表也一起清空，
     * 等于把"可能已经坏了"的会话整个换掉。所以按钮语义是"刷新我自己的浏览器"。
     */
    fun refresh() {
        stop()
        start()
    }

    /**
     * 把本地这一侧的会话状态全部复位，但不碰看门狗。
     *
     * 抽出来是因为两条路都要用它：正常 [stop]，以及注册失败后的重试
     * （重试前必须先把上一条 listener 摘干净，否则新会话还是撞 ALREADY_ACTIVE）。
     */
    private fun releaseDiscovery() {
        // cancel 幂等，且在途解析的回调看到 continuation 已取消就会丢弃结果，不会把设备"复活"
        resolveJob?.cancel()
        resolveJob = null

        // 先复位字段再注销：注销失败也不影响"已停止"这个状态
        val listener = discoveryListener
        discoveryListener = null
        if (listener != null) {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (error: Exception) {
                // 会话在系统侧可能已经失效（比如网络断开会自动结束），注销失败不影响本地状态；
                // 但它正是"下一条会话撞 ALREADY_ACTIVE"的直接前因，所以留一行日志
                Log.w(TAG, "stop discovery threw: ${error.message}")
            }
        }

        mutableServices.update { emptyMap() }
        // 丢掉上一轮积压的解析请求，避免下次 start() 处理旧会话的数据
        activeNames.clear()
        while (resolveQueue.tryReceive().isSuccess) {
            // 空循环：tryReceive 取到就丢
        }
        if (multicastLock?.isHeld == true) multicastLock.release()
    }

    /**
     * 注册失败后按退避再试几次（300 → 600 → 1200 毫秒）。
     *
     * 系统侧旧会话的注销是异步的，失败往往只是"撞车"，等一会儿就能成功；
     * 用完 [MAX_START_ATTEMPTS] 次就放弃，把机会留给下一次前后台切换或用户点刷新。
     */
    private fun scheduleStartRetry() {
        releaseDiscovery()
        if (startAttempt >= MAX_START_ATTEMPTS) {
            Log.w(TAG, "discovery start failed $startAttempt times, giving up until next start()")
            startAttempt = 0
            return
        }
        val waitMillis = START_RETRY_BASE_MILLIS shl startAttempt   // 等价于 base * 2^attempt
        startAttempt++
        resolveScope.launch {
            delay(waitMillis.milliseconds)
            start()
        }
    }

    /**
     * 看门狗：列表空着、又 [WATCHDOG_INTERVAL_MILLIS] 没收到任何回调时，重开一次会话。
     *
     * 只在表为空时动手：有设备在应答就说明会话是活的，此时重开会把已有的行抖掉。
     * 表空又长时间没有任何事件，正是"系统会话坏了但没人知道"的样子。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = resolveScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MILLIS.milliseconds)
                if (mutableServices.value.isNotEmpty()) continue
                val idleMillis = SystemClock.elapsedRealtime() - lastEventAt
                if (idleMillis < WATCHDOG_INTERVAL_MILLIS) continue
                Log.w(TAG, "no callback for ${idleMillis}ms and list is empty, restarting discovery")
                // 先摘掉自己的引用，否则下面的 stop() 取消到的就是本协程，start() 根本不会执行
                watchdogJob = null
                stop()
                start()
                return@launch
            }
        }
    }

    /**
     * 把一次解析的结果转成要显示的条目；不该显示时返回 null。
     *
     * 返回 Pair 而不是只返回 [LanDevice]：写上层的 Map 需要 key（mDNS 实例名）。
     */
    private fun parseDeviceFromResolvedService(serviceInfo: NsdServiceInfo): Pair<String, LanDevice>? {
        // 只接受 IPv4：SRV 记录也可能解析出 IPv6（fe80::…），而裸 IPv6 字面量拼进 URL 缺方括号
        // （http://fe80::1234:8080 是非法的），Request.Builder().url() 会直接抛异常。
        // `as?` 是安全转换：类型不对返回 null，不抛 ClassCastException
        val host = (serviceInfo.host as? Inet4Address)?.hostAddress
        // TXT 里的设备身份；与本机相同即"自己"，不是对面那台手机
        val peerDeviceId = serviceInfo.attributes?.get("deviceId")?.decodeToString()
        Log.d(TAG, "resolved: ${serviceInfo.serviceName} host=$host port=${serviceInfo.port} deviceId=$peerDeviceId self=$selfDeviceId")
        if (host == null) return null
        if (serviceInfo.port !in 1..65535) return null
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
     * 解析一条发现结果，失败最多再试 [RESOLVE_ATTEMPTS] 次。
     *
     * 为什么要重试：系统同一时刻只允许一个 `resolveService`，撞车就会
     * `onResolveFailed(FAILURE_ALREADY_ACTIVE)`；而 mdnsd 对"已经知道的实例"不会再补发
     * `onServiceFound`，一次失败就等于这台设备永远消失。隔几百毫秒再问一次通常就通了。
     */
    private suspend fun resolveOnce(serviceInfo: NsdServiceInfo): NsdServiceInfo? {
        var attempt = 0
        while (attempt < RESOLVE_ATTEMPTS) {
            val resolved = resolveAttempt(serviceInfo)
            if (resolved != null) return resolved
            attempt++
            if (attempt < RESOLVE_ATTEMPTS) delay(RESOLVE_RETRY_DELAY_MILLIS.milliseconds)
        }
        return null
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
    private suspend fun resolveAttempt(serviceInfo: NsdServiceInfo): NsdServiceInfo? =
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
                // 同步抛异常（系统 NSD 拒绝了这次解析）：当成一次失败交给 resolveOnce 重试。
                // 别指望"下次 onServiceFound 再来一遍"——mdnsd 对已经知道的实例不会补发。
                try {
                    nsdManager.resolveService(serviceInfo, listener)
                } catch (error: Exception) {
                    Log.w(TAG, "resolve threw: ${serviceInfo.serviceName}", error)
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }

    /** 轻量 TCP 探活：验证对端 Ktor 端口确实在监听，过滤 mdnsd 缓存里已失效的幽灵记录。 */
    private suspend fun isPortReachable(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // use 块结束时自动关闭 socket，等价于 try { ... } finally { socket.close() }
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_PROBE_TIMEOUT_MILLIS)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        const val TAG = "MdnsBrowser"

        /** TCP 探活超时：局域网在线端口通常 1~20ms 即握手完成，关共享端口 1~5ms 即回 RST。 */
        const val TCP_PROBE_TIMEOUT_MILLIS = 1_000

        /** 单次解析的等待上限；局域网正常是几十毫秒，3 秒还没结果说明系统卡住了。 */
        const val RESOLVE_TIMEOUT_MILLIS = 3_000L

        /** 一条发现结果最多解析几次（含第一次）。 */
        const val RESOLVE_ATTEMPTS = 2

        /** 两次解析之间的间隔：留给系统把上一次"占着"的解析会话放掉。 */
        const val RESOLVE_RETRY_DELAY_MILLIS = 400L

        /** 注册发现会话最多试几次（含第一次）；用完就等下次前后台切换或用户点刷新。 */
        const val MAX_START_ATTEMPTS = 3

        /** 退避起点：300 → 600 → 1200 毫秒。 */
        const val START_RETRY_BASE_MILLIS = 300L

        /** 列表空着又这么久没有任何回调，就认为会话已经哑了（系统 TTL 是 120 秒，取 90 秒更早发现）。 */
        const val WATCHDOG_INTERVAL_MILLIS = 90_000L
    }
}
