package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * an off-token clock is converted to a token. The Utility and Tweaker rail follows the focus with
 * `animateScrollTo`, which on its own runs Compose's default spring: an untokenized clock, moving beside a focus ring that rides
 * [com.gruning.mimir.rewrite.core.ui.MimirMotion.FocusMs]. The follow now rides that clock on the house easing, and jumps under reduced
 * motion. There is no frame harness for a scroll's easing, so this is a source pin of the mechanism; the rail's first settle (the
 * unanimated `scrollTo`) is untouched.
 */
class UtilityRailScrollOnFocusClockTest {

    private val source: String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
    ).first(File::isFile).readText().replace("\r\n", "\n")

    @Test
    fun `the rail follows the focus on the focus clock and the house easing`() {
        assertTrue(
            "animateScrollTo carries the focus clock on the house easing",
            source.contains("railScroll.animateScrollTo(target, tween(MimirMotion.FocusMs, easing = MimirMotion.Easing))"),
        )
    }

    @Test
    fun `the rail never follows on a bare animateScrollTo, and jumps under reduced motion`() {
        assertFalse("no spec-less animateScrollTo(target): that is Compose's default spring", source.contains("animateScrollTo(target)"))
        assertTrue(
            "reduced motion jumps",
            source.contains("if (railScrollReducedMotion) {\n                        railScroll.scrollTo(target)\n                    } else {"),
        )
    }
}
