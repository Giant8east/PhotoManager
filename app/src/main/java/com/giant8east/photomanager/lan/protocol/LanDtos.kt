/** 局域网协议的线上 DTO：服务端与客户端共用同一份定义，改接口时两边同时生效。 */
package com.giant8east.photomanager.lan.protocol

import kotlinx.serialization.Serializable

/**
 * 语法糖说明：
 * - `data class` 会自动生成 equals/hashCode/toString/copy，避免手写样板。
 * - `@Serializable` 是 kotlinx.serialization 的编译器插件标记：编译器会为这个类
 *   生成 `serializer()` 和 `$$serializer`，所以 IDE 里会出现自动生成的 Companion，
 *   源码中并不需要手写 `companion object`。
 */

/** 探测接口返回的设备信息，同时用于客户端展示与兼容性判断。 */
@Serializable
data class DeviceInfoDto(
    val deviceId: String,
    val name: String,
    val version: String
)

/** 已通过鉴权的会话信息：证明当前 token 属于哪台设备。 */
@Serializable
data class SessionDto(val deviceId: String)

/** 统一错误体：`code` 是业务错误码（枚举序列化为同名大写字符串），客户端按它分支。 */
@Serializable
data class ErrorDto(
    val code: LanErrorCode,
    val message: String
)

/**
 * 相册的线上表示。
 *
 * 说明：这里刻意不放 Room 实体（AlbumEntity）的字段，只保留协议需要的部分；
 * `coverUrl` 是相对路径（如 `/v1/media/968`），客户端自己拼 baseUrl。
 * 字段都不给默认值：让 JSON 里每个字段都出现，契约更稳定、测试更容易断言。
 */
@Serializable
data class LanAlbumDto(
    val id: Long,
    val name: String,
    val photoCount: Int,
    val sizeBytes: Long,
    val groupId: Long?,
    val coverUrl: String?,
    val modifiedAt: String?
)

/** 分组本身及其相册数量（收藏视图不参与共享）。 */
@Serializable
data class LanGroupDto(
    val id: Long,
    val name: String,
    val albumCount: Int
)

/**
 * 相册列表响应。
 *
 * - `defaultSort*`：宿主当前的排序设置，远端用来初始化"默认排序"或"恢复默认"；
 * - `sort*`：本次实际应用的排序（请求带参数则等于参数，否则等于 default）；
 * 两者分开返回，避免把用户当前选择误当成宿主默认值。
 */
@Serializable
data class LanAlbumListDto(
    val albums: List<LanAlbumDto>,
    val defaultSortField: LanSortField,
    val defaultSortDescending: Boolean,
    val sortField: LanSortField,
    val sortDescending: Boolean
)

/** 远端抽屉需要的完整分组信息：分组列表 + 未分组相册数量。 */
@Serializable
data class LanGroupListDto(
    val groups: List<LanGroupDto>,
    val ungroupedCount: Int
)

/**
 * 图片的线上表示。
 *
 * `mediaUrl` 由服务端给出相对路径，客户端不需要知道 URL 模板；
 * `width`/`height` 允许为 0（尚未读取到尺寸时）。
 */
@Serializable
data class LanPhotoDto(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val format: String?,
    val mediaUrl: String
)

/**
 * 配对请求：只支持"主机确认"一种方式。
 *
 * 带 `deviceId` 是为了让主机在白名单里查找"这台设备是否被记住过"；
 * 陌生设备会拿到 `202 + requestId`，等主机点"允许"。
 */
@Serializable
data class LanPairRequestDto(
    val deviceId: String,
    val name: String
)

/**
 * 配对成功：只返回 token。
 *
 * 不返回 `expiresAt`：token 是滑动过期（每次使用顺延），
 * 客户端只需要在 401 时重新配对。
 */
@Serializable
data class LanPairResponseDto(
    val token: String
)

/** 配对进行中：主机需要用户确认，客户端拿这个 id 轮询结果。 */
@Serializable
data class LanPairPendingDto(val requestId: String)
