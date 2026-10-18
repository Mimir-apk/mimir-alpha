package com.gruning.mimir.rewrite.core.metadata

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException

/**
 * A [ContentOpener] bound to one [ContentCancellationScope]. SAF reads use [SafCancellableContentAccess];
 * direct managed paths register close-on-cancel without a provider [android.os.CancellationSignal].
 */
class CancellationBoundContentOpener(
    private val contentResolver: ContentResolver,
    private val scope: ContentCancellationScope,
    private val directFile: (documentUri: String) -> File? = { null },
    private val resolveRelatedUri: (documentUri: String, relativePath: String) -> Uri? = { _, _ -> null },
    private val resolveRelatedFile: (managedPath: String, relativePath: String) -> File? = { _, _ -> null },
) : ContentOpener {
    override fun boundTo(nextScope: ContentCancellationScope): ContentOpener =
        CancellationBoundContentOpener(
            contentResolver = contentResolver,
            scope = nextScope,
            directFile = directFile,
            resolveRelatedUri = resolveRelatedUri,
            resolveRelatedFile = resolveRelatedFile,
        )

    override fun open(documentUri: String?, managedPath: String?): InputStream? = openOrNull {
        scope.throwIfCancelled()
        when {
            documentUri != null -> {
                directFile(documentUri)?.inputStream()?.let { direct ->
                    SafCancellableContentAccess.registerDirectInputStream(direct, scope)
                } ?: SafCancellableContentAccess.openInputStream(
                    contentResolver,
                    Uri.parse(documentUri),
                    scope,
                )
            }
            managedPath != null -> {
                SafCancellableContentAccess.registerDirectInputStream(
                    File(managedPath).inputStream(),
                    scope,
                )
            }
            else -> null
        }
    }

    override fun openRelated(
        documentUri: String?,
        managedPath: String?,
        relativePath: String,
    ): InputStream? = openOrNull {
        scope.throwIfCancelled()
        when {
            managedPath != null -> {
                val related = resolveRelatedFile(managedPath, relativePath) ?: return@openOrNull null
                SafCancellableContentAccess.registerDirectInputStream(related.inputStream(), scope)
            }
            documentUri != null -> {
                val related = resolveRelatedUri(documentUri, relativePath) ?: return@openOrNull null
                directFile(related.toString())?.inputStream()?.let { direct ->
                    SafCancellableContentAccess.registerDirectInputStream(direct, scope)
                } ?: SafCancellableContentAccess.openInputStream(contentResolver, related, scope)
            }
            else -> null
        }
    }

    override fun openRandomAccess(documentUri: String?, managedPath: String?): RandomAccessContent? = openOrNull {
        scope.throwIfCancelled()
        val direct = when {
            managedPath != null -> File(managedPath).takeIf { it.isFile }
            documentUri != null -> directFile(documentUri)
            else -> null
        }
        if (direct != null) {
            val content = BoundFileRandomAccessContent.open(direct.absolutePath, scope)
                ?: return@openOrNull null
            return@openOrNull SafCancellableContentAccess.registerDirectRandomAccess(content, scope)
        }
        if (documentUri == null) return@openOrNull null
        SafCancellableContentAccess.openRandomAccess(
            contentResolver,
            Uri.parse(documentUri),
            scope,
        )
    }

    override fun localPath(documentUri: String?, managedPath: String?): String? = when {
        managedPath != null -> File(managedPath).takeIf { it.isFile }?.absolutePath
        documentUri != null -> directFile(documentUri)?.absolutePath
        else -> null
    }

    override fun openNativeSeekablePath(
        documentUri: String?,
        managedPath: String?,
    ): NativeSeekablePath? = openOrNull {
        scope.throwIfCancelled()
        localPath(documentUri, managedPath)?.let { path ->
            SafCancellableContentAccess.registerDirectNativeSeekablePath(BoundFixedNativeSeekablePath(path), scope)
        } ?: documentUri?.let { uri ->
            SafCancellableContentAccess.openNativeSeekablePath(
                contentResolver,
                Uri.parse(uri),
                scope,
            )
        }
    }

    private inline fun <T> openOrNull(block: () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
}

private class BoundFixedNativeSeekablePath(
    override val path: String,
) : NativeSeekablePath {
    override fun close() = Unit
}

private object BoundFileRandomAccessContent {
    fun open(path: String, scope: ContentCancellationScope): RandomAccessContent? = runCatching {
        val target = File(path)
        if (!target.isFile) return@runCatching null
        BoundDirectFileRandomAccessContent(java.io.RandomAccessFile(target, "r"), scope)
    }.getOrNull()
}

private class BoundDirectFileRandomAccessContent(
    private val file: java.io.RandomAccessFile,
    private val scope: ContentCancellationScope,
) : RandomAccessContent {
    override val length: Long
        get() {
            scope.throwIfCancelled()
            return try {
                file.length()
            } catch (error: Exception) {
                throw SafCancellableContentAccess.normalizeScopeFailure(scope, error)
            }
        }

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
        scope.throwIfCancelled()
        return try {
            synchronized(file) {
                file.seek(offset)
                file.read(destination, destinationOffset, byteCount).coerceAtLeast(0)
            }
        } catch (error: Exception) {
            throw SafCancellableContentAccess.normalizeScopeFailure(scope, error)
        }
    }

    override fun close() = file.close()
}
