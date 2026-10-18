package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings after the ring migration.
 * Source pins, the module's idiom: no Compose harness runs here, and whether a ring clips is device-only.
 */
class SettingsSelectionRingTest {

    @Test
    fun `the rail rings, keeps its ember plate as active, and spaces its entries for the ring`() {
        val home = source("SettingsHome.kt")
        val rail = home.substringAfter("private fun RailItem(").substringBefore("internal fun openDetail(")
        assertTrue(rail.contains(".mimirSelectionRing("))
        assertTrue("the open category's plate is the family's active", rail.contains("active = active,"))
        assertTrue("bright fields keep the readable accent", rail.contains("accent = interactiveAccent,"))
        assertTrue("revealed with the ring's reach", rail.contains("revealOutset = MimirFocusTileRingReach,"))
        assertTrue("ring before clip", rail.indexOf(".mimirSelectionRing(") < rail.indexOf(".clip(shape)"))
        assertFalse("no private border ring", rail.contains(".border("))
        val pane = home.substringAfter("private fun SettingsRailPane(").substringBefore("private fun SettingsDetailPane(")
        assertTrue("siblings 4 dp apart so all seven stand whole at rest (previously 8 dp)", pane.contains("verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),"))
        assertFalse("not the old 8 dp gap", pane.contains("verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),"))
        val rail2 = home.substringAfter("private fun RailLabel(").substringBefore("internal fun settingsRailLabelSizeSp(")
        assertTrue("a rail label is set whole: stepped down to the floor against its own room, ellipsis only past that", rail2.contains("settingsRailLabelSizeSp(") && rail2.contains("MimirTypeStep.Micro") && rail2.contains("overflow = TextOverflow.Ellipsis"))
        assertTrue(
            "ring room at both ends sits inside the scroll host, and the viewport bleeds up so no entry moves (walk-32m)",
            pane.indexOf(".settingsRailBleedUp(SettingsRailRingBleed)") in 0 until pane.indexOf(".verticalScroll(railScroll)") &&
                pane.contains(".padding(top = MimirFocusTileRingReach, bottom = MimirFocusTileRingReach)"),
        )
        assertTrue(
            "once scrolled, the bleed above the pane is cut (outside the bleed, so it clips at the bounds the bleed reports): no entry draws up under the shell's badge (alpha-cert sweep 10-09)",
            pane.indexOf(".settingsRailClipBleedWhenScrolled(railScroll)") in 0 until pane.indexOf(".settingsRailBleedUp(SettingsRailRingBleed)"),
        )
        assertFalse(home.contains("spacedBy(6.dp)"))
    }

    @Test
    fun `rows, the gold CTA and the tonal CTA wear the one ring on their own shapes`() {
        val components = source("SettingsComponents.kt")
        val row = components.substringAfter("fun SettingsRow(").substringBefore("fun SettingsInfoRow(")
        assertTrue(row.contains("shape = MimirRingShape.Row(MimirSpacing.radiusCard),"))
        assertTrue(row.contains("restingAlpha = MimirFocusRestingAlpha,"))
        assertTrue(row.contains("outlineColor = palette.glassRestingOutline(),"))
        // Sweep 10-09 (F7): the reveal asks the pane for the ring's reach PLUS a little air, so a halo is never cut flat at the scroll edges (SettingsRevealOutset).
        assertTrue(row.contains("revealOutset = SettingsRevealOutset)"))
        assertFalse(row.contains(".mimirGlassRow("))
        val gold = components.substringAfter("fun GradientCta(").substringBefore("fun TonalCta(")
        // The arrival flare is used on onboarding pills; neither Settings CTA flares.
        // Update : the gold CTA is the PRIMARY etched chip and the tonal one the unselected chip; neither is filled, so neither takes the filled gap,
        // a fill handed to the ring, or a resting hairline (the metal has its own rim). The ring is on the plate itself, drawn BEFORE the surface.
        assertTrue("a primary etched chip is unfilled, so no gap", gold.contains("shape = MimirRingShape.Pill, flare = false"))
        assertFalse("no filled gap, no fill", gold.contains("filled = ") || gold.contains("fill = "))
        assertTrue(gold.contains(".mimirEtchedChipSurface(palette, focused = focused, primary = true, edgeFlush = true)"))
        assertFalse("a primary is never warmed (the selected look)", gold.contains("warmed = "))
        assertTrue("the ring is drawn before the surface", gold.indexOf(".mimirSelectionRing(") < gold.indexOf(".mimirEtchedChipSurface("))
        assertFalse("and takes no inset", gold.contains("inset ="))
        assertFalse("no arrival flare off onboarding", gold.contains("flare = true"))
        assertTrue(gold.contains("revealOutset = SettingsRevealOutset)"))
        val tonal = components.substringAfter("fun TonalCta(").substringBefore("fun SampleDataNote(")
        assertTrue(tonal.contains("shape = MimirRingShape.Pill, flare = false"))
        assertFalse("no resting hairline and no fill on the ring", tonal.contains("restingOutline") || tonal.contains("fill = ") || tonal.contains(".background("))
        assertTrue("the unselected chip, its rim handed to the ring while focused", tonal.contains(".mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)"))
        assertTrue("the ring is drawn before the surface", tonal.indexOf(".mimirSelectionRing(") < tonal.indexOf(".mimirEtchedChipSurface("))
        assertFalse("and takes no inset", tonal.contains("inset ="))
        assertFalse("no arrival flare off onboarding", tonal.contains("flare = true"))
        assertTrue(tonal.contains("revealOutset = SettingsRevealOutset)"))
        assertFalse("one colour: never focusRing on a ring", components.contains("palette.focusRing"))
    }

    @Test
    fun `the theme cards ring in the palette they preview, with no scale and no clickable`() {
        val gallery = source("ThemeGallery.kt")
        val card = gallery.substringAfter("private fun ThemePreviewCard(").substringBefore("private val ThemePreviewCardCorner")
        assertTrue(card.contains("palette = palette,"))
        assertTrue(card.contains("shape = MimirRingShape.Card(ThemePreviewCardCorner),"))
        assertTrue(card.contains(".mimirActivatable(label = palette.id.label, authority = authority, key = focusKey, onActivate = onActivate)"))
        assertFalse(card.contains(".clickable("))
        assertFalse(card.contains("focusedScale"))
        assertFalse(gallery.contains(".mimirFocusRing("))
    }

    @Test
    fun `the colour dial rings the detent's seat, not the swatch that grows and dims`() {
        val dial = source("MimirColorDial.kt")
        val detent = dial.substringAfter("private fun DialDetent(").substringBefore("private fun stepDial(")
        assertTrue(detent.contains("shape = MimirRingShape.Circle,"))
        assertTrue(detent.contains("identity = stop,"))
        assertTrue("the ring sits on the 52 dp seat", detent.indexOf(".size(52.dp)") < detent.indexOf(".mimirSelectionRing("))
        assertTrue("and above the alpha'd swatch", detent.indexOf(".mimirSelectionRing(") < detent.indexOf(".alpha("))
        assertFalse(detent.contains("dialDetentRing"))
        // One ring colour everywhere: the dial's ring follows the live palette (ring stop + ringPreview), never the
        // authored one, so the focused detent's ring is the colour being previewed.
        val ring = detent.substringAfter(".mimirSelectionRing(").substringBefore(")")
        assertFalse("the dial's ring reads LocalMimirPalette", ring.contains("palette ="))
        assertFalse(ring.contains("authoredPalette"))
    }

    @Test
    fun `the colour dial's scroll content leaves the end detents' rings their reach`() {
        val dial = source("MimirColorDial.kt")
        val row = dial.substringAfter(".horizontalScroll(dialScroll, enabled = false)").substringBefore("verticalAlignment")
        assertTrue("reach padded inside the scroll host", row.contains(".padding(horizontal = MimirFocusTileRingReach)"))
        assertTrue("padding before the seats' width", row.indexOf(".padding(horizontal = MimirFocusTileRingReach)") < row.indexOf(".width(seatWidth * stops.size)"))
        assertTrue("centring counts the reach", dial.contains("ringReachDp = MimirFocusTileRingReach.value,"))
        assertTrue("scroll target counts the reach", dial.contains("ringReachPx = MimirFocusTileRingReach.toPx(),"))
    }

    private fun source(name: String): String = listOf(
        File("../core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/select/$name"),
        File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/select/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
        File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
    ).first(File::isFile).readText()
}
