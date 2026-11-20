/**
 * 对端的连接状态推送：维护一条常开的 SSE 连接，收到任何字节就说明对端还活着。
 *
 * 只有访问方用它。主机侧不需要知道"谁连着"——共享开关一关，连接自然断掉，
 * 访问方那边立刻就能看到。
 */
package com.giant8east.photomanager.lan.client

import com.giant8east.photomanager.lan.protocol.API_PREFIX
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.SSE_READ_TIMEOUT_MILLIS
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import okhttp3.Call
import okhttp3.Request

/**
 * 一条 SSE 连接的持有者。
 *
 * 页面关掉时能精确地只取消自己那条连接。
 */
class LanEventStream @Inject constructor(
    private val http: LanHttp,
    private val pairing: PairingFlow
) {
    /**
     * 状态推送专用的 OkHttp 客户端：读超时缩短到 4 秒。
     *
     * `newBuilder()` 会复用原客户端的连接池与拦截器（包括注入 token 的那个），
     * 只是把"多久收不到字节算失联"改成推送需要的值；普通接口要传图片，30 秒是合理的。
     */
    private val client = http.client.newBuilder()
        .readTimeout(SSE_READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var call: Call? = null

    /**
     * 开始盯住 [device]：每收到一帧就发一个 [Unit]，连接断开时抛异常或正常结束。
     *
     * 这里只管"这条连接怎么了"，要不要显示"已离线"、多久重连由调用方决定。
     */
    fun watch(device: LanDevice): Flow<Unit> = flow {
        // 已有 token 时立刻返回，没有才现场配对（和普通接口用的同一套规则）
        pairing.connect(device)
        try {
            pump(device)
        } catch (_: UnauthorizedException) {
            // token 过期或被主机撤销：重配对一次；再失败就让调用方按"连不上"处理
            pairing.reconnect(device)
            pump(device)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 取消当前连接（幂等）。
     *
     * 阻塞在 `readUtf8Line()` 上的线程不会自己检查协程是否被取消，
     * 必须由调用方调它把 socket 关掉，否则会一直挂到 4 秒读超时。
     */
    fun cancel() {
        call?.cancel()
    }

    /** 真正读这条流：一行一行读，读到一行就说明通道活着。 */
    private suspend fun FlowCollector<Unit>.pump(device: LanDevice) {
        val request = Request.Builder()
            .url(http.url(device.address, "$API_PREFIX/events"))
            .header("Accept", "text/event-stream")
            .build()
        val current = client.newCall(request)
        call?.cancel()   // 旧连接退休
        call = current
        // 下游取消 → 立刻关 socket；读阻塞期间协程无法自己感知取消
        currentCoroutineContext().job.invokeOnCompletion { current.cancel() }
        try {
            current.execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 401) throw UnauthorizedException()
                    throw IOException("event stream failed with HTTP ${response.code}")
                }
                val source = response.body?.source() ?: throw IOException("event stream has no body")
                while (true) {
                    // null = 对端正常关闭了连接（关共享、进程退出）
                    val line = source.readUtf8Line() ?: return
                    // 空行是"一帧结束"的分隔符，不代表有新数据
                    if (line.isEmpty()) continue
                    emit(Unit)
                }
            }
        } finally {
            call = null
            // 幂等：已经结束的连接再取消没有副作用
            current.cancel()
        }
    }
}

/**
 * 401：token 过期或被撤销。
 *
 * 只在本文件内部用——`watch` 抓到它就重配对一次，不往上层暴露；
 * 上层看到的仍然是"连不上"，不需要区分是参数错还是 token 过期。
 */
private class UnauthorizedException : Exception("event stream rejected the token")
