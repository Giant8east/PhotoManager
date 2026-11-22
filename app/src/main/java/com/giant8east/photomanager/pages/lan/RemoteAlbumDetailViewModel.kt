/** 远端相册详情页的 ViewModel：按对方相册 ID 拉取图片列表，结合 LRU 缓存与保留原内容的下拉刷新。 */
package com.giant8east.photomanager.pages.lan

import android.content.Context
import android.widget.Toast
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
import com.giant8east.photomanager.ui.state.resolve
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class RemotePhotoContent(val photos: List<LanPhotoDto>)

@HiltViewModel
class RemoteAlbumDetailViewModel @Inject constructor(
    private val lanClient: LanClient,
    private val detailCache: RemoteAlbumDetailCache,
    @ApplicationContext private val context: Context
) : ViewModel() {
    var state by mutableStateOf<PageState<RemotePhotoContent>>(PageState.Loading)
        private set

    /** 下拉刷新态：已有内容时刷新不切回全屏 Loading，失败保留原内容并弹 Toast。 */
    var isRefreshing by mutableStateOf(false)
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

    /** 首屏加载或全屏错误页重试：进入全屏 Loading。 */
    fun retry() {
        val target = device ?: return
        val currentAlbum = album ?: return
        loadJob?.cancel()
        isRefreshing = false
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

    /** 已有内容时的下拉刷新：绕过 LRU 强制重新拉取，期间保留网格不清空，失败弹 Toast。 */
    fun refresh() {
        val target = device ?: return
        val currentAlbum = album ?: return
        if (state !is PageState.Content) {
            retry()
            return
        }
        loadJob?.cancel()
        isRefreshing = true
        loadJob = viewModelScope.launch {
            try {
                val photos = lanClient.photos(target, currentAlbum.id)
                detailCache.put(target, currentAlbum, photos)
                state = PageState.Content(RemotePhotoContent(photos))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(context, error.toLanUiText().resolve(context), Toast.LENGTH_SHORT).show()
            } finally {
                isRefreshing = false
            }
        }
    }
}
