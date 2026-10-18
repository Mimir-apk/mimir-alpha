package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.AppEntry
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionRunningApp
import com.gruning.mimir.rewrite.feature.library.NativeShelfRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class CompanionRunningAppStateTest {
    private val shelf = CompanionPayload(mode = CompanionMode.HOME, themeId = "midnight", statsLabel = "Shelf")

    @Before
    fun setUp() {
        resetCompanionRunningApp()
        CompanionState.set(shelf)
    }

    @After
    fun tearDown() {
        resetCompanionRunningApp()
        CompanionState.set(CompanionPayload.idle())
    }

    @Test
    fun `matching return clears process truth and restores the non-running payload`() {
        val app = CompanionRunningApp("com.clock", "Clock", "/icons/clock.png")
        CompanionRunningAppState.set(app)
        CompanionState.showRunningApp(app)

        assertEquals(CompanionMode.RUNNING_APP, CompanionState.payload.value.mode)
        assertTrue(clearCompanionRunningApp("com.clock"))
        assertNull(CompanionRunningAppState.running.value)
        assertEquals(shelf, CompanionState.payload.value)
    }

    @Test
    fun `stale return cannot clear a newer running app`() {
        val app = CompanionRunningApp("com.new", "New")
        CompanionRunningAppState.set(app)
        CompanionState.showRunningApp(app)

        assertFalse(clearCompanionRunningApp("com.old"))
        assertEquals(app, CompanionRunningAppState.running.value)
        assertEquals(CompanionMode.RUNNING_APP, CompanionState.payload.value.mode)
    }

    @Test
    fun `dispatch stays pending until its display-zero host confirms coverage`() {
        val app = CompanionRunningApp("com.clock", "Clock")
        val operationId = UUID(0L, 91L)

        assertTrue(
            CompanionRunningAppState.stageTransfer(
                operationId = operationId,
                app = app,
                startedAtEpochMs = 2_000L,
                nowMonotonicMs = 10L,
            ),
        )
        assertNull(CompanionRunningAppState.running.value)
        assertEquals(shelf, CompanionState.payload.value)
        assertTrue(claimAndBegin(operationId, "com.clock", nowMonotonicMs = 5_000L))

        assertTrue(
            confirmCompanionRunningAppTransfer(
                operationId = operationId,
                packageName = "com.clock",
                nowMonotonicMs = 5_001L,
            ),
        )
        assertEquals(app, CompanionRunningAppState.running.value)
        assertEquals(CompanionMode.RUNNING_APP, CompanionState.payload.value.mode)
    }

    @Test
    fun `failed dispatch cancellation never publishes a card`() {
        val operationId = UUID(0L, 92L)
        assertTrue(
            CompanionRunningAppState.stageTransfer(
                operationId = operationId,
                app = CompanionRunningApp("com.clock", "Clock"),
                nowMonotonicMs = 10L,
            ),
        )

        assertTrue(CompanionRunningAppState.cancelTransfer(operationId))
        assertFalse(
            confirmCompanionRunningAppTransfer(
                operationId = operationId,
                packageName = "com.clock",
                nowMonotonicMs = 11L,
            ),
        )
        assertNull(CompanionRunningAppState.running.value)
        assertEquals(shelf, CompanionState.payload.value)
    }

    @Test
    fun `live pending transfer rejects a duplicate operation`() {
        val first = UUID(0L, 94L)
        val duplicate = UUID(0L, 95L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(first, app, nowMonotonicMs = 10L))

        assertFalse(CompanionRunningAppState.stageTransfer(duplicate, app, nowMonotonicMs = 11L))
        assertFalse(CompanionRunningAppState.cancelTransfer(duplicate))
        assertTrue(CompanionRunningAppState.hasPendingTransfer(first))
    }

    @Test
    fun `unarmed staging lease can be superseded without consuming host coverage time`() {
        val first = UUID(0L, 108L)
        val second = UUID(0L, 109L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(first, app, nowMonotonicMs = 10L))

        assertTrue(
            CompanionRunningAppState.stageTransfer(
                second,
                app,
                nowMonotonicMs = 10L + PRIMARY_TRANSFER_STAGING_WINDOW_MILLIS + 1L,
            ),
        )
        assertFalse(CompanionRunningAppState.hasPendingTransfer(first))
        assertTrue(CompanionRunningAppState.hasPendingTransfer(second))
        assertFalse(claimCompanionRunningAppPrimaryHostLaunch(first, "com.clock", 20_000L))
        assertTrue(claimCompanionRunningAppPrimaryHostLaunch(second, "com.clock", 20_000L))
    }

    @Test
    fun `primary transfer cannot overwrite an active primary app`() {
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.set(app))
        assertFalse(CompanionRunningAppState.stageTransfer(UUID(0L, 111L), app, nowMonotonicMs = 10L))

        assertFalse(CompanionRunningAppState.hasPendingTransfer(UUID(0L, 111L)))
        assertEquals(app, CompanionRunningAppState.running.value)
    }

    @Test
    fun `stale same-package return cannot clear a newer confirmed session`() {
        val app = CompanionRunningApp("com.clock", "Clock")
        CompanionRunningAppState.set(app, startedAtEpochMs = 2_000L)
        CompanionState.showRunningApp(app)

        assertFalse(clearCompanionRunningApp("com.clock", observedAtEpochMs = 1_999L))
        assertFalse(clearCompanionRunningApp("com.clock", observedAtEpochMs = 2_000L))
        assertEquals(app, CompanionRunningAppState.running.value)
        assertTrue(clearCompanionRunningApp("com.clock", observedAtEpochMs = 2_001L))
        assertNull(CompanionRunningAppState.running.value)
    }

    @Test
    fun `coverage deadline starts at host dispatch and rejects expiration from that anchor`() {
        val operationId = UUID(0L, 93L)
        assertTrue(
            CompanionRunningAppState.stageTransfer(
                operationId = operationId,
                app = CompanionRunningApp("com.clock", "Clock"),
                nowMonotonicMs = 10L,
            ),
        )

        assertFalse(
            confirmCompanionRunningAppTransfer(
                operationId = operationId,
                packageName = "com.clock",
                nowMonotonicMs = 3_011L,
            ),
        )
        assertFalse(beginCompanionRunningAppPrimaryHostDispatch(UUID(0L, 999L), "com.clock", 5_000L))
        assertFalse(beginCompanionRunningAppPrimaryHostDispatch(operationId, "com.other", 5_000L))
        assertTrue(claimCompanionRunningAppPrimaryHostLaunch(operationId, "com.clock", 4_999L))
        assertTrue(
            beginCompanionRunningAppPrimaryHostDispatch(
                operationId = operationId,
                packageName = "com.clock",
                nowMonotonicMs = 5_000L,
            ),
        )
        assertFalse(beginCompanionRunningAppPrimaryHostDispatch(operationId, "com.clock", 5_001L))
        assertFalse(
            confirmCompanionRunningAppTransfer(
                operationId = operationId,
                packageName = "com.clock",
                nowMonotonicMs = 13_001L,
            ),
        )
        assertNull(CompanionRunningAppState.running.value)
    }

    @Test
    fun `coverage at the exact host deadline is accepted`() {
        val operation = UUID(0L, 110L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(operation, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(operation, "com.clock", nowMonotonicMs = 5_000L))

        assertTrue(
            confirmCompanionRunningAppTransfer(
                operation,
                "com.clock",
                nowMonotonicMs = 5_000L + PRIMARY_TRANSFER_COVERAGE_WINDOW_MILLIS,
            ),
        )
        assertEquals(app, CompanionRunningAppState.running.value)
    }

    @Test
    fun `wrong operation and package cannot confirm a newer transfer`() {
        val operationId = UUID(0L, 96L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(
            CompanionRunningAppState.stageTransfer(
                operationId = operationId,
                app = app,
                startedAtEpochMs = 2_000L,
                nowMonotonicMs = 10L,
            ),
        )
        assertTrue(claimAndBegin(operationId, "com.clock", nowMonotonicMs = 10L))

        assertNull(CompanionRunningAppState.confirmTransferFromPrimaryHost(UUID(0L, 95L), "com.clock", 11L))
        assertNull(CompanionRunningAppState.confirmTransferFromPrimaryHost(operationId, "com.other", 11L))
        assertTrue(CompanionRunningAppState.hasPendingTransfer(operationId))
        assertEquals(app, CompanionRunningAppState.confirmTransferFromPrimaryHost(operationId, "com.clock", 11L))
    }

    @Test
    fun `returning transfer host cannot clear a newer same-package session`() {
        val first = UUID(0L, 97L)
        val second = UUID(0L, 98L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(first, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(first, "com.clock", nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(first, "com.clock", nowMonotonicMs = 12L))
        assertTrue(clearCompanionRunningAppTransfer(first, "com.clock"))
        assertTrue(CompanionRunningAppState.stageTransfer(second, app, nowMonotonicMs = 13L))
        assertTrue(claimAndBegin(second, "com.clock", nowMonotonicMs = 14L))
        assertTrue(confirmCompanionRunningAppTransfer(second, "com.clock", nowMonotonicMs = 15L))

        assertFalse(clearCompanionRunningAppTransfer(first, "com.clock"))
        assertEquals(app, CompanionRunningAppState.running.value)
        assertTrue(clearCompanionRunningAppTransfer(second, "com.clock"))
        assertNull(CompanionRunningAppState.running.value)
    }

    @Test
    fun `secondary host clears only the exact armed primary session after coverage`() {
        val primary = UUID(0L, 99L)
        val secondary = UUID(0L, 100L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(primary, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(primary, "com.clock", nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(primary, "com.clock", nowMonotonicMs = 12L))
        assertTrue(CompanionRunningAppState.armSecondaryReturn(secondary, "com.clock"))

        assertFalse(confirmCompanionRunningAppSecondaryReturn(UUID(0L, 101L), "com.clock"))
        assertFalse(confirmCompanionRunningAppSecondaryReturn(secondary, "com.other"))
        assertEquals(app, CompanionRunningAppState.running.value)
        assertTrue(confirmCompanionRunningAppSecondaryReturn(secondary, "com.clock"))
        assertNull(CompanionRunningAppState.running.value)
        assertEquals(shelf, CompanionState.payload.value)
    }

    @Test
    fun `a completed secondary return cannot clear a later primary session`() {
        val first = UUID(0L, 102L)
        val second = UUID(0L, 103L)
        val secondary = UUID(0L, 104L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(first, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(first, "com.clock", nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(first, "com.clock", nowMonotonicMs = 12L))
        assertTrue(CompanionRunningAppState.armSecondaryReturn(secondary, "com.clock"))
        assertTrue(confirmCompanionRunningAppSecondaryReturn(secondary, "com.clock"))
        assertTrue(CompanionRunningAppState.stageTransfer(second, app, nowMonotonicMs = 13L))
        assertTrue(claimAndBegin(second, "com.clock", nowMonotonicMs = 14L))
        assertTrue(confirmCompanionRunningAppTransfer(second, "com.clock", nowMonotonicMs = 15L))

        assertFalse(confirmCompanionRunningAppSecondaryReturn(secondary, "com.clock"))
        assertEquals(app, CompanionRunningAppState.running.value)
    }

    @Test
    fun `failed secondary host revokes only its exact return arm and retains on-main truth`() {
        val primary = UUID(0L, 105L)
        val secondary = UUID(0L, 106L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(primary, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(primary, "com.clock", nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(primary, "com.clock", nowMonotonicMs = 12L))
        assertTrue(CompanionRunningAppState.armSecondaryReturn(secondary, "com.clock"))

        assertFalse(cancelCompanionRunningAppSecondaryReturn(UUID(0L, 999L), "com.clock"))
        assertFalse(cancelCompanionRunningAppSecondaryReturn(secondary, "com.other"))
        assertTrue(cancelCompanionRunningAppSecondaryReturn(secondary, "com.clock"))
        assertEquals(app, CompanionRunningAppState.running.value)
        assertFalse(confirmCompanionRunningAppSecondaryReturn(secondary, "com.clock"))
        assertEquals(CompanionMode.RUNNING_APP, CompanionState.payload.value.mode)
    }

    @Test
    fun `orphaned primary host clears its exact session only when Main resumes`() {
        val operation = UUID(0L, 113L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(operation, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(operation, app.packageName, nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(operation, app.packageName, nowMonotonicMs = 12L))

        assertEquals(
            PrimaryHostOrphanSettlement.MARKED,
            markCompanionRunningAppPrimaryHostOrphaned(operation, app.packageName),
        )
        assertEquals(app, CompanionRunningAppState.running.value)
        assertTrue(noteCompanionRunningAppMainResumed())
        assertNull(CompanionRunningAppState.running.value)
        assertEquals(shelf, CompanionState.payload.value)
    }

    @Test
    fun `orphan ticket from session A cannot clear replacement session B`() {
        val first = UUID(0L, 114L)
        val second = UUID(0L, 115L)
        val appA = CompanionRunningApp("com.clock", "Clock")
        val appB = CompanionRunningApp("com.calendar", "Calendar")
        assertTrue(CompanionRunningAppState.stageTransfer(first, appA, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(first, appA.packageName, nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(first, appA.packageName, nowMonotonicMs = 12L))
        assertEquals(
            PrimaryHostOrphanSettlement.MARKED,
            markCompanionRunningAppPrimaryHostOrphaned(first, appA.packageName),
        )
        assertFalse(CompanionRunningAppState.stageTransfer(second, appB, nowMonotonicMs = 13L))
        assertTrue(noteCompanionRunningAppMainResumed())
        assertTrue(CompanionRunningAppState.stageTransfer(second, appB, nowMonotonicMs = 13L))
        assertTrue(claimAndBegin(second, appB.packageName, nowMonotonicMs = 14L))
        assertTrue(confirmCompanionRunningAppTransfer(second, appB.packageName, nowMonotonicMs = 15L))

        assertFalse(noteCompanionRunningAppMainResumed())
        assertEquals(appB, CompanionRunningAppState.running.value)
        assertEquals(CompanionMode.RUNNING_APP, CompanionState.payload.value.mode)
    }

    @Test
    fun `transfer revalidation requires the exact yielded host token and package`() {
        val hostOperationId = OperationId.create()
        val host = HostLease(
            operationId = hostOperationId,
            packageName = "com.clock",
            displayId = 4,
            launchMode = "secondary_app",
            primaryOrigin = PrimaryReturnOrigin("library"),
        )
        val snapshot = CompanionRuntimeSnapshot(
            stateVersion = 5L,
            displayId = 4,
            disposition = SecondaryDisplayDisposition.Yielded(host, YieldEvidence.HOST_COVERED),
            rendererOwnerId = null,
            mirrorActive = false,
            launchLockOperationId = null,
            launchSettlement = null,
        )

        assertTrue(snapshot.isYieldedHost("com.clock", hostOperationId))
        assertFalse(snapshot.isYieldedHost("com.other", hostOperationId))
        assertFalse(snapshot.isYieldedHost("com.clock", OperationId.create()))
        assertFalse(
            snapshot.copy(disposition = SecondaryDisplayDisposition.NoDisplay)
                .isYieldedHost("com.clock", hostOperationId),
        )
    }

    @Test
    fun `metadata prefers native shelf identity then tracked fallback`() {
        val tracked = listOf(AppEntry("com.clock", "Tracked Clock", 4, 1L))
        val native = NativeShelfSnapshot(
            apps = listOf(
                NativeShelfRow(
                    packageName = "com.clock",
                    label = "Clock",
                    launchActivity = null,
                    isGame = false,
                    iconPath = "/icons/native-clock.png",
                ),
            ),
        )

        assertEquals(
            CompanionRunningApp("com.clock", "Clock", "/icons/native-clock.png"),
            companionRunningAppMetadata("com.clock", tracked, native),
        )
        assertEquals(
            CompanionRunningApp("com.other", "Other", "/icons/cached.png"),
            companionRunningAppMetadata(
                packageName = "com.other",
                trackedApps = listOf(AppEntry("com.other", "Other", 4, 2L)),
                nativeSnapshot = NativeShelfSnapshot(),
                cachedIconPaths = mapOf("com.other" to "/icons/cached.png"),
            ),
        )
    }

    // For Main occupancy, the session ledger is the one record of what
    // occupies Main, and the card is a read-only view of it. Before the ruling the card had its own writer, so a Main
    // session the ledger ended by another route left the card claiming Main while SWAP's ledger read said it was free.
    @Test
    fun `the card is a view of the ledger - a Main session ended elsewhere leaves no card`() {
        val operation = UUID(0L, 120L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(operation, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(operation, app.packageName, nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(operation, app.packageName, nowMonotonicMs = 12L))
        assertEquals(app, CompanionRunningAppState.running.value)

        // The settlement the process runtime applies when a Main app lands on the Bottom.
        assertTrue(MimirSessionAuthority.endPrimaryAppForSecondaryMove(app.packageName))

        assertNull(MimirSessionAuthority.state.value.primaryOccupant())
        assertNull("the card must not outlive the ledger's Main session", CompanionRunningAppState.running.value)
    }

    @Test
    fun `a coverage confirm the ledger no longer accepts publishes no card`() {
        val operation = UUID(0L, 121L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(operation, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(operation, app.packageName, nowMonotonicMs = 11L))
        // Main resumed while the transfer was still dispatched: MainActivity's return settlement ends that session in
        // the ledger before the display-0 host observes coverage.
        val ticket = requireNotNull(MimirSessionAuthority.beginPrimaryAppReturn(primaryAppSessionOperation(operation)))
        assertTrue(MimirSessionAuthority.completePrimaryAppReturn(ticket.operationId, foregroundProven = false))

        assertFalse(confirmCompanionRunningAppTransfer(operation, app.packageName, nowMonotonicMs = 12L))
        assertNull(CompanionRunningAppState.running.value)
        assertEquals(shelf, CompanionState.payload.value)
    }

    @Test
    fun `the ledger carries the icon the Main card shows`() {
        val operation = UUID(0L, 122L)
        val app = CompanionRunningApp("com.clock", "Clock", "/icons/clock.png")
        assertTrue(CompanionRunningAppState.stageTransfer(operation, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(operation, app.packageName, nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(operation, app.packageName, nowMonotonicMs = 12L))

        assertEquals(app, MimirSessionAuthority.state.value.primaryOccupant())
        assertEquals(app, CompanionRunningAppState.running.value)
    }

    @Test
    fun `a dock-launched Main app landing on the Bottom settles its ledger session at once for the next leg`() {
        val app = CompanionRunningApp("com.android.chrome", "Chrome")
        val operation = requireNotNull(MimirSessionAuthority.preparePrimaryApp(app, PrimaryReturnOrigin("HOME")))
        try {
            assertTrue(MimirSessionAuthority.appDispatched(operation))
            assertEquals(app, MimirSessionAuthority.state.value.primaryOccupant())

            val settlement = settleCompanionMainAppLandedOnBottom(landedLease(UUID(0L, 123L), app.packageName))

            assertTrue(settlement.settled)
            assertNull(settlement.occupant)
            assertEquals(MimirSessionPhase.ENDED, MimirSessionAuthority.state.value.primaryAppSession?.phase)
            // Leg 3 is admitted by the ledger's own Main mutex, with no other collector left to wait for.
            val next = MimirSessionAuthority.preparePrimaryApp(
                CompanionRunningApp("com.google.android.youtube", "YouTube"),
                PrimaryReturnOrigin("HOME"),
            )
            assertNotNull(next)
            MimirSessionAuthority.appFailed(next!!, "test_cleanup")
        } finally {
            MimirSessionAuthority.appFailed(operation, "test_cleanup")
        }
    }

    @Test
    fun `a transferred Main app landing on the Bottom ends through its exact armed return and clears the card`() {
        val primary = UUID(0L, 124L)
        val secondary = UUID(0L, 125L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(primary, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(primary, app.packageName, nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(primary, app.packageName, nowMonotonicMs = 12L))
        assertTrue(CompanionRunningAppState.armSecondaryReturn(secondary, app.packageName))

        val settlement = settleCompanionMainAppLandedOnBottom(landedLease(secondary, app.packageName))

        assertTrue(settlement.settled)
        assertNull(settlement.occupant)
        assertNull(CompanionRunningAppState.running.value)
        assertEquals(shelf, CompanionState.payload.value)
        // The process runtime's Yielded collector applies the same settlement afterwards; it is a no-op by then.
        assertFalse(confirmCompanionRunningAppSecondaryReturn(secondary, app.packageName))
        assertFalse(MimirSessionAuthority.endPrimaryAppForSecondaryMove(app.packageName))
    }

    @Test
    fun `a landing for another package or a Bottom game leaves the Main session in place`() {
        val operation = UUID(0L, 126L)
        val app = CompanionRunningApp("com.clock", "Clock")
        assertTrue(CompanionRunningAppState.stageTransfer(operation, app, nowMonotonicMs = 10L))
        assertTrue(claimAndBegin(operation, app.packageName, nowMonotonicMs = 11L))
        assertTrue(confirmCompanionRunningAppTransfer(operation, app.packageName, nowMonotonicMs = 12L))

        val other = settleCompanionMainAppLandedOnBottom(landedLease(UUID(0L, 127L), "com.other"))
        assertFalse(other.settled)
        assertEquals(app, other.occupant)

        val game = settleCompanionMainAppLandedOnBottom(
            landedLease(UUID(0L, 128L), app.packageName)
                .copy(launchMode = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME),
        )
        assertFalse(game.settled)
        assertEquals(app, game.occupant)
        assertEquals(app, CompanionRunningAppState.running.value)
    }

    private fun landedLease(operationId: UUID, packageName: String) = HostLease(
        operationId = requireNotNull(OperationId.parseOrNull(operationId.toString())),
        packageName = packageName,
        displayId = 4,
        launchMode = CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER,
        primaryOrigin = PrimaryReturnOrigin("library"),
    )

    private fun claimAndBegin(operationId: UUID, packageName: String, nowMonotonicMs: Long): Boolean =
        claimCompanionRunningAppPrimaryHostLaunch(operationId, packageName, nowMonotonicMs) &&
            beginCompanionRunningAppPrimaryHostDispatch(operationId, packageName, nowMonotonicMs)

}
