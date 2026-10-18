package com.gruning.mimir.rewrite.core.metadata

import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.zip.CRC32

/**
 * Computes the fingerprints the metadata layer matches games on — **CRC32** (No-Intro / Redump / MAME DAT
 * primary key, and ScreenScraper's `jeuInfos` `crc` param), **MD5**, **SHA-1**, and byte **size** — in a
 * SINGLE streaming pass with a fixed buffer. It never loads the file into memory, so it is safe over SAF
 * for multi-GB discs (though large files should be hashed lazily / in the background, not on the scan path).
 *
 * This is the privacy primitive: a hash lets the opt-in online path send a fingerprint instead of the
 * user's filename. Pure + local — no network.
 */
object FileHasher {

    /** Stream [input] once → its [ContentHash]. Closes [input]. */
    fun hash(input: InputStream, bufferSize: Int = DEFAULT_BUFFER): ContentHash {
        val crc = CRC32()
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val pooled = bufferSize == DEFAULT_BUFFER
        val buffer = (if (pooled) buffers.poll() else null) ?: ByteArray(bufferSize)
        var total = 0L
        try {
            input.use { stream ->
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                    md5.update(buffer, 0, read)
                    sha1.update(buffer, 0, read)
                    total += read
                }
            }
        } finally {
            // Only bytes [0, read) of a buffer are ever used, so a buffer goes back as it is.
            if (pooled) buffers.offer(buffer)
        }
        return ContentHash(
            crc32 = crc.value.toString(16).padStart(8, '0'),
            md5 = md5.digest().toHex(),
            sha1 = sha1.digest().toHex(),
            sizeBytes = total,
        )
    }

    // 1 MiB, not 64 KiB. A profile of the RG Rotate's identity sweep  had each hashing
    // thread spend ~56% of its time in read() through the SD card's FUSE mount at ~7 MB/s; every read is one FUSE
    // round trip, so a bigger read cuts the round trips sixteen-fold. The bytes hashed are unchanged.
    private const val DEFAULT_BUFFER = 1 shl 20 // 1 MiB

    /**
     * Spare 1 MiB buffers, one per hashing thread (the sweep hashes 2 to 4 files at once). On an
     * RG Rotate (2026-09-28), a new buffer per file caused 14-21 MB/s of large-object allocation;
     * collecting it pauses the UI thread too. A thread
     * that finds the pool empty allocates as before; a full pool drops the extra buffer.
     */
    private val buffers = ArrayBlockingQueue<ByteArray>(4)

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
