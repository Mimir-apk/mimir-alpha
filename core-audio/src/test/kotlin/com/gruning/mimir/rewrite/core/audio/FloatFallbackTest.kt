package com.gruning.mimir.rewrite.core.audio

import android.os.Bundle
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The way back from float output: an AudioTrack that will not start rebuilds the player without float, once, and never loops. */
class FloatFallbackTest {
    /** A player error as the player reports it, without a clock or a device (the timestamp is given). */
    private fun playerError(code: Int): PlaybackException = object : PlaybackException("fake", null, code, Bundle.EMPTY, 0L) {}

    private val trackInitFailed = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

    @Test
    fun `the first float AudioTrack that will not start asks for the rebuild, and float is then off for good`() {
        var probes = 0
        val fallback = FloatFallback { probes += 1; 7_680 }
        assertTrue(fallback.useFloat())
        assertEquals(1, probes)

        assertTrue("the rebuild is asked for", fallback.onPlayerError(playerError(trackInitFailed).errorCode, builtWithFloat = true))
        assertTrue(fallback.refused)

        // The rebuilt player is built without float, and the platform is not even asked.
        assertFalse(fallback.useFloat())
        assertEquals(1, probes)
    }

    @Test
    fun `a second refusal never rebuilds again, whichever player reports it`() {
        val fallback = FloatFallback { 7_680 }
        assertTrue(fallback.onPlayerError(trackInitFailed, builtWithFloat = true))
        // The rebuilt player has no float, and if it still cannot start a track the ordinary path (skip, give up) takes it.
        assertFalse(fallback.onPlayerError(trackInitFailed, builtWithFloat = false))
        // Not even a player that claims float again: the refusal is sticky.
        assertFalse(fallback.onPlayerError(trackInitFailed, builtWithFloat = true))
        assertFalse(fallback.onPlayerError(trackInitFailed, builtWithFloat = true))
        assertTrue(fallback.refused)
    }

    @Test
    fun `an error that is not the float output's does not rebuild and does not refuse float`() {
        val fallback = FloatFallback { 7_680 }
        val others = listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
        for (code in others) assertFalse("code $code", fallback.onPlayerError(playerError(code).errorCode, builtWithFloat = true))
        assertFalse(fallback.refused)
        assertTrue(fallback.useFloat())
    }

    @Test
    fun `a player built without float has nothing to give up, so its failing AudioTrack is an ordinary failure`() {
        val fallback = FloatFallback { -2 }
        assertFalse(fallback.useFloat())
        assertFalse(fallback.onPlayerError(trackInitFailed, builtWithFloat = false))
        assertFalse("a refusal is only recorded for float output", fallback.refused)
    }

    @Test
    fun `float is used only while the platform plays it and nothing has refused it`() {
        assertTrue(FloatFallback { 7_680 }.useFloat())
        assertFalse(FloatFallback { -2 }.useFloat())
        assertFalse(FloatFallback { -1 }.useFloat())
        assertFalse(FloatFallback { throw IllegalStateException("no audio service") }.useFloat())
    }
}
