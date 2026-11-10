/** 应用级相册删除协调器：持有长删除任务，暴露进度并协调扫描写库屏障。 */
package com.example.photomanager.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.example.photomanager.data.database.AlbumEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class DeleteState(
    val isRunning: Boolean = false,
    val currentAlbumName: String? = null,
    val deletedFiles: Long = 0,
    val failedFiles: Int = 0,
    val completedAlbums: Int = 0,
    val totalAlbums: Int = 0,
    val result: DeleteResult? = null,
    val failureMessage: String? = null
)

sealed interface DeleteResult {
    data object Success : DeleteResult
    data object Busy : DeleteResult
    data class Partial(
        val deletedAlbums: Int,
        val failedAlbumIds: List<Long>,
        val failedFiles: Int
    ) : DeleteResult
    data class Failure(val message: String?) : DeleteResult
}

@Singleton
class DeleteManager @Inject constructor(
    private val albumData: AlbumData,
    private val deletionGate: DeletionGate,
    private val fileTreeDeleter: FileTreeDeleter
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationMutex = Mutex()
    private var activeJob: Deferred<DeleteResult>? = null
    private val _state = MutableStateFlow(DeleteState())
    val state: StateFlow<DeleteState> = _state.asStateFlow()

    /**
     * 在调用方启动协程前同步清空上一次结果，避免进度弹窗把旧结果当成新任务结果。
     */
    fun prepare(albums: List<AlbumEntity>) {
        if (activeJob?.isActive == true) return
        _state.value = DeleteState(
            isRunning = true,
            totalAlbums = albums.size
        )
    }

    /**
     * 启动一次删除。任务运行在应用级作用域，调用方取消不会中断删除。
     */
    suspend fun delete(albums: List<AlbumEntity>): DeleteResult {
        val selected = albums.distinctBy { it.id }
        if (selected.isEmpty()) return DeleteResult.Success

        val operation = operationMutex.withLock {
            if (activeJob?.isActive == true) {
                return DeleteResult.Busy
            }
            _state.value = DeleteState(
                isRunning = true,
                totalAlbums = selected.size
            )
            scope.async { deleteInternal(selected) }.also { activeJob = it }
        }
        return operation.await()
    }

    private suspend fun deleteInternal(albums: List<AlbumEntity>): DeleteResult {
        val lease = deletionGate.beginDeletion(albums.map { it.albumPath })
        var deletedFiles = 0L
        var failedFiles = 0
        var completedAlbums = 0
        val failedAlbumIds = mutableListOf<Long>()
        var failureMessage: String? = null

        _state.value = DeleteState(
            isRunning = true,
            totalAlbums = albums.size
        )

        try {
            for (album in albums) {
                currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(
                        currentAlbumName = album.name,
                        completedAlbums = completedAlbums,
                        deletedFiles = deletedFiles,
                        failedFiles = failedFiles
                    )
                }

                val treeResult = fileTreeDeleter.deleteTree(
                    File(DirectoryScanner.normalizePath(album.albumPath))
                ) { progressFiles ->
                    _state.update { it.copy(deletedFiles = deletedFiles + progressFiles) }
                }
                deletedFiles += treeResult.deletedFiles
                failedFiles += treeResult.failedFiles

                if (treeResult.hasBlockingFileFailure) {
                    failedAlbumIds += album.id
                    continue
                }

                try {
                    albumData.deleteAlbumsByIds(listOf(album.id))
                    completedAlbums++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    failedAlbumIds += album.id
                    failureMessage = error.message
                }
            }

            val result = if (failedAlbumIds.isEmpty()) {
                DeleteResult.Success
            } else {
                DeleteResult.Partial(
                    deletedAlbums = completedAlbums,
                    failedAlbumIds = failedAlbumIds,
                    failedFiles = failedFiles
                )
            }
            _state.value = DeleteState(
                result = result,
                deletedFiles = deletedFiles,
                failedFiles = failedFiles,
                completedAlbums = completedAlbums,
                totalAlbums = albums.size,
                failureMessage = failureMessage
            )
            return result
        } catch (cancelled: CancellationException) {
            _state.update {
                it.copy(
                    isRunning = false,
                    currentAlbumName = null,
                    deletedFiles = deletedFiles,
                    failedFiles = failedFiles,
                    completedAlbums = completedAlbums,
                    result = DeleteResult.Failure(null)
                )
            }
            throw cancelled
        } catch (error: Exception) {
            val result = DeleteResult.Failure(error.message)
            _state.value = DeleteState(
                result = result,
                deletedFiles = deletedFiles,
                failedFiles = failedFiles,
                completedAlbums = completedAlbums,
                totalAlbums = albums.size,
                failureMessage = error.message
            )
            return result
        } finally {
            deletionGate.endDeletion(lease)
        }
    }
}
