package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Your music plays everywhere and continuously, and its player gain compensates for the
 * STREAM_MUSIC level so the Music slider is the perceived level. The theme bed keeps every gate.
 */
class YourMusicEverywherePolicyTest {
    private val backgrounded = FrontendMusicPolicyInput(
        appForeground = false,
        musicVolume = 0.5f,
        focus = AudioFocusState.Suspended,
        environmentBlocked = true,
        competingOwnedBed = false,
        everywhere = true,
    )

    @Test
    fun `Your music ignores Mimir backgrounding, a launched game and another app's playback`() {
        assertEquals(0.5f, FrontendMusicPolicy.targetVolume(backgrounded), 0.0001f)
        // Control: the same input as the theme bed is silent, preserving the theme-bed gating policy.
        assertEquals(0f, FrontendMusicPolicy.targetVolume(backgrounded.copy(everywhere = false)), 0f)
    }

    @Test
    fun `moving between Mimir and other apps never changes Your music's target, so nothing restarts or re-fades`() {
        val inMimir = backgrounded.copy(appForeground = true, focus = AudioFocusState.Foreground, environmentBlocked = false)
        assertEquals(FrontendMusicPolicy.targetVolume(inMimir), FrontendMusicPolicy.targetVolume(backgrounded), 0f)
    }

    @Test
    fun `each pause reason silences Your music, and the Music switch and level still do`() {
        for (reason in MusicPauseReason.entries) {
            assertEquals("$reason", 0f, FrontendMusicPolicy.targetVolume(backgrounded.copy(pauseReason = reason)), 0f)
        }
        assertEquals(0f, FrontendMusicPolicy.targetVolume(backgrounded.copy(musicEnabled = false)), 0f)
        assertEquals(0f, FrontendMusicPolicy.targetVolume(backgrounded.copy(musicVolume = 0f)), 0f)
    }

    @Test
    fun `Hero Motion still ducks Your music inside Mimir`() {
        assertEquals(0.5f * 0.15f, FrontendMusicPolicy.targetVolume(backgrounded.copy(competingOwnedBed = true)), 0.0001f)
    }

    @Test
    fun `the gain is the perceived level over the stream level, capped at full`() {
        // M 0.5 at S 1.0: half gain. S 0.5: full gain. Lowering S below M cannot be made up for.
        assertEquals(0.5f, FrontendMusicPolicy.compensatedGain(perceived = 0.5f, streamLevel = 1f), 0.0001f)
        assertEquals(1f, FrontendMusicPolicy.compensatedGain(perceived = 0.5f, streamLevel = 0.5f), 0.0001f)
        assertEquals(1f, FrontendMusicPolicy.compensatedGain(perceived = 0.5f, streamLevel = 0.25f), 0.0001f)
        assertEquals(0.5f, FrontendMusicPolicy.compensatedGain(perceived = 0.2f, streamLevel = 0.4f), 0.0001f)
    }

    @Test
    fun `lowering the stream keeps the perceived music where it is until the stream reaches it`() {
        val perceived = 0.4f
        var previous = -1f
        for (stream in listOf(1f, 0.8f, 0.6f, 0.4f, 0.3f, 0.2f)) {
            val gain = FrontendMusicPolicy.compensatedGain(perceived, stream)
            val heard = gain * stream
            if (stream >= perceived) {
                assertEquals("S=$stream", perceived, heard, 0.0001f)
            } else {
                assertTrue("below M both fall: S=$stream", heard < perceived && (previous < 0f || heard <= previous))
            }
            previous = heard
        }
    }

    @Test
    fun `a stream at zero is silence, not a division`() {
        assertEquals(0f, FrontendMusicPolicy.compensatedGain(perceived = 0.5f, streamLevel = 0f), 0f)
        assertEquals(0f, FrontendMusicPolicy.compensatedGain(perceived = 0f, streamLevel = 0.5f), 0f)
    }

    @Test
    fun `the policy applies the gain to Your music only`() {
        val yours = backgrounded.copy(mediaLevel = 0.25f, musicVolume = 0.2f)
        assertEquals(0.8f, FrontendMusicPolicy.targetVolume(yours), 0.0001f)
        // The theme bed rides the stream unchanged.
        val bed = FrontendMusicPolicyInput(
            appForeground = true,
            musicVolume = 0.2f,
            focus = AudioFocusState.Foreground,
            environmentBlocked = false,
            competingOwnedBed = false,
            mediaLevel = 0.25f,
        )
        assertEquals(0.2f, FrontendMusicPolicy.targetVolume(bed), 0.0001f)
    }

    @Test
    fun `the cap shows exactly while Game and media is below the Music level`() {
        assertTrue(FrontendMusicPolicy.musicCappedByMedia(perceived = 0.6f, streamLevel = 0.4f))
        assertFalse(FrontendMusicPolicy.musicCappedByMedia(perceived = 0.4f, streamLevel = 0.4f))
        assertFalse(FrontendMusicPolicy.musicCappedByMedia(perceived = 0.4f, streamLevel = 0.9f))
    }
}
