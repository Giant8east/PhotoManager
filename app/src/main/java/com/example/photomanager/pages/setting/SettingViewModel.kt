package com.example.photomanager.pages.setting

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photomanager.R
import com.example.photomanager.data.SettingData
import com.example.photomanager.data.SettingDirectory
import com.example.photomanager.data.SyncManager
import com.example.photomanager.ui.state.PageState
import com.example.photomanager.ui.state.asPageStates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingViewModel @Inject constructor(
    private val settingData: SettingData,
    private val syncManager: SyncManager
) : ViewModel() {
    var state by mutableStateOf<PageState<SettingContent>>(PageState.Loading)
        private set
    private var loadJob: Job? = null
    var syncingPath by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set

    val isSyncing get() = syncingPath != null

    init {
        retry()
        viewModelScope.launch {
            syncManager.state.collect { sync ->
                syncingPath = sync.path
                errorMessage = sync.errorMessage
            }
        }
    }

    fun retry() {
        loadJob?.cancel()
        state = PageState.Loading
        loadJob = viewModelScope.launch {
            combine(settingData.directories, settingData.validAlbumCount, ::SettingContent)
                .asPageStates(R.string.page_settings_load_failed) { PageState.Content(it) }
                .collect { state = it }
        }
    }

    fun onDirectoryPicked(uri: Uri) = syncManager.addDirectory(uri)

    fun syncDirectory(path: String) = syncManager.syncDirectory(path)

    fun syncAllDirectories() = syncManager.syncAllDirectories()

    fun stopSync() = syncManager.stop()

    fun removeDirectory(directory: SettingDirectory) = syncManager.removeDirectory(directory)
}

data class SettingContent(val directories: List<SettingDirectory>, val validAlbumCount: Int)
