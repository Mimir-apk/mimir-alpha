package com.gruning.mimir.rewrite.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The equalizer processor on synthetic 16-bit PCM: what it does to a tone, and what it must never do to the bits. */
class GraphicEqualizerProcessorTest {
    private val stereo = 2

    private fun processor(settings: EqualizerSettings, rate: Int = 48_000, channels: Int = stereo): GraphicEqualizerProcessor {
        val p = GraphicEqualizerProcessor()
        p.setSettings(settings)
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        p.flush(StreamMetadata.DEFAULT)
        return p
    }

    private fun sine(freq: Double, amplitude: Double, frames: Int, rate: Int, channels: Int = stereo): ShortArray =
        ShortArray(frames * channels) { i ->
            val frame = i / channels
            (amplitude * 32_767.0 * sin(2.0 * PI * freq * frame / rate)).roundToInt().toShort()
        }

    private fun buffer(samples: ShortArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { b.putShort(it) }
        b.flip()
        return b
    }

    /** Runs [samples] through [p] in 1,000-frame buffers, as the sink would, and returns everything it produced. */
    private fun run(p: GraphicEqualizerProcessor, samples: ShortArray, channels: Int = stereo, chunkFrames: Int = 1_000): ShortArray {
        val out = ArrayList<Short>(samples.size)
        var at = 0
        while (at < samples.size) {
            val end = minOf(samples.size, at + chunkFrames * channels)
            p.queueInput(buffer(samples.copyOfRange(at, end)))
            val produced = p.output
            val shorts = produced.order(ByteOrder.nativeOrder()).asShortBuffer()
            while (shorts.hasRemaining()) out += shorts.get()
            at = end
        }
        return out.toShortArray()
    }

    private fun rms(samples: ShortArray, from: Int = 0): Double {
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (samples.size - from))
    }

    /** The gain the processor gives a tone at [freq], in dB, measured on the second half so the filters have settled. */
    private fun gainAt(settings: EqualizerSettings, freq: Double, rate: Int = 48_000): Double {
        val p = processor(settings, rate)
        val input = sine(freq, 0.25, rate, rate)
        val output = run(p, input)
        return 20.0 * log10(rms(output, output.size / 2) / rms(input, input.size / 2))
    }

    private fun with(vararg bands: Pair<Int, Float>): EqualizerSettings {
        var s = EqualizerSettings(enabled = true)
        bands.forEach { (i, db) -> s = s.withBand(i, db) }
        return s
    }

    @Test
    fun `flat or off is a bit-exact passthrough, extremes included`() {
        val noise = ShortArray(9_000) { i -> (((i * 2_654_435_761L) ushr 7).toInt() and 0xFFFF).toShort() }
        noise[0] = Short.MIN_VALUE
        noise[1] = Short.MAX_VALUE
        listOf(EqualizerSettings(enabled = false), EqualizerSettings(enabled = true), with(5 to 6f).copy(enabled = false)).forEach {
            val out = run(processor(it), noise)
            assertArrayEquals(noise, out)
        }
    }

    @Test
    fun `a boosted band raises its tone by the boost minus the headroom, and the far tones fall by the headroom`() {
        val settings = with(5 to 6f)
        val headroom = settings.effectiveHeadroomDb.toDouble()
        assertEquals(6.0, headroom, 0.1)
        assertEquals("the boosted tone", 6.0 - headroom, gainAt(settings, 1_000.0), 0.15)
        // The far tones are not boosted, so the preamp alone moves them: they land headroom dB below the source.
        assertEquals("a far low tone", -headroom, gainAt(settings, 62.0), 0.6)
        assertEquals("a far high tone", -headroom, gainAt(settings, 16_000.0), 0.6)
        // The relative boost is real: the band stands 6 dB above the far tones (a passthrough would make this zero).
        assertEquals(6.0, gainAt(settings, 1_000.0) - gainAt(settings, 16_000.0), 0.6)
    }

    @Test
    fun `a cut needs no headroom and lowers its tone by the cut`() {
        val settings = with(5 to -6f)
        assertEquals(0f, settings.effectiveHeadroomDb, 0.001f)
        assertEquals(-6.0, gainAt(settings, 1_000.0), 0.15)
        assertEquals(0.0, gainAt(settings, 16_000.0), 0.2)
    }

    @Test
    fun `the bass and treble shelves lift their ends`() {
        val settings = EqualizerSettings(enabled = true).withBass(6f).withTreble(6f)
        val headroom = settings.effectiveHeadroomDb.toDouble()
        assertTrue(headroom in 5.5..7.0)
        assertEquals(6.0 - headroom, gainAt(settings, 40.0), 1.0)
        assertEquals(6.0 - headroom, gainAt(settings, 16_000.0), 1.0)
        assertEquals(-headroom, gainAt(settings, 1_000.0), 0.5)
    }

    @Test
    fun `the strongest curve never clips a loud source`() {
        val settings = EqualizerSettings(enabled = true, bandsDb = List(10) { 12f }, bassDb = 12f, trebleDb = 12f, presetId = EqualizerPresetId.CUSTOM)
        assertTrue(settings.effectiveHeadroomDb > 12f)
        for (freq in listOf(31.0, 100.0, 440.0, 1_000.0, 3_000.0, 10_000.0, 16_000.0)) {
            val out = run(processor(settings), sine(freq, 0.99, 48_000, 48_000))
            val peak = out.maxOf { abs(it.toInt()) }
            assertTrue("peak $peak at $freq Hz", peak < 32_767)
        }
    }

    @Test
    fun `a flush of the same format keeps the filters memory, so a gapless join is no discontinuity`() {
        val settings = EqualizerSettings(enabled = true).withBass(10f).withBand(2, 8f)
        val input = sine(80.0, 0.5, 9_600, 48_000)
        val whole = run(processor(settings), input)
        // The same signal with the sink's flush at the join (Media3 flushes at every item transition, gapless ones too).
        val p = processor(settings)
        val half = input.size / 2
        val first = run(p, input.copyOfRange(0, half))
        p.flush(StreamMetadata.DEFAULT)
        val second = run(p, input.copyOfRange(half, input.size))
        assertArrayEquals("the join is the unbroken signal, bit for bit", whole, first + second)
    }

    @Test
    fun `a flush that changes the rate or the channel count starts the filters afresh`() {
        val settings = EqualizerSettings(enabled = true).withBass(10f).withBand(2, 8f)
        val p = processor(settings, rate = 48_000)
        run(p, sine(80.0, 0.5, 4_800, 48_000))
        p.configure(AudioFormat(44_100, stereo, C.ENCODING_PCM_16BIT))
        p.flush(StreamMetadata.DEFAULT)
        val input = sine(80.0, 0.5, 4_800, 44_100)
        assertArrayEquals("a new rate is as a new processor", run(processor(settings, rate = 44_100), input), run(p, input))

        val q = processor(settings, rate = 44_100)
        run(q, sine(80.0, 0.5, 4_800, 44_100))
        q.configure(AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        q.flush(StreamMetadata.DEFAULT)
        val mono = sine(80.0, 0.5, 4_800, 44_100, channels = 1)
        assertArrayEquals(
            "a new channel count is as a new processor",
            run(processor(settings, rate = 44_100, channels = 1), mono, channels = 1),
            run(q, mono, channels = 1),
        )
    }

    @Test
    fun `a choice changed before a same-format flush arrives through the glide, not a step`() {
        val p = processor(EqualizerSettings(enabled = false))
        val tone = sine(1_000.0, 0.4, 4_800, 48_000)
        run(p, tone)
        p.setSettings(with(5 to 6f))
        p.flush(StreamMetadata.DEFAULT)
        val after = run(p, tone)
        // The choice took effect (the tone is louder than the source's own level after the glide)...
        assertTrue(rms(after, after.size / 2) != rms(tone, tone.size / 2))
        // ...and the first sample is the source's, not a jump to the new curve: a glide begins from where the filters were.
        assertEquals(tone[0].toInt(), after[0].toInt())
    }

    @Test
    fun `a new sample rate recompiles the curve for that rate`() {
        val settings = with(7 to 6f)
        assertNotEquals(
            EqualizerMath.coefficients(settings, 44_100).toList(),
            EqualizerMath.coefficients(settings, 96_000).toList(),
        )
        val p = GraphicEqualizerProcessor()
        p.setSettings(settings)
        for (rate in listOf(44_100, 96_000, 22_050)) {
            p.configure(AudioFormat(rate, stereo, C.ENCODING_PCM_16BIT))
            p.flush(StreamMetadata.DEFAULT)
            val input = sine(4_000.0, 0.25, rate, rate)
            val output = run(p, input)
            val gain = 20.0 * log10(rms(output, output.size / 2) / rms(input, input.size / 2))
            // 6 dB of boost at 4 kHz minus the headroom is about zero at every rate; stale coefficients would be off by several dB.
            assertEquals("at $rate Hz", 0.0, gain, 0.3)
        }
    }

    @Test
    fun `a change of curve glides, with no step in the signal`() {
        val rate = 48_000
        val p = processor(EqualizerSettings(enabled = false), rate)
        val all = sine(1_000.0, 0.5, rate / 2, rate)
        val half = all.size / 2
        val before = run(p, all.copyOfRange(0, half))
        p.setSettings(with(5 to 12f, 4 to 12f, 6 to 12f, 0 to 12f))
        val after = run(p, all.copyOfRange(half, all.size))
        val out = before + after
        var largest = 0
        for (i in 2 until out.size) largest = maxOf(largest, abs(out[i].toInt() - out[i - 2].toInt()))
        // A 0.5-amplitude 1 kHz tone moves at most about 2,140 per sample; a hard switch of the preamp alone would jump by 12,000.
        assertTrue("largest step $largest", largest < 3_500)
    }

    @Test
    fun `turning it off glides to the byte copy`() {
        val p = processor(with(5 to 6f))
        run(p, sine(1_000.0, 0.5, 9_600, 48_000))
        p.setSettings(EqualizerSettings(enabled = false))
        run(p, sine(1_000.0, 0.5, 2_400, 48_000))
        val noise = ShortArray(6_000) { i -> ((i * 7_919) % 20_000 - 10_000).toShort() }
        assertArrayEquals(noise, run(p, noise))
    }

    @Test
    fun `a format it cannot work on leaves the processor inactive and a 16-bit one active even when flat`() {
        val p = GraphicEqualizerProcessor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)))
        assertFalse(p.isActive)
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT)))
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 9, C.ENCODING_PCM_16BIT)))
        val format = AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, p.configure(format))
        assertTrue(p.isActive)
    }

    @Test
    fun `mono and stereo streams of the same tone agree`() {
        val settings = with(5 to 6f)
        val mono = run(processor(settings, channels = 1), sine(1_000.0, 0.25, 48_000, 48_000, channels = 1), channels = 1)
        val stereoOut = run(processor(settings), sine(1_000.0, 0.25, 48_000, 48_000))
        for (i in 24_000 until 48_000 step 97) {
            assertEquals(mono[i].toInt(), stereoOut[i * 2].toInt())
            assertEquals(mono[i].toInt(), stereoOut[i * 2 + 1].toInt())
        }
    }

    @Test
    fun `a peaking bands gain at its centre is the gain that was set, and the soft clip leaves quiet samples alone`() {
        val settings = with(3 to 9f)
        val coefficients = EqualizerMath.coefficients(settings, 48_000)
        assertEquals(9.0, EqualizerMath.responseDb(coefficients, 48_000, 250.0), 0.01)
        assertEquals(0.0, EqualizerMath.responseDb(EqualizerMath.identity(), 48_000, 1_000.0), 0.0)
        assertEquals(0.5, GraphicEqualizerProcessor.softClip(0.5), 0.0)
        assertEquals(-0.9, GraphicEqualizerProcessor.softClip(-0.9), 0.0)
        assertTrue(GraphicEqualizerProcessor.softClip(3.0) <= 1.0)
        assertTrue(GraphicEqualizerProcessor.softClip(-3.0) >= -1.0)
    }
}
