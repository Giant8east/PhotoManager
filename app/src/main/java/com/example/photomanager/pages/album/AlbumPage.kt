package com.example.photomanager.pages.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings


import coil.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.setting.SettingPage
import androidx.compose.foundation.layout.Spacer

/** 相册库主页，提供搜索、分组筛选、排序入口和相册列表。 */
@Composable
fun AlbumPage(
    navigator: AppNavigator,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    AlbumGroupDrawer(
        groups = viewModel.groups,
        selectedGroupId = viewModel.selectedGroupId,
        onGroupSelected = viewModel::selectGroup
    ) {
        Box(Modifier.fillMaxSize()) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = viewModel.searchKeyword,
                        onValueChange = viewModel::updateSearchKeyword,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("搜索相册标题") },

                        trailingIcon = if (viewModel.searchKeyword.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.updateSearchKeyword("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                                }
                            }
                        } else null
                    )
                    IconButton(onClick = { navigator.pushTo { SettingPage(navigator) } }) {
                        Icon(Icons.Default.Settings, contentDescription = "目录导入管理")
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${viewModel.groupName} · ${viewModel.albums.size} 个",
                        style = MaterialTheme.typography.labelLarge
                    )
                    AlbumSortButton(
                        currentField = viewModel.sortField,
                        isDescending = viewModel.isDescending,
                        onSortSelected = viewModel::sort
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(viewModel.albums, key = { it.id }) { album ->
                        AlbumCard(album, viewModel::toggleAlbumSelection)
                    }
                }
            }
            AlbumActionFab(
                isAllSelected = viewModel.isAllSelected,
                hasSelection = viewModel.selectedAlbumIds.isNotEmpty(),
                groups = viewModel.groups,
                onSelectAll = viewModel::selectAllVisible,
                onDelete = viewModel::deleteSelected,
                onMoveToGroup = viewModel::moveSelectedToGroup,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(album: AlbumEntity, onLongClick: (Long) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { }, onLongClick = { onLongClick(album.id) })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = album.coverPath,
            contentDescription = album.name,
            modifier = Modifier
                .width(96.dp)
                .height(128.dp),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Text(
            album.name,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text("${album.photoCount} 张", style = MaterialTheme.typography.labelMedium)
    }
}

