package com.gruning.mimir.rewrite.core.audio

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The facade's state and the M-tap input, as pure functions. */
class MusicControlStateTest {
    private val yours = MusicControlInputs(
        yourMusicActive = true,
        yourMusicSounding = true,
        title = "Windy Hill",
        pause = null,
        musicVolume = 0.5f,
    )
    private val spotify = OtherPlayerSnapshot("Song", "Artist", "Spotify", artwork = null, isPlaying = true)

    @Test
    fun `playing Your music reports its title and no pause`() {
        val card = deriveMusicControlState(yours).yours!!
        assertEquals("Windy Hill", card.title)
        assertTrue(card.isPlaying)
        assertNull(card.pausedReason)
        assertFalse(card.pausedNeedsPlay)
    }

    @Test
    fun `the track's artist tag reaches Your music's card, and a track the library has not read names none`() {
        assertNull("no artist until a tag names one", deriveMusicControlState(yours).yours!!.artist)
        assertEquals("Sweep Band", deriveMusicControlState(yours.copy(artist = "Sweep Band")).yours!!.artist)
    }

    @Test
    fun `Your music's position, length and seek reach its card once the player has read the track`() {
        // Before the player reports anything: no position, no length, nothing to seek.
        val bare = deriveMusicControlState(yours).yours!!
        assertNull(bare.positionMs)
        assertNull(bare.durationMs)
        assertFalse(bare.canSeek)
        val timing = YourMusicTiming(positionMs = 61_000L, durationMs = 240_000L, updatedAtElapsedRealtimeMs = 5_000L, advancing = true)
        val card = deriveMusicControlState(yours.copy(timing = timing)).yours!!
        assertEquals(61_000L, card.positionMs)
        assertEquals(240_000L, card.durationMs)
        assertEquals(5_000L, card.positionUpdatedAtElapsedRealtimeMs)
        assertTrue(card.positionAdvancing)
        assertTrue("the length is known, so the track can be seeked", card.canSeek)
    }

    @Test
    fun `the track's key reaches Your music's card, so two tracks with one title and one length are two keys`() {
        assertNull("no key before the player reports one", deriveMusicControlState(yours).yours!!.trackKey)
        val first = YourMusicTiming(positionMs = 0L, durationMs = 240_000L, updatedAtElapsedRealtimeMs = 1L, advancing = true, trackKey = "/music/a.mp3#1")
        val second = first.copy(trackKey = "/music/b.mp3#2")
        assertEquals("/music/a.mp3#1", deriveMusicControlState(yours.copy(timing = first)).yours!!.trackKey)
        assertEquals("/music/b.mp3#2", deriveMusicControlState(yours.copy(timing = second)).yours!!.trackKey)
        assertNull("an empty key is no key", deriveMusicControlState(yours.copy(timing = first.copy(trackKey = ""))).yours!!.trackKey)
    }

    @Test
    fun `a track whose length is not known yet has a position but cannot be seeked`() {
        val opening = YourMusicTiming(positionMs = 0L, durationMs = null, updatedAtElapsedRealtimeMs = 1L, advancing = false)
        val card = deriveMusicControlState(yours.copy(timing = opening)).yours!!
        assertEquals(0L, card.positionMs)
        assertNull(card.durationMs)
        assertFalse(card.canSeek)
        assertFalse(card.positionAdvancing)
    }

    @Test
    fun `the track's format reaches Your music's card and is absent until the player reports it`() {
        assertNull(deriveMusicControlState(yours).yours!!.format)
        val format = TrackFormat("FLAC", lossless = true, bitDepth = 16, sampleRateHz = 44_100, bitrateKbps = 900)
        assertEquals(format, deriveMusicControlState(yours.copy(format = format)).yours!!.format)
    }

    @Test
    fun `each pause reason reaches the panel, and the title stays`() {
        for (reason in MusicPauseReason.entries) {
            val state = deriveMusicControlState(yours.copy(yourMusicSounding = false, pause = reason)).yours!!
            assertEquals(reason, state.pausedReason)
            assertFalse(state.isPlaying)
            assertEquals("Windy Hill", state.title)
        }
    }

    @Test
    fun `the 260 ms fade out of a pause already reads as paused`() {
        val state = deriveMusicControlState(yours.copy(yourMusicSounding = true, pause = MusicPauseReason.OTHER_APP)).yours!!
        assertFalse(state.isPlaying)
        assertEquals(MusicPauseReason.OTHER_APP, state.pausedReason)
    }

    @Test
    fun `both players are carried when both exist, Your music paused for the other app included`() {
        val both = deriveMusicControlState(yours.copy(other = spotify, otherAccess = true))
        assertTrue(both.yours!!.isPlaying)
        assertEquals("Song", both.other!!.title)
        assertTrue(both.otherAccess)
        // Paused for another app and that app's session is offered: both cards, Your music with the reason.
        val pausedForIt = deriveMusicControlState(
            yours.copy(yourMusicSounding = false, pause = MusicPauseReason.OTHER_APP, other = spotify),
        )
        assertEquals(MusicPauseReason.OTHER_APP, pausedForIt.yours!!.pausedReason)
        assertNotNull(pausedForIt.other)
    }

    @Test
    fun `another player is offered with its title, artist, app and state when Your music is not present`() {
        val state = deriveMusicControlState(yours.copy(yourMusicActive = false, other = spotify))
        assertNull(state.yours)
        val other = state.other!!
        assertEquals("Song", other.title)
        assertEquals("Artist", other.artist)
        assertEquals("Spotify", other.appLabel)
        assertTrue(other.isPlaying)
        assertTrue(state.present)

        val paused = deriveMusicControlState(yours.copy(yourMusicActive = false, other = spotify.copy(isPlaying = false)))
        assertFalse(paused.other!!.isPlaying)
        assertTrue("a paused session still opens the panel", paused.present)
    }

    @Test
    fun `a permanent hold with no grant says the player must press play, and only for another app's pause`() {
        val held = yours.copy(yourMusicSounding = false, pause = MusicPauseReason.OTHER_APP, pauseNeedsPlay = true)
        assertTrue(deriveMusicControlState(held).yours!!.pausedNeedsPlay)
        // Control arms: the grant is held (the hold ends itself), a different reason, and playing.
        assertFalse(deriveMusicControlState(held.copy(pauseNeedsPlay = false)).yours!!.pausedNeedsPlay)
        assertFalse(deriveMusicControlState(held.copy(pause = MusicPauseReason.SCREEN_OFF)).yours!!.pausedNeedsPlay)
        assertFalse(deriveMusicControlState(yours.copy(pauseNeedsPlay = true)).yours!!.pausedNeedsPlay)
    }

    @Test
    fun `Your music can skip only with more than one track`() {
        for (count in listOf(0, 1)) {
            val state = deriveMusicControlState(yours.copy(yourMusicTrackCount = count)).yours!!
            assertFalse("$count track(s): Previous", state.canPrevious)
            assertFalse("$count track(s): Next", state.canNext)
        }
        val many = deriveMusicControlState(yours.copy(yourMusicTrackCount = 2)).yours!!
        assertTrue(many.canPrevious)
        assertTrue(many.canNext)
        // A pause does not change what there is to skip to.
        val paused = deriveMusicControlState(yours.copy(yourMusicSounding = false, pause = MusicPauseReason.USER, yourMusicTrackCount = 5)).yours!!
        assertTrue(paused.canNext)
    }

    @Test
    fun `another player's controls are what its session offers`() {
        val offered = deriveMusicControlState(yours.copy(yourMusicActive = false, other = spotify)).other!!
        assertTrue("a session is assumed to skip and play until its bridge says otherwise", offered.canPrevious && offered.canNext && offered.canPlayPause)
        val limited = deriveMusicControlState(
            yours.copy(yourMusicActive = false, other = spotify.copy(canPrevious = false, canNext = true, canPlayPause = false)),
        ).other!!
        assertFalse(limited.canPrevious)
        assertTrue(limited.canNext)
        assertFalse(limited.canPlayPause)
    }

    @Test
    fun `without a session nothing is offered`() {
        assertEquals(MusicControlState(), deriveMusicControlState(yours.copy(yourMusicActive = false)))
        assertEquals(MusicControlState(), deriveMusicControlState(yours.copy(yourMusicSounding = false)))
        assertFalse(MusicControlState().present)
    }

    @Test
    fun `the music is off at level zero, so the panel is not offered for it`() {
        val silent = yours.copy(musicVolume = 0f, yourMusicSounding = false)
        assertNull(deriveMusicControlState(silent).yours)
        assertFalse(musicPanelAvailable(silent))
    }

    @Test
    fun `the M-tap input is Your music playing or paused, or another session playing or paused`() {
        assertTrue("playing", musicPanelAvailable(yours))
        for (reason in MusicPauseReason.entries) {
            assertTrue("paused: $reason", musicPanelAvailable(yours.copy(yourMusicSounding = false, pause = reason)))
        }
        assertTrue("another session playing", musicPanelAvailable(yours.copy(yourMusicActive = false, other = spotify)))
        assertTrue("another session paused", musicPanelAvailable(yours.copy(yourMusicActive = false, other = spotify.copy(isPlaying = false))))
        // Control arms: the theme bed (Your music inactive), a stopped player, and no grant (the bridge offers no session).
        assertFalse("theme bed", musicPanelAvailable(yours.copy(yourMusicActive = false)))
        assertFalse("stopped", musicPanelAvailable(yours.copy(yourMusicSounding = false)))
    }

    // ---- the theme bed, for what names the sound on the device (the Equalizer's header) ----

    /** The theme bed is the source: Your music is not active, and the engine's now-playing names the bed. */
    private val bed = yours.copy(yourMusicActive = false, title = null, bedTitle = "Overworld Theme")

    @Test
    fun `the theme bed is named while its deck sounds, and is not a panel player`() {
        val state = deriveMusicControlState(bed)
        assertEquals(ThemeBedState(title = "Overworld Theme", isPlaying = true), state.bed)
        assertNull("the bed is not Your music", state.yours)
        assertFalse("and it never opens the panel", state.present)
        assertFalse(musicPanelAvailable(bed))
        assertEquals("nor does it move the last-played source", MusicSource.YOUR_MUSIC, musicLastPlayed(MusicSource.YOUR_MUSIC, state))
        assertEquals(MusicSource.OTHER_PLAYER, musicLastPlayed(MusicSource.OTHER_PLAYER, state))
    }

    @Test
    fun `a bed that is held silent keeps its title but does not read as playing`() {
        // The engine releases the deck when another app, a call, the hours or a game silence the bed: nothing sounds, whatever nowPlaying says.
        assertEquals(ThemeBedState("Overworld Theme", isPlaying = false), deriveMusicControlState(bed.copy(yourMusicSounding = false)).bed)
        // The level at zero is the off switch, even with a deck still fading.
        assertEquals(ThemeBedState("Overworld Theme", isPlaying = false), deriveMusicControlState(bed.copy(musicVolume = 0f)).bed)
    }

    @Test
    fun `no bed is reported while Your music is the source or the music is off`() {
        assertNull("Your music is active: it is the music", deriveMusicControlState(yours.copy(bedTitle = "Overworld Theme")).bed)
        assertNull("Music off leaves no now-playing title, so no bed", deriveMusicControlState(bed.copy(bedTitle = null)).bed)
        assertEquals(MusicControlState(), deriveMusicControlState(yours.copy(yourMusicActive = false, yourMusicSounding = false)))
    }

    @Test
    fun `the bed sounds alongside another app's session without becoming a card`() {
        val both = deriveMusicControlState(bed.copy(other = spotify, otherAccess = true))
        assertTrue(both.bed!!.isPlaying)
        assertEquals("Song", both.other!!.title)
        assertNull(both.yours)
        assertTrue("the panel is open for the other app alone", both.present)
    }

    @Test
    fun `stranger audio is the active count less Mimir's own, never negative`() {
        assertEquals(1, strangerPlayers(activeConfigurations = 2, ownActivePlayers = 1))
        assertEquals(0, strangerPlayers(activeConfigurations = 1, ownActivePlayers = 1))
        assertEquals(0, strangerPlayers(activeConfigurations = 0, ownActivePlayers = 2))
    }

    @Test
    fun `without the grant only USAGE_MEDIA music or movie counts as another player`() {
        val media = AudioAttributes.USAGE_MEDIA
        assertTrue("YouTube/Spotify music", isRealMediaPlayback(media, AudioAttributes.CONTENT_TYPE_MUSIC))
        assertTrue("video", isRealMediaPlayback(media, AudioAttributes.CONTENT_TYPE_MOVIE))
        // The backgrounded Dolphin: OpenSL, USAGE_MEDIA, CONTENT_TYPE_UNKNOWN, no focus, started indefinitely.
        assertFalse("emulator UNKNOWN", isRealMediaPlayback(media, AudioAttributes.CONTENT_TYPE_UNKNOWN))
        assertFalse("USAGE_GAME music", isRealMediaPlayback(AudioAttributes.USAGE_GAME, AudioAttributes.CONTENT_TYPE_MUSIC))
        assertFalse("UI sonification", isRealMediaPlayback(media, AudioAttributes.CONTENT_TYPE_SONIFICATION))
        assertFalse("speech", isRealMediaPlayback(media, AudioAttributes.CONTENT_TYPE_SPEECH))
        assertFalse("notification usage", isRealMediaPlayback(AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.CONTENT_TYPE_MUSIC))
    }

    @Test
    fun `with the grant the bridge decides and the heuristic is ignored, without it the heuristic decides`() {
        // Granted: a playing session wins; an emulator-like stranger the heuristic sees does not count.
        assertTrue(anotherPlayerPlaying(sessionAccessGranted = true, sessionPlaying = true, heuristicStrangers = 0))
        assertFalse(anotherPlayerPlaying(sessionAccessGranted = true, sessionPlaying = false, heuristicStrangers = 1))
        // Not granted: the session flag is meaningless, the count rules.
        assertTrue(anotherPlayerPlaying(sessionAccessGranted = false, sessionPlaying = false, heuristicStrangers = 1))
        assertFalse(anotherPlayerPlaying(sessionAccessGranted = false, sessionPlaying = true, heuristicStrangers = 0))
    }

    @Test
    fun `previous goes back through the folder and wraps, a bed has one piece`() {
        val tracks = listOf("a", "b", "c").map { MusicTrack(folder = "", name = "$it.mp3", id = it) }
        val folder = FrontendMusicPlan.Folder("tree", null, tracks)
        assertEquals(2, folder.previousCursor(0))
        assertEquals(0, folder.previousCursor(1))
        assertEquals(1, folder.nextCursor(0))
        assertEquals(0, FrontendMusicPlan.Bed(MimirFrontendBed.Ambient).previousCursor(0))
    }

    @Test
    fun `the last player to sound is the one that plays, and both or neither leave it standing`() {
        val yoursPlaying = deriveMusicControlState(yours)
        val yoursPaused = deriveMusicControlState(yours.copy(pause = MusicPauseReason.USER))
        val otherPlaying = deriveMusicControlState(yours.copy(pause = MusicPauseReason.OTHER_APP, other = spotify))
        val otherPaused = deriveMusicControlState(
            yours.copy(pause = MusicPauseReason.USER, other = spotify.copy(isPlaying = false)),
        )
        val both = deriveMusicControlState(yours.copy(other = spotify))

        assertEquals(MusicSource.YOUR_MUSIC, musicLastPlayed(MusicSource.OTHER_PLAYER, yoursPlaying))
        assertEquals(MusicSource.OTHER_PLAYER, musicLastPlayed(MusicSource.YOUR_MUSIC, otherPlaying))
        // Neither plays: the most recently played stands, whichever it was.
        assertEquals(MusicSource.OTHER_PLAYER, musicLastPlayed(MusicSource.OTHER_PLAYER, otherPaused))
        assertEquals(MusicSource.YOUR_MUSIC, musicLastPlayed(MusicSource.YOUR_MUSIC, otherPaused))
        assertEquals(MusicSource.OTHER_PLAYER, musicLastPlayed(MusicSource.OTHER_PLAYER, yoursPaused))
        // Both play (a moment around a switch): unchanged as well.
        assertEquals(MusicSource.OTHER_PLAYER, musicLastPlayed(MusicSource.OTHER_PLAYER, both))
        assertEquals(MusicSource.YOUR_MUSIC, musicLastPlayed(MusicSource.YOUR_MUSIC, both))
        // The panel's state starts on Your music until anything has played.
        assertEquals(MusicSource.YOUR_MUSIC, MusicControlState().lastPlayed)
    }
}
