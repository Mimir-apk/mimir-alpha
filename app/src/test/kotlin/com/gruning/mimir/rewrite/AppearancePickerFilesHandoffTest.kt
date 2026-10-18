package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-over between an appearance-image picker and Mimir's image browser. Captured at 5x before the fix: the picker's pixels left on OverlayMs from the press
 * while the browser sat at alpha 0 through its lead, and on the return the picker arrived cold while the browser
 * dissolved -- two opaque surfaces at partial alpha over the Settings pane both directions.
 *
 * Now, both directions start the arriving surface at 1 and let the departing pixels, which the host draws on top, do
 * the work: on Upload the picker STANDS for the browser's lead then dissolves on the browser's own arrival clock (the
 * dock's Files motion, seen from the other layer); on the return the picker is live at 1 under the browser from its
 * first frame and the browser dissolves over it on the return clock. Exhaustive over the overlay kinds, in the shape of
 * [IncomingOverlayHoldTest]: every other pair keeps exactly the clocks it had.
 */
class AppearancePickerFilesHandoffTest {

    private val pickers = setOf(MimirOverlayKind.BACKGROUND_PICKER, MimirOverlayKind.MBUTTON_PICKER)
    private val browser = MimirOverlayKind.FOLDER_BROWSER

    private fun target(kind: MimirOverlayKind?) = kind?.let { MimirDirectOverlayTarget(it) }

    @Test
    fun `a picker leaving for the browser stands for the browser's lead, then dissolves on the browser's arrival clock`() {
        pickers.forEach { picker ->
            assertEquals(
                "$picker holds for the lead every page arrival waits, the same beat the dock's Files opening waits",
                mimirIncomingOverlayLeadMs(reducedMotion = false, kind = browser),
                mimirOutgoingLayerHoldMs(from = picker, to = browser, reducedMotion = false),
            )
            assertEquals(MimirMotion.OverlayDissolveCoverMs, mimirOutgoingLayerHoldMs(picker, browser, reducedMotion = false))
            assertEquals(
                "$picker dissolves on the clock the browser arrives with from the dock",
                mimirIncomingOverlayArriveMs(kind = browser, homeTomeLibraryOpenOrigin = null, reducedMotion = false),
                mimirDirectOverlayDepartMs(from = picker, to = browser),
            )
            assertEquals(MimirMotion.ShineMs, mimirDirectOverlayDepartMs(picker, browser))
            assertEquals("reduced motion is a cut", 0, mimirOutgoingLayerHoldMs(picker, browser, reducedMotion = true))
            assertEquals(0, mimirDirectOverlayDepartMs(picker, browser, reducedMotion = true))
        }
    }

    @Test
    fun `the browser returning to a picker dissolves on the house overlay clock, matched with Utility (Q3)`() {
        pickers.forEach { picker ->
            assertEquals(MimirMotion.OverlayMs, mimirDirectOverlayDepartMs(from = browser, to = picker))
            assertEquals("nothing stands on a return", 0, mimirOutgoingLayerHoldMs(from = browser, to = picker, reducedMotion = false))
        }
    }

    @Test
    fun `every other hand-over keeps the clocks it had -- a per-pair rule, not a general one`() {
        val kinds = MimirOverlayKind.entries + null
        kinds.forEach { from ->
            kinds.forEach { to ->
                val pickerToBrowser = from in pickers && to == browser
                assertEquals(
                    "$from -> $to must keep NO departure hold",
                    if (pickerToBrowser) MimirMotion.OverlayDissolveCoverMs else 0,
                    mimirOutgoingLayerHoldMs(from, to, reducedMotion = false),
                )
                val expected = when {
                    pickerToBrowser -> MimirMotion.ShineMs
                    else -> MimirMotion.OverlayMs
                }
                assertEquals("$from -> $to departs on the clock it had", expected, mimirDirectOverlayDepartMs(from, to))
            }
        }
        // The one-argument form every existing caller uses is unchanged.
        assertEquals("Files departs as Utility does (review 2026-10-15, Q3 siblings matched)", MimirMotion.OverlayMs, mimirDirectOverlayDepartMs(browser))
        assertEquals(MimirMotion.OverlayMs, mimirDirectOverlayDepartMs(MimirOverlayKind.BACKGROUND_PICKER))
        assertEquals(MimirMotion.OverlayMs, mimirDirectOverlayDepartMs(null))
    }

    @Test
    fun `the browser over a picker and a picker under the returning browser both start at 1, and nothing else changes`() {
        pickers.forEach { picker ->
            assertEquals(
                "the browser paints at 1 beneath the picker's held pixels",
                1f,
                mimirIncomingLayerStartAlpha(target(browser), cachedLayerTarget = null, cachedLayerAlpha = 0f, hasOutgoing = true, replacedKind = picker),
                0f,
            )
            assertEquals(
                "the picker is the page returned to, at rest from its first frame",
                1f,
                mimirIncomingLayerStartAlpha(target(picker), cachedLayerTarget = target(picker), cachedLayerAlpha = 0f, hasOutgoing = true, replacedKind = browser),
                0f,
            )
            assertEquals("a picker opening from Settings still arrives cold", 0f, mimirIncomingLayerStartAlpha(target(picker), null, 0f, hasOutgoing = true, replacedKind = null), 0f)
            assertEquals(1f, mimirIncomingLayerStartAlpha(target(picker), null, 0f, hasOutgoing = false, replacedKind = null), 0f)
        }
        // Utility's return from the browser keeps its own rule (MimirOverlaysOwningTheirArrival), and the browser over
        // Utility, or over the scene, still arrives cold from the host's lead.
        assertEquals(1f, mimirIncomingLayerStartAlpha(target(MimirOverlayKind.BRIGHTNESS), null, 0f, hasOutgoing = true, replacedKind = browser), 0f)
        assertEquals(0f, mimirIncomingLayerStartAlpha(target(browser), null, 0f, hasOutgoing = true, replacedKind = MimirOverlayKind.BRIGHTNESS), 0f)
        assertEquals(0f, mimirIncomingLayerStartAlpha(target(browser), null, 0f, hasOutgoing = true, replacedKind = null), 0f)
        (MimirOverlayKind.entries - pickers - browser - MimirOverlaysOwningTheirArrival).forEach { kind ->
            assertEquals("$kind keeps its cold arrival after the browser", 0f, mimirIncomingLayerStartAlpha(target(kind), null, 0f, hasOutgoing = true, replacedKind = browser), 0f)
        }
    }

    @Test
    fun `the host draws the departing pixels above the arriving ones and carries the hold as the dissolve's own delay`() {
        val code = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt").takeIf { it.isFile }
            ?: File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt")
        val text = code.readText()
        val pixelHost = text.substringAfter("private fun <T : Any> MimirPixelFadeHost(").substringBefore("private fun MimirDirectOverlayPixelFadeHost(")
        assertTrue(pixelHost.contains("departHoldMs: (from: T?, to: T?) -> Int = { _, _ -> 0 },"))
        assertTrue("the hold rides the animator scale as the tween's delay", pixelHost.contains("tween(departureMs, delayMillis = hold, easing = MimirMotion.Easing)"))
        assertTrue("a page still dissolving finishes in the share it has left (RC-54, review 2026-10-15)", pixelHost.contains("if (retainLingering) mimirLingeringDepartMs(full, outgoingAlpha.value) else full"))
        assertTrue("presses wait while the departing surface stands", pixelHost.contains("if (hold > 0 && outgoingAlpha.value >= 1f) holdMimirInputUntilShown(outgoingAlpha) { outgoingAlpha.value < 1f }"))
        // The primary (arriving) Box is composed first, the outgoing layer's Box after it: above.
        val primaryAt = pixelHost.indexOf("primaryLayer.record { primaryContent.drawContent() }")
        val outgoingAt = pixelHost.indexOf("drawLayer(outgoingLayer)")
        assertTrue(primaryAt in 1 until outgoingAt)
        val direct = text.substringAfter("private fun MimirDirectOverlayPixelFadeHost(").substringBefore("private fun MimirDirectOverlayContent(")
        assertTrue(direct.contains("departHoldMs = { from, to -> mimirOutgoingLayerHoldMs(from?.kind, to?.kind, reducedMotion) },"))
        assertTrue(direct.contains("replacedKind = replacedKind,"))
    }
}
