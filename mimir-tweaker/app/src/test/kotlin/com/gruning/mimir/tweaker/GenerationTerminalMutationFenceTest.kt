package com.gruning.mimir.tweaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationTerminalMutationFenceTest {
    private val current = GenerationFenceIdentity("boot_17", 7, "tx_abc", "session_abc")
    private val source = InstalledTweakerApkSource(
        "/data/app/~~YVNPC3HPZB4E0d9-2C937Q==/com.gruning.mimir.tweaker-P1x25Fj8KMtUp0aC0qwNVQ==/base.apk",
        "a".repeat(64),
    )
    private val authority = PinnedTweakerApkAuthority("a".repeat(64))

    @Test
    fun `delayed apply is blocked after matching terminal tombstone`() {
        val result = GenerationTerminalFencePolicy.evaluateApply(
            current,
            FenceDiskState(true, current, terminalMarkerIdentity = current, lockAcquired = true),
        )

        assertEquals(FenceApplyDecision.TERMINALIZED, result)
        val command = GenerationTerminalMutationFence.renderApply(current, source, authority)
        assertEquals(RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.APPLY_UNDER_FENCE), command)
    }

    @Test
    fun `terminal waits behind admitted apply then wins and blocks later apply`() {
        val ledger = GenerationTerminalFenceLedger(current)
        assertEquals(FenceApplyDecision.ALLOW, ledger.beginApply())
        assertEquals(TerminalRequestResult.WAITING_FOR_IN_FLIGHT_APPLY, ledger.requestTerminal())
        assertEquals(FenceApplyDecision.TERMINALIZED, ledger.beginApply())
        assertFalse(ledger.canCleanupTombstone())

        ledger.completeApply()

        assertEquals(current, ledger.tombstoneIdentity())
        assertTrue(ledger.canCleanupTombstone())
        assertEquals(FenceApplyDecision.TERMINALIZED, ledger.beginApply())
    }

    @Test
    fun `stale generation cannot terminalize or block a new generation`() {
        val stale = current.copy(generation = 6)
        val fresh = current.copy(generation = 8)
        assertNotEquals(GenerationTerminalMutationFence.directory(stale), GenerationTerminalMutationFence.directory(fresh))

        val freshResult = GenerationTerminalFencePolicy.evaluateApply(
            fresh,
            FenceDiskState(true, fresh, terminalMarkerIdentity = stale, lockAcquired = true),
        )
        assertEquals(FenceApplyDecision.CORRUPT_TERMINAL_MARKER, freshResult)
        assertEquals(
            RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.TERMINALIZE),
            GenerationTerminalMutationFence.renderTerminalize(stale, source, authority),
        )
        assertFalse(GenerationTerminalMutationFence.renderTerminalize(stale, source, authority).contains(GenerationTerminalMutationFence.terminalMarker(fresh)))
    }

    @Test
    fun `missing directory or mismatched identity fail closed`() {
        assertEquals(
            FenceApplyDecision.MISSING_DIRECTORY,
            GenerationTerminalFencePolicy.evaluateApply(current, FenceDiskState(false, current, lockAcquired = true)),
        )
        assertEquals(
            FenceApplyDecision.IDENTITY_MISMATCH,
            GenerationTerminalFencePolicy.evaluateApply(
                current,
                FenceDiskState(true, current.copy(sessionId = "other"), lockAcquired = true),
            ),
        )
    }

    @Test
    fun `identity shell metacharacters are rejected before rendering`() {
        assertRejected { GenerationFenceIdentity("boot;rm", 1, "tx", "session") }
        assertRejected { GenerationFenceIdentity("boot", 1, "tx$(evil)", "session") }
        assertRejected { GenerationFenceIdentity("boot", 1, "tx", "session with space") }
        assertRejected { GenerationFenceIdentity("boot", 0, "tx", "session") }
    }

    @Test
    fun `lock timeout is fail closed and Binder renderer stays fixed`() {
        assertEquals(
            FenceApplyDecision.LOCK_TIMEOUT,
            GenerationTerminalFencePolicy.evaluateApply(current, FenceDiskState(true, current, lockAcquired = false)),
        )
        val command = GenerationTerminalMutationFence.renderApply(current, source, authority, timeouts = FenceTimeouts(7, 2))
        assertEquals(RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.APPLY_UNDER_FENCE), command)
    }

    @Test
    fun `tombstone cleanup remains ordered after terminal and no inflight proof`() {
        val ledger = GenerationTerminalFenceLedger(current)
        assertEquals(TerminalRequestResult.TERMINALIZED, ledger.requestTerminal())
        assertTrue(ledger.canCleanupTombstone())
        val cleanup = GenerationTerminalMutationFence.renderCleanupAfterNoInflightProof(current, source, authority)
        assertEquals(RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.CLEANUP), cleanup)
    }

    @Test
    fun `rendered apply contains no caller supplied hardware fragment`() {
        val apply = GenerationTerminalMutationFence.renderApply(current, source, authority)
        assertEquals(RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.APPLY_UNDER_FENCE), apply)
        assertFalse(apply.contains("/sys/"))
        assertFalse(apply.contains("settings put"))
        assertFalse(apply.contains("echo "))
    }

    @Test
    fun `stage creates only its derived directory before taking the shared lock`() {
        val stage = GenerationTerminalMutationFence.renderStage(current, source, authority)
        assertEquals(RestoreGuardianDirectLauncher.renderRequired(source, authority, RestoreGuardianRunnerMode.STAGE), stage)
    }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
