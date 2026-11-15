/** 通用的图标下拉菜单按钮，内部管理展开状态并把关闭回调交给菜单内容。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun DropdownMenuButton(
    modifier: Modifier = Modifier,
    tint: Color,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(width = 48.dp, height = 56.dp)
        ) {
            Icon(
                Icons.Default.MoreVert,
                null,
                tint = tint
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            menuContent { expanded = false }
        }
    }
}
