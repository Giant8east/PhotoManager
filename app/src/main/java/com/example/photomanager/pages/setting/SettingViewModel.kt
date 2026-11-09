package com.example.photomanager.pages.setting

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photomanager.R
import com.example.photomanager.data.SettingData
import com.example.photomanager.data.SettingDirectory
import com.example.photomanager.data.SyncManager
import com.example.photomanager.ui.state.UiText
import com.example.photomanager.ui.state.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingViewModel @Inject constructor(
    private val settingData: SettingData,
    private val syncManager: SyncManager
) : ViewModel() {
    var directories by mutableStateOf<List<SettingDirectory>>(emptyList())
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
                settingData.directories,
                settingData.validAlbumCount
            ) { directories, count -> directories to count }
                .catch { error ->
                    if (error is CancellationException) throw error
                    isLoading = false
                    loadError = error.toUiText(R.string.page_settings_load_failed)
                }
                .collect { (directories, count) ->
                    this@SettingViewModel.directories = directories
                    validAlbumCount = count
                    isLoading = false
                    loadError = null
                }
        }
    }

    fun onDirectoryPicked(uri: Uri) = syncManager.addDirectory(uri)

    fun syncAllDirectories() = syncManager.syncAllDirectories()

    fun stopSync() = syncManager.stop()

    fun removeDirectory(directory: SettingDirectory) = syncManager.removeDirectory(directory)
}
