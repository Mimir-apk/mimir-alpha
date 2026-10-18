package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Who owns the main screen has two sources, and only one of them was ever read.**
 *
 * `CompanionRunningAppState.running` is published by the display-0 transfer host alone. An app the
 * player launched from the Bottom dock never crosses that host — it exists only as this ledger's
 * `primaryAppSession` — so the swap planner saw `mainRunning=null` over a screen that visibly had
 * Chrome on it (test device, 2026-09-11) and moved the Bottom app on top of it instead of exchanging.
 *
 * The same session is also what BLOCKS the other half of an exchange: `appPreparing(PRIMARY)` refuses
 * while a PRIMARY session sits in an active presentation phase, and a dock-launched app has no transfer
 * host to end it once it has been moved down to the Bottom. Both halves are pinned here.
 */
class MimirSessionPrimaryOccupantTest {

    private val origin = PrimaryReturnOrigin("HOME")

    @Test
    fun anEmptyLedgerHasNoMainOccupant() {
        assertNull(MimirSessionCoordinator().state.value.primaryOccupant())
    }

    @Test
    fun aDispatchedPrimarySessionIsTheMainOccupant() {
        val coordinator = MimirSessionCoordinator()
        assertTrue(coordinator.dockLaunch("primary:chrome", "com.android.chrome", "Chrome"))
        assertTrue(coordinator.appDispatched("primary:chrome"))

        // DISPATCHED is the phase a dock launch usually STAYS in — foreground proof often never lands —
        // so it is exactly the case the old read missed.
        assertEquals(MimirSessionPhase.DISPATCHED, coordinator.state.value.primaryAppSession?.phase)
        val occupant = coordinator.state.value.primaryOccupant()
        assertEquals(CompanionRunningApp("com.android.chrome", "Chrome", null), occupant)
    }

    @Test
    fun aForegroundProvenPrimarySessionIsTheMainOccupant() {
        val coordinator = MimirSessionCoordinator()
        assertTrue(coordinator.dockLaunch("primary:notes", "com.example.notes", "Notes"))
        assertTrue(coordinator.appDispatched("primary:notes"))
        assertTrue(coordinator.appForegroundProven("primary:notes"))

        assertEquals(MimirSessionPhase.FOREGROUND_PROVEN, coordinator.state.value.primaryAppSession?.phase)
        assertEquals(
            CompanionRunningApp("com.example.notes", "Notes", null),
            coordinator.state.value.primaryOccupant(),
        )
    }

    @Test
    fun anEndedOrFailedPrimarySessionIsNotAnOccupant() {
        val ended = MimirSessionCoordinator()
        assertTrue(ended.dockLaunch("primary:ended", "com.example.ended", "Ended"))
        assertTrue(ended.appDispatched("primary:ended"))
        assertTrue(ended.appEnded("primary:ended"))
        assertEquals(MimirSessionPhase.ENDED, ended.state.value.primaryAppSession?.phase)
        assertNull("an ended session must not hold the main screen", ended.state.value.primaryOccupant())

        val failed = MimirSessionCoordinator()
        assertTrue(failed.dockLaunch("primary:failed", "com.example.failed", "Failed"))
        assertTrue(failed.appFailed("primary:failed", "target_never_resolved"))
        assertEquals(MimirSessionPhase.FAILED, failed.state.value.primaryAppSession?.phase)
        assertNull("a failed session must not hold the main screen", failed.state.value.primaryOccupant())
    }

    @Test
    fun aSecondarySessionIsNeverTheMainOccupant() {
        val coordinator = MimirSessionCoordinator()
        assertTrue(
            coordinator.appPreparing(
                operationId = "secondary:youtube",
                app = CompanionRunningApp("com.google.android.youtube", "YouTube"),
                placement = MimirAppPlacement.SECONDARY,
                returnAnchor = origin,
            ),
        )
        assertTrue(coordinator.appDispatched("secondary:youtube"))

        // The Bottom app is a live session too; reading it as a Main occupant would plan an exchange
        // against itself.
        assertNull(coordinator.state.value.primaryOccupant())
    }

    @Test
    fun movingTheMainAppToTheBottomEndsItsMainSession() {
        val coordinator = MimirSessionCoordinator()
        assertTrue(coordinator.dockLaunch("primary:chrome", "com.android.chrome", "Chrome"))
        assertTrue(coordinator.appDispatched("primary:chrome"))

        assertTrue(coordinator.endPrimaryAppForSecondaryMove("com.android.chrome"))

        assertEquals(MimirSessionPhase.ENDED, coordinator.state.value.primaryAppSession?.phase)
        assertNull(coordinator.state.value.primaryOccupant())
        // The point of ending it: a second Main launch is admissible again. Left DISPATCHED, this
        // returned false and the exchange's final leg could never come up.
        assertTrue(coordinator.dockLaunch("primary:youtube", "com.google.android.youtube", "YouTube"))
    }

    @Test
    fun onlyTheMatchingPackageEndsTheMainSession() {
        val coordinator = MimirSessionCoordinator()
        assertTrue(coordinator.dockLaunch("primary:chrome", "com.android.chrome", "Chrome"))
        assertTrue(coordinator.appDispatched("primary:chrome"))
        val revision = coordinator.state.value.revision

        assertFalse(coordinator.endPrimaryAppForSecondaryMove("com.google.android.youtube"))
        assertFalse(coordinator.endPrimaryAppForSecondaryMove("  "))

        assertEquals("a non-matching move must publish nothing", revision, coordinator.state.value.revision)
        assertEquals(MimirSessionPhase.DISPATCHED, coordinator.state.value.primaryAppSession?.phase)

        // The lease package arrives from the native ledger; padding and case are not identity.
        assertTrue(coordinator.endPrimaryAppForSecondaryMove("  COM.Android.Chrome "))
        assertEquals(MimirSessionPhase.ENDED, coordinator.state.value.primaryAppSession?.phase)
    }

    @Test
    fun anInactiveMainSessionIsNotEndedTwice() {
        val coordinator = MimirSessionCoordinator()
        assertTrue(coordinator.dockLaunch("primary:chrome", "com.android.chrome", "Chrome"))
        assertTrue(coordinator.appDispatched("primary:chrome"))
        assertTrue(coordinator.endPrimaryAppForSecondaryMove("com.android.chrome"))
        val revision = coordinator.state.value.revision

        // The runtime collector fires on every Yielded snapshot, so the SAME lease reaches this hook
        // repeatedly. A second call must be a no-op, not another published event.
        assertFalse(coordinator.endPrimaryAppForSecondaryMove("com.android.chrome"))
        assertEquals(revision, coordinator.state.value.revision)
    }

    @Test
    fun anEmptyLedgerRefusesTheMove() {
        assertFalse(MimirSessionCoordinator().endPrimaryAppForSecondaryMove("com.android.chrome"))
    }

    /** What the dock's Main route produces: a PRIMARY session with no transfer host behind it. */
    private fun MimirSessionCoordinator.dockLaunch(
        operationId: String,
        packageName: String,
        label: String,
    ): Boolean = appPreparing(
        operationId = operationId,
        app = CompanionRunningApp(packageName, label),
        placement = MimirAppPlacement.PRIMARY,
        returnAnchor = origin,
    )
}
