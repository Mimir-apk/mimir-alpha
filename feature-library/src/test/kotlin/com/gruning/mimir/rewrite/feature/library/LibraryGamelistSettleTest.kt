package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tome settle (w_rev_6): a loaded, empty list and Favorites' Android segments must settle at once, not sit out
 * MimirPageMotion.SettleCapMs with the input gate held. A hang fails the 1 s timeouts below.
 */
class LibraryGamelistSettleTest {
    private val snes = PlatformId("snes")

    @Test
    fun `a list that has landed empty settles at once`() = runBlocking {
        val landed = withTimeout(1_000) {
            awaitLibraryGamelistRows(
                MutableStateFlow<PlatformId?>(snes),
                MutableStateFlow(LibraryVisibleGames(snes, emptyList())),
            )
        }
        assertEquals(snes to emptyList<GameRow>(), landed)
    }

    @Test
    fun `rows wait for their platform to land and then arrive`() = runBlocking {
        val visible = MutableStateFlow(LibraryVisibleGames(null, emptyList()))
        val pending = async { awaitLibraryGamelistRows(MutableStateFlow<PlatformId?>(snes), visible) }
        yield()
        assertFalse("the previous page's empty list is not this one's", pending.isCompleted)
        visible.value = LibraryVisibleGames(PlatformId("nes"), listOf(row(0)))
        yield()
        assertFalse("another platform landing is not this one", pending.isCompleted)
        val landed = listOf(row(0), row(1))
        visible.value = LibraryVisibleGames(snes, landed)
        assertEquals(snes to landed, withTimeout(1_000) { pending.await() })
    }

    @Test
    fun `a platform switch inside the hold re-targets the wait instead of running it to the cap`() = runBlocking {
        val gba = PlatformId("gba")
        val open = MutableStateFlow<PlatformId?>(snes)
        val visible = MutableStateFlow(LibraryVisibleGames(null, emptyList()))
        val pending = async { awaitLibraryGamelistRows(open, visible) }
        yield()
        assertFalse("nothing loaded yet", pending.isCompleted)
        // L2/R2 inside the hold: the open platform moves to gba before snes's rows ever land.
        open.value = gba
        yield()
        assertFalse(pending.isCompleted)
        val gbaRows = listOf(row(0).copy(platformId = gba))
        visible.value = LibraryVisibleGames(gba, gbaRows)
        assertEquals("the wait lands on the platform that shows", gba to gbaRows, withTimeout(1_000) { pending.await() })
    }

    @Test
    fun `rows loaded for a platform no longer open do not settle the wait, and a close ends it`() = runBlocking {
        val open = MutableStateFlow<PlatformId?>(PlatformId("gba"))
        val visible = MutableStateFlow(LibraryVisibleGames(snes, listOf(row(0))))
        val pending = async { awaitLibraryGamelistRows(open, visible) }
        yield()
        assertFalse("stale snes rows must not settle a gba wait", pending.isCompleted)
        open.value = null
        assertEquals(null, withTimeout(1_000) { pending.await() })
    }

    @Test
    fun `only ROM gamelists have covers to warm`() {
        val favorites = LibraryViewModel.FAVORITES_PLATFORM_ID
        assertTrue(libraryGamelistSettleWarmsCovers(favorites, FavoriteSegment.GAMES))
        assertFalse(libraryGamelistSettleWarmsCovers(favorites, FavoriteSegment.ANDROID_GAMES))
        assertFalse(libraryGamelistSettleWarmsCovers(favorites, FavoriteSegment.ANDROID_APPS))
        FavoriteSegment.values().forEach { assertTrue(libraryGamelistSettleWarmsCovers(snes, it)) }
    }

    @Test
    fun `the settle follows the open platform on its truly loaded rows and never waits on a non-empty list`() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt").readText()
        val body = source.substringAfter("internal suspend fun libraryGamelistSettleRows(")
            .substringBefore("\n}\n")
        assertTrue(body.contains("libraryGamelistSettleWarmsCovers(opening, viewModel.favoriteSegment.value)"))
        assertTrue(body.contains("awaitLibraryGamelistRows(viewModel.openPlatform, viewModel.visibleGamesFor)"))
        assertFalse(body.contains("isNullOrEmpty"))
        assertFalse("the loaded flag flips before its rows land", body.contains("gamesLoadedForPlatform"))
    }

    private fun row(index: Int) = GameRow(
        gameId = GameId("g$index"),
        title = "Game $index",
        platformId = snes,
        memberCount = 1,
        duplicateKind = null,
    )
}
