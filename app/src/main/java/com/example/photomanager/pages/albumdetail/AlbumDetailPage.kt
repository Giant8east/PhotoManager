/** 相册详情整页滚动，画廊使用完整图片索引，缩略图由惰性网格按需加载。 */
package com.example.photomanager.pages.albumdetail

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.decode.BitmapFactoryDecoder
import coil.request.ImageRequest
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.data.database.PhotoEntity
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.photoviewer.PhotoViewerPage

@Composable
fun AlbumDetailPage(
    navigator: AppNavigator,
    album: AlbumEntity,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }
    LaunchedEffect(album.id) { viewModel.load(album) }
    val updatedAlbum by viewModel.album.collectAsState()
    val currentAlbum = updatedAlbum?.takeIf { it.id == album.id } ?: album
    val sizeBytes = currentAlbum.totalSizeBytes
    val photos by viewModel.photos.collectAsState()
    val context = LocalContext.current
    var isDeleteVisible by remember { mutableStateOf(false) }
    var isMoreVisible by remember { mutableStateOf(false) }
    fun read(index: Int) {
        navigator.pushTo(useSafeArea = false) { PhotoViewerPage(navigator, album.id, index) }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondary)
                        .padding(start = 16.dp, top = 24.dp, end = 8.dp, bottom = 32.dp)
                ) {
                    AlbumThumbnail(
                        currentAlbum.coverPath, currentAlbum.name,
                        Modifier
                            .weight(0.42f)
                            .aspectRatio(2f / 3f)
                    )
                    Column(
                        Modifier
                            .weight(0.58f)
                            .padding(start = 16.dp)) {
                        Box(Modifier.fillMaxWidth()) {
                            Text(
                                currentAlbum.name,
                                Modifier
                                    .fillMaxWidth()
                                    .padding(end = 32.dp),
                                color = MaterialTheme.colorScheme.onSecondary,
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 5, overflow = TextOverflow.Ellipsis
                            )
                            Box(Modifier.align(Alignment.TopEnd)) {
                                IconButton(
                                    onClick = { isMoreVisible = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MoreVert,
                                        "更多操作",
                                        tint = MaterialTheme.colorScheme.onSecondary
                                    )
                                }
                                DropdownMenu(
                                    expanded = isMoreVisible,
                                    onDismissRequest = { isMoreVisible = false }) {
                                    DropdownMenuItem(
                                        text = { Text(if (viewModel.isRefreshing) "刷新中…" else "刷新") },
                                        enabled = !viewModel.isRefreshing,
                                        onClick = {
                                            isMoreVisible = false
                                            viewModel.refresh(
                                                currentAlbum,
                                                onAlbumRemoved = navigator::pop
                                            )
                                        })
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "本地相册", color = MaterialTheme.colorScheme.onSecondary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                Card(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .offset(y = (-12).dp),
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    elevation = CardDefaults.cardElevation(3.dp)
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { isDeleteVisible = true }, enabled = !viewModel.isDeleting,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) { Text("删除", fontWeight = FontWeight.Bold) }
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                        TextButton(
                            onClick = { read(currentAlbum.lastViewedPhotoIndex) },
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            Text(
                                "阅读",
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                DetailDivider()
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${currentAlbum.lastViewedPhotoIndex + 1}/${currentAlbum.photoCount}P")
                    Text(sizeBytes?.let { Formatter.formatFileSize(context, it) }
                        ?: "容量未知，请刷新")
                }
                Text(
                    "最后修改：${viewModel.displayTime(currentAlbum)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                viewModel.refreshMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                DetailDivider()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp)) {
                    Text("标签", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "暂无标签", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                DetailDivider()
                Text("画廊", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        items(photos.size, key = { photos[it].id }) { index ->
            val photo = photos[index]
            Column(
                Modifier
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .clickable { read(index) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PhotoThumbnail(photo)
                Text(
                    "${index + 1}",
                    Modifier.padding(vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp), contentAlignment = Alignment.Center) {
                if (photos.isNotEmpty())
                    Text(
                        "全部 ${photos.size} 张",
                        style = MaterialTheme.typography.bodySmall
                    )
            }
        }
    }
    if (isDeleteVisible) AlertDialog(
        onDismissRequest = { if (!viewModel.isDeleting) isDeleteVisible = false },
        title = { Text("删除相册？") },
        text = {
            Column {
                Text("将删除“${currentAlbum.name}”的真实目录及其全部文件，此操作无法撤销。")
                viewModel.deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.delete(currentAlbum) {
                    Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
                    navigator.pop()
                }
            }, enabled = !viewModel.isDeleting) {
                Text(if (viewModel.isDeleting) "删除中…" else "删除")
            }
        },
        dismissButton = {
            TextButton(
                onClick = { isDeleteVisible = false },
                enabled = !viewModel.isDeleting
            ) { Text("取消") }
        }
    )
}

@Composable
private fun DetailDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun AlbumThumbnail(uri: String?, name: String, modifier: Modifier) {
    val context = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(context).data(uri).decoderFactory(BitmapFactoryDecoder.Factory())
            .build()
    }
    AsyncImage(request, name, modifier, contentScale = ContentScale.Fit)
}

@Composable
private fun PhotoThumbnail(photo: PhotoEntity) {
    val context = LocalContext.current
    val request = remember(photo.contentUri, photo.modifiedAt, photo.sizeBytes) {
        ImageRequest.Builder(context).data(photo.contentUri)
            .decoderFactory(BitmapFactoryDecoder.Factory())
            .memoryCacheKey("${photo.contentUri}|${photo.modifiedAt}|${photo.sizeBytes}|thumbnail")
            .build()
    }
    AsyncImage(
        request,
        photo.name,
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f),
        contentScale = ContentScale.Fit
    )
}
