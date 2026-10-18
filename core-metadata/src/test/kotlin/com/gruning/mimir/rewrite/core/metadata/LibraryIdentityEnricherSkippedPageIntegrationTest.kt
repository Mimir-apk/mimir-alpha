package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIdentityEnricherSkippedPageIntegrationTest {

    @Test
    fun `precheckpoint cancellation replays skipped page on null cursor retry`() = runBlocking {
        val clock = FakeClock(0L)
        val fixture = SkippedPageFixture()
        val cursorStore = InMemorySweepCursorStore()

        var killed: Throwable? = null
        try {
            fixture.enricher(clock).enrich(
                maxGames = 2,
                batch = 2,
                concurrency = 1,
                reconcile = false,
                onCheckpoint = { checkpoint ->
                    cursorStore.write(checkpoint)
                    throw CancellationException("process killed before batch writes")
                },
            )
        } catch (cancelled: CancellationException) {
            killed = cancelled
        }
        assertTrue(killed is CancellationException)
        assertNotNull(cursorStore.read())
        assertEquals(2L, cursorStore.read()?.sizeBytes)
        assertEquals("root::b.chd", cursorStore.read()?.entryKey)
        assertEquals(listOf(listOf("root::a.chd", "root::b.chd")), fixture.fetchedPages)

        val tailPass = fixture.enricher(clock).enrich(
            maxGames = 2,
            batch = 2,
            concurrency = 1,
            reconcile = false,
            afterCursor = cursorStore.read(),
            onCheckpoint = cursorStore::write,
        )

        assertEquals(1, tailPass.attempted)
        assertEquals(
            listOf(
                listOf("root::a.chd", "root::b.chd"),
                listOf("root::c.chd"),
            ),
            fixture.fetchedPages,
        )
        assertTrue(tailPass.exhausted)
        cursorStore.clear()
        assertNull(cursorStore.read())
        assertEquals(3, fixture.unresolvedCount())
        assertTrue(shouldRetryAfterSkippedPage(tailPass, fixture.unresolvedCount()))

        fixture.complete("root::c.chd")
        val replayPass = fixture.enricher(clock).enrich(
            maxGames = 3,
            batch = 3,
            concurrency = 1,
            reconcile = false,
            afterCursor = null,
            onCheckpoint = cursorStore::write,
        )

        assertEquals(2, replayPass.attempted)
        assertEquals(
            listOf(
                listOf("root::a.chd", "root::b.chd"),
                listOf("root::c.chd"),
                listOf("root::a.chd", "root::b.chd"),
            ),
            fixture.fetchedPages,
        )
        fixture.complete("root::a.chd")
        fixture.complete("root::b.chd")
        assertEquals(0, fixture.unresolvedCount())
        assertFalse(shouldRetryAfterSkippedPage(replayPass, fixture.unresolvedCount()))
    }

    private fun shouldRetryAfterSkippedPage(
        report: LibraryIdentityEnricher.Report,
        remaining: Int,
    ): Boolean = remaining > 0 && (!report.exhausted || remaining > report.retryable)

    private class InMemorySweepCursorStore {
        private var cursor: LibraryIdentityEnricher.IdentityEnrichmentCursor? = null

        fun read(): LibraryIdentityEnricher.IdentityEnrichmentCursor? = cursor

        fun write(cursor: LibraryIdentityEnricher.IdentityEnrichmentCursor) {
            this.cursor = cursor
        }

        fun clear() {
            cursor = null
        }
    }

    private class SkippedPageFixture {
        val games = listOf(
            game("root::a.chd", 1L),
            game("root::b.chd", 2L),
            game("root::c.chd", 3L),
        )
        val fetchedPages = mutableListOf<List<String>>()
        private val resolved = mutableSetOf<String>()

        fun unresolvedCount(): Int = games.count { it.entryKey !in resolved }

        fun complete(entryKey: String) {
            resolved += entryKey
        }

        fun enricher(clock: FakeClock) = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = dao(),
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
            opener = object : ContentOpener {
                override fun open(documentUri: String?, managedPath: String?): java.io.InputStream? = null
            },
            nanoTime = clock::now,
        )

        private fun dao(): GameDao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries" -> page(args?.getOrNull(0) as? Int ?: games.size)
                "unresolvedPrimariesAfter" -> {
                    val afterSize = args?.getOrNull(0) as? Long ?: -1L
                    val afterKey = args?.getOrNull(1) as? String ?: ""
                    val limit = args?.getOrNull(2) as? Int ?: games.size
                    page(limit, afterSize, afterKey)
                }
                "setIdentities" -> Unit
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao

        private fun page(
            limit: Int,
            afterSize: Long = -1L,
            afterKey: String = "",
        ): List<GameEntity> {
            val batch = games.filter { game ->
                game.entryKey !in resolved &&
                    (
                        afterSize < 0 ||
                            game.sizeBytes > afterSize ||
                            (game.sizeBytes == afterSize && game.entryKey > afterKey)
                        )
            }
                .sortedWith(compareBy({ it.sizeBytes }, { it.entryKey }))
                .take(limit)
            if (batch.isNotEmpty()) {
                fetchedPages += batch.map(GameEntity::entryKey)
            }
            return batch
        }

        private fun game(entryKey: String, sizeBytes: Long) = GameEntity(
            entryKey = entryKey,
            gameId = "$entryKey-game",
            platformId = "gba",
            contentKind = "GAME",
            displayName = entryKey,
            title = "Before",
            region = null,
            version = null,
            serial = null,
            titleCatalogKey = null,
            identitySource = "FILENAME",
            locationKind = "MANAGED_PATH",
            availability = "AVAILABLE",
            sizeBytes = sizeBytes,
            contentHash = null,
            sourceRootUri = "root",
            sourceDocumentUri = null,
            managedPath = "/roms/${entryKey.substringAfterLast("::")}",
            relativePath = entryKey,
            discNumber = 0,
            duplicateGroupId = null,
            isPrimaryMember = true,
            duplicateGroupKind = null,
        )
    }
}
