/** 相册多选操作 FAB，交互对齐 EhViewer 下载页的批量操作入口。 */
package com.example.photomanager.pages.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.photomanager.R
import com.example.photomanager.pages.common.AppDialog
import com.example.photomanager.pages.common.DialogHost
import com.example.photomanager.pages.common.rememberDialogStack
import com.example.photomanager.ui.theme.appColors

@Composable
internal fun AlbumActionFab(
    viewModel: AlbumViewModel,
    selection: AlbumSelectionState
) {
    val albumIds = viewModel.albums.map { it.id }.toSet()
    val hasSelection = selection.selectedIds.isNotEmpty()
    val isExpanded = selection.isSelectionMode

    BackHandler(enabled = isExpanded) {
        selection.clear()
    }

    val dialogs = rememberDialogStack()
    val showDelete: () -> Unit = {
        dialogs.show {
            AppDialog(
                title = stringResource(R.string.album_delete_title),
                onDismiss = { dialogs.pop() },
                buttons = {
                    TextButton(
                        onClick = {
                            viewModel.delete(selection.selectedIds)
                            selection.clear()
                            dialogs.pop()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = appColors.actionText
                        )
                    ) { Text(stringResource(R.string.album_action_delete)) }
                }
            ) {
                Text(
                    stringResource(
                        R.string.album_delete_selected_message,
                        selection.selectedIds.size
                    ),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        letterSpacing = 0.sp
                    )
                )
            }
        }
    }
    val showGroups: () -> Unit = {
        dialogs.show {
            AppDialog(
                title = stringResource(R.string.album_action_move),
                onDismiss = { dialogs.pop() }
            ) {
                LazyColumn(Modifier.fillMaxWidth()) {
                    item {
                        MoveRow(
                            label = stringResource(R.string.album_group_default_short),
                            onClick = {
                                viewModel.move(selection.selectedIds, null)
                                selection.clear()
                                dialogs.pop()
                            }
                        )
                    }
                    items(viewModel.groups, key = { it.id }) { group ->
                        MoveRow(
                            label = group.name,
                            onClick = {
                                viewModel.move(selection.selectedIds, group.id)
                                selection.clear()
                                dialogs.pop()
                            }
                        )
                    }
                }
            }
        }
    }

    // 按钮的旋转动画
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 135f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        ),
        label = "albumFabRotation"
    )
    // fab的拉伸动画
    val progress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = if (isExpanded) LinearOutSlowInEasing else FastOutLinearInEasing
        ),
        label = "albumFabProgress"
    )
    val density = LocalDensity.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 3.dp, bottom = 70.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            fun miniModifier(distance: Int): Modifier =
                Modifier.graphicsLayer {
                    translationY = (1f - progress) * with(density) {
                        (72.dp + 56.dp * distance.toFloat()).toPx()
                    }
                    alpha = progress
                }

            if (progress > 0f) {
                MiniActionFab(
                    modifier = miniModifier(distance = 2),
                    iconRes = R.drawable.ic_eh_check_all,
                    onClick = {
                        if (isExpanded) selection.selectAll(albumIds)
                    }
                )
                Spacer(Modifier.height(16.dp))
                MiniActionFab(
                    modifier = miniModifier(distance = 1),
                    iconRes = R.drawable.ic_eh_delete,
                    onClick = {
                        if (isExpanded && hasSelection) showDelete()
                    }
                )
                Spacer(Modifier.height(16.dp))
                MiniActionFab(
                    modifier = miniModifier(distance = 0),
                    iconRes = R.drawable.ic_eh_folder_move,
                    onClick = {
                        if (isExpanded && hasSelection) showGroups()
                    }
                )
                Spacer(Modifier.height(24.dp))
            }
            FloatingActionButton(
                onClick = {
                    if (selection.isSelectionMode) selection.clear()
                    else selection.enter()
                },
                shape = CircleShape,
                containerColor = appColors.fabPrimaryBackground,
                contentColor = appColors.fabPrimaryContent
            ) {
                Canvas(
                    Modifier
                        .size(24.dp)
                        .rotate(rotation)
                ) {
                    val half = 10.dp.toPx()
                    val stroke = 2.dp.toPx()
                    val center = this.center
                    drawLine(
                        color = Color.White,
                        start = Offset(center.x, center.y - half),
                        end = Offset(center.x, center.y + half),
                        strokeWidth = stroke,
                        cap = StrokeCap.Butt
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(center.x - half, center.y),
                        end = Offset(center.x + half, center.y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Butt
                    )
                }
            }
        }
    }
    DialogHost(dialogs)
}

@Composable
private fun MoveRow(
    label: String,
    onClick: () -> Unit
) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        style = MaterialTheme.typography.bodyLarge.copy(
            fontSize = 16.sp,
            lineHeight = 24.sp,
            letterSpacing = 0.sp
        )
    )
}

@Composable
private fun MiniActionFab(
    modifier: Modifier,
    iconRes: Int,
    onClick: () -> Unit
) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = appColors.fabSecondaryBackground,
        contentColor = appColors.fabSecondaryContent
    ) {
        Icon(painterResource(iconRes), contentDescription = null)
    }
}
