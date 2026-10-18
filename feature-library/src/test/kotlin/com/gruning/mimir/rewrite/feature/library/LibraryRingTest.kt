package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library after the ring migration : every focusable in this module rings once through
 * the one selection ring, before its clip, with no grow; the search overlay's lists and the add picker's list
 * clear the reach; every `.clickable` beside the focus authority is gone. Source pins, the module's idiom.
 */
class LibraryRingTest {

    private val files = listOf("LibraryComponents.kt", "LibraryScreen.kt", "LibrarySearchOverlay.kt", "GamelistMedia.kt", "LibraryGameShelf.kt")

    @Test
    fun `no library surface wears the old ring or grows on focus`() {
        files.forEach { name ->
            val code = codeOf(name)
            assertFalse("$name still calls mimirFocusRing", code.contains(".mimirFocusRing("))
            assertFalse("$name still names a focusedScale", code.contains("focusedScale"))
        }
    }

    @Test
    fun `the platform tile and the CTA ring on the card corner, the tile as a card and the CTA as a row`() {
        val components = codeOf("LibraryComponents.kt")
        val tile = components.substringAfter("fun PlatformTile(").substringBefore("@Composable")
        assertTrue(tile.contains("shape = MimirRingShape.Card(MimirSpacing.radiusCard)"))
        assertTrue(tile.indexOf(".mimirSelectionRing(") < tile.indexOf(".clip("))
        assertTrue(components.contains("shape = MimirRingShape.Row(MimirSpacing.radiusCard)"))
        assertFalse("the CTA's accentGlow fill is the ring's wash now", components.contains("palette.accentGlow"))
    }

    @Test
    fun `the add picker and the search launcher ring, and the picker's list clears the reach`() {
        val screen = codeOf("LibraryScreen.kt")
        assertTrue(screen.contains("shape = MimirRingShape.Key(NativeAddPickerCloseCorner)"))
        assertTrue(screen.contains("val closeShape = RoundedCornerShape(NativeAddPickerCloseCorner)"))
        assertTrue(screen.contains("shape = MimirRingShape.Row(NativeAddPickerControlRadius)"))
        assertTrue(screen.contains("shape = MimirRingShape.Row(NativeAddPickerRowRadius)"))
        assertTrue(screen.contains("identity = LibrarySearchLauncherKey, shape = MimirRingShape.Pill"))
        assertTrue(screen.contains("identity = iconPath ?: fallbackTitle, shape = MimirRingShape.Card(MimirSpacing.radiusCard)"))
        assertFalse("the picker row's focus shadow retired with the ring", screen.contains(".shadow("))
        assertTrue(screen.contains("top = MimirFocusTileRingReach,\n                            end = 4.dp,"))
        assertFalse(screen.contains("palette.focusRing"))
    }

    @Test
    fun `the search overlay's rows ring on their named corners and its lists clear the reach`() {
        val overlay = codeOf("LibrarySearchOverlay.kt")
        // The result rows (the fields are etched capsules now); one corner symbol for the clip and the ring.
        assertEquals(1, Regex("""shape = MimirRingShape\.Row\(LibrarySearchRowCorner\)""").findAll(overlay).count())
        assertEquals(1, Regex("""shape = MimirRingShape\.Row\(LibrarySearchPickerRowCorner\)""").findAll(overlay).count())
        assertEquals(0, Regex("""RoundedCornerShape\(1[02]\.dp\)""").findAll(overlay).count())
        // The result list pads its top only: a vertical list clips 30 dp out on its sides, so a ring reaches past a row's edge and the rows share the fields' left edge.
        assertTrue(overlay.contains("contentPadding = PaddingValues(\n                            top = MimirFocusTileRingReach,\n                            end = LibrarySearchResultEndPaddingDp.dp,"))
        assertFalse(overlay.contains("start = MimirFocusTileRingReach"))
        assertTrue(overlay.contains("contentPadding = PaddingValues(MimirFocusTileRingReach)"))
        assertEquals(0, Regex("""spacedBy\(7\.dp\)""").findAll(overlay).count())
    }

    @Test
    fun `the Get Art pill fades below its ring on a ModulateAlpha layer`() {
        val carousel = codeOf("GamelistMedia.kt")
        val pill = carousel.substringAfter("identity = GamelistGetArtKey, shape = MimirRingShape.Pill, edgeFlush = true)")
        assertTrue(pill.take(260).contains("compositingStrategy = CompositingStrategy.ModulateAlpha"))
        assertFalse(carousel.contains("Modifier.alpha(0.45f)"))
    }

    @Test
    fun `the Keep and Hide choices and the search surfaces keep the resting outline the old ring drew`() {
        // w_rev_ring_2: mimirSelectionRing defaults the hairline and the rest dim off; mimirFocusRing drew both.
        val cta = codeOf("LibraryComponents.kt").substringAfter("fun LibraryActionTile(").substringBefore(".clip(")
        assertTrue("a fill-less choice needs its resting edge", cta.contains("restingOutline = true"))
        assertTrue("and the old 0.88 rest dim", cta.contains("restingAlpha = MimirFocusRestingAlpha"))
        // Issue #265: the gamelist's search launcher is an etched chip, whose metal has its own rim, so its ring draws no hairline under it; the surface is drawn
        // before the ring (and the controller modifier that carries it), so the ring's wash shows on the metal.
        val launcher = codeOf("LibraryScreen.kt")
        assertTrue(launcher.contains("identity = LibrarySearchLauncherKey, shape = MimirRingShape.Pill, edgeFlush = true)"))
        assertFalse(launcher.contains("identity = LibrarySearchLauncherKey, shape = MimirRingShape.Pill, restingOutline = true)"))
        assertEquals(2, Regex("""restingOutline = true""").findAll(codeOf("LibrarySearchOverlay.kt")).count())
    }

    @Test
    fun `the search overlay's fields are etched capsules with the ring on the plate, its rows keep one ground, and no spine runs through its results`() {
        val overlay = codeOf("LibrarySearchOverlay.kt")
        val field = overlay.substringAfter("private fun LibrarySearchControl(").substringBefore("@Composable\nprivate fun LibrarySearchResultRow(")
        assertTrue(field.contains(".mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)"))
        assertTrue(field.indexOf(".mimirSelectionRing(focused = focused, identity = key, shape = MimirRingShape.Pill, edgeFlush = true)") in 0 until field.indexOf(".mimirEtchedChipSurface("))
        assertFalse("no fill, no clip, no hairline on a field", field.contains("fill =") || field.contains(".clip(") || field.contains("restingOutline"))
        val row = overlay.substringAfter("private fun LibrarySearchResultRow(").substringBefore("@Composable")
        assertTrue(row.contains("fill = SolidColor(palette.surfaceRaised.copy(alpha = 0.82f)),"))
        assertFalse("the ground does not change on focus", row.contains("0.98f") || row.contains("if (focused) 0."))
        assertEquals("both the panel and the picker are one page of rows", 2, Regex("""mimirNorseBookFrame\(\w+, spine = false\)""").findAll(overlay).count())
    }

    @Test
    fun `the search overlay's fields stand a ring's whole reach below its count line`() {
        val overlay = codeOf("LibrarySearchOverlay.kt")
        val call = overlay.substringAfter("Box(Modifier.padding(top = MimirFocusTileRingReach)) {").substringBefore("if (games.isEmpty())")
        assertTrue("the top controls sit inside that room, on top of the column's own gap", call.contains("LibrarySearchTopControls("))
    }

    @Test
    fun `the Get Art chip, the gamelist search bar and the favourites chips are etched chips, not filled capsules`() {
        val media = codeOf("GamelistMedia.kt")
        val chip = media.substringAfter("internal fun GamelistMediaActionChip(").substringBefore("private val PlatformCoverAspects")
        assertTrue("its rim is handed to the ring while focused", chip.contains(".mimirEtchedChipSurface(palette, warmed = false, focused = focused, edgeFlush = true)"))
        assertFalse("no fill, no clip, no tint", chip.contains(".background(") || chip.contains(".clip(") || chip.contains("fill"))
        val screen = codeOf("LibraryScreen.kt")
        val search = screen.substringAfter("fun GamelistSearchBar(").substringBefore("private fun launchStatusText(")
        assertEquals("both the full and the small bar wear the surface, handing its rim to the ring", 2, Regex("""\.mimirEtchedChipSurface\(palette, warmed = false, focused = controllerEnabled && focused, edgeFlush = true\)""").findAll(search).count())
        assertFalse("and neither is filled", search.contains(".background(MimirColors.SurfaceRaised)"))
        // The ring (inside controllerModifier) is on the PLATE and BEFORE the surface in both bars, so it hugs the metal with no inset and its wash is under it.
        assertEquals("the ring comes before the surface in both", 2, Regex("""\.then\(controllerModifier\)\s*\.mimirEtchedChipSurface""").findAll(search).count())
        assertFalse("and takes no inset", search.contains("inset ="))
        assertTrue("the magnifier is the drawn glyph at the one search size, not a font speck", search.contains("MimirSearchGlyph(color = MimirColors.TextSecondary)") && !search.contains("\"⌕\""))
        // The shelf's Add pill is the PRIMARY chip: no gradient, no bloom, no border; the ring is on the plate, before the surface.
        val add = screen.substringAfter("fun ShelfAddPill(").substringBefore("internal fun gamelistUsesLightMediaContrast(")
        assertTrue(add.contains(".mimirEtchedChipSurface(palette, focused = focused, primary = true, edgeFlush = true)"))
        assertTrue(add.indexOf(".mimirSelectionRing(") in 0 until add.indexOf(".mimirEtchedChipSurface("))
        assertFalse("no fill, no border, no bloom, no clip", add.contains(".background(") || add.contains(".border(") || add.contains("ctaMaterial") || add.contains("Animatable") || add.contains(".clip("))
        val strip = screen.substringAfter("private fun FavoritesSegmentStrip(").substringBefore("private fun NativeFavoritesRowDetailView(")
        assertTrue("warmed when it is the active segment", strip.contains(".mimirEtchedChipSurface(palette, warmed = selected)"))
        assertFalse("no fill and no outline", strip.contains(".background(") || strip.contains(".border(") || strip.contains("accentGlow"))
    }

    private fun codeOf(name: String): String = listOf(
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
    ).first(File::isFile).readText()
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("""//[^\n]*"""), "")
}
