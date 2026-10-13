package com.example.photomanager.pages.album

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.database.GroupEntity
import kotlinx.coroutines.launch

/** 自维护抽屉状态，业务分组由页面传入。 */
@Composable
fun AlbumGroupDrawer(
    groups: List<GroupEntity>,
    selectedGroupId: Long?,
    onGroupSelected: (Long?) -> Unit,
//    gesturesEnabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val selectGroup: (Long?) -> Unit = { groupId ->
        onGroupSelected(groupId)
        scope.launch { drawerState.close() }
    }
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                LazyColumn(Modifier.padding(16.dp)) {
                    item { Text("分组标签", style = MaterialTheme.typography.headlineSmall) }
                    item {
                        NavigationDrawerItem(
                            label = { Text("全部相册") },
                            selected = selectedGroupId == null,
                            onClick = { selectGroup(null) }
                        )
                    }
                    items(groups, key = { it.id }) { group ->
                        NavigationDrawerItem(
                            label = { Text(group.name) },
                            selected = selectedGroupId == group.id,
                            onClick = { selectGroup(group.id) }
                        )
                    }
                }
            }
        },
//        gesturesEnabled = gesturesEnabled,
        content = content
    )
}
