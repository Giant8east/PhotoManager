/** 共享前台服务：保证进程存活并展示地址，关闭动作回到控制器。 */
package com.giant8east.photomanager.lan.server

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.giant8east.photomanager.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LanShareService : Service() {
    @Inject lateinit var controller: LanShareController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 必须先进入前台，否则系统会在 5 秒内杀掉服务
        startForegroundWith(controller.state.value)
        scope.launch {
            controller.state.collect { state -> startForegroundWith(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // 关闭共享要停引擎、清 token，可能耗时几百毫秒；等它做完服务会被控制器一并收掉
            // （stopLocked → backgroundHost.stop），所以这里不再 stopSelf()，停服务只有一个负责人。
            scope.launch { controller.setEnabled(false) }
        }
        // 不用 START_STICKY：进程被杀后重启的是一张空的 Hilt 图——引擎、token、配对全在内存里，
        // 已经全没了，服务既做不了事也无法自我恢复，却会在通知栏留下一条"共享已开启"的假消息。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWith(state: LanShareState) {
        val address = state.address ?: getString(R.string.lan_notification_waiting)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lan_share)
            .setContentTitle(getString(R.string.lan_notification_title))
            .setContentText(getString(R.string.lan_notification_text, address))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.lan_notification_stop), stopPendingIntent())
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, LanShareService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.lan_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "lan_share"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.giant8east.photomanager.lan.STOP"
    }
}
