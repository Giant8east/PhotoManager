/** 排序字段的线上枚举：客户端只传 wire 值，服务端负责映射成本地排序表达式。 */
package com.giant8east.photomanager.lan.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 与本地 `AlbumSortField` 一一对应，但**不依赖** app 模块（`:lan` 是纯 JVM）。
 *
 * - `wire`：查询参数与 JSON 里的取值（大小写敏感，仅小写 kamelCase）；
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
