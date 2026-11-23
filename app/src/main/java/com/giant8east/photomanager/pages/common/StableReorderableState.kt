/** 拖拽重排状态封装：复用 reorderable 2.4+ 内置视口稳定性并统一换位触感反馈。 */
package com.giant8east.photomanager.pages.common

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalView
import sh.calvin.reorderable.ReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 在 [rememberReorderableLazyListState] 外包一层：
 * - [onMove] 返回 true 表示确实发生了位置交换；
 * - 发生真实换位时触发轻微触感反馈，不再手动调用 requestScrollToItem 干扰库内拖拽偏移计算。
 */
@Composable
fun rememberStableReorderableLazyListState(
    lazyListState: LazyListState,
    onMove: (fromIndex: Int, toIndex: Int) -> Boolean
): ReorderableLazyListState {
    val view = LocalView.current
    return rememberReorderableLazyListState(lazyListState) { from, to ->
        if (onMove(from.index, to.index)) {
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
    }
}
