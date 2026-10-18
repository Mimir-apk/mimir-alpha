package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Platform switching must restore the prior game focus without first landing on tile one.
 *
 * The memory was never missing. It is keyed per platform and per lane and written on every focus move.
 * The switch path simply opted out — in a comment, with no pin holding it there — and fell back to
 * `authority.rememberedFocusIn(GamelistScope)`, a SINGLE slot shared by every platform. On A→B→A that slot
 * holds B's key, which is not registered in A's ring, so focus falls to tile one and the per-lane memory
 * then moves it again a frame later: two focus events for one press.
 *
 * These pin the STORE's behaviour, which is what the landing now reads. The wiring itself is asserted by
 * the source pin below, matching how this file's siblings pin composable-only paths.
 *
 * **The original store pins asserted nothing.** They read `games.value` straight after
 * `openPlatform` and returned when the PS1 row was missing. It was always missing, because the gamelist loads on
 * Dispatchers.Default and a fresh view model has no rows yet (20 of 20 fresh view models in a probe). They
 * now wait for the rows, bounded, and fail if the rows never come. Each is red-armed against the store it is named
 * for: one slot shared by every platform, one slot shared by both lanes, and a favorites seat written into the
 * all-games lane.
 */
class GamelistSwitchFocusMemoryTest {

    @Test
    fun `each platform remembers its own game, so returning is not a reset`() = runBlocking {
        val repository = FakeLibraryRepository()
        // The repository's own rows: the view model's `platforms` never emits under plain JUnit.
        val platforms = repository.observePlatforms().first()
        val vm = LibraryViewModel(repository)
        vm.openPlatform(PlatformId.PS1)
        val ps1Rows = awaitRows(vm, PlatformId.PS1)
        // Not the first tile: a reset lands on the first tile, so only a later seat tells memory from a reset.
        val ps1Seat = ps1Rows.last()
        assertNotEquals("fixture: PS1 needs a seat that is not its first tile", ps1Rows.first().gameId, ps1Seat.gameId)
        vm.rememberGamelistFocus(ps1Seat)

        assertTrue("fixture: the switch to the next platform is accepted", vm.switchOpenPlatform(1, platforms))
        val other = requireNotNull(vm.openPlatform.value)
        assertNotEquals("fixture: the switch left PS1", PlatformId.PS1, other)
        val otherSeat = awaitRows(vm, other).last()
        vm.rememberGamelistFocus(otherSeat)
        assertEquals(
            "the platform he switched to answers with its own seat",
            otherSeat.gameId,
            vm.rememberedGamelistFocus(favoritesOnly = false),
        )

        assertTrue("fixture: the switch back is accepted", vm.switchOpenPlatform(-1, platforms))
        assertEquals("fixture: the switch back reached PS1", PlatformId.PS1, vm.openPlatform.value)
        assertEquals(
            "the platform he left must still know where he was: back on PS1 the seat is PS1's own, not the " +
                "other platform's and not the first tile",
            ps1Seat.gameId,
            vm.rememberedGamelistFocus(favoritesOnly = false),
        )
    }

    @Test
    fun `the lanes do not bleed into each other`() = runBlocking {
        val repository = FakeLibraryRepository()
        val vm = LibraryViewModel(repository)
        vm.openPlatform(PlatformId.PS1)
        val rows = awaitRows(vm, PlatformId.PS1)
        val allGamesSeat = rows.last()
        vm.rememberGamelistFocus(allGamesSeat)
        assertEquals(
            "fixture: the all-games lane holds the seat just written, so the null below is an answer",
            allGamesSeat.gameId,
            vm.rememberedGamelistFocus(favoritesOnly = false),
        )
        assertEquals(
            "favorites is a different lane of the same platform and must not inherit the all-games seat",
            null,
            vm.rememberedGamelistFocus(favoritesOnly = true),
        )

        // The other direction: a seat taken inside favorites must not move the all-games seat.
        val favorite = rows.first()
        repository.setFavorite(favorite.gameId, true)
        awaitWithin("PS1 reporting its new favorite") { vm.openPlatformHasFavorites.first { it } }
        assertTrue("fixture: the favorites lane opens", vm.toggleFavoritesOnly(focusedGameId = allGamesSeat.gameId))
        val favoriteSeat = awaitWithin("the favorites lane's rows") {
            vm.games.first { lane -> lane.isNotEmpty() && lane.all(GameRow::isFavorite) }
        }.single()
        vm.rememberGamelistFocus(favoriteSeat)
        assertEquals(
            "fixture: the favorites lane holds its own seat",
            favorite.gameId,
            vm.rememberedGamelistFocus(favoritesOnly = true),
        )
        assertEquals(
            "all-games is a different lane of the same platform and must not inherit the favorites seat",
            allGamesSeat.gameId,
            vm.rememberedGamelistFocus(favoritesOnly = false),
        )
    }

    /**
     * The wiring. Without this the store stays correct and the landing keeps ignoring it, which is exactly
     * the state the regression report described: a memory that exists, is written, and is never read where it matters.
     */
    @Test
    fun `the switch landing reads the destination's own memory, not the shared scope slot`() {
        val src = java.io.File(
            "src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt",
        )
        check(src.exists()) { "LibraryScreen.kt not found -- assertion would be vacuous" }
        // A fixed window, not substringBefore(")") -- that cut at the `if (previous == null)` paren and
        // the assertion passed vacuously on four lines of nothing. Caught by running the differential.
        val body = src.readText()
            .substringAfter("pendingGamelistLanding = PendingGamelistLanding(")
            .take(600)
        assertTrue(
            "search window missed the landing construction -- the assertion below would be vacuous",
            body.contains("platformId = current") && body.contains("rememberedFocus ="),
        )
        assertTrue(
            "an in-gamelist switch must seed from the destination's own lane memory, not fall through " +
                "to the single shared GamelistScope slot; found: ${body.take(320)}",
            body.contains("viewModel.rememberedGamelistFocus("),
        )
    }

    /** The open gamelist's rows once they are [platform]'s, bounded: the gamelist loads on Dispatchers.Default. */
    private suspend fun awaitRows(vm: LibraryViewModel, platform: PlatformId): List<GameRow> =
        awaitWithin("the ${platform.value} gamelist loading, without which nothing below is asserted") {
            vm.games.first { rows -> rows.isNotEmpty() && rows.all { it.platformId == platform } }
        }
}
