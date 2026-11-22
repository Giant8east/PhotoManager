/** 远端相册详情页的 ViewModel：按对方相册 ID 拉取图片列表，仅用于在线浏览。 */
package com.giant8east.photomanager.pages.lan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.lan.client.LanClient
import com.giant8east.photomanager.lan.protocol.LanDevice
import com.giant8east.photomanager.lan.protocol.LanPhotoDto
import com.giant8east.photomanager.ui.state.PageState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class RemotePhotoContent(val photos: List<LanPhotoDto>)

@HiltViewModel
class RemoteAlbumDetailViewModel @Inject constructor(
    private val lanClient: LanClient
) : ViewModel() {
    var state by mutableStateOf<PageState<RemotePhotoContent>>(PageState.Loading)
        private set

    private var device: LanDevice? = null
    private var albumId: Long = 0
    private var loadJob: Job? = null

    fun load(target: LanDevice, id: Long) {
        if (device == target && albumId == id && state is PageState.Content) return
        device = target
        albumId = id
        retry()
    }

    fun retry() {
        val target = device ?: return
        loadJob?.cancel()
        state = PageState.Loading
        loadJob = viewModelScope.launch {
            val result = runCatching { lanClient.photos(target, albumId) }
            result.onSuccess { photos ->
                state = PageState.Content(RemotePhotoContent(photos))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = PageState.Message(error.toLanUiText(), canRetry = true)
            }
        }
    }
}
