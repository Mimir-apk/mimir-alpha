package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Utility/Tweaker swap container was TopStart inside a centred host, so the different-height
 * panels jumped on Back and drifted on open, and the
 * departing panel showed the arriving one's footer label and ring.
 */
class UtilityTweakerSwapSteadyTest {

    private val source = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
    ).first(File::isFile).readText()

    private val swap = source.substringAfter("val swapReducedMotion = mimirReducedMotion()")
        .substringBefore("if (backActionLabel == \"Back\") onBack() else onClose()")

    @Test
    fun `the swap is centred like its host, so neither panel moves while the other dissolves`() {
        val header = swap.substringBefore("{ presentedSurface ->")
        assertTrue(header, header.contains("contentAlignment = Alignment.Center"))
    }

    @Test
    fun `each layer dissolves with its own footer label and ring`() {
        assertTrue(swap.contains("label = layerBackActionLabel"))
        assertFalse("no layer reads the live focus for its ring", swap.contains("focused = focusedControl =="))
        assertTrue(swap.contains("val layerFocusedControl = if (isLive) focusedControl else layerMemory.focusedControl"))
    }
}
