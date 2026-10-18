package com.gruning.mimir.rewrite

import android.app.usage.UsageEvents
import com.gruning.mimir.rewrite.core.companionnative.AppEntry
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuOrigin
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplayState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ledger D35, the two scenarios that are about how fresh the Recents toggle's picture of Main is: (1) a tap right after the player
 * picked an app from Recents, before UsageStats wrote its resume, and (2) a Recents opened by other means more than 15 minutes ago.
 * These run the real read path ([AndroidMButtonMainRecents] over a scripted [MainHomeRecoveryPlatform] with a virtual clock) into the
 * real [runMButtonRecentsTap], so what is pinned is what a tap sends, not only what a reader returns. That Main really lands where it
 * was on a Thor is the Thor's to say (DEVICE-UNCERTIFIED).
 */
class MButtonMainRecentsFreshnessTest {
    private val self = "com.gruning.mimir.rewrite"
    private val mimirMain = "$self/$self.MainActivity"
    private val gameA = "org.example.gamea/org.example.gamea.Main"
    private val appB = "org.example.appb/org.example.appb.Main"
    private val bottomApp = "org.example.bottom/org.example.bottom.Main"
    private val bottomFloor = "$self/$self.CompanionSecondaryHomeActivity"
    private val foreignLauncher = "com.android.launcher3/com.android.launcher3.Launcher"

    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED

    private val minute = 60_000L
    private val hour = 60 * minute

    private fun event(timeMs: Long, component: String, type: Int) =
        MainHomeActivityEvent(timeMs, component.substringBefore('/'), component.substringAfter('/'), type)

    private val exclusions = MainRecentsExclusions(
        components = setOf(foreignLauncher, bottomFloor),
        bottomPackages = setOf("org.example.bottom"),
        bottomComponents = setOf(bottomFloor),
    )

    /** UsageStats as a script: it answers only the events written by `now`, and the clock moves only when a test says so. */
    private class FakePlatform(
        private val selfName: String,
        private val homes: Set<String>,
        private val secondaryHomes: Set<String>,
        var now: Long,
    ) : MainHomeRecoveryPlatform {
        val events = mutableListOf<MainHomeActivityEvent>()
        val queries = mutableListOf<Pair<Long, Long>>()
        val started = mutableListOf<MainHomeStartRequest>()

        override val selfPackage: String = selfName
        override fun defaultHomePackage(): String? = selfName
        override fun homeComponents(): Set<String> = homes
        override fun secondaryHomeComponents(): Set<String> = secondaryHomes
        override fun usageAccessGranted(): Boolean = true

        override fun activityEvents(fromWallClockMs: Long, toWallClockMs: Long): List<MainHomeActivityEvent>? {
            queries += fromWallClockMs to toWallClockMs
            return events.filter { it.timeMs in fromWallClockMs..toWallClockMs }
        }

        override fun wallClockNowMs(): Long = now
        override fun sinceProcessStartMs(): Long = 0L
        override fun start(request: MainHomeStartRequest) {
            started += request
        }
    }

    private class FakeKey : MButtonSystemRecentsGateway {
        var presses = 0
        override fun systemRecentsAccessStatus() = PrivilegedUtilityStatus.READY
        override fun requestSystemRecentsAccess(expectedStatus: PrivilegedUtilityStatus) = false
        override fun openSystemRecents(): Boolean {
            presses++
            return true
        }
    }

    private class FakeTasks(private val taskId: Int? = 42) : PrivilegedTaskCloseGateway {
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
            return PrivilegedTaskFocus.FOCUSED
        }
    }

    private val bottomState = SecondaryDisplayState(
        primaryDisplayId = 0,
        secondaryDisplayId = 4,
        foregroundByDisplay = mapOf(4 to "org.example.bottom"),
        recents = listOf(AppEntry("org.example.bottom", "Bottom", 4, 0L)),
    )

    private class Rig(
        val platform: FakePlatform,
        val tasks: FakeTasks,
        val key: FakeKey,
        val pauses: MutableList<Long>,
        val main: AndroidMButtonMainRecents,
    ) {
        fun tap(origin: QuickMenuOrigin = QuickMenuOrigin.COIN_TAP) = runMButtonRecentsTap(origin, main, key)
    }

    /** [onPause] runs when the toggle waits: the pick's events land during the wait. The clock moves by the wait. */
    private fun rig(nowMs: Long, onPause: (FakePlatform) -> Unit = {}): Rig {
        val platform = FakePlatform(self, setOf(mimirMain, foreignLauncher), setOf(bottomFloor), nowMs)
        val tasks = FakeTasks()
        val pauses = mutableListOf<Long>()
        val main = AndroidMButtonMainRecents(platform, tasks, { bottomState }) { ms ->
            pauses += ms
            platform.now += ms
            onPause(platform)
        }
        return Rig(platform, tasks, FakeKey(), pauses, main)
    }

    // ---- scenario 1: the pick is not written yet ----

    @Test
    fun `a tap right after a pick from Recents opens Recents and restores nothing, because the settle read sees the pick`() {
        val t0 = 100 * hour
        val rig = rig(t0 + 10_000) { platform ->
            // The pick lands in UsageStats during the wait: Recents pauses, app B resumes.
            platform.events += event(platform.now - 150, QuickstepRecentsComponent, paused)
            platform.events += event(platform.now - 100, appB, resumed)
        }
        // The player opened Recents by other means over game A, then picked app B; none of the pick is written yet.
        rig.platform.events += event(t0 - 200, gameA, paused)
        rig.platform.events += event(t0, QuickstepRecentsComponent, resumed)

        val stale = rig.main.read()
        assertEquals("fixture: the first read alone is stale and would restore game A", MainRecentsPresence.UP, stale.presence)
        assertEquals(gameA, stale.priorTop)
        rig.pauses.clear()

        val tap = rig.tap()

        assertEquals(listOf(MainRecentsSettleMs), rig.pauses)
        assertEquals(RecentsTapAction.OPEN, tap.action)
        assertTrue("game A was not put over app B", rig.tasks.resolved.isEmpty() && rig.tasks.focused.isEmpty())
        assertTrue("nor was Mimir", rig.platform.started.isEmpty())
        assertEquals("Recents was down, so the tap opened it", 1, rig.key.presses)
    }

    @Test
    fun `a tap on Recents that is really still up waits once and closes to the prior top`() {
        val t0 = 100 * hour
        val rig = rig(t0 + 10_000)
        rig.platform.events += event(t0 - 200, gameA, paused)
        rig.platform.events += event(t0, QuickstepRecentsComponent, resumed)

        val tap = rig.tap()

        assertEquals(listOf(MainRecentsSettleMs), rig.pauses)
        assertEquals(RecentsTapAction.CLOSE, tap.action)
        assertTrue(tap.accepted)
        assertEquals(listOf("org.example.gamea" to 0), rig.tasks.resolved)
        assertEquals(listOf(42), rig.tasks.focused)
        assertEquals("187 only opens", 0, rig.key.presses)
    }

    @Test
    fun `the pick landing during the wait is also what stops Mimir's Main coming over it`() {
        val t0 = 100 * hour
        val rig = rig(t0 + 10_000) { platform -> platform.events += event(platform.now - 50, appB, resumed) }
        // Nothing on Main paused as Recents resumed, so the prior top is unknown and a close would be the HOME start.
        rig.platform.events += event(t0, QuickstepRecentsComponent, resumed)

        val tap = rig.tap()

        assertEquals(RecentsTapAction.OPEN, tap.action)
        assertTrue("no HOME start over the pick", rig.platform.started.isEmpty())
    }

    @Test
    fun `an open waits for nothing`() {
        val rig = rig(100 * hour)
        rig.platform.events += event(100 * hour - minute, gameA, resumed)

        val tap = rig.tap()

        assertEquals(RecentsTapAction.OPEN, tap.action)
        assertTrue("Recents was not up: no wait", rig.pauses.isEmpty())
        assertEquals(1, rig.key.presses)
    }

    @Test
    fun `the plain toggle still round-trips a Recents our own tap opened`() {
        val t0 = 100 * hour
        val rig = rig(t0)
        rig.platform.events += event(t0 - 5 * minute, gameA, resumed)

        val open = rig.tap()
        assertEquals(RecentsTapAction.OPEN, open.action)
        assertTrue(rig.pauses.isEmpty())
        // Recents comes up over game A after the press.
        rig.platform.now = t0 + 2_000
        rig.platform.events += event(t0 + 290, gameA, paused)
        rig.platform.events += event(t0 + 300, QuickstepRecentsComponent, resumed)

        val close = rig.tap()

        assertEquals(RecentsTapAction.CLOSE, close.action)
        assertEquals("game A came back", listOf("org.example.gamea" to 0), rig.tasks.resolved)
        assertEquals("the one open key, no key to close", 1, rig.key.presses)
        assertEquals(listOf(MainRecentsSettleMs), rig.pauses)
    }

    // ---- scenario 2: a Recents opened by other means long ago ----

    @Test
    fun `a Recents opened by other means twenty minutes ago is still up, and a tap closes it instead of pressing the key`() {
        val now = 100 * hour
        val rig = rig(now)
        val opened = now - 20 * minute
        rig.platform.events += event(opened - 200, gameA, paused)
        rig.platform.events += event(opened, QuickstepRecentsComponent, resumed)
        // Only the Bottom moved since: it never ends a Main Recents.
        rig.platform.events += event(now - 3 * minute, bottomFloor, resumed)
        rig.platform.events += event(now - 2 * minute, bottomApp, resumed)

        val tap = rig.tap()

        assertEquals(RecentsTapAction.CLOSE, tap.action)
        assertEquals(listOf("org.example.gamea" to 0), rig.tasks.resolved)
        assertEquals("no open key into a Recents that is up (it quick-switches in AOSP 13)", 0, rig.key.presses)
    }

    @Test
    fun `a Recents left up for hours with nothing known about what it covered closes to Mimir's Main, never the key`() {
        val now = 100 * hour
        val rig = rig(now)
        rig.platform.events += event(now - 3 * hour, QuickstepRecentsComponent, resumed)

        val tap = rig.tap()

        assertEquals(RecentsTapAction.CLOSE_HOME, tap.action)
        assertEquals(1, rig.platform.started.size)
        assertEquals(0, rig.key.presses)
    }

    @Test
    fun `a Recents that was closed long ago, and a game running for hours, still read as not up and open`() {
        val now = 100 * hour
        val closed = rig(now)
        closed.platform.events += event(now - 40 * minute, QuickstepRecentsComponent, resumed)
        closed.platform.events += event(now - 39 * minute, QuickstepRecentsComponent, paused)
        closed.platform.events += event(now - 39 * minute, appB, resumed)
        assertEquals(RecentsTapAction.OPEN, closed.tap().action)
        assertTrue(closed.pauses.isEmpty())

        val game = rig(now)
        game.platform.events += event(now - 3 * hour, gameA, resumed)
        assertEquals(RecentsTapAction.OPEN, game.tap().action)
    }

    @Test
    fun `the deeper read happens only when the ordinary window is silent about Main`() {
        val now = 100 * hour
        val quick = rig(now)
        quick.platform.events += event(now - 5 * minute, QuickstepRecentsComponent, resumed)
        quick.main.read()
        assertEquals("a window that names a Recents event is one query", 1, quick.platform.queries.size)

        val appOnTop = rig(now)
        appOnTop.platform.events += event(now - 5 * minute, appB, resumed)
        appOnTop.main.read()
        assertEquals("a window with an app resume is one query", 1, appOnTop.platform.queries.size)

        val silent = rig(now)
        silent.main.read()
        assertEquals("a silent window gets exactly one deeper read", 2, silent.platform.queries.size)
        assertEquals(now - MainRecentsDeepLookbackMs, silent.platform.queries[1].first)
    }

    // ---- the pure pieces of scenario 2 ----

    @Test
    fun `a window is silent about Main when it holds no Recents event and no resume outside the Bottom`() {
        assertTrue(mainRecentsWindowSilent(emptyList(), exclusions))
        assertTrue(
            "the Bottom's floor and display-4 apps resume without Main changing",
            mainRecentsWindowSilent(listOf(event(1, bottomFloor, resumed), event(2, bottomApp, resumed)), exclusions),
        )
        assertTrue("a pause with no resume says nothing", mainRecentsWindowSilent(listOf(event(1, gameA, paused)), exclusions))
        assertFalse(mainRecentsWindowSilent(listOf(event(1, appB, resumed)), exclusions))
        assertFalse("Mimir's own MainActivity is a resume on Main", mainRecentsWindowSilent(listOf(event(1, mimirMain, resumed)), exclusions))
        assertFalse(mainRecentsWindowSilent(listOf(event(1, QuickstepRecentsComponent, paused)), exclusions))
    }

    @Test
    fun `the deep read finds a Recents up for twenty minutes and names what it covered`() {
        val now = 100 * hour
        val shallowFrom = now - MainRecentsLookbackMs
        // Recents resumed just before the ordinary window starts, game A paused just before that: both only the deeper read sees.
        val events = listOf(
            event(shallowFrom - 300, gameA, paused),
            event(shallowFrom - 200, QuickstepRecentsComponent, resumed),
        )
        val queries = mutableListOf<Pair<Long, Long>>()
        val reading = readMainRecentsDeep(now, shallowFrom, exclusions, null) { from, to ->
            queries += from to to
            events.filter { it.timeMs in from..to }
        }
        assertEquals(MainRecentsPresence.UP, reading.presence)
        assertEquals("the pause before Recents' resume names what it covered", gameA, reading.priorTop)
        assertEquals(2, queries.size)
    }

    @Test
    fun `a deeper read that fails, or a Recents older than the deep reach, keeps the ordinary answer`() {
        val now = 100 * hour
        val shallowFrom = now - MainRecentsLookbackMs
        val old = listOf(event(now - MainRecentsDeepLookbackMs - hour, QuickstepRecentsComponent, resumed))
        val tooOld = readMainRecentsDeep(now, shallowFrom, exclusions, null) { from, to -> old.filter { it.timeMs in from..to } }
        assertEquals("older than 24 hours is not reached", MainRecentsPresence.NOT_UP, tooOld.presence)

        val fresh = listOf(event(now - hour, QuickstepRecentsComponent, resumed))
        val failed = readMainRecentsDeep(now, shallowFrom, exclusions, null) { from, to ->
            if (from < shallowFrom) null else fresh.filter { it.timeMs in from..to }
        }
        assertEquals("a failed deeper read is the ordinary NOT_UP", MainRecentsPresence.NOT_UP, failed.presence)

        val unreadable = readMainRecentsDeep(now, shallowFrom, exclusions, null) { _, _ -> null }
        assertEquals(MainRecentsPresence.UNKNOWN, unreadable.presence)
        assertNull(unreadable.priorTop)
    }

    // ---- the call site the blocking wait depends on ----

    @Test
    fun `the tap runs off the main thread, so the half second wait never blocks it`() {
        var dir: File? = File(".").absoluteFile
        while (dir != null && !(File(dir, "settings.gradle.kts").isFile && File(dir, "app").isDirectory)) dir = dir.parentFile
        val root = checkNotNull(dir) { "repository root not found" }
        val runtime = File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/MButtonRuntime.kt").readText()
        val flat = runtime.replace(Regex("\\s+"), " ")
        assertTrue(
            "the Tabs tap must keep running on Dispatchers.IO: settle() blocks",
            flat.contains("scope.launch(Dispatchers.IO) { try { val tap = runMButtonRecentsTap(menu.origin, mainRecents, systemRecents)"),
        )
        assertTrue(
            "one Recents tap at a time: a second tap during the wait is dropped",
            flat.contains("if (!recentsTapInFlight.compareAndSet(false, true))") && flat.contains("finally { recentsTapInFlight.set(false) }"),
        )
        val recents = File(root, "app/src/main/kotlin/com/gruning/mimir/rewrite/MButtonMainRecents.kt").readText()
        assertFalse("no polling loop: one wait, bounded", Regex("""while\s*\(""").containsMatchIn(recents.substringAfter("internal class AndroidMButtonMainRecents")))
    }
}
