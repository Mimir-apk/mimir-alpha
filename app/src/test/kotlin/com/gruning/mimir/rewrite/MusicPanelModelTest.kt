package com.gruning.mimir.rewrite

import android.view.KeyEvent
import com.gruning.mimir.rewrite.core.audio.EqualizerPresetId
import com.gruning.mimir.rewrite.core.audio.EqualizerSettings
import com.gruning.mimir.rewrite.core.audio.MusicControlState
import com.gruning.mimir.rewrite.core.audio.MusicPauseReason
import com.gruning.mimir.rewrite.core.audio.MusicSource
import com.gruning.mimir.rewrite.core.audio.OtherPlayerSnapshot
import com.gruning.mimir.rewrite.core.audio.SystemEqStatus
import com.gruning.mimir.rewrite.core.audio.SystemEqualizerState
import com.gruning.mimir.rewrite.core.audio.TrackFormat
import com.gruning.mimir.rewrite.core.audio.YourMusicState
import com.gruning.mimir.rewrite.core.input.ControllerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The music panel's pure side: what each player and pause reason puts on its card, which buttons work, how the one full card and the switch row go, which
 * sliders show and where the cap marker and its one line stand; then where the D-pad goes and when A and B act.
 */
class MusicPanelModelTest {
    private fun yours(
        playing: Boolean = true,
        reason: MusicPauseReason? = null,
        tracks: Boolean = true,
        title: String? = "Song",
        needsPlay: Boolean = false,
    ) = YourMusicState(
        title = title,
        isPlaying = playing,
        pausedReason = reason,
        pausedNeedsPlay = needsPlay,
        canPrevious = tracks,
        canNext = tracks,
    )

    private val spotify = OtherPlayerSnapshot(
        title = "Track",
        artist = "Artist",
        appLabel = "Spotify",
        artwork = null,
        isPlaying = true,
        canPrevious = true,
        canNext = true,
    )

    /** Your music alone, as the panel was before it showed both. */
    private fun only(yours: YourMusicState, access: Boolean = true) = MusicControlState(yours = yours, otherAccess = access)

    /** Another player alone. */
    private val otherOnly = MusicControlState(other = spotify, otherAccess = true)

    /** Both players. */
    private fun both(yours: YourMusicState = yours(), other: OtherPlayerSnapshot = spotify, access: Boolean = true) =
        MusicControlState(yours = yours, other = other, otherAccess = access)

    private fun model(
        control: MusicControlState,
        stopped: Boolean = false,
        music: Float = 0.5f,
        media: Float = 0.8f,
        mediaAvailable: Boolean = true,
        fixed: Boolean = false,
        capped: Boolean = false,
        pushed: Boolean = false,
        fullCard: MusicPanelCard = musicPanelFullCard(control),
    ) = musicPanelModel(control, stopped, music, media, mediaAvailable, fixed, capped, pushed, fullCard)

    // ---- source and status ----

    @Test
    fun `Your music says so, plays, and shows its title in the companion's status line`() {
        val m = model(only(yours()))
        assertEquals(1, m.cards.size)
        assertEquals("Music on this device", m.top.heading)
        assertEquals("Playing — Song", m.top.statusLine)
        assertTrue(m.top.playing)
        assertFalse("Your music's tracks carry no artwork or artist", m.top.showArtwork)
        assertNull(m.top.artist)
    }

    @Test
    fun `a paused source says Paused with its title`() {
        val m = model(only(yours(playing = false, reason = MusicPauseReason.USER)))
        assertEquals("Paused — Song", m.top.statusLine)
        assertFalse(m.top.playing)
    }

    @Test
    fun `another player is Now playing with its artist and art, and its status line`() {
        val m = model(otherOnly)
        assertEquals(1, m.cards.size)
        assertEquals("Now playing", m.top.heading)
        assertEquals("Playing — Track", m.top.statusLine)
        assertEquals("Artist", m.top.artist)
        assertTrue(m.top.showArtwork)
        assertEquals("Track", m.top.artworkTitle)
    }

    @Test
    fun `a missing title leaves just the state`() {
        assertEquals("Playing", model(only(yours(title = null))).top.statusLine)
        assertEquals("Playing", model(only(yours(title = "  "))).top.statusLine)
    }

    @Test
    fun `when nothing is left to control the card keeps what it showed and says Stopped`() {
        val m = model(only(yours()), stopped = true)
        assertEquals("Stopped — Song", m.top.statusLine)
        assertFalse(m.top.playing)
        assertFalse(m.top.canPlayPause)
        assertFalse(m.top.canPrevious)
        assertFalse(m.top.canNext)
        assertNull("no hint for a stop", m.top.hint)
        assertTrue("the Music slider stays reachable", m.showMusicVolume)
    }

    @Test
    fun `a stop with both players held shows the full card alone, and no switch row`() {
        val held = both()
        val m = model(held, stopped = true, fullCard = MusicPanelCard.OTHER)
        assertEquals(listOf(MusicPanelCard.OTHER), m.cards.map { it.card })
        assertEquals("Stopped — Track", m.top.statusLine)
        assertNull("nothing is left to switch to", m.switchRow)
        assertFalse("Your music is not on the panel, so no Music slider", m.showMusicVolume)
    }

    @Test
    fun `an empty state still gives Your music's card, stopped, so the panel never has no card`() {
        val m = model(MusicControlState(), stopped = true)
        assertEquals(listOf(MusicPanelCard.YOURS), m.cards.map { it.card })
        assertEquals("Stopped", m.top.statusLine)
    }

    // ---- one full card and a switch row ----

    private fun paused(reason: MusicPauseReason = MusicPauseReason.USER) = yours(false, reason)
    private val spotifyPaused get() = spotify.copy(isPlaying = false)

    @Test
    fun `the full card is the player that is playing, and the other source is the switch row`() {
        // Your music playing, the other paused: Your music is the card, the other player the row.
        val yoursPlaying = model(both(other = spotifyPaused))
        assertEquals(listOf(MusicPanelCard.YOURS), yoursPlaying.cards.map { it.card })
        assertEquals(MusicPanelCard.OTHER, yoursPlaying.switchRow!!.card)
        // Your music paused, the other playing: the other is the card, Your music the row.
        val otherPlaying = model(both(yours = paused(MusicPauseReason.OTHER_APP)))
        assertEquals(listOf(MusicPanelCard.OTHER), otherPlaying.cards.map { it.card })
        assertEquals(MusicPanelCard.YOURS, otherPlaying.switchRow!!.card)
        // A single source has no row, and the panel is as it was.
        assertNull(model(only(yours())).switchRow)
        assertNull(model(otherOnly).switchRow)
        assertEquals(1, model(only(yours())).cards.size)
    }

    @Test
    fun `with neither playing the most recently played is the card, and with both playing too`() {
        val neither = both(yours = paused(), other = spotifyPaused)
        assertEquals(MusicPanelCard.YOURS, model(neither).top.card)
        assertEquals(MusicPanelCard.OTHER, model(neither.copy(lastPlayed = MusicSource.OTHER_PLAYER)).top.card)
        assertEquals(MusicPanelCard.YOURS, model(neither.copy(lastPlayed = MusicSource.YOUR_MUSIC)).top.card)
        // Both playing is the same rule; the one that is playing never loses to the history.
        assertEquals(MusicPanelCard.OTHER, model(both().copy(lastPlayed = MusicSource.OTHER_PLAYER)).top.card)
        assertEquals(MusicPanelCard.YOURS, model(both(other = spotifyPaused).copy(lastPlayed = MusicSource.OTHER_PLAYER)).top.card)
    }

    @Test
    fun `the switch row names the source and its track, and says so when a source names no track`() {
        val toOther = model(both(other = spotifyPaused)).switchRow!!
        assertEquals("Spotify", toOther.name)
        assertEquals("Track", toOther.track)
        assertFalse(toOther.playing)
        val toYours = model(both(yours = paused(MusicPauseReason.OTHER_APP))).switchRow!!
        assertEquals("Music on this device", toYours.name)
        assertEquals("Song", toYours.track)
        // No app name, no title: the fallbacks, never a blank line.
        val bare = model(both(other = spotifyPaused.copy(appLabel = null, title = "  "))).switchRow!!
        assertEquals("Other player", bare.name)
        assertEquals("Paused", bare.track)
    }

    @Test
    fun `the full card keeps its own status line and transport, and only the card has art`() {
        val m = model(both(yours = paused(MusicPauseReason.OTHER_APP), other = spotify.copy(canNext = false)))
        val other = m.top
        assertEquals(MusicPanelCard.OTHER, other.card)
        assertEquals("Playing — Track", other.statusLine)
        assertEquals("Artist", other.artist)
        assertTrue(other.showArtwork)
        assertTrue(other.canOpenPlayer)
        assertFalse("its session offers no Next", other.canNext)
        // Your music as the row has no art and no card, so no art control either.
        assertNull(m.card(MusicPanelCard.YOURS))
        val own = model(both(other = spotifyPaused)).top
        assertEquals("Playing — Song", own.statusLine)
        assertTrue("Your music is its own: Next works", own.canNext)
        assertFalse(own.showArtwork)
    }

    @Test
    fun `a card whose player has left is not held, the one that remains is shown`() {
        // The held card was Your music, but only the other player is there now.
        val m = model(otherOnly, fullCard = MusicPanelCard.YOURS)
        assertEquals(listOf(MusicPanelCard.OTHER), m.cards.map { it.card })
        assertNull(m.switchRow)
    }

    @Test
    fun `the other player's buttons follow its session's actions, Play included`() {
        val limited = model(otherOnly.copy(other = spotify.copy(canPrevious = false, canNext = false, canPlayPause = false)))
        assertFalse(limited.top.canPrevious)
        assertFalse(limited.top.canNext)
        assertFalse(limited.top.canPlayPause)
        // Its transport is all off, so the ring starts on the one thing left to press: the art, which opens the player.
        assertEquals(MusicPanelControl.OTHER_ART, musicPanelInitialControl(limited.copy(showMediaVolume = false, showMusicVolume = false)))
        val gone = limited.copy(cards = listOf(limited.top.copy(canOpenPlayer = false)), showMediaVolume = false, showMusicVolume = false)
        assertEquals("a card with nothing to press has no ring to start on", null, musicPanelInitialControl(gone))
    }

    @Test
    fun `the sliders show once, Game and media always and Music only when Your music is on the panel`() {
        val both = model(both())
        assertTrue(both.showMediaVolume)
        assertTrue(both.showMusicVolume)
        val other = model(otherOnly)
        assertTrue(other.showMediaVolume)
        assertFalse("another player's audio lives inside Game & media", other.showMusicVolume)
        assertTrue(model(only(yours())).showMusicVolume)
    }

    @Test
    fun `the cap is Your music's, so it shows with both and never with the other player alone`() {
        val withBoth = model(both(), music = 0.9f, media = 0.5f, capped = true, pushed = true)
        assertEquals(0.5f, withBoth.capMarker!!, 0f)
        assertTrue(withBoth.capLineVisible)
        val alone = model(otherOnly, music = 0.9f, media = 0.5f, capped = true, pushed = true)
        assertNull(alone.capMarker)
        assertFalse(alone.capLineVisible)
    }

    // ---- the foot line ----

    @Test
    fun `the foot line shows while Your music is on the panel and notification access is not held`() {
        assertEquals("See other players here — allow in Settings › Music", model(only(yours(), access = false)).accessHint)
        assertEquals(MusicPanelAccessHint, model(only(yours(), access = false)).accessHint)
        // Control arms: access held, and the other player alone (it is there, so the grant is).
        assertNull(model(only(yours(), access = true)).accessHint)
        assertNull(model(otherOnly).accessHint)
        assertNull("with both there the grant is held", model(both()).accessHint)
    }

    @Test
    fun `a paused Your music still shows the foot line, and a stopped one too`() {
        assertEquals(MusicPanelAccessHint, model(only(yours(false, MusicPauseReason.USER), access = false)).accessHint)
        assertEquals(MusicPanelAccessHint, model(only(yours(), access = false), stopped = true).accessHint)
    }

    // ---- the pause hints ----

    @Test
    fun `each involuntary pause says why, in the product's words`() {
        assertEquals("Paused for another app. Resumes when it stops.", model(only(yours(false, MusicPauseReason.OTHER_APP))).top.hint)
        assertEquals("Paused when the screen went off.", model(only(yours(false, MusicPauseReason.SCREEN_OFF))).top.hint)
        assertEquals("Paused when headphones were unplugged.", model(only(yours(false, MusicPauseReason.HEADPHONES))).top.hint)
    }

    @Test
    fun `a permanent hold with no notification access says to press play, because nothing else will resume it`() {
        val m = model(only(yours(false, MusicPauseReason.OTHER_APP, needsPlay = true), access = false))
        assertEquals("Paused for another app. Press play to resume.", m.top.hint)
        // Control arm: the same pause with the grant held resumes by itself.
        assertEquals(MusicHintOtherApp, model(only(yours(false, MusicPauseReason.OTHER_APP, needsPlay = false))).top.hint)
    }

    @Test
    fun `a pause by the player and a playing track say nothing, and Play stays enabled either way`() {
        assertNull(model(only(yours(false, MusicPauseReason.USER))).top.hint)
        assertNull(model(only(yours(true))).top.hint)
        for (reason in MusicPauseReason.entries) {
            assertTrue("Play stays enabled while paused for $reason", model(only(yours(false, reason))).top.canPlayPause)
        }
    }

    @Test
    fun `another player's own pause carries no hint`() {
        assertNull(model(otherOnly.copy(other = spotify.copy(isPlaying = false))).top.hint)
    }

    // ---- transport ----

    @Test
    fun `a single track disables Previous and Next, and more than one enables them`() {
        val single = model(only(yours(tracks = false))).top
        assertFalse(single.canPrevious)
        assertFalse(single.canNext)
        assertTrue(single.canPlayPause)
        val many = model(only(yours(tracks = true))).top
        assertTrue(many.canPrevious)
        assertTrue(many.canNext)
    }

    @Test
    fun `another player's skips follow what its session offers`() {
        val m = model(otherOnly.copy(other = spotify.copy(canPrevious = false, canNext = true))).top
        assertFalse(m.canPrevious)
        assertTrue(m.canNext)
    }

    // ---- the sliders ----

    @Test
    fun `Game and media shows a percent, or FIXED when the device fixes it`() {
        assertEquals("80%", model(only(yours())).mediaReadout)
        val fixed = model(only(yours()), fixed = true)
        assertEquals("FIXED", fixed.mediaReadout)
        assertTrue(fixed.showMediaVolume)
        assertTrue(fixed.mediaFixed)
    }

    @Test
    fun `a device without the stream has no Game and media row and no cap`() {
        val m = model(only(yours()), mediaAvailable = false, capped = true, pushed = true)
        assertFalse(m.showMediaVolume)
        assertNull(m.capMarker)
        assertFalse(m.capLineVisible)
        assertFalse(m.capLineSeat)
    }

    @Test
    fun `Music shows for Your music and is hidden for another player`() {
        assertTrue(model(only(yours())).showMusicVolume)
        val m = model(otherOnly, capped = true, pushed = true)
        assertFalse(m.showMusicVolume)
        assertNull("no cap on another player's audio", m.capMarker)
        assertFalse(m.capLineVisible)
    }

    @Test
    fun `an uncapped Music slider shows its own level and no marker or line`() {
        val m = model(only(yours()), music = 0.4f, media = 0.8f)
        assertEquals(0.4f, m.musicFraction, 0f)
        assertEquals("40%", m.musicReadout)
        assertNull(m.capMarker)
        assertFalse(m.capLineVisible)
        assertFalse(m.capLineSeat)
    }

    @Test
    fun `a capped Music slider stops at Game and media with a marker there and no line until pushed`() {
        val m = model(only(yours()), music = 0.9f, media = 0.5f, capped = true)
        assertEquals("the thumb stops at the Game & media level", 0.5f, m.musicFraction, 0f)
        assertEquals("50%", m.musicReadout)
        assertEquals(0.5f, m.capMarker!!, 0f)
        assertFalse("the line appears only while the player pushes past it", m.capLineVisible)
        assertTrue("its seat is held so the line moves nothing", m.capLineSeat)
    }

    @Test
    fun `pushing past the cap shows the one line`() {
        val m = model(only(yours()), music = 0.9f, media = 0.5f, capped = true, pushed = true)
        assertTrue(m.capLineVisible)
        assertEquals("Music can't be louder than Game & media.", MusicCapLine)
    }

    @Test
    fun `a push at the stop keeps the marker and the line even once the stored level equals the cap`() {
        // The panel stores the cap itself when it is pushed, so the engine no longer calls Music capped; the push still shows.
        val m = model(only(yours()), music = 0.5f, media = 0.5f, capped = false, pushed = true)
        assertEquals(0.5f, m.capMarker!!, 0f)
        assertTrue(m.capLineVisible)
    }

    // ---- where the ring goes ----

    private val full = model(only(yours()))
    private val Y_PREV = MusicPanelControl.YOURS_PREVIOUS
    private val Y_PLAY = MusicPanelControl.YOURS_PLAY_PAUSE
    private val Y_NEXT = MusicPanelControl.YOURS_NEXT
    private val O_PREV = MusicPanelControl.OTHER_PREVIOUS
    private val O_PLAY = MusicPanelControl.OTHER_PLAY_PAUSE
    private val O_NEXT = MusicPanelControl.OTHER_NEXT

    @Test
    fun `the ring starts on the top card's Play-Pause`() {
        assertEquals(Y_PLAY, musicPanelInitialControl(full))
        assertEquals("the other player's card on top", O_PLAY, musicPanelInitialControl(model(both(yours = paused(MusicPauseReason.OTHER_APP)))))
        assertEquals("Your music's card on top", Y_PLAY, musicPanelInitialControl(model(both(other = spotify.copy(isPlaying = false)))))
    }

    @Test
    fun `left and right walk a transport row and stop at its ends`() {
        assertEquals(Y_NEXT, musicPanelNeighbor(full, Y_PLAY, ControllerAction.NAV_RIGHT))
        assertEquals(Y_PREV, musicPanelNeighbor(full, Y_PLAY, ControllerAction.NAV_LEFT))
        assertNull("no wrap", musicPanelNeighbor(full, Y_NEXT, ControllerAction.NAV_RIGHT))
        assertNull("no wrap", musicPanelNeighbor(full, Y_PREV, ControllerAction.NAV_LEFT))
    }

    @Test
    fun `down goes to Game and media and then Music, and up goes back, with no wrap`() {
        for (from in listOf(Y_PREV, Y_PLAY, Y_NEXT)) {
            assertEquals(MusicPanelControl.MEDIA_VOLUME, musicPanelNeighbor(full, from, ControllerAction.NAV_DOWN))
        }
        assertEquals(MusicPanelControl.MUSIC_VOLUME, musicPanelNeighbor(full, MusicPanelControl.MEDIA_VOLUME, ControllerAction.NAV_DOWN))
        assertNull(musicPanelNeighbor(full, MusicPanelControl.MUSIC_VOLUME, ControllerAction.NAV_DOWN))
        assertEquals(MusicPanelControl.MEDIA_VOLUME, musicPanelNeighbor(full, MusicPanelControl.MUSIC_VOLUME, ControllerAction.NAV_UP))
        assertEquals(Y_PLAY, musicPanelNeighbor(full, MusicPanelControl.MEDIA_VOLUME, ControllerAction.NAV_UP))
        assertNull(musicPanelNeighbor(full, Y_PLAY, ControllerAction.NAV_UP))
    }

    @Test
    fun `with a switch row the ring walks the card, the row right under it, then the sliders`() {
        val two = model(both(yours = paused(MusicPauseReason.OTHER_APP)))
        assertEquals(
            listOf(
                listOf(MusicPanelControl.OTHER_ART),
                listOf(O_PREV, O_PLAY, O_NEXT),
                listOf(MusicPanelControl.SWITCH),
                listOf(MusicPanelControl.MEDIA_VOLUME),
                listOf(MusicPanelControl.MUSIC_VOLUME),
            ),
            musicPanelFocusRows(two),
        )
        // Down from any card button lands on the switch row, and on from there to the sliders.
        for (from in listOf(O_PREV, O_PLAY, O_NEXT)) {
            assertEquals(MusicPanelControl.SWITCH, musicPanelNeighbor(two, from, ControllerAction.NAV_DOWN))
        }
        assertEquals(MusicPanelControl.MEDIA_VOLUME, musicPanelNeighbor(two, MusicPanelControl.SWITCH, ControllerAction.NAV_DOWN))
        // And back up the same way: UP from the row is the card's Play/Pause.
        assertEquals(O_PLAY, musicPanelNeighbor(two, MusicPanelControl.SWITCH, ControllerAction.NAV_UP))
        // The other player's art sits above its transport: UP reaches it, DOWN from it is Play/Pause, and it is the top.
        assertEquals(MusicPanelControl.OTHER_ART, musicPanelNeighbor(two, O_PLAY, ControllerAction.NAV_UP))
        assertEquals(O_PLAY, musicPanelNeighbor(two, MusicPanelControl.OTHER_ART, ControllerAction.NAV_DOWN))
        assertNull(musicPanelNeighbor(two, MusicPanelControl.OTHER_ART, ControllerAction.NAV_UP))
        // The row takes no sideways move: it is one control.
        assertNull(musicPanelNeighbor(two, MusicPanelControl.SWITCH, ControllerAction.NAV_LEFT))
        assertNull(musicPanelNeighbor(two, MusicPanelControl.SWITCH, ControllerAction.NAV_RIGHT))
        // Without a second source there is no row to reach.
        assertFalse(musicPanelFocusRows(model(only(yours()))).flatten().contains(MusicPanelControl.SWITCH))
        assertFalse(musicPanelFocusRows(model(otherOnly)).flatten().contains(MusicPanelControl.SWITCH))
    }

    @Test
    fun `the focus order follows the card, then the row, Your music's card first when it plays`() {
        val yoursOnTop = model(both(other = spotifyPaused))
        assertEquals(
            listOf(Y_PREV, Y_PLAY, Y_NEXT, MusicPanelControl.SWITCH, MusicPanelControl.MEDIA_VOLUME, MusicPanelControl.MUSIC_VOLUME),
            musicPanelFocusRows(yoursOnTop).flatten(),
        )
        assertEquals("the ring starts on the card's Play/Pause, never the row", Y_PLAY, musicPanelInitialControl(yoursOnTop))
    }

    @Test
    fun `disabled buttons take no ring`() {
        val single = model(only(yours(tracks = false)))
        assertEquals(
            listOf(listOf(Y_PLAY), listOf(MusicPanelControl.MEDIA_VOLUME), listOf(MusicPanelControl.MUSIC_VOLUME)),
            musicPanelFocusRows(single),
        )
        assertNull(musicPanelNeighbor(single, Y_PLAY, ControllerAction.NAV_RIGHT))
    }

    @Test
    fun `a fixed Game and media is skipped, and another player has no Music row`() {
        val fixed = model(only(yours()), fixed = true)
        assertEquals(MusicPanelControl.MUSIC_VOLUME, musicPanelNeighbor(fixed, Y_PLAY, ControllerAction.NAV_DOWN))
        val player = model(otherOnly)
        assertEquals(MusicPanelControl.MEDIA_VOLUME, musicPanelNeighbor(player, O_PLAY, ControllerAction.NAV_DOWN))
        assertNull(musicPanelNeighbor(player, MusicPanelControl.MEDIA_VOLUME, ControllerAction.NAV_DOWN))
    }

    @Test
    fun `with nothing to play the ring starts on the first slider`() {
        val stopped = model(only(yours()), stopped = true)
        assertEquals(MusicPanelControl.MEDIA_VOLUME, musicPanelInitialControl(stopped))
    }

    @Test
    fun `every control has its own key and one card`() {
        assertEquals(MusicPanelControl.entries.size, MusicPanelControl.entries.map(::musicPanelFocusKey).toSet().size)
        for (control in MusicPanelControl.entries) assertEquals(control, musicPanelControlFor(musicPanelFocusKey(control)))
        assertEquals(setOf(MusicPanelCard.YOURS, MusicPanelCard.OTHER), MusicPanelControl.entries.mapNotNull { it.card }.toSet())
        assertNull(MusicPanelControl.MEDIA_VOLUME.card)
        assertNull(MusicPanelControl.MUSIC_VOLUME.card)
    }

    // ---- press and release ----

    private class Recorder {
        val events = mutableListOf<String>()
        val gate = MusicPanelKeyGate(onConfirm = { events += "confirm" }, onCancel = { events += "cancel" })
    }

    private fun Recorder.press(keyCode: Int, action: ControllerAction, route: () -> Boolean = { true }): Boolean =
        gate.onKey(keyCode, down = true, repeatCount = 0) {
            gate.arm(action)
            route()
        }

    private fun Recorder.release(keyCode: Int, route: () -> Boolean = { true }): Boolean =
        gate.onKey(keyCode, down = false, repeatCount = 0, route = route)

    @Test
    fun `A acts on its release, not its press, and both edges are consumed`() {
        val r = Recorder()
        assertTrue(r.press(KeyEvent.KEYCODE_BUTTON_A, ControllerAction.CONFIRM))
        assertEquals("nothing on the press", emptyList<String>(), r.events)
        assertTrue(r.release(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(listOf("confirm"), r.events)
    }

    @Test
    fun `B closes on its release and swallows the press and the release`() {
        val r = Recorder()
        // Even if the router would not consume the edges, the gate does.
        assertTrue(r.press(KeyEvent.KEYCODE_BUTTON_B, ControllerAction.CANCEL, route = { false }))
        assertEquals(emptyList<String>(), r.events)
        assertTrue(r.release(KeyEvent.KEYCODE_BUTTON_B, route = { false }))
        assertEquals(listOf("cancel"), r.events)
    }

    @Test
    fun `the Back key is a B`() {
        val r = Recorder()
        assertTrue(r.press(KeyEvent.KEYCODE_BACK, ControllerAction.SYSTEM_BACK))
        assertEquals(emptyList<String>(), r.events)
        assertTrue(r.release(KeyEvent.KEYCODE_BACK))
        assertEquals(listOf("cancel"), r.events)
    }

    @Test
    fun `the release goes through the router too, so its own pressed-key bookkeeping stays honest`() {
        val r = Recorder()
        r.press(KeyEvent.KEYCODE_BUTTON_A, ControllerAction.CONFIRM)
        var routed = false
        r.release(KeyEvent.KEYCODE_BUTTON_A) { routed = true; true }
        assertTrue(routed)
    }

    @Test
    fun `a release with no armed press does nothing here`() {
        val r = Recorder()
        assertTrue(r.release(KeyEvent.KEYCODE_BUTTON_A, route = { true }))
        assertEquals(emptyList<String>(), r.events)
        assertFalse("an unowned release is the router's answer", r.release(KeyEvent.KEYCODE_BUTTON_X, route = { false }))
    }

    @Test
    fun `a held key's repeats arm nothing and a second press acts once`() {
        val r = Recorder()
        r.press(KeyEvent.KEYCODE_BUTTON_A, ControllerAction.CONFIRM)
        r.gate.onKey(KeyEvent.KEYCODE_BUTTON_A, down = true, repeatCount = 3) {
            r.gate.arm(ControllerAction.CONFIRM)
            true
        }
        r.release(KeyEvent.KEYCODE_BUTTON_A)
        assertEquals(listOf("confirm"), r.events)
    }

    @Test
    fun `an action that did not come from a key acts at once`() {
        val r = Recorder()
        r.gate.arm(ControllerAction.CONFIRM)
        r.gate.arm(ControllerAction.CANCEL)
        assertEquals(listOf("confirm", "cancel"), r.events)
    }

    @Test
    fun `the volume keys are handed back to Android in both edges`() {
        val r = Recorder()
        for (key in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)) {
            var routed = false
            assertFalse(r.gate.onKey(key, down = true, repeatCount = 0) { routed = true; true })
            assertFalse(r.gate.onKey(key, down = false, repeatCount = 0) { routed = true; true })
            assertFalse("the router never sees a volume key", routed)
        }
    }

    @Test
    fun `a direction press is not gated`() {
        val r = Recorder()
        var routed = false
        assertTrue(r.gate.onKey(KeyEvent.KEYCODE_DPAD_RIGHT, down = true, repeatCount = 0) { routed = true; true })
        assertTrue(routed)
        assertEquals(emptyList<String>(), r.events)
    }

    // ---- the track format, the Equalizer row, the other player's art ----

    @Test
    fun `Your music's card shows the track's source format where the artist would be when the tags name none, and says nothing before it is known`() {
        val known = only(yours().copy(format = TrackFormat(codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304)))
        assertEquals("FLAC · 24/96 · 2,304 kbps", model(known).top.artist)
        assertFalse("the format line is chrome, drawn capped", model(known).top.artistIsAuthored)
        assertNull(model(only(yours())).top.artist)
        // The other player's artist stays where it was.
        assertEquals("Artist", model(otherOnly).top.artist)
        assertTrue(model(otherOnly).top.artistIsAuthored)
    }

    private val flac = TrackFormat(codec = "FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304)

    @Test
    fun `Your music's card shows the tag artist in its own case, in place of the format line`() {
        val tagged = only(yours().copy(artist = "Sweep Band", format = flac))
        assertEquals("Sweep Band", model(tagged).top.artist)
        assertTrue("a tag artist is authored, so it is not drawn capped", model(tagged).top.artistIsAuthored)
        // Before the format arrives the artist is still there.
        assertEquals("Sweep Band", model(only(yours().copy(artist = "Sweep Band"))).top.artist)
    }

    @Test
    fun `a blank artist tag is no artist, so the card keeps the format line`() {
        val blank = only(yours().copy(artist = "   ", format = flac))
        assertEquals("FLAC · 24/96 · 2,304 kbps", model(blank).top.artist)
        assertFalse(model(blank).top.artistIsAuthored)
        assertNull(model(only(yours().copy(artist = ""))).top.artist)
    }

    @Test
    fun `a tag arriving after the card is up turns the format line into the artist, and the format line is not lost to the equalizer's header`() {
        val before = only(yours().copy(format = flac))
        val after = only(yours().copy(artist = "Sweep Band", format = flac))
        assertEquals("FLAC · 24/96 · 2,304 kbps", model(before).top.artist)
        assertEquals("Sweep Band", model(after).top.artist)
        // The track's format is still on the state itself, which is what the equalizer editor and Settings' Now playing row read.
        assertEquals("FLAC · 24/96 · 2,304 kbps", after.yours?.format?.sourceLine)
    }

    @Test
    fun `the Equalizer row shows only with Your music on the panel and the equalizer on at open, labelled with the live preset`() {
        fun build(control: MusicControlState, shown: Boolean, preset: EqualizerPresetId = EqualizerPresetId.FLAT) = musicPanelModel(
            control, false, 0.5f, 0.8f, true, false, false, false,
            equalizer = EqualizerSettings(enabled = true).withPreset(preset), equalizerShown = shown,
        )
        val rock = build(only(yours()), shown = true, preset = EqualizerPresetId.ROCK)
        assertTrue(rock.showEqualizer)
        assertEquals("Rock", rock.equalizerLabel)
        assertEquals(listOf(MusicPanelControl.EQUALIZER), musicPanelFocusRows(rock).last())
        assertFalse(build(only(yours()), shown = false).showEqualizer)
        assertFalse("another player's audio is not equalized", build(otherOnly, shown = true).showEqualizer)
        assertTrue(build(both(), shown = true).showEqualizer)
        assertEquals(MusicPanelControl.EQUALIZER, musicPanelNeighbor(rock, MusicPanelControl.MUSIC_VOLUME, ControllerAction.NAV_DOWN))
        assertNull(musicPanelNeighbor(rock, MusicPanelControl.EQUALIZER, ControllerAction.NAV_DOWN))
    }

    @Test
    fun `the Equalizer row claims all sound only while the engine has the mix, and says music only right now when it does not`() {
        fun line(enabled: Boolean, status: SystemEqStatus) = musicPanelModel(
            only(yours()), false, 0.5f, 0.8f, true, false, false, false,
            equalizer = EqualizerSettings(enabled = enabled).withPreset(EqualizerPresetId.ROCK),
            systemEq = SystemEqualizerState(wanted = enabled, status = status), equalizerShown = true,
        ).equalizerSupporting
        assertEquals("Shapes all sound on this device.", line(enabled = true, SystemEqStatus.ON))
        // Another app holds the system equalizer, or Android refused it: the same words as the editor's header and the Settings row.
        assertEquals("Music on this device only right now.", line(enabled = true, SystemEqStatus.NO_CONTROL))
        assertEquals("Music on this device only right now.", line(enabled = true, SystemEqStatus.UNAVAILABLE))
        assertTrue(MusicPanelEqualizerMimirOnlySupporting.contains("music on this device only right now", ignoreCase = true))
        // Not claimed either way before the engine has answered, and never while the switch is off (the row is not offered then).
        assertEquals(MusicPanelEqualizerSupporting, line(enabled = true, SystemEqStatus.OFF))
        assertEquals(MusicPanelEqualizerSupporting, line(enabled = false, SystemEqStatus.NO_CONTROL))
        // An engine that says nothing leaves the default, which is what every other caller of the model gets.
        assertEquals(MusicPanelEqualizerSupporting, model(only(yours())).equalizerSupporting)
    }

    @Test
    fun `the preset cycle walks every preset then Custom and wraps both ways`() {
        assertEquals(10, EqualizerPresetCycle.size)
        assertEquals(EqualizerPresetId.BASS_BOOST, equalizerCyclePreset(EqualizerPresetId.FLAT, 1))
        assertEquals(EqualizerPresetId.CUSTOM, equalizerCyclePreset(EqualizerPresetId.LOUDNESS, 1))
        assertEquals(EqualizerPresetId.FLAT, equalizerCyclePreset(EqualizerPresetId.CUSTOM, 1))
        assertEquals(EqualizerPresetId.CUSTOM, equalizerCyclePreset(EqualizerPresetId.FLAT, -1))
        assertEquals(EqualizerPresetId.LOUDNESS, equalizerCyclePreset(EqualizerPresetId.CUSTOM, -1))
    }

    @Test
    fun `the other player's art is a control only while its card can still be opened, and Your music has none`() {
        val live = model(both(yours = paused(MusicPauseReason.OTHER_APP)))
        assertTrue(live.card(MusicPanelCard.OTHER)!!.canOpenPlayer)
        assertEquals(MusicPanelControl.OTHER_ART, musicPanelFocusRows(live).first().single())
        assertFalse(model(only(yours())).top.canOpenPlayer)
        assertFalse(musicPanelFocusRows(model(only(yours()))).flatten().contains(MusicPanelControl.OTHER_ART))
        val stopped = model(otherOnly, stopped = true)
        assertFalse(stopped.top.canOpenPlayer)
        assertFalse(musicPanelFocusRows(stopped).flatten().contains(MusicPanelControl.OTHER_ART))
        // The art has no transport, so a press-and-release gate still sees it as a card control and not a button.
        assertNull(MusicPanelControl.OTHER_ART.transport)
        assertEquals(MusicPanelCard.OTHER, MusicPanelControl.OTHER_ART.card)
    }
}
