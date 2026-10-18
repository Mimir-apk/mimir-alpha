package com.gruning.mimir.rewrite.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Puts the equalizer in Your music's player and keeps it on the player's choice ([AudioPreferences.equalizer]).
 *
 * The equalizer is two instances of [GraphicEqualizerProcessor], one per output encoding, because Media3's sink runs its processor
 * chain only for the 16-bit path (`DefaultAudioSink.configure`: float output gets the sink's own float converter and no chain):
 * - [floatStage], driven by [FloatEqualizerOutput] on the way to the AudioTrack, for whatever reaches the sink as float or
 *   high-resolution PCM. With float output on, `MediaCodecAudioRenderer` asks EVERY decoder for float, and the c2 MP3, AAC and
 *   FLAC decoders give it, so most files run through it, 16-bit ones included, and a 24-bit file keeps its depth. Float is the
 *   container that keeps 24-bit exact (a float has a 24-bit significand), and it is what the system mixer works in.
 * - [processor], in the sink's chain, for what stays 16-bit PCM: raw 16-bit WAV and AIFF, which have no decoder to ask, and a
 *   decoder that ignores the float request. It is also the whole equalizer where float is off.
 *
 * Float is on where the device plays it ([FloatOutputPlan]) and no AudioTrack has refused it ([FloatFallback]); else the sink is
 * built as it always was and only [processor] is in use.
 *
 * The instances are one each for the process, as the player is. The theme beds play on the platform engine and have no
 * equalizer: it is for Your music.
 */
@OptIn(UnstableApi::class)
internal object YourMusicEqualizer {
    private const val Tag = "MimirYourMusicEq"

    val processor = GraphicEqualizerProcessor()
    val floatStage = GraphicEqualizerProcessor(C.ENCODING_PCM_FLOAT)

    private val failureHandler = CoroutineExceptionHandler { _, error -> Log.e(Tag, "equalizer settings failed", error) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + failureHandler)

    @Volatile
    private var following = false

    /**
     * Whether float output may be used (the platform plays it) and whether the player is built with it: see [FloatFallback]. The
     * player asks [FloatFallback.onPlayerError] about an AudioTrack that would not start, and is rebuilt without float if so.
     */
    val fallback = FloatFallback { probeFloatTrack() }

    /** True when the player was last built with float output. Written by [renderersFactory], read for an error that may be float's. */
    @Volatile
    var builtWithFloat = false
        private set

    /**
     * The player's renderers, with the equalizer in the audio sink: float output and [floatStage] where the device plays float
     * (and has not refused it), else the 16-bit sink with [processor] alone.
     */
    fun renderersFactory(context: Context): DefaultRenderersFactory {
        val useFloat = fallback.useFloat()
        builtWithFloat = useFloat
        return EqualizerRenderersFactory(context, processor, floatStage, useFloat)
    }

    /** Starts following the stored choice (idempotent): every change reaches the processors' next buffer. */
    fun follow() {
        if (following) return
        following = true
        scope.launch {
            AudioPreferences.equalizer.collect {
                processor.setSettings(it)
                floatStage.setSettings(it)
            }
        }
    }

    /** What the platform says a float stereo track at 48 kHz needs as a buffer: a positive size, or an error code. */
    private fun probeFloatTrack(): Int =
        AudioTrack.getMinBufferSize(48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
}

/** Decides, once when the player is built, whether the sink plays float. A pure function of the platform's answer. */
internal object FloatOutputPlan {
    /**
     * True when the AudioTrack can play float PCM: [minBufferBytes] (`AudioTrack.getMinBufferSize` for float stereo) answers a
     * positive size. An error code (zero or below), or a probe that throws, is false: the sink then stays on today's 16-bit path,
     * which cannot lose a track to a missing float output.
     */
    fun useFloat(minBufferBytes: () -> Int): Boolean = try {
        minBufferBytes() > 0
    } catch (error: RuntimeException) {
        false
    }
}

/**
 * Float output, with the way back from it. [useFloat] is [FloatOutputPlan] unless an AudioTrack has refused float in this process
 * ([onPlayerError]); the refusal is sticky, so the player is rebuilt without float once and never again.
 */
internal class FloatFallback(private val minBufferBytes: () -> Int) {
    @Volatile
    var refused = false
        private set

    /** Whether a player built now should play float: the platform answers for it and nothing has refused it. */
    fun useFloat(): Boolean = !refused && FloatOutputPlan.useFloat(minBufferBytes)

    /**
     * Whether the player must be rebuilt without float for this error: the AudioTrack of a player built WITH float would not
     * start ([PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED]) and float has not been refused before. True at most once for
     * the life of the process: it records the refusal, so the rebuilt player (and any error it has) takes the ordinary path.
     */
    fun onPlayerError(errorCode: Int, builtWithFloat: Boolean): Boolean {
        if (!builtWithFloat || refused || errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
        refused = true
        return true
    }
}

@OptIn(UnstableApi::class)
private class EqualizerRenderersFactory(
    context: Context,
    private val processor: GraphicEqualizerProcessor,
    private val floatStage: GraphicEqualizerProcessor,
    private val useFloat: Boolean,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(processor))
        if (!useFloat) return builder.setEnableFloatOutput(enableFloatOutput).build()
        // Float output turns a 24-bit or float stream into a float AudioTrack, and the sink then skips its chain: the equalizer is
        // applied to the float output instead. The renderer asks every decoder for float and the c2 MP3, AAC and FLAC decoders give
        // it, so most files, 16-bit ones too, go this way. Only PCM that stays 16-bit (raw WAV/AIFF, a decoder that ignores the
        // request) keeps the chain's processor, and an output that is not float is not wrapped.
        return builder
            .setEnableFloatOutput(true)
            .setAudioOutputProvider(FloatEqualizerOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build(), floatStage))
            .build()
    }
}

/** The stock AudioTrack provider, except that the float outputs it makes carry the equalizer ([FloatEqualizerOutput]). */
@OptIn(UnstableApi::class)
internal class FloatEqualizerOutputProvider(
    delegate: AudioOutputProvider,
    private val stage: GraphicEqualizerProcessor,
) : ForwardingAudioOutputProvider(delegate) {
    override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
        val output = super.getAudioOutput(config)
        // Only a PCM float output to an AudioTrack that mixes: offload and tunnelling are not PCM the app can touch.
        if (config.encoding != C.ENCODING_PCM_FLOAT || config.isOffload || config.isTunneling) return output
        return FloatEqualizerOutput(output, stage, Integer.bitCount(config.channelMask), config.sampleRate)
    }
}

/**
 * An AudioOutput that runs the float equalizer ([YourMusicEqualizer.floatStage]) on what the sink writes to it.
 *
 * - **Flat or off** the sink's own buffer goes straight to the AudioTrack: no copy, no arithmetic, the bits are the source's.
 * - **Otherwise** a buffer is filtered once, whole, into the stage's own buffer, and that is written (to the AudioTrack, which may take
 *   only part of it). The sink's buffer is never modified. The caller's position is moved by the bytes the AudioTrack took, so a
 *   write that returns false is repeated with the same buffer, as the [AudioOutput.write] contract says, and the stage's output
 *   is not made again: each sample is filtered once.
 * - A buffer is told apart by the [pending] flag, not by identity: the sink hands the same ByteBuffer object back with new content.
 * - A format the stage cannot work on (more than [GraphicEqualizerProcessor.MaxChannels] channels) is written as it is, and so is
 *   everything after a failure inside the equalizer: music over silence.
 *
 * The filters start clean here. The sink makes a new output after every seek and keeps one across a gapless join, so a join
 * continues the signal and a seek begins one.
 */
@OptIn(UnstableApi::class)
internal class FloatEqualizerOutput(
    delegate: AudioOutput,
    private val stage: GraphicEqualizerProcessor,
    channels: Int,
    sampleRateHz: Int,
) : ForwardingAudioOutput(delegate) {
    private val active: Boolean
    private var faulted = false

    /** True from the first write of a buffer until a write returns true. */
    private var pending = false
    private var filtered: ByteBuffer? = null
    private var inputStart = 0

    init {
        stage.configure(AudioProcessor.AudioFormat(sampleRateHz, channels, C.ENCODING_PCM_FLOAT))
        active = stage.isActive
        if (active) {
            stage.flush(AudioProcessor.StreamMetadata.DEFAULT)
            stage.restart()
        }
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        if (!pending) begin(buffer)
        val ready = filtered
        val done: Boolean
        if (ready == null) {
            done = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
        } else {
            done = super.write(ready, encodedAccessUnitCount, presentationTimeUs)
            // The filtered bytes are the same count as the input's, so what the output took is what the sink's buffer is moved on by.
            buffer.position(if (done) buffer.limit() else inputStart + ready.position())
        }
        pending = !done
        if (done) filtered = null
        return done
    }

    private fun begin(buffer: ByteBuffer) {
        pending = true
        filtered = null
        if (!active || faulted || !buffer.hasRemaining() || stage.canPassThrough()) return
        inputStart = buffer.position()
        try {
            stage.queueInput(buffer)
            filtered = stage.output
        } catch (error: RuntimeException) {
            faulted = true
            filtered = null
            runCatching { Log.e(Tag, "float equalizer failed, playing without it", error) }
        } finally {
            buffer.position(inputStart)
        }
    }

    override fun flush() {
        pending = false
        filtered = null
        if (active) stage.restart()
        super.flush()
    }

    override fun release() {
        pending = false
        filtered = null
        super.release()
    }

    private companion object {
        const val Tag = "MimirFloatEq"
    }
}
