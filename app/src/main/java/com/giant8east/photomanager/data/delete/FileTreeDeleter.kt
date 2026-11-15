/** 文件树删除器：逐项删除目录内容，记录失败项，不因单个文件失败中止整次删除。 */
package com.giant8east.photomanager.data.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject

/**
 * 一次文件树删除的结果。
 *
 * 区分"文件失败"和"目录失败"是有业务意义的：
 * - 文件删不掉 → 目录里还有图片 → 数据库记录必须保留，否则用户会看到"相册消失但文件还在"；
 * - 只剩空目录删不掉 → 图片确实没了 → 记录可以删，残留空目录下次扫描会被当作无图片目录忽略。
 */
data class TreeDeleteResult(
    val deletedFiles: Long,
    val failedFiles: Int,
    val failedDirectories: Int,
    /** 失败路径采样，最多 64 条，用于提示而不是全量记录。 */
    val failedPaths: List<String>
) {
    /** 失败条目总数（文件 + 目录）；`get()` 表示每次访问都重新计算，不占字段。 */
    val failedEntries: Int get() = failedFiles + failedDirectories

    /** 是否存在"文件级"失败；调用方据此决定是否保留相册记录。 */
    val hasBlockingFileFailure: Boolean get() = failedFiles > 0
}

/**
 * 直接操作文件系统的删除工具，不碰数据库、不发通知。
 *
 * 交给 `DeleteManager` 编排：先由调用方拉起 [DeletionGate] 的删除标记，再调本类删文件，
 * 成功后才由调用方删除数据库记录。
 */
class FileTreeDeleter @Inject constructor() {
    /**
     * 删除 [root] 下的文件树。目录不存在视为已经删除；文件/目录删除失败只记录，不中断遍历。
     *
     * [onProgress] 每删除 [PROGRESS_STEP] 个文件回调一次，结束时再补一次最终值，
     * 避免每个文件都触发一次 UI 更新。
     */
    suspend fun deleteTree(
        root: File,
        onProgress: (deletedFiles: Long) -> Unit
    ): TreeDeleteResult {
        val rootPath = root.toPath()
        // 目录已被别人删掉：没有工作要做，也不是失败
        if (!Files.exists(rootPath)) {
            return TreeDeleteResult(
                deletedFiles = 0,
                failedFiles = 0,
                failedDirectories = 0,
                failedPaths = emptyList()
            )
        }

        // 在切到 IO 线程之前抓住**调用方**的协程上下文，
        // 这样用户在删除过程中取消时，下面每个回调里的 ensureActive() 都能感知到
        val context = currentCoroutineContext()
        var deletedFiles = 0L
        var failedFiles = 0
        var failedDirectories = 0
        val failedPaths = ArrayList<String>(64)

        /** 记录一次失败；采样上限避免删除上万文件时占用过多内存。 */
        fun recordFailure(path: Path, directory: Boolean) {
            if (directory) failedDirectories++ else failedFiles++
            if (failedPaths.size < MAX_FAILED_SAMPLE) {
                failedPaths += path.toString()
            }
        }

        withContext(Dispatchers.IO) {
            // walkFileTree 是深度优先：先 visitFile 处理子项，再 postVisitDirectory 处理目录本身。
            // 顺序不能反——非空目录无法删除，必须先清空内容。
            Files.walkFileTree(
                rootPath,
                object : SimpleFileVisitor<Path>() {
                    /** 处理普通文件：删掉它，失败也继续遍历下一个。 */
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes
                    ): FileVisitResult {
                        context.ensureActive()
                        try {
                            Files.deleteIfExists(file)
                            deletedFiles++
                            if (deletedFiles % PROGRESS_STEP == 0L) {
                                onProgress(deletedFiles)
                            }
                        } catch (_: NoSuchFileException) {
                            // 已被其他进程删除，目的已达到，视为成功。
                        } catch (_: IOException) {
                            recordFailure(file, directory = false)
                        } catch (_: SecurityException) {
                            recordFailure(file, directory = false)
                        }
                        // CONTINUE：单个文件失败不影响整次删除
                        return FileVisitResult.CONTINUE
                    }

                    /** 连文件都访问不到（权限、路径损坏等），只能计数，无法删除。 */
                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException
                    ): FileVisitResult {
                        context.ensureActive()
                        if (exc !is NoSuchFileException) {
                            recordFailure(
                                file,
                                // 这里拿不到类型，只能再查一次；查不到就当文件处理
                                directory = runCatching { Files.isDirectory(file) }.getOrDefault(
                                    false
                                )
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }

                    /** 子项处理完后删除目录本身；子项遍历有异常时不尝试删除。 */
                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?
                    ): FileVisitResult {
                        context.ensureActive()
                        if (exc != null) {
                            // 目录里可能还有没处理完的内容，删除大概率失败，直接记失败
                            if (exc !is NoSuchFileException) recordFailure(dir, directory = true)
                            return FileVisitResult.CONTINUE
                        }
                        try {
                            Files.deleteIfExists(dir)
                        } catch (_: NoSuchFileException) {
                            // 已被其他进程删除，视为成功。
                        } catch (_: IOException) {
                            recordFailure(dir, directory = true)
                        } catch (_: SecurityException) {
                            recordFailure(dir, directory = true)
                        }
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        }

        // 补发最终进度：文件数不是 PROGRESS_STEP 的整数倍时，界面也能拿到准确值
        onProgress(deletedFiles)
        return TreeDeleteResult(
            deletedFiles = deletedFiles,
            failedFiles = failedFiles,
            failedDirectories = failedDirectories,
            failedPaths = failedPaths
        )
    }

    private companion object {
        /** 进度回调间隔：每删除多少个文件上报一次。 */
        const val PROGRESS_STEP = 64L

        /** 失败路径采样上限，避免大目录删除时列表无限增长。 */
        const val MAX_FAILED_SAMPLE = 64
    }
}
