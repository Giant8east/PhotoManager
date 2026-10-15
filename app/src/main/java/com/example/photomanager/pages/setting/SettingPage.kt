/** 设置页面，负责选择扫描目录、发起同步并展示本地相册统计。 */
package com.example.photomanager.pages.setting

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.res.painterResource
import com.example.photomanager.R
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.navigation.AppNavigator
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingPage(navigator: AppNavigator, viewModel: SettingViewModel = hiltViewModel()) {
    BackHandler { navigator.pop() }
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
                title = { Text("设置") },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                navigationIcon = {
                    IconButton(onClick = navigator::pop) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { launcher.launch(null) }) {
                        Icon(
                            Icons.Default.Add,
                            "添加目录"
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
                    Icon(painterResource(R.drawable.ic_stop), contentDescription = "停止同步")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "同步全部目录")
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 16.dp)
                ) {
                Text("已同步相册：${viewModel.validAlbumCount}", style = MaterialTheme.typography.titleMedium)
                if (viewModel.isSyncing) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text("同步中…", style = MaterialTheme.typography.titleMedium)
                }
            }
            viewModel.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(viewModel.directories, key = { it.uri }) { directory ->
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(directory.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    directory.uri,
                                    modifier = Modifier.basicMarquee(),
                                    maxLines = 1,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.removeDirectory(directory) }) {
                                Icon(Icons.Default.Delete, "移除目录")
                            }
                        }
                    }
                }
            }
        }
    }
}
