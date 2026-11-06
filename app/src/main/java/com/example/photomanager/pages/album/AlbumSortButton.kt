package com.example.photomanager.pages.album

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.photomanager.R
import com.example.photomanager.data.AlbumSortField

/** 排序入口只负责 show，弹窗内容由 AlbumSortSheetHost 渲染。 */
@Composable
internal fun AlbumSortButton(
    currentField: AlbumSortField,
    isDescending: Boolean,
    onSortSelected: (AlbumSortField, Boolean) -> Unit,
    sheetState: AlbumSortSheetState,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    val title = stringResource(R.string.album_sort_title)
    IconButton(
        modifier = modifier,
        onClick = {
            sheetState.show(
                field = currentField,
                descending = isDescending,
                title = title,
                onSelected = onSortSelected
            )
        }
    ) {
        Icon(
            painterResource(R.drawable.ic_sort),
            contentDescription = null,
            tint = tint
        )
    }
}
