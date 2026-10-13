package com.example.photomanager.pages.album

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
import com.example.photomanager.pages.common.DialogHost
import com.example.photomanager.pages.common.rememberDialogStack

/** 展开状态由页面控制，确认弹窗通过弹窗栈打开和关闭。 */
@Composable
fun AlbumActionFab(
    isAllSelected: Boolean,
    hasSelection: Boolean,
    groups: List<GroupEntity>,
    onSelectAll: () -> Unit,
    onDelete: () -> Unit,
    onMoveToGroup: (Long?) -> Unit,
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val dialogs = rememberDialogStack()
    val showDelete: () -> Unit = { dialogs.show {
        AlertDialog(
            onDismissRequest = { dialogs.pop() },
            title = { Text("删除相册") },
            text = { Text("将删除选中相册的真实目录及其文件，是否继续？") },
            confirmButton = {
                TextButton(onClick = { onDelete(); dialogs.pop(); onExpandedChange(false) }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { dialogs.pop() }) { Text("取消") } }
        )
    } }
    val showGroups: () -> Unit = { dialogs.show {
        AlertDialog(
            onDismissRequest = { dialogs.pop() },
            title = { Text("移动到分组") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    item {
                        TextButton(onClick = { onMoveToGroup(null); dialogs.pop(); onExpandedChange(false) }) { Text("移出分组") }
                    }
                    items(groups.size) { index ->
                        val group = groups[index]
                        TextButton(onClick = { onMoveToGroup(group.id); dialogs.pop(); onExpandedChange(false) }) { Text(group.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { dialogs.pop() }) { Text("取消") } }
        )
    } }
    val rotation by animateFloatAsState(if (isExpanded) 45f else 0f, label = "fabRotation")
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
                FilledIconButton(onClick = showDelete, enabled = hasSelection) {
                    Icon(Icons.Default.Delete, contentDescription = "删除选中相册")
                }
                FilledIconButton(onClick = showGroups, enabled = hasSelection) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "移动到分组")
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        FloatingActionButton(onClick = { onExpandedChange(!isExpanded) }) {
            Icon(Icons.Default.Add, contentDescription = if (isExpanded) "收起操作" else "展开操作", modifier = Modifier.rotate(rotation))
        }
    }
    DialogHost(dialogs)
}
