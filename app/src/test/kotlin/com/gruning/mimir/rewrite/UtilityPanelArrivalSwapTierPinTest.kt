package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Utility's panel opens on the swap tier, with its scrim, on one clock. It
 * pinned the page clock previously (scrim on the 180 ms glass ramp, panel from a 130 ms lead over 540 ms),
 * which is what dimmed the region under the panel and then raised it. The frames are `UtilityOpenSwapTierFrameTest`'s.
 */
class UtilityPanelArrivalSwapTierPinTest {

    @Test
    fun `the scrim and the panel share the one glass ramp, and the pad waits for the first paint of a cold open`() {
        val source = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/BrightnessOverlay.kt"),
        ).first(File::isFile).readText()
        // A cold open: one glass ramp for the scrim and the panel. Back from the file browser both are at rest from
        // the first frame -- the page returned to -- and neither the ramp nor the hold runs.
        assertTrue(source.contains("val settledOnReturn = remember { settledOnMount }"))
        assertTrue(source.contains("val panelEntry: () -> Float = if (settledOnReturn) {\n        { 1f }\n    } else {\n        rememberMimirGlassProgress(visible = true)\n    }"))
        assertTrue(source.contains("drawRect(color = scrimColor, alpha = panelEntry().coerceIn(0f, 1f))"))
        assertTrue("the panel is on the scrim's clock", source.contains("val panelArrival: () -> Float = panelEntry\n"))
        assertTrue(source.contains(".mimirGlassPanelEntry(panelArrival)"))
        assertFalse("no page-clock panel progress remains", source.contains("rememberMimirGlassPanelProgress"))
        // The hold takes the host's holdPad, which Main's
        // hosts keep at the true default and the Bottom's host passes false.
        assertTrue(source.contains("holdPad: Boolean = true,"))
        assertTrue(source.contains("if (holdPad && !settledOnReturn) {\n        LaunchedEffect(Unit) { holdMimirInputUntilShown(panelEntry) { panelEntry() > 0f } }\n    }"))
    }
}
