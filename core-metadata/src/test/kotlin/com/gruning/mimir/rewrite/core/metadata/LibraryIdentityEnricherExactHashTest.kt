package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.SourceRootMutationCoordinator
import com.gruning.mimir.rewrite.core.storage.dao.IdentityWrite
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryIdentityEnricherExactHashTest {

    @Test
    fun `raw whole-file sha1 persists even when the DAT lookup misses`() = runBlocking {
        val game = game()
        var resolved = false
        var persistedEntry: String? = null
        var persistedHash: String? = null
        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (resolved) emptyList<GameEntity>() else listOf(game)
                "membersOfGameBySize" -> listOf(game)
                "setContentHash" -> {
                    val callArgs = requireNotNull(args)
                    persistedEntry = callArgs[0] as String
                    persistedHash = callArgs[1] as String
                    1
                }
                "setIdentity" -> {
                    resolved = true
                    Unit
                }
                // SD01b: the enricher now lands each chunk through the batched transaction.
                "setIdentities" -> {
                    resolved = true
                    Unit
                }
                "reconcileExactHashDuplicates" -> 0
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
        val bytes = "exact raw bytes".toByteArray()
        val enricher = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = dao,
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
            opener = ContentOpener { _, _ -> ByteArrayInputStream(bytes) },
            nowMillis = { 1L },
        )

        val report = enricher.enrich(maxGames = 1, batch = 1)

        assertEquals(1, report.attempted)
        assertEquals(0, report.matched)
        assertEquals(0, report.exactGroupsFolded)
        assertEquals(game.entryKey, persistedEntry)
        assertEquals("raw-sha1:${FileHasher.hash(ByteArrayInputStream(bytes)).sha1}", persistedHash)
    }

    @Test
    fun `same path replacement refuses stale hash and identity then resolves fresh bytes`() = runBlocking {
        val oldBytes = "resident bytes A".toByteArray()
        val newBytes = "resident bytes B".toByteArray()
        check(oldBytes.size == newBytes.size)
        val currentGame = AtomicReference(game())
        val currentBytes = AtomicReference(oldBytes)
        val firstReadStarted = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        val firstRead = AtomicBoolean(true)
        val persistedHashes = mutableListOf<Pair<String, String>>()
        val persistedIdentities = mutableListOf<IdentityWrite>()
        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" -> listOf(currentGame.get())
                "membersOfGameBySize" -> listOf(currentGame.get())
                "setContentHash" -> {
                    val callArgs = requireNotNull(args)
                    persistedHashes += (callArgs[0] as String) to (callArgs[1] as String)
                    1
                }
                "setIdentities" -> {
                    @Suppress("UNCHECKED_CAST")
                    persistedIdentities += requireNotNull(args)[0] as List<IdentityWrite>
                    Unit
                }
                "reconcileExactHashDuplicates" -> 0
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
        val mutations = SourceRootMutationCoordinator()
        val opener = ContentOpener { _, _ ->
            val bytesForThisOpen = currentBytes.get()
            if (firstRead.compareAndSet(true, false)) {
                firstReadStarted.countDown()
                check(releaseFirstRead.await(5, TimeUnit.SECONDS))
            }
            ByteArrayInputStream(bytesForThisOpen)
        }
        val enricher = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = dao,
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
            opener = opener,
            nowMillis = { 1L },
            sourceIndexMutations = mutations,
        )

        val staleRun = async(Dispatchers.Default) {
            enricher.enrich(maxGames = 1, batch = 1)
        }
        assertTrue(firstReadStarted.await(5, TimeUnit.SECONDS))
        mutations.commitScanIfPresent("root") {
            // Same key, game id, path, and size: only the generation can distinguish this incarnation.
            currentGame.set(game().copy(title = "Replacement"))
            currentBytes.set(newBytes)
        }
        releaseFirstRead.countDown()

        val staleReport = staleRun.await()
        assertEquals(1, staleReport.attempted)
        assertEquals(0, staleReport.matched)
        assertEquals(1, staleReport.retryable)
        assertTrue(staleReport.restartFromBeginning)
        assertEquals(null, staleReport.nextCursor)
        assertTrue(persistedHashes.isEmpty())
        assertTrue(persistedIdentities.isEmpty())

        val freshReport = enricher.enrich(maxGames = 1, batch = 1)
        assertEquals(1, freshReport.attempted)
        assertEquals(0, freshReport.retryable)
        assertEquals(
            listOf(
                currentGame.get().entryKey to
                    "raw-sha1:${FileHasher.hash(ByteArrayInputStream(newBytes)).sha1}",
            ),
            persistedHashes,
        )
        assertEquals(1, persistedIdentities.size)
    }

    @Test
    fun `same path replacement refuses retryable no-write batch and restarts cursor`() = runBlocking {
        val currentGame = AtomicReference(game())
        val openStarted = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val mutations = SourceRootMutationCoordinator()
        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" -> listOf(currentGame.get())
                "reconcileExactHashDuplicates" -> 0
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
        val enricher = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = dao,
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
            opener = ContentOpener { _, _ ->
                openStarted.countDown()
                check(releaseOpen.await(5, TimeUnit.SECONDS))
                null
            },
            nowMillis = { 1L },
            sourceIndexMutations = mutations,
        )

        val staleRun = async(Dispatchers.Default) {
            enricher.enrich(maxGames = 1, batch = 1)
        }
        assertTrue(openStarted.await(5, TimeUnit.SECONDS))
        mutations.commitScanIfPresent("root") {
            currentGame.set(game().copy(title = "Replacement"))
        }
        releaseOpen.countDown()

        val report = staleRun.await()
        assertEquals(1, report.attempted)
        assertEquals(0, report.matched)
        assertEquals(1, report.retryable)
        assertTrue(report.restartFromBeginning)
        assertEquals(null, report.nextCursor)
    }

    @Test
    fun `replacement after accepted retryable page invalidates resumed cursor`() = runBlocking {
        val first = game().copy(
            entryKey = "root::a.gb",
            gameId = "a",
            managedPath = "/games/a.gb",
            relativePath = "a.gb",
            sizeBytes = 10L,
        )
        val second = game().copy(
            entryKey = "root::b.gb",
            gameId = "b",
            managedPath = "/games/b.gb",
            relativePath = "b.gb",
            sizeBytes = 20L,
        )
        val currentFirst = AtomicReference(first)
        val queryCount = AtomicInteger()
        val mutations = SourceRootMutationCoordinator()
        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    when (queryCount.getAndIncrement()) {
                        0 -> listOf(currentFirst.get())
                        1 -> listOf(second)
                        else -> emptyList<GameEntity>()
                    }
                "reconcileExactHashDuplicates" -> 0
                else -> error("Unexpected GameDao call: ${method.name}")
            }
        } as GameDao
        val enricher = LibraryIdentityEnricher(
            chdIdentity = ChdIdentitySource.RECOMPUTED_PAYLOAD,
            dao = dao,
            datSource = BundledDatSource(PackedDatAssetProvider { null }, manifest = emptyList()),
            idSource = BundledIdSource(DatAssetProvider { null }, manifest = emptyList()),
            opener = ContentOpener { _, path ->
                check(path != second.managedPath) { "stale resumed cursor reached file I/O" }
                null
            },
            nowMillis = { 1L },
            sourceIndexMutations = mutations,
        )

        val firstReport = enricher.enrich(maxGames = 1, batch = 1, reconcile = false)
        assertEquals(1, firstReport.attempted)
        assertEquals(1, firstReport.retryable)
        val acceptedCursor = requireNotNull(firstReport.nextCursor)
        val acceptedGeneration = requireNotNull(firstReport.nextGeneration)

        mutations.commitScanIfPresent("root") {
            currentFirst.set(first.copy(title = "Replacement"))
        }

        val resumed = enricher.enrich(
            maxGames = 1,
            batch = 1,
            reconcile = false,
            afterCursor = acceptedCursor,
            afterGeneration = acceptedGeneration,
        )
        assertEquals(0, resumed.attempted)
        assertEquals(0, resumed.retryable)
        assertTrue(resumed.restartFromBeginning)
        assertEquals(null, resumed.nextCursor)
        assertEquals(null, resumed.nextGeneration)
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
        sizeBytes = 15L,
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
}
