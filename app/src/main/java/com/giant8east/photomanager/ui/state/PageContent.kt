package com.giant8east.photomanager.ui.state

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Context
import com.giant8east.photomanager.R

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> stringResource(id)
    is UiText.Literal -> value
}

/**
 * 非 Composable 场景（Toast、通知）用的解析。
 *
 * Composable 里请用上面那个无参版本：它跟随 Compose 的本地化环境，
 * 而这个版本读的是 Context 的配置。两者在界面切换语言时才会分叉，Toast 边上差这一次无所谓。
 */
fun UiText.resolve(context: Context): String = when (this) {
    is UiText.Resource -> context.getString(id)
    is UiText.Literal -> value
}

/** 保留宿主导航与操作区域，只切换主内容。 */
@Composable
fun <T> PageContent(
    state: PageState<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            PageState.Loading -> CircularProgressIndicator()
            is PageState.Content -> content(state.value)
            is PageState.Message -> PageMessage(
                message = state.message.resolve(),
                onRetry = if (state.canRetry) onRetry else null
            )
        }
    }
}

/** 居中展示空态/错误提示与可选重试按钮，供 PageContent 与 Paging 列表页共用。 */
@Composable
fun PageMessage(
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                message,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.page_retry))
                }
            }
        }
    }
}
