package com.gruning.mimir.rewrite.core.metadata

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentOpenerCancellationDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun lambdaContentOpenerBoundToReturnsSameInstance() = runBlocking {
        withContentCancellation { scope ->
            val opener = ContentOpener { _, _ -> null }
            assertSame(opener, opener.boundTo(scope))
        }
    }

    @Test
    fun coroutineCancellationSignalsABlockedProviderOpen() = runBlocking {
        HostileOpenDocumentsProvider.reset(HostileOpenDocumentsProvider.Mode.BLOCK_OPEN)
        val read = async(Dispatchers.IO) {
            withContentCancellation { scope ->
                boundOpener(scope).open(HostileOpenDocumentsProvider.DOCUMENT_URI.toString(), null)
            }
        }
        assertTrue(
            "provider open did not start",
            HostileOpenDocumentsProvider.openEntered.await(2, TimeUnit.SECONDS),
        )
        assertTrue(
            "framework did not deliver the requested CancellationSignal",
            HostileOpenDocumentsProvider.openSignalReceived.await(2, TimeUnit.SECONDS),
        )

        read.cancel(CancellationException("cancel blocked content open"))
        withTimeout(2_000) { read.join() }

        assertTrue(
            "provider open did not observe CancellationSignal.cancel()",
            HostileOpenDocumentsProvider.openCancellationObserved.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun coroutineCancellationClosesABlockedProviderRead() = runBlocking {
        HostileOpenDocumentsProvider.reset(HostileOpenDocumentsProvider.Mode.BLOCK_READ)
        val read = async(Dispatchers.IO) {
            withContentCancellation { scope ->
                boundOpener(scope).open(HostileOpenDocumentsProvider.DOCUMENT_URI.toString(), null)
                    ?.use { input ->
                        val buffer = ByteArray(1)
                        while (input.read(buffer) >= 0) {
                            // spin until cancellation closes the descriptor
                        }
                    }
            }
        }
        assertTrue(
            "provider pipe did not reach the blocking read fixture",
            HostileOpenDocumentsProvider.readBlockReady.await(2, TimeUnit.SECONDS),
        )

        read.cancel(CancellationException("cancel blocked content read"))
        try {
            withTimeout(2_000) { read.join() }
        } finally {
            HostileOpenDocumentsProvider.readCloseProbe.countDown()
        }

        assertTrue(
            "descriptor-backed stream was not closed on cancellation",
            HostileOpenDocumentsProvider.readCloseObserved.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun coroutineCancellationReleasesRandomAccessAndNativeSeekResources() = runBlocking {
        HostileOpenDocumentsProvider.reset(HostileOpenDocumentsProvider.Mode.SEEKABLE_HOLD)
        val uri = HostileOpenDocumentsProvider.DOCUMENT_URI.toString()
        val work = async(Dispatchers.IO) {
            withContentCancellation { scope ->
                val bound = boundOpener(scope)
                bound.openRandomAccess(uri, null)
                    ?: error("random access must open for seekable fixture")
                bound.openNativeSeekablePath(uri, null)
                    ?: error("native seek path must open for seekable fixture")
                awaitCancellation()
            }
        }
        assertTrue(
            "both descriptor opens were not observed before cancellation",
            HostileOpenDocumentsProvider.seekableBothOpensObserved.await(2, TimeUnit.SECONDS),
        )
        work.cancel(CancellationException("cancel seek resources"))
        withTimeout(2_000) { work.join() }
        assertTrue(
            "random-access descriptor was not closed on cancellation",
            HostileOpenDocumentsProvider.seekableCloseObserved.await(2, TimeUnit.SECONDS),
        )
        assertEquals(
            "each opened descriptor must close independently",
            0,
            HostileOpenDocumentsProvider.seekableCloseObserved.count,
        )
    }

    @Test
    fun cancellationInducedIOExceptionEscapesAsCancellationException() = runBlocking {
        HostileOpenDocumentsProvider.reset(HostileOpenDocumentsProvider.Mode.BLOCK_READ)
        val scope = ContentCancellationScope()
        try {
            val observer = async(Dispatchers.IO) {
                val probe = ResidentHashProbe(boundOpener(scope))
                probe.fingerprintEvidenceResultFor(
                    platformId = "nes",
                    documentUri = HostileOpenDocumentsProvider.DOCUMENT_URI.toString(),
                    managedPath = null,
                )
            }
            assertTrue(
                HostileOpenDocumentsProvider.readBlockReady.await(2, TimeUnit.SECONDS),
            )
            scope.cancel()
            try {
                withTimeout(2_000) {
                    observer.await()
                }
                fail("Expected fingerprint cancellation")
            } catch (_: TimeoutCancellationException) {
                fail("Observer hung instead of cancelling within the timeout")
            } catch (_: CancellationException) {
                // Expected: bound opener must not collapse cancellation into Retryable.
            } finally {
                observer.cancel()
                runCatching { withTimeout(2_000) { observer.join() } }
            }
        } finally {
            scope.close()
            HostileOpenDocumentsProvider.readCloseProbe.countDown()
        }
    }

    @Test
    fun seekableDescriptorRandomAccessAndNativeSeekRemainUnchanged() = runBlocking {
        HostileOpenDocumentsProvider.reset(HostileOpenDocumentsProvider.Mode.SEEKABLE)
        val uri = HostileOpenDocumentsProvider.DOCUMENT_URI.toString()
        withContentCancellation { scope ->
            val bound = boundOpener(scope)
            bound.openRandomAccess(uri, null)?.use { content ->
                val observed = ByteArray(11)
                assertArrayEquals(
                    ByteArray(11) { index -> (index * 7 + 3).toByte() },
                    observed.also { content.readAt(0, it) },
                )
            } ?: error("random access must open for seekable fixture")
            val native = bound.openNativeSeekablePath(uri, null)
                ?: error("native seek path must open for seekable fixture")
            native.use { seekable ->
                assertTrue(seekable.path.startsWith("/proc/self/fd/"))
            }
        }
    }

    @Test
    fun safNativeSeekablePathGetterThrowsAfterExplicitScopeCancel() = runBlocking {
        HostileOpenDocumentsProvider.reset(HostileOpenDocumentsProvider.Mode.SEEKABLE)
        val scope = ContentCancellationScope()
        try {
            val native = boundOpener(scope).openNativeSeekablePath(
                HostileOpenDocumentsProvider.DOCUMENT_URI.toString(),
                null,
            ) ?: error("native seek path must open for seekable fixture")
            assertTrue(native.path.startsWith("/proc/self/fd/"))
            scope.cancel()
            try {
                native.path
                fail("Expected CancellationException when reading SAF native path after scope cancel")
            } catch (_: CancellationException) {
                // Expected: eager path must not escape once cancellation closes the descriptor.
            }
        } finally {
            scope.close()
        }
    }

    @Test
    fun directNativeSeekablePathGetterThrowsAfterExplicitScopeCancel() = runBlocking {
        val file = kotlin.io.path.createTempFile("mimir-native-cancel", ".bin").toFile()
        file.writeBytes(byteArrayOf(1, 2, 3))
        val scope = ContentCancellationScope()
        try {
            val native = boundOpener(scope).openNativeSeekablePath(null, file.absolutePath)
                ?: error("direct native seek path must open for managed fixture")
            assertEquals(file.absolutePath, native.path)
            scope.cancel()
            try {
                native.path
                fail("Expected CancellationException when reading direct native path after scope cancel")
            } catch (_: CancellationException) {
                // Expected: eager path must not escape once cancellation closes the holder.
            }
        } finally {
            scope.close()
            file.delete()
        }
    }

    @Test
    fun directManagedPathOpenDoesNotRequireProviderClient() = runBlocking {
        val file = kotlin.io.path.createTempFile("mimir-open-cancel", ".bin").toFile()
        file.writeBytes(byteArrayOf(9, 8, 7))
        try {
            withContentCancellation { scope ->
                val bound = boundOpener(scope)
                bound.open(null, file.absolutePath)!!.use { stream ->
                    assertEquals(9, stream.read())
                }
                assertNotNull(bound.openRandomAccess(null, file.absolutePath))
            }
        } finally {
            file.delete()
        }
    }

    private fun boundOpener(scope: ContentCancellationScope): ContentOpener =
        CancellationBoundContentOpener(
            contentResolver = context.contentResolver,
            scope = scope,
            directFile = { null },
        )
}
