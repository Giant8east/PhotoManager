/** 相册主页顶部栏：分组抽屉入口、搜索和排序。 */
package com.example.photomanager.pages.album

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
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
import com.example.photomanager.R
import com.example.photomanager.pages.common.SheetStack
import com.example.photomanager.ui.theme.appColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumTopBar(
    searchInput: String,
    onSearchInputChange: (String) -> Unit,
    searchEnabled: Boolean,
    sheetStack: SheetStack,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(searchEnabled) {
        if (!searchEnabled) focusManager.clearFocus()
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
                        AlbumSortButton(
                            sheetStack = sheetStack,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        BasicTextField(
                            value = searchInput,
                            onValueChange = onSearchInputChange,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 4.dp),
                            enabled = searchEnabled,
                            singleLine = true,
                            textStyle = TextStyle(
                                fontSize = 18.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.weight(1f)) {
                                        if (searchInput.isEmpty()) {
                                            Text(
                                                text = stringResource(R.string.album_search_hint),
                                                fontSize = 18.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        innerTextField()
                                    }

                                    if (searchEnabled && searchInput.isNotEmpty()) {
                                        IconButton(
                                            onClick = { onSearchInputChange("") }
                                        ) {
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

                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {},
        actions = {},
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = appColors.toolbarBackground,
            titleContentColor = appColors.toolbarContent
        )
    )
}
