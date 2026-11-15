/** 远端相册页：外观与本地相册页一致（侧边分组抽屉 + 同一张相册卡片），只是数据来自对方设备。 */
package com.giant8east.photomanager.pages.lan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.database.GroupEntity
import com.giant8east.photomanager.data.database.GroupWithAlbumCount
import com.giant8east.photomanager.lan.LanAlbumDto
import com.giant8east.photomanager.lan.LanDevice
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.album.AlbumGroupDrawer
import com.giant8east.photomanager.pages.album.AlbumListCard
import com.giant8east.photomanager.pages.album.AlbumSelectionState
import com.giant8east.photomanager.pages.common.DrawerHost
import com.giant8east.photomanager.pages.common.TopBarShadow
import com.giant8east.photomanager.ui.state.PageContent
import com.giant8east.photomanager.ui.theme.appColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteAlbumPage(
    navigator: AppNavigator,
    device: LanDevice,
    viewModel: RemoteAlbumViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }
    LaunchedEffect(device) { viewModel.load(device) }
    val listState = rememberLazyListState()
    val selectedGroupId = viewModel.selectedGroupId
    // 远端不进入多选，也不接受拖拽与收藏；卡片本身与本地完全共用。
    val selection = remember { AlbumSelectionState() }

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
                onManageGroups = {},
                onCloseDrawer = closeDrawer,
                // 对方不共享收藏视图。
                showFavorites = false
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            CenterAlignedTopAppBar(
                title = { Text(device.name) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = appColors.toolbarBackground,
                    titleContentColor = appColors.toolbarContent,
                    navigationIconContentColor = appColors.toolbarContent
                ),
                navigationIcon = {
                    IconButton(onClick = navigator::pop) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
            Box(Modifier.weight(1f)) {
                PageContent(
                    state = viewModel.state,
                    onRetry = viewModel::retry,
                    modifier = Modifier.fillMaxSize()
                ) { content ->
                    Box(Modifier.fillMaxSize()) {
                        val albums = content.albums.filter { it.groupId == selectedGroupId }
                        if (albums.isEmpty()) {
                            Text(
                                stringResource(R.string.page_no_albums),
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(24.dp),
                                color = MaterialTheme.colorScheme.onBackground,
                                textAlign = TextAlign.Center
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(albums, key = { it.id }) { album ->
                                    RemoteAlbumRow(
                                        album = album,
                                        device = device,
                                        token = viewModel.token,
                                        selection = selection,
                                        navigator = navigator
                                    )
                                }
                            }
                        }
                    }
                }
                TopBarShadow(Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

@Composable
private fun RemoteAlbumRow(
    album: LanAlbumDto,
    device: LanDevice,
    token: String?,
    selection: AlbumSelectionState,
    navigator: AppNavigator
) {
    // 映射成本地实体后交给同一张卡片：封面地址是对方的 /media/{id}，其余字段语义不变。
    val remoteAlbum = remember(album, token, device.address) {
        AlbumEntity(
            id = album.id,
            name = album.name,
            modifiedAt = album.modifiedAt,
            photoCount = album.photoCount,
            totalSizeBytes = album.sizeBytes,
            coverPath = album.coverUrl?.let { "http://${device.address}$it?token=$token" }.orEmpty(),
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
