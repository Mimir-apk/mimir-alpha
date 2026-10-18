package com.gruning.mimir.rewrite.core.ui

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focus ring and its flare match the onboarding pace product-wide: the
 * ring settles on 280 ms and gleams on 360 ms when focus lands, a held D-pad keeps the fast clock, and only the ring
 * slows (not the scale or dimming beside it).
 */
class MimirFocusRingClockTest {
    private var now = 0L

    @After
    fun restore() {
        MimirFocusTraversal.reset()
        MimirFocusTraversal.clock = { android.os.SystemClock.uptimeMillis() }
    }

    private fun useClock() {
        MimirFocusTraversal.clock = { now }
    }

    @Test
    fun theRingSettlesAtTwoAndAHalfTimesTheFocusResponseAndTheGleamAtTwoAndAHalfTimesTheTransientBound() {
        // The ring clock increased to 2x, then by another 0.5x.
        assertEquals(420, MimirFocusRingSettleMs)
        assertEquals(MimirMotion.FocusMs * 3, MimirFocusRingSettleMs)
        assertEquals(540, MimirFocusGlintMs)
    }

    @Test
    fun aMoveCountsFromItsOwnPressTime() {
        // Walk-32b: the Files listing asks whether the player moved since an arrival began.
        assertFalse("no move yet", MimirFocusTraversal.movedSince(0L))
        MimirFocusTraversal.onDirectionalMove(1_000L)
        assertTrue(MimirFocusTraversal.movedSince(1_000L))
        assertTrue(MimirFocusTraversal.movedSince(900L))
        assertFalse("pressed before the arrival began", MimirFocusTraversal.movedSince(1_001L))
    }

    @Test
    fun aHoldIsATraverseFromItsFirstRepeat() {
        // InputTuning.directionalRepeatDelayMs (core-input, which core-ui cannot see): the first repeat of a hold.
        val firstRepeatMs = 260L
        assertTrue(MimirFocusTraversalStreamMs > firstRepeatMs)
        useClock()
        now = 1_000L
        MimirFocusTraversal.onDirectionalMove(now)
        now += firstRepeatMs
        MimirFocusTraversal.onDirectionalMove(now)
        now += 16L
        assertTrue(MimirFocusTraversal.streaming)
        assertEquals(MimirMotion.FocusMs, mimirFocusRingMs())
    }

    @Test
    fun beforeAnyMoveTheRingSettlesSlowAndTheClockIsNeverRead() {
        MimirFocusTraversal.clock = { error("the clock must not be read before a move") }
        assertFalse(MimirFocusTraversal.streaming)
        assertEquals(MimirFocusRingSettleMs, mimirFocusRingMs())
        assertEquals(MimirFocusRingSettleMs, mimirFocusRingSpec<Float>().durationMillis)
    }

    @Test
    fun aLonePressOrTwoUnhurriedOnesSettleSlow() {
        useClock()
        now = 5_000L
        MimirFocusTraversal.onDirectionalMove(now)
        now += 16L
        assertFalse("one press is not a traverse", MimirFocusTraversal.streaming)
        // A whole settle clock after the first press (the window follows the ring's clock as it slows).
        now += MimirFocusTraversalStreamMs
        MimirFocusTraversal.onDirectionalMove(now)
        now += 16L
        assertFalse("two presses a whole settle clock apart are not a traverse", MimirFocusTraversal.streaming)
        assertEquals(MimirFocusRingSettleMs, mimirFocusRingMs())
    }

    @Test
    fun aFocusChangeAfterAHoldEndsSettlesSlow() {
        // Judged at read time: a touch or a programmatic focus change after the hold must not inherit it.
        useClock()
        now = 9_000L
        MimirFocusTraversal.onDirectionalMove(now)
        now += 120L
        MimirFocusTraversal.onDirectionalMove(now)
        now += 500L
        assertFalse(MimirFocusTraversal.streaming)
        assertEquals(MimirFocusRingSettleMs, mimirFocusRingMs())
    }

    @Test
    fun aStampFromTheWrongSideOfTheClockIsNotATraverse() {
        useClock()
        now = 2_000L
        MimirFocusTraversal.onDirectionalMove(now)
        MimirFocusTraversal.onDirectionalMove(now - 50L)
        assertFalse(MimirFocusTraversal.streaming)
    }

    @Test
    fun onlyTheRingsOwnChannelsTakeTheRingClock() {
        fun source(name: String) = listOf(
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/$name"),
        ).first(File::isFile).readText()
        fun block(text: String, label: String) = text.substringBefore("label = \"$label\"").takeLast(260)

        val tokens = source("MimirTokens.kt")
        for (ring in listOf("focusRingColor", "focusRingWidth")) {
            assertTrue(ring, block(tokens, ring).contains("animationSpec = mimirFocusRingSpec()"))
        }
        for (rider in listOf("focusRingScale", "focusRingAlpha")) {
            assertTrue(rider, block(tokens, rider).contains("tween(MimirFocusRingDurationMs, easing = MimirFocusEasing)"))
        }
        val glass = source("MimirLiquidGlass.kt")
        for (ring in listOf("glassRowRingColor", "glassRowRingWidth")) {
            assertTrue(ring, block(glass, ring).contains("animationSpec = mimirFocusRingSpec()"))
        }
        for (rider in listOf("glassRowAlpha", "glassRowFill")) {
            assertTrue(rider, block(glass, rider).contains("tween(MimirFocusRingDurationMs, easing = MimirFocusEasing)"))
        }
        val book = source("MimirNorseBook.kt")
        assertTrue(block(book, "mimir-norse-book-focus-ring").contains("animationSpec = mimirFocusRingSpec()"))
        assertTrue(block(book, "mimir-norse-book-scale").contains("tween(MimirMotion.FocusMs"))
        val rail = source("MimirRunicQuickRail.kt")
        assertTrue(rail.contains("ringPresence.animateTo(0f, quickRailRingDepartureSpec())"))
        assertFalse("the dock glide reads the ring clock", rail.contains("QuickRailGlideSpec"))
    }
}
