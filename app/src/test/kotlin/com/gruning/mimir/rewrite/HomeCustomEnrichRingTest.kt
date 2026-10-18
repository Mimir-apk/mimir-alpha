package com.gruning.mimir.rewrite

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Customize media after the ring migration : the one selection ring on the search field, the
 * gallery tiles and the two action pills; the strips' start/end clearance is the ring's reach while the band's
 * vertical air stays the height budget's own, so every certified band holds. Source pins, the module's idiom.
 */
class HomeCustomEnrichRingTest {

    @Test
    fun `the strips clear the ring's reach on the axis that clips, and the band keeps its own air`() {
        assertEquals(MimirFocusTileRingReach.value.toInt(), EnrichRingClearanceDp)
        assertEquals(6, EnrichGalleryVerticalAirDp)
        // The bands the viewport budget certified: unchanged by the clearance split.
        assertEquals(148, homeCustomEnrichGalleryMinHeightDp(EnrichMediaKind.ICON, compactSquare = true))
        assertEquals(124, homeCustomEnrichGalleryMinHeightDp(EnrichMediaKind.ICON, compactSquare = false))
        assertEquals(
            homeCustomEnrichGalleryMinHeightDp(EnrichMediaKind.HERO, compactSquare = false),
            kotlin.math.ceil(HomeCustomEnrichHeroSelectionWidthDp / homeCustomEnrichPreviewStageAspect(EnrichMediaKind.HERO))
                .toInt() + EnrichGalleryVerticalAirDp * 2,
        )
        // The follow-scroll seat cancels the clearance out; the effective width reads it once per edge.
        assertEquals(128f, homeCustomEnrichGalleryScrollOffsetDp(2, 112, 16), 0f)
        // A viewport narrower than the authored 144 dp hero: the tile shrinks by one clearance per edge.
        assertEquals(
            160 - EnrichRingClearanceDp * 2,
            homeCustomEnrichEffectiveTileWidthDp(EnrichMediaKind.HERO, compactSquare = false, galleryViewportWidthDp = 160),
        )
    }

    @Test
    fun `the search field, the tiles and the pills ring once, before their clip, with no grow`() {
        val source = pickerSource()
        assertFalse(source.contains(".mimirFocusRing("))
        assertFalse(source.contains("focusedScale"))
        val fieldChain = source.substringAfter("val key = homeCustomEnrichSearchKey(state)").substringBefore(".padding(")
        assertTrue(fieldChain.contains("shape = MimirRingShape.Row(MimirSpacing.radiusCard)"))
        assertTrue("the ring paints the field's glass, so the wash lands over it", fieldChain.contains("fill = SolidColor("))
        assertTrue(fieldChain.indexOf(".mimirSelectionRing(") < fieldChain.indexOf(".clip("))
        val tile = source.substringAfter("private fun EnrichAssetTile(").substringBefore(".clearAndSetSemantics {")
        assertTrue(tile.contains("shape = MimirRingShape.Card(MimirSpacing.radiusCard)"))
        assertTrue(tile.indexOf(".mimirSelectionRing(") < tile.indexOf(".clip("))
        // #263: the footer's actions are unselected etched chips wearing the top row's pill ring: a Pill ring, no flare, no resting
        // outline, no inset, and no glass fill painted by the ring. The ring is on the plate it hugs, inside the 48 dp hit box.
        val pills = source.substringAfter("val key = homeCustomEnrichUploadKey(state)").substringBefore("// The legend takes only the room")
        assertEquals(2, Regex("""EnrichActionChip\(""").findAll(pills).count())
        assertFalse(pills.contains("flare = true"))
        assertFalse(pills.contains("fill = SolidColor("))
        assertFalse(pills.contains("restingOutline"))
        assertFalse(pills.contains("homeCustomEnrichGlassControlFill"))
        val chip = chipSource()
        assertTrue(chip.contains(".mimirFocusable(authority, key, HomeCustomEnrichScope, autoBringIntoView = false)"))
        assertTrue(chip.contains(".mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)"))
        // The plate is the shared MimirEtchedChipPlate: the ring is in its modifier, which it applies before its surface, and it takes the lit flag.
        assertTrue(chip.contains("MimirEtchedChipPlate("))
        assertTrue(chip.contains("focused = focused,"))
        assertTrue("the ring is in the plate's modifier", chip.indexOf(".mimirSelectionRing(") in chip.indexOf("MimirEtchedChipPlate(") until chip.indexOf("focused = focused,"))
        assertFalse("no surface of its own", chip.contains(".mimirEtchedChipSurface("))
        assertFalse(chip.contains("flare ="))
        assertFalse(chip.contains("fill ="))
        assertFalse(chip.contains("restingOutline ="))
        assertFalse(chip.contains("inset ="))
        // The top row's own controls wear the same ring in the same place (reported double rim): a Pill ring, no flare, no inset,
        // on the PLATE and before its etched surface; the 48 dp hit box carries focus and no ring of its own.
        val top = topRowSource()
        val ring = top.substringAfter("private fun customMediaChipRing").substringBefore("/** The ring for a row of a panel")
        assertTrue(ring.contains("shape = MimirRingShape.Pill, edgeFlush = true)"))
        assertFalse(ring.contains("flare ="))
        assertFalse(ring.contains("inset ="))
        val target = top.substringAfter("private fun customMediaChipTarget").substringBefore("/** The ring of a top-row control")
        assertTrue(target.contains(".mimirFocusable(authority, key, HomeCustomEnrichScope, autoBringIntoView = false)"))
        assertFalse("the hit box rings nothing", target.contains("mimirSelectionRing"))
        assertFalse("and no inset ring floats inside it", target.contains("inset ="))
        assertTrue("the focused chip is drawn above its neighbours, so a later plate cannot cover its ring", target.contains(".zIndex("))
        // Search (the name and the mark) and the Filters mark put it on their plates, before the etched surface; Media and the chips lend it to the
        // chip's plate through the plate modifier.
        assertEquals(3, Regex("""customMediaChipRing\(key, focused\)\)\s*\.mimirEtchedChipSurface""").findAll(top).count())
        assertEquals(2, Regex("""plateModifier = customMediaChipRing\(key, focused\)""").findAll(top).count())
        assertEquals("nothing but the panel's rows rings the 48 dp box with an inset", 1, Regex("""inset = MimirSpacing\.xs""").findAll(top).count())
        val plate = sourceOf("CompanionActivityAchievementFilterHeader.kt").substringAfter("private fun ActivityChipPlate(")
        assertTrue("the plate modifier leads the chain, so the ring is before the surface", plate.indexOf("plateModifier") in 0 until plate.indexOf(".mimirEtchedChipSurface("))
    }

    @Test
    fun `the top row is held a ring's whole reach from the clip by the panel's side air, at the start and at the end`() {
        // Nothing is added at either end: the panel's padding and the page's side air (see CustomMediaSweepRulesTest) are the whole reach.
        assertEquals(0.dp, customMediaTopRowEdgeInset(compactTypography = false))
        assertEquals(0.dp, customMediaTopRowEdgeInset(compactTypography = true))
        // The call site hands the row the page's column start and its end inset.
        assertTrue(
            pickerSource().contains(
                ".padding(start = customMediaColumnStart(compactTypography), end = customMediaTopRowEdgeInset(compactTypography)),",
            ),
        )
    }

    @Test
    fun `the gallery strip pads its start and end by the reach and its top and bottom by the band's air`() {
        val source = pickerSource()
        // Top and bottom: the band's own air, plus the bleed that makes it the ring's reach too (the viewport is taller by it; the reported band is not).
        assertTrue(source.contains("horizontal = EnrichRingClearanceDp.dp,"))
        assertTrue(source.contains("vertical = (EnrichGalleryVerticalAirDp + EnrichGalleryRingVerticalBleedDp).dp,"))
        // The chip strip wears no ring , so it keeps its own 6 dp air, not the ring's reach.
        assertTrue(source.contains(".padding(horizontal = EnrichChipStripClearanceDp.dp, vertical = EnrichTargetRingAirDp.dp)"))
        assertEquals(6, EnrichChipStripClearanceDp)
    }

    @Test
    fun `the filter chips keep their metallic treatment and activate through the helper`() {
        // no chip around the metallic label. Until a ruling settles that against the ring, the
        // ellipse and the lift stay, and only the second focus node goes.
        val source = pickerSource()
        assertEquals(2, Regex("""Modifier\.enrichFilterTextFocus\(focused, palette\)""").findAll(source).count())
        assertTrue(source.contains(".mimirActivatable(label = filter.label) {"))
        assertTrue(source.contains(".mimirActivatable(label = option, enabled = enabled) {"))
    }

    @Test
    fun `the search field, the tiles and the pills keep the resting outline the old ring drew`() {
        // w_rev_ring_2: the field and the gallery tile. The Upload and Stop chips  ring as a top-row chip, with no resting outline.
        val source = pickerSource()
        assertEquals(2, Regex("""restingOutline = true""").findAll(source).count())
        val tile = source.substringAfter("private fun EnrichAssetTile(").substringBefore(".clip(")
        assertTrue(tile.contains("restingOutline = true"))
    }

    @Test
    fun `the panel's edge controls clear the ring and flare its clip would cut, inside the certified floor`() {
        assertEquals(MimirSpacing.sm, homeCustomEnrichPanelPadding(compactTypography = true))
        assertEquals(MimirSpacing.md, homeCustomEnrichPanelPadding(compactTypography = false))
        assertEquals(6.dp, homeCustomEnrichEdgeRingInset(MimirSpacing.sm, flare = false))
        assertEquals(2.dp, homeCustomEnrichEdgeRingInset(MimirSpacing.md, flare = false))
        assertEquals(12.dp, homeCustomEnrichEdgeRingInset(MimirSpacing.sm, flare = true))
        assertEquals(8.dp, homeCustomEnrichEdgeRingInset(MimirSpacing.md, flare = true))
        assertEquals(0.dp, homeCustomEnrichEdgeRingInset(MimirFocusTileRingReach + MimirFocusFlareReach, flare = true))
        listOf(true, false).forEach { compact ->
            val pad = homeCustomEnrichPanelPadding(compact)
            // Start edge: the field's ring, and the pills' ring plus flare, clear the clip.
            assertTrue(pad + homeCustomEnrichEdgeRingInset(pad, flare = false) >= MimirFocusTileRingReach)
            assertTrue(
                pad + homeCustomEnrichEdgeRingInset(pad, flare = true) >= MimirFocusTileRingReach + MimirFocusFlareReach,
            )
            // Bottom edge: the settled ring always clears.
            assertTrue(pad + EnrichFooterBottomAirDp.dp >= MimirFocusTileRingReach)
        }
        // At md the flare clears the bottom too; sm's 4 dp flare residual waits on a re-certified 360x360 budget.
        assertTrue(MimirSpacing.md + EnrichFooterBottomAirDp.dp >= MimirFocusTileRingReach + MimirFocusFlareReach)
        // The bottom air is carved out of the 56 dp floor, never added to it.
        assertEquals(56, homeCustomEnrichActionRowMinHeightDp())
        assertTrue(EnrichFooterBottomAirDp <= homeCustomEnrichActionRowMinHeightDp() - MimirSpacing.minTarget.value.toInt())

        val source = pickerSource()
        assertTrue(source.contains("contentPadding = homeCustomEnrichPanelPadding(compactTypography),"))
        assertFalse(source.contains("contentPadding = if (compactTypography) MimirSpacing.sm else MimirSpacing.md,"))
        val topBand = source.substringAfter(".heightIn(min = homeCustomEnrichTopBandMinHeightDp().dp)")
            .substringBefore("EnrichSearchRow(")
        assertTrue(
            topBand.contains(
                ".padding(start = homeCustomEnrichEdgeRingInset(homeCustomEnrichPanelPadding(compactTypography), flare = false))",
            ),
        )
        val footer = source.substringAfter(".heightIn(min = homeCustomEnrichActionRowMinHeightDp().dp)")
            .substringBefore("if (showUpload)")
        // The row starts where its caller says; the legacy default is the ring-and-flare reach the padding lacks, and the Customize media
        // page's column already holds it (CustomMediaSweepRulesTest).
        assertTrue(footer.contains("start = startInset,"))
        assertTrue(
            source.contains(
                "startInset: androidx.compose.ui.unit.Dp = homeCustomEnrichEdgeRingInset(homeCustomEnrichPanelPadding(compactTypography), flare = true),",
            ),
        )
        assertTrue(footer.contains("bottom = EnrichFooterBottomAirDp.dp,"))
    }

    private fun pickerSource(): String = sourceOf("HomeCustomEnrichPicker.kt")

    private fun chipSource(): String = sourceOf("CustomMediaActionChip.kt")

    private fun topRowSource(): String = sourceOf("CustomMediaTopRow.kt")

    private fun sourceOf(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()
}
