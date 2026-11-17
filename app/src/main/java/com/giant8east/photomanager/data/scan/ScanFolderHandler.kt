/** 扫描任务：把一个文件夹读进数据库；扫哪些文件夹由同步入口事先算好，任务之间互不派生。 */
package com.giant8east.photomanager.data.scan

import com.giant8east.photomanager.data.album.AlbumIndexer
import com.giant8east.photomanager.data.task.FolderTask
import com.giant8east.photomanager.data.task.FolderTaskHandler
import com.giant8east.photomanager.data.task.TaskContext
import com.giant8east.photomanager.data.task.TaskResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanFolderHandler @Inject constructor(
    private val albumIndexer: AlbumIndexer
) : FolderTaskHandler {

    override suspend fun handle(task: FolderTask, context: TaskContext): TaskResult =
        if (albumIndexer.scanAndWrite(task.path)) {
            TaskResult.Done()
        } else {
            // 这一层没有图片，记录已经删掉；对调用它的删除任务来说这就是正常结束
            TaskResult.Skipped
        }
}
