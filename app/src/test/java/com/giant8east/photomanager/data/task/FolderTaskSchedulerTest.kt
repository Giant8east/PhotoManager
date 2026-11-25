package com.giant8east.photomanager.data.task

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderTaskSchedulerTest {

    private fun handler(
        block: suspend (FolderTask, TaskContext) -> TaskResult
    ): FolderTaskHandler = object : FolderTaskHandler {
        override suspend fun handle(task: FolderTask, context: TaskContext): TaskResult =
            block(task, context)
    }

    @Test
    fun samePathAndKindReusesPendingDeferred() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val scanRuns = AtomicInteger(0)

        val scheduler = FolderTaskScheduler(
            mapOf(
                TaskKind.DELETE_FILES to handler { _, _ ->
                    started.complete(Unit)
                    gate.await()
                    TaskResult.Done(deletedFiles = 1)
                },
                TaskKind.SCAN_REFRESH to handler { _, _ ->
                    scanRuns.incrementAndGet()
                    TaskResult.Done()
                }
            )
        )

        // 先用删除任务占住 /album，确保后续两个扫描任务停在 pending 队列里触发去重
        val deleteHandle = scheduler.enqueue(FolderTask("/album", TaskKind.DELETE_FILES))
        started.await()

        val scan1 = scheduler.enqueue(FolderTask("/album", TaskKind.SCAN_REFRESH))
        val scan2 = scheduler.enqueue(FolderTask("/album", TaskKind.SCAN_REFRESH))

        gate.complete(Unit)

        assertTrue(deleteHandle.await() is TaskResult.Done)
        assertTrue(scan1.await() is TaskResult.Done)
        assertTrue(scan2.await() is TaskResult.Done)
        assertEquals(1, scanRuns.get())
    }

    @Test
    fun differentKindWithHigherPriorityPendingDoesNotReuseDeferred() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val deleteRuns = AtomicInteger(0)

        val scheduler = FolderTaskScheduler(
            mapOf(
                TaskKind.SCAN_REFRESH to handler { _, _ ->
                    started.complete(Unit)
                    gate.await()
                    TaskResult.Done()
                },
                TaskKind.UNINDEX to handler { _, _ ->
                    TaskResult.Done()
                },
                TaskKind.DELETE_FILES to handler { _, _ ->
                    deleteRuns.incrementAndGet()
                    TaskResult.Done(deletedFiles = 5)
                }
            )
        )

        // 先用扫描占住 /album，让 UNINDEX（优先级 30）留在 pending 队列里
        val runningScan = scheduler.enqueue(FolderTask("/album", TaskKind.SCAN_REFRESH))
        started.await()

        val unindexHandle = scheduler.enqueue(FolderTask("/album", TaskKind.UNINDEX))
        // 此时再排 DELETE_FILES（优先级 20）：不能复用 UNINDEX 的 Done，应直接 Cancelled
        val deleteHandle = scheduler.enqueue(FolderTask("/album", TaskKind.DELETE_FILES))

        assertEquals(TaskResult.Cancelled, deleteHandle.await())

        gate.complete(Unit)
        assertTrue(runningScan.await() is TaskResult.Done)
        assertTrue(unindexHandle.await() is TaskResult.Done)
        assertEquals(0, deleteRuns.get())
    }

    @Test
    fun higherPriorityPreemptsLowerPriorityPendingTask() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val scanRuns = AtomicInteger(0)

        val scheduler = FolderTaskScheduler(
            mapOf(
                TaskKind.DELETE_FILES to handler { _, _ ->
                    started.complete(Unit)
                    gate.await()
                    TaskResult.Done(deletedFiles = 3)
                },
                TaskKind.SCAN_REFRESH to handler { _, _ ->
                    scanRuns.incrementAndGet()
                    TaskResult.Done()
                },
                TaskKind.UNINDEX to handler { _, _ ->
                    TaskResult.Done()
                }
            )
        )

        val runningDelete = scheduler.enqueue(FolderTask("/album", TaskKind.DELETE_FILES))
        started.await()

        val pendingScan = scheduler.enqueue(FolderTask("/album", TaskKind.SCAN_REFRESH))
        val unindex = scheduler.enqueue(FolderTask("/album", TaskKind.UNINDEX))

        assertEquals(TaskResult.Cancelled, pendingScan.await())

        gate.complete(Unit)
        assertTrue(runningDelete.await() is TaskResult.Done)
        assertTrue(unindex.await() is TaskResult.Done)
        assertEquals(0, scanRuns.get())
    }

    @Test
    fun scanImmediateJumpsAheadOfQueuedScanRefresh() = runBlocking {
        // 用 4 个占满 DATA_WORKERS 的扫描任务挡住队列，验证后入队的 SCAN_IMMEDIATE 优先于先入队的 SCAN_REFRESH 执行
        val blockerGate = CompletableDeferred<Unit>()
        val blockersStarted = AtomicInteger(0)
        val allBlockersRunning = CompletableDeferred<Unit>()
        val executionOrder = mutableListOf<String>()
        val orderLock = Any()

        val scheduler = FolderTaskScheduler(
            mapOf(
                TaskKind.SCAN_REFRESH to handler { task, _ ->
                    if (task.path.startsWith("/block/")) {
                        if (blockersStarted.incrementAndGet() == 4) {
                            allBlockersRunning.complete(Unit)
                        }
                        blockerGate.await()
                    } else {
                        synchronized(orderLock) { executionOrder += task.path }
                    }
                    TaskResult.Done()
                },
                TaskKind.SCAN_IMMEDIATE to handler { task, _ ->
                    synchronized(orderLock) { executionOrder += task.path }
                    TaskResult.Done()
                }
            )
        )

        val blockers = (1..4).map { i ->
            scheduler.enqueue(FolderTask("/block/$i", TaskKind.SCAN_REFRESH))
        }
        allBlockersRunning.await()

        val queuedBackground = scheduler.enqueue(FolderTask("/bg/1", TaskKind.SCAN_REFRESH))
        val queuedImmediate = scheduler.enqueue(FolderTask("/immediate/1", TaskKind.SCAN_IMMEDIATE))

        blockerGate.complete(Unit)
        blockers.forEach { it.await() }
        queuedBackground.await()
        queuedImmediate.await()

        assertEquals("/immediate/1", executionOrder.first())
    }
}
