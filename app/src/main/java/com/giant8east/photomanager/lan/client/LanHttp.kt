/** 访客侧 HTTP 装配：一个 OkHttpClient（含 token 拦截器）+ 基于它的 Retrofit 接口。 */
package com.giant8east.photomanager.lan.client

import com.giant8east.photomanager.lan.protocol.LanAlbumListDto
import com.giant8east.photomanager.lan.protocol.LanGroupListDto
import com.giant8east.photomanager.lan.protocol.LanPhotoDto
import com.giant8east.photomanager.lan.protocol.DeviceInfoDto
import com.giant8east.photomanager.lan.protocol.SessionDto
import com.giant8east.photomanager.lan.protocol.TOKEN_HEADER
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Url

/** 访客侧的只读接口；地址由调用方按设备拼好，所以每个方法都用 `@Url` 收绝对地址。 */
interface LanApi {
    @GET
    suspend fun ping(@Url url: String): DeviceInfoDto

    @GET
    suspend fun session(@Url url: String): SessionDto

    @GET
    suspend fun albums(
        @Url url: String,
        @Query("sort") sort: String?,
        @Query("desc") desc: Boolean?,
        @Query("q") query: String?,
        @Query("groupId") groupId: Long?,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int
    ): LanAlbumListDto

    @GET
    suspend fun groups(@Url url: String): LanGroupListDto

    @GET
    suspend fun photos(@Url url: String): List<LanPhotoDto>
}

/**
 * 按目标 `host:port` 注入 token 的拦截器。
 *
 * 这样页面与 Coil 都只面对普通 URL（`http://ip:port/v1/media/12`），
 * token 永远不出现在 URL 里，日志与分享链接也不会泄漏。
 */
private class TokenInterceptor(private val sessions: LanSessionRegistry) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val address = "${request.url.host}:${request.url.port}"
        // ?.let 等价于 if (token != null) 才加头
        val token = sessions.tokenFor(address)
        // 等价于 if (token == null) 用原 request，否则加 token 头
        val withToken = if (token == null) request
        else request.newBuilder().header(TOKEN_HEADER, token).build()
        return chain.proceed(withToken)
    }
}

@Singleton
class LanHttp @Inject constructor(sessions: LanSessionRegistry) {
    val json: Json = Json {
        // 对端升级协议后多出字段时，旧客户端不该直接崩
        ignoreUnknownKeys = true
    }

    /** Coil 与 Retrofit 共用这一个 client：同一套连接池、超时与 token 注入。 */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(TokenInterceptor(sessions))
        .build()

    val api: LanApi = Retrofit.Builder()
        // 全部请求用 @Url 绝对地址，这里只是 Retrofit 的必填占位
        .baseUrl("http://127.0.0.1/")
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(LanApi::class.java)

    /** 拼某个设备的接口地址；call 只接受已规范化的 `ip:port`。 */
    fun url(address: String, path: String): String = "http://$address$path"
}
