/** 远端相册详情页的 ViewModel：按对方相册 ID 拉取图片列表，仅用于在线浏览。 */
package com.giant8east.photomanager.pages.lan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.R
import com.giant8east.photomanager.lan.LanClient
import com.giant8east.photomanager.lan.LanDevice
import com.giant8east.photomanager.lan.LanPhotoDto
import com.giant8east.photomanager.ui.state.PageState
import com.giant8east.photomanager.ui.state.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RemotePhotoContent(val photos: List<LanPhotoDto>)

@HiltViewModel
class RemoteAlbumDetailViewModel @Inject constructor() : ViewModel() {
    var state by mutableStateOf<PageState<RemotePhotoContent>>(PageState.Loading)
        private set
    var token by mutableStateOf<String?>(null)
        private set

    private var device: LanDevice? = null
    private var albumId: Long = 0

    fun load(target: LanDevice, id: Long) {
        if (device == target && albumId == id && state is PageState.Content) return
        device = target
        albumId = id
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
                    issued to remote.photos(issued, albumId)
                }
            }
            result.onSuccess { (issued, photos) ->
                token = issued
                state = PageState.Content(RemotePhotoContent(photos))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = PageState.Message(UiText.Resource(R.string.lan_unreachable), canRetry = true)
            }
        }
    }
}
