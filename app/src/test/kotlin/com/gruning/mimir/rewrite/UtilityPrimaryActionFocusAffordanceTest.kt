package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Utility panel's primary action, "Allow" (`BrightnessControlKey.ALLOW_SYSTEM_CHANGES`, `BrightnessOverlay.kt`'s only `UtilityAction(primary = true, ...)` call site).
 *
 * It used to be a rectangle filled with `palette.accent`, and a FOCUSED ring in the accent too landed on the same hue (the accent-on-accent collision the keyboard's Done key had,
 * previously observed), so its focused fill was shifted toward the ink. The update made it the PRIMARY etched chip:
 * the same dark plate as the plain chip beside it, its rim and its word in the accent, and no fill at all. There is no fill left to collide with the ring, and the focus affordance is
 * the plate handing its rim to the ring (`focused` passed to the plate), so a focused primary has ONE edge, not an outline and a ring with a gap between.
 *
 * The wiring is pinned in `UtilityAction`s own source text (this module's idiom, `HomeLaunchCardTest`'s `homeLaunchCardSource()`); the pixels are
 * `FilledRectButtonCaptureTest`'s.
 */
class UtilityPrimaryActionFocusAffordanceTest {

    @Test
    fun `the primary Utility action's word is the accent on every theme, and not the plain chip's ink`() {
        MimirThemePalette.all.forEach { palette ->
            assertEquals("theme ${palette.id}: the primary's word is the bright accent", palette.accentBright, palette.etchedChipInk(primary = true))
            assertNotEquals(
                "theme ${palette.id}: the primary reads differently from the plain Close beside it",
                palette.etchedChipInk(),
                palette.etchedChipInk(primary = true),
            )
        }
    }

    @Test
    fun `UtilityAction is the etched chip, the primary one for Allow, with no fill of its own`() {
        val action = brightnessOverlaySource()
            .substringAfter("internal fun UtilityAction(")
            .substringBefore("private fun percentLabel(")

        assertTrue("the plate is the shared etched chip", action.contains("MimirEtchedChipPlate("))
        assertTrue("the plate is handed the primary", action.contains("primary = primary,"))
        assertTrue("the plate is handed focus, so its rim hands over to the ring", action.contains("focused = focused,"))
        assertTrue("the word is the chip's own ink, in the accent for the primary", action.contains("palette.etchedChipInk(primary = primary)"))
        assertTrue("the word is the chip's own type", action.contains("style = MimirEtchedChipLabelStyle"))
        assertFalse("no fill under the plate", action.contains(".background("))
        assertFalse("no outline of its own: the plate's rim and the ring are the only edges", action.contains(".border("))
        assertFalse("the old primary fill pair is gone", action.contains("utilityPrimaryAction"))
    }

    private fun brightnessOverlaySource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("BrightnessOverlay.kt not found from ${File(".").absolutePath}")
    }
}
