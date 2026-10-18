package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion audit 2026-09-28, batch A. Utility's scrim, panel alpha and lift arrive on one glass ramp; the overlay host
 * ramped its layer too, and the two 180 ms curves multiplied into a squared one (a slow, mushy start). Utility now owns
 * its arrival like the Folio, and the host only fades it out.
 */
class UtilityArrivalNotSquaredTest {

    private val utility = MimirDirectOverlayTarget(MimirOverlayKind.BRIGHTNESS)
    private val theme = MimirDirectOverlayTarget(MimirOverlayKind.THEME_PICKER)

    @Test
    fun `a cold Utility open is not ramped by the host`() {
        assertEquals(
            1f,
            mimirIncomingLayerStartAlpha(utility, cachedLayerTarget = null, cachedLayerAlpha = 0f, hasOutgoing = true),
            0f,
        )
    }

    @Test
    fun `other overlays keep the host's arrival`() {
        assertEquals(
            0f,
            mimirIncomingLayerStartAlpha(theme, cachedLayerTarget = null, cachedLayerAlpha = 0f, hasOutgoing = true),
            0f,
        )
        assertEquals(setOf(MimirOverlayKind.BRIGHTNESS), MimirOverlaysOwningTheirArrival)
    }

    @Test
    fun `the arrival hold is the tween's delay, not a coroutine delay`() {
        val host = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        ).first(File::isFile).readText()
        assertFalse(host.contains("delay(holdMs.toLong())"))
        // Batch F: the arrival runs on its own clock from the larger of the CP01 hold and the page lead.
        assertTrue(host.contains("tween(incomingArriveMs, delayMillis = lead, easing = MimirMotion.Easing)"))
        assertTrue(host.contains("val lead = maxOf(holdMs, leadMs)"))
    }
}
