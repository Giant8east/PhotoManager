/** 相册排序底部弹窗的状态与宿主，页面通过 show 打开。 */
package com.giant8east.photomanager.pages.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.album.AlbumSortField
import com.giant8east.photomanager.pages.common.SheetFrame

@Composable
internal fun AlbumSortSheet(
    viewModel: AlbumViewModel = hiltViewModel()
) {
    SheetFrame {
        AlbumSortContent(
            currentField = viewModel.sortField,
            isDescending = viewModel.isDescending,
            onFieldSelected = viewModel::selectSortField,
            onDirectionChanged = viewModel::setSortDescending
        )
    }
}

@Composable
private fun AlbumSortContent(
    currentField: AlbumSortField,
    isDescending: Boolean,
    onFieldSelected: (AlbumSortField) -> Unit,
    onDirectionChanged: (Boolean) -> Unit
) {
    AlbumSortField.entries.forEachIndexed { index, field ->
        SortRow(
            field = field,
            selected = field == currentField,
            descending = isDescending,
            onSelect = {
                onFieldSelected(field)
            },
            onDirectionSelected = { descending ->
                onDirectionChanged(descending)
            }
        )
        if (index != AlbumSortField.entries.lastIndex) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }

}

@Composable
private fun SortRow(
    field: AlbumSortField,
    selected: Boolean,
    descending: Boolean,
    onSelect: () -> Unit,
    onDirectionSelected: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onSelect)
            .padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.outline
            )
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = stringResource(field.titleRes),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (selected) {
                Text(
                    text = stringResource(
                        if (descending) field.descendingRes else field.ascendingRes
                    ),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (field != AlbumSortField.CUSTOM && selected) {
            DirectionChoice(
                descending = descending,
                onSelected = onDirectionSelected
            )
        }
    }
}

/** 升序/降序二选一控件：选中项用白色圆底突出，与截图一致。 */
@Composable
private fun DirectionChoice(
    descending: Boolean,
    onSelected: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f))
            .clickable { onSelected(!descending) }
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DirectionOption(
            active = !descending,
            icon = Icons.Default.KeyboardArrowUp,
            description = stringResource(R.string.album_sort_ascending)
        )
        DirectionOption(
            active = descending,
            icon = Icons.Default.KeyboardArrowDown,
            description = stringResource(R.string.album_sort_descending)
        )
    }
}

@Composable
private fun DirectionOption(
    active: Boolean,
    icon: ImageVector,
    description: String
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(
                if (active) MaterialTheme.colorScheme.surface
                else Color.Transparent
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.size(20.dp),
            tint = if (active) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
    }
}
