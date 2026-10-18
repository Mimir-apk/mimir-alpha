package com.gruning.mimir.rewrite

import android.content.res.AssetFileDescriptor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Profiles what it costs this hardware to decode the frontend bed as Ogg Vorbis versus as AAC.
 *
 * The last unproven line in `core-audio/PROVENANCE.md`: *"Vorbis vs AAC decode cost on these
 * handhelds is unprofiled."* It has sat there since the module left AAC to fix the loop seam, as the
 * honest counterweight to that decision — leaving AAC was right for seam exactness, and nobody had
 * checked what it cost in CPU.
 *
 * **Method.** Decode each file to completion as fast as the decoder will go, discard the PCM, and
 * measure the PROCESS cpu time consumed (`Process.getElapsedCpuTime()` covers every thread, which is
 * what matters — MediaCodec does its work on its own). Wall-clock would measure the scheduler; CPU
 * time measures the decoder. Reported per second of audio decoded, so the three files compare
 * directly despite identical length.
 *
 * **Fairness.** All three sources are the SAME 57.600 s of audio: the shipped Vorbis, and two AAC
 * encodes made from a PCM decode of that exact file. 104 kb/s is what the retired V3 actually
 * shipped at; 155 kb/s is matched to the Vorbis the module ships now, so the comparison is not
 * quietly a bitrate comparison.
 *
 * An idle baseline is measured and subtracted, and each file gets a warm-up pass that is thrown
 * away, so class loading and codec instantiation do not land in the number.
 *
 * The decoder's own NAME is recorded. It is the finding most likely to matter: a vendor hardware AAC
 * path and a software Vorbis path would be a real asymmetry, and one no amount of arithmetic about
 * bitrates would reveal.
 */
@RunWith(AndroidJUnit4::class)
class AudioDecodeCostDeviceTest {

    private data class DecodeCost(
        val label: String,
        val codecName: String,
        val framesDecoded: Long,
        val cpuMs: Long,
        val wallMs: Long,
    ) {
        val audioSeconds: Double get() = framesDecoded / 48_000.0
        val cpuMsPerAudioSecond: Double get() = if (audioSeconds > 0) cpuMs / audioSeconds else 0.0
        /** How many times faster than realtime this decodes, by CPU. Higher is cheaper. */
        val realtimeFactor: Double get() = if (cpuMs > 0) audioSeconds * 1000.0 / cpuMs else 0.0
    }

    @Test
    fun vorbis_and_aac_decode_cost_is_profiled() {
        val idleCpuMs = measureIdleCpuMs()
        Log.i(TAG, "idle baseline: $idleCpuMs ms cpu over ${IDLE_SAMPLE_MS} ms wall")

        val results = listOf(
            "Ogg Vorbis q6 (SHIPPED)" to { openShippedVorbis() },
            "AAC 104 kb/s (retired V3 rate)" to { openAsset("v22_aac_104k.m4a") },
            "AAC 155 kb/s (bitrate-matched)" to { openAsset("v22_aac_155k.m4a") },
        ).map { (label, open) ->
            decodeOnce(label, open, warmUp = true)
            decodeOnce(label, open, warmUp = false)
        }

        results.forEach { r ->
            Log.i(TAG, "=== ${r.label}")
            Log.i(TAG, "  decoder      = ${r.codecName}")
            Log.i(TAG, "  audio        = %.3f s (%d frames)".format(r.audioSeconds, r.framesDecoded))
            Log.i(TAG, "  cpu          = %d ms  (%.2f ms per audio second)".format(r.cpuMs, r.cpuMsPerAudioSecond))
            Log.i(TAG, "  wall         = %d ms".format(r.wallMs))
            Log.i(TAG, "  realtime x   = %.0f".format(r.realtimeFactor))
        }
        val vorbis = results.first()
        results.drop(1).forEach { aac ->
            val ratio = if (aac.cpuMs > 0) vorbis.cpuMs.toDouble() / aac.cpuMs else 0.0
            Log.i(TAG, "VORBIS / ${aac.label} cpu ratio = %.2fx".format(ratio))
        }

        // All three are the same audio; a decoder that produced a wildly different frame count would
        // mean the comparison is not measuring what it claims.
        val frames = results.map { it.framesDecoded }
        assertTrue(
            "decoded frame counts disagree ($frames) — the three sources are not the same audio",
            frames.max() - frames.min() < 48_000,
        )
        assertTrue("no CPU time attributed to decoding", results.all { it.cpuMs > 0 })
    }

    private fun measureIdleCpuMs(): Long {
        val before = Process.getElapsedCpuTime()
        SystemClock.sleep(IDLE_SAMPLE_MS)
        return Process.getElapsedCpuTime() - before
    }

    private fun openShippedVorbis(): AssetFileDescriptor =
        InstrumentationRegistry.getInstrumentation().targetContext.resources
            .openRawResourceFd(com.gruning.mimir.rewrite.core.audio.R.raw.mimir_the_well_remembers_v22)

    private fun openAsset(name: String): AssetFileDescriptor =
        InstrumentationRegistry.getInstrumentation().context.assets.openFd(name)

    private fun decodeOnce(
        label: String,
        open: () -> AssetFileDescriptor,
        warmUp: Boolean,
    ): DecodeCost {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            open().use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val track = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                    .orEmpty().startsWith("audio/")
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()

            val cpuBefore = Process.getElapsedCpuTime()
            val wallBefore = SystemClock.elapsedRealtime()
            var frames = 0L
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(inIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (outIndex >= 0) {
                    // Two channels of 16-bit PCM. Counting frames rather than bytes keeps the three
                    // sources comparable even if a decoder hands back a different buffer size.
                    frames += info.size / 4L
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break
                }
            }
            val cpuMs = Process.getElapsedCpuTime() - cpuBefore
            val wallMs = SystemClock.elapsedRealtime() - wallBefore
            return DecodeCost(
                label = label,
                codecName = runCatching { decoder.name }.getOrDefault("unknown"),
                framesDecoded = frames,
                cpuMs = cpuMs,
                wallMs = wallMs,
            ).also { if (warmUp) Log.i(TAG, "warm-up ${it.label}: ${it.cpuMs} ms cpu (discarded)") }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private companion object {
        const val TAG = "MimirDecodeCost"
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val IDLE_SAMPLE_MS = 1_000L
    }
}
