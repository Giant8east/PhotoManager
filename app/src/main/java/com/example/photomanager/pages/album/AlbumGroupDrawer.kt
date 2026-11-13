/** 相册分组抽屉内容：使用通用抽屉框架组合分组标题、管理入口和分组列表。 */
package com.example.photomanager.pages.album

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photomanager.R
import com.example.photomanager.data.database.GroupWithAlbumCount
import com.example.photomanager.pages.common.DrawerFrame
import com.example.photomanager.pages.common.AppLazyColumnScrollbar
import com.example.photomanager.ui.theme.appColors

@Composable
internal fun AlbumGroupDrawer(
    groupsWithCounts: List<GroupWithAlbumCount>,
    ungroupedAlbumCount: Int,
    favoriteCount: Int,
    selectedGroupId: Long?,
    favoritesSelected: Boolean,
    onGroupSelected: (Long?) -> Unit,
    onFavoritesSelected: () -> Unit,
    onManageGroups: () -> Unit,
    onCloseDrawer: () -> Unit,
    // 远端设备不共享收藏视图，抽屉里也就不出现这一项。
    showFavorites: Boolean = true
) {
    DrawerFrame(
        title = stringResource(R.string.album_group_title),
        headerActions = {
            IconButton(
                onClick = {
                    onManageGroups()
                    onCloseDrawer()
                }
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = appColors.toolbarContent
                )
            }
        }
    ) {
        val listState = rememberLazyListState()
        AppLazyColumnScrollbar(state = listState) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item {
                    if (showFavorites) {
                        Column {
                            DrawerRow(
                                label = stringResource(R.string.album_group_favorites),
                                count = favoriteCount,
                                selected = favoritesSelected,
                                onClick = {
                                    onFavoritesSelected()
                                    onCloseDrawer()
                                }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
                item {
                    Column {
                        DrawerRow(
                            label = stringResource(R.string.album_group_ungrouped),
                            count = ungroupedAlbumCount,
                            selected = selectedGroupId == null && !favoritesSelected,
                            onClick = {
                                onGroupSelected(null)
                                onCloseDrawer()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                items(groupsWithCounts, key = { it.group.id }) { groupWithCount ->
                    val group = groupWithCount.group
                    Column {
                        DrawerRow(
                            label = group.name,
                            count = groupWithCount.albumCount,
                            selected = group.id == selectedGroupId,
                            onClick = {
                                onGroupSelected(group.id)
                                onCloseDrawer()
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerRow(
    label: String,
    count: Int,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            fontSize = 16.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = count.toString(),
            modifier = Modifier.padding(start = 8.dp),
            fontSize = 16.sp,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
