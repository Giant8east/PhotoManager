package com.example.photomanager.ui.state

import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/** 页面主数据状态；操作中的进度及失败不替换已有页面内容。 */
sealed interface PageState<out T> {
    data object Loading : PageState<Nothing>
    data class Content<T>(val value: T) : PageState<T>
    data class Message(val message: UiText, val canRetry: Boolean = false) : PageState<Nothing>
}

/** 资源由界面解析，ViewModel 不持有 Context 或翻译后的兜底文案。 */
sealed interface UiText {
    data class Resource(@StringRes val id: Int) : UiText
    data class Literal(val value: String) : UiText
}

fun Throwable.toUiText(@StringRes fallback: Int): UiText =
    message?.takeIf { it.isNotBlank() }?.let(UiText::Literal) ?: UiText.Resource(fallback)

/** 每次订阅先等待数据；取消只向上传递，普通读取失败允许重新订阅。 */
fun <T, C> Flow<T>.asPageStates(
    @StringRes fallback: Int,
    content: (T) -> PageState<C>
): Flow<PageState<C>> = map(content)
    .onStart { emit(PageState.Loading) }
    .catch { error ->
        if (error is CancellationException) throw error
        emit(PageState.Message(error.toUiText(fallback), canRetry = true))
    }
