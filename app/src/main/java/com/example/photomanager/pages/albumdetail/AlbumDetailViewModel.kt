/** 相册详情观察完整图片索引和目录摘要，负责刷新目录；删除由相册库统一管理。 */
package com.example.photomanager.pages.albumdetail

import android.content.Context
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.compose.runtime.*
import com.example.photomanager.R
import com.example.photomanager.ui.state.*
import com.example.photomanager.data.database.PhotoEntity
import kotlinx.coroutines.Job
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.database.AlbumEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

@HiltViewModel(assistedFactory = AlbumDetailViewModel.Factory::class)
class AlbumDetailViewModel @AssistedInject constructor(
    private val albumData: AlbumData,
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
                val isAlbumAvailable = albumData.refreshAlbum(albumPath)
                val messageRes = if (isAlbumAvailable) R.string.album_refresh_success
                else R.string.album_record_removed
                Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
                onFinished(isAlbumAvailable)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    error.message?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.album_detail_refresh_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

data class AlbumDetailUiState(val album: AlbumEntity, val photos: List<PhotoEntity>)
