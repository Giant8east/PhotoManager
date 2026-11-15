package com.giant8east.photomanager.data.delete

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletionGateTest {
    @Test
    fun deletionInvalidatesTicketAndBlocksNewRefresh() = runBlocking {
        val gate = DeletionGate()
        val beforeDeletion = gate.beginRefresh("/albums/a")
        assertNotNull(beforeDeletion)

        val lease = gate.beginDeletion(listOf("/albums/a"))
        val whileDeleting = gate.beginRefresh("/albums/a/child")
        assertTrue(whileDeleting == null)

        var committed = false
        assertFalse(
            gate.commit(beforeDeletion!!) {
                committed = true
            }
        )
        assertFalse(committed)

        gate.endDeletion(lease)

        val afterDeletion = gate.beginRefresh("/albums/a")
        assertNotNull(afterDeletion)
        assertTrue(
            gate.commit(afterDeletion!!) {
                committed = true
            }
        )
        assertTrue(committed)
    }
}
