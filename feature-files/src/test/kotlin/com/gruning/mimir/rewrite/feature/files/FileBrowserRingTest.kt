package com.gruning.mimir.rewrite.feature.files

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The file browser after the ring migration : every row, tile, chip and button in this module
 * rings once through the one selection ring, on a corner it shares with its clip, and every lazy list pads both
 * clipped ends by the ring's reach. Source pins, the module's idiom.
 */
class FileBrowserRingTest {

    private val surfaces = listOf(
        "FileBrowserSurface.kt",
        "FileBrowserActionOverlaySurface.kt",
        "FileBrowserDestinationSurface.kt",
        "FileBrowserDiscSetSurface.kt",
        "FileBrowserExtractSurface.kt",
        "FileBrowserQueueSurface.kt",
        "FileBrowserSearchSurface.kt",
        "FileBrowserTransferSurface.kt",
    )

    @Test
    fun `no surface in this module wears the old ring, and none scales on focus`() {
        surfaces.forEach { name ->
            val source = sourceOf(name)
            assertFalse("$name still calls mimirFocusRing", source.contains(".mimirFocusRing("))
            assertFalse("$name still names a focusedScale", source.contains("focusedScale"))
        }
        assertEquals(12, surfaces.sumOf { Regex("""\.mimirSelectionRing\(""").findAll(sourceOf(it)).count() })
    }

    @Test
    fun `rows and the tile ring on the corner they clip on`() {
        assertEquals(12.dp, FileBrowserRowCorner)
        assertEquals(14.dp, FileBrowserTileCorner)
        // R-1: the clip that follows a ring shares the ring's symbol, never a literal.
        surfaces.forEach { name ->
            val source = sourceOf(name)
            Regex("""\.mimirSelectionRing\(""").findAll(source).forEach { ring ->
                val clip = source.indexOf(".clip(", ring.range.first)
                // An etched chip's surface draws its own pill plate, which the ring hugs (the queue's buttons): a ring followed by that surface in its own chain has no clip to share a corner with.
                val etched = source.indexOf(".mimirEtchedChipSurface(", ring.range.first)
                if (etched > 0 && (clip < 0 || etched < clip) && etched - ring.range.first < 120) return@forEach
                assertTrue("$name: a ring without a clip after it", clip > 0)
                val clipCall = source.substring(clip, minOf(source.length, clip + 60))
                assertFalse("$name clips a ringed element on a literal corner: $clipCall", Regex("""RoundedCornerShape\(\d+\.dp\)""").containsMatchIn(clipCall))
            }
        }
        val surface = sourceOf("FileBrowserSurface.kt")
        assertTrue(surface.contains("shape = MimirRingShape.Card(FileBrowserTileCorner)"))
        assertTrue(surface.contains(".clip(RoundedCornerShape(FileBrowserTileCorner))"))
        assertTrue(surface.contains("CornerRadius(FileBrowserRowCorner.toPx())"))
        assertEquals(
            "the four leather-free dialog rows share one treatment: the ring paints their grey lift under the wash",
            4,
            listOf("FileBrowserDestinationSurface.kt", "FileBrowserDiscSetSurface.kt", "FileBrowserExtractSurface.kt", "FileBrowserTransferSurface.kt")
                .count { sourceOf(it).contains("fill = SolidColor(if (focused) palette.surfaceHigh else palette.surfaceRaised),\n            )\n            .clip(RoundedCornerShape(FileBrowserRowCorner))") },
        )
    }

    @Test
    fun `every lazy list pads both clipped ends by the ring's reach`() {
        listOf("FileBrowserSurface.kt", "FileBrowserQueueSurface.kt", "FileBrowserSearchSurface.kt").forEach { name ->
            val source = sourceOf(name)
            assertTrue(name, source.contains("PaddingValues(top = MimirFocusTileRingReach, end = 5.dp, bottom = MimirFocusTileRingReach)"))
            assertFalse(name, source.contains("PaddingValues(end = 5.dp, bottom = 10.dp)"))
        }
    }

    @Test
    fun `the destination list pads both clipped ends by the ring's reach`() {
        val list = sourceOf("FileBrowserDestinationSurface.kt").substringAfter("LazyColumn(").substringBefore(") {")
        assertTrue(list, list.contains("contentPadding = PaddingValues(vertical = MimirFocusTileRingReach)"))
    }

    @Test
    fun `the dialog rows and the tile keep the resting outline the old ring drew, and the field, the chips and the buttons do not`() {
        // w_rev_ring_2: four dialog rows and the listing tile. Update: the search field, the filter chips and the queue buttons are etched chips, whose metal has its
        // own rim, so they draw no hairline under the ring.
        assertEquals(5, surfaces.sumOf { Regex("""restingOutline = true""").findAll(sourceOf(it)).count() })
        assertFalse(sourceOf("FileBrowserQueueSurface.kt").contains("restingOutline"))
        assertTrue(sourceOf("FileBrowserSearchSurface.kt").contains(".mimirEtchedChipSurface(palette, warmed = selected, focused = focused, edgeFlush = true)"))
        // The lead queue button is the PRIMARY chip (never warmed: that is the selected look), and the ring is on the plate and BEFORE the surface in both.
        val queue = sourceOf("FileBrowserQueueSurface.kt")
        assertTrue(queue.contains(".mimirEtchedChipSurface(palette, focused = focused, primary = lead, edgeFlush = true)"))
        assertFalse(queue.contains("warmed = lead"))
        assertTrue(queue.indexOf(".mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)") in 0 until queue.indexOf(".mimirEtchedChipSurface(palette, focused = focused, primary = lead, edgeFlush = true)"))
        val chips = sourceOf("FileBrowserSearchSurface.kt")
        // The query field is the same capsule plate as a chip beside it, in the same two boxes, its ring on the plate before the surface.
        val field = chips.substringAfter("private fun FileBrowserSearchQueryField(").substringBefore("internal val FileBrowserSearchPlaceholder")
        assertTrue(field.indexOf(".mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)") in 0 until field.indexOf(".mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)"))
        assertFalse(field.contains("fill = SolidColor("))
        assertFalse(field.contains("restingOutline"))
        assertTrue(chips.contains(".defaultMinSize(minWidth = MimirSpacing.minTarget, minHeight = 30.dp)"))
        assertTrue(chips.indexOf(".mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)") in 0 until chips.indexOf(".mimirEtchedChipSurface(palette, warmed = selected, focused = focused, edgeFlush = true)"))
        assertTrue(sourceOf("FileBrowserSurface.kt").contains("shape = MimirRingShape.Card(FileBrowserTileCorner), restingOutline = true)"))
    }

    @Test
    fun `the inspector's buttons are etched rounded rectangles, Open the primary chip in the accent, and nothing is filled`() {
        // Issue #265's sweep (10-09): Open was a solid gold gradient. The shape rule (a rectangle with rounded corners, never a pill) holds: the plate takes the card corner.
        val surface = sourceOf("FileBrowserSurface.kt")
        val button = surface.substringAfter("internal fun FileBrowserButton(").substringBefore("private fun FileBrowserFooterLegend(")
        assertTrue(button.contains(".mimirEtchedChipSurface(palette, primary = primary, corner = MimirSpacing.radiusCard)"))
        assertTrue(button.contains("val primary = variant == FileBrowserButtonVariant.PRIMARY"))
        assertTrue(button.contains("color = palette.etchedChipInk(primary = primary)"))
        assertTrue(button.contains("style = MimirEtchedChipLabelStyle"))
        val code = button.replace(Regex("""//[^\n]*"""), "")
        assertFalse("no gradient, no tint, no clip", code.contains(".background(") || code.contains("ctaMaterial") || code.contains("Brush.") || code.contains(".clip("))
        assertFalse("never a pill", code.contains("radiusPill") || code.contains("CircleShape"))
        // The call sites other tests pin verbatim are unchanged.
        assertTrue(surface.contains("FileBrowserButton(res.getString(R.string.files_inspector_open), FileBrowserButtonVariant.PRIMARY, palette, onOpen)"))
    }

    private fun sourceOf(name: String): String = listOf(
        File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
    ).first(File::isFile).readText()
}
