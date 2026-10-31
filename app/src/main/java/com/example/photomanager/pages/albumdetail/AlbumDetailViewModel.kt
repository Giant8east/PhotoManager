/** 相册详情观察完整图片索引、目录摘要与删除动作，图片像素由网格按需加载。 */
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    var isDeleting by mutableStateOf(false)
        private set
    private var loadJob: Job? = null
    private var refreshJob: Job? = null

    init {
        observeAlbum()
    }

    private fun observeAlbum(showLoading: Boolean = true) {
        loadJob?.cancel()
        if (showLoading) state = PageState.Loading
        loadJob = viewModelScope.launch {
            combine(albumData.observeAlbum(albumId), albumData.observePhotos(albumId)) { album, photos ->
                album to photos
            }.asPageStates(R.string.page_detail_load_failed) { (album, photos) ->
                when {
                    album == null -> PageState.Message(
                        UiText.Resource(R.string.page_album_missing),
                        canRetry = true
                    )
                    photos.isEmpty() -> PageState.Message(UiText.Resource(R.string.page_no_photos))
                    else -> PageState.Content(AlbumDetailUiState(album, photos))
                }
            }.collect { if (showLoading || it != PageState.Loading) state = it }
        }
    }

    /** 读取失败后重新订阅 Room；不触发目录扫描。 */
    fun retryLoad() = observeAlbum()

    fun refreshDirectory(onFinished: (Boolean) -> Unit) {
        if (isDeleting || refreshJob?.isActive == true) return
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

    fun delete(onDeleted: () -> Unit) {
        val previousState = state as? PageState.Content<AlbumDetailUiState> ?: return
        val album = previousState.value.album
        loadJob?.cancel()
        refreshJob?.cancel()
        isDeleting = true
        state = PageState.Loading
        viewModelScope.launch {
            try {
                // 文件删除和数据库清理必须成对完成，页面取消不能停在中间状态。
                withContext(NonCancellable) { albumData.delete(listOf(album)) }
                if (currentCoroutineContext()[Job]?.isActive == true) {
                    Toast.makeText(context, R.string.album_delete_success, Toast.LENGTH_SHORT).show()
                    onDeleted()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (currentCoroutineContext()[Job]?.isActive == true) {
                    isDeleting = false
                    state = previousState
                    observeAlbum(showLoading = false)
                    Toast.makeText(
                        context,
                        error.message?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.album_detail_delete_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}

data class AlbumDetailUiState(val album: AlbumEntity, val photos: List<PhotoEntity>)
