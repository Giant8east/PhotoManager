/** 电量数据入口，把系统电池广播归一化为百分比流，页面不直接注册广播。 */
package com.example.photomanager.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** 电量不可读时的统一取值，调用方据此隐藏电量显示。 */
const val UNKNOWN_BATTERY_LEVEL = -1

@Singleton
class BatteryData @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 观察电量百分比，收集开始时注册广播，取消收集时注销。 */
    fun observeBatteryLevel(): Flow<Int> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.toBatteryLevel())
            }
        }
        // ACTION_BATTERY_CHANGED 是粘性广播，注册时即可取回当前电量。
        val sticky = context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        trySend(sticky?.toBatteryLevel() ?: UNKNOWN_BATTERY_LEVEL)
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    /** EXTRA_LEVEL 是相对 EXTRA_SCALE 的刻度值，换算后才是百分比。 */
    private fun Intent.toBatteryLevel(): Int {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, UNKNOWN_BATTERY_LEVEL)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, UNKNOWN_BATTERY_LEVEL)
        return if (level < 0 || scale <= 0) UNKNOWN_BATTERY_LEVEL else level * 100 / scale
    }
}
