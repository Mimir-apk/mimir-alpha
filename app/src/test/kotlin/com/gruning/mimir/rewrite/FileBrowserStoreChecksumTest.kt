package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.LocalFileRepository
import com.gruning.mimir.rewrite.feature.files.FileBrowserChecksumState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pins the algorithm the file browser's "Verify" button computes.
 *
 * The trap this guards: `LocalFileRepository.getChecksum` defaults to **SHA-256**, while the corpus the
 * verification exists to check against is keyed on **SHA-1** (`core-metadata/BundledDatSource.kt:17`)
 * and CRC32 (`core-metadata/DatIdentitySource.kt:5`). Taking the default would ship a Verify button
 * that computes a real, correct, permanently unmatchable hash — and whose failure would read as a gap
 * in the catalogue rather than as the wrong algorithm.
 *
 * **What this test does NOT see, stated rather than implied.** It pins the constant, and it proves that
 * constant is a genuinely different algorithm at the exact repository method `FileBrowserStore` hands it
 * to. It does **not** pin the call site: if someone deleted the second argument from
 * `repository.getChecksum(path, CHECKSUM_ALGORITHM)` inside `FileBrowserStore.verifyChecksum`, the
 * default would silently return and **nothing here would fail**. Pinning that needs a Robolectric
 * harness that opens the store with a live `Context` + `FocusAuthority` and drives a real focused tile
 * through `feature-files`' focus machinery — disproportionate for a one-line call, and brittle against
 * the concurrent input lane. Recorded as owed, not quietly skipped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FileBrowserStoreChecksumTest {

    @Test
    fun `Verify computes the DAT corpus algorithm, never the unmatchable SHA-256 default`() = runBlocking {
        assertEquals(
            "the browser must ask for SHA-1 explicitly; the repository's default matches no DAT row",
            "SHA-1",
            FileBrowserStore.CHECKSUM_ALGORITHM,
        )

        val fixture = File.createTempFile("mimir-verify-algorithm-pin", ".bin")
        try {
            fixture.writeText("MIMIR-CHECKSUM-FIXTURE")
            val repository = LocalFileRepository()

            val chosen = repository.getChecksum(fixture.path, FileBrowserStore.CHECKSUM_ALGORITHM)
            val default = repository.getChecksum(fixture.path)

            // 40 hex characters is SHA-1; the default's 64 is SHA-256. Asserting both the length and
            // the inequality keeps this honest if either algorithm is ever swapped for another of the
            // same width.
            assertEquals("SHA-1 is 160 bits -> 40 hex characters", 40, chosen.length)
            assertNotEquals(
                "the chosen algorithm must actually differ from the repository default",
                default,
                chosen,
            )
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun `duplicate Verify press for the active path starts one read and one callback`() = runTest {
        val result = CompletableDeferred<String>()
        val states = mutableListOf<FileBrowserChecksumState>()
        val callbacks = mutableListOf<String>()
        var reads = 0
        val session = Any()
        val coordinator = FileBrowserChecksumRequestCoordinator(
            scope = this,
            algorithm = FileBrowserStore.CHECKSUM_ALGORITHM,
            compute = {
                reads++
                result.await()
            },
            publish = states::add,
        )

        coordinator.request("/tmp/zelda.gba", session, callbacks::add) { error ->
            throw AssertionError(error)
        }
        runCurrent()
        coordinator.request("/tmp/zelda.gba", session, callbacks::add) { error ->
            throw AssertionError(error)
        }

        assertEquals("same path while active must reuse the in-flight read", 1, reads)
        result.complete("digest-1")
        advanceUntilIdle()

        assertEquals(listOf("digest-1"), callbacks)
        assertEquals(
            FileBrowserChecksumState.Checked(
                "/tmp/zelda.gba",
                FileBrowserStore.CHECKSUM_ALGORITHM,
                "digest-1",
            ),
            states.last(),
        )
    }

    @Test
    fun `a stale completion from an older path cannot replace the latest result`() = runTest {
        val oldResult = CompletableDeferred<String>()
        val newResult = CompletableDeferred<String>()
        val states = mutableListOf<FileBrowserChecksumState>()
        val callbacks = mutableListOf<String>()
        val session = Any()
        val coordinator = FileBrowserChecksumRequestCoordinator(
            scope = this,
            algorithm = FileBrowserStore.CHECKSUM_ALGORITHM,
            compute = { path ->
                if (path == "/tmp/old.gba") {
                    withContext(NonCancellable) { oldResult.await() }
                } else {
                    newResult.await()
                }
            },
            publish = states::add,
        )

        coordinator.request("/tmp/old.gba", session, callbacks::add) { error ->
            throw AssertionError(error)
        }
        runCurrent()
        coordinator.request("/tmp/new.gba", session, callbacks::add) { error ->
            throw AssertionError(error)
        }
        runCurrent()

        newResult.complete("new-digest")
        runCurrent()
        oldResult.complete("old-digest")
        advanceUntilIdle()

        assertEquals(listOf("new-digest"), callbacks)
        assertEquals(
            FileBrowserChecksumState.Checked(
                "/tmp/new.gba",
                FileBrowserStore.CHECKSUM_ALGORITHM,
                "new-digest",
            ),
            states.last(),
        )
    }

    @Test
    fun `clear then reopen prevents the old session completion from publishing`() = runTest {
        val oldResult = CompletableDeferred<String>()
        val newResult = CompletableDeferred<String>()
        val states = mutableListOf<FileBrowserChecksumState>()
        val callbacks = mutableListOf<String>()
        val oldSession = Any()
        val newSession = Any()
        var reads = 0
        val coordinator = FileBrowserChecksumRequestCoordinator(
            scope = this,
            algorithm = FileBrowserStore.CHECKSUM_ALGORITHM,
            compute = {
                if (reads++ == 0) {
                    // Keep the first session's read alive despite cancellation so the identity guard
                    // is tested independently of the filesystem's cancellation timing.
                    withContext(NonCancellable) { oldResult.await() }
                } else {
                    newResult.await()
                }
            },
            publish = states::add,
        )

        coordinator.request("/tmp/reopened.gba", oldSession, callbacks::add) { error ->
            throw AssertionError(error)
        }
        runCurrent()
        coordinator.clear()
        coordinator.request("/tmp/reopened.gba", newSession, callbacks::add) { error ->
            throw AssertionError(error)
        }
        runCurrent()

        newResult.complete("new-session-digest")
        runCurrent()
        oldResult.complete("old-session-digest")
        advanceUntilIdle()

        assertEquals(listOf("new-session-digest"), callbacks)
        assertEquals(
            FileBrowserChecksumState.Checked(
                "/tmp/reopened.gba",
                FileBrowserStore.CHECKSUM_ALGORITHM,
                "new-session-digest",
            ),
            states.last(),
        )
    }
}
