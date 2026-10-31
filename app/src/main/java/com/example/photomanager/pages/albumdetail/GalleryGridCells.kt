package com.example.photomanager.pages.albumdetail

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 对齐 EhViewer 的合适列宽策略；偏差相同时选择更多列。 */
internal fun galleryColumnCount(availableWidth: Int, targetWidth: Int): Int {
    val columns = availableWidth / targetWidth
    if (columns <= 0) return 1
    val nextColumns = columns + 1
    val currentDifference = abs(availableWidth.toLong() - columns.toLong() * targetWidth)
    val nextDifference = abs(availableWidth.toLong() - nextColumns.toLong() * targetWidth)
    return if (currentDifference * nextColumns < nextDifference * columns) columns else nextColumns
}

/** 头部保持全宽；仅画廊单元格分担两侧 16dp 和列间 8dp 的留白。 */
internal class GalleryGridCells(private val columnCount: Int) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val edge = 16.dp.roundToPx()
        val gap = 8.dp.roundToPx()
        val halfGap = gap / 2
        val paddingWidths = List(columnCount) { column ->
            (if (column == 0) edge else gap - halfGap) +
                (if (column == columnCount - 1) edge else halfGap)
        }
        val imageSpace = availableSize - paddingWidths.sum() - spacing * (columnCount - 1)
        val imageWidth = imageSpace / columnCount
        val remainder = imageSpace % columnCount
        return List(columnCount) { column ->
            imageWidth + (if (column < remainder) 1 else 0) + paddingWidths[column]
        }
    }
}

/** 只有常规竖图裁剪铺满；横图、超长图及边界比例完整居中显示。 */
internal fun galleryThumbnailScale(width: Int, height: Int): ContentScale {
    if (width <= 0 || height <= 0) return ContentScale.Fit
    val aspect = width.toFloat() / height
    return if (aspect > 0.5f && aspect < 0.8f) ContentScale.Crop else ContentScale.Fit
}
