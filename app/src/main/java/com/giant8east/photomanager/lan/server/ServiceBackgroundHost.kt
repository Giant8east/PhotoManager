/** 只负责拉起/停掉前台服务，不含任何业务判断。 */
package com.giant8east.photomanager.lan.server

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "要不要保活"由控制器决定；这里回答"怎么保活"——用启动前台服务的方式。
 *
 * 通知内容与关闭动作都在 [LanShareService] 里，跟着 `LanShareState` 走。
 */
@Singleton
class ServiceBackgroundHost @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun start() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, LanShareService::class.java)
        )
    }

    fun stop() {
        context.stopService(Intent(context, LanShareService::class.java))
    }
}
