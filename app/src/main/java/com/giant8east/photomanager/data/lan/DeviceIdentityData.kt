/** 本机身份的数据来源：deviceId 持久化在 SharedPreferences，机型与版本号来自系统。 */
package com.giant8east.photomanager.data.lan

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.giant8east.photomanager.lan.protocol.LanIdentity
import com.hippo.drawerlayout.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * deviceId 只生成一次并持久化：白名单与配对记录都按它认人，每次启动换新 id 会让"记住设备"失效。
 */
@Singleton
class DeviceIdentityData @Inject constructor(@ApplicationContext context: Context) {
    val identity: LanIdentity

    init {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 语法糖：?: 右边带 also，等价于"生成后存进变量、存盘、再把变量作为结果"
        val deviceId = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { created ->
            prefs.edit { putString(KEY_DEVICE_ID, created) }
        }
        identity = LanIdentity(
            deviceId = deviceId,
            deviceName = Build.MODEL,
            version = BuildConfig.VERSION_NAME
        )
    }

    private companion object {
        const val PREFS_NAME = "lan_share"
        const val KEY_DEVICE_ID = "device_id"
    }
}
