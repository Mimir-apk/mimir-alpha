package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RC-48 (audit 2026-10-14): the loose Home sort compiled a Regex twice per comparison (about 1,300 compiles for 100 favourites,
 * on the UI thread, at every Home open and favourites change), and the alphabet jump compiled one per game per press. The Regex is
 * compiled once and each game's keys are worked out once; the ORDER must not move.
 */
class HomeFavoriteSortKeyTest {

    private val snes = PlatformId("snes")

    private fun game(id: String, title: String = id) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = snes,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = true,
        lastPlayedMillis = null,
    )

    /** The comparator this change replaced, verbatim: fold, then lowercase title, then the item id. */
    private val reference = compareBy<FavoriteHomeGame>(
        { Normalizer.normalize(it.row.title, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT) },
        { it.row.title.lowercase(Locale.ROOT) },
        { it.row.itemId.toString() },
    )

    @Test
    fun `the precomputed keys sort exactly as the comparator they replace, ties included`() {
        // Titles that fold together (accents, case), equal titles with different ids, digits, symbols and a non-Latin start.
        val titles = listOf(
            "Éclair", "eclair", "Eclair", "ECLAIR", "éclair", "Ωmega", "9 lives", "Zelda", "zelda", "Żelda", "Ångström", "angstrom",
            "Mario", "mario", "Mário", "MARIO", "!Bang", "Über", "uber", "Uber", "Cafe", "Café", "cafe",
        )
        val games = titles.flatMapIndexed { index, title ->
            // Two games per title, ids out of order, so the third key (the item id) is what separates them.
            listOf(game("id-${99 - index}-a", title), game("id-${index}-b", title))
        }
        val projection = projectFavoriteHome(favoriteHomeGames(games, emptyList()), HomeTomeDocument())

        val expected = projection.games.sortedWith(reference).map { it.row.itemId }
        val actual = orderedLooseHomeGames(projection).map { it.row.itemId }

        assertEquals(expected, actual)
    }

    @Test
    fun `the diacritic Regex is compiled once, not per comparison or per game`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeFavoriteProjection.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeFavoriteProjection.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("HomeFavoriteProjection.kt not found from ${File(".").absolutePath}")
        val code = source.lines().filterNot { it.trimStart().let { line -> line.startsWith("*") || line.startsWith("//") || line.startsWith("/*") } }
        assertEquals("one Regex in the file, hoisted to a val", 1, code.count { it.contains("Regex(") })
        assertTrue("and it is the top-level val", code.any { it.startsWith("private val CombiningMarks = Regex(") })
        assertEquals("the alphabet bucket and the fold both use it", 2, code.count { it.contains("replace(CombiningMarks,") })
    }
}
