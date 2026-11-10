/** 文件树删除器：逐项删除目录内容，记录失败项，不因单个文件失败中止整次删除。 */
package com.example.photomanager.data

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

data class TreeDeleteResult(
    val deletedFiles: Long,
    val failedFiles: Int,
    val failedDirectories: Int,
    val failedPaths: List<String>
) {
    val failedEntries: Int get() = failedFiles + failedDirectories
    val hasBlockingFileFailure: Boolean get() = failedFiles > 0
}

class FileTreeDeleter @Inject constructor() {
    /**
     * 删除 [root] 下的文件树。目录不存在视为已经删除；文件/目录删除失败只记录，不中断遍历。
     */
    suspend fun deleteTree(
        root: File,
        onProgress: (deletedFiles: Long) -> Unit
    ): TreeDeleteResult {
        val rootPath = root.toPath()
        if (!Files.exists(rootPath)) {
            return TreeDeleteResult(
                deletedFiles = 0,
                failedFiles = 0,
                failedDirectories = 0,
                failedPaths = emptyList()
            )
        }

        val context = currentCoroutineContext()
        var deletedFiles = 0L
        var failedFiles = 0
        var failedDirectories = 0
        val failedPaths = ArrayList<String>(64)

        fun recordFailure(path: Path, directory: Boolean) {
            if (directory) failedDirectories++ else failedFiles++
            if (failedPaths.size < MAX_FAILED_SAMPLE) {
                failedPaths += path.toString()
            }
        }

        withContext(Dispatchers.IO) {
            Files.walkFileTree(
                rootPath,
                object : SimpleFileVisitor<Path>() {
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
                            // 已被其他进程删除，视为成功。
                        } catch (_: IOException) {
                            recordFailure(file, directory = false)
                        } catch (_: SecurityException) {
                            recordFailure(file, directory = false)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exc: IOException
                    ): FileVisitResult {
                        context.ensureActive()
                        if (exc !is NoSuchFileException) {
                            recordFailure(
                                file,
                                directory = runCatching { Files.isDirectory(file) }.getOrDefault(
                                    false
                                )
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        exc: IOException?
                    ): FileVisitResult {
                        context.ensureActive()
                        if (exc != null) {
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

        onProgress(deletedFiles)
        return TreeDeleteResult(
            deletedFiles = deletedFiles,
            failedFiles = failedFiles,
            failedDirectories = failedDirectories,
            failedPaths = failedPaths
        )
    }

    private companion object {
        const val PROGRESS_STEP = 64L
        const val MAX_FAILED_SAMPLE = 64
    }
}
