/** 相册详情的列数、列宽和缩略图裁剪策略，不包含具体网格布局。 */
package com.giant8east.photomanager.pages.albumdetail

import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

internal val GALLERY_HORIZONTAL_PADDING: Dp = 16.dp
internal val GALLERY_TARGET_COLUMN_WIDTH: Dp = 120.dp

/** 对齐 EhViewer 的合适列宽策略；偏差相同时选择更多列。 */
internal fun galleryColumnCount(availableWidth: Int, targetWidth: Int): Int {
    val columns = availableWidth / targetWidth
    if (columns <= 0) return 1
    val nextColumns = columns + 1
    val currentDifference = abs(availableWidth.toLong() - columns.toLong() * targetWidth)
    val nextDifference = abs(availableWidth.toLong() - nextColumns.toLong() * targetWidth)
    return if (currentDifference * nextColumns < nextDifference * columns) columns else nextColumns
}

/** 只有常规竖图裁剪铺满；横图、超长图及边界比例完整居中显示。 */
internal fun galleryThumbnailScale(width: Int, height: Int): ContentScale {
    if (width <= 0 || height <= 0) return ContentScale.Fit
    val aspect = width.toFloat() / height
    return if (aspect > 0.5f && aspect < 0.8f) ContentScale.Crop else ContentScale.Fit
}
