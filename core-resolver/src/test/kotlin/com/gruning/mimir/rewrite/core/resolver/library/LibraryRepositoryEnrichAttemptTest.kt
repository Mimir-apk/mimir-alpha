package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptOutcome
import com.gruning.mimir.rewrite.core.model.library.EnrichAttemptSource
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * how the last Enrich ended reaches every door through `GameRow.enrichAttempt`, which the projection reads from the
 * app's own record (the seam), never from Room and never from the network. A finished attempt hides Enrich; the writer re-projects the one game.
 */
class LibraryRepositoryEnrichAttemptTest {
    private val gameId = GameId("gba/metroid-fusion")
    private val projection = GameRowProjection(
        gameId = gameId.value,
        title = "Metroid Fusion",
        platformId = PlatformId.GBA.value,
        duplicateGroupKind = null,
        memberCount = 1,
        managedPath = "/games/Metroid Fusion.gba",
    )

    @Test
    fun `a row carries the attempt the seam answers for its game, and null when none is on record`() = runBlocking {
        val asked = mutableListOf<String>()
        val finished = repository(EnrichAttemptSource { id -> asked += id; EnrichAttemptOutcome.NO_MATCH })
            .observeGames(PlatformId.GBA).first().single()
        assertEquals(EnrichAttemptOutcome.NO_MATCH, finished.enrichAttempt)
        assertEquals(listOf(gameId.value), asked)

        val none = repository(EnrichAttemptSource { null }).observeGames(PlatformId.GBA).first().single()
        assertNull(none.enrichAttempt)
    }

    @Test
    fun `the default seam answers nothing`() = runBlocking {
        assertNull(repository(null).observeGames(PlatformId.GBA).first().single().enrichAttempt)
    }

    @Test
    fun `a failed attempt is carried as failed, which is what keeps the button`() = runBlocking {
        val row = repository(EnrichAttemptSource { EnrichAttemptOutcome.FAILED }).observeGame(gameId).first()!!
        assertEquals(EnrichAttemptOutcome.FAILED, row.enrichAttempt)
    }

    /** The writer says so once, through [LibraryRepositoryImpl.refreshArt] for the game, and the open flow re-asks the seam. */
    @Test
    fun `a record written after the row was projected reaches the open flow when the game is refreshed`() = runBlocking {
        var onRecord: EnrichAttemptOutcome? = null
        val repository = repository(EnrichAttemptSource { onRecord })
        val emissions = mutableListOf<EnrichAttemptOutcome?>()
        val job = launch(Dispatchers.Unconfined) {
            repository.observeGames(PlatformId.GBA).collect { emissions += it.single().enrichAttempt }
        }
        assertEquals(listOf<EnrichAttemptOutcome?>(null), emissions)

        onRecord = EnrichAttemptOutcome.MATCHED
        repository.refreshArt(gameId)

        assertEquals(listOf<EnrichAttemptOutcome?>(null, EnrichAttemptOutcome.MATCHED), emissions)
        job.cancel()
    }

    private fun repository(source: EnrichAttemptSource?): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "visibleGameRowsForPlatform" -> flowOf(listOf(projection))
                "observeGameRow" -> flowOf(projection)
                // refreshArt evicts the memoized probes of the game's ROM; this fixture's game has no member row.
                "membersOfGame" -> emptyList<Any>()
                else -> error("Unexpected GameDao call: $methodName")
            }
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = unusedProxy(),
            hiddenItemDao = unusedProxy(),
            favoriteDao = unusedProxy(),
            enrichAttemptSource = source ?: EnrichAttemptSource { null },
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult =
                    SourceWalkResult.Complete(emptyList())
            },
            runScanTransaction = { block -> block() },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private inline fun <reified T> unusedProxy(): T = proxy { methodName ->
        error("Unexpected ${T::class.java.simpleName} call: $methodName")
    }

    private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T
}
