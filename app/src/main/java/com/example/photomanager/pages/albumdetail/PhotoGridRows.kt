/** 将项目按列分组成 LazyColumn 行；只负责行内排列，不负责 header/footer 或列数计算。 */
package com.example.photomanager.pages.albumdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun <T> LazyListScope.photoGridRows(
    items: List<T>,
    columns: Int,
    itemKey: (T) -> Any,
    horizontalPadding: Dp = GALLERY_HORIZONTAL_PADDING,
    columnSpacing: Dp = 8.dp,
    rowSpacing: Dp = 8.dp,
    itemContent: @Composable (index: Int, item: T) -> Unit
) {
    val rows = items.chunked(columns)
    itemsIndexed(
        items = rows,
        key = { _, row -> itemKey(row.first()) }
    ) { rowIndex, row ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
                .padding(bottom = if (rowIndex < rows.lastIndex) rowSpacing else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(columnSpacing)
        ) {
            row.forEachIndexed { column, item ->
                Box(Modifier.weight(1f)) {
                    itemContent(rowIndex * columns + column, item)
                }
            }
            repeat(columns - row.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}
