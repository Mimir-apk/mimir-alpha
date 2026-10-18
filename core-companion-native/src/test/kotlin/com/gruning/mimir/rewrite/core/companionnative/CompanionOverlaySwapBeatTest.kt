package com.gruning.mimir.rewrite.core.companionnative

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ᛗ menu's swap on Main's swap tier (replacing the earlier
 * 90 + 90 ms, scale 0.98 / 0.96 beat).
 *
 * Opening or closing the menu from the button swaps two roots of a native window that floats over the game. The outgoing root
 * fades over 180 ms with no scale. At 130 ms, where the house curve has it at alpha 0.044 and nothing of it is left to see, it is
 * removed and the one replacement root is added; that fades in over 180 ms and, when it is the menu, rises 8 dp into place
 * (the button and Main's card-sized menu window would clip it, so they only fade). About 310 ms in all, as every Compose swap in Mimir. Exactly one root is
 * mounted at a time: the controller removes the outgoing window before it adds the incoming one.
 *
 * This module has no project dependencies, so the beat is pinned as the literals it is; the app's
 * `CompanionOverlaySwapSourcePinTest` holds the three values equal to `MimirMotion.OverlayMs`, `OverlayDissolveCoverMs` and
 * `SwapTravelDp`. The beat's behaviour on a real window is the app's Robolectric `CompanionOverlaySwapMotionTest`.
 */
class CompanionOverlaySwapBeatTest {

    private val source = listOf(
        File("core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt"),
    ).first(File::isFile).readText()

    private val houseCubic = ".setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f))"

    private val swap = source.substringAfter("private fun animateOverlaySwap(").substringBefore("\n    private fun addCurrentView(")

    @Test
    fun `the swap is the swap tier's 180 ms out, a 130 ms hand-over and 180 ms in, 310 ms in all`() {
        assertEquals("the house overlay beat", 180L, CompanionOverlaySwapDurationMs)
        assertEquals("where the outgoing is at 0.044 and is handed over", 130L, CompanionOverlaySwapHandoffMs)
        assertEquals("the rise", 8, CompanionOverlaySwapRiseDp)
        assertEquals(310L, CompanionOverlaySwapHandoffMs + CompanionOverlaySwapDurationMs)
        assertFalse("the old 90 ms half beat is gone", source.contains("CompanionOverlayOutgoingDurationMs"))
    }

    @Test
    fun `on the house cubic the outgoing root is at alpha 0_044 at the hand-over, as the token documents`() {
        // CSS ease, cubic-bezier(0.25, 0.1, 0.25, 1): solve x(t) = 130 / 180 by bisection, read y.
        fun bez(t: Double, a: Double, b: Double) = 3 * (1 - t) * (1 - t) * t * a + 3 * (1 - t) * t * t * b + t * t * t
        val x = CompanionOverlaySwapHandoffMs.toDouble() / CompanionOverlaySwapDurationMs
        var lo = 0.0
        var hi = 1.0
        repeat(60) {
            val mid = (lo + hi) / 2
            if (bez(mid, 0.25, 0.25) < x) lo = mid else hi = mid
        }
        val alpha = 1.0 - bez((lo + hi) / 2, 0.1, 1.0)
        assertEquals("the outgoing's alpha at 130 ms", 0.044, alpha, 0.006)
    }

    @Test
    fun `the outgoing root fades over 180 ms with no scale, on the house cubic, and hands over from its own clock`() {
        val outgoing = swap.substringAfterLast("previous.animate()").substringBefore(".start()")
        assertTrue(outgoing.contains(".alpha(0f)"))
        assertFalse("no scale on the way out", outgoing.contains("scaleX") || outgoing.contains("scaleY"))
        assertTrue("180 ms", outgoing.contains(".setDuration(CompanionOverlaySwapDurationMs)"))
        assertTrue("the house cubic", outgoing.contains(houseCubic))
        assertTrue(
            "the hand-over is read off the fade's own animator time, so it follows the animation scale",
            outgoing.contains("fade.currentPlayTime >= CompanionOverlaySwapHandoffMs"),
        )
        assertTrue("and runs from its end when that time is never reached (animations off)", outgoing.contains("override fun onAnimationEnd(animation: Animator) = handOver()"))
        assertFalse("no wall-clock timer", swap.contains("postDelayed") || swap.contains("startDelay") || swap.contains("setStartDelay"))
    }

    @Test
    fun `the replacement is mounted only after the outgoing window is removed, once, with nothing between`() {
        val handOver = swap.substringAfter("fun handOver() {").substringBefore("        previous.animate()\n            .alpha(0f)")
        assertTrue("it runs once", handOver.contains("if (handedOver) return") && handOver.contains("handedOver = true"))
        assertTrue(
            "the outgoing root is removed first, then the incoming one is added",
            handOver.indexOf("wm.removeViewImmediate(previous)") in 0 until handOver.indexOf("addCurrentView("),
        )
        assertTrue("with the swap tier's own animation", handOver.contains("addCurrentView(positionOverride, animate = true)"))
        assertTrue("the removed root's tail is stopped", handOver.contains("previous.animate().setUpdateListener(null).setListener(null).cancel()"))
        assertTrue(
            "one root at a time: the controller mounts exactly one incoming root after the outgoing one",
            source.contains("Fade the outgoing root, then mount exactly one incoming root"),
        )
    }

    @Test
    fun `the replacement fades in over 180 ms with an 8 dp rise and no scale, on the same cubic`() {
        val add = source.substringAfter("private fun addCurrentView(").substringBefore("\n    private fun animateOverlayReplacement(")
        assertTrue("it starts invisible", add.contains("view.alpha = if (animate) 0f else targetAlpha"))
        assertTrue(
            "the Bottom's full-display menu starts the rise below its seat; the button and Main's card-sized menu window, which would clip it, do not",
            add.contains("view.translationY = if (animate && menuOpen && displayRole == CompanionOverlayRole.SECONDARY_COMPANION) {") &&
                add.contains("dp(displayContext ?: appContext, CompanionOverlaySwapRiseDp).toFloat()"),
        )
        assertFalse("no scale on the way in", add.contains("scaleX") || add.contains("scaleY"))
        assertTrue("and is animated for the swap tier's 180 ms", add.contains("animateOverlayReplacement(view, targetAlpha)"))

        val replacement = source.substringAfter("private fun animateOverlayReplacement(").substringBefore("\n    }\n")
        assertTrue("up to its resting alpha and its seat", replacement.contains(".alpha(targetAlpha)") && replacement.contains(".translationY(0f)"))
        assertFalse(replacement.contains(".scaleX(") || replacement.contains(".scaleY("))
        assertTrue("180 ms", replacement.contains("durationMs: Long = CompanionOverlaySwapDurationMs") && replacement.contains(".setDuration(durationMs)"))
        assertTrue("the house cubic", replacement.contains(houseCubic))
    }

    @Test
    fun `the cleanup is written where each swap exit is, source pins only, and what it does to a window that will not let go is the app CompanionOverlaySwapMotionTest`() {
        val replacement = source.substringAfter("private fun animateOverlayReplacement(").substringBefore("\n    }\n")
        assertTrue("on the replacement's own end", replacement.contains("view.translationY = 0f"))
        val cancel = source.substringAfter("private fun cancelOverlayTransition()").substringBefore("\n    private fun overlayParams(")
        assertTrue("on a cancelled swap", cancel.contains("current.translationY = 0f"))
        val failed = swap.substringAfter("if (!removed || overlayTransitionGeneration != generation || overlayView !== previous) {").substringBefore("return\n            }")
        assertTrue(
            "a root that could not be removed has its fade stopped before its alpha is restored",
            failed.indexOf("previous.animate().setUpdateListener(null).setListener(null).cancel()") in 0 until failed.indexOf("previous.alpha ="),
        )
        assertTrue("and a cancelled swap stops it the same way", cancel.contains("current.animate().setUpdateListener(null).setListener(null).cancel()"))
        // A mount with no animation never offsets: the immediate path adds with animate = false.
        assertTrue(source.contains("if (!addCurrentView(positionOverride, animate = false)) {"))
    }
}
