package com.gruning.mimir.rewrite

import androidx.compose.animation.core.CubicBezierEasing
import com.gruning.mimir.rewrite.core.ui.MimirBottomMarkDefaultTopDp
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `core-companion-native` has no core-ui dependency, so the Main coin's glide (it glides to its edge on the app's overlay clock and the house easing)
 * and the Bottom coin's default seat (just below the pages' header row) keep local copies in MButtonPlacement.kt. This reads that source and holds them
 * equal to the tokens they mirror, so a change to a token is a red test here rather than a coin that quietly keeps an old glide or sits on a header.
 *
 * It also holds the retirement of the 2026-10-08 corner slot: no page reserves room for the Bottom coin (the Presentation provides no reserve to the Bottom's pages), and the
 * Bottom's headers have no slot machinery left.
 */
class BottomMarkPlacementSourcePinTest {

    private fun read(vararg candidates: String): String =
        candidates.map(::File).firstOrNull(File::isFile)?.readText() ?: error("${candidates.first()} must be readable from the module dir")

    private val placement: String = read(
        "../core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/MButtonPlacement.kt",
        "core-companion-native/src/main/kotlin/com/gruning/mimir/rewrite/core/companionnative/MButtonPlacement.kt",
    )

    private val presentation: String = read(
        "src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt",
        "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirCompanionPresentation.kt",
    )

    @Test
    fun `the glide runs on the overlay clock`() {
        val ms = checkNotNull(Regex("""const val MButtonRestGlideMs\s*=\s*(\d+)L""").find(placement)) { "MButtonRestGlideMs moved: move this pin with it" }
            .groupValues[1].toLong()
        assertEquals(MimirMotion.OverlayMs.toLong(), ms)
    }

    @Test
    fun `the glide runs on the house easing`() {
        val points = checkNotNull(Regex("""MButtonRestGlideEasing\s*=\s*floatArrayOf\(([^)]*)\)""").find(placement)) { "MButtonRestGlideEasing moved: move this pin with it" }
            .groupValues[1].split(",").map { it.trim().removeSuffix("f").toFloat() }
        assertEquals(4, points.size)
        val local = CubicBezierEasing(points[0], points[1], points[2], points[3])
        for (fraction in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            assertEquals("at $fraction", MimirMotion.Easing.transform(fraction), local.transform(fraction), 0.0005f)
        }
    }

    @Test
    fun `Main's glide is one named constant, on`() {
        assertTrue(Regex("""const val MButtonRestGlides\s*=\s*true""").containsMatchIn(placement))
    }

    @Test
    fun `the Bottom coin's default seat is the one core-ui names`() {
        val top = checkNotNull(Regex("""const val MButtonBottomDefaultTopDp\s*=\s*(\d+)""").find(placement)) { "MButtonBottomDefaultTopDp moved: move this pin with it" }
            .groupValues[1].toFloat()
        assertEquals(MimirBottomMarkDefaultTopDp, top, 0f)
    }

    @Test
    fun `the Bottom's pages are given no reserve for the coin`() {
        assertTrue("the trackpad gateway is still the one provider", presentation.contains("CompositionLocalProvider(LocalBottomTrackpadOpen provides openTrackpad) {"))
        assertFalse("the Presentation provides no end reserve to the Bottom", presentation.contains("LocalMimirTopEndReserve"))
        assertFalse("nor a header height for it", presentation.contains("LocalMimirCompactHeaderMinHeight"))
        assertFalse("nor the retired provider", presentation.contains("bottomMarkProvidedValues"))
    }
}
