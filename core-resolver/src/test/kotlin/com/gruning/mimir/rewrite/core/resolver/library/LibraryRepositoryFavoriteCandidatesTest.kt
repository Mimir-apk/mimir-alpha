package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * RG Rotate measurement (2026-09-28): The favourites picker's full read is throttled, because it holds Room's
 * write connection while identification is writing; the star comes from its own small read and is not.
 */
class LibraryRepositoryFavoriteCandidatesTest {

    @Test
    fun `a favourite toggle repaints at once, without waiting for the throttled list`() = runTest {
        val rows = MutableStateFlow(listOf(row("snes:mana"), row("snes:earth")))
        val favourites = MutableStateFlow(emptyList<String>())
        val repository = repository(rows, favourites, StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<List<GameRow>>()
        val job = launch { repository.observeFavoriteCandidates().collect { seen += it } }
        runCurrent()
        assertEquals(listOf(false, false), seen.last().map { it.isFavorite })

        advanceTimeBy(100)
        favourites.value = listOf("snes:earth")
        runCurrent()
        assertEquals(listOf(false, true), seen.last().map { it.isFavorite })
        job.cancel()
    }

    @Test
    fun `a star re-marks the rows it touches and reuses every other row as it was`() = runTest {
        // A favorite change previously took too long to appear on RG Rotate. A star
        // used to re-map every row; now only the starred row is a new object.
        val rows = MutableStateFlow(listOf(row("snes:mana"), row("snes:earth"), row("snes:chrono")))
        val favourites = MutableStateFlow(emptyList<String>())
        val repository = repository(rows, favourites, StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<List<GameRow>>()
        val job = launch { repository.observeFavoriteCandidates().collect { seen += it } }
        runCurrent()
        val before = seen.last()

        advanceTimeBy(100)
        favourites.value = listOf("snes:earth")
        runCurrent()
        val after = seen.last()
        assertEquals(listOf(false, true, false), after.map { it.isFavorite })
        assertSame(before[0], after[0])
        assertSame(before[2], after[2])
        assertNotSame(before[1], after[1])
        job.cancel()
    }

    @Test
    fun `a list change inside the period waits for the period, then lands`() = runTest {
        val rows = MutableStateFlow(listOf(row("snes:mana")))
        val repository = repository(rows, MutableStateFlow(emptyList()), StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<List<GameRow>>()
        val job = launch { repository.observeFavoriteCandidates().collect { seen += it } }
        runCurrent()
        assertEquals(1, seen.last().size)

        advanceTimeBy(100)
        rows.value = listOf(row("snes:mana"), row("snes:earth"))
        runCurrent()
        assertEquals("inside the period the old list stands", 1, seen.last().size)

        advanceTimeBy(FavoriteCandidateRefreshMillis)
        runCurrent()
        assertEquals(2, seen.last().size)
        job.cancel()
    }

    private fun row(gameId: String) = GameRowProjection(
        gameId = gameId,
        title = gameId.substringAfter(':'),
        platformId = "snes",
        duplicateGroupKind = null,
        memberCount = 1,
        isFavorite = 0,
        hasOverride = 0,
        lastPlayedMillis = null,
        playCount = 0,
        region = null,
        version = null,
        sizeBytes = 0L,
    )

    private fun repository(
        rows: Flow<List<GameRowProjection>>,
        favourites: Flow<List<String>>,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "favoriteCandidateGameRows" -> rows
                "favoriteGroupIds" -> favourites
                else -> error("Unexpected GameDao call: $methodName")
            }
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = unusedProxy(),
            hiddenItemDao = unusedProxy(),
            favoriteDao = unusedProxy(),
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult =
                    SourceWalkResult.Complete(emptyList())
            },
            runScanTransaction = { block -> block() },
            ioDispatcher = dispatcher,
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
