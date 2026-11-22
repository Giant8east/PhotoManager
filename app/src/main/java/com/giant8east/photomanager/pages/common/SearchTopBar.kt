/** 相册列表页与远端相册页共用的搜索顶栏：统一卡片输入框样式，左右按钮通过插槽注入。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giant8east.photomanager.R
import com.giant8east.photomanager.ui.theme.appColors

/**
 * 占位文字与输入框必须共用同一份带显式行高的样式：Text 会继承 bodyLarge 的 24sp 行高，
 * 而 BasicTextField 不给 lineHeight 时只有字体默认行高，两者行高不同会让空输入时光标偏上。
 */
private val SearchTextStyle = TextStyle(fontSize = 18.sp, lineHeight = 24.sp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchTopBar(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: @Composable RowScope.() -> Unit = {},
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(enabled) {
        if (!enabled) focusManager.clearFocus()
    }

    CenterAlignedTopAppBar(
        modifier = modifier,
        title = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    shape = RoundedCornerShape(2.dp),
                    color = appColors.cardBackground,
                    tonalElevation = 0.dp,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        leadingContent()
                        BasicTextField(
                            value = searchInput,
                            onValueChange = onSearchInputChange,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            enabled = enabled,
                            singleLine = true,
                            textStyle = SearchTextStyle.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchInput.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.album_search_hint),
                                                style = SearchTextStyle,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }
                                    if (enabled && searchInput.isNotEmpty()) {
                                        IconButton(onClick = { onSearchInputChange("") }) {
                                            Icon(
                                                Icons.Default.Clear,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        trailingContent()
                    }
                }
            }
        },
        navigationIcon = {},
        actions = {},
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = appColors.toolbarBackground,
            titleContentColor = appColors.toolbarContent,
            navigationIconContentColor = appColors.toolbarContent
        )
    )
}
