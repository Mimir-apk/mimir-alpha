package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home <-> Library with B. A reversal mid-dissolve starts the page being left from the screen as it was
 * (the complement of what the returning page was painted at), not from full alpha: no blink.
 */
class RootShelfReversalTest {

    @Test
    fun `a reversal starts the page left at the complement of the returning page's painted alpha`() {
        assertEquals(0.7f, rootShelfOutgoingStartAlpha(returning = true, paintedReturning = 0.3f), 1e-6f)
        assertEquals("a finished dissolve painted 0: the normal start", 1f, rootShelfOutgoingStartAlpha(true, 0f), 0f)
        assertEquals("a returning page fully painted: nothing left to dissolve", 0f, rootShelfOutgoingStartAlpha(true, 1f), 0f)
        assertEquals(RootShelfOutgoingLayerStartAlpha, rootShelfOutgoingStartAlpha(returning = false, paintedReturning = 0.4f), 0f)
    }

    @Test
    fun `every hop clears on one scene clock including Library to Home`() {
        // The retired ~50 ms exception: it measured as a 33-42 ms cut on the Thor against 134-139 ms
        // the other way. Library -> Home is symmetric with every other hop, on SceneMs.
        assertEquals(MimirMotion.SceneMs, RootShelfOutgoingLayerDurationMs)
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        ).first(File::isFile).readText()
        assertFalse("the Library -> Home exception is gone", file.contains("RootShelfLibraryToHomeClearMs"))
        assertFalse(file.contains("fun rootShelfOutgoingDurationMs("))
        val host = file.substringAfter("private fun RootShelfMotionHost(").substringBefore("private fun MimirDestinationHost(")
        assertTrue(host.contains("val clearMs = RootShelfOutgoingLayerDurationMs"))
        assertTrue(host.contains("durationMillis = clearMs,"))
    }

    @Test
    fun `the pill reads battery honestly from the sticky broadcast`() {
        assertNull(homeStatusBatteryPercent(null))
        assertEquals(false, homeStatusCharging(null))
    }
}
