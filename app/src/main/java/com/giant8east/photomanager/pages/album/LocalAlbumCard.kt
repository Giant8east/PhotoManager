/** 本地相册卡片：基于 AlbumCardFrame 组合收藏/多选拖拽、隐藏标识与本地阅读统计信息。 */
package com.giant8east.photomanager.pages.album

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.albumdetail.AlbumDetailPage
import com.giant8east.photomanager.pages.common.AlbumCardFrame
import com.giant8east.photomanager.pages.common.AlbumCardMessageStyle
import com.giant8east.photomanager.ui.format.rememberAlbumModifiedTimeText
import com.giant8east.photomanager.ui.theme.FavoriteRed

@Composable
internal fun LocalAlbumCard(
    album: AlbumEntity,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    onToggleFavorite: (AlbumEntity) -> Unit,
    dragHandleModifier: Modifier,
    reorderEnabled: Boolean,
    onReorderBlocked: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val formattedSize = remember(album.totalSizeBytes) {
        Formatter.formatFileSize(context, album.totalSizeBytes)
    }
    AlbumCardFrame(
        title = album.name,
        coverUri = album.coverPath,
        localAlbumPath = album.albumPath,
        coverVersionKey = album.modifiedAt,
        selected = album.id in selection.selectedIds,
        onClick = {
            if (selection.isSelectionMode) {
                selection.toggle(album.id)
            } else {
                navigator.pushTo { AlbumDetailPage(navigator, album) }
            }
        },
        onLongClick = { selection.enter(album.id) },
        modifier = modifier
    ) {
        // 卡片右侧垂直居中：普通态显示收藏按钮，多选态显示拖拽手柄
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp)
                .size(40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selection.isSelectionMode) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(
                            if (reorderEnabled) dragHandleModifier
                            else Modifier.clickable(onClick = onReorderBlocked)
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
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable { onToggleFavorite(album) },
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
                    text = stringResource(
                        R.string.album_read_progress,
                        album.lastViewedPhotoIndex + 1,
                        album.photoCount
                    ),
                    style = AlbumCardMessageStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formattedSize,
                    style = AlbumCardMessageStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    text = rememberAlbumModifiedTimeText(album.modifiedAt),
                    style = AlbumCardMessageStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
