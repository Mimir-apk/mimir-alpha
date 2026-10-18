package com.gruning.mimir.rewrite.core.audio

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Pause during calls" and the identity of an audio-focus request, as pure logic over fakes of the system's side. The mode listener, the
 * focus callbacks and the handler are Android-bound, so the control's use of them is pinned on its source.
 */
class MusicCallAndFocusRequestTest {

    // ── Pause during calls: the rule ────────────────────────────────────────────────────────────────

    @Test
    fun `a phone call, a VoIP call and a ringing phone are calls, and nothing else is`() {
        assertTrue(MusicCallPolicy.inCall(AudioManager.MODE_IN_CALL))
        assertTrue(MusicCallPolicy.inCall(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(MusicCallPolicy.inCall(AudioManager.MODE_RINGTONE))
        assertFalse(MusicCallPolicy.inCall(AudioManager.MODE_NORMAL))
        assertFalse(MusicCallPolicy.inCall(AudioManager.MODE_CURRENT))
        assertFalse(MusicCallPolicy.inCall(AudioManager.MODE_INVALID))
    }

    @Test
    fun `a call holds the music under the other-app reason, with or without focus`() {
        val call = MusicPausePolicy.onCall(MusicPauseState(), inCall = true)
        assertTrue(call.paused)
        assertEquals(MusicPauseReason.OTHER_APP, call.reason)
        assertTrue(MusicPausePolicy.forYours(call, pausesOnScreenOff = false).paused)
        assertFalse("the control arm: no call, no hold", MusicPausePolicy.onCall(MusicPauseState(), inCall = false).paused)
    }

    @Test
    fun `the game exemption plus a VoIP call holds the music, and the mode returning to normal resumes it as today`() {
        // A game is in front: Mimir has let go of focus, so no focus loss will ever reach it. The music plays on (the exemption).
        val playingUnderGame = MusicPauseState()
        assertTrue(MusicPausePolicy.abandonsFocusForGame(focusHeld = true, gameInFront = true))
        assertFalse(playingUnderGame.paused)
        // A VoIP call starts: the audio mode says so.
        val held = MusicPausePolicy.onCall(playingUnderGame, MusicCallPolicy.inCall(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue("held, though no focus was held and none was lost", held.paused)
        assertEquals(MusicPauseReason.OTHER_APP, held.reason)
        assertEquals(
            "and no request is made for focus while the call is up",
            MusicPausePolicy.FocusDecision.NONE,
            MusicPausePolicy.focusDecision(held, gameInFront = true, deliberate = false, otherPlayerPlaying = false),
        )
        assertFalse(MusicPausePolicy.mayRequestFocus(held, gameInFront = false))
        // The mode returns to normal: the hold clears, and the game is still in front, so it plays on as it did before the call.
        val after = MusicPausePolicy.onCall(held, MusicCallPolicy.inCall(AudioManager.MODE_NORMAL))
        assertFalse(after.paused)
        assertEquals(playingUnderGame, after)
        assertEquals(
            "still no request under the game: the exemption is as it was",
            MusicPausePolicy.FocusDecision.NONE,
            MusicPausePolicy.focusDecision(after, gameInFront = true, deliberate = false, otherPlayerPlaying = false),
        )
    }

    @Test
    fun `a call that ends away from a game resumes through the normal path, which asks for focus`() {
        val held = MusicPausePolicy.onCall(MusicPauseState(), inCall = true)
        val ended = MusicPausePolicy.onCall(held, inCall = false)
        assertFalse(ended.paused)
        assertEquals(
            MusicPausePolicy.FocusDecision.REQUEST,
            MusicPausePolicy.focusDecision(ended, gameInFront = false, deliberate = false, otherPlayerPlaying = false),
        )
    }

    @Test
    fun `a phone call with focus holds until both the mode is normal and the focus gain has arrived`() {
        var state = MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.LOSS_TRANSIENT, gameInFront = false)
        state = MusicPausePolicy.onCall(state, inCall = true)
        // The mode returns first: the focus hold still holds.
        val modeFirst = MusicPausePolicy.onCall(state, inCall = false)
        assertTrue(modeFirst.paused)
        assertEquals(OtherAppHold.TRANSIENT, modeFirst.otherApp)
        assertFalse(MusicPausePolicy.onFocus(modeFirst, FocusSignal.GAIN, gameInFront = false).paused)
        // The gain comes first: the call still holds.
        val gainFirst = MusicPausePolicy.onFocus(state, FocusSignal.GAIN, gameInFront = false)
        assertTrue(gainFirst.paused)
        assertFalse(MusicPausePolicy.onCall(gainFirst, inCall = false).paused)
    }

    @Test
    fun `a call is a fact about the device, so a press of play and the other transitions leave it alone`() {
        val call = MusicPausePolicy.onCall(MusicPauseState(user = true, headphones = true, schedule = true), inCall = true)
        val pressed = MusicPausePolicy.onUserPlayPause(call, yoursPaused = true)
        assertEquals("play takes over the choices, not the call", MusicPauseState(call = true), pressed)
        assertTrue(pressed.paused)
        assertTrue(MusicPausePolicy.onScreen(call, on = false).call)
        assertTrue(MusicPausePolicy.onScreen(call, on = true).call)
        assertTrue(MusicPausePolicy.onFocus(call, FocusSignal.GAIN, gameInFront = false).call)
        assertTrue(MusicPausePolicy.onSchedule(call, outsideHours = false).call)
        assertTrue(MusicPausePolicy.onFocusAbandoned(call).call)
        // The reasons keep their order with a call in them: the player's pause and the cable are named first, the screen and hours last.
        assertEquals(MusicPauseReason.USER, MusicPauseState(user = true, call = true).reason)
        assertEquals(MusicPauseReason.HEADPHONES, MusicPauseState(headphones = true, call = true).reason)
        assertEquals(MusicPauseReason.OTHER_APP, MusicPauseState(screenOff = true, schedule = true, call = true).reason)
    }

    // ── Pause during calls: the watch ───────────────────────────────────────────────────────────────

    private class FakePort(override val pushes: Boolean) : AudioModePort {
        var mode = AudioManager.MODE_NORMAL
        var listener: ((Int) -> Unit)? = null
        var listens = 0
        var unlistens = 0
        val posted = mutableListOf<Pair<Long, Runnable>>()
        var cancels = 0

        override fun mode(): Int = mode

        override fun listen(onMode: (Int) -> Unit) {
            listens += 1
            listener = onMode
        }

        override fun unlisten() {
            unlistens += 1
            // A callback already on its way would still reach the watch: the listener is kept to be called after removal.
        }

        override fun postDelayed(delayMs: Long, task: Runnable) {
            posted += delayMs to task
        }

        override fun cancel(task: Runnable) {
            cancels += 1
            posted.removeAll { it.second === task }
        }
    }

    private fun newWatch(port: FakePort, reports: MutableList<Boolean>) = MusicCallWatch(port) { reports += it }

    @Test
    fun `no listener and no poll while the music is off`() {
        for (pushes in listOf(true, false)) {
            val port = FakePort(pushes)
            val reports = mutableListOf<Boolean>()
            val watch = newWatch(port, reports)
            assertFalse(MusicCallPolicy.watches(musicWanted = false))
            watch.setWanted(MusicCallPolicy.watches(false))
            watch.setWanted(MusicCallPolicy.watches(false))
            assertEquals("pushes=$pushes: nothing registered", 0, port.listens)
            assertEquals(0, port.posted.size)
            assertTrue("and nothing reported", reports.isEmpty())
        }
    }

    @Test
    fun `wanted music registers one listener, reads the mode at once and follows it`() {
        val port = FakePort(pushes = true)
        val reports = mutableListOf<Boolean>()
        val watch = newWatch(port, reports)
        assertTrue(MusicCallPolicy.watches(musicWanted = true))
        watch.setWanted(true)
        watch.setWanted(true)
        assertEquals("one listener however often it is asked", 1, port.listens)
        assertEquals("the mode is read as it starts", listOf(false), reports)
        port.listener!!(AudioManager.MODE_IN_COMMUNICATION)
        port.listener!!(AudioManager.MODE_NORMAL)
        port.listener!!(AudioManager.MODE_RINGTONE)
        port.listener!!(AudioManager.MODE_IN_CALL)
        assertEquals(listOf(false, true, false, true, true), reports)
        assertEquals("a push never starts a poll", 0, port.posted.size)
    }

    @Test
    fun `a call already up when the music becomes wanted holds it from the start`() {
        val port = FakePort(pushes = true).also { it.mode = AudioManager.MODE_IN_COMMUNICATION }
        val reports = mutableListOf<Boolean>()
        newWatch(port, reports).setWanted(true)
        assertEquals(listOf(true), reports)
    }

    @Test
    fun `turning the music off removes the listener, clears the hold, and ignores a callback still on its way`() {
        val port = FakePort(pushes = true)
        val reports = mutableListOf<Boolean>()
        val watch = newWatch(port, reports)
        watch.setWanted(true)
        port.listener!!(AudioManager.MODE_IN_CALL)
        watch.setWanted(false)
        assertEquals(1, port.unlistens)
        assertEquals("a hold the watch set does not outlive it", false, reports.last())
        val reported = reports.size
        port.listener!!(AudioManager.MODE_IN_CALL)
        assertEquals("a late push after removal changes nothing", reported, reports.size)
        // And it can be wanted again.
        watch.setWanted(true)
        assertEquals(2, port.listens)
    }

    @Test
    fun `where the system cannot push the mode, a slow poll runs only while the music is wanted`() {
        val port = FakePort(pushes = false)
        val reports = mutableListOf<Boolean>()
        val watch = newWatch(port, reports)
        watch.setWanted(true)
        assertEquals("no listener on Android 11", 0, port.listens)
        assertEquals("one poll pending, at the slow interval", listOf(MusicCallPolicy.ModePollMs), port.posted.map { it.first })
        assertEquals(listOf(false), reports)
        // The poll runs, sees a call, and posts itself again.
        port.mode = AudioManager.MODE_IN_COMMUNICATION
        val task = port.posted.single().second
        port.posted.clear()
        task.run()
        assertEquals(listOf(false, true), reports)
        assertEquals(1, port.posted.size)
        // The music goes off: the poll is cancelled, the hold is cleared, and a run that was already queued does nothing.
        watch.setWanted(false)
        assertEquals(1, port.cancels)
        assertEquals(0, port.posted.size)
        assertEquals(false, reports.last())
        val reported = reports.size
        task.run()
        assertEquals(reported, reports.size)
        assertEquals("and it posted nothing more", 0, port.posted.size)
    }

    @Test
    fun `the control watches the mode only through the wanted flag, and the poll is only for where the system cannot push`() {
        val control = source("MimirMusicControl.kt")
        assertTrue(control.contains("yourMusicActive.collect { active -> worker.post { callWatch?.setWanted(MusicCallPolicy.watches(active)) } }"))
        assertEquals("the watch is only ever started by that collector", 1, Regex("""setWanted\(""").findAll(control).count())
        assertTrue(control.contains("MusicPausePolicy.onCall(it, inCall)"))
        val watch = source("MusicCallWatch.kt")
        assertTrue(watch.contains("val pushes: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertEquals(
            "the Android 12 types sit behind the version check, in every method that names them",
            4,
            Regex("""Build\.VERSION\.SDK_INT (>=|<) Build\.VERSION_CODES\.S""").findAll(watch).count() - 1,
        )
    }

    // ── The identity of a focus request ─────────────────────────────────────────────────────────────

    /** The control's decision on a callback, built from the same pieces: the generation, then the held flag, then the policy. */
    private fun deliver(
        generations: FocusRequestGeneration,
        generation: Long,
        focusHeld: Boolean,
        state: MusicPauseState,
        signal: FocusSignal,
    ): MusicPauseState =
        if (!generations.isCurrent(generation) || !focusHeld) state else MusicPausePolicy.onFocus(state, signal, gameInFront = false)

    @Test
    fun `an old listener's transient loss after an abandon and a fresh grant has no effect`() {
        val generations = FocusRequestGeneration()
        val first = generations.issue() // the first request, granted
        generations.retire() // let go (schedule closed, backgrounded, a game came to the front)
        val second = generations.issue() // asked afresh, and granted
        val playing = MusicPausePolicy.onFocus(MusicPauseState(), FocusSignal.GAIN, gameInFront = false)
        assertFalse(playing.paused)
        // The first request's loss was already queued when it was let go; it is delivered now, with a request held.
        val lateLoss = deliver(generations, first, focusHeld = true, state = playing, signal = FocusSignal.LOSS_TRANSIENT)
        assertEquals("the music plays on: no hold from a request that is gone", playing, lateLoss)
        assertNull(lateLoss.reason)
        // The new request's own loss counts.
        val realLoss = deliver(generations, second, focusHeld = true, state = playing, signal = FocusSignal.LOSS_TRANSIENT)
        assertEquals(OtherAppHold.TRANSIENT, realLoss.otherApp)
        // The control arm: the held flag alone (what it checked before) lets the old callback through, with a hold no gain could end.
        val heldFlagOnly = MusicPausePolicy.onFocus(playing, FocusSignal.LOSS_TRANSIENT, gameInFront = false)
        assertEquals(OtherAppHold.TRANSIENT, heldFlagOnly.otherApp)
    }

    @Test
    fun `an abandon retires the generation even when no new request follows`() {
        val generations = FocusRequestGeneration()
        val only = generations.issue()
        assertTrue(generations.isCurrent(only))
        generations.retire()
        assertFalse(generations.isCurrent(only))
        val next = generations.issue()
        assertTrue(generations.isCurrent(next))
        assertFalse(generations.isCurrent(only))
        assertTrue("generations never repeat", next != only)
    }

    @Test
    fun `every request has a listener and a generation of its own, and an abandon retires it`() {
        val control = source("MimirMusicControl.kt")
        val fresh = control.substringAfter("private fun newFocusRequest()").substringBefore("Brings focus into line")
        assertTrue(fresh.contains("focusGenerations.issue()"))
        assertTrue("a new listener per request, tied to its generation", fresh.contains("OnAudioFocusChangeListener { change -> onFocusChange(generation, change) }"))
        assertFalse("no listener shared between requests", control.contains("private val focusListener"))
        val change = control.substringAfter("private fun onFocusChange(").substringBefore("private fun newFocusRequest()")
        assertTrue(change.indexOf("focusGenerations.isCurrent(generation)") in 0 until change.indexOf("MusicPausePolicy.onFocus(it, signal, game)"))
        val abandon = control.substringAfter("private fun abandonFocus(").substringBefore("// --- Other audio")
        assertTrue(abandon.indexOf("focusGenerations.retire()") in 0 until abandon.indexOf("abandonAudioFocusRequest"))
        assertTrue("each request is made fresh", control.contains("manager.requestAudioFocus(newFocusRequest())"))
    }

    private fun source(name: String): String =
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/audio/$name").readText()
}
