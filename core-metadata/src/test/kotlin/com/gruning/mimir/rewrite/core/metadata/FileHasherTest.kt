package com.gruning.mimir.rewrite.core.metadata

import org.junit.Assert.assertEquals
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertThrows
import org.junit.Test

/** Known-answer tests: FileHasher must match the standard CRC32/MD5/SHA-1 reference vectors. */
class FileHasherTest {

    @Test
    fun `hashes the abc reference vector`() {
        val h = FileHasher.hash(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("352441c2", h.crc32)
        assertEquals("900150983cd24fb0d6963f7d28e17f72", h.md5)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", h.sha1)
        assertEquals(3L, h.sizeBytes)
    }

    @Test
    fun `hashes the empty input`() {
        val h = FileHasher.hash(ByteArrayInputStream(ByteArray(0)))
        assertEquals("00000000", h.crc32)
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", h.md5)
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", h.sha1)
        assertEquals(0L, h.sizeBytes)
    }

    @Test
    fun `a tiny buffer streams to the same result (no single-shot assumption)`() {
        val data = ByteArray(4096) { (it % 251).toByte() } // non-trivial, spans many buffer fills
        val oneShot = FileHasher.hash(ByteArrayInputStream(data))
        val streamed = FileHasher.hash(ByteArrayInputStream(data), bufferSize = 7)
        assertEquals(oneShot, streamed)
        assertEquals(4096L, streamed.sizeBytes)
    }

    /** The pooled buffers are shared by the hashing threads; no thread may see another's bytes. */
    @Test
    fun `hashes on many threads at once match hashing one at a time`() {
        val inputs = (0 until 24).map { seed -> ByteArray(3 * (1 shl 20) + seed * 977) { ((it * 31 + seed) % 253).toByte() } }
        val expected = inputs.map { FileHasher.hash(ByteArrayInputStream(it)) }
        val pool = Executors.newFixedThreadPool(6)
        try {
            val actual = pool.invokeAll(inputs.map { data -> Callable { FileHasher.hash(ByteArrayInputStream(data)) } })
                .map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(expected, actual)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a read that fails part way leaves the next hash correct`() {
        val broken = object : InputStream() {
            var served = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 0) throw IOException("card removed")
                b.fill(0x5A, off, off + len)
                served += len
                return len
            }
        }
        assertThrows(IOException::class.java) { FileHasher.hash(broken) }
        val h = FileHasher.hash(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("352441c2", h.crc32)
        assertEquals(3L, h.sizeBytes)
    }
}
