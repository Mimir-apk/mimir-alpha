package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapper's rows wear the shared SettingsRow ring (reach MimirFocusTileRingReach). Its settlement pins the
 * first row to scroll 0 and Apply to maxValue, so the ring's clearance must sit inside the scroll host, and the
 * rows must stand far enough apart that a focused ring does not run over its neighbours' hairlines.
 * Source pin, the module's idiom: whether a ring clips is device-only.
 */
class ControllerMappingRingClearanceTest {

    @Test
    fun `the scroll host carries the ring's reach inside the scroll and spaces rows like the rail`() {
        val overlay = source("ControllerMappingOverlay.kt")
        val host = overlay.substringAfter(".testTag(ControllerMappingViewportTestTag)").substringBefore("rows.forEach")
        val scrollAt = host.indexOf(".verticalScroll(scroll)")
        val padAt = host.indexOf(".padding(vertical = MimirFocusTileRingReach)")
        assertTrue("the scroll host exists", scrollAt >= 0)
        assertTrue("padding after verticalScroll, so it scrolls with the rows", padAt > scrollAt)
        assertTrue(host.contains("verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),"))
        assertFalse("the 2 dp double line is gone", host.contains("spacedBy(2.dp)"))
    }

    @Test
    fun `the ends still settle to the scroll extremes, which now include the clearance`() {
        val ids = listOf("first", "middle", "last")
        assertEquals(0, controllerMappingSettlementTarget("first", ids, null, 300, 500))
        assertEquals(500, controllerMappingSettlementTarget("last", ids, null, 300, 500))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
        File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
    ).first(File::isFile).readText()
}
