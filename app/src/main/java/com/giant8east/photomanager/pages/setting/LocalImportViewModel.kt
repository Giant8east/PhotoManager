/** 本地导入页的 ViewModel：管理扫描目录、发起同步并统计已同步相册数量。 */
package com.giant8east.photomanager.pages.setting

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.giant8east.photomanager.R
import com.giant8east.photomanager.data.album.AlbumData
import com.giant8east.photomanager.data.scan.ScanRoot
import com.giant8east.photomanager.data.scan.ScanRootsData
import com.giant8east.photomanager.data.scan.SyncManager
import com.giant8east.photomanager.ui.state.UiText
import com.giant8east.photomanager.ui.state.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

@HiltViewModel
class LocalImportViewModel @Inject constructor(
    private val scanRootsData: ScanRootsData,
    private val albumData: AlbumData,
    private val syncManager: SyncManager
) : ViewModel() {
    var directories by mutableStateOf<List<ScanRoot>>(emptyList())
        private set
    var validAlbumCount by mutableIntStateOf(0)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var loadError by mutableStateOf<UiText?>(null)
        private set
    private var settingsJob: Job? = null

    var syncingPath by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val isSyncing get() = syncingPath != null

    init {
        reload()
        viewModelScope.launch {
            syncManager.state.collect { sync ->
                syncingPath = sync.path
                errorMessage = sync.errorMessage
            }
        }
    }

    /** 重新订阅目录和相册数量；已有内容时保留列表，仅更新数据。 */
    fun reload() {
        settingsJob?.cancel()
        loadError = null
        isLoading = directories.isEmpty()
        settingsJob = viewModelScope.launch {
            combine(
                scanRootsData.roots,
                albumData.observeValidAlbumCount()
            ) { directories, count -> directories to count }
                .catch { error ->
                    if (error is CancellationException) throw error
                    isLoading = false
                    loadError = error.toUiText(R.string.page_settings_load_failed)
                }
                .collect { (directories, count) ->
                    this@LocalImportViewModel.directories = directories
                    validAlbumCount = count
                    isLoading = false
                    loadError = null
                }
        }
    }

    fun onDirectoryPicked(uri: Uri) = syncManager.addDirectory(uri)

    fun syncAllDirectories() = syncManager.syncAllDirectories()

    fun stopSync() = syncManager.stop()

    fun removeDirectory(directory: ScanRoot) = syncManager.removeDirectory(directory)
}
