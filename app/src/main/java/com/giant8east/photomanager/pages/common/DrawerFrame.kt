/** 可复用抽屉内容框架：只负责标题栏、右上角动作区和内容区域，具体列表由页面组合。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giant8east.photomanager.ui.theme.appColors

/**
 * 抽屉的通用外观框架。
 *
 * 页面通过 [headerActions] 提供右上角按钮，通过 [content] 提供具体列表；
 * 框架本身不感知分组、相册等业务数据。
 */
@Composable
fun DrawerFrame(
    title: String,
    modifier: Modifier = Modifier,
    headerActions: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(appColors.toolbarBackground)
        ) {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp),
                    fontSize = 20.sp,
                    color = appColors.toolbarContent
                )
                headerActions()
            }
        }

        Box(Modifier.weight(1f)) {
            content()

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x38000000), Color.Transparent)
                        )
                    )
            )
        }
    }
}
