package com.gruning.mimir.rewrite

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * The launch sound for a clip whose audio track is NOT AAC (reported sample: 11 of 12 normalized clips, so only the
 * AAC ones ever got a sound). It decodes the track to PCM with the device's own decoder and encodes that to AAC-LC into the
 * same audio-only MP4 the copy path makes, so the file beside the game's art is always `<base>-audio.m4a` with one AAC
 * track, whatever the clip carried. The AAC copy ([LaunchAudioRemux.remuxAacTrack]) stays the fast path; this is for every
 * other track the device can decode. No other container or codec is ever written.
 *
 * **Bounded work.** Only the first [maxDurationUs] of the track is decoded ([LaunchAudioMaxDurationMs]), so a clip costs a
 * few seconds of audio to decode and encode whatever its length, and the PCM between the two halves is at most that many
 * seconds (a few MB). Both halves stop at a wall-clock deadline ([StallDeadlineMs]) so a codec that never answers costs the
 * lane one bounded wait and a [LaunchAudioRemuxOutcome.TransientFailure], not a hung batch. Decode and encode are two
 * phases, not an interleaved pipeline: no buffer-size or back-pressure coupling between the two codecs, at the price of
 * holding the PCM.
 *
 * **Channels.** Mono and stereo are kept as they are. A track with more channels (5.1 and up) is reduced to its first two,
 * the front pair, because the AAC encoder is only asked for mono or stereo; it is a launch sound, not a mix.
 *
 * **What a failure says.** A fact about the clip is [LaunchAudioRemuxOutcome.NoAudioTrack], no decoder on this device
 * ([LaunchAudioRemuxOutcome.UnsupportedCodec]), or a file that cannot be used ([LaunchAudioRemuxOutcome.Failed]): a caller
 * remembers those. Trouble in the codecs, the muxer or the deadline is [LaunchAudioRemuxOutcome.TransientFailure]: a caller
 * records nothing, because the same clip may transcode on the next batch. [transcodeFailureOutcome] is the one place that
 * sorts a thrown exception into one or the other.
 *
 * Blocking, file to file, off the main thread. The caller runs at most [MaxConcurrentTranscodes] of these at once (a
 * [TranscodeGate]) even though a batch has up to six lanes. It never reads a URL and never logs: the clip is a local `.part`
 * file by now.
 */
internal object LaunchAudioTranscode {

    /** How long one `dequeue` call may wait, in microseconds. */
    private const val DequeueTimeoutUs = 10_000L

    /** The whole transcode of one clip may take no longer than this before it is given up as [LaunchAudioRemuxOutcome.TransientFailure]. */
    internal const val StallDeadlineMs = 30_000L

    private const val StereoBitRate = 96_000
    private const val MonoBitRate = 64_000

    /** The AAC encoder refuses a bit rate far above what its sample rate can carry (8 kHz mono at 64 kbps, for one). */
    private const val MaxBitsPerSample = 3
    private const val BytesPerSample = 2
    private const val MaxOutputChannels = 2

    /** The PCM a decode produced, always 16-bit interleaved. */
    private class Pcm(val bytes: ByteArray, val sampleRate: Int, val channels: Int)

    /**
     * Transcodes the first [maxDurationUs] of [input]'s first audio track to AAC in [output].
     *
     * [LaunchAudioRemuxOutcome.NoAudioTrack] when there is no audio track; [LaunchAudioRemuxOutcome.UnsupportedCodec] when
     * the device has no decoder for the track's MIME type; [LaunchAudioRemuxOutcome.Failed] for a file that cannot be used;
     * [LaunchAudioRemuxOutcome.TransientFailure] for codec, muxer and deadline trouble (see [transcodeFailureOutcome]).
     * Every outcome but [LaunchAudioRemuxOutcome.Written] leaves no [output]. Throws only an [InterruptedException], when the
     * calling coroutine was cancelled (the caller runs this in `runInterruptible`); see [checkTranscodeNotInterrupted].
     */
    fun transcodeToAac(
        input: File,
        output: File,
        maxDurationUs: Long = LaunchAudioMaxDurationUs,
        stallDeadlineMs: Long = StallDeadlineMs,
    ): LaunchAudioRemuxOutcome {
        var outcome: LaunchAudioRemuxOutcome = LaunchAudioRemuxOutcome.TransientFailure
        try {
            val deadlineNanos = System.nanoTime() + stallDeadlineMs * 1_000_000L
            outcome = transcode(input, output, maxDurationUs, deadlineNanos)
            return outcome
        } catch (interrupted: InterruptedException) {
            // The download was cancelled under the transcode: not a codec failure and not a fact about the clip, so it is
            // neither an outcome nor a negative. The output goes in the finally; the caller sees the cancellation.
            throw interrupted
        } catch (failure: Exception) {
            outcome = transcodeFailureOutcome(failure)
            return outcome
        } finally {
            if (outcome !is LaunchAudioRemuxOutcome.Written) runCatching { output.delete() }
        }
    }

    private fun transcode(input: File, output: File, maxDurationUs: Long, deadlineNanos: Long): LaunchAudioRemuxOutcome {
        val extractor = MediaExtractor()
        try {
            // A file the extractor cannot read at all is a fact about the file, not a codec having a bad moment.
            try {
                extractor.setDataSource(input.absolutePath)
            } catch (_: Exception) {
                return LaunchAudioRemuxOutcome.Failed
            }
            var trackIndex = -1
            var format: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val candidate = extractor.getTrackFormat(index)
                if (candidate.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = index
                    format = candidate
                    break
                }
            }
            if (format == null) return LaunchAudioRemuxOutcome.NoAudioTrack
            // A decoder the device does not have is a fact about this clip on this device; any other codec trouble is not.
            val decoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                ?: return LaunchAudioRemuxOutcome.UnsupportedCodec
            extractor.selectTrack(trackIndex)

            val pcm = decode(extractor, format, decoderName, maxDurationUs, deadlineNanos)
            val frameBytes = pcm.channels * BytesPerSample
            val capFrames = pcm.sampleRate.toLong() * maxDurationUs / 1_000_000L
            val frames = min((pcm.bytes.size / frameBytes).toLong(), capFrames).toInt()
            if (frames <= 0) return LaunchAudioRemuxOutcome.Failed
            val outChannels = min(pcm.channels, MaxOutputChannels)
            val samples = if (pcm.channels == outChannels) {
                pcm.bytes.copyOf(frames * frameBytes)
            } else {
                frontPair(pcm.bytes, frames, pcm.channels)
            }
            return encode(samples, pcm.sampleRate, outChannels, output, deadlineNanos)
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Decodes the selected track of [extractor] to 16-bit PCM, stopping at the first sample past [maxDurationUs]. */
    private fun decode(
        extractor: MediaExtractor,
        format: MediaFormat,
        decoderName: String,
        maxDurationUs: Long,
        deadlineNanos: Long,
    ): Pcm {
        val decoder = MediaCodec.createByCodecName(decoderName)
        try {
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()
            var sampleRate = format.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            var channels = format.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var chunk = ByteArray(0)
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                checkTranscodeDeadline(deadlineNanos)
                checkTranscodeNotInterrupted()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(DequeueTimeoutUs)
                    if (index >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(index)) { "no decoder input buffer" }
                        val size = extractor.readSampleData(buffer, 0)
                        val timeUs = extractor.sampleTime
                        if (size < 0 || timeUs > maxDurationUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, timeUs, 0)
                            extractor.advance()
                        }
                    }
                }
                // Drain everything the decoder has ready before feeding it again: waiting out a dequeue timeout per
                // output buffer would make the clip cost a timeout per frame instead of the decoder's own time.
                var wait = DequeueTimeoutUs
                while (!outputDone) {
                    val out = decoder.dequeueOutputBuffer(info, wait)
                    wait = 0L
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val produced = decoder.outputFormat
                        sampleRate = produced.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: sampleRate
                        channels = produced.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        val encoding = produced.intOrNull(MediaFormat.KEY_PCM_ENCODING)
                        if (encoding != null && encoding != AudioFormat.ENCODING_PCM_16BIT) {
                            throw UnusableAudioTrack("decoder output is not 16-bit PCM")
                        }
                    } else if (out >= 0) {
                        if (info.size > 0) {
                            val buffer = checkNotNull(decoder.getOutputBuffer(out)) { "no decoder output buffer" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            if (chunk.size < info.size) chunk = ByteArray(info.size)
                            buffer.get(chunk, 0, info.size)
                            pcm.write(chunk, 0, info.size)
                        }
                        decoder.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else {
                        break
                    }
                }
            }
            if (sampleRate <= 0 || channels <= 0) throw UnusableAudioTrack("decoder reported no sample rate or channel count")
            return Pcm(pcm.toByteArray(), sampleRate, channels)
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
        }
    }

    /** The first two channels of every frame of interleaved 16-bit PCM that has more than two. */
    private fun frontPair(pcm: ByteArray, frames: Int, channels: Int): ByteArray {
        val frameBytes = channels * BytesPerSample
        val pairBytes = MaxOutputChannels * BytesPerSample
        val out = ByteArray(frames * pairBytes)
        for (frame in 0 until frames) System.arraycopy(pcm, frame * frameBytes, out, frame * pairBytes, pairBytes)
        return out
    }

    /** Encodes interleaved 16-bit [pcm] (mono or stereo) to AAC-LC in an audio-only MP4 at [output]. */
    private fun encode(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        output: File,
        deadlineNanos: Long,
    ): LaunchAudioRemuxOutcome {
        val nominalBitRate = if (channels == 1) MonoBitRate else StereoBitRate
        val format = MediaFormat.createAudioFormat(LaunchAudioAacMime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, min(nominalBitRate, sampleRate * channels * MaxBitsPerSample))
        }
        val encoder = MediaCodec.createEncoderByType(LaunchAudioAacMime)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            val sink = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = sink

            val frameBytes = channels * BytesPerSample
            val info = MediaCodec.BufferInfo()
            var fedBytes = 0
            var track = -1
            var written = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                checkTranscodeDeadline(deadlineNanos)
                checkTranscodeNotInterrupted()
                if (!inputDone) {
                    val index = encoder.dequeueInputBuffer(DequeueTimeoutUs)
                    if (index >= 0) {
                        val buffer = checkNotNull(encoder.getInputBuffer(index)) { "no encoder input buffer" }
                        buffer.clear()
                        // Whole frames only, so a buffer never splits a sample across two presentation times.
                        val room = (buffer.capacity() / frameBytes) * frameBytes
                        check(room > 0) { "encoder input buffer holds less than one frame" }
                        val size = min(pcm.size - fedBytes, room)
                        val ptsUs = (fedBytes / frameBytes) * 1_000_000L / sampleRate
                        val last = fedBytes + size >= pcm.size
                        buffer.put(pcm, fedBytes, size)
                        encoder.queueInputBuffer(index, 0, size, ptsUs, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        fedBytes += size
                        if (last) inputDone = true
                    }
                }
                // Drain everything the encoder has ready (one input buffer is several AAC frames) before feeding it again.
                var wait = DequeueTimeoutUs
                while (!outputDone) {
                    val out = encoder.dequeueOutputBuffer(info, wait)
                    wait = 0L
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted) { "encoder changed its output format twice" }
                        // The encoder's OWN output format carries the codec-specific data the muxer needs.
                        track = sink.addTrack(encoder.outputFormat)
                        sink.start()
                        muxerStarted = true
                    } else if (out >= 0) {
                        val buffer = checkNotNull(encoder.getOutputBuffer(out)) { "no encoder output buffer" }
                        // Codec config is already in the output format; it is not a sample.
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(muxerStarted) { "encoder produced a sample before its output format" }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            sink.writeSampleData(track, buffer, info)
                            written++
                        }
                        encoder.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    } else {
                        break
                    }
                }
            }
            // A muxer that was started and never written to refuses to stop. PCM was fed in, so an encoder that produced no
            // samples (or a muxer that wrote an empty file) is a codec having a bad moment, not a fact about the clip.
            if (!muxerStarted || written == 0) return LaunchAudioRemuxOutcome.TransientFailure
            sink.stop()
            muxerStarted = false
            val length = output.length()
            return if (length > 0L) LaunchAudioRemuxOutcome.Written(length, written) else LaunchAudioRemuxOutcome.TransientFailure
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            muxer?.let { sink ->
                if (muxerStarted) runCatching { sink.stop() }
                runCatching { sink.release() }
            }
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
}

/**
 * The track decoded, or tried to, and what came out cannot be used: the decoder will not give 16-bit PCM, or reports no
 * sample rate or channel count. The same on every attempt on this device, so a fact about the clip ([LaunchAudioRemuxOutcome.Failed]),
 * unlike the codec trouble [transcodeFailureOutcome] treats as transient.
 */
internal class UnusableAudioTrack(message: String) : Exception(message)

/**
 * Throws when [deadlineNanos] has passed. An [IllegalStateException], like every other codec or muxer call that is made in
 * the wrong state, so [transcodeFailureOutcome] answers it as transient: a codec that stalls says nothing about the clip.
 */
internal fun checkTranscodeDeadline(deadlineNanos: Long, nowNanos: Long = System.nanoTime()) {
    check(nowNanos - deadlineNanos < 0L) { "transcode stalled" }
}

/**
 * Throws when the thread was interrupted: the coroutine running the transcode was cancelled (`runInterruptible` sets the flag).
 * An [InterruptedException] is not an [IllegalStateException], so [transcodeFailureOutcome] never sees it: a stop is not a codec
 * having trouble, and must not record a negative for the clip.
 */
internal fun checkTranscodeNotInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("transcode cancelled")
}

/**
 * The one place a thrown exception becomes an outcome, so what a transcode failure records is decided in one spot.
 *
 * - [UnusableAudioTrack] is a fact about the clip: [LaunchAudioRemuxOutcome.Failed], remembered for the cooldown.
 * - [MediaCodec.CodecException], any other [IllegalStateException] (a codec or muxer call in a bad state, and the stall
 *   deadline) and an [IOException] (a codec that could not be created, an output that could not be opened) are transient:
 *   [LaunchAudioRemuxOutcome.TransientFailure], which records nothing. Six lanes exhausting a device's codec instances, or one
 *   slow moment, must not silence a game's sound for fourteen days.
 * - Anything else (a format a codec rejects as an [IllegalArgumentException], for one) is the same on every attempt:
 *   [LaunchAudioRemuxOutcome.Failed].
 */
internal fun transcodeFailureOutcome(failure: Throwable): LaunchAudioRemuxOutcome = when (failure) {
    is UnusableAudioTrack -> LaunchAudioRemuxOutcome.Failed
    is MediaCodec.CodecException -> LaunchAudioRemuxOutcome.TransientFailure
    is IllegalStateException -> LaunchAudioRemuxOutcome.TransientFailure
    is IOException -> LaunchAudioRemuxOutcome.TransientFailure
    else -> LaunchAudioRemuxOutcome.Failed
}
