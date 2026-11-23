/** 远端相册页：外观与本地相册页一致（侧边分组抽屉 + 同一张相册卡片 + 多选下载 FAB）。 */
package com.giant8east.photomanager.pages.lan

import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.GroupEntity
import com.giant8east.photomanager.data.database.GroupWithAlbumCount
import com.giant8east.photomanager.data.lan.RemoteAlbumKey
import com.giant8east.photomanager.data.lan.RemoteDownloadStatus
import com.giant8east.photomanager.data.lan.RemoteDownloadTask
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.navigation.LocalIsNavTransitioning
import com.giant8east.photomanager.pages.album.AlbumGroupDrawer
import com.giant8east.photomanager.pages.album.AlbumListCard
import com.giant8east.photomanager.pages.album.AlbumSelectionState
import com.giant8east.photomanager.pages.album.AlbumSortSheet
import com.giant8east.photomanager.pages.common.AppLazyColumnScrollbar
import com.giant8east.photomanager.pages.common.DrawerHost
import com.giant8east.photomanager.pages.common.ExpandableActionFab
import com.giant8east.photomanager.pages.common.ExpandableFabAction
import com.giant8east.photomanager.pages.common.SearchTopBar
import com.giant8east.photomanager.pages.common.SheetHost
import com.giant8east.photomanager.pages.common.TopBarShadow
import com.giant8east.photomanager.pages.common.rememberSheetStack
import com.giant8east.photomanager.ui.state.PageMessage
import com.giant8east.photomanager.ui.state.resolve

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAlbumPage(
    navigator: AppNavigator,
    device: LanDevice,
    viewModel: RemoteAlbumViewModel = hiltViewModel()
) {
    val listState = rememberLazyListState()
    val selectedGroupId = viewModel.selectedGroupId
    val selection = remember { AlbumSelectionState() }
    val albums = viewModel.albums.collectAsLazyPagingItems()
    val downloadTasks by viewModel.downloadTasks.collectAsState()
    val sheets = rememberSheetStack()

    // 多选态按返回键先退出多选，普通态再出栈
    BackHandler {
        if (selection.isSelectionMode) selection.clear() else navigator.pop()
    }
    LaunchedEffect(device) { viewModel.load(device) }

    // 只在页面可见时维护推送连接：切到后台还每秒收心跳纯属白耗电。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, device) {
        fun syncWatching() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                viewModel.startWatching(device)
            } else {
                viewModel.stopWatching()
            }
        }

        val observer = LifecycleEventObserver { _, _ -> syncWatching() }
        lifecycle.addObserver(observer)
        syncWatching()
        onDispose {
            lifecycle.removeObserver(observer)
            viewModel.stopWatching()
        }
    }

    val actionsEnabled = !selection.isSelectionMode

    DrawerHost(
        gesturesEnabled = actionsEnabled,
        drawerContent = { closeDrawer ->
            AlbumGroupDrawer(
                groupsWithCounts = viewModel.groups.map {
                    GroupWithAlbumCount(GroupEntity(id = it.id, name = it.name), it.albumCount)
                },
                ungroupedAlbumCount = viewModel.ungroupedCount,
                favoriteCount = 0,
                selectedGroupId = selectedGroupId,
                favoritesSelected = false,
                onGroupSelected = viewModel::selectGroup,
                onFavoritesSelected = {},
                onCloseDrawer = closeDrawer,
                showFavorites = false
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
                    .copy(alpha = if (actionsEnabled) 1f else 0.38f)
                SearchTopBar(
                    searchInput = viewModel.searchInput,
                    onSearchInputChange = viewModel::onSearchInputChange,
                    enabled = actionsEnabled,
                    leadingContent = {
                        IconButton(enabled = actionsEnabled, onClick = navigator::pop) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = iconTint
                            )
                        }
                    },
                    trailingContent = {
                        // 排序：字段与方向一起发给服务端，本机不保存偏好；弹窗本身复用本地那一套
                        IconButton(
                            enabled = actionsEnabled,
                            onClick = {
                                sheets.show {
                                    AlbumSortSheet(
                                        currentField = viewModel.sortField,
                                        isDescending = viewModel.isDescending,
                                        onFieldSelected = viewModel::selectSortField,
                                        onDirectionChanged = viewModel::setSortDescending
                                    )
                                }
                            }
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_sort),
                                contentDescription = null,
                                tint = iconTint
                            )
                        }
                    }
                )
                // 顶栏被搜索/排序占满，这里留一行设备名，连接不正常时同一行补一句状态
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val linkStatus = when (viewModel.linkState) {
                        RemoteLinkState.CONNECTED -> null
                        RemoteLinkState.CONNECTING -> R.string.lan_link_connecting
                        RemoteLinkState.OFFLINE -> R.string.lan_link_offline
                    }
                    if (linkStatus != null) {
                        Text(
                            text = stringResource(linkStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (viewModel.linkState == RemoteLinkState.OFFLINE) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
                viewModel.groupsMessage?.let { message ->
                    TextButton(
                        onClick = viewModel::refreshGroups,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Text(message.resolve())
                    }
                }
                Box(Modifier.weight(1f)) {
                    RemoteAlbumList(
                        albums = albums,
                        device = device,
                        selection = selection,
                        navigator = navigator,
                        listState = listState,
                        downloadTasks = downloadTasks,
                        downloadedAlbumIds = viewModel.downloadedAlbumIds,
                        onToggleDownload = viewModel::toggleAlbumDownload,
                        onRefresh = {
                            albums.refresh()
                            viewModel.refreshGroups()
                        }
                    )
                    TopBarShadow(Modifier.align(Alignment.TopCenter))
                }
            }

            // 远端批量下载 FAB：仅保留全选（当前已加载）与下载两个按钮
            ExpandableActionFab(
                isExpanded = selection.isSelectionMode,
                onMainClick = {
                    if (selection.isSelectionMode) selection.clear() else selection.enter()
                },
                actions = listOf(
                    ExpandableFabAction(
                        iconRes = R.drawable.ic_eh_check_all,
                        onClick = {
                            val loadedIds = albums.itemSnapshotList.items.map { it.id }.toSet()
                            selection.selectAll(loadedIds)
                        }
                    ),
                    ExpandableFabAction(
                        imageVector = Icons.Filled.Download,
                        onClick = {
                            val selected = albums.itemSnapshotList.items
                                .filter { it.id in selection.selectedIds }
                            if (selected.isNotEmpty()) {
                                viewModel.downloadAlbums(selected)
                                selection.clear()
                            }
                        }
                    )
                )
            )
        }
        SheetHost(sheets)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteAlbumList(
    albums: LazyPagingItems<LanAlbumDto>,
    device: LanDevice,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    listState: LazyListState,
    downloadTasks: Map<RemoteAlbumKey, RemoteDownloadTask>,
    downloadedAlbumIds: Set<Long>,
    onToggleDownload: (LanAlbumDto) -> Unit,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val refreshState = albums.loadState.refresh
    val appendState = albums.loadState.append
    val isTransitioning = LocalIsNavTransitioning.current
    // 仅在首次入场转场期间延后挂载远端相册卡片，避免滑动动画与首屏封面加载争抢主线程；
    // 从详情页返回时 rememberSaveable 保持为 true，避免滚动位置跳回顶部。
    var listReady by rememberSaveable { mutableStateOf(!isTransitioning) }
    LaunchedEffect(isTransitioning) {
        if (!isTransitioning) listReady = true
    }

    // 已有列表时下拉刷新失败、或底部加载下一页失败：保留现有列表并用 Toast 提示（对齐 EhViewer）
    LaunchedEffect(refreshState) {
        if (refreshState is LoadState.Error && albums.itemCount > 0) {
            Toast.makeText(
                context,
                refreshState.error.toLanUiText().resolve(context),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    LaunchedEffect(appendState) {
        if (appendState is LoadState.Error) {
            Toast.makeText(
                context,
                appendState.error.toLanUiText().resolve(context),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    when {
        refreshState is LoadState.Loading && albums.itemCount == 0 -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
        refreshState is LoadState.Error && albums.itemCount == 0 -> {
            PageMessage(message = refreshState.error.toLanUiText().resolve(), onRetry = albums::retry)
        }
        albums.itemCount == 0 -> PageMessage(message = stringResource(R.string.page_no_albums), onRetry = null)
        else -> PullToRefreshBox(
            isRefreshing = refreshState is LoadState.Loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            AppLazyColumnScrollbar(state = listState) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (listReady) {
                        items(count = albums.itemCount, key = { index -> albums.peek(index)?.id ?: index }) { index ->
                            val album = albums[index]
                            if (album != null) {
                                val task = downloadTasks[RemoteAlbumKey.of(device, album.id)]
                                RemoteAlbumRow(
                                    album = album,
                                    device = device,
                                    selection = selection,
                                    navigator = navigator,
                                    task = task,
                                    isDownloaded = album.id in downloadedAlbumIds,
                                    onToggleDownload = { onToggleDownload(album) }
                                )
                            }
                        }
                    }
                }
            }
            // 对齐 EhViewer：底部加载下一页时贴容器底边叠一条 3dp 横向动画条，不占列表项空间
            if (appendState is LoadState.Loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                )
            }
        }
    }
}

@Composable
private fun RemoteAlbumRow(
    album: LanAlbumDto,
    device: LanDevice,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    task: RemoteDownloadTask?,
    isDownloaded: Boolean,
    onToggleDownload: () -> Unit
) {
    val context = LocalContext.current
    val remoteAlbum = remember(album, device.address) {
        AlbumEntity(
            id = album.id,
            name = album.name,
            modifiedAt = album.modifiedAt,
            photoCount = album.photoCount,
            totalSizeBytes = album.sizeBytes,
            coverPath = album.coverUrl?.let { "http://${device.address}$it" }.orEmpty(),
            albumPath = ""
        )
    }
    val status = task?.status
    val isActive = status is RemoteDownloadStatus.Queued || status is RemoteDownloadStatus.Downloading
    val downloading = status as? RemoteDownloadStatus.Downloading

    AlbumListCard(
        album = remoteAlbum,
        selection = selection,
        navigator = navigator,
        onToggleFavorite = {},
        dragHandleModifier = Modifier,
        reorderEnabled = false,
        onReorderBlocked = {},
        onCardClick = {
            navigator.pushTo {
                RemoteAlbumDetailPage(navigator = navigator, device = device, album = album)
            }
        },
        showFavorite = false,
        selectionEnabled = true,
        showDragHandle = false,
        // 非下载态的右侧按钮：排队中显示暂停，其余（含已下载）显示播放以开始或增量刷新
        trailingAction = {
            IconButton(
                onClick = onToggleDownload,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        statusBadge = {
            when (status) {
                is RemoteDownloadStatus.Queued -> Text(
                    text = stringResource(R.string.lan_download_queued),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                is RemoteDownloadStatus.Paused -> Text(
                    text = stringResource(R.string.lan_download_paused_short, status.finished, status.total),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                is RemoteDownloadStatus.Failed -> Text(
                    text = stringResource(R.string.lan_download_failed),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1
                )
                is RemoteDownloadStatus.Finished -> Text(
                    text = stringResource(R.string.lan_download_completed_badge),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                else -> if (isDownloaded) {
                    Text(
                        text = stringResource(R.string.lan_download_completed_badge),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        },
        // 对齐 EhViewer item_download.xml：下载中隐藏原先的统计信息，原位替换为 进度/速度 + 内部进度条 + 右下暂停
        downloadingContent = downloading?.let { dl ->
            {
                val speedText = "${Formatter.formatShortFileSize(context, dl.bytesPerSecond)}/s"
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (dl.total > 0) "${dl.finished}/${dl.total}" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Text(
                            text = speedText,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (dl.total > 0) {
                        LinearProgressIndicator(
                            progress = { (dl.finished.toFloat() / dl.total).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .height(4.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .height(4.dp)
                        )
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                        IconButton(
                            onClick = onToggleDownload,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Pause,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    )
}
