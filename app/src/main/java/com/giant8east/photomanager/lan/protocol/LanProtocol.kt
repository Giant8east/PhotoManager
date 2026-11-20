/** 两端共享的协议词汇：常量、错误码、排序字段。改这里主机与访客同时生效。 */
package com.giant8east.photomanager.lan.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 鉴权头名字。自定义头，避免与标准 Authorization 混淆；客户端拦截器统一注入。 */
const val TOKEN_HEADER = "token"

/** 协议版本前缀，所有业务接口都挂在它下面。 */
const val API_PREFIX = "/v1"

/** mDNS/DNS-SD 服务类型：分享方用它注册、访客方按它发现，两端必须完全一致。 */
const val LAN_SERVICE_TYPE = "_photomanager._tcp"

/**
 * 连接状态推送（`GET /v1/events`）的心跳间隔：服务端每 1.5 秒写一帧注释行。
 *
 * 心跳不是为了"告诉客户端我还活着"——真关机时连接会被立刻关闭；
 * 它是为了**静默断链**：Wi-Fi 断了、对端掉电，TCP 不会马上报错，
 * 只能靠"这段时间一个字节都没收到"来判断。
 */
const val SSE_HEARTBEAT_MILLIS = 1_500L

/**
 * 访客侧的读超时：连续这么久收不到任何字节就判定对端失联。
 *
 * 必须明显大于 [SSE_HEARTBEAT_MILLIS]（留出 2～3 次心跳的余量），否则正常抖动也会误判。
 */
const val SSE_READ_TIMEOUT_MILLIS = 4_000L

/** 分页默认页大小：请求不带 limit 时服务端用它。 */
const val DEFAULT_PAGE_LIMIT = 60

/** 单页上限：limit 超过它按它截断，避免一次请求要走完整个相册库。 */
const val MAX_PAGE_LIMIT = 200

/**
 * 业务错误码（与 HTTP 状态码是两回事）：
 * - HTTP 状态码在响应行里，表示"哪一类失败"（400/401/404…）；
 * - 这里的错误码在响应体里，表示"具体是哪一种失败"，客户端用它做精确分支。
 *
 * 序列化时用枚举名本身作为字符串（`UNAUTHORIZED` 等），
 * 因此**重命名枚举项等于改协议**，改名必须同步客户端与文档。
 */
@Serializable
enum class LanErrorCode {
    UNAUTHORIZED,
    INVALID_ID,
    INVALID_SORT,

    /** 分页参数非法：offset 为负或 limit 不是正整数。 */
    INVALID_PAGE,
    NOT_FOUND,

    /** 配对：主机用户拒绝。 */
    PAIR_REJECTED,

    /** 配对：等待确认超过 60 秒。 */
    PAIR_TIMEOUT
}

/**
 * 排序字段的线上枚举：客户端只传 wire 值，服务端负责映射成本地排序表达式。
 *
 * - `defaultDescending`：切换字段时的默认方向，与本地枚举保持一致。
 *
 * 语法糖：`@SerialName("custom")` 让 JSON 里输出 `"custom"` 而不是枚举名 `CUSTOM`；
 * 不加这个注解时，kotlinx 默认用 `enum.name`（全大写）。
 */
@Serializable
enum class LanSortField(
    val wire: String,
    val defaultDescending: Boolean
) {
    @SerialName("custom") CUSTOM("custom", false),
    @SerialName("name") NAME("name", false),
    @SerialName("modified") MODIFIED("modified", true),
    @SerialName("photoCount") PHOTO_COUNT("photoCount", true),
    @SerialName("fileSize") FILE_SIZE("fileSize", true);

    companion object {
        /**
         * 解析查询参数；未知值返回 null。
         *
         * "参数缺省"和"参数非法"是两种语义：缺省由调用方直接使用默认排序，
         * 非法（返回 null 且参数存在）由路由返回 400。
         */
        fun fromWire(value: String): LanSortField? = entries.firstOrNull { it.wire == value }
    }
}
