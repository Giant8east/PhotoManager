/** 统一的应用内对话框外壳：圆角、背景、标题和内容间距只在这里维护。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.giant8east.photomanager.ui.theme.appColors

@Composable
fun AppDialog(
    title: String,
    buttons: (@Composable RowScope.(close: () -> Unit) -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp),
    /** `Dp.Unspecified` 表示不限制内容高度，由 Dialog 窗口约束。 */
    contentMaxHeight: Dp = 360.dp,
    /** false 时点外部与返回键都不会关闭，用于"正在等待结果"的弹窗。 */
    dismissible: Boolean = true,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit
) {
    val dialogs = LocalDialogStack.current
    val close: () -> Unit = { dialogs.pop() }

    Dialog(
        onDismissRequest = { if (dismissible) close() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            shape = MaterialTheme.shapes.extraSmall,
            color = appColors.dialogBackground,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    title,
                    modifier = Modifier.padding(
                        start = 24.dp,
                        top = 24.dp,
                        end = 24.dp,
                        bottom = 8.dp
                    ),
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp,
                        lineHeight = 28.sp,
                        letterSpacing = 0.sp
                    )
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = contentMaxHeight)
                        .padding(contentPadding)
                ) {
                    content(this, close)
                }
                buttons?.let { actions ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        actions(this, close)
                    }
                }
            }
        }
    }
}
