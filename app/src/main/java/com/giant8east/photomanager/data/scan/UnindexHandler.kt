/** 移除索引任务：只删数据库记录（含子目录），一个磁盘文件都不碰。 */
package com.giant8east.photomanager.data.scan

import com.giant8east.photomanager.data.album.AlbumData
import com.giant8east.photomanager.data.task.FolderTask
import com.giant8east.photomanager.data.task.FolderTaskHandler
import com.giant8east.photomanager.data.task.TaskContext
import com.giant8east.photomanager.data.task.TaskResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用在"移除扫描目录"：配置里已经不存在这个目录，它的相册记录也应该消失。
 * 注意它与删除不同——文件仍然留在磁盘上，只是不再被本应用索引。
 */
@Singleton
class UnindexHandler @Inject constructor(
    private val albumData: AlbumData
) : FolderTaskHandler {

    override suspend fun handle(task: FolderTask, context: TaskContext): TaskResult {
        albumData.unindexAlbumsUnderPath(task.path)
        return TaskResult.Done()
    }
}
