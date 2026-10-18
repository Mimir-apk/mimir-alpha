package com.gruning.mimir.rewrite.core.metadata

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MamePayloadCatalogTest {

    @Test
    fun `real release-pinned asset passes every runtime lock`() {
        val directory = listOf(File("../app/src/main/assets/dat"), File("app/src/main/assets/dat"))
            .firstOrNull(File::isDirectory)
            ?: error("bundled asset directory is not visible from the test working directory")
        val catalog = MamePayloadCatalog(
            DatAssetProvider { name -> File(directory, name).takeIf(File::isFile)?.inputStream() },
        )

        assertTrue(catalog.isAvailable)
        assertTrue(catalog.memberIndexAvailable)
        assertEquals(
            MamePayloadManifest.COMPRESSED_BYTES.toLong(),
            File(directory, MamePayloadManifest.ASSET_NAME).length(),
        )
        assertEquals(
            MameMemberManifest.COMPRESSED_BYTES.toLong(),
            File(directory, MameMemberManifest.ASSET_NAME).length(),
        )
    }

    @Test
    fun `renaming and reordering members preserve exact complete set identity`() = runBlocking {
        val first = "first payload".toByteArray()
        val second = byteArrayOf(7, 3, 1, 9)
        val signature = signature(first, second)
        val record = MameSetRecord(
            memberCount = 2,
            totalBytes = (first.size + second.size).toLong(),
            machineName = "fixture",
            cloneRoot = "fixture",
            canonicalTitle = "Fixture Machine",
        )
        val lookup = lookup(signature, record)

        val a = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("one.bin" to first, "two.rom" to second)),
            lookup,
        )
        val b = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("renamed-b.rom" to second, "renamed-a.bin" to first)),
            lookup,
        )

        assertEquals(record, (a as ContainerIdentityResult.Identified).value.record)
        assertEquals(
            (a as ContainerIdentityResult.Identified).value.setSignature,
            (b as ContainerIdentityResult.Identified).value.setSignature,
        )
    }

    @Test
    fun `partial altered and extra sets never inherit the complete-set result`() = runBlocking {
        val first = "first payload".toByteArray()
        val second = "second payload".toByteArray()
        val signature = signature(first, second)
        val record = MameSetRecord(2, (first.size + second.size).toLong(), "fixture", "fixture", "Fixture")
        val lookup = lookup(signature, record)

        val partial = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("one" to first)),
            lookup,
        )
        val altered = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("one" to first, "two" to (second + byteArrayOf(1)))),
            lookup,
        )
        val extra = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("one" to first, "two" to second, "extra" to byteArrayOf(1))),
            lookup,
        )

        listOf(partial, altered, extra).forEach {
            assertEquals(
                ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.NO_CATALOG_MATCH),
                it,
            )
        }
    }

    @Test
    fun `complete observation credits split and zero-byte atoms without minting set identity`() = runBlocking {
        val splitMember = "split dependency member".toByteArray()
        val emptyMember = ByteArray(0)
        val noExactSet = lookup(
            signature = "0".repeat(64),
            record = MameSetRecord(1, 1, "unreachable", "unreachable", "Unreachable"),
        )

        val result = MameZipIdentityReader.observe(
            ByteArrayInputStream(
                zipOf(
                    "split.rom" to splitMember,
                    "empty.prom" to emptyMember,
                ),
            ),
            noExactSet,
        )

        val observed = (result as ContainerIdentityResult.Identified).value
        assertEquals(null, observed.exactSetIdentity)
        assertEquals((splitMember.size + emptyMember.size).toLong(), observed.expandedBytes)
        assertTrue(MameRomAtom(sha1(splitMember), splitMember.size.toLong()) in observed.romAtoms)
        assertTrue(MameRomAtom(sha1(emptyMember), 0L) in observed.romAtoms)
    }

    @Test
    fun `unsupported direct-set shapes terminalize without weakening nested refusal`() = runBlocking {
        val lookup = lookup("0".repeat(64), MameSetRecord(1, 1, "x", "x", "X"))
        val collision = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("ROM/A.BIN" to byteArrayOf(1), "rom/a.bin" to byteArrayOf(2))),
            lookup,
        )
        val nested = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("payload.zip" to byteArrayOf(1, 2, 3))),
            lookup,
        )
        val unsafePath = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("../rom.bin" to byteArrayOf(1))),
            lookup,
        )
        val empty = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf()),
            lookup,
        )

        listOf(collision, nested, unsafePath).forEach { result ->
            assertEquals(
                ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER),
                result,
            )
        }
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
            empty,
        )
    }

    @Test
    fun `entry-count and compression-ratio bombs stop at policy budgets`() = runBlocking {
        val lookup = lookup("0".repeat(64), MameSetRecord(1, 1, "x", "x", "X"))
        val tooMany = (0..MameZipIdentityReader.MAX_ENTRIES).map { "r$it" to byteArrayOf(it.toByte()) }
        val countResult = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf(*tooMany.toTypedArray())),
            lookup,
        )
        val tooManyDirectories = (0..MameZipIdentityReader.MAX_ENTRIES)
            .map { "directory-$it/" to ByteArray(0) }
        val directoryCountResult = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf(*tooManyDirectories.toTypedArray())),
            lookup,
        )
        val ratioResult = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("blank.bin" to ByteArray(1024 * 1024))),
            lookup,
        )

        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            countResult,
        )
        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER),
            directoryCountResult,
        )
        assertEquals(
            ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.RESOURCE_REFUSED),
            ratioResult,
        )
    }

    @Test
    fun `data-bearing directory entry is rejected without draining its payload`() = runBlocking {
        val lookup = lookup("0".repeat(64), MameSetRecord(1, 1, "x", "x", "X"))
        val archive = zipOf("payload/" to ByteArray(8 * 1024 * 1024))
        val counted = CountingInputStream(ByteArrayInputStream(archive))

        val result = MameZipIdentityReader.identify(counted, lookup)

        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.UNSUPPORTED_CONTAINER),
            result,
        )
        assertTrue("reader must close the hostile resident", counted.closed)
        assertTrue(
            "directory rejection must not drain/inflate its hidden payload",
            counted.bytesRead < 64 * 1024,
        )
    }

    @Test
    fun `CRC-corrupt outer zip remains retryable for later resident replay`() = runBlocking {
        val lookup = lookup("0".repeat(64), MameSetRecord(1, 1, "x", "x", "X"))
        val corrupt = corruptFirstEntryPayload(storedZipOf("rom.bin", ByteArray(256) { it.toByte() }))

        val observed = MameZipIdentityReader.observe(
            ByteArrayInputStream(corrupt),
            lookup,
        )
        val result = MameZipIdentityReader.identify(ByteArrayInputStream(corrupt), lookup)

        assertTrue(observed is ContainerIdentityResult.RetryableFailure)
        assertTrue(result is ContainerIdentityResult.RetryableFailure)
    }

    @Test
    fun `zero byte and truncated no-entry residents remain retryable`() = runBlocking {
        val lookup = lookup("0".repeat(64), MameSetRecord(1, 1, "x", "x", "X"))
        val truncatedLocalHeader = byteArrayOf(
            0x50, 0x4b, 0x03, 0x04,
            0x14, 0x00, 0x00, 0x00,
        )

        listOf(ByteArray(0), truncatedLocalHeader).forEach { resident ->
            assertEquals(
                ContainerIdentityResult.RetryableFailure(ContainerIdentityReason.MALFORMED),
                MameZipIdentityReader.identify(ByteArrayInputStream(resident), lookup),
            )
        }
    }

    @Test
    fun `small repetitive ROM remains eligible under absolute archive budgets`() = runBlocking {
        val rom = ByteArray(64 * 1024)
        val signature = signature(rom)
        val record = MameSetRecord(1, rom.size.toLong(), "fixture", "fixture", "Fixture")

        val result = MameZipIdentityReader.identify(
            ByteArrayInputStream(zipOf("prom.bin" to rom)),
            lookup(signature, record),
        )

        assertEquals(record, (result as ContainerIdentityResult.Identified).value.record)
    }

    @Test
    fun `loose required member is typed member-only and never a complete game`() = runBlocking {
        val member = "one exact MAME member".toByteArray()
        val digest = MessageDigest.getInstance("SHA-1").digest(member)
        val lookup = object : MameMemberLookup {
            override val memberIndexAvailable = true

            override fun containsMember(sha1: ByteArray, sizeBytes: Long): Boolean =
                sha1.contentEquals(digest) && sizeBytes == member.size.toLong()
        }

        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.MEMBER_ONLY),
            MameLooseMemberReader.classify(ByteArrayInputStream(member), lookup),
        )
        assertEquals(
            ContainerIdentityResult.PolicyAbstain(ContainerIdentityReason.NO_CATALOG_MATCH),
            MameLooseMemberReader.classify(
                ByteArrayInputStream("different member".toByteArray()),
                lookup,
            ),
        )

        val observed = MameLooseMemberReader.observe(ByteArrayInputStream(member), lookup)
        val value = (observed as ContainerIdentityResult.Identified).value
        assertEquals(MameRomAtom(sha1(member), member.size.toLong()), value.atom)
        assertTrue(value.inCatalog)
    }

    @Test
    fun `member-reader cancellation is rethrown and closes its resident`() {
        var closed = false
        val input = object : java.io.InputStream() {
            override fun read(): Int = throw CancellationException("cancel fixture")
            override fun close() {
                closed = true
            }
        }
        val lookup = object : MameMemberLookup {
            override val memberIndexAvailable = true
            override fun containsMember(sha1: ByteArray, sizeBytes: Long) = false
        }

        val failure = runCatching {
            runBlocking { MameLooseMemberReader.classify(input, lookup) }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(closed)
    }

    @Test
    fun `set-reader cancellation is rethrown and closes its resident`() {
        val archive = zipOf("rom.bin" to ByteArray(4096) { it.toByte() })
        val input = CancellingInputStream(ByteArrayInputStream(archive), cancelAfterBytes = 64)
        val lookup = lookup("0".repeat(64), MameSetRecord(1, 1, "x", "x", "X"))

        val failure = runCatching {
            runBlocking { MameZipIdentityReader.identify(input, lookup) }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(input.closed)
    }

    @Test
    fun `already-cancelled set observation closes its resident before doing work`() {
        val input = CountingInputStream(
            ByteArrayInputStream(zipOf("rom.bin" to byteArrayOf(1, 2, 3))),
        )
        val lookup = lookup("0".repeat(64), MameSetRecord(1, 1, "x", "x", "X"))

        val failure = runCatching {
            runBlocking {
                coroutineContext.cancel()
                MameZipIdentityReader.observe(input, lookup)
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(input.closed)

        val unavailableInput = CountingInputStream(ByteArrayInputStream(ByteArray(0)))
        val unavailableLookup = object : MameSetLookup {
            override val isAvailable = false
            override fun set(signature: String): MameSetRecord? = null
        }
        val unavailableFailure = runCatching {
            runBlocking {
                coroutineContext.cancel()
                MameZipIdentityReader.observe(unavailableInput, unavailableLookup)
            }
        }.exceptionOrNull()

        assertTrue(unavailableFailure is CancellationException)
        assertTrue(unavailableInput.closed)
    }

    @Test
    fun `already-cancelled loose observation closes its resident before doing work`() {
        val input = CountingInputStream(ByteArrayInputStream("member".toByteArray()))
        val lookup = object : MameMemberLookup {
            override val memberIndexAvailable = true
            override fun containsMember(sha1: ByteArray, sizeBytes: Long) = false
        }

        val failure = runCatching {
            runBlocking {
                coroutineContext.cancel()
                MameLooseMemberReader.observe(input, lookup)
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(input.closed)

        val unavailableInput = CountingInputStream(ByteArrayInputStream(ByteArray(0)))
        val unavailableLookup = object : MameMemberLookup {
            override val memberIndexAvailable = false
            override fun containsMember(sha1: ByteArray, sizeBytes: Long) = false
        }
        val unavailableFailure = runCatching {
            runBlocking {
                coroutineContext.cancel()
                MameLooseMemberReader.observe(unavailableInput, unavailableLookup)
            }
        }.exceptionOrNull()

        assertTrue(unavailableFailure is CancellationException)
        assertTrue(unavailableInput.closed)
    }

    private fun lookup(signature: String, record: MameSetRecord): MameSetLookup =
        object : MameSetLookup {
            override val isAvailable = true
            override fun set(signatureToFind: String): MameSetRecord? =
                record.takeIf { signatureToFind == signature }
        }

    private fun signature(vararg payloads: ByteArray): String {
        val members = payloads.map { sha1(it) to it.size.toLong() }
        val canonical = members.sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString(separator = "") { "${it.first}:${it.second}\n" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.US_ASCII))
            .toHex()
    }

    private fun sha1(bytes: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun storedZipOf(name: String, bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val crc = CRC32().apply { update(bytes) }
            val entry = ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = bytes.size.toLong()
                this.crc = crc.value
            }
            zip.putNextEntry(entry)
            zip.write(bytes)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun corruptFirstEntryPayload(archive: ByteArray): ByteArray = archive.copyOf().also { bytes ->
        val nameLength = (bytes[26].toInt() and 0xff) or ((bytes[27].toInt() and 0xff) shl 8)
        val extraLength = (bytes[28].toInt() and 0xff) or ((bytes[29].toInt() and 0xff) shl 8)
        val payloadOffset = 30 + nameLength + extraLength
        bytes[payloadOffset] = (bytes[payloadOffset].toInt() xor 0x5a).toByte()
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead = 0L
            private set
        var closed = false
            private set

        override fun read(): Int = super.read().also { if (it >= 0) bytesRead++ }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) bytesRead += it }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class CancellingInputStream(
        input: InputStream,
        private val cancelAfterBytes: Int,
    ) : FilterInputStream(input) {
        private var bytesRead = 0
        var closed = false
            private set

        override fun read(): Int {
            if (bytesRead >= cancelAfterBytes) throw CancellationException("cancel fixture")
            return super.read().also { if (it >= 0) bytesRead++ }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (bytesRead >= cancelAfterBytes) throw CancellationException("cancel fixture")
            val allowed = minOf(length, cancelAfterBytes - bytesRead)
            return super.read(buffer, offset, allowed).also { if (it > 0) bytesRead += it }
        }

        override fun close() {
            closed = true
            super.close()
        }
    }
}
