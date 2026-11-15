/** 图片格式角标，按格式决定是否显示以及显示文本。 */
package com.giant8east.photomanager.pages.albumdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giant8east.photomanager.data.media.PhotoFormat

@Composable
internal fun PhotoFormatBadge(
    format: PhotoFormat,
    modifier: Modifier = Modifier
) {
    val label = when (format) {
        PhotoFormat.GIF -> "GIF"
        else -> return
    }

    Text(
        label,
        modifier = modifier
            .background(
                Color.White.copy(alpha = 0.88f),
                RoundedCornerShape(2.dp)
            )
            .border(
                1.dp,
                Color.Black.copy(alpha = 0.35f),
                RoundedCornerShape(2.dp)
            )
            .padding(horizontal = 4.dp, vertical = 2.dp),
        color = Color.Black,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        fontWeight = FontWeight.Bold
    )
}
