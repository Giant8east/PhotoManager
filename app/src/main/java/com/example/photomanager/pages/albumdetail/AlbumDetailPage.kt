/** 相册详情整页滚动，画廊使用完整图片索引，缩略图由惰性网格按需加载。 */
package com.example.photomanager.pages.albumdetail

import com.example.photomanager.ui.state.PageContent
import android.text.format.Formatter
import com.example.photomanager.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.example.photomanager.pages.common.DialogHost
import com.example.photomanager.pages.common.DialogStack
import com.example.photomanager.pages.common.DropdownMenuButton
import com.example.photomanager.pages.common.rememberDialogStack
import com.example.photomanager.ui.format.rememberAlbumModifiedTimeText
import com.example.photomanager.ui.theme.appColors
import com.example.photomanager.pages.photoviewer.PhotoViewerPage

@Composable
fun AlbumDetailPage(
    navigator: AppNavigator,
    album: AlbumEntity,
    viewModel: AlbumDetailViewModel = hiltViewModel<AlbumDetailViewModel, AlbumDetailViewModel.Factory>(
        creationCallback = { it.create(album.id, album.albumPath) }
    )
) {
    BackHandler { navigator.pop() }
    PageContent(viewModel.state, onRetry = viewModel::retryLoad) {
        content -> AlbumDetailBody(navigator, content, viewModel)
    }
}

@Composable
private fun AlbumDetailBody(
    navigator: AppNavigator,
    content: AlbumDetailUiState,
    viewModel: AlbumDetailViewModel,
) {
    val currentAlbum = content.album
    val photos = content.photos
    val colors = MaterialTheme.colorScheme
    val dialogs = rememberDialogStack()

    // 普通正文不继承宿主或按钮的文字颜色；标题栏与操作区域再按背景覆盖。
    CompositionLocalProvider(
        LocalContentColor provides colors.onBackground,
        LocalTextStyle provides TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    ) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 手动计算列数
            val density = LocalDensity.current
            val columns = with(density) {
                galleryColumnCount(
                    maxWidth.roundToPx() - GALLERY_HORIZONTAL_PADDING.roundToPx() * 2,
                    GALLERY_TARGET_COLUMN_WIDTH.roundToPx()
                )
            }

            LazyColumn(Modifier.fillMaxSize()) {
                item(key = "header") {
                    AlbumDetailHeader(album = currentAlbum, navigator = navigator,
                        viewModel = viewModel, dialogs = dialogs)
                }

                photoGridRows(items = photos, columns = columns, itemKey = { it.id }
                ) { index, photo ->
                    Column(
                        Modifier.clickable { navigator.pushTo(useSafeArea = false) {
                            PhotoViewerPage(navigator, currentAlbum.id, index)
                        } },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        PhotoThumbnail(photo)
                        Text(
                            stringResource(R.string.album_detail_photo_index, index + 1),
                            color = colors.onBackground, fontSize = 14.sp, lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
    DialogHost(dialogs)
}

@Composable
private fun DetailDivider() {
    HorizontalDivider(
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun AlbumThumbnail(uri: String) {
    val context = LocalContext.current
    val request = remember(uri) {
        ImageRequest.Builder(context).data(uri)
            .memoryCacheKey("$uri|detail-cover-animated")
            .build()
    }
    AsyncImage(request, null, Modifier.size(width = 128.dp, height = 192.dp),
        contentScale = ContentScale.Fit
    )
}

@Composable
private fun PhotoThumbnail(photo: PhotoEntity) {
    val context = LocalContext.current
    val request = remember(photo.contentUri, photo.modifiedAt) {
        ImageRequest.Builder(context).data(photo.contentUri)
            .decoderFactory(BitmapFactoryDecoder.Factory()) // 强制按静态 Bitmap 解码，只取第一帧
            .memoryCacheKey("${photo.contentUri}|${photo.modifiedAt}|gallery-static")
            .build()
    }
    var contentScale by remember(request) { mutableStateOf(ContentScale.Fit) }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(2 / 3f)
    ) {
        AsyncImage(
            request,
            null,
            Modifier.fillMaxSize(),
            contentScale = contentScale,
            onSuccess = { result ->
                val drawable = result.result.drawable
                contentScale = galleryThumbnailScale(drawable.intrinsicWidth, drawable.intrinsicHeight)
            }
        )
        PhotoFormatBadge(
            format = photo.format,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
        )
    }
}

@Composable
private fun AlbumDetailHeader(
    album: AlbumEntity,
    navigator: AppNavigator,
    viewModel: AlbumDetailViewModel,
    dialogs: DialogStack,
) {
    val lastViewedIndex = album.lastViewedPhotoIndex
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme

    Column {
        Box {
            Column(Modifier.matchParentSize()
            ) {
                Spacer(Modifier.fillMaxWidth().weight(1f).background(appColors.headerBackground))
                Spacer(Modifier.height(24.dp))
            }
            Column { Row(Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    AlbumThumbnail(album.coverPath,)
                    Column(Modifier.weight(1f).padding(start = 16.dp)
                    ) {
                        Text(
                            album.name, Modifier.fillMaxWidth().padding(end = 48.dp),
                            color = appColors.headerContent,
                            style = TextStyle(fontSize = 20.sp, lineHeight = 24.sp),
                            maxLines = 5, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.album_detail_local),
                            color = appColors.headerContent,
                            style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
                        )
                    }
                }
                Card(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp),
                    shape = RoundedCornerShape(2.dp),
                    colors = CardDefaults.cardColors(containerColor = appColors.cardBackground),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Row(Modifier.fillMaxWidth().height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { dialogs.show { AlbumDeleteDialog(
                                album = album, viewModel = viewModel,
                                navigator = navigator, dialogs = dialogs)
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Text(stringResource(R.string.album_detail_delete),
                                color = appColors.actionText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.width(1.dp).height(32.dp).background(colors.outlineVariant))
                        TextButton(
                            onClick = {
                                navigator.pushTo(useSafeArea = false) {
                                    PhotoViewerPage(navigator, album.id, lastViewedIndex)
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight()
                        ) {
                            Text(stringResource(R.string.album_detail_read),
                                color = appColors.accentActionText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            DropdownMenuButton(
                modifier = Modifier.align(Alignment.TopEnd), tint = appColors.headerContent
            ) { dismiss ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.album_detail_refresh)) },
                    onClick = { dismiss()
                        viewModel.refreshDirectory { isAlbumAvailable ->
                            if (!isAlbumAvailable) navigator.pop()
                        }
                    }
                )
            }
        }
        DetailDivider()
    }

    Column(
        Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(
                R.string.album_detail_progress,
                lastViewedIndex + 1,
                album.photoCount
                )
            )
            Text(Formatter.formatFileSize(context, album.totalSizeBytes))
        }
        Text(stringResource(
                R.string.album_detail_modified,
                rememberAlbumModifiedTimeText(album.modifiedAt)
            ),
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
        )
    }

    Column {
        DetailDivider()
        Column(Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.album_detail_no_tags),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = appColors.accentActionText,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
            )
        }
        DetailDivider()
    }
    Spacer(Modifier.height(16.dp))
}
