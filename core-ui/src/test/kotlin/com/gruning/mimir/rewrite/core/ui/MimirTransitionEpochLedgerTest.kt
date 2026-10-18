package com.gruning.mimir.rewrite.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimirTransitionEpochLedgerTest {
    @Test
    fun staleCompletionCannotCleanUpANewerRoute() {
        val ledger = MimirTransitionEpochLedger("home")
        val toLibrary = ledger.prepare("library") { _, _ -> true }
        assertTrue(ledger.commit(toLibrary))
        val backHome = ledger.prepare("home") { _, _ -> true }
        assertTrue(ledger.commit(backHome))

        assertFalse(ledger.acceptsCompletion(toLibrary.epoch))
        assertTrue(ledger.acceptsCompletion(backHome.epoch))
        assertTrue(backHome.epoch > toLibrary.epoch)
    }

    @Test
    fun rapidReversalRetargetsOneOutgoingLayerWithoutQueueing() {
        val ledger = MimirTransitionEpochLedger("home")
        val outbound = ledger.prepare("library") { _, _ -> true }
        ledger.commit(outbound)
        val reversal = ledger.prepare("home") { _, _ -> true }
        ledger.commit(reversal)

        assertEquals(2, outbound.surfaceCount)
        assertEquals(2, reversal.surfaceCount)
        assertEquals(outbound.primaryLayerIndex, reversal.outgoingLayerIndex)
        assertEquals(outbound.outgoingLayerIndex, reversal.primaryLayerIndex)
    }

    @Test
    fun hardCutNeverComposesAnOutgoingRoute() {
        val ledger = MimirTransitionEpochLedger("home")
        val frame = ledger.prepare("settings") { _, _ -> false }
        ledger.commit(frame)

        assertEquals(1, frame.surfaceCount)
        assertNull(frame.outgoingLayerIndex)
    }
}
