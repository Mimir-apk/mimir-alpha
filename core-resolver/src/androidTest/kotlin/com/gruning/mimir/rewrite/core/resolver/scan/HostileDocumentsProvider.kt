package com.gruning.mimir.rewrite.core.resolver.scan

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Instrumentation-only provider for bounded/cancellable SAF traversal proof. It never opens or mutates
 * user content; each mode exposes a synthetic document graph through DocumentsContract-shaped URIs.
 */
class HostileDocumentsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
        cancellationSignal: CancellationSignal?,
    ): Cursor {
        val columns = projection?.map { it }?.toTypedArray() ?: DEFAULT_COLUMNS
        val parent = DocumentsContract.getDocumentId(uri)
        if (mode == Mode.BLOCK_QUERY) {
            queryEntered.countDown()
            while (cancellationSignal?.isCanceled != true) {
                Thread.yield()
            }
            cancellationObserved.countDown()
            cancellationSignal.throwIfCanceled()
        }
        if (mode == Mode.PROVIDER_FAULT) error("synthetic provider fault")

        val rows = when (mode) {
            Mode.COMPLETE -> if (parent == ROOT_ID) {
                listOf(Document("game", "Game.gba", "application/octet-stream", 2L * 1024 * 1024))
            } else {
                emptyList()
            }
            Mode.SELF_CYCLE -> if (parent == ROOT_ID) {
                listOf(Document(ROOT_ID, "loop", DocumentsContract.Document.MIME_TYPE_DIR, 0))
            } else {
                emptyList()
            }
            Mode.TWO_NODE_CYCLE -> when (parent) {
                ROOT_ID -> listOf(Document("a", "A", DocumentsContract.Document.MIME_TYPE_DIR, 0))
                "a" -> listOf(Document("b", "B", DocumentsContract.Document.MIME_TYPE_DIR, 0))
                "b" -> listOf(Document("a", "A-again", DocumentsContract.Document.MIME_TYPE_DIR, 0))
                else -> emptyList()
            }
            Mode.DUPLICATE_DOCUMENT_ID -> if (parent == ROOT_ID) {
                listOf(
                    Document("same", "One.gba", "application/octet-stream", 2L * 1024 * 1024),
                    Document("same", "Two.gba", "application/octet-stream", 2L * 1024 * 1024),
                )
            } else {
                emptyList()
            }
            Mode.DUPLICATE_RELATIVE_PATH -> if (parent == ROOT_ID) {
                listOf(
                    Document("one", "Same.gba", "application/octet-stream", 2L * 1024 * 1024),
                    Document("two", "Same.gba", "application/octet-stream", 2L * 1024 * 1024),
                )
            } else {
                emptyList()
            }
            Mode.NULL_DOCUMENT_ID -> if (parent == ROOT_ID) {
                listOf(Document(null, "Null.gba", "application/octet-stream", 2L * 1024 * 1024))
            } else {
                emptyList()
            }
            Mode.NULL_DISPLAY_NAME -> if (parent == ROOT_ID) {
                listOf(Document("null-name", null, "application/octet-stream", 2L * 1024 * 1024))
            } else {
                emptyList()
            }
            Mode.NULL_MIME_TYPE -> if (parent == ROOT_ID) {
                listOf(Document("null-mime", "Null.gba", null, 2L * 1024 * 1024))
            } else {
                emptyList()
            }
            Mode.BLOCK_QUERY -> emptyList()
            Mode.BLOCK_OPEN,
            Mode.BLOCK_READ,
            -> if (parent == ROOT_ID) {
                listOf(Document(PLAYLIST_ID, "Set.m3u", "audio/x-mpegurl", 64))
            } else {
                emptyList()
            }
            Mode.PROVIDER_FAULT -> error("handled above")
        }
        return MatrixCursor(columns).apply {
            rows.forEach { document ->
                addRow(
                    columns.map { column ->
                        when (column) {
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID -> document.id
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME -> document.name
                            DocumentsContract.Document.COLUMN_MIME_TYPE -> document.mimeType
                            DocumentsContract.Document.COLUMN_SIZE -> document.size
                            else -> null
                        }
                    },
                )
            }
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        openFile(uri, mode, null)

    override fun openFile(
        uri: Uri,
        mode: String,
        cancellationSignal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only fixture")
        return when (HostileDocumentsProvider.mode) {
            Mode.BLOCK_OPEN -> {
                openEntered.countDown()
                if (cancellationSignal == null) {
                    throw FileNotFoundException("framework did not deliver the requested CancellationSignal")
                }
                openSignalReceived.countDown()
                while (!cancellationSignal.isCanceled) {
                    Thread.yield()
                }
                openCancellationObserved.countDown()
                cancellationSignal.throwIfCanceled()
                throw FileNotFoundException("unreachable after cancellation")
            }
            Mode.BLOCK_READ -> blockedReadPipe()
            else -> throw FileNotFoundException("fixture mode does not expose bytes")
        }
    }

    private fun blockedReadPipe(): ParcelFileDescriptor {
        val pipe = ParcelFileDescriptor.createPipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                output.write('x'.code)
                output.flush()
                readBlockReady.countDown()
                readCloseProbe.await(5, TimeUnit.SECONDS)
                while (true) {
                    try {
                        output.write('y'.code)
                        output.flush()
                        Thread.sleep(10)
                    } catch (_: IOException) {
                        readCloseObserved.countDown()
                        return@Thread
                    }
                }
            }
        }.apply {
            name = "mimir-hostile-m3u-writer"
            isDaemon = true
            start()
        }
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private data class Document(
        val id: String?,
        val name: String?,
        val mimeType: String?,
        val size: Long,
    )

    enum class Mode {
        COMPLETE,
        SELF_CYCLE,
        TWO_NODE_CYCLE,
        DUPLICATE_DOCUMENT_ID,
        DUPLICATE_RELATIVE_PATH,
        NULL_DOCUMENT_ID,
        NULL_DISPLAY_NAME,
        NULL_MIME_TYPE,
        PROVIDER_FAULT,
        BLOCK_QUERY,
        BLOCK_OPEN,
        BLOCK_READ,
    }

    companion object {
        const val AUTHORITY = "com.gruning.mimir.rewrite.core.resolver.ep02.documents"
        const val ROOT_ID = "root"
        const val PLAYLIST_ID = "playlist"
        val TREE_URI: Uri = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)
        private val DEFAULT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        @Volatile
        var mode: Mode = Mode.COMPLETE

        @Volatile
        var queryEntered = CountDownLatch(1)

        @Volatile
        var cancellationObserved = CountDownLatch(1)

        @Volatile
        var openEntered = CountDownLatch(1)

        @Volatile
        var openSignalReceived = CountDownLatch(1)

        @Volatile
        var openCancellationObserved = CountDownLatch(1)

        @Volatile
        var readBlockReady = CountDownLatch(1)

        @Volatile
        var readCloseProbe = CountDownLatch(1)

        @Volatile
        var readCloseObserved = CountDownLatch(1)

        fun reset(nextMode: Mode) {
            mode = nextMode
            queryEntered = CountDownLatch(1)
            cancellationObserved = CountDownLatch(1)
            openEntered = CountDownLatch(1)
            openSignalReceived = CountDownLatch(1)
            openCancellationObserved = CountDownLatch(1)
            readBlockReady = CountDownLatch(1)
            readCloseProbe = CountDownLatch(1)
            readCloseObserved = CountDownLatch(1)
        }
    }
}
