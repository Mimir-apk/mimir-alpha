package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.AppEntry
import com.gruning.mimir.rewrite.core.companionnative.ForegroundMonitorDisposition
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplayState
import com.gruning.mimir.rewrite.core.companionnative.SecondaryTaskCloseState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionForegroundMonitorDemandTest {
    @Test
    fun `close ledger changes wake and stop mirror observation without disposition change`() {
        val snapshot = mirroredSnapshot()
        val open = SecondaryDisplayState(recents = listOf(AppEntry("org.example.game", "Game", 4, 1L)))
        val closeOperation = UUID.randomUUID()
        val closing = open.copy(
            recents = listOf(
                AppEntry(
                    packageName = "org.example.game",
                    label = "Game",
                    displayId = 4,
                    lastSeenAtMs = 2L,
                    closeState = SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED,
                    closeOperationId = closeOperation,
                    closeRequestedAtElapsedRealtimeMs = 3L,
                ),
            ),
        )

        val idleDemand = foregroundMonitorDemand(snapshot, open)
        val closeDemand = foregroundMonitorDemand(snapshot, closing)

        assertEquals(ForegroundMonitorDisposition.MIRROR_PRESENTED, idleDemand.disposition)
        assertFalse(idleDemand.taskObservationPending)
        assertTrue(closeDemand.taskObservationPending)
        assertFalse(foregroundMonitorDemand(snapshot, open).taskObservationPending)
    }

    @Test
    fun `matching runtime launch lock requests task evidence even with open ledger`() {
        val snapshot = mirroredSnapshot().copy(launchLockOperationId = OperationId.create())
        val state = SecondaryDisplayState(recents = listOf(AppEntry("org.example.game", "Game", 4, 1L)))

        assertTrue(foregroundMonitorDemand(snapshot, state).taskObservationPending)
    }

    @Test
    fun `cold recovery suppresses idle task polling while ordinary recovery retains it`() {
        val open = SecondaryDisplayState()
        val cold = mirroredSnapshot().copy(
            disposition = SecondaryDisplayDisposition.RecoveryNeeded(
                operationId = OperationId.create(),
                displayId = 4,
                fallback = PrimaryReturnOrigin.LegacyCurrent,
                failure = FailureReason(FailureCode.PROCESS_RECOVERED_NON_MIRROR, "saved host"),
            ),
            mirrorActive = false,
        )
        val ordinary = cold.copy(
            disposition = (cold.disposition as SecondaryDisplayDisposition.RecoveryNeeded).copy(
                failure = FailureReason(FailureCode.RENDER_FAILED, "renderer recovery"),
            ),
        )

        assertFalse(foregroundMonitorDemand(cold, open, primaryStopped = false).recoveryTaskEvidenceRequired)
        assertTrue(foregroundMonitorDemand(ordinary, open, primaryStopped = false).recoveryTaskEvidenceRequired)
    }

    @Test
    fun `foreground primary disables external follow without suppressing launch evidence`() {
        val open = SecondaryDisplayState(recents = listOf(AppEntry("org.example.game", "Game", 4, 1L)))
        val idle = foregroundMonitorDemand(mirroredSnapshot(), open, primaryStopped = false)
        val pending = foregroundMonitorDemand(
            mirroredSnapshot().copy(launchLockOperationId = OperationId.create()),
            open,
            primaryStopped = false,
        )

        assertFalse(idle.externalFollowActive)
        assertFalse(idle.taskObservationPending)
        assertFalse(pending.externalFollowActive)
        assertTrue(pending.taskObservationPending)
    }

    /**
     * **THE REGRESSION for policy L-4, 2026-09-01 (review findings D3 + D9).**
     *
     * A `Yielded` lease has no liveness check of any kind: the root deadline is cancelled when the
     * host covers us, nothing is scheduled in its place, and the only accepted close evidence is
     * package uninstall — so it can outlive its subject unboundedly. The one advisory observer that
     * could notice was gated on `primaryStopped`, which is FALSE for the whole session on a device
     * where both displays hold a resumed activity at once (measured on the Thor, `b687f3b5`). The
     * observer was therefore asleep during exactly the session where a stale lease is most likely.
     */
    @Test
    fun `a live yielded lease keeps the external follow awake even with the primary foreground`() {
        val open = SecondaryDisplayState(recents = listOf(AppEntry("org.example.game", "Game", 4, 1L)))
        val yielded = mirroredSnapshot().copy(
            disposition = SecondaryDisplayDisposition.Yielded(
                lease = HostLease(
                    operationId = OperationId.create(),
                    packageName = "org.example.game",
                    displayId = 4,
                    launchMode = "secondary_app",
                    primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
                ),
                evidence = YieldEvidence.HOST_COVERED,
            ),
            mirrorActive = false,
        )

        val demand = foregroundMonitorDemand(yielded, open, primaryStopped = false)

        assertTrue(
            "the lease's only advisory observer must not sleep through the lease",
            demand.externalFollowActive,
        )
    }

    /** And with no display there is nothing to follow, lease or not. */
    @Test
    fun `a yielded lease with no display does not wake the follow`() {
        val open = SecondaryDisplayState(recents = listOf(AppEntry("org.example.game", "Game", 4, 1L)))
        val yielded = mirroredSnapshot().copy(
            displayId = null,
            disposition = SecondaryDisplayDisposition.Yielded(
                lease = HostLease(
                    operationId = OperationId.create(),
                    packageName = "org.example.game",
                    displayId = 4,
                    launchMode = "secondary_app",
                    primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
                ),
                evidence = YieldEvidence.HOST_COVERED,
            ),
            mirrorActive = false,
        )

        assertFalse(foregroundMonitorDemand(yielded, open, primaryStopped = false).externalFollowActive)
    }

    @Test
    fun `covered companion retains primary tab changes but filters its own secondary host`() {
        val host = HostLease(
            operationId = OperationId.create(),
            packageName = "org.example.emulator",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("LIBRARY"),
        )
        val covered = mirroredSnapshot().copy(
            disposition = SecondaryDisplayDisposition.Yielded(host, YieldEvidence.HOST_COVERED),
            mirrorActive = false,
        )

        assertFalse(shouldRetainForeignForeground(covered, host.packageName))
        assertTrue(shouldRetainForeignForeground(covered, "org.example.browser"))
        assertFalse(
            shouldRetainForeignForeground(
                covered.copy(disposition = SecondaryDisplayDisposition.NoDisplay, displayId = null),
                "org.example.browser",
            ),
        )
    }

    private fun mirroredSnapshot(): CompanionRuntimeSnapshot {
        val parent = OperationId.create()
        val attempt = RenderAttempt(
            operationId = OperationId.create(),
            parentDispositionId = parent,
            rendererOwnerId = RendererOwnerId.create(),
            displayId = 4,
        )
        return CompanionRuntimeSnapshot(
            stateVersion = 1L,
            displayId = 4,
            disposition = SecondaryDisplayDisposition.MirrorPresented(attempt),
            rendererOwnerId = attempt.rendererOwnerId,
            mirrorActive = true,
            launchLockOperationId = null,
            launchSettlement = null,
        )
    }
}
