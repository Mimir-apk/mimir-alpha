package com.gruning.mimir.rewrite.core.metadata

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchPfs0IdentityReaderTest {

    @Test
    fun `base and update tickets retain raw role before sharing a base family`() = runBlocking {
        val base = identify("0100ABCD12345000")
        val update = identify("0100ABCD12345800")

        base as ContainerIdentityResult.Identified
        update as ContainerIdentityResult.Identified
        assertEquals("0100ABCD12345000", base.value.rawTitleId)
        assertEquals("0100ABCD12345000", base.value.baseTitleId)
        assertEquals(SwitchPackageRole.BASE, base.value.role)
        assertEquals("0100ABCD12345800", update.value.rawTitleId)
        assertEquals("0100ABCD12345000", update.value.baseTitleId)
        assertEquals(SwitchPackageRole.UPDATE, update.value.role)
    }

    @Test
    fun `ticket lookup is random access and never scans an earlier NCA payload`() = runBlocking {
        val rights = rightsId("0100ABCD12345000")
        val nca = ByteArray(64 * 1024) { 0x5a }
        val packageBytes = pfs0(
            "content.nca" to nca,
            "${rights.toHex()}.tik" to ticket(rights),
        )
        val content = RecordingContent(packageBytes)

        val result = SwitchPfs0IdentityReader.identify(content)

        assertTrue(result is ContainerIdentityResult.Identified)
        assertTrue("reader consumed ${content.totalBytesRead} bytes", content.totalBytesRead < 512)
        assertTrue(content.reads.all { it.second <= 64 * 1024 })
    }

    @Test
    fun `ticket filename and signed rights field disagreement requires review`() = runBlocking {
        val payloadRights = rightsId("0100ABCD12345000")
        val differentName = rightsId("0100ABCD99999000").toHex()
        val packageBytes = pfs0("$differentName.tik" to ticket(payloadRights))

        assertEquals(
            ContainerIdentityResult.NeedsReview(ContainerIdentityReason.AMBIGUOUS),
            SwitchPfs0IdentityReader.identify(RecordingContent(packageBytes)),
        )
    }

    @Test
    fun `conflicting tickets never pick the first package identity`() = runBlocking {
        val first = rightsId("0100ABCD12345000")
        val second = rightsId("0100DCBA54321000")
        val packageBytes = pfs0(
            "${first.toHex()}.tik" to ticket(first),
            "${second.toHex()}.tik" to ticket(second),
        )

        assertEquals(
            ContainerIdentityResult.NeedsReview(ContainerIdentityReason.AMBIGUOUS),
            SwitchPfs0IdentityReader.identify(RecordingContent(packageBytes)),
        )
    }

    @Test
    fun `ticketless package and DLC role abstain by explicit policy`() = runBlocking {
        val ticketless = SwitchPfs0IdentityReader.identify(
            RecordingContent(pfs0("content.nca" to ByteArray(8))),
        )
        val dlc = identify("0100ABCD12345001")

        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.NO_CATALOG_MATCH),
            ticketless,
        )
        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.DLC_ROLE_NOT_ADMITTED),
            dlc,
        )
    }

    @Test
    fun `overlapping entries and unsupported signature layouts fail closed`() = runBlocking {
        val rights = rightsId("0100ABCD12345000")
        val overlap = pfs0(
            "first.nca" to ByteArray(16),
            "${rights.toHex()}.tik" to ticket(rights),
        ).also { bytes ->
            // Second PFS0 record's relative data offset.
            for (index in 0 until 8) bytes[0x10 + 0x18 + index] = 0
        }
        val unsupportedTicket = ticket(rights).also {
            it[0] = 0x7f
            it[1] = 0x7f
            it[2] = 0x7f
            it[3] = 0x7f
        }
        val unsupported = pfs0("${rights.toHex()}.tik" to unsupportedTicket)

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            SwitchPfs0IdentityReader.identify(RecordingContent(overlap)),
        )
        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER),
            SwitchPfs0IdentityReader.identify(RecordingContent(unsupported)),
        )
    }

    @Test
    fun `string offsets must begin at a PFS0 string boundary`() = runBlocking {
        val rights = rightsId("0100ABCD12345000")
        val packageBytes = pfs0(
            "junk${rights.toHex()}.tik" to ticket(rights),
        ).also { bytes ->
            // First record's string-table offset points into the middle of the stored name.
            bytes[0x20] = 4
        }

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            SwitchPfs0IdentityReader.identify(RecordingContent(packageBytes)),
        )
    }

    @Test
    fun `random-access cancellation is never collapsed into retryable IO`() {
        val failure = runCatching {
            runBlocking {
                SwitchPfs0IdentityReader.identify(
                    object : RandomAccessContent {
                        override val length = 16L
                        override fun readAt(
                            offset: Long,
                            destination: ByteArray,
                            destinationOffset: Int,
                            byteCount: Int,
                        ): Int = throw CancellationException("cancel fixture")

                        override fun close() = Unit
                    },
                )
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    private suspend fun identify(rawTitleId: String): ContainerIdentityResult<SwitchPayloadIdentity> {
        val rights = rightsId(rawTitleId)
        return SwitchPfs0IdentityReader.identify(
            RecordingContent(pfs0("${rights.toHex()}.tik" to ticket(rights))),
        )
    }

    private fun rightsId(rawTitleId: String): ByteArray =
        hex(rawTitleId + "0000000000000001")

    private fun ticket(rightsId: ByteArray): ByteArray = ByteArray(0x2c0).also { bytes ->
        // RSA-2048 / SHA-256 signature type, little-endian; signed ticket data begins at 0x140.
        bytes[0] = 0x04
        bytes[1] = 0x00
        bytes[2] = 0x01
        bytes[3] = 0x00
        val issuer = "Root-CA00000003-XS00000020".toByteArray(Charsets.US_ASCII)
        issuer.copyInto(bytes, destinationOffset = 0x140)
        bytes[0x280] = 2 // Switch ticket format version
        bytes[0x281] = 0 // common title-key type; the key block itself is never read by the parser
        rightsId.copyInto(bytes, destinationOffset = 0x2a0)
    }

    private fun pfs0(vararg entries: Pair<String, ByteArray>): ByteArray {
        val names = ByteArrayOutputStream()
        val nameOffsets = entries.map { (name, _) ->
            val offset = names.size()
            names.write(name.toByteArray(Charsets.US_ASCII))
            names.write(0)
            offset
        }
        val strings = names.toByteArray()
        val output = ByteArrayOutputStream()
        output.write("PFS0".toByteArray(Charsets.US_ASCII))
        output.writeLe32(entries.size)
        output.writeLe32(strings.size)
        output.writeLe32(0)
        var relativeOffset = 0L
        entries.forEachIndexed { index, (_, bytes) ->
            output.writeLe64(relativeOffset)
            output.writeLe64(bytes.size.toLong())
            output.writeLe32(nameOffsets[index])
            output.writeLe32(0)
            relativeOffset += bytes.size
        }
        output.write(strings)
        entries.forEach { output.write(it.second) }
        return output.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        repeat(4) { write(value ushr (it * 8)) }
    }

    private fun ByteArrayOutputStream.writeLe64(value: Long) {
        repeat(8) { write((value ushr (it * 8)).toInt()) }
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private class RecordingContent(
        private val bytes: ByteArray,
    ) : RandomAccessContent {
        override val length = bytes.size.toLong()
        val reads = mutableListOf<Pair<Long, Int>>()
        var totalBytesRead = 0
            private set

        override fun readAt(
            offset: Long,
            destination: ByteArray,
            destinationOffset: Int,
            byteCount: Int,
        ): Int {
            if (offset !in 0 until length) return 0
            val count = minOf(byteCount, bytes.size - offset.toInt())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + count)
            reads += offset to count
            totalBytesRead += count
            return count
        }

        override fun close() = Unit
    }
}
