package com.gruning.mimir.rewrite.core.companionnative

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perf 2026-09-11 §4.4a: dragging the open ᛗ menu card drew 10 frames in 585 ms on the Thor. Every ACTION_MOVE
 * wrote the card's margins, a full measure and layout of a card whose coins build gradient shaders in onDraw.
 * The drag now moves the card by translation and commits the margins once, on UP or CANCEL.
 *
 * The gesture has no JVM-reachable observable, so its wiring is pinned as source (the reason
 * [CompanionOverlayMenuBackdropTest] gives); the position arithmetic is tested directly.
 */
class SecondaryMenuCardDragTest {

    private val code: String by lazy {
        val file = File(
            "src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/" +
                "AndroidCompanionOverlayController.kt",
        )
        assertTrue("AndroidCompanionOverlayController.kt must be readable", file.isFile)
        file.readText()
    }

    @Test
    fun `the effective position is the committed margins plus the translation not yet folded in`() {
        assertEquals(100 to 50, secondaryMenuCardEffectivePosition(100, 50, 0f, 0f))
        assertEquals(160 to 10, secondaryMenuCardEffectivePosition(100, 50, 60f, -40f))
        assertEquals(101 to 49, secondaryMenuCardEffectivePosition(100, 50, 0.6f, -0.6f))
    }

    @Test
    fun `a committed drag lands exactly where the translated card was drawn`() {
        val margins = 100 to 50
        val target = clampCompanionOverlayPosition(x = 900, y = -20, bounds = 1280 to 720, width = 300, height = 200)
        val drawn = secondaryMenuCardEffectivePosition(
            leftMargin = margins.first,
            topMargin = margins.second,
            translationX = (target.first - margins.first).toFloat(),
            translationY = (target.second - margins.second).toFloat(),
        )
        assertEquals(900 to 0, target)
        assertEquals(target, drawn)
    }

    @Test
    fun `a move translates the card and never lays it out`() {
        val drag = code.substringAfter("private fun handleMenuDrag(", "")
        val move = drag.substringAfter("MotionEvent.ACTION_MOVE ->", "").substringBefore("MotionEvent.ACTION_UP", "")
        assertTrue(
            "the card follows the finger by translation",
            "card.translationX =" in move && "card.translationY =" in move,
        )
        assertFalse("a layout per move is the 15 fps drag", "layoutParams" in move)
        val release = drag.substringAfter("MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->", "")
            .substringBefore("return true", "")
        assertTrue(
            "UP and CANCEL fold the translation into the margins before the position is saved",
            "commitCardTranslation(card, cardLp)" in release,
        )
    }

    @Test
    fun `a rebuild mid-drag reads where the card is drawn, not its stale margins`() {
        val reader = code.substringAfter("private fun currentSecondaryMenuCardPosition()", "")
            .substringBefore("private fun ", "")
        assertTrue("effectiveCardPosition(" in reader)
    }

    @Test
    fun `the card keeps the overlay inset from every edge, while the coin's own paths keep their plain clamp`() {
        val build = code.substringAfter("private fun buildMenu(", "").substringBefore("val book = FrameLayout(context)", "")
        assertTrue("the opening card is clamped with the inset", "clampCompanionMenuCardPosition(" in build && "inset = dp(context, MButtonSafeInsetDp)" in build)
        val drag = code.substringAfter("private fun handleMenuDrag(", "")
        val move = drag.substringAfter("MotionEvent.ACTION_MOVE ->", "").substringBefore("MotionEvent.ACTION_UP", "")
        assertTrue("and so is the card while it is dragged", "clampCompanionMenuCardPosition(" in move && "inset = dp(context, MButtonSafeInsetDp)" in move)
        val coin = code.substringAfter("private fun loadButtonPosition(", "").substringBefore("private fun loadMenuPosition(", "")
        assertFalse("where the coin rests is wherever it was dropped: its own load never takes the card's inset", "clampCompanionMenuCardPosition" in coin)
    }
}
