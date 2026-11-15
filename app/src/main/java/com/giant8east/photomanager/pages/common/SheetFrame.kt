/** 可复用底部弹窗外壳：统一圆角、标题栏、关闭按钮和点击空白处收起。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 底部弹窗的通用外观外壳。
 *
 * 是否显示由 [SheetHost] 控制；点击遮罩、返回键或下滑都会关闭当前弹窗。
 * [title] 可选，不传时显示顶部拖拽横线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetFrame(
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val sheets = LocalSheetStack.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = sheets::pop,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = if (title == null) {
            { BottomSheetDefaults.DragHandle() }
        } else {
            null
        }
    ) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
            if (title != null) {
                SheetHeader(title)
            }

            content()
        }
    }
}

@Composable
private fun SheetHeader(title: String) {
    Box(Modifier.fillMaxWidth().height(64.dp)) {
        Text(
            text = title,
            modifier = Modifier.align(Alignment.Center),
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
