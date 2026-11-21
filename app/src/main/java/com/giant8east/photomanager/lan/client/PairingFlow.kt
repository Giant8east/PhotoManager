/** 访客侧配对流程：POST /v1/pair 后按 200/202/403/409 分支，202 时轮询直到有结果。 */
package com.giant8east.photomanager.lan.client

import com.giant8east.photomanager.lan.protocol.API_PREFIX
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.LanIdentity
import com.giant8east.photomanager.lan.protocol.LanPairPendingDto
import com.giant8east.photomanager.lan.protocol.LanPairRequestDto
import com.giant8east.photomanager.lan.protocol.LanPairResponseDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 配对失败的原因，类型本身就是"哪一类失败"。
 *
 * `message` 只给日志看，写技术描述、不当界面文案：界面文案由
 * `com.giant8east.photomanager.pages.lan.toLanUiText()` 按类型查 `R.string.*`，
 * 从不读这里的 `message`。两边文案分开写，看 toast 和看日志时才分得清是哪一层给的。
 */
sealed class LanPairFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 主机点了拒绝。 */
    class Rejected : LanPairFailure("peer rejected the pair request")

    /** 主机一直没确认，请求过期。 */
    class Expired : LanPairFailure("pair request expired without confirmation")

    /** 连不上：不在同一网络、对方关闭了共享、地址变了。 */
    class Unreachable(cause: Throwable?) : LanPairFailure("cannot reach the peer device", cause)
}

/**
 * 配对是唯一带请求体、且要按 200/202/403/409 分支的接口，
 * 所以这里直接用 OkHttp 原始调用，而不是 Retrofit 的强类型返回。
 */
@Singleton
class PairingFlow @Inject constructor(
    private val http: LanHttp,
    private val identity: LanIdentity,
    private val sessions: LanSessionRegistry
) {
    private val mutex = Mutex()
    // 同一台设备（address）正在进行中的配对请求：并发到达的 connect / reconnect 直接复用，不重复发 POST /v1/pair
    private val inFlight = mutableMapOf<String, CompletableDeferred<String>>()

    /** 已经配对过就直接复用 token；否则走完整流程（同设备并发调用自动合并为一次请求）。 */
    suspend fun connect(device: LanDevice): String = acquireToken(device, forceRefresh = false)

    /** 401 之后重新配对：先丢掉旧 token，再走一次完整流程（同设备并发 401 自动合并）。 */
    suspend fun reconnect(device: LanDevice): String = acquireToken(device, forceRefresh = true)

    private suspend fun acquireToken(device: LanDevice, forceRefresh: Boolean): String =
        withContext(Dispatchers.IO) {
            val address = device.address
            var cachedToken: String? = null
            var joined: CompletableDeferred<String>? = null
            var leader: CompletableDeferred<String>? = null

            mutex.withLock {
                val running = inFlight[address]
                if (running != null) {
                    // 已有在途配对就直接搭便车，不重复发起网络请求
                    joined = running
                } else if (forceRefresh) {
                    // 只有成为领跑者时才清旧 token，避免并发 reconnect 反复擦除状态
                    sessions.remove(address)
                    val created = CompletableDeferred<String>()
                    inFlight[address] = created
                    leader = created
                } else {
                    val token = sessions.tokenFor(address)
                    if (token != null) {
                        cachedToken = token
                    } else {
                        val created = CompletableDeferred<String>()
                        inFlight[address] = created
                        leader = created
                    }
                }
            }

            val hit = cachedToken
            if (hit != null) return@withContext hit
            val running = joined
            if (running != null) return@withContext running.await()

            val active = checkNotNull(leader)
            try {
                val issued = try {
                    submitPair(device)
                } catch (io: IOException) {
                    throw LanPairFailure.Unreachable(io)   // 连不上/超时，和"被拒绝"区分开
                }
                sessions.put(address, issued)
                active.complete(issued)
                issued
            } catch (error: Throwable) {
                active.completeExceptionally(error)
                throw error
            } finally {
                // 即使当前协程被取消，也必须清掉占位条目，否则后续请求会永远 await 一个失效的 deferred
                withContext(NonCancellable) {
                    mutex.withLock { inFlight.remove(address, active) }
                }
            }
        }

    private suspend fun submitPair(device: LanDevice): String {
        val payload = http.json
            .encodeToString(LanPairRequestDto(deviceId = identity.deviceId, name = identity.deviceName))
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(http.url(device.address, "$API_PREFIX/pair"))
            .post(payload)
            .build()

        val (code, body) = execute(request)
        return when (code) {
            200 -> http.json.decodeFromString<LanPairResponseDto>(body).token
            202 -> pollUntilDecided(device.address, http.json.decodeFromString<LanPairPendingDto>(body).requestId)
            403 -> throw LanPairFailure.Rejected()
            409 -> throw LanPairFailure.Expired()
            // 其余状态码说明对端不是我们预期的版本，或它自己出错了；把码带进日志，别只说"连不上"
            else -> throw LanPairFailure.Unreachable(IOException("unexpected HTTP $code"))
        }
    }

    /** 主机还没点按钮：每秒钟问一次，最多等 60 秒。 */
    private suspend fun pollUntilDecided(address: String, requestId: String): String {
        val url = http.url(address, "$API_PREFIX/pair/$requestId")
        repeat(POLL_TIMES) {
            delay(POLL_INTERVAL_MILLIS.milliseconds)
            val (code, body) = execute(Request.Builder().url(url).build())
            when (code) {
                200 -> return http.json.decodeFromString<LanPairResponseDto>(body).token
                202 -> Unit
                403 -> throw LanPairFailure.Rejected()
                404 -> throw LanPairFailure.Expired()          // 请求已被主机丢弃（重启共享/结果过期）
                409 -> throw LanPairFailure.Expired()
                else -> throw LanPairFailure.Unreachable(IOException("unexpected HTTP $code"))
            }
        }
        throw LanPairFailure.Expired()
    }

    /** 同步执行一次请求，返回状态码与响应体文本。 */
    private fun execute(request: Request): Pair<Int, String> =
        http.client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }

    private companion object {
        val JSON = "application/json".toMediaType()
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val POLL_TIMES = 60
    }
}
