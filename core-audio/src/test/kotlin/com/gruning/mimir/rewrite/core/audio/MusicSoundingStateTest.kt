package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings › Music status reads whether Mimir's music SOUNDS (the bed's deck at a
 * level above zero, or Your music playing with no pause), so it says Paused while the music is held or silent.
 */
class MusicSoundingStateTest {
    // ── 3. The status reads whether the music sounds ─────────────────────────────────────────────────────────────

    private val bedInputs = MusicControlInputs(
        yourMusicActive = false,
        yourMusicSounding = true,
        title = null,
        pause = null,
        musicVolume = 0.5f,
        bedTitle = "Overworld Theme",
    )
    private val yoursInputs = MusicControlInputs(yourMusicActive = true, yourMusicSounding = true, title = "Windy Hill", pause = null, musicVolume = 0.5f)

    @Test
    fun `the theme bed sounding reads as sounding`() {
        assertTrue(deriveMusicControlState(bedInputs).mimirMusicSounding)
    }

    @Test
    fun `the theme bed held silent, at level zero or with no deck reads as not sounding`() {
        assertFalse("held: the engine released the deck", deriveMusicControlState(bedInputs.copy(yourMusicSounding = false)).mimirMusicSounding)
        assertFalse("level zero", deriveMusicControlState(bedInputs.copy(musicVolume = 0f)).mimirMusicSounding)
        assertFalse("no bed at all (Music off)", deriveMusicControlState(bedInputs.copy(bedTitle = null)).mimirMusicSounding)
    }

    @Test
    fun `Your music playing reads as sounding, and paused for any reason does not`() {
        assertTrue(deriveMusicControlState(yoursInputs).mimirMusicSounding)
        for (reason in MusicPauseReason.entries) {
            assertFalse("$reason", deriveMusicControlState(yoursInputs.copy(pause = reason)).mimirMusicSounding)
        }
        assertFalse("nothing sounding", deriveMusicControlState(yoursInputs.copy(yourMusicSounding = false)).mimirMusicSounding)
    }

    @Test
    fun `another app's player sounding is not Mimir's music`() {
        val other = OtherPlayerSnapshot("Song", "Artist", "YT Music", artwork = null, isPlaying = true)
        assertFalse(deriveMusicControlState(bedInputs.copy(yourMusicSounding = false, other = other)).mimirMusicSounding)
    }

    @Test
    fun `sounding into a silent stream is not audible, so the chip says Paused`() {
        assertTrue("sounding at a level", mimirMusicAudible(sounding = true, mediaLevel = 0.4f))
        assertFalse("Game & media at zero: the player runs into silence", mimirMusicAudible(sounding = true, mediaLevel = 0f))
        assertFalse("not sounding, whatever the level", mimirMusicAudible(sounding = false, mediaLevel = 1f))
        assertFalse(mimirMusicAudible(sounding = false, mediaLevel = 0f))
    }
}
