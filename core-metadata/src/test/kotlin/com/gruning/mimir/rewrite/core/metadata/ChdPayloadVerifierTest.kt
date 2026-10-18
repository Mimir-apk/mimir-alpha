package com.gruning.mimir.rewrite.core.metadata

import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChdPayloadVerifierTest {

    @Test
    fun `libchdr identifies only after raw and checksum metadata digests agree`() = runBlocking {
        val payload = "ten-bytes!".toByteArray()
        val rawSha1 = sha1(payload)
        val metadata = listOf(
            ChdMetadataRead.Checksummed(tag("CHT2"), "track metadata".toByteArray()),
            ChdMetadataRead.NotChecksummed,
        )
        val session = FakeSession(payload, rawSha1 = rawSha1, metadata = metadata)
        val result = ChdPayloadVerifier.verify(
            localPath = "/fixture.chd",
            header = header(payload.size.toLong(), rawSha1, session.combinedSha1),
            backend = ChdVerifierBackend { ContainerIdentityResult.Identified(session) },
        )

        result as ContainerIdentityResult.Identified
        assertEquals(rawSha1, result.value.rawSha1)
        assertEquals(session.combinedSha1, result.value.combinedSha1)
        assertTrue(session.closed)
        assertEquals(listOf(0, 1, 2), session.reads)
    }

    @Test
    fun `copied header with different body cannot launder its catalog digest`() = runBlocking {
        val declaredPayload = "catalog-body".toByteArray()
        val actualPayload = "changed-body".toByteArray()
        val declaredRaw = sha1(declaredPayload)
        val session = FakeSession(actualPayload, rawSha1 = declaredRaw)

        val result = ChdPayloadVerifier.verify(
            localPath = "/fixture.chd",
            header = header(actualPayload.size.toLong(), declaredRaw, session.combinedSha1),
            backend = ChdVerifierBackend { ContainerIdentityResult.Identified(session) },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            result,
        )
        assertTrue(session.closed)
    }

    @Test
    fun `valid raw payload with forged combined digest never identifies`() = runBlocking {
        val payload = "valid raw payload".toByteArray()
        val raw = sha1(payload)
        val forged = "f".repeat(40)
        val session = FakeSession(
            payload = payload,
            rawSha1 = raw,
            metadata = listOf(ChdMetadataRead.Checksummed(tag("CHT2"), "real metadata".toByteArray())),
            advertisedCombinedSha1 = forged,
        )

        val result = ChdPayloadVerifier.verify(
            "/forged-combined.chd",
            header(payload.size.toLong(), raw, forged),
            ChdVerifierBackend { ContainerIdentityResult.Identified(session) },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            result,
        )
        assertTrue(session.closed)
    }

    @Test
    fun `version three uses its sole sha1 as raw and overall identity`() = runBlocking {
        val payload = "legacy-v3-payload".toByteArray()
        val raw = sha1(payload)
        val session = FakeSession(
            payload = payload,
            rawSha1 = raw,
            advertisedCombinedSha1 = raw,
            advertisedVersion = 3,
        )

        val result = ChdPayloadVerifier.verify(
            "/legacy-v3.chd",
            header(payload.size.toLong(), raw, raw).copy(version = 3),
            ChdVerifierBackend { ContainerIdentityResult.Identified(session) },
        )

        result as ContainerIdentityResult.Identified
        assertEquals(3, result.value.version)
        assertEquals(raw, result.value.rawSha1)
        assertEquals(raw, result.value.combinedSha1)
        assertEquals(emptyList<Int>(), session.metadataReads)
    }

    @Test
    fun `post-open hunk and metadata failures retain typed policy`() = runBlocking {
        val payload = "typed-native-read".toByteArray()
        val raw = sha1(payload)
        val ioSession = FakeSession(
            payload = payload,
            rawSha1 = raw,
            hunkRead = ChdHunkRead.IO_RETRYABLE,
        )
        val unsupportedMetadata = FakeSession(
            payload = payload,
            rawSha1 = raw,
            metadata = listOf(ChdMetadataRead.Unsupported),
        )

        val ioResult = ChdPayloadVerifier.verify(
            "/temporarily-unreadable.chd",
            header(payload.size.toLong(), raw, ioSession.combinedSha1),
            ChdVerifierBackend { ContainerIdentityResult.Identified(ioSession) },
        )
        val unsupportedResult = ChdPayloadVerifier.verify(
            "/unsupported-metadata.chd",
            header(payload.size.toLong(), raw, unsupportedMetadata.combinedSha1),
            ChdVerifierBackend { ContainerIdentityResult.Identified(unsupportedMetadata) },
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.IO_RETRYABLE),
            ioResult,
        )
        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER),
            unsupportedResult,
        )
    }

    @Test
    fun `parent-required CHD abstains before native open`() = runBlocking {
        var opened = false
        val result = ChdPayloadVerifier.verify(
            localPath = "/child.chd",
            header = header(8, "1".repeat(40), "1".repeat(40))
                .copy(parentSha1 = "2".repeat(40)),
            backend = ChdVerifierBackend {
                opened = true
                error("must not open a parentless child")
            },
        )

        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.PARENT_REQUIRED),
            result,
        )
        assertFalse(opened)
    }

    @Test
    fun `hunk and wall-time budgets leave evidence retryable and close native state`() = runBlocking {
        val payload = ByteArray(8)
        val raw = sha1(payload)
        val oversized = FakeSession(payload, rawSha1 = raw, advertisedHunkBytes = 9 * 1024 * 1024)
        var oversizedOpened = false
        val oversizedResult = ChdPayloadVerifier.verify(
            "/oversized.chd",
            header(payload.size.toLong(), raw, oversized.combinedSha1)
                .copy(hunkBytes = oversized.hunkBytes),
            ChdVerifierBackend {
                oversizedOpened = true
                ContainerIdentityResult.Identified(oversized)
            },
        )
        var now = 0L
        val timed = object : ChdReadSession {
            override val version = 5
            override val logicalBytes = payload.size.toLong()
            override val hunkBytes = 4
            override val hunkCount = ((payload.size + hunkBytes - 1) / hunkBytes).coerceAtLeast(1)
            override val rawSha1 = raw
            override val combinedSha1 = raw
            var closed = false

            override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                destination.fill(0)
                val start = index * hunkBytes
                payload.copyInto(destination, 0, start, minOf(payload.size, start + hunkBytes))
                return ChdHunkRead.OK
            }

            override fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead = ChdMetadataRead.End

            override fun close() {
                closed = true
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )
        val timedResult = ChdPayloadVerifier.verify(
            "/slow.chd",
            header(payload.size.toLong(), raw, raw),
            ChdVerifierBackend { ContainerIdentityResult.Identified(timed) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            oversizedResult,
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            timedResult,
        )
        assertFalse(oversizedOpened)
        assertFalse(oversized.closed)
        assertTrue(timed.closed)
    }

    @Test
    fun `declared hunk count is bounded and reconciled before native open`() = runBlocking {
        val raw = "1".repeat(40)
        var opened = false
        val backend = ChdVerifierBackend {
            opened = true
            error("hostile header must never reach native open")
        }

        val excessive = ChdPayloadVerifier.verify(
            "/excessive-map.chd",
            header(4L, raw, raw).copy(declaredHunkCount = 1_000_001L),
            backend,
        )
        val inconsistent = ChdPayloadVerifier.verify(
            "/inconsistent-map.chd",
            header(4L, raw, raw).copy(declaredHunkCount = 2L),
            backend,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            excessive,
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            inconsistent,
        )
        assertFalse(opened)
    }

    @Test
    fun `standalone verifier refuses when open consumes deadline`() = runBlocking {
        val payload = ByteArray(8)
        val raw = sha1(payload)
        var now = 0L
        val session = FakeSession(payload, rawSha1 = raw)
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val result = ChdPayloadVerifier.verify(
            "/slow-open.chd",
            header(payload.size.toLong(), raw, session.combinedSha1),
            ChdVerifierBackend {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                ContainerIdentityResult.Identified(session)
            },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            result,
        )
        assertTrue(session.closed)
    }

    @Test
    fun `slow open PolicyAbstain becomes resource refused when budget expires`() = runBlocking {
        var now = 0L
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val result = ChdPayloadVerifier.verify(
            "/slow-abstain-open.chd",
            header(8L, "1".repeat(40), "2".repeat(40)),
            ChdVerifierBackend {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER)
            },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            result,
        )
    }

    @Test
    fun `slow hunk Unsupported becomes resource refused when budget expires`() = runBlocking {
        val payload = ByteArray(8)
        val raw = sha1(payload)
        var now = 0L
        val session = object : ChdReadSession {
            override val version = 5
            override val logicalBytes = payload.size.toLong()
            override val hunkBytes = 4
            override val hunkCount = ((payload.size + hunkBytes - 1) / hunkBytes).coerceAtLeast(1)
            override val rawSha1 = raw
            override val combinedSha1 = raw
            var closed = false

            override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                return ChdHunkRead.UNSUPPORTED
            }

            override fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead = ChdMetadataRead.End

            override fun close() {
                closed = true
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val result = ChdPayloadVerifier.verify(
            "/slow-hunk-unsupported.chd",
            header(payload.size.toLong(), raw, session.combinedSha1),
            ChdVerifierBackend { ContainerIdentityResult.Identified(session) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            result,
        )
        assertTrue(session.closed)
    }

    @Test
    fun `slow metadata Unsupported becomes resource refused when budget expires`() = runBlocking {
        val payload = "v4-payload!".toByteArray()
        val raw = sha1(payload)
        var now = 0L
        val session = object : ChdReadSession {
            override val version = 5
            override val logicalBytes = payload.size.toLong()
            override val hunkBytes = 4
            override val hunkCount = ((payload.size + hunkBytes - 1) / hunkBytes).coerceAtLeast(1)
            override val rawSha1 = raw
            override val combinedSha1 = overall(raw, emptyList())
            var closed = false

            override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead {
                destination.fill(0)
                val start = index * hunkBytes
                payload.copyInto(destination, 0, start, minOf(payload.size, start + hunkBytes))
                return ChdHunkRead.OK
            }

            override fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead {
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                return ChdMetadataRead.Unsupported
            }

            override fun close() {
                closed = true
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val result = ChdPayloadVerifier.verify(
            "/slow-metadata-unsupported.chd",
            header(payload.size.toLong(), raw, session.combinedSha1),
            ChdVerifierBackend { ContainerIdentityResult.Identified(session) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            result,
        )
        assertTrue(session.closed)
    }

    @Test
    fun `standalone verifier refuses after slow final End read crosses deadline`() = runBlocking {
        val payload = "v4-payload!".toByteArray()
        val raw = sha1(payload)
        val metadata = listOf(ChdMetadataRead.Checksummed(tag("CHT2"), "track metadata".toByteArray()))
        var now = 0L
        val session = object : ChdReadSession {
            override val version = 5
            override val logicalBytes = payload.size.toLong()
            override val hunkBytes = 4
            override val hunkCount = ((payload.size + hunkBytes - 1) / hunkBytes).coerceAtLeast(1)
            override val rawSha1 = raw
            override val combinedSha1 = overall(raw, metadata)
            var closed = false

            override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead {
                destination.fill(0)
                val start = index * hunkBytes
                payload.copyInto(destination, 0, start, minOf(payload.size, start + hunkBytes))
                return ChdHunkRead.OK
            }

            override fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead {
                if (index < metadata.size) {
                    return metadata[index]
                }
                now = ChdVerificationBudget.MIN_ADMISSION_NANOS + 2
                return ChdMetadataRead.End
            }

            override fun close() {
                closed = true
            }
        }
        val budget = ChdVerificationBudget.forResolve(
            nanoTime = { now },
            absoluteDeadlineNanos = ChdVerificationBudget.MIN_ADMISSION_NANOS + 1,
        )

        val result = ChdPayloadVerifier.verify(
            "/slow-end.chd",
            header(payload.size.toLong(), raw, session.combinedSha1),
            ChdVerifierBackend { ContainerIdentityResult.Identified(session) },
            budget = budget,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            result,
        )
        assertTrue(session.closed)
    }

    @Test
    fun `cancellation is rethrown and still closes native state`() {
        var closed = false
        val session = object : ChdReadSession {
            override val version = 5
            override val logicalBytes = 4L
            override val hunkBytes = 4
            override val hunkCount = 1
            override val rawSha1 = "1".repeat(40)
            override val combinedSha1 = "2".repeat(40)

            override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead {
                throw CancellationException("cancel fixture")
            }

            override fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead =
                ChdMetadataRead.End

            override fun close() {
                closed = true
            }
        }

        val failure = runCatching {
            runBlocking {
                ChdPayloadVerifier.verify(
                    "/cancelled.chd",
                    header(4L, session.rawSha1, session.combinedSha1),
                    ChdVerifierBackend { ContainerIdentityResult.Identified(session) },
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(closed)
    }

    private fun header(logicalBytes: Long, rawSha1: String, combinedSha1: String) = ChdHeaderEvidence(
        version = 5,
        logicalBytes = logicalBytes,
        hunkBytes = 4,
        rawSha1 = rawSha1,
        combinedSha1 = combinedSha1,
        parentSha1 = null,
    )

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1")
        .digest(bytes)
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun overall(rawSha1: String, metadata: List<ChdMetadataRead>): String {
        val records = metadata.filterIsInstance<ChdMetadataRead.Checksummed>().map { entry ->
            ByteArray(24).also { record ->
                repeat(4) { index ->
                    record[index] = (entry.tag ushr ((3 - index) * 8)).toByte()
                }
                MessageDigest.getInstance("SHA-1").digest(entry.bytes).copyInto(record, 4)
            }
        }.sortedWith { left, right ->
            left.indices.firstNotNullOfOrNull { index ->
                ((left[index].toInt() and 0xff) - (right[index].toInt() and 0xff))
                    .takeIf { it != 0 }
            } ?: 0
        }
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(hex(rawSha1))
        records.forEach(digest::update)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun hex(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun tag(value: String): Int {
        require(value.length == 4)
        return value.fold(0) { result, character -> (result shl 8) or character.code }
    }

    private class FakeSession(
        private val payload: ByteArray,
        override val rawSha1: String,
        advertisedHunkBytes: Int = 4,
        private val metadata: List<ChdMetadataRead> = emptyList(),
        advertisedCombinedSha1: String? = null,
        private val advertisedVersion: Int = 5,
        private val hunkRead: ChdHunkRead = ChdHunkRead.OK,
    ) : ChdReadSession {
        override val version = advertisedVersion
        override val logicalBytes = payload.size.toLong()
        override val hunkBytes = advertisedHunkBytes
        override val hunkCount = ((payload.size + hunkBytes - 1) / hunkBytes).coerceAtLeast(1)
        override val combinedSha1 =
            advertisedCombinedSha1 ?: ChdPayloadVerifierTest().overall(rawSha1, metadata)
        val reads = mutableListOf<Int>()
        val metadataReads = mutableListOf<Int>()
        var closed = false
            private set

        override fun readHunk(index: Int, destination: ByteArray): ChdHunkRead {
            reads += index
            if (hunkRead != ChdHunkRead.OK) return hunkRead
            destination.fill(0)
            val start = index * hunkBytes
            if (start >= payload.size) return ChdHunkRead.MALFORMED
            val count = minOf(hunkBytes, payload.size - start)
            payload.copyInto(destination, 0, start, start + count)
            return ChdHunkRead.OK
        }

        override fun readMetadata(index: Int, maxBytes: Int): ChdMetadataRead {
            metadataReads += index
            return metadata.getOrNull(index) ?: ChdMetadataRead.End
        }

        override fun close() {
            closed = true
        }
    }

}
