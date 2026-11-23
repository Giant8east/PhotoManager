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
import androidx.compose.material.icons.outlined.VisibilityOff
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
import coil.size.Precision
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.media.LocalAlbumCoverInterceptor
import com.giant8east.photomanager.navigation.AppNavigator
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
// 列表卡片封面区域为 80dp × 120dp，按 3x 密度封顶解码尺寸，配合 INEXACT 触发快速降采样
private const val COVER_DECODE_WIDTH_PX = 240
private const val COVER_DECODE_HEIGHT_PX = 360

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
    selectionEnabled: Boolean = true,
    showDragHandle: Boolean = true,
    trailingAction: (@Composable () -> Unit)? = null,
    statusBadge: (@Composable () -> Unit)? = null,
    // 对齐 EhViewer item_download：下载中时隐藏原有底部信息，原位替换成进度条/速度/暂停面板
    downloadingContent: (@Composable () -> Unit)? = null
) {
    val selected = album.id in selection.selectedIds
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    // 本地相册封面由 LocalAlbumCoverInterceptor 在 IO 线程解析 .thumb，主线程不做文件系统访问
    val coverRequest = remember(album.albumPath, album.coverPath, album.modifiedAt) {
        val builder = ImageRequest.Builder(context)
            .data(album.coverPath)
            .size(COVER_DECODE_WIDTH_PX, COVER_DECODE_HEIGHT_PX)
            .precision(Precision.INEXACT)
        if (album.albumPath.isNotEmpty()) {
            builder.setParameter(LocalAlbumCoverInterceptor.ALBUM_PATH_PARAM, album.albumPath)
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
        Column {
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
                if (downloadingContent != null) {
                    // 对齐 EhViewer item_download：下载中隐藏原先的统计信息与右侧普通按钮，原位展示进度条/速度/暂停
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                    ) {
                        downloadingContent()
                    }
                } else {
                    // 卡片右侧垂直居中：本地普通态是收藏、多选态是拖拽手柄；远端可插入播放/暂停下载按钮。
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 6.dp)
                            .size(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectionEnabled && selection.isSelectionMode) {
                            if (showDragHandle) {
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
                        } else {
                            trailingAction?.invoke()
                        }
                    }
                    if (statusBadge != null) {
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 8.dp, bottom = 4.dp)
                        ) {
                            statusBadge()
                        }
                    }
                    Column(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 6.dp, bottom = 4.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (album.isHidden) {
                                Icon(
                                    imageVector = Icons.Outlined.VisibilityOff,
                                    contentDescription = stringResource(R.string.album_hidden_badge),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
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
                        }
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
    }
}
