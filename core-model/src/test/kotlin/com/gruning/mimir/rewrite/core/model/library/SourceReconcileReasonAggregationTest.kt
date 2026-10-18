package com.gruning.mimir.rewrite.core.model.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LibraryRepository.reconcileSources] must SUM each root's skip-reason breakdown, not drop it.
 *
 * Until then this loop carried four integers out of a multi-root
 * reconcile and discarded every root's `skippedByReason`, so a rescan-all could say how many files
 * were skipped and never why — and the last-scan summary a surface reads would have gone stale, or
 * silently lost its breakdown, the moment the player rescanned everything.
 *
 * The invariant asserted here is [ScanResult.skippedByReason]'s own, carried up to the aggregate:
 * **the values sum to exactly `skipped`.** It is checked rather than assumed because summation is
 * precisely where an invariant like that gets quietly broken.
 */
class SourceReconcileReasonAggregationTest {

    private class TwoRootRepository(
        private val results: Map<String, ScanResult>,
    ) : LibraryRepository {
        override fun observeSources(): Flow<List<SourceRow>> = flowOf(
            results.keys.map { uri ->
                SourceRow(friendlyPath = uri, rootUri = uri, gameCount = 0, available = true)
            },
        )

        override suspend fun rescan(rootUri: String): ScanResult = results.getValue(rootUri)

        // Nothing below is reached by reconcileSources; a fake that pretended otherwise would be
        // claiming coverage it does not have.
        override fun observeSummary(): Flow<LibrarySummary> = TODO("not used")
        override fun observePlatforms(): Flow<List<PlatformRow>> = TODO("not used")
        override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> = TODO("not used")
        override fun observeGame(gameId: GameId): Flow<GameRow?> = TODO("not used")
        override fun observeGameMembers(gameId: GameId): Flow<List<GameMemberRow>> = TODO("not used")
        override fun observeFavorites(): Flow<List<GameRow>> = TODO("not used")
        override fun observeResolvingCount(): Flow<Int> = TODO("not used")
        override fun observeHiddenGames(): Flow<List<GameRow>> = TODO("not used")
        override suspend fun setFavorite(gameId: GameId, favorite: Boolean) = TODO("not used")
        override suspend fun setHidden(gameId: GameId, hidden: Boolean) = TODO("not used")
        override suspend fun setTitleOverride(gameId: GameId, title: String) = TODO("not used")
        override suspend fun clearTitleOverride(gameId: GameId) = TODO("not used")
        override suspend fun hasGame(gameId: GameId): Boolean = TODO("not used")
    }

    private fun scan(skipped: Int, reasons: Map<String, Int>) = ScanResult(
        filesSeen = 100,
        recognized = 100 - skipped,
        groups = 1,
        skipped = skipped,
        outcome = ScanOutcome.Healthy,
        skippedByReason = reasons,
    )

    @Test
    fun `reconcile sums each root's reasons and keeps the sum-to-skipped invariant`() = runBlocking {
        val repository = TwoRootRepository(
            mapOf(
                // One reason shared between the roots and one unique to each: a merge that only
                // concatenated, or only kept the last root, would pass a single-reason fixture.
                "content://a" to scan(skipped = 5, reasons = mapOf("UNREADABLE" to 3, "BIOS" to 2)),
                "content://b" to scan(skipped = 4, reasons = mapOf("UNREADABLE" to 1, "ARCHIVE" to 3)),
            ),
        )

        val result = repository.reconcileSources()

        assertEquals(9, result.skipped)
        assertEquals(mapOf("UNREADABLE" to 4, "BIOS" to 2, "ARCHIVE" to 3), result.skippedByReason)
        assertEquals(
            "the aggregate must satisfy the same invariant each root's map does",
            result.skipped,
            result.skippedByReason.values.sum(),
        )
    }

    @Test
    fun `a root that tallied no reasons contributes nothing rather than a zero`() = runBlocking {
        val repository = TwoRootRepository(
            mapOf(
                "content://a" to scan(skipped = 2, reasons = mapOf("BIOS" to 2)),
                // A producer that does not tally: skipped is real, the breakdown is absent. Absence
                // must stay absence -- inventing a zero row here would be the fabricated measurement
                // ScanResult's own KDoc forbids.
                "content://b" to scan(skipped = 3, reasons = emptyMap()),
            ),
        )

        val result = repository.reconcileSources()

        assertEquals(5, result.skipped)
        assertEquals(mapOf("BIOS" to 2), result.skippedByReason)
        assertTrue(
            "an untallied root breaks the sum invariant, and that is honest -- it must not be patched over",
            result.skippedByReason.values.sum() < result.skipped,
        )
    }
}
