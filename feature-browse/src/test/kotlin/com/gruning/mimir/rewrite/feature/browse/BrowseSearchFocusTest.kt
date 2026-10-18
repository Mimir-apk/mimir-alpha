package com.gruning.mimir.rewrite.feature.browse

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Observed: "Search hidden games" carried no focus registration, so a controller could not reach it. It is a focus stop above the hidden rows now: UP from the first game
 * reaches it, A on it opens the touch keyboard, and DOWN steps back to the first game. Pure JVM on [DefaultFocusAuthority]; the screen's own source pins sit in [BrowseRingTest].
 */
class BrowseSearchFocusTest {

    private val searchBounds = FocusBounds(left = 40f, top = 100f, right = 940f, bottom = 148f)
    private val firstBounds = FocusBounds(left = 40f, top = 180f, right = 940f, bottom = 260f)
    private val secondBounds = FocusBounds(left = 40f, top = 270f, right = 940f, bottom = 350f)

    private val games = listOf(game("g1", "Tetris"), game("g2", "Kirby"))
    private val firstKey = FocusKey("browse/restore/g1")
    private val secondKey = FocusKey("browse/restore/g2")

    private fun game(id: String, title: String) = GameRow(
        gameId = GameId(id),
        title = title,
        platformId = PlatformId("gb"),
        memberCount = 1,
        duplicateKind = null,
    )

    /** Browse as it lays out: the search bar registers first (it is the first thing on the page), then the hidden rows. */
    private fun browse(): DefaultFocusAuthority {
        val authority = DefaultFocusAuthority()
        authority.pushScope(BrowseScope)
        authority.register(BrowseSearchKey, BrowseScope, searchBounds)
        authority.register(firstKey, BrowseScope, firstBounds)
        authority.register(secondKey, BrowseScope, secondBounds)
        return authority
    }

    private class Calls {
        var opened = 0
        val restored = mutableListOf<GameId>()
    }

    private fun press(authority: DefaultFocusAuthority, action: ControllerAction, calls: Calls): Boolean = dispatchToBrowse(
        authority = authority,
        hiddenGames = games,
        keyboardOpen = false,
        keyboardHost = null,
        action = action,
        onRestore = { calls.restored += it },
        onCloseKeyboard = {},
        onExit = {},
        onOpenSearch = { calls.opened++ },
    )

    @Test
    fun `the search bar is a focus stop above the first hidden game, reached by UP and left by DOWN`() {
        val authority = browse()
        val calls = Calls()
        assertEquals("it registers first, so Browse is entered on it", BrowseSearchKey, authority.state.value.focused)

        assertTrue(press(authority, ControllerAction.NAV_DOWN, calls))
        assertEquals(firstKey, authority.state.value.focused)
        assertTrue(press(authority, ControllerAction.NAV_DOWN, calls))
        assertEquals(secondKey, authority.state.value.focused)

        assertTrue(press(authority, ControllerAction.NAV_UP, calls))
        assertTrue(press(authority, ControllerAction.NAV_UP, calls))
        assertEquals("UP from the first game reaches the search bar", BrowseSearchKey, authority.state.value.focused)
        assertTrue("UP past the top is consumed, and focus stays", press(authority, ControllerAction.NAV_UP, calls))
        assertEquals(BrowseSearchKey, authority.state.value.focused)
    }

    @Test
    fun `A on the search bar opens the keyboard and restores nothing, A on a game restores that game and opens nothing`() {
        val authority = browse()
        val calls = Calls()
        assertTrue(press(authority, ControllerAction.CONFIRM, calls))
        assertEquals(1, calls.opened)
        assertEquals(emptyList<GameId>(), calls.restored)

        authority.requestFocus(secondKey)
        assertTrue(press(authority, ControllerAction.CONFIRM, calls))
        assertEquals("the keyboard did not open again", 1, calls.opened)
        assertEquals(listOf(GameId("g2")), calls.restored)
    }

    @Test
    fun `with the keyboard open the pad belongs to the keyboard and the search bar is not activated again`() {
        val authority = browse()
        val calls = Calls()
        val handled = dispatchToBrowse(
            authority = authority,
            hiddenGames = games,
            keyboardOpen = true,
            keyboardHost = null,
            action = ControllerAction.CONFIRM,
            onRestore = { calls.restored += it },
            onCloseKeyboard = { calls.opened-- },
            onExit = {},
            onOpenSearch = { calls.opened++ },
        )
        assertTrue("quarantined, not dropped", handled)
        assertEquals(0, calls.opened)
        assertFalse(calls.restored.isNotEmpty())
    }

    @Test
    fun `the search bar's focus identity is a stable key and the bar wears the ring on its plate with one activation`() {
        assertEquals("browse/search", BrowseSearchKey.value)
        val code = listOf(
            File("feature-browse/src/main/kotlin/com/gruning/mimir/rewrite/feature/browse/BrowseScreen.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/browse/BrowseScreen.kt"),
        ).first(File::isFile).readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
        val bar = code.substringAfter("private fun BrowseSearchBar(").substringBefore("@Composable")
        assertTrue(bar.contains(".mimirFocusable(authority, BrowseSearchKey, BrowseScope, revealOutset = MimirFocusTileRingReach)"))
        assertTrue(bar.contains(".mimirSelectionRing(focused = focused, identity = BrowseSearchKey, shape = MimirRingShape.Pill, edgeFlush = true)"))
        assertTrue(bar.contains(".mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)"))
        assertTrue("the ring is on the plate and before the surface", bar.indexOf(".mimirSelectionRing(") < bar.indexOf(".mimirEtchedChipSurface("))
        assertTrue(bar.contains(".mimirActivatable(label = \"Search hidden games\", authority = authority, key = BrowseSearchKey, onActivate = onOpen)"))
        assertFalse("a tap beside a focus registration is one activation, never a second Compose focus target", bar.substringBefore("if (query.isNotBlank())").contains(".clickable("))
        assertFalse("no fill, no inset", bar.contains("fill =") || bar.contains("inset ="))
        assertTrue("the magnifier is the one drawn glyph at the one search size, not a font speck beside 30 px text", bar.contains("MimirSearchGlyph(color = MimirColors.TextSecondary)") && !bar.contains("\"⌕\""))
    }
}
