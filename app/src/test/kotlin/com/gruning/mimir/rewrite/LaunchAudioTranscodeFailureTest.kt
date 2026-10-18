package com.gruning.mimir.rewrite

import android.media.MediaCodec
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a thrown exception in the transcode becomes (walk 32l): trouble that says nothing about the clip is
 * [LaunchAudioRemuxOutcome.TransientFailure] and records no negative; a fact about the clip is [LaunchAudioRemuxOutcome.Failed]
 * and does. The step's side of it (a transient outcome records nothing and counts in its own field) is in
 * [LaunchAudioStepTest]; this class pins the sorting, which is the one place that decides.
 *
 * Robolectric, because a real [MediaCodec.CodecException] cannot be built without the framework's own class: its constructor
 * is package-private, so it is made reflectively from the real android-all jar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30], application = android.app.Application::class)
class LaunchAudioTranscodeFailureTest {

    private fun realCodecException(): MediaCodec.CodecException {
        val constructor = MediaCodec.CodecException::class.java.declaredConstructors.first()
        constructor.isAccessible = true
        val arguments = constructor.parameterTypes.map { type ->
            when (type) {
                Int::class.javaPrimitiveType -> 0
                String::class.java -> "codec exploded"
                else -> null
            }
        }.toTypedArray()
        return constructor.newInstance(*arguments) as MediaCodec.CodecException
    }

    @Test
    fun `a codec exception is transient`() {
        val failure = realCodecException()
        assertNotNull(failure)

        assertEquals(LaunchAudioRemuxOutcome.TransientFailure, transcodeFailureOutcome(failure))
    }

    @Test
    fun `the stall deadline is transient`() {
        val deadline = 1_000L
        checkTranscodeDeadline(deadline, nowNanos = deadline - 1)
        val stalled = assertThrows(IllegalStateException::class.java) { checkTranscodeDeadline(deadline, nowNanos = deadline) }

        assertEquals("transcode stalled", stalled.message)
        assertEquals(LaunchAudioRemuxOutcome.TransientFailure, transcodeFailureOutcome(stalled))
        assertEquals("well past it too", true, runCatching { checkTranscodeDeadline(deadline, nowNanos = deadline + 30_000_000_000L) }.isFailure)
    }

    @Test
    fun `a codec or muxer call in a bad state and a codec or output that cannot be opened are transient`() {
        assertEquals(LaunchAudioRemuxOutcome.TransientFailure, transcodeFailureOutcome(IllegalStateException("muxer not started")))
        assertEquals(LaunchAudioRemuxOutcome.TransientFailure, transcodeFailureOutcome(IOException("could not create the codec")))
        assertEquals(LaunchAudioRemuxOutcome.TransientFailure, transcodeFailureOutcome(java.io.FileNotFoundException("output")))
    }

    @Test
    fun `a track that cannot be used and a format a codec rejects are facts about the clip`() {
        assertEquals(LaunchAudioRemuxOutcome.Failed, transcodeFailureOutcome(UnusableAudioTrack("decoder output is not 16-bit PCM")))
        assertEquals(LaunchAudioRemuxOutcome.Failed, transcodeFailureOutcome(IllegalArgumentException("unsupported sample rate")))
        assertEquals(LaunchAudioRemuxOutcome.Failed, transcodeFailureOutcome(RuntimeException("something else entirely")))
        assertFalse(transcodeFailureOutcome(UnusableAudioTrack("x")) == LaunchAudioRemuxOutcome.TransientFailure)
    }

    @Test
    fun `no decoder and no audio track are answered before any exception and stay facts`() {
        // They are outcomes the transcode returns directly, not exceptions, so the sorting above can never turn them transient.
        val code = source("LaunchAudioTranscode.kt")
        assertTrue(code.contains("if (format == null) return LaunchAudioRemuxOutcome.NoAudioTrack"))
        assertTrue(
            "no decoder is answered directly, not thrown",
            code.replace(Regex("\\s+"), " ").contains("findDecoderForFormat(format) ?: return LaunchAudioRemuxOutcome.UnsupportedCodec"),
        )
        val extractorGuard = code.substringAfter("// A file the extractor cannot read at all").substringBefore("var trackIndex")
        assertTrue(
            "an unreadable file is a fact about the file: $extractorGuard",
            extractorGuard.contains("catch (_: Exception)") && extractorGuard.contains("return LaunchAudioRemuxOutcome.Failed"),
        )
    }

    @Test
    fun `the transcode sorts every thrown exception through the one function and never swallows one as a fact`() {
        val code = source("LaunchAudioTranscode.kt")
        val entry = code.substringAfter("fun transcodeToAac(").substringBefore("private fun transcode(")
        assertTrue(entry, entry.contains("catch (failure: Exception)") && entry.contains("outcome = transcodeFailureOutcome(failure)"))
        assertFalse("no catch in the transcode turns a thrown exception into Failed on its own", entry.contains("outcome = LaunchAudioRemuxOutcome.Failed"))
        assertTrue("the deadline is checked in both halves through the one function", Regex("""checkTranscodeDeadline\(deadlineNanos\)""").findAll(code).count() == 2)
    }

    private fun source(name: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/kotlin/com/gruning/mimir/rewrite/$name")
            if (candidate.isFile) return candidate.readText()
            if (File(dir, "settings.gradle.kts").isFile) break
            dir = dir.parentFile
        }
        error("$name not found from ${File(".").absolutePath}")
    }
}
