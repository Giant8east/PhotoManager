/** 删除文件任务：删掉本层图片与点文件，然后把这个文件夹移交给扫描任务。 */
package com.giant8east.photomanager.data.delete

import com.giant8east.photomanager.data.task.FolderTask
import com.giant8east.photomanager.data.task.FolderTaskHandler
import com.giant8east.photomanager.data.task.TaskContext
import com.giant8east.photomanager.data.task.TaskKind
import com.giant8east.photomanager.data.task.TaskResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeleteFilesHandler @Inject constructor(
    private val fileDeleter: FolderFileDeleter
) : FolderTaskHandler {

    override suspend fun handle(task: FolderTask, context: TaskContext): TaskResult {
        val local = fileDeleter.deleteLocalFiles(File(task.path)) { deletedFiles, failedFiles ->
            context.reportFileProgress(deletedFiles, failedFiles)
        }

        // 文件删完就移交扫描：记录该不该消失由扫描判断（扫描是唯一写数据库的任务）。
        // 必须先移交占用再等待，否则扫描会因为"路径被当前任务占着"永远轮不到。
        val scan = context.handOff(FolderTask(task.path, TaskKind.SCAN_REFRESH))
        val scanResult = scan.await()

        return when {
            local.hasBlockingFileFailure ->
                TaskResult.Failed(null, local.deletedFiles, local.failedFiles)
            scanResult is TaskResult.Failed ->
                TaskResult.Failed(scanResult.message, local.deletedFiles, local.failedFiles)
            else -> TaskResult.Done(local.deletedFiles, local.failedFiles)
        }
    }
}
