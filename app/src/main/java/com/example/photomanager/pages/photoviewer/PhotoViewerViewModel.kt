/** 图片浏览页状态，观察相册完整图片索引并保存阅读进度。 */
package com.example.photomanager.pages.photoviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.database.PhotoEntity
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.PhotoViewerSettingsData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    private val albumData: AlbumData,
    private val settingsData: PhotoViewerSettingsData
) : ViewModel() {
    /** 一次待写入的阅读进度，带上相册 ID 避免切换相册后写错目标。 */
    private data class ReadingProgress(val albumId: Long, val photoIndex: Int)

    private var currentAlbumId: Long? = null
    private var photoQueryJob: Job? = null
    private val readingProgress = MutableStateFlow<ReadingProgress?>(null)

    var photos by mutableStateOf<List<PhotoEntity>?>(null)
        private set
    val settings = settingsData.settings

    init {
        // 进度写入串行执行，连续翻页只保留最新位置，不为每一页启动一个写入协程。
        viewModelScope.launch {
            readingProgress.filterNotNull().collectLatest { progress ->
                albumData.updateReadingProgress(progress.albumId, progress.photoIndex)
            }
        }
    }

    fun saveSettings(value: PhotoViewerSettings) {
        viewModelScope.launch { settingsData.save(value) }
    }

    /** 同一相册复用观察任务，切换相册时取消旧任务。 */
    fun load(albumId: Long) {
        if (currentAlbumId == albumId && photoQueryJob?.isActive == true) return
        photoQueryJob?.cancel()
        currentAlbumId = albumId
        readingProgress.value = null
        photos = null
        photoQueryJob = viewModelScope.launch {
            albumData.observePhotos(albumId).collect { updatedPhotos ->
                photos = updatedPhotos
            }
        }
    }

    /** 记录翻页落定后的位置，相同位置由 StateFlow 自动去重。 */
    fun moveTo(index: Int) {
        val albumId = currentAlbumId ?: return
        readingProgress.value = ReadingProgress(albumId, index)
    }
}
