/** 服务端路由与鉴权：只负责协议层，业务数据由调用方以 lambda 注入。 */
package com.giant8east.photomanager.lan.server

import com.giant8east.photomanager.model.AlbumSortField
import com.giant8east.photomanager.lan.protocol.DEFAULT_PAGE_LIMIT
import com.giant8east.photomanager.lan.protocol.DeviceInfoDto
import com.giant8east.photomanager.lan.protocol.ErrorDto
import com.giant8east.photomanager.lan.protocol.LanErrorCode
import com.giant8east.photomanager.lan.protocol.LanPairPendingDto
import com.giant8east.photomanager.lan.protocol.LanPairRequestDto
import com.giant8east.photomanager.lan.protocol.LanPairResponseDto
import com.giant8east.photomanager.lan.protocol.MAX_PAGE_LIMIT
import com.giant8east.photomanager.lan.protocol.SSE_HEARTBEAT_MILLIS
import com.giant8east.photomanager.lan.protocol.SessionDto
import com.giant8east.photomanager.lan.protocol.TOKEN_HEADER
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/** 鉴权通过后把设备 id 放进 call 的属性里，路由里用 [deviceId] 读取。 */
private val DeviceIdKey = AttributeKey<String>("LanDeviceId")

/** deviceId / name 的长度上限：两者都会进主机弹窗、白名单表和列表 UI，超长没有业务意义。 */
private const val MAX_DEVICE_FIELD_LENGTH = 64

/**
 * 服务端模块：装 JSON 内容协商与 Range 支持，注册开放接口（ping、配对）与受保护接口。
 *
 * 鉴权是**统一**的：所有需要 token 的接口都写在 [authenticated] 块里，
 * 由块上的拦截器一次性校验，路由内部不再各自调用 `requireDevice`。
 *
 * 参数说明：
 * - [tokens]：token 仓库，测试可注入预置实例；
 * - [deviceInfo]：函数类型参数 `() -> DeviceInfoDto`，把"设备名从哪来"交给 Android 侧；
 * - [dataSource]：共享数据的只读快照来源，路由不直接依赖 Room。
 */
fun Application.lanModule(
    tokens: LanTokenStore,
    pairing: LanPairing,
    deviceInfo: () -> DeviceInfoDto,
    dataSource: ShareDataSource
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
    // StatusPages：全模块唯一的"异常 → HTTP 响应"映射处。
    //
    // 参数校验失败一律靠抛异常（见 LanParameters.kt），路由里不再写 respond + return；
    // 这三个 handler 按异常类型由具体到宽泛匹配，具体类型永远优先。
    install(StatusPages) {
        // 参数错误：走自定义异常，message 里已经写明是哪个参数、什么值
        exception<InvalidParameterException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorDto(LanErrorCode.INVALID_PARAM, cause.message ?: "invalid parameter")
            )
        }
        // 请求体无法反序列化（JSON 语法错误、缺必填字段）：当成参数错误，而不是服务端 500。
        // ContentTransformationException 不继承 BadRequestException，引擎默认会把它归到 500。
        exception<ContentTransformationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorDto(LanErrorCode.INVALID_PARAM, "malformed request body: ${cause.message}")
            )
        }
        // Ktor 自己抛的 400（例如 MissingFieldException）：同样按协议格式返回
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorDto(LanErrorCode.INVALID_PARAM, cause.message ?: "bad request")
            )
        }
        // 兜底：必须留日志，否则线上 500 会变成"用户说连不上、我们什么都查不到"。
        // 同时不能把 message 回给对端 —— 同网段任何设备都能看到，内部信息会泄漏。
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled failure on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto(LanErrorCode.INTERNAL, "internal error")
            )
        }
    }

    // routing { } 用 lambda 构建路由表：路径 + HTTP 方法 → 处理函数
    routing {
        // 开放接口：任何同网段设备都能访问，用于发现后确认"这是谁"
        get("/v1/ping") {
            call.respond(deviceInfo())
        }

        // 配对：本身必须开放（它就是用来换 token 的）
        post("/v1/pair") {
            val request = call.receive<LanPairRequestDto>()
            // deviceId 是白名单主键：不 trim 的话 "abc" 和 " abc " 会被当成两台不同设备。
            // name 会原样进主机弹窗与白名单列表，长度不设上限等于把外部字符串直接塞进 UI 和数据库。
            val deviceId = request.deviceId.trim().requireLength("deviceId", 1..MAX_DEVICE_FIELD_LENGTH)
            val name = request.name.trim().take(MAX_DEVICE_FIELD_LENGTH)
            call.respondPairOutcome(pairing.submit(request.copy(deviceId = deviceId, name = name)))
        }

        // 轮询：202 表示仍在等待，200 表示已批准，403 拒绝，409 超时
        get("/v1/pair/{requestId}") {
            call.respondPairOutcome(pairing.poll(call.requiredPathParameter("requestId")))
        }

        // 受保护接口：统一在 authenticated 里校验 token，块内路由不需要各自校验
        authenticated(tokens) {
            get("/v1/session") {
                // 语法糖：checkNotNull 在属性缺失时抛异常（正常流程由拦截器保证存在）
                call.respond(SessionDto(checkNotNull(call.deviceId())))
            }

            // 相册列表：sort/desc 缺省 = 宿主默认排序；groupId 缺省 = 未分组；分页参数非法 400
            get("/v1/albums") {
                val request = AlbumListRequest(
                    // 未知排序字段算参数错误（缺省则是 null = 跟随宿主设置）
                    sortField = call.request.queryParameters["sort"]?.let { raw ->
                        AlbumSortField.fromWire(raw) ?: throw InvalidParameterException("sort", raw)
                    },
                    descending = call.request.booleanParameter("desc"),
                    // 关键词原样透传：空白归一化与"空白视为无搜索"统一由 AlbumQueryBuilder 处理
                    query = call.request.queryParameters["q"],
                    // 分组：缺省表示未分组（与宿主首页默认视图一致）；有关键词时数据源会忽略它
                    groupId = call.request.longParameter("groupId"),
                    // 分页：offset 缺省 0，limit 缺省 DEFAULT_PAGE_LIMIT，超过上限按上限截断
                    offset = call.request.intParameter("offset", default = 0, minimum = 0),
                    limit = call.request.intParameter("limit", default = DEFAULT_PAGE_LIMIT, minimum = 1)
                        .coerceAtMost(MAX_PAGE_LIMIT)
                )
                call.respond(dataSource.albums(request))
            }

            // 分组 + 计数 + 未分组数量（不含收藏视图）
            get("/v1/groups") {
                call.respond(dataSource.groups())
            }

            // 某相册的图片列表；相册不存在时返回空数组（共享只包含有图的相册）
            get("/v1/albums/{id}/photos") {
                call.respond(dataSource.photos(call.longPathParameter("id")))
            }

            // 图片字节流：respondFile + PartialContent 自动处理 Range/Content-Type
            get("/v1/media/{id}") {
                val media = dataSource.mediaFile(call.longPathParameter("id"))
                if (media == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(LanErrorCode.NOT_FOUND, "media not found"))
                    return@get
                }
                // 数据库里有这一行不代表文件还在：删除流程先删文件再更新记录，
                // 别的 App 删图也不会通知我们，扫描没跟上之前这条记录一直是有效的。
                // 直接 respondFile 会在读文件时抛 FileNotFoundException，客户端只看到一张坏图。
                val file = File(media.path)
                if (!file.isFile) {
                    call.respond(HttpStatusCode.NotFound, ErrorDto(LanErrorCode.NOT_FOUND, "media file missing"))
                    return@get
                }
                call.respondFile(file)
            }

            // 连接状态推送（SSE）：一条一直挂着的 GET，访客端用它判断"对端还在不在"。
            //
            // 现在只写注释行（以 ':' 开头，协议规定客户端要忽略），不带业务数据；
            // V2 的"删相册实时消失""下载进度"会以 `event:` + `data:` 帧复用这条连接。
            // Ktor 2.3 没有官方 SSE 插件（3.0 才有），所以这里手写流式响应。
            get("/v1/events") {
                call.respondTextWriter(ContentType.parse("text/event-stream")) {
                    try {
                        // 唯一的出口是"协程被取消"（对端断开时连接关闭）或写失败，不是死循环
                        while (true) {
                            // 先写再等：客户端一连上就拿到首帧，读超时从这一刻开始计时
                            write(": keep-alive\n\n")
                            flush()
                            delay(SSE_HEARTBEAT_MILLIS.milliseconds)
                        }
                    } catch (_: IOException) {
                        // 客户端已经关掉连接（对端退页面、关共享、掉线），push 到此结束，属正常收尾
                    }
                }
            }
        }
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
