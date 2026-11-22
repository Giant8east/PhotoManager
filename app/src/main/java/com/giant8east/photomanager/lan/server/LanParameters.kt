/**
 * 路由参数解析：把"取值 + 校验 + 报错"收成一行表达式，路由里不再出现 if + return。
 *
 * 报错统一靠抛异常，再由 `lanModule` 里装的一次 `StatusPages` 变成 400 —— 一处映射，所有接口生效。
 */
package com.giant8east.photomanager.lan.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.ApplicationRequest

/**
 * 参数不合法：路径 id 不是数字、排序字段未知、分页参数越界、请求体字段为空等。
 *
 * 继承 [BadRequestException] 有两层作用：
 * 1. Ktor 引擎内置的状态码映射会把它变成 400（即使没装 StatusPages 也不会漂成 500）；
 * 2. `StatusPages` 按这个类型注册一个 handler，就能把细节写成协议规定的 `ErrorDto`。
 */
class InvalidParameterException(val name: String, val value: String?) :
    BadRequestException("invalid parameter $name=$value")

/** 路径里的数字参数（`/v1/albums/{id}/photos` 的 id）：缺失或不是数字都算参数错误。 */
fun ApplicationCall.longPathParameter(name: String): Long {
    val raw = parameters[name]
    return raw?.toLongOrNull() ?: throw InvalidParameterException(name, raw)
}

/** 路径里的必填文本参数：空串与缺失同样处理（`/v1/pair/{requestId}`）。 */
fun ApplicationCall.requiredPathParameter(name: String): String {
    val raw = parameters[name]
    return raw?.takeIf { it.isNotBlank() } ?: throw InvalidParameterException(name, raw)
}

/** query 里的数字参数，缺省 [default]；给了但不是数字或小于 [minimum] 就抛。 */
fun ApplicationRequest.intParameter(name: String, default: Int, minimum: Int): Int {
    val raw = queryParameters[name] ?: return default
    return raw.toIntOrNull()?.takeIf { it >= minimum } ?: throw InvalidParameterException(name, raw)
}

/** query 里的可选数字参数：不传返回 null（表示"不限"），传了就必须是数字。 */
fun ApplicationRequest.longParameter(name: String): Long? {
    val raw = queryParameters[name] ?: return null
    return raw.toLongOrNull() ?: throw InvalidParameterException(name, raw)
}

/** query 里的可选布尔参数：只接受 `true` / `false`，其余一律算参数错误。 */
fun ApplicationRequest.booleanParameter(name: String): Boolean? = when (val raw = queryParameters[name]) {
    null -> null
    "true" -> true
    "false" -> false
    else -> throw InvalidParameterException(name, raw)
}

/** 请求体里的必填文本：按调用方给的范围校验长度，例如 deviceId 的 `1..64`。 */
fun String.requireLength(name: String, range: IntRange): String =
    takeIf { it.length in range } ?: throw InvalidParameterException(name, this)
