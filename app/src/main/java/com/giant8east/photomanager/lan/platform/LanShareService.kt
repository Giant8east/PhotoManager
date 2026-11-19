/** 共享前台服务：保证进程存活并展示地址，关闭动作回到控制器。 */
package com.giant8east.photomanager.lan.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.giant8east.photomanager.R
import com.giant8east.photomanager.lan.host.LanShareController
import com.giant8east.photomanager.lan.host.LanShareState
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
            // 关闭共享要停引擎、清 token，可能耗时几十毫秒；等它做完再停服务，
            // 否则服务先被停掉，共享状态却还挂在运行中。
            scope.launch {
                controller.setEnabled(false)
                stopSelf()
            }
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LanShareService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanShareService::class.java))
        }
    }
}
