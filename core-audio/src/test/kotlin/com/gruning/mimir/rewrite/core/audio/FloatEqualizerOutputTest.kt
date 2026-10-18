package com.gruning.mimir.rewrite.core.audio

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** The float equalizer's AudioOutput, against a fake that takes as much of each write as it is told to. */
class FloatEqualizerOutputTest {
    private val rate = 48_000
    private val stereoMask = 0x0C

    /** Takes at most [bytesPerWrite] of each buffer and keeps them, like a full AudioTrack buffer would. */
    private class RecordingOutput(var bytesPerWrite: Int = Int.MAX_VALUE) : AudioOutput {
        val written = ByteArrayOutputStream()
        var lastBuffer: ByteBuffer? = null
        var writes = 0
        var flushes = 0
        var releases = 0

        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            lastBuffer = buffer
            writes += 1
            val taken = ByteArray(minOf(buffer.remaining(), bytesPerWrite))
            buffer.get(taken)
            written.write(taken)
            return !buffer.hasRemaining()
        }

        override fun flush() {
            flushes += 1
        }

        override fun release() {
            releases += 1
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun isOffloadedPlayback() = false
        override fun getAudioSessionId() = C.AUDIO_SESSION_ID_UNSET
        override fun getSampleRate() = 48_000
        override fun getBufferSizeInFrames() = 0L
        override fun getPositionUs() = 0L
        override fun getPlaybackParameters(): PlaybackParameters = PlaybackParameters.DEFAULT
        override fun isStalled() = false
        override fun addListener(listener: AudioOutput.Listener) = Unit
        override fun removeListener(listener: AudioOutput.Listener) = Unit
        override fun setPlaybackParameters(playbackParams: PlaybackParameters) = Unit
        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
        override fun setOffloadEndOfStream() = Unit
        override fun attachAuxEffect(effectId: Int) = Unit
        override fun setAuxEffectSendLevel(level: Float) = Unit
        override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
    }

    private class FakeProvider(val output: AudioOutput) : AudioOutputProvider {
        override fun getFormatSupport(formatConfig: AudioOutputProvider.FormatConfig) = AudioOutputProvider.FormatSupport.UNSUPPORTED
        override fun getOutputConfig(formatConfig: AudioOutputProvider.FormatConfig): AudioOutputProvider.OutputConfig =
            throw UnsupportedOperationException()
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = output
        override fun addListener(listener: AudioOutputProvider.Listener) = Unit
        override fun removeListener(listener: AudioOutputProvider.Listener) = Unit
        override fun release() = Unit
    }

    private fun with(vararg bands: Pair<Int, Float>): EqualizerSettings {
        var s = EqualizerSettings(enabled = true)
        bands.forEach { (i, db) -> s = s.withBand(i, db) }
        return s
    }

    private fun stage(settings: EqualizerSettings): GraphicEqualizerProcessor =
        GraphicEqualizerProcessor(C.ENCODING_PCM_FLOAT).also { it.setSettings(settings) }

    private fun floatBuffer(samples: FloatArray): ByteBuffer {
        val b = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        samples.forEach { b.putFloat(it) }
        b.flip()
        return b
    }

    private fun bytesOf(buffer: ByteBuffer): ByteArray = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }

    private fun tone(frames: Int, freq: Double = 1_000.0, amplitude: Double = 0.3, from: Int = 0): FloatArray =
        FloatArray(frames * 2) { (amplitude * sin(2.0 * PI * freq * (from + it / 2) / rate)).toFloat() }

    /** What the stage alone makes of [chunks], fed in order to a new processor: the signal a perfect output would receive. */
    private fun expected(settings: EqualizerSettings, vararg chunks: FloatArray): ByteArray {
        val p = stage(settings)
        p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        p.flush(StreamMetadata.DEFAULT)
        p.restart()
        val all = ByteArrayOutputStream()
        for (chunk in chunks) {
            p.queueInput(floatBuffer(chunk))
            all.write(bytesOf(p.output))
        }
        return all.toByteArray()
    }

    private fun newOutput(fake: RecordingOutput, settings: EqualizerSettings): FloatEqualizerOutput =
        FloatEqualizerOutput(fake, stage(settings), channels = 2, sampleRateHz = rate)

    /** Writes [buffer] the way the sink does, again and again with the same buffer until it is taken whole. */
    private fun writeAll(output: AudioOutput, buffer: ByteBuffer): Int {
        var calls = 0
        while (!output.write(buffer, 1, 0L)) {
            calls += 1
            assertTrue("never taken", calls < 10_000)
        }
        return calls + 1
    }

    @Test
    fun `a flat curve hands the sink's own buffer on, untouched`() {
        for (settings in listOf(EqualizerSettings(enabled = false), EqualizerSettings(enabled = true))) {
            val fake = RecordingOutput()
            val output = newOutput(fake, settings)
            // 24-bit values in a float, the extremes among them.
            val samples = FloatArray(4_000) { ((it * 8_191 - 8_388_608) % 8_388_608) / 8_388_608f }
            samples[0] = -1f
            samples[1] = 8_388_607 / 8_388_608f
            val input = floatBuffer(samples)
            val before = bytesOf(input)
            assertTrue(output.write(input, 1, 0L))
            assertSame("no copy", input, fake.lastBuffer)
            assertArrayEquals(before, fake.written.toByteArray())
            assertFalse(input.hasRemaining())
        }
    }

    @Test
    fun `a partial write resumes with the same filtered samples, each filtered once`() {
        val settings = with(5 to 6f, 0 to -4f).withBass(3f)
        val fake = RecordingOutput(bytesPerWrite = 2_000)
        val output = newOutput(fake, settings)
        val input = floatBuffer(tone(3_000))
        val start = input.limit()
        val calls = writeAll(output, input)
        assertTrue("took several writes ($calls)", calls > 3)
        assertEquals("the sink's buffer is consumed whole", start, input.position())
        assertNotSame("the filtered bytes were written, not the sink's own", input, fake.lastBuffer)
        assertArrayEquals(expected(settings, tone(3_000)), fake.written.toByteArray())
    }

    @Test
    fun `the sink's buffer is never written to, and its position is only moved by what the output took`() {
        val settings = with(5 to 6f)
        val fake = RecordingOutput(bytesPerWrite = 1_000)
        val output = newOutput(fake, settings)
        val input = floatBuffer(tone(500))
        val before = bytesOf(input)
        assertFalse(output.write(input, 1, 0L))
        assertEquals(1_000, input.position())
        assertFalse(output.write(input, 1, 0L))
        assertEquals(2_000, input.position())
        fake.bytesPerWrite = Int.MAX_VALUE
        assertTrue(output.write(input, 1, 0L))
        assertEquals(input.limit(), input.position())
        input.rewind()
        assertArrayEquals("read-only", before, bytesOf(input))
    }

    @Test
    fun `the same buffer object refilled after a complete write is filtered again, and the signal carries on across the join`() {
        val settings = with(5 to 6f).withBass(4f)
        val fake = RecordingOutput(bytesPerWrite = 3_000)
        val output = newOutput(fake, settings)
        val first = tone(2_000)
        val second = tone(2_000, from = 2_000)
        val buffer = ByteBuffer.allocateDirect(first.size * 4).order(ByteOrder.nativeOrder())
        for (chunk in listOf(first, second)) {
            buffer.clear()
            chunk.forEach { buffer.putFloat(it) }
            buffer.flip()
            writeAll(output, buffer)
        }
        assertArrayEquals(expected(settings, first, second), fake.written.toByteArray())
    }

    @Test
    fun `a flush drops the buffer in flight and starts the filters clean`() {
        val settings = with(5 to 6f).withBass(4f)
        val fake = RecordingOutput(bytesPerWrite = 1_000)
        val output = newOutput(fake, settings)
        val old = floatBuffer(tone(2_000))
        assertFalse(output.write(old, 1, 0L))
        output.flush()
        assertEquals(1, fake.flushes)
        fake.bytesPerWrite = Int.MAX_VALUE
        val fresh = tone(1_500, freq = 440.0, from = 777)
        assertTrue(output.write(floatBuffer(fresh), 1, 0L))
        val all = fake.written.toByteArray()
        val tail = all.copyOfRange(1_000, all.size)
        assertArrayEquals("what the old buffer had left was not sent, and the new signal starts from a clean state", expected(settings, fresh), tail)
        output.release()
        assertEquals(1, fake.releases)
    }

    @Test
    fun `turning the curve off glides out and then the sink's buffer goes on untouched again`() {
        val fake = RecordingOutput()
        val stage = stage(with(5 to 6f))
        val output = FloatEqualizerOutput(fake, stage, channels = 2, sampleRateHz = rate)
        assertTrue(output.write(floatBuffer(tone(2_000)), 1, 0L))
        assertTrue(fake.lastBuffer != null)
        stage.setSettings(EqualizerSettings(enabled = false))
        // The glide takes 20 ms (960 frames): those buffers are filtered, then the filters are at rest.
        repeat(3) { assertTrue(output.write(floatBuffer(tone(1_000, from = 2_000 + it * 1_000)), 1, 0L)) }
        val clean = floatBuffer(tone(1_000, from = 5_000))
        assertTrue(output.write(clean, 1, 0L))
        assertSame(clean, fake.lastBuffer)
    }

    @Test
    fun `only a float output that mixes is wrapped`() {
        val fake = RecordingOutput()
        val provider = FloatEqualizerOutputProvider(FakeProvider(fake), stage(with(5 to 6f)))
        fun config(encoding: Int, offload: Boolean = false, tunneling: Boolean = false) = AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(encoding)
            .setSampleRate(rate)
            .setChannelMask(stereoMask)
            .setIsOffload(offload)
            .setIsTunneling(tunneling)
            .build()

        assertSame("16-bit keeps the chain's processor", fake, provider.getAudioOutput(config(C.ENCODING_PCM_16BIT)))
        assertSame("offload", fake, provider.getAudioOutput(config(C.ENCODING_PCM_FLOAT, offload = true)))
        assertSame("tunnelling", fake, provider.getAudioOutput(config(C.ENCODING_PCM_FLOAT, tunneling = true)))
        val wrapped = provider.getAudioOutput(config(C.ENCODING_PCM_FLOAT))
        assertNotSame(fake, wrapped)
        assertTrue(wrapped is FloatEqualizerOutput)
    }

    @Test
    fun `a stream the stage cannot work on is written as it is`() {
        val fake = RecordingOutput()
        val provider = FloatEqualizerOutputProvider(FakeProvider(fake), stage(with(5 to 6f)))
        val config = AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(C.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(0x1FF) // nine channels
            .build()
        val output = provider.getAudioOutput(config)
        val input = floatBuffer(FloatArray(9 * 100) { it / 1_000f })
        val before = bytesOf(input)
        assertTrue(output.write(input, 1, 0L))
        assertSame(input, fake.lastBuffer)
        assertArrayEquals(before, fake.written.toByteArray())
    }

    @Test
    fun `float output is used only when the platform answers a positive buffer size for float`() {
        assertTrue(FloatOutputPlan.useFloat { 7_680 })
        assertTrue(FloatOutputPlan.useFloat { 1 })
        // AudioTrack.ERROR_BAD_VALUE, AudioTrack.ERROR, and a size of nothing.
        assertFalse(FloatOutputPlan.useFloat { -2 })
        assertFalse(FloatOutputPlan.useFloat { -1 })
        assertFalse(FloatOutputPlan.useFloat { 0 })
        assertFalse(FloatOutputPlan.useFloat { throw IllegalStateException("no audio service") })
        assertFalse(FloatOutputPlan.useFloat { throw UnsupportedOperationException() })
    }
}
