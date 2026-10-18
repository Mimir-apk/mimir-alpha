package com.gruning.mimir.rewrite.feature.library

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderLeadingTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTag
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTrailingTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirStaticChrome
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Slice L4: the platform shelf's header, density ladder and paging, rendered at the Thor Bottom Presentation's size, measured on the device on 2026-10-02:
 * 1240 x 1080 px at 369 dpi (density 2.30625), which is 537.7 x 468.3 dp. The shelf fills it, so its page is 505.7 dp wide (16 dp a side).
 *
 * For each of Large, Standard and Dense at font scales 1.0 and 1.3, with 43 games (so the last page is partial at every step): nothing overflows or clips, every
 * target (the back pill, the density chip, every tile) is at least 48 dp, the labels of Large and Standard are `Row` 14 sp in at most two lines and Dense has
 * none, and the page's height and cells are what the solver says. Every number is printed with the tag `L4-SHELF`. Pixel values are the JVM's; the handheld's are
 * device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionPlatformShelfRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private val gutter = 16f
    private val platformId = "genesis"

    private var density = 0f
    private var titleNaturalPx = 0
    private var measureTitle: (Int) -> TextLayoutResult = { error("not mounted") }
    private val tempFiles = mutableListOf<File>()

    private class GamesRepository(private val games: List<GameRow>) : LibraryRepository by FakeLibraryRepository() {
        override fun observeGames(platformId: PlatformId): Flow<List<GameRow>> = flowOf(games)
    }

    private fun games(count: Int) = (1..count).map { n ->
        GameRow(
            gameId = GameId("g%03d".format(n)),
            title = "Game %02d with a rather long title that just keeps on going and going".format(n),
            platformId = PlatformId(platformId),
            memberCount = 1,
            duplicateKind = null,
        )
    }

    @Before
    fun setUp() {
        PlatformTileArtStore.art.value = emptyMap()
        PlatformTileArtStore.hydrated.value = true
    }

    @After
    fun tearDown() {
        PlatformTileArtStore.art.value = emptyMap()
        PlatformTileArtStore.hydrated.value = false
        tempFiles.forEach { it.delete() }
    }

    private fun mount(
        step: CompanionShelfDensity,
        fontScale: Float,
        count: Int = 43,
        label: String = "Genesis",
        onBack: () -> Unit = {},
        onStep: (CompanionShelfDensity) -> Unit = {},
        onArranged: (List<String>) -> Unit = {},
        look: CompanionLaunchLook = CompanionLaunchLook.Default,
        onLook: (CompanionLaunchLook) -> Unit = {},
        display: Int = BottomUiReceiptFormat.NO_DISPLAY,
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale), LocalBottomUiDisplay provides display) {
                MimirTheme {
                    val measurer = rememberTextMeasurer()
                    titleNaturalPx = measurer.measure(AnnotatedString(label.uppercase()), MimirTypeScale.TitleCaps, maxLines = 1).size.width
                    // The header's title speaks through MimirChromeText (no text-layout action), so its lines are re-measured here at the width it took.
                    measureTitle = { widthPx ->
                        measurer.measure(
                            AnnotatedString(mimirStaticChrome(label)),
                            MimirTypeScale.TitleCaps,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 2,
                            constraints = Constraints(maxWidth = widthPx),
                        )
                    }
                    density = LocalDensity.current.density
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).testTag("l4-host")) {
                        CompanionPlatformShelf(
                            platformIdValue = platformId,
                            label = label,
                            repository = GamesRepository(games(count)),
                            filedOrder = emptyList(),
                            onArranged = onArranged,
                            onBack = onBack,
                            onLaunch = {},
                            active = true,
                            density = step,
                            onDensityChange = onStep,
                            look = look,
                            onLookChange = onLook,
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun settle(ms: Long = 1_000L) {
        // A state write from a pointer coroutine on a paused clock is only published by this (the paused-clock note).
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    /** The window is smaller than the panel (the JVM's decor), so the panel is centred in it and every box is measured from the panel's own corner. */
    private fun hostOrigin(): Offset = compose.onNodeWithTag("l4-host").fetchSemanticsNode().boundsInRoot.topLeft

    private fun dp(b: androidx.compose.ui.geometry.Rect): FloatArray {
        val o = hostOrigin()
        return floatArrayOf((b.left - o.x) / density, (b.top - o.y) / density, b.width / density, b.height / density)
    }

    private fun boundsDp(tag: String, unmerged: Boolean = false): FloatArray =
        dp(compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot)

    private fun allBoundsDp(tag: String, unmerged: Boolean = false): List<FloatArray> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().map { dp(it.boundsInRoot) }

    private fun count(tag: String, unmerged: Boolean = false): Int = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().size

    private fun describe(tag: String): String? =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun overlaps(a: FloatArray, b: FloatArray, tolerance: Float = 0.5f): Boolean =
        a[0] + a[2] - tolerance > b[0] && b[0] + b[2] - tolerance > a[0] &&
            a[1] + a[3] - tolerance > b[1] && b[1] + b[3] - tolerance > a[1]

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float = 0.8f) =
        assertTrue("$message: expected $expected, was $actual", abs(expected - actual) <= tolerance)

    private fun assertInside(name: String, inner: FloatArray, outer: FloatArray) =
        assertTrue(
            "$name ${inner.toList()} must sit inside ${outer.toList()}",
            inner[0] >= outer[0] - 0.6f && inner[0] + inner[2] <= outer[0] + outer[2] + 0.6f &&
                inner[1] >= outer[1] - 0.6f && inner[1] + inner[3] <= outer[1] + outer[3] + 0.6f,
        )

    private fun textLayouts(tag: String, unmerged: Boolean = true): List<TextLayoutResult> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().map { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            val action = checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) { "$tag has no layout action" }
            check(action(layouts)) { "$tag has no text layout yet" }
            layouts.single()
        }

    private fun assertShelf(step: CompanionShelfDensity, fontScale: Float) {
        mount(step, fontScale)
        val host = floatArrayOf(0f, 0f, hostWidthDp, hostHeightDp)
        val band = boundsDp(CompanionShelfHeaderBandTag)
        val header = boundsDp(MimirCompactHeaderTag)
        val row = boundsDp(MimirCompactHeaderRowTag)
        val pill = boundsDp(CompanionShelfBackPillTag)
        val seat = boundsDp(CompanionShelfIconSeatTag)
        val chip = boundsDp(CompanionShelfDensityChipTag)
        val title = boundsDp(MimirCompactHeaderTitleTag, unmerged = true)
        val trailing = boundsDp(MimirCompactHeaderTrailingTag)
        val page = boundsDp(CompanionShelfPageAreaTag)
        val strip = boundsDp(CompanionShelfPageStripTag)
        val tiles = allBoundsDp(CompanionShelfTileTag)
        val labels = allBoundsDp(CompanionShelfTileLabelTag, unmerged = true)
        val countBox = boundsDp(CompanionShelfCountTag, unmerged = true)

        val pageW = page[2]
        val pageH = page[3]
        val cellW = tiles.maxOf { it[2] }
        val cellH = tiles.maxOf { it[3] }
        val bandH = labels.firstOrNull()?.get(3) ?: 0f
        println(
            "L4-SHELF ${step.name} ${step.readout} fontScale=$fontScale host=${"%.2f".format(hostWidthDp)}x${"%.2f".format(hostHeightDp)}dp " +
                "band=${"%.2f".format(band[3])}dp@y${"%.2f".format(band[1])} page=${"%.2f".format(pageW)}x${"%.2f".format(pageH)}dp@y${"%.2f".format(page[1])} " +
                "cell=${"%.2f".format(cellW)}x${"%.2f".format(cellH)}dp labelBand=${"%.2f".format(bandH)}dp tiles=${tiles.size} strip=${"%.2f".format(strip[3])}dp@y${"%.2f".format(strip[1])} " +
                "pill=${"%.2f".format(pill[2])}x${"%.2f".format(pill[3])}dp chip=${"%.2f".format(chip[2])}x${"%.2f".format(chip[3])}dp row=${"%.2f".format(row[3])}dp header=${"%.2f".format(header[3])}dp " +
                "title=${"%.2f".format(title[2])}dp count=${"%.2f".format(countBox[2])}dp runes='${describe(CompanionShelfPageRunesTag)}'",
        )

        // The geometry: 16 dp a side and 10 dp above, the 64 dp band, an 8 dp clearance, then the page, 8 dp, and the 16 dp strip that closes the panel.
        assertNear("the band is 64 dp", CompanionShelfHeaderBandDp, band[3])
        assertNear("the band starts 10 dp down", CompanionShelfTopInsetDp, band[1])
        assertNear("the header is the shell's width", hostWidthDp - 2 * gutter, header[2])
        assertNear("the page is the shell's width", hostWidthDp - 2 * gutter, pageW)
        assertNear("the page is one clearance under the band", band[1] + band[3] + 8f, page[1])
        assertNear("the strip is the 16 dp under the page and a gap", page[1] + page[3] + 8f, strip[1])
        assertNear("the strip closes the panel", hostHeightDp, strip[1] + strip[3])
        assertTrue("the strip is at least 16 dp", strip[3] >= 15.5f)
        assertInside("the strip", strip, host)

        // Every target is at least 48 dp.
        assertTrue("back pill ${pill.toList()}", pill[2] >= 47.5f && pill[3] >= 47.5f)
        assertTrue("density chip ${chip.toList()}", chip[2] >= 47.5f && chip[3] >= 47.5f)
        assertNear("the icon seat is 40 dp wide", CompanionShelfIconSeatDp, seat[2])
        assertNear("the icon seat is 40 dp tall", CompanionShelfIconSeatDp, seat[3])
        for (tile in tiles) assertTrue("tile ${tile.toList()}", tile[2] >= 47.5f && tile[3] >= 47.5f)
        assertEquals("the leading slot is the pill", boundsDp(MimirCompactHeaderLeadingTag)[0], pill[0], 0.6f)

        // The header: nothing overlaps, nothing is outside it, and the chip is flush right. Left to right: pill, icon, name, count, chip.
        assertInside("pill", pill, header)
        assertInside("seat", seat, header)
        assertInside("trailing", trailing, header)
        assertInside("title", title, header)
        assertNear("the chip ends at the header's right edge", header[0] + header[2], chip[0] + chip[2])
        assertInside("the count", countBox, trailing)
        assertInside("the chip", chip, trailing)
        val parts = listOf("pill" to pill, "icon" to seat, "title" to title, "count" to countBox, "chip" to chip)
        for (i in parts.indices) for (j in i + 1 until parts.size) {
            assertFalse("${parts[i].first} and ${parts[j].first} overlap: ${parts[i].second.toList()} ${parts[j].second.toList()}", overlaps(parts[i].second, parts[j].second))
        }
        for (i in 0 until parts.size - 1) {
            assertTrue("${parts[i].first} is left of ${parts[i + 1].first}", parts[i].second[0] + parts[i].second[2] <= parts[i + 1].second[0] + 0.6f)
        }
        assertEquals("the count says the games", "43 games", textLayouts(CompanionShelfCountTag).single().layoutInput.text.text)
        assertEquals("the chip says the grid", step.readout, textLayouts(CompanionShelfDensityChipTextTag).single().layoutInput.text.text)
        assertInside("the header in the band", header, band)

        // The page: whole rows at the step's columns, uniform cells filling it, nothing clipped.
        assertEquals("a full page of tiles (43 games is more than a page at every step)", step.perPage, tiles.size)
        assertEquals("columns", step.columns, tiles.map { Math.round(it[0] * 10f) }.distinct().size)
        assertEquals("rows", step.rows, tiles.map { Math.round(it[1] * 10f) }.distinct().size)
        val expectedW = (pageW - 12f * (step.columns - 1)) / step.columns
        val expectedH = (pageH - 12f * (step.rows - 1)) / step.rows
        for (tile in tiles) {
            assertNear("cell width", expectedW, tile[2], 0.6f)
            assertNear("cell height", expectedH, tile[3], 0.6f)
            assertInside("tile", tile, page)
        }
        assertNear("the last column ends at the page's right edge", page[0] + page[2], tiles.maxOf { it[0] + it[2] }, 0.6f)
        assertNear("the last row ends at the page's bottom edge", page[1] + page[3], tiles.maxOf { it[1] + it[3] }, 0.6f)
        for (i in tiles.indices) for (j in i + 1 until tiles.size) assertFalse("tiles $i and $j overlap", overlaps(tiles[i], tiles[j]))

        // Labels: Row 14 sp, authored case, two lines at most, inside their tile; Dense has none.
        if (step.showsLabel) {
            assertEquals("a label a tile", step.perPage, labels.size)
            val layouts = textLayouts(CompanionShelfTileLabelTag)
            for ((index, label) in labels.withIndex()) {
                assertTrue("label ${label.toList()} sits inside its tile", tiles.any { tile -> abs(tile[2] - cellW) < 1f && label[0] >= tile[0] - 0.6f && label[0] + label[2] <= tile[0] + tile[2] + 0.6f && label[1] >= tile[1] - 0.6f && label[1] + label[3] <= tile[1] + tile[3] + 0.6f })
                val layout = layouts[index]
                if (index == 0) println("L4-SHELF label ${step.name} fontScale=$fontScale line0=${layout.getLineBottom(0) - layout.getLineTop(0)}px lines=${layout.lineCount} sizePx=${layout.size.height} bandPx=${label[3] * density} fontPx=${layout.layoutInput.style.fontSize.value}sp overflowH=${layout.didOverflowHeight}")
                assertEquals("Row is 14 sp", 14f, layout.layoutInput.style.fontSize.value, 0.01f)
                // At most two lines, and both inside the band (the band is two measured lines). A Standard cell (117 dp) cannot hold these titles in one line.
                assertTrue("one or two lines, was ${layout.lineCount}", layout.lineCount in 1..2)
                assertTrue("the lines end inside the band: ${layout.getLineBottom(layout.lineCount - 1)} of ${label[3] * density}", layout.getLineBottom(layout.lineCount - 1) <= label[3] * density + 1f)
                if (step == CompanionShelfDensity.STANDARD) {
                    assertEquals("two lines in a Standard cell", 2, layout.lineCount)
                    assertTrue("the second line is the ellipsized one", layout.isLineEllipsized(1))
                }
                assertEquals("two lines is the cap", 2, layout.layoutInput.maxLines)
                val spoken = layout.layoutInput.text.text
                assertTrue("authored case, never capitals: '$spoken'", spoken.startsWith("Game "))
                // The text is at most the band tall: nothing spills out of it.
                assertTrue("text height ${layout.size.height} within the band ${label[3] * density}", layout.size.height <= label[3] * density + 1f)
            }
        } else {
            assertEquals("Dense draws no label", 0, labels.size)
        }

        // The runes: one node says Page n of m.
        val pages = companionShelfPageCount(43, step.perPage)
        assertEquals("Page 1 of $pages", describe(CompanionShelfPageRunesTag))
        assertEquals(pages, (43 + step.perPage - 1) / step.perPage)
        assertTrue("43 games make a partial last page", 43 % step.perPage != 0)
        // The whole shelf inside the host: no node past the panel.
        for (tag in listOf(CompanionShelfHeaderBandTag, CompanionShelfPageAreaTag, CompanionShelfPageStripTag)) assertInside(tag, boundsDp(tag), host)
    }

    @Test fun `Large at font scale 1_0`() = assertShelf(CompanionShelfDensity.LARGE, 1.0f)
    @Test fun `Large at font scale 1_3`() = assertShelf(CompanionShelfDensity.LARGE, 1.3f)
    @Test fun `Standard at font scale 1_0`() = assertShelf(CompanionShelfDensity.STANDARD, 1.0f)
    @Test fun `Standard at font scale 1_3`() = assertShelf(CompanionShelfDensity.STANDARD, 1.3f)
    @Test fun `Dense at font scale 1_0`() = assertShelf(CompanionShelfDensity.DENSE, 1.0f)
    @Test fun `Dense at font scale 1_3`() = assertShelf(CompanionShelfDensity.DENSE, 1.3f)

    /** The longest platform name in the catalog (platforms/mimir-platform-registry.v2.json, 226 platforms): 39 characters. */
    private val longestCatalogName = "1292 Advanced Programmable Video System"

    /** Longer than any catalog name, so the name must give out even at 1.0. */
    private val overlongName = "Nintendo Entertainment System Famicom Disk System Collection"

    private fun assertHeaderFit(label: String, fontScale: Float, expectLines: IntRange, expectEllipsis: Boolean?) {
        mount(CompanionShelfDensity.STANDARD, fontScale, label = label)
        val band = boundsDp(CompanionShelfHeaderBandTag)
        val header = boundsDp(MimirCompactHeaderTag)
        val row = boundsDp(MimirCompactHeaderRowTag)
        val pill = boundsDp(CompanionShelfBackPillTag)
        val seat = boundsDp(CompanionShelfIconSeatTag)
        val chip = boundsDp(CompanionShelfDensityChipTag)
        val title = boundsDp(MimirCompactHeaderTitleTag, unmerged = true)
        val trailing = boundsDp(MimirCompactHeaderTrailingTag)
        val countBox = boundsDp(CompanionShelfCountTag, unmerged = true)
        val titleNodePx = compose.onNodeWithTag(MimirCompactHeaderTitleTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val titleLayout = measureTitle(Math.round(titleNodePx.width) + 1)
        val singleLinePx = titleLayout.getLineBottom(0) - titleLayout.getLineTop(0)
        assertEquals("the node is as tall as the lines it measured", titleLayout.lineCount.toFloat(), titleNodePx.height / singleLinePx, 0.15f)
        val countLayout = textLayouts(CompanionShelfCountTag).single()
        val ellipsized = titleLayout.isLineEllipsized(titleLayout.lineCount - 1)
        println(
            "W2C-HEADER '$label' fontScale=$fontScale band=${"%.2f".format(band[3])}dp header=${"%.2f".format(header[3])}dp row=${"%.2f".format(row[3])}dp " +
                "titleRoom=${"%.2f".format(title[2])}dp natural=${"%.2f".format(titleNaturalPx / density)}dp lines=${titleLayout.lineCount} ellipsized=$ellipsized " +
                "pill=${"%.2f".format(pill[2])}x${"%.2f".format(pill[3])} seat=${"%.2f".format(seat[2])}x${"%.2f".format(seat[3])} chip=${"%.2f".format(chip[2])}x${"%.2f".format(chip[3])} " +
                "count=${"%.2f".format(countBox[2])}dp gaps pill-seat=${"%.2f".format(seat[0] - pill[0] - pill[2])} seat-title=${"%.2f".format(title[0] - seat[0] - seat[2])} " +
                "title-count=${"%.2f".format(countBox[0] - title[0] - title[2])} count-chip=${"%.2f".format(chip[0] - countBox[0] - countBox[2])}dp",
        )
        // The band stays 64 dp and holds the whole header; the row (a 48 dp target, a 40 dp seat, two lines of name) is what grows, never past the band.
        assertNear("the band is 64 dp", CompanionShelfHeaderBandDp, band[3])
        assertInside("the header in the band", header, band)
        assertTrue("the header (${header[3]}) fits the band", header[3] <= CompanionShelfHeaderBandDp + 0.5f)
        // No 48 dp target shrinks.
        assertTrue("the pill is 48 dp: ${pill.toList()}", pill[2] >= 47.5f && pill[3] >= 47.5f)
        assertTrue("the chip is 48 dp: ${chip.toList()}", chip[2] >= 47.5f && chip[3] >= 47.5f)
        assertNear("the seat keeps 40 dp", CompanionShelfIconSeatDp, seat[2])
        assertNear("the seat keeps 40 dp", CompanionShelfIconSeatDp, seat[3])
        // Nothing overlaps, and everything is in the row, left to right: pill, icon, name, count, chip.
        val parts = listOf("pill" to pill, "icon" to seat, "title" to title, "count" to countBox, "chip" to chip)
        for (i in parts.indices) for (j in i + 1 until parts.size) {
            assertFalse("${parts[i].first} and ${parts[j].first} overlap", overlaps(parts[i].second, parts[j].second))
        }
        for (i in 0 until parts.size - 1) {
            assertTrue("${parts[i].first} is left of ${parts[i + 1].first}", parts[i].second[0] + parts[i].second[2] <= parts[i + 1].second[0] + 0.6f)
        }
        for ((name, box) in parts) assertInside(name, box, row)
        assertInside("the trailing slot", trailing, header)
        assertNear("the chip ends at the header's right edge", header[0] + header[2], chip[0] + chip[2])
        // The name wraps at a word break first, then ellipsizes on its second line; it never takes a third.
        assertTrue("lines ${titleLayout.lineCount} in $expectLines", titleLayout.lineCount in expectLines)
        assertEquals("two lines is the cap", 2, titleLayout.layoutInput.maxLines)
        assertTrue("the name is never cut on line 1 of 2", titleLayout.lineCount == 1 || !titleLayout.isLineEllipsized(0))
        if (expectEllipsis != null) assertEquals("ellipsized", expectEllipsis, ellipsized)
        if (ellipsized) assertEquals("a name wraps before it ellipsizes", 2, titleLayout.lineCount)
        assertEquals("the full name is said to a screen reader", label, describe(MimirCompactHeaderTitleTag))
        // The count is always shown, whole, one line, in its own width.
        assertEquals("the caller's count, whole", "43 games", countLayout.layoutInput.text.text)
        assertEquals("one line", 1, countLayout.lineCount)
        assertFalse("not ellipsized", countLayout.isLineEllipsized(0))
        assertTrue("the text is inside its node", countLayout.getLineRight(0) <= countBox[2] * density + 1f)
        assertTrue("it has its own width: ${countBox.toList()}", countBox[2] > 20f)
        assertEquals("one title node", 1, count(MimirCompactHeaderTitleTag, unmerged = true))
    }

    @Test fun `a short name takes one line and nothing crowds it at 1_0`() = assertHeaderFit("SNES", 1.0f, 1..1, false)
    @Test fun `a short name takes one line and nothing crowds it at 1_3`() = assertHeaderFit("SNES", 1.3f, 1..1, false)
    @Test fun `the longest catalog name wraps to two lines at 1_0`() = assertHeaderFit(longestCatalogName, 1.0f, 2..2, null)
    @Test fun `the longest catalog name wraps to two lines at 1_3`() = assertHeaderFit(longestCatalogName, 1.3f, 2..2, null)
    @Test fun `an overlong name ellipsizes on its second line and the count stays at 1_0`() = assertHeaderFit(overlongName, 1.0f, 2..2, true)
    @Test fun `an overlong name ellipsizes on its second line and the count stays at 1_3`() = assertHeaderFit(overlongName, 1.3f, 2..2, true)

    /** The Back pill was a 48 dp plate: it is now a chip-sized plate inside the same 48 dp target. */
    @Test
    fun `the back pill is a compact plate inside its 48 dp target with the chevron and the label on one centre line`() {
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        val target = boundsDp(CompanionShelfBackPillTag)
        val plate = boundsDp(CompanionShelfBackPillPlateTag, unmerged = true)
        fun word(text: String) = dp(compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().first().boundsInRoot)
        val chevron = word("‹")
        val label = word("Shelf")
        println("L4-SHELF backpill target=${target.toList()} plate=${plate.toList()} chevron=${chevron.toList()} label=${label.toList()}")
        assertTrue("the touch target is at least 48 dp each way: ${target.toList()}", target[2] >= 47.9f && target[3] >= 47.9f)
        assertTrue("the visual plate is smaller than the target: ${plate.toList()} in ${target.toList()}", plate[3] < target[3] - 4f)
        assertInside("the plate", plate, target)
        fun centreY(b: FloatArray) = b[1] + b[3] / 2f
        assertNear("the plate is centred in its target", centreY(target), centreY(plate), 1f)
        assertNear("the label's centre line is the plate's", centreY(plate), centreY(label), 1f)
        assertNear("and so is the chevron's", centreY(plate), centreY(chevron), 1f)
        assertNear("one 4 dp gap between the chevron and the label", MimirSpacing.xs.value, label[0] - (chevron[0] + chevron[2]))
        assertNear("a chip's padding: 8 dp above and below the label", MimirSpacing.sm.value * 2f + label[3], plate[3], 1f)
    }

    /** The density chip was the old 48 dp plate beside the new compact Back pill: both are chip-sized plates now, in 48 dp targets. */
    private fun assertDensityChipIsCompact(fontScale: Float) {
        mount(CompanionShelfDensity.STANDARD, fontScale)
        val chip = boundsDp(CompanionShelfDensityChipTag)
        val chipPlate = boundsDp(CompanionShelfDensityChipPlateTag, unmerged = true)
        val backPlate = boundsDp(CompanionShelfBackPillPlateTag, unmerged = true)
        println("L4-SHELF plates font=$fontScale back=${backPlate.toList()} density=${chipPlate.toList()} densityTarget=${chip.toList()}")
        assertNear("the density plate is the back plate's height at font $fontScale", backPlate[3], chipPlate[3], 1f)
        assertTrue("the density target is at least 48 dp each way: ${chip.toList()}", chip[2] >= 47.9f && chip[3] >= 47.9f)
        assertTrue("the visual plate is smaller than its target: ${chipPlate.toList()} in ${chip.toList()}", chipPlate[3] < chip[3] - 4f)
        assertInside("the density plate", chipPlate, chip)
        assertNear("both plates share a centre line", backPlate[1] + backPlate[3] / 2f, chipPlate[1] + chipPlate[3] / 2f, 1f)
    }

    @Test
    fun `the density chip is a compact plate in a 48 dp target, the same height as the back pill's plate`() = assertDensityChipIsCompact(1.0f)

    @Test
    fun `the density chip keeps its compact plate and 48 dp target at 1_3`() = assertDensityChipIsCompact(1.3f)

    @Test
    fun `the back pill names its target, is tapped once, and reports where it sits`() {
        var backs = 0
        mount(CompanionShelfDensity.STANDARD, 1.0f, onBack = { backs++ })
        val pillNode = compose.onNodeWithTag(CompanionShelfBackPillTag)
        val raw = pillNode.fetchSemanticsNode().boundsInRoot
        val bounds = raw.translate(-hostOrigin().x, -hostOrigin().y)
        println(
            "L4-SHELF backpill centre=${"%.1f".format(bounds.center.x)},${"%.1f".format(bounds.center.y)}px of 1240x1080 " +
                "size=${"%.1f".format(bounds.width / density)}x${"%.1f".format(bounds.height / density)}dp (the old chip's centre was 60,55)",
        )
        assertEquals("names its target", listOf("Back to Shelf"), pillNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        pillNode.performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    private fun tapChip() {
        compose.onNodeWithTag(CompanionShelfDensityChipTag).performClick()
        settle()
    }

    @Test
    fun `the chip opens a three-row popover under it, and a rung steps the grid and closes it`() {
        val steps = mutableListOf<CompanionShelfDensity>()
        mount(CompanionShelfDensity.STANDARD, 1.0f, onStep = { steps += it })
        assertEquals("closed at first", 0, count(CompanionShelfDensityMenuTag))
        assertEquals("the chip names the grid", "4×2", textLayouts(CompanionShelfDensityChipTextTag).single().layoutInput.text.text)
        tapChip()
        val chip = boundsDp(CompanionShelfDensityChipTag)
        val menu = boundsDp(CompanionShelfDensityMenuTag)
        val host = floatArrayOf(0f, 0f, hostWidthDp, hostHeightDp)
        println("W2C-MENU chip=${chip.toList()} menu=${menu.toList()} rows=${CompanionShelfDensity.values().map { boundsDp(companionShelfDensityRungTag(it)).toList() }}")
        assertEquals(1, count(CompanionShelfDensityMenuTag))
        assertInside("the popover", menu, host)
        assertTrue("the popover hangs under the chip", menu[1] >= chip[1] + chip[3] - 0.6f)
        assertNear("it is the chip's gap under it", chip[1] + chip[3] + CompanionShelfDensityMenuGapDp, menu[1], 1.2f)
        assertNear("its right edge is the chip's", chip[0] + chip[2], menu[0] + menu[2], 1.2f)
        assertNear("it is its own width", CompanionShelfDensityMenuWidthDp, menu[2], 1.2f)
        val rows = CompanionShelfDensity.values().map { boundsDp(companionShelfDensityRungTag(it)) }
        for ((index, row) in rows.withIndex()) {
            assertTrue("row $index is a 48 dp target: ${row.toList()}", row[2] >= 47.5f && row[3] >= 47.5f)
            assertInside("row $index", row, menu)
            assertTrue("row $index says its rung", describe(companionShelfDensityRungTag(CompanionShelfDensity.values()[index]))!!.startsWith(CompanionShelfDensity.values()[index].rungLabel))
        }
        for (i in 0 until rows.size - 1) assertFalse("rows $i and ${i + 1} overlap", overlaps(rows[i], rows[i + 1]))
        // Picking Dense steps the grid and closes the popover.
        compose.onNodeWithTag(companionShelfDensityRungTag(CompanionShelfDensity.DENSE)).performClick()
        settle()
        assertEquals(listOf(CompanionShelfDensity.DENSE), steps)
        assertEquals("the popover is gone", 0, count(CompanionShelfDensityMenuTag))
        assertEquals("no catcher is left", 0, count(CompanionShelfDensityCatcherTag))
        assertEquals("the chip follows", "6×3", textLayouts(CompanionShelfDensityChipTextTag).single().layoutInput.text.text)
        assertEquals("the grid re-solved to a Dense page", CompanionShelfDensity.DENSE.perPage, count(CompanionShelfTileTag))
        // The same rung again is no step; every rung is reachable.
        tapChip()
        compose.onNodeWithTag(companionShelfDensityRungTag(CompanionShelfDensity.DENSE)).performClick()
        settle()
        assertEquals("the current rung writes nothing", listOf(CompanionShelfDensity.DENSE), steps)
        assertEquals(0, count(CompanionShelfDensityMenuTag))
        tapChip()
        compose.onNodeWithTag(companionShelfDensityRungTag(CompanionShelfDensity.LARGE)).performClick()
        settle()
        assertEquals(listOf(CompanionShelfDensity.DENSE, CompanionShelfDensity.LARGE), steps)
        assertEquals(CompanionShelfDensity.LARGE.perPage, count(CompanionShelfTileTag))
        assertEquals("2×1", textLayouts(CompanionShelfDensityChipTextTag).single().layoutInput.text.text)
    }

    @Test
    fun `an outside tap closes the popover and reaches nothing under it, a swipe does not turn the page, and the chip toggles it`() {
        val steps = mutableListOf<CompanionShelfDensity>()
        var backs = 0
        mount(CompanionShelfDensity.STANDARD, 1.0f, onStep = { steps += it }, onBack = { backs++ })
        tapChip()
        assertEquals(1, count(CompanionShelfDensityMenuTag))
        assertEquals(1, count(CompanionShelfDensityCatcherTag))
        // A swipe up on the page under the catcher turns nothing: the catcher takes the touch (and, like any scrim, lets go as a close).
        compose.onNodeWithTag(CompanionShelfDensityCatcherTag).performTouchInput { swipeUp() }
        settle()
        assertEquals("the pager never saw the swipe", "Page 1 of 6", describe(CompanionShelfPageRunesTag))
        assertEquals("the swipe closed the popover", 0, count(CompanionShelfDensityMenuTag))
        assertEquals("and left no catcher", 0, count(CompanionShelfDensityCatcherTag))
        tapChip()
        assertEquals(1, count(CompanionShelfDensityCatcherTag))
        // A tap on a tile (outside the popover) only closes it: no launch card opens.
        val tile = allBoundsDp(CompanionShelfTileTag).minWith(compareBy({ it[1] }, { it[0] }))
        compose.onNodeWithTag(CompanionShelfDensityCatcherTag).performTouchInput {
            click(Offset((tile[0] + tile[2] / 2f) * density, (tile[1] + tile[3] / 2f) * density))
        }
        settle()
        assertEquals("the popover closed", 0, count(CompanionShelfDensityMenuTag))
        assertEquals("the catcher is gone", 0, count(CompanionShelfDensityCatcherTag))
        assertEquals("no launch card opened", 0, count(CompanionLaunchCardTag, unmerged = true))
        assertEquals("no step", emptyList<CompanionShelfDensity>(), steps)
        assertEquals("no back", 0, backs)
        // The chip again opens it, and again closes it.
        tapChip()
        assertEquals(1, count(CompanionShelfDensityMenuTag))
        tapChip()
        assertEquals("the catcher is over the chip, so a tap on it closes the popover", 0, count(CompanionShelfDensityMenuTag))
    }

    @Test
    fun `the popover at font scale 1_3 keeps 48 dp rows and every rung's text inside its capsule and the panel`() {
        mount(CompanionShelfDensity.STANDARD, 1.3f)
        tapChip()
        val chip = boundsDp(CompanionShelfDensityChipTag)
        val menu = boundsDp(CompanionShelfDensityMenuTag)
        val host = floatArrayOf(0f, 0f, hostWidthDp, hostHeightDp)
        assertInside("the popover", menu, host)
        assertNear("it is still 200 dp wide", CompanionShelfDensityMenuWidthDp, menu[2], 1.2f)
        assertNear("its right edge is the chip's", chip[0] + chip[2], menu[0] + menu[2], 1.2f)
        val rows = CompanionShelfDensity.values().map { boundsDp(companionShelfDensityRungTag(it)) }
        val lg = MimirSpacing.lg.value
        for ((index, option) in CompanionShelfDensity.values().withIndex()) {
            val row = rows[index]
            val text = compose.onAllNodes(hasText(option.rungLabel), useUnmergedTree = true).fetchSemanticsNodes().single()
            val box = dp(text.boundsInRoot)
            val layout = mutableListOf<TextLayoutResult>()
            check(text.config[SemanticsActions.GetTextLayoutResult].action!!(layout))
            println("W2C-MENU13 ${option.rungLabel} row=${row.toList()} text=${box.toList()} lines=${layout.single().lineCount} lineRightPx=${layout.single().getLineRight(0)} nodePx=${box[2] * density}")
            assertTrue("row ${option.name} is a 48 dp target: ${row.toList()}", row[2] >= 47.5f && row[3] >= 47.5f)
            assertInside("row ${option.name}", row, menu)
            assertEquals("one line", 1, layout.single().lineCount)
            assertTrue("the text is not cut: line ends at ${layout.single().getLineRight(0)} px of a ${box[2] * density} px node", layout.single().getLineRight(0) <= box[2] * density + 1f)
            // The capsule is the text plus MimirSpacing.lg a side; it, and the ring around it, stay clear of the popover's edges.
            val capsuleLeft = box[0] - lg
            val capsuleRight = box[0] + box[2] + lg
            assertTrue("the capsule clears the popover's left edge by 8 dp: $capsuleLeft vs ${menu[0]}", capsuleLeft >= menu[0] + 8f)
            assertTrue("and its right edge: $capsuleRight vs ${menu[0] + menu[2]}", capsuleRight <= menu[0] + menu[2] - 8f)
            assertInside("the text is in its row", box, row)
        }
        for (i in 0 until rows.size - 1) assertFalse("rows $i and ${i + 1} overlap", overlaps(rows[i], rows[i + 1]))
    }

    private var swapPlatform by mutableStateOf("genesis")
    private var swapDensity by mutableStateOf(CompanionShelfDensity.STANDARD)

    private fun mountSwappable() {
        swapPlatform = "genesis"
        swapDensity = CompanionShelfDensity.STANDARD
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1f)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    Box(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).testTag("l4-host")) {
                        CompanionPlatformShelf(
                            platformIdValue = swapPlatform,
                            label = swapPlatform,
                            repository = GamesRepository(games(43)),
                            filedOrder = emptyList(),
                            onArranged = {},
                            onBack = {},
                            onLaunch = {},
                            active = true,
                            density = swapDensity,
                            onDensityChange = {},
                        )
                    }
                }
            }
        }
        settle()
    }

    @Test
    fun `the popover is closed by a replacement platform and by a density that changes from outside`() {
        mountSwappable()
        tapChip()
        assertEquals("open", 1, count(CompanionShelfDensityMenuTag))
        // The shelf is replaced by another platform's: the menu does not stay open over it.
        swapPlatform = "snes"
        settle()
        assertEquals("a new platform starts closed", 0, count(CompanionShelfDensityMenuTag))
        assertEquals("and leaves no catcher", 0, count(CompanionShelfDensityCatcherTag))
        // The app's persisted density changes under an open menu (a restored value): it closes and the chip follows.
        tapChip()
        assertEquals("open again", 1, count(CompanionShelfDensityMenuTag))
        swapDensity = CompanionShelfDensity.DENSE
        settle()
        assertEquals("an outside density change closes it", 0, count(CompanionShelfDensityMenuTag))
        assertEquals(0, count(CompanionShelfDensityCatcherTag))
        assertEquals("6×3", textLayouts(CompanionShelfDensityChipTextTag).single().layoutInput.text.text)
    }

    @Test
    fun `the chip and a rung report their receipts, and a rung still writes the density receipt`() {
        ShadowLog.clear()
        mount(CompanionShelfDensity.STANDARD, 1.0f, display = 1)
        tapChip()
        compose.onNodeWithTag(companionShelfDensityRungTag(CompanionShelfDensity.DENSE)).performClick()
        settle()
        compose.onNodeWithTag(CompanionShelfDensityChipTag).performClick()
        settle()
        compose.onNodeWithTag(companionShelfDensityRungTag(CompanionShelfDensity.LARGE)).performClick()
        settle()
        val lines = ShadowLog.getLogsForTag(BottomUiReceiptFormat.TAG).map { it.msg }
        println("W2C-RECEIPTS ${lines.filter { it.contains("density") }}")
        assertTrue("the chip: $lines", lines.any { it.contains("platform-shelf.density_menu") })
        assertTrue("a denser rung: $lines", lines.any { it.contains("shelf.density_denser") })
        assertTrue("a larger rung: $lines", lines.any { it.contains("shelf.density_larger") })
    }

    @Test
    fun `paging goes by whole pages, the runes follow, and the last page is partial`() {
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        assertEquals("Page 1 of 6", describe(CompanionShelfPageRunesTag))
        assertEquals(8, count(CompanionShelfTileTag))
        compose.onNodeWithTag(CompanionShelfPageAreaTag).performTouchInput { swipeUp() }
        settle()
        assertEquals("Page 2 of 6", describe(CompanionShelfPageRunesTag))
        assertEquals(8, count(CompanionShelfTileTag))
        val first = boundsDp(CompanionShelfPageAreaTag)
        for (tile in allBoundsDp(CompanionShelfTileTag)) assertInside("tile on page 2 sits in the page", tile, first)
        repeat(4) {
            compose.onNodeWithTag(CompanionShelfPageAreaTag).performTouchInput { swipeUp() }
            settle()
        }
        assertEquals("Page 6 of 6", describe(CompanionShelfPageRunesTag))
        assertEquals("43 games: the last page holds 3", 3, count(CompanionShelfTileTag))
    }

    @Test
    fun `a carried tile is dropped on the next seat and the filed order is the whole visible order`() {
        val filed = mutableListOf<List<String>>()
        mount(CompanionShelfDensity.STANDARD, 1.0f, count = 12, onArranged = { filed += it })
        val tiles = allBoundsDp(CompanionShelfTileTag)
        val first = tiles.minWith(compareBy({ it[1] }, { it[0] }))
        val pitch = (tiles.maxOf { it[0] } - tiles.minOf { it[0] }) / (CompanionShelfDensity.STANDARD.columns - 1)
        compose.onAllNodes(hasTestTag(CompanionShelfTileTag))[0].performTouchInput {
            down(center)
        }
        compose.mainClock.advanceTimeBy(900L)
        compose.onAllNodes(hasTestTag(CompanionShelfTileTag))[0].performTouchInput {
            moveBy(Offset(pitch * density + 4f, 0f))
        }
        compose.mainClock.advanceTimeBy(100L)
        compose.onAllNodes(hasTestTag(CompanionShelfTileTag))[0].performTouchInput { up() }
        settle()
        println("L4-SHELF drag first=${first.toList()} pitch=$pitch filed=$filed")
        assertEquals("one drop filed", 1, filed.size)
        assertEquals("g002", filed.single()[0])
        assertEquals("g001", filed.single()[1])
        assertEquals("the whole visible order is handed back", 12, filed.single().size)
    }

    private fun swipePage(up: Boolean) {
        compose.onNodeWithTag(CompanionShelfPageAreaTag).performTouchInput { if (up) swipeUp() else swipeDown() }
        settle()
    }

    private fun pickRung(target: CompanionShelfDensity) {
        tapChip()
        compose.onNodeWithTag(companionShelfDensityRungTag(target)).performClick()
        settle()
    }

    private fun firstTitle(): String = textLayouts(CompanionShelfTileLabelTag).first().layoutInput.text.text

    @Test
    fun `the place follows the page the player is on, a return to the opening page included`() {
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        // Standard page 1 -> page 2 -> page 1, then Large: the player is on seats 0..7, so Large opens at the start, not at seat 8's page.
        swipePage(up = true)
        assertEquals("Page 2 of 6", describe(CompanionShelfPageRunesTag))
        swipePage(up = false)
        assertEquals("Page 1 of 6", describe(CompanionShelfPageRunesTag))
        pickRung(CompanionShelfDensity.LARGE)
        assertEquals("Large opens on the first page", "Page 1 of 22", describe(CompanionShelfPageRunesTag))
        assertTrue("and it holds game 01: ${firstTitle()}", firstTitle().startsWith("Game 01 "))
    }

    @Test
    fun `a step lands on the page that holds the seat the player turned to`() {
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        swipePage(up = true) // seats 8..15
        pickRung(CompanionShelfDensity.LARGE)
        assertEquals("seat 8 at 2 a page is page 5", "Page 5 of 22", describe(CompanionShelfPageRunesTag))
        assertTrue(firstTitle(), firstTitle().startsWith("Game 09 "))
        pickRung(CompanionShelfDensity.STANDARD)
        pickRung(CompanionShelfDensity.DENSE)
        assertEquals("seat 8 at 18 a page is page 1", "Page 1 of 3", describe(CompanionShelfPageRunesTag))
    }

    @Test
    fun `a step during a page snap is not overwritten by the departing pager`() {
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        // A short flick up: the snap to page 2 is still to run (the pager crosses half-way during it) when the chip's rung is tapped.
        compose.onNodeWithTag(CompanionShelfPageAreaTag).performTouchInput {
            swipeUp(startY = bottom - 1f, endY = bottom - height * 0.4f, durationMillis = 100)
        }
        // The chip and then the Dense rung, one frame between them and no settling after the flick: the snap is still running under both taps.
        compose.onNodeWithTag(CompanionShelfDensityChipTag).performClick()
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(16L)
        compose.waitForIdle()
        compose.onNodeWithTag(companionShelfDensityRungTag(CompanionShelfDensity.DENSE)).performClick()
        settle()
        println("L4-SHELF snap+step runes='${describe(CompanionShelfPageRunesTag)}'")
        // Dense holds seats 0..17: whichever page the departing Standard pager ended on, the live one is Dense's page 1, and only it speaks.
        assertEquals("Page 1 of 3", describe(CompanionShelfPageRunesTag))
        assertEquals(CompanionShelfDensity.DENSE.perPage, count(CompanionShelfTileTag))
    }

    @Test
    fun `a drag that crosses the page midpoint and springs back is not a turn, so a step still reopens on the original page`() {
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        // Standard page 2 holds seats 8..15, so the player's place is seat 8.
        swipePage(up = true)
        assertEquals("Page 2 of 6", describe(CompanionShelfPageRunesTag))
        // Dense holds seats 0..17 on its first page, so seat 8 is on Dense's page 1 (and the anchor must stay 8).
        pickRung(CompanionShelfDensity.DENSE)
        assertEquals("Page 1 of 3", describe(CompanionShelfPageRunesTag))
        // Drag the page up past its midpoint with the finger still down: the indicator follows the nearest page, so it reads page 2 ...
        compose.onNodeWithTag(CompanionShelfPageAreaTag).performTouchInput {
            down(center)
            moveBy(Offset(0f, -height * 0.6f))
        }
        settle(100L)
        assertEquals("the indicator follows the drag past the midpoint", "Page 2 of 3", describe(CompanionShelfPageRunesTag))
        // ... then drag back to where it started and let go: no turn completed.
        compose.onNodeWithTag(CompanionShelfPageAreaTag).performTouchInput {
            moveBy(Offset(0f, height * 0.6f))
            up()
        }
        settle()
        println("L4-SHELF midpoint spring-back runes='${describe(CompanionShelfPageRunesTag)}'")
        assertEquals("Page 1 of 3", describe(CompanionShelfPageRunesTag))
        // Back to Standard: the place is still seat 8, which is Standard's page 2 (it was 18 -> 0 -> page 1 before the anchor followed settled pages).
        pickRung(CompanionShelfDensity.STANDARD)
        assertEquals("Standard reopens on the page that holds seat 8", "Page 2 of 6", describe(CompanionShelfPageRunesTag))
        assertTrue("and it holds game 09: ${firstTitle()}", firstTitle().startsWith("Game 09 "))
    }

    @Test
    fun `a completed turn still moves the place a step carries over`() {
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        pickRung(CompanionShelfDensity.DENSE)
        assertEquals("Page 1 of 3", describe(CompanionShelfPageRunesTag))
        swipePage(up = true) // Dense page 2: seats 18..35
        assertEquals("Page 2 of 3", describe(CompanionShelfPageRunesTag))
        pickRung(CompanionShelfDensity.STANDARD)
        // Seat 18 at 8 a page is page 3, which opens on seat 16 (game 17).
        assertEquals("Page 3 of 6", describe(CompanionShelfPageRunesTag))
        assertTrue(firstTitle(), firstTitle().startsWith("Game 17 "))
    }

    @Test
    fun `a tile opens the launch card in the saved look, and its Look switch changes the face and reports it`() {
        // Slices L5 and L6 through the shelf itself: the card is the shelf's, in the global look the app passes (here a restored Journal).
        val looks = mutableListOf<CompanionLaunchLook>()
        mount(CompanionShelfDensity.STANDARD, 1.0f, count = 12, look = CompanionLaunchLook.JOURNAL, onLook = { looks += it })
        assertEquals("no card until a tile is tapped", 0, count(CompanionLaunchCardTag, unmerged = true))
        compose.onAllNodes(hasTestTag(CompanionShelfTileTag))[0].performClick()
        settle()
        assertEquals(1, count(CompanionLaunchCardTag, unmerged = true))
        val card = boundsDp(CompanionLaunchCardTag, unmerged = true)
        println("L5-SHELF card=${card.toList()}")
        assertNear("the card is 470 dp wide", 470f, card[2])
        assertEquals("it opens in the saved look: the Journal's grid is drawn", 1, count(CompanionLaunchCardFactsTag, unmerged = true))
        assertEquals("with the shelf's own platform as the eyebrow", "GENESIS", textLayouts(CompanionLaunchCardEyebrowTag).single().layoutInput.text.text)
        compose.onNodeWithTag(CompanionLaunchCardLookTag).performClick()
        settle()
        assertEquals(listOf(CompanionLaunchLook.LOGO_ONLY), looks)
        assertEquals("the card changed face", 1, count(CompanionLaunchCardStageTag, unmerged = true))
        assertEquals(0, count(CompanionLaunchCardFactsTag, unmerged = true))
        // PLAY is the card's, a 48 dp ring pill.
        val play = boundsDp(CompanionLaunchCardPlayTag, unmerged = true)
        assertTrue("PLAY is at least 48 dp: ${play.toList()}", play[2] >= 47.5f && play[3] >= 47.5f)
    }

    // ── The icon seat ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `before the art pass has proved anything the icon seat is empty and keeps its 40 dp`() {
        PlatformTileArtStore.hydrated.value = false
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        assertEquals(0, count(CompanionShelfIconWiredTag))
        assertEquals(0, count(CompanionShelfIconConsoleTag))
        val seat = boundsDp(CompanionShelfIconSeatTag)
        assertNear("the seat is 40 dp wide", CompanionShelfIconSeatDp, seat[2])
        assertNear("the seat is 40 dp tall", CompanionShelfIconSeatDp, seat[3])
    }

    @Test
    fun `with no wired art the seat holds the console line art at full ink, in the row, and nothing draws behind the header`() {
        mount(CompanionShelfDensity.STANDARD, 1.0f)
        assertEquals(1, count(CompanionShelfIconConsoleTag))
        assertEquals("the console art is not wired art", 0, count(CompanionShelfIconWiredTag))
        val seat = boundsDp(CompanionShelfIconSeatTag)
        val art = boundsDp(CompanionShelfIconConsoleTag)
        val row = boundsDp(MimirCompactHeaderRowTag)
        val pill = boundsDp(CompanionShelfBackPillTag)
        val chip = boundsDp(CompanionShelfDensityChipTag)
        println("W2C-ICON console seat=${seat.toList()} art=${art.toList()} row=${row.toList()}")
        assertNear("the art fills the seat (left)", seat[0], art[0])
        assertNear("the art fills the seat (width)", seat[2], art[2])
        assertNear("the art fills the seat (height)", seat[3], art[3])
        assertInside("the seat is in the header row", seat, row)
        assertFalse("the seat and the pill", overlaps(seat, pill))
        assertFalse("the seat and the chip", overlaps(seat, chip))
        // Nothing is drawn in the band but the header: the band's only descendants are the header's own nodes.
        val band = boundsDp(CompanionShelfHeaderBandTag)
        assertInside("the art stays in the band", art, band)
        assertEquals("one icon in the header, not a second one behind it", 1, count(CompanionShelfIconConsoleTag) + count(CompanionShelfIconWiredTag))
    }

    private fun tempArt(bytes: ByteArray): String {
        val file = File.createTempFile("l4-art", ".png")
        file.writeBytes(bytes)
        tempFiles += file
        return file.toURI().toString().replaceFirst("file:/", "file:///")
    }

    private fun mountHeader(states: MutableList<AsyncImagePainter.State>) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, 1.0f)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    Box(Modifier.requiredSize(hostWidthDp.dp, 96.dp).testTag("l4-host")) {
                        CompanionShelfHeader(
                            platformIdValue = platformId,
                            title = "Genesis",
                            gameCount = 5,
                            density = CompanionShelfDensity.STANDARD,
                            active = true,
                            onBack = {},
                            onDensityMenu = {},
                            onWiredIconState = { states += it },
                        )
                    }
                }
            }
        }
        settle()
    }

    private fun awaitArt(states: List<AsyncImagePainter.State>) {
        repeat(120) {
            if (states.any { it is AsyncImagePainter.State.Success || it is AsyncImagePainter.State.Error }) return
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50L)
        }
    }

    // A 1 x 1 PNG.
    private val png = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
    )

    @Test
    fun `wired platform art is drawn bare and the console art is not`() {
        PlatformTileArtStore.art.value = mapOf(platformId to tempArt(png))
        val states = mutableListOf<AsyncImagePainter.State>()
        mountHeader(states)
        assertEquals(1, count(CompanionShelfIconWiredTag))
        assertEquals("wired art stands in no console art", 0, count(CompanionShelfIconConsoleTag))
        awaitArt(states)
        println("L4-SHELF backdrop wired states=${states.map { it::class.simpleName }}")
        assertTrue("it loaded: $states", states.any { it is AsyncImagePainter.State.Success })
        assertEquals("still no console art after it loaded", 0, count(CompanionShelfIconConsoleTag))
        val seat = boundsDp(CompanionShelfIconSeatTag)
        val art = boundsDp(CompanionShelfIconWiredTag)
        assertNear("the wired art fills the 40 dp seat", CompanionShelfIconSeatDp, art[2])
        assertNear("the wired art fills the 40 dp seat", CompanionShelfIconSeatDp, art[3])
        assertNear("and sits in it", seat[0], art[0])
    }

    @Test
    fun `wired art that fails to decode draws nothing in its place`() {
        PlatformTileArtStore.art.value = mapOf(platformId to tempArt(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        val states = mutableListOf<AsyncImagePainter.State>()
        mountHeader(states)
        awaitArt(states)
        println("L4-SHELF backdrop failed states=${states.map { it::class.simpleName }}")
        assertTrue("it failed: $states", states.any { it is AsyncImagePainter.State.Error })
        assertEquals("the wired slot stays, drawing nothing (no error painter)", 1, count(CompanionShelfIconWiredTag))
        assertEquals("a failed load is not console art", 0, count(CompanionShelfIconConsoleTag))
        assertNear("the seat keeps its 40 dp", CompanionShelfIconSeatDp, boundsDp(CompanionShelfIconSeatTag)[2])
    }

    @Test
    fun `wired art whose file was deleted is no art, so the console art`() {
        val uri = tempArt(png)
        File(java.net.URI(uri.replaceFirst("file:///", "file:/"))).delete()
        PlatformTileArtStore.art.value = mapOf(platformId to uri)
        val states = mutableListOf<AsyncImagePainter.State>()
        mountHeader(states)
        settle(500L)
        Thread.sleep(200)
        shadowOf(Looper.getMainLooper()).idle()
        settle(500L)
        assertEquals(0, count(CompanionShelfIconWiredTag))
        assertEquals(1, count(CompanionShelfIconConsoleTag))
    }
}
