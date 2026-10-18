package com.gruning.mimir.rewrite.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The track-info strings, the bitrate arithmetic and the codec mapping behind them: pure, so no device is needed. */
class TrackFormatTest {
    @Test
    fun `a lossless file reads codec, depth over rate, and the file average`() {
        val format = TrackFormat("FLAC", lossless = true, bitDepth = 24, sampleRateHz = 96_000, bitrateKbps = 2_304)
        assertEquals("FLAC · 24/96 · 2,304 kbps", format.sourceLine)
    }

    @Test
    fun `a lossy stream reads codec, bitrate and rate in kilohertz`() {
        val format = TrackFormat("MP3", lossless = false, sampleRateHz = 44_100, bitrateKbps = 320)
        assertEquals("MP3 · 320 kbps · 44.1 kHz", format.sourceLine)
    }

    @Test
    fun `the output line names the depth and the rate, and a float output says float`() {
        assertEquals("Output 16-bit · 48 kHz", TrackFormat(output = OutputFormat(16, false, 48_000)).outputLine)
        assertEquals("Output float · 96 kHz", TrackFormat(output = OutputFormat(32, true, 96_000)).outputLine)
        assertEquals("16-bit · 48 kHz", TrackFormat(output = OutputFormat(16, false, 48_000)).outputValue)
        assertEquals("float · 96 kHz", TrackFormat(output = OutputFormat(32, true, 96_000)).outputValue)
    }

    @Test
    fun `an unknown part is left out and nothing ever prints null`() {
        val cases = listOf(
            TrackFormat("FLAC", lossless = true, sampleRateHz = 44_100),
            TrackFormat("FLAC", lossless = true, bitDepth = 16),
            TrackFormat("Opus", lossless = false),
            TrackFormat(codec = null, lossless = false, sampleRateHz = 48_000),
            TrackFormat("AAC", lossless = false, bitrateKbps = 256),
            TrackFormat(output = OutputFormat(null, false, 44_100)),
            TrackFormat(output = OutputFormat(16, false, null)),
        )
        cases.forEach {
            assertFalse(it.toString(), (it.sourceLine ?: "").contains("null"))
            assertFalse(it.toString(), (it.outputLine ?: "").contains("null"))
        }
        assertEquals("FLAC · 44.1 kHz", cases[0].sourceLine)
        assertEquals("FLAC · 16-bit", cases[1].sourceLine)
        assertEquals("Opus", cases[2].sourceLine)
        assertEquals("48 kHz", cases[3].sourceLine)
        assertEquals("AAC · 256 kbps", cases[4].sourceLine)
        assertEquals("Output 44.1 kHz", cases[5].outputLine)
        assertEquals("Output 16-bit", cases[6].outputLine)
    }

    @Test
    fun `nothing known is no line at all`() {
        assertNull(TrackFormat().sourceLine)
        assertNull(TrackFormat().outputLine)
        assertNull(TrackFormat().outputValue)
        assertNull(TrackFormat(output = OutputFormat()).outputLine)
    }

    @Test
    fun `rates and thousands are spelled the same on every device`() {
        assertEquals("44.1 kHz", TrackFormatText.kilohertz(44_100))
        assertEquals("48 kHz", TrackFormatText.kilohertz(48_000))
        assertEquals("22.05 kHz", TrackFormatText.kilohertz(22_050))
        assertEquals("192 kHz", TrackFormatText.kilohertz(192_000))
        assertEquals("8 kHz", TrackFormatText.kilohertz(8_000))
        assertEquals("999", TrackFormatText.grouped(999))
        assertEquals("1,000", TrackFormatText.grouped(1_000))
        assertEquals("4,608", TrackFormatText.grouped(4_608))
        assertEquals("1,234,567", TrackFormatText.grouped(1_234_567))
    }

    @Test
    fun `a lossless average is size times eight over the duration, rounded`() {
        // 4 minutes of 16/44.1 stereo PCM: 1,411.2 kbps. 240 s x 44,100 x 4 bytes = 42,336,000 bytes.
        assertEquals(1_411, TrackFormatText.losslessBitrateKbps(42_336_000L, 240_000L))
        // Bits per millisecond are kilobits per second: 1,000,000 bytes over 8 s is exactly 1,000 kbps.
        assertEquals(1_000, TrackFormatText.losslessBitrateKbps(1_000_000L, 8_000L))
        // Rounds to nearest: 999.6 -> 1000, 999.4 -> 999.
        assertEquals(1_000, TrackFormatText.losslessBitrateKbps(999_600L, 8_000L))
        assertEquals(999, TrackFormatText.losslessBitrateKbps(999_400L, 8_000L))
        // A long hi-res file does not overflow: 2 GB over 2 hours.
        assertEquals(2_386, TrackFormatText.losslessBitrateKbps(2_147_483_648L, 7_200_000L))
    }

    @Test
    fun `an unknown size or duration is no bitrate`() {
        assertNull(TrackFormatText.losslessBitrateKbps(0L, 240_000L))
        assertNull(TrackFormatText.losslessBitrateKbps(-1L, 240_000L))
        assertNull(TrackFormatText.losslessBitrateKbps(1_000L, 0L))
        assertNull(TrackFormatText.losslessBitrateKbps(1_000L, -9_223_372_036_854_775_807L))
        assertNull(TrackFormatText.lossyBitrateKbps(-1))
        assertNull(TrackFormatText.lossyBitrateKbps(0))
    }

    @Test
    fun `a lossy bitrate rounds bits per second to kilobits`() {
        assertEquals(320, TrackFormatText.lossyBitrateKbps(320_000))
        assertEquals(320, TrackFormatText.lossyBitrateKbps(319_700))
        assertEquals(128, TrackFormatText.lossyBitrateKbps(128_499))
    }

    @Test
    fun `codec labels come from the sample mime type`() {
        assertEquals("FLAC", TrackFormatText.codecLabel("audio/flac", null))
        assertEquals("MP3", TrackFormatText.codecLabel("audio/mpeg", null))
        assertEquals("AAC", TrackFormatText.codecLabel("audio/mp4a-latm", "audio/mp4"))
        assertEquals("Opus", TrackFormatText.codecLabel("audio/opus", "audio/ogg"))
        assertEquals("Vorbis", TrackFormatText.codecLabel("audio/vorbis", "audio/ogg"))
        assertEquals("ALAC", TrackFormatText.codecLabel("audio/alac", "audio/mp4"))
        assertEquals("WAV", TrackFormatText.codecLabel("audio/raw", null))
        assertNull(TrackFormatText.codecLabel("audio/amr", null))
        assertNull(TrackFormatText.codecLabel(null, null))
    }

    @Test
    fun `the source format keeps bit depth for lossless only and takes the average bitrate before the peak for lossy`() {
        val flac = TrackFormatText.sourceFormat("audio/flac", null, 21, 96_000, 2, -1, -1, 2_304, null)
        assertEquals(TrackFormat("FLAC", true, 24, 96_000, 2, 2_304, null), flac)
        // A lossy stream has no bit depth even when the extractor reports an encoding.
        val mp3 = TrackFormatText.sourceFormat("audio/mpeg", null, 2, 44_100, 2, 256_000, 320_000, 1_411, null)
        assertEquals(TrackFormat("MP3", false, null, 44_100, 2, 256, null), mp3)
        val vorbis = TrackFormatText.sourceFormat("audio/vorbis", "audio/ogg", -1, 44_100, 2, -1, 192_000, null, null)
        assertEquals(192, vorbis.bitrateKbps)
        // A lossless file whose size is not known yet has no bitrate, not a made-up one.
        assertNull(TrackFormatText.sourceFormat("audio/flac", null, 2, 44_100, 2, -1, -1, null, null).bitrateKbps)
    }

    @Test
    fun `pcm encodings map to bit depths`() {
        assertEquals(8, TrackFormatText.pcmBitDepth(3))
        assertEquals(16, TrackFormatText.pcmBitDepth(2))
        assertEquals(24, TrackFormatText.pcmBitDepth(21))
        assertEquals(32, TrackFormatText.pcmBitDepth(22))
        assertEquals(32, TrackFormatText.pcmBitDepth(4))
        assertNull(TrackFormatText.pcmBitDepth(-1))
    }
}
