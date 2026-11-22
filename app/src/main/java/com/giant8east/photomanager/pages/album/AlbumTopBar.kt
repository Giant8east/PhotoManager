/** 相册主页顶部栏：排序、搜索和设置入口。 */
package com.giant8east.photomanager.pages.album

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import com.giant8east.photomanager.R
import com.giant8east.photomanager.pages.common.SearchTopBar

@Composable
internal fun AlbumTopBar(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    // 搜索、排序和设置共用同一个可用状态：进入多选后全部禁用。
    actionsEnabled: Boolean,
    onSortClick: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
        .copy(alpha = if (actionsEnabled) 1f else 0.38f)
    SearchTopBar(
        searchInput = searchInput,
        onSearchInputChange = onSearchInputChange,
        modifier = modifier,
        enabled = actionsEnabled,
        leadingContent = {
            IconButton(enabled = actionsEnabled, onClick = onSortClick) {
                Icon(
                    painterResource(R.drawable.ic_sort),
                    contentDescription = null,
                    tint = iconTint
                )
            }
        },
        trailingContent = {
            IconButton(enabled = actionsEnabled, onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = iconTint
                )
            }
        }
    )
}
