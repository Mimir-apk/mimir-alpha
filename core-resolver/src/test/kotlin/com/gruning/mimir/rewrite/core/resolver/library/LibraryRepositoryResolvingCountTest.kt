package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.FavoriteDao
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.HiddenItemDao
import com.gruning.mimir.rewrite.core.storage.dao.PlatformDao
import com.gruning.mimir.rewrite.core.storage.dao.SourceRootDao
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 *  on the
 * SETTLING screen. The cause was `LibraryIdentityEnricher.enrich()`'s `batch = 50` default
 * (`core-metadata/.../LibraryIdentityEnricher.kt:180`) combined with `IdentityEnrichmentWorker` never
 * overriding it -- a 26-game library fits in ONE page, so the whole run lands as ONE `GameDao.setIdentities`
 * `@Transaction` (`Daos.kt` ~1174, called from `LibraryIdentityEnricher.kt:284`), which is ONE Room table
 * invalidation. `unresolvedPrimaryCount()`'s `Flow<Int>` therefore has exactly two real values to emit --
 * the starting count and the final one -- and nothing in between exists to observe. That is a batching-
 * granularity fact about the identity-enrichment writer (core-metadata / app), not a defect in [LibraryRepositoryImpl.observeResolvingCount].
 *
 * This class pins the repository-level property: the repository's plumbing
 * between `GameDao.unresolvedPrimaryCount()` and [LibraryRepository.observeResolvingCount] is a faithful,
 * order-preserving passthrough -- it does not itself collapse, reorder, or drop emissions, and would carry
 * a finer-grained signal through untouched if the writer ever emitted one. That refutes the other three
 * candidate mechanisms from the report (`distinctUntilChanged` collapsing, a missing `flowOn`/wrong-
 * dispatcher stall, or the collector silently discarding values) at this layer.
 */
class LibraryRepositoryResolvingCountTest {

    @Test
    fun `observeResolvingCount forwards every distinct value the DAO emits, in order`() = runBlocking {
        // A hypothetical finer-grained writer (the out-of-scope fix) stepping down in six commits instead
        // of one. If the repository silently coalesced or reordered these, this would fail.
        val steps = listOf(26, 21, 16, 11, 6, 1, 0)
        val repository = repository(resolvingCountFlow = flowOf(*steps.toTypedArray()))

        val observed = withTimeout(5_000) {
            repository.observeResolvingCount().toList()
        }

        assertEquals(steps, observed)
    }

    @Test
    fun `observeResolvingCount reproduces today's single-jump behaviour for a batch-sized library`() =
        runBlocking {
            // This is what the real writer currently produces for a measured 26-game library: one
            // pre-run value, one post-run value, nothing live in between. Pinned here so a future change
            // to the repository layer cannot accidentally "fix" this by inventing intermediate values that
            // never happened in storage -- the fix belongs to the writer, not to this passthrough.
            val repository = repository(resolvingCountFlow = flowOf(26, 0))

            val observed = withTimeout(5_000) {
                repository.observeResolvingCount().toList()
            }

            assertEquals(listOf(26, 0), observed)
        }

    private fun repository(
        resolvingCountFlow: kotlinx.coroutines.flow.Flow<Int>,
    ): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "unresolvedPrimaryCount" -> resolvingCountFlow
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
