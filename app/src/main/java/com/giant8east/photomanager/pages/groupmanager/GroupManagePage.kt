/** 分组管理页：新增、重命名、删除并拖动调整分组顺序。 */
package com.giant8east.photomanager.pages.groupmanager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.database.GroupEntity
import com.giant8east.photomanager.navigation.AppNavigator
import com.giant8east.photomanager.pages.common.AppLazyColumnScrollbar
import com.giant8east.photomanager.pages.common.AppPageScaffold
import com.giant8east.photomanager.pages.common.DialogHost
import com.giant8east.photomanager.pages.common.rememberDialogStack
import com.giant8east.photomanager.pages.common.rememberStableReorderableLazyListState
import com.giant8east.photomanager.ui.state.PageContent
import com.giant8east.photomanager.ui.theme.appColors
import sh.calvin.reorderable.ReorderableItem

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupManagePage(
    navigator: AppNavigator,
    viewModel: GroupManageViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }
    val dialogs = rememberDialogStack()

    AppPageScaffold(
        title = stringResource(R.string.album_group_manage),
        onBack = navigator::pop,
        actions = {
            IconButton(
                onClick = {
                    dialogs.show { GroupCreateDialog() }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    ) {
        PageContent(
            state = viewModel.state,
            onRetry = viewModel::retry,
            modifier = Modifier.fillMaxSize()
        ) { groups ->
            if (groups.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.album_group_empty))
                }
            } else {
                GroupManageList(
                    groups = groups,
                    onRename = { group -> dialogs.show { GroupRenameDialog(group) } },
                    onDelete = { group -> dialogs.show { GroupDeleteDialog(group) } },
                    onMove = viewModel::moveGroup
                )
            }
        }
    }

    DialogHost(dialogs)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupManageList(
    groups: List<GroupEntity>,
    onRename: (GroupEntity) -> Unit,
    onDelete: (GroupEntity) -> Unit,
    onMove: (fromIndex: Int, toIndex: Int, onFailed: () -> Unit) -> Unit
) {
    val lazyListState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    // 仅在拖拽进行中与等待落库回流的短暂窗口内使用临时顺序，其余时间直接渲染数据库数据源
    var draggingOriginalIndex by remember { mutableIntStateOf(-1) }
    var dragOrder by remember { mutableStateOf<List<GroupEntity>?>(null) }
    val displayedGroups = dragOrder ?: groups

    // 数据库回流或拖拽结束（包括取消）后立即丢弃临时顺序，恢复以数据库为唯一数据源
    LaunchedEffect(groups, draggingOriginalIndex) {
        if (draggingOriginalIndex == -1) {
            dragOrder = null
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            draggingOriginalIndex = -1
            dragOrder = null
        }
    }

    val reorderableState = rememberStableReorderableLazyListState(lazyListState) { from, to ->
        val current = (dragOrder ?: groups).toMutableList()
        val target = to.coerceIn(0, current.lastIndex)
        if (from in current.indices && from != target) {
            current.add(target, current.removeAt(from))
            dragOrder = current
            true
        } else {
            false
        }
    }

    AppLazyColumnScrollbar(state = lazyListState) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            state = lazyListState,
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(displayedGroups, key = { _, group -> group.id }) { _, group ->
                ReorderableItem(
                    state = reorderableState,
                    key = group.id,
                    animateItemModifier = Modifier.animateItem()
                ) { isDragging ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .shadow(
                                elevation = if (isDragging) 8.dp else 2.dp,
                                shape = RoundedCornerShape(2.dp),
                                clip = false
                            )
                            .background(
                                color = appColors.cardBackground,
                                shape = RoundedCornerShape(2.dp)
                            )
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { onDelete(group) }) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                            Text(
                                group.name,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onRename(group) }
                                    .padding(horizontal = 16.dp),
                                maxLines = 1
                            )
                            Box(
                                Modifier
                                    .size(48.dp)
                                    .draggableHandle(
                                        onDragStarted = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            val startList = dragOrder ?: groups
                                            draggingOriginalIndex = startList.indexOfFirst { it.id == group.id }
                                            dragOrder = startList
                                        },
                                        onDragStopped = {
                                            val fromIndex = draggingOriginalIndex
                                            val currentList = dragOrder ?: groups
                                            val toIndex = currentList.indexOfFirst { it.id == group.id }
                                            draggingOriginalIndex = -1
                                            if (fromIndex in groups.indices &&
                                                toIndex != -1 &&
                                                toIndex != fromIndex
                                            ) {
                                                onMove(fromIndex, toIndex) { dragOrder = null }
                                            } else {
                                                dragOrder = null
                                            }
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Menu, contentDescription = null)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
