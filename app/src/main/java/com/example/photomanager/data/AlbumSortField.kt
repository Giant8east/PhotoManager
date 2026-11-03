/** 限定相册排序字段，避免将外部输入作为 SQL 列名执行。 */
package com.example.photomanager.data

import com.example.photomanager.R
import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

@Serializable
enum class AlbumSortField(@get:StringRes val titleRes: Int, internal val sqlExpression: String) {
    CUSTOM(R.string.sort_custom, "sortOrder"),
    ID(R.string.sort_id, "id"),
    NAME(R.string.sort_name, "name COLLATE NOCASE"),
    MODIFIED_AT(R.string.sort_modified, "julianday(modifiedAt)"),
    PHOTO_COUNT(R.string.sort_photo_count, "photoCount")
}