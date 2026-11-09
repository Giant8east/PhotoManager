/** 设置页面，负责选择扫描目录、发起同步并展示本地相册统计。 */
package com.example.photomanager.pages.setting

import com.example.photomanager.ui.theme.appColors
import com.example.photomanager.ui.state.resolve
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.R
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.common.AppLazyColumnScrollbar
import com.example.photomanager.pages.common.TopBarShadow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingPage(navigator: AppNavigator, viewModel: SettingViewModel = hiltViewModel()) {
    BackHandler { navigator.pop() }
    val listState = rememberLazyListState()
    // 创建一个“可以启动系统功能的对象”
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            // 如果用户没有选择目录，就直接结束回调（标签返回）
            uri ?: return@rememberLauncherForActivityResult
            viewModel.onDirectoryPicked(uri)
        }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.setting_title)) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = appColors.toolbarBackground,
                    titleContentColor = appColors.toolbarContent,
                    navigationIconContentColor = appColors.toolbarContent,
                    actionIconContentColor = appColors.toolbarContent
                ),
                navigationIcon = {
                    IconButton(onClick = navigator::pop) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { launcher.launch(null) }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (viewModel.isSyncing) viewModel.stopSync() else viewModel.syncAllDirectories()
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                if (viewModel.isSyncing) {
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = null)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                }
            }
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.setting_synced_album_count,
                            viewModel.validAlbumCount
                        ),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (viewModel.isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            stringResource(R.string.setting_syncing),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
                viewModel.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                viewModel.loadError?.let { error ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            error.resolve(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = viewModel::reload) {
                            Text(stringResource(R.string.page_retry))
                        }
                    }
                }
                when {
                    viewModel.isLoading && viewModel.directories.isEmpty() -> Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }

                    viewModel.directories.isEmpty() -> Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.setting_no_directories))
                    }

                    else -> AppLazyColumnScrollbar(
                        state = listState,
                        modifier = Modifier.weight(1f)
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(viewModel.directories, key = { it.path }) { directory ->
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                directory.name,
                                                style = MaterialTheme.typography.titleMedium
                                            )
                                            Text(
                                                directory.path,
                                                modifier = Modifier.basicMarquee(),
                                                maxLines = 1,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = { viewModel.removeDirectory(directory) }) {
                                            Icon(Icons.Default.Delete, contentDescription = null)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            TopBarShadow(Modifier.align(Alignment.TopCenter))
        }
    }
}
