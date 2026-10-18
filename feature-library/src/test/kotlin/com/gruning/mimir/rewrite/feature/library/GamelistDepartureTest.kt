package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.geometry.Rect
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Walk 32g: what counts as a departure and where it seats, and the small tracker that carries one across recompositions.
 * All of it is decided from what the screen shows, so it is checked here without a screen; [GamelistDepartureMotionTest]
 * runs the same wiring in Compose.
 */
class GamelistDepartureTest {
    private fun game(id: String, favorite: Boolean = false) = GameRow(
        gameId = GameId(id),
        title = id,
        platformId = PlatformId.PS1,
        memberCount = 1,
        duplicateKind = null,
        isFavorite = favorite,
    )

    private val a = game("a")
    private val b = game("b")
    private val c = game("c")
    private val d = game("d")

    private fun leaves(vararg ids: GameRow): (GameRow) -> Boolean = { row -> ids.any { it.gameId == row.gameId } }

    /** JUnit's assertNotNull returns nothing; a test that goes on to use the value needs it back. */
    private fun <T : Any> present(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    // ---------------------------------------------------------------------------------- what counts as a removal

    @Test
    fun `a game gone from the middle is one removal at the index it stood at`() {
        val removal = singleGamelistRemoval(listOf(a, b, c, d), listOf(a, c, d), leaves(b))

        assertEquals(GamelistRemoval(b, 1), removal)
    }

    @Test
    fun `the first game and the last game are removals too`() {
        assertEquals(GamelistRemoval(a, 0), singleGamelistRemoval(listOf(a, b, c), listOf(b, c), leaves(a)))
        assertEquals(GamelistRemoval(c, 2), singleGamelistRemoval(listOf(a, b, c), listOf(a, b), leaves(c)))
    }

    @Test
    fun `a game the player did not send is not a departure`() {
        assertNull(singleGamelistRemoval(listOf(a, b, c), listOf(a, c), leaves(c)))
        assertNull(singleGamelistRemoval(listOf(a, b, c), listOf(a, c)) { false })
    }

    @Test
    fun `anything but exactly one game missing is not a removal`() {
        val everyone = leaves(a, b, c, d)

        assertNull("nothing changed", singleGamelistRemoval(listOf(a, b, c), listOf(a, b, c), everyone))
        assertNull("two games", singleGamelistRemoval(listOf(a, b, c, d), listOf(a, d), everyone))
        assertNull("one game and another arriving", singleGamelistRemoval(listOf(a, b, c), listOf(a, d, c), everyone))
        assertNull("reordered", singleGamelistRemoval(listOf(a, b, c), listOf(c, a), everyone))
        assertNull("a game added", singleGamelistRemoval(listOf(a, b), listOf(a, b, c), everyone))
        assertNull("emptied: nothing left to close a gap", singleGamelistRemoval(listOf(a), emptyList(), everyone))
    }

    @Test
    fun `only identity and order count, so a refreshed row is the same row`() {
        val refreshed = a.copy(title = "A, remastered", isFavorite = true)

        assertEquals(GamelistRemoval(b, 1), singleGamelistRemoval(listOf(a, b, c), listOf(refreshed, c), leaves(b)))
    }

    // ------------------------------------------------------------------------------------------ what can be seated

    @Test
    fun `the centred card leaving and focus landing on its successor is seated`() {
        val removal = GamelistRemoval(b, 1)

        assertTrue(gamelistDepartureSeats(removal, sizeBefore = 4, centredBefore = 1, centredAfter = 1))
    }

    @Test
    fun `the last card leaving and focus landing on the one before it is seated`() {
        val removal = GamelistRemoval(d, 3)

        assertTrue(gamelistDepartureSeats(removal, sizeBefore = 4, centredBefore = 3, centredAfter = 2))
    }

    @Test
    fun `a card the player was not on leaving is left to cut`() {
        val removal = GamelistRemoval(b, 1)

        assertFalse(gamelistDepartureSeats(removal, sizeBefore = 4, centredBefore = 2, centredAfter = 1))
        assertFalse(gamelistDepartureSeats(removal, sizeBefore = 4, centredBefore = 0, centredAfter = 0))
    }

    @Test
    fun `focus landing anywhere but beside the leaver is left to cut`() {
        val removal = GamelistRemoval(b, 1)

        // Fell through to game one instead of the survivor: the seated motion would close a gap around the wrong card.
        assertFalse(gamelistDepartureSeats(removal, sizeBefore = 4, centredBefore = 1, centredAfter = 0))
        // The predecessor is only the landing for the LAST card: a middle card's landing is its successor.
        assertFalse(gamelistDepartureSeats(removal, sizeBefore = 4, centredBefore = 1, centredAfter = 2))
    }

    @Test
    fun `a list of one has no card to close over its leaver`() {
        assertFalse(gamelistDepartureSeats(GamelistRemoval(a, 0), sizeBefore = 1, centredBefore = 0, centredAfter = 0))
    }

    // ------------------------------------------------------------------------------------------ which card is centred

    @Test
    fun `the focused key names the centre, and an unresolved focus centres game one`() {
        val games = listOf(a, b, c)

        assertEquals(2, gamelistCentredIndex(games, gameKey(c), getArtFocused = false, detailGame = null))
        assertEquals(0, gamelistCentredIndex(games, gameKey(game("gone")), getArtFocused = false, detailGame = null))
        assertEquals(0, gamelistCentredIndex(games, null, getArtFocused = false, detailGame = null))
    }

    @Test
    fun `the artwork pill keeps its game centred`() {
        val games = listOf(a, b, c)

        assertEquals(1, gamelistCentredIndex(games, GamelistGetArtKey, getArtFocused = true, detailGame = b))
        assertEquals(0, gamelistCentredIndex(games, GamelistGetArtKey, getArtFocused = true, detailGame = game("gone")))
        assertEquals(0, gamelistCentredIndex(games, GamelistGetArtKey, getArtFocused = true, detailGame = null))
    }

    // ------------------------------------------------------------------------------------------------ the seat maths

    @Test
    fun `at the start of a departure every card that stays sits exactly where it sat before`() {
        // Before: [a, b, c, d] centred on b (index 1). After: [a, c, d], centre still 1, gap 1.
        val centre = 1f
        val before = listOf(-1f, 0f, 1f, 2f)
        val after = listOf(
            gamelistDepartureSeat(0, centre, departureIndex = 1, gap = 1f), // a
            gamelistDepartureSeat(1, centre, departureIndex = 1, gap = 1f), // c, the survivor
            gamelistDepartureSeat(2, centre, departureIndex = 1, gap = 1f), // d
        )

        assertEquals(listOf(before[0], before[2], before[3]), after)
    }

    @Test
    fun `at the end of a departure every card sits where the new list puts it`() {
        val centre = 1f

        assertEquals(-1f, gamelistDepartureSeat(0, centre, departureIndex = 1, gap = 0f), 0f)
        assertEquals(0f, gamelistDepartureSeat(1, centre, departureIndex = 1, gap = 0f), 0f)
        assertEquals(1f, gamelistDepartureSeat(2, centre, departureIndex = 1, gap = 0f), 0f)
    }

    @Test
    fun `cards before the leaver never move, and the gap closes evenly for every card after it`() {
        val centre = 1f

        for (gap in listOf(1f, 0.75f, 0.5f, 0.25f, 0f)) {
            assertEquals(-1f, gamelistDepartureSeat(0, centre, departureIndex = 1, gap = gap), 0f)
            assertEquals(0f + gap, gamelistDepartureSeat(1, centre, departureIndex = 1, gap = gap), 0f)
            assertEquals(1f + gap, gamelistDepartureSeat(2, centre, departureIndex = 1, gap = gap), 0f)
        }
    }

    @Test
    fun `with no departure a card sits at its index minus the centre, as it always did`() {
        assertEquals(2.5f, gamelistDepartureSeat(4, 1.5f, departureIndex = null, gap = 1f), 0f)
    }

    // ------------------------------------------------------------------------------------------------- the tracker

    private class Harness(private val tracker: GamelistDepartureTracker = GamelistDepartureTracker()) {
        var scope: Any? = "ps1"
        var motion = true

        /** Observe and commit, the way a composition and its SideEffect do. */
        fun show(
            games: List<GameRow>,
            centred: Int,
            leaving: (GameRow) -> Boolean = { false },
        ): GamelistDepartureFrame = observe(games, centred, leaving).also { tracker.commit() }

        fun observe(games: List<GameRow>, centred: Int, leaving: (GameRow) -> Boolean = { false }) =
            tracker.observe(games, centred, scope, leaving, motion)

        val bounds get() = tracker.centreBounds
    }

    @Test
    fun `the first list shown is not a departure`() {
        val harness = Harness()

        val frame = harness.show(listOf(a, b, c), centred = 0)

        assertNull(frame.departure)
    }

    @Test
    fun `a sent game leaving the centre is a departure, and the lineage outlasts it`() {
        val harness = Harness()
        val before = harness.show(listOf(a, b, c, d), centred = 1)

        val frame = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        val departure = present(frame.departure)
        assertEquals(b, departure.game)
        assertEquals(1, departure.index)
        assertSame("the centre is not re-created by a removal", before.lineage, frame.lineage)
    }

    @Test
    fun `the departure stays for the frames after the one that showed it, and is the same one`() {
        val harness = Harness()
        harness.show(listOf(a, b, c, d), centred = 1)
        val first = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        // A later recomposition of the same list: the pending entry has been settled, the rows were refreshed.
        val later = harness.show(listOf(a.copy(title = "A2"), c, d), centred = 1)

        assertSame(first.departure, later.departure)
        assertSame(first.lineage, later.lineage)
    }

    @Test
    fun `observing the same composition twice before it commits gives one departure`() {
        val harness = Harness()
        harness.show(listOf(a, b, c, d), centred = 1)
        val shown = listOf(a, c, d)

        val first = harness.observe(shown, centred = 1, leaving = leaves(b))
        val second = harness.observe(shown, centred = 1, leaving = leaves(b))

        assertSame(first.departure, second.departure)
        assertSame(first.lineage, second.lineage)
    }

    @Test
    fun `a composition that is abandoned leaves no trace`() {
        val harness = Harness()
        val before = harness.show(listOf(a, b, c, d), centred = 1)

        // Observed but never committed.
        harness.observe(listOf(a, c, d), centred = 1, leaving = leaves(b))
        val next = harness.show(listOf(a, b, c, d), centred = 1)

        assertNull(next.departure)
        assertSame(before.lineage, next.lineage)
    }

    @Test
    fun `the last card leaving is a departure that lands on its predecessor`() {
        val harness = Harness()
        harness.show(listOf(a, b, c), centred = 2)

        val frame = harness.show(listOf(a, b), centred = 1, leaving = leaves(c))

        assertEquals(c, present(frame.departure).game)
    }

    @Test
    fun `a removal the player did not send is a cut with a new identity`() {
        val harness = Harness()
        val before = harness.show(listOf(a, b, c, d), centred = 1)

        val frame = harness.show(listOf(a, c, d), centred = 1)

        assertNull(frame.departure)
        assertNotSame("the list changed, so the centre is re-created as it always was", before.lineage, frame.lineage)
    }

    @Test
    fun `a removal beside a card the player was not on is a cut`() {
        val harness = Harness()
        val before = harness.show(listOf(a, b, c, d), centred = 2)

        val frame = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        assertNull(frame.departure)
        assertNotSame(before.lineage, frame.lineage)
    }

    @Test
    fun `focus that fell through to game one is a cut, not a gap closed around the wrong card`() {
        val harness = Harness()
        val before = harness.show(listOf(a, b, c, d), centred = 2)

        val frame = harness.show(listOf(a, b, d), centred = 0, leaving = leaves(c))

        assertNull(frame.departure)
        assertNotSame(before.lineage, frame.lineage)
    }

    @Test
    fun `reduced motion is a cut`() {
        val harness = Harness().apply { motion = false }
        val before = harness.show(listOf(a, b, c, d), centred = 1)

        val frame = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        assertNull(frame.departure)
        assertNotSame(before.lineage, frame.lineage)
    }

    @Test
    fun `a change of list under the same games is not a departure`() {
        val harness = Harness()
        val before = harness.show(listOf(a, b, c, d), centred = 1)
        harness.scope = "ps2"

        val frame = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        assertNull(frame.departure)
        assertNotSame(before.lineage, frame.lineage)
    }

    @Test
    fun `refreshed rows with the same identities keep the identity and start nothing`() {
        val harness = Harness()
        val before = harness.show(listOf(a, b, c), centred = 1)

        val frame = harness.show(listOf(a.copy(title = "A2"), b.copy(isFavorite = true), c), centred = 1)

        assertNull(frame.departure)
        assertSame(before.lineage, frame.lineage)
    }

    @Test
    fun `a game arriving mid-departure ends it and changes the identity`() {
        val harness = Harness()
        harness.show(listOf(a, b, c, d), centred = 1)
        val departing = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        val frame = harness.show(listOf(a, c, d, game("e")), centred = 1)

        assertNull(frame.departure)
        assertNotSame(departing.lineage, frame.lineage)
    }

    @Test
    fun `a finished departure is dropped on the next composition and the identity stays`() {
        val harness = Harness()
        harness.show(listOf(a, b, c, d), centred = 1)
        val departing = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        present(departing.departure).finished = true
        val after = harness.show(listOf(a, c, d), centred = 1)

        assertNull(after.departure)
        assertSame(departing.lineage, after.lineage)
        assertNull("and it stays dropped", harness.show(listOf(a, c, d), centred = 1).departure)
    }

    @Test
    fun `the very same list recomposed after the departure finished drops it too`() {
        // The screen hands the same list instance to every recomposition until the list changes. What was decided for it
        // in the frame that seated the departure must not be replayed: the recomposition that `finished` triggers has to
        // read it again, or the card would be left on screen at alpha 0 until something else changed.
        val harness = Harness()
        harness.show(listOf(a, b, c, d), centred = 1)
        val shown = listOf(a, c, d)
        val departing = harness.show(shown, centred = 1, leaving = leaves(b))
        assertSame("still running: the same departure on the same list", departing.departure, harness.show(shown, centred = 1).departure)

        present(departing.departure).finished = true
        val after = harness.show(shown, centred = 1)

        assertNull(after.departure)
        assertSame(departing.lineage, after.lineage)
    }

    @Test
    fun `a second removal while one is still running replaces it`() {
        val harness = Harness()
        harness.show(listOf(a, b, c, d), centred = 1)
        val first = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        // c is now centred at index 1 and is sent away too.
        val second = harness.show(listOf(a, d), centred = 1, leaving = leaves(c))

        assertNotNull(first.departure)
        assertEquals(c, present(second.departure).game)
        assertNotSame(first.departure, second.departure)
        assertSame("the same lineage: neither was a change of list", first.lineage, second.lineage)
    }

    @Test
    fun `the departure carries the last bounds of the centred card`() {
        val harness = Harness()
        harness.show(listOf(a, b, c, d), centred = 1)
        val seat = Rect(120f, 40f, 320f, 360f)
        harness.bounds.value = seat

        val frame = harness.show(listOf(a, c, d), centred = 1, leaving = leaves(b))

        assertEquals(seat, present(frame.departure).lastBounds)
    }

    // ------------------------------------------------------------------------------------------ the departure itself

    @Test
    fun `a departure starts unfinished with its whole gap to close, and closes it as its progress runs`() {
        val departure = GamelistDeparture(b, index = 1)

        assertFalse(departure.finished)
        assertEquals(1f, departure.gap(), 0f)
    }
}
