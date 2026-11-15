/** 顶部栏与正文之间的柔和分割阴影。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun TopBarShadow(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.22f),
                        Color.Transparent
                    )
                )
            )
    )
}
