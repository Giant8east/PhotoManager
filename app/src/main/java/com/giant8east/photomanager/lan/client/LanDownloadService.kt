/** 远端下载前台服务：对齐 EhViewer DownloadService 通知样式（进度分类、主题色、子文本进度、全部停止按钮）。 */
package com.giant8east.photomanager.lan.client

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.IBinder
import android.os.SystemClock
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.lan.DownloadServiceSummary
import com.giant8east.photomanager.data.lan.RemoteDownloadManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LanDownloadService : Service() {
    @Inject lateinit var downloadManager: RemoteDownloadManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastNotifyAt = 0L
    private var hasSeenActive = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        updateForeground(downloadManager.summary.value, force = true)
        scope.launch {
            downloadManager.summary.collect { summary ->
                if (summary.isActive) {
                    hasSeenActive = true
                    updateForeground(summary, force = false)
                } else if (hasSeenActive) {
                    postCompletionSummary(summary)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            downloadManager.stopAll()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 对齐 EhViewer DownloadService：CATEGORY_PROGRESS + 系统下载图标 + 速度正文 + x/y 子文本 + 500ms 节流。 */
    private fun updateForeground(summary: DownloadServiceSummary, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyAt < NOTIFY_THROTTLE_MILLIS) return
        lastNotifyAt = now

        val title = summary.currentAlbumName.ifEmpty { getString(R.string.lan_download_starting) }
        val speedText = "${Formatter.formatShortFileSize(this, summary.bytesPerSecond)}/s"
        val subText = if (summary.total > 0) "${summary.finished}/${summary.total}" else null

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(if (summary.total > 0) speedText else getString(R.string.lan_download_starting))
            .setSubText(subText)
            .setProgress(summary.total, summary.finished, summary.total <= 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setColor(NOTIFICATION_ACCENT_COLOR)
            .setShowWhen(false)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.lan_download_stop_all),
                stopAllPendingIntent()
            )
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun postCompletionSummary(summary: DownloadServiceSummary) {
        if (summary.sessionCompleted == 0 && summary.sessionFailed == 0) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val text = if (summary.sessionFailed > 0) {
            getString(R.string.lan_download_summary_with_failed, summary.sessionCompleted, summary.sessionFailed)
        } else {
            getString(R.string.lan_download_summary_done, summary.sessionCompleted)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
            .setContentTitle(getString(R.string.lan_download_channel))
            .setContentText(text)
            .setColor(NOTIFICATION_ACCENT_COLOR)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        manager.notify(SUMMARY_NOTIFICATION_ID, notification)
    }

    private fun stopAllPendingIntent(): PendingIntent {
        val intent = Intent(this, LanDownloadService::class.java).setAction(ACTION_STOP_ALL)
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
            getString(R.string.lan_download_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "lan_download"
        private const val NOTIFICATION_ID = 1002
        private const val SUMMARY_NOTIFICATION_ID = 1003
        private const val NOTIFY_THROTTLE_MILLIS = 500L
        private const val NOTIFICATION_ACCENT_COLOR = 0xFF009688.toInt()
        private const val ACTION_STOP_ALL = "com.giant8east.photomanager.lan.DOWNLOAD_STOP_ALL"
    }
}
