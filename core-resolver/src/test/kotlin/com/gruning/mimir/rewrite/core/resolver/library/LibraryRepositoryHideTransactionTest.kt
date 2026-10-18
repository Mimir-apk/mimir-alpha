package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao
import com.gruning.mimir.rewrite.core.storage.entity.HiddenItemEntity
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A hide is ONE write, and it leaves the favourite alone.
 *
 * The earlier hide path visibly removed the favourite mark before removing the game.
 * Hiding a favourite ran `setFavorite(false)` and then `setHidden(true)`, two Room transactions, so a list observing the
 * library painted the star leaving a row that was still on screen and only then dropped it. Putting the pair in one
 * transaction fixed the jitter and still lost the star for good: a game restored from Hidden games came back without it.
 *
 * Now a hide never touches the favourite. Every list and count reads favourites through a hidden filter (pinned against
 * the real SQL by core-storage's `HiddenFavoriteReadModelTest`), so a hidden favourite shows nowhere, the one write
 * re-emits a list once, and a restore brings the game back starred. These tests record the transaction boundaries and
 * every DAO write inside them; the favourite DAO answers nothing, so any call to it fails the test.
 */
class LibraryRepositoryHideTransactionTest {

    @Test
    fun `hiding a favourite is one transaction with one write, and the star is not touched`() = runTest {
        val log = mutableListOf<String>()
        val repository = repository(log)

        repository.setHidden(GameId("snes:mana"), hidden = true)

        assertEquals(listOf("tx:begin", "hidden.upsert:snes:mana", "tx:end"), log)
    }

    @Test
    fun `the hidden row is the one the Hidden games list restores from`() = runTest {
        val log = mutableListOf<String>()
        val written = mutableListOf<HiddenItemEntity>()
        val repository = repository(log, written = written, clock = { 42L })

        repository.setHidden(GameId("snes:mana"), hidden = true)

        val row = written.single()
        assertEquals("snes:mana", row.gameId)
        assertTrue(row.hidden)
        assertEquals("USER_DISMISSED", row.reason)
        assertTrue(row.isPrimaryMember)
        assertEquals(42L, row.hiddenAtMillis)
    }

    @Test
    fun `restoring clears only the hidden row, so the game comes back with its star`() = runTest {
        val log = mutableListOf<String>()
        val repository = repository(log)

        repository.setHidden(GameId("snes:mana"), hidden = false)

        assertEquals(listOf("tx:begin", "hidden.clear:snes:mana", "tx:end"), log)
    }

    private fun TestScope.repository(
        log: MutableList<String>,
        written: MutableList<HiddenItemEntity> = mutableListOf(),
        clock: () -> Long = { 0L },
    ): LibraryRepositoryImpl {
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
        val gameDao = proxy<GameDao> { method, _ ->
            when (method) {
                "membersOfGame" -> emptyList<Any>()
                else -> error("Unexpected GameDao call: $method")
            }
        }
        val hiddenItemDao = proxy<HiddenItemDao> { method, args ->
            when (method) {
                "upsert" -> {
                    val item = args[0] as HiddenItemEntity
                    written += item
                    log += "hidden.upsert:${item.gameId}"
                }
                "clear" -> log += "hidden.clear:${args[0]}"
                else -> error("Unexpected HiddenItemDao call: $method")
            }
            null
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = unusedProxy(),
            hiddenItemDao = hiddenItemDao,
            // A hide or a restore that reaches for the star fails here, loudly, inside its transaction.
            favoriteDao = unusedProxy(),
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult =
                    SourceWalkResult.Complete(emptyList())
            },
            // A transaction that says where it begins and how it ends, like Room's withTransaction does for a rollback.
            runScanTransaction = { block ->
                log += "tx:begin"
                try {
                    block()
                } catch (t: Throwable) {
                    log += "tx:abort"
                    throw t
                }
                log += "tx:end"
            },
            ioDispatcher = dispatcher,
            clock = clock,
        )
    }

    private inline fun <reified T> unusedProxy(): T = proxy { method, _ ->
        error("Unexpected ${T::class.java.simpleName} call: $method")
    }

    /**
     * Suspend DAO methods arrive here with their Continuation as the last argument; [answer] gets the leading ones and
     * whatever it returns is the (synchronously completed) suspend result.
     */
    private inline fun <reified T> proxy(crossinline answer: (String, List<Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name, args.orEmpty().toList().dropLast(1))
        } as T
}
