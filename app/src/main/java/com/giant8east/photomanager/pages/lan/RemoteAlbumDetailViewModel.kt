/** 远端相册详情页的 ViewModel：拉取图片列表（LRU + 下拉刷新），并管理单本相册的下载/已下载删除。 */
package com.giant8east.photomanager.pages.lan

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.data.database.AlbumDao
import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.delete.DeleteManager
import com.giant8east.photomanager.data.lan.RemoteAlbumDetailCache
import com.giant8east.photomanager.data.lan.RemoteAlbumKey
import com.giant8east.photomanager.data.lan.RemoteDownloadManager
import com.giant8east.photomanager.data.lan.RemoteDownloadStatus
import com.giant8east.photomanager.data.lan.RemoteDownloadTask
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class RemotePhotoContent(val photos: List<LanPhotoDto>)

@HiltViewModel
class RemoteAlbumDetailViewModel @Inject constructor(
    private val lanClient: LanClient,
    private val detailCache: RemoteAlbumDetailCache,
    private val downloadManager: RemoteDownloadManager,
    private val albumDao: AlbumDao,
    private val deleteManager: DeleteManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    var state by mutableStateOf<PageState<RemotePhotoContent>>(PageState.Loading)
        private set

    /** 下拉刷新态：已有内容时刷新不切回全屏 Loading，失败保留原内容并弹 Toast。 */
    var isRefreshing by mutableStateOf(false)
        private set

    /** 直接透传下载状态流，实时更新详情页"下载 / 下载中 / 已下载"按钮。 */
    val downloadTasks: StateFlow<Map<RemoteAlbumKey, RemoteDownloadTask>> = downloadManager.tasks

    /** 本机若已下载过该远端相册，持有对应的本地实体（用于显示"已下载"及确认删除）。 */
    var downloadedAlbum by mutableStateOf<AlbumEntity?>(null)
        private set

    private var device: LanDevice? = null
    private var album: LanAlbumDto? = null
    private var loadJob: Job? = null
    private var downloadedJob: Job? = null

    fun load(target: LanDevice, targetAlbum: LanAlbumDto) {
        if (device == target && album?.id == targetAlbum.id && state is PageState.Content) return
        device = target
        album = targetAlbum
        observeDownloaded(target, targetAlbum.id)
        // 命中 LRU 且张数与修改时间未变时直接秒开，不发网络请求
        val cached = detailCache.getIfFresh(target, targetAlbum)
        if (cached != null) {
            state = PageState.Content(RemotePhotoContent(cached))
            return
        }
        retry()
    }

    private fun observeDownloaded(target: LanDevice, remoteAlbumId: Long) {
        downloadedJob?.cancel()
        val deviceKey = target.deviceId ?: target.address
        downloadedJob = viewModelScope.launch {
            albumDao.observeDownloadedAlbumsByDeviceQuery(deviceKey).collect { list ->
                downloadedAlbum = list.firstOrNull { it.remoteAlbumId == remoteAlbumId }
            }
        }
    }

    /** 开始下载或取消进行中的下载。 */
    fun toggleDownload() {
        val target = device ?: return
        val currentAlbum = album ?: return
        val key = RemoteAlbumKey.of(target, currentAlbum.id)
        val status = downloadManager.tasks.value[key]?.status
        if (status is RemoteDownloadStatus.Queued || status is RemoteDownloadStatus.Downloading) {
            downloadManager.cancelOne(key)
        } else {
            downloadManager.enqueue(target, listOf(currentAlbum))
        }
    }

    /** 删除本机已下载的对应相册文件与数据库记录。 */
    fun deleteDownloaded() {
        val local = downloadedAlbum ?: return
        val target = device
        val currentAlbum = album
        if (target != null && currentAlbum != null) {
            downloadManager.cancelOne(RemoteAlbumKey.of(target, currentAlbum.id))
        }
        deleteManager.prepare(listOf(local))
        viewModelScope.launch {
            deleteManager.delete(listOf(local))
        }
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
                // 局域网响应极快，留出 1 秒视觉停留避免下拉刷新圆球一闪而逝
                delay(REFRESH_DELAY_MILLIS)
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

    private companion object {
        const val REFRESH_DELAY_MILLIS = 1_000L
    }
}
