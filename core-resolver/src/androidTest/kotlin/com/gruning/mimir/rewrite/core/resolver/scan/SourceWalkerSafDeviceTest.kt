package com.gruning.mimir.rewrite.core.resolver.scan

import android.content.Context
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceWalkerSafDeviceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val walker = SourceWalker(context, SourceWalkLimits(maxDepth = 8, maxEntries = 32))

    @Test
    fun hostileDocumentGraphsFailWithoutPartialSuccess() = runBlocking {
        listOf(
            HostileDocumentsProvider.Mode.SELF_CYCLE,
            HostileDocumentsProvider.Mode.TWO_NODE_CYCLE,
            HostileDocumentsProvider.Mode.DUPLICATE_DOCUMENT_ID,
            HostileDocumentsProvider.Mode.DUPLICATE_RELATIVE_PATH,
            HostileDocumentsProvider.Mode.NULL_DOCUMENT_ID,
            HostileDocumentsProvider.Mode.NULL_DISPLAY_NAME,
            HostileDocumentsProvider.Mode.NULL_MIME_TYPE,
            HostileDocumentsProvider.Mode.PROVIDER_FAULT,
        ).forEach { mode ->
            HostileDocumentsProvider.reset(mode)
            assertTrue("$mode must fail closed", walker.walk(HostileDocumentsProvider.TREE_URI.toString()) is
                SourceWalkResult.Failure)
        }
    }

    @Test
    fun wellFormedProviderCompletes() = runBlocking {
        HostileDocumentsProvider.reset(HostileDocumentsProvider.Mode.COMPLETE)

        val result = walker.walk(HostileDocumentsProvider.TREE_URI.toString())

        assertEquals(1, (result as SourceWalkResult.Complete).files.size)
        assertEquals("Game.gba", result.files.single().displayName)
    }

    @Test
    fun coroutineCancellationReleasesABlockedProviderQuery() = runBlocking {
        HostileDocumentsProvider.reset(HostileDocumentsProvider.Mode.BLOCK_QUERY)
        val scan = async(Dispatchers.IO) {
            walker.walk(HostileDocumentsProvider.TREE_URI.toString())
        }
        assertTrue(
            "provider query did not start",
            HostileDocumentsProvider.queryEntered.await(2, TimeUnit.SECONDS),
        )

        scan.cancel(CancellationException("device cancellation probe"))
        try {
            withTimeout(2_000) { scan.await() }
            throw AssertionError("Expected scan cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }

        assertTrue(
            "provider did not observe CancellationSignal.cancel()",
            HostileDocumentsProvider.cancellationObserved.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun coroutineCancellationSignalsABlockedPlaylistOpen() = runBlocking {
        HostileDocumentsProvider.reset(HostileDocumentsProvider.Mode.BLOCK_OPEN)
        val read = async(Dispatchers.IO) {
            walker.readUtf8(playlistFile(), 1024)
        }
        assertTrue(
            "provider open did not start",
            HostileDocumentsProvider.openEntered.await(2, TimeUnit.SECONDS),
        )
        assertTrue(
            "framework did not deliver the requested CancellationSignal",
            HostileDocumentsProvider.openSignalReceived.await(2, TimeUnit.SECONDS),
        )

        read.cancel(CancellationException("cancel blocked M3U open"))
        withTimeout(2_000) { read.join() }

        assertTrue(
            "provider open did not observe CancellationSignal.cancel()",
            HostileDocumentsProvider.openCancellationObserved.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun coroutineCancellationClosesABlockedPlaylistRead() = runBlocking {
        HostileDocumentsProvider.reset(HostileDocumentsProvider.Mode.BLOCK_READ)
        val read = async(Dispatchers.IO) {
            walker.readUtf8(playlistFile(), 1024)
        }
        assertTrue(
            "provider pipe did not reach the blocking read fixture",
            HostileDocumentsProvider.readBlockReady.await(2, TimeUnit.SECONDS),
        )

        read.cancel(CancellationException("cancel blocked M3U read"))
        try {
            // Do not let the provider write again until the cancelled reader has actually returned.
            // A successful join therefore proves cancellation closed the pinned read; a fixture byte
            // cannot wake it and create a false pass.
            withTimeout(2_000) { read.join() }
        } finally {
            HostileDocumentsProvider.readCloseProbe.countDown()
        }

        assertTrue(
            "descriptor-backed stream was not closed on cancellation",
            HostileDocumentsProvider.readCloseObserved.await(2, TimeUnit.SECONDS),
        )
    }

    private fun playlistFile(): WalkedFile = WalkedFile(
        displayName = "Set.m3u",
        relativePath = "Set.m3u",
        sizeBytes = 64,
        documentUri = DocumentsContract.buildDocumentUriUsingTree(
            HostileDocumentsProvider.TREE_URI,
            HostileDocumentsProvider.PLAYLIST_ID,
        ).toString(),
        filePath = null,
    )
}
