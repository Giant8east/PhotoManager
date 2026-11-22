/** 远端相册详情页的 ViewModel：按对方相册 ID 拉取图片列表，结合 LRU 缓存避免重复请求。 */
package com.giant8east.photomanager.pages.lan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.data.lan.RemoteAlbumDetailCache
import com.giant8east.photomanager.lan.client.LanClient
import com.giant8east.photomanager.lan.protocol.LanAlbumDto
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
    private val lanClient: LanClient,
    private val detailCache: RemoteAlbumDetailCache
) : ViewModel() {
    var state by mutableStateOf<PageState<RemotePhotoContent>>(PageState.Loading)
        private set

    private var device: LanDevice? = null
    private var album: LanAlbumDto? = null
    private var loadJob: Job? = null

    fun load(target: LanDevice, targetAlbum: LanAlbumDto) {
        if (device == target && album?.id == targetAlbum.id && state is PageState.Content) return
        device = target
        album = targetAlbum
        // 命中 LRU 且张数与修改时间未变时直接秒开，不发网络请求
        val cached = detailCache.getIfFresh(target, targetAlbum)
        if (cached != null) {
            state = PageState.Content(RemotePhotoContent(cached))
            return
        }
        retry()
    }

    fun retry() {
        val target = device ?: return
        val currentAlbum = album ?: return
        loadJob?.cancel()
        state = PageState.Loading
        loadJob = viewModelScope.launch {
            val result = runCatching { lanClient.photos(target, currentAlbum.id) }
            result.onSuccess { photos ->
                detailCache.put(target, currentAlbum, photos)
                state = PageState.Content(RemotePhotoContent(photos))
            }.onFailure { error ->
                if (error is CancellationException) throw error
                state = PageState.Message(error.toLanUiText(), canRetry = true)
            }
        }
    }
}
