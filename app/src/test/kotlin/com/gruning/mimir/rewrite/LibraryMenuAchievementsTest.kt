package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  The Library hero menu's Achievements row (and its hero and clip
 * loading) asked Home's shelf for the game, and the shelf only knows its own favourites, so any Library game that is not a Home favourite came back null and
 * nothing opened. The menu carries its game ([HomeTileMenuState.sourceGame]); [homeTileMenuGame] is the one place every action asks.
 */
class LibraryMenuAchievementsTest {

    private val game = GameRow(
        gameId = GameId("snes/chrono-trigger"),
        title = "Chrono Trigger",
        platformId = PlatformId.SNES,
        memberCount = 1,
        duplicateKind = null,
    )

    /** What Home's shelf answers for a Library game that is not one of its favourites. */
    private val shelfKnowsNothing: (HomeShelfRow) -> GameRow? = { null }

    private fun reduce(
        state: HomeTileMenuState,
        action: ControllerAction,
        onOpenAchievements: () -> Unit = {},
    ): HomeTileMenuState? = reduceHomeTileMenu(
        state = state,
        action = action,
        onRemove = {},
        onLoadArt = {},
        onApplyPick = { _, _ -> },
        onOpenAchievements = onOpenAchievements,
    )

    @Test
    fun `a Library game that is not a Home favourite resolves from the menu itself`() {
        val state = libraryHeroPickerMenuState(game)
        assertSame(game, homeTileMenuGame(state, shelfKnowsNothing))
    }

    @Test
    fun `Home Edit has no game of its own and asks the shelf`() {
        val edit = HomeTileMenuState(row = libraryHeroPickerRow(game), removable = true)
        assertNull(edit.sourceGame)
        assertSame(game, homeTileMenuGame(edit, { game }))
        assertNull(homeTileMenuGame(edit, shelfKnowsNothing))
    }

    @Test
    fun `the menu's own game wins over the shelf's`() {
        val shelfCopy = game.copy(title = "Chrono Trigger (shelf copy)")
        assertSame(game, homeTileMenuGame(libraryHeroPickerMenuState(game), { shelfCopy }))
    }

    @Test
    fun `the Library hero menu's Achievements row opens the card for a non favourite and closes the menu`() {
        val state = libraryHeroPickerMenuState(game)
        val focus = homeTileMenuActions(state).indexOf(HomeTileMenuAction.ACHIEVEMENTS)
        assertTrue("the Library hero menu offers Achievements", focus >= 0)
        val opened = mutableListOf<GameRow>()
        // The coordinator's own wiring: the game comes from the menu through the helper, with the shelf knowing nothing of it.
        val next = reduce(
            state.copy(menuFocus = focus),
            ControllerAction.CONFIRM,
            onOpenAchievements = { homeTileMenuGame(state, shelfKnowsNothing)?.let(opened::add) },
        )
        assertEquals("the card opens for the Library game", listOf(game), opened)
        assertNull("the menu closes, so B on the card lands on the Library", next)
    }

    @Test
    fun `hero and clip loading from the Library find their game without the shelf`() {
        val state = libraryHeroPickerMenuState(game)
        var loaded: GameRow? = null
        var missing = false
        // The loader's first step: the exact GameRow, or the "no longer available in Favorites" ending. A Library game must never take the ending.
        val resolve = { homeTileMenuGame(state, shelfKnowsNothing)?.also { loaded = it } ?: run { missing = true; null } }
        resolve()
        assertSame(game, loaded)
        assertFalse(missing)
    }

    private fun coordinatorSource(): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppCoordinator.kt"),
    ).firstOrNull(File::isFile)?.readText() ?: error("MimirAppCoordinator.kt not found from ${File(".").absolutePath}")

    /**
     * Source characterization (the coordinator's closures sit in one large composable with no harness here, as [LibraryHeroPickerCoordinatorWiringTest]
     * documents): every tile-menu action that needs the game asks [homeTileMenuGame], and none asks the Home shelf for it alone.
     */
    @Test
    fun `every tile menu action that needs the game asks the menu first, never the Home shelf alone`() {
        val handler = coordinatorSource()
            .substringAfter("val onHomeTileMenuAction: (ControllerAction) -> Unit = { action ->")
            .substringBefore("val openLibraryHeroPicker: (GameRow) -> Unit")
        assertTrue("the handler was found", handler.contains("reduceHomeTileMenu("))
        assertFalse(
            "no action may ask the Home shelf alone for the menu's game (a Library game is not a Home favourite)",
            handler.contains("homeShelf.gameForAchievements(current.row)"),
        )
        // The picker transitions, Achievements, hero loading, clip loading and Enrich.
        val uses = Regex("""homeTileMenuGame\(current, homeShelf\.gameForAchievements\)""").findAll(handler).count()
        assertEquals("transitions, achievements, hero, clip and enrich each ask the menu first", 5, uses)
        val achievementsBlock = handler.substringAfter("onOpenAchievements = {").substringBefore("onLoadHero = {")
        assertTrue(achievementsBlock.contains("homeTileMenuGame(current, homeShelf.gameForAchievements)"))
        assertTrue(achievementsBlock.contains("achievements.state::openCardFromHome"))
    }
}
