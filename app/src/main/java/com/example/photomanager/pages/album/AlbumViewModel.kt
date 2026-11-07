/** 相册主页状态与动作：查询、筛选、排序、分组管理和单相册操作。 */
package com.example.photomanager.pages.album

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photomanager.R
import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.AlbumListSettings
import com.example.photomanager.data.AlbumListSettingsData
import com.example.photomanager.data.AlbumQuery
import com.example.photomanager.data.AlbumSortField
import com.example.photomanager.data.GroupData
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.data.database.GroupWithAlbumCount
import com.example.photomanager.ui.state.PageState
import com.example.photomanager.ui.state.UiText
import com.example.photomanager.ui.state.asPageStates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val albumData: AlbumData,
    private val groupData: GroupData,
    private val settingsData: AlbumListSettingsData,
    @get:ApplicationContext private val context: Context
) : ViewModel() {
    var state by mutableStateOf<PageState<AlbumContent>>(PageState.Loading)
        private set
    val albums get() = (state as? PageState.Content)?.value?.albums.orEmpty()

    var groupItems by mutableStateOf<List<GroupWithAlbumCount>>(emptyList())
        private set
    val groups get() = groupItems.map { it.group }
    var ungroupedAlbumCount by mutableIntStateOf(0)
        private set
    var searchInput by mutableStateOf("")
        private set
    var selectedGroupId by mutableStateOf<Long?>(null)
        private set
    var sortField by mutableStateOf(AlbumSortField.CUSTOM)
        private set
    var isDescending by mutableStateOf(false)
        private set

    private var albumQueryJob: Job? = null
    private var searchJob: Job? = null
    private var groupJob: Job? = null
    private var ungroupedCountJob: Job? = null

    init {
        viewModelScope.launch {
            val settings = loadSettings()
            sortField = settings.sortField
            isDescending = if (sortField == AlbumSortField.CUSTOM) {
                sortField.defaultDescending
            } else {
                settings.isDescending
            }
            observeGroups()
            observeUngroupedAlbumCount()
            observeAlbums()
        }
    }

    private suspend fun loadSettings(): AlbumListSettings = try {
        settingsData.observeSettings().first()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        AlbumListSettings()
    }

    fun retry() {
        searchJob?.cancel()
        observeAlbums()
    }

    fun onSearchInputChange(value: String) {
        searchInput = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (value.isNotBlank()) delay(300.milliseconds)
            observeAlbums()
        }
    }

    fun selectGroup(groupId: Long?) {
        if (selectedGroupId == groupId) return
        selectedGroupId = groupId
        searchJob?.cancel()
        observeAlbums()
    }

    fun selectSortField(field: AlbumSortField) {
        if (sortField == field) return
        searchJob?.cancel()
        sortField = field
        isDescending = field.defaultDescending
        persistSettings()
        observeAlbums()
    }

    fun setSortDescending(descending: Boolean) {
        if (sortField == AlbumSortField.CUSTOM || isDescending == descending) return
        isDescending = descending
        persistSettings()
        observeAlbums()
    }

    fun move(ids: Set<Long>, groupId: Long?) = perform {
        albumData.move(albums.filter { it.id in ids }, groupId)
    }

    fun delete(ids: Set<Long>) = perform {
        albumData.delete(albums.filter { it.id in ids })
    }

    private fun observeUngroupedAlbumCount() {
        ungroupedCountJob?.cancel()
        ungroupedCountJob = viewModelScope.launch {
            albumData.observeUngroupedAlbumCount()
                .catch { error ->
                    if (error is CancellationException) throw error
                    ungroupedAlbumCount = 0
                }
                .collect { ungroupedAlbumCount = it }
        }
    }

    private fun observeGroups() {
        groupJob?.cancel()
        groupJob = viewModelScope.launch {
            groupData.groupsWithAlbumCounts
                .catch { error ->
                    if (error is CancellationException) throw error
                    errorMessage(R.string.page_albums_load_failed)
                }
                .collect { updatedGroups ->
                    groupItems = updatedGroups
                    val currentGroupId = selectedGroupId
                    if (currentGroupId != null &&
                        updatedGroups.none { it.group.id == currentGroupId }
                    ) {
                        selectedGroupId = null
                        observeAlbums()
                    }
                }
        }
    }

    private fun observeAlbums() {
        albumQueryJob?.cancel()
        state = PageState.Loading
        // 查询参数在启动前确定，旧任务不能读取下一次查询参数。
        val groupId = selectedGroupId
        val keyword = searchInput
        val field = sortField
        val descending = isDescending
        val query = AlbumQuery(groupId, keyword, field, descending)
        albumQueryJob = viewModelScope.launch {
            albumData.observeAlbums(query)
                .asPageStates(R.string.page_albums_load_failed) { albums ->
                    if (albums.isEmpty()) {
                        val message = if (keyword.isNotBlank() || groupId != null)
                            R.string.page_no_matching_albums else R.string.page_no_albums
                        PageState.Message(UiText.Resource(message))
                    } else {
                        PageState.Content(AlbumContent(albums))
                    }
                }
                .collect { state = it }
        }
    }

    private fun persistSettings() {
        viewModelScope.launch {
            try {
                settingsData.save(
                    AlbumListSettings(
                        sortField = sortField,
                        isDescending = isDescending
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                errorMessage(R.string.album_operation_failed)
            }
        }
    }

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                errorMessage(R.string.album_operation_failed)
            }
        }
    }

    private fun errorMessage(@StringRes messageRes: Int) {
        Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
    }
}

data class AlbumContent(val albums: List<AlbumEntity>)
