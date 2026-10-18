package com.gruning.mimir.rewrite.core.files

import net.lingala.zip4j.exception.ZipException
import org.apache.commons.compress.PasswordRequiredException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.NumberFormat
import java.util.Locale

/**
 * MIMIR ADDITION — **a failed card says why.**
 *
 * Every observed failure (a password, a truncated download, a text file called `.zip`, a zip-slip,
 * a bomb, 10,001 entries) read the same single word, `Failed`. The engine knew the difference in every
 * case; the card threw it away. This file keeps it: [ArchiveFailureKind] is the engine's own knowledge,
 * [FileFailureReasons.describe] is the plain sentence the card shows.
 */
enum class ArchiveFailureKind {
    /** Encrypted, and Mimir has no password prompt. */
    PASSWORD,

    /** Looks like the right kind of archive but cannot be read to the end: truncated, corrupt. */
    DAMAGED,

    /** Not the kind of archive its name says (text called `.zip`, a 7z called `.zip`, ...). */
    NOT_AN_ARCHIVE,

    /** A zero-byte file. Never a valid archive: even an empty zip is 22 bytes. */
    EMPTY_FILE,

    TOO_MANY_ENTRIES,
    TOO_LARGE,

    /** An entry's path would escape the folder, or is too long or deep. */
    UNSAFE_PATH,

    DUPLICATE_ENTRY,
    UNSUPPORTED,

    /** The destination is Mimir's own staging folder. */
    RESERVED_DESTINATION,

    /** The extraction was placing files and stopped part-way (see [ArchiveExtractionException.placed]). */
    PARTIAL,

    /** The place to extract into is a file, not a folder. */
    DESTINATION_NOT_A_FOLDER,

    /** A file stands where the archive needs a folder, or a folder where it needs a file. */
    FILE_FOLDER_CLASH,

    /** A folder the extraction needs could not be made (a read-only card, no permission). */
    CANNOT_CREATE_FOLDER,

    /** Keep both found every "(copy)" name for a file already taken. */
    NO_FREE_COPY_NAME,

    /** Between planning and placing, something else put a file where this one was to go. */
    TARGET_TAKEN,

    OTHER,
}

/**
 * Raised by the extraction engine. `internal` as it always was; the outside world sees it only through
 * [FileFailureReasons.describe].
 *
 * @param format for [ArchiveFailureKind.NOT_AN_ARCHIVE], the extension the name claimed (`zip`, `7z`).
 * @param placed / [total] / [folder] for [ArchiveFailureKind.PARTIAL]: how far the placing got.
 */
internal class ArchiveExtractionException(
    message: String,
    val kind: ArchiveFailureKind = ArchiveFailureKind.OTHER,
    cause: Throwable? = null,
    val format: String? = null,
    val placed: Int = -1,
    val total: Int = -1,
    val folder: String? = null,
) : IOException(message, cause)

/**
 * An operation Mimir declined to do, with the sentence the card shows already written ([sentence]).
 * For rules that have an obvious plain answer -- a name that can't be used, a folder that can't replace a
 * folder -- where wrapping the reason in a generic "couldn't finish" would throw the answer away.
 */
class FileRefusedException(val sentence: String) : IOException(sentence)

/**
 * **The player stopped an extraction, and it has been taken back.** What the rollback found: [removed] files
 * this job had already placed are gone again, [remaining] could not be removed and are still in [folder].
 * Everything else the job touched (its staging, a file it replaced) is already restored.
 *
 * Handed to [ArchiveHelper.extract]'s `onCancelled` callback rather than thrown, and the reason is a fact
 * about coroutines worth recording: when the cancellation comes from the job that called the engine, that
 * job's own cancellation is the exception `withContext` rethrows, and anything the block throws instead is
 * dropped. A typed exception carrying these numbers never reached the caller; a callback does. It runs
 * before the engine rethrows, so by the time the caller sees the cancellation the disk is settled and
 * [sentence] is true.
 */
class ExtractionCancelOutcome(
    val removed: Int,
    val remaining: Int,
    val folder: String?,
) {
    /** What the cancel left on disk, in plain words: the card's second line. */
    val sentence: String
        get() = when {
            remaining > 0 -> {
                val where = folder?.let { "in $it" } ?: "where you asked for them"
                "Stopped. ${files(remaining)} already placed couldn’t be removed and ${if (remaining == 1) "was" else "were"} left $where."
            }
            removed > 0 -> "Nothing was extracted. The ${files(removed)} already placed ${if (removed == 1) "was" else "were"} removed again."
            else -> "Nothing was extracted."
        }

    private fun files(n: Int): String =
        if (n == 1) "1 file" else "${NumberFormat.getIntegerInstance(Locale.US).format(n)} files"
}

/**
 * The plain sentence for a failed file operation. The only place card copy for a failure is written, so
 * the queue, a toast and the tests cannot drift apart.
 */
object FileFailureReasons {
    private const val GENERIC = "Mimir couldn't finish this."

    fun describe(error: Throwable): String = when (error) {
        is FileRefusedException -> error.sentence
        is ArchiveExtractionException -> describeArchive(error)
        is RepositoryException -> describeRepository(error)
        else -> describeByMessage(error) ?: GENERIC
    }

    private fun describeArchive(error: ArchiveExtractionException): String = when (error.kind) {
        ArchiveFailureKind.PASSWORD -> "Password-protected. Mimir can't open archives that need a password."
        ArchiveFailureKind.DAMAGED -> "Damaged or incomplete archive."
        ArchiveFailureKind.NOT_AN_ARCHIVE ->
            error.format?.let { "Not a real .$it archive." } ?: "Not an archive Mimir can read."
        ArchiveFailureKind.EMPTY_FILE -> "Not an archive (empty file)."
        ArchiveFailureKind.TOO_MANY_ENTRIES ->
            "Too many files inside (more than ${count(ArchiveEntryPathPolicy.MAX_ENTRIES)})."
        ArchiveFailureKind.TOO_LARGE -> "Too large to unpack safely."
        ArchiveFailureKind.UNSAFE_PATH -> "An entry inside has an unsafe path, so nothing was extracted."
        ArchiveFailureKind.DUPLICATE_ENTRY ->
            "Two entries inside share a name, so it can't be unpacked safely."
        ArchiveFailureKind.UNSUPPORTED -> "Unsupported archive format."
        ArchiveFailureKind.RESERVED_DESTINATION ->
            "That is Mimir's own working folder, so nothing can be put there."
        ArchiveFailureKind.PARTIAL -> {
            val where = error.folder?.let { " in $it" }.orEmpty()
            if (error.placed >= 0 && error.total > 0) {
                "Stopped part-way: ${count(error.placed)} of ${count(error.total)} files were placed$where " +
                    "and left there."
            } else {
                "Stopped part-way. Some files may already be placed$where."
            }
        }
        ArchiveFailureKind.DESTINATION_NOT_A_FOLDER -> "The place to extract into is a file, not a folder."
        ArchiveFailureKind.FILE_FOLDER_CLASH ->
            "A file and a folder share a name in the destination, so nothing was extracted."
        ArchiveFailureKind.CANNOT_CREATE_FOLDER ->
            "Mimir couldn't make a folder it needed in the destination."
        ArchiveFailureKind.NO_FREE_COPY_NAME ->
            "All the (copy) names for a file inside are already taken, so both can't be kept."
        ArchiveFailureKind.TARGET_TAKEN ->
            "Something else put a file there first while this was extracting, so nothing was overwritten."
        ArchiveFailureKind.OTHER -> describeByMessage(error) ?: "Mimir couldn't unpack this archive."
    }

    private fun describeRepository(error: RepositoryException): String = when (error.error.kind) {
        RepositoryErrorKind.PERMISSION -> "Mimir doesn't have permission to do that here."
        RepositoryErrorKind.NOT_FOUND -> "The file or folder is no longer there."
        RepositoryErrorKind.CONFLICT -> "That name is already taken."
        RepositoryErrorKind.CORRUPT -> "The file is damaged."
        RepositoryErrorKind.INVALID -> "Mimir can't do that with this file."
        RepositoryErrorKind.STORAGE -> "The drive is full or can't be written to."
        else -> describeByMessage(error) ?: "Mimir hit a read or write error."
    }

    /** Disk-full and a refused destination are the only message-only facts worth a sentence of their own. */
    private fun describeByMessage(error: Throwable): String? {
        val text = generateSequence(error) { it.cause }.take(4).joinToString(" ") { it.message.orEmpty() }
        return when {
            text.contains("No space left", ignoreCase = true) -> "There isn't enough free space on the drive."
            text.contains(RESERVED_DESTINATION_MESSAGE) ->
                "That is Mimir's own working folder, so nothing can be put there."
            else -> null
        }
    }

    /** The message every destination refusal carries, so [describeByMessage] recognises one in a wrapped cause. */
    internal const val RESERVED_DESTINATION_MESSAGE = "is Mimir's own working folder"

    private fun count(value: Int): String = NumberFormat.getIntegerInstance(Locale.US).format(value)
}

/**
 * Turns whatever an archive library threw into an [ArchiveExtractionException] that says what happened,
 * by asking the file rather than the exception text: zip4j and commons-compress both answer "damaged" and
 * "not an archive at all" with the same exception type.
 */
internal object ArchiveFailureClassifier {
    private val ZIP_LOCAL = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_END = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val SEVEN_Z = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)

    /** The most a zip's end-of-central-directory record can sit from the end of the file (comment + record). */
    private const val ZIP_END_WINDOW = 65_557

    fun classify(error: Exception, archive: File, extension: String): Exception {
        if (error is ArchiveExtractionException) return error
        if (error is FileOperationConflictException) return error
        // A sentence Mimir already wrote for the player (a name waiting on an Undo): not a damaged archive.
        if (error is FileRefusedException) return error

        val ext = extension.lowercase()
        val kind = when {
            error is PasswordRequiredException -> ArchiveFailureKind.PASSWORD
            error is ZipException && error.type == ZipException.Type.WRONG_PASSWORD -> ArchiveFailureKind.PASSWORD
            // Anything below is "the library could not read this as an archive". Only that is re-labelled;
            // a full disk or a permission error keeps its own identity and its own sentence.
            error is ZipException ||
                (error is IOException && looksLikeFormatError(error)) -> sniff(archive, ext)
            else -> return error
        }
        if (kind == ArchiveFailureKind.PASSWORD) {
            return ArchiveExtractionException("Archive is password protected", kind, error)
        }
        val format = ext.takeIf { kind == ArchiveFailureKind.NOT_AN_ARCHIVE }
        return ArchiveExtractionException(error.message ?: "Unreadable archive", kind, error, format = format)
    }

    /** IOExceptions that mean "the bytes are not what the format needs", as opposed to "the disk said no". */
    private fun looksLikeFormatError(error: IOException): Boolean {
        val text = error.message.orEmpty().lowercase()
        if ("no space left" in text || "permission denied" in text || "read-only" in text) return false
        return error !is java.nio.file.FileSystemException &&
            error !is java.io.FileNotFoundException
    }

    /**
     * Whether [archive] is the right kind of file that is broken ([ArchiveFailureKind.DAMAGED]) or not that
     * kind of file at all ([ArchiveFailureKind.NOT_AN_ARCHIVE]; [ArchiveFailureKind.EMPTY_FILE] for zero bytes).
     */
    fun sniff(archive: File, ext: String): ArchiveFailureKind {
        val length = runCatching { archive.length() }.getOrDefault(-1L)
        if (length == 0L) return ArchiveFailureKind.EMPTY_FILE
        return try {
            RandomAccessFile(archive, "r").use { file ->
                when (ext) {
                    "zip", "jar" -> if (startsWith(file, ZIP_LOCAL) || startsWith(file, ZIP_END) ||
                        containsInTail(file, ZIP_END)
                    ) {
                        ArchiveFailureKind.DAMAGED
                    } else {
                        ArchiveFailureKind.NOT_AN_ARCHIVE
                    }
                    "7z" -> if (startsWith(file, SEVEN_Z)) ArchiveFailureKind.DAMAGED else ArchiveFailureKind.NOT_AN_ARCHIVE
                    else -> ArchiveFailureKind.DAMAGED
                }
            }
        } catch (_: IOException) {
            ArchiveFailureKind.DAMAGED
        }
    }

    private fun startsWith(file: RandomAccessFile, magic: ByteArray): Boolean {
        val head = ByteArray(magic.size)
        file.seek(0)
        return runCatching { file.readFully(head) }.isSuccess && head.contentEquals(magic)
    }

    private fun containsInTail(file: RandomAccessFile, magic: ByteArray): Boolean {
        val size = file.length()
        val window = minOf(size, ZIP_END_WINDOW.toLong()).toInt()
        val tail = ByteArray(window)
        file.seek(size - window)
        file.readFully(tail)
        for (index in 0..(tail.size - magic.size)) {
            if (magic.indices.all { tail[index + it] == magic[it] }) return true
        }
        return false
    }
}
