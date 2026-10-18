package com.gruning.mimir.rewrite.core.metadata

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Privacy-safe real-codec proof over the two opaque-staged private CHDv5 fixtures. The fixtures are
 * supplied as instrumentation arguments (`athena.private.chd.a` / `athena.private.chd.b`) pointing at
 * temporary opaque copies; without both arguments the test is an explicit JUnit assumption SKIP, never
 * a silent pass. Nothing private is asserted, logged or thrown: evidence is limited to codec classes,
 * bucketed elapsed time, descriptor-leak delta, cancellation cleanup and pass/fail.
 */
@RunWith(AndroidJUnit4::class)
class AthenaA1DPrivateChdRealCodecDeviceTest {

    @Test
    fun privateCompressedChdRecomputesEveryHunkAndChecksumMetadata() {
        val arguments = InstrumentationRegistry.getArguments()
        val staged = listOfNotNull(
            arguments.getString("athena.private.chd.a"),
            arguments.getString("athena.private.chd.b"),
        ).map(::File)
        assumeTrue("private CHD fixtures are not staged on this device", staged.size == 2)
        staged.forEachIndexed { index, file ->
            assumeTrue("staged fixture ${index + 1} is absent", file.isFile)

            val label = "fixture-${index + 1}"
            // Read failures rethrow sanitized: an IO exception's own message may carry the staged path.
            val head = try {
                file.inputStream().use { stream ->
                    val buffer = ByteArray(CHD_HEADER_READ_BYTES)
                    var read = 0
                    while (read < buffer.size) {
                        val r = stream.read(buffer, read, buffer.size - read)
                        if (r < 0) break
                        read += r
                    }
                    buffer to read
                }
            } catch (_: Exception) {
                throw AssertionError("$label is unreadable on this device")
            }
            val header = requireNotNull(RomFingerprinter.chdHeaderEvidence(head.first, head.second)) {
                "$label must carry a parseable CHD header claim"
            }
            assertEquals("$label must be CHDv5", 5, header.version)
            // assertTrue, never a value-printing assertion: a parent digest is title-identifying.
            assertTrue("$label must be standalone", header.parentSha1 == null)
            val codecs = header.compressionTags.filter { it != 0 }.map(::fourCc)
            assertTrue("$label must use a real compressed map", codecs.isNotEmpty())

            val before = openDescriptorCount()

            // Cancellation cleanup: cancel one verification mid-decode, then prove no descriptor leak.
            // The worker runs on IO so the cancel actually lands between hunk reads.
            val cancelOutcome = runBlocking {
                val job = launch(Dispatchers.IO) {
                    ChdChainPayloadVerifier.verify(
                        listOf(ChdVerificationNode(file.absolutePath, header)),
                    )
                }
                delay(200)
                job.cancel()
                job.join()
                if (job.isCancelled) "cancelled" else "completed-before-cancel"
            }
            val afterCancel = openDescriptorCount()
            // Leak proof is deterministic: a leaked native session would hold a descriptor whose
            // target is the staged fixture; total-count deltas stay log-only (runtime fds drift).
            assertEquals("$label fixture-held descriptors after cancellation", 0, fixtureFdCount())

            val startedNanos = System.nanoTime()
            val result = runBlocking {
                ChdChainPayloadVerifier.verify(
                    listOf(ChdVerificationNode(file.absolutePath, header)),
                )
            }
            val elapsedSecondsBucket = ((System.nanoTime() - startedNanos) / 1_000_000_000L).toInt() + 1
            val afterVerify = openDescriptorCount()

            // Typed outcomes are enum vocabulary, never content: safe in evidence and assertions.
            val outcomeLabel = when (result) {
                is ContainerIdentityResult.Identified -> "Identified"
                is ContainerIdentityResult.PolicyAbstain -> "PolicyAbstain(${result.reason})"
                is ContainerIdentityResult.NeedsReview -> "NeedsReview(${result.reason})"
                is ContainerIdentityResult.RetryableFailure -> "RetryableFailure(${result.reason})"
            }
            // The only published evidence: typed outcome, codec classes, bucketed elapsed, deltas.
            Log.i(
                "AthenaA1DT7",
                "$label outcome=$outcomeLabel codecs=$codecs elapsedBucket<=${elapsedSecondsBucket}s " +
                    "cancel=$cancelOutcome fdDeltaCancel=${afterCancel - before} " +
                    "fdDeltaVerify=${afterVerify - before}",
            )
            assertTrue(
                "$label must fully verify (raw + checksummed metadata recomputed); got $outcomeLabel",
                result is ContainerIdentityResult.Identified,
            )
            val identity = (result as ContainerIdentityResult.Identified).value
            assertEquals("$label chain depth", 1, identity.chainDepth)
            assertEquals("$label fixture-held descriptors after verification", 0, fixtureFdCount())
        }
    }

    private fun openDescriptorCount(): Int = File("/proc/self/fd").list()?.size ?: -1

    /** Descriptors whose resolved target is a staged opaque fixture — exactly what a leak would hold. */
    private fun fixtureFdCount(): Int = File("/proc/self/fd").listFiles()?.count { fd ->
        runCatching { fd.canonicalPath }.getOrNull()?.contains("mimir-opaque") == true
    } ?: -1

    private fun fourCc(tag: Int): String = buildString {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val c = (tag ushr shift) and 0xff
            append(if (c in 0x20..0x7e) c.toChar() else '?')
        }
    }

    private companion object {
        const val CHD_HEADER_READ_BYTES = 132
    }
}
