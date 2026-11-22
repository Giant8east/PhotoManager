/** 稳定视口的拖拽重排状态封装：屏蔽 Compose LazyList 交换可见首项时的视口跳动，并统一换位触感反馈。 */
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
 * - 若交换涉及当前可见首项（`firstVisibleItemIndex`），同帧调用 `requestScrollToItem`
 *   锁住原视口位置，避免 Compose 追随旧 key 导致整屏抖动（无需再塞假占位项或做 index - 1 平移）；
 * - 发生真实换位时触发轻微触感反馈。
 */
@Composable
fun rememberStableReorderableLazyListState(
    lazyListState: LazyListState,
    onMove: (fromIndex: Int, toIndex: Int) -> Boolean
): ReorderableLazyListState {
    val view = LocalView.current
    return rememberReorderableLazyListState(lazyListState) { from, to ->
        val firstIndex = lazyListState.firstVisibleItemIndex
        val firstOffset = lazyListState.firstVisibleItemScrollOffset
        if (onMove(from.index, to.index)) {
            if (from.index == firstIndex || to.index == firstIndex) {
                lazyListState.requestScrollToItem(firstIndex, firstOffset)
            }
            view.performHapticFeedback(HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
    }
}
