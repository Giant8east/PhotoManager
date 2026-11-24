/** 相册详情观察完整图片索引和目录摘要，负责收藏切换；删除由相册库统一管理。 */
package com.giant8east.photomanager.pages.albumdetail

import android.content.Context
import android.util.Log
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.runtime.*
import com.giant8east.photomanager.R
import com.giant8east.photomanager.ui.state.*
import com.giant8east.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.Job
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.data.album.AlbumData
import com.giant8east.photomanager.data.album.AlbumSyncData
import com.giant8east.photomanager.data.album.RefreshAlbumResult
import com.giant8east.photomanager.data.database.AlbumEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

@HiltViewModel(assistedFactory = AlbumDetailViewModel.Factory::class)
class AlbumDetailViewModel @AssistedInject constructor(
    private val albumData: AlbumData,
    private val albumSyncData: AlbumSyncData,
    @ApplicationContext private val context: Context,
    @Assisted private val albumId: Long,
    @Assisted private val albumPath: String
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(albumId: Long, albumPath: String): AlbumDetailViewModel
    }

    var state by mutableStateOf<PageState<AlbumDetailUiState>>(PageState.Loading)
        private set
    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    init {
        observeAlbum()
    }

    private fun observeAlbum() {
        loadJob?.cancel()
        state = PageState.Loading
        loadJob = viewModelScope.launch {
            albumData.observeAlbumDetail(albumId)
                .asPageStates(R.string.page_detail_load_failed) { detail ->
                    when {
                        detail == null -> PageState.Message(
                            UiText.Resource(R.string.page_album_missing),
                            canRetry = true
                        )
                        else -> PageState.Content(
                            AlbumDetailUiState(detail.album, detail.photos)
                        )
                    }
                }.collect { state = it }
        }
    }

    /** 读取失败后重新订阅 Room；不触发目录扫描。 */
    fun retryLoad() = observeAlbum()

    fun refreshDirectory(onFinished: (Boolean) -> Unit) {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            try {
                when (val result = albumSyncData.refreshAlbum(albumPath)) {
                    RefreshAlbumResult.Updated -> {
                        Toast.makeText(context, R.string.album_refresh_success, Toast.LENGTH_SHORT).show()
                        onFinished(true)
                    }
                    RefreshAlbumResult.Removed -> {
                        Toast.makeText(context, R.string.album_record_removed, Toast.LENGTH_SHORT).show()
                        onFinished(false)
                    }
                    RefreshAlbumResult.Cancelled -> {
                        Toast.makeText(context, R.string.album_refresh_busy, Toast.LENGTH_SHORT).show()
                        onFinished(true)
                    }
                    is RefreshAlbumResult.Failed -> {
                        val message = result.message ?: context.getString(R.string.album_detail_refresh_failed)
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        onFinished(true)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to refresh album directory: $albumPath", error)
                Toast.makeText(
                    context,
                    error.toUiText(R.string.album_detail_refresh_failed).resolve(context),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 切换收藏状态，收藏与分组互不影响。 */
    fun toggleFavorite() {
        val album = (state as? PageState.Content)?.value?.album ?: return
        viewModelScope.launch {
            try {
                albumData.setFavorite(album.id, !album.isFavorite)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to toggle favorite for album ${album.id}", error)
                Toast.makeText(context, R.string.album_operation_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 切换当前相册的 `.nomedia` 隐藏状态。 */
    fun toggleHidden() {
        val album = (state as? PageState.Content)?.value?.album ?: return
        val targetHidden = !album.isHidden
        viewModelScope.launch {
            try {
                albumData.setHidden(listOf(album), targetHidden)
                val toastRes = if (targetHidden) R.string.album_hidden_done else R.string.album_unhidden_done
                Toast.makeText(context, toastRes, Toast.LENGTH_SHORT).show()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Failed to toggle hidden for album ${album.id}", error)
                Toast.makeText(context, R.string.album_operation_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val TAG = "AlbumDetailViewModel"
    }
}

data class AlbumDetailUiState(val album: AlbumEntity, val photos: List<PhotoEntity>)
