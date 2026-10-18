package com.gruning.mimir.rewrite.core.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equalizer on float PCM (what a 24-bit file plays as): it must give the 16-bit processor's response, keep a flat curve
 * bit-transparent, keep 24-bit values exact, and leave an in-range sample alone.
 */
class FloatEqualizerProcessorTest {
    private val stereo = 2

    private fun floatProcessor(settings: EqualizerSettings, rate: Int = 48_000, channels: Int = stereo): GraphicEqualizerProcessor {
        val p = GraphicEqualizerProcessor(C.ENCODING_PCM_FLOAT)
        p.setSettings(settings)
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        p.flush(StreamMetadata.DEFAULT)
        return p
    }

    private fun shortProcessor(settings: EqualizerSettings, rate: Int = 48_000, channels: Int = stereo): GraphicEqualizerProcessor {
        val p = GraphicEqualizerProcessor()
        p.setSettings(settings)
        p.configure(AudioFormat(rate, channels, C.ENCODING_PCM_16BIT))
        p.flush(StreamMetadata.DEFAULT)
        return p
    }

    private fun with(vararg bands: Pair<Int, Float>): EqualizerSettings {
        var s = EqualizerSettings(enabled = true)
        bands.forEach { (i, db) -> s = s.withBand(i, db) }
        return s
    }

    /** A sine of [amplitude] of full scale, as 16-bit samples (so the float copy of them is exact). */
    private fun sine16(freq: Double, amplitude: Double, frames: Int, rate: Int, channels: Int = stereo): ShortArray =
        ShortArray(frames * channels) { i ->
            Math.rint(amplitude * 32_767.0 * sin(2.0 * PI * freq * (i / channels) / rate)).toInt().toShort()
        }

    private fun floatsOf(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it] / 32_768f }

    private fun shortBuffer(samples: ShortArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { b.putShort(it) }
        b.flip()
        return b
    }

    private fun floatBuffer(samples: FloatArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { b.putFloat(it) }
        b.flip()
        return b
    }

    private fun run16(p: GraphicEqualizerProcessor, samples: ShortArray, channels: Int = stereo, chunkFrames: Int = 1_000): ShortArray {
        val out = ArrayList<Short>(samples.size)
        var at = 0
        while (at < samples.size) {
            val end = minOf(samples.size, at + chunkFrames * channels)
            p.queueInput(shortBuffer(samples.copyOfRange(at, end)))
            val shorts = p.output.order(ByteOrder.nativeOrder()).asShortBuffer()
            while (shorts.hasRemaining()) out += shorts.get()
            at = end
        }
        return out.toShortArray()
    }

    private fun runFloat(p: GraphicEqualizerProcessor, samples: FloatArray, channels: Int = stereo, chunkFrames: Int = 1_000): FloatArray {
        val out = ArrayList<Float>(samples.size)
        var at = 0
        while (at < samples.size) {
            val end = minOf(samples.size, at + chunkFrames * channels)
            p.queueInput(floatBuffer(samples.copyOfRange(at, end)))
            val floats = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
            while (floats.hasRemaining()) out += floats.get()
            at = end
        }
        return out.toFloatArray()
    }

    private fun rms(samples: FloatArray, from: Int): Double {
        var sum = 0.0
        for (i in from until samples.size) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (samples.size - from))
    }

    private fun gainDb(input: FloatArray, output: FloatArray): Double = 20.0 * log10(rms(output, output.size / 2) / rms(input, input.size / 2))

    private fun rawBits(samples: FloatArray): IntArray = IntArray(samples.size) { java.lang.Float.floatToRawIntBits(samples[it]) }

    /** The most a float output is off the 16-bit output, in 16-bit steps. */
    private fun largestDifferenceInSteps(floats: FloatArray, shorts: ShortArray): Double {
        assertEquals(shorts.size, floats.size)
        var largest = 0.0
        for (i in shorts.indices) largest = maxOf(largest, abs(floats[i] * 32_768.0 - shorts[i]))
        return largest
    }

    @Test
    fun `each band and both shelves give the float path the response of the 16-bit path`() {
        val rate = 48_000
        val cases = ArrayList<Pair<String, Pair<EqualizerSettings, Double>>>()
        for (band in 0 until Equalizer.BandCount) {
            cases += "band $band" to (with(band to 6f) to Equalizer.BandFrequenciesHz[band].toDouble())
        }
        cases += "bass shelf" to (EqualizerSettings(enabled = true).withBass(6f) to 40.0)
        cases += "treble shelf" to (EqualizerSettings(enabled = true).withTreble(6f) to 16_000.0)
        for ((name, case) in cases) {
            val (settings, freq) = case
            val tone16 = sine16(freq, 0.25, rate, rate)
            val toneFloat = floatsOf(tone16)
            val out16 = run16(shortProcessor(settings), tone16)
            val outFloat = runFloat(floatProcessor(settings), toneFloat)
            val gain16 = 20.0 * log10(
                sqrt(out16.drop(out16.size / 2).sumOf { it.toDouble() * it } / (out16.size - out16.size / 2)) /
                    sqrt(tone16.drop(tone16.size / 2).sumOf { it.toDouble() * it } / (tone16.size - tone16.size / 2)),
            )
            assertEquals("$name: the float gain is the 16-bit gain", gain16, gainDb(toneFloat, outFloat), 0.01)
            // The same arithmetic, so apart from the 16-bit path's rounding to a step the two are one signal.
            assertTrue("$name: sample for sample", largestDifferenceInSteps(outFloat, out16) <= 0.51)
            // And the curve is really applied: the tone stands at the boost minus the headroom (a lone band is about 0 dB at its own
            // centre; the far tones are checked against the headroom in the 16-bit tests, and this path equals that one).
            val headroom = settings.effectiveHeadroomDb.toDouble()
            assertEquals("$name: boost minus headroom", 6.0 - headroom, gainDb(toneFloat, outFloat), 1.0)
        }
        // A tone far from the boost falls by the headroom, which a passthrough could not do.
        val boosted = with(5 to 6f)
        val far = floatsOf(sine16(16_000.0, 0.25, rate, rate))
        assertEquals(-boosted.effectiveHeadroomDb.toDouble(), gainDb(far, runFloat(floatProcessor(boosted), far)), 0.6)
    }

    @Test
    fun `a cut is the same in float and a mixed curve on noise stays a step apart`() {
        val settings = with(3 to 6f, 8 to -6f, 0 to 4f).withBass(5f).withTreble(-4f)
        val noise16 = ShortArray(48_000) { i -> (((i * 2_654_435_761L) ushr 9).toInt() % 6_000).toShort() }
        val outFloat = runFloat(floatProcessor(settings), floatsOf(noise16))
        val out16 = run16(shortProcessor(settings), noise16)
        assertTrue(largestDifferenceInSteps(outFloat, out16) <= 0.51)
    }

    @Test
    fun `a change of curve glides in float exactly as it does in 16 bits`() {
        val rate = 48_000
        val tone16 = sine16(1_000.0, 0.25, rate / 2, rate)
        val half = tone16.size / 2
        val boosted = with(5 to 12f, 4 to 12f, 6 to 12f, 0 to 12f)

        val p16 = shortProcessor(EqualizerSettings(enabled = false), rate)
        val before16 = run16(p16, tone16.copyOfRange(0, half))
        p16.setSettings(boosted)
        val after16 = run16(p16, tone16.copyOfRange(half, tone16.size))

        val pFloat = floatProcessor(EqualizerSettings(enabled = false), rate)
        val beforeFloat = runFloat(pFloat, floatsOf(tone16.copyOfRange(0, half)))
        pFloat.setSettings(boosted)
        val afterFloat = runFloat(pFloat, floatsOf(tone16.copyOfRange(half, tone16.size)))

        assertTrue(largestDifferenceInSteps(beforeFloat + afterFloat, before16 + after16) <= 0.51)
        // The curve did arrive (the tail is not the source's level), and there is no step on the way.
        val all = beforeFloat + afterFloat
        assertTrue(rms(afterFloat, afterFloat.size / 2) != rms(floatsOf(tone16.copyOfRange(half, tone16.size)), half / 2))
        var largest = 0.0
        for (i in 2 until all.size) largest = maxOf(largest, abs((all[i] - all[i - 2]).toDouble()))
        assertTrue("largest step $largest", largest < 3_500.0 / 32_768.0)
    }

    @Test
    fun `a flat or off curve is bit-transparent in float, every bit pattern included`() {
        val random = java.util.Random(270)
        val samples = FloatArray(12_000) { java.lang.Float.intBitsToFloat(random.nextInt()) }
        // The edges a float stream can carry: full scale, signed zero, the smallest step, an infinity and a NaN with a payload.
        val edges = floatArrayOf(1f, -1f, 0f, -0f, Float.MIN_VALUE, -Float.MIN_VALUE, Float.POSITIVE_INFINITY, Float.NaN, java.lang.Float.intBitsToFloat(0x7fc12345))
        edges.copyInto(samples)
        listOf(EqualizerSettings(enabled = false), EqualizerSettings(enabled = true), with(5 to 6f).copy(enabled = false)).forEach {
            val p = floatProcessor(it)
            assertTrue(p.canPassThrough())
            assertArrayEquals(rawBits(samples), rawBits(runFloat(p, samples)))
        }
    }

    @Test
    fun `24-bit values go through a flat curve exactly`() {
        val scale = 8_388_608.0 // 2^23
        val values = ArrayList<Int>()
        var n = -8_388_608
        while (n < 8_388_608) {
            values += n
            n += 4_099
        }
        values += listOf(-8_388_608, -8_388_607, -1, 0, 1, 8_388_606, 8_388_607)
        if (values.size % 2 == 1) values += 12_345
        val samples = FloatArray(values.size) { (values[it] / scale).toFloat() }
        // A 24-bit integer is exact in a float: this is the source as a decoder hands it over.
        for (i in samples.indices) assertEquals(values[i].toLong(), Math.rint(samples[i] * scale).toLong())
        for (settings in listOf(EqualizerSettings(enabled = false), EqualizerSettings(enabled = true))) {
            val out = runFloat(floatProcessor(settings), samples)
            assertArrayEquals(rawBits(samples), rawBits(out))
            for (i in out.indices) assertEquals(values[i].toLong(), Math.rint(out[i] * scale).toLong())
        }
        // Turned off after being on, it ends back at the exact copy.
        val p = floatProcessor(with(5 to 6f))
        runFloat(p, FloatArray(9_600) { (0.5 * sin(2.0 * PI * 1_000.0 * (it / 2) / 48_000)).toFloat() })
        p.setSettings(EqualizerSettings(enabled = false))
        runFloat(p, FloatArray(9_600) { (0.5 * sin(2.0 * PI * 1_000.0 * (it / 2) / 48_000)).toFloat() })
        assertTrue(p.canPassThrough())
        assertArrayEquals(rawBits(samples), rawBits(runFloat(p, samples)))
    }

    /** The curve's own arithmetic in a mono double-precision cascade: no clip, no float anywhere but the input. */
    private fun reference(settings: EqualizerSettings, rate: Int, input: FloatArray): DoubleArray {
        val coefficients = EqualizerMath.coefficients(settings, rate)
        val preamp = EqualizerMath.preampLinear(EqualizerMath.maxBoostDb(coefficients, rate))
        val memory = DoubleArray(EqualizerMath.SectionCount * 2)
        return DoubleArray(input.size) { i ->
            var x = input[i] * preamp
            for (section in 0 until EqualizerMath.SectionCount) {
                val at = section * EqualizerMath.CoefficientsPerSection
                val y = coefficients[at] * x + memory[section * 2]
                memory[section * 2] = coefficients[at + 1] * x - coefficients[at + 3] * y + memory[section * 2 + 1]
                memory[section * 2 + 1] = coefficients[at + 2] * x - coefficients[at + 4] * y
                x = y
            }
            x
        }
    }

    @Test
    fun `the float path keeps the precision of the arithmetic and the clip does not fire on a sample in range`() {
        val rate = 48_000
        // A cut needs no headroom, so a loud tone away from the cut comes out as loud as it went in: above the 16-bit path's
        // 0.9 knee but within full scale. Its clip would round these samples off; the float clip must not.
        val cut = with(5 to -6f)
        val loud = FloatArray(rate) { (0.96 * sin(2.0 * PI * 7_000.0 * it / rate)).toFloat() }
        val loudOut = runFloat(floatProcessor(cut, channels = 1), loud, channels = 1)
        val loudWanted = reference(cut, rate, loud)
        var peak = 0.0
        for (i in loud.indices) {
            peak = maxOf(peak, abs(loudOut[i].toDouble()))
            assertEquals("sample $i", loudWanted[i], loudOut[i].toDouble(), 1e-6)
        }
        assertTrue("the tone stays above the 16-bit knee ($peak)", peak > 0.9 && peak <= 1.0)
        assertNotEquals("the 16-bit clip would have moved this peak", peak, GraphicEqualizerProcessor.softClip(peak), 1e-3)

        // A boost, with its preamp: the same, and precise to the float's own rounding.
        val boost = with(5 to 6f, 2 to 4f)
        val tone = FloatArray(rate) { (0.9 * sin(2.0 * PI * 1_000.0 * it / rate)).toFloat() }
        val toneOut = runFloat(floatProcessor(boost, channels = 1), tone, channels = 1)
        val toneWanted = reference(boost, rate, tone)
        for (i in tone.indices) assertEquals("sample $i", toneWanted[i], toneOut[i].toDouble(), 1e-6)
    }

    @Test
    fun `the float soft clip is exact up to full scale and rounds an over off above it`() {
        var x = -1.0
        while (x <= 1.0) {
            assertEquals(x, GraphicEqualizerProcessor.softClipFloat(x), 0.0)
            x += 0.0078125
        }
        assertEquals(0.97, GraphicEqualizerProcessor.softClipFloat(0.97), 0.0)
        assertEquals(-1.0, GraphicEqualizerProcessor.softClipFloat(-1.0), 0.0)
        // Continuous at the knee, rising, odd, and bounded however far over the sample is.
        assertEquals(1.0, GraphicEqualizerProcessor.softClipFloat(1.0 + 1e-9), 1e-8)
        var last = 1.0
        for (over in listOf(1.01, 1.05, 1.1, 1.2, 1.3)) {
            val clipped = GraphicEqualizerProcessor.softClipFloat(over)
            assertTrue("$over -> $clipped", clipped > last && clipped < 1.1 && clipped < over)
            assertEquals(-clipped, GraphicEqualizerProcessor.softClipFloat(-over), 0.0)
            last = clipped
        }
        for (far in listOf(3.0, 100.0, 1e30)) {
            assertEquals(1.1, GraphicEqualizerProcessor.softClipFloat(far), 1e-9)
        }
        // The 16-bit clip is as it was: it already moves 0.95.
        assertNotEquals(0.95, GraphicEqualizerProcessor.softClip(0.95), 1e-4)
    }

    @Test
    fun `a flush of the same format keeps the filters memory in float, so a gapless join is no discontinuity`() {
        val settings = EqualizerSettings(enabled = true).withBass(10f).withBand(2, 8f)
        val input = floatsOf(sine16(80.0, 0.5, 9_600, 48_000))
        val whole = runFloat(floatProcessor(settings), input)
        val p = floatProcessor(settings)
        val half = input.size / 2
        val first = runFloat(p, input.copyOfRange(0, half))
        p.flush(StreamMetadata.DEFAULT)
        val second = runFloat(p, input.copyOfRange(half, input.size))
        assertArrayEquals("the join is the unbroken signal, bit for bit", rawBits(whole), rawBits(first + second))
    }

    @Test
    fun `a restart clears the memory and starts at the latest choice`() {
        val settings = EqualizerSettings(enabled = true).withBass(10f).withBand(2, 8f)
        val tone = floatsOf(sine16(80.0, 0.5, 4_800, 48_000))
        val fresh = runFloat(floatProcessor(settings), tone)
        val used = floatProcessor(settings)
        runFloat(used, floatsOf(sine16(300.0, 0.5, 4_800, 48_000)))
        used.setSettings(with(7 to 6f))
        used.setSettings(settings)
        used.restart()
        assertArrayEquals("as a new processor", rawBits(fresh), rawBits(runFloat(used, tone)))
    }

    @Test
    fun `a sample that is not a number is silence and does not stay in the filters`() {
        val settings = with(5 to 6f)
        val p = floatProcessor(settings, channels = 1)
        val poisoned = FloatArray(2_000) { if (it == 100) Float.NaN else if (it == 101) Float.POSITIVE_INFINITY else 0.1f }
        val out = runFloat(p, poisoned, channels = 1)
        assertTrue(out.all { it.isFinite() })
        assertTrue(runFloat(p, FloatArray(2_000) { 0.1f }, channels = 1).all { it.isFinite() })
    }

    @Test
    fun `each processor takes only its own encoding, so the equalizer is never applied twice`() {
        val floatStage = GraphicEqualizerProcessor(C.ENCODING_PCM_FLOAT)
        assertEquals(AudioFormat.NOT_SET, floatStage.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)))
        assertEquals(AudioFormat.NOT_SET, floatStage.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_24BIT)))
        assertEquals(AudioFormat.NOT_SET, floatStage.configure(AudioFormat(48_000, 9, C.ENCODING_PCM_FLOAT)))
        assertFalse(floatStage.isActive)
        val format = AudioFormat(96_000, 6, C.ENCODING_PCM_FLOAT)
        assertEquals(format, floatStage.configure(format))
        assertTrue(floatStage.isActive)

        val chain = GraphicEqualizerProcessor()
        assertEquals(AudioFormat.NOT_SET, chain.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)))
        assertFalse(chain.isActive)
        try {
            GraphicEqualizerProcessor(C.ENCODING_PCM_24BIT)
            fail("a 24-bit processor does not exist")
        } catch (expected: IllegalArgumentException) {
            // The only encodings are 16-bit and float.
        }
    }

    @Test
    fun `a trailing part of a frame passes through unchanged in float`() {
        val p = floatProcessor(with(5 to 6f))
        val whole = FloatArray(2_000) { 0.1f * ((it % 7) - 3) }
        val buffer = floatBuffer(whole)
        val withTail = ByteBuffer.allocateDirect(buffer.remaining() + 3).order(ByteOrder.nativeOrder())
        withTail.put(buffer)
        withTail.put(byteArrayOf(9, 8, 7))
        withTail.flip()
        p.queueInput(withTail)
        val out = p.output
        assertEquals(whole.size * 4 + 3, out.remaining())
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        assertArrayEquals(byteArrayOf(9, 8, 7), bytes.copyOfRange(bytes.size - 3, bytes.size))
    }
}
