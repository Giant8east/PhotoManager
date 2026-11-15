/** 通用异步确认弹窗：提交期间保持模态并阻止返回/外部关闭，成功与失败后的导航由调用方决定。 */
package com.giant8east.photomanager.pages.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.giant8east.photomanager.ui.theme.appColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AsyncConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    loadingMessage: String,
    onConfirm: suspend () -> Unit,
    onSuccess: () -> Unit,
    onError: (Exception) -> Unit
) {
    var isSubmitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dialogs = LocalDialogStack.current

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) dialogs.pop()
        },
        shape = RoundedCornerShape(4.dp),
        containerColor = appColors.dialogBackground,
        tonalElevation = 0.dp,
        properties = DialogProperties(
            dismissOnBackPress = !isSubmitting,
            dismissOnClickOutside = !isSubmitting
        ),
        title = { Text(title) },
        text = {
            if (isSubmitting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(loadingMessage)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message)

                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSubmitting,
                onClick = {
                    if (isSubmitting) return@TextButton
                    isSubmitting = true
                    scope.launch {
                        try {
                            onConfirm()
                            dialogs.pop()
                            onSuccess()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            isSubmitting = false
                            onError(error)
                        }
                    }
                }
            ) {
                Text(if (isSubmitting) loadingMessage else confirmLabel)
            }
        },
    )
}
