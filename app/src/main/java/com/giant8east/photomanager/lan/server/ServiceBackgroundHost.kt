/** 只负责拉起/停掉前台服务与共享期间的 Wi-Fi 锁（高性能 + 组播），不含任何业务判断。 */
package com.giant8east.photomanager.lan.server

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "要不要保活"由控制器决定；这里回答"怎么保活"——前台服务加两把 Wi-Fi 锁。
 *
 * 两把锁各管一件事：
 * - [WifiManager.WifiLock]：熄屏后避免 Wi-Fi 进入省电模式——省电时收包会被攒到下一个 DTIM
 *   周期才递上来，1.5 秒的心跳就有被拖过 4 秒读超时的风险（预防性措施，没有逐台实测）；
 * - [WifiManager.MulticastLock]：别让芯片丢掉组播帧，否则 `mdnsd` 收不到别人发来的 mDNS 查询，
 *   我们的服务在对方列表里就会"静默消失"（应答者只在被问到时才回答）。
 *
 * 通知内容与关闭动作都在 [LanShareService] 里，跟着 `LanShareState` 走。
 */
@Singleton
class ServiceBackgroundHost @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 共享期间持有的 Wi-Fi 锁；null 表示当前没持锁。 */
    private var wifiLock: WifiManager.WifiLock? = null

    /** 共享期间持有的组播锁；与 [wifiLock] 同生同死。 */
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        // 先起服务再持锁：LOW_LATENCY 这种模式要求调用方是前台服务，
        // 而且启动服务若抛异常，此时还没拿锁，不需要额外回滚。
        ContextCompat.startForegroundService(
            context,
            Intent(context, LanShareService::class.java)
        )
        acquireWifiLock()
        acquireMulticastLock()
    }

    fun stop() {
        context.stopService(Intent(context, LanShareService::class.java))
        releaseWifiLock()
        releaseMulticastLock()
    }

    /**
     * 熄屏后系统会把 Wi-Fi 降到省电模式，收包被攒到下一个 DTIM 周期才递上来，
     * 1.5 秒的心跳可能被拖到 4 秒读超时之外，对端就判离线了。
     * 这是"设备层面"的保活，和进程调度是两件事，缺一样都会偶发断连。
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        // 真机上 WifiManager 恒存在，这里可空只是配合平台签名：拿不到就放弃持锁——
        // 共享仍然能工作，只是熄屏后更容易掉连接。
        val manager = context.getSystemService(WifiManager::class.java) ?: return
        val lock = manager
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)
            // 不引用计数：start/stop 由控制器串行调用，重复 acquire 没有意义
            .apply { setReferenceCounted(false) }
        lock.acquire()
        wifiLock = lock
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        if (lock.isHeld) lock.release()
        wifiLock = null
    }

    /**
     * 持住组播锁：没人持它的时候，Wi-Fi 芯片会按省电策略丢弃组播/广播帧，
     * `mdnsd` 收不到别人发来的"有人吗"（PTR 查询），我们的注册还在、却永远不会应答，
     * 对端只能等自己的缓存 TTL（约 120 秒）到期后把这台设备从列表里删掉。
     *
     * 这把锁是**整机**开关（系统按持有者数量计数），所以我们的浏览端 `MdnsBrowser`
     * 在前台浏览时也持着同一类锁；共享端必须自己持，不能指望别人。
     */
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        // 同上：拿不到管理器就不持锁，`mdnsd` 仍然能工作，只是熄屏后可能收不到组播查询。
        val manager = context.getSystemService(WifiManager::class.java) ?: return
        val lock = manager
            .createMulticastLock(MULTICAST_LOCK_TAG)
            // 不引用计数：start/stop 由控制器串行调用，重复 acquire 没有意义
            .apply { setReferenceCounted(false) }
        lock.acquire()
        multicastLock = lock
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        if (lock.isHeld) lock.release()
        multicastLock = null
    }

    private companion object {
        /** 会出现在 `dumpsys wifi` 的 Wi-Fi lock 列表里，便于排查是谁在压着 Wi-Fi。 */
        const val WIFI_LOCK_TAG = "PhotoManager:lanShare"

        /** 同上；单独一个 tag，便于区分"压着 Wi-Fi"和"收组播"这两件事。 */
        const val MULTICAST_LOCK_TAG = "PhotoManager:lanShareMdns"
    }
}
