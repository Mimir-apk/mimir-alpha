package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import com.gruning.mimir.rewrite.core.model.DuplicateGroupKind
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MediaArtBookTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the Library's shelf puts on the screen, on the real [LibraryGameShelf]. The title, the status chip and the meta line stand in Home's title rail above the strip; no
 * tile carries a label and nothing stands under the strip; the favourite rune and the disc badge ride the tile; the ring stands on the
 * selected seat; a game with no cover wears the runed book, and a game whose art is not looked up yet paints nothing.
 */
@RunWith(RobolectricTestRunner::class)
// A window wide enough for the 833 x 444 dp host: the default 320 dp one would clamp it, and the shelf would solve three seats, not four.
@Config(sdk = [34], application = Application::class, qualifiers = "w900dp-h560dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryGameShelfHostTest {

    @get:Rule
    val compose = createComposeRule()

    private val authority = DefaultFocusAuthority()

    /** Game 0 is a favourite two-disc title; the rest are plain. Every game's art is "not looked up yet" unless a test says otherwise. */
    private fun fixture(settled: Boolean = false) = List(4) { index ->
        GameRow(
            gameId = GameId("g$index"),
            title = "Game $index",
            platformId = PlatformId.GBA,
            memberCount = if (index == 0) 2 else 1,
            duplicateKind = if (index == 0) DuplicateGroupKind.MULTI_DISC else null,
            discCount = if (index == 0) 2 else 0,
            isFavorite = index == 0,
            artResolutionSettled = settled,
        )
    }

    private var games = fixture()
    private val shown = mutableStateOf(games)
    private var loading = false
    private var query = ""

    @After
    fun restoreAnimators() = setCarouselAnimatorScale(1f)

    private fun mount(rows: List<GameRow> = games) {
        games = rows
        shown.value = rows
        authority.pushScope(GamelistScope)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(833.dp, 444.dp)) {
                    // The gamelist Column's own horizontal padding, which the shelf bleeds out of to the screen's edges.
                    Box(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp)) {
                        val focus by authority.state.collectAsState()
                        LibraryGameShelf(
                            games = shown.value,
                            emptyMessage = "Nothing here yet.",
                            isLoading = loading,
                            searchQuery = query,
                            authority = authority,
                            focusState = focus,
                            centeredKey = authority.renderedFocusIn(GamelistScope, focus),
                            getArtFocused = false,
                            detailGame = null,
                            metaForGame = { "Meta of ${it.title}" },
                            statusForGame = { if (it.gameId.value == "g0") GameStatusChip("Ready", true) else null },
                            onOpenGame = {},
                            mediaContrastRail = false,
                            bottomControlInset = 60.dp,
                            windowMemoryKey = "host-test",
                            departureFrame = null,
                            shatter = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.touchFrames(3)
        if (rows.isNotEmpty()) {
            authority.requestFocus(gameKey(rows[0]))
            compose.touchFrames(2)
            compose.touchSettle(1_000L)
        }
    }

    private fun focus(index: Int) {
        authority.requestFocus(gameKey(games[index]))
        compose.touchFrames(2)
        compose.touchSettle(600L)
    }

    private fun tile(index: Int): SemanticsNodeInteraction = compose.onNodeWithTag(libraryShelfTileTestTag(games[index]))

    private fun inTitleRail(text: String) =
        compose.onAllNodes(hasText(text) and hasAnyAncestor(hasTestTag(HomeShelfTitleTestTag)), useUnmergedTree = true)

    private fun paintedText(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true)

    /** The chrome text of this app speaks through its content description (the intent text clears its own semantics). */
    private fun spokenText(text: String, substring: Boolean = false) = compose.onAllNodes(
        hasContentDescription(text, substring = substring) or hasText(text, substring = substring),
        useUnmergedTree = true,
    )

    @Test
    fun `four games stand in four seats and the ring stands on the selected one`() {
        mount()
        val tiles = games.indices.count { compose.onAllNodesWithTag(libraryShelfTileTestTag(games[it])).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("every game of a four-seat window has its tile", 4, tiles)
        listOf(0, 2).forEach { selected ->
            focus(selected)
            val ring = compose.onNodeWithTag(LibraryGameShelfIds.ringTag).fetchSemanticsNode().boundsInRoot
            val seat = compose.onNodeWithTag(LibraryGameShelfIds.selectedSlotTag).fetchSemanticsNode().boundsInRoot
            val tileBounds = tile(selected).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "the stationary ring (${ring.left}) stands on the selected seat (${seat.left})",
                abs(ring.left - seat.left) < 1.5f && abs(ring.width - seat.width) < 1.5f,
            )
            assertTrue(
                "the selected tile sits inside the ring's seat",
                tileBounds.left >= seat.left - 1f && tileBounds.right <= seat.right + 1f,
            )
        }
    }

    @Test
    fun `the title rail carries the selected game's title, meta line and status chip, and follows focus`() {
        mount()
        assertEquals(1, inTitleRail("Game 0").fetchSemanticsNodes().size)
        assertEquals(1, inTitleRail("Meta of Game 0").fetchSemanticsNodes().size)
        compose.onNodeWithTag(HomeShelfStatusTestTag).assertExists()

        focus(1)
        assertEquals(1, inTitleRail("Game 1").fetchSemanticsNodes().size)
        assertEquals(1, inTitleRail("Meta of Game 1").fetchSemanticsNodes().size)
        assertEquals("the rail moved on from the first game", 0, inTitleRail("Game 0").fetchSemanticsNodes().size)
        assertEquals("a game with no status shows no chip", 0, compose.onAllNodesWithTag(HomeShelfStatusTestTag).fetchSemanticsNodes().size)
    }

    @Test
    fun `no tile carries a label and nothing stands under the strip`() {
        mount()
        // Art that is not looked up yet paints nothing, so the only text a game can have on screen is the rail's title: one node, for the
        // selected game. A label under a tile, or a destination lane under the strip, would be a second node or another game's title.
        games.forEachIndexed { index, game ->
            val painted = paintedText(game.title).fetchSemanticsNodes().size
            assertEquals("${game.title} is painted ${if (index == 0) "once, in the rail" else "nowhere"}", if (index == 0) 1 else 0, painted)
        }
        val band = compose.onNodeWithTag(LibraryGameShelfIds.selectedSlotTag).fetchSemanticsNode().boundsInRoot
        val body = tile(0).fetchSemanticsNode().boundsInRoot
        val texts = compose.onAllNodes(hasText("Game", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(
            "no text stands under the strip (the seat ends at ${band.bottom}, the tile at ${body.bottom}): ${texts.map { it.boundsInRoot }}",
            texts.none { it.boundsInRoot.top >= band.bottom - 1f },
        )
    }

    @Test
    fun `the disc badge rides its game's tile and no other`() {
        mount(fixture(settled = true))
        fun badges(index: Int) = compose.onAllNodes(
            (hasText("2 discs") or hasContentDescription("2 discs")) and hasAnyAncestor(hasTestTag(libraryShelfTileTestTag(games[index]))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("the two-disc game states it on its tile", 1, badges(0))
        (1..3).forEach { assertEquals("game $it is a single title", 0, badges(it)) }
    }

    /** A favourite is a favourite whether or not its cover has been looked up: its badge (and its rune) do not vanish while the art is pending. */
    @Test
    fun `the marks stay on a tile whose art is not looked up yet`() {
        mount(fixture(settled = false))
        fun badges(index: Int) = compose.onAllNodes(
            (hasText("2 discs") or hasContentDescription("2 discs")) and hasAnyAncestor(hasTestTag(libraryShelfTileTestTag(games[index]))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("the two-disc game still states it while its art is pending", 1, badges(0))
        assertEquals("and the others still do not", 0, badges(1) + badges(2) + badges(3))
        val books = games.indices.sumOf { index ->
            compose.onAllNodes(
                hasTestTag(MediaArtBookTitleTag) and hasAnyAncestor(hasTestTag(libraryShelfTileTestTag(games[index]))),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().size
        }
        assertEquals("pending art claims no book", 0, books)
    }

    @Test
    fun `a game with no cover wears the runed book and a game whose art is unknown paints nothing`() {
        val rows = fixture(settled = false).toMutableList()
        rows[1] = rows[1].copy(artResolutionSettled = true)
        mount(rows)
        fun books(index: Int) = compose.onAllNodes(
            hasTestTag(MediaArtBookTitleTag) and hasAnyAncestor(hasTestTag(libraryShelfTileTestTag(games[index]))),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size
        assertEquals("art known to be absent is the book", 1, books(1))
        listOf(0, 2, 3).forEach { assertEquals("game $it's art is not looked up yet: no book is claimed", 0, books(it)) }
    }

    @Test
    fun `an empty list that is still loading says nothing and one that landed empty says so`() {
        loading = true
        mount(emptyList())
        assertEquals(0, spokenText("Nothing here yet.").fetchSemanticsNodes().size)
    }

    @Test
    fun `an empty list that landed says why`() {
        mount(emptyList())
        assertEquals(1, spokenText("Nothing here yet.").fetchSemanticsNodes().size)
    }

    @Test
    fun `an empty search says which search found nothing`() {
        query = "zelda"
        mount(emptyList())
        assertEquals(1, spokenText("zelda", substring = true).fetchSemanticsNodes().size)
    }
}
