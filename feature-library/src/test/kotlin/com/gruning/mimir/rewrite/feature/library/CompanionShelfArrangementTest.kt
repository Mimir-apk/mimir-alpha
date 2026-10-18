package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Arrange-and-drop on the companion shelf — policy 2026-09-01, bringing the tome's system there.
 *
 * The gesture cannot be unit-tested; the two rules it stands on can, and they are the ones that go wrong
 * quietly: what a filed order does with games it does not name, and whether a move lands where the finger
 * is in BOTH directions.
 */
class CompanionShelfArrangementTest {

    private fun game(id: String) = GameRow(
        gameId = GameId(id),
        title = id.uppercase(),
        platformId = PlatformId("psx"),
        memberCount = 1,
        duplicateKind = null,
    )

    private val rows = listOf(game("a"), game("b"), game("c"), game("d"))

    private fun ids(list: List<GameRow>) = list.map { it.gameId.value }

    @Test
    fun anUnarrangedShelfIsProducerOrder() {
        assertEquals(rows, companionShelfArrangement(rows, emptyList()))
    }

    @Test
    fun filedOrderLeadsAndUnfiledGamesFollowInProducerOrder() {
        // "c, a" were filed; "b, d" have never been touched and must NOT be interleaved into positions
        // nobody chose — a scan landing new games must not scatter them through a hand-filed shelf.
        assertEquals(listOf("c", "a", "b", "d"), ids(companionShelfArrangement(rows, listOf("c", "a"))))
    }

    @Test
    fun aFiledGameThatIsGoneIsSkippedRatherThanBreakingTheRest() {
        // The store never prunes, so a game whose source is unmounted stays in the file. It must simply
        // not appear — and everything filed after it must keep its relative order.
        assertEquals(
            listOf("d", "b", "a", "c"),
            ids(companionShelfArrangement(rows, listOf("gone", "d", "b", "a"))),
        )
    }

    @Test
    fun aDuplicateInTheFileCannotDuplicateAGame() {
        // Two records naming the same id must not paint the tile twice; the grid is keyed by game id and
        // a duplicate key is a crash, not a cosmetic fault.
        assertEquals(listOf("b", "a", "c", "d"), ids(companionShelfArrangement(rows, listOf("b", "b", "a"))))
    }

    @Test
    fun aMoveLandsWhereTheFingerIsInBothDirections() {
        // The classic off-by-one: forwards and backwards drags disagree if the removal is not accounted
        // for. Invisible in review, obvious on glass.
        assertEquals(listOf("b", "c", "a", "d"), ids(rows.companionArrangeMove(0, 2)))
        assertEquals(listOf("a", "d", "b", "c"), ids(rows.companionArrangeMove(3, 1)))
    }

    @Test
    fun backSwipeIsQuickHorizontalAndLeftwardOnly() {
        assertEquals(
            true,
            companionShelfBackSwipeAccepted(
                deltaX = -80f,
                deltaY = 4f,
                elapsedMillis = 320L,
                touchSlopPx = 8f,
                triggerDistancePx = 48f,
                longPressMillis = 500L,
            ),
        )
        assertEquals(
            false,
            companionShelfBackSwipeAccepted(
                deltaX = -80f,
                deltaY = 4f,
                elapsedMillis = 500L,
                touchSlopPx = 8f,
                triggerDistancePx = 48f,
                longPressMillis = 500L,
            ),
        )
        assertEquals(
            false,
            companionShelfBackSwipeAccepted(
                deltaX = -12f,
                deltaY = -80f,
                elapsedMillis = 120L,
                touchSlopPx = 8f,
                triggerDistancePx = 48f,
            ),
        )
        assertEquals(
            false,
            companionShelfBackSwipeAccepted(
                deltaX = 80f,
                deltaY = 4f,
                elapsedMillis = 120L,
                touchSlopPx = 8f,
                triggerDistancePx = 48f,
            ),
        )
    }

    // R6-P1-10: the mirror edge-back predicate adds one gate on top of the shelf's own back-swipe shape —
    // the down must land within the right-edge band. A stroke starting outside it is rejected outright, and
    // a mere tap (no leftward distance) inside it is rejected the same way the shelf swipe already is.
    @Test
    fun mirrorEdgeBackAcceptsOnlyAnInwardStrokeStartingInTheRightEdgeBand() {
        // Inward stroke, down inside the 32dp band on a 200px-wide window (down at x=190, band=32).
        assertEquals(
            true,
            companionMirrorEdgeBackSwipeAccepted(
                downX = 190f,
                widthPx = 200f,
                edgeDistancePx = 32f,
                triggerDistancePx = 40f,
                deltaX = -60f,
                deltaY = 4f,
                elapsedMillis = 120L,
                touchSlopPx = 8f,
            ),
        )
        // A tap: negligible movement, so it never reaches the trigger distance.
        assertEquals(
            false,
            companionMirrorEdgeBackSwipeAccepted(
                downX = 190f,
                widthPx = 200f,
                edgeDistancePx = 32f,
                triggerDistancePx = 40f,
                deltaX = -2f,
                deltaY = 1f,
                elapsedMillis = 80L,
                touchSlopPx = 8f,
            ),
        )
        // Same inward stroke, but the down started outside the band (x=100 on a 200px window).
        assertEquals(
            false,
            companionMirrorEdgeBackSwipeAccepted(
                downX = 100f,
                widthPx = 200f,
                edgeDistancePx = 32f,
                triggerDistancePx = 40f,
                deltaX = -60f,
                deltaY = 4f,
                elapsedMillis = 120L,
                touchSlopPx = 8f,
            ),
        )
    }

    // ── The seat mapping () ─────────

    private val grid = solveCompanionShelfGrid(
        availableWidth = 1000f,
        pageHeight = 900f,
        columns = 3,
        rows = 3,
        gap = 20f,
        count = 9,
    )

    @Test
    fun theGridFillsTheWidthAndThePageHeightExactly() {
        // L4: the step names the columns and rows; the solver cuts the cells to fill the page. 3 columns of (1000 - 2 * 20) / 3, 3 rows of (900 - 2 * 20) / 3.
        assertEquals(3, grid.columns)
        assertEquals(1000f, grid.columns * grid.cellWidth + (grid.columns - 1) * grid.gap, 0.01f)
        assertEquals(900f, grid.rows * grid.cellHeight + (grid.rows - 1) * grid.gap, 0.01f)
        assertEquals(900f, grid.pageHeight, 0.01f)
        // Never zero columns or rows, whatever it is handed — a zero would divide by zero in `seatAt`.
        assertEquals(1, solveCompanionShelfGrid(10f, 10f, 0, 0, 20f, 3).columns)
        assertEquals(1, solveCompanionShelfGrid(10f, 10f, 0, 0, 20f, 3).rows)
    }

    @Test
    fun aSeatMapsToAPositionAndBackToTheSameSeat() {
        // The inverse property, which is the whole reason this replaced hit-testing measured rects.
        (0 until 9).forEach { index ->
            assertEquals("seat $index did not round-trip", index, grid.seatAt(grid.centreOf(index)))
        }
    }

    @Test
    fun theSameQuestionAlwaysGetsTheSameAnswer() {
        // Idempotency, stated as a test because the defect it replaces was precisely that asking twice
        // could answer differently depending on which layout pass had landed.
        val probe = Offset(517f, 349f)
        val first = grid.seatAt(probe)
        repeat(5) { assertEquals(first, grid.seatAt(probe)) }
    }

    @Test
    fun aFingerOutsideTheGridResolvesToTheNearestEndRatherThanNowhere() {
        // Clamped, not null: dragging into the gutter or past the last tile still has an unambiguous
        // intent, and returning nothing would freeze the carry at whatever seat it last resolved.
        assertEquals(0, grid.seatAt(Offset(-500f, -500f)))
        assertEquals(8, grid.seatAt(Offset(5000f, 5000f)))
        assertEquals(2, grid.seatAt(Offset(5000f, 0f)))
    }

    @Test
    fun everyPointInsideASeatBelongsToThatSeat() {
        // Sweep the interior of each cell rather than spot-checking its centre: an off-by-one in the
        // floor() would only show at the edges, which is exactly where a dragging finger lives.
        (0 until 9).forEach { index ->
            val origin = grid.originOf(index)
            listOf(1f, grid.cellWidth / 2f, grid.cellWidth - 1f).forEach { dx ->
                listOf(1f, grid.cellHeight / 2f, grid.cellHeight - 1f).forEach { dy ->
                    assertEquals(
                        "point (+$dx,+$dy) in seat $index",
                        index,
                        grid.seatAt(origin + Offset(dx, dy)),
                    )
                }
            }
        }
    }

    @Test
    fun aMoveOutOfBoundsOrToItselfIsTheSameList() {
        assertEquals(rows, rows.companionArrangeMove(1, 1))
        assertEquals(rows, rows.companionArrangeMove(-1, 2))
        assertEquals(rows, rows.companionArrangeMove(0, 9))
    }
}
