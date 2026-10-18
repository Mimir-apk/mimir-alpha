package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PrimaryTransferActivityStateTest {
    private val operation = UUID(0L, 501L)
    private val validEnvironment = TransferHostCoverageEnvironment(
        displayOn = true,
        deviceInteractive = true,
        keyguardLocked = false,
        callInterruptionActive = false,
    )

    @Test
    fun `coverage requires the exact dispatched operation and a target-triggered pause`() {
        val initial = PrimaryTransferHostExecutionState()
        val dispatched = initial.targetDispatched(operation)

        assertFalse(dispatched.targetReturnedBeforeCoverage())
        assertFalse(dispatched.claimHostCoverage(operation, validEnvironment).second)
        assertFalse(dispatched.targetPaused().claimHostCoverage(UUID(0L, 502L), validEnvironment).second)

        val paused = dispatched.targetPaused()
        assertTrue(paused.targetReturnedBeforeCoverage())
        val (covered, claimed) = paused.claimHostCoverage(operation, validEnvironment)
        assertTrue(claimed)
        assertEquals(PrimaryTransferHostPhase.HOST_COVERED, covered.phase)
        assertFalse(covered.claimHostCoverage(operation, validEnvironment).second)
    }

    @Test
    fun `screen lock noninteractive display and call interruption all fail closed`() {
        val paused = PrimaryTransferHostExecutionState()
            .targetDispatched(operation)
            .targetPaused()
        val blocked = listOf(
            validEnvironment.copy(displayOn = false),
            validEnvironment.copy(deviceInteractive = false),
            validEnvironment.copy(keyguardLocked = true),
            validEnvironment.copy(callInterruptionActive = true),
            validEnvironment.copy(homeOrRecentsBecameVisible = true),
        )

        blocked.forEach { environment ->
            val (unchanged, claimed) = paused.claimHostCoverage(operation, environment)
            assertFalse(claimed)
            assertEquals(paused, unchanged)
        }
    }

    @Test
    fun `Home or Recents user-leave hint fails closed even after target pause`() {
        val state = PrimaryTransferHostExecutionState()
            .targetDispatched(operation)
            .userLeft()
            .userLeft()
            .targetPaused()

        assertTrue(state.userLeaveObserved)
        assertFalse(state.claimHostCoverage(operation, validEnvironment).second)
    }

    @Test
    fun `target launch departure consumes one expected leave hint`() {
        val state = PrimaryTransferHostExecutionState()
            .targetDispatched(operation)
            .userLeft()
            .targetPaused()

        assertFalse(state.userLeaveObserved)
        assertFalse(state.expectedTargetLeaveHint)
        assertTrue(state.claimHostCoverage(operation, validEnvironment).second)
    }

    @Test
    fun `user departure before primary dispatch invalidates initial host state`() {
        val state = PrimaryTransferHostExecutionState().userLeft()

        assertTrue(state.userLeaveObserved)
        assertEquals(PrimaryTransferHostPhase.FAILED, state.failed().phase)
    }

    @Test
    fun `duplicate dispatch and stale lifecycle callbacks cannot replace the owned operation`() {
        val first = PrimaryTransferHostExecutionState().targetDispatched(operation)
        val duplicate = first.targetDispatched(UUID(0L, 503L))
        val pausedOnce = first.targetPaused()

        assertEquals(first, duplicate)
        assertEquals(pausedOnce, pausedOnce.targetPaused())
        assertEquals(PrimaryTransferHostPhase.FAILED, first.failed().phase)
        assertFalse(first.failed().claimHostCoverage(operation, validEnvironment).second)
    }
}
