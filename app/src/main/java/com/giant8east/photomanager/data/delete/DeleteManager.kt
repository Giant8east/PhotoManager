/** 应用级相册删除协调器：持有长删除任务，暴露进度并协调扫描写库屏障。 */
package com.giant8east.photomanager.data.delete

import com.giant8east.photomanager.data.album.AlbumData
import com.giant8east.photomanager.data.scan.DirectoryScanner

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
import com.giant8east.photomanager.data.database.AlbumEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 删除进度快照；UI 只读订阅，不参与业务判断。 */
data class DeleteState(
    val isRunning: Boolean = false,
    /** 当前正在删除的相册名，用于进度弹窗标题。 */
    val currentAlbumName: String? = null,
    val deletedFiles: Long = 0,
    val failedFiles: Int = 0,
    /** 已完成（文件与记录都处理成功）的相册数。 */
    val completedAlbums: Int = 0,
    val totalAlbums: Int = 0,
    /** 任务结束后才有值；运行中为 null。 */
    val result: DeleteResult? = null,
    val failureMessage: String? = null
)

/** 一次删除的最终结论；用 sealed interface 让调用方的 `when` 必须处理所有情况。 */
sealed interface DeleteResult {
    /** 全部成功。 */
    data object Success : DeleteResult

    /** 已有删除任务在跑，本次请求被拒绝 */
    data object Busy : DeleteResult

    /** 部分相册没删干净；[failedAlbumIds] 供 UI 保留选中或提示重试。 */
    data class Partial(
        val deletedAlbums: Int,
        val failedAlbumIds: List<Long>,
        val failedFiles: Int
    ) : DeleteResult

    /** 整体失败（异常或取消）。 */
    data class Failure(val message: String?) : DeleteResult
}

/**
 * 删除流程的应用级协调器。
 *
 * 三个协作者的分工：
 * - [DeletionGate]：挡住"正在删除的路径"的扫描写库（顺序问题）；
 * - [FileTreeDeleter]：真正删除文件（动作）；
 * - [AlbumData]：删除数据库记录。
 *
 * 为什么任务跑在应用级 [scope] 而不是调用方的 viewModelScope：
 * 删除已经开始就无法回滚，页面退出时中断只会留下"文件删了一半、记录还在"的中间状态，
 * 所以调用方取消不应影响任务本身；UI 通过 [state] 观察进度。
 *
 * 同一时刻只允许一个删除任务（[operationMutex] + [activeJob]），避免两个任务互相删除
 * 同一路径时，一个成功一个报错。
 */
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

    /** 对外只读视图 */
    val state: StateFlow<DeleteState> = _state.asStateFlow()

    /**
     * 在调用方启动协程前同步清空上一次结果，避免进度弹窗把旧结果当成新任务结果。
     *
     * 已有任务在跑时不重置，否则会把正在进行的进度擦掉。
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
     *
     * 用 `async` 而不是 `launch`：调用方需要拿到最终 [DeleteResult]。
     * `also` 用于"创建任务的同时记下它"，等价于先赋值再返回任务对象。
     */
    suspend fun delete(albums: List<AlbumEntity>): DeleteResult {
        // 同一相册被重复选中时只删一次
        val selected = albums.distinctBy { it.id }
        if (selected.isEmpty()) return DeleteResult.Success

        // 在锁内完成"检查是否忙碌 + 创建任务"，避免两个调用同时通过检查
        val operation = operationMutex.withLock {
            if (activeJob?.isActive == true) {
                return DeleteResult.Busy
            }
            _state.value = DeleteState(
                isRunning = true,
                totalAlbums = selected.size
            )
            // also 等价于：val job = scope.async {...}; activeJob = job; job
            scope.async { deleteInternal(selected) }.also { activeJob = it }
        }
        return operation.await()
    }

    /**
     * 真正的删除循环。每个相册的步骤固定为：
     * 1. 删文件（[FileTreeDeleter]）；
     * 2. 文件删干净了才删数据库记录（[AlbumData]）。
     *
     * 顺序不能反：先删记录再删文件，一旦文件删除失败，用户会看到"相册不见了但磁盘文件还在"，
     * 而且这些文件下次同步会被当成新相册重新出现。
     */
    private suspend fun deleteInternal(albums: List<AlbumEntity>): DeleteResult {
        // 拉起闸门：让这批路径在此期间无法被扫描写库
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
                // 取消检查：被取消时直接抛 CancellationException，跳进下面的 catch
                currentCoroutineContext().ensureActive()
                _state.update {
                    it.copy(
                        currentAlbumName = album.name,
                        completedAlbums = completedAlbums,
                        deletedFiles = deletedFiles,
                        failedFiles = failedFiles
                    )
                }

                // 进度回调给出的是"本次目录已删除的文件数"，需要叠加到累计值上
                val treeResult = fileTreeDeleter.deleteTree(
                    File(DirectoryScanner.normalizePath(album.albumPath))
                ) { progressFiles ->
                    _state.update { it.copy(deletedFiles = deletedFiles + progressFiles) }
                }
                deletedFiles += treeResult.deletedFiles
                failedFiles += treeResult.failedFiles

                // 有文件删不掉就保留记录：宁可显示"删除失败"，也不能让磁盘上留下孤儿文件
                if (treeResult.hasBlockingFileFailure) {
                    failedAlbumIds += album.id
                    continue
                }

                try {
                    albumData.deleteAlbumsByIds(listOf(album.id))
                    completedAlbums++
                } catch (cancelled: CancellationException) {
                    // 协程规范：取消异常必须继续抛出，不能被当成普通失败吞掉
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
            // 被取消时保留已完成的进度，UI 能看到"删到哪一步停下"
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
            // 必须归还租约，否则这批路径会永远无法再同步
            deletionGate.endDeletion(lease)
        }
    }
}
