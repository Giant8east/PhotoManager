package com.example.photomanager.pages.album

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.example.photomanager.R
import com.example.photomanager.pages.common.SheetStack

/** 排序入口只负责把排序弹窗加入页面弹窗栈。 */
@Composable
internal fun AlbumSortButton(
    sheetStack: SheetStack,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    IconButton(
        modifier = modifier,
        onClick = {
            sheetStack.show {
                AlbumSortSheet()
            }
        }
    ) {
        Icon(
            painterResource(R.drawable.ic_sort),
            contentDescription = null,
            tint = tint
        )
    }
}
