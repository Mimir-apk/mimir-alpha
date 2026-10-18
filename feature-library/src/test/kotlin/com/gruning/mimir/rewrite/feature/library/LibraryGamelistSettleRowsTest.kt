package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review finding 11 (2026-09-29): the settle decoded the carousel's five-cover window whatever the page drew, and
 * (after w_rev_6) paired the loaded flag with the previous `games` value, so the shelf's stale empty list could pass
 * for the opening platform's. It now waits on [LibraryViewModel.visibleGamesFor] and decodes only what the page paints.
 *
 * Plain JUnit: [LibraryViewModel.games], and so visibleGamesFor, run on Dispatchers.Default (see
 * LibraryLoadedGamelistReopenTest's harness note). Every wait is bounded; a settle that hangs fails on the bound.
 */
class LibraryGamelistSettleRowsTest {

    @Test
    fun `a platform with no games ends the settle instead of waiting out the cap`() = runBlocking {
        val vm = LibraryViewModel(FakeLibraryRepository())
        vm.openPlatform(PlatformId.GAMECUBE) // the fake repository seeds no GameCube games
        assertEquals(emptyList<GameRow>(), settle(vm, compact = false))
    }

    @Test
    fun `favorites with no ROM favorites ends the settle too`() = runBlocking {
        val vm = LibraryViewModel(FakeLibraryRepository())
        vm.openFavorites()
        assertEquals(emptyList<GameRow>(), settle(vm, compact = false))
    }

    @Test
    fun `favorites on an Android segment settles on nothing`() = runBlocking {
        val vm = LibraryViewModel(FakeLibraryRepository())
        vm.openFavorites(FavoriteSegment.ANDROID_GAMES)
        assertEquals(emptyList<GameRow>(), settle(vm, compact = false))
    }

    @Test
    fun `the carousel settles on its centre window of the open platform, never the previous one`() = runBlocking {
        val vm = LibraryViewModel(FakeLibraryRepository())
        vm.openPlatform(PlatformId.PS1)
        val ps1 = settle(vm, compact = false)
        assertTrue("fixture: PS1 has rows", ps1.isNotEmpty())
        assertTrue(ps1.all { it.platformId == PlatformId.PS1 })
        vm.openPlatform(PlatformId.GAMECUBE)
        assertEquals("the stale PS1 list is not GameCube's", emptyList<GameRow>(), settle(vm, compact = false))
        vm.openPlatform(PlatformId.SNES)
        val snes = settle(vm, compact = false)
        assertTrue("SNES rows, not the list on hand before the switch", snes.isNotEmpty() && snes.all { it.platformId == PlatformId.SNES })
    }

    @Test
    fun `the list settles on its panel cover, and on nothing where compact drops the panel`() = runBlocking {
        val vm = LibraryViewModel(FakeLibraryRepository())
        vm.openPlatform(PlatformId.PS1)
        val rows = withTimeout(BOUND_MS) {
            vm.visibleGamesFor.first { it.loadedFor == PlatformId.PS1 && it.rows.isNotEmpty() }
        }.rows
        vm.rememberGamelistFocus(rows.last())
        vm.toggleGamelistViewMode()
        assertEquals(GamelistViewMode.LIST, vm.gamelistViewMode.value)
        assertEquals("the panel paints the landing game only", listOf(rows.last().gameId), settle(vm, compact = false).map { it.gameId })
        assertEquals("compact has no panel: nothing to decode", emptyList<GameRow>(), settle(vm, compact = true))
    }

    @Test
    fun `settle covers per view`() {
        val rows = (0 until 10).map { GameRow(GameId("g$it"), "Game $it", PlatformId.PS1, memberCount = 1, null) }
        // the carousel's centre window is every card it paints, three either side.
        assertEquals(7, libraryGamelistSettleCovers(rows, GameId("g5"), listView = false, previewPanel = false).size)
        assertEquals(listOf(GameId("g5")), libraryGamelistSettleCovers(rows, GameId("g5"), listView = true, previewPanel = true).map { it.gameId })
        assertEquals("no landing: the first row", listOf(GameId("g0")), libraryGamelistSettleCovers(rows, null, listView = true, previewPanel = true).map { it.gameId })
        assertEquals(emptyList<GameRow>(), libraryGamelistSettleCovers(rows, GameId("g5"), listView = true, previewPanel = false))
        assertEquals(emptyList<GameRow>(), libraryGamelistSettleCovers(emptyList(), null, listView = true, previewPanel = true))
    }

    private suspend fun settle(vm: LibraryViewModel, compact: Boolean): List<GameRow> =
        withTimeout(BOUND_MS) { libraryGamelistSettleRows(vm, compact) }

    private companion object {
        const val BOUND_MS = 5_000L
    }
}
