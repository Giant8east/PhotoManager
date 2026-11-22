/** 相册列表卡：封面、名称、阅读进度、修改时间和容量。 */
package com.giant8east.photomanager.pages.album

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.media.ThumbGenerator
import com.giant8east.photomanager.navigation.AppNavigator
import java.io.File
import com.giant8east.photomanager.pages.albumdetail.AlbumDetailPage
import com.giant8east.photomanager.ui.format.rememberAlbumModifiedTimeText
import com.giant8east.photomanager.ui.theme.FavoriteRed
import com.giant8east.photomanager.ui.theme.appColors

// 对齐 EhViewer 的 TextView：常规字重、自然行高，并保留字体上下留白。
private val CardTitleStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = true)
)
private val CardMessageStyle = CardTitleStyle.copy(fontSize = 14.sp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumListCard(
    album: AlbumEntity,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    onToggleFavorite: (AlbumEntity) -> Unit,
    dragHandleModifier: Modifier,
    reorderEnabled: Boolean,
    onReorderBlocked: () -> Unit,
    modifier: Modifier = Modifier,
    // 远端相册复用同一张卡片：点击交给调用方，多选与收藏按需关闭。
    onCardClick: (() -> Unit)? = null,
    showFavorite: Boolean = true,
    selectionEnabled: Boolean = true
) {
    val selected = album.id in selection.selectedIds
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // 本地相册优先使用 .thumb 压缩图并带 lastModified 作缓存键，避免换首图后 Coil 命中旧图
    val coverRequest = remember(album.albumPath, album.coverPath, album.modifiedAt) {
        val localThumb = if (album.albumPath.isNotEmpty()) {
            File(album.albumPath, ThumbGenerator.THUMB_FILE_NAME).takeIf { it.isFile }
        } else {
            null
        }
        val builder = ImageRequest.Builder(context)
        if (localThumb != null) {
            builder.data(localThumb)
                .memoryCacheKey("${localThumb.path}|${localThumb.lastModified()}")
        } else {
            builder.data(album.coverPath)
        }
        builder.build()
    }
    var coverScale by remember(coverRequest) { mutableStateOf(ContentScale.Fit) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when {
                        selectionEnabled && selection.isSelectionMode -> selection.toggle(album.id)
                        onCardClick != null -> onCardClick()
                        else -> navigator.pushTo { AlbumDetailPage(navigator, album) }
                    }
                },
                onLongClick = {
                    if (selectionEnabled) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        selection.enter(album.id)
                    }
                }
            )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(2.dp),
            shape = RoundedCornerShape(2.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) appColors.selectedCardBackground
                else appColors.cardBackground
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
        Row {
            AsyncImage(
                model = coverRequest,
                contentDescription = null,
                modifier = Modifier
                    .width(80.dp)
                    .height(120.dp),
                contentScale = coverScale,
                onSuccess = { result ->
                    val drawable = result.result.drawable
                    val width = drawable.intrinsicWidth
                    val height = drawable.intrinsicHeight
                    if (width > 0 && height > 0) {
                        val aspect = width.toFloat() / height
                        coverScale = if (aspect > 1f / 3f && aspect < 4f / 3f) {
                            ContentScale.Crop
                        } else {
                            ContentScale.Fit
                        }
                    }
                }
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(120.dp)
            ) {
                Column(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .padding(start = 8.dp, top = 4.dp, end = 46.dp)
                ) {
                    Text(
                        album.name,
                        style = CardTitleStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 卡片右侧垂直居中：普通态是收藏开关，多选态在原地换成拖拽手柄。
                Box(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp)
                        .size(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (selectionEnabled && selection.isSelectionMode) {
                        Box(
                            Modifier.fillMaxSize().then(
                                if (reorderEnabled) {
                                    dragHandleModifier
                                } else {
                                    Modifier.clickable(onClick = onReorderBlocked)
                                }
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DragHandle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                    alpha = if (reorderEnabled) 1f else 0.38f
                                )
                            )
                        }
                    } else if (showFavorite) {
                        Box(
                            Modifier.fillMaxSize().clickable { onToggleFavorite(album) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (album.isFavorite) Icons.Filled.Favorite
                                else Icons.Filled.FavoriteBorder,
                                contentDescription = null,
                                tint = if (album.isFavorite) FavoriteRed
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 6.dp, bottom = 4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        stringResource(
                            R.string.album_read_progress,
                            album.lastViewedPhotoIndex + 1,
                            album.photoCount
                        ),
                        style = CardMessageStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            Formatter.formatFileSize(context, album.totalSizeBytes),
                            style = CardMessageStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Text(
                            rememberAlbumModifiedTimeText(album.modifiedAt),
                            style = CardMessageStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
    }
}
