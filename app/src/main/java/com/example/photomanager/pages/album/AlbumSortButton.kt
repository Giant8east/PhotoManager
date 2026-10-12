package com.example.photomanager.pages.album

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.photomanager.R
import com.example.photomanager.data.AlbumSortField

/** 自维护排序菜单显示状态，选择结果交给页面持久化或查询。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumSortButton(
    currentField: AlbumSortField,
    isDescending: Boolean,
    onSortSelected: (AlbumSortField, Boolean) -> Unit
) {
    var isSheetVisible by remember { mutableStateOf(false) }
    IconButton(onClick = { isSheetVisible = true }) {
        Icon(painterResource(R.drawable.ic_sort), contentDescription = "相册排序")
    }
    if (isSheetVisible) {
        ModalBottomSheet(onDismissRequest = { isSheetVisible = false }) {
            Text("相册排序", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            AlbumSortField.entries.forEach { field ->
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        onSortSelected(field, if (field == currentField) !isDescending else false)
                        isSheetVisible = false
                    }
                ) {
                    Text(field.title, modifier = Modifier.weight(1f))
                    if (field == currentField) {
                        Text(if (isDescending) "降序" else "升序")
                        Icon(Icons.Default.Check, contentDescription = "当前排序")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}