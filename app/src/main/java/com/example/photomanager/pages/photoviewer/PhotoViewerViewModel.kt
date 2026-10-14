/** 图片浏览页状态，负责分页图片、当前索引和阅读进度。 */
package com.example.photomanager.pages.photoviewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.database.PhotoEntity
import com.example.photomanager.data.PhotoViewerSettings
import com.example.photomanager.data.PhotoViewerSettingsData
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    private val albumData: AlbumData
    , private val settingsData: PhotoViewerSettingsData
) : ViewModel() {
    private val albumId = MutableStateFlow<Long?>(null)
    private var currentAlbumId: Long? = null
    private var _currentIndex = MutableStateFlow(0)
    val currentIndex: Flow<Int> = _currentIndex
    val photos: Flow<PagingData<PhotoEntity>> = albumId.flatMapLatest { id ->
        id?.let(albumData::pagePhotos) ?: flowOf(PagingData.empty())
    }.cachedIn(viewModelScope)
    val settings = settingsData.settings

    fun saveSettings(value: PhotoViewerSettings) {
        viewModelScope.launch { settingsData.save(value) }
    }

    fun load(albumId: Long, initialIndex: Int) {
        currentAlbumId = albumId
        _currentIndex.value = initialIndex.coerceAtLeast(0)
        this.albumId.value = albumId
    }

    fun moveTo(index: Int) {
        _currentIndex.value = index.coerceAtLeast(0)
        saveReadingProgress()
    }

    private fun saveReadingProgress() {
        currentAlbumId?.let { id ->
            viewModelScope.launch { albumData.updateReadingProgress(id, _currentIndex.value) }
        }
    }

    override fun onCleared() {
        saveReadingProgress()
        super.onCleared()
    }
}
