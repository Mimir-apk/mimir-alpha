package com.gruning.mimir.rewrite.core.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Your music's equalizer, as a stage of the player's own audio pipeline (the Media3 sink runs it between the decoder and the
 * system mixer): ten peaking bands, a bass and a treble shelf ([EqualizerMath]), an automatic preamp that is the loudest boost
 * turned down, and a soft clip at the end. It is device independent and adds no latency. It never changes the encoding, because
 * the sink reads a processor's activity only when it flushes.
 *
 * **Two instances, two encodings.** An instance works on one [encoding]: 16-bit PCM in, double-precision filtering, 16-bit PCM
 * out (the sink's own chain, `YourMusicEqualizer.processor`), or float PCM in and out (the float output's stage,
 * `YourMusicEqualizer.floatStage`, which `FloatEqualizerOutput` drives, because the sink does not run its chain for float output).
 * The filters, the glide and the preamp are the same numbers; the float path differs in its sample format and its clip
 * ([softClipFloat]: float has headroom above full scale, so nothing within range is touched). Neither instance accepts the
 * other's encoding, so the equalizer is never applied twice to one stream.
 *
 * **Always active.** It stays in the pipeline for every stream of its encoding and decides inside whether to do anything: flat or
 * off is a byte copy, so the bits are the source's. Any other format (the other encoding, 24-bit, more than [MaxChannels]
 * channels) leaves the processor inactive, which the sink skips.
 *
 * **Settings.** [setSettings] may be called from any thread. It compiles the choice for the sample rate in use into an immutable
 * snapshot and publishes it with a volatile write; the audio thread picks it up at the next buffer boundary and glides the
 * coefficients and the preamp to it over [GlideMs], so a slider drag is no zipper and a toggle is no click. A glide to a flat
 * choice ends in the byte copy. A new sample rate (a track of another rate, [onConfigure]) recompiles; a flush ([onFlush]) of a
 * different format (rate or channel count) clears the filters' memory and starts at the latest choice, while a flush of the same
 * format (a gapless join, a seek) keeps the memory and the glide, so a join is not a click.
 *
 * **The audio thread allocates nothing** after the first buffer: the arrays are sized once and the output buffer is reused.
 */
@OptIn(UnstableApi::class)
internal class GraphicEqualizerProcessor(private val encoding: Int = C.ENCODING_PCM_16BIT) : BaseAudioProcessor() {
    init {
        require(encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) { "unsupported encoding $encoding" }
    }

    private val isFloat = encoding == C.ENCODING_PCM_FLOAT
    private val bytesPerSample = if (isFloat) 4 else 2

    /** What the audio thread reads: the coefficients for one sample rate, the preamp, and whether the choice changes nothing. */
    private class Snapshot(val sampleRateHz: Int, val coefficients: DoubleArray, val preamp: Double, val flat: Boolean)

    private val lock = Any()
    private var settings = EqualizerSettings()
    private var configuredRateHz = DefaultRateHz

    @Volatile
    private var target: Snapshot = compile(settings, DefaultRateHz)

    // --- Audio-thread state ---------------------------------------------------------------------------------------------
    private var applied: Snapshot? = null
    private var channels = 0
    private var rateHz = 0
    private var bypass = true
    private val current = EqualizerMath.identity()
    private val step = DoubleArray(EqualizerMath.SectionCount * EqualizerMath.CoefficientsPerSection)
    private var currentPreamp = 1.0
    private var stepPreamp = 0.0
    private var glideLeft = 0

    /** Transposed direct form II memory, two numbers per section per channel. */
    private val memory = DoubleArray(EqualizerMath.SectionCount * MaxChannels * 2)

    fun setSettings(next: EqualizerSettings) {
        synchronized(lock) {
            settings = next
            target = compile(next, configuredRateHz)
        }
    }

    override fun onConfigure(format: AudioFormat): AudioFormat {
        if (format.encoding != encoding || format.channelCount !in 1..MaxChannels || format.sampleRate <= 0) {
            return AudioFormat.NOT_SET
        }
        synchronized(lock) {
            configuredRateHz = format.sampleRate
            target = compile(settings, configuredRateHz)
        }
        return format
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        val nextChannels = inputAudioFormat.channelCount
        val nextRateHz = inputAudioFormat.sampleRate
        val sameFormat = applied != null && nextChannels == channels && nextRateHz == rateHz
        channels = nextChannels
        rateHz = nextRateHz
        // Media3 flushes the pipeline at every item transition, a gapless join of the same format included. The next track
        // continues the signal there, so the filters keep their memory, coefficients and glide: wiping them mid-waveform is a
        // click that rings for milliseconds. A changed choice is picked up by the next buffer through the glide, not a hard swap.
        // Only a format that changed (a new rate or channel count) starts the filters afresh.
        if (sameFormat) return
        adoptNow(target)
    }

    override fun onReset() {
        applied = null
        channels = 0
        rateHz = 0
        bypass = true
        glideLeft = 0
    }

    /**
     * Starts the stream afresh at the latest choice: the filters' memory is cleared and no glide is in flight. For a new output
     * after a seek, which has nothing of the old signal to continue. Call it after [configure] and [flush].
     */
    fun restart() {
        if (rateHz > 0) adoptNow(target)
    }

    /**
     * Whether the next buffer would come out as the bytes it went in as: the filters are at rest and the choice is flat or off.
     * Audio thread only. The float output then hands the sink's own buffer on without a copy.
     */
    fun canPassThrough(): Boolean = bypass && target.flat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val wanted = target
        if (wanted !== applied && wanted.sampleRateHz == rateHz) beginGlide(wanted)
        val output = replaceOutputBuffer(remaining)
        if (bypass) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val frames = remaining / (channels * bytesPerSample)
        if (isFloat) processFloat(inputBuffer, output, frames) else process(inputBuffer, output, frames)
        // A part of a frame at the end (a decoder that cut one) passes through unchanged.
        while (inputBuffer.hasRemaining()) output.put(inputBuffer.get())
        output.flip()
        val goal = applied
        if (glideLeft == 0 && goal != null && goal.flat) {
            bypass = true
            java.util.Arrays.fill(memory, 0.0)
        }
    }

    private fun adoptNow(snapshot: Snapshot) {
        applied = snapshot
        java.util.Arrays.fill(memory, 0.0)
        glideLeft = 0
        if (snapshot.flat || snapshot.sampleRateHz != rateHz) {
            bypass = true
            System.arraycopy(IdentityCoefficients, 0, current, 0, current.size)
            currentPreamp = 1.0
        } else {
            bypass = false
            System.arraycopy(snapshot.coefficients, 0, current, 0, current.size)
            currentPreamp = snapshot.preamp
        }
    }

    private fun beginGlide(snapshot: Snapshot) {
        applied = snapshot
        if (bypass) {
            if (snapshot.flat) return
            // Coming out of the byte copy: the filters are the identity with no memory, which is exactly where they start.
            bypass = false
            java.util.Arrays.fill(memory, 0.0)
            System.arraycopy(IdentityCoefficients, 0, current, 0, current.size)
            currentPreamp = 1.0
        }
        val goalCoefficients = if (snapshot.flat) IdentityCoefficients else snapshot.coefficients
        val goalPreamp = if (snapshot.flat) 1.0 else snapshot.preamp
        val length = (rateHz.toLong() * GlideMs / 1_000L).toInt().coerceAtLeast(1)
        for (i in step.indices) step[i] = (goalCoefficients[i] - current[i]) / length
        stepPreamp = (goalPreamp - currentPreamp) / length
        glideLeft = length
    }

    private fun process(input: ByteBuffer, output: ByteBuffer, frames: Int) {
        val coefficients = current
        val memory = memory
        val base = input.position()
        val channelCount = channels
        var glide = glideLeft
        var preamp = currentPreamp
        val goal = applied
        var index = 0
        for (frame in 0 until frames) {
            if (glide > 0) {
                glide -= 1
                if (glide == 0 && goal != null) {
                    val goalCoefficients = if (goal.flat) IdentityCoefficients else goal.coefficients
                    System.arraycopy(goalCoefficients, 0, coefficients, 0, coefficients.size)
                    preamp = if (goal.flat) 1.0 else goal.preamp
                } else {
                    for (i in coefficients.indices) coefficients[i] += step[i]
                    preamp += stepPreamp
                }
            }
            for (channel in 0 until channelCount) {
                var x = input.getShort(base + index * 2) * (preamp / SampleScale)
                var memoryAt = channel * 2
                var at = 0
                for (section in 0 until EqualizerMath.SectionCount) {
                    val y = coefficients[at] * x + memory[memoryAt]
                    memory[memoryAt] = coefficients[at + 1] * x - coefficients[at + 3] * y + memory[memoryAt + 1]
                    memory[memoryAt + 1] = coefficients[at + 2] * x - coefficients[at + 4] * y
                    x = y
                    at += EqualizerMath.CoefficientsPerSection
                    memoryAt += MaxChannels * 2
                }
                output.putShort(index * 2, toPcm(softClip(x)))
                index += 1
            }
        }
        glideLeft = glide
        currentPreamp = preamp
        input.position(base + index * 2)
        output.position(index * 2)
    }

    /** [process] on 32-bit float samples: the same filters, glide and preamp, the float clip, and a sample that is no number as silence. */
    private fun processFloat(input: ByteBuffer, output: ByteBuffer, frames: Int) {
        val coefficients = current
        val memory = memory
        val base = input.position()
        val channelCount = channels
        var glide = glideLeft
        var preamp = currentPreamp
        val goal = applied
        var index = 0
        for (frame in 0 until frames) {
            if (glide > 0) {
                glide -= 1
                if (glide == 0 && goal != null) {
                    val goalCoefficients = if (goal.flat) IdentityCoefficients else goal.coefficients
                    System.arraycopy(goalCoefficients, 0, coefficients, 0, coefficients.size)
                    preamp = if (goal.flat) 1.0 else goal.preamp
                } else {
                    for (i in coefficients.indices) coefficients[i] += step[i]
                    preamp += stepPreamp
                }
            }
            for (channel in 0 until channelCount) {
                val sample = input.getFloat(base + index * 4)
                // A NaN or an infinity would stay in the filters' memory for good: it is dropped as silence.
                var x = if (sample.isFinite()) sample * preamp else 0.0
                var memoryAt = channel * 2
                var at = 0
                for (section in 0 until EqualizerMath.SectionCount) {
                    val y = coefficients[at] * x + memory[memoryAt]
                    memory[memoryAt] = coefficients[at + 1] * x - coefficients[at + 3] * y + memory[memoryAt + 1]
                    memory[memoryAt + 1] = coefficients[at + 2] * x - coefficients[at + 4] * y
                    x = y
                    at += EqualizerMath.CoefficientsPerSection
                    memoryAt += MaxChannels * 2
                }
                output.putFloat(index * 4, softClipFloat(x).toFloat())
                index += 1
            }
        }
        glideLeft = glide
        currentPreamp = preamp
        input.position(base + index * 4)
        output.position(index * 4)
    }

    companion object {
        const val MaxChannels = 8

        /** The time a change of the curve takes to arrive. Long enough that a step in gain is no click. */
        const val GlideMs = 20

        private const val DefaultRateHz = 48_000
        private const val SampleScale = 32_768.0

        /** Below this magnitude the clip leaves the sample alone; above it the knee rounds off to full scale. */
        private const val ClipKnee = 0.9

        /** The float clip's width: it leaves everything up to full scale alone, then rounds off over this much above it. */
        private const val FloatClipWidth = 0.1

        private val IdentityCoefficients = EqualizerMath.identity()

        private fun compile(settings: EqualizerSettings, rateHz: Int): Snapshot {
            val flat = !settings.enabled || settings.isFlat
            if (flat) return Snapshot(rateHz, IdentityCoefficients, 1.0, flat = true)
            val coefficients = EqualizerMath.coefficients(settings, rateHz)
            val preamp = EqualizerMath.preampLinear(EqualizerMath.maxBoostDb(coefficients, rateHz))
            return Snapshot(rateHz, coefficients, preamp, flat = false)
        }

        /** The final soft clip: unchanged to [ClipKnee], then a tanh knee that reaches full scale only asymptotically. */
        fun softClip(x: Double): Double {
            val magnitude = abs(x)
            if (magnitude <= ClipKnee) return x
            val clipped = ClipKnee + (1.0 - ClipKnee) * tanh((magnitude - ClipKnee) / (1.0 - ClipKnee))
            return if (x < 0.0) -clipped else clipped
        }

        /**
         * The final soft clip of the float path: the same tanh knee as [softClip], moved up to full scale. A float stream has no
         * ceiling to stay under, so a sample within [-1, 1] comes back exactly as it went in, and only an over (a hot source, a
         * boost the preamp could not cover) is rounded off, to at most [FloatClipWidth] above full scale. The system mixer
         * clamps the sum at the end.
         */
        fun softClipFloat(x: Double): Double {
            val magnitude = abs(x)
            if (magnitude <= 1.0) return x
            val clipped = 1.0 + FloatClipWidth * tanh((magnitude - 1.0) / FloatClipWidth)
            return if (x < 0.0) -clipped else clipped
        }

        private fun toPcm(x: Double): Short = Math.rint(x * SampleScale).coerceIn(-32_768.0, 32_767.0).toInt().toShort()
    }
}
