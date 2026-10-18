package com.gruning.mimir.rewrite.feature.library

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Home's L1/R1 header and runes depart through [com.gruning.mimir.rewrite.core.ui.MimirPageHandoff], whose departing
 * page references (does not copy) a layered child's RenderNode. The title Text, the RA seat and each rune carry their
 * own layer; if they survived the flip they would re-record, and the departing header would show the NEW title at once
 * and then blink out (review w_rev_15). Keying them on surfaceEpoch disposes them, and a disposed layer keeps its last
 * recording. Composition-group identity and RenderNode sharing are not observable from a JVM unit test, so this is a
 * source pin.
 */
class HomeShelfHandoffEpochKeyTest {

    private val screen = File(
        "src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeShelfScreen.kt",
    )

    private fun source(): String {
        assertTrue("HomeShelfScreen.kt not found at ${screen.absolutePath}", screen.isFile)
        return screen.readText()
    }

    private fun handoffs(src: String): List<String> {
        val out = mutableListOf<String>()
        var at = src.indexOf("MimirPageHandoff(")
        while (at >= 0) {
            val next = src.indexOf("MimirPageHandoff(", at + 1)
            out += src.substring(at, if (next >= 0) next else minOf(src.length, at + 12_000))
            at = next
        }
        return out
    }

    @Test
    fun `the header departs as the old title because it is keyed on the epoch`() {
        val header = handoffs(source()).single { it.contains("HomeShelfTitle(") }
        val keyAt = header.indexOf("key(surfaceEpoch) {")
        val titleAt = header.indexOf("HomeShelfTitle(")
        assertTrue("the header's HomeShelfTitle must sit inside key(surfaceEpoch)", keyAt in 0 until titleAt)
    }

    @Test
    fun `the runes depart as the old continuation because they are keyed on the epoch`() {
        // The runes moved from HomeShelfScreen.kt into the shared strip (GameShelf.kt, 2026-10-10). Home's band handoff
        // hosts the strip, and the strip keys the runes on the same epoch it is handed.
        val band = handoffs(source()).single { it.contains("GameShelf(") }
        assertTrue("the band's own handoff is keyed on the epoch", band.contains("epoch = surfaceEpoch,"))
        // The strip takes its epoch from the state Home builds, and Home builds that state from the epoch the band is keyed on.
        val stateCall = homeShelfScreenBody().sliceBetween("rememberGameShelfState(", "val visualSelectedSlotOffset")
        assertTrue("the state is built from the screen's epoch", stateCall.contains("surfaceEpoch = surfaceEpoch,"))
        val strip = gameShelfStripBody()
        assertTrue("the strip keys on the epoch it was built with", strip.contains("val surfaceEpoch = state.surfaceEpoch"))
        val runesAt = strip.indexOf("GameShelfContinuationRunes(")
        val keyAt = strip.lastIndexOf("key(surfaceEpoch) {", runesAt)
        val tileKeyAt = strip.indexOf("key(surfaceEpoch, slot.item.focusKey)")
        assertTrue("the tile key must exist to be passed", tileKeyAt >= 0)
        assertTrue("the runes must sit inside their own key(surfaceEpoch)", keyAt >= 0 && keyAt > tileKeyAt)
    }
}
