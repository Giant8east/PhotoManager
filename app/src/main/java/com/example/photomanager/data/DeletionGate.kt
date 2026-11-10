/** 删除与目录扫描写库之间的协调器，避免删除后的旧扫描结果重新写回相册记录。 */
package com.example.photomanager.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeletionGate @Inject constructor() {
    private val mutex = Mutex()
    private var epoch = 0L
    private val deletingRoots = mutableSetOf<String>()

    data class RefreshTicket(
        val path: String,
        val epoch: Long
    )

    data class DeletionLease(
        val roots: List<String>,
        val epoch: Long
    )

    /** 返回 null 表示该路径正在被删除，扫描结果不应写库。 */
    suspend fun beginRefresh(path: String): RefreshTicket? = mutex.withLock {
        val normalized = DirectoryScanner.normalizePath(path)
        if (deletingRoots.any { DirectoryScanner.isSameOrSubPath(it, normalized) }) {
            null
        } else {
            RefreshTicket(normalized, epoch)
        }
    }

    /**
     * 在删除屏障内提交扫描结果。ticket 过期或路径已进入删除集合时拒绝提交。
     */
    suspend fun commit(
        ticket: RefreshTicket,
        block: suspend () -> Unit
    ): Boolean = mutex.withLock {
        if (ticket.epoch != epoch ||
            deletingRoots.any { DirectoryScanner.isSameOrSubPath(it, ticket.path) }
        ) {
            false
        } else {
            block()
            true
        }
    }

    /**
     * 开始删除；递增 epoch 并使删除开始前取得的刷新 ticket 全部失效。
     * 文件删除本身不持有此 Mutex，只要求在删除标记期间不能提交扫描写库。
     */
    suspend fun beginDeletion(paths: List<String>): DeletionLease = mutex.withLock {
        val normalized = paths.map { DirectoryScanner.normalizePath(it) }.distinct()
        epoch++
        deletingRoots += normalized
        DeletionLease(normalized, epoch)
    }

    suspend fun endDeletion(lease: DeletionLease) = mutex.withLock {
        deletingRoots -= lease.roots.toSet()
    }
}
