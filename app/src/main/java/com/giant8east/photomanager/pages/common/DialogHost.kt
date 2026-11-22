/** 页面级弹窗栈，负责 show、pop 和栈顶弹窗渲染（与 SheetHost.kt 互为镜像，改一处需同步另一处）。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/** 当前页面弹窗栈；弹窗内容必须由 DialogStack 渲染。 */
val LocalDialogStack = staticCompositionLocalOf<DialogStack> {
    error("Dialog content must be rendered by DialogStack")
}

/** 页面级弹窗栈，只呈现栈顶；页面离开组合后一起释放。 */
class DialogStack {
    private val dialogs = mutableStateListOf<@Composable () -> Unit>()

    fun show(content: @Composable () -> Unit) {
        dialogs += content
    }

    fun pop() {
        if (dialogs.isNotEmpty()) dialogs.removeAt(dialogs.lastIndex)
    }

    @Composable
    fun render() {
        dialogs.lastOrNull()?.let { dialog ->
            CompositionLocalProvider(LocalDialogStack provides this) {
                key(dialog) { dialog() }
            }
        }
    }
}

@Composable
fun rememberDialogStack(): DialogStack = remember { DialogStack() }

@Composable
fun DialogHost(dialogs: DialogStack) {
    dialogs.render()
}
