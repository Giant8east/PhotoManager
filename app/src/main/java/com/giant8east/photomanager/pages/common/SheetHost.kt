/** 页面级底部弹窗栈，负责 show、pop 和栈顶弹窗渲染（与 DialogHost.kt 互为镜像，改一处需同步另一处）。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** 当前页面底部弹窗栈；弹窗内容必须由 SheetStack 渲染。 */
val LocalSheetStack = staticCompositionLocalOf<SheetStack> {
    error("Sheet content must be rendered by SheetStack")
}

/** 页面作用域的弹窗栈，只呈现栈顶；页面离开组合后一起释放。 */
class SheetStack {
    private val sheets = mutableStateListOf<@Composable () -> Unit>()

    /** 入栈并显示一个底部弹窗。 */
    fun show(content: @Composable () -> Unit) {
        sheets += content
    }

    /** 关闭栈顶弹窗；没有弹窗时忽略。 */
    fun pop() {
        if (sheets.isNotEmpty()) sheets.removeAt(sheets.lastIndex)
    }

    @Composable
    internal fun render() {
        val sheet = sheets.lastOrNull() ?: return
        CompositionLocalProvider(LocalSheetStack provides this) {
            key(sheet) { sheet() }
        }
    }
}

@Composable
fun rememberSheetStack(): SheetStack = remember { SheetStack() }

@Composable
fun SheetHost(sheets: SheetStack) {
    sheets.render()
}
