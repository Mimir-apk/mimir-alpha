package com.gruning.mimir.rewrite

import android.app.usage.UsageEvents
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O14 : When a game tab clears, finish its elapsed measurement at the emulator's last foreground activity
 * from Usage Access, counting the crashed session while excluding time after the game ended.
 *
 * Thor test unit, 2026-09-12: Minish Cap on the Bottom, RetroArch `am_proc_start` 01:26:26.529, Mimir's process ended
 * 01:26:32.045, RetroArch `am_proc_died` 01:26:44.999. The clock values below are test values shaped like that session:
 * a start, a restart 12 s later, an exit about 18.5 s after the start, and a Tabs request minutes later.
 */
class BottomOrphanPlayTimeTest {

    @Test
    fun `a dead process's Bottom measurement closes at the emulator's first move out of the foreground`() {
        val reads = mutableListOf<Pair<Long, Long>>()

        val decision = decide(
            reader = { from, to ->
                reads += from to to
                listOf(
                    retroArch(StartElapsed + 700, Resumed),
                    retroArch(ExitElapsed, Paused),
                    retroArch(ExitElapsed + 400, Stopped),
                )
            },
        )

        assertEquals(
            BottomOrphanPlayTimeDecision.Finish(
                returnToken = Token,
                startElapsedMillis = StartElapsed,
                endElapsedMillis = ExitElapsed,
                endWallClockMs = wall(ExitElapsed),
                endSource = "ACTIVITY_PAUSED",
                returnedToForegroundLater = false,
            ),
            decision,
        )
        // The read spans the measurement's start to now, on the event clock.
        assertEquals(listOf(wall(StartElapsed) to wall(NowElapsed)), reads)

        // Closed by the store's own reducer: the span from start to exit is added and `active` clears.
        val finish = assertFinish(decision)
        val closed = finalizeHomeExternalSession(document(), finish.returnToken, finish.endElapsedMillis, Boot)
        assertEquals(PriorTotal + (ExitElapsed - StartElapsed), closed.elapsedTotals[MinishCap])
        assertNull(closed.active)
    }

    @Test
    fun `a stop with no pause before it ends the session too`() {
        val decision = decide(
            reader = { _, _ -> listOf(retroArch(StartElapsed + 700, Resumed), retroArch(ExitElapsed, Stopped)) },
        )

        val finish = assertFinish(decision)
        assertEquals(ExitElapsed, finish.endElapsedMillis)
        assertEquals("ACTIVITY_STOPPED", finish.endSource)
    }

    @Test
    fun `a later return to the foreground is reported and not counted`() {
        val decision = decide(
            reader = { _, _ ->
                listOf(
                    retroArch(StartElapsed + 700, Resumed),
                    retroArch(ExitElapsed, Paused),
                    retroArch(ExitElapsed + 60_000, Resumed),
                    retroArch(ExitElapsed + 300_000, Paused),
                )
            },
        )

        val finish = assertFinish(decision)
        assertEquals(ExitElapsed, finish.endElapsedMillis)
        assertTrue(finish.returnedToForegroundLater)
    }

    @Test
    fun `pauses before the start and another app's pauses do not end the session`() {
        val decision = decide(
            reader = { _, _ ->
                listOf(
                    retroArch(StartElapsed - 5_000, Paused),
                    retroArch(StartElapsed + 2_000, Paused, packageName = "com.google.android.youtube"),
                    retroArch(StartElapsed + 700, Resumed),
                    retroArch(ExitElapsed, Paused),
                )
            },
        )

        assertEquals(ExitElapsed, assertFinish(decision).endElapsedMillis)
    }

    @Test
    fun `without Usage Access the measurement is left open and the event log is not read`() {
        var reads = 0

        val decision = decide(
            usageAccess = false,
            reader = { _, _ ->
                reads++
                listOf(retroArch(StartElapsed + 700, Resumed), retroArch(ExitElapsed, Paused))
            },
        )

        assertEquals(BottomOrphanPlayTimeDecision.Leave("usage_access_missing"), decision)
        assertEquals(0, reads)
    }

    @Test
    fun `an unreadable event log or no exit since the start leaves the measurement open`() {
        assertEquals(BottomOrphanPlayTimeDecision.Leave("usage_events_unreadable"), decide(reader = { _, _ -> null }))
        assertEquals(
            BottomOrphanPlayTimeDecision.Leave("no_qualifying_event"),
            decide(reader = { _, _ -> listOf(retroArch(StartElapsed + 700, Resumed)) }),
        )
        // A killed process reports no pause (see launcherOrRecentsOnMain): its death alone is not an exit.
        assertEquals(BottomOrphanPlayTimeDecision.Leave("no_qualifying_event"), decide(reader = { _, _ -> emptyList() }))
    }

    @Test
    fun `a measurement from another boot, or with no readable boot, is left open and nothing is read`() {
        var reads = 0
        val reader: (Long, Long) -> List<MainHomeActivityEvent> = { _, _ ->
            reads++
            listOf(retroArch(StartElapsed + 700, Resumed), retroArch(ExitElapsed, Paused))
        }

        assertEquals(BottomOrphanPlayTimeDecision.Leave("different_boot"), decide(bootCount = Boot + 1, reader = reader))
        assertEquals(BottomOrphanPlayTimeDecision.Leave("boot_count_unreadable"), decide(bootCount = null, reader = reader))
        assertEquals(0, reads)
    }

    @Test
    fun `a tab for another game, or one naming no game, does not close this measurement`() {
        assertEquals(
            BottomOrphanPlayTimeDecision.Leave("different_game"),
            decide(tab = BottomGameTabIdentity(RetroArch, "catalog:snes:chrono-trigger")),
        )
        assertEquals(
            BottomOrphanPlayTimeDecision.Leave("tab_names_no_game"),
            decide(tab = BottomGameTabIdentity(RetroArch, null)),
        )
        assertEquals(
            BottomOrphanPlayTimeDecision.Leave("tab_names_no_game"),
            decide(tab = BottomGameTabIdentity(RetroArch, " ")),
        )
    }

    @Test
    fun `a game two tabs name is ambiguous and left open`() {
        assertEquals(
            BottomOrphanPlayTimeDecision.Leave("ambiguous_match"),
            decide(others = listOf(BottomGameTabIdentity("org.ppsspp.ppsspp", MinishId))),
        )
        // Control, one field apart: the other tab names another game, so this one is attributable.
        assertFinish(decide(others = listOf(BottomGameTabIdentity("org.ppsspp.ppsspp", "catalog:psp:other"))))
    }

    @Test
    fun `only an open Bottom measurement an earlier process began is closed here`() {
        assertEquals(BottomOrphanPlayTimeDecision.Leave("no_open_measurement"), decide(active = null))
        assertEquals(
            BottomOrphanPlayTimeDecision.Leave("measurement_not_bottom"),
            decide(active = measurement(placement = MimirGamePlacement.MAIN)),
        )
        assertEquals(
            BottomOrphanPlayTimeDecision.Leave("measurement_not_bottom"),
            decide(active = measurement(placement = null)),
        )
        // This process began it: its own return path owns it.
        assertEquals(
            BottomOrphanPlayTimeDecision.Leave("begun_by_this_process"),
            decide(active = measurement(dispatchedAt = ProcessStart)),
        )
        // Control, one millisecond earlier: an earlier process began it.
        assertFinish(decide(active = measurement(dispatchedAt = ProcessStart - 1)))
    }

    @Test
    fun `the decision line names the end, its source and what the store added`() {
        val tab = BottomGameTabIdentity(RetroArch, MinishId)
        val finish = BottomOrphanPlayTimeDecision.Finish(
            returnToken = Token,
            startElapsedMillis = StartElapsed,
            endElapsedMillis = ExitElapsed,
            endWallClockMs = wall(ExitElapsed),
            endSource = "ACTIVITY_PAUSED",
            returnedToForegroundLater = false,
        )

        assertEquals(
            "bottom play time decision=finished reason=last_activity game=$MinishId package=$RetroArch end=01:26:44.990 " +
                "endWallMs=${wall(ExitElapsed)} endSource=ACTIVITY_PAUSED measuredMs=18470 addedMs=18470 " +
                "returnedLater=false finalized=true",
            bottomOrphanPlayTimeLogLine(finish, tab, endLocalTime = "01:26:44.990", addedMs = 18_470L, finalized = true),
        )
        assertEquals(
            "bottom play time decision=left reason=usage_access_missing game=$MinishId package=$RetroArch",
            bottomOrphanPlayTimeLogLine(BottomOrphanPlayTimeDecision.Leave("usage_access_missing"), tab),
        )
    }

    /** Fails as an assertion, naming the decision, when the measurement was not closed. */
    private fun assertFinish(decision: BottomOrphanPlayTimeDecision): BottomOrphanPlayTimeDecision.Finish {
        assertTrue(
            "expected the measurement to be closed, but the decision was $decision",
            decision is BottomOrphanPlayTimeDecision.Finish,
        )
        return decision as BottomOrphanPlayTimeDecision.Finish
    }

    private fun decide(
        active: HomeExternalSession? = measurement(),
        tab: BottomGameTabIdentity = BottomGameTabIdentity(RetroArch, MinishId),
        others: List<BottomGameTabIdentity> = emptyList(),
        bootCount: Int? = Boot,
        usageAccess: Boolean = true,
        reader: (Long, Long) -> List<MainHomeActivityEvent>? = { _, _ ->
            listOf(retroArch(StartElapsed + 700, Resumed), retroArch(ExitElapsed, Paused))
        },
    ): BottomOrphanPlayTimeDecision = bottomOrphanPlayTimeDecision(
        active = active,
        retired = tab,
        otherGameTabs = others,
        currentBootCount = bootCount,
        processStartElapsedMillis = ProcessStart,
        nowElapsedMillis = NowElapsed,
        nowWallClockMs = wall(NowElapsed),
        usageAccessGranted = usageAccess,
        activityEvents = reader,
    )

    private fun measurement(
        placement: MimirGamePlacement? = MimirGamePlacement.BOTTOM,
        dispatchedAt: Long = StartElapsed,
    ) = HomeExternalSession(MinishCap, Token, dispatchedAt, Boot, placement)

    private fun document() = HomeExternalSessionElapsedDocument(
        elapsedTotals = mapOf(MinishCap to PriorTotal),
        active = measurement(),
    )

    private fun wall(elapsed: Long): Long = elapsed + WallMinusElapsed

    private fun retroArch(elapsed: Long, type: Int, packageName: String = RetroArch) = MainHomeActivityEvent(
        timeMs = wall(elapsed),
        packageName = packageName,
        className = "com.retroarch.browser.retroactivity.RetroActivityFuture",
        type = type,
    )

    private companion object {
        const val Boot = 3
        const val Token = "minish-token"
        const val RetroArch = "com.retroarch.aarch64"
        const val MinishId = "catalog:gba:zelda-minish-cap"
        const val StartElapsed = 400_000L
        const val ProcessStart = 412_000L
        const val ExitElapsed = 418_470L
        const val NowElapsed = 1_400_000L
        const val WallMinusElapsed = 1_000_000_000L
        const val PriorTotal = 14_698L
        val Resumed = UsageEvents.Event.ACTIVITY_RESUMED
        val Paused = UsageEvents.Event.ACTIVITY_PAUSED
        val Stopped = UsageEvents.Event.ACTIVITY_STOPPED
        val MinishCap = HomeShelfItemId.Emulated(GameId(MinishId))
    }
}

/** Source pins for O14's Android half, which no JVM test drives; the decision itself is [BottomOrphanPlayTimeTest]. */
class BottomOrphanPlayTimeWiringTest {
    @Test
    fun `the close is the store's own token-matched finish at the decided end, off the main thread`() {
        val runner = appSource("BottomOrphanPlayTime.kt").substringAfter("internal object BottomOrphanPlayTime {", "")

        listOf(
            "CompanionProcessRuntime.processScope.launch(Dispatchers.IO + NonCancellable)",
            "HomeExternalSessionElapsedStore.activeMeasurement(app)",
            "currentBootCount = HomeExternalSessionElapsedStore.bootCountNow(app),",
            "processStartElapsedMillis = Process.getStartElapsedRealtime(),",
            "val platform = AndroidMainHomeRecoveryPlatform(app)",
            "usageAccessGranted = platform.usageAccessGranted(),",
            "activityEvents = platform::activityEvents,",
            "HomeExternalSessionElapsedStore.finalize(",
            "returnToken = finish.returnToken,",
            "nowElapsedMillis = finish.endElapsedMillis,",
        ).forEach { expected -> assertTrue("missing: $expected", runner.contains(expected)) }
        // No second accounting path: the runner never reduces or writes a document itself.
        assertFalse(runner.contains("finalizeHomeExternalSession("))
        assertFalse(runner.contains("getSharedPreferences("))
    }

    private fun appSource(name: String): String =
        listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("source file not found from ${File(".").absolutePath}")
}
