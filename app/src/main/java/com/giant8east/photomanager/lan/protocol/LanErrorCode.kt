/** 协议错误码：集中定义，避免各处手写字符串导致客户端匹配不上。 */
package com.giant8east.photomanager.lan.protocol

import kotlinx.serialization.Serializable

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
    NOT_FOUND,

    /** 配对：主机用户拒绝。 */
    PAIR_REJECTED,

    /** 配对：等待确认超过 60 秒。 */
    PAIR_TIMEOUT
}
