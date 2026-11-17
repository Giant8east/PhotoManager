/** 单层文件删除器：只删这个文件夹本层的图片与点文件，不进入子目录。 */
package com.giant8east.photomanager.data.delete

import com.giant8east.photomanager.data.media.PhotoFormat
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 本层删除结果。
 *
 * [failedFiles] 大于 0 表示有图片没删掉：这时不能动相册记录，否则下次同步会把它们当新相册重新扫出来。
 */
data class LocalDeleteResult(
    val deletedFiles: Long,
    val failedFiles: Int
) {
    /** 有图片删不掉时，记录必须保留。 */
    val hasBlockingFileFailure: Boolean get() = failedFiles > 0
}

/**
 * 删除一个文件夹本层的文件。
 *
 * 为什么不再递归：相册的最小单位是文件夹，子目录是别的相册。
 * 递归删子树会连带删掉子相册的图片，而数据库只删父相册一行，留下"文件没了、记录还在"的孤儿记录。
 * 所以这里只处理本层，子目录交给它自己的删除任务。
 *
 * 目录本身只有在本层文件都删干净、且没有子目录（即真的空了）时才删除；
 * 只要还有子相册，父目录就必须留着。
 */
@Singleton
class FolderFileDeleter @Inject constructor() {

    suspend fun deleteLocalFiles(
        root: File,
        onProgress: (deletedFiles: Long, failedFiles: Int) -> Unit
    ): LocalDeleteResult = withContext(Dispatchers.IO) {
        // 先拿到当前协程再切线程：扫描/删除中途被取消时要能抛出去
        val context = currentCoroutineContext()
        // 目录已经不在了：没有文件可删，也不算失败（记录交给后面的扫描清掉）
        if (!root.isDirectory) {
            return@withContext LocalDeleteResult(0, 0)
        }
        // listFiles 返回 null 表示有读权限问题：按一个失败上报，不能当成"删干净了"
        val entries = root.listFiles()
            ?: return@withContext LocalDeleteResult(0, 1)

        var deletedFiles = 0L
        var failedFiles = 0

        for (entry in entries) {
            context.ensureActive()
            // 子目录属于别的相册，本层不进去
            if (entry.isDirectory) continue
            if (!isDeletableFileName(entry.name)) continue

            if (deleteFile(entry)) {
                deletedFiles++
            } else {
                failedFiles++
            }
            // 每删一个都回报：调用方是 StateFlow，重复写入会被合并，界面最多每帧刷新一次
            onProgress(deletedFiles, failedFiles)
        }

        // 本层删空且没有子目录，才把这个空壳目录也删掉
        if (failedFiles == 0 && root.listFiles()?.isEmpty() == true) {
            root.delete()
        }

        LocalDeleteResult(deletedFiles, failedFiles)
    }

    /** 图片按扩展名判断（与相册扫描的判据一致），另外所有点文件也算可删（含 .nomedia / .thumb）。 */
    private fun isDeletableFileName(name: String): Boolean =
        name.startsWith(".") || PhotoFormat.fromFileName(name) != PhotoFormat.OTHER

    /** 删除单个文件；失败（权限、占用）由调用方计数并上报，不在这里吞掉。 */
    private fun deleteFile(file: File): Boolean =
        runCatching { file.delete() }.getOrDefault(false)
}
