package com.example.photomanager.pages.album

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.AlbumSortField
import com.example.photomanager.data.GroupData
import com.example.photomanager.data.database.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.net.Uri
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val albumData: AlbumData,
    private val groupData: GroupData
) : ViewModel() {
    private var _albums by mutableStateOf<List<AlbumEntity>>(emptyList())
    private var _groups by mutableStateOf<List<GroupEntity>>(emptyList())
    private var _searchKeyword by mutableStateOf("")
    private var _selectedGroupId by mutableStateOf<Long?>(null)
    private var _selectedAlbumIds by mutableStateOf<Set<Long>>(emptySet())
    private var _sortField by mutableStateOf(AlbumSortField.CUSTOM)
    private var _descending by mutableStateOf(false)
    private var _isLoading by mutableStateOf(true)
    private var _errorMessage by mutableStateOf<String?>(null)

    val albums get() = _albums
    val groups get() = _groups
    val searchKeyword get() = _searchKeyword
    val selectedGroupId get() = _selectedGroupId
    val selectedAlbumIds get() = _selectedAlbumIds
    val sortField get() = _sortField
    val isDescending get() = _descending
    val isLoading get() = _isLoading
    val errorMessage get() = _errorMessage
    val pagedAlbums: Flow<PagingData<AlbumEntity>>
        get() = albumData.pageAlbums(selectedGroupId, searchKeyword, sortField, isDescending)
    private var albumQueryJob: Job? = null

    init {
        observeAlbums()
        viewModelScope.launch {
            groupData.groups.collect { updatedGroups ->
                _groups = updatedGroups
                if (selectedGroupId != null && updatedGroups.none { it.id == selectedGroupId }) {
                    selectGroup(null)
                }
            }
        }
    }

    private fun observeAlbums() {
        albumQueryJob?.cancel()
        albumQueryJob = viewModelScope.launch {
            try {
                albumData.observeAlbums(selectedGroupId, searchKeyword, sortField, isDescending)
                    .collect {
                        _albums = it
                        _isLoading = false
                        retainSelection()
                    }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _errorMessage = error.message ?: "相册加载失败"
                _isLoading = false
            }
        }
    }

    val groupName get() = groups.firstOrNull { it.id == selectedGroupId }?.name ?: "全部相册"
    val isAllSelected get() = albums.isNotEmpty() && albums.all { it.id in selectedAlbumIds }

    fun displayTime(album: AlbumEntity) = albumData.displayTime(album.modifiedAt)
    fun updateSearchKeyword(keyword: String) {
        _searchKeyword = keyword
        observeAlbums()
    }

    fun selectGroup(id: Long?) {
        _selectedGroupId = id
        _selectedAlbumIds = emptySet(); observeAlbums()
    }

    fun sort(field: AlbumSortField, reverse: Boolean) {
        _sortField = field
        _descending = reverse; observeAlbums()
    }

    fun toggleAlbumSelection(id: Long) {
        _selectedAlbumIds =
            if (id in selectedAlbumIds) selectedAlbumIds - id else selectedAlbumIds + id
    }

    fun selectAllVisible() {
        _selectedAlbumIds = if (isAllSelected) emptySet() else albums.map { it.id }.toSet()
    }

    fun clearSelection() {
        _selectedAlbumIds = emptySet()
    }

    private fun retainSelection() {
        _selectedAlbumIds = selectedAlbumIds intersect albums.map { it.id }.toSet()
    }

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            _isLoading = true
            _errorMessage = null
            try {
                action()
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                _errorMessage = error.message ?: "操作失败"
            } finally {
                _isLoading = false
            }
        }
    }

    fun importDirectory(uri: Uri) = perform { albumData.importDirectory(uri) }
    fun createGroup(name: String) = perform { groupData.createGroup(name) }
    fun renameGroup(group: GroupEntity, name: String) =
        perform { groupData.renameGroup(group, name) }

    fun deleteGroup(groupId: Long) = perform { groupData.deleteGroup(groupId) }
    fun moveSelectedToGroup(id: Long?) = perform {
        albumData.move(
            albums.filter { it.id in selectedAlbumIds },
            id
        ); clearSelection()
    }

    fun deleteSelected() =
        perform { albumData.delete(albums.filter { it.id in selectedAlbumIds }); clearSelection() }
}
