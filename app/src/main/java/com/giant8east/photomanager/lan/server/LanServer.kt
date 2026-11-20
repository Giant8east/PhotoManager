/** 服务端生命周期：启动/停止 Ktor CIO 引擎，并把系统分配的真实端口告诉调用方。 */
package com.giant8east.photomanager.lan.server

import android.util.Log
import com.giant8east.photomanager.lan.protocol.DeviceInfoDto
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer

/**
 * 只负责"把服务跑起来／停下来"：绑定端口、启动引擎、解析实际端口。
 *
 * 端口传 0 表示让系统分配空闲端口（避免和别的应用撞端口），
 * 启动后用 [port] 把地址广播出去（mDNS TXT / 通知里显示）。
 *
 * 实例是一次性的：[LanShareController] 每次开启共享都新建一个，关闭后丢弃，
 * 所以这里不处理"重复 start"。
 *
 * 语法糖说明（构造函数）：
 * - 主构造参数写成 `private val` 时，参数同时成为类属性，省去手写字段和赋值；
 * - `deviceInfo: () -> DeviceInfoDto` 是函数类型参数，等价于"传入一个获取设备信息的方法"。
 */
class LanServer(
    private val dataSource: ShareDataSource,
    private val pairing: LanPairing,
    private val tokens: LanTokenStore,
    private val deviceInfo: () -> DeviceInfoDto
) {
    // 可空字段用来表达"还没启动"这个状态：
    //   engine == null → 未启动；engine != null → 已启动
    private var engine: ApplicationEngine? = null

    var port: Int = 0
        // `private set` 是语法糖：对外只读（外部不能赋值），类内部可以赋值
        private set

    /** 启动服务并返回实际监听的端口。 */
    suspend fun start(bindPort: Int = 0): Int {
        val started = embeddedServer(CIO, port = bindPort) {
            lanModule(tokens, pairing, deviceInfo, dataSource)
        }.start(wait = false)

        // resolvedConnectors()：查询引擎实际绑定到的地址和端口（bindPort = 0 时必须查）。
        // start(wait = false) 能正常返回，就说明绑定已经完成——CIO 在 startupJob 完成之前
        // 就把 resolvedConnectors 填好了，绑定失败则由 start() 自己抛出。所以这里不需要 try。
        val resolved = started.resolvedConnectors().firstOrNull()
        if (resolved == null) {
            // 引擎起来了却拿不到连接信息，说明没真正监听：关掉它并抛异常，
            // 让上层显示"共享启动失败"，而不是拿一个假端口去广播。
            try {
                started.stop(gracePeriodMillis = 0, timeoutMillis = 0)
            } catch (error: Exception) {
                // 收尾失败只剩"引擎还占着端口"这一种后果：用户那边由下面的异常说明，
                // 这里只留排查线索，不改变本次调用的结果。
                Log.w(TAG, "failed to stop engine after unresolved connector: ${error.message}")
            }
            throw IllegalStateException("LAN server started without a resolved connector")
        }

        engine = started
        port = resolved.port
        return port
    }

    fun stop() {
        // 优雅关闭：先给最多 500ms 处理完在途请求，再最多等 1500ms 强制结束
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1_500)
        // 置空表示"回到未启动状态"，下次 start 会重新创建引擎
        engine = null
        port = 0
    }

    // companion object：Kotlin 放"类级常量"的地方，等价于 Java 的 static final
    private companion object {
        /** logcat 过滤器标签：adb logcat -s LanServer */
        const val TAG = "LanServer"
    }
}
