/** 相册列表卡：封面、名称、分组、图片数量、修改时间和容量。 */
package com.example.photomanager.pages.album

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.photomanager.R
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.ui.format.rememberAlbumModifiedTimeText

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumListCard(
    album: AlbumEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverRequest = remember(album.coverPath) {
        ImageRequest.Builder(context).data(album.coverPath).build()
    }
    var coverScale by remember(coverRequest) { mutableStateOf(ContentScale.Fit) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
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
                    val aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                    coverScale = if (aspect > 1f / 3f && aspect < 4f / 3f) {
                        ContentScale.Crop
                    } else {
                        ContentScale.Fit
                    }
                }
            )
            Column(
                Modifier
                    .weight(1f)
                    .height(120.dp)
                    .padding(start = 8.dp, top = 4.dp, end = 6.dp, bottom = 4.dp)
            ) {
                Text(
                    album.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.album_photo_count, album.photoCount),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                    Text(
                        Formatter.formatFileSize(context, album.totalSizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                Text(
                    stringResource(
                        R.string.album_modified,
                        rememberAlbumModifiedTimeText(album.modifiedAt)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}
