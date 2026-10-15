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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
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

    fun onDirectoryPicked(uri: Uri) = runSync {
        settingData.persistDirectoryPermission(uri)
        if (!settingData.contains(uri)) {
            settingData.addDirectory(uri)
            syncAllDirectoriesNow()
        }
    }

    fun syncDirectory(uri: Uri) {
        runSync(uri.toString()) { albumData.syncDirectory(uri) }
    }

    /** 重新同步当前所有已保存的扫描目录。 */
    fun syncAllDirectories() {
        runSync { syncAllDirectoriesNow() }
    }

    /** 取消当前扫描，已提交的相册保留，由任务结束时复位同步状态。 */
    fun stopSync() {
        syncJob?.cancel()
    }

    /** 导入、移除和手动同步共用任务入口，等待旧任务退出后再操作目录。 */
    private fun runSync(uri: String = "all", operation: suspend () -> Unit) {
        val previousJob = syncJob
        syncJob = viewModelScope.launch {
            previousJob?.cancelAndJoin()
            _syncingUri = uri; _errorMessage = null
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _errorMessage = error.message ?: "目录同步失败"
            } finally { _syncingUri = null }
        }
    }

    private suspend fun syncAllDirectoriesNow() {
        settingData.directories.first().forEach { albumData.syncDirectory(Uri.parse(it.uri)) }
    }

    fun removeDirectory(directory: SettingDirectory) = runSync {
        settingData.removeDirectory(directory.uri)
        albumData.deleteAlbumsUnderPath(directory.uri)
        syncAllDirectoriesNow()
    }
}
