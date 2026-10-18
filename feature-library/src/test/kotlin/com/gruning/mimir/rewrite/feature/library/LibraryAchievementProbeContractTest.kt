package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the achievement count lives on the LAUNCH CARD ONLY, fetched when the card opens. Moving focus through the Main shelf, the list, the
 * favorites or the carousel asks RetroAchievements for nothing, so none of those screens may carry the probe any more. These pins read the source,
 * because the contract is "this call site does not exist": a Compose test cannot observe a call that is not made.
 */
class LibraryAchievementProbeContractTest {
    @Test
    fun displayedEmulatedDestinationStillResolvesFromFocusAndRetainedIndex() {
        val games = listOf(game("one"), game("two"), game("three"))

        assertEquals(games[1], gamelistDisplayedGame(games, focusedGameIndex = 1, retainedGameIndex = 0))
        assertEquals(games[2], gamelistDisplayedGame(games, focusedGameIndex = -1, retainedGameIndex = 2))
        assertEquals(games[0], gamelistDisplayedGame(games, focusedGameIndex = -1, retainedGameIndex = -1))
        assertNull(gamelistDisplayedGame(emptyList(), focusedGameIndex = -1, retainedGameIndex = -1))

        assertEquals("two", gamelistAchievementProbeKey(games[1]))
        assertEquals(
            "metadata refreshes for one game must not manufacture a new launch-card effect key",
            gamelistAchievementProbeKey(games[1]),
            gamelistAchievementProbeKey(games[1].copy(title = "Updated title")),
        )
        assertFalse(gamelistAchievementProbeKey(games[1]) == gamelistAchievementProbeKey(games[2]))
    }

    @Test
    fun noGamelistScreenProbesOnFocus() {
        val library = source("LibraryScreen.kt")

        // The shelf, the list and the favorites view no longer take or pass a probe at all.
        val body = library.substringBefore("internal fun gamelistAchievementProbeKey")
        val probeUses = Regex("onProbeAchievements").findAll(body).count()
        // Exactly two: the screen's own parameter, and the launch card's effect that reads it.
        assertEquals("only the screen parameter and the launch card may mention the probe", 2, probeUses)

        val regularList = library
            .substringAfter("GamelistViewMode.LIST -> GamelistView(")
            .substringBefore("bottomControlInset = listBottomControlInset")
        assertFalse(regularList.contains("onProbeAchievements"))

        val listRenderer = library
            .substringAfter("fun GamelistView(")
            .substringBefore("@Composable\ninternal fun ScrollFocusedLazyItemIntoView")
        assertFalse(listRenderer.contains("probeAchievements"))
        assertFalse(listRenderer.contains("gamelistAchievementProbeKey"))

        val carousel = source("LibraryGameShelf.kt")
        assertFalse(carousel.contains("onProbeAchievements"))
        assertFalse(carousel.contains("achievementLabelForGame"))
        assertFalse(carousel.contains("GamelistAchievementProbe"))
        assertFalse(carousel.contains("AchievementProgressLabel"))
        assertFalse(carousel.contains("progressLabel"))
    }

    @Test
    fun theLaunchCardOwnsTheProbeAndFiresItWhenTheCardOpens() {
        val library = source("LibraryScreen.kt")
        val detail = library
            .substringAfter("is LibraryDetailTarget.Emulated -> {")
            .substringBefore("GameDetailScreen(")
        assertTrue(detail.contains("val probeForLaunchCard by rememberUpdatedState(onProbeAchievements)"))
        assertTrue(detail.contains("LaunchedEffect(gamelistAchievementProbeKey(activeDetailState.target.game))"))
        assertTrue(detail.contains("probeForLaunchCard(activeDetailState.target.game)"))
    }

    private fun source(name: String): String {
        val dir = "src/main/kotlin/com/gruning/mimir/rewrite/feature/library/"
        val candidates = listOf(File("feature-library/$dir$name"), File("$dir$name"))
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId("gba"),
        memberCount = 1,
        duplicateKind = null,
    )
}
