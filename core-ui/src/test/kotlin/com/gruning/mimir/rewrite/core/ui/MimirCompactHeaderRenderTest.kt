package com.gruning.mimir.rewrite.core.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
 * [MimirCompactHeader] rendered at the Thor Bottom Presentation's size, measured on the device on 2026-10-02: the full 1240 x 1080 px at 369 dpi
 * (density 369 / 160 = 2.30625), which is 537.7 x 468.3 dp. The header sits inside the Bottom shell's 18 dp side inset (502 dp wide), as the lattice's
 * and the Activity page's do, above an 8 dp clearance and a body box.
 *
 * Three headers, each at font scales 1.0, 1.3 and 2.0:
 *  - the LATTICE's (slice H0b): the badge, "Mimir", the tagline "the well of memory" on its baseline, and the stat, no slots;
 *  - the ACTIVITY page's: "Activity", its stat, no tagline, and the 48 dp back pill as the leading slot;
 *  - two 32 dp slots (the shelf's pill and density stepper), no tagline.
 * The rune eyebrow that used to sit on the rule is gone, so nothing overhangs the rule and the header is the row, the gap and
 * the 3 dp rule at every font scale. Every number is printed with the tag `H0B-HEADER`. The lattice's header on the real surface, with the A1 cell numbers, is
 * feature-companion's CompanionLatticeHeaderRenderTest. Pixel values are the JVM's; the handheld's are device-owed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MimirCompactHeaderRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f
    private val hostWidthDp = 1240f / density369
    private val hostHeightDp = 1080f / density369
    private val gutter = 18
    private val latticeStat = "19 platforms · 67 games"
    private val activityStat = "67 games"
    private val tagline = "the well of memory"

    private var density = 0f
    private var titleNaturalPx = 0
    private var titleBaselinePx = 0f
    private var supportBaselinePx = 0f

    private enum class Kind { LATTICE, ACTIVITY, SLOTS }

    private fun mount(
        fontScale: Float,
        kind: Kind,
        slotHeightDp: Int = 32,
        edgeToEdge: Boolean = false,
        hostWidth: Float = hostWidthDp,
        tag: String? = null,
    ) {
        val title = if (kind == Kind.ACTIVITY) "Activity" else "Mimir"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    val measurer = rememberTextMeasurer()
                    val titleMeasure = measurer.measure(AnnotatedString(title.uppercase()), MimirTypeScale.TitleCaps, maxLines = 1)
                    titleNaturalPx = titleMeasure.size.width
                    titleBaselinePx = titleMeasure.firstBaseline
                    supportBaselinePx = measurer.measure(AnnotatedString(tagline), MimirTypeScale.Support, maxLines = 1).firstBaseline
                    density = LocalDensity.current.density
                    Column(
                        modifier = Modifier
                            .requiredSize(hostWidth.dp, hostHeightDp.dp)
                            .padding(horizontal = if (edgeToEdge) 0.dp else gutter.dp),
                        verticalArrangement = Arrangement.spacedBy(MimirCompactHeaderClearanceDp.dp),
                    ) {
                        MimirCompactHeader(
                            title = title,
                            palette = LocalMimirPalette.current,
                            stat = if (kind == Kind.ACTIVITY) activityStat else latticeStat,
                            tagline = tag ?: if (kind == Kind.LATTICE) tagline else null,
                            leading = when (kind) {
                                Kind.LATTICE -> null
                                Kind.ACTIVITY -> {
                                    { Box(Modifier.size(48.dp).background(Color.Gray).testTag("slot-leading")) }
                                }
                                Kind.SLOTS -> {
                                    { Box(Modifier.size(56.dp, slotHeightDp.dp).background(Color.Gray).testTag("slot-leading")) }
                                }
                            },
                            trailing = if (kind == Kind.SLOTS) {
                                { Box(Modifier.size(96.dp, slotHeightDp.dp).background(Color.Gray).testTag("slot-trailing")) }
                            } else {
                                null
                            },
                        )
                        Box(Modifier.weight(1f).fillMaxWidth().testTag("body"))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun boundsDp(tag: String): FloatArray {
        val b = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return floatArrayOf(b.left / density, b.top / density, b.width / density, b.height / density)
    }

    private fun count(tag: String): Int = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size

    private fun overlaps(a: FloatArray, b: FloatArray, tolerance: Float = 0.5f): Boolean =
        a[0] + a[2] - tolerance > b[0] && b[0] + b[2] - tolerance > a[0] &&
            a[1] + a[3] - tolerance > b[1] && b[1] + b[3] - tolerance > a[1]

    private fun assertNear(message: String, expected: Float, actual: Float, tolerance: Float = 0.6f) =
        assertTrue("$message: expected $expected, was $actual", abs(expected - actual) <= tolerance)

    private fun assertInside(name: String, inner: FloatArray, outer: FloatArray) =
        assertTrue(
            "$name ${inner.toList()} must sit inside ${outer.toList()}",
            inner[0] >= outer[0] - 0.5f && inner[0] + inner[2] <= outer[0] + outer[2] + 0.5f &&
                inner[1] >= outer[1] - 0.5f && inner[1] + inner[3] <= outer[1] + outer[3] + 0.5f,
        )

    private fun textLayout(tag: String): TextLayoutResult {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(node.config[SemanticsActions.GetTextLayoutResult].action) { "$tag has no layout action" }
        check(action(layouts)) { "$tag has no text layout yet" }
        return layouts.single()
    }

    private fun assertHeader(fontScale: Float, kind: Kind) {
        mount(fontScale, kind)
        val header = boundsDp(MimirCompactHeaderTag)
        val row = boundsDp(MimirCompactHeaderRowTag)
        val body = boundsDp("body")
        val badge = boundsDp(MimirCompactHeaderBadgeTag)
        val title = boundsDp(MimirCompactHeaderTitleTag)
        val stat = boundsDp(MimirCompactHeaderStatTag)
        val rule = boundsDp(MimirCompactHeaderRuleTag)
        val hasTagline = kind == Kind.LATTICE
        val rowBottom = row[1] + row[3]
        val statLayout = textLayout(MimirCompactHeaderStatTag)
        println(
            "H0B-HEADER $kind fontScale=$fontScale host=${"%.2f".format(hostWidthDp)}x${"%.2f".format(hostHeightDp)}dp header=${header[2]}x${header[3]}dp " +
                "headerTop=${header[1]} rowTop=${row[1]} rowH=${row[3]} badgeTop=${badge[1]} ruleTop=${rule[1]} titleTop=${title[1]} statTop=${stat[1]} row=${row[3]}dp rule=${rule[3]}dp title=${title[2]}x${title[3]} stat=${stat[2]}x${stat[3]} statLines=${statLayout.lineCount} statCutW=${statLayout.didOverflowWidth}",
        )

        assertNear("badge width", 32f, badge[2], 0.3f)
        assertNear("badge height", 32f, badge[3], 0.3f)
        // Nothing overhangs the rule: the header is the row, the gap and the 3 dp rule at every font scale, and the body is one clearance under it.
        assertNear("header height is the row, the gap and the rule", row[3] + MimirCompactHeaderRuleGapDp + MimirHeaderRuleHeightDp, header[3], 0.8f)
        assertNear("the rule is its own height", MimirHeaderRuleHeightDp.toFloat(), rule[3], 0.5f)
        assertNear("the rule is one gap under the row", rowBottom + MimirCompactHeaderRuleGapDp, rule[1], 0.8f)
        assertEquals("the constant is the row, the gap and the rule", 41f, MimirCompactHeaderHeightDp, 0.001f)
        assertNear("the body starts one clearance under the header", header[1] + header[3] + MimirCompactHeaderClearanceDp, body[1], 0.6f)
        if (fontScale == 1.0f) {
            val expectedHeader = if (kind == Kind.ACTIVITY) 41.19241f + 16f else 41.19241f
            assertEquals("the header's height at the default font scale ($kind)", expectedHeader, header[3], 0.9f)
            if (kind != Kind.ACTIVITY) assertEquals("the lattice's 41.19 dp (95 px at 369 dpi), as it measured with the eyebrow's reserve", 41.19241f, header[3], 0.01f)
            if (kind != Kind.ACTIVITY) assertNear("the rule's own 3 dp sits as the header's last 3 dp", header[1] + header[3] - 3f, rule[1], 0.8f)
        }
        if (kind == Kind.ACTIVITY) assertNear("the 48 dp back pill sets the row", 48f, row[3], 0.6f)

        // Nothing clipped: everything sits inside the header's own box, and the header inside the host's gutter.
        assertTrue("the header is the shell's width", abs(header[2] - (hostWidthDp - 2 * gutter)) <= 0.6f)
        assertInside("row", row, header)
        for ((name, box) in listOf("badge" to badge, "title" to title, "rule" to rule)) assertInside(name, box, header)
        // "Mimir" (or "Activity") is whole at every scale.
        assertTrue("title ($titleNaturalPx px) fits its node (${title[2] * density} px)", title[2] * density + 1f >= titleNaturalPx)
        assertTrue("title is one line inside the row, was ${title[3]}", title[3] <= row[3] + 0.5f)

        val items = mutableListOf<Pair<String, FloatArray>>()
        if (kind != Kind.LATTICE) items += "leading" to boundsDp(MimirCompactHeaderLeadingTag)
        items += "badge" to badge
        items += "title" to title
        if (hasTagline) {
            val taglineBox = boundsDp(MimirCompactHeaderTaglineTag)
            val taglineLayout = textLayout(MimirCompactHeaderTaglineTag)
            println(
                "H0B-HEADER $kind tagline fontScale=$fontScale ${taglineBox[0]},${taglineBox[1]} ${taglineBox[2]}x${taglineBox[3]} lines=${taglineLayout.lineCount} " +
                    "cutW=${taglineLayout.isLineEllipsized(0)}",
            )
            assertEquals("tagline remains lowercase with no dash", tagline, taglineLayout.layoutInput.text.text)
            assertEquals("one line", 1, taglineLayout.lineCount)
            assertTrue("the tagline follows the title", taglineBox[0] >= title[0] + title[2] - 0.5f)
            assertInside("tagline", taglineBox, header)
            assertNear("the tagline stands on the title's baseline", title[1] + titleBaselinePx / density, taglineBox[1] + supportBaselinePx / density, 0.6f)
            items += "tagline" to taglineBox
            if (fontScale <= 1.3f) assertFalse("the tagline is whole at font scale $fontScale", taglineLayout.isLineEllipsized(0))
            // The stat yields first: a cut tagline never sits next to a shown stat.
            if (stat[2] > 0.5f) assertFalse("a cut tagline next to a shown stat means the stat did not yield first", taglineLayout.isLineEllipsized(0))
        } else {
            assertEquals("no tagline node without a tagline ($kind)", 0, count(MimirCompactHeaderTaglineTag))
        }
        assertEquals("the caller's own stat text", if (kind == Kind.ACTIVITY) activityStat else latticeStat, statLayout.layoutInput.text.text)
        val statShown = stat[2] > 0.5f
        if (statShown) {
            assertEquals("a shown stat is one line", 1, statLayout.lineCount)
            // Right-aligned to the title group's end: the header's edge, or the trailing slot's start less the row's 12 dp gap.
            val groupEnd = if (kind == Kind.SLOTS) boundsDp(MimirCompactHeaderTrailingTag)[0] - 12f else header[0] + header[2]
            assertNear("the stat ends at the title group's right edge", groupEnd, stat[0] + stat[2], 0.8f)
            assertInside("stat", stat, header)
            items += "stat" to stat
            if (hasTagline) assertFalse("a shown stat next to the tagline is whole", statLayout.didOverflowWidth)
        }
        if (fontScale <= 1.0f) assertTrue("at the default font scale the stat is shown ($kind)", statShown)
        if (kind == Kind.ACTIVITY || kind == Kind.SLOTS) {
            // The Activity page's stat, as it always has been: one line at every scale, and not cut at 1.3 or below.
            assertEquals("one line at every scale", 1, statLayout.lineCount)
            if (fontScale <= 1.3f && kind == Kind.ACTIVITY) assertFalse("the stat is not cut", statLayout.didOverflowWidth || statLayout.didOverflowHeight)
        }

        // The row's items never overlap each other.
        if (kind == Kind.SLOTS) items += "trailing" to boundsDp(MimirCompactHeaderTrailingTag)
        for (i in items.indices) for (j in i + 1 until items.size) {
            assertFalse("${items[i].first} must not overlap ${items[j].first}: ${items[i].second.toList()} vs ${items[j].second.toList()}", overlaps(items[i].second, items[j].second))
        }
        if (kind != Kind.LATTICE) {
            assertEquals("the leading slot is the row's first item", header[0], boundsDp(MimirCompactHeaderLeadingTag)[0], 0.6f)
            assertNear("the leading slot keeps its own width", if (kind == Kind.ACTIVITY) 48f else 56f, boundsDp("slot-leading")[2], 0.3f)
        }
        if (kind == Kind.SLOTS) {
            val trailing = boundsDp(MimirCompactHeaderTrailingTag)
            assertEquals("the trailing slot ends at the header's right edge", header[0] + header[2], trailing[0] + trailing[2], 0.6f)
            assertNear("the slot content is its own 96 dp", 96f, boundsDp("slot-trailing")[2], 0.3f)
        }
        assertEquals("there is no eyebrow node: the header is the row and the rule", 0, count("mimir-compact-header-eyebrow"))
    }

    @Test
    fun `the lattice header at font scale 1_0`() = assertHeader(1.0f, Kind.LATTICE)

    @Test
    fun `the lattice header at font scale 1_3`() = assertHeader(1.3f, Kind.LATTICE)

    @Test
    fun `the lattice header at font scale 2_0`() = assertHeader(2.0f, Kind.LATTICE)

    @Test
    fun `the Activity header with the 48 dp back pill at font scale 1_0`() = assertHeader(1.0f, Kind.ACTIVITY)

    @Test
    fun `the Activity header with the 48 dp back pill at font scale 1_3`() = assertHeader(1.3f, Kind.ACTIVITY)

    @Test
    fun `the Activity header with the 48 dp back pill at font scale 2_0`() = assertHeader(2.0f, Kind.ACTIVITY)

    @Test
    fun `leading and trailing slots at font scale 1_0`() = assertHeader(1.0f, Kind.SLOTS)

    @Test
    fun `leading and trailing slots at font scale 1_3`() = assertHeader(1.3f, Kind.SLOTS)

    @Test
    fun `leading and trailing slots at font scale 2_0`() = assertHeader(2.0f, Kind.SLOTS)

    /**
     * The yield order, forced: on a host too narrow for the title, the tagline and the stat, the stat goes first (as a zero-width node, never cut to a stub), then
     * the tagline ellipsizes, and "Mimir" is never cut. 300 dp leaves the group about 218 dp at the default scale.
     */
    private fun assertYield(hostWidth: Float, fontScale: Float, expectStat: Boolean, expectTaglineCut: Boolean) {
        mount(fontScale, Kind.LATTICE, hostWidth = hostWidth)
        val title = boundsDp(MimirCompactHeaderTitleTag)
        val tag = boundsDp(MimirCompactHeaderTaglineTag)
        val stat = boundsDp(MimirCompactHeaderStatTag)
        val tagLayout = textLayout(MimirCompactHeaderTaglineTag)
        println(
            "H0B-HEADER yield host=$hostWidth fontScale=$fontScale title=${title[2]}dp tagline=${tag[2]}dp cut=${tagLayout.isLineEllipsized(0)} stat=${stat[2]}dp",
        )
        assertTrue("\"Mimir\" is never cut: node ${title[2] * density} px vs text $titleNaturalPx px", title[2] * density + 1f >= titleNaturalPx)
        assertEquals("the stat is ${if (expectStat) "shown" else "yielded"}", expectStat, stat[2] > 0.5f)
        assertEquals("the tagline is ${if (expectTaglineCut) "cut" else "whole"}", expectTaglineCut, tagLayout.isLineEllipsized(0))
        if (expectTaglineCut) assertFalse("the stat has already yielded when the tagline is cut", stat[2] > 0.5f)
        assertFalse("the tagline must not overlap the title", overlaps(tag, title))
        assertFalse("the tagline must not overlap the stat", overlaps(tag, stat))
    }

    @Test
    fun `on a narrower host the stat yields first and the tagline stays whole`() = assertYield(hostWidth = 380f, fontScale = 1.0f, expectStat = false, expectTaglineCut = false)

    @Test
    fun `on a still narrower host the tagline ellipsizes and Mimir is never cut`() = assertYield(hostWidth = 230f, fontScale = 1.0f, expectStat = false, expectTaglineCut = true)

    @Test
    fun `on the full host every part of the lattice header is whole`() = assertYield(hostWidth = hostWidthDp, fontScale = 1.0f, expectStat = true, expectTaglineCut = false)

    @Test
    fun `a slot taller than the 32 dp row grows the row and the header by exactly the difference`() {
        mount(1.0f, Kind.SLOTS, slotHeightDp = 48)
        val header = boundsDp(MimirCompactHeaderTag)
        val row = boundsDp(MimirCompactHeaderRowTag)
        println("H0B-HEADER tall-slot header=${header[3]}dp row=${row[3]}dp")
        assertNear("a 48 dp slot makes the row 48 dp", 48f, row[3], 0.6f)
        assertNear("and the header 16 dp taller than the 41.19 dp it is", 41.19241f + 16f, header[3], 0.9f)
        val badge = boundsDp(MimirCompactHeaderBadgeTag)
        assertNear("the badge stays centred in the taller row", row[1] + row[3] / 2f, badge[1] + badge[3] / 2f, 0.6f)
    }

    private fun assertLongTitleKeepsSlots(fontScale: Float) {
        val longTitle = "Nintendo Entertainment System Famicom Disk System Super Long Platform Name"
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    val measurer = rememberTextMeasurer()
                    titleNaturalPx = measurer.measure(AnnotatedString(longTitle.uppercase()), MimirTypeScale.TitleCaps, maxLines = 1).size.width
                    density = LocalDensity.current.density
                    Column(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).padding(horizontal = gutter.dp)) {
                        MimirCompactHeader(
                            title = longTitle,
                            palette = LocalMimirPalette.current,
                            stat = "1,234 games",
                            leading = { Box(Modifier.size(48.dp).background(Color.Gray).testTag("pill")) },
                            trailing = {
                                Row {
                                    for (i in 0..2) Box(Modifier.size(48.dp).background(Color.Gray).testTag("step$i"))
                                }
                            },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
        val header = boundsDp(MimirCompactHeaderTag)
        val pill = boundsDp("pill")
        val steps = (0..2).map { boundsDp("step$it") }
        val trailing = boundsDp(MimirCompactHeaderTrailingTag)
        val badge = boundsDp(MimirCompactHeaderBadgeTag)
        val title = boundsDp(MimirCompactHeaderTitleTag)
        println(
            "H0B-HEADER long-title fontScale=$fontScale header=${header[2]}x${header[3]} pill=${pill[2]}x${pill[3]} stepper=${trailing[2]}x${trailing[3]} " +
                "title=${title[2]}dp (natural ${titleNaturalPx / density}dp)",
        )
        assertNear("the back pill keeps its full 48 dp width", 48f, pill[2], 0.3f)
        assertNear("the back pill keeps its full 48 dp height", 48f, pill[3], 0.3f)
        for ((i, step) in steps.withIndex()) {
            assertNear("stepper button $i keeps 48 dp wide", 48f, step[2], 0.3f)
            assertNear("stepper button $i keeps 48 dp tall", 48f, step[3], 0.3f)
        }
        assertNear("the stepper is 3 x 48 dp", 144f, trailing[2], 0.6f)
        assertNear("the stepper ends at the header's right edge", header[0] + header[2], trailing[0] + trailing[2], 0.6f)
        assertNear("the pill starts at the header's left edge", header[0], pill[0], 0.6f)
        assertTrue("the title is cut: node ${title[2] * density} px is narrower than its ${titleNaturalPx} px", title[2] * density + 1f < titleNaturalPx)
        assertTrue("the title ends before the stepper begins", title[0] + title[2] <= trailing[0] + 0.5f)
        assertTrue("the badge sits between the pill and the title", pill[0] + pill[2] <= badge[0] + 0.5f && badge[0] + badge[2] <= title[0] + 0.5f)
        assertFalse("the title does not overlap the pill", overlaps(title, pill))
        assertFalse("the title does not overlap the stepper", overlaps(title, trailing))
        assertNear("the row is as tall as the slots", 48f, boundsDp(MimirCompactHeaderRowTag)[3], 0.6f)
    }

    @Test
    fun `a very long title ellipsizes and every slot keeps its 48 dp target at font scale 1_0`() = assertLongTitleKeepsSlots(1.0f)

    @Test
    fun `a very long title ellipsizes and every slot keeps its 48 dp target at font scale 1_3`() = assertLongTitleKeepsSlots(1.3f)

    @Test
    fun `the header measured edge to edge across the full 538 dp panel at the default scale`() {
        mount(1.0f, Kind.SLOTS, edgeToEdge = true)
        val header = boundsDp(MimirCompactHeaderTag)
        println("H0B-HEADER edge-to-edge header=${header[2]}x${header[3]}dp")
        assertNear("the header takes the panel's full width", hostWidthDp, header[2], 0.6f)
        assertEquals("the same 41.19 dp", 41.19241f, header[3], 0.01f)
    }

    private var measureTitle: (Int) -> TextLayoutResult = { error("not mounted") }

    /** The title speaks through MimirChromeText (no text-layout action), so its lines are re-measured at the width its node took. */
    private fun titleLayout(title: String): TextLayoutResult {
        val node = compose.onNodeWithTag(MimirCompactHeaderTitleTag).fetchSemanticsNode().boundsInRoot
        return measureTitle(Math.round(node.width) + 1)
    }

    private fun mountWithBadge(fontScale: Float, title: String, titleMaxLines: Int) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    val measurer = rememberTextMeasurer()
                    measureTitle = { widthPx ->
                        measurer.measure(
                            AnnotatedString(mimirStaticChrome(title)),
                            MimirTypeScale.TitleCaps,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = titleMaxLines,
                            constraints = Constraints(maxWidth = widthPx),
                        )
                    }
                    Column(Modifier.requiredSize(hostWidthDp.dp, hostHeightDp.dp).padding(horizontal = gutter.dp)) {
                        MimirCompactHeader(
                            title = title,
                            palette = LocalMimirPalette.current,
                            titleMaxLines = titleMaxLines,
                            leading = { Box(Modifier.size(56.dp, 48.dp).background(Color.Gray).testTag("slot-leading")) },
                            badge = { Box(Modifier.size(40.dp).background(Color.Red).testTag("custom-badge")) },
                            trailing = { Box(Modifier.size(96.dp, 48.dp).background(Color.Gray).testTag("slot-trailing")) },
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000L)
        compose.waitForIdle()
    }

    private fun assertCustomBadgeAndTwoLines(fontScale: Float) {
        val long = "Nintendo Entertainment System Famicom Disk System Collection"
        mountWithBadge(fontScale, long, titleMaxLines = 2)
        assertEquals("the Mimir mark is replaced, not joined", 0, count(MimirCompactHeaderBadgeTag))
        val badge = boundsDp("custom-badge")
        val title = boundsDp(MimirCompactHeaderTitleTag)
        val pill = boundsDp("slot-leading")
        val trailing = boundsDp("slot-trailing")
        val row = boundsDp(MimirCompactHeaderRowTag)
        val layout = titleLayout(long)
        println("W2C-BADGE fontScale=$fontScale badge=${badge.toList()} title=${title.toList()} lines=${layout.lineCount} row=${row[3]}")
        assertNear("the caller's seat keeps its 40 dp", 40f, badge[2], 0.3f)
        assertNear("the caller's seat keeps its 40 dp", 40f, badge[3], 0.3f)
        assertEquals("a long title takes two lines", 2, layout.lineCount)
        assertTrue("and ellipsizes on the second", layout.isLineEllipsized(1))
        assertFalse("the title and the badge", overlaps(title, badge))
        assertFalse("the title and the leading slot", overlaps(title, pill))
        assertFalse("the title and the trailing slot", overlaps(title, trailing))
        assertTrue("the badge sits between the pill and the title", pill[0] + pill[2] <= badge[0] + 0.5f && badge[0] + badge[2] <= title[0] + 0.5f)
        assertNear("the slots keep 48 dp", 48f, pill[3], 0.3f)
        assertTrue("the row holds a 48 dp slot, a 40 dp seat and the title", row[3] >= 48f - 0.5f && title[3] <= row[3] + 0.5f)
    }

    @Test
    fun `a caller's badge replaces the Mimir mark and a title may take two lines at font scale 1_0`() = assertCustomBadgeAndTwoLines(1.0f)

    @Test
    fun `a caller's badge replaces the Mimir mark and a title may take two lines at font scale 1_3`() = assertCustomBadgeAndTwoLines(1.3f)

    @Test
    fun `the default badge is still the Mimir mark and the title is still one line`() {
        mount(1.0f, Kind.LATTICE)
        assertEquals(1, count(MimirCompactHeaderBadgeTag))
        val title = boundsDp(MimirCompactHeaderTitleTag)
        val badge = boundsDp(MimirCompactHeaderBadgeTag)
        assertNear("the badge is the 32 dp mark", 32f, badge[2], 0.3f)
        assertTrue("one line by default: ${title[3]} dp tall in a ${boundsDp(MimirCompactHeaderRowTag)[3]} dp row", title[3] <= boundsDp(MimirCompactHeaderRowTag)[3] + 0.5f)
    }
}
