/** 服务端路由与鉴权：只负责协议层，业务数据由调用方以 lambda 注入。 */
package com.giant8east.photomanager.lan

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import java.io.File

/** 鉴权头名字，自定义头，避免与标准 Authorization 混淆，客户端拦截器统一注入。 */
const val TOKEN_HEADER = "token"

/** 鉴权通过后把设备 id 放进 call 的属性里，路由里用 [deviceId] 读取。 */
private val DeviceIdKey = AttributeKey<String>("LanDeviceId")

/**
 * 服务端模块：装 JSON 内容协商与 Range 支持，注册开放接口（ping）与受保护接口，
 * 再通过 [routes] 挂载调用方自己的路由。
 *
 * 鉴权是**统一**的：所有需要 token 的接口都写在 [authenticated] 块里，
 * 由块上的拦截器一次性校验，路由内部不再各自调用 `requireDevice`。
 *
 * 参数说明：
 * - [tokens]：token 仓库，测试可注入预置实例；
 * - [deviceInfo]：函数类型参数 `() -> DeviceInfoDto`，把"设备名从哪来"交给 Android 侧；
 * - [dataSource]：共享数据的只读快照来源，路由不直接依赖 Room；
 * - [routes]：带接收者的 lambda（等价于 `(Route) -> Unit`），用于追加业务路由。
 */
fun Application.lanModule(
    tokens: LanTokenStore,
    pairing: LanPairing,
    deviceInfo: () -> DeviceInfoDto,
    dataSource: ShareDataSource,
    routes: Route.() -> Unit = {}
) {
    // install 是 Ktor 的插件机制：注册插件后会介入请求处理管道
    install(ContentNegotiation) {
        // 对象 ↔ JSON 的转换由插件完成，并自动设置 Content-Type
        json()
    }
    // PartialContent：让 respondFile 自动支持 Range 请求（206 / Content-Range）
    install(PartialContent)
    // AutoHeadResponse：把 HEAD 请求转给同路径的 GET 处理，客户端可用它探测大小/类型
    install(AutoHeadResponse)

    // routing { } 用 lambda 构建路由表：路径 + HTTP 方法 → 处理函数
    routing {
        // 开放接口：任何同网段设备都能访问，用于发现后确认"这是谁"
        get("/v1/ping") {
            call.respond(deviceInfo())
        }

        // 配对：本身必须开放（它就是用来换 token 的）
        post("/v1/pair") {
            val request = call.receive<LanPairRequestDto>()
            if (request.deviceId.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(LanErrorCode.INVALID_ID, "deviceId must not be blank")
                )
                return@post
            }
            call.respondPairOutcome(pairing.submit(request))
        }

        // 轮询：202 表示仍在等待，200 表示已批准，403 拒绝，409 超时
        get("/v1/pair/{requestId}") {
            val requestId = call.parameters["requestId"]
            if (requestId.isNullOrBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto(LanErrorCode.INVALID_ID, "requestId must not be blank")
                )
                return@get
            }
            call.respondPairOutcome(pairing.poll(requestId))
        }

        // 受保护接口：统一在 authenticated 里校验 token，块内路由不需要各自校验
        authenticated(tokens) {
            get("/v1/session") {
                // 语法糖：checkNotNull 在属性缺失时抛异常（正常流程由拦截器保证存在）
                call.respond(SessionDto(checkNotNull(call.deviceId())))
            }

            // 相册列表：sort/desc 缺省 = 宿主默认排序；非法参数 400
            get("/v1/albums") {
                val sortParam = call.request.queryParameters["sort"]
                // 判断sort字段是否合法
                val sort = sortParam?.let(LanSortField::fromWire)
                if (sortParam != null && sort == null) {
                    call.respondInvalidSort("unsupported sort: $sortParam")
                    return@get
                }

                // 判断desc字段是否合法
                val descParam = call.request.queryParameters["desc"]
                val descending = when (descParam) {
                    null -> null
                    "true" -> true
                    "false" -> false
                    else -> {
                        call.respondInvalidSort("desc must be true or false")
                        return@get
                    }
                }

                // 关键词：空白视为"没有搜索"，避免 q="" 走成 LIKE '%%' 全匹配
                val query = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }

                call.respond(dataSource.albums(sort, descending, query))
            }

            // 分组 + 计数 + 未分组数量（不含收藏视图）
            get("/v1/groups") {
                call.respond(dataSource.groups())
            }

            // 某相册的图片列表；相册不存在时返回空数组（共享只包含有图的相册）
            get("/v1/albums/{id}/photos") {
                val albumId = call.parameters["id"]?.toLongOrNull()
                if (albumId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto(LanErrorCode.INVALID_ID, "album id must be a number")
                    )
                    return@get
                }
                call.respond(dataSource.photos(albumId))
            }

            // 图片字节流：respondFile + PartialContent 自动处理 Range/Content-Type
            get("/v1/media/{id}") {
                val photoId = call.parameters["id"]?.toLongOrNull()
                if (photoId == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto(LanErrorCode.INVALID_ID, "photo id must be a number")
                    )
                    return@get
                }
                val media = dataSource.mediaFile(photoId)
                if (media == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(LanErrorCode.NOT_FOUND, "media not found"))
                    return@get
                }
                call.respondFile(File(media.path))
            }
        }

        // 调用方追加的业务路由（例如 /v1/pair，它本身需要保持未鉴权）
        routes()
    }
}

/**
 * 把 [build] 里的所有路由包进一个"必须先通过 token 校验"的子路由。
 *
 * 实现方式：创建一个永远匹配的子 Route，在 Plugins 阶段插入拦截器；
 * 校验失败时写 401 并 `finish()`，路由处理函数不会再执行。
 *
 * 语法糖：`Route.authenticated(...)` 是扩展函数，等价于 `authenticated(route, ...)`；
 * `build: Route.() -> Unit` 是带接收者的 lambda，块内 `this` 是那个子 Route，
 * 所以可以直接写 `get("/v1/...") { }`。
 */
fun Route.authenticated(tokens: LanTokenStore, build: Route.() -> Unit) {
    val secured = createChild(AlwaysMatchSelector)
    secured.intercept(ApplicationCallPipeline.Plugins) {
        // header(...) 返回 String?；?.let 等价于 if (值 != null) 把它传给 resolve
        val deviceId = call.request.header(TOKEN_HEADER)?.let(tokens::resolve)
        if (deviceId == null) {
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorDto(LanErrorCode.UNAUTHORIZED, "missing or invalid $TOKEN_HEADER")
            )
            // finish() 表示"本次请求到此结束"，后面的路由处理函数不会再执行
            finish()
        } else {
            call.attributes.put(DeviceIdKey, deviceId)
        }
    }
    secured.build()
}

/** 鉴权通过后的设备 id；未走 [authenticated] 的请求里为 null。 */
fun ApplicationCall.deviceId(): String? = attributes.getOrNull(DeviceIdKey)

/** 永远匹配的路由选择器：只用来创造一层"包住所有受保护路由"的节点。 */
private object AlwaysMatchSelector : RouteSelector() {
    override fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation =
        RouteSelectorEvaluation.Constant
}

/** 把配对结果映射成 HTTP 状态码与业务错误码。 */
private suspend fun ApplicationCall.respondPairOutcome(outcome: LanPairing.Outcome) {
    when (outcome) {
        is LanPairing.Outcome.Issued -> respond(
            LanPairResponseDto(token = outcome.token)
        )

        is LanPairing.Outcome.Waiting -> respond(
            HttpStatusCode.Accepted,
            LanPairPendingDto(requestId = outcome.requestId)
        )

        LanPairing.Outcome.Rejected -> respond(
            HttpStatusCode.Forbidden,
            ErrorDto(LanErrorCode.PAIR_REJECTED, "pairing rejected")
        )

        LanPairing.Outcome.Expired -> respond(
            HttpStatusCode.Conflict,
            ErrorDto(LanErrorCode.PAIR_TIMEOUT, "pairing request expired")
        )

        LanPairing.Outcome.Unknown -> respond(
            HttpStatusCode.NotFound,
            ErrorDto(LanErrorCode.NOT_FOUND, "pairing request not found")
        )
    }
}

/** 统一的排序参数错误响应；抽出来避免两处重复拼 code/message。 */
private suspend fun ApplicationCall.respondInvalidSort(message: String) {
    respond(HttpStatusCode.BadRequest, ErrorDto(code = LanErrorCode.INVALID_SORT, message = message))
}

/** 媒体类型推断；未知扩展名退回二进制流，避免客户端把图片当文本解析。 */
internal fun contentTypeOf(fileName: String): ContentType =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> ContentType.Image.JPEG
        "png" -> ContentType.Image.PNG
        "webp" -> ContentType("image", "webp")
        "gif" -> ContentType.Image.GIF
        "bmp" -> ContentType("image", "bmp")
        "heic", "heif" -> ContentType("image", "heic")
        "avif" -> ContentType("image", "avif")
        else -> ContentType.Application.OctetStream
    }
