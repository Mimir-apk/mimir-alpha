package com.gruning.mimir.rewrite.core.companionnative

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * every ViewPropertyAnimator on the ᛗ coin ran on Android's default
 * AccelerateDecelerate beside the house `PathInterpolator(0.25, 0.1, 0.25, 1)` that the swap, the blur hand-off and the
 * glide animators already wear. The Main coin's 90/120 ms durations stay pinned
 * (`CompanionButtonPressSourcePinTest`); the curve was not part of that duration contract, so this pins it on all four animators:
 * the press, the two releases (up and cancel) and the panel-coin fade.
 */
class CompanionCoinHouseCurveSourcePinTest {
    private val code: String by lazy {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/AndroidCompanionOverlayController.kt")
        assertTrue("AndroidCompanionOverlayController.kt must be readable from the module dir", file.isFile)
        file.readText().lineSequence().joinToString("\n") { line ->
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    private val houseCurve = ".setInterpolator(PathInterpolator(0.25f, 0.1f, 0.25f, 1f)).start()"

    /** The coin's touch handler, where press and release live. */
    private val touch: String by lazy {
        val start = code.indexOf("private fun handleButtonTouch(")
        check(start >= 0) { "handleButtonTouch moved: move this pin with it" }
        code.substring(start, code.indexOf("private fun handleMenuDrag(", start))
    }

    @Test
    fun `the press takes the house curve and keeps the role's own beat`() {
        assertTrue(
            "the press animator ends on the house curve",
            Regex("""\.setDuration\(companionButtonPressMs\(displayRole\)\)\.setInterpolator\(PathInterpolator\(0\.25f, 0\.1f, 0\.25f, 1f\)\)\.start\(\)""").containsMatchIn(touch),
        )
    }

    @Test
    fun `the release on up and on cancel take the house curve`() {
        assertEquals(
            "up and cancel both end their release on the house curve",
            2,
            Regex("""\.setDuration\(companionButtonReleaseMs\(displayRole\)\)\.setInterpolator\(PathInterpolator\(0\.25f, 0\.1f, 0\.25f, 1f\)\)\.start\(\)""").findAll(touch).count(),
        )
    }

    @Test
    fun `the panel coin fade takes the house curve`() {
        val apply = code.substringAfter("private fun applyCoinHiddenForPanel(", "").substringBefore("\n    private fun ", "")
        assertTrue("the function is found", apply.isNotEmpty())
        assertTrue(
            "the fade is on CompanionPanelCoinFadeMs and the house curve",
            apply.contains(".setDuration(CompanionPanelCoinFadeMs)$houseCurve"),
        )
    }

    @Test
    fun `no coin animator is left on the default interpolator`() {
        // Every `.animate().alpha(...)...start()` chain in the controller (the coin's four and the two overlay fades) names an
        // interpolator before it starts.
        val chains = Regex("""\.animate\(\)\s*\.alpha\([\s\S]*?\.start\(\)""").findAll(code).map { it.value }.toList()
        assertTrue("the animators are found", chains.size >= 6)
        chains.forEach { chain ->
            assertTrue("a coin animator has no interpolator: $chain", chain.contains("setInterpolator("))
        }
    }
}
