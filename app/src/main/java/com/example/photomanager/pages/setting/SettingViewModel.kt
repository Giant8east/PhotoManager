package com.example.photomanager.pages.setting

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.photomanager.data.AlbumData
import com.example.photomanager.data.SettingData
import com.example.photomanager.data.SettingDirectory
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class SettingViewModel @Inject constructor(
    private val settingData: SettingData,
    private val albumData: AlbumData
) : ViewModel() {
    private var _directories by mutableStateOf<List<SettingDirectory>>(emptyList())
    private var _validAlbumCount by mutableIntStateOf(0)
    private var _syncingUri by mutableStateOf<String?>(null)
    private var _errorMessage by mutableStateOf<String?>(null)
    val directories get() = _directories
    val validAlbumCount get() = _validAlbumCount
    val syncingUri get() = _syncingUri
    val isSyncing get() = _syncingUri != null
    val errorMessage get() = _errorMessage
    private var syncJob: Job? = null

    init {
        viewModelScope.launch { settingData.directories.collect { _directories = it } }
        viewModelScope.launch { settingData.validAlbumCount.collect { _validAlbumCount = it } }
    }

    fun onDirectoryPicked(uri: Uri) = viewModelScope.launch {
        try {
            settingData.persistDirectoryPermission(uri)
            if (!settingData.contains(uri)) {
                settingData.addDirectory(uri)
                syncAllDirectoriesNow()
            }
        } catch (error: Exception) {
            _errorMessage = error.message ?: "目录导入失败"
        }
    }

    fun syncDirectory(uri: Uri) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _syncingUri = uri.toString(); _errorMessage = null
            try {
                albumData.syncDirectory(uri)
            } catch (error: Exception) {
                _errorMessage = error.message ?: "目录同步失败"
            } finally {
                _syncingUri = null
            }
        }
    }

    /** 重新同步当前所有已保存的扫描目录。 */
    fun syncAllDirectories() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            _syncingUri = "all"; _errorMessage = null
            try {
                syncAllDirectoriesNow()
            } catch (error: Exception) {
                _errorMessage = error.message ?: "目录同步失败"
            } finally { _syncingUri = null }
        }
    }

    private suspend fun syncAllDirectoriesNow() {
        settingData.directories.first().forEach { albumData.syncDirectory(Uri.parse(it.uri)) }
    }

    fun removeDirectory(directory: SettingDirectory) = viewModelScope.launch {
        settingData.removeDirectory(directory.uri)
        albumData.deleteAlbumsUnderPath(directory.uri)
        syncAllDirectoriesNow()
    }
}
