/** 相册详情观察完整图片索引、目录摘要与删除动作，图片像素由网格按需加载。 */
package com.example.photomanager.pages.albumdetail

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.database.AlbumEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(private val albumData: AlbumData) : ViewModel() {
    private val albumId = MutableStateFlow<Long?>(null)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val photos = albumId.filterNotNull().flatMapLatest(albumData::observePhotos)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val album = albumId.filterNotNull().flatMapLatest(albumData::observeAlbum)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    var isDeleting by mutableStateOf(false)
        private set
    var deleteError by mutableStateOf<String?>(null)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    var refreshMessage by mutableStateOf<String?>(null)
        private set

    fun load(album: AlbumEntity) {
        albumId.value = album.id
    }

    fun displayTime(album: AlbumEntity) = albumData.displayTime(album.modifiedAt)

    fun refresh(album: AlbumEntity, onAlbumRemoved: () -> Unit) {
        if (isRefreshing) return
        viewModelScope.launch {
            isRefreshing = true
            refreshMessage = null
            try {
                if (albumData.refreshAlbum(album)) refreshMessage = "刷新完成"
                else onAlbumRemoved()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                refreshMessage = error.message ?: "刷新失败"
            } finally {
                isRefreshing = false
            }
        }
    }

    fun delete(album: AlbumEntity, onSuccess: () -> Unit) {
        if (isDeleting) return
        viewModelScope.launch {
            isDeleting = true
            deleteError = null
            try {
                albumData.delete(listOf(album))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                deleteError = error.message ?: "删除失败"
                return@launch
            } finally {
                isDeleting = false
            }
            onSuccess()
        }
    }
}
