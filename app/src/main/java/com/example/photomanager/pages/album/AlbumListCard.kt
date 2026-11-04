/** 相册列表卡：封面、名称、阅读进度、修改时间和容量。 */
package com.example.photomanager.pages.album

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.example.photomanager.R
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.albumdetail.AlbumDetailPage
import com.example.photomanager.ui.format.rememberAlbumModifiedTimeText
import com.example.photomanager.ui.theme.appColors

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
) {
    val selected = album.id in selection.selectedIds
    val context = LocalContext.current
    val coverRequest = remember(album.coverPath) {
        ImageRequest.Builder(context).data(album.coverPath).build()
    }
    var coverScale by remember(coverRequest) { mutableStateOf(ContentScale.Fit) }
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(2.dp)
        .combinedClickable(
            onClick = {
                if (selection.isSelectionMode) {
                    selection.toggle(album.id)
                } else {
                    navigator.pushTo { AlbumDetailPage(navigator, album) }
                }
            },
            onLongClick = { selection.enter(album.id) }
        ),
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
                        .padding(start = 8.dp, top = 4.dp, end = 8.dp)
                ) {
                    Text(
                        album.name,
                        style = CardTitleStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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
