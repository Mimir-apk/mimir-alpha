package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityCanonicalTitleRepairTest {

    @Test fun `repair restamps only affected multi-disc groups and becomes a no-op`() = runBlocking {
        val stored = linkedMapOf(
            "chrono" to game(
                gameId = "chrono",
                canonicalTitle = "Chrono Cross (USA, Canada) (Disc 2)",
                groupKind = DuplicateGroupKind.MULTI_DISC.name,
            ),
            "xenogears" to game(
                gameId = "xenogears",
                canonicalTitle = "Xenogears (USA) (Disc 1)",
                groupKind = DuplicateGroupKind.MULTI_DISC.name,
            ),
            "lone" to game(
                gameId = "lone",
                canonicalTitle = "Resident Evil - Code Veronica X (USA) (Disc 2)",
                groupKind = null,
            ),
            "already-neutral" to game(
                gameId = "already-neutral",
                canonicalTitle = "Final Fantasy VII (USA)",
                groupKind = DuplicateGroupKind.MULTI_DISC.name,
            ),
            "unknown-kind" to game(
                gameId = "unknown-kind",
                canonicalTitle = "Future Game (USA) (Disc 1)",
                groupKind = "FUTURE_KIND",
            ),
        )
        val calls = mutableListOf<Pair<GameEntity, String>>()
        val persist: suspend (GameEntity, String) -> Unit = { primary, title ->
            calls += primary to title
            stored[primary.gameId] = primary.copy(canonicalTitle = title)
        }

        assertEquals(
            2,
            repairDiscTaggedCanonicalTitles(loadPrimaries = { stored.values.toList() }, persist = persist),
        )
        assertEquals(
            listOf(
                "chrono" to "Chrono Cross (USA, Canada)",
                "xenogears" to "Xenogears (USA)",
            ),
            calls.map { (primary, title) -> primary.gameId to title },
        )
        assertEquals("collapse:chrono", calls.first().first.collapseKey)
        assertEquals("REDUMP", calls.first().first.identityOrigin)
        assertEquals(1234L, calls.first().first.identityResolvedAtMillis)

        calls.clear()
        assertEquals(
            0,
            repairDiscTaggedCanonicalTitles(loadPrimaries = { stored.values.toList() }, persist = persist),
        )
        assertEquals(emptyList<Pair<GameEntity, String>>(), calls)
    }

    @Test fun `ordered repair cannot stamp an obsolete identity onto same path replacement`() = runBlocking {
        val mutations = SourceRootMutationCoordinator()
        val old = game(
            gameId = "chrono",
            canonicalTitle = "Chrono Cross (USA, Canada) (Disc 2)",
            groupKind = DuplicateGroupKind.MULTI_DISC.name,
        )
        val current = AtomicReference(old)
        val persistStarted = CountDownLatch(1)
        val releasePersist = CountDownLatch(1)
        val scanAttempted = CountDownLatch(1)
        val scanMutationEntered = CountDownLatch(1)

        val repair = async(Dispatchers.Default) {
            repairDiscTaggedCanonicalTitlesOrdered(
                sourceIndexMutations = mutations,
                loadPrimaries = { listOf(current.get()) },
                persist = { primary, title ->
                    persistStarted.countDown()
                    check(releasePersist.await(5, TimeUnit.SECONDS))
                    current.set(primary.copy(canonicalTitle = title))
                },
            )
        }
        assertTrue(persistStarted.await(5, TimeUnit.SECONDS))
        val scan = async(Dispatchers.Default) {
            scanAttempted.countDown()
            mutations.commitScanIfPresent("root") {
                scanMutationEntered.countDown()
                // The replacement owns the same stable logical id/path. Scan truth clears old identity.
                current.set(old.copy(title = "Replacement", canonicalTitle = null))
            }
        }
        assertTrue(scanAttempted.await(5, TimeUnit.SECONDS))
        assertFalse(scanMutationEntered.await(200, TimeUnit.MILLISECONDS))
        assertTrue("scan must wait while the ordered repair owns the gate", !scan.isCompleted)
        releasePersist.countDown()

        assertEquals(1, repair.await())
        assertTrue(scan.await())
        assertEquals("Replacement", current.get().title)
        assertNull(current.get().canonicalTitle)
    }

    private fun game(gameId: String, canonicalTitle: String?, groupKind: String?): GameEntity = GameEntity(
        entryKey = "$gameId-primary",
        gameId = gameId,
        platformId = "ps1",
        contentKind = "GAME",
        displayName = "$gameId.m3u",
        title = gameId,
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "SAF",
        availability = "AVAILABLE",
        sizeBytes = 64,
        contentHash = null,
        sourceRootUri = "content://root",
        sourceDocumentUri = "content://root/$gameId.m3u",
        managedPath = null,
        relativePath = "$gameId.m3u",
        discNumber = 0,
        duplicateGroupId = gameId,
        isPrimaryMember = true,
        duplicateGroupKind = groupKind,
        canonicalTitle = canonicalTitle,
        collapseKey = "collapse:$gameId",
        identityOrigin = "REDUMP",
        identityResolvedAtMillis = 1234L,
    )
}
