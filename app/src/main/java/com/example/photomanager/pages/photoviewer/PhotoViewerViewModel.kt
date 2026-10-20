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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    private val albumData: AlbumData,
    private val settingsData: PhotoViewerSettingsData
) : ViewModel() {
    private var currentAlbumId: Long? = null
    private var lastSavedIndex: Int? = null
    private var photoQueryJob: Job? = null
    var photos by mutableStateOf<List<PhotoEntity>>(emptyList())
        private set
    var isLoading by mutableStateOf(true)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    val settings = settingsData.settings

    fun saveSettings(value: PhotoViewerSettings) {
        viewModelScope.launch { settingsData.save(value) }
    }

    /** 同一相册复用观察任务，切换相册时取消旧任务。 */
    fun load(albumId: Long) {
        if (currentAlbumId == albumId && photoQueryJob?.isActive == true) return
        photoQueryJob?.cancel()
        currentAlbumId = albumId
        lastSavedIndex = null
        photos = emptyList()
        isLoading = true
        errorMessage = null
        photoQueryJob = viewModelScope.launch {
            try {
                albumData.observePhotos(albumId).collect { updatedPhotos ->
                    photos = updatedPhotos
                    isLoading = false
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                errorMessage = error.localizedMessage ?: "读取相册失败"
                isLoading = false
            }
        }
    }

    fun moveTo(index: Int) {
        if (lastSavedIndex == index) return
        lastSavedIndex = index
        currentAlbumId?.let { id ->
            viewModelScope.launch { albumData.updateReadingProgress(id, index) }
        }
    }
}
