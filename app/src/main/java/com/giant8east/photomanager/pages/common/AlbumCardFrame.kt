/** 相册卡片基础壳：统一卡片外观、点击/长按震动、左侧缩略图封面与顶部两行标题，右侧内容由本地/远端卡片各自组合。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giant8east.photomanager.ui.theme.appColors

// 对齐 EhViewer 的 TextView：常规字重、自然行高，并保留字体上下留白。
val AlbumCardTitleStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    letterSpacing = 0.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = true)
)
val AlbumCardMessageStyle = AlbumCardTitleStyle.copy(fontSize = 14.sp)

// 列表卡片封面区域为 80dp × 120dp，按 3x 密度封顶解码尺寸
private const val COVER_DECODE_WIDTH_PX = 240
private const val COVER_DECODE_HEIGHT_PX = 360

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCardFrame(
    title: String,
    coverUri: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    localAlbumPath: String = "",
    coverVersionKey: Any? = null,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val haptic = LocalHapticFeedback.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { callback ->
                    // 等价于 if (onLongClick != null) { ... } else null
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        callback()
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
                ThumbnailImage(
                    uri = coverUri,
                    decodeWidthPx = COVER_DECODE_WIDTH_PX,
                    decodeHeightPx = COVER_DECODE_HEIGHT_PX,
                    localAlbumPath = localAlbumPath,
                    cacheVersionKey = coverVersionKey,
                    modifier = Modifier
                        .width(80.dp)
                        .height(120.dp)
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
                            text = title,
                            style = AlbumCardTitleStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    content()
                }
            }
        }
    }
}
