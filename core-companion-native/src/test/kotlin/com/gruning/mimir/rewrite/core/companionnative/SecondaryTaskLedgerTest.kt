package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SecondaryTaskLedgerTest {
    @Test
    fun `relaunch refiles the app at the newest end`() {
        val first = entry()
        val second = first.copy(packageName = "org.example.second", label = "Second", lastSeenAtMs = 200L)
        val relaunched = first.copy(lastSeenAtMs = 300L)
        val ledger = SecondaryTaskLedger()
        ledger.noteLaunched(first)
        ledger.noteLaunched(second)
        ledger.noteLaunched(relaunched)

        assertEquals(listOf(second, relaunched), ledger.ordered())
    }

    @Test
    fun `restore preserves order resets close state and keeps existing packages`() {
        val first = entry()
        val second = first.copy(packageName = "org.example.second", label = "Second", lastSeenAtMs = 200L)
        val saved = SecondaryTaskLedger { UUID(0L, 51L) }
        saved.noteLaunched(first)
        saved.noteLaunched(second)
        saved.requestClose(second.packageName, 300L)

        val restored = SecondaryTaskLedger()
        restored.restore(saved.ordered())
        assertEquals(listOf(first, second), restored.ordered())

        val newest = first.copy(packageName = "org.example.newest", label = "Newest", lastSeenAtMs = 400L)
        restored.restore(listOf(second.copy(label = "Stale"), newest, first.copy(label = "Stale")))
        assertEquals(listOf(first, second, newest), restored.ordered())
    }

    @Test
    fun closeRequestAndTimeoutRetainTrackedTabAsUnconfirmed() {
        val operationId = UUID(0L, 11L)
        val ledger = SecondaryTaskLedger { operationId }.apply { noteLaunched(entry()) }

        val request = ledger.requestClose("org.example.game", requestedAtElapsedRealtimeMs = 1_000L)

        assertEquals(operationId, request?.operationId)
        assertEquals(1, ledger.values().size)
        assertEquals(
            SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED,
            ledger.entry("org.example.game")?.closeState,
        )
        assertTrue(ledger.markCloseUnconfirmed(operationId))
        assertEquals(1, ledger.values().size)
        assertEquals(
            SecondaryTaskCloseState.CLOSE_UNCONFIRMED,
            ledger.entry("org.example.game")?.closeState,
        )
    }

    @Test
    fun dispatchUsageStatsAndProcessAbsenceAreNeverTerminalEvidence() {
        val operationId = UUID(0L, 12L)
        val ledger = SecondaryTaskLedger { operationId }.apply { noteLaunched(entry()) }
        ledger.requestClose("org.example.game", requestedAtElapsedRealtimeMs = 1_000L)

        val observations = listOf(
            SecondaryCloseObservation.RequestDispatched("org.example.game", operationId),
            SecondaryCloseObservation.UsageStatsBackground("org.example.game"),
            SecondaryCloseObservation.RunningProcessAbsent("org.example.game"),
        )

        observations.forEach { assertFalse(ledger.observe(it)) }
        assertEquals(1, ledger.values().size)
        assertEquals(
            SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED,
            ledger.entry("org.example.game")?.closeState,
        )
    }

    @Test
    fun packageRemovalIsTerminalAndForgetIsSeparateTrackingAction() {
        val ledger = SecondaryTaskLedger { UUID(0L, 13L) }.apply { noteLaunched(entry()) }
        ledger.requestClose("org.example.game", requestedAtElapsedRealtimeMs = 1_000L)

        assertTrue(ledger.observe(SecondaryCloseObservation.PackageRemoved("org.example.game")))
        assertTrue(ledger.values().isEmpty())

        ledger.noteLaunched(entry())
        assertTrue(ledger.forget("org.example.game"))
        assertTrue(ledger.values().isEmpty())
    }

    @Test
    fun staleTimeoutCannotDowngradeNewerCloseOperation() {
        val ids = ArrayDeque(listOf(UUID(0L, 21L), UUID(0L, 22L)))
        val ledger = SecondaryTaskLedger { ids.removeFirst() }.apply { noteLaunched(entry()) }
        val first = ledger.requestClose("org.example.game", 1_000L)!!
        val second = ledger.requestClose("org.example.game", 2_000L)!!

        assertFalse(ledger.markCloseUnconfirmed(first.operationId))
        assertTrue(ledger.markCloseUnconfirmed(second.operationId))
    }

    @Test
    fun `close-then-forget removes the tab and a stale timeout cannot resurrect it`() {
        // Close sequence: the ✕ composes requestClose (kill needs the live entry) + forget (tab leaves NOW).
        val operationId = UUID(0L, 41L)
        val ledger = SecondaryTaskLedger { operationId }.apply { noteLaunched(entry()) }

        val request = ledger.requestClose("org.example.game", requestedAtElapsedRealtimeMs = 1_000L)
        assertEquals(operationId, request?.operationId)
        assertTrue(ledger.forget("org.example.game"))
        assertTrue(ledger.values().isEmpty())

        // The 5s unconfirm timeout landing after the forget must not bring back a ghost "Close unconfirmed".
        assertFalse(ledger.markCloseUnconfirmed(operationId))
        assertTrue(ledger.values().isEmpty())
    }

    @Test
    fun `swap result is tokenized and remains dispatch honest`() {
        val result = SecondarySwapResult(
            packageName = "org.example.game",
            operationId = UUID(0L, 31L),
            targetDisplayId = 0,
            dispatchAccepted = true,
        )

        assertTrue(result.dispatchAccepted)
        assertFalse(result.transferConfirmed)
        assertTrue(result.truth.contains("not display-ownership evidence"))
    }

    @Test
    fun `a close request carries the tracked task id and a restored tab carries none`() {
        val ledger = SecondaryTaskLedger { UUID(0L, 71L) }.apply { noteLaunched(entry()) }

        assertTrue(ledger.noteTaskId("org.example.game", 41))
        assertEquals(41, ledger.taskId("org.example.game"))
        assertEquals(41, ledger.requestClose("org.example.game", 1_000L)?.taskId)

        // An id is only meaningful for the boot that issued it, so nothing invents one for an
        // untracked package, for a restored tab, or from a rejected value.
        assertFalse(ledger.noteTaskId("org.example.untracked", 42))
        assertFalse(ledger.noteTaskId("org.example.game", 0))
        assertFalse(ledger.noteTaskId("org.example.game", -1))
        assertEquals(41, ledger.taskId("org.example.game"))

        val restored = SecondaryTaskLedger { UUID(0L, 72L) }
        restored.restore(listOf(entry()))
        assertNull(restored.taskId("org.example.game"))
        assertNull(restored.requestClose("org.example.game", 1_000L)?.taskId)
    }

    @Test
    fun `a verified close is terminal while advisory evidence still is not`() {
        val operationId = UUID(0L, 73L)
        val ledger = SecondaryTaskLedger { operationId }.apply { noteLaunched(entry()) }
        ledger.noteTaskId("org.example.game", 41)
        val request = ledger.requestClose("org.example.game", 1_000L)!!

        // The advisory samples still cannot retire the row -- only the privileged readback may.
        assertFalse(ledger.observe(SecondaryCloseObservation.RunningProcessAbsent("org.example.game")))
        assertEquals(1, ledger.values().size)

        assertTrue(ledger.markCloseVerified(request.operationId))
        assertTrue(ledger.values().isEmpty())
        assertNull(ledger.taskId("org.example.game"))
        // A late timeout for the same operation cannot resurrect a "Close unconfirmed" ghost.
        assertFalse(ledger.markCloseUnconfirmed(operationId))
    }

    @Test
    fun `a stale verification cannot retire a newer close`() {
        val ids = ArrayDeque(listOf(UUID(0L, 74L), UUID(0L, 75L)))
        val ledger = SecondaryTaskLedger { ids.removeFirst() }.apply { noteLaunched(entry()) }
        val first = ledger.requestClose("org.example.game", 1_000L)!!
        val second = ledger.requestClose("org.example.game", 2_000L)!!

        assertFalse(ledger.markCloseVerified(first.operationId))
        assertEquals(1, ledger.values().size)
        assertTrue(ledger.markCloseVerified(second.operationId))
        assertTrue(ledger.values().isEmpty())
    }

    @Test
    fun `forgetting a tab drops its task id so a later close cannot spend a stale one`() {
        val ledger = SecondaryTaskLedger { UUID(0L, 76L) }.apply { noteLaunched(entry()) }
        ledger.noteTaskId("org.example.game", 41)

        assertTrue(ledger.forget("org.example.game"))
        assertNull(ledger.taskId("org.example.game"))

        ledger.noteLaunched(entry())
        assertNull(ledger.requestClose("org.example.game", 1_000L)?.taskId)
    }

    @Test
    fun `process absence verified by the authority retires an open app row and its task id`() {
        // Round-5 B1: YouTube stopped outside Mimir kept its tab. The privileged pidof readback is the
        // evidence that retires it; the non-privileged process-list sample still cannot.
        val ledger = SecondaryTaskLedger().apply { noteLaunched(entry()) }
        ledger.noteTaskId("org.example.game", 41)

        assertFalse(ledger.observe(SecondaryCloseObservation.RunningProcessAbsent("org.example.game")))
        assertEquals(1, ledger.values().size)

        assertTrue(ledger.observe(SecondaryCloseObservation.ProcessAbsentVerified("org.example.game")))
        assertNull(ledger.entry("org.example.game"))
        assertNull(ledger.taskId("org.example.game"))
        // Nothing is left to retire: a second readback for the same package is not a second removal.
        assertFalse(ledger.observe(SecondaryCloseObservation.ProcessAbsentVerified("org.example.game")))
    }

    @Test
    fun `a verified absence retires an open game tab and its task id, never a tab a close already owns`() {
        // Round 5b (Thor, 2026-09-12): a GAME row outlived its emulator because this refused it and nothing else
        // asked. What its ✕ also settles, the Bottom game session, the registry now settles on retirement.
        val ledger = SecondaryTaskLedger { UUID(0L, 77L) }
        ledger.noteLaunched(gameEntry())
        ledger.noteTaskId("org.example.emulator", 42)
        ledger.noteLaunched(entry())
        ledger.requestClose("org.example.game", 1_000L)

        assertTrue(ledger.observe(SecondaryCloseObservation.ProcessAbsentVerified("org.example.emulator")))
        assertNull(ledger.entry("org.example.emulator"))
        assertNull(ledger.taskId("org.example.emulator"))
        // A close in flight keeps its own evidence path: a verified close, or the unconfirmed timeout.
        assertFalse(ledger.observe(SecondaryCloseObservation.ProcessAbsentVerified("org.example.game")))
        assertEquals(
            SecondaryTaskCloseState.CLOSE_REQUESTED_UNVERIFIED,
            ledger.entry("org.example.game")?.closeState,
        )
    }

    // ---------------------------------------------------------------------- tabs liveness and tabs.v1
    //
    // Observed on Thor: `shared_prefs/mimir_secondary_tabs.xml` `tabs.v1` still held Minish
    // Cap's row at 01:44 and 01:49 with no RetroArch process. [THOR_TABS_V1] carries the fields the evidence
    // quotes for that row; the fields it elides and the whole YouTube row are test values.

    @Test
    fun `the orphaned game row leaves tabs v1 once its emulator is proven absent`() {
        val ledger = restoredFromThorTabs()
        val absent = secondaryTabsToRetire(ledger.ordered()) { SecondaryPackageLiveness.ABSENT }

        val retired = ledger.retireProbedAbsent(absent) { false }

        assertEquals(listOf(YOUTUBE, RETROARCH), retired.map(AppEntry::packageName))
        assertEquals(emptyList<AppEntry>(), SecondaryTabsCodec.decode(ledger.persistedTabsDocument()))
    }

    @Test
    fun `a running emulator keeps its game row in tabs v1`() {
        val ledger = restoredFromThorTabs()
        val absent = secondaryTabsToRetire(ledger.ordered()) { pkg ->
            if (pkg == RETROARCH) SecondaryPackageLiveness.ALIVE else SecondaryPackageLiveness.ABSENT
        }

        ledger.retireProbedAbsent(absent) { false }

        val persisted = SecondaryTabsCodec.decode(ledger.persistedTabsDocument())
        assertEquals(listOf(RETROARCH), persisted.map(AppEntry::packageName))
        assertEquals(SecondaryTaskKind.GAME, persisted.single().kind)
        assertEquals("The Legend of Zelda : The Minish Cap", persisted.single().gameTitle)
    }

    @Test
    fun `a probed absence spares a row a launch re-filed and a game launch still starting`() {
        val probedGame = gameEntry()
        val probedApp = entry()
        val probed = listOf(probedGame, probedApp)

        // Re-filed after the probe: the newer launch's row is not the row that was asked about.
        val refiled = SecondaryTaskLedger().apply {
            noteLaunched(probedGame.copy(lastSeenAtMs = 900L))
            noteLaunched(probedApp.copy(lastSeenAtMs = 900L))
        }
        assertEquals(emptyList<AppEntry>(), refiled.retireProbedAbsent(probed) { false })
        assertEquals(2, refiled.values().size)

        // A Bottom game launch is starting for the emulator, so its row stays; the app row never reads that.
        val starting = SecondaryTaskLedger().apply {
            noteLaunched(probedGame)
            noteLaunched(probedApp)
        }
        assertEquals(listOf(probedApp), starting.retireProbedAbsent(probed) { true })
        assertEquals(SecondaryTaskKind.GAME, starting.entry("org.example.emulator")?.kind)

        // Control, one field apart from each: the rows as probed, nothing starting. Both retire.
        val control = SecondaryTaskLedger().apply {
            noteLaunched(probedGame)
            noteLaunched(probedApp)
        }
        assertEquals(probed, control.retireProbedAbsent(probed) { false })
        assertTrue(control.values().isEmpty())
    }

    // ------------------------------------------------------------------------------- GAME tabs
    //
    //  The row is what survives the
    // reclaim, so these three -- created, kept across a reopen, removed by the ✕ -- are the fix.

    @Test
    fun `a game tab is tracked with its kind and identity`() {
        val ledger = SecondaryTaskLedger()
        ledger.noteLaunched(gameEntry())

        val tracked = ledger.entry("org.example.emulator")
        assertEquals(SecondaryTaskKind.GAME, tracked?.kind)
        assertEquals("Chrono Trigger", tracked?.gameTitle)
        assertEquals("game-7", tracked?.gameId)
        assertEquals("content://art/7", tracked?.gameArtRef)
    }

    @Test
    fun `reopening a game tab as an ordinary app launch keeps it a game`() {
        val ledger = SecondaryTaskLedger()
        ledger.noteLaunched(gameEntry())
        // The reopen is the SECONDARY_APP path on purpose -- relaunching the emulator package is what
        // brings its existing task forward -- so it arrives with no kind and no identity.
        ledger.noteLaunched(
            AppEntry(packageName = "org.example.emulator", label = "Emulator", displayId = 4, lastSeenAtMs = 900L),
        )

        val tracked = ledger.entry("org.example.emulator")
        assertEquals(SecondaryTaskKind.GAME, tracked?.kind)
        assertEquals("Chrono Trigger", tracked?.gameTitle)
        assertEquals("game-7", tracked?.gameId)
        assertEquals(900L, tracked?.lastSeenAtMs)
    }

    @Test
    fun `a verified close removes the game tab`() {
        val ledger = SecondaryTaskLedger { UUID(0L, 91L) }
        ledger.noteLaunched(gameEntry())
        val request = ledger.requestClose("org.example.emulator", 1_000L)

        assertTrue(ledger.markCloseVerified(requireNotNull(request).operationId))
        assertNull(ledger.entry("org.example.emulator"))
    }

    @Test
    fun `game identity arrives after dispatch and never erases what it does not know`() {
        val ledger = SecondaryTaskLedger()
        ledger.noteLaunched(gameEntry().copy(gameTitle = null, gameId = null, gameArtRef = null))

        assertTrue(ledger.noteGameIdentity("org.example.emulator", "Chrono Trigger", "game-7", null))
        assertEquals("Chrono Trigger", ledger.entry("org.example.emulator")?.gameTitle)
        // A later enrichment that knows less must not blank the name already on screen.
        assertFalse(ledger.noteGameIdentity("org.example.emulator", "  ", null, null))
        assertEquals("Chrono Trigger", ledger.entry("org.example.emulator")?.gameTitle)
    }

    @Test
    fun `an app tab refuses game identity`() {
        val ledger = SecondaryTaskLedger().apply { noteLaunched(entry()) }

        assertFalse(ledger.noteGameIdentity("org.example.game", "Chrono Trigger", "game-7", null))
        assertNull(ledger.entry("org.example.game")?.gameTitle)
    }

    private fun entry() = AppEntry(
        packageName = "org.example.game",
        label = "Example",
        displayId = 4,
        lastSeenAtMs = 100L,
    )

    private fun gameEntry() = AppEntry(
        packageName = "org.example.emulator",
        label = "Emulator",
        displayId = 4,
        lastSeenAtMs = 100L,
        kind = SecondaryTaskKind.GAME,
        gameTitle = "Chrono Trigger",
        gameId = "game-7",
        gameArtRef = "content://art/7",
    )

    private fun restoredFromThorTabs(): SecondaryTaskLedger = SecondaryTaskLedger().apply {
        restore(SecondaryTabsCodec.decode(THOR_TABS_V1))
        assertEquals("the fixture decodes both rows", listOf(YOUTUBE, RETROARCH), ordered().map(AppEntry::packageName))
    }

    private companion object {
        const val YOUTUBE = "com.google.android.youtube"
        const val RETROARCH = "com.retroarch.aarch64"

        /**
         * The Thor's `tabs.v1`, as far as the evidence quotes it: package, label, displayId, kind and gameTitle
         * of the RetroArch row. Its lastSeenAtMs, iconPackageName, gameId and gameArtRef, and every YouTube
         * value, are test values.
         */
        val THOR_TABS_V1 = """
            [
              {"package":"com.google.android.youtube","label":"YouTube","displayId":4,"lastSeenAtMs":1000,
               "iconPackageName":"com.google.android.youtube","kind":"APP","gameTitle":null,"gameId":null,
               "gameArtRef":null},
              {"package":"com.retroarch.aarch64","label":"RetroArch (AArch64)","displayId":4,"kind":"GAME",
               "gameTitle":"The Legend of Zelda : The Minish Cap","lastSeenAtMs":2000,
               "iconPackageName":"com.retroarch.aarch64","gameId":null,"gameArtRef":null}
            ]
        """.trimIndent()
    }
}
