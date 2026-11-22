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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.giant8east.photomanager.pages.common.DialogHost
import com.giant8east.photomanager.pages.common.AppPageScaffold
import com.giant8east.photomanager.pages.common.rememberDialogStack
import com.giant8east.photomanager.pages.common.rememberStableReorderableLazyListState
import com.giant8east.photomanager.ui.theme.appColors
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GroupManagePage(
    navigator: AppNavigator,
    viewModel: GroupManageViewModel = hiltViewModel()
) {
    BackHandler { navigator.pop() }

    var draggingOriginalIndex by remember { mutableIntStateOf(-1) }
    val lazyListState = rememberLazyListState()
    val dialogs = rememberDialogStack()

    val haptic = LocalHapticFeedback.current
    val localGroups = remember {
        mutableStateListOf<GroupEntity>().apply { addAll(viewModel.groups) }
    }
    val reorderableState = rememberStableReorderableLazyListState(lazyListState) { from, to ->
        val target = to.coerceIn(0, localGroups.lastIndex)
        if (from in localGroups.indices && from != target) {
            localGroups.add(target, localGroups.removeAt(from))
            true
        } else {
            false
        }
    }

    LaunchedEffect(viewModel.groups) {
        if (draggingOriginalIndex == -1) {
            localGroups.clear()
            localGroups.addAll(viewModel.groups)
        }
    }

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
            if (localGroups.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.album_group_empty))
                }
            } else {
                AppLazyColumnScrollbar(state = lazyListState) {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        state = lazyListState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                itemsIndexed(localGroups, key = { _, group -> group.id }) { _, group ->
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
                                IconButton(
                                    onClick = {
                                        dialogs.show { GroupDeleteDialog(group) }
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                }
                                Text(
                                    group.name,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            dialogs.show { GroupRenameDialog(group) }
                                        }
                                        .padding(horizontal = 16.dp),
                                    maxLines = 1
                                )
                                Box(
                                    Modifier
                                        .size(48.dp)
                                        .draggableHandle(
                                            onDragStarted = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                draggingOriginalIndex = localGroups.indexOfFirst {
                                                    it.id == group.id
                                                }
                                            },
                                            onDragStopped = {
                                                val finalIndex =
                                                    localGroups.indexOfFirst { it.id == group.id }
                                                if (draggingOriginalIndex in viewModel.groups.indices &&
                                                    finalIndex != -1 &&
                                                    finalIndex != draggingOriginalIndex
                                                ) {
                                                    viewModel.moveGroup(
                                                        draggingOriginalIndex,
                                                        finalIndex
                                                    )
                                                }
                                                draggingOriginalIndex = -1
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
    }

    DialogHost(dialogs)
}
