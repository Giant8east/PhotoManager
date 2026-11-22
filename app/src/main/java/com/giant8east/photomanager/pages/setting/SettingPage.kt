/** 设置入口：只保留"本地导入"与"远程连接"两个二级入口。 */
package com.giant8east.photomanager.pages.setting

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.giant8east.photomanager.R
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.common.AppPageScaffold

@Composable
fun SettingPage(navigator: AppNavigator) {
    BackHandler { navigator.pop() }
    AppPageScaffold(
        title = stringResource(R.string.setting_title),
        onBack = navigator::pop
    ) {
        Column(Modifier.fillMaxSize()) {
            SettingEntryRow(
                icon = painterResource(R.drawable.ic_eh_folder_move),
                label = stringResource(R.string.setting_local_import)
            ) {
                navigator.pushTo { LocalImportPage(navigator) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingEntryRow(
                icon = painterResource(R.drawable.ic_lan_share),
                label = stringResource(R.string.setting_remote_connect)
            ) {
                navigator.pushTo { RemotePage(navigator) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** 与 EhViewer 设置页一致的条目：左侧图标 + 单行标题，整行可点。 */
@Composable
private fun SettingEntryRow(icon: Painter, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(24.dp))
        Text(
            text = label,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
