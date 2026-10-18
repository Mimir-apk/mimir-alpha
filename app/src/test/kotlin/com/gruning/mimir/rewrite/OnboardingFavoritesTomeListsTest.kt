package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A 5,529-game measurement on RG Rotate: the favourites Tome rebuilt its lists and every
 * focus key on each recomposition and each press, and a star re-registered every focus slot.
 */
class OnboardingFavoritesTomeListsTest {

    private fun game(id: String, platform: String = "snes", title: String = id, favorite: Boolean = false) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId(platform),
        memberCount = 1,
        duplicateKind = null,
        isFavorite = favorite,
    )

    private val games = listOf(
        game("mana", title = "Secret of Mana"),
        game("earth", title = "EarthBound"),
        game("zelda", platform = "nes", title = "The Legend of Zelda"),
    )

    @Test
    fun `the same games and filters give back the same lists`() {
        val first = onboardingFavoritesTomeLists(null, games, null, "")
        assertSame(first, onboardingFavoritesTomeLists(first, games, null, ""))
    }

    @Test
    fun `a star makes a new list but keeps the keys and their index`() {
        val first = onboardingFavoritesTomeLists(null, games, null, "")
        val starred = games.map { if (it.gameId.value == "earth") it.copy(isFavorite = true) else it }
        val second = onboardingFavoritesTomeLists(first, starred, null, "")
        assertNotSame(first, second)
        assertSame(first.keys, second.keys)
        assertEquals(true, second.visibleGame(onboardingFavoriteGameKey("earth"))?.isFavorite)
    }

    @Test
    fun `a filter or a new game builds new keys`() {
        val first = onboardingFavoritesTomeLists(null, games, null, "")
        val snes = onboardingFavoritesTomeLists(first, games, PlatformId("snes"), "")
        assertNotSame(first.keys, snes.keys)
        assertEquals(listOf("mana", "earth"), snes.visible.map { it.gameId.value })
        val grown = onboardingFavoritesTomeLists(snes, games + game("chrono"), PlatformId("snes"), "")
        assertNotSame(snes.keys, grown.keys)
        assertEquals(3, grown.visible.size)
    }

    @Test
    fun `keys match the flat key list and every game is found by its key`() {
        val lists = onboardingFavoritesTomeLists(null, games, null, "")
        assertEquals(onboardingFavoritesFocusKeys(lists.visible, lists.platforms), lists.keys)
        lists.visible.forEachIndexed { i, game ->
            assertEquals(i, lists.visibleIndexOf(onboardingFavoriteGameKey(game.gameId.value)))
        }
        assertEquals(-1, lists.visibleIndexOf(OnboardingFavoritesSearchKey))
        assertNull(lists.visibleGame(null))
    }

    @Test
    fun `a search narrows the index with the list`() {
        val lists = onboardingFavoritesTomeLists(null, games, null, "zel")
        assertEquals(listOf("zelda"), lists.visible.map { it.gameId.value })
        assertEquals(0, lists.visibleIndexOf(onboardingFavoriteGameKey("zelda")))
        assertEquals(-1, lists.visibleIndexOf(onboardingFavoriteGameKey("mana")))
    }
}
