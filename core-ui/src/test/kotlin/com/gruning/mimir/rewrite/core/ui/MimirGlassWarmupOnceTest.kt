package com.gruning.mimir.rewrite.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * RC-59 (the L7 review, 2026-10-15) and its review: the blur warm-up runs once per PROCESS, not once per host, and the process's gate is
 * closed by the warm-up frame's DRAW, never by composition. HWUI has one RenderThread and one GrContext per process, so the first host
 * to warm (Main or the Bottom Presentation) warms every window; a host recreated before its first draw (a config change on the way in)
 * has warmed nothing and must leave the gate to the next host. Robolectric draws no GraphicsLayer, so the draw's claim is exercised on
 * the plan the draw executes ([mimirGlassWarmupDraw]) and the composition rule on an injected gate. API 34, where the platform blurs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MimirGlassWarmupOnceTest {

    @get:Rule
    val compose = createComposeRule()

    private fun write(block: () -> Unit) {
        compose.runOnUiThread(block)
        Snapshot.sendApplyNotifications()
    }

    @Test
    fun `the draw closes the gate after its frame, and a warmed process draws no second warm-up`() {
        val gate = MimirGlassWarmupGate()
        var drawn = 0
        mimirGlassWarmupDraw(gate) {
            assertTrue("the gate is still open while the warm-up frame draws", gate.isOpen())
            drawn += 1
        }
        assertEquals(1, drawn)
        assertFalse("drawn: closed", gate.isOpen())
        mimirGlassWarmupDraw(gate) { drawn += 1 } // the next host of the same process
        assertEquals("a warmed process draws no second warm-up", 1, drawn)
    }

    @Test
    fun `a host disposed before it draws leaves the gate open, and the next host warms`() {
        val gate = MimirGlassWarmupGate()
        val host = mutableStateOf("main, before the config change")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMimirGlassWarmupGate provides gate) {
                key(host.value) { Box { MimirGlassWarmup() } }
            }
        }
        compose.onAllNodesWithTag(MimirGlassWarmupTag).assertCountEquals(1)
        assertTrue("composition claims nothing", gate.isOpen())
        // The host is recreated before its first frame draws: the old one composed a warm-up it never drew.
        write { host.value = "main, recreated" }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag(MimirGlassWarmupTag).assertCountEquals(1)
    }

    @Test
    fun `a host that finds the process warmed composes nothing`() {
        val gate = MimirGlassWarmupGate()
        mimirGlassWarmupDraw(gate) { } // Main's warm-up frame drew
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMimirGlassWarmupGate provides gate) {
                Box { MimirGlassWarmup() } // the Bottom Presentation, created later
            }
        }
        compose.onAllNodesWithTag(MimirGlassWarmupTag).assertCountEquals(0)
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag(MimirGlassWarmupTag).assertCountEquals(0)
    }

    @Test
    fun `the warm-up composes for its frame beat and then nothing`() {
        val gate = MimirGlassWarmupGate()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMimirGlassWarmupGate provides gate) { Box { MimirGlassWarmup() } }
        }
        compose.onAllNodesWithTag(MimirGlassWarmupTag).assertCountEquals(1)
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        compose.onAllNodesWithTag(MimirGlassWarmupTag).assertCountEquals(0)
    }

    @Test
    fun `the gate answers once, and a test can reopen it`() {
        val gate = MimirGlassWarmupGate()
        assertTrue(gate.isOpen())
        assertTrue("the first claim", gate.claim())
        assertFalse(gate.isOpen())
        assertFalse("the second does not", gate.claim())
        gate.resetForTests()
        assertTrue(gate.isOpen())
        assertTrue(gate.claim())
    }
}
