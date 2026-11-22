/** 通用的可展开悬浮操作按钮：主圆形旋转 + 按钮与纵向弹出的子操作按钮栈。 */
package com.giant8east.photomanager.pages.common

import androidx.annotation.DrawableRes
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.giant8east.photomanager.ui.theme.appColors

data class ExpandableFabAction(
    @DrawableRes val iconRes: Int? = null,
    val imageVector: ImageVector? = null,
    val containerColor: Color? = null,
    val contentColor: Color? = null,
    val onClick: () -> Unit
)

@Composable
fun ExpandableActionFab(
    isExpanded: Boolean,
    onMainClick: () -> Unit,
    actions: List<ExpandableFabAction>,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isExpanded) 135f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "expandableFabRotation"
    )
    val progress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = tween(
            durationMillis = 300,
            easing = if (isExpanded) LinearOutSlowInEasing else FastOutLinearInEasing
        ),
        label = "expandableFabProgress"
    )
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(end = FAB_END_PADDING, bottom = FAB_BOTTOM_PADDING),
        contentAlignment = Alignment.BottomEnd
    ) {
        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (progress > 0f) {
                actions.forEachIndexed { index, action ->
                    val distance = actions.lastIndex - index
                    val itemModifier = Modifier.graphicsLayer {
                        translationY = (1f - progress) * with(density) {
                            (FAB_LIFT_BASE + FAB_SPACING * distance.toFloat()).toPx()
                        }
                        alpha = progress
                    }
                    SmallFloatingActionButton(
                        onClick = { if (isExpanded) action.onClick() },
                        modifier = itemModifier,
                        shape = CircleShape,
                        containerColor = action.containerColor ?: appColors.fabSecondaryBackground,
                        contentColor = action.contentColor ?: appColors.fabSecondaryContent
                    ) {
                        when {
                            action.iconRes != null -> Icon(
                                painter = painterResource(action.iconRes),
                                contentDescription = null
                            )
                            action.imageVector != null -> Icon(
                                imageVector = action.imageVector,
                                contentDescription = null
                            )
                        }
                    }
                    if (index < actions.lastIndex) {
                        Spacer(Modifier.height(16.dp))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            FloatingActionButton(
                onClick = onMainClick,
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
}

private val FAB_END_PADDING = 3.dp
private val FAB_BOTTOM_PADDING = 70.dp
private val FAB_LIFT_BASE = 72.dp
private val FAB_SPACING = 56.dp
