package com.gruning.mimir.rewrite.core.metadata

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

/**
 * Instrumentation-only provider for cancellation-bound [ContentOpener] proof. It never opens or mutates user
 * content; each mode exposes a synthetic document graph through DocumentsContract-shaped URIs.
 */
class HostileOpenDocumentsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = unsupported()

    override fun getType(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        openFile(uri, mode, null)

    override fun openFile(
        uri: Uri,
        mode: String,
        cancellationSignal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only fixture")
        return when (mode()) {
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
            Mode.SEEKABLE -> seekableFileFixture()
            Mode.SEEKABLE_HOLD -> seekableHoldFixture()
            else -> throw FileNotFoundException("fixture mode does not expose bytes")
        }
    }

    private fun seekableFileFixture(): ParcelFileDescriptor {
        val bytes = ByteArray(64) { index -> (index * 7 + 3).toByte() }
        val file = File(requireNotNull(context).cacheDir, "mimir-ep20-seek-fixture.bin")
        file.writeBytes(bytes)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun seekableHoldFixture(): ParcelFileDescriptor {
        val bytes = ByteArray(64) { index -> (index * 7 + 3).toByte() }
        val file = File(
            requireNotNull(context).cacheDir,
            "mimir-ep20-seek-hold-${seekableOpenCount.get()}.bin",
        )
        file.writeBytes(bytes)
        val opened = seekableOpenCount.incrementAndGet()
        if (opened == 1) {
            seekableOpensEntered.countDown()
        } else if (opened == 2) {
            seekableBothOpensObserved.countDown()
        }
        val base = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return ParcelFileDescriptor.wrap(
            base,
            android.os.Handler(android.os.Looper.getMainLooper()),
            ParcelFileDescriptor.OnCloseListener { seekableCloseObserved.countDown() },
        )
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
            name = "mimir-hostile-open-writer"
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

    private fun unsupported(): Nothing = error("fixture provider does not support query")

    enum class Mode {
        BLOCK_OPEN,
        BLOCK_READ,
        SEEKABLE,
        SEEKABLE_HOLD,
    }

    companion object {
        const val AUTHORITY = "com.gruning.mimir.rewrite.core.metadata.ep20.open"
        const val DOCUMENT_ID = "fixture"
        val DOCUMENT_URI: Uri = DocumentsContract.buildDocumentUri(AUTHORITY, DOCUMENT_ID)

        @Volatile
        private var currentMode: Mode = Mode.BLOCK_OPEN

        fun mode(): Mode = currentMode

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

        @Volatile
        var seekableOpensEntered = CountDownLatch(1)

        @Volatile
        var seekableCloseObserved = CountDownLatch(2)

        @Volatile
        var seekableBothOpensObserved = CountDownLatch(1)

        @Volatile
        var seekableOpenCount = AtomicInteger(0)

        fun reset(nextMode: Mode) {
            currentMode = nextMode
            openEntered = CountDownLatch(1)
            openSignalReceived = CountDownLatch(1)
            openCancellationObserved = CountDownLatch(1)
            readBlockReady = CountDownLatch(1)
            readCloseProbe = CountDownLatch(1)
            readCloseObserved = CountDownLatch(1)
            seekableOpensEntered = CountDownLatch(1)
            seekableCloseObserved = CountDownLatch(2)
            seekableBothOpensObserved = CountDownLatch(1)
            seekableOpenCount = AtomicInteger(0)
        }
    }
}
