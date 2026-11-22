/** 相册多选操作 FAB，交互对齐 EhViewer 下载页的批量操作入口。 */
package com.giant8east.photomanager.pages.album

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.pages.common.DialogHost
import com.giant8east.photomanager.pages.common.rememberDialogStack
import com.giant8east.photomanager.ui.theme.Teal
import com.giant8east.photomanager.ui.theme.appColors

@Composable
internal fun AlbumActionFab(
    selection: AlbumSelectionState,
    dragState: AlbumDragState,
    viewModel: AlbumViewModel = hiltViewModel()
) {
    val albumIds = viewModel.albums.map { it.id }.toSet()
    val hasSelection = selection.selectedIds.isNotEmpty()
    val isExpanded = selection.isSelectionMode

    val dialogs = rememberDialogStack()
    // 退出多选前先确认未保存的拖拽顺序，避免用户以为已经生效。
    val requestExit: () -> Unit = {
        if (dragState.hasPendingOrder) {
            dialogs.show {
                AlbumSaveOrderDialog(dragState = dragState, selection = selection)
            }
        } else {
            selection.clear()
        }
    }

    BackHandler(enabled = isExpanded) { requestExit() }

    val showDelete: () -> Unit = {
        dialogs.show {
            AlbumDeleteDialog(selection = selection)
        }
    }
    val showGroups: () -> Unit = {
        dialogs.show {
            AlbumMoveDialog(selection = selection)
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
            .padding(end = FAB_END_PADDING, bottom = FAB_BOTTOM_PADDING),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            fun miniModifier(distance: Int): Modifier =
                Modifier.graphicsLayer {
                    translationY = (1f - progress) * with(density) {
                        (FAB_LIFT_BASE + FAB_SPACING * distance.toFloat()).toPx()
                    }
                    alpha = progress
                }

            if (progress > 0f) {
                val selectedAlbums = viewModel.albums.filter { it.id in selection.selectedIds }
                // 方案 A：选中项只要有未隐藏就全部隐藏；仅当全部都已隐藏时切为取消隐藏
                val allSelectedHidden = selectedAlbums.isNotEmpty() && selectedAlbums.all { it.isHidden }
                MiniActionFab(
                    modifier = miniModifier(distance = 4),
                    iconRes = R.drawable.ic_eh_check_all,
                    onClick = {
                        if (isExpanded) selection.selectAll(albumIds)
                    }
                )
                Spacer(Modifier.height(16.dp))
                MiniActionFab(
                    modifier = miniModifier(distance = 3),
                    iconRes = R.drawable.ic_eh_delete,
                    onClick = {
                        if (isExpanded && hasSelection) showDelete()
                    }
                )
                Spacer(Modifier.height(16.dp))
                MiniActionFab(
                    modifier = miniModifier(distance = 2),
                    iconRes = R.drawable.ic_eh_folder_move,
                    onClick = {
                        if (isExpanded && hasSelection) showGroups()
                    }
                )
                Spacer(Modifier.height(16.dp))
                MiniIconActionFab(
                    modifier = miniModifier(distance = 1),
                    imageVector = if (allSelectedHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    containerColor = appColors.fabSecondaryBackground,
                    contentColor = appColors.fabSecondaryContent,
                    onClick = {
                        if (isExpanded && hasSelection) {
                            viewModel.setHidden(selection.selectedIds, hidden = !allSelectedHidden)
                        }
                    }
                )
                Spacer(Modifier.height(16.dp))
                // 保存拖拽排序：没有待保存结果时用中性表面色+半透明图标（禁用观感），
                // 有结果时换成主题的 Teal 主色；两套配色在明暗主题下都有明显对比。
                val hasPendingOrder = dragState.hasPendingOrder
                MiniIconActionFab(
                    modifier = miniModifier(distance = 0),
                    imageVector = Icons.Filled.Save,
                    containerColor = if (hasPendingOrder) {
                        Teal
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (hasPendingOrder) {
                        Color.White
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    },
                    onClick = {
                        if (hasPendingOrder) {
                            dragState.pendingOrder()?.let(viewModel::reorderAlbums)
                        }
                    }
                )
                Spacer(Modifier.height(24.dp))
            }
            FloatingActionButton(
                onClick = {
                    if (selection.isSelectionMode) requestExit()
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

/** 用矢量图标的小按钮，支持按状态切换底色（保存排序入口会用到）。 */
@Composable
private fun MiniIconActionFab(
    modifier: Modifier,
    imageVector: ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        containerColor = containerColor,
        contentColor = contentColor
    ) {
        Icon(imageVector, contentDescription = stringResource(R.string.album_save_order_action))
    }
}

private val FAB_END_PADDING = 3.dp
private val FAB_BOTTOM_PADDING = 70.dp
private val FAB_LIFT_BASE = 72.dp
private val FAB_SPACING = 56.dp
