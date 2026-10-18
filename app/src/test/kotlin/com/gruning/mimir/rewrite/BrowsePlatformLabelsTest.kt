package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Update's sweep (10-09): the hidden Tetris row said "Unknown platform" for a Game Boy game. The shelf's platform list holds only platforms that still have visible games, so a platform whose
 * every game is hidden has no row there; Browse now names it the way the shelf does ([platformLabelForCompanion]): the library's label when it has one, the registry's name when it has not.
 */
class BrowsePlatformLabelsTest {

    private fun game(id: String, platform: String) = GameRow(
        gameId = GameId(id),
        title = "Game $id",
        platformId = PlatformId(platform),
        memberCount = 1,
        duplicateKind = null,
    )

    @Test
    fun `a hidden game whose platform has no visible games is named by the registry, not Unknown platform`() {
        val labels = browsePlatformLabels(hidden = listOf(game("g1", "gb")), platforms = emptyList())
        assertEquals("Game Boy", labels[PlatformId("gb")])
    }

    @Test
    fun `a platform the shelf still lists keeps the shelf's own label`() {
        val shelf = listOf(PlatformRow(PlatformId("snes"), "Super Nintendo", gameCount = 3))
        val labels = browsePlatformLabels(hidden = listOf(game("g1", "snes"), game("g2", "gb")), platforms = shelf)
        assertEquals("Super Nintendo", labels[PlatformId("snes")])
        assertEquals("Game Boy", labels[PlatformId("gb")])
    }

    @Test
    fun `Settings' hidden rows carry the same names, so they do not say Unknown platform either`() {
        val shelf = listOf(PlatformRow(PlatformId("snes"), "Super Nintendo", gameCount = 3))
        val rows = hiddenGamePlatformRows(hidden = listOf(game("g1", "snes"), game("g2", "gb")), platforms = shelf)
        assertEquals("Super Nintendo", rows.first { it.platformId == PlatformId("snes") }.label)
        assertEquals("Game Boy", rows.first { it.platformId == PlatformId("gb") }.label)
        assertEquals("the shelf's count is kept", 3, rows.first { it.platformId == PlatformId("snes") }.gameCount)
        assertEquals("a platform with no visible games has none", 0, rows.first { it.platformId == PlatformId("gb") }.gameCount)
    }

    @Test
    fun `the library coordinator hands the Settings read model those rows`() {
        val source = java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/MimirLibraryCoordinator.kt").takeIf { it.isFile }
            ?: java.io.File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirLibraryCoordinator.kt")
        assertTrue(source.readText().contains("platforms = hiddenGamePlatformRows(hiddenGames, platforms)"))
    }

    @Test
    fun `every hidden game's platform resolves to a real name`() {
        val hidden = listOf("gb", "gba", "nes", "megadrive", "ps1").mapIndexed { i, p -> game("g$i", p) }
        val labels = browsePlatformLabels(hidden, emptyList())
        hidden.forEach { assertFalse("${it.platformId.value} has a name", labels[it.platformId].isNullOrBlank()) }
    }
}
