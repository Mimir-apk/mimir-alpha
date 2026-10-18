package com.gruning.mimir.rewrite.core.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertTrue
import org.junit.Test

class ResidentHashProbeTest {

    /** Minimal single-entry zip — this file only needs ONE fixture, so it doesn't pull in
     *  RomFingerprinterTest's fuller helper; see that file for the zip-entry-selection unit tests. */
    private fun zipOf(entryName: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(data)
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `fingerprints the stream the opener supplies`() = runBlocking {
        val probe = ResidentHashProbe(ContentOpener { _, _ -> ByteArrayInputStream("abc".toByteArray()) })
        val h = probe.fingerprint(documentUri = "content://games/1", managedPath = null)
        assertEquals("352441c2", h?.crc32)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", h?.sha1)
        assertEquals(3L, h?.sizeBytes)
    }

    @Test
    fun `returns null (never throws) when the content cannot be opened`() = runBlocking {
        val probe = ResidentHashProbe(ContentOpener { _, _ -> null })
        assertNull(probe.fingerprint("content://revoked-grant", null))
    }

    @Test
    fun `format-aware evidence returns DAT hash and raw provenance from one opener pass`() = runBlocking {
        var opens = 0
        val probe = ResidentHashProbe(
            ContentOpener { _, _ ->
                opens++
                ByteArrayInputStream("abc".toByteArray())
            },
        )
        val evidence = probe.fingerprintEvidenceFor("gb", null, "/games/a.gb")
        assertEquals(1, opens)
        assertEquals("352441c2", evidence?.datHash?.crc32)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", evidence?.exactRawSha1)
    }

    // ── S2 wiring: ResidentHashProbe must derive a real file NAME (not just open a stream) so
    // RomFingerprinter can recognise a zip — these cover the URI/path parsing this class owns, distinct
    // from RomFingerprinterTest's coverage of the zip-entry-selection logic itself.

    @Test
    fun `a managedPath ending in zip resolves through the entry, not the container`() = runBlocking {
        val rom = "exact rom bytes".toByteArray()
        val probe = ResidentHashProbe(ContentOpener { _, _ -> ByteArrayInputStream(zipOf("game.gb", rom)) })
        val evidence = probe.fingerprintEvidenceFor("gb", documentUri = null, managedPath = "/sdcard/GB/game.zip")
        assertEquals(FileHasher.hash(ByteArrayInputStream(rom)).sha1, evidence?.datHash?.sha1)
    }

    @Test
    fun `a URL-encoded SAF documentUri is decoded before the zip check`() = runBlocking {
        val rom = "exact rom bytes".toByteArray()
        val probe = ResidentHashProbe(ContentOpener { _, _ -> ByteArrayInputStream(zipOf("game.gb", rom)) })
        // "primary:Roms/GB/game.zip" as a real SAF tree document id would arrive percent-encoded.
        val uri = "content://com.android.externalstorage.documents/document/primary%3ARoms%2FGB%2Fgame.zip"
        val evidence = probe.fingerprintEvidenceFor("gb", documentUri = uri, managedPath = null)
        assertEquals(FileHasher.hash(ByteArrayInputStream(rom)).sha1, evidence?.datHash?.sha1)
    }

    @Test
    fun `typed probe keeps transient read failure retryable`() = runBlocking {
        val probe = ResidentHashProbe(
            ContentOpener { _, _ ->
                object : java.io.InputStream() {
                    override fun read(): Int = throw IOException("transient fixture")
                }
            },
        )

        assertEquals(
            ResidentFingerprintResult.Retryable,
            probe.fingerprintEvidenceResultFor("gb", null, "/games/a.gb"),
        )
    }

    @Test
    fun `typed probe never swallows cancellation`() {
        val probe = ResidentHashProbe(
            ContentOpener { _, _ ->
                object : java.io.InputStream() {
                    override fun read(): Int = throw CancellationException("cancel fixture")
                }
            },
        )

        val failure = runCatching {
            runBlocking {
                probe.fingerprintEvidenceResultFor("gb", null, "/games/a.gb")
            }
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }
}
