/** 相册修改时间的展示格式化，只负责把数据层保存的 UTC ISO 字符串转换为本地时间文本。 */
package com.example.photomanager.ui.format

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.example.photomanager.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AlbumTimePattern = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")

/** 无法解析的旧数据按未知时间处理，避免展示层因脏数据崩溃。 */
internal fun formatAlbumModifiedTime(modifiedAt: String?): String? =
    modifiedAt?.let {
        runCatching {
            AlbumTimePattern.withZone(ZoneId.systemDefault()).format(Instant.parse(it))
        }.getOrNull()
    }

@Composable
internal fun rememberAlbumModifiedTimeText(modifiedAt: String?): String {
    val unknown = stringResource(R.string.unknown_modified_time)
    return remember(modifiedAt, unknown) {
        formatAlbumModifiedTime(modifiedAt) ?: unknown
    }
}
