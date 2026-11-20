/** 只负责拉起/停掉前台服务与共享期间的 Wi-Fi 高性能锁，不含任何业务判断。 */
package com.giant8east.photomanager.lan.server

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "要不要保活"由控制器决定；这里回答"怎么保活"——前台服务加 Wi-Fi 高性能锁。
 *
 * 通知内容与关闭动作都在 [LanShareService] 里，跟着 `LanShareState` 走。
 */
@Singleton
class ServiceBackgroundHost @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 共享期间持有的 Wi-Fi 锁；null 表示当前没持锁。 */
    private var wifiLock: WifiManager.WifiLock? = null

    fun start() {
        // 先起服务再持锁：LOW_LATENCY 这种模式要求调用方是前台服务，
        // 而且启动服务若抛异常，此时还没拿锁，不需要额外回滚。
        ContextCompat.startForegroundService(
            context,
            Intent(context, LanShareService::class.java)
        )
        acquireWifiLock()
    }

    fun stop() {
        context.stopService(Intent(context, LanShareService::class.java))
        releaseWifiLock()
    }

    /**
     * 熄屏后系统会把 Wi-Fi 降到省电模式，收包被攒到下一个 DTIM 周期才递上来，
     * 1.5 秒的心跳可能被拖到 4 秒读超时之外，对端就判离线了。
     * 这是"设备层面"的保活，和进程调度是两件事，缺一样都会偶发断连。
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
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

    private companion object {
        /** 会出现在 `dumpsys wifi` 的 WakeLock 列表里，便于排查是谁在压着 Wi-Fi。 */
        const val WIFI_LOCK_TAG = "PhotoManager:lanShare"
    }
}
