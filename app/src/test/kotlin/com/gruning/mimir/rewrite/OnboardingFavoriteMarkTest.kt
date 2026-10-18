package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The favourite
 * write waits in Room's serial transaction queue behind identification, so the picker shows the mark at once and
 * the database catches up.
 */
class OnboardingFavoriteMarkTest {

    private fun game(id: String, favorite: Boolean = false) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId("snes"),
        memberCount = 1,
        duplicateKind = null,
        isFavorite = favorite,
    )

    private val rows = listOf(game("a"), game("b", favorite = true), game("c"))

    @Test
    fun `a mark shows at once, before the database has it`() {
        val shown = onboardingFavoritesWithPendingMarks(
            rows,
            mapOf(GameId("a") to OnboardingPendingFavorite(marked = true, written = false)),
        )
        assertEquals(listOf(true, true, false), shown.map { it.isFavorite })
    }

    @Test
    fun `an unmark shows at once too`() {
        val shown = onboardingFavoritesWithPendingMarks(
            rows,
            mapOf(GameId("b") to OnboardingPendingFavorite(marked = false, written = false)),
        )
        assertEquals(listOf(false, false, false), shown.map { it.isFavorite })
    }

    @Test
    fun `with nothing pending the list is passed through untouched`() {
        assertSame(rows, onboardingFavoritesWithPendingMarks(rows, emptyMap()))
    }

    @Test
    fun `a mark retires only once written and shown by the database`() {
        val pending = mapOf(
            GameId("a") to OnboardingPendingFavorite(marked = true, written = true),
            GameId("b") to OnboardingPendingFavorite(marked = true, written = false),
            GameId("c") to OnboardingPendingFavorite(marked = true, written = true),
        )
        val database = listOf(game("a", favorite = true), game("b", favorite = true), game("c"))
        // a: written and agreed. b: agrees, but its write is still queued behind an older one. c: not read back yet.
        assertEquals(setOf(GameId("a")), onboardingSettledFavoriteMarks(database, pending))
    }

    @Test
    fun `the writes run one after another, in the order they were made`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirOnboardingCoordinator.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirOnboardingCoordinator.kt"),
        ).first(File::isFile).readText()
        val toggle = source.substringAfter("toggleFavorite = { game ->").substringBefore("requestOverlayAccess")
        assertTrue(toggle.contains("favoriteWrites.withLock { core.repository.setFavorite(game.gameId, marked) }"))
        assertTrue(source.contains("favoriteGames = shownFavoriteGames,"))
    }
}
