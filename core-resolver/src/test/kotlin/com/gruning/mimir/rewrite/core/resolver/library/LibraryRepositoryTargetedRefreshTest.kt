package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.ArtSource
import com.gruning.mimir.rewrite.core.model.library.ChangedMedia
import com.gruning.mimir.rewrite.core.resolver.scan.SourceLister
import com.gruning.mimir.rewrite.core.resolver.scan.SourceWalkResult
import com.gruning.mimir.rewrite.core.storage.dao.GameDao
import com.gruning.mimir.rewrite.core.storage.dao.GameRowProjection
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the targeted batch-completion refresh, pinned:
 *  - [LibraryRepositoryImpl.refreshMediaFor] evicts ONLY the changed locators and version-busts ONLY the
 *    changed rows (unchanged games keep their memoized probes AND their Coil keys),
 *  - one call covers many games in ONE version update,
 *  - a same-path REPLACED cover gets a fresh `#mv=` render URI (the stale-replaced-cover regression: the
 *    old library-wide batch epoch re-projected rows but never re-keyed Coil, so replaced art stayed stale),
 *  - favorites + hidden surfaces consume the version (they previously took neither the combine nor the
 *    versions, so a fetch never repainted them until relaunch),
 *  - an empty changed set is a strict no-op.
 */
class LibraryRepositoryTargetedRefreshTest {

    private val zelda = projection("gbc/zelda-ages", "/games/Zelda Ages.gbc", "Zelda Ages")
    private val metroid = projection("gba/metroid-fusion", "/games/Metroid Fusion.gba", "Metroid Fusion")

    @Test
    fun `targeted refresh busts only the changed game and evicts only its locator`() = runBlocking {
        val artSource = RecordingArtSource()
        val repository = repository(artSource, zelda, metroid)

        repository.refreshMediaFor(
            listOf(ChangedMedia(GameId(zelda.gameId), sourceDocumentUri = null, managedPath = zelda.managedPath)),
        )

        val rows = repository.observeGames(PlatformId.GBA).first().associateBy { it.gameId.value }
        assertTrue("changed game re-keys Coil", rows.getValue(zelda.gameId).artUri!!.endsWith("#mv=1"))
        assertTrue(
            "changed physical media re-keys Coil",
            rows.getValue(zelda.gameId).physicalMediaUri!!.endsWith("#mv=1"),
        )
        assertTrue("unchanged game keeps its key", !rows.getValue(metroid.gameId).artUri!!.contains("#mv="))
        assertEquals(listOf(null to zelda.managedPath), artSource.invalidations)
    }

    @Test
    fun `one call batches many games into one version update`() = runBlocking {
        val artSource = RecordingArtSource()
        val repository = repository(artSource, zelda, metroid)

        repository.refreshMediaFor(
            listOf(
                ChangedMedia(GameId(zelda.gameId), null, zelda.managedPath),
                ChangedMedia(GameId(metroid.gameId), null, metroid.managedPath),
            ),
        )

        val rows = repository.observeGames(PlatformId.GBA).first()
        assertTrue(rows.all { it.artUri!!.endsWith("#mv=1") })
        assertEquals(2, artSource.invalidations.size)
    }

    @Test
    fun `same-path replaced art regression - the render URI changes even when the file URI does not`() =
        runBlocking {
            val artSource = RecordingArtSource()
            val repository = repository(artSource, zelda)

            val before = repository.observeGames(PlatformId.GBA).first().single().artUri
            repository.refreshMediaFor(listOf(ChangedMedia(GameId(zelda.gameId), null, zelda.managedPath)))
            val after = repository.observeGames(PlatformId.GBA).first().single().artUri

            assertEquals(before + "#mv=1", after)
        }

    @Test
    fun `favorites and hidden surfaces repaint after a targeted refresh`() = runBlocking {
        val artSource = RecordingArtSource()
        val repository = repository(artSource, zelda)

        repository.refreshMediaFor(listOf(ChangedMedia(GameId(zelda.gameId), null, zelda.managedPath)))

        val favorite = repository.observeFavorites().first().single()
        val hidden = repository.observeHiddenGames().first().single()
        assertTrue("favorites consume the version", favorite.artUri!!.endsWith("#mv=1"))
        assertTrue("hidden consumes the version", hidden.artUri!!.endsWith("#mv=1"))
    }

    @Test
    fun `empty changed set is a strict no-op`() = runBlocking {
        val artSource = RecordingArtSource()
        val repository = repository(artSource, zelda)

        repository.refreshMediaFor(emptyList())

        val row = repository.observeGames(PlatformId.GBA).first().single()
        assertTrue(!row.artUri!!.contains("#mv="))
        assertTrue(artSource.invalidations.isEmpty())
    }

    @Test
    fun `library wide refresh gives every local media URI a new render key`() = runBlocking {
        val repository = repository(RecordingArtSource(), zelda, metroid)

        repository.refreshAllMedia()

        val rows = repository.observeGames(PlatformId.GBA).first()
        assertTrue(rows.all { it.artUri!!.endsWith("#mv=b1") })
        assertTrue(rows.all { it.physicalMediaUri!!.endsWith("#mv=b1") })
    }

    // ── harness (mirrors LibraryRepositoryClearLogoTest) ──────────────────────

    private fun projection(gameId: String, managedPath: String, title: String) = GameRowProjection(
        gameId = gameId,
        title = title,
        platformId = PlatformId.GBA.value,
        duplicateGroupKind = null,
        memberCount = 1,
        managedPath = managedPath,
    )

    private fun repository(artSource: ArtSource, vararg projections: GameRowProjection): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "visibleGameRowsForPlatform" -> flowOf(projections.toList())
                "favoriteGameRows" -> flowOf(projections.toList())
                "hiddenGameRows" -> flowOf(projections.toList())
                else -> error("Unexpected GameDao call: $methodName")
            }
        }
        return LibraryRepositoryImpl(
            gameDao = gameDao,
            platformDao = unusedProxy(),
            sourceRootDao = unusedProxy(),
            hiddenItemDao = unusedProxy(),
            favoriteDao = unusedProxy(),
            artSource = artSource,
            walker = object : SourceLister {
                override suspend fun walk(rootUri: String): SourceWalkResult =
                    SourceWalkResult.Complete(emptyList())
            },
            runScanTransaction = { block -> block() },
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private class RecordingArtSource : ArtSource {
        val invalidations = mutableListOf<Pair<String?, String?>>()

        override fun artUriFor(sourceDocumentUri: String?, managedPath: String?) =
            "file://" + (managedPath ?: "unknown").replace(" ", "%20").removeSuffix(".gbc").removeSuffix(".gba") + ".png"

        override fun physicalMediaUriFor(sourceDocumentUri: String?, managedPath: String?) =
            artUriFor(sourceDocumentUri, managedPath)?.removeSuffix(".png") + "-support.png"

        override fun invalidate(sourceDocumentUri: String?, managedPath: String?) {
            invalidations += sourceDocumentUri to managedPath
        }
    }

    private inline fun <reified T> unusedProxy(): T = proxy { methodName ->
        error("Unexpected ${T::class.java.simpleName} call: $methodName")
    }

    private inline fun <reified T> proxy(crossinline answer: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method.name)
        } as T
}
