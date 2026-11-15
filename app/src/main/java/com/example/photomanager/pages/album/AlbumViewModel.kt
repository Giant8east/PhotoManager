/** 相册主页状态与动作：查询、筛选、排序、分组管理和单相册操作。 */
package com.example.photomanager.pages.album

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photomanager.R
import com.example.photomanager.data.album.AlbumData
import com.example.photomanager.data.settings.AlbumListSettingsData
import com.example.photomanager.data.album.AlbumQuery
import com.example.photomanager.data.album.AlbumSortField
import com.example.photomanager.data.delete.DeleteManager
import com.example.photomanager.data.group.GroupData
import com.example.photomanager.data.database.AlbumEntity
import com.example.photomanager.data.database.GroupWithAlbumCount
import com.example.photomanager.data.delete.DeleteState
import com.example.photomanager.data.settings.AlbumListSettings
import com.example.photomanager.ui.state.PageState
import com.example.photomanager.ui.state.asPageStates
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class AlbumViewModel @Inject constructor(
    private val albumData: AlbumData,
    private val groupData: GroupData,
    private val settingsData: AlbumListSettingsData,
    private val deleteManager: DeleteManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    var state by mutableStateOf<PageState<AlbumContent>>(PageState.Loading)
        private set
    val albums get() = (state as? PageState.Content)?.value?.albums.orEmpty()
    val deleteState: StateFlow<DeleteState> = deleteManager.state

    /** 分组及其相册数量，抽屉与移动分组弹窗共用这一份事实来源。 */
    var groupsWithCounts by mutableStateOf<List<GroupWithAlbumCount>>(emptyList())
        private set
    var ungroupedAlbumCount by mutableIntStateOf(0)
        private set
    var favoriteCount by mutableIntStateOf(0)
        private set
    var searchInput by mutableStateOf("")
        private set
    var selectedGroupId by mutableStateOf<Long?>(null)
        private set
    /** 收藏视图与分组视图互斥，选中其一时清空另一个。 */
    var favoritesOnly by mutableStateOf(false)
        private set
    var sortField by mutableStateOf(AlbumSortField.CUSTOM)
        private set
    var isDescending by mutableStateOf(false)
        private set

    private var albumQueryJob: Job? = null
    private var searchJob: Job? = null
    private var groupJob: Job? = null
    private var ungroupedCountJob: Job? = null
    private var favoriteCountJob: Job? = null

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
            observeFavoriteCount()
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

    /**
     * 关键词变化等同切换筛选：防抖结束后先进入 Loading，让列表整体让位，
     * 新结果到达后再一次性重建，避免剩余项向上补位的动画。
     */
    fun onSearchInputChange(value: String) {
        if (searchInput == value) return
        searchInput = value
        searchJob?.cancel()
        // 停掉在途查询，避免旧关键词的结果在防抖窗口里抢先上屏。
        albumQueryJob?.cancel()
        searchJob = viewModelScope.launch {
            if (value.isNotBlank()) delay(300.milliseconds)
            state = PageState.Loading
            observeAlbums()
        }
    }

    fun selectGroup(groupId: Long?) {
        // 搜索是全局的，切换视图时要先清空关键词，否则点了分组列表看起来没反应。
        val isSameView = selectedGroupId == groupId && !favoritesOnly
        if (isSameView && searchInput.isEmpty()) return
        // 从搜索结果退回分组列表同样是一次筛选切换，走 Loading 而不是逐项补位。
        val leavingSearch = searchInput.isNotEmpty()
        selectedGroupId = groupId
        favoritesOnly = false
        searchInput = ""
        searchJob?.cancel()
        albumQueryJob?.cancel()
        if (leavingSearch) state = PageState.Loading
        observeAlbums()
    }

    /** 切换到收藏视图，与分组筛选互斥；同样清空全局搜索关键词。 */
    fun selectFavorites() {
        if (favoritesOnly && searchInput.isEmpty()) return
        val leavingSearch = searchInput.isNotEmpty()
        favoritesOnly = true
        selectedGroupId = null
        searchInput = ""
        searchJob?.cancel()
        albumQueryJob?.cancel()
        if (leavingSearch) state = PageState.Loading
        observeAlbums()
    }

    /** 切换收藏状态；收藏视图下取消收藏时该行会随查询结果消失。 */
    fun toggleFavorite(album: AlbumEntity) = perform {
        albumData.setFavorite(album.id, !album.isFavorite)
    }

    /**
     * 保存拖拽后的自定义顺序。首次在其他排序下拖拽时，先把当前顺序物化为 `sortOrder`
     * 再切到自定义排序。
     *
     * 切换排序的关键是"不把中间态交给界面"：先停掉旧排序的观察，写完顺序与设置后
     * 再以自定义排序重新订阅。这样界面全程停留在拖拽结果上，既不会先弹回旧顺序，
     * 也不会出现重建闪烁与随后的位移动画。
     */
    fun reorderAlbums(albums: List<AlbumEntity>) {
        if (albums.isEmpty()) return
        val needsAdopt = sortField != AlbumSortField.CUSTOM
        // 收藏视图和分组视图各自维护一套顺序，写入前固定作用域。
        val favoritesView = favoritesOnly
        viewModelScope.launch {
            try {
                if (needsAdopt) {
                    // 暂停观察：写 sortOrder 会让旧排序的查询回流一次，界面不能看到它。
                    albumQueryJob?.cancel()
                    albumData.reorder(albums, favoritesView)
                    // 先持久化再改内存状态：保存失败时 UI 与查询仍停在原排序，不会半切换。
                    settingsData.save(
                        AlbumListSettings(
                            sortField = AlbumSortField.CUSTOM,
                            isDescending = AlbumSortField.CUSTOM.defaultDescending
                        )
                    )
                    sortField = AlbumSortField.CUSTOM
                    isDescending = AlbumSortField.CUSTOM.defaultDescending
                    // 保存是显式动作，和菜单换排序一致地走 Loading：一次性换成新顺序。
                    state = PageState.Loading
                    observeAlbums()
                    Toast.makeText(
                        context,
                        R.string.album_sort_switched_to_custom,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    albumData.reorder(albums, favoritesView)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 观察已被取消时也要恢复，否则列表会停在拖拽结果上不再跟随数据库。
                if (needsAdopt) observeAlbums()
                errorMessage(R.string.album_operation_failed)
            }
        }
    }

    /** 搜索结果是子集，重排会打散无关相册的相对顺序，因此拒绝拖拽并提示。 */
    fun notifyReorderBlocked() {
        Toast.makeText(context, R.string.album_reorder_blocked_by_search, Toast.LENGTH_SHORT).show()
    }

    /** 切换排序：先进入 Loading 让列表整体让位，新结果到达后一次性重建（对齐 EhViewer）。 */
    fun selectSortField(field: AlbumSortField) {
        if (sortField == field) return
        searchJob?.cancel()
        sortField = field
        isDescending = field.defaultDescending
        state = PageState.Loading
        persistSettings()
        observeAlbums()
    }

    fun setSortDescending(descending: Boolean) {
        if (sortField == AlbumSortField.CUSTOM || isDescending == descending) return
        // 与 selectSortField 一致：取消未到期的搜索防抖，避免排序后又被旧关键词查询一次。
        searchJob?.cancel()
        isDescending = descending
        state = PageState.Loading
        persistSettings()
        observeAlbums()
    }

    fun move(ids: Set<Long>, groupId: Long?) = perform {
        albumData.move(albums.filter { it.id in ids }, groupId)
    }

    fun delete(ids: Set<Long>) {
        val selected = albums.filter { it.id in ids }
        if (selected.isEmpty()) return
        deleteManager.prepare(selected)
        viewModelScope.launch {
            deleteManager.delete(selected)
        }
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

    private fun observeFavoriteCount() {
        favoriteCountJob?.cancel()
        favoriteCountJob = viewModelScope.launch {
            albumData.observeFavoriteCount()
                .catch { error ->
                    if (error is CancellationException) throw error
                    favoriteCount = 0
                }
                .collect { favoriteCount = it }
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
                    groupsWithCounts = updatedGroups
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
        // 已有内容时保留旧列表，避免分组切换出现整页 Loading 和列表重建。
        if (state !is PageState.Content) {
            state = PageState.Loading
        }
        // 查询参数在启动前确定，旧任务不能读取下一次查询参数。
        val groupId = selectedGroupId
        val keyword = searchInput
        val field = sortField
        val descending = isDescending
        val onlyFavorites = favoritesOnly
        val query = AlbumQuery(groupId, keyword, field, descending, onlyFavorites)
        albumQueryJob = viewModelScope.launch {
            albumData.observeAlbums(query)
                .asPageStates(R.string.page_albums_load_failed) { albums ->
                    PageState.Content(AlbumContent(albums))
                }
                .collect { nextState ->
                    // asPageStates 每次重新订阅都会先发 Loading；已有内容时忽略它。
                    if (nextState is PageState.Loading && state is PageState.Content) {
                        return@collect
                    }
                    state = nextState
                }
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
