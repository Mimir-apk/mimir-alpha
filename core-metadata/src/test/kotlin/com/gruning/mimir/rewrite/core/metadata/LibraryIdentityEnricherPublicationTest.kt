package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RG Rotate measurement (2026-09-28, 5,529 games): Each identified hash was its own commit, and each commit
 * re-ran every open query on `games`; and the probe hashed on Dispatchers.IO at normal priority, whatever pool
 * the sweep ran on. A batch now publishes inside one transaction, on the pool the sweep hands in.
 */
class LibraryIdentityEnricherPublicationTest {

    @Test
    fun `a batch's hashes and identities are written inside one publish transaction`() = runBlocking {
        var insideTransaction = false
        var transactions = 0
        val writesOutside = mutableListOf<String>()
        val dao = recordingDao { name -> if (!insideTransaction) writesOutside += name }
        val enricher = enricher(
            dao = dao,
            runPublishTransaction = { block ->
                transactions++
                insideTransaction = true
                try {
                    block()
                } finally {
                    insideTransaction = false
                }
            },
        )

        enricher.enrich(maxGames = 1, batch = 1)

        assertEquals(1, transactions)
        assertEquals("every publish write lands inside the transaction", emptyList<String>(), writesOutside)
    }

    @Test
    fun `files are read on the dispatcher the sweep hands in`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "sweep-hash-pool") }
        try {
            val openedOn = mutableListOf<String>()
            val enricher = enricher(
                dao = recordingDao { },
                hashDispatcher = executor.asCoroutineDispatcher(),
                opener = ContentOpener { _, _ ->
                    openedOn += Thread.currentThread().name
                    ByteArrayInputStream(BYTES)
                },
            )

            enricher.enrich(maxGames = 1, batch = 1)

            assertTrue("opened at least once", openedOn.isNotEmpty())
            // The coroutines debug agent appends " @coroutine#N" to the thread name under test.
            assertEquals(setOf("sweep-hash-pool"), openedOn.map { it.substringBefore(" @") }.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun enricher(
        dao: GameDao,
        runPublishTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
        hashDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO,
        opener: ContentOpener = ContentOpener { _, _ -> ByteArrayInputStream(BYTES) },
    ) = LibraryIdentityEnricher(
        chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
        dao = dao,
        datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
        idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
        opener = opener,
        nowMillis = { 1L },
        sourceIndexMutations = SourceRootMutationCoordinator(),
        runPublishTransaction = runPublishTransaction,
        hashDispatcher = hashDispatcher,
    )

    /** One unresolved game; [onWrite] sees each publish write by name. */
    private fun recordingDao(onWrite: (String) -> Unit): GameDao {
        var resolved = false
        return Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (resolved) emptyList<GameEntity>() else listOf(game())
                "membersOfGameBySize" -> listOf(game())
                "setContentHash" -> {
                    onWrite(method.name)
                    1
                }
                "setIdentity", "setIdentities" -> {
                    onWrite(method.name)
                    resolved = true
                    Unit
                }
                "reconcileExactHashDuplicates" -> 0
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
    }

    private fun game() = GameEntity(
        entryKey = "root::game.gb",
        gameId = "game",
        platformId = "gb",
        contentKind = "GAME",
        displayName = "game.gb",
        title = "Game",
        region = null,
        version = null,
        serial = null,
        titleCatalogKey = null,
        identitySource = "FILENAME",
        locationKind = "MANAGED_PATH",
        availability = "AVAILABLE",
        sizeBytes = BYTES.size.toLong(),
        contentHash = null,
        sourceRootUri = "root",
        sourceDocumentUri = null,
        managedPath = "/games/game.gb",
        relativePath = "game.gb",
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )

    private companion object {
        val BYTES = "exact raw bytes".toByteArray()
    }
}
