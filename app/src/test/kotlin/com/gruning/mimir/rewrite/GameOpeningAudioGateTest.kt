package com.gruning.mimir.rewrite

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowMediaPlayer
import org.robolectric.shadows.util.DataSource
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.ContinuationInterceptor

/**
 * Observed on Thor: with a game's launch card open on the Bottom, closing the lid slept the device but its sound kept playing. The
 * card's opening sound is a looping `MediaPlayer`; `GameOpeningIntroState` could release it and never pause it, so it looped on
 * (the media server restarted its track every ~15 s through the sleep) while Your music, which follows `MusicPausePolicy`, paused.
 *
 * The card's sound now follows the same policy (`MimirMusicControl.pauseReason`) plus the Bottom window's liveness, through one
 * idempotent gate. These tests drive it with the policy's reason and the window's two flags as flows, and a fake voice that counts
 * what the gate told it. Robolectric only for the `MimirOpening` log lines and the released-player state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = Application::class)
class GameOpeningAudioGateTest {
    private val gameId = "card-game"
    private val musicPause = MutableStateFlow<MusicPauseReason?>(null)
    private val parked = MutableStateFlow(false)
    private val screenOn = MutableStateFlow(true)
    private val scopes = mutableListOf<CoroutineScope>()
    private lateinit var originalSources: () -> OpeningAudioSources
    private lateinit var originalPlayerFactory: () -> MediaPlayer

    /**
     * What the gate told the voice, on which thread, and the player's native loop: it wraps only while the voice is sounding, as a paused
     * MediaPlayer does. [throwOnResume] and [throwOnPause] are a player in the wrong state.
     */
    private class FakeVoice : OpeningAudioVoice {
        var starts = 0
        var pauses = 0
        var sounding = false
        var loops = 0
        var throwOnResume = false
        var throwOnPause = false
        val threads = mutableListOf<Thread>()

        override fun resume() {
            threads += Thread.currentThread()
            if (throwOnResume) throw IllegalStateException("start called in the wrong state")
            starts++
            sounding = true
        }

        override fun pause() {
            threads += Thread.currentThread()
            if (throwOnPause) throw IllegalStateException("pause called in the wrong state")
            pauses++
            sounding = false
        }

        fun runFor(ms: Long) {
            if (sounding) loops += (ms / 15_000L).toInt()
        }
    }

    @Before
    fun setUp() {
        ShadowLog.clear()
        GameOpeningIntroState.hide()
        originalSources = GameOpeningIntroState.openingAudioSources
        originalPlayerFactory = GameOpeningIntroState.openingPlayerFactory
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        GameOpeningIntroState.openingAudioSources = { OpeningAudioSources(musicPause, scope) }
        GameOpeningIntroState.bottomWindow.value = CompanionWindowLive(parked, screenOn)
    }

    @After
    fun tearDown() {
        GameOpeningIntroState.hide()
        GameOpeningIntroState.openingAudioSources = originalSources
        GameOpeningIntroState.openingPlayerFactory = originalPlayerFactory
        GameOpeningIntroState.bottomWindow.value = null
        scopes.forEach { it.coroutineContext[Job]?.cancel() }
    }

    private fun logs(): List<String> = ShadowLog.getLogsForTag("MimirOpening").map { it.msg }

    private fun plan(id: String = gameId) = GameOpeningIntroPlan(
        gameIdValue = id,
        title = "Card Game",
        heroUri = "file:///hero.png",
        logoUri = null,
        audioUri = null,
    )

    /** The card opens: the plan is shown and its looping sound is attached, as `presentGameOpening` does. */
    private fun openCard(voice: FakeVoice, player: MediaPlayer = MediaPlayer(), id: String = gameId): MediaPlayer {
        if (GameOpeningIntroState.current.value?.gameIdValue != id) GameOpeningIntroState.show(plan(id))
        assertTrue(GameOpeningIntroState.attachPlayer(id, player, targetVolume = 0.5f, presentation = voice))
        return player
    }

    private fun lines(prefix: String) = logs().filter { it.startsWith(prefix) }

    // ---- the decision

    @Test
    fun `the opening sound holds for every reason the music holds for, and for a window that is not live, and for nothing else`() {
        assertEquals(null, openingAudioHold(null, windowLive = true))
        assertEquals(OpeningAudioHold.SCREEN_OFF, openingAudioHold(MusicPauseReason.SCREEN_OFF, true))
        assertEquals(OpeningAudioHold.HEADPHONES, openingAudioHold(MusicPauseReason.HEADPHONES, true))
        assertEquals(OpeningAudioHold.OTHER_APP, openingAudioHold(MusicPauseReason.OTHER_APP, true))
        assertEquals(OpeningAudioHold.USER, openingAudioHold(MusicPauseReason.USER, true))
        assertEquals(OpeningAudioHold.WINDOW_NOT_LIVE, openingAudioHold(null, windowLive = false))
        // The music hours are the one reason that is not a hold: a game's launch sound is not the music (Music hours, 10-07).
        for (reason in MusicPauseReason.values().filter { it != MusicPauseReason.SCHEDULE }) {
            assertTrue("$reason holds it however lit the window is", openingAudioHold(reason, true) != null)
            assertTrue("$reason still holds it with the window dark", openingAudioHold(reason, false) != null)
        }
        assertEquals("the music hours hold nothing while the window is lit", null, openingAudioHold(MusicPauseReason.SCHEDULE, true))
        assertEquals(OpeningAudioHold.WINDOW_NOT_LIVE, openingAudioHold(MusicPauseReason.SCHEDULE, false))
    }

    // ---- screen off (a closed lid)

    @Test
    fun `screen off during playback pauses the sound, says why once, and nothing restarts it while off`() {
        val voice = FakeVoice()
        openCard(voice)
        assertTrue("the card's sound starts when nothing holds it", voice.sounding)
        assertEquals(1, voice.starts)
        voice.runFor(15_000L)
        assertEquals("it loops while it sounds", 1, voice.loops)

        // The lid closes: the music's policy and this window's own screen watcher both say so.
        musicPause.value = MusicPauseReason.SCREEN_OFF
        screenOn.value = false

        assertFalse(voice.sounding)
        assertEquals(1, voice.pauses)
        assertEquals(listOf("opening pause reason=SCREEN_OFF"), lines("opening pause"))
        // The 10:49:29, 10:49:45 and 10:50:00 restarts of the log: a held sound has none.
        voice.runFor(60_000L)
        assertEquals("no loop wrapped while off", 1, voice.loops)
        assertEquals("and nothing started it again", 1, voice.starts)
    }

    @Test
    fun `the loop does not restart while paused, whatever else changes meanwhile`() {
        val voice = FakeVoice()
        openCard(voice)
        musicPause.value = MusicPauseReason.SCREEN_OFF

        // The reason changes while held, the window parks and unparks: none of it is a release of the hold.
        musicPause.value = MusicPauseReason.HEADPHONES
        parked.value = true
        parked.value = false
        voice.runFor(120_000L)

        assertFalse(voice.sounding)
        assertEquals(0, voice.loops)
        assertEquals("never started again", 1, voice.starts)
        assertEquals("held once, one decision, one line", 1, lines("opening pause").size)
        assertEquals(0, lines("opening resume").size)
    }

    @Test
    fun `a card opened while the screen is off never starts, and starts when the screen comes on with the card still open`() {
        musicPause.value = MusicPauseReason.SCREEN_OFF
        screenOn.value = false
        val voice = FakeVoice()
        openCard(voice)

        assertEquals("never started playing while the screen is off", 0, voice.starts)
        assertFalse(voice.sounding)
        assertEquals(listOf("opening pause reason=SCREEN_OFF"), lines("opening pause"))
        assertFalse(GameOpeningIntroState.cardAudioSounding(gameId))

        musicPause.value = null
        assertEquals("one lit signal is not enough while the window is dark", 0, voice.starts)
        screenOn.value = true
        assertEquals(1, voice.starts)
        assertTrue(voice.sounding)
        assertEquals(listOf("opening resume"), lines("opening resume"))
    }

    // ---- screen on

    @Test
    fun `screen on with the card still open resumes the sound once both the music's policy and the window agree`() {
        val voice = FakeVoice()
        openCard(voice)
        musicPause.value = MusicPauseReason.SCREEN_OFF
        screenOn.value = false

        musicPause.value = null
        assertFalse("the window's screen has not reported yet", voice.sounding)
        screenOn.value = true

        assertTrue(voice.sounding)
        assertEquals(2, voice.starts)
        assertEquals(listOf("opening resume"), lines("opening resume"))
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))
    }

    @Test
    fun `screen on with the card closed stays stopped`() {
        val voice = FakeVoice()
        val player = openCard(voice)
        musicPause.value = MusicPauseReason.SCREEN_OFF
        screenOn.value = false

        // The card closes while the screen is off (the dismiss path ends in hide).
        GameOpeningIntroState.hide()
        assertEquals("the intro's removal released the player", ShadowMediaPlayer.State.END, shadowOf(player).state)

        musicPause.value = null
        screenOn.value = true

        assertEquals("nothing started it again", 1, voice.starts)
        assertFalse(voice.sounding)
        assertEquals(0, lines("opening resume").size)
    }

    @Test
    fun `a card another intro replaced, or a launch took, is never resumed by a later screen on`() {
        val replaced = FakeVoice()
        openCard(replaced)
        musicPause.value = MusicPauseReason.SCREEN_OFF
        // Another intro replaces the card's (a Main launch shows its own plan).
        GameOpeningIntroState.show(plan("another-game"))
        musicPause.value = null
        assertEquals(1, replaced.starts)
        assertFalse(replaced.sounding)

        val taken = FakeVoice()
        openCard(taken, id = "another-game")
        musicPause.value = MusicPauseReason.SCREEN_OFF
        // The launch cover takes the player at the yield: it is no longer this intro's to pause or resume.
        val detached = GameOpeningIntroState.detachPlayer("another-game")
        musicPause.value = null
        assertEquals(1, taken.starts)
        assertTrue("the cover's player is not released by the intro", shadowOf(detached!!).state != ShadowMediaPlayer.State.END)
        detached.release()
    }

    // ---- the window

    @Test
    fun `the window parked stops the sound, and unparked with the card still open resumes it`() {
        val voice = FakeVoice()
        openCard(voice)

        parked.value = true
        assertFalse(voice.sounding)
        assertEquals(listOf("opening pause reason=window-not-live"), lines("opening pause"))

        parked.value = false
        assertTrue(voice.sounding)
        assertEquals(2, voice.starts)
        assertEquals(listOf("opening resume"), lines("opening resume"))
    }

    @Test
    fun `the window unparked after the card closed does not bring the sound back`() {
        val voice = FakeVoice()
        openCard(voice)
        parked.value = true
        GameOpeningIntroState.hide()

        parked.value = false

        assertEquals(1, voice.starts)
        assertFalse(voice.sounding)
        assertEquals(0, lines("opening resume").size)
    }

    @Test
    fun `a window that ends takes the card's sound with it, and a later window cannot resume it`() {
        val voice = FakeVoice()
        val player = openCard(voice)
        parked.value = true

        GameOpeningIntroState.stopCardAudio("window-stopped")

        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertEquals(listOf("opening stop reason=window-stopped"), lines("opening stop"))
        // The next window registers and is live: there is no voice left to resume.
        GameOpeningIntroState.bottomWindow.value = CompanionWindowLive(MutableStateFlow(false), MutableStateFlow(true))
        parked.value = false
        assertEquals(1, voice.starts)
        assertFalse(voice.sounding)
        // And with no card sound attached it is a no-op, so a launch's own intro sound is never touched by it.
        GameOpeningIntroState.stopCardAudio("game-launch")
        assertEquals(1, lines("opening stop").size)
    }

    @Test
    fun `the launch's one-shot intro sound is not the gate's, so a screen off does not touch its player`() {
        GameOpeningIntroState.show(plan())
        val player = MediaPlayer()
        assertTrue(GameOpeningIntroState.attachPlayer(gameId, player, targetVolume = 0.4f))

        musicPause.value = MusicPauseReason.SCREEN_OFF
        GameOpeningIntroState.stopCardAudio("game-launch")

        assertEquals(0, lines("opening pause").size)
        assertEquals(0, lines("opening stop").size)
        assertTrue("still attached, not released", shadowOf(player).state != ShadowMediaPlayer.State.END)
    }

    // ---- the other holds

    @Test
    fun `headphones, another app and the player's own pause hold the sound too, each named once`() {
        for ((reason, name) in listOf(
            MusicPauseReason.HEADPHONES to "HEADPHONES",
            MusicPauseReason.OTHER_APP to "OTHER_APP",
            MusicPauseReason.USER to "USER",
        )) {
            ShadowLog.clear()
            musicPause.value = null
            val voice = FakeVoice()
            openCard(voice)
            assertTrue("$reason: sounding before", voice.sounding)

            musicPause.value = reason
            musicPause.value = reason

            assertFalse("$reason: held", voice.sounding)
            assertEquals("$reason: one pause", 1, voice.pauses)
            assertEquals(listOf("opening pause reason=$name"), lines("opening pause"))

            musicPause.value = null
            assertTrue("$reason: resumed", voice.sounding)
            GameOpeningIntroState.hide()
        }
    }

    // ---- idempotence

    @Test
    fun `repeated off and on never double starts a sound or stacks a player`() {
        val voice = FakeVoice()
        openCard(voice)

        repeat(5) {
            musicPause.value = MusicPauseReason.SCREEN_OFF
            musicPause.value = MusicPauseReason.SCREEN_OFF
            screenOn.value = false
            screenOn.value = false
            assertFalse(voice.sounding)

            musicPause.value = null
            musicPause.value = null
            screenOn.value = true
            screenOn.value = true
            assertTrue(voice.sounding)
        }

        assertEquals("one start, then one per return", 6, voice.starts)
        assertEquals("one pause per off", 5, voice.pauses)
        assertEquals(5, lines("opening pause").size)
        assertEquals(5, lines("opening resume").size)
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))
    }

    @Test
    fun `a second attach for the same card releases the first player and hands the gate to the new voice`() {
        val first = FakeVoice()
        val firstPlayer = openCard(first)
        val second = FakeVoice()
        val secondPlayer = openCard(second)

        assertEquals("the first player is released, not stacked", ShadowMediaPlayer.State.END, shadowOf(firstPlayer).state)
        assertTrue(shadowOf(secondPlayer).state != ShadowMediaPlayer.State.END)

        musicPause.value = MusicPauseReason.SCREEN_OFF
        musicPause.value = null

        assertEquals("the old voice is never touched again", 1, first.starts)
        assertEquals(0, first.pauses)
        assertEquals(2, second.starts)
        assertEquals(1, second.pauses)
    }

    // ---- a stale watcher

    @Test
    fun `a stale collector from an earlier attach cannot resume the replacement while the window is not live`() {
        val first = FakeVoice()
        openCard(first)
        val staleGeneration = GameOpeningIntroState.currentAudioGateGeneration

        // The Bottom parks, then the same card attaches again (a replacement) while it is parked: held at its attach.
        parked.value = true
        val second = FakeVoice()
        openCard(second)
        assertNotEquals("every attach is stamped", staleGeneration, GameOpeningIntroState.currentAudioGateGeneration)
        assertEquals(0, second.starts)

        // The first attach's collector, blocked on the lock until now, finally runs with its old stamp and an old "live" reading.
        GameOpeningIntroState.reevaluateAudioGate(staleGeneration)

        assertEquals("the replacement is not resumed by a stale watcher", 0, second.starts)
        assertFalse(second.sounding)
        assertEquals(0, lines("opening resume").size)
        assertEquals("and the first voice is never touched again", 1, first.pauses)

        // The current attach's own watcher still works.
        parked.value = false
        assertEquals(1, second.starts)
        assertTrue(second.sounding)
    }

    @Test
    fun `a removal stamps the state too, so a watcher that outlives its card applies nothing`() {
        val voice = FakeVoice()
        openCard(voice)
        val generation = GameOpeningIntroState.currentAudioGateGeneration
        parked.value = true
        GameOpeningIntroState.hide()
        assertNotEquals(generation, GameOpeningIntroState.currentAudioGateGeneration)

        parked.value = false
        GameOpeningIntroState.reevaluateAudioGate(generation)

        assertEquals(1, voice.starts)
        assertFalse(voice.sounding)
    }

    // ---- one thread for the player

    @Test
    fun `every call on the card's player is made on the main looper, even when the change is made on another thread`() {
        val mainThread = Thread.currentThread()
        assertSame(Looper.getMainLooper().thread, mainThread)
        // The production scope shape: collectors on the main looper, immediate.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        GameOpeningIntroState.openingAudioSources = { OpeningAudioSources(musicPause, scope) }
        val voice = FakeVoice()
        openCard(voice)

        Thread { musicPause.value = MusicPauseReason.SCREEN_OFF }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse("the pause landed once the main looper ran", voice.sounding)

        Thread { musicPause.value = null }.apply { start(); join() }
        Thread { parked.value = true }.apply { start(); join() }
        Thread { parked.value = false }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(voice.sounding)

        assertEquals("start, pause, start", 3, voice.threads.size)
        assertTrue("all on the main thread: ${voice.threads}", voice.threads.all { it === mainThread })
    }

    @Test
    fun `the production watcher runs on the main looper`() {
        assertSame(Dispatchers.Main.immediate, OpeningAudioSources.watchScope.coroutineContext[ContinuationInterceptor])
    }

    @Test
    fun `a release asked for from another thread is posted to the main looper, not made there`() {
        val player = MediaPlayer()
        Thread { releaseOnMainLooper(player) }.apply { start(); join() }
        assertTrue("not released from the worker", shadowOf(player).state != ShadowMediaPlayer.State.END)

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
    }

    // ---- a call that throws is a failure, never a success

    @Test
    fun `a pause that throws releases the player and records it stopped, not sounding`() {
        val voice = FakeVoice()
        val player = openCard(voice)
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))
        voice.throwOnPause = true

        musicPause.value = MusicPauseReason.SCREEN_OFF

        assertEquals("the player is released", ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertFalse("and never reported as sounding", GameOpeningIntroState.cardAudioSounding(gameId))
        assertEquals(listOf("opening audio failed reason=IllegalStateException"), lines("opening audio failed"))
        assertEquals("no hold was recorded that did not happen", 0, lines("opening pause").size)

        // Nothing brings it back: the voice is gone, whatever the inputs do.
        voice.throwOnPause = false
        musicPause.value = null
        parked.value = true
        parked.value = false
        assertEquals(1, voice.starts)
        assertFalse(GameOpeningIntroState.cardAudioSounding(gameId))
    }

    @Test
    fun `a start that throws at the attach leaves the card silent with its player released`() {
        val voice = FakeVoice().apply { throwOnResume = true }
        val player = MediaPlayer()
        GameOpeningIntroState.show(plan())

        assertTrue("the attach itself is accepted", GameOpeningIntroState.attachPlayer(gameId, player, 0.5f, presentation = voice))

        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertFalse(GameOpeningIntroState.cardAudioSounding(gameId))
        assertEquals(listOf("opening audio failed reason=IllegalStateException"), lines("opening audio failed"))
        assertEquals("nothing is left to carry a level to the cover", 1f, GameOpeningIntroState.audioTargetFor(gameId), 0f)
    }

    @Test
    fun `a resume that throws after a hold releases the player and is not logged as a resume`() {
        val voice = FakeVoice()
        val player = openCard(voice)
        musicPause.value = MusicPauseReason.SCREEN_OFF
        voice.throwOnResume = true

        musicPause.value = null

        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertFalse(GameOpeningIntroState.cardAudioSounding(gameId))
        assertEquals(0, lines("opening resume").size)
        assertEquals(1, lines("opening audio failed").size)
    }

    // ---- a player that ends with NO throw (#247)
    //
    // A native MEDIA_ERROR is DELIVERED through OnErrorListener; nothing is thrown, so the gate used to keep a voice it believed was
    // sounding. It now ends exactly as a throwing call does: the voice is dropped and the player released, once.

    /** A MediaPlayer that counts its releases and notes the thread of every call the intro makes on it. */
    private class RecordingPlayer : MediaPlayer() {
        var releases = 0
        val calls = CopyOnWriteArrayList<Pair<String, Thread>>()

        /** A native error delivered from inside prepareAsync (before the wait for the prepare has even begun). */
        var errorInsidePrepare: Pair<Int, Int>? = null

        /** A native error delivered by the main looper while the wait for the prepare is suspended. */
        var errorLater: Pair<Int, Int>? = null

        /** prepareAsync is accepted and nothing ever comes back. */
        var neverPrepares = false

        private fun note(call: String) {
            calls += call to Thread.currentThread()
        }

        fun called(call: String) = calls.any { it.first == call }

        override fun setAudioAttributes(attributes: AudioAttributes) {
            note("setAudioAttributes")
            super.setAudioAttributes(attributes)
        }

        override fun setDataSource(context: Context, uri: Uri) {
            note("setDataSource")
            super.setDataSource(context, uri)
        }

        override fun setLooping(looping: Boolean) {
            note("setLooping")
            super.setLooping(looping)
        }

        override fun prepareAsync() {
            note("prepareAsync")
            if (!neverPrepares) super.prepareAsync()
            errorInsidePrepare?.let { (what, extra) -> shadowOf(this).invokeErrorListener(what, extra) }
            errorLater?.let { (what, extra) -> Handler(Looper.getMainLooper()).post { shadowOf(this).invokeErrorListener(what, extra) } }
        }

        override fun start() {
            note("start")
            super.start()
        }

        override fun pause() {
            note("pause")
            super.pause()
        }

        override fun setVolume(leftVolume: Float, rightVolume: Float) {
            note("setVolume")
            super.setVolume(leftVolume, rightVolume)
        }

        override fun release() {
            releases++
            note("release")
            super.release()
        }
    }

    private val soundUri = "file:///media_art/card-game-audio.m4a"

    private fun soundPlan() = GameOpeningIntroPlan(
        gameIdValue = gameId,
        title = "Card Game",
        heroUri = "file:///hero.png",
        logoUri = null,
        audioUri = soundUri,
    )

    /** Registers [soundUri] with the shadow player, which refuses a data source it was never told about. */
    private fun registerSound() {
        ShadowMediaPlayer.addMediaInfo(
            DataSource.toDataSource(RuntimeEnvironment.getApplication(), Uri.parse(soundUri)),
            ShadowMediaPlayer.MediaInfo(3_000, 0),
        )
    }

    /** Lets the main looper run until [done]: the work a worker thread awaits is posted to it. The verdict is the condition's, not a clock's. */
    private fun idleUntil(what: String, done: () -> Boolean) {
        var spins = 0
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
            assertTrue("never reached: $what", ++spins < 5_000_000)
        }
    }

    private fun <T> settle(job: Deferred<T>): Result<T> {
        idleUntil("the work to settle") { job.isCompleted }
        return runBlocking { runCatching { job.await() } }
    }

    private fun <T> fromWorker(block: suspend () -> T): Deferred<T> =
        CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }.async { block() }

    @Test
    fun `a native error with no throw releases the player once and the gate stops waiting on the sound`() {
        val voice = FakeVoice()
        val player = RecordingPlayer()
        openCard(voice, player)
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))

        shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -38)

        assertEquals("released once", 1, player.releases)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertFalse("and no longer believed to be sounding", GameOpeningIntroState.cardAudioSounding(gameId))
        assertEquals(listOf("opening audio failed reason=media-error what=1 extra=-38"), lines("opening audio failed"))
        assertEquals("nothing is left to carry a level to the cover", 1f, GameOpeningIntroState.audioTargetFor(gameId), 0f)

        // The gate is open: the dropped voice is told nothing more, whatever the inputs do.
        musicPause.value = MusicPauseReason.SCREEN_OFF
        musicPause.value = null
        parked.value = true
        parked.value = false
        assertEquals(1, voice.starts)
        assertEquals(0, voice.pauses)
        assertEquals(0, lines("opening pause").size)
        assertEquals(0, lines("opening resume").size)
    }

    @Test
    fun `a release after an error is idempotent, whoever asks for it and however often the error is reported`() {
        val voice = FakeVoice()
        val player = RecordingPlayer()
        openCard(voice, player)

        shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004)
        assertEquals(1, player.releases)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)

        // The error is reported again (the shadow puts the dead player back in ERROR, as the platform may report late), and every
        // other way to end the sound is asked for.
        shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004)
        GameOpeningIntroState.hide()
        GameOpeningIntroState.stopCardAudio("window-stopped")
        GameOpeningIntroState.hideIfCurrent(gameId)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("released once, not once per asker", 1, player.releases)
        assertEquals("one failure, one line", 1, lines("opening audio failed").size)
        assertEquals("a card with no sound left has nothing to stop", 0, lines("opening stop").size)

        // A later card is a new sound: the old error cannot touch it.
        val next = RecordingPlayer()
        val nextVoice = FakeVoice()
        openCard(nextVoice, next)
        shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1004)
        assertEquals(0, next.releases)
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))
    }

    @Test
    fun `an error from a player the card no longer holds changes nothing and releases nothing`() {
        val first = RecordingPlayer()
        openCard(FakeVoice(), first)
        val second = RecordingPlayer()
        val secondVoice = FakeVoice()
        openCard(secondVoice, second)
        assertEquals("the replacement released the first player, once", 1, first.releases)

        shadowOf(first).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -38)

        assertEquals(1, first.releases)
        assertEquals("the second is not the one that ended", 0, second.releases)
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))
        assertEquals(0, lines("opening audio failed").size)

        // The launch cover takes the player: its own errors are its to handle, the intro releases nothing it no longer owns.
        val taken = GameOpeningIntroState.detachPlayer(gameId)
        assertSame(second, taken)
        shadowOf(second).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -38)
        assertEquals(0, second.releases)
        assertEquals(0, lines("opening audio failed").size)
        second.release()
    }

    @Test
    fun `a looping sound that runs out is the same ending as an error`() {
        val voice = FakeVoice()
        val player = RecordingPlayer()
        openCard(voice, player)

        shadowOf(player).invokeCompletionListener()

        assertEquals(1, player.releases)
        assertFalse(GameOpeningIntroState.cardAudioSounding(gameId))
        assertEquals(listOf("opening audio failed reason=completed"), lines("opening audio failed"))
    }

    @Test
    fun `the launch's one-shot sound that errors or finishes is released once too, though no gate holds it`() {
        for (name in listOf("error", "completion")) {
            ShadowLog.clear()
            GameOpeningIntroState.hide()
            GameOpeningIntroState.show(plan())
            val player = RecordingPlayer()
            assertTrue(GameOpeningIntroState.attachPlayer(gameId, player, targetVolume = 0.4f))
            assertEquals(0.4f, GameOpeningIntroState.audioTargetFor(gameId), 0f)

            if (name == "error") shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -38) else shadowOf(player).invokeCompletionListener()
            assertEquals("$name: released once, by the ending itself", 1, player.releases)
            assertEquals("$name: nothing left to carry to the cover", 1f, GameOpeningIntroState.audioTargetFor(gameId), 0f)
            GameOpeningIntroState.hide()

            assertEquals("$name: and the card closing releases nothing again", 1, player.releases)
            assertEquals("$name: the gate was never involved", 0, lines("opening audio failed").size)
            assertEquals("$name: and the ending is logged", 1, lines("opening audio ended").size)
        }
    }

    // ---- an error never holds a launch, and no call is made off the player's thread

    @Test
    fun `a native error while the sound prepares ends the wait with the player released once`() {
        registerSound()
        for (inside in listOf(true, false)) {
            val players = mutableListOf<RecordingPlayer>()
            val result = settle(
                fromWorker {
                    prepareOpeningPlayer(RuntimeEnvironment.getApplication(), soundUri, looping = true) {
                        RecordingPlayer().also {
                            if (inside) it.errorInsidePrepare = 1 to -1004 else it.errorLater = 1 to -1004
                            it.neverPrepares = !inside
                            players += it
                        }
                    }
                },
            )
            shadowOf(Looper.getMainLooper()).idle()

            val failure = result.exceptionOrNull()
            assertTrue("inside=$inside: the wait ended with the native error, not a hang: $failure", failure is OpeningAudioNativeError)
            assertEquals(1, (failure as OpeningAudioNativeError).what)
            assertEquals(-1004, failure.extra)
            assertEquals("inside=$inside: released once", 1, players.single().releases)
        }
    }

    @Test
    fun `a source that cannot be opened ends the wait with the player released once`() {
        // Nothing registered: the shadow refuses the data source, as a player refuses a file it cannot open.
        val players = mutableListOf<RecordingPlayer>()
        val result = settle(
            fromWorker {
                prepareOpeningPlayer(RuntimeEnvironment.getApplication(), "file:///media_art/missing.m4a", looping = false) {
                    RecordingPlayer().also { players += it }
                }
            },
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(result.isFailure)
        assertEquals(1, players.single().releases)
    }

    @Test
    fun `a caller cancelled while the sound prepares releases the player once`() {
        registerSound()
        val players = mutableListOf<RecordingPlayer>()
        val job = fromWorker {
            prepareOpeningPlayer(RuntimeEnvironment.getApplication(), soundUri, looping = true) {
                RecordingPlayer().also {
                    it.neverPrepares = true
                    players += it
                }
            }
        }
        idleUntil("the prepare to be asked for") { players.any { it.called("prepareAsync") } }

        job.cancel()
        val result = settle(job)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(1, players.single().releases)
    }

    @Test
    fun `no call on the player is made off the main looper, from the prepare to the release, whoever asks`() {
        val mainThread = Thread.currentThread()
        registerSound()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        GameOpeningIntroState.openingAudioSources = { OpeningAudioSources(musicPause, scope) }
        val players = mutableListOf<RecordingPlayer>()
        GameOpeningIntroState.openingPlayerFactory = { RecordingPlayer().also { players += it } }

        // The card is opened from a worker thread, as a caller that is not on Main would.
        val opened = fromWorker { presentGameOpening(RuntimeEnvironment.getApplication(), soundPlan()) }
        // The caller's job also owns the ramp (a child that outlives presentGameOpening), so the card being attached and sounding is the
        // signal, not the job completing. The scope is cancelled with the test.
        idleUntil("the sound to be attached and started by the gate") { GameOpeningIntroState.cardAudioSounding(gameId) }
        assertFalse("presenting the card did not fail: ${opened.isCancelled}", opened.isCancelled)
        val player = players.single()
        assertTrue("the prepared sound was attached and started by the gate", GameOpeningIntroState.cardAudioSounding(gameId))

        // The policy and the window change on other threads; the card is closed on another thread.
        // Each change is let land before the next (a StateFlow conflates), so every pause and every resume is really made.
        Thread { musicPause.value = MusicPauseReason.SCREEN_OFF }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(GameOpeningIntroState.cardAudioSounding(gameId))
        Thread { musicPause.value = null }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))
        Thread { parked.value = true }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(GameOpeningIntroState.cardAudioSounding(gameId))
        Thread { parked.value = false }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))
        Thread { GameOpeningIntroState.hide() }.apply { start(); join() }
        shadowOf(Looper.getMainLooper()).idle()

        val names = player.calls.map { it.first }
        for (call in listOf("setAudioAttributes", "setDataSource", "setLooping", "prepareAsync", "setVolume", "start", "pause", "release")) {
            assertTrue("$call was called: $names", call in names)
        }
        assertEquals("released once", 1, player.releases)
        assertEquals(ShadowMediaPlayer.State.END, shadowOf(player).state)
        assertTrue(
            "every call on the player was made on the main thread: ${player.calls.filter { it.second !== mainThread }}",
            player.calls.all { it.second === mainThread },
        )
    }

    @Test
    fun `an error delivered after the sound is attached ends it whole, through the production start`() {
        registerSound()
        val players = mutableListOf<RecordingPlayer>()
        GameOpeningIntroState.openingPlayerFactory = { RecordingPlayer().also { players += it } }
        val opened = fromWorker { presentGameOpening(RuntimeEnvironment.getApplication(), soundPlan()) }
        idleUntil("the sound to be attached and started by the gate") { GameOpeningIntroState.cardAudioSounding(gameId) }
        val player = players.single()
        assertTrue(GameOpeningIntroState.cardAudioSounding(gameId))

        shadowOf(player).invokeErrorListener(MediaPlayer.MEDIA_ERROR_SERVER_DIED, 0)

        assertEquals(1, player.releases)
        assertFalse(GameOpeningIntroState.cardAudioSounding(gameId))
        assertEquals(listOf("opening audio failed reason=media-error what=100 extra=0"), lines("opening audio failed"))
        GameOpeningIntroState.hide()
        assertEquals("closing the card afterwards releases nothing again", 1, player.releases)
    }

    // ---- where the decision is wired

    private fun source(name: String): String = File(
        listOf(
            "app/src/main/kotlin/com/gruning/mimir/rewrite/$name",
            "src/main/kotlin/com/gruning/mimir/rewrite/$name",
        ).first { File(it).isFile },
    ).readText()

    @Test
    fun `the card's looping sound is started by the gate and never before the attach, and the one-shot intro is not gated`() {
        val intro = source("GameOpeningIntro.kt")
        val start = intro.substringAfter("private suspend fun startGameOpeningIntroAudio(")
        val flat = start.replace(Regex("\\s+"), " ")
        assertTrue("a looping player is never started ahead of the gate", flat.contains("if (!looping) { mediaPlayer.start()"))
        assertTrue(
            "the player is built and prepared, and every call after the prepare is made, on the main looper",
            flat.contains(
                "withContext(Dispatchers.Main.immediate) { val mediaPlayer = try { " +
                    "prepareOpeningPlayer(context, audioUri, looping, GameOpeningIntroState.openingPlayerFactory)",
            ),
        )
        assertTrue("then the volume, in the same main-looper block", flat.contains("runCatching { mediaPlayer.setVolume(0f, 0f)"))
        val code = start.substringBefore("@Composable")
        assertFalse("no worker thread touches the player", code.contains("Dispatchers.IO"))
        assertFalse("and nothing prepares it by blocking", Regex("""\.prepare\(\)""").containsMatchIn(code))
        assertTrue("and the ramp runs there too", flat.contains("CoroutineScope(currentCoroutineContext() + Dispatchers.Main.immediate)"))
        assertEquals("one raw start of a player in the intro file's audio start", 1, Regex("""mediaPlayer\.start\(\)""").findAll(start).count())
        assertTrue("only the card's looping sound is handed to the gate", start.contains("val presentation = if (looping) MediaPlayerOpeningVoice(mediaPlayer) else null"))
        val prepare = source("GameOpeningAudioGate.kt").substringAfter("internal suspend fun prepareOpeningPlayer(").substringBefore("/** Releases [player] on the main looper")
        assertTrue("the looping sound is native and gapless, held by pause", prepare.contains("player.isLooping = looping"))
        assertTrue("prepared asynchronously, on the main looper", prepare.contains("withContext(Dispatchers.Main.immediate)") && prepare.contains("player.prepareAsync()"))
        assertFalse("never prepared by blocking", Regex("""\.prepare\(\)""").containsMatchIn(prepare))
        assertFalse("and never on a worker thread", prepare.contains("Dispatchers.IO"))
    }

    @Test
    fun `the Presentation registers its window for the gate and ends the card's sound with the window`() {
        val presentation = source("MimirCompanionPresentation.kt")
        assertTrue(presentation.contains("GameOpeningIntroState.bottomWindow.value = windowLive"))
        val stop = presentation.substringAfter("override fun onStop() {").substringBefore("host.destroy()")
        assertTrue(stop.contains("GameOpeningIntroState.stopCardAudio(\"window-stopped\")"))
        assertTrue(
            "stopped first, then the window is withdrawn (withdrawing first would read as a live window and resume it)",
            stop.indexOf("stopCardAudio(") < stop.indexOf("bottomWindow.value = null"),
        )
    }

    @Test
    fun `a launch that plays no intro of its own ends a card's sound left from an open card`() {
        val intro = source("GameOpeningIntro.kt")
        val begin = intro.substringAfter("internal suspend fun beginGameOpeningIntro(").substringBefore("playGameOpeningIntro(context, plan)")
        assertTrue(begin.contains("GameOpeningIntroState.stopCardAudio(\"game-launch\")"))
    }

    @Test
    fun `the watcher applies nothing it was handed, it re-reads both inputs under the lock for its own stamp`() {
        val intro = source("GameOpeningIntro.kt")
        val watch = intro.substringAfter("private fun startAudioGateWatch(").substringBefore("/** The stamp of the attach")
        assertTrue(watch.contains("sources.musicPause.collect { reevaluateAudioGate(generation) }"))
        assertTrue(watch.contains(".collect { reevaluateAudioGate(generation) }"))
        assertTrue(watch.contains("if (generation != audioGateGeneration) return"))
        assertTrue(
            "both inputs are read there, now",
            watch.contains("audioGate.refresh(sources.musicPause.value, windowLiveNow())"),
        )
        assertFalse(intro.contains("audioGate.onMusicPause("))
        assertFalse(intro.contains("audioGate.onWindowLive("))
    }

    @Test
    fun `the gate reads the music's own pause answer and keeps no second screen or headphone receiver`() {
        val gate = source("GameOpeningAudioGate.kt")
        assertTrue(gate.contains("MimirMusicControl.pauseReason"))
        assertFalse(gate.contains("ACTION_SCREEN_OFF"))
        assertFalse(gate.contains("ACTION_AUDIO_BECOMING_NOISY"))
        assertFalse(gate.contains("registerReceiver"))
    }
}
