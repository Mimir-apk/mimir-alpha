package com.gruning.mimir.rewrite

import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one piece of the launch-sound feature a fake cannot vouch for: [LaunchAudioRemux] (the AAC copy and the probe) and
 * [LaunchAudioTranscode] (decode any other track, encode AAC) against the platform's own MediaExtractor, MediaCodec and
 * MediaMuxer.
 *
 * **No committed media.** Every fixture is made here, at run time, from a sine tone: MediaCodec encodes AAC, MediaCodec
 * encodes a few black frames of H.264 from an input Surface, and MediaMuxer wraps them in an MP4. That gives the same
 * shape a ScreenScraper clip has (a video track beside an AAC track) without a real clip, a credential or a network.
 *
 * Runs against the separate-UID lab target (`com.gruning.mimir.rewrite.lab`), so it cannot touch the production
 * install's data. Plays at zero volume: it checks that the file plays to its end, not how it sounds.
 */
@RunWith(AndroidJUnit4::class)
class LaunchAudioRemuxDeviceTest {

    private class Sample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    private class EncodedTrack(val format: MediaFormat, val samples: List<Sample>)

    private val workDir: File
        get() = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "launch-audio-remux-test",
        ).apply { mkdirs() }

    @After
    fun cleanUp() {
        workDir.deleteRecursively()
    }

    @Test
    fun aac_track_of_a_clip_with_video_is_kept_alone_and_plays_to_completion() {
        val clip = File(workDir, "clip-with-video.mp4")
        val audio = encodeSine(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate = 44_100, bitRate = 64_000, seconds = 1.5)
        val video = encodeBlackVideo(frames = 6)
        mux(clip, video, audio)
        // The fixture has to be the shape under test, or the assertions below prove nothing.
        assertEquals("fixture must carry a video track and an audio track", listOf("video", "audio"), trackKinds(clip))

        val output = File(workDir, "sound.m4a")
        val outcome = LaunchAudioRemux.remuxAacTrack(clip, output)

        assertTrue("expected Written, got $outcome", outcome is LaunchAudioRemuxOutcome.Written)
        outcome as LaunchAudioRemuxOutcome.Written
        assertTrue(output.isFile)
        assertEquals("the outcome reports the file's real size", output.length(), outcome.bytes)
        assertEquals("exactly one track, and it is audio", listOf("audio"), trackKinds(output))
        assertEquals(
            "a remux is a copy: every AAC access unit of the clip survives",
            countSamples(clip, "audio/"),
            countSamples(output, "audio/"),
        )
        assertEquals(countSamples(clip, "audio/"), outcome.samples)
        assertEquals("audio/mp4a-latm", firstTrackMime(output))

        val played = playToCompletion(output)
        Log.i(TAG, "remuxed sound: bytes=${output.length()} samples=${outcome.samples} played=$played")
        assertTrue("the output must play to its end", played.completed)
        assertTrue("a 1.5 s tone reported ${played.durationMs} ms", played.durationMs in 1_200..1_900)
    }

    @Test
    fun a_clip_with_no_audio_track_is_a_negative_and_leaves_no_file() {
        val clip = File(workDir, "silent-clip.mp4")
        mux(clip, encodeBlackVideo(frames = 6))
        assertEquals(listOf("video"), trackKinds(clip))

        val output = File(workDir, "sound.m4a")
        val outcome = LaunchAudioRemux.remuxAacTrack(clip, output)

        assertEquals(LaunchAudioRemuxOutcome.NoAudioTrack, outcome)
        assertFalse("a negative writes nothing", output.exists())
    }

    @Test
    fun the_copy_path_refuses_a_track_that_is_not_aac_and_leaves_no_file() {
        val amr = amrNbOrSkip(seconds = 1.0)
        val clip = File(workDir, "amr-clip.mp4")
        mux(clip, amr)

        val output = File(workDir, "sound.m4a")
        val outcome = LaunchAudioRemux.remuxAacTrack(clip, output)

        assertEquals(LaunchAudioRemuxOutcome.UnsupportedCodec, outcome)
        assertFalse(output.exists())
    }

    // ── the probe: what the step decides the path by ────────────────────────────────────────────

    @Test
    fun the_probe_names_the_first_audio_track_and_the_absence_of_one() {
        val withAac = File(workDir, "aac-clip.mp4")
        mux(withAac, encodeBlackVideo(frames = 6), encodeSine(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 64_000, 1.0))
        val silent = File(workDir, "silent-clip.mp4")
        mux(silent, encodeBlackVideo(frames = 6))
        val junk = File(workDir, "junk.part").apply { writeBytes(ByteArray(4_096) { (it * 31).toByte() }) }

        assertEquals(ClipAudioProbe.Track("audio/mp4a-latm"), LaunchAudioRemux.probeAudio(withAac))
        assertEquals(ClipAudioProbe.NoAudioTrack, LaunchAudioRemux.probeAudio(silent))
        assertEquals(ClipAudioProbe.Unreadable, LaunchAudioRemux.probeAudio(junk))

        val amr = runCatching { encodeSine(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8_000, 12_200, 1.0) }
        if (amr.isSuccess) {
            val amrClip = File(workDir, "amr-clip.mp4")
            mux(amrClip, amr.getOrThrow())
            assertEquals(ClipAudioProbe.Track("audio/3gpp"), LaunchAudioRemux.probeAudio(amrClip))
        }
    }

    // ── the transcode: a track that is not AAC becomes one ──────────────────────────────────────

    @Test
    fun a_non_aac_track_is_transcoded_to_one_playable_aac_track_and_its_cost_is_logged() {
        val amr = amrNbOrSkip(seconds = 3.0)
        val video = encodeBlackVideo(frames = 6)
        val clip = File(workDir, "amr-with-video.mp4")
        mux(clip, video, amr)
        assertEquals("the fixture has a video track and a non-AAC audio track", listOf("video", "audio"), trackKinds(clip))

        val output = File(workDir, "sound.m4a")
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = LaunchAudioTranscode.transcodeToAac(clip, output)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        assertTrue("expected Written, got $outcome", outcome is LaunchAudioRemuxOutcome.Written)
        outcome as LaunchAudioRemuxOutcome.Written
        assertEquals("the outcome reports the file's real size", output.length(), outcome.bytes)
        assertEquals("one track, and it is audio: no video survives", listOf("audio"), trackKinds(output))
        val info = trackInfo(output)
        assertEquals("audio/mp4a-latm", info.mime)
        assertEquals("the source's own rate and channels are kept", 8_000, info.sampleRate)
        assertEquals(1, info.channels)
        val durationMs = lastSampleTimeUs(output) / 1_000
        assertTrue("a 3 s source came out $durationMs ms long", durationMs in 2_700..3_200)

        val played = playToCompletion(output)
        Log.i(TAG, "transcode amr-nb 3.0s: elapsedMs=$elapsedMs bytes=${output.length()} frames=${outcome.samples} lastSampleMs=$durationMs played=$played")
        assertTrue("the output must play to its end", played.completed)
    }

    @Test
    fun a_stereo_48k_non_aac_track_keeps_its_rate_and_channels_through_the_transcode() {
        val opus = runCatching { encodeSine(MediaFormat.MIMETYPE_AUDIO_OPUS, 48_000, 64_000, 2.0, channels = 2) }
        assumeTrue("no Opus encoder on this image: ${opus.exceptionOrNull()}", opus.isSuccess)
        // MediaMuxer cannot hold Opus in an MP4 on this platform (API 33 said "Failed to add the track"), and the transcode is
        // container-agnostic, so the stereo 48 kHz source is a WebM: the extractor and the Opus decoder are what is under test.
        val clip = File(workDir, "opus-clip.webm")
        val muxed = runCatching { muxWebm(clip, opus.getOrThrow()) }
        assumeTrue("this muxer cannot hold Opus in WebM either: ${muxed.exceptionOrNull()}", muxed.isSuccess)
        assumeTrue("the extractor cannot read the fixture back", runCatching { trackInfo(clip) }.isSuccess)

        val output = File(workDir, "sound.m4a")
        val startedAt = SystemClock.elapsedRealtime()
        val outcome = LaunchAudioTranscode.transcodeToAac(clip, output)
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt

        assertTrue("expected Written, got $outcome", outcome is LaunchAudioRemuxOutcome.Written)
        val info = trackInfo(output)
        Log.i(TAG, "transcode opus 48k stereo 2.0s: elapsedMs=$elapsedMs bytes=${output.length()} info=$info")
        assertEquals("audio/mp4a-latm", info.mime)
        assertEquals(48_000, info.sampleRate)
        assertEquals(2, info.channels)
        assertTrue(lastSampleTimeUs(output) / 1_000 in 1_800..2_300)
    }

    @Test
    fun the_transcode_answers_no_audio_track_and_failure_without_leaving_a_file() {
        val silent = File(workDir, "silent-clip.mp4")
        mux(silent, encodeBlackVideo(frames = 6))
        val junk = File(workDir, "junk.part").apply { writeBytes(ByteArray(4_096) { (it * 31).toByte() }) }
        val output = File(workDir, "sound.m4a")

        assertEquals(LaunchAudioRemuxOutcome.NoAudioTrack, LaunchAudioTranscode.transcodeToAac(silent, output))
        assertFalse(output.exists())
        assertEquals(LaunchAudioRemuxOutcome.Failed, LaunchAudioTranscode.transcodeToAac(junk, output))
        assertFalse(output.exists())
        assertTrue("the input is the caller's to delete, not the helper's", junk.isFile)
    }

    // ── the duration cap, on both paths ─────────────────────────────────────────────────────────

    @Test
    fun the_duration_cap_bounds_the_copy_path_and_the_transcode_alike() {
        val aac = encodeSine(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 64_000, 4.0)
        val aacClip = File(workDir, "aac-4s.mp4")
        mux(aacClip, aac)
        val copied = File(workDir, "copied.m4a")
        val copyOutcome = LaunchAudioRemux.remuxAacTrack(aacClip, copied, maxDurationUs = 2_000_000L)
        assertTrue("expected Written, got $copyOutcome", copyOutcome is LaunchAudioRemuxOutcome.Written)
        val copiedMs = lastSampleTimeUs(copied) / 1_000
        assertTrue("a 4 s AAC clip capped at 2 s kept $copiedMs ms", copiedMs in 1_700..2_100)
        assertTrue("and fewer samples than the source", countSamples(copied, "audio/") < countSamples(aacClip, "audio/"))

        val amr = amrNbOrSkip(seconds = 4.0)
        val amrClip = File(workDir, "amr-4s.mp4")
        mux(amrClip, amr)
        val transcoded = File(workDir, "transcoded.m4a")
        val transcodeOutcome = LaunchAudioTranscode.transcodeToAac(amrClip, transcoded, maxDurationUs = 2_000_000L)
        assertTrue("expected Written, got $transcodeOutcome", transcodeOutcome is LaunchAudioRemuxOutcome.Written)
        val transcodedMs = lastSampleTimeUs(transcoded) / 1_000
        assertTrue("a 4 s AMR clip capped at 2 s kept $transcodedMs ms", transcodedMs in 1_700..2_200)
    }

    @Test
    fun the_named_cap_trims_a_clip_longer_than_it_on_both_paths_and_the_worst_transcode_is_timed() {
        val cap = LaunchAudioMaxDurationMs
        val longSeconds = (cap / 1_000) + 5.0

        val aacClip = File(workDir, "aac-long.mp4")
        mux(aacClip, encodeSine(MediaFormat.MIMETYPE_AUDIO_AAC, 44_100, 64_000, longSeconds))
        val copied = File(workDir, "copied.m4a")
        val copyStartedAt = SystemClock.elapsedRealtime()
        val copyOutcome = LaunchAudioRemux.remuxAacTrack(aacClip, copied)
        val copyMs = SystemClock.elapsedRealtime() - copyStartedAt
        assertTrue("expected Written, got $copyOutcome", copyOutcome is LaunchAudioRemuxOutcome.Written)
        val copiedMs = lastSampleTimeUs(copied) / 1_000
        Log.i(TAG, "copy ${longSeconds}s clip at the named cap: elapsedMs=$copyMs bytes=${copied.length()} lastSampleMs=$copiedMs")
        assertTrue("copy kept $copiedMs ms of a ${longSeconds} s clip, cap $cap ms", copiedMs in (cap - 300)..(cap + 300))

        val amr = amrNbOrSkip(seconds = longSeconds)
        val amrClip = File(workDir, "amr-long.mp4")
        mux(amrClip, amr)
        val transcoded = File(workDir, "transcoded.m4a")
        val transcodeStartedAt = SystemClock.elapsedRealtime()
        val transcodeOutcome = LaunchAudioTranscode.transcodeToAac(amrClip, transcoded)
        val transcodeMs = SystemClock.elapsedRealtime() - transcodeStartedAt
        assertTrue("expected Written, got $transcodeOutcome", transcodeOutcome is LaunchAudioRemuxOutcome.Written)
        val transcodedMs = lastSampleTimeUs(transcoded) / 1_000
        Log.i(TAG, "transcode ${longSeconds}s amr clip at the named cap: elapsedMs=$transcodeMs bytes=${transcoded.length()} lastSampleMs=$transcodedMs")
        assertTrue("transcode kept $transcodedMs ms of a ${longSeconds} s clip, cap $cap ms", transcodedMs in (cap - 300)..(cap + 300))
        assertTrue("a capped transcode finishes well inside its own deadline: $transcodeMs ms", transcodeMs < 10_000)
    }

    private fun amrNbOrSkip(seconds: Double): EncodedTrack {
        val amr = runCatching {
            encodeSine(MediaFormat.MIMETYPE_AUDIO_AMR_NB, sampleRate = 8_000, bitRate = 12_200, seconds = seconds)
        }
        // Not every image carries an AMR-NB encoder. Say so in the log rather than fail a claim about the helper.
        assumeTrue("no AMR-NB encoder on this image: ${amr.exceptionOrNull()}", amr.isSuccess)
        return amr.getOrThrow()
    }

    @Test
    fun a_file_that_is_not_media_is_a_failure_not_a_crash_and_leaves_no_file() {
        val junk = File(workDir, "junk.part").apply { writeBytes(ByteArray(4_096) { (it * 31).toByte() }) }
        val output = File(workDir, "sound.m4a")

        val outcome = LaunchAudioRemux.remuxAacTrack(junk, output)

        assertEquals(LaunchAudioRemuxOutcome.Failed, outcome)
        assertFalse(output.exists())
        assertTrue("the input is the caller's to delete, not the helper's", junk.isFile)
    }

    /**
     * Not a claim about the remux: what this platform's own MIME tables say about the sound's extension, and that the type
     * the SAF writer creates the document with is the one those tables agree with. A DocumentsProvider keeps the requested
     * `-audio.m4a` name only when type and extension agree in these tables; otherwise it appends another extension and the
     * writer discards the document. Logs the raw answers so a device check can read them.
     */
    @Test
    fun the_sound_is_created_with_the_type_the_platform_tables_give_its_extension() {
        val fromExtension = MimeTypeMap.getSingleton().getMimeTypeFromExtension("m4a")
        val fromType = MimeTypeMap.getSingleton().getExtensionFromMimeType("audio/mp4")
        val chosen = safCreateMimeFor("m4a")
        Log.i(TAG, "mime tables: m4a=$fromExtension audio/mp4=$fromType chosen=$chosen")
        assertEquals(fromExtension ?: "application/octet-stream", chosen)
    }

    // ── fixture: audio ──────────────────────────────────────────────────────────────────────────

    private fun encodeSine(
        mime: String,
        sampleRate: Int,
        bitRate: Int,
        seconds: Double,
        channels: Int = 1,
    ): EncodedTrack {
        val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }
        }
        val codec = MediaCodec.createEncoderByType(mime)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val totalFrames = (sampleRate * seconds).toInt()
            var fed = 0
            var inputDone = false
            var outputFormat: MediaFormat? = null
            val samples = mutableListOf<Sample>()
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + CODEC_DEADLINE_MS
            while (true) {
                check(SystemClock.elapsedRealtime() < deadline) { "audio encoder stalled" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val frames = min(totalFrames - fed, buffer.capacity() / (2 * channels))
                        val ptsUs = fed * 1_000_000L / sampleRate
                        if (frames <= 0) {
                            codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (i in 0 until frames) {
                                val value = sin(2.0 * PI * 440.0 * (fed + i) / sampleRate) * 0.3
                                repeat(channels) { buffer.putShort((value * Short.MAX_VALUE).toInt().toShort()) }
                            }
                            codec.queueInputBuffer(index, 0, frames * 2 * channels, ptsUs, 0)
                            fed += frames
                        }
                    }
                }
                // Drain everything that is ready, not one buffer per pass: a 20 s clip is hundreds of frames and a timeout
                // per frame made this fixture slower than the code under test.
                var wait = TIMEOUT_US
                var endOfStream = false
                while (true) {
                    val out = codec.dequeueOutputBuffer(info, wait)
                    wait = 0L
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outputFormat = codec.outputFormat
                    } else if (out >= 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(out))
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            endOfStream = true
                            break
                        }
                    } else {
                        break
                    }
                }
                if (endOfStream) break
            }
            check(samples.isNotEmpty()) { "audio encoder produced nothing" }
            return EncodedTrack(outputFormat ?: codec.outputFormat, samples)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    // ── fixture: video ──────────────────────────────────────────────────────────────────────────

    private fun encodeBlackVideo(frames: Int): EncodedTrack {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 128, 128).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 200_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            var outputFormat: MediaFormat? = null
            val samples = mutableListOf<Sample>()
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + CODEC_DEADLINE_MS

            fun drain(untilEndOfStream: Boolean) {
                while (true) {
                    check(SystemClock.elapsedRealtime() < deadline) { "video encoder stalled" }
                    val out = codec.dequeueOutputBuffer(info, if (untilEndOfStream) TIMEOUT_US else 0L)
                    when {
                        out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                        out >= 0 -> {
                            val buffer = requireNotNull(codec.getOutputBuffer(out))
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val bytes = ByteArray(info.size)
                                buffer.get(bytes)
                                samples += Sample(
                                    bytes,
                                    info.presentationTimeUs,
                                    info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME,
                                )
                            }
                            codec.releaseOutputBuffer(out, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                        out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEndOfStream) return
                    }
                }
            }

            repeat(frames) { index ->
                val canvas = surface.lockCanvas(null)
                canvas.drawColor(if (index % 2 == 0) Color.BLACK else Color.DKGRAY)
                surface.unlockCanvasAndPost(canvas)
                SystemClock.sleep(40)
                drain(untilEndOfStream = false)
            }
            codec.signalEndOfInputStream()
            drain(untilEndOfStream = true)
            surface.release()
            check(samples.isNotEmpty()) { "video encoder produced nothing" }
            return EncodedTrack(outputFormat ?: codec.outputFormat, samples)
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    // ── fixture: container ──────────────────────────────────────────────────────────────────────

    private fun muxWebm(target: File, track: EncodedTrack) {
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM)
        try {
            val index = muxer.addTrack(track.format)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            track.samples.forEach { sample ->
                info.set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                muxer.writeSampleData(index, ByteBuffer.wrap(sample.data), info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    private fun mux(target: File, vararg tracks: EncodedTrack) {
        val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val indices = tracks.map { muxer.addTrack(it.format) }
            muxer.start()
            val info = MediaCodec.BufferInfo()
            tracks.forEachIndexed { position, track ->
                track.samples.forEach { sample ->
                    info.set(0, sample.data.size, sample.presentationTimeUs, sample.flags)
                    muxer.writeSampleData(indices[position], ByteBuffer.wrap(sample.data), info)
                }
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    // ── inspection ──────────────────────────────────────────────────────────────────────────────

    private fun trackKinds(file: File): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return (0 until extractor.trackCount).map { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().substringBefore('/')
            }
        } finally {
            extractor.release()
        }
    }

    private fun firstTrackMime(file: File): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            return extractor.getTrackFormat(0).getString(MediaFormat.KEY_MIME)
        } finally {
            extractor.release()
        }
    }

    private fun countSamples(file: File, mimePrefix: String): Int {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(mimePrefix)
            }
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(1 shl 20)
            var count = 0
            while (true) {
                buffer.clear()
                if (extractor.readSampleData(buffer, 0) < 0) break
                count++
                extractor.advance()
            }
            return count
        } finally {
            extractor.release()
        }
    }

    private data class TrackInfo(val mime: String?, val sampleRate: Int, val channels: Int)

    /** The first track's mime, sample rate and channel count, as the extractor reads them back from the file. */
    private fun trackInfo(file: File): TrackInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = extractor.getTrackFormat(0)
            return TrackInfo(
                mime = format.getString(MediaFormat.KEY_MIME),
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
        } finally {
            extractor.release()
        }
    }

    /** The presentation time of the file's last audio sample, in microseconds: how much sound it really holds. */
    private fun lastSampleTimeUs(file: File): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            }
            extractor.selectTrack(track)
            val buffer = ByteBuffer.allocate(1 shl 20)
            var last = 0L
            while (true) {
                buffer.clear()
                if (extractor.readSampleData(buffer, 0) < 0) break
                last = extractor.sampleTime
                extractor.advance()
            }
            return last
        } finally {
            extractor.release()
        }
    }

    private data class Playback(val completed: Boolean, val durationMs: Int)

    private fun playToCompletion(file: File): Playback {
        val done = CountDownLatch(1)
        var failed = false
        val player = MediaPlayer()
        try {
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { done.countDown() }
            player.setOnErrorListener { _, _, _ ->
                failed = true
                done.countDown()
                true
            }
            player.prepare()
            player.setVolume(0f, 0f)
            val durationMs = player.duration
            player.start()
            val completed = done.await(durationMs + PLAY_SLACK_MS, TimeUnit.MILLISECONDS) && !failed
            return Playback(completed, durationMs)
        } finally {
            runCatching { player.release() }
        }
    }

    private companion object {
        const val TAG = "LaunchAudioRemuxTest"
        const val TIMEOUT_US = 10_000L
        const val CODEC_DEADLINE_MS = 20_000L
        const val PLAY_SLACK_MS = 6_000L
    }
}
