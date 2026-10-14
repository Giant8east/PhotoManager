/** 相册详情页，展示相册信息和分页图片网格。 */
package com.example.photomanager.pages.albumdetail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import coil.compose.AsyncImage
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.photoviewer.PhotoViewerPage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailPage(
    navigator: AppNavigator,
    album: AlbumEntity,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }
    LaunchedEffect(album.id) { viewModel.load(album.id) }
    val photos = viewModel.photos.collectAsLazyPagingItems()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album.name, maxLines = 1) },
                navigationIcon = { TextButton(onClick = navigator::pop) { Text("返回") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(16.dp)) {
                AsyncImage(
                    model = album.coverPath,
                    contentDescription = album.name,
                    modifier = Modifier.size(width = 96.dp, height = 128.dp),
                    contentScale = ContentScale.Crop
                )
                Column(Modifier.padding(start = 16.dp)) {
                    Text(album.name, style = MaterialTheme.typography.titleLarge)
                    Text("${album.photoCount} 张图片", style = MaterialTheme.typography.bodyMedium)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(photos.itemCount) { index ->
                    val photo = photos[index]
                    if (photo != null) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                navigator.pushTo { PhotoViewerPage(navigator, album.id, index) }
                            }
                        ) {
                            AsyncImage(
                                model = photo.contentUri,
                                contentDescription = photo.name,
                                modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f),
                                contentScale = ContentScale.Fit
                            )
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
