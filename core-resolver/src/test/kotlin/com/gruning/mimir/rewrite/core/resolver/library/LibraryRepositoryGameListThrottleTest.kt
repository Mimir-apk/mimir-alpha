package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Measured 2026-09-28: A platform gamelist is read from one snapshot, which holds Room's write connection, so
 * it re-reads at most once per [GameListRefreshMillis] while identification writes in bursts. A change after
 * a quiet spell must still land at once.
 */
class LibraryRepositoryGameListThrottleTest {

    @Test
    fun `a change after a quiet spell lands at once`() = runTest {
        val rows = MutableStateFlow(listOf(row("snes:mana")))
        val repository = repository(rows, StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<List<GameRow>>()
        val job = launch { repository.observeGames(PlatformId.SNES).collect { seen += it } }
        runCurrent()

        advanceTimeBy(GameListRefreshMillis + 1)
        rows.value = listOf(row("snes:mana"), row("snes:earth"))
        runCurrent()
        assertEquals(2, seen.last().size)
        job.cancel()
    }

    @Test
    fun `a change inside a burst waits for the period, then lands`() = runTest {
        val rows = MutableStateFlow(listOf(row("snes:mana")))
        val repository = repository(rows, StandardTestDispatcher(testScheduler))
        val seen = mutableListOf<List<GameRow>>()
        val job = launch { repository.observeGames(PlatformId.SNES).collect { seen += it } }
        runCurrent()

        advanceTimeBy(100)
        rows.value = listOf(row("snes:mana"), row("snes:earth"))
        runCurrent()
        assertEquals("inside the period the old list stands", 1, seen.last().size)

        advanceTimeBy(GameListRefreshMillis)
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
    )

    private fun repository(
        rows: MutableStateFlow<List<GameRowProjection>>,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "visibleGameRowsForPlatform" -> rows
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
