/** 图片浏览页状态，观察相册完整图片索引并保存阅读进度。 */
package com.giant8east.photomanager.pages.photoviewer

import android.content.Context
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import com.giant8east.photomanager.R
import com.giant8east.photomanager.ui.state.*
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.giant8east.photomanager.data.album.AlbumData
import com.giant8east.photomanager.data.database.PhotoEntity
import com.giant8east.photomanager.data.settings.PhotoViewerSettings
import com.giant8east.photomanager.data.settings.PhotoViewerSettingsData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    private val albumData: AlbumData,
    private val settingsData: PhotoViewerSettingsData,
    @ApplicationContext private val context: Context
) : ViewModel() {
    /** 一次待写入的阅读进度，带上相册 ID 避免切换相册后写错目标。 */
    private data class ReadingProgress(val albumId: Long, val photoIndex: Int)

    private var currentAlbumId: Long? = null
    private var photoQueryJob: Job? = null
    private val readingProgress = MutableStateFlow<ReadingProgress?>(null)

    var state by mutableStateOf<PageState<PhotoViewerContent>>(PageState.Loading)
        private set

    init {
        // 进度写入串行执行，连续翻页只保留最新位置，不为每一页启动一个写入协程。
        viewModelScope.launch {
            readingProgress.filterNotNull().collectLatest { progress ->
                albumData.updateReadingProgress(progress.albumId, progress.photoIndex)
            }
        }
    }

    fun saveSettings(value: PhotoViewerSettings) {
        viewModelScope.launch {
            try {
                settingsData.save(value)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(context, R.string.viewer_settings_save_failed, Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    /** 同一相册复用观察任务，切换相册时取消旧任务。 */
    fun load(albumId: Long) {
        if (currentAlbumId == albumId && photoQueryJob?.isActive == true) return
        photoQueryJob?.cancel()
        currentAlbumId = albumId
        readingProgress.value = null
        state = PageState.Loading
        photoQueryJob = viewModelScope.launch {
            combine(albumData.observePhotos(albumId), settingsData.observeSettings()) { photos, settings ->
                PhotoViewerContent(photos, settings)
            }.asPageStates(R.string.page_reader_load_failed) { content ->
                if (content.photos.isEmpty()) PageState.Message(UiText.Resource(R.string.page_no_photos))
                else PageState.Content(content)
            }.collect { state = it }
        }
    }

    fun retry() {
        val albumId = currentAlbumId ?: return
        photoQueryJob?.cancel()
        photoQueryJob = null
        load(albumId)
    }

    /**
     * 远端相册：照片列表由调用方提供（`contentUri` 是对方的媒体地址），
     * 只观察本地阅读设置，不写本地阅读进度。
     */
    fun loadPhotos(photos: List<PhotoEntity>) {
        photoQueryJob?.cancel()
        currentAlbumId = null
        readingProgress.value = null
        state = PageState.Loading
        photoQueryJob = viewModelScope.launch {
            settingsData.observeSettings().collect { settings ->
                state = if (photos.isEmpty()) {
                    PageState.Message(UiText.Resource(R.string.page_no_photos))
                } else {
                    PageState.Content(PhotoViewerContent(photos, settings))
                }
            }
        }
    }

    /** 记录翻页落定后的位置，相同位置由 StateFlow 自动去重。 */
    fun moveTo(index: Int) {
        val albumId = currentAlbumId ?: return
        readingProgress.value = ReadingProgress(albumId, index)
    }
}

data class PhotoViewerContent(val photos: List<PhotoEntity>, val settings: PhotoViewerSettings)
