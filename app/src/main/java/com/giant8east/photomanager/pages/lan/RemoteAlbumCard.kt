/** 远端相册卡片：基于 AlbumCardFrame 组合下载进度替换面板、下载状态徽标与单本下载/暂停操作。 */
package com.giant8east.photomanager.pages.lan

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.lan.RemoteDownloadStatus
import com.giant8east.photomanager.data.lan.RemoteDownloadTask
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.album.AlbumSelectionState
import com.giant8east.photomanager.pages.common.AlbumCardFrame
import com.giant8east.photomanager.pages.common.AlbumCardMessageStyle
import com.giant8east.photomanager.ui.format.rememberAlbumModifiedTimeText

@Composable
internal fun RemoteAlbumCard(
    album: LanAlbumDto,
    device: LanDevice,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    task: RemoteDownloadTask?,
    isDownloaded: Boolean,
    onToggleDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val formattedSize = remember(album.sizeBytes) {
        Formatter.formatFileSize(context, album.sizeBytes)
    }
    // 等价于 if (album.coverUrl != null) "http://${device.address}${album.coverUrl}" else ""
    val coverUri = album.coverUrl?.let { "http://${device.address}$it" } ?: ""
    val status = task?.status
    val isActive = status is RemoteDownloadStatus.Queued || status is RemoteDownloadStatus.Downloading
    val downloading = status as? RemoteDownloadStatus.Downloading

    AlbumCardFrame(
        title = album.name,
        coverUri = coverUri,
        coverVersionKey = album.modifiedAt,
        selected = album.id in selection.selectedIds,
        onClick = {
            if (selection.isSelectionMode) {
                selection.toggle(album.id)
            } else {
                navigator.pushTo {
                    RemoteAlbumDetailPage(navigator = navigator, device = device, album = album)
                }
            }
        },
        onLongClick = { selection.enter(album.id) },
        modifier = modifier
    ) {
        if (downloading != null) {
            // 对齐 EhViewer item_download：下载中隐藏原先的统计信息与右侧普通按钮，原位展示进度条/速度/暂停
            RemoteDownloadingPanel(
                downloading = downloading,
                onPause = onToggleDownload,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
            )
        } else {
            // 非下载态的右侧按钮：排队中显示暂停，其余（含已下载）显示播放以开始或增量刷新
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 6.dp)
                    .size(40.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onToggleDownload,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 8.dp, bottom = 4.dp)
            ) {
                RemoteDownloadStatusBadge(status = status, isDownloaded = isDownloaded)
            }

            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    text = stringResource(R.string.album_read_progress, 1, album.photoCount),
                    style = AlbumCardMessageStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
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
}

@Composable
private fun RemoteDownloadingPanel(
    downloading: RemoteDownloadStatus.Downloading,
    onPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val speedText = "${Formatter.formatShortFileSize(context, downloading.bytesPerSecond)}/s"
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (downloading.total > 0) "${downloading.finished}/${downloading.total}" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                text = speedText,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (downloading.total > 0) {
            LinearProgressIndicator(
                progress = { (downloading.finished.toFloat() / downloading.total).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(4.dp)
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .height(4.dp)
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            IconButton(
                onClick = onPause,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RemoteDownloadStatusBadge(
    status: RemoteDownloadStatus?,
    isDownloaded: Boolean
) {
    when (status) {
        is RemoteDownloadStatus.Queued -> Text(
            text = stringResource(R.string.lan_download_queued),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        is RemoteDownloadStatus.Paused -> Text(
            text = stringResource(R.string.lan_download_paused_short, status.finished, status.total),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        is RemoteDownloadStatus.Failed -> Text(
            text = stringResource(R.string.lan_download_failed),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1
        )
        is RemoteDownloadStatus.Finished -> Text(
            text = stringResource(R.string.lan_download_completed_badge),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        else -> if (isDownloaded) {
            Text(
                text = stringResource(R.string.lan_download_completed_badge),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }
    }
}
