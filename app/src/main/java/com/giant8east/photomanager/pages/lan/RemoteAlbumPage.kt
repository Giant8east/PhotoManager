/** 远端相册页：外观与本地相册页一致（侧边分组抽屉 + 同一张相册卡片），只是数据来自对方设备。 */
package com.giant8east.photomanager.pages.lan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.album.AlbumGroupDrawer
import com.giant8east.photomanager.pages.album.AlbumListCard
import com.giant8east.photomanager.pages.album.AlbumSelectionState
import com.giant8east.photomanager.pages.album.AlbumSortSheet
import com.giant8east.photomanager.pages.common.AppLazyColumnScrollbar
import com.giant8east.photomanager.pages.common.DrawerHost
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
    BackHandler { navigator.pop() }
    LaunchedEffect(device) { viewModel.load(device) }

    // 只在页面可见时维护推送连接：切到后台还每秒收心跳纯属白耗电。
    // 观察者挂上时先对齐一次当前状态，因为 addObserver 之前 ON_START 可能已经发生过。
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

    val listState = rememberLazyListState()
    val selectedGroupId = viewModel.selectedGroupId
    // 远端不进入多选，也不接受拖拽与收藏；卡片本身与本地完全共用。
    val selection = remember { AlbumSelectionState() }
    val albums = viewModel.albums.collectAsLazyPagingItems()
    val sheets = rememberSheetStack()

    DrawerHost(
        gesturesEnabled = true,
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
                // 对方不共享收藏视图；也没有"管理分组"，所以不传 headerActions。
                showFavorites = false
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            SearchTopBar(
                searchInput = viewModel.searchInput,
                onSearchInputChange = viewModel::onSearchInputChange,
                leadingContent = {
                    IconButton(onClick = navigator::pop) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                trailingContent = {
                    // 排序：字段与方向一起发给服务端，本机不保存偏好；弹窗本身复用本地那一套
                    IconButton(
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
            // 顶栏被搜索/分组占满，这里留一行设备名，免得忘了在看谁的相册；
            // 连接不正常时同一行补一句状态，不额外占高度
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
                // null = 连接正常，不打扰用户
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
            // 分组只是侧边抽屉的辅助信息：它失败不该挡住相册列表，所以单独一行提示 + 重试
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
                    listState = listState
                )
                TopBarShadow(Modifier.align(Alignment.TopCenter))
            }
        }
        SheetHost(sheets)
    }
}

@Composable
private fun RemoteAlbumList(
    albums: LazyPagingItems<LanAlbumDto>,
    device: LanDevice,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    listState: LazyListState
) {
    when {
        // 首屏加载：还没有任何条目
        albums.loadState.refresh is LoadState.Loading && albums.itemCount == 0 -> Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }
        // 首屏失败：显示一行原因 + 重试，不用空列表伪装成功
        albums.loadState.refresh is LoadState.Error && albums.itemCount == 0 -> {
            val error = (albums.loadState.refresh as LoadState.Error).error
            PageMessage(message = error.toLanUiText().resolve(), onRetry = albums::retry)
        }
        // 正常空结果：不提供重试
        albums.itemCount == 0 -> PageMessage(message = stringResource(R.string.page_no_albums), onRetry = null)
        else -> AppLazyColumnScrollbar(state = listState) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // key 用相册 id；占位行还没有数据，退化成索引
                items(count = albums.itemCount, key = { index -> albums.peek(index)?.id ?: index }) { index ->
                    val album = albums[index]
                    if (album != null) {
                        RemoteAlbumRow(
                            album = album,
                            device = device,
                            selection = selection,
                            navigator = navigator
                        )
                    }
                }
                // 追加下一页：用列表尾部的一行表达加载/失败，翻到底部就能看到
                item(key = "footer") {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        when (albums.loadState.append) {
                            is LoadState.Loading -> CircularProgressIndicator(Modifier.size(24.dp))
                            is LoadState.Error -> TextButton(onClick = albums::retry) {
                                Text(stringResource(R.string.page_retry))
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** 页面中部的"一句话 + 可选重试"统一复用 ui/state/PageMessage。 */

@Composable
private fun RemoteAlbumRow(
    album: LanAlbumDto,
    device: LanDevice,
    selection: AlbumSelectionState,
    navigator: AppNavigator
) {
    // 映射成本地实体后交给同一张卡片：封面地址是对方的 /media/{id}，token 由拦截器注入。
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
        selectionEnabled = false
    )
}