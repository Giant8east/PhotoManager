/**
 * 相册排序字段的词汇表：本地相册页、远端浏览、HTTP 协议三处共用这一份"有哪些排序"的定义。
 *
 * 这个包只收**零依赖的跨层词汇**：不 import 资源 id（那是 UI 的事），不 import SQL（那是数据层的事），
 * 也不 import 任何 Android 类型。所以它既可以被 `data` 用（拼 ORDER BY），也可以被 `lan` 用（拼 `?sort=`），
 * 而不用破坏"lan 不依赖数据层"这条可 grep 的规则。
 *
 * 各层自己的细节留在各自家里，靠穷尽 `when` 由编译器保证不遗漏：
 * - 界面文案（`titleRes` / 方向文案）→ `pages/album/AlbumSortSheet.kt`
 * - ORDER BY 片段 → `data/album/AlbumQueryBuilder.kt`
 *
 * 注意：不要给枚举项加 `@SerialName`。DataStore 里的 `album_list_settings.json` 存的是枚举名
 * （`"MODIFIED_AT"`），加了注解会让旧文件解码失败 → `ReplaceFileCorruptionHandler` 静默重置
 * 用户的排序设置。协议上的小写取值走 [wire]，由 `AlbumSortFieldWireSerializer` 单独处理。
 */
package com.giant8east.photomanager.model

import kotlinx.serialization.Serializable

@Serializable
enum class AlbumSortField(
    /** 切到该字段时的默认方向，UI 用它决定"下一个方向"。 */
    val defaultDescending: Boolean,
    /** 协议取值（`GET /v1/albums?sort=` 用）；只用于 HTTP，不参与 DataStore 序列化。 */
    val wire: String
) {
    CUSTOM(defaultDescending = false, wire = "custom"),
    NAME(defaultDescending = false, wire = "name"),
    MODIFIED_AT(defaultDescending = true, wire = "modified"),
    PHOTO_COUNT(defaultDescending = true, wire = "photoCount"),
    FILE_SIZE(defaultDescending = true, wire = "fileSize");

    companion object {
        /**
         * 解析协议取值；未知值返回 null。
         *
         * "参数缺省"与"参数非法"是两种语义：缺省由调用方回落到宿主默认排序，
         * 非法（返回 null 且参数存在）由路由返回 400。
         */
        fun fromWire(value: String): AlbumSortField? = entries.firstOrNull { it.wire == value }
    }
}
