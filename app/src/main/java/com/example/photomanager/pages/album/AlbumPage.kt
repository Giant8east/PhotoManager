/** 相册库主页内容：顶部搜索、相册列表和批量操作 FAB；分组抽屉由 AlbumRoot 承载。 */
package com.example.photomanager.pages.album

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.R
import com.example.photomanager.data.AlbumSortField
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
                groups = viewModel.groupItems,
                ungroupedAlbumCount = viewModel.ungroupedAlbumCount,
                favoriteCount = viewModel.favoriteCount,
                selectedGroupId = viewModel.selectedGroupId,
                favoritesSelected = viewModel.favoritesOnly,
                onGroupSelected = viewModel::selectGroup,
                onFavoritesSelected = viewModel::selectFavorites,
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
                    actionsEnabled = !selection.isSelectionMode,
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
                                listState = albumListState,
                                sortField = viewModel.sortField,
                                isDescending = viewModel.isDescending,
                                // 搜索结果是子集，重排会打散无关相册，因此只允许在无关键词时拖拽。
                                reorderEnabled = viewModel.searchInput.isBlank(),
                                onToggleFavorite = viewModel::toggleFavorite,
                                onReorderBlocked = viewModel::notifyReorderBlocked,
                                onReorderFinished = viewModel::reorderAlbums
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumList(
    albums: List<AlbumEntity>,
    selection: AlbumSelectionState,
    navigator: AppNavigator,
    listState: LazyListState,
    sortField: AlbumSortField,
    isDescending: Boolean,
    reorderEnabled: Boolean,
    onToggleFavorite: (AlbumEntity) -> Unit,
    onReorderBlocked: () -> Unit,
    onReorderFinished: (List<AlbumEntity>) -> Unit
) {
    // 拖拽期间改用本地副本；数据库回流不能在手势中途替换列表顺序。
    val draggedAlbums = remember { mutableStateListOf<AlbumEntity>() }
    var isDragging by remember { mutableStateOf(false) }
    // 松手后继续显示拖拽结果，直到写入后的新列表回流，避免顺序回弹一帧。
    var pendingOrder by remember { mutableStateOf<List<AlbumEntity>?>(null) }
    LaunchedEffect(albums) { pendingOrder = null }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        if (draggedAlbums.isNotEmpty()) {
            // 列表第 0 项是不可拖拽的锚点占位，真实相册下标各减 1。
            val source = from.index - 1
            val target = (to.index - 1).coerceIn(0, draggedAlbums.lastIndex)
            if (source in draggedAlbums.indices && source != target) {
                draggedAlbums.add(target, draggedAlbums.removeAt(source))
            }
        }
    }
    val displayedAlbums = when {
        isDragging -> draggedAlbums
        pendingOrder != null -> pendingOrder.orEmpty()
        else -> albums
    }
    AppLazyColumnScrollbar(state = listState) {
        // 排序条件变化时整棵列表重建，等价于 EhViewer 换适配器：一次性换成新顺序，
        // 不做逐项差分与位移动画，避免可见项被复用后新旧状态混杂。
        key(sortField, isDescending) {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                // 固定 key 的锚点：LazyList 按"第一个可见项的 key"保持视口，
                // 有了这一项，交换前两个相册时锚点不会移动，视口就不会整体错位。
                item(key = "reorder-anchor") {
                    Spacer(Modifier.height(1.dp))
                }
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
                                onDragStarted = {
                                    draggedAlbums.clear()
                                    draggedAlbums.addAll(albums)
                                    isDragging = true
                                },
                                onDragStopped = {
                                    isDragging = false
                                    pendingOrder = draggedAlbums.toList()
                                    onReorderFinished(draggedAlbums.toList())
                                }
                            ),
                            reorderEnabled = reorderEnabled,
                            onReorderBlocked = onReorderBlocked
                        )
                    }
                }
            }
        }
    }
}
