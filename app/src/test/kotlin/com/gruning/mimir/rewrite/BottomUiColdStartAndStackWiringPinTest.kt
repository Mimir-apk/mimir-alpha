package com.gruning.mimir.rewrite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hosts that write the startup cover's exit and the surface-stack lines. The log's own behaviour is pinned in core-ui
 * (`BottomUiReceiptLogTest`, `BottomUiStackHeartbeatTest`); what only a source pin can say is WHERE the app calls it: the
 * controller that commits the Presentation's first frame (the floor Activity never leaves, so it cannot write its own exit), and the
 * Presentation's own composition (so the heartbeat dies with the window).
 */
class BottomUiColdStartAndStackWiringPinTest {
    private fun main(name: String): String =
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/$name").readText()

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** A function body, from `fun <name>(` to its balanced closing brace. */
    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name( must exist", start >= 0)
        val open = source.indexOf('{', source.indexOf(')', start))
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        throw AssertionError("unbalanced body for $name")
    }

    @Test
    fun `the first committed frame writes the covers exit then the ready stack, only when the frame is accepted`() {
        val settle = squash(body(main("CompanionDisplayController.kt"), "handleFrameCommitted"))
        val shown = settle.indexOf("presentationShown(")
        val accepted = settle.indexOf("} else { BottomUiReceipts.log.presentation( \"ready\",")
        val coverGone = settle.indexOf("BottomUiReceipts.log.coldStartCoverGone(candidate.display.displayId)")
        val ready = settle.indexOf("BottomUiReceipts.log.stackSnapshot(candidate.display.displayId, BottomUiReceiptLog.REASON_READY)")
        val rejected = settle.indexOf("\"frame_rejected\"")
        assertTrue("accepted branch after presentationShown: $shown $accepted", shown in 0 until accepted)
        assertTrue("presentation.ready, then the cover's exit, then the ready stack: $accepted $coverGone $ready", accepted < coverGone && coverGone < ready)
        assertTrue("and none of it on the rejected path: $rejected $accepted", rejected in 0 until accepted)
        assertEquals("the exit is written exactly once per frame commit", 1, Regex("coldStartCoverGone\\(").findAll(settle).count())
    }

    @Test
    fun `the floor still enters its cover and the Presentation arms one heartbeat for its own lifetime`() {
        assertTrue(
            "the floor Activity still reports its cover entering",
            main("CompanionSecondaryHomeActivity.kt").contains("BottomUiSurfaceReceipt(surface = BottomSurface.COLD_START, visible = true, display = floorDisplay)"),
        )
        val presentation = main("MimirCompanionPresentation.kt")
        assertEquals("one heartbeat, composed in the window's own content", 1, Regex("BottomUiStackHeartbeat\\(receiptDisplayId\\)").findAll(presentation).count())
        assertFalse("never a GlobalScope or an unbound timer", presentation.contains("GlobalScope"))
    }

    @Test
    fun `the process log writes stack snapshots and nothing per frame feeds them`() {
        val receipts = java.io.File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/BottomUiReceipts.kt").readText()
        assertTrue("the process log turns the snapshots on", squash(receipts).contains("stackSnapshots = true, )"))
        assertTrue("the heartbeat is one effect on the interval", squash(receipts).contains("LaunchedEffect(display, log, intervalMs) { log.runStackHeartbeat(display, intervalMs, wait) }"))
    }
}
