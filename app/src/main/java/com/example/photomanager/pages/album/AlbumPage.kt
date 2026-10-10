/** 相册库主页，提供搜索、分组筛选、排序入口和相册列表。 */
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.res.painterResource
import com.example.photomanager.R
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.navigation.AppNavigator

@Composable
fun AlbumPage(
    navigator: AppNavigator,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { }) {
                    Icon(Icons.Default.Menu, contentDescription = "打开分组菜单")
                }
                OutlinedTextField(
                    value = viewModel.searchKeyword,
                    onValueChange = viewModel::updateSearchKeyword,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("搜索相册标题") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (viewModel.searchKeyword.isNotEmpty()) {
                        {
                            IconButton(onClick = { viewModel.updateSearchKeyword("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "清空搜索")
                            }
                        }
                    } else null
                )
                IconButton(onClick = { }) {
                    Icon(Icons.Default.Add, contentDescription = "导入目录")
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("全部相册 · ${viewModel.visibleAlbums.size} 个", style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = { }) {
                    Icon(painterResource(R.drawable.ic_sort), contentDescription = "相册排序")
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(viewModel.visibleAlbums, key = { it.id }) { album ->
                    AlbumCard(album, viewModel::toggleAlbumSelection)
                }
            }
        }
        FloatingActionButton(onClick = viewModel::selectAllVisible, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) {
            Icon(Icons.Default.Add, contentDescription = "全选或取消全选")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(album: AlbumEntity, onLongClick: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(onClick = { }, onLongClick = { onLongClick(album.id) }).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(album.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${album.photoCount} 张", style = MaterialTheme.typography.labelMedium)
    }
}
