package com.gruning.mimir.rewrite.core.resolver.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.ArtSource
import com.gruning.mimir.rewrite.core.model.library.CoverOverrideSource
import com.gruning.mimir.rewrite.core.model.library.ShelfFaceShape
import com.gruning.mimir.rewrite.core.model.library.WiredFace
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
 * one shelf face for EVERY platform. The seam behind `GameRow.wiredFace` used to be asked
 * once per row and answered only the explicit pick; it is now told whether the resolver already holds a cover, so
 * the app can add the one tier that depends on it (the automatic SteamGridDB square, only without a cover) -- and it
 * is asked for a GBA game exactly as for a PC game. The resolver itself reads no store and fetches nothing.
 */
class LibraryRepositoryShelfFaceTest {
    private val gameId = GameId("gba/metroid-fusion")
    private val projection = GameRowProjection(
        gameId = gameId.value,
        title = "Metroid Fusion",
        platformId = PlatformId.GBA.value,
        duplicateGroupKind = null,
        memberCount = 1,
        managedPath = "/games/Metroid Fusion.gba",
    )

    private val tile = WiredFace("file:///files/sgdb2/tile.png#v=1", ShelfFaceShape.SQUARE)

    @Test
    fun `a non-pc platform row carries the face the seam answers`() = runBlocking {
        val asked = mutableListOf<Pair<String, Boolean>>()
        val row = repository(
            artSource = ArtSource { _, _ -> null },
            overrides = CoverOverrideSource { id, hasCover ->
                asked += id to hasCover
                tile
            },
        ).observeGames(PlatformId.GBA).first().single()

        assertEquals(tile, row.wiredFace)
        assertEquals(listOf(gameId.value to false), asked)
    }

    @Test
    fun `the seam is told a cover exists when the automatic cover resolves, and the cover stays in artUri`() = runBlocking {
        var told: Boolean? = null
        val row = repository(
            artSource = ArtSource { _, _ -> "file:///games/Metroid%20Fusion.png" },
            overrides = CoverOverrideSource { _, hasCover ->
                told = hasCover
                null
            },
        ).observeGames(PlatformId.GBA).first().single()

        assertEquals(true, told)
        assertEquals("file:///games/Metroid%20Fusion.png", row.artUri)
        assertNull(row.wiredFace)
    }

    /** A provider URL is dropped from the row, so it cannot count as a cover either. */
    @Test
    fun `a non-local cover is not a cover as far as the seam is concerned`() = runBlocking {
        var told: Boolean? = null
        val row = repository(
            artSource = ArtSource { _, _ -> "https://example.invalid/cover.png" },
            overrides = CoverOverrideSource { _, hasCover ->
                told = hasCover
                null
            },
        ).observeGames(PlatformId.GBA).first().single()

        assertNull(row.artUri)
        assertEquals(false, told)
    }

    @Test
    fun `the default seam answers nothing`() = runBlocking {
        val row = repository(artSource = ArtSource { _, _ -> null }).observeGames(PlatformId.GBA).first().single()

        assertNull(row.wiredFace)
    }

    /**
     * PM gate DEFECT 1: a tile write touches no Room table, so an open flow never re-emits on its own. The writer says so
     * once, through [LibraryRepositoryImpl.refreshShelfFaces], and every projection re-asks the seam.
     */
    @Test
    fun `refreshShelfFaces re-projects the open games flow once, and a refresh that changes nothing emits nothing`() = runBlocking {
        var onDisk: WiredFace? = null
        val repository = repository(artSource = ArtSource { _, _ -> null }, overrides = CoverOverrideSource { _, _ -> onDisk })
        val emissions = mutableListOf<WiredFace?>()
        val job = launch(Dispatchers.Unconfined) {
            repository.observeGames(PlatformId.GBA).collect { emissions += it.single().wiredFace }
        }
        assertEquals("the game starts with no face", listOf<WiredFace?>(null), emissions)

        repository.refreshShelfFaces()
        assertEquals("a refresh with nothing new on disk is not a new row", listOf<WiredFace?>(null), emissions)

        onDisk = tile
        repository.refreshShelfFaces()
        assertEquals("the write was published once, the row is re-projected once", listOf<WiredFace?>(null, tile), emissions)

        repository.refreshShelfFaces()
        assertEquals(listOf<WiredFace?>(null, tile), emissions)
        job.cancel()
    }

    /**
     * The merged Icon art picker's apply funnels through the coordinator's `refreshCustomEnrichOwner`, which asks the repository for
     * [LibraryRepositoryImpl.refreshArt] of the game (and never `refreshShelfFaces`: the two would be a second library-wide bump). That
     * one call bumps the shared art version every row flow combines with, so every open flow re-projects its rows and re-asks the seam
     * for the shelf face: the Library and the Bottom repaint a picked face with no other signal. Pinned here, where the flow is.
     */
    @Test
    fun `refreshArt re-projects the open games flow and re-asks the seam, so a media apply repaints the Library`() = runBlocking {
        var onDisk: WiredFace? = null
        val asked = mutableListOf<String>()
        val repository = repository(
            artSource = ArtSource { _, _ -> null },
            overrides = CoverOverrideSource { id, _ ->
                asked += id
                onDisk
            },
        )
        val emissions = mutableListOf<WiredFace?>()
        val job = launch(Dispatchers.Unconfined) {
            repository.observeGames(PlatformId.GBA).collect { emissions += it.single().wiredFace }
        }
        assertEquals(listOf<WiredFace?>(null), emissions)
        val askedBefore = asked.size

        onDisk = tile
        repository.refreshArt(gameId)

        assertEquals("the apply was published once, the row is re-projected once", listOf<WiredFace?>(null, tile), emissions)
        assertEquals("and the seam was asked for the game again", askedBefore + 1, asked.size)
        job.cancel()
    }

    @Test
    fun `refreshShelfFaces re-projects the detail read and the favourites flow too`() = runBlocking {
        var onDisk: WiredFace? = null
        val repository = repository(artSource = ArtSource { _, _ -> null }, overrides = CoverOverrideSource { _, _ -> onDisk })
        val detail = mutableListOf<WiredFace?>()
        val favourites = mutableListOf<WiredFace?>()
        val a = launch(Dispatchers.Unconfined) { repository.observeGame(gameId).collect { detail += it?.wiredFace } }
        val b = launch(Dispatchers.Unconfined) { repository.observeFavorites().collect { favourites += it.single().wiredFace } }
        assertEquals(listOf<WiredFace?>(null), detail)
        assertEquals(listOf<WiredFace?>(null), favourites)

        onDisk = tile
        repository.refreshShelfFaces()

        assertEquals(listOf<WiredFace?>(null, tile), detail)
        assertEquals(listOf<WiredFace?>(null, tile), favourites)
        a.cancel()
        b.cancel()
    }

    private fun repository(
        artSource: ArtSource,
        overrides: CoverOverrideSource = CoverOverrideSource { _, _ -> null },
    ): LibraryRepositoryImpl {
        val gameDao = proxy<GameDao> { methodName ->
            when (methodName) {
                "visibleGameRowsForPlatform" -> flowOf(listOf(projection))
                "observeGameRow" -> flowOf(projection)
                "favoriteGameRows" -> flowOf(listOf(projection))
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
            artSource = artSource,
            coverOverrideSource = overrides,
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
