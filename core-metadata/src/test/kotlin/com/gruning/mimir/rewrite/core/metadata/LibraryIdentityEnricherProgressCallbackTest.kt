package com.gruning.mimir.rewrite.core.metadata

import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.entity.GameEntity
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins live, in-memory, per-file identification progress: [LibraryIdentityEnricher.enrich]'s
 * `onGameAttempted` callback must fire once per game, the instant THAT game's own resolution attempt
 * finishes — strictly BEFORE the batch it belongs to reaches [GameDao.setIdentities]. This is what makes
 * the signal real work-done rather than a relabeled Room-commit count: SD01b's batching is exactly what
 * made the prior "N of M" number sit frozen for the length of a whole batch even though every game inside
 * it had already finished hashing.
 */
class LibraryIdentityEnricherProgressCallbackTest {

    @Test
    fun `callback fires per-file before the batch's setIdentities commit`() = runBlocking {
        val games = listOf(game("root::a.gb", "a", 1L), game("root::b.gb", "b", 2L))
        var commitCount = 0
        val commitCountAtCallback = mutableListOf<Int>()
        val attemptedSequence = mutableListOf<Int>()
        val dao = Proxy.newProxyInstance(
            GameDao::class.java.classLoader,
            arrayOf(GameDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "unresolvedPrimaries", "unresolvedPrimariesAfter" ->
                    if (commitCount > 0) emptyList<GameEntity>() else games
                "membersOfGameBySize" -> games.filter { it.gameId == args?.getOrNull(0) as? String }
                "setContentHash" -> 1
                "setIdentities" -> {
                    commitCount++
                    Unit
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
            opener = ContentOpener { _, _ -> ByteArrayInputStream("resident bytes".toByteArray()) },
            nowMillis = { 1L },
        )

        val report = enricher.enrich(
            maxGames = 2,
            batch = 2,
            concurrency = 1,
            reconcile = false,
            onGameAttempted = { attemptedSoFar ->
                attemptedSequence += attemptedSoFar
                commitCountAtCallback += commitCount
            },
        )

        assertEquals(2, report.attempted)
        // Both games' attempts completed -- the callback fired twice, cumulatively 1 then 2 -- and BOTH
        // observations happened while commitCount was still 0: the one setIdentities call for this batch
        // of 2 only lands after every game inside it has already reported through the callback.
        assertEquals(listOf(1, 2), attemptedSequence)
        assertEquals(listOf(0, 0), commitCountAtCallback)
        assertEquals(1, commitCount)
    }

    private fun game(entryKey: String, gameId: String, sizeBytes: Long) = GameEntity(
        entryKey = entryKey,
        gameId = gameId,
        platformId = "gb",
        contentKind = "GAME",
        displayName = entryKey,
        title = "Game",
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
        managedPath = "/games/${entryKey.substringAfterLast("::")}",
        relativePath = entryKey,
        discNumber = 0,
        duplicateGroupId = null,
        isPrimaryMember = true,
        duplicateGroupKind = null,
    )
}
