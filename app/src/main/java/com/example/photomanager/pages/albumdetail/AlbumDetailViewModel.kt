/** 相册详情页状态，负责分页读取当前相册的图片元数据。 */
package com.example.photomanager.pages.albumdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.database.PhotoEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val albumData: AlbumData
) : ViewModel() {
    private val albumId = MutableStateFlow<Long?>(null)
    val photos: Flow<PagingData<PhotoEntity>> = albumId
        .flatMapLatest { id ->
            if (id == null) kotlinx.coroutines.flow.flowOf(PagingData.empty())
            else albumData.pagePhotos(id)
        }
        .cachedIn(viewModelScope)

    fun load(albumId: Long) { this.albumId.value = albumId }
}
