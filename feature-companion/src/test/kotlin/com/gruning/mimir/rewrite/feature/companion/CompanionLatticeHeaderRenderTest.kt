package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirHeaderRuleHeightDp
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderBadgeTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRuleGapDp
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderHeightDp
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRowDp
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderRuleTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderStatTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTaglineTag
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderTitleTag
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * V3 of the Thor Bottom revamp, rendered: the lattice's one header and the cold-start cover, on the REAL
 * [CompanionSurface] and the real [CompanionMannazCover], under Robolectric's native graphics with real text shaping.
 *
 * The size is the Thor Bottom's: 1240 x 1080 px at 369 dpi (density 369 / 160 = 2.30625), which is 537.7 x 468.3 dp (the Presentation draws the full 1080 px, not the 445 dp window; ledger D18/D19). The font scales
 * are 1.0, the certified 1.3, 1.5 and 2.0. Every number the report quotes is printed here with the tag `V3-RENDER`. The cover's handoff fade is
 * not tested here: it is the Presentation's (CompanionPresentationColdCoverTest, in :app, through the real window), and this class pins what the cover looks like.
 *
 * Pixel values are the JVM's; the pixels on a handheld are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionLatticeHeaderRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1080f / Density369

    private val stat = "19 platforms · 1,234 games"
    private val tiles = (1..19).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
    private val payload = CompanionPayload(
        mode = CompanionMode.HOME,
        statsLabel = stat,
        homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
    )

    private var titleNaturalPx = 0
    private var titleBaselinePx = 0f
    private var taglineBaselinePx = 0f
    private var density = 0f

    private fun mountSurface(fontScale: Float, swipeHint: Boolean? = null) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val scaled = Density(Density369, fontScale)
            CompositionLocalProvider(LocalDensity provides scaled) {
                MimirTheme {
                    val measurer = rememberTextMeasurer()
                    val titleMeasure = measurer.measure(AnnotatedString("MIMIR"), MimirTypeScale.TitleCaps, maxLines = 1)
                    titleNaturalPx = titleMeasure.size.width
                    titleBaselinePx = titleMeasure.firstBaseline
                    taglineBaselinePx = measurer.measure(AnnotatedString(CompanionHeaderTagline), MimirTypeScale.Support, maxLines = 1).firstBaseline
                    density = LocalDensity.current.density
                    CompanionSurface(
                        payload = payload,
                        cursorId = "p1",
                        // The host offers the Activity swipe only when it passes the callback: null here is the V3 pin's mount, no hint at all; a Boolean is the Presentation's,
                        // with the hint shown (true) or retired (false).
                        onWorkspaceDrag = if (swipeHint != null) ({ _: Float -> 0f }) else null,
                        showActivitySwipeHint = swipeHint ?: true,
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_500L)
        compose.waitForIdle()
    }

    private fun boundsDp(tag: String): FloatArray {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return floatArrayOf(b.left / density, b.top / density, b.width / density, b.height / density)
    }

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float = 0.6f) =
        assertTrue("$message: expected $expected, was $actual", abs(expected - actual) <= tolerance)

    private fun shapedLayout(tag: String): TextLayoutResult {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) { "$tag has no layout action" }
        check(action(layouts)) { "$tag has no text layout yet" }
        return layouts.single()
    }

    private fun overlaps(a: FloatArray, b: FloatArray, tolerance: Float = 0.5f): Boolean =
        a[0] + a[2] - tolerance > b[0] && b[0] + b[2] - tolerance > a[0] &&
            a[1] + a[3] - tolerance > b[1] && b[1] + b[3] - tolerance > a[1]

    private fun assertHeader(fontScale: Float) {
        mountSurface(fontScale)

        val header = boundsDp(MimirCompactHeaderTag)
        val row = boundsDp(MimirCompactHeaderRowTag)
        val body = boundsDp(CompanionShellBodyTag)
        val badge = boundsDp(MimirCompactHeaderBadgeTag)
        val title = boundsDp(MimirCompactHeaderTitleTag)
        val tagline = boundsDp(MimirCompactHeaderTaglineTag)
        val stat = boundsDp(MimirCompactHeaderStatTag)
        val rule = boundsDp(MimirCompactHeaderRuleTag)
        val rowBottom = row[1] + row[3]
        println(
            "H0B-RENDER header fontScale=$fontScale size=${"%.2f".format(widthDp)}x${"%.2f".format(heightDp)}dp header=${header[3]}dp " +
                "row=${row[3]}dp rule=${rule[3]}dp badge=${badge[2]}x${badge[3]}dp bodyTop=${body[1]}",
        )
        println(
            "H0B-RENDER row fontScale=$fontScale title=${title[0]},${title[1]} ${title[2]}x${title[3]} tagline=${tagline[0]},${tagline[1]} ${tagline[2]}x${tagline[3]} " +
                "stat=${stat[0]},${stat[1]} ${stat[2]}x${stat[3]} headerRight=${header[0] + header[2]}",
        )

        // The promoted badge at 32 dp.
        assertNear("badge width", 32f, badge[2], 0.3f)
        assertNear("badge height", 32f, badge[3], 0.3f)

        // The header's height is the row, the gap and the 3 dp rule: nothing overhangs it any more, so nothing below the row grows it.
        assertNear("header height at font scale $fontScale is the row, the gap and the rule", row[3] + MimirCompactHeaderRuleGapDp + MimirHeaderRuleHeightDp, header[3], 0.8f)
        assertNear("the rule is its own height", MimirHeaderRuleHeightDp.toFloat(), rule[3], 0.5f)
        assertTrue("the rule is under the row: rule ${rule[1]} vs row bottom $rowBottom", rule[1] >= rowBottom + MimirCompactHeaderRuleGapDp - 0.6f)
        assertTrue("the rule stays inside the header: rule bottom ${rule[1] + rule[3]} header bottom ${header[1] + header[3]}", rule[1] + rule[3] <= header[1] + header[3] + 0.5f)
        assertTrue("the body starts at or below the header's bottom", body[1] >= header[1] + header[3] - 0.5f)
        if (fontScale == 1.0f) {
            // Nothing moves at the default font scale: the header is the 41 dp it measured with the eyebrow's reserve (95 px at 369 dpi), and A1's cell, which is solved from
            // what the header leaves, is the cell it was.
            assertEquals("the header's height is the row, the gap and the rule", 41f, MimirCompactHeaderHeightDp, 0.001f)
            assertEquals("the header's height at the default font scale is unchanged by removing the eyebrow", 41.19241f, header[3], 0.01f)
            assertTrue("title is one line inside the 32 dp row, was ${title[3]}", title[3] <= MimirCompactHeaderRowDp + 0.5f)
        }

        // The eyebrow is gone: no node anywhere draws the rune line, the old wordmark, the typed rune or the old count.
        for (gone in listOf("ᛗ", "M I M I R", "19 consoles")) {
            val found = compose.onAllNodes(hasText(gone) or hasContentDescription(gone)).fetchSemanticsNodes()
            assertTrue("\"$gone\" must not be on the lattice any more", found.isEmpty())
        }
        val runic = compose.onAllNodes(hasText("WELL OF MEMORY", substring = true) or hasContentDescription("WELL OF MEMORY", substring = true)).fetchSemanticsNodes()
        assertTrue("the rune eyebrow must not be on the lattice any more", runic.isEmpty())

        // "Mimir" is whole at every scale: its node is no narrower than the text wants, on one line, and the product name is spoken.
        assertTrue("title ($titleNaturalPx px) fits its node (${title[2] * density} px)", title[2] * density + 1f >= titleNaturalPx)
        assertTrue(
            "the title is spoken as the product name",
            compose.onAllNodes(hasContentDescription("Mimir")).fetchSemanticsNodes().isNotEmpty(),
        )

        // The tagline: lowercase wording, no dash or other separator, after the title, on the title's baseline.
        val taglineLayout = shapedLayout(MimirCompactHeaderTaglineTag)
        println(
            "H0B-RENDER tagline fontScale=$fontScale text='${taglineLayout.layoutInput.text.text}' lines=${taglineLayout.lineCount} " +
                "ellipsized=${taglineLayout.isLineEllipsized(0)} overflowH=${taglineLayout.didOverflowHeight}",
        )
        assertEquals("lattice tagline", "the well of memory", taglineLayout.layoutInput.text.text)
        assertEquals("the tagline is one line", 1, taglineLayout.lineCount)
        assertTrue("the tagline is after the title: title right ${title[0] + title[2]}, tagline left ${tagline[0]}", tagline[0] >= title[0] + title[2] - 0.5f)
        assertFalse("the tagline must not overlap the title", overlaps(tagline, title))
        assertFalse("the tagline must not overlap the badge", overlaps(tagline, badge))
        assertNear(
            "the tagline stands on the title's baseline",
            title[1] + titleBaselinePx / density,
            tagline[1] + taglineBaselinePx / density,
            0.6f,
        )
        assertTrue("the tagline stays inside the header: ${tagline.toList()} in ${header.toList()}", tagline[0] + tagline[2] <= header[0] + header[2] + 0.5f)

        // The stat is plain text with its own semantics, right-aligned, and it is the FIRST to yield: it is shown whole or not at all next to the tagline, and
        // the tagline is never cut while the stat is still shown.
        val statLayout = shapedLayout(MimirCompactHeaderStatTag)
        println("H0B-RENDER stat fontScale=$fontScale text='${statLayout.layoutInput.text.text}' lines=${statLayout.lineCount} overflowW=${statLayout.didOverflowWidth} width=${stat[2]}dp")
        assertEquals("Main's own count line, from the payload", this.stat, statLayout.layoutInput.text.text)
        val statShown = stat[2] > 0.5f
        if (statShown) {
            assertEquals("the stat is one line", 1, statLayout.lineCount)
            assertFalse("a shown stat is whole, not cut", statLayout.didOverflowWidth || statLayout.didOverflowHeight)
            assertNear("the stat is right-aligned to the header's edge", header[0] + header[2], stat[0] + stat[2], 0.6f)
            assertFalse("the stat must not overlap the tagline", overlaps(stat, tagline))
            assertFalse("the stat must not overlap the title", overlaps(stat, title))
            assertFalse("a tagline that is cut next to a shown stat means the stat did not yield first", taglineLayout.isLineEllipsized(0))
            assertNear("the stat stands on the same baseline", title[1] + titleBaselinePx / density, stat[1] + taglineBaselinePx / density, 0.6f)
        }
        if (fontScale <= 1.0f) {
            assertTrue("at the default font scale the stat is shown", statShown)
            assertFalse("and the tagline is whole", taglineLayout.isLineEllipsized(0))
        }

        // The page position says where it is, in one node.
        val runes = compose.onNodeWithTag(CompanionPageRunesTag).fetchSemanticsNode()
        assertEquals(listOf("Page 1 of 3"), runes.config[SemanticsProperties.ContentDescription])

        // A1's cell re-solved from the cell the lattice now has: slot 0 of the real surface.
        val cell = boundsDp(companionSlotTag(0))
        val solved = solveCompanionLatticeCell(cell[2], cell[3], fontScale)
        println(
            "V3-RENDER cell fontScale=$fontScale slot=${cell[2]}x${cell[3]}dp top=${cell[1]}dp " +
                "ring=${solved.ringEdgeDp} face=${solved.faceEdgeDp} band=${solved.bandDp} lines=${solved.labelLines}",
        )
        if (fontScale == 1.0f) {
            // The A1 numbers at the default font scale on the Thor's real 468.3 dp Bottom, with no swipe callback (so no hint row) and since the page diamonds moved onto the frame no
            // footer at all. The body is 865 px (1080 less the 23 px crown, 95 px header, 18 px gap, 23 px base and the rail's two 28 px insets), which three rows do not share evenly: row 0
            // takes the spare pixel, 289 px = 125.31 dp (288 px = 124.88 dp for rows 1 and 2). These pins stood at 117.07 on the 444.4 dp host the revamp first measured (ledger D19).
            // The header and the cell width are unchanged.
            assertEquals(150.8943f, cell[2], 0.01f)
            assertEquals(125.31165f, cell[3], 0.01f)
            assertEquals(81.31165f, solved.ringEdgeDp, 0.01f)
            // The face is the ring less the one ring's 8 dp of sibling clearance (73.31 here; 65.07 on the 444.4 dp host).
            assertEquals(73.31165f, solved.faceEdgeDp, 0.01f)
            assertEquals(36f, solved.bandDp, 0.01f)
        }
        assertEquals("two name lines still fit the cell", CompanionLatticeLabelLines, solved.labelLines)
        assertTrue("the face keeps an area", solved.faceEdgeDp > 0f)
    }

    @Test
    fun `the lattice header at the Thor Bottom size and the default font scale`() = assertHeader(1.0f)

    @Test
    fun `the lattice header at the Thor Bottom size and the certified font scale`() = assertHeader(1.3f)

    @Test
    fun `the lattice header at the Thor Bottom size and font scale 1_5`() = assertHeader(1.5f)

    @Test
    fun `the lattice header at the Thor Bottom size and font scale 2_0`() = assertHeader(2.0f)

    /**
     * B6: the header does not move with the hint, and the cell grows by the footer when it is retired. At the default font scale the Presentation's mount (the swipe callback passed) has the same
     * 41.19 dp header, and the hint's row (the 6 dp spacer and the footer's 13 dp minimum, 44 px together) is what the grid gives up while it shows: 151 x 118.37 dp with the hint shown,
     * 151 x 125.31 dp retired, on the real 468.3 dp Bottom ([CompanionLatticeFootprintRenderTest] prints the footer and the 1.3 case).
     */
    private fun assertHeaderHoldsWithHint(shown: Boolean) {
        mountSurface(1.0f, swipeHint = shown)
        val header = boundsDp(MimirCompactHeaderTag)
        val cell = boundsDp(companionSlotTag(0))
        val solved = solveCompanionLatticeCell(cell[2], cell[3], 1.0f)
        println("V6-RENDER hint=${if (shown) "shown" else "retired"} header=${header[3]}dp cell=${cell[2]}x${cell[3]}dp ring=${solved.ringEdgeDp} face=${solved.faceEdgeDp}")
        assertEquals("the header does not move with the hint", 41.19241f, header[3], 0.01f)
        assertEquals(150.8943f, cell[2], 0.01f)
        // The hint shown: its row (6 dp spacer + 13 dp) is under the frame and the body is 821 px, so row 0 is the three-way split short by a pixel: 273 px = 118.37 dp. Retired: no row and
        // no spacer, the diamonds stand on the frame and take no height, and the 19 dp is the grid's: 865 px, row 0 is 289 px = 125.31 dp and the face 73.31.
        val cellH = if (shown) 118.373984f else 125.31165f
        assertEquals("the cell with the hint ${if (shown) "shown" else "retired"}", cellH, cell[3], 0.01f)
        assertEquals(cellH - 44f, solved.ringEdgeDp, 0.01f)
        assertEquals(cellH - 52f, solved.faceEdgeDp, 0.01f)
    }

    @Test
    fun `the header and the cell hold at the default font scale with the swipe hint shown`() = assertHeaderHoldsWithHint(shown = true)

    @Test
    fun `the header and the cell hold at the default font scale with the swipe hint retired`() = assertHeaderHoldsWithHint(shown = false)

    private fun mountCover(fontScale: Float, visible: androidx.compose.runtime.MutableState<Boolean>) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompanionMannazCover(modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp), visible = visible.value)
                }
            }
        }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
    }

    private fun assertCover(fontScale: Float) {
        val visible = mutableStateOf(true)
        mountCover(fontScale, visible)

        val badge = boundsDp(CompanionColdCoverBadgeTag)
        val cover = boundsDp(CompanionColdCoverTag)
        println("V3-RENDER cover fontScale=$fontScale cover=${cover[2]}x${cover[3]}dp badge=${badge[2]}x${badge[3]}dp")
        assertNear("badge width", 56f, badge[2], 0.3f)
        assertNear("badge height", 56f, badge[3], 0.3f)
        assertEquals("the badge's own constant", 56f, CompanionColdCoverBadgeDp, 0.001f)
        // Centred on the cover.
        assertNear("badge centre x", cover[0] + cover[2] / 2f, badge[0] + badge[2] / 2f, 1f)
        assertNear("badge centre y", cover[1] + cover[3] / 2f, badge[1] + badge[3] / 2f, 1f)

        // No wordmark, no sigil text, no copy: the cover carries no text node and no spoken description at all.
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
        assertTrue("the cold-start cover carries no text, found ${texts.map { it.config }}", texts.isEmpty())
        for (gone in listOf("M I M I R", "MIMIR", "ᛗ", "Returning to shelf…", "Ready when you are")) {
            assertTrue(
                "\"$gone\" must not be on the cold-start cover",
                compose.onAllNodes(hasText(gone) or hasContentDescription(gone)).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun `the cold cover at the Thor Bottom size and the default font scale is the badge alone`() = assertCover(1.0f)

    @Test
    fun `the cold cover at the Thor Bottom size and the certified font scale is the badge alone`() = assertCover(1.3f)


    /**
     * the cover reports it has LEFT when its exit fade has finished (alpha 0), not when `visible` goes false, which is only the start of the
     * 180 ms fade. The Presentation holds the cursor ring's arming until this, so the sweep cannot begin under a half-faded badge.
     */
    @Test
    fun `the cold cover reports it is gone only once its exit fade has finished, and once`() {
        val visible = mutableStateOf(true)
        var gone = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, 1.0f)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompanionMannazCover(modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp), visible = visible.value, onGone = { gone++ })
                }
            }
        }
        compose.mainClock.advanceTimeBy(500L)
        compose.waitForIdle()
        assertEquals("a cover that is up has not gone", 0, gone)

        visible.value = false
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        var elapsed = 0L
        while (elapsed < 1_000L && gone == 0) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            elapsed += 16L
            if (elapsed < com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs - 40L) {
                assertEquals("it has not gone ${elapsed} ms into its ${com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs} ms fade", 0, gone)
                assertTrue("the badge is still on screen mid-fade", compose.onAllNodes(androidx.compose.ui.test.hasTestTag(CompanionColdCoverTag)).fetchSemanticsNodes().isNotEmpty())
            }
        }
        println("V6B-COVER goneAfter=${elapsed}ms fade=${com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs}ms")
        assertEquals(1, gone)
        assertTrue(
            "it is gone when the overlay-clock fade has run, took $elapsed ms",
            elapsed in (com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs - 24L)..(com.gruning.mimir.rewrite.core.ui.MimirMotion.OverlayMs + 64L),
        )
        assertTrue("the badge left the tree", compose.onAllNodes(androidx.compose.ui.test.hasTestTag(CompanionColdCoverTag)).fetchSemanticsNodes().isEmpty())
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        assertEquals("it reports once", 1, gone)
    }

    private companion object {
        /** The Thor Bottom's density: 369 dpi / 160. */
        const val Density369 = 369f / 160f
    }
}
