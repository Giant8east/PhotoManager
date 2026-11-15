package com.giant8east.photomanager.data.delete

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FileTreeDeleterTest {
    @Test
    fun deletesNestedFilesAndCountsProgress() = runBlocking {
        val root = File.createTempFile("photo-manager-delete", "").apply {
            delete()
            mkdirs()
        }
        try {
            val child = File(root, "child").apply { mkdirs() }
            File(root, "a.jpg").writeBytes(byteArrayOf(1))
            File(child, "b.jpg").writeBytes(byteArrayOf(2))

            var lastProgress = 0L
            val result = FileTreeDeleter().deleteTree(root) { lastProgress = it }

            assertEquals(2L, result.deletedFiles)
            assertEquals(0, result.failedEntries)
            assertEquals(2L, lastProgress)
            assertFalse(root.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
