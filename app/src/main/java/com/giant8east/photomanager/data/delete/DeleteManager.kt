/** 应用级相册删除协调器：把删除拆成调度任务，订阅进度并给出最终结论。 */
package com.giant8east.photomanager.data.delete

import com.giant8east.photomanager.data.database.AlbumEntity
import com.giant8east.photomanager.data.scan.DirectoryScanner
import com.giant8east.photomanager.data.task.FolderTask
import com.giant8east.photomanager.data.task.FolderTaskScheduler
import com.giant8east.photomanager.data.task.TaskKind
import com.giant8east.photomanager.data.task.TaskResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** 部分相册没删干净：成功 [deletedAlbums] 个，失败 [failedAlbums] 个。 */
    data class Partial(
        val deletedAlbums: Int,
        val failedAlbums: Int,
    ) : DeleteResult

    /** 整体失败（异常或取消）。 */
    data class Failure(val message: String?) : DeleteResult
}

/**
 * 删除流程的应用级协调器。
 *
 * 它不再自己删文件、也不自己删记录，只做三件事：
 * 1. 给每个相册排一个 [TaskKind.DELETE_FILES] 任务；
 * 2. 订阅调度器的文件进度，把数字翻译成界面状态；
 * 3. 任务结束以后按"这个文件夹的图片有没有删干净"给出结论。
 *
 * 判断依据是文件夹本身，不是数据库：记录是扫描写出来的结果，
 * 拿它当验收标准等于用"派生物"去判断"真实物"，删不掉的文件反而会被掩盖。
 *
 * 为什么任务跑在应用级 [scope] 而不是调用方的 viewModelScope：
 * 删除已经开始就无法回滚，页面退出时中断只会留下"文件删了一半、记录还在"的状态，
 * 所以调用方取消不应影响调度器里的任务本身；UI 通过 [state] 观察进度。
 */
@Singleton
class DeleteManager @Inject constructor(
    private val scheduler: FolderTaskScheduler
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
     * 真正的删除循环：每个相册排一个删除任务，等它连同接手的扫描任务一起结束。
     *
     * 结论完全来自每个任务的返回值：任务说没删干净（有图片删不掉、扫描写库失败）
     * 或者根本没执行（排队时被更高优先级任务顶掉），就把这个相册记为失败；
     * 只有 Done / Skipped 才算删干净。
     */
    private suspend fun deleteInternal(albums: List<AlbumEntity>): DeleteResult {
        var deletedFiles = 0L
        var failedFiles = 0
        var completedAlbums = 0
        var failureMessage: String? = null
        var failedAlbums = 0

        _state.value = DeleteState(isRunning = true, totalAlbums = albums.size)

        try {
            for (album in albums) {
                // 取消检查：被取消时直接抛 CancellationException，跳进下面的 catch
                currentCoroutineContext().ensureActive()
                val path = DirectoryScanner.normalizePath(album.albumPath)
                _state.update {
                    it.copy(
                        currentAlbumName = album.name,
                        completedAlbums = completedAlbums,
                        deletedFiles = deletedFiles,
                        failedFiles = failedFiles
                    )
                }

                val handle = scheduler.enqueue(FolderTask(path, TaskKind.DELETE_FILES))
                val result = awaitWithProgress(handle, path, deletedFiles, failedFiles)

                // 结果里带的是"这一个文件夹删了多少"，累加后作为已完成部分的基准
                deletedFiles += result.deletedFileCount
                failedFiles += result.failedFileCount
                when (result) {
                    is TaskResult.Failed -> {
                        failedAlbums++
                        failureMessage = result.message ?: failureMessage
                    }
                    // 排队期间被更高优先级的任务（例如移除扫描根）顶掉，文件一个都没动，不能算删除成功
                    TaskResult.Cancelled -> failedAlbums++
                    // Done 表示删完并扫描过了；Skipped 表示扫描发现目录里已经没有图片，两种都是正常结束
                    is TaskResult.Done, TaskResult.Skipped -> completedAlbums++
                }
            }

            val result = if (failedAlbums == 0) {
                DeleteResult.Success
            } else {
                DeleteResult.Partial(
                    deletedAlbums = completedAlbums,
                    failedAlbums = failedAlbums,
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
        }
    }

    /**
     * 等一个删除任务结束，同时把调度器上报的文件进度翻译成界面状态。
     *
     * 进度是"这个文件夹已经删了多少"，所以要叠加到 [deletedFiles] / [failedFiles] 基准上。
     */
    private suspend fun awaitWithProgress(
        handle: Deferred<TaskResult>,
        path: String,
        deletedFiles: Long,
        failedFiles: Int
    ): TaskResult = coroutineScope {
        val progressJob = launch {
            scheduler.fileProgress.collect { progress ->
                if (progress.path != path) return@collect
                _state.update {
                    it.copy(
                        deletedFiles = deletedFiles + progress.deletedFiles,
                        failedFiles = failedFiles + progress.failedFiles
                    )
                }
            }
        }
        val result = handle.await()
        progressJob.cancel()
        result
    }
}
