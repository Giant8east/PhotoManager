/** 限定相册排序字段，避免将外部输入作为 SQL 列名执行。 */
package com.example.photomanager.data

import com.example.photomanager.R
import androidx.annotation.StringRes
import kotlinx.serialization.Serializable

@Serializable
enum class AlbumSortField(
    @get:StringRes val titleRes: Int,
    @get:StringRes val ascendingRes: Int,
    @get:StringRes val descendingRes: Int,
    internal val sqlExpression: String,
    val defaultDescending: Boolean
) {
    CUSTOM(
        R.string.sort_custom,
        R.string.album_sort_order_manual,
        R.string.album_sort_order_manual,
        "sortOrder",
        false
    ),
    NAME(
        R.string.sort_name,
        R.string.album_sort_order_name_ascending,
        R.string.album_sort_order_name_descending,
        "name COLLATE NOCASE",
        false
    ),
    MODIFIED_AT(
        R.string.sort_modified,
        R.string.album_sort_order_oldest_first,
        R.string.album_sort_order_newest_first,
        "julianday(modifiedAt)",
        true
    ),
    PHOTO_COUNT(
        R.string.sort_photo_count,
        R.string.album_sort_order_fewest_first,
        R.string.album_sort_order_most_first,
        "photoCount",
        true
    ),
    FILE_SIZE(
        R.string.sort_file_size,
        R.string.album_sort_order_smallest_first,
        R.string.album_sort_order_largest_first,
        "totalSizeBytes",
        true
    )
}