/** 相册同步入口：先列出整棵目录树，再把每个文件夹排成扫描任务，最后清掉磁盘上已不存在的记录。 */
package com.giant8east.photomanager.data.album

import com.giant8east.photomanager.data.database.PhotoDatabase
import com.giant8east.photomanager.data.scan.DirectoryScanner
import com.giant8east.photomanager.data.task.FolderTask
import com.giant8east.photomanager.data.task.FolderTaskScheduler
import com.giant8east.photomanager.data.task.TaskKind
import com.giant8east.photomanager.data.task.TaskResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * 同步的唯一入口，分三个阶段，各管一件事：
 * 1. 遍历：只列出目录清单（[DirectoryScanner.collectDirectories]），不写库；
 * 2. 扫描：一个目录一个 [TaskKind.SCAN_REFRESH] 任务，任务之间平级，谁先跑都行；
 * 3. 清理：清单里没有、数据库里还留着的路径，说明目录已经被删掉了，直接删记录。
 *
 * 这样调度器只需要"入队 + 返回句柄"，同步入口只等待自己这一批句柄全部结束（不依赖全局队列空），
 * 删除与同步抢同一个文件夹时，仍由调度器的路径占用决定谁先来，而不是各写各的。
 */
@Singleton
class AlbumSyncData @Inject constructor(
    private val database: PhotoDatabase,
    private val directoryScanner: DirectoryScanner,
    private val scheduler: FolderTaskScheduler
) {
    /** 同步一个扫描根目录：列出整棵树 → 逐目录扫 → 清理消失的记录。 */
    suspend fun syncDirectory(rootPath: String) = withContext(Dispatchers.IO) {
        val normalizedRoot = DirectoryScanner.normalizePath(rootPath)
        val directories = directoryScanner.collectDirectories(normalizedRoot)
        // 只等待本次同步入队的这一批任务句柄完成，避免受其他来源的 DATA 车道任务串扰
        val handles = directories.map { directory ->
            scheduler.enqueue(FolderTask(directory, TaskKind.SCAN_REFRESH))
        }
        handles.awaitAll()
        unindexVanished(normalizedRoot, directories)
    }

    /** 单相册刷新入口：返回刷新后目录是否仍有图片。 */
    suspend fun refreshAlbum(path: String): Boolean {
        val task = FolderTask(DirectoryScanner.normalizePath(path), TaskKind.SCAN_REFRESH)
        return scheduler.enqueue(task).await() is TaskResult.Done
    }

    /**
     * 移除一个扫描根在索引里的全部记录，等任务真正执行完再返回。
     *
     * 只删记录、不碰文件，所以用 [TaskKind.UNINDEX]（子树占用）：它会把同一路径下排队中的扫描
     * 任务作废，避免扫描把刚删掉的记录又写回来。
     */
    suspend fun unindexDirectory(path: String) {
        val task = FolderTask(DirectoryScanner.normalizePath(path), TaskKind.UNINDEX)
        scheduler.enqueue(task).await()
    }

    /**
     * 删掉"目录已经不存在、数据库里还留着"的记录。
     *
     * 目录清单是本次遍历的快照，清单里没有的相册路径只可能是磁盘上已经删掉的，
     * 所以这里能一次清干净整棵子树，不用每个文件夹各对一遍。
     *
     * 清单为空说明根目录本身没读到（被删掉、存储卡掉线或权限抖动）：这时一条记录都不动。
     * 相册记录里带着收藏、分组、排序和阅读位置，误删比留下几条陈旧记录代价大得多；
     * 真的删掉扫描根目录时走的是 SyncManager 的移除流程，不靠这里兜底。
     */
    private suspend fun unindexVanished(root: String, directories: List<String>) {
        if (directories.isEmpty()) return

        val onDisk = directories.toHashSet()
        val stalePaths = database.albumDao()
            .queryAlbumPathsUnderPath(root, DirectoryScanner.childPathPrefix(root))
            .filter { it !in onDisk }
        for (path in stalePaths) {
            database.albumDao().deleteAlbumByPath(path)
        }
    }
}
