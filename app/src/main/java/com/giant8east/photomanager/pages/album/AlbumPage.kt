/** 相册库主页内容：右侧分组抽屉、顶部搜索、相册列表和批量操作 FAB。 */
package com.giant8east.photomanager.pages.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement.Absolute.spacedBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.common.AppLazyColumnScrollbar
import com.giant8east.photomanager.pages.common.DrawerHost
import com.giant8east.photomanager.pages.common.SheetHost
import com.giant8east.photomanager.pages.common.TopBarShadow
import com.giant8east.photomanager.pages.common.rememberSheetStack
import com.giant8east.photomanager.pages.common.rememberStableReorderableLazyListState
import com.giant8east.photomanager.pages.groupmanager.GroupManagePage
import com.giant8east.photomanager.pages.setting.SettingPage
import com.giant8east.photomanager.ui.state.PageContent
import com.giant8east.photomanager.ui.state.PageState
import com.giant8east.photomanager.ui.theme.appColors
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun AlbumPage(
    navigator: AppNavigator,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    val selection = remember { AlbumSelectionState() }
    // 提升到 DrawerHost 外层，确保 LazyListState 处于页面级 SaveableStateProvider 作用域内。
    val albumListState = rememberLazyListState()
    // 拖拽顺序由页面持有：列表、FAB 的保存按钮、退出多选的确认弹窗共用同一份快照。
    val dragState = remember { AlbumDragState() }

    // 排序切换后回到顶部：旧滚动索引在新顺序下没有意义。
    // 初始值取当前排序，首次组合与返回页面时都不滚动，避免破坏滚动位置恢复。
    val sortKey = viewModel.sortField to viewModel.isDescending
    val previousSortKey = remember { mutableStateOf(sortKey) }
    LaunchedEffect(sortKey) {
        if (previousSortKey.value != sortKey) {
            previousSortKey.value = sortKey
            albumListState.requestScrollToItem(0)
        }
    }

    LaunchedEffect(viewModel.state) {
        when (val state = viewModel.state) {
            is PageState.Content -> selection.retain(
                state.value.albums.map { it.id }.toSet()
            )
            is PageState.Loading -> Unit
            is PageState.Message -> Unit
        }
    }

    val sheets = rememberSheetStack()

    DrawerHost(
        gesturesEnabled = !selection.isSelectionMode,
        drawerContent = { closeDrawer ->
            AlbumGroupDrawer(
                groupsWithCounts = viewModel.groupsWithCounts,
                ungroupedAlbumCount = viewModel.ungroupedAlbumCount,
                favoriteCount = viewModel.favoriteCount,
                selectedGroupId = viewModel.selectedGroupId,
                favoritesSelected = viewModel.favoritesOnly,
                onGroupSelected = viewModel::selectGroup,
                onFavoritesSelected = viewModel::selectFavorites,
                onCloseDrawer = closeDrawer,
                headerActions = {
                    IconButton(
                        onClick = {
                            navigator.pushTo { GroupManagePage(navigator) }
                            closeDrawer()
                        }
                    ) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.album_group_manage),
                            tint = appColors.toolbarContent
                        )
                    }
                }
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
                    actionsEnabled = !selection.isSelectionMode,
                    onSortClick = {
                        sheets.show {
                            AlbumSortSheet(
                                currentField = viewModel.sortField,
                                isDescending = viewModel.isDescending,
                                onFieldSelected = viewModel::selectSortField,
                                onDirectionChanged = viewModel::setSortDescending
                            )
                        }
                    },
                    onOpenSettings = { navigator.pushTo { SettingPage(navigator) } }
                )
                Box(Modifier.weight(1f)) {
                    PageContent(
                        state = viewModel.state,
                        onRetry = viewModel::retry,
                        modifier = Modifier.fillMaxSize()
                    ) { content ->
                        Box(Modifier.fillMaxSize()) {
                            // 排序条件变化时整棵列表重建，等价于 EhViewer 换适配器：
                            // 一次性换成新顺序，不做逐项差分与逐项位移动画。
                            key(viewModel.sortField, viewModel.isDescending) {
                                AlbumList(
                                    albums = content.albums,
                                    selection = selection,
                                    navigator = navigator,
                                    listState = albumListState,
                                    // 搜索结果是子集，重排会打散无关相册，因此只允许在无关键词时拖拽。
                                    reorderEnabled = viewModel.searchInput.isBlank(),
                                    dragState = dragState,
                                    onToggleFavorite = viewModel::toggleFavorite,
                                    onReorderBlocked = viewModel::notifyReorderBlocked
                                )
                            }
                            if (content.albums.isEmpty()) {
                                Text(
                                    text = stringResource(
                                        when {
                                            viewModel.searchInput.isNotBlank() ->
                                                R.string.page_no_matching_albums
                                            viewModel.favoritesOnly -> R.string.album_favorite_empty
                                            viewModel.selectedGroupId != null ->
                                                R.string.page_no_matching_albums
                                            else -> R.string.page_no_albums
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

            AlbumActionFab(selection = selection, dragState = dragState)
        }

        SheetHost(sheets)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumList(
    albums: List<AlbumEntity>,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    listState: LazyListState,
    reorderEnabled: Boolean,
    dragState: AlbumDragState,
    onToggleFavorite: (AlbumEntity) -> Unit,
    onReorderBlocked: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(albums) { dragState.onAlbumsChanged(albums) }
    val displayedAlbums = dragState.displayed(albums)
    val reorderableState = rememberStableReorderableLazyListState(listState) { from, to ->
        dragState.move(from, to)
    }
    AppLazyColumnScrollbar(state = listState) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = spacedBy(8.dp),
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(displayedAlbums, key = { it.id }) { album ->
                ReorderableItem(
                    state = reorderableState,
                    key = album.id,
                    // 位移/淡入淡出统一由这里控制，卡片上不再叠加第二层 animateItem。
                    animateItemModifier = Modifier.animateItem()
                ) { _ ->
                    AlbumListCard(
                        album = album,
                        selection = selection,
                        navigator = navigator,
                        onToggleFavorite = onToggleFavorite,
                        dragHandleModifier = Modifier.draggableHandle(
                            // 从上一次拖拽的结果开始，而不是数据库里可能还没回流的旧顺序。
                            onDragStarted = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                dragState.startDrag()
                            },
                            // 松手只留下待保存快照，写入数据库由 FAB 或退出多选时的弹窗触发。
                            onDragStopped = { dragState.stopDrag() }
                        ),
                        reorderEnabled = reorderEnabled,
                        onReorderBlocked = onReorderBlocked
                    )
                }
            }
        }
    }
}
