/** 分组管理页：新增、重命名、删除并拖动调整分组顺序。 */
package com.example.photomanager.pages.groupmanager

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.photomanager.R
import com.example.photomanager.data.database.GroupEntity
import com.example.photomanager.navigation.AppNavigator
import com.example.photomanager.pages.common.DialogHost
import com.example.photomanager.pages.common.TopBarShadow
import com.example.photomanager.pages.common.rememberDialogStack
import com.example.photomanager.ui.theme.appColors
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

    val localGroups = remember {
        mutableStateListOf<GroupEntity>().apply { addAll(viewModel.groups) }
    }
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        localGroups.add(to.index, localGroups.removeAt(from.index))
    }

    LaunchedEffect(viewModel.groups) {
        if (draggingOriginalIndex == -1) {
            localGroups.clear()
            localGroups.addAll(viewModel.groups)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.album_group_manage)) },
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
                    IconButton(
                        onClick = {
                            dialogs.show { GroupCreateDialog() }
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (localGroups.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.album_group_empty))
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                itemsIndexed(localGroups, key = { _, group -> group.id }) { _, group ->
                    ReorderableItem(reorderableState, key = group.id) { isDragging ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f)
                                .shadow(
                                    elevation = if (isDragging) 8.dp else 2.dp,
                                    clip = false
                                )
                                .background(
                                    color = appColors.cardBackground,
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
            TopBarShadow(Modifier.align(Alignment.TopCenter))
        }
    }

    DialogHost(dialogs)
}
