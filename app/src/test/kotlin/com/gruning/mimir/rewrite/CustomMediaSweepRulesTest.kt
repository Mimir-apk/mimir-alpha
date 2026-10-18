package com.gruning.mimir.rewrite

import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.resolve
import com.gruning.mimir.rewrite.feature.library.HomeShelfRow
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 10-09 visual sweep of the Customize media page (the lead's list, P1 to P3): the rules that are pure, and source pins for the ones
 * that are layout (their pixels are drawn and measured by [CustomMediaMainGrammarRenderTest], [CustomMediaTileRenderTest] and
 * [CustomMediaPageCaptureTest]). Source pins, the module's idiom: each names the sweep finding it holds.
 */
class CustomMediaSweepRulesTest {

    private val menu = HomeTileMenuState(
        row = HomeShelfRow(
            itemId = HomeShelfItemId.Emulated(GameId("game-1")),
            rank = 1L,
            addedAtMillis = 10L,
            title = "Chrono Trigger",
        ),
        removable = true,
    )
    private val types = homeTileCustomMediaTypes(menu)

    private fun page(searchStatus: HomeCustomEnrichSearchStatus = HomeCustomEnrichSearchStatus.Idle, withTypes: Boolean = true) = HomeCustomEnrichState(
        sessionId = "sweep",
        kind = EnrichMediaKind.HERO,
        cacheKey = "game-1",
        gameTitle = "Chrono Trigger",
        queryDraft = "Chrono Trigger",
        searchStatus = searchStatus,
        mediaTypes = if (withTypes) types else emptyList(),
    )

    // ── P2 5: one column ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `the page keeps one column, which stands a whole ring's reach from the panel's clip at both paddings`() {
        // The column is the content's own edge: nothing in the footer or the top row needs more, since Upload's ring does not flare.
        listOf(true, false).forEach { compact ->
            assertEquals("the column is the content's edge ($compact)", 0.dp, customMediaColumnStart(compact))
            assertEquals("the footer stands on the column ($compact)", 0.dp, customMediaFooterInset(compact))
            assertEquals("the top row's end holds nothing more ($compact)", 0.dp, customMediaTopRowEdgeInset(compact))
            // The panel's padding and the page's side air together are a ring's whole reach (14 dp): a focused Upload, Default tile or Search plate
            // on the column is clear of the panel's clip, and the halo is not narrowed.
            assertEquals(
                "padding + side air is the ring's reach ($compact)",
                MimirFocusTileRingReach,
                homeCustomEnrichPanelPadding(compact) + customMediaPanelSideAir(compact),
            )
        }
        // The side air is what the padding lacks: 6 dp at compact's 8 dp, and the rest at md's.
        assertEquals(6.dp, customMediaPanelSideAir(compactTypography = true))
        assertEquals(MimirFocusTileRingReach - homeCustomEnrichPanelPadding(false), customMediaPanelSideAir(compactTypography = false))
        // The panel is that much wider on each side and its content is padded by the same, so the content keeps the width it had; the
        // legacy picker (no Media chip) takes none.
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        assertTrue(picker.contains("val panelSideAir = if (state.mediaTypes.isNotEmpty()) customMediaPanelSideAir(compactTypography) else 0.dp"))
        assertTrue(picker.contains("680.dp) + panelSideAir * 2, viewport.width)"))
        assertTrue(picker.contains(".padding(horizontal = panelSideAir + backdropBleed, vertical = backdropBleed),"))
    }

    @Test
    fun `the page's title, top row, body and footer are all seated on that column`() {
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        // The title.
        assertTrue(picker.contains(".padding(start = if (state.mediaTypes.isNotEmpty()) customMediaColumnStart(compactTypography) else 0.dp)"))
        // The top row, the status line, strip and footer (CustomMediaBody).
        assertTrue(picker.contains(".padding(start = customMediaColumnStart(compactTypography), end = customMediaTopRowEdgeInset(compactTypography)),"))
        val body = sourceOf("CustomMediaTopRow.kt").substringAfter("internal fun CustomMediaBody(").substringBefore("internal fun CustomMediaPanels(")
        assertTrue(body.contains(".padding(start = customMediaColumnStart(compactTypography))"))
        assertTrue(body.contains("startInset = customMediaFooterInset(compactTypography),"))
        // The strip's first tile starts ON the column: its viewport bleeds the ring's reach past each side and pads its content by the same.
        assertTrue(picker.contains(".enrichStripBleed(EnrichRingClearanceDp.dp, EnrichGalleryRingVerticalBleedDp.dp)"))
        assertTrue(picker.contains("val galleryViewportWidthDp = maxWidth.value.toInt() + EnrichRingClearanceDp * 2"))
        // The preview stands on it too: no cue column, no ring clearance, no rune inset on either side of the caption or the stage.
        assertFalse("no cue column pushes the strip or the preview in", picker.contains("EnrichGalleryCueWidthDp"))
        val preview = picker.substringAfter("private fun EnrichPreviewBlock(").substringBefore("private fun EnrichExpandedMediaPreview(")
        assertFalse(preview.contains("padding(horizontal ="))
        // The brackets stand ON the stage's edge, which is the column (half the stroke in); the media keeps its own air inside them.
        assertTrue(preview.contains("val inset = stroke / 2f"))
        assertTrue(preview.contains(".testTag(CustomMediaPreviewStageTag)"))
        assertTrue(preview.contains(".padding(EnrichPreviewRuneInsetDp.dp)"))
    }

    // ── P2 6, 7: the chevrons and the gap ──────────────────────────────────────────────────────────

    @Test
    fun `the strip's chevrons are drawn, tagged, and shown only while the strip can scroll that way`() {
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        assertTrue(picker.contains("if (scroll.canScrollBackward) {"))
        assertTrue(picker.contains("if (scroll.canScrollForward) {"))
        val tile = sourceOf("CustomMediaTile.kt")
        val cue = tile.substringAfter("internal fun EnrichGalleryCue(").substringBefore("/** One bar's height")
        assertTrue("a drawn chevron, never a rune", cue.contains("drawLine(") && !cue.contains("Text("))
        assertTrue(cue.contains("CustomMediaGalleryCueTag"))
    }

    @Test
    fun `the status line and the strip stand a gap apart that clears the lit tile's halo`() {
        // The halo reaches the ring's reach past a tile; the band's own air takes 6 dp of it, so 8 dp stand over the band.
        val haloOverBand = EnrichRingClearanceDp - EnrichGalleryVerticalAirDp
        assertEquals(12, EnrichStatusToStripGapDp)
        assertTrue(EnrichStatusToStripGapDp > haloOverBand)
        // The page's column already spaces its rows by sm (md at most); the padding adds what the gap lacks, so the gap is the gap, and it
        // comes out of the band's minimum rather than on top of it: the certified height budget holds. The compact page, whose height is all
        // spent, keeps its row gap.
        val body = sourceOf("CustomMediaTopRow.kt").substringAfter("internal fun CustomMediaBody(").substringBefore("internal fun CustomMediaPanels(")
        assertTrue(body.contains("(EnrichStatusToStripGapDp.dp - (if (compactTypography) MimirSpacing.xs else MimirSpacing.sm)).coerceAtLeast(0.dp)"))
        assertTrue(body.contains(".padding(top = statusGap)"))
        assertTrue(body.contains(".heightIn(min = homeCustomEnrichGalleryMinHeightDp(shown.kind, compactSquare).dp - statusGap)"))
        assertTrue(MimirSpacing.sm.value.toInt() < EnrichStatusToStripGapDp)
    }

    // ── The certified height budget: the bar is carved out of the tile, never added to it ──────────────

    @Test
    fun `a tile is the certified band less its air, its bar is part of that, and the art is what the bar leaves`() {
        val barDp = 21
        EnrichMediaKind.values().forEach { kind ->
            listOf(true, false).forEach { compact ->
                val band = homeCustomEnrichGalleryMinHeightDp(kind, compact)
                val tile = homeCustomEnrichTileHeightDp(kind, compact)
                assertEquals("$kind/$compact: the tile fills the band less its air above and below", band, tile + EnrichGalleryVerticalAirDp * 2)
                assertEquals("$kind/$compact: the art and the bar are the tile", tile, homeCustomEnrichTileArtHeightDp(kind, compact, barDp) + barDp)
            }
        }
        assertEquals(homeCustomEnrichCoverTileHeightDp(compactSquare = false), homeCustomEnrichTileHeightDp(EnrichMediaKind.COVER, compactSquare = false))
        // The width of every non-cover tile is its art's own shape at the art's height (and never more than the kind's authored width).
        assertEquals(124 - 12 - barDp, homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.ICON, false, 0, barDp))
        assertEquals(kotlin.math.round(homeCustomEnrichTileArtHeightDp(EnrichMediaKind.HERO, false, barDp) * 16f / 9f).toInt(), homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.HERO, false, 0, barDp))
        assertTrue(homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.HERO, false, 0, barDp) <= HomeCustomEnrichHeroSelectionWidthDp)
        assertEquals("no bar: the authored widths, as before", 112, homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.ICON, false, 0, 0))
        assertEquals(HomeCustomEnrichHeroSelectionWidthDp, homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.HERO, false, 0, 0))
        // A cover's width is its art's aspect times the art's height; the floor is the old portrait tile.
        assertEquals(80, homeCustomEnrichCoverTileWidthDp(false, 0.72f, 0, barDp))
        assertEquals(kotlin.math.round((112 - barDp) * 0.95f).toInt(), homeCustomEnrichCoverTileWidthDp(false, 0.95f, 0, barDp))
        assertEquals("a square is as wide as the art is tall", 112 - barDp, homeCustomEnrichCoverTileWidthDp(false, 1f, 0, barDp))
    }

    // ── P1 1, 8: a tile is its art over one bar ────────────────────────────────────────────────────

    @Test
    fun `a tile is its art over one bar, nothing is drawn over the art, and focus moves nothing in the bar`() {
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        val tile = picker.substringAfter("private fun EnrichAssetTile(").substringBefore("internal fun EnrichFooter(")
        assertFalse("no badge sits on the art", tile.contains("MimirOverArtBadge"))
        assertFalse("nothing aligns over the art", tile.contains("Alignment.TopStart") || tile.contains("Alignment.BottomCenter"))
        // The bar is a sibling BELOW the art box in a Column, one fixed height.
        assertTrue(tile.contains("Column(\n        modifier = Modifier\n            .width(tileWidthDp.dp)"))
        assertTrue(tile.contains(".height(tileLabel.barHeightDp.dp)"))
        assertTrue("the art is the tile's certified height less the bar, the strip's growth put back", tile.contains("homeCustomEnrichTileArtHeightDp(state.geometryKind, compactSquare, barDp, barGrowthDp).dp"))
        assertFalse("the bar is not conditional on focus", tile.contains("|| removable) Row("))
        // The remove mark's seat is reserved on every tile that can be removed; only its ink and its tap wait for focus.
        assertTrue(tile.contains("val removable = state.canRemove(asset)"))
        assertFalse(tile.contains("focused && state.canRemove"))
        assertTrue(tile.contains("color = if (focused) palette.danger else Color.Transparent,"))
        // The Default tile's own word is said in the bar.
        assertTrue(tile.contains("fixedBadge = if (isDefault) MimirCopy.Default.uppercase() else null,"))
        // Said once: the Default tile shows the rune mark, the preview stage says NO AUTOMATIC MEDIA.
        assertFalse("the tile does not say NO AUTOMATIC MEDIA", tile.contains("EnrichNoAutomaticMediaLabel"))
        assertTrue(tile.contains("text = \"ᛗ\""))
        // With no stage on the page nothing else would say it, so the Default tile says NO ART under the mark, and only then.
        assertTrue(tile.contains("if (!stageShown) {") && tile.contains("visualOverride = \"NO ART\","))
        assertTrue(tile.contains("stageShown: Boolean = true,"))
        assertTrue(picker.contains("stageShown = previewHeightDp > 0,"))
    }

    // ── P1 2, 3: one rule for the stages, and the overlays stand on an opaque ground ──────────────────

    @Test
    fun `every preview contains its media, centred, and only a video keeps a frame of its own`() {
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        val inline = picker.substringAfter("private fun EnrichPreviewBlock(").substringBefore("private fun EnrichExpandedMediaPreview(")
        val expanded = picker.substringAfter("private fun EnrichExpandedMediaPreview(").substringBefore("private fun EnrichNoAutomaticMediaLabel(")
        listOf("the inline preview" to inline, "the expanded preview" to expanded).forEach { (what, source) ->
            assertFalse("$what crops nothing", source.contains("ContentScale.Crop"))
            assertFalse("$what has no stage aspect of its own", source.contains("homeCustomEnrichPreviewStageAspect") || source.contains("stageAspect"))
            assertTrue("$what contains its media", source.contains("ContentScale.Fit"))
        }
        assertTrue(inline.contains("(if (framed) Modifier.fillMaxHeight().aspectRatio(16f / 9f) else Modifier.fillMaxSize())"))
        assertTrue(expanded.contains("(if (motion) Modifier.aspectRatio(16f / 9f) else Modifier.fillMaxSize())"))
        assertFalse(picker.contains("homeCustomEnrichPreviewUsesCrop"))
    }

    @Test
    fun `the expanded preview and the search keyboard stand on an opaque ground, and the keyboard fits the panel`() {
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        val expanded = picker.substringAfter("private fun EnrichExpandedMediaPreview(").substringBefore("private fun EnrichNoAutomaticMediaLabel(")
        assertTrue(expanded.contains(".background(palette.scrim.copy(alpha = 1f))"))
        assertFalse(expanded.contains("alpha = 0.88f"))
        // The keyboard is hosted INSIDE the panel (its width less the sheet's air, its height fraction), above its bottom edge, on an opaque sheet;
        // the shared keyboard itself is untouched (only a parameter it already has, floating).
        val host = picker.substringAfter("visible = state.keyboardOpen,").substringBefore("visible = state.previewExpanded,")
        assertTrue(host.contains(".width(panelWidth - EnrichKeyboardSheetAirDp.dp * 2)"))
        assertTrue(host.contains(".fillMaxHeight(panelHeightFraction)"))
        assertTrue(host.contains("floating = true,"))
        // The sheet is the opaque surface colour, drawn by the sheet layout (CustomMediaKeyboardSheet.kt) from below the top row, or over it whole: never across it.
        assertTrue(host.contains("CustomMediaKeyboardSheet(") && host.contains("surface = palette.surfaceBase,") && host.contains("rowBottomPx = "))
        assertTrue(sourceOf("CustomMediaKeyboardSheet.kt").contains(".background(surface)"))
        assertFalse("not the viewport's whole width, not the screen's bottom", host.contains("Alignment.BottomCenter),\n        ) {"))
        assertTrue(picker.contains(".fillMaxHeight(panelHeightFraction)\n                .testTag(HomeCustomEnrichPanelTestTag)"))
    }

    // ── P2 4: the value panel's halo ──────────────────────────────────────────────────────────────

    @Test
    fun `the value panels pad their list by the ring's reach so the first and last row's halo is not cut flat`() {
        val topRow = sourceOf("CustomMediaTopRow.kt")
        assertEquals("the Media panel, the Filters panel and a chip's panel", 3, Regex("""rowsEdgeAir = MimirFocusTileRingReach,""").findAll(topRow).count())
        val header = sourceOf("CompanionActivityAchievementFilterHeader.kt")
        // The list pads itself by the air it is handed: the ring's reach when the page's list fits, and a whole row when it must scroll (activityPickerListFit).
        assertTrue(header.contains("contentPadding = PaddingValues(vertical = air),"))
        assertTrue("the hub's list is handed its own air (none)", header.contains("ActivityPickerLazyList(listState, rows, rowsTag, rowTarget, edgeAir, litKey, sideInset"))
        assertTrue("the page's list starts from the ring's reach", header.contains("activityPickerListFit(constraints.maxHeight, maxOf(minRowPx, firstRowPx), with(density) { edgeAir.roundToPx() }, rows.size)"))
        assertTrue("the hub's list keeps its own (no air)", header.contains("rowsEdgeAir: Dp = 0.dp,"))
    }

    // ── P3: copy ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the eyebrow names the page for every type, never the type`() {
        assertTrue(types.size >= 3)
        types.forEach { type ->
            val header = customMediaHeader(type)
            assertEquals("Customize media", header.first)
            assertEquals("ᛗ · CUSTOMIZE MEDIA", header.second)
        }
    }

    @Test
    fun `the box's back reads Back cover while its value, which the provider request carries, stays Back`() {
        val box = HomeCustomEnrichFilter("box", "Type", listOf("All", "Front", "3D", "Texture", "Back"), selectedIndex = 4)
        assertEquals("Back", box.selected)
        assertEquals("Back cover", box.selectedDisplay)
        assertEquals("Front", box.copy(selectedIndex = 1).selectedDisplay)
    }

    @Test
    fun `the status line speaks in one casing, and what is spoken keeps its sentence case`() {
        val note = homeCustomEnrichStatusCaps(MimirTextIntent.Body("Automatic art restored"))
        assertEquals("AUTOMATIC ART RESTORED", note.resolve().visualText)
        assertEquals("Automatic art restored", note.resolve().spokenText)
        // Chrome and mixed copy are already the micro-label family; their authored fragments (a count, a provider) are left as they are.
        val searching = MimirTextIntent.Mixed(
            listOf(
                MimirTextFragment.Chrome("Searching "),
                MimirTextFragment.Authored("ScreenScraper"),
                MimirTextFragment.Chrome("… · "),
                MimirTextFragment.Authored("3"),
                MimirTextFragment.Chrome(" found"),
            ),
        )
        assertEquals(searching, homeCustomEnrichStatusCaps(searching))
        assertEquals("SEARCHING ScreenScraper… · 3 FOUND", searching.resolve().visualText)
        val idle = homeCustomEnrichDownloadedChoicesIntent()
        assertEquals(idle, homeCustomEnrichStatusCaps(idle))
        // The line is drawn in the micro-label style, through that mapping.
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        val line = picker.substringAfter("internal fun EnrichStatusLine(").substringBefore("internal fun EnrichGallery(")
        assertTrue(line.contains("intent = homeCustomEnrichStatusCaps("))
        assertTrue(line.contains("style = MimirTypeScale.MicroStatus,"))
    }

    @Test
    fun `no rune this page draws can be read as an arrow, and no check is a font glyph`() {
        listOf("HomeCustomEnrichPicker.kt", "CustomMediaTile.kt", "CustomMediaTopRow.kt", "CustomMediaPage.kt", "CompanionActivityAchievementFilterHeader.kt").forEach { name ->
            val source = sourceOf(name)
            assertFalse("$name draws no ᚲ (it reads as ‹)", source.contains("ᚲ"))
            assertFalse("$name draws its check as a path, not U+2713", source.contains("✓") || source.contains("✓"))
        }
        assertEquals("ᛒ · COVER ART PREVIEW", homeCustomEnrichExpandedPreviewTitleVisual(EnrichMediaKind.COVER))
    }

    @Test
    fun `the footer says the shoulders' action on the page, and nowhere they do nothing`() {
        val page = page()
        fun legend(state: HomeCustomEnrichState, lane: HomeCustomEnrichLane, removable: Boolean = false, previewable: Boolean = false, retry: Boolean = false) =
            homeCustomEnrichFooterCopy(state, lane, removable, retry, previewable).legend
        assertEquals("A USE · L1 R1 TYPE · B BACK", legend(page, HomeCustomEnrichLane.GALLERY))
        assertEquals("A USE · SELECT PREVIEW · L1 R1 TYPE · B BACK", legend(page, HomeCustomEnrichLane.GALLERY, previewable = true))
        // A saved tile focused: X's action stands beside them.
        assertEquals("A USE · X REMOVE · L1 R1 TYPE · B BACK", legend(page, HomeCustomEnrichLane.GALLERY, removable = true))
        assertEquals("A USE · SELECT PREVIEW · X REMOVE · L1 R1 TYPE · B BACK", legend(page, HomeCustomEnrichLane.GALLERY, removable = true, previewable = true))
        assertEquals("A EDIT · Y SEARCH · L1 R1 TYPE · B BACK", legend(page, HomeCustomEnrichLane.SEARCH))
        assertEquals("A CHOOSE · L1 R1 TYPE · B BACK", legend(page, HomeCustomEnrichLane.FOOTER))
        // While a search runs L1 and R1 do nothing: B stops it, and nothing else is said.
        val searching = page(HomeCustomEnrichSearchStatus.Searching("ScreenScraper", 2))
        assertEquals("B STOP", legend(searching, HomeCustomEnrichLane.SEARCH))
        // A picker with no Media chip has no types to step: its legend is as it was.
        val legacy = page(withTypes = false)
        assertEquals("A USE · B BACK", legend(legacy, HomeCustomEnrichLane.GALLERY))
        assertEquals("A EDIT · Y SEARCH · B BACK", legend(legacy, HomeCustomEnrichLane.SEARCH))
    }

    // ── P2 10: the remove-favourite confirm ───────────────────────────────────────────────────────

    @Test
    fun `the confirm asks one question by name and offers Remove and Cancel as etched chips, the lit one with a single rim`() {
        val overlay = sourceOf("HomeTileMenuOverlay.kt")
        val confirm = overlay.substringAfter("is HomeTileMenuPhase.ConfirmRemove -> {").substringBefore("is HomeTileMenuPhase.CollectionPick -> {")
        // A sentence in sentence case (a Body intent is never turned into capitals), with the game's name in it.
        assertTrue(confirm.contains("MimirTextIntent.Body(\"Remove \$title from Favorites?\"),"))
        assertFalse("no chrome fragment shouts the question", confirm.contains("MimirTextFragment.Chrome("))
        assertEquals("the two answers", 2, Regex("""HomeTileMenuConfirmChip\(""").findAll(confirm).count())
        assertTrue(confirm.contains("label = \"Remove\","))
        assertTrue(confirm.contains("label = \"Cancel\","))
        assertTrue("Remove is lit until Cancel is (LEFT and RIGHT move the lit answer)", confirm.contains("focused = !confirmCancelFocused,") && confirm.contains("focused = confirmCancelFocused,"))
        assertFalse("the words REMOVE FAVORITE are not said three times", confirm.contains("text = \"Remove favorite"))
        assertTrue("the question and the buttons share the panel's own text inset", Regex("""padding\(horizontal = actionHorizontalPadding\)""").findAll(confirm).count() >= 2)
        val chip = overlay.substringAfter("private fun HomeTileMenuConfirmChip(").substringBefore("/** The confirm-remove row's ring identity")
        assertTrue("the shared etched chip plate, the ring in its modifier", chip.contains("MimirEtchedChipPlate(") && chip.contains(".mimirSelectionRing(focused = focused, identity = identity, shape = MimirRingShape.Pill, edgeFlush = true)"))
        assertTrue(chip.contains("focused = focused,"))
        assertFalse("no surface of its own", chip.contains(".mimirEtchedChipSurface("))
    }

    // ── Round 3 (10-09): the tiles' widths, the stage's give, the cue's seat, the words ────────────────────

    @Test
    fun `the strip grows by the room it has up to a bar, and then the art keeps its certified height and widths`() {
        val bar = 21
        EnrichMediaKind.values().forEach { kind ->
            val band = homeCustomEnrichGalleryMinHeightDp(kind, false)
            val tile = homeCustomEnrichTileHeightDp(kind, false)
            assertEquals("$kind: a compact square grows nothing", 0, homeCustomEnrichStripGrowthDp(900, kind, true, bar))
            assertEquals("$kind: no room beyond the band, no growth", 0, homeCustomEnrichStripGrowthDp(band - 5, kind, false, bar))
            assertEquals("$kind: some room, that much", 7, homeCustomEnrichStripGrowthDp(band + 7, kind, false, bar))
            assertEquals("$kind: a bar's height at most", bar, homeCustomEnrichStripGrowthDp(band + 300, kind, false, bar))
            assertEquals("$kind: grown by a bar the art is the certified tile", tile, homeCustomEnrichTileArtHeightDp(kind, false, bar, bar))
            assertEquals("$kind: the art and the bar are the tile and the growth", tile + 7, homeCustomEnrichTileArtHeightDp(kind, false, bar, 7) + bar)
        }
        // The widths the tiles had before the bar moved under the art.
        assertEquals(HomeCustomEnrichHeroSelectionWidthDp, homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.HERO, false, 0, bar, bar))
        assertEquals(112, homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.ICON, false, 0, bar, bar))
        assertEquals(112, homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.TOME_PHYSICAL, false, 0, bar, bar))
        assertEquals(164, homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.CLIP, false, 0, bar, bar))
        assertEquals(101, homeCustomEnrichCoverTileWidthDp(false, 0.9f, 0, bar, bar))
        assertEquals(112, homeCustomEnrichCoverTileWidthDp(false, 1f, 0, bar, bar))
        assertEquals(81, homeCustomEnrichCoverTileWidthDp(false, 0.72f, 0, bar, bar))
        assertEquals(80, homeCustomEnrichCoverTileWidthDp(false, null, 0, bar, bar))
        // The default is no growth: the carved-out tile of the compact squares and every test that never asked.
        assertEquals(124 - 12 - bar, homeCustomEnrichBarredTileWidthDp(EnrichMediaKind.ICON, false, 0, bar))
    }

    @Test
    fun `the stage gives up what the strip grows, and its floor goes with it so a stage that was shown stays shown`() {
        // Hero on Main (band 93): the 853 x 480 page measures 222 and the stage had 101 dp of media, 5 over its floor of 96.
        assertEquals(101, homeCustomEnrichPreviewHeightDp(222, EnrichMediaKind.HERO, false))
        assertEquals(80, homeCustomEnrichPreviewHeightDp(222, EnrichMediaKind.HERO, false, growthDp = 21))
        assertEquals("a grown stage's floor", 72, homeCustomEnrichPreviewHeightDp(93 + 21 + 28 + 72, EnrichMediaKind.HERO, false, growthDp = 21))
        assertEquals(0, homeCustomEnrichPreviewHeightDp(93 + 21 + 28 + 71, EnrichMediaKind.HERO, false, growthDp = 21))
        // Where the strip did not grow the floor is the certified 96.
        assertEquals(96, homeCustomEnrichPreviewHeightDp(93 + 28 + 96, EnrichMediaKind.HERO, false))
        assertEquals(0, homeCustomEnrichPreviewHeightDp(93 + 28 + 95, EnrichMediaKind.HERO, false))
        // An Icon page that had no stage on Main has none after.
        assertEquals(0, homeCustomEnrichPreviewHeightDp(222, EnrichMediaKind.ICON, false, growthDp = 21))
    }

    @Test
    fun `the overflow cue is seated inside the panel's clip at both paddings`() {
        // The room beyond the column is the panel's padding and its side air, 14 dp at both paddings: the full seat fits.
        assertEquals(EnrichGalleryCueSeatDp, customMediaCueSeatDp(compactTypography = true))
        assertEquals(EnrichGalleryCueSeatDp, customMediaCueSeatDp(compactTypography = false))
        listOf(true, false).forEach { compact ->
            // The cue is 8 dp wide and its far edge is the seat past the column; the panel's padding is all the room there is beyond the column.
            assertTrue(
                "compact=$compact",
                customMediaCueSeatDp(compact) + 1 <= (homeCustomEnrichPanelPadding(compact) + customMediaPanelSideAir(compact)).value.toInt(),
            )
        }
        val body = sourceOf("CustomMediaTopRow.kt").substringAfter("internal fun CustomMediaBody(").substringBefore("internal fun CustomMediaPanels(")
        assertTrue(body.contains("cueSeatDp = customMediaCueSeatDp(compactTypography),"))
    }

    @Test
    fun `one result is one result`() {
        assertEquals("result", homeCustomEnrichResultsWord(1))
        listOf(0, 2, 12, 120).forEach { assertEquals("results", homeCustomEnrichResultsWord(it)) }
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        assertFalse("no fixed plural is left in the status line", picker.contains("Chrome(\" results\")"))
        assertFalse(picker.contains("Chrome(\" results · more available\")"))
        assertTrue(picker.contains("Chrome(\" \${homeCustomEnrichResultsWord(value.found)}\")"))
    }

    @Test
    fun `the strip's Default tile is never narrower than its word and its check`() {
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        assertTrue(picker.contains("val defaultTileFloorDp = rememberEnrichDefaultTileFloorDp()"))
        assertTrue(picker.contains("if (asset == HomeCustomEnrichAsset.Automatic) maxOf(width, defaultTileFloorDp) else width"))
    }

    // ── The 10-09 re-sweep (sweep 2) ───────────────────────────────────────────────────────────────

    @Test
    fun `the keyboard's sheet starts below the top row, squeezes its air before it covers the row, and never lies across it`() {
        val gap = 8
        val air = 24
        val minAir = 8
        fun plan(height: Int, keyboard: Int, rowTop: Float? = 100f, rowBottom: Float? = 220f) =
            customMediaKeyboardSheetPlan(height, keyboard, rowTop, rowBottom, gap, air, minAir)
        // Room under the row: the sheet is the keyboard's own with its full air, the row whole above it.
        assertEquals(CustomMediaKeyboardSheetPlan(sheetTopPx = 700 - 200 - 2 * air, airPx = air), plan(700, 200))
        // A few px short of the full air: the air is squeezed to what is left, the sheet starting exactly a gap under the row.
        val squeezed = plan(height = 220 + gap + 300 + 2 * 12, keyboard = 300)
        assertEquals("the air gives up to 12 px each side", 12, squeezed.airPx)
        assertEquals("the sheet starts a gap below the row", 220 + gap, squeezed.sheetTopPx)
        // At the minimum air it still fits, and the row is left alone.
        val tight = plan(height = 220 + gap + 300 + 2 * minAir, keyboard = 300)
        assertEquals(minAir, tight.airPx)
        assertEquals(220 + gap, tight.sheetTopPx)
        // One px short of the minimum: the sheet covers the row entirely, from a gap above its top, with its full air.
        val covering = plan(height = 220 + gap + 300 + 2 * minAir - 2, keyboard = 300)
        assertEquals(air, covering.airPx)
        assertEquals("a gap above the row's top", 100 - gap, covering.sheetTopPx)
        // A taller keyboard covers more of the page, never the row's half: the sheet starts above the row's top.
        assertEquals(40, plan(height = 400, keyboard = 400 - 40 - 2 * air).sheetTopPx)
        // Never above the panel; with the row not yet measured the keyboard's own.
        assertEquals(0, plan(height = 100, keyboard = 400).sheetTopPx)
        assertEquals(CustomMediaKeyboardSheetPlan(400 - 200 - 2 * air, air), customMediaKeyboardSheetPlan(400, 200, null, null, gap, air, minAir))
        // Whatever the keyboard's height, the sheet's edge is never inside the row's span.
        (0..420 step 5).forEach { keyboard ->
            val sheetTop = plan(height = 600, keyboard = keyboard).sheetTopPx
            assertTrue("keyboard $keyboard gave a sheet top of $sheetTop", sheetTop >= 220 || sheetTop <= 100)
        }
    }

    @Test
    fun `a cover upload is staged whole, and only an icon is square-cropped`() {
        val coordinator = sourceOf("MimirAppCoordinator.kt")
        assertTrue(coordinator.contains("EnrichMediaKind.COVER -> HomeImageImportTarget.Icon(picker.cacheKey, keepsAspect = true)"))
        assertTrue(coordinator.contains("CustomImageImportStore.stageCover(core.context, uri, staged)"))
        assertTrue("the cartridge and the icon stay on the square staging", coordinator.contains("EnrichMediaKind.TOME_PHYSICAL -> HomeImageImportTarget.Icon(picker.cacheKey)\n"))
        val store = sourceOf("CustomImageImportStore.kt")
        val cover = store.substringAfter("CustomImageSlot.COVER -> {").substringBefore("CustomImageSlot.HERO -> decoded")
        assertFalse("a cover is not cropped", cover.contains("createBitmap(decoded"))
        assertTrue("it is only brought down by its longest edge", cover.contains("customImageCoverSize("))
    }

    @Test
    fun `the title book is contained whole in its art box, which clips, and the book is tagged`() {
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        val face = picker.substringAfter("private fun EnrichDefaultArtFace(").substringBefore("private fun EnrichAssetTile(")
        assertTrue(face.contains("homeCustomEnrichTitleBookWidthDp(maxWidth.value, maxHeight.value)"))
        assertTrue("contained, not cover-fitted and not offset", face.contains(".requiredSize(bookWidth.dp, bookHeight.dp)") && !face.contains(".offset(") && !face.contains("TitleBookLift"))
        assertTrue(face.contains(".testTag(CustomMediaTitleBookTag)"))
        assertFalse("not the cover-fit that put the rune under the bar", face.contains("maxHeight * 0.75f"))
        val tile = picker.substringAfter("private fun EnrichAssetTile(").substringBefore("internal fun EnrichFooter(")
        assertTrue("no face paints under the bar", tile.contains("barGrowthDp).dp).clipToBounds()"))
    }

    @Test
    fun `the footer's cue and legend are asked together, so SELECT is said once`() {
        val picker = sourceOf("HomeCustomEnrichPicker.kt")
        assertTrue(picker.contains("homeCustomEnrichFooterSaysExpand(state, focusedKey, footerCopy.legend)"))
        assertFalse("the cue is no longer shown on its own predicate", picker.contains("if (homeCustomEnrichShowsExpandHint(state, focusedKey)) {"))
    }

    private fun sourceOf(name: String): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
    ).first(File::isFile).readText()
}
