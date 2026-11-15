/** 远端相册页的 ViewModel：握手后拉取对方分组与相册，只在线浏览、不做下载。 */
package com.giant8east.photomanager.pages.lan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.R
import com.giant8east.photomanager.lan.LanAlbumDto
import com.giant8east.photomanager.lan.LanClient
import com.giant8east.photomanager.lan.LanDevice
import com.giant8east.photomanager.lan.LanGroupDto
import com.giant8east.photomanager.ui.state.PageState
import com.giant8east.photomanager.ui.state.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 远端相册列表的一次性快照，供 PageContent 交付页面。 */
data class RemoteAlbumContent(val albums: List<LanAlbumDto>)

@HiltViewModel
class RemoteAlbumViewModel @Inject constructor() : ViewModel() {
    var device by mutableStateOf<LanDevice?>(null)
        private set
    var state by mutableStateOf<PageState<RemoteAlbumContent>>(PageState.Loading)
        private set
    var groups by mutableStateOf<List<LanGroupDto>>(emptyList())
        private set
    var ungroupedCount by mutableIntStateOf(0)
        private set
    /** null 表示未分组视图，与本地相册页的语义一致。 */
    var selectedGroupId by mutableStateOf<Long?>(null)
        private set
    var token by mutableStateOf<String?>(null)
        private set

    fun load(target: LanDevice) {
        if (device == target && state is PageState.Content) return
        device = target
        retry()
    }

    fun retry() {
        val target = device ?: return
        state = PageState.Loading
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val remote = LanClient(target.address)
                    val issued = remote.handshake()
                    Triple(issued, remote.albums(issued), remote.groups(issued))
                }
            }
            result.onSuccess { (issued, albums, groupList) ->
                token = issued
                groups = groupList.groups
                ungroupedCount = groupList.ungroupedCount
                state = PageState.Content(RemoteAlbumContent(albums))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = PageState.Message(UiText.Resource(R.string.lan_unreachable), canRetry = true)
            }
        }
    }

    fun selectGroup(groupId: Long?) {
        selectedGroupId = groupId
    }
}
