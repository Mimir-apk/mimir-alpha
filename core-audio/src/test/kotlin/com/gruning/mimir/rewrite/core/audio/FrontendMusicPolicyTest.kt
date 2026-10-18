package com.gruning.mimir.rewrite.core.audio

import android.media.AudioAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrontendMusicPolicyTest {
    private val admitted = FrontendMusicPolicyInput(
        appForeground = true,
        musicVolume = 0.22f,
        focus = AudioFocusState.Foreground,
        environmentBlocked = false,
        competingOwnedBed = false,
    )

    /**
     * The rail's number is the delivered number. Previously the bed was scaled a second time by the
     * UI sound-effect volume, so a rail reading 22% delivered 13.2% -- the control was 4.4 dB out,
     * which made tuning the music by ear tuning against a lie.
     */
    @Test
    fun `the bed delivers exactly the loudness the rail shows, and clamps`() {
        assertEquals(0.22f, FrontendMusicPolicy.targetVolume(admitted), 0.0001f)
        assertEquals(1f, FrontendMusicPolicy.targetVolume(admitted.copy(musicVolume = 4f)), 0.0001f)
        assertEquals(0f, FrontendMusicPolicy.targetVolume(admitted.copy(musicVolume = -1f)), 0f)
        // Zero on its own control is the off switch.
        assertEquals(0f, FrontendMusicPolicy.targetVolume(admitted.copy(musicVolume = 0f)), 0f)
    }

    @Test
    fun `the Music switch silences the player through the ordinary fade, whatever else admits it`() {
        assertEquals(0f, FrontendMusicPolicy.targetVolume(admitted.copy(musicEnabled = false)), 0f)
        // On is the default, so a caller that predates the switch keeps its meaning.
        assertEquals(0.22f, FrontendMusicPolicy.targetVolume(admitted.copy(musicEnabled = true)), 0.0001f)
        assertEquals(0.22f, FrontendMusicPolicy.targetVolume(admitted), 0.0001f)
    }

    @Test
    fun `frontend music is continuous across in-app surfaces and silent outside foreground`() {
        assertEquals(0f, FrontendMusicPolicy.targetVolume(admitted.copy(appForeground = false)), 0f)
    }

    @Test
    fun `games and external audio suspend while Hero Motion ducks to fifteen percent`() {
        assertEquals(
            0f,
            FrontendMusicPolicy.targetVolume(admitted.copy(focus = AudioFocusState.Suspended)),
            0f,
        )
        assertEquals(
            0f,
            FrontendMusicPolicy.targetVolume(admitted.copy(environmentBlocked = true)),
            0f,
        )
        assertEquals(
            0.22f * 0.15f,
            FrontendMusicPolicy.targetVolume(admitted.copy(competingOwnedBed = true)),
            0.0001f,
        )
        assertEquals(0.22f, FrontendMusicPolicy.targetVolume(admitted), 0.0001f)
    }

    @Test
    fun `only another owned token competes with frontend music`() {
        assertFalse(frontendMusicHasCompetingBed(emptySet(), frontendTokens = setOf(7L)))
        assertFalse(frontendMusicHasCompetingBed(setOf(7L), frontendTokens = setOf(7L)))
        assertTrue(frontendMusicHasCompetingBed(setOf(7L, 9L), frontendTokens = setOf(7L)))

        // The gapless loop runs two of our own decks. Neither may read as a competitor, or the bed
        // would duck itself to 15% for the whole life of the loop — there is always a next deck.
        assertFalse(frontendMusicHasCompetingBed(setOf(7L, 8L), frontendTokens = setOf(7L, 8L)))
        // A real stranger alongside both of our decks still competes.
        assertTrue(frontendMusicHasCompetingBed(setOf(7L, 8L, 9L), frontendTokens = setOf(7L, 8L)))
    }

    @Test
    fun `media-bed ownership admits both Hero Motion and original frontend music`() {
        assertTrue(mimirOwnBedContentType(AudioAttributes.CONTENT_TYPE_MOVIE))
        assertTrue(mimirOwnBedContentType(AudioAttributes.CONTENT_TYPE_MUSIC))
        assertFalse(mimirOwnBedContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION))
    }
}
