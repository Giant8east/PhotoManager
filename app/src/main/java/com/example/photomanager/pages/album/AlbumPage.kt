/** 相册库主页内容：顶部搜索、相册列表和批量操作 FAB；分组抽屉由 AlbumRoot 承载。 */
package com.example.photomanager.pages.album

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.R
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.common.AppLazyColumnScrollbar
import com.example.photomanager.pages.common.DrawerHost
import com.example.photomanager.pages.common.SheetHost
import com.example.photomanager.pages.common.TopBarShadow
import com.example.photomanager.pages.common.rememberSheetStack
import com.example.photomanager.pages.groupmanager.GroupManagePage
import com.example.photomanager.pages.setting.SettingPage
import com.example.photomanager.ui.state.PageContent
import com.example.photomanager.ui.state.PageState

@Composable
internal fun AlbumPage(
    navigator: AppNavigator,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    val selection = remember { AlbumSelectionState() }

    LaunchedEffect(viewModel.state) {
        when (val state = viewModel.state) {
            is PageState.Content -> selection.retain(
                state.value.albums.map { it.id }.toSet()
            )
            is PageState.Loading -> Unit
            is PageState.Message -> Unit
        }
    }

    LaunchedEffect(viewModel.state, viewModel.sortCompletionToken) {
        if (viewModel.suppressPlacementAnimation) {
            when (viewModel.state) {
                is PageState.Content -> {
                    // 先让排序后的列表完成一帧布局，再恢复后续删除/移动的补位动画。
                    withFrameNanos { }
                    viewModel.clearSortPlacementSuppression()
                }
                is PageState.Message -> viewModel.clearSortPlacementSuppression()
                PageState.Loading -> Unit
            }
        }
    }

    val sheets = rememberSheetStack()

    DrawerHost(
        gesturesEnabled = !selection.isSelectionMode,
        drawerContent = { closeDrawer ->
            AlbumGroupDrawer(
                groups = viewModel.groupItems,
                ungroupedAlbumCount = viewModel.ungroupedAlbumCount,
                selectedGroupId = viewModel.selectedGroupId,
                onGroupSelected = viewModel::selectGroup,
                onManageGroups = {
                    navigator.pushTo { GroupManagePage(navigator) }
                },
                onCloseDrawer = closeDrawer
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AlbumTopBar(
                    searchInput = viewModel.searchInput,
                    onSearchInputChange = viewModel::onSearchInputChange,
                    searchEnabled = !selection.isSelectionMode,
                    sheetStack = sheets,
                    onOpenSettings = { navigator.pushTo { SettingPage(navigator) } }
                )
                Box(Modifier.weight(1f)) {
                    PageContent(
                        state = viewModel.state,
                        onRetry = viewModel::retry,
                        modifier = Modifier.fillMaxSize()
                    ) { content ->
                        Box(Modifier.fillMaxSize()) {
                            AlbumList(
                                albums = content.albums,
                                selection = selection,
                                navigator = navigator,
                                suppressPlacementAnimation = viewModel.suppressPlacementAnimation
                            )
                            if (content.albums.isEmpty()) {
                                Text(
                                    text = stringResource(
                                        if (viewModel.searchInput.isNotBlank() ||
                                            viewModel.selectedGroupId != null
                                        ) {
                                            R.string.page_no_matching_albums
                                        } else {
                                            R.string.page_no_albums
                                        }
                                    ),
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .padding(24.dp),
                                    color = MaterialTheme.colorScheme.onBackground,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    TopBarShadow(Modifier.align(Alignment.TopCenter))
                }
            }

            AlbumActionFab(selection = selection)
        }

        SheetHost(sheets)
    }
}

@Composable
private fun AlbumList(
    albums: List<AlbumEntity>,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    suppressPlacementAnimation: Boolean
) {
    val listState = rememberLazyListState()
    AppLazyColumnScrollbar(state = listState) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumListCard(
                    album = album,
                    selection = selection,
                    navigator = navigator,
                    modifier = if (suppressPlacementAnimation) {
                        Modifier.animateItem(
                            fadeInSpec = null,
                            placementSpec = null,
                            fadeOutSpec = null
                        )
                    } else {
                        Modifier.animateItem()
                    }
                )
            }
        }
    }
}
