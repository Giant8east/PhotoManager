/** 应用级目录同步协调器：脱离设置页面生命周期，统一持有同步任务和状态。 */
package com.example.photomanager.data

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class SyncState(
    val path: String? = null,
    val errorMessage: String? = null
) {
    val isSyncing: Boolean get() = path != null
}

@Singleton
class SyncManager @Inject constructor(
    private val albumData: AlbumData,
    private val settingData: SettingData
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private var syncJob: Job? = null
    private var generation = 0L

    /** 同步所有已配置目录；新任务会取消并等待正在执行的同步。 */
    fun syncAllDirectories() = start("all") {
        syncAllDirectoriesNow()
    }

    /** 同步指定规范化目录路径。 */
    fun syncDirectory(path: String) = start(path) {
        albumData.syncDirectory(path)
    }

    /** 解析并添加目录；添加成功后重新同步全部已配置目录。 */
    fun addDirectory(uri: Uri) = start("all") {
        val path = settingData.resolvePath(uri)
        if (settingData.addDirectory(path)) {
            syncAllDirectoriesNow()
        }
    }

    /** 移除目录配置及其相册记录，然后同步剩余目录。 */
    fun removeDirectory(directory: SettingDirectory) = start("all") {
        settingData.removeDirectory(directory.path)
        albumData.deleteAlbumsUnderPath(directory.path)
        syncAllDirectoriesNow()
    }

    /** 取消当前同步并清空状态；递增 generation 阻止旧任务回写。 */
    fun stop() {
        generation++
        syncJob?.cancel()
        syncJob = null
        _state.value = SyncState()
    }

    /**
     * 启动新的同步任务。
     *
     * 先取消并等待旧任务，token 与 generation 不一致时直接退出，
     * 保证过期任务不会覆盖当前同步状态。
     *
     * @param path 展示在同步状态中的目录路径，或 "all" 表示全量同步。
     * @param operation 实际执行的同步操作。
     */
    private fun start(path: String, operation: suspend () -> Unit) {
        val previous = syncJob
        val token = ++generation
        syncJob = scope.launch {
            previous?.cancelAndJoin()
            if (token != generation) return@launch

            _state.value = SyncState(path = path)
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(errorMessage = error.message ?: "目录同步失败")
                }
            } finally {
                if (token == generation) {
                    _state.update { it.copy(path = null) }
                }
            }
        }
    }

    /** 按配置顺序逐个同步全部目录；调用方负责保证运行在 IO 作用域。 */
    private suspend fun syncAllDirectoriesNow() {
        settingData.directories.first().forEach { directory ->
            albumData.syncDirectory(directory.path)
        }
    }
}
