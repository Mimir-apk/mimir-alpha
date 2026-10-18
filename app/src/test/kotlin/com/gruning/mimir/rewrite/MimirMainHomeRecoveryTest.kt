package com.gruning.mimir.rewrite

import android.app.usage.UsageEvents
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O10,
 *
 * Fixtures follow the Thor evidence of 2026-09-11: after a
 * signal ended Mimir's process at 22:33:31 with a game on the Bottom, display 0's top was
 * `com.android.launcher3/com.android.quickstep.RecentsActivity` until a HOME key four minutes later. The events are what
 * the app's own Usage Access returns -- activity lifecycle events carrying no display id. Pure JVM pins; the device
 * signature is owed to the Thor.
 */
class MimirMainHomeRecoveryTest {

    private val self = "com.gruning.mimir.rewrite"
    private val recents = "com.android.launcher3/com.android.quickstep.RecentsActivity"
    private val launcherHome = "com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher"
    private val secondaryLauncher = "com.android.launcher3/com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher"
    private val settingsHome = "com.android.settings/com.android.settings.FallbackHome"
    private val settingsApp = "com.android.settings/com.android.settings.Settings"
    private val retroArch = "com.retroarch/com.retroarch.browser.retroactivity.RetroActivityFuture"
    private val youTube = "com.google.android.youtube/com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity"
    private val mimirMain = "$self/$self.MainActivity"
    private val mimirFloor = "$self/$self.CompanionSecondaryHomeActivity"

    /** Wall clock of the fixture process's start; every decision below reads 1.5 s after it. */
    private val processStart = 1_700_000_000_000L

    private fun resumed(component: String, atMs: Long) = event(component, atMs, UsageEvents.Event.ACTIVITY_RESUMED)
    private fun paused(component: String, atMs: Long) = event(component, atMs, UsageEvents.Event.ACTIVITY_PAUSED)
    private fun stopped(component: String, atMs: Long) = event(component, atMs, UsageEvents.Event.ACTIVITY_STOPPED)
    private fun event(component: String, atMs: Long, type: Int) =
        MainHomeActivityEvent(atMs, component.substringBefore('/'), component.substringAfter('/'), type)

    private inner class Platform(
        private val events: List<MainHomeActivityEvent>,
        private val defaultHome: String? = self,
        private val usageAccess: Boolean = true,
        private val eventsReadable: Boolean = true,
        private val startFailure: RuntimeException? = null,
    ) : MainHomeRecoveryPlatform {
        val starts = mutableListOf<MainHomeStartRequest>()
        val windows = mutableListOf<Pair<Long, Long>>()
        override val selfPackage: String = self
        override fun defaultHomePackage(): String? = defaultHome
        override fun homeComponents(): Set<String> = setOf(mimirMain, launcherHome, settingsHome)
        override fun secondaryHomeComponents(): Set<String> = setOf(mimirFloor, secondaryLauncher)
        override fun usageAccessGranted(): Boolean = usageAccess
        override fun activityEvents(fromWallClockMs: Long, toWallClockMs: Long): List<MainHomeActivityEvent>? {
            windows += fromWallClockMs to toWallClockMs
            if (!eventsReadable) return null
            return events.filter { it.timeMs in fromWallClockMs..toWallClockMs }
        }
        override fun wallClockNowMs(): Long = processStart + 1_500
        override fun sinceProcessStartMs(): Long = 1_500
        override fun start(request: MainHomeStartRequest) {
            starts += request
            startFailure?.let { throw it }
        }
    }

    private class Harness(platform: MainHomeRecoveryPlatform) {
        val logs = mutableListOf<String>()
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        val gate = MainHomeRecoveryGate(
            platform = platform,
            schedule = { delayMillis, action -> pending += delayMillis to action },
            log = { line -> logs += line },
        )

        fun settle() {
            while (pending.isNotEmpty()) pending.removeAt(0).second()
        }
    }

    @Test
    fun `Recents on Main after a process start starts Mimir's Home once`() {
        // The 22:33 shape: a game resumed on the Bottom, Mimir's Main resumed until its process was killed (a killed
        // process reports no pause, so its own last event still reads resumed), then quickstep's Recents resumed on Main
        // at the death, 1.3 s before this process began.
        val platform = Platform(
            listOf(
                resumed(mimirMain, processStart - 30_000),
                resumed(retroArch, processStart - 20_000),
                resumed(recents, processStart - 1_300),
            ),
        )
        val harness = Harness(platform)

        harness.gate.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
        assertEquals("the trigger itself starts nothing; one decision is scheduled", 1, harness.pending.size)
        assertEquals(0, platform.starts.size)
        val settle = harness.pending.single().first
        assertTrue(
            "settle $settle ms must outlast the launch transaction queued with the process attach and stay short",
            settle in 250L..5_000L,
        )
        harness.settle()

        assertEquals("exactly one start", 1, platform.starts.size)
        assertEquals(
            listOf(
                "main home decision=started reason=launcher_or_recents_on_main top=$recents trigger=process_recovery " +
                    "resumed=[$recents,$retroArch,$mimirMain] sinceStartMs=1500",
            ),
            harness.logs,
        )
    }

    @Test
    fun `a launcher that is not Mimir on Main starts Mimir's Home once`() {
        val platform = Platform(
            listOf(
                resumed(launcherHome, processStart - 900),
                resumed(mimirFloor, processStart + 600),
            ),
        )
        val harness = Harness(platform)

        harness.gate.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
        harness.settle()

        assertEquals("exactly one start", 1, platform.starts.size)
        assertEquals(
            listOf(
                "main home decision=started reason=launcher_or_recents_on_main top=$launcherHome trigger=floor_resumed " +
                    "resumed=[$mimirFloor,$launcherHome] sinceStartMs=1500",
            ),
            harness.logs,
        )
    }

    @Test
    fun `a game or app on Main is skipped`() {
        mapOf(
            "a game launched over Recents, floor restarted on the Bottom" to listOf(
                resumed(recents, processStart - 50_000),
                paused(recents, processStart - 40_000),
                resumed(retroArch, processStart - 39_900),
                stopped(recents, processStart - 39_500),
                resumed(mimirFloor, processStart + 600),
            ),
            "an app over the launcher" to listOf(
                resumed(launcherHome, processStart - 20_000),
                paused(launcherHome, processStart - 10_000),
                resumed(youTube, processStart - 9_900),
                stopped(launcherHome, processStart - 9_500),
                resumed(mimirFloor, processStart + 600),
            ),
            "a game on Main while launcher3's secondary-display launcher is resumed on display 4" to listOf(
                resumed(secondaryLauncher, processStart - 5_000),
                resumed(retroArch, processStart - 4_000),
            ),
            "Android Settings on Main, whose package also exposes FallbackHome" to listOf(
                resumed(settingsApp, processStart - 3_000),
                resumed(mimirFloor, processStart + 600),
            ),
        ).forEach { (scenario, events) ->
            val platform = Platform(events)
            val harness = Harness(platform)
            harness.gate.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
            harness.settle()
            assertEquals("$scenario: nothing may cover it", 0, platform.starts.size)
            assertEquals("$scenario: one decision line", 1, harness.logs.size)
            assertTrue(
                "$scenario: ${harness.logs.singleOrNull()}",
                harness.logs.single().startsWith(
                    "main home decision=skipped reason=no_launcher_or_recents_resumed top=unattributed trigger=floor_resumed ",
                ),
            )
        }
    }

    @Test
    fun `Mimir already on Main is skipped`() {
        val recentsAtDeath = listOf(resumed(recents, processStart - 1_300))

        val created = Platform(recentsAtDeath)
        val before = Harness(created)
        before.gate.noteMainActivityCreated()
        before.gate.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
        before.gate.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
        assertTrue("MainActivity created in this process closes the window: nothing scheduled", before.pending.isEmpty())
        assertTrue("and nothing logged, so a healthy process's floor resumes stay quiet", before.logs.isEmpty())
        assertEquals(0, created.starts.size)

        val racing = Platform(recentsAtDeath)
        val during = Harness(racing)
        during.gate.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
        during.gate.noteMainActivityCreated()
        during.settle()
        assertEquals("Main came up during the settle", 0, racing.starts.size)
        assertTrue("so UsageStats is not even read", racing.windows.isEmpty())
        assertEquals(
            listOf(
                "main home decision=skipped reason=main_activity_created top=unread trigger=process_recovery " +
                    "resumed=unread sinceStartMs=1500",
            ),
            during.logs,
        )
    }

    @Test
    fun `a second trigger in the same process is skipped`() {
        val platform = Platform(listOf(resumed(recents, processStart - 1_300)))
        val harness = Harness(platform)

        harness.gate.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
        harness.gate.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
        assertEquals("only the first trigger schedules a decision", 1, harness.pending.size)
        harness.settle()
        harness.gate.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
        harness.settle()

        assertEquals("one start for the whole process", 1, platform.starts.size)
        assertEquals(
            listOf(
                "main home decision=skipped reason=once_per_process top=unread trigger=floor_resumed " +
                    "resumed=unread sinceStartMs=1500",
                "main home decision=started reason=launcher_or_recents_on_main top=$recents trigger=process_recovery " +
                    "resumed=[$recents] sinceStartMs=1500",
                "main home decision=skipped reason=once_per_process top=unread trigger=floor_resumed " +
                    "resumed=unread sinceStartMs=1500",
            ),
            harness.logs,
        )

        harness.gate.noteMainActivityCreated()
        harness.gate.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
        assertEquals("once Main exists every later trigger is silent", 3, harness.logs.size)
        assertTrue(harness.pending.isEmpty())
    }

    @Test
    fun `a refused start is logged once and never retried`() {
        val platform = Platform(
            listOf(resumed(recents, processStart - 1_300)),
            startFailure = SecurityException("refused"),
        )
        val harness = Harness(platform)

        harness.gate.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
        harness.settle()
        harness.gate.trigger(MainHomeRecoveryTrigger.FLOOR_RESUMED)
        harness.settle()

        assertEquals("one attempt, no retry", 1, platform.starts.size)
        assertEquals(
            listOf(
                "main home decision=failed reason=start_failed:SecurityException top=$recents trigger=floor_resumed " +
                    "resumed=[$recents] sinceStartMs=1500",
                "main home decision=skipped reason=once_per_process top=unread trigger=floor_resumed " +
                    "resumed=unread sinceStartMs=1500",
            ),
            harness.logs,
        )
    }

    @Test
    fun `the start names display 0 and uses the home route`() {
        val platform = Platform(listOf(resumed(recents, processStart - 1_300)))
        val harness = Harness(platform)

        harness.gate.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
        harness.settle()

        assertEquals("Recents at the death must issue exactly one start", 1, platform.starts.size)
        val request = platform.starts.single()
        assertEquals(
            "Main is display 0; a start naming no display lands on the focused one, which can be display 4",
            0,
            request.launchDisplayId,
        )
        assertEquals(Intent.ACTION_MAIN, request.action)
        assertEquals(
            "the HOME category and nothing else: a component-less HOME start is typed home",
            Intent.CATEGORY_HOME,
            request.category,
        )
        assertEquals("resolved inside Mimir's package only", self, request.packageName)
        assertEquals(
            "a new task from a non-Activity context, and no flag that could add a second Main",
            Intent.FLAG_ACTIVITY_NEW_TASK,
            request.flags,
        )
    }

    @Test
    fun `nothing starts without Usage Access, when Mimir is not the default home, or when UsageStats cannot be read`() {
        val recentsAtDeath = listOf(resumed(recents, processStart - 1_300))
        mapOf(
            "not_default_home" to Platform(recentsAtDeath, defaultHome = "com.android.launcher3"),
            "usage_access_missing" to Platform(recentsAtDeath, usageAccess = false),
            "usage_events_unreadable" to Platform(recentsAtDeath, eventsReadable = false),
        ).forEach { (reason, platform) ->
            val harness = Harness(platform)
            harness.gate.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
            harness.settle()
            assertEquals("$reason: no start", 0, platform.starts.size)
            assertEquals(
                listOf(
                    "main home decision=skipped reason=$reason top=unread trigger=process_recovery " +
                        "resumed=unread sinceStartMs=1500",
                ),
                harness.logs,
            )
        }
    }

    @Test
    fun `the read reaches back before the process start but leaves a Recents opened long before alone`() {
        // A service restart can land seconds after the death, and repeated crashes back off further.
        val slowRestart = Platform(listOf(resumed(recents, processStart - 30_000)))
        Harness(slowRestart).apply {
            gate.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
            settle()
        }
        assertEquals("Recents resumed 30 s before this process started still counts", 1, slowRestart.starts.size)
        assertEquals("the read ends at the decision", processStart + 1_500, slowRestart.windows.single().second)

        val openedLongBefore = Platform(listOf(resumed(recents, processStart - 600_000)))
        val harness = Harness(openedLongBefore)
        harness.gate.trigger(MainHomeRecoveryTrigger.PROCESS_RECOVERY)
        harness.settle()
        assertEquals(
            "a Recents the player opened ten minutes before the restart did not come up because Mimir died",
            0,
            openedLongBefore.starts.size,
        )
        assertTrue(
            harness.logs.single().startsWith(
                "main home decision=skipped reason=no_launcher_or_recents_resumed top=unattributed trigger=process_recovery ",
            ),
        )
    }
}
