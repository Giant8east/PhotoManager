/** 访客侧配对流程：POST /v1/pair 后按 200/202/403/409 分支，202 时轮询直到有结果。 */
package com.giant8east.photomanager.lan.guest

import com.giant8east.photomanager.lan.protocol.API_PREFIX
import com.giant8east.photomanager.lan.platform.AndroidLanIdentity
import com.giant8east.photomanager.lan.protocol.LanPairPendingDto
import com.giant8east.photomanager.lan.protocol.LanPairRequestDto
import com.giant8east.photomanager.lan.protocol.LanPairResponseDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 配对失败的原因；界面只需要一行提示，所以这里直接带文案。 */
sealed class LanPairFailure(message: String) : Exception(message) {
    /** 主机点了拒绝。 */
    class Rejected : LanPairFailure("对方拒绝了配对请求")

    /** 主机一直没确认，请求过期。 */
    class Expired : LanPairFailure("对方没有确认，配对已超时")

    /** 连不上：不在同一网络、对方关闭了共享、地址变了。 */
    class Unreachable(cause: Throwable?) : LanPairFailure("无法连接到对方设备")
}

/**
 * 配对是唯一带请求体、且要按 200/202/403/409 分支的接口，
 * 所以这里直接用 OkHttp 原始调用，而不是 Retrofit 的强类型返回。
 */
@Singleton
class PairingFlow @Inject constructor(
    private val http: LanHttp,
    private val identity: AndroidLanIdentity,
    private val sessions: LanSessionRegistry
) {
    /** 已经配对过就直接复用 token；否则走完整流程。 */
    suspend fun connect(device: LanDevice): String = withContext(Dispatchers.IO) {
        sessions.tokenFor(device.address)?.let { return@withContext it }

        // 先 ping 一次：把"地址不通"与"配对被拒"区分开，提示更准确
        runCatching { http.api.ping(http.url(device.address, "$API_PREFIX/ping")) }
            .getOrElse { throw LanPairFailure.Unreachable(it) }

        val issued = submitPair(device)
        sessions.put(device.address, issued)
        issued
    }

    /** 401 之后重新配对：先丢掉旧 token，再走一次完整流程。 */
    suspend fun reconnect(device: LanDevice): String {
        sessions.remove(device.address)
        return connect(device)
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
            else -> throw LanPairFailure.Unreachable(null)
        }
    }

    /** 主机还没点按钮：每秒钟问一次，最多等 60 秒。 */
    private suspend fun pollUntilDecided(address: String, requestId: String): String {
        val url = http.url(address, "$API_PREFIX/pair/$requestId")
        repeat(POLL_TIMES) {
            delay(POLL_INTERVAL_MILLIS)
            val (code, body) = execute(Request.Builder().url(url).build())
            when (code) {
                200 -> return http.json.decodeFromString<LanPairResponseDto>(body).token
                202 -> Unit
                403 -> throw LanPairFailure.Rejected()
                409 -> throw LanPairFailure.Expired()
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
