/** 页面级底部弹窗栈，负责 show、pop 和栈顶弹窗渲染。 */
package com.example.photomanager.pages.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/** 页面作用域的弹窗栈，只呈现栈顶；页面离开组合后一起释放。 */
class SheetStack {
    private data class Entry(
        val title: String?,
        val onClose: (() -> Unit)?,
        val content: @Composable () -> Unit
    )

    private val sheets = mutableStateListOf<Entry>()

    /**
     * 入栈并显示一个底部弹窗。
     *
     * [onClose] 在弹窗因遮罩、返回键或下滑关闭时调用，用于清理调用方的临时状态。
     */
    fun show(
        title: String? = null,
        onClose: (() -> Unit)? = null,
        content: @Composable () -> Unit
    ) {
        sheets += Entry(title, onClose, content)
    }

    /** 关闭栈顶弹窗；没有弹窗时忽略。 */
    fun pop() {
        if (sheets.isEmpty()) return
        sheets.removeAt(sheets.lastIndex).onClose?.invoke()
    }

    @Composable
    internal fun render() {
        val sheet = sheets.lastOrNull() ?: return
        SheetFrame(
            onDismiss = ::pop,
            title = sheet.title
        ) {
            key(sheet) { sheet.content() }
        }
    }
}

@Composable
fun rememberSheetStack(): SheetStack = remember { SheetStack() }

@Composable
fun SheetHost(sheets: SheetStack) {
    sheets.render()
}
