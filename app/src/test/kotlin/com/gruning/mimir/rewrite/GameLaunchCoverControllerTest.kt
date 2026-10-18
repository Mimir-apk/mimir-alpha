package com.gruning.mimir.rewrite

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.gruning.mimir.rewrite.core.companionnative.ActivityResumedObservation
import com.gruning.mimir.rewrite.core.launch.LaunchCoverRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launch cover's controller: the machine's decisions carried out against a window, an audio player,
 * a scheduler and a UsageStats subscription, all faked here. What is pinned: every end takes the window, the wake-up, the
 * subscription and the audio away together, in that order of importance, and the `MimirOpening` lines the device walk reads
 * say what happened.
 *
 * Robolectric only for a real [Bitmap] in the snapshot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class GameLaunchCoverControllerTest {
    private val gameId = "title:ps2:some game"
    private val rule = LaunchCoverRule(
        packageName = "com.armsx2",
        dispatchActivity = "com.armsx2.BootSplashActivity",
        gameActivity = "com.armsx2.Main",
    )

    private val window = FakeWindow()
    private val scheduler = FakeScheduler()
    private val observer = FakeObserver()
    private val logs = mutableListOf<String>()
    private var now = 1_000L
    private var glideMs = GameLaunchCoverGlideMs
    private val mainQueue = ArrayDeque<() -> Unit>()

    private fun epoch() = EPOCH + (now - 1_000L)

    private val controller = GameLaunchCoverController(
        window = window,
        observeResumes = { since, onResumed -> observer.subscribe(since, onResumed) },
        scheduler = scheduler,
        postToMain = { block -> mainQueue.addLast(block) },
        uptimeMs = { now },
        epochMs = { epoch() },
        ownPackageName = "com.gruning.mimir.rewrite",
        glideMs = { glideMs },
        log = { logs += it },
    )

    private fun snapshot(id: String = gameId) = GameLaunchCoverSnapshot(
        gameIdValue = id,
        title = "Some Game",
        heroUri = "file:///hero.png",
        hero = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).asImageBitmap(),
        logoUri = null,
        frame = gameOpeningIntroFrameAt(3_000L),
    )

    private fun arm(id: String = gameId) = controller.arm(snapshot(id), rule) { window.revealed = true }

    private fun advanceTo(target: Long) {
        scheduler.advanceTo(target) { now = it }
        now = target
        drainMain()
    }

    private fun drainMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst()()
    }

    /**
     * A resume stamped now and read [lagMs] later. The stamp is never before the mount (a stamp before it is a previous
     * launch's replay, which the machine ignores), so the lag is on the observation side: `observedAtMs - eventAtMs`.
     */
    private fun resume(pkg: String, cls: String, lagMs: Long = 40L) {
        observer.deliver(ActivityResumedObservation(pkg, cls, eventAtMs = epoch(), observedAtMs = epoch() + lagMs))
        drainMain()
    }

    // ---- arming

    @Test
    fun `arming adds the window before anything else and starts the subscription at the mount`() {
        assertTrue(arm())

        assertEquals(listOf("mount"), window.calls)
        assertEquals(gameId, controller.liveGameIdValue)
        assertEquals("the subscription begins at the mount, in wall-clock time", listOf(EPOCH), observer.sinceEpochMs)
        assertTrue(logs.toString(), logs.contains("cover start game=$gameId profile=com.armsx2 hero=file:///hero.png"))
    }

    @Test
    fun `a window that cannot be added leaves nothing behind and says why`() {
        window.mountResult = false

        assertFalse(arm())

        assertNull(controller.liveGameIdValue)
        assertEquals(0, observer.subscriptions)
        assertEquals(0, scheduler.pending)
        assertTrue(logs.toString(), logs.contains("cover skip game=$gameId profile=com.armsx2 reason=mount-failed"))
    }

    @Test
    fun `a second arm takes the first cover away, with its audio, before adding its own`() {
        arm("first")
        val audio = FakeAudio()
        controller.yielded("first", audio)

        arm("second")

        assertEquals(listOf("mount", "remove", "mount"), window.calls)
        assertEquals(1, audio.released)
        assertEquals("second", controller.liveGameIdValue)
        assertTrue(logs.toString(), logs.any { it.startsWith("cover end game=first via=teardown") })
        assertEquals("the first cover's subscription is closed", 1, observer.subscriptions)
    }

    // ---- the walk: the game's activity ends it

    @Test
    fun `the game's activity resuming ends the cover after the 0_2 s hold and the glide, taking everything with it`() {
        arm()
        val audio = FakeAudio()
        advanceTo(1_500L)
        controller.hostStopped()
        assertTrue(controller.yielded(gameId, audio))

        advanceTo(4_400L)
        resume("com.armsx2", "com.armsx2.BootSplashActivity")
        assertEquals("the splash is the dispatch activity, expected", listOf("mount"), window.calls)

        resume("com.armsx2", "com.armsx2.Main", lagMs = 120L)
        assertTrue(
            logs.toString(),
            logs.contains("cover game-resumed game=$gameId activity=com.armsx2/com.armsx2.Main lagMs=120 sinceStartMs=3400 sinceYieldMs=2900"),
        )
        assertEquals("the hold: still up", listOf("mount"), window.calls)

        advanceTo(4_400L + GameLaunchCoverGameHoldMs - 1L)
        assertEquals(listOf("mount"), window.calls)

        advanceTo(4_400L + GameLaunchCoverGameHoldMs)
        assertEquals(listOf("mount", "glide:$glideMs"), window.calls)
        assertEquals("the audio fades over the same time the picture glides", listOf(glideMs), audio.fadeOuts)
        assertEquals(0, audio.released)

        advanceTo(4_400L + GameLaunchCoverGameHoldMs + glideMs - 1L)
        assertEquals("one millisecond short: still blending, nothing removed", listOf("mount", "glide:$glideMs"), window.calls)

        advanceTo(4_400L + GameLaunchCoverGameHoldMs + glideMs)
        assertEquals(listOf("mount", "glide:$glideMs", "remove"), window.calls)
        assertEquals(1, audio.released)
        assertNothingLeftAttached()
        val removedAfterMs = (4_400L - 1_000L) + GameLaunchCoverGameHoldMs + glideMs
        val removedSinceYieldMs = removedAfterMs - (1_500L - 1_000L)
        assertTrue(
            logs.toString(),
            logs.any { it.startsWith("cover end game=$gameId via=game elapsedMs=$removedAfterMs sinceYieldMs=$removedSinceYieldMs") },
        )
        val glideBeganAfterMs = (4_400L - 1_000L) + GameLaunchCoverGameHoldMs
        assertTrue(
            "the glide's own line, where the walk-32i dissolve line was: $logs",
            logs.any { it.startsWith("cover glide game=$gameId via=game elapsedMs=$glideBeganAfterMs sinceYieldMs=${glideBeganAfterMs - 500L}") },
        )
        assertTrue("no dissolve line any more", logs.none { it.startsWith("cover fade") })
    }

    @Test
    fun `audio handed over after the glide began joins it for what is left of it`() {
        // Dolphin's game screen is up before Mimir stops, so the glide can begin before the yield hands the audio over.
        arm()
        advanceTo(1_300L)
        resume("com.armsx2", "com.armsx2.Main")
        advanceTo(1_500L) // hold over, glide begun
        assertEquals(listOf("mount", "glide:$glideMs"), window.calls)

        val audio = FakeAudio()
        advanceTo(1_560L) // 60 ms into the glide
        assertTrue(controller.yielded(gameId, audio))

        assertEquals(listOf(glideMs - 60L), audio.fadeOuts)
    }

    // ---- (a) the player came back

    @Test
    fun `Mimir starting takes the window away at once, with the audio and the subscription, and waits for nothing`() {
        arm()
        val audio = FakeAudio()
        controller.yielded(gameId, audio)
        resume("com.armsx2", "com.armsx2.Main")

        controller.hostStarted()

        assertEquals("removed synchronously, no glide", listOf("mount", "remove"), window.calls)
        assertEquals(1, audio.released)
        assertEquals("no fade was asked of the audio either", emptyList<Long>(), audio.fadeOuts)
        assertNothingLeftAttached()
        assertTrue(logs.toString(), logs.any { it.startsWith("cover end game=$gameId via=onStart") })
    }

    @Test
    fun `Mimir starting mid-glide cuts it`() {
        arm()
        controller.yielded(gameId, FakeAudio())
        resume("com.armsx2", "com.armsx2.Main")
        advanceTo(1_000L + GameLaunchCoverGameHoldMs)
        assertEquals(listOf("mount", "glide:$glideMs"), window.calls)

        controller.hostStarted()

        assertEquals(listOf("mount", "glide:$glideMs", "remove"), window.calls)
        assertNothingLeftAttached()
    }

    @Test
    fun `Mimir starting with no cover up does nothing`() {
        controller.hostStarted()
        controller.hostStopped()
        controller.teardown()

        assertEquals(emptyList<String>(), window.calls)
        assertEquals(emptyList<String>(), logs)
    }

    // ---- (b) anything outside the pair

    @Test
    fun `a stranger's screen takes the cover away at once and the log names it`() {
        arm()
        controller.yielded(gameId, FakeAudio())

        resume("com.armsx2", "com.armsx2.ErrorDialog")

        assertEquals(listOf("mount", "remove"), window.calls)
        assertNothingLeftAttached()
        assertTrue(
            logs.toString(),
            logs.any { it.startsWith("cover end game=$gameId via=foreign") && it.endsWith("activity=com.armsx2/com.armsx2.ErrorDialog") },
        )
    }

    @Test
    fun `Mimir's own activities resuming say nothing, the return is onStart's to report`() {
        arm()

        resume("com.gruning.mimir.rewrite", "com.gruning.mimir.rewrite.MainActivity")

        assertEquals(listOf("mount"), window.calls)
        assertEquals(gameId, controller.liveGameIdValue)
    }

    @Test
    fun `the monitor's thread reaches the main thread before the controller acts on a resume`() {
        arm()
        observer.deliver(ActivityResumedObservation("com.android.settings", "x.Y", EPOCH + 10L, EPOCH + 20L))

        assertEquals("not yet: the observation is queued for the main thread", listOf("mount"), window.calls)
        assertEquals(1, mainQueue.size)

        drainMain()
        assertEquals(listOf("mount", "remove"), window.calls)
    }

    // ---- (d) the ceiling, and the launches that never yield

    @Test
    fun `the ceiling glides out and removes 6 s after the yield`() {
        arm()
        val audio = FakeAudio()
        advanceTo(2_000L)
        controller.hostStopped()
        controller.yielded(gameId, audio)

        advanceTo(2_000L + GameLaunchCoverCeilingMs - 1L)
        assertEquals(listOf("mount"), window.calls)

        advanceTo(2_000L + GameLaunchCoverCeilingMs)
        assertEquals(listOf("mount", "glide:$glideMs"), window.calls)
        assertEquals(listOf(glideMs), audio.fadeOuts)

        advanceTo(2_000L + GameLaunchCoverCeilingMs + glideMs)
        assertEquals(listOf("mount", "glide:$glideMs", "remove"), window.calls)
        assertNothingLeftAttached()
        assertTrue(logs.toString(), logs.any { it.startsWith("cover end game=$gameId via=ceiling") })
    }

    @Test
    fun `a launch that never yields is cut at 6 s from the mount, with no glide`() {
        arm()
        advanceTo(1_000L + GameLaunchCoverUnyieldedCeilingMs - 1L)
        assertEquals(listOf("mount"), window.calls)

        advanceTo(1_000L + GameLaunchCoverUnyieldedCeilingMs)

        assertEquals(listOf("mount", "remove"), window.calls)
        assertNothingLeftAttached()
        assertTrue(logs.toString(), logs.any { it.startsWith("cover end game=$gameId via=ceiling elapsedMs=6000 sinceYieldMs=none") })
    }

    @Test
    fun `a launch that did not take Mimir's screen ends the cover at once`() {
        arm()
        controller.settled("another game")
        assertEquals("another launch's verdict is not this one's", listOf("mount"), window.calls)

        controller.settled(gameId)

        assertEquals(listOf("mount", "remove"), window.calls)
        assertNothingLeftAttached()
        assertTrue(logs.toString(), logs.any { it.startsWith("cover end game=$gameId via=settled") })
    }

    // ---- audio

    @Test
    fun `audio offered when no cover is up is released and the caller is told to handle it itself`() {
        val audio = FakeAudio()

        assertFalse(controller.yielded(gameId, audio))

        assertEquals(1, audio.released)
    }

    @Test
    fun `a second player for the same cover is released and the first keeps its place`() {
        arm()
        val first = FakeAudio()
        val second = FakeAudio()

        controller.yielded(gameId, first)
        controller.yielded(gameId, second)

        assertEquals(0, first.released)
        assertEquals(1, second.released)
    }

    @Test
    fun `a yield with no audio to take still starts the ceiling`() {
        arm()
        advanceTo(2_000L)

        assertTrue(controller.yielded(gameId, null))

        advanceTo(2_000L + GameLaunchCoverCeilingMs)
        assertEquals(listOf("mount", "glide:$glideMs"), window.calls)
    }

    // ---- reduced motion

    @Test
    fun `with animators off there is no glide, only the removal`() {
        glideMs = 0L
        arm()
        val audio = FakeAudio()
        controller.yielded(gameId, audio)
        resume("com.armsx2", "com.armsx2.Main")

        advanceTo(1_000L + GameLaunchCoverGameHoldMs)

        assertEquals(listOf("mount", "remove"), window.calls)
        assertEquals(emptyList<Long>(), audio.fadeOuts)
        assertEquals(1, audio.released)
        assertNothingLeftAttached()
    }

    // ---- robustness: nothing may leave a window attached

    @Test
    fun `a window that throws on removal still lets the subscription, the wake-up and the audio go`() {
        arm()
        val audio = FakeAudio()
        controller.yielded(gameId, audio)
        window.removeThrows = true

        controller.hostStarted()

        assertEquals(1, audio.released)
        assertNothingLeftAttached()
        assertTrue(logs.toString(), logs.any { it.startsWith("cover window removal failed game=$gameId") })
        assertTrue(logs.toString(), logs.any { it.startsWith("cover end game=$gameId via=onStart") })
    }

    @Test
    fun `a subscription that cannot be made does not stop the cover, whose ceilings still end it`() {
        observer.failToSubscribe = true
        assertTrue(arm())
        assertTrue(logs.toString(), logs.any { it.startsWith("cover observe unavailable game=$gameId") })

        advanceTo(1_000L + GameLaunchCoverUnyieldedCeilingMs)

        assertEquals(listOf("mount", "remove"), window.calls)
        assertNothingLeftAttached()
    }

    @Test
    fun `a wake-up that outlives its cover does nothing to the next one`() {
        arm("first")
        controller.hostStarted()
        arm("second")
        // The first cover's wake-up was cancelled with it; run whatever is left and the second is untouched.
        advanceTo(1_000L + GameLaunchCoverGameHoldMs)

        assertEquals("second", controller.liveGameIdValue)
        assertEquals(listOf("mount", "remove", "mount"), window.calls)
    }

    @Test
    fun `every way a cover can end leaves no window, no wake-up, no subscription and no audio`() {
        val ends = listOf<Pair<String, () -> Unit>>(
            "onStart" to { controller.hostStarted() },
            "foreign" to { resume("com.android.settings", "x.Y") },
            "game" to {
                resume("com.armsx2", "com.armsx2.Main")
                advanceTo(now + GameLaunchCoverGameHoldMs + GameLaunchCoverGlideMs)
            },
            "ceiling" to { advanceTo(now + GameLaunchCoverCeilingMs + GameLaunchCoverGlideMs) },
            "settled" to { controller.settled(gameId) },
            "teardown" to { controller.teardown() },
        )
        for ((name, end) in ends) {
            val audio = FakeAudio()
            arm()
            controller.hostStopped()
            controller.yielded(gameId, audio)

            end()

            assertNothingLeftAttached("end=$name")
            assertEquals("end=$name: audio released once", 1, audio.released)
            assertNull("end=$name", controller.liveGameIdValue)
        }
    }

    private fun assertNothingLeftAttached(message: String = "") {
        assertFalse("$message window still mounted", window.mounted)
        assertEquals("$message wake-ups pending", 0, scheduler.pending)
        assertEquals("$message subscriptions open", 0, observer.subscriptions)
        assertNull("$message", controller.liveGameIdValue)
    }

    // ---- fakes

    private class FakeWindow : GameLaunchCoverWindow {
        val calls = mutableListOf<String>()
        var mounted = false
        var revealed = false
        var mountResult = true
        var removeThrows = false

        override fun mount(snapshot: GameLaunchCoverSnapshot, onRevealed: () -> Unit): Boolean {
            if (!mountResult) return false
            calls += "mount"
            mounted = true
            return true
        }

        override fun beginGlide(glideMs: Long) {
            calls += "glide:$glideMs"
        }

        override fun removeImmediate() {
            calls += "remove"
            mounted = false
            if (removeThrows) error("window gone")
        }
    }

    private class FakeAudio : GameLaunchCoverAudio {
        val fadeOuts = mutableListOf<Long>()
        var released = 0

        override fun fadeOut(durationMs: Long) {
            fadeOuts += durationMs
        }

        override fun release() {
            released += 1
        }
    }

    private class FakeScheduler : GameLaunchCoverScheduler {
        private class Task(val at: Long, val run: () -> Unit, var cancelled: Boolean = false)

        private val tasks = mutableListOf<Task>()
        val pending: Int get() = tasks.count { !it.cancelled }

        override fun postAt(uptimeMs: Long, task: () -> Unit): AutoCloseable {
            val entry = Task(uptimeMs, task)
            tasks += entry
            return AutoCloseable { entry.cancelled = true }
        }

        fun advanceTo(target: Long, setNow: (Long) -> Unit) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                setNow(next.at)
                next.cancelled = true
                next.run()
            }
        }
    }

    private class FakeObserver {
        val sinceEpochMs = mutableListOf<Long>()
        var failToSubscribe = false
        private val listeners = mutableListOf<(ActivityResumedObservation) -> Unit>()
        val subscriptions: Int get() = listeners.size

        fun subscribe(since: Long, onResumed: (ActivityResumedObservation) -> Unit): AutoCloseable {
            if (failToSubscribe) error("monitor not started")
            sinceEpochMs += since
            listeners += onResumed
            return AutoCloseable { listeners.remove(onResumed) }
        }

        fun deliver(observation: ActivityResumedObservation) {
            listeners.toList().forEach { it(observation) }
        }
    }

    private companion object {
        const val EPOCH = 5_000_000L
    }
}
