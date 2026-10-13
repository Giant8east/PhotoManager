package com.example.photomanager.pages.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember

/** 页面级弹窗栈，只呈现栈顶；页面离开组合后一起释放。 */
class DialogStack {
    private val dialogs = mutableStateListOf<@Composable () -> Unit>()

    fun show(content: @Composable () -> Unit) {
        dialogs += content
    }

    fun pop() {
        if (dialogs.isNotEmpty()) dialogs.removeLast()
    }

    @Composable
    fun render() {
        dialogs.lastOrNull()?.let { dialog -> key(dialog) { dialog() } }
    }
}

@Composable
fun rememberDialogStack(): DialogStack = remember { DialogStack() }

@Composable
fun DialogHost(dialogs: DialogStack) {
    dialogs.render()
}
