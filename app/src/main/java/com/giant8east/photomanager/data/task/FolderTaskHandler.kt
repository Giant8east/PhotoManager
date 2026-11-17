/** 任务处理器与执行上下文：处理器只回答"这个文件夹要做什么"，排队与路径占用交给调度器。 */
package com.giant8east.photomanager.data.task

import kotlinx.coroutines.Deferred

/**
 * 处理器在执行过程中能对调度器做的三件事。
 *
 * 处理器拿不到队列本身，只能通过这里申请"再排一个任务"或上报进度，
 * 这样调度器始终是唯一修改队列与路径占用的地方。
 */
interface TaskContext {
    /** 追加一个任务并返回句柄；当前任务仍持有自己的路径占用。 */
    suspend fun enqueue(task: FolderTask): Deferred<TaskResult>

    /**
     * 把当前任务的路径占用移交给下一个任务，并等待它结束。
     *
     * 删除文件后必须让扫描接手同一个文件夹：先移交占用再等待，
     * 否则扫描任务会因为"同一个路径被自己占着"而永远轮不到，直接死锁。
     */
    suspend fun handOff(task: FolderTask): Deferred<TaskResult>

    /** 上报当前任务的文件进度，供进度弹窗显示；只有文件车道的任务会被采用。 */
    fun reportFileProgress(deletedFiles: Long, failedFiles: Int)
}

/** 每种 [TaskKind] 对应一个处理器；由依赖注入按类型装配成映射。 */
interface FolderTaskHandler {
    suspend fun handle(task: FolderTask, context: TaskContext): TaskResult
}
