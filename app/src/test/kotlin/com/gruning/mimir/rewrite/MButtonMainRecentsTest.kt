package com.gruning.mimir.rewrite

import android.app.usage.UsageEvents
import com.gruning.mimir.rewrite.core.companionnative.AppEntry
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuOrigin
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * a light coin tap closes the Recents it opened and brings Main back to what was on top. What it decides and
 * what it sends are pinned here with fakes; that Main really lands where it was is the Thor's to say (DEVICE-UNCERTIFIED).
 * The one key this path presses opens Recents, and none of these tests lets it press while Recents is up.
 */
class MButtonMainRecentsTest {
    private val self = "com.gruning.mimir.rewrite"
    private val mimirMain = "$self/$self.MainActivity"
    private val gameA = "org.example.gamea/org.example.gamea.Main"
    private val appB = "org.example.appb/org.example.appb.Main"
    private val bottomApp = "org.example.bottom/org.example.bottom.Main"
    private val bottomFloor = "$self/$self.CompanionSecondaryHomeActivity"
    private val foreignLauncher = "com.android.launcher3/com.android.launcher3.Launcher"

    // Mimir's own HOME-declared MainActivity is NOT excluded; only the foreign launcher and the Bottom's floor are.
    private val exclusions = MainRecentsExclusions(
        components = setOf(foreignLauncher, bottomFloor),
        bottomPackages = setOf("org.example.bottom"),
        bottomComponents = setOf(bottomFloor),
    )

    private fun event(timeMs: Long, component: String, type: Int) =
        MainHomeActivityEvent(timeMs, component.substringBefore('/'), component.substringAfter('/'), type)

    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED
    private val stopped = UsageEvents.Event.ACTIVITY_STOPPED

    // ---- is Recents up, and what did it cover (opened by other means) ----

    @Test
    fun `Recents resumed last is up and Mimir's own MainActivity pausing as it resumed is what it covered`() {
        val reading = readMainRecents(
            listOf(event(1_000, mimirMain, resumed), event(9_800, mimirMain, paused), event(10_000, QuickstepRecentsComponent, resumed)),
            exclusions,
        )
        assertEquals(MainRecentsPresence.UP, reading.presence)
        assertEquals("Mimir is a valid prior top", mimirMain, reading.priorTop)
    }

    @Test
    fun `an app that paused as Recents resumed is what it covered`() {
        val reading = readMainRecents(
            listOf(event(9_800, gameA, paused), event(10_000, QuickstepRecentsComponent, resumed)),
            exclusions,
        )
        assertEquals(gameA, reading.priorTop)
    }

    @Test
    fun `Recents closed by other means reads as not up`() {
        val closed = readMainRecents(
            listOf(
                event(9_800, gameA, paused),
                event(10_000, QuickstepRecentsComponent, resumed),
                event(20_000, QuickstepRecentsComponent, paused),
                event(20_050, appB, resumed),
            ),
            exclusions,
        )
        assertEquals(MainRecentsPresence.NOT_UP, closed.presence)
        assertEquals(MainRecentsPresence.NOT_UP, readMainRecents(listOf(event(10_000, QuickstepRecentsComponent, stopped)), exclusions).presence)
    }

    @Test
    fun `a resume after Recents' own resume in the same snapshot says it was left, even with no pause written yet`() {
        val reading = readMainRecents(
            listOf(event(10_000, QuickstepRecentsComponent, resumed), event(10_400, appB, resumed)),
            exclusions,
        )
        assertEquals("the player picked app B: not up, so a tap opens", MainRecentsPresence.NOT_UP, reading.presence)
        assertEquals(RecentsTapPlan.Open, recentsTapPlan(reading))
    }

    @Test
    fun `the Bottom's own resumes never end a Main Recents`() {
        val reading = readMainRecents(
            listOf(
                event(9_800, gameA, paused),
                event(10_000, QuickstepRecentsComponent, resumed),
                event(10_300, bottomFloor, resumed),
                event(10_400, bottomApp, resumed),
            ),
            exclusions,
        )
        assertEquals(MainRecentsPresence.UP, reading.presence)
    }

    @Test
    fun `no Recents event is not up, an unreadable read is unknown`() {
        assertEquals(MainRecentsPresence.NOT_UP, readMainRecents(listOf(event(1, gameA, resumed)), exclusions).presence)
        assertEquals(MainRecentsPresence.UNKNOWN, readMainRecents(null, exclusions).presence)
    }

    @Test
    fun `a display-4 app's pause is never what Recents covered, and the launcher and Bottom floor are excluded`() {
        val onlyBottom = readMainRecents(
            listOf(
                event(9_800, bottomApp, paused),
                event(9_850, bottomFloor, paused),
                event(9_900, foreignLauncher, paused),
                event(10_000, QuickstepRecentsComponent, resumed),
            ),
            exclusions,
        )
        assertEquals(MainRecentsPresence.UP, onlyBottom.presence)
        assertNull("nothing on Main qualifies", onlyBottom.priorTop)

        val bottomAndMain = readMainRecents(
            listOf(event(9_800, bottomApp, paused), event(9_900, gameA, paused), event(10_000, QuickstepRecentsComponent, resumed)),
            exclusions,
        )
        assertEquals("the display-4 pause does not make it ambiguous", gameA, bottomAndMain.priorTop)
    }

    @Test
    fun `two candidates, or a pause outside the window, are no answer`() {
        val two = readMainRecents(
            listOf(event(9_900, gameA, paused), event(9_950, appB, paused), event(10_000, QuickstepRecentsComponent, resumed)),
            exclusions,
        )
        assertNull(two.priorTop)
        val late = readMainRecents(
            listOf(event(10_000 - MainRecentsPriorPauseWindowMs - 1, gameA, paused), event(10_000, QuickstepRecentsComponent, resumed)),
            exclusions,
        )
        assertNull(late.priorTop)
    }

    // ---- captured at OPEN ----

    @Test
    fun `the capture is the last resumed Main activity, Mimir's own MainActivity included`() {
        val events = listOf(
            event(1_000, gameA, resumed),
            event(2_000, gameA, paused),
            event(2_100, mimirMain, resumed),
            event(2_200, bottomApp, resumed),
            event(2_300, bottomFloor, resumed),
        )
        assertEquals("the Bottom's app and floor are skipped", mimirMain, capturePriorTop(events, exclusions))
        assertEquals(gameA, capturePriorTop(listOf(event(1_000, gameA, resumed), event(2_000, foreignLauncher, resumed)), exclusions))
        assertNull(capturePriorTop(listOf(event(1_000, bottomApp, resumed)), exclusions))
        assertNull(capturePriorTop(null, exclusions))
    }

    @Test
    fun `captured at open beats pause inference while it is the Recents our press opened`() {
        val record = MainRecentsOpenRecord(priorTop = gameA, openedAtMs = 9_950)
        val events = listOf(event(9_800, appB, paused), event(10_000, QuickstepRecentsComponent, resumed))

        assertEquals("the record, not the pause", gameA, readMainRecents(events, exclusions, record).priorTop)
        assertEquals("without a record the pause is all there is", appB, readMainRecents(events, exclusions).priorTop)
        assertEquals(
            "a null capture is unknown, so the pause identifies what Recents covered",
            appB,
            readMainRecents(events, exclusions, MainRecentsOpenRecord(null, 9_950)).priorTop,
        )
        assertNull(
            "and with no pause either it is no answer",
            readMainRecents(listOf(event(10_000, QuickstepRecentsComponent, resumed)), exclusions, MainRecentsOpenRecord(null, 9_950)).priorTop,
        )
    }

    @Test
    fun `a game in the foreground for twenty minutes, then Recents, then a tap, restores the game`() {
        val pressAt = 20 * 60_000L + 5_000L
        // The press reads a 15-minute window: the game resumed 20 minutes ago is not in it, so the capture is null.
        val capture = capturePriorTop(listOf(event(pressAt - 1_000, foreignLauncher, paused)), exclusions)
        assertNull("fixture: the long-running game left no resume to capture", capture)
        val record = MainRecentsOpenRecord(capture, pressAt)
        // Recents resumes after the press, and the game's pause is just before it.
        val events = listOf(
            event(1_000, gameA, resumed),
            event(pressAt + 290, gameA, paused),
            event(pressAt + 300, QuickstepRecentsComponent, resumed),
        )
        val reading = readMainRecents(events, exclusions, record)
        assertEquals(MainRecentsPresence.UP, reading.presence)
        assertEquals(gameA, reading.priorTop)

        val key = FakeRecentsKey()
        val main = FakeMain(reading)
        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, key)
        assertEquals(RecentsTapAction.CLOSE, tap.action)
        assertEquals("the game came back, not Mimir", listOf(gameA), main.restored)
        assertEquals(0, main.homes)
        assertEquals(0, key.presses)
    }

    @Test
    fun `a record for an earlier open is stale for a Recents opened later by other means`() {
        val record = MainRecentsOpenRecord(priorTop = gameA, openedAtMs = 1_000)
        val events = listOf(event(99_800, appB, paused), event(100_000, QuickstepRecentsComponent, resumed))
        assertEquals("pause inference, not the old record", appB, readMainRecents(events, exclusions, record).priorTop)
    }

    // ---- a pick UsageStats has not written yet (ledger D35 scenario 1) ----

    @Test
    fun `a snapshot taken before the pick is written reads up, and the one with the pick reads not up`() {
        val record = MainRecentsOpenRecord(priorTop = gameA, openedAtMs = 9_950)
        val beforeThePick = listOf(event(9_800, gameA, paused), event(10_000, QuickstepRecentsComponent, resumed))
        val withThePick = beforeThePick + listOf(event(14_000, QuickstepRecentsComponent, paused), event(14_300, appB, resumed))

        val stale = readMainRecents(beforeThePick, exclusions, record)
        assertEquals(MainRecentsPresence.UP, stale.presence)
        assertEquals("the stale reading still names the earlier app", gameA, stale.priorTop)
        assertEquals(MainRecentsPresence.NOT_UP, readMainRecents(withThePick, exclusions, record).presence)
        // The pick's pause alone, before its resume lands, already says Recents was left.
        assertEquals(
            MainRecentsPresence.NOT_UP,
            readMainRecents(beforeThePick + event(14_000, QuickstepRecentsComponent, paused), exclusions, record).presence,
        )
    }

    @Test
    fun `a tap right after the player picked an app from Recents does not restore the stale prior top`() {
        val key = FakeRecentsKey()
        val main = FakeMain(up(gameA), later = listOf(MainRecentsReading(MainRecentsPresence.NOT_UP)))

        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, key)

        assertTrue("the first read was stale, and the tap waited and read again", main.settles == 1 && main.reads == 2)
        assertTrue("the earlier app is not put over the pick", main.restored.isEmpty())
        assertEquals("nor is Mimir's Main", 0, main.homes)
        assertEquals("Recents is down, so the tap opens it, which is the ordinary toggle", RecentsTapAction.OPEN, tap.action)
        assertEquals(1, key.presses)
        assertEquals(1, main.captures)
    }

    @Test
    fun `the stale prior top is no more restored by the HOME start when nothing named it`() {
        val key = FakeRecentsKey()
        val main = FakeMain(up(null), later = listOf(MainRecentsReading(MainRecentsPresence.NOT_UP)))

        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, key)

        assertEquals(RecentsTapAction.OPEN, tap.action)
        assertEquals("Mimir does not come over the app the player picked", 0, main.homes)
        assertTrue(main.restored.isEmpty())
    }

    @Test
    fun `a close is planned from the second reading, so a fresher prior top wins`() {
        val main = FakeMain(up(gameA), later = listOf(up(appB)))

        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, FakeRecentsKey())

        assertEquals(RecentsTapAction.CLOSE, tap.action)
        assertEquals(listOf(appB), main.restored)
        assertEquals(1, main.settles)
    }

    @Test
    fun `a second read that cannot say keeps the first, which said up`() {
        val key = FakeRecentsKey()
        val main = FakeMain(up(gameA), later = listOf(MainRecentsReading.Unknown))

        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, key)

        assertEquals(RecentsTapAction.CLOSE, tap.action)
        assertEquals(listOf(gameA), main.restored)
        assertEquals("an unreadable second read never turns a close into the key", 0, key.presses)
    }

    @Test
    fun `only a read that found Recents up waits, and a menu row never waits or reads`() {
        for (reading in listOf(MainRecentsReading(MainRecentsPresence.NOT_UP), MainRecentsReading.Unknown)) {
            val main = FakeMain(reading)
            assertEquals(RecentsTapAction.OPEN, runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, FakeRecentsKey()).action)
            assertEquals("${reading.presence} reads once and does not wait", 1, main.reads)
            assertEquals(0, main.settles)
        }
        val row = FakeMain(up(gameA))
        runMButtonRecentsTap(QuickMenuOrigin.MENU_ROW, row, FakeRecentsKey())
        assertEquals(0, row.reads)
        assertEquals(0, row.settles)
    }

    @Test
    fun `the settle is the bounded half second and the unavailable platform has none`() {
        assertEquals(500L, MainRecentsSettleMs)
        MButtonMainRecents.Unavailable.settle()
    }

    // ---- what one tap sends ----

    private class FakeRecentsKey(var opens: Boolean = true) : MButtonSystemRecentsGateway {
        var presses = 0
        override fun systemRecentsAccessStatus() = PrivilegedUtilityStatus.READY
        override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus) = false
        override fun openSystemRecents(): Boolean {
            presses++
            return opens
        }
    }

    /** [reading] is the first read; each later read takes the next of [later], and the last one repeats. */
    private class FakeMain(
        private val reading: MainRecentsReading,
        private val restores: Boolean = true,
        private val homeStarts: Boolean = true,
        private val later: List<MainRecentsReading> = emptyList(),
    ) : MButtonMainRecents {
        var reads = 0
        var settles = 0
        var captures = 0
        var homes = 0
        val restored = mutableListOf<String>()
        override fun read(): MainRecentsReading {
            val index = reads++
            return if (index == 0 || later.isEmpty()) reading else later[minOf(index - 1, later.lastIndex)]
        }

        override fun settle() {
            settles++
        }

        override fun captureBeforeOpen() {
            captures++
        }

        override fun restore(component: String): Boolean {
            restored += component
            return restores
        }

        override fun returnToMimir(): Boolean {
            homes++
            return homeStarts
        }
    }

    private fun up(prior: String?) = MainRecentsReading(MainRecentsPresence.UP, prior)

    @Test
    fun `Recents opened over Mimir, then a tap, restores Mimir by the HOME start and sends no key`() {
        val key = FakeRecentsKey()
        val main = FakeMain(up(mimirMain), restores = false) // Mimir's own component is never a task restore

        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, key)

        assertEquals(RecentsTapAction.CLOSE_HOME, tap.action)
        assertTrue(tap.accepted)
        assertEquals(1, main.homes)
        assertEquals("187 only opens", 0, key.presses)
    }

    @Test
    fun `Recents opened over app A, then a tap, restores A and nothing else`() {
        val key = FakeRecentsKey()
        val main = FakeMain(up(gameA))

        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, key)

        assertEquals(RecentsTapAction.CLOSE, tap.action)
        assertTrue(tap.accepted)
        assertEquals(listOf(gameA), main.restored)
        assertEquals("no HOME start when A came back", 0, main.homes)
        assertEquals(0, key.presses)
        assertNull(tap.press)
    }

    @Test
    fun `an ambiguous, unrestorable or expired prior top returns to Mimir's Main and never presses 187`() {
        for ((name, main) in listOf(
            "ambiguous or expired (no prior top)" to FakeMain(up(null)),
            "the restore fails" to FakeMain(up(gameA), restores = false),
        )) {
            val key = FakeRecentsKey()
            val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, key)
            assertEquals(name, RecentsTapAction.CLOSE_HOME, tap.action)
            assertTrue(name, tap.accepted)
            assertEquals("$name: Mimir's Main by the HOME start", 1, main.homes)
            assertEquals("$name: no key while Recents is up", 0, key.presses)
        }
    }

    @Test
    fun `a close that cannot start Mimir's Main is refused, still with no key`() {
        val key = FakeRecentsKey()
        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, FakeMain(up(null), homeStarts = false), key)
        assertEquals(RecentsTapAction.CLOSE_HOME, tap.action)
        assertFalse(tap.accepted)
        assertEquals(0, key.presses)
    }

    @Test
    fun `Recents closed by other means, then a tap, opens it again and records what was on top first`() {
        val key = FakeRecentsKey()
        val main = FakeMain(MainRecentsReading(MainRecentsPresence.NOT_UP))

        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, main, key)

        assertEquals(RecentsTapAction.OPEN, tap.action)
        assertTrue(tap.accepted)
        assertEquals(1, key.presses)
        assertEquals("the prior top is captured before the key", 1, main.captures)
        assertTrue(main.restored.isEmpty() && main.homes == 0)
    }

    @Test
    fun `an unreadable Recents opens, as a tap did before`() {
        val key = FakeRecentsKey()
        assertEquals(RecentsTapAction.OPEN, runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, FakeMain(MainRecentsReading.Unknown), key).action)
        assertEquals(1, key.presses)
        assertEquals(RecentsTapAction.OPEN, runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, MButtonMainRecents.Unavailable, FakeRecentsKey()).action)
    }

    @Test
    fun `a menu row's Tabs request is the plain open that reads nothing and records the prior top`() {
        val key = FakeRecentsKey()
        val main = FakeMain(up(gameA))

        val tap = runMButtonRecentsTap(QuickMenuOrigin.MENU_ROW, main, key)

        assertEquals(RecentsTapAction.OPEN, tap.action)
        assertEquals(0, main.reads)
        assertEquals(1, main.captures)
        assertEquals(1, key.presses)
    }

    @Test
    fun `a refused open key is not accepted, so the open failure toast still fires`() {
        val tap = runMButtonRecentsTap(QuickMenuOrigin.COIN_TAP, FakeMain(MainRecentsReading(MainRecentsPresence.NOT_UP)), FakeRecentsKey(opens = false))
        assertFalse(tap.accepted)
        assertEquals(RecentsTapAction.OPEN, tap.action)
    }

    // ---- bringing a task back, display 0 only ----

    private class FakeTasks(private val taskId: Int?, private val focus: PrivilegedTaskFocus = PrivilegedTaskFocus.FOCUSED) :
        PrivilegedTaskCloseGateway {
        val resolved = mutableListOf<Pair<String, Int>>()
        val focused = mutableListOf<Int>()
        override fun closeLaunchTarget(packageName: String) = PrivilegedCloseOutcome.AUTHORITY_UNAVAILABLE
        override fun removeTask(taskId: Int) = PrivilegedTaskRemoval.AUTHORITY_UNAVAILABLE
        override fun resolveTaskId(packageName: String, displayId: Int): Int? {
            resolved += packageName to displayId
            return taskId
        }

        override fun focusTask(taskId: Int): PrivilegedTaskFocus {
            focused += taskId
            return focus
        }
    }

    @Test
    fun `another package comes back through the existing resolve on display 0 and focus`() {
        val tasks = FakeTasks(42)
        assertTrue(restoreMainTop(gameA, self, setOf("org.example.bottom"), tasks))
        assertEquals(listOf("org.example.gamea" to 0), tasks.resolved)
        assertEquals(listOf(42), tasks.focused)
    }

    @Test
    fun `a display-4 package and Mimir's own components are never task-restored`() {
        val tasks = FakeTasks(42)
        assertFalse("a package the registry puts on display 4", restoreMainTop(bottomApp, self, setOf("org.example.bottom"), tasks))
        assertFalse("Mimir comes back by the HOME start", restoreMainTop(mimirMain, self, emptySet(), tasks))
        assertTrue("neither reached a task operation", tasks.resolved.isEmpty() && tasks.focused.isEmpty())
    }

    @Test
    fun `a package with no display-0 task, or a refused focus, is a failed restore`() {
        val none = FakeTasks(null)
        assertFalse(restoreMainTop(gameA, self, emptySet(), none))
        assertTrue(none.focused.isEmpty())
        assertFalse(restoreMainTop(gameA, self, emptySet(), FakeTasks(42, PrivilegedTaskFocus.FAILED)))
    }

    @Test
    fun `the registry's display-4 packages are its secondary display's recents and foreground`() {
        val state = SecondaryDisplayState(
            primaryDisplayId = 0,
            secondaryDisplayId = 4,
            foregroundByDisplay = mapOf(4 to "org.example.fg", 0 to "org.example.main"),
            recents = listOf(
                AppEntry("org.example.bottom", "Bottom", 4, 0L),
                AppEntry("org.example.gamea", "A", 0, 0L),
            ),
        )
        assertEquals(setOf("org.example.bottom", "org.example.fg"), bottomDisplayPackages(state))
        assertEquals(emptySet<String>(), bottomDisplayPackages(SecondaryDisplayState()))
    }
}
