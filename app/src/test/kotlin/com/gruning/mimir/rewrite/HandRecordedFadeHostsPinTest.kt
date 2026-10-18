package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 8 of the 2026-10-14 transition audit: "the picker may vanish instead of fading" (filed unproven). Its fade replays a layer
 * that was recorded by hand into a `GraphicsLayer` after the picker has left composition, and the known trap (memory:
 * graphicslayer-replay-trap) is that a hand-recorded layer which nothing draws loses its recording, after which Compose replays the
 * stored block, a `drawContent()` on a scope whose pass has ended, and paints nothing.
 *
 * **Disproved for the fade hosts, by source.** A pixel capture cannot settle it on the JVM: Compose's explicit `GraphicsLayer.draw`
 * ends in `Canvas.drawRenderNode`, which Robolectric's software canvas refuses ("Software rendering doesn't support
 * drawRenderNode", seen when the Library host was put in front of a capture). The two facts the answer rests on are these:
 *  1. AOSP `RenderNode::syncDisplayList` raises the parent count of every child of the NEW display list before it drops the OLD
 *     list's ("Make sure we inc first so that we don't fluctuate between 0 and 1"), so the layer that moves from the primary Box to the
 *     outgoing Box on the frame the target clears never reaches zero and keeps its recording. The recording is dropped only for a layer
 *     that no synced frame draws.
 *  2. The outgoing layer IS drawn on every frame of its fade: it is only ever drawn (never recorded) in its Box, and that Box
 *     stays mounted until the fade's own completion (or a cancellation) clears `outgoingVisible`.
 * Fact 2 is what this pins, in every host that records by hand, so a later edit cannot quietly start dropping the layer early or
 * re-recording it from a finished pass. The handheld's own compositor is device-owed (emulator slow motion at 5x).
 */
class HandRecordedFadeHostsPinTest {

    private fun source(vararg candidates: String): String =
        candidates.map(::File).firstOrNull(File::isFile)?.readText() ?: throw AssertionError("not reachable: ${candidates.toList()}")

    private val hostSource: String get() = source(
        "src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt",
        "app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt",
    )

    private val librarySource: String get() = source(
        "../feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt",
        "feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt",
    )

    /** Every `drawWithContent` block that draws an OUTGOING layer: its body, from the opening to the draw. */
    private fun outgoingDrawBlocks(text: String): List<String> {
        val marker = "drawLayer(outgoingLayer)"
        val blocks = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = text.indexOf(marker, from)
            if (at < 0) break
            val open = text.lastIndexOf("drawWithContent {", at)
            assertTrue("an outgoing draw outside a drawWithContent block at $at", open >= 0)
            blocks += text.substring(open, at + marker.length)
            from = at + marker.length
        }
        return blocks
    }

    @Test
    fun `an outgoing layer is only ever drawn, never recorded, in every host that records by hand`() {
        for ((name, text) in listOf("MimirAppHost" to hostSource, "LibraryScreen" to librarySource)) {
            val blocks = outgoingDrawBlocks(text)
            assertTrue("$name has outgoing draws to check", blocks.isNotEmpty())
            blocks.forEach { block ->
                assertFalse("$name: an outgoing block must not record: $block", block.contains(".record"))
                assertFalse("$name: an outgoing block must not replay a finished pass's drawContent(): $block", block.contains("drawContent()"))
            }
        }
    }

    @Test
    fun `an outgoing layer is unmounted only by its fade's completion or a cancellation, never by the target changing`() {
        // `outgoingVisible = false` is assigned only under `epochs.acceptsCompletion` (the fade ended, or was cancelled for THIS epoch), in
        // every hand-recording host.
        for ((name, text) in listOf("MimirAppHost" to hostSource, "LibraryScreen" to librarySource)) {
            val assignments = Regex("""outgoingVisible = false""").findAll(text).map { it.range.first }.toList()
            assertTrue("$name assigns outgoingVisible", assignments.isNotEmpty())
            assignments.forEach { at ->
                val lineStart = text.lastIndexOf('\n', at)
                val context = text.substring(maxOf(0, lineStart - 120), at)
                assertTrue(
                    "$name: outgoingVisible = false at $at must sit under acceptsCompletion: ...${context.takeLast(100)}",
                    context.contains("acceptsCompletion(frame.epoch)"),
                )
            }
        }
    }

    @Test
    fun `the primary layer is re-recorded inside the pass that draws it, so a lost recording never needs a replay`() {
        for ((name, text) in listOf("MimirAppHost" to hostSource, "LibraryScreen" to librarySource)) {
            val records = Regex("""primaryLayer\.record \{ primaryContent\.drawContent\(\) \}""").findAll(text).count()
            assertTrue("$name records its primary layer by hand", records > 0)
            // Each recording is immediately followed in the same block by the draw of that layer.
            val pairs = Regex(
                """primaryLayer\.record \{ primaryContent\.drawContent\(\) \}[\s\S]{0,400}?drawLayer\(primaryLayer\)""",
            ).findAll(text).count()
            assertEquals("$name: every recording is drawn in the same pass", records, pairs)
        }
    }
}
