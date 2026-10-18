package com.gruning.mimir.rewrite

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import com.gruning.mimir.rewrite.core.metadata.CancellationBoundContentOpener
import com.gruning.mimir.rewrite.core.metadata.ContentCancellationScope
import com.gruning.mimir.rewrite.core.metadata.ContentOpener
import com.gruning.mimir.rewrite.core.metadata.NativeSeekablePath
import com.gruning.mimir.rewrite.core.metadata.RandomAccessContent
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * The Android [ContentOpener]: opens a SAF `content://` document via the ContentResolver (authorized by the
 * persisted read grant taken at folder-pick), or a plain file path via a file stream. Read-only; used ON
 * DEMAND for fingerprinting/metadata, never on the scan path. Returns null (never throws) on a revoked
 * grant / missing file. Local-only — reads a file already on the device, sends nothing.
 *
 * SD01b: when all-files access is granted (the primary lane — the design constraint)
 * an externalstorage document opens as a plain [File] instead — the identity pass at dirty-card scale paid
 * a Binder + MediaProvider open tax ~11k times (~minutes of the 17-minute wall). Same bytes, same file,
 * read-only; any miss (odd provider, unmounted volume, non-file) falls back to the ContentResolver.
 */
class AndroidContentOpener(private val context: Context) : ContentOpener {
    override fun boundTo(scope: ContentCancellationScope): ContentOpener =
        CancellationBoundContentOpener(
            contentResolver = context.contentResolver,
            scope = scope,
            directFile = { documentUri -> directFile(documentUri) },
            resolveRelatedUri = { documentUri, relativePath ->
                val primary = Uri.parse(documentUri)
                val relatedId = resolveRelatedDocumentId(
                    DocumentsContract.getDocumentId(primary),
                    relativePath,
                ) ?: return@CancellationBoundContentOpener null
                DocumentsContract.buildDocumentUriUsingTree(primary, relatedId)
            },
            resolveRelatedFile = { managedPath, relativePath ->
                resolveRelatedFile(File(managedPath), relativePath)
            },
        )

    override fun open(documentUri: String?, managedPath: String?): InputStream? = runCatching {
        when {
            documentUri != null ->
                directFileStream(documentUri)
                    ?: context.contentResolver.openInputStream(Uri.parse(documentUri))
            managedPath != null -> File(managedPath).inputStream()
            else -> null
        }
    }.getOrNull()

    override fun openRelated(documentUri: String?, managedPath: String?, relativePath: String): InputStream? =
        runCatching {
            when {
                managedPath != null -> resolveRelatedFile(File(managedPath), relativePath)?.inputStream()
                documentUri != null -> {
                    val primary = Uri.parse(documentUri)
                    val primaryId = DocumentsContract.getDocumentId(primary)
                    val relatedId = resolveRelatedDocumentId(primaryId, relativePath) ?: return@runCatching null
                    val related = DocumentsContract.buildDocumentUriUsingTree(primary, relatedId)
                    directFileStream(related.toString()) ?: context.contentResolver.openInputStream(related)
                }
                else -> null
            }
        }.getOrNull()

    override fun openRandomAccess(documentUri: String?, managedPath: String?): RandomAccessContent? =
        runCatching {
            val direct = when {
                managedPath != null -> File(managedPath).takeIf { it.isFile }
                documentUri != null -> directFile(documentUri)
                else -> null
            }
            if (direct != null) {
                return@runCatching ChannelRandomAccessContent(FileInputStream(direct).channel)
            }
            if (documentUri == null) return@runCatching null
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(documentUri), "r")
                ?: return@runCatching null
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            val content = runCatching { ChannelRandomAccessContent(stream.channel) }.getOrElse {
                stream.close()
                return@runCatching null
            }
            content
        }.getOrNull()

    override fun localPath(documentUri: String?, managedPath: String?): String? = when {
        managedPath != null -> File(managedPath).takeIf { it.isFile }?.absolutePath
        documentUri != null -> directFile(documentUri)?.absolutePath
        else -> null
    }

    override fun openNativeSeekablePath(
        documentUri: String?,
        managedPath: String?,
    ): NativeSeekablePath? {
        localPath(documentUri, managedPath)?.let { return AndroidNativeSeekablePath(it) }
        if (documentUri == null) return null
        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(documentUri), "r")
        }.getOrNull() ?: return null
        val seekable = runCatching {
            Os.lseek(descriptor.fileDescriptor, 0L, OsConstants.SEEK_CUR)
            AndroidNativeSeekablePath("/proc/self/fd/${descriptor.fd}", descriptor)
        }.getOrNull()
        if (seekable == null) {
            descriptor.close()
        }
        return seekable
    }

    private fun directFileStream(documentUri: String): InputStream? {
        val file = directFile(documentUri) ?: return null
        return runCatching { file.inputStream() }.getOrNull()
    }

    private fun directFile(documentUri: String): File? {
        if (!runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)) return null
        val path = ExternalStorageDocPath.resolve(documentUri) ?: return null
        return File(path).takeIf { it.isFile }
    }
}

private class AndroidNativeSeekablePath(
    override val path: String,
    private val descriptor: ParcelFileDescriptor? = null,
) : NativeSeekablePath {
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        descriptor?.close()
    }
}

private class ChannelRandomAccessContent(
    private val channel: FileChannel,
) : RandomAccessContent {
    override val length: Long = channel.size()

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
        val target = ByteBuffer.wrap(destination, destinationOffset, byteCount)
        var total = 0
        while (target.hasRemaining()) {
            val read = channel.read(target, offset + total)
            if (read < 0) break
            if (read == 0) break
            total += read
        }
        return total
    }

    override fun close() = channel.close()
}

internal fun resolveRelatedFile(primary: File, relativePath: String): File? {
    val base = primary.parentFile ?: return null
    val candidate = File(base, relativePath).canonicalFile
    return candidate.takeIf { it.isFile }
}

/** Resolve a SAF document id without assuming an external-storage provider beyond slash-delimited paths. */
internal fun resolveRelatedDocumentId(primaryId: String, relativePath: String): String? {
    if (relativePath.isBlank() || relativePath.startsWith('/')) return null
    val colon = primaryId.indexOf(':')
    val prefix = if (colon >= 0) primaryId.substring(0, colon + 1) else ""
    val rawPath = if (colon >= 0) primaryId.substring(colon + 1) else primaryId
    val segments = rawPath.split('/').filter { it.isNotEmpty() }.dropLast(1).toMutableList()
    for (segment in relativePath.replace('\\', '/').split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> {
                if (segment.contains(':') || segment.indexOf('\u0000') >= 0) return null
                segments += segment
            }
        }
    }
    if (segments.isEmpty()) return null
    return prefix + segments.joinToString("/")
}
