/** 文件夹任务调度器：唯一决定"哪个文件夹此刻归哪个任务"的地方。 */
package com.giant8east.photomanager.data.task

import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 文件车道的实时进度；同一时刻最多一个删除任务，所以只需要一份。 */
data class FileProgress(
    val path: String? = null,
    val deletedFiles: Long = 0,
    val failedFiles: Int = 0
)

/**
 * 按文件夹调度的任务队列。
 *
 * 三条规则：
 * 1. 同一时刻一个文件夹只能被一个任务占用（[ActiveClaim]），跨车道也一样；
 * 2. 同一路径在队列里只排一个任务，新任务优先（低优先级的旧任务直接作废）；
 * 3. 高优先级任务拿到路径时，队列里被它覆盖的低优先级任务直接作废。
 *
 * 每个车道有独立的唤醒通道：文件车道的删除阻塞住，不会挡住数据车道的扫描。
 */
@Singleton
class FolderTaskScheduler @Inject constructor(
    private val handlers: Map<TaskKind, @JvmSuppressWildcards FolderTaskHandler>
) {
    /** 排队中的任务；[deferred] 在任务结束（或被作废）时完成。 */
    private class QueuedTask(val task: FolderTask, val deferred: CompletableDeferred<TaskResult>)

    /** 正在运行的任务对路径的占用。 */
    private class ActiveClaim(val path: String, val scope: ClaimScope, val kind: TaskKind)

    private class Dispatch(val queued: QueuedTask, val claim: ActiveClaim)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 一把锁保护下面所有可变状态；所有读取与修改都必须持有它。 */
    private val mutex = Mutex()

    private val wakeups = EnumMap<TaskLane, Channel<Unit>>(TaskLane::class.java).apply {
        TaskLane.entries.forEach { lane -> put(lane, Channel(Channel.CONFLATED)) }
    }

    private val pending = mutableListOf<QueuedTask>()

    private val claims = mutableListOf<ActiveClaim>()

    private var workersStarted = false

    private val _fileProgress = MutableStateFlow(FileProgress())

    /** 当前删除任务的实时进度，UI 只读订阅。 */
    val fileProgress: StateFlow<FileProgress> = _fileProgress.asStateFlow()

    /**
     * 排队一个任务并返回句柄；同一路径已有同级或更高优先级的任务时，复用那个任务的句柄。
     */
    suspend fun enqueue(task: FolderTask): Deferred<TaskResult> {
        val deferred = CompletableDeferred<TaskResult>()
        val handle = mutex.withLock {
            startWorkersLocked()
            enqueueLocked(task, deferred)
        }
        wakeups.getValue(task.kind.lane).trySend(Unit)
        return handle
    }

    /**
     * 把任务放进队列，保证"同一路径只排一个任务"。
     *
     * 已有同级或更高优先级的任务时直接复用它的句柄——本次这个任务不再执行，
     * 调用方等到的就是那个已在队列里的任务的结果。
     * 只有更高优先级的任务能顶掉排队的低优先级任务，被顶掉的那个以 [TaskResult.Cancelled] 结束。
     *
     * 调用方必须已经持有 [mutex]：入队与去重必须是一个原子步骤，否则两个并发入队会都通过检查。
     */
    private fun enqueueLocked(
        task: FolderTask,
        deferred: CompletableDeferred<TaskResult>
    ): Deferred<TaskResult> {
        val existingIndex = pending.indexOfFirst { it.task.path == task.path }
        if (existingIndex >= 0) {
            val existing = pending[existingIndex]
            if (existing.task.kind.priority >= task.kind.priority) {
                return existing.deferred
            }
            pending.removeAt(existingIndex)
            existing.deferred.complete(TaskResult.Cancelled)
        }
        pending.add(QueuedTask(task, deferred))
        return deferred
    }

    /** 首次入队时启动 worker；等价于 if (workersStarted) return; workersStarted = true; ... */
    private fun startWorkersLocked() {
        if (workersStarted) return
        workersStarted = true
        repeat(FILE_WORKERS) { scope.launch { worker(TaskLane.FILES) } }
        repeat(DATA_WORKERS) { scope.launch { worker(TaskLane.DATA) } }
    }

    private suspend fun worker(lane: TaskLane) {
        val wakeup = wakeups.getValue(lane)
        while (true) {
            wakeup.receive()
            // 一次唤醒尽量把自己的车道清空；领不到任务（都被占用）就回去等下一次唤醒
            while (true) {
                val dispatch = mutex.withLock { claimNextLocked(lane) } ?: break
                runTask(dispatch)
            }
        }
    }

    /** 领出本车道当前可执行、优先级最高的任务；没有可执行的返回 null。 */
    private fun claimNextLocked(lane: TaskLane): Dispatch? {
        var bestIndex = -1
        for (index in pending.indices) {
            val task = pending[index].task
            if (task.kind.lane != lane) continue
            if (hasConflictLocked(task)) continue
            if (bestIndex < 0 || task.kind.priority > pending[bestIndex].task.kind.priority) {
                bestIndex = index
            }
        }
        if (bestIndex < 0) return null

        val queued = pending.removeAt(bestIndex)
        val claim = ActiveClaim(queued.task.path, queued.task.kind.claimScope, queued.task.kind)
        claims.add(claim)
        if (queued.task.kind.lane == TaskLane.FILES) {
            _fileProgress.value = FileProgress(path = queued.task.path)
        }
        // 删除/移除索引拿到路径后，队列里被它覆盖的扫描任务已经没有意义
        if (queued.task.kind == TaskKind.DELETE_FILES || queued.task.kind == TaskKind.UNINDEX) {
            dropCoveredLocked(queued.task)
        }
        return Dispatch(queued, claim)
    }

    private suspend fun runTask(dispatch: Dispatch) {
        val task = dispatch.queued.task
        val handler = handlers[task.kind]
        val result = try {
            handler?.handle(task, SchedulerContext(task)) ?: TaskResult.Failed("没有注册的任务处理器：${task.kind}")
        } catch (cancelled: CancellationException) {
            // 协程取消必须继续抛出，不能让取消被当成普通失败吞掉
            finish(dispatch, TaskResult.Cancelled)
            throw cancelled
        } catch (error: Exception) {
            TaskResult.Failed(error.message)
        }
        finish(dispatch, result)
    }

    private suspend fun finish(dispatch: Dispatch, result: TaskResult) {
        mutex.withLock {
            // 处理器可能已经通过 handOff 交出了占用，这里的移除是幂等的
            claims.remove(dispatch.claim)
            dispatch.queued.deferred.complete(result)
        }
        // 占用释放后其他车道可能就有活干了，全部唤醒一次
        wakeups.values.forEach { it.trySend(Unit) }
    }

    /** 路径占用是否与正在运行的任务冲突。 */
    private fun hasConflictLocked(task: FolderTask): Boolean =
        claims.any { conflict(it.path, it.scope, task.path, task.kind.claimScope) }

    private fun conflict(
        runningPath: String,
        runningScope: ClaimScope,
        otherPath: String,
        otherScope: ClaimScope
    ): Boolean = when {
        runningScope == ClaimScope.SELF && otherScope == ClaimScope.SELF -> runningPath == otherPath
        runningScope == ClaimScope.SELF -> isSameOrChildPath(otherPath, runningPath)
        otherScope == ClaimScope.SELF -> isSameOrChildPath(runningPath, otherPath)
        else -> isSameOrChildPath(runningPath, otherPath) || isSameOrChildPath(otherPath, runningPath)
    }

    /** 队列里凡是会被 [task] 覆盖的，直接作废；判断只看字符串前缀，路径入队前已规范化。 */
    private fun dropCoveredLocked(task: FolderTask) {
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val queued = iterator.next()
            val covered = when (task.kind.claimScope) {
                ClaimScope.SELF -> queued.task.path == task.path
                ClaimScope.SUBTREE -> isSameOrChildPath(task.path, queued.task.path)
            }
            if (covered) {
                iterator.remove()
                queued.deferred.complete(TaskResult.Cancelled)
            }
        }
    }

    /** 处理器看到的调度器接口；只能申请任务和上报进度，碰不到队列本身。 */
    private inner class SchedulerContext(private val task: FolderTask) : TaskContext {
        override suspend fun enqueue(task: FolderTask): Deferred<TaskResult> =
            this@FolderTaskScheduler.enqueue(task)

        override suspend fun handOff(task: FolderTask): Deferred<TaskResult> {
            val deferred = CompletableDeferred<TaskResult>()
            val handle = mutex.withLock {
                // 先交出占用，让接手同一个文件夹的任务能立刻被调度
                claims.removeAll { it.path == this.task.path && it.kind == this.task.kind }
                // 交接同样要走去重：这条路径上可能已经排着详情刷新，不能再排第二个扫描
                enqueueLocked(task, deferred)
            }
            wakeups.values.forEach { it.trySend(Unit) }
            return handle
        }

        override fun reportFileProgress(deletedFiles: Long, failedFiles: Int) {
            _fileProgress.value = FileProgress(
                path = task.path,
                deletedFiles = deletedFiles,
                failedFiles = failedFiles
            )
        }
    }

    private companion object {
        /** 删除串行：同时只跑一个删除任务，进度与失败原因都能对上。 */
        const val FILE_WORKERS = 1

        /** 扫描与移除索引并发上限，避免大量文件夹同时抢 Room 事务。 */
        const val DATA_WORKERS = 4

        /** 判断 [child] 是否是 [parent] 自己或它的子目录；只比较字符串，路径必须已规范化。 */
        fun isSameOrChildPath(parent: String, child: String): Boolean {
            if (child == parent) return true
            val prefix = if (parent.endsWith("/")) parent else "$parent/"
            return child.startsWith(prefix)
        }
    }
}
