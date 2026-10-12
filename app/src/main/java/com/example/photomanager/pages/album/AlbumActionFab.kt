package com.example.photomanager.pages.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.example.photomanager.data.database.GroupEntity

/** 自维护展开状态、删除确认和分组选择，按钮仅显示图标。 */
@Composable
fun AlbumActionFab(
    isAllSelected: Boolean,
    hasSelection: Boolean,
    groups: List<GroupEntity>,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onMoveToGroup: (Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isDeleteVisible by remember { mutableStateOf(false) }
    var isGroupPickerVisible by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (isExpanded) 45f else 0f, label = "fabRotation")
    BackHandler(isExpanded && !isDeleteVisible && !isGroupPickerVisible) { isExpanded = false }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(isExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledIconButton(onClick = onSelectAll) {
                    Icon(
                        if (isAllSelected) Icons.Default.CheckCircle else Icons.Default.Check,
                        contentDescription = if (isAllSelected) "取消全选" else "全选",
                        tint = if (isAllSelected) MaterialTheme.colorScheme.tertiary else LocalContentColor.current
                    )
                }
                FilledIconButton(onClick = { isDeleteVisible = true }, enabled = hasSelection) {
                    Icon(Icons.Default.Delete, contentDescription = "删除选中相册")
                }
                FilledIconButton(onClick = { isGroupPickerVisible = true }, enabled = hasSelection) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "移动到分组")
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        FloatingActionButton(onClick = { isExpanded = !isExpanded }) {
            Icon(Icons.Default.Add, contentDescription = if (isExpanded) "收起操作" else "展开操作", modifier = Modifier.rotate(rotation))
        }
    }
    if (isDeleteVisible) {
        AlertDialog(
            onDismissRequest = { isDeleteVisible = false },
            title = { Text("删除相册") },
            text = { Text("将删除选中相册的真实目录及其文件，是否继续？") },
            confirmButton = {
                TextButton(onClick = { isDeleteVisible = false; isExpanded = false; onDelete() }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { isDeleteVisible = false }) { Text("取消") } }
        )
    }
    if (isGroupPickerVisible) {
        AlertDialog(
            onDismissRequest = { isGroupPickerVisible = false },
            title = { Text("移动到分组") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    item {
                        TextButton(onClick = { onMoveToGroup(null); isGroupPickerVisible = false; isExpanded = false }) { Text("移出分组") }
                    }
                    items(groups.size) { index ->
                        val group = groups[index]
                        TextButton(onClick = { onMoveToGroup(group.id); isGroupPickerVisible = false; isExpanded = false }) { Text(group.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { isGroupPickerVisible = false }) { Text("取消") } }
        )
    }
}