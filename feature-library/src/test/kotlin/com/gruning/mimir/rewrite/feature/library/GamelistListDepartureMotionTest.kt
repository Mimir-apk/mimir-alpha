package com.gruning.mimir.rewrite.feature.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirShatterMotion
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
import org.robolectric.annotation.GraphicsMode

/**
 * Favorites removal in Compose with the clock held, on the real [GamelistView]. The host wires the departure
 * exactly as the screen does: the tracker asked in the composition that shows the list one game shorter, its frame given to
 * the list, its committed state in a SideEffect and its runner beside it.
 *
 * What is under test is what a frame shows: the row that left is still drawn, in place, whole, and fades; the rows after it
 * start where they were and move up by exactly one row on the departure's clock, all together, and have settled when it ends;
 * nothing pops back; the list it is drawn over is never re-created; and focus is on the survivor from the first frame to the
 * last. A hide's shatter plays the same gap on its own timeline under a row that paints nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GamelistListDepartureMotionTest {

    @get:Rule
    val compose = createComposeRule()

    private var games = makeGames(6)
    private val authority = DefaultFocusAuthority()
    private val shown = mutableStateOf(games)
    private val focused = mutableStateOf<FocusKey?>(null)
    private val sent = mutableStateOf(emptySet<GameId>())
    private val motion = mutableStateOf(true)
    private val shatters = mutableStateOf(false)

    private fun makeGames(count: Int) = List(count) { index ->
        GameRow(
            gameId = GameId("g$index"),
            title = "Game $index",
            platformId = PlatformId.GBA,
            memberCount = 1,
            duplicateKind = null,
            isFavorite = true,
        )
    }

    @Before
    fun gate() {
        MimirTransitionInputGate.reset()
        MimirTransitionInputGate.clock = { 0L }
    }

    @After
    fun resetGate() = MimirTransitionInputGate.reset()

    /** Hosts the list with focus on [focusedIndex] and lets it settle, so the removal comes to a list at rest. */
    private fun host(count: Int = 6, focusedIndex: Int = 2, height: Int = 444) {
        games = makeGames(count)
        shown.value = games
        focused.value = gameKey(games[focusedIndex])
        authority.pushScope(GamelistScope)
        authority.rememberFocusForScope(GamelistScope, gameKey(games[focusedIndex]))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MimirTheme {
                Box(Modifier.size(833.dp, height.dp)) {
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
                        shatters = { shatters.value },
                    )
                    SideEffect { tracker.commit() }
                    GamelistDepartureRunner(frame.departure)
                    GamelistView(
                        games = shown.value,
                        emptyMessage = "Empty",
                        searchQuery = "",
                        favoritesOnly = true,
                        keyboardOpen = false,
                        authority = authority,
                        focused = focused.value,
                        detailGame = null,
                        launchState = GameLaunchUiState.Idle,
                        onOpenGame = {},
                        departureFrame = frame,
                    )
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(600L)
    }

    /** What the screen does in ONE frame when the player sends [leaving] away: the survivor hand-off, the pending set and the write. */
    private fun sendAway(leaving: GameRow, focusOn: GameRow) {
        authority.rememberFocusForScope(GamelistScope, gameKey(focusOn))
        authority.requestFocus(gameKey(focusOn))
        sent.value = setOf(leaving.gameId)
        shown.value = shown.value - leaving
        focused.value = gameKey(focusOn)
        Snapshot.sendApplyNotifications()
    }

    private fun rowTag(game: GameRow) = gamelistListRowTestTag(game)

    private fun leavingTag(game: GameRow) = gamelistListLeavingRowTestTag(game)

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    private fun top(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun height(tag: String): Float = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height

    private fun alpha(game: GameRow): Float =
        compose.onNodeWithTag(leavingTag(game)).fetchSemanticsNode().config[GamelistListPresentationAlphaSemantics]

    /** The top of every listed row that is on screen, by game. */
    private fun tops(rows: List<GameRow>): Map<GameId, Float> =
        rows.filter { count(rowTag(it)) == 1 }.associate { it.gameId to top(rowTag(it)) }

    /** The pitch of the list: one row and the space after it. */
    private fun pitch(first: GameRow, second: GameRow): Float = top(rowTag(second)) - top(rowTag(first))

    private fun step(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
    }

    // ---------------------------------------------------------------------------------- the frame that drops the game

    @Test
    fun `the frame that drops the game draws its row, in place, whole, and moves nothing`() {
        host()
        val before = tops(games)
        val leavingHeight = height(rowTag(games[2]))
        assertEquals("the whole list is on screen", 6, before.size)

        sendAway(leaving = games[2], focusOn = games[3])
        compose.mainClock.advanceTimeByFrame()

        assertEquals("the leaving row is drawn, once", 1, count(leavingTag(games[2])))
        assertEquals("and it is no longer the row of that game", 0, count(rowTag(games[2])))
        assertEquals("in place", before.getValue(games[2].gameId), top(leavingTag(games[2])), 1f)
        assertEquals("whole", leavingHeight, height(leavingTag(games[2])), 1f)
        assertTrue("with hardly a fade: ${alpha(games[2])}", alpha(games[2]) > 0.85f)
        (games - games[2]).forEach { survivor ->
            assertEquals("${survivor.title} starts where it stood", before.getValue(survivor.gameId), top(rowTag(survivor)), 1.5f)
        }
    }

    // ---------------------------------------------------------------------------------------- the glide, on the clock

    @Test
    fun `the row fades and every row after it moves up by one row, together, and has settled when the motion ends`() {
        host()
        val before = tops(games)
        val rowPitch = before.getValue(games[3].gameId) - before.getValue(games[2].gameId)
        assertTrue("a real row pitch: $rowPitch", rowPitch > 20f)

        sendAway(leaving = games[2], focusOn = games[3])
        compose.mainClock.advanceTimeByFrame()
        step(MimirMotion.OverlayMs / 2L)

        val fading = alpha(games[2])
        assertTrue("half way, the row is part faded: $fading", fading > 0.05f && fading < 0.95f)
        val moved = listOf(games[3], games[4], games[5]).map { before.getValue(it.gameId) - top(rowTag(it)) }
        assertTrue("half way, the rows after it are part of the way up: $moved", moved.all { it > 0.05f * rowPitch && it < 0.95f * rowPitch })
        moved.forEach { assertEquals("every row after it has moved by the same amount", moved[0], it, 1.5f)}
        // One clock: what has moved is what has faded.
        assertEquals("the glide and the fade are one motion", 1f - fading, moved[0] / rowPitch, 0.2f)
        listOf(games[0], games[1]).forEach {
            assertEquals("a row before it is not touched", before.getValue(it.gameId), top(rowTag(it)), 1f)
        }

        step(MimirMotion.OverlayMs + 100L)

        assertEquals("the row has gone", 0, count(leavingTag(games[2])))
        listOf(games[0], games[1]).forEach {
            assertEquals(before.getValue(it.gameId), top(rowTag(it)), 1f)
        }
        listOf(games[3], games[4], games[5]).forEach {
            assertEquals(
                "${it.title} sits exactly one row higher than it did",
                before.getValue(it.gameId) - rowPitch,
                top(rowTag(it)),
                1.5f,
            )
        }
    }

    @Test
    fun `no row pops back, nothing moves down, and nothing moves at all after the last frame of the motion`() {
        host()
        sendAway(leaving = games[2], focusOn = games[3])
        compose.mainClock.advanceTimeByFrame()

        var previous = tops(games - games[2])
        var frames = 0
        while (frames < 60 && count(leavingTag(games[2])) == 1) {
            compose.mainClock.advanceTimeByFrame()
            val now = tops(games - games[2])
            now.forEach { (id, y) ->
                assertTrue("frame $frames: $id moved down, from ${previous[id]} to $y", y <= previous.getValue(id) + 1f)
            }
            previous = now
            frames++
        }
        assertTrue("the motion ended", count(leavingTag(games[2])) == 0)

        step(400L)
        val settled = tops(games - games[2])
        settled.forEach { (id, y) -> assertEquals("$id does not move once the ghost has gone", previous.getValue(id), y, 1f) }
    }

    @Test
    fun `the list it is drawn over is never re-created, so a scrolled list keeps the rows above the leaver where they were`() {
        host(count = 14, focusedIndex = 0, height = 300)
        // Walk focus down to a row that needs the list scrolled to reach it, the way the pad does.
        for (index in 1..9) {
            focused.value = gameKey(games[index])
            authority.requestFocus(gameKey(games[index]))
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
            step(400L)
        }
        val visible = games.filter { count(rowTag(it)) == 1 }
        val above = visible.filter { it.gameId.value.removePrefix("g").toInt() in 5..8 }
        assertTrue("rows above the focused one are on screen: ${visible.map { it.title }}", above.size >= 2)
        val before = tops(above)

        sendAway(leaving = games[9], focusOn = games[10])
        compose.mainClock.advanceTimeByFrame()
        for (frame in 0 until 12) {
            compose.mainClock.advanceTimeByFrame()
            above.forEach {
                assertEquals("frame $frame: ${it.title} stays put", before.getValue(it.gameId), top(rowTag(it)), 1.5f)
            }
        }
        step(MimirMotion.OverlayMs + 100L)
        above.forEach {
            assertEquals("${it.title} stays put to the end", before.getValue(it.gameId), top(rowTag(it)), 1.5f)
        }
    }

    // ------------------------------------------------------------------------------------------------ the last row

    @Test
    fun `the last row leaving dissolves in place, and nothing above it moves`() {
        host(focusedIndex = 5)
        val before = tops(games)

        sendAway(leaving = games[5], focusOn = games[4])
        compose.mainClock.advanceTimeByFrame()
        step(MimirMotion.OverlayMs / 2L)

        val fading = alpha(games[5])
        assertTrue("part faded: $fading", fading > 0.05f && fading < 0.95f)
        (0..4).forEach {
            assertEquals("${games[it].title} is where it was", before.getValue(games[it].gameId), top(rowTag(games[it])), 1.5f)
        }

        step(MimirMotion.OverlayMs + 100L)

        assertEquals(0, count(leavingTag(games[5])))
        (0..4).forEach {
            assertEquals(before.getValue(games[it].gameId), top(rowTag(games[it])), 1.5f)
        }
    }

    // ------------------------------------------------------------------------------------------------- focus and press

    @Test
    fun `focus is on the survivor from the first frame to the last, and the leaving row is not a place focus can be`() {
        host()
        val survivor = gameKey(games[3])

        sendAway(leaving = games[2], focusOn = games[3])
        assertEquals("the hand-off came first", survivor, authority.state.value.focused)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(survivor, authority.state.value.focused)
        for (frame in 0 until 20) {
            compose.mainClock.advanceTimeByFrame()
            assertEquals("frame $frame", survivor, authority.state.value.focused)
        }
        step(MimirMotion.OverlayMs + 100L)

        assertEquals(survivor, authority.state.value.focused)
        assertFalse("the leaver's key is registered with nothing", gameKey(games[2]) in authority.registeredKeysInScope(GamelistScope))
    }

    @Test
    fun `the leaving row takes no press`() {
        host()

        sendAway(leaving = games[2], focusOn = games[3])
        compose.mainClock.advanceTimeByFrame()

        val config = compose.onNodeWithTag(leavingTag(games[2])).fetchSemanticsNode().config
        assertFalse("a tap on it would open a key nothing lists", config.contains(SemanticsActions.OnClick))
        val live = compose.onNodeWithTag(rowTag(games[3])).fetchSemanticsNode().config
        assertTrue("while the row that stays can be pressed", live.contains(SemanticsActions.OnClick))
    }

    @Test
    fun `the pad waits from the frame the game leaves until the gap has shut`() {
        host()
        assertFalse(MimirTransitionInputGate.holding)

        sendAway(leaving = games[2], focusOn = games[3])
        compose.mainClock.advanceTimeByFrame()
        assertTrue("held while the row dissolves", MimirTransitionInputGate.holding)

        step(MimirMotion.OverlayMs + 100L)
        assertFalse("released once it has", MimirTransitionInputGate.holding)
    }

    // --------------------------------------------------------------------------------------------------- a hide's gap

    @Test
    fun `a hide paints nothing under the shards, holds its place for the strike, then closes the gap on the shatter's clock`() {
        shatters.value = true
        host()
        val before = tops(games)
        val rowPitch = before.getValue(games[3].gameId) - before.getValue(games[2].gameId)

        sendAway(leaving = games[2], focusOn = games[3])
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the row paints nothing, from the first frame", 0f, alpha(games[2]), 0f)
        assertEquals("and keeps its place", before.getValue(games[3].gameId), top(rowTag(games[3])), 1.5f)

        step(MimirShatterMotion.GapLeadMs.toLong() - 50L)
        assertEquals("still nothing under the shards", 0f, alpha(games[2]), 0f)
        assertEquals("the gap has not begun to close before its lead", before.getValue(games[3].gameId), top(rowTag(games[3])), 1.5f)

        step(50L + MimirShatterMotion.GapGlideMs / 2L)
        val moved = before.getValue(games[3].gameId) - top(rowTag(games[3]))
        assertTrue("half way through the glide the rows are part of the way up: $moved of $rowPitch", moved > 0.05f * rowPitch && moved < 0.95f * rowPitch)
        assertEquals("the row is still nothing", 0f, alpha(games[2]), 0f)

        step(MimirShatterMotion.GapGlideMs.toLong() + 100L)
        assertEquals("the row has gone", 0, count(leavingTag(games[2])))
        assertEquals("and the rows sit one higher", before.getValue(games[3].gameId) - rowPitch, top(rowTag(games[3])), 1.5f)
        assertFalse("the pad is let go", MimirTransitionInputGate.holding)
    }

    // ------------------------------------------------------------------------------------------ what is not a departure

    @Test
    fun `reduced motion cuts, with no ghost and every row at its new seat at once`() {
        motion.value = false
        host()
        val before = tops(games)
        val rowPitch = before.getValue(games[3].gameId) - before.getValue(games[2].gameId)

        sendAway(leaving = games[2], focusOn = games[3])
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0, count(leavingTag(games[2])))
        assertEquals(0, count(rowTag(games[2])))
        assertEquals("the survivor is one row higher at once", before.getValue(games[3].gameId) - rowPitch, top(rowTag(games[3])), 1.5f)
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `a game the player did not send leaves as it always did`() {
        host()

        shown.value = shown.value - games[2]
        focused.value = gameKey(games[3])
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()

        assertEquals(0, count(leavingTag(games[2])))
        assertEquals(0, count(rowTag(games[2])))
        assertFalse(MimirTransitionInputGate.holding)
    }
}
