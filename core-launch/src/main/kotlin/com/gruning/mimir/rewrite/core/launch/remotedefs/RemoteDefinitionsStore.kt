package com.gruning.mimir.rewrite.core.launch.remotedefs

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger

/** Where a simulated crash can land inside [RemoteDefinitionsStore.commit] (the T18 test seam). */
enum class StoreStep { AFTER_TMP_WRITE, AFTER_OVERLAY_RENAME, BEFORE_POINTER_SWITCH, AFTER_POINTER_SWITCH }

/**
 * Last-good storage for one overlay, in one directory the app owns:
 *
 *   overlay-<rawSha256>.json   the received bytes, named by their hash (content addressed)
 *   pointer                    ONE line naming the live file, switched by an atomic rename
 *   *.tmp                      in-flight writes, removed at the next start
 *
 * Commit order: write overlay.tmp and fsync, read it back and check its hash, ATOMIC_MOVE to the final
 * name, write pointer.tmp and fsync, ATOMIC_MOVE over `pointer`. A crash at any step leaves the previous pointer
 * valid, so a reader sees the old overlay or the new one whole, never a mix. Reads never trust the pointer alone:
 * [readVerified] re-hashes the file it names.
 *
 * One instance per directory per process (it is the process lock): commits are serialised and compare-and-swap on
 * the raw hash the caller last read, so two racing refreshes cannot interleave writes.
 */
class RemoteDefinitionsStore(
    private val dir: File,
    private val faultHook: (StoreStep) -> Unit = {},
) {
    /** What the pointer line says. [etag] and [fetchedAtMillis] are display/bandwidth hints, never identity. */
    data class Record(
        val rawSha256: String,
        val ordinal: Long,
        val schema: Int,
        val canonicalSha256: String,
        val etag: String?,
        val fetchedAtMillis: Long?,
    )

    data class Verified(val record: Record, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean =
            other is Verified && record == other.record && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * record.hashCode() + bytes.contentHashCode()
    }

    sealed interface CommitResult {
        data object Committed : CommitResult

        /** The stored overlay is not the one the caller read: decide again against [current]. */
        data class Conflict(val current: Record?) : CommitResult
    }

    private val lock = Any()
    private val commits = AtomicInteger(0)

    /** How many times a commit actually wrote files. A no-op refresh must leave this unchanged. */
    val commitCount: Int get() = commits.get()

    init {
        dir.mkdirs()
        removeStaleTemps()
        // A crash after the pointer switch (or before it) can leave an overlay file nothing points at.
        collectGarbage(keep = readRecordLocked()?.rawSha256 ?: "")
    }

    private val pointerFile get() = File(dir, POINTER)

    fun overlayFile(rawSha256: String): File = File(dir, "overlay-$rawSha256.json")

    /** The pointer line as written, or null when absent or malformed. */
    fun readRecord(): Record? = synchronized(lock) { readRecordLocked() }

    /** The live record and its bytes, only if the file is present and hashes to what the pointer says. */
    fun readVerified(): Verified? = synchronized(lock) { readVerifiedLocked() }

    /** True when a pointer file exists at all, valid or not. */
    fun hasPointerFile(): Boolean = pointerFile.exists()

    /** Drop the pointer (a corrupt copy fell back to bundled). The overlay file is left for the next commit to replace. */
    fun clearPointer() = synchronized(lock) {
        pointerFile.delete()
        Unit
    }

    /**
     * Writes [bytes] as the live overlay iff the live record's raw hash is still [expectedPreviousRaw] (null: none).
     * [afterCommit] runs under the same lock, so publishing to listeners cannot be reordered against the write.
     * Throws [IOException] on a real disk problem, leaving the previous pointer intact.
     */
    fun commit(
        bytes: ByteArray,
        record: Record,
        expectedPreviousRaw: String?,
        afterCommit: () -> Unit = {},
    ): CommitResult = synchronized(lock) {
        val current = readVerifiedLocked()?.record
        if (current?.rawSha256 != expectedPreviousRaw) return@synchronized CommitResult.Conflict(current)

        val tmp = File(dir, OVERLAY_TMP)
        try {
            writeSynced(tmp, bytes)
            faultHook(StoreStep.AFTER_TMP_WRITE)
            if (sha256Hex(tmp.readBytes()) != record.rawSha256) throw IOException("temp file does not hash to the record")
            val target = overlayFile(record.rawSha256)
            move(tmp, target)
            faultHook(StoreStep.AFTER_OVERLAY_RENAME)

            val pointerTmp = File(dir, POINTER_TMP)
            writeSynced(pointerTmp, (formatPointer(record) + "\n").toByteArray(Charsets.US_ASCII))
            faultHook(StoreStep.BEFORE_POINTER_SWITCH)
            move(pointerTmp, pointerFile)
            faultHook(StoreStep.AFTER_POINTER_SWITCH)
        } catch (io: IOException) {
            tmp.delete()
            File(dir, POINTER_TMP).delete()
            throw io
        }
        commits.incrementAndGet()
        collectGarbage(keep = record.rawSha256)
        afterCommit()
        CommitResult.Committed
    }

    private fun readRecordLocked(): Record? {
        val line: String? = try {
            pointerFile.takeIf { it.isFile }?.readText(Charsets.US_ASCII)?.trim()
        } catch (_: IOException) {
            null
        }
        return if (line == null) null else parsePointer(line)
    }

    private fun readVerifiedLocked(): Verified? {
        val record = readRecordLocked() ?: return null
        val bytes: ByteArray? = try {
            overlayFile(record.rawSha256).takeIf { it.isFile }?.readBytes()
        } catch (_: IOException) {
            null
        }
        if (bytes == null || sha256Hex(bytes) != record.rawSha256) return null
        return Verified(record, bytes)
    }

    private fun removeStaleTemps() {
        dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun collectGarbage(keep: String) {
        dir.listFiles { f -> f.name.startsWith("overlay-") && f.name.endsWith(".json") }
            ?.filter { it.name != "overlay-$keep.json" }
            ?.forEach { it.delete() }
    }

    private fun writeSynced(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
    }

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        private const val POINTER = "pointer"
        private const val POINTER_TMP = "pointer.tmp"
        private const val OVERLAY_TMP = "overlay.tmp"
        private const val FORMAT = "rd1"
        private val SHA = Regex("^[0-9a-f]{64}$")
        private val ETAG = Regex("^(W/)?\"[!#-~]{0,120}\"$")

        internal fun formatPointer(r: Record): String = listOf(
            FORMAT,
            r.rawSha256,
            r.ordinal.toString(),
            r.schema.toString(),
            r.canonicalSha256,
            r.etag?.takeIf { ETAG.matches(it) } ?: "-",
            r.fetchedAtMillis?.toString() ?: "-",
        ).joinToString(" ")

        internal fun parsePointer(line: String): Record? {
            val f = line.split(' ')
            if (f.size != 7 || f[0] != FORMAT) return null
            if (!SHA.matches(f[1]) || !SHA.matches(f[4])) return null
            val ordinal = f[2].toLongOrNull() ?: return null
            val schema = f[3].toIntOrNull() ?: return null
            if (ordinal < 1 || schema < 1) return null
            val etag = f[5].takeIf { it != "-" }?.also { if (!ETAG.matches(it)) return null }
            val fetchedAt = f[6].takeIf { it != "-" }?.let { it.toLongOrNull() ?: return null }
            return Record(f[1], ordinal, schema, f[4], etag, fetchedAt)
        }
    }
}
