/** 设备身份的平台实现：从 SharedPreferences 读 deviceId，从系统读机型与版本号。 */
package com.giant8east.photomanager.lan.platform

import android.content.Context
import android.os.Build.MODEL
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit
import com.hippo.drawerlayout.BuildConfig

/**
 * 设备标识只在首次启动时生成，之后持久化，避免每次重启都变成"新设备"
 * （白名单与配对记录都按这个 id 认人）。
 */
@Singleton
class AndroidLanIdentity @Inject constructor(@ApplicationContext context: Context) {
    val deviceId: String
    val deviceName: String
    val version: String = BuildConfig.VERSION_NAME

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        deviceId = existing ?: UUID.randomUUID().toString().also { created ->
            prefs.edit { putString(KEY_DEVICE_ID, created) }
        }
        deviceName = MODEL
    }

    private companion object {
        const val PREFS_NAME = "lan_share"
        const val KEY_DEVICE_ID = "device_id"
    }
}