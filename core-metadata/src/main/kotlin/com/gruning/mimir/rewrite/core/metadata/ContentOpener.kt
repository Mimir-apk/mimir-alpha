package com.gruning.mimir.rewrite.core.metadata

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A read-only, seekable resident. Container readers use this instead of copying multi-gigabyte media merely
 * to reach a table or ticket near the end of a file.
 */
interface RandomAccessContent : Closeable {
    val length: Long

    /**
     * Reads up to [byteCount] bytes at absolute [offset] into [destination]. Returns 0 only at EOF.
     * Implementations never mutate a shared stream position.
     */
    fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int = 0,
        byteCount: Int = destination.size - destinationOffset,
    ): Int
}

/** A path kept valid for the lifetime of this handle so a native seek reader can open the same resident. */
interface NativeSeekablePath : Closeable {
    val path: String
}

/**
 * Opens a readable byte stream for a resident file — the ONE Android/SAF touch-point of the hashing path,
 * injected so the pure hashing logic stays testable. The caller (a resident carries a SAF `documentUri`
 * OR a plain `managedPath`) passes whichever it has; the implementation opens it (content-resolver for a
 * `content://` uri, a file stream for a path) and the CALLER closes the returned stream.
 *
 * Returns null when the content can't be opened (revoked grant, missing file) — never throws up the stack.
 */
fun interface ContentOpener {
    fun open(documentUri: String?, managedPath: String?): InputStream?

    /** Binds this opener to [scope] so SAF/direct reads honour coroutine cancellation. */
    fun boundTo(scope: ContentCancellationScope): ContentOpener = this

    /**
     * Opens a local file related to the primary by a normalized relative path (for example
     * `../PARAM.SFO`). Android/SAF implementations may override it; ordinary hash-only test seams and
     * providers remain valid lambdas and fail closed by default.
     */
    fun openRelated(documentUri: String?, managedPath: String?, relativePath: String): InputStream? = null

    /**
     * Opens a seekable view when the backing provider supports one. The default handles ordinary managed
     * paths and fails closed for SAF; Android overrides it for seekable document descriptors.
     */
    fun openRandomAccess(documentUri: String?, managedPath: String?): RandomAccessContent? =
        managedPath?.let(FileRandomAccessContent::open)

    /**
     * Returns the exact local path when native seek readers can safely consume the resident in place.
     * A SAF URI is never guessed into a path by the core layer.
     */
    fun localPath(documentUri: String?, managedPath: String?): String? =
        managedPath?.takeIf { File(it).isFile }

    /**
     * Keeps a native-openable path alive. Managed files need no extra resource; Android may retain a
     * seekable SAF descriptor and expose its process-local `/proc/self/fd` path for libchdr.
     */
    fun openNativeSeekablePath(
        documentUri: String?,
        managedPath: String?,
    ): NativeSeekablePath? = localPath(documentUri, managedPath)?.let(::FixedNativeSeekablePath)
}

private class FixedNativeSeekablePath(
    override val path: String,
) : NativeSeekablePath {
    override fun close() = Unit
}

private class FileRandomAccessContent(
    private val file: RandomAccessFile,
) : RandomAccessContent {
    override val length: Long = file.length()

    override fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        byteCount: Int,
    ): Int {
        require(offset >= 0L)
        require(destinationOffset >= 0 && byteCount >= 0)
        require(destinationOffset <= destination.size - byteCount)
        if (byteCount == 0 || offset >= length) return 0
        synchronized(file) {
            file.seek(offset)
            return file.read(destination, destinationOffset, byteCount).coerceAtLeast(0)
        }
    }

    override fun close() = file.close()

    companion object {
        fun open(path: String): RandomAccessContent? = runCatching {
            val target = File(path)
            if (!target.isFile) return@runCatching null
            FileRandomAccessContent(RandomAccessFile(target, "r"))
        }.getOrNull()
    }
}
