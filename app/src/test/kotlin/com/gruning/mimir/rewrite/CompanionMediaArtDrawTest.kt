package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Activity media art mirrors Main's music panel: the `surfaceHigh` plate and the glyph
 * are for NO_ART only; LOADING and ERROR draw nothing; a ready bitmap draws without the plate and fades in. The receipt's
 * `plate=visible` is the plate this site actually draws, so a track whose art is still loading never reads as a breach.
 */
class CompanionMediaArtDrawTest {

    @Test
    fun `the plate is for no art only`() {
        assertTrue(companionMediaPlateVisible(CompanionMediaArtworkLoadState.NO_ART, hasPainter = false))
        assertFalse(companionMediaPlateVisible(CompanionMediaArtworkLoadState.LOADING, hasPainter = false))
        assertFalse(companionMediaPlateVisible(CompanionMediaArtworkLoadState.ERROR, hasPainter = false))
        assertFalse(companionMediaPlateVisible(CompanionMediaArtworkLoadState.READY, hasPainter = true))
    }

    @Test
    fun `a retained painter never wears the plate, whatever the state says`() {
        CompanionMediaArtworkLoadState.values().forEach { state ->
            assertFalse("$state with a painter", companionMediaPlateVisible(state, hasPainter = true))
        }
    }

    @Test
    fun `loading reads hasArt true and plate hidden`() {
        assertEquals(
            CompanionMediaArtReceiptFacts(hasArt = true, loaded = false, failed = false, plateVisible = false),
            companionMediaArtReceiptFacts(CompanionMediaArtworkLoadState.LOADING, hasPainter = false),
        )
    }

    @Test
    fun `a failed load reads hasArt true state failed and plate hidden, no breach`() {
        assertEquals(
            CompanionMediaArtReceiptFacts(hasArt = true, loaded = false, failed = true, plateVisible = false),
            companionMediaArtReceiptFacts(CompanionMediaArtworkLoadState.ERROR, hasPainter = false),
        )
    }

    @Test
    fun `no art reads hasArt false and plate visible`() {
        assertEquals(
            CompanionMediaArtReceiptFacts(hasArt = false, loaded = false, failed = false, plateVisible = true),
            companionMediaArtReceiptFacts(CompanionMediaArtworkLoadState.NO_ART, hasPainter = false),
        )
    }

    @Test
    fun `a ready bitmap reads loaded and plate hidden`() {
        assertEquals(
            CompanionMediaArtReceiptFacts(hasArt = true, loaded = true, failed = false, plateVisible = false),
            companionMediaArtReceiptFacts(CompanionMediaArtworkLoadState.READY, hasPainter = true),
        )
    }

    @Test
    fun `no state of the media art is a breach`() {
        CompanionMediaArtworkLoadState.values().forEach { state ->
            listOf(true, false).forEach { painter ->
                val facts = companionMediaArtReceiptFacts(state, painter)
                assertFalse("$state painter=$painter", facts.hasArt && facts.plateVisible)
            }
        }
    }

    @Test
    fun `a deleted local file is no art, not an error`() {
        // R8a: the file is gone, so the track has NO art and keeps the plate and glyph; the painter's own error for it (which can land
        // before the off-main stat does) must not turn it into a failure that draws nothing.
        listOf(true, false).forEach { painterError ->
            assertEquals(
                "painterError=$painterError",
                CompanionMediaArtworkLoadState.NO_ART,
                companionMediaArtworkCandidateState(
                    hasDirectPainter = false,
                    localFileGone = true,
                    asyncSuccess = false,
                    asyncError = painterError,
                    trackState = CompanionMediaArtworkLoadState.LOADING,
                ),
            )
        }
    }

    @Test
    fun `an error for a file that is there is a failure, which draws nothing`() {
        assertEquals(
            CompanionMediaArtworkLoadState.ERROR,
            companionMediaArtworkCandidateState(
                hasDirectPainter = false,
                localFileGone = false,
                asyncSuccess = false,
                asyncError = true,
                trackState = null,
            ),
        )
        assertFalse(companionMediaPlateVisible(CompanionMediaArtworkLoadState.ERROR, hasPainter = false))
    }

    @Test
    fun `a deleted artwork drops the painter remembered as settled for that track, and leaves every other track's`() {
        // R8a: track A settled, its file was deleted, A returns. The remembered READY painter must not survive the NO_ART verdict.
        val memory = CompanionMediaArtworkMemory()
        val a = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Red)
        val b = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Blue)
        memory.settledPainters["a"] = a
        memory.settledPainters["b"] = b
        memory.requestKey = "a"
        memory.displayedRequestKey = "a"
        memory.displayedPainter = a
        memory.displayedState = CompanionMediaArtworkLoadState.READY

        memory.forget("a")

        assertEquals("the settled painter is gone", null, memory.settledPainters["a"])
        assertEquals("nothing on display reads READY for it", null, memory.displayedPainter)
        assertEquals(null, memory.displayedRequestKey)
        assertEquals(CompanionMediaArtworkLoadState.NO_ART, memory.displayedState)
        assertEquals("another track's remembered art is not touched", b, memory.settledPainters["b"])
        assertEquals("the request itself stays current", "a", memory.requestKey)
    }

    @Test
    fun `forgetting a track that is not on display leaves the display alone`() {
        val memory = CompanionMediaArtworkMemory()
        val shown = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Green)
        memory.settledPainters["shown"] = shown
        memory.settledPainters["gone"] = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.Red)
        memory.displayedRequestKey = "shown"
        memory.displayedPainter = shown
        memory.displayedState = CompanionMediaArtworkLoadState.READY

        memory.forget("gone")

        assertEquals(null, memory.settledPainters["gone"])
        assertEquals(shown, memory.displayedPainter)
        assertEquals(CompanionMediaArtworkLoadState.READY, memory.displayedState)
    }
}
