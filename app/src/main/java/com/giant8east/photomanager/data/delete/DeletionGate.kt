/** 删除与目录扫描写库之间的协调器，避免删除后的旧扫描结果重新写回相册记录。 */
package com.giant8east.photomanager.data.delete

import com.giant8east.photomanager.data.scan.DirectoryScanner

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 删除闸门：把"扫描写库"和"删除"这两条路径互斥起来。
 *
 * 要解决的问题（没有闸门时的时序）：
 * ```
 * t0  同步协程 beginRefresh(/albums/a)   → 拿到票据
 * t1  同步协程 开始扫盘（200ms）…
 * t2  用户   删除 /albums/a → 文件删光、数据库记录删除
 * t3  同步协程 扫完，按扫描结果写库       → 已删除的相册又回来了 ❌
 * ```
 *
 * 解法是两段式检查：
 * 1. 扫描开始前 [beginRefresh] 领票据；路径正被删除则返回 null，不扫；
 * 2. 写库前 [commit] 再检查一次票据是否还有效，无效则放弃写入。
 *
 * 两个字段分别负责两种失效：
 * - [epoch]：每次删除 +1，让**所有**更早取得的票据整体作废（保守但简单）；
 * - [deletingRoots]：记录正在删除的根路径，命中"同一路径或子路径"也作废。
 *
 * 锁只在"检查"和"开始/结束删除"的瞬间持有；真正删文件的过程不持锁
 * （见 [beginDeletion] 注释），否则一次几秒的删除会卡住整个同步流程。
 */
@Singleton
class DeletionGate @Inject constructor() {
    private val mutex = Mutex()

    /** 删除代数：每次 [beginDeletion] 自增，用来判定旧票据是否过期。只增不减。 */
    private var epoch = 0L

    /** 正在删除的根路径集合；[endDeletion] 后移除。 */
    private val deletingRoots = mutableSetOf<String>()

    /** 扫描者在开始扫盘前领到的号码牌，带着当时的 [epoch]。 */
    data class RefreshTicket(
        val path: String,
        val epoch: Long
    )

    /** 删除者持有的租约；[endDeletion] 时凭它释放 [deletingRoots]。 */
    data class DeletionLease(
        val roots: List<String>,
        val epoch: Long
    )

    /**
     * 扫描开始前调用：返回 null 表示该路径（或它的祖先目录）正在被删除，
     * 调用方应当跳过扫描与写库，而不是继续干活。
     *
     * `any { ... }` 等价于 `for (root in deletingRoots) { if (判断) return null }`。
     */
    suspend fun beginRefresh(path: String): RefreshTicket? = mutex.withLock {
        val normalized = DirectoryScanner.normalizePath(path)
        // isSameOrSubPath(父, 子)：删 /albums/a 时，扫 /albums/a/sub 也会被拦住
        if (deletingRoots.any { DirectoryScanner.isSameOrSubPath(it, normalized) }) {
            null
        } else {
            RefreshTicket(normalized, epoch)
        }
    }

    /**
     * 在删除屏障内提交扫描结果。票据过期或路径已进入删除集合时拒绝提交。
     *
     * 注意 [block] 是在**持锁状态**下执行的：这保证"写库"与"开始删除"不可能交错，
     * 否则会出现"检查通过 → 释放锁 → 删除开始 → 仍然写库"的窗口，闸门就失效了。
     * 代价是写库操作被串行化；它们都是毫秒级操作，可以接受。
     *
     * @return true 表示已提交；false 表示结果已过期，调用方应当放弃这次扫描结果。
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
     *
     * 调用方必须用 try/finally 保证 [endDeletion] 一定执行：否则该路径会一直留在
     * [deletingRoots] 里，之后永远无法再同步（beginRefresh 一直返回 null）。
     */
    suspend fun beginDeletion(paths: List<String>): DeletionLease = mutex.withLock {
        // distinct() 去掉重复路径，避免 endDeletion 时误删别人加进来的同名标记
        val normalized = paths.map { DirectoryScanner.normalizePath(it) }.distinct()
        epoch++
        deletingRoots += normalized
        DeletionLease(normalized, epoch)
    }

    /** 归还租约：只移除本次删除标记的路径，不动 [epoch]（旧票据应当永久失效）。 */
    suspend fun endDeletion(lease: DeletionLease) = mutex.withLock {
        deletingRoots -= lease.roots.toSet()
    }
}
