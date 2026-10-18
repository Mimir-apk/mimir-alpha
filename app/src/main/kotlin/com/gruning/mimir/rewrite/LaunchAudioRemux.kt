package com.gruning.mimir.rewrite

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * The longest stretch of a clip the launch sound keeps, for the copy path and the transcode path alike. A TECHNICAL bound, not
 * a product rule: the sound is only ever heard through the intro ([GameOpeningIntroHoldMs] plus its enter and exit) and then
 * under the launch cover, which ends by its own ceiling ([GameLaunchCoverCeilingMs]), so nothing past that can be audible by
 * design, and keeping more would only be stored bytes and, for a transcode, CPU. It bounds the transcode's decode and
 * encode work per clip. Comfortably above intro plus cover ceiling; change it only if either of those grows.
 */
internal const val LaunchAudioMaxDurationMs = 15_000L

/** [LaunchAudioMaxDurationMs] in the microseconds MediaExtractor and MediaCodec count in. */
internal const val LaunchAudioMaxDurationUs = LaunchAudioMaxDurationMs * 1_000L

/** The MIME type of the audio the sound is stored as, and the only one copied without a decode. */
internal const val LaunchAudioAacMime = "audio/mp4a-latm"

/**
 * What [LaunchAudioRemux.probeAudio] found in a downloaded clip, without reading a sample.
 *
 * [Track.mime] is the source track's own MIME string (for example `audio/mpeg`): it is what the cost line's `audioCodecs`
 * tallies, so it is the only thing about the clip that ever leaves this file.
 */
internal sealed interface ClipAudioProbe {
    /** The clip's first audio track. */
    data class Track(val mime: String) : ClipAudioProbe

    /** The clip carries no audio track at all (a silent trailer, or video only). A fact about this clip. */
    data object NoAudioTrack : ClipAudioProbe

    /** The extractor refused the file (truncated, corrupt, unreadable). */
    data object Unreadable : ClipAudioProbe
}

/**
 * What [LaunchAudioRemux.remuxAacTrack] or [LaunchAudioTranscode.transcodeToAac] made of one downloaded clip.
 *
 * Every case except [Written] leaves NO output file behind, so a caller that sees anything else has nothing to clean up
 * and nothing to write.
 */
internal sealed interface LaunchAudioRemuxOutcome {
    /** A finished audio-only MP4 holding the clip's sound as AAC: copied unchanged, or transcoded. */
    data class Written(val bytes: Long, val samples: Int) : LaunchAudioRemuxOutcome

    /** The clip carries no audio track at all (a silent trailer, or video only). A fact about this clip. */
    data object NoAudioTrack : LaunchAudioRemuxOutcome

    /**
     * The clip's audio track is one this device cannot turn into AAC: [LaunchAudioRemux.remuxAacTrack] only copies AAC and
     * so answers this for any other track, and [LaunchAudioTranscode.transcodeToAac] answers it when the device has NO
     * DECODER for the track's MIME type. A fact about this clip on this device.
     */
    data object UnsupportedCodec : LaunchAudioRemuxOutcome

    /**
     * The file itself is the problem, so it will be the same next time: the extractor cannot read it at all, the muxer
     * refused the AAC it was given, the track decoded to no sound, or a codec rejected the track's own format. A fact about
     * this clip.
     */
    data object Failed : LaunchAudioRemuxOutcome

    /**
     * The transcode ran into trouble that says nothing about the clip: a codec that errored or could not be created (several
     * lanes can exhaust a device's codec instances), a codec or muxer call in a bad state, an output that could not be
     * opened, or the wall-clock deadline. Only [LaunchAudioTranscode.transcodeToAac] answers it, and a caller records NO
     * negative for it, the way a dropped download records none: the same clip may transcode fine on the next batch.
     */
    data object TransientFailure : LaunchAudioRemuxOutcome
}

/**
 * The AAC fast path, and the look at a clip that decides which path it takes.
 *
 * [remuxAacTrack] keeps only the sound of a ScreenScraper clip when that sound is already AAC: the track is copied sample for
 * sample into an audio-only MP4 (`MediaExtractor` to `MediaMuxer`, no decode, no re-encode). Any other audio track is
 * [LaunchAudioTranscode]'s. the launch animation plays the game's sound, and "sound
 * only" means no video is kept, so the clip can never become a backdrop.
 *
 * Blocking, file to file, off the main thread. It never reads a URL: the clip is already a local `.part` file by the
 * time it gets here, so there is nothing in this file that could log a credential.
 */
internal object LaunchAudioRemux {

    /** Large enough for any AAC access unit; raised to the track's own declared maximum when that is bigger. */
    private const val MinSampleBufferBytes = 1 shl 20

    /**
     * Reports the clip's first audio track, or that it has none, without reading a sample. Cheap: it opens the file and
     * reads the track table. Never throws.
     */
    fun probeAudio(input: File): ClipAudioProbe {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(input.absolutePath)
            var found: ClipAudioProbe = ClipAudioProbe.NoAudioTrack
            for (index in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    found = ClipAudioProbe.Track(mime)
                    break
                }
            }
            found
        } catch (_: Exception) {
            ClipAudioProbe.Unreadable
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * Copies the first [maxDurationUs] of the clip's AAC track into [output]. A track that is not AAC is
     * [LaunchAudioRemuxOutcome.UnsupportedCodec] here (the caller sends those to the transcode).
     */
    fun remuxAacTrack(
        input: File,
        output: File,
        maxDurationUs: Long = LaunchAudioMaxDurationUs,
    ): LaunchAudioRemuxOutcome {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var outcome: LaunchAudioRemuxOutcome = LaunchAudioRemuxOutcome.Failed
        try {
            extractor.setDataSource(input.absolutePath)
            var audioIndex = -1
            var audioFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioIndex = index
                    audioFormat = format
                    break
                }
            }
            if (audioFormat == null) {
                outcome = LaunchAudioRemuxOutcome.NoAudioTrack
                return outcome
            }
            if (audioFormat.getString(MediaFormat.KEY_MIME) != LaunchAudioAacMime) {
                outcome = LaunchAudioRemuxOutcome.UnsupportedCodec
                return outcome
            }
            extractor.selectTrack(audioIndex)
            val sink = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = sink
            val destinationTrack = sink.addTrack(audioFormat)
            sink.start()
            muxerStarted = true

            val declaredMax = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                0
            }
            val buffer = ByteBuffer.allocate(maxOf(declaredMax, MinSampleBufferBytes))
            val info = MediaCodec.BufferInfo()
            var samples = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val sampleTimeUs = extractor.sampleTime
                // Past the cap: nothing later is ever audible, so none of it is stored.
                if (sampleTimeUs > maxDurationUs) break
                val keyFrame = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(
                    0,
                    size,
                    sampleTimeUs,
                    if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
                )
                sink.writeSampleData(destinationTrack, buffer, info)
                samples++
                extractor.advance()
            }
            // A muxer that was started and never written to refuses to stop; an audio track with no samples is no sound.
            if (samples == 0) return outcome
            sink.stop()
            muxerStarted = false
            val length = output.length()
            outcome = if (length > 0L) LaunchAudioRemuxOutcome.Written(length, samples) else outcome
            return outcome
        } catch (_: Exception) {
            outcome = LaunchAudioRemuxOutcome.Failed
            return outcome
        } finally {
            runCatching { extractor.release() }
            muxer?.let { sink ->
                if (muxerStarted) runCatching { sink.stop() }
                runCatching { sink.release() }
            }
            if (outcome !is LaunchAudioRemuxOutcome.Written) runCatching { output.delete() }
        }
    }
}
