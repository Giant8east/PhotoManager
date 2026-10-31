package com.example.photomanager.ui.state

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.photomanager.R

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Resource -> stringResource(id)
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
            is PageState.Message -> Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(state.message.resolve(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (state.canRetry) TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.page_retry))
                }
            }
        }
    }
}
