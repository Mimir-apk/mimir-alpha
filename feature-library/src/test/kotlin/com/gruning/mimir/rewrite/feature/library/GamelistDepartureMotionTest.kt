package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Departure motion in Compose
 * with the clock held, on the Library's shelf ([LibraryGameShelf], the Home shelf since 2026-10-10). The host wires the departure
 * exactly as the screen does: the tracker asked in the composition that shows the list one game shorter, its frame given to the
 * shelf, its committed state in a SideEffect and its runner beside it. What is under test is what a frame shows: the tile that left is
 * still drawn, at its old seat, and fades; the tiles after it start where they were and close the gap; the pad waits; and every kind of
 * removal that is not a departure cuts as it did.
 *
 * Four games fit the shelf's four seats, so the window never moves: the focused game stands in seat 1 and the strip is left-anchored.
 * Seats are counted from the strip's left edge (the old carousel counted from its centre).
 */
@RunWith(RobolectricTestRunner::class)
// A window wide enough for the 833 x 444 dp host: the default 320 dp one would clamp it, and the shelf would solve three seats, not four.
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h560dp-mdpi")
class GamelistDepartureMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private val games = List(4) { index ->
        GameRow(
            gameId = GameId("g$index"),
            title = "Game $index",
            platformId = PlatformId.GBA,
            memberCount = 1,
            duplicateKind = null,
        )
    }
    private val authority = DefaultFocusAuthority()
    private val shown = mutableStateOf(games)
    private val focused = mutableStateOf(gameKey(games[1]))
    private val sent = mutableStateOf(emptySet<GameId>())
    private val motion = mutableStateOf(true)

    @Before
    fun gate() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { 0L }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    private fun host() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(833.dp, 444.dp)) {
                    // The gamelist Column's own horizontal padding, which the shelf bleeds out of to the screen's edges.
                    Box(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistBodyPaddingDp)) {
                        val tracker = remember { GamelistDepartureTracker() }
                        val frame = tracker.observe(
                            games = shown.value,
                            centredIndex = gamelistCentredIndex(
                                games = shown.value,
                                centeredKey = focused.value,
                                getArtFocused = false,
                                detailGame = null,
                            ),
                            scope = Unit,
                            leaving = { it.gameId in sent.value },
                            motionEnabled = motion.value,
                        )
                        SideEffect {
                            tracker.commit()
                        }
                        GamelistDepartureRunner(frame.departure)
                        val focus by authority.state.collectAsState()
                        LibraryGameShelf(
                            games = shown.value,
                            emptyMessage = "Empty",
                            isLoading = false,
                            searchQuery = "",
                            authority = authority,
                            focusState = focus,
                            centeredKey = focused.value,
                            getArtFocused = false,
                            detailGame = null,
                            metaForGame = { null },
                            statusForGame = { null },
                            onOpenGame = {},
                            mediaContrastRail = false,
                            bottomControlInset = 60.dp,
                            windowMemoryKey = Unit,
                            departureFrame = frame,
                            shatter = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        // Long enough for the first pose to settle, so the strip is at rest when the removal comes.
        compose.mainClock.advanceTimeBy(400L)
    }

    /** What the screen does in ONE frame when the player confirms: the pending set and the focus hand-off together. */
    private fun sendAway(leaving: GameRow, focusOn: GameRow) {
        val after = games - leaving
        sent.value = setOf(leaving.gameId)
        shown.value = after
        focused.value = gameKey(focusOn)
        Snapshot.sendApplyNotifications()
    }

    private fun leavingTag(game: GameRow) = libraryShelfLeavingTileTestTag(game)

    private fun seat(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[LibraryGameShelfIds.visualSlot]

    private fun alpha(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[LibraryGameShelfIds.presentationAlpha]

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun tileSeat(game: GameRow) = seat(libraryShelfTileTestTag(game))

    // -------------------------------------------------------------------------------- the frame that drops the game

    @Test
    fun `the frame that drops the game still draws its tile at its old seat, whole`() {
        host()

        sendAway(leaving = games[1], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()

        assertEquals("the leaving tile is drawn, once", 1, count(leavingTag(games[1])))
        assertEquals("and it is no longer the tile of that game", 0, count(libraryShelfTileTestTag(games[1])))
        assertEquals("it is where the game stood", 1f, seat(leavingTag(games[1])), 0.06f)
        assertTrue("and it has hardly begun to fade: ${alpha(leavingTag(games[1]))}", alpha(leavingTag(games[1])) > 0.85f)
    }

    @Test
    fun `every tile that stays starts where it stood before the removal`() {
        host()

        sendAway(leaving = games[1], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()

        assertEquals("before the leaver: untouched", 0f, tileSeat(games[0]), 0.06f)
        assertEquals("the survivor waits one place along", 2f, tileSeat(games[2]), 0.15f)
        assertEquals("the tile after it keeps its old seat", 3f, tileSeat(games[3]), 0.15f)
    }

    @Test
    fun `the leaver dissolves and the gap closes on the overlay clock, together`() {
        host()
        sendAway(leaving = games[1], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()

        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs / 2L)

        val fading = alpha(leavingTag(games[1]))
        val gap = tileSeat(games[2]) - 1f
        assertTrue("half way, the tile is part faded: $fading", fading > 0.05f && fading < 0.95f)
        assertTrue("half way, the survivor is part of the way in: $gap", gap > 0.05f && gap < 0.95f)
        // One number drives both: the gap still open is the tile's presence.
        assertEquals(fading, gap, 0.2f)
    }

    @Test
    fun `when the motion has run the leaver is gone and every tile sits where the new list puts it`() {
        host()
        sendAway(leaving = games[1], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()

        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs + 100L)

        assertEquals(0, count(leavingTag(games[1])))
        assertEquals(0f, tileSeat(games[0]), 0.01f)
        assertEquals(1f, tileSeat(games[2]), 0.01f)
        assertEquals(2f, tileSeat(games[3]), 0.01f)
    }

    @Test
    fun `the pad waits from the frame the game leaves until the gap has shut`() {
        host()
        assertFalse(MimirTransitionInputGate.holding)

        sendAway(leaving = games[1], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()
        assertTrue("held while the tile dissolves", MimirTransitionInputGate.holding)

        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs + 100L)
        assertFalse("released once it has", MimirTransitionInputGate.holding)
    }

    @Test
    fun `the leaving tile takes no press and is not focus`() {
        host()

        sendAway(leaving = games[1], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()

        val config = compose.onNodeWithTag(leavingTag(games[1])).fetchSemanticsNode().config
        assertTrue("disabled: a tap on it would open a key nothing registers", config.contains(SemanticsProperties.Disabled))
    }

    // ---------------------------------------------------------------------------------------- the last tile leaves

    @Test
    fun `the last tile leaving dissolves in place while the one before it stays where it stands`() {
        focused.value = gameKey(games[3])
        host()

        sendAway(leaving = games[3], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()

        assertEquals("the leaver is drawn at the seat it left", 3f, seat(leavingTag(games[3])), 0.06f)
        assertEquals("nothing before it moves: the survivor stands still", 2f, tileSeat(games[2]), 0.06f)

        compose.mainClock.advanceTimeBy(700L)

        assertEquals(0, count(leavingTag(games[3])))
        assertEquals(2f, tileSeat(games[2]), 0.01f)
    }

    // ------------------------------------------------------------------------------------ what is not a departure

    @Test
    fun `reduced motion cuts, with no ghost and the survivor in the seat at once`() {
        motion.value = false
        host()

        sendAway(leaving = games[1], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0, count(leavingTag(games[1])))
        assertEquals(1f, tileSeat(games[2]), 0.01f)
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `a game the player did not send leaves as it always did`() {
        host()

        shown.value = games - games[1]
        focused.value = gameKey(games[2])
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0, count(leavingTag(games[1])))
        assertEquals(1f, tileSeat(games[2]), 0.01f)
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `a removal whose focus fell through to game one cuts and never closes a gap around the wrong tile`() {
        host()

        sendAway(leaving = games[1], focusOn = games[0])
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0, count(leavingTag(games[1])))
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `an ordinary step is untouched by any of this`() {
        host()

        focused.value = gameKey(games[2])
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(700L)

        assertEquals(4, games.count { count(libraryShelfTileTestTag(it)) == 1 })
        assertEquals(2f, tileSeat(games[2]), 0.01f)
        assertEquals(1f, tileSeat(games[1]), 0.01f)
        compose.onAllNodesWithTag(leavingTag(games[1])).assertCountEquals(0)
    }
}
