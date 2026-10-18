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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Every hide shatters the card" (walk-32h), in Compose with the clock held. The host wires the departure exactly as the
 * screen does (the tracker asked in the composition that shows the list one game shorter, asked whether the leaver is the
 * card the stage is breaking; its frame given to the Library's shelf ([LibraryGameShelf], the Home shelf since 2026-10-10), which
 * hosts the overlay; its runner beside it), and stands in for the hide slot by publishing a job in the same frame the game is sent
 * away. Four games fill the shelf's four seats, so seats are counted from its left edge and the focused game stands in seat 1.
 *
 * The spec's timeline: the flash is on at 45 ms, the hero holds to 300 and lets go while the gap runs on, the overlay is
 * gone at 600 ms, and the gap is closed and the pad released at 660 ms. A SELECT un-star publishes no job and keeps
 * walk-32g's dissolve exactly.
 */
@RunWith(RobolectricTestRunner::class)
// A window wide enough for the 833 x 444 dp host: the default 320 dp one would clamp it, and the shelf would solve three seats, not four.
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "w900dp-h560dp-mdpi")
// Native graphics: the job carries a real ImageBitmap, which the legacy shadow cannot create, and the overlay and the
// captured card are then really drawn on every frame the clock is advanced.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryShatterMotionTest {

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
    private val stage = LibraryShatterStage()
    private var latest: GamelistDepartureFrame? = null
    private var origin = 0L

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
                            motionEnabled = true,
                            shatters = stage::shatters,
                        )
                        latest = frame
                        SideEffect { tracker.commit() }
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
                            shatter = stage,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(400L)
    }

    /** What the screen does in one frame when the player confirms: the pending set and the focus hand-off together. */
    private fun sendAway(leaving: GameRow, focusOn: GameRow) {
        sent.value = setOf(leaving.gameId)
        shown.value = games - leaving
        focused.value = gameKey(focusOn)
        Snapshot.sendApplyNotifications()
        origin = compose.mainClock.currentTime
    }

    /** What the hide slot does before it returns: the picture is taken and the job is published. */
    private fun shatter(leaving: GameRow, focusOn: GameRow): LibraryShatterJob {
        val job = LibraryShatterJob(ImageBitmap(64, 96), Rect(0f, 0f, 64f, 96f), leaving.gameId.value)
        stage.job = job
        sendAway(leaving, focusOn)
        return job
    }

    /**
     * Runs frames until the shatter's own clock has left 0, and takes that moment as its start: the overlay's clock, the
     * departure's gap and the hero's hold all begin in the frame the overlay composes, and every time below is read from it.
     */
    private fun startClock(job: LibraryShatterJob) {
        var frames = 0
        while (job.clock.value <= 0f && frames++ < 20) compose.mainClock.advanceTimeByFrame()
        assertTrue("the shatter clock started", job.clock.value > 0f)
        origin = compose.mainClock.currentTime - job.clock.value.toLong()
    }

    /** Advances the held clock until [ms] milliseconds have passed since the shatter started (a frame is 16 ms). */
    private fun advanceTo(ms: Long) {
        val remaining = ms - (compose.mainClock.currentTime - origin)
        if (remaining > 0) compose.mainClock.advanceTimeBy(remaining)
    }

    private fun leavingTag(game: GameRow) = libraryShelfLeavingTileTestTag(game)

    private fun seat(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[LibraryGameShelfIds.visualSlot]

    private fun alpha(tag: String): Float =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[LibraryGameShelfIds.presentationAlpha]

    private fun count(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size

    /** Seats from the strip's left edge, less the ring's seat: 0 is the seat the focused game stands in. */
    private fun cardSeat(game: GameRow) = seat(libraryShelfTileTestTag(game)) - 1f

    @Test
    fun `the flash is on at 45 ms, the overlay is gone at 600 ms, and the gap is closed and the pad free at 660 ms`() {
        host()
        val job = shatter(leaving = games[1], focusOn = games[2])
        startClock(job)

        advanceTo(45L)
        val at45 = job.clock.value
        assertTrue("the clock is at about 45 ms: $at45", at45 in 45f..80f)
        assertEquals("the strike", ShatterPhase.Strike, ShatterTimeline.phaseAt(at45))
        assertTrue("the flash is on: ${ShatterTimeline.flashAlpha(at45)}", ShatterTimeline.flashAlpha(at45) > 0.2f)
        assertEquals("the overlay is on screen", 1, count(LibraryShatterOverlayTag))
        assertEquals("the departure is a shatter", GamelistDepartureKind.Shatter, latest?.departure?.kind)
        assertTrue("the pad is held from the strike", MimirTransitionInputGate.holding)

        advanceTo(600L)
        assertEquals("the overlay is gone at 600 ms", 0, count(LibraryShatterOverlayTag))
        assertNull("and its job is cleared", stage.job)
        assertTrue("the gap is not shut yet: ${cardSeat(games[2])}", cardSeat(games[2]) > 0.005f)
        assertTrue("and the pad is still held", MimirTransitionInputGate.holding)

        advanceTo(700L)
        assertEquals("the gap is closed by 660 ms", 0f, cardSeat(games[2]), 0.01f)
        assertEquals(-1f, cardSeat(games[0]), 0.01f)
        assertEquals(1f, cardSeat(games[3]), 0.01f)
        assertEquals("the ghost is gone", 0, count(leavingTag(games[1])))
        assertFalse("and the pad is free", MimirTransitionInputGate.holding)
    }

    @Test
    fun `the card that is being broken paints nothing under the shards, and the gap has not begun to close`() {
        host()
        startClock(shatter(leaving = games[1], focusOn = games[2]))
        advanceTo(200L)

        assertEquals("the broken card is not painted at all: the shards are drawn from a picture of it", 0, count(leavingTag(games[1])))
        assertEquals("and it is no longer the tile of that game", 0, count(libraryShelfTileTestTag(games[1])))
        assertEquals("the survivor waits one place off the centre", 1f, cardSeat(games[2]), 0.06f)
        assertEquals("and so does the card after it", 2f, cardSeat(games[3]), 0.06f)
    }

    @Test
    fun `the hero holds the game that left to 300 ms, then lets go while the gap closes`() {
        host()
        startClock(shatter(leaving = games[1], focusOn = games[2]))

        advanceTo(200L)
        assertTrue("held at 200 ms", latest?.departure?.holdsHero == true)

        advanceTo(340L)
        assertFalse("released by 340 ms", latest?.departure?.holdsHero == true)
        assertTrue("and the gap has begun to close: ${cardSeat(games[2])}", cardSeat(games[2]) < 0.99f && cardSeat(games[2]) > 0.3f)
        assertTrue("the pad is still held while it does", MimirTransitionInputGate.holding)
    }

    @Test
    fun `a SELECT un-star publishes no job, so it keeps the house dissolve exactly`() {
        host()
        sendAway(leaving = games[1], focusOn = games[2])
        compose.mainClock.advanceTimeByFrame()

        assertEquals(GamelistDepartureKind.Dissolve, latest?.departure?.kind)
        assertTrue("the card is drawn and fading, not skipped: ${alpha(leavingTag(games[1]))}", alpha(leavingTag(games[1])) > 0.85f)
        assertEquals("no overlay", 0, count(LibraryShatterOverlayTag))
        assertTrue("the hero is held for the dissolve", latest?.departure?.holdsHero == true)

        compose.mainClock.advanceTimeBy(MimirMotion.OverlayMs + 100L)
        assertEquals("gone on the overlay clock, not the shatter's", 0, count(leavingTag(games[1])))
        assertEquals(0f, cardSeat(games[2]), 0.01f)
        assertFalse(MimirTransitionInputGate.holding)
    }

    @Test
    fun `a finished job is cleared, and a newer one is not disturbed by an older one finishing`() {
        val first = LibraryShatterJob(ImageBitmap(1, 1), Rect(0f, 0f, 1f, 1f), "g1")
        val second = LibraryShatterJob(first.bitmap, first.bounds, "g2")
        stage.job = first
        assertTrue(stage.shatters(games[1]))
        stage.job = second
        stage.finish(first)
        assertEquals("the older job finishing leaves the newer one", second, stage.job)
        stage.finish(second)
        assertNull(stage.job)
    }
}
