/** 访客侧门面：页面只需要拿着设备列表，调用 start/stop 控制浏览。 */
package com.giant8east.photomanager.lan.platform

import android.content.Context
import com.giant8east.photomanager.lan.guest.LanDevice
import kotlinx.coroutines.flow.StateFlow

/**
 * 对外只暴露"发现结果 + 开关"两件事，mDNS 细节留在 guest 内部。
 * 页面用 `LanDiscovery(context)` 直接构造，不需要注入。
 */
class LanDiscovery(context: Context) {
    private val browser = MdnsBrowser(context)

    val devices: StateFlow<List<LanDevice>> = browser.devices

    fun start() = browser.start()

    fun stop() = browser.stop()
}
