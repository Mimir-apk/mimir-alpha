package com.gruning.mimir.rewrite

import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The controller around the pure model: what a press does, what the sliders store, and when the cap line is up. */
class MusicPanelControllerTest {
    private class FakeEngine : MusicPanelEngine {
        override val state = MutableStateFlow(
            MusicControlState(
                yours = YourMusicState(title = "Song", isPlaying = true, canPrevious = true, canNext = true),
                otherAccess = true,
            ),
        )
        override val musicVolume = MutableStateFlow(0.4f)
        override val mediaVolume = MutableStateFlow(0.5f)
        override val cappedByMedia = MutableStateFlow(false)
        override val equalizer = MutableStateFlow(EqualizerSettings())
        override val systemEqualizer = MutableStateFlow(SystemEqualizerState())
        var opens = 0
        var openWorks = true
        val calls = mutableListOf<String>()

        override fun applyEqualizerPreset(id: EqualizerPresetId) {
            calls += "preset:$id"
            equalizer.value = equalizer.value.withPreset(id)
        }

        override fun openOtherPlayer(view: View?, bounds: Rect?): Boolean {
            opens += 1
            calls += "open"
            return openWorks
        }

        override fun playPause(source: MusicSource) { calls += "playPause:$source" }
        override fun next(source: MusicSource) { calls += "next:$source" }
        override fun previous(source: MusicSource) { calls += "previous:$source" }
        override fun setMusicVolume(fraction: Float) {
            calls += "music=$fraction"
            musicVolume.value = fraction
            cappedByMedia.value = fraction > mediaVolume.value
        }
        override fun setMediaVolume(fraction: Float) {
            calls += "media=$fraction"
            mediaVolume.value = fraction
            cappedByMedia.value = musicVolume.value > fraction
        }
    }

    private class FakeMedia(var fixed: Boolean = false, var available: Boolean = true) : MediaVolumeGateway {
        val steps = mutableListOf<Int>()
        override fun isAvailable() = available
        override fun isFixedVolume() = fixed
        override fun readFraction(): Float? = null
        override fun writeFraction(fraction: Float) = false
        override fun step(direction: Int): Float? {
            steps += direction
            return null
        }
    }

    private val engine = FakeEngine()
    private val media = FakeMedia()
    private val authority = DefaultFocusAuthority()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var closed = 0
    private lateinit var controller: MusicPanelController

    private val yours = YourMusicState(title = "Song", isPlaying = true, canPrevious = true, canNext = true)
    private val spotify = OtherPlayerSnapshot(
        title = "Track",
        artist = "Artist",
        appLabel = "Spotify",
        artwork = null,
        isPlaying = true,
        canPrevious = true,
        canNext = true,
    )

    @Before
    fun setUp() {
        controller = MusicPanelController(engine, authority, scope, media, anchor = null, onClose = { closed++ })
        registerAll(authority)
    }

    /** Lay the controls out in enum order, so the authority can move between them. */
    private fun registerAll(target: DefaultFocusAuthority) {
        var top = 0f
        for (control in MusicPanelControl.entries) {
            target.register(musicPanelFocusKey(control), MusicPanelScope, FocusBounds(0f, top, 100f, top + 10f))
            top += 20f
        }
    }

    @After
    fun tearDown() {
        controller.dispose()
        scope.cancel()
    }

    private fun focused() = musicPanelControlFor(authority.state.value.focused)

    private fun press(action: ControllerAction) = controller.dispatch(action)

    private fun pressA() {
        controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = true, repeatCount = 0) { press(ControllerAction.CONFIRM) }
        controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = false, repeatCount = 0) { true }
    }

    @Test
    fun `the ring opens on Play-Pause`() {
        assertEquals(MusicPanelControl.YOURS_PLAY_PAUSE, focused())
    }

    @Test
    fun `A on a button acts on its release`() {
        controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = true, repeatCount = 0) { press(ControllerAction.CONFIRM) }
        assertEquals(emptyList<String>(), engine.calls)
        controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = false, repeatCount = 0) { true }
        assertEquals(listOf("playPause:YOUR_MUSIC"), engine.calls)
    }

    @Test
    fun `A on Next skips Your music, and on a slider does nothing`() {
        press(ControllerAction.NAV_RIGHT)
        pressA()
        assertEquals(listOf("next:YOUR_MUSIC"), engine.calls)
        engine.calls.clear()
        press(ControllerAction.NAV_DOWN)
        assertEquals(MusicPanelControl.MEDIA_VOLUME, focused())
        pressA()
        assertEquals(emptyList<String>(), engine.calls)
    }

    @Test
    fun `B closes on its release`() {
        controller.onKey(KeyEvent.KEYCODE_BUTTON_B, down = true, repeatCount = 0) { press(ControllerAction.CANCEL) }
        assertEquals(0, closed)
        controller.onKey(KeyEvent.KEYCODE_BUTTON_B, down = false, repeatCount = 0) { true }
        assertEquals(1, closed)
    }

    @Test
    fun `the ring walks the transport row, then down through the sliders, and does not wrap`() {
        press(ControllerAction.NAV_RIGHT)
        assertEquals(MusicPanelControl.YOURS_NEXT, focused())
        press(ControllerAction.NAV_RIGHT)
        assertEquals("no wrap", MusicPanelControl.YOURS_NEXT, focused())
        press(ControllerAction.NAV_DOWN)
        assertEquals(MusicPanelControl.MEDIA_VOLUME, focused())
        press(ControllerAction.NAV_DOWN)
        assertEquals(MusicPanelControl.MUSIC_VOLUME, focused())
        press(ControllerAction.NAV_DOWN)
        assertEquals("no wrap", MusicPanelControl.MUSIC_VOLUME, focused())
    }

    @Test
    fun `left and right on Game and media take one system step each and do not move the ring`() {
        press(ControllerAction.NAV_DOWN)
        press(ControllerAction.NAV_RIGHT)
        press(ControllerAction.NAV_LEFT)
        assertEquals(listOf(1, -1), media.steps)
        assertEquals(MusicPanelControl.MEDIA_VOLUME, focused())
    }

    @Test
    fun `left and right on Music adjust it by five percent`() {
        press(ControllerAction.NAV_DOWN)
        press(ControllerAction.NAV_DOWN)
        press(ControllerAction.NAV_RIGHT)
        assertEquals(0.45f, engine.musicVolume.value, 0.001f)
        press(ControllerAction.NAV_LEFT)
        press(ControllerAction.NAV_LEFT)
        assertEquals(0.35f, engine.musicVolume.value, 0.001f)
        assertEquals(MusicPanelControl.MUSIC_VOLUME, focused())
    }

    @Test
    fun `a fixed Game and media takes no ring and no step`() {
        val fixedMedia = FakeMedia(fixed = true)
        val own = DefaultFocusAuthority()
        val fixed = MusicPanelController(engine, own, scope, fixedMedia, anchor = null, onClose = {})
        assertTrue(fixed.model.mediaFixed)
        assertEquals("FIXED", fixed.model.mediaReadout)
        fixed.onMediaRequested(0.9f)
        assertEquals("a fixed stream is never written", emptyList<String>(), engine.calls)
        fixed.dispose()
    }

    // ---- one card and a switch row ----

    /** A second controller over its own engine and authority, started with both players there. */
    private inner class Two(initial: MusicControlState, equalizerOn: Boolean = false) {
        val engine = FakeEngine().also {
            it.state.value = initial
            it.equalizer.value = EqualizerSettings(enabled = equalizerOn)
        }
        val authority = DefaultFocusAuthority()
        var closes = 0
        var failures = 0
        /** The controller's uptime, held still unless a test moves it. */
        var clock = 1_000L
        val controller = MusicPanelController(
            engine, authority, scope, FakeMedia(), anchor = null,
            onClose = { closes++ },
            onOpenFailed = { failures++ },
            now = { clock },
            swapSettleMs = 310L,
        )

        init {
            registerAll(authority)
        }

        fun focused() = musicPanelControlFor(authority.state.value.focused)
        fun press(action: ControllerAction) = controller.dispatch(action)
        fun pressA() {
            controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = true, repeatCount = 0) { press(ControllerAction.CONFIRM) }
            controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = false, repeatCount = 0) { true }
        }
    }

    /** Both players, as one-player-sounds leaves them: the other is playing and Your music is paused for it, so the other is the card. */
    private fun both(
        yours: YourMusicState = this.yours.copy(isPlaying = false, pausedReason = MusicPauseReason.OTHER_APP),
        other: OtherPlayerSnapshot = spotify,
    ) = MusicControlState(yours = yours, other = other, otherAccess = true)

    @Test
    fun `the ring opens on the card's Play-Pause, and A drives that card's player`() {
        val two = Two(both())
        try {
            assertEquals(listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
            assertEquals(MusicPanelCard.YOURS, two.controller.model.switchRow!!.card)
            assertEquals(MusicPanelControl.OTHER_PLAY_PAUSE, two.focused())
            two.pressA()
            assertEquals(listOf("playPause:OTHER_PLAYER"), two.engine.calls)
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `A on the switch row plays the other source through the engine, and the card follows the press`() {
        val two = Two(both())
        try {
            two.press(ControllerAction.NAV_DOWN)
            assertEquals(MusicPanelControl.SWITCH, two.focused())
            two.pressA()
            assertEquals("the engine is told to play the OTHER source, once", listOf("playPause:YOUR_MUSIC"), two.engine.calls)
            // The card follows the press at once, before the engine's state has caught up; the row now offers the other player.
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            assertEquals(MusicPanelCard.OTHER, two.controller.model.switchRow!!.card)
            assertEquals("the ring stays on the row, so A again switches back", MusicPanelControl.SWITCH, two.focused())
            // The engine catches up (Your music plays, the other is paused), and the card stays where the press put it.
            two.engine.state.value = both(yours = yours, other = spotify.copy(isPlaying = false))
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            // A press inside the swap (the card is still changing) is not another switch: no double switch from one gesture.
            two.clock += 200L
            two.pressA()
            two.controller.onSwitchSource()
            assertEquals(listOf("playPause:YOUR_MUSIC"), two.engine.calls)
            assertEquals("the card did not flip back", listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            // Once the swap has settled the row switches back, and lands on the source it names.
            two.clock += 200L
            two.pressA()
            assertEquals(listOf("playPause:YOUR_MUSIC", "playPause:OTHER_PLAYER"), two.engine.calls)
            assertEquals(listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `under reduced motion there is no swap to wait for, and a second press switches back at once`() {
        val two = Two(both())
        val instant = MusicPanelController(
            two.engine, two.authority, scope, FakeMedia(), anchor = null, onClose = {}, now = { two.clock }, swapSettleMs = 0L,
        )
        try {
            instant.onSwitchSource()
            // The engine catches up: Your music plays, the other is paused.
            two.engine.state.value = both(yours = yours, other = spotify.copy(isPlaying = false))
            instant.onSwitchSource()
            assertEquals(listOf("playPause:YOUR_MUSIC", "playPause:OTHER_PLAYER"), two.engine.calls)
        } finally {
            instant.dispose()
            two.controller.dispose()
        }
    }

    @Test
    fun `a tap on the switch row does what A does, and a source that already plays is only brought forward`() {
        val tap = Two(both())
        val playing = Two(both(yours = yours).copy(lastPlayed = MusicSource.OTHER_PLAYER))
        try {
            tap.controller.onSwitchSource()
            assertEquals(listOf("playPause:YOUR_MUSIC"), tap.engine.calls)
            // Both playing (a moment around a switch): pressing the row must not pause the source it names.
            assertEquals(listOf(MusicPanelCard.OTHER), playing.controller.model.cards.map { it.card })
            playing.controller.onSwitchSource()
            assertEquals(emptyList<String>(), playing.engine.calls)
            assertEquals(listOf(MusicPanelCard.YOURS), playing.controller.model.cards.map { it.card })
        } finally {
            tap.controller.dispose()
            playing.controller.dispose()
        }
    }

    @Test
    fun `with one source there is no row to press`() {
        val one = Two(MusicControlState(yours = yours, otherAccess = true))
        try {
            assertNull(one.controller.model.switchRow)
            one.controller.onSwitchSource()
            assertEquals(emptyList<String>(), one.engine.calls)
        } finally {
            one.controller.dispose()
        }
    }

    @Test
    fun `the ring walks the card, the switch row, then the sliders, and does not wrap`() {
        val two = Two(both())
        try {
            two.press(ControllerAction.NAV_DOWN)
            assertEquals(MusicPanelControl.SWITCH, two.focused())
            two.press(ControllerAction.NAV_DOWN)
            assertEquals(MusicPanelControl.MEDIA_VOLUME, two.focused())
            two.press(ControllerAction.NAV_DOWN)
            assertEquals(MusicPanelControl.MUSIC_VOLUME, two.focused())
            two.press(ControllerAction.NAV_DOWN)
            assertEquals("no wrap", MusicPanelControl.MUSIC_VOLUME, two.focused())
            repeat(3) { two.press(ControllerAction.NAV_UP) }
            assertEquals(MusicPanelControl.OTHER_PLAY_PAUSE, two.focused())
            // The other player's art is the first control of its card, above its transport.
            two.press(ControllerAction.NAV_UP)
            assertEquals(MusicPanelControl.OTHER_ART, two.focused())
            two.press(ControllerAction.NAV_UP)
            assertEquals("no wrap", MusicPanelControl.OTHER_ART, two.focused())
        } finally {
            two.controller.dispose()
        }
    }

    /** Your music is the card, the other player paused: the state a panel opens on before another player starts. */
    private fun yoursCardState() = MusicControlState(yours = yours, other = spotify.copy(isPlaying = false), otherAccess = true)

    @Test
    fun `a card whose player pauses is not swapped away from while nothing else plays`() {
        val two = Two(both())
        try {
            // The other player is playing, so it is the card; it pauses and Your music is paused too: nothing plays, so nothing follows.
            two.engine.state.value = both(other = spotify.copy(isPlaying = false))
            assertEquals("held: nothing swapped under the ring", listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
            assertEquals("Paused — Track", two.controller.model.top.statusLine)
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `the open panel's card follows the source that starts playing, holding through the tie in the fade window`() {
        // Observed: Your music was playing, YouTube Music started, the loss paused Your music, and the open panel kept Your card.
        val two = Two(yoursCardState())
        try {
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            // YouTube Music starts: for Android's 2 s fade BOTH read as playing. A tie is not a change: the card holds.
            two.engine.state.value = MusicControlState(yours = yours, other = spotify, otherAccess = true)
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            // The focus loss pauses Your music: exactly one source plays, and it is not the card's, so the card follows it.
            two.engine.state.value = both()
            assertEquals(listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
            assertEquals(MusicPanelCard.YOURS, two.controller.model.switchRow!!.card)
            // The follow is a swap like any other: a press on the row inside it is not a second switch.
            two.controller.onSwitchSource()
            assertEquals(emptyList<String>(), two.engine.calls)
            assertEquals("it did not flip twice", listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `a follow that arrives with a finger down waits for the lift`() {
        val two = Two(yoursCardState())
        try {
            two.controller.onTouchActive(true)
            two.engine.state.value = both()
            assertEquals("held under the finger", listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            two.controller.onTouchActive(false)
            assertEquals("applied at the lift", listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `a follow that arrives with a key held waits for its release`() {
        val two = Two(yoursCardState())
        try {
            two.controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = true, repeatCount = 0) { two.press(ControllerAction.CONFIRM) }
            two.engine.state.value = both()
            assertEquals("held under the press", listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            two.controller.onKey(KeyEvent.KEYCODE_BUTTON_A, down = false, repeatCount = 0) { true }
            assertEquals("applied at the release", listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `a follow that arrives inside the swap that is still running waits until it is over`() {
        val two = Two(yoursCardState())
        try {
            two.engine.state.value = both()
            assertEquals(listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
            // Inside that swap (310 ms) Your music resumes alone: the card does not flip back mid-transition.
            two.clock += 100L
            two.engine.state.value = MusicControlState(yours = yours, other = spotify.copy(isPlaying = false, title = "Track 2"), otherAccess = true)
            assertEquals("deferred", listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
            // Once the swap is over the next look at the state applies it.
            two.clock += 400L
            two.engine.state.value = MusicControlState(yours = yours, other = spotify.copy(isPlaying = false, title = "Track 3"), otherAccess = true)
            assertEquals("applied after the swap", listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `the old state does not pull the card back after a press on the row, until the hold is over`() {
        val two = Two(both())
        try {
            two.press(ControllerAction.NAV_DOWN)
            two.pressA()
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            // The engine has not caught up: the other player still reads as the only one playing. The press stands.
            two.clock += 500L
            two.engine.state.value = both(other = spotify.copy(title = "Track 2"))
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            // Long after, if that is still the truth (the press never took), the card follows it.
            two.clock += MusicPanelFollowHoldMs
            two.engine.state.value = both(other = spotify.copy(title = "Track 3"))
            assertEquals(listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `the card is decided again when a source arrives, the playing one`() {
        // Your music alone, then another player's session appears and is playing: it is the card, Your music the row.
        val two = Two(MusicControlState(yours = yours, otherAccess = true))
        try {
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            two.engine.state.value = both()
            assertEquals(listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
            // It leaves: Your music is alone again.
            two.engine.state.value = MusicControlState(yours = yours, otherAccess = true)
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            assertNull(two.controller.model.switchRow)
            // It comes back paused: Your music, which is playing, stays the card.
            two.engine.state.value = both(yours = yours, other = spotify.copy(isPlaying = false))
            assertEquals(listOf(MusicPanelCard.YOURS), two.controller.model.cards.map { it.card })
            assertEquals(MusicPanelCard.OTHER, two.controller.model.switchRow!!.card)
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `a source that is gone is dropped while the other remains, with no Stopped card and no row`() {
        val two = Two(both())
        try {
            two.engine.state.value = MusicControlState(other = spotify, otherAccess = true)
            assertEquals(listOf(MusicPanelCard.OTHER), two.controller.model.cards.map { it.card })
            assertNull(two.controller.model.switchRow)
            assertFalse("Your music is gone from the panel, so is its slider", two.controller.model.showMusicVolume)
            assertTrue(two.controller.model.top.canPlayPause)
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `the foot line follows the grant while the panel is open`() {
        val two = Two(MusicControlState(yours = yours, otherAccess = false))
        try {
            assertEquals(MusicPanelAccessHint, two.controller.model.accessHint)
            two.engine.state.value = MusicControlState(yours = yours, otherAccess = true)
            assertNull(two.controller.model.accessHint)
        } finally {
            two.controller.dispose()
        }
    }

    // ---- the cap ----

    @Test
    fun `Music is held at the Game and media level and the one line shows while it is pushed past`() {
        engine.musicVolume.value = 0.45f
        controller.onMusicRequested(0.45f)
        assertFalse(controller.model.capLineVisible)
        controller.onMusicRequested(0.9f)
        assertEquals("stops at Game & media", 0.5f, engine.musicVolume.value, 0.001f)
        assertTrue(controller.model.capLineVisible)
        assertEquals(0.5f, controller.model.capMarker!!, 0.001f)
        // The finger lifts: the line goes.
        controller.onMusicInteractionEnd()
        assertFalse(controller.model.capLineVisible)
    }

    @Test
    fun `pushing back under the cap clears the line`() {
        controller.onMusicRequested(0.9f)
        assertTrue(controller.model.capLineVisible)
        controller.onMusicRequested(0.3f)
        assertFalse(controller.model.capLineVisible)
        assertEquals(0.3f, engine.musicVolume.value, 0.001f)
    }

    @Test
    fun `a level already above Game and media is not lowered by pushing, so it returns if Game and media comes back up`() {
        engine.musicVolume.value = 0.9f
        engine.cappedByMedia.value = true
        engine.calls.clear()
        controller.onMusicRequested(1f)
        assertEquals("the stored level is left alone", emptyList<String>(), engine.calls)
        assertEquals(0.5f, controller.model.musicFraction, 0f)
        assertTrue(controller.model.capLineVisible)
    }

    @Test
    fun `Game and media at zero never drags Music to zero, which is its off switch`() {
        engine.mediaVolume.value = 0f
        engine.musicVolume.value = 0.4f
        controller.onMusicRequested(0.9f)
        assertEquals("no write", emptyList<String>(), engine.calls)
        assertEquals(0.4f, engine.musicVolume.value, 0f)
    }

    @Test
    fun `a controller push at the stop shows the line and moving the ring clears it`() {
        press(ControllerAction.NAV_DOWN)
        press(ControllerAction.NAV_DOWN)
        engine.musicVolume.value = 0.5f
        press(ControllerAction.NAV_RIGHT)
        assertTrue(controller.model.capLineVisible)
        press(ControllerAction.NAV_UP)
        assertFalse(controller.model.capLineVisible)
    }

    // ---- playback changes never close it ----

    @Test
    fun `when playback ends the panel stays, with the transport disabled`() {
        engine.state.value = MusicControlState()
        assertEquals(0, closed)
        assertEquals("Stopped — Song", controller.model.top.statusLine)
        assertFalse(controller.model.top.canPlayPause)
        assertTrue("Music stays reachable", controller.model.showMusicVolume)
    }

    @Test
    fun `a change of track or source updates the card without closing it`() {
        engine.state.value = MusicControlState(
            other = spotify.copy(title = "Elsewhere", artist = "Someone", isPlaying = false, canPrevious = false, canNext = true),
            otherAccess = true,
        )
        assertEquals(0, closed)
        val card = controller.model.top
        assertEquals("Now playing", card.heading)
        assertEquals("Paused — Elsewhere", card.statusLine)
        assertNull(card.hint)
        assertFalse(controller.model.showMusicVolume)
        assertFalse(card.canPrevious)
        assertTrue(card.canNext)
    }

    // ---- the Equalizer row ----

    private fun yoursOnly() = MusicControlState(yours = yours, otherAccess = true)

    @Test
    fun `the Equalizer row is on the panel when the equalizer was on at open and Your music is there, and not otherwise`() {
        val on = Two(yoursOnly(), equalizerOn = true)
        val off = Two(yoursOnly(), equalizerOn = false)
        val otherOnly = Two(MusicControlState(other = spotify, otherAccess = true), equalizerOn = true)
        try {
            assertTrue(on.controller.model.showEqualizer)
            assertEquals("Flat", on.controller.model.equalizerLabel)
            assertFalse(off.controller.model.showEqualizer)
            assertFalse("another player's audio is not equalized", otherOnly.controller.model.showEqualizer)
            // Presence is decided at open: switching it on or off while the panel is up neither adds nor removes the row.
            off.engine.equalizer.value = EqualizerSettings(enabled = true)
            assertFalse(off.controller.model.showEqualizer)
            on.engine.equalizer.value = EqualizerSettings(enabled = false)
            assertTrue(on.controller.model.showEqualizer)
        } finally {
            on.controller.dispose()
            off.controller.dispose()
            otherOnly.controller.dispose()
        }
    }

    @Test
    fun `the Equalizer row's line follows what the global engine reports, live, and says music only when it is not reaching all sound`() {
        val two = Two(yoursOnly(), equalizerOn = true)
        try {
            two.engine.systemEqualizer.value = SystemEqualizerState(wanted = true, status = SystemEqStatus.ON)
            assertEquals("Shapes all sound on this device.", two.controller.model.equalizerSupporting)
            // Another app takes the system equalizer while the panel is up: the line follows, the row (decided at open) stays.
            two.engine.systemEqualizer.value = SystemEqualizerState(wanted = true, status = SystemEqStatus.NO_CONTROL)
            assertEquals("Music on this device only right now.", two.controller.model.equalizerSupporting)
            assertTrue(two.controller.model.showEqualizer)
            two.engine.systemEqualizer.value = SystemEqualizerState(wanted = true, status = SystemEqStatus.UNAVAILABLE)
            assertEquals("Music on this device only right now.", two.controller.model.equalizerSupporting)
            two.engine.systemEqualizer.value = SystemEqualizerState(wanted = true, status = SystemEqStatus.ON)
            assertEquals("and back, when it has the mix again", "Shapes all sound on this device.", two.controller.model.equalizerSupporting)
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `the Equalizer row is the last stop, after Music, and left and right cycle the preset`() {
        val two = Two(yoursOnly(), equalizerOn = true)
        try {
            repeat(3) { two.press(ControllerAction.NAV_DOWN) }
            assertEquals(MusicPanelControl.EQUALIZER, two.focused())
            two.press(ControllerAction.NAV_DOWN)
            assertEquals("no wrap", MusicPanelControl.EQUALIZER, two.focused())
            two.press(ControllerAction.NAV_RIGHT)
            assertEquals(EqualizerPresetId.BASS_BOOST, two.engine.equalizer.value.presetId)
            assertEquals("Bass boost", two.controller.model.equalizerLabel)
            two.press(ControllerAction.NAV_LEFT)
            two.press(ControllerAction.NAV_LEFT)
            assertEquals("the cycle wraps, Custom last", EqualizerPresetId.CUSTOM, two.engine.equalizer.value.presetId)
            assertEquals("Custom", two.controller.model.equalizerLabel)
            assertTrue(two.engine.equalizer.value.enabled)
            two.press(ControllerAction.NAV_UP)
            assertEquals(MusicPanelControl.MUSIC_VOLUME, two.focused())
        } finally {
            two.controller.dispose()
        }
    }

    // ---- the other player's art ----

    @Test
    fun `the other player's art is the first control of its card, and A on it opens the player and detaches the panel`() {
        val two = Two(both())
        try {
            assertEquals(
                listOf(
                    listOf(MusicPanelControl.OTHER_ART),
                    listOf(
                        MusicPanelControl.OTHER_PREVIOUS,
                        MusicPanelControl.OTHER_PLAY_PAUSE,
                        MusicPanelControl.OTHER_NEXT,
                    ),
                    listOf(MusicPanelControl.SWITCH),
                    listOf(MusicPanelControl.MEDIA_VOLUME),
                    listOf(MusicPanelControl.MUSIC_VOLUME),
                ),
                musicPanelFocusRows(two.controller.model),
            )
            two.press(ControllerAction.NAV_UP)
            assertEquals(MusicPanelControl.OTHER_ART, two.focused())
            two.pressA()
            assertEquals(listOf("open"), two.engine.calls)
            assertEquals("the panel goes with the open", 1, two.closes)
            assertEquals(0, two.failures)
            // DOWN from the art lands on that card's Play/Pause again.
            two.press(ControllerAction.NAV_DOWN)
            assertEquals(MusicPanelControl.OTHER_PLAY_PAUSE, two.focused())
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `a player that will not open leaves the panel up and says so`() {
        val two = Two(both())
        try {
            two.engine.openWorks = false
            two.controller.onOpenOtherPlayer()
            assertEquals(1, two.engine.opens)
            assertEquals(0, two.closes)
            assertEquals(1, two.failures)
        } finally {
            two.controller.dispose()
        }
    }

    @Test
    fun `Your music's art does nothing, and a stopped card cannot be opened`() {
        val own = Two(yoursOnly())
        val stopped = Two(MusicControlState(other = spotify, otherAccess = true))
        try {
            assertFalse(musicPanelFocusRows(own.controller.model).flatten().contains(MusicPanelControl.OTHER_ART))
            own.controller.onOpenOtherPlayer()
            assertEquals(0, own.engine.opens)
            // The other player ends: the panel keeps its last card with the transport off, and the art is no longer a control.
            stopped.engine.state.value = MusicControlState(otherAccess = true)
            assertFalse(stopped.controller.model.card(MusicPanelCard.OTHER)!!.canOpenPlayer)
            assertFalse(musicPanelFocusRows(stopped.controller.model).flatten().contains(MusicPanelControl.OTHER_ART))
            stopped.controller.onOpenOtherPlayer()
            assertEquals(0, stopped.engine.opens)
        } finally {
            own.controller.dispose()
            stopped.controller.dispose()
        }
    }

    /**
     * D24: a panel the Bottom has PARKED (the open player, swiped away from) collects nothing: the engine's five flows are not observed, so a change in
     * them does not rebuild the model, and nothing is subscribed. When the panel comes back it rebuilds from the engine's CURRENT state at once.
     */
    @Test
    fun `a parked panel collects no engine flow and a returning one comes back current`() {
        assertEquals("fixture: live, the model follows the engine", 0.4f, controller.model.musicFraction, 0.001f)
        assertTrue("fixture: the feed is subscribed", engine.musicVolume.subscriptionCount.value > 0)

        controller.setLive(false)
        assertEquals("nothing collects the engine's flows while parked", 0, engine.musicVolume.subscriptionCount.value)
        assertEquals(0, engine.state.subscriptionCount.value)
        assertEquals(0, engine.mediaVolume.subscriptionCount.value)
        assertEquals(0, engine.cappedByMedia.subscriptionCount.value)
        assertEquals(0, engine.equalizer.subscriptionCount.value)
        assertEquals(0, engine.systemEqualizer.subscriptionCount.value)
        engine.musicVolume.value = 0.45f
        assertEquals("a change while parked rebuilds nothing", 0.4f, controller.model.musicFraction, 0.001f)

        controller.setLive(true)
        assertEquals("back, it is current at once", 0.45f, controller.model.musicFraction, 0.001f)
        assertEquals("and subscribed again, once", 1, engine.musicVolume.subscriptionCount.value)
        controller.setLive(true)
        assertEquals("a second resume subscribes nothing more", 1, engine.musicVolume.subscriptionCount.value)
    }
}
