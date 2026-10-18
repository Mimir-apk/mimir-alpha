package com.gruning.mimir.rewrite

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.TomeId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * the Tome collection menu is centred on screen, on every form factor, and never clips at a large
 * font. The REAL [HomeTomeLibraryOverlay] is composed on a paused clock inside a host of the stated size, and its glass
 * panel, its header and footer strips and its Home-book-plus-grid composition are measured from the semantics tree.
 *
 * Sizes (dp, at 369 dpi like the Thor tests): Thor Main 1920 x 1080 px = 832.5 x 468.3 dp, and that panel stood upright
 * (468.3 x 832.5), a 640 x 360 compact handheld and the 700 x 350 floor. Font scales 1.0, 1.3 and 2.0. Pixel values are the
 * JVM's; the pixels on a handheld are device-owed. Every measurement is printed as `TOME-CENTER`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w2400dp-h2400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeTomeLibraryCenteringTest {

    @get:Rule
    val compose = createComposeRule()

    private val density369 = 369f / 160f

    private class Form(val name: String, val widthDp: Float, val heightDp: Float)

    private val thorMain = Form("thor-main", 1920f / 369f * 160f, 1080f / 369f * 160f)
    private val thorMainUpright = Form("thor-main-upright", 1080f / 369f * 160f, 1920f / 369f * 160f)
    private val compact = Form("compact-640x360", 640f, 360f)
    private val floor = Form("floor-700x350", 700f, 350f)

    /** Home Shelf, six custom Tomes (a second grid row to scroll to) and the create cell. */
    private fun entries(): List<HomeTomeLibraryEntry> {
        val customs = (1..6).map { TomeId.Custom(UUID.fromString("00000000-0000-0000-0000-00000000000$it")) }
        val document = HomeTomeDocument(
            pinOrder = customs.take(1),
            customTomes = customs.mapIndexed { i, id ->
                CustomHomeTome(id, "Tome ${i + 1}", emptyList(), createdAtMillis = 100L + i)
            },
        )
        val available: Map<TomeId, HomeShelfTome> =
            customs.mapIndexed { i, id -> id to HomeShelfTome(id, "Tome ${i + 1}", emptyList(), 0) }.toMap()
        return homeTomeLibraryEntries(
            document,
            available,
            homeOverview = HomeShelfTome(TomeId.HomeOverview, "HOME SHELF", emptyList(), 0),
        )
    }

    private fun mount(form: Form, fontScale: Float, tomes: List<HomeTomeLibraryEntry> = entries()) {
        val authority = DefaultFocusAuthority()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density369, fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredSize(form.widthDp.dp, form.heightDp.dp).testTag(HostTag)) {
                        HomeTomeLibraryOverlay(
                            state = HomeTomeLibraryState(entries = tomes, invokingEntry = null),
                            authority = authority,
                            active = true,
                            reducedMotion = true,
                            onFirstVisibleIndex = {},
                            onFocusSettled = {},
                            onActivate = {},
                            onDraftChange = {},
                            onDraftDone = {},
                            onDeleteAction = {},
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun check(form: Form, fontScale: Float) {
        mount(form, fontScale)
        val host = bounds(HostTag)
        val panel = bounds(HomeTomeLibraryPanelTestTag)
        val header = bounds(HomeTomeLibraryHeaderTestTag)
        val footer = bounds(HomeTomeLibraryFooterTestTag)
        val composition = bounds(HomeTomeLibraryCompositionTestTag)
        fun toDp(px: Float) = px / density369
        println(
            "TOME-CENTER ${form.name} fontScale=$fontScale host=${toDp(host.width)}x${toDp(host.height)}dp " +
                "panel=${toDp(panel.width)}x${toDp(panel.height)}dp margins L=${toDp(panel.left - host.left)} " +
                "R=${toDp(host.right - panel.right)} T=${toDp(panel.top - host.top)} B=${toDp(host.bottom - panel.bottom)} " +
                "header=${toDp(header.height)}dp footer=${toDp(footer.height)}dp composition=${toDp(composition.width)}x${toDp(composition.height)}dp",
        )

        // The panel is centred on both axes: equal margins either side and above and below.
        assertEquals("${form.name}: left and right margins", panel.left - host.left, host.right - panel.right, OnePx)
        assertEquals("${form.name}: top and bottom margins", panel.top - host.top, host.bottom - panel.bottom, OnePx)
        // ...and stays on the screen, inside the 94% the hero art is left around it.
        assertTrue("${form.name}: panel is inside the screen", panel.left >= host.left - OnePx && panel.right <= host.right + OnePx)
        assertTrue("${form.name}: panel is inside the screen", panel.top >= host.top - OnePx && panel.bottom <= host.bottom + OnePx)
        assertEquals("${form.name}: panel height is the 94%", host.height * HomeTomeLibraryPanelViewportFraction, panel.height, OnePx)
        assertEquals(
            "${form.name}: panel width is the 94% (or the 960 dp cap)",
            minOf(host.width * HomeTomeLibraryPanelViewportFraction, HomeTomeLibraryPanelMaxWidthDp * density369),
            panel.width,
            OnePx,
        )

        // The composition (Home book, beam, collection) is centred on the screen horizontally, and vertically in the
        // room the header and the footer leave.
        assertEquals("${form.name}: composition centred across", (composition.left + composition.right) / 2f, (host.left + host.right) / 2f, OnePx)
        val bandCentre = (header.bottom + footer.top) / 2f
        assertEquals("${form.name}: composition centred in its band", (composition.top + composition.bottom) / 2f, bandCentre, OnePx + 1f)

        // Header and footer sit inside the panel, header above the composition, footer below.
        assertTrue("${form.name}: header in panel", header.top >= panel.top - OnePx && header.bottom <= composition.top + OnePx)
        assertTrue("${form.name}: footer in panel", footer.bottom <= panel.bottom + OnePx && footer.top >= composition.bottom - OnePx)

        // Nothing is clipped by its own strip: every text's laid-out height is its natural height.
        val clipped = textNodes().filter { node ->
            val result = layoutOf(node) ?: return@filter false
            result.size.height + 0.5f < result.multiParagraph.height
        }.map { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { t -> t.text } }
        assertTrue("${form.name} x$fontScale: texts clipped vertically: $clipped", clipped.isEmpty())

        // The header and footer strips are one line each (maxLines = 1), and a one-line text that does not fit its strip is
        // ellipsized or cut at the side, which the vertical check above cannot see. Read from the UNMERGED tree, because the
        // chrome texts (the footer's key legend) clear their text semantics in the merged one. The 700 x 350 floor, the
        // compact handheld and the upright Thor (468 dp wide, where the eyebrow was ellipsized at 2.0) are held at a raised scale;
        // every form is printed (no ellipsis at 2.0).
        val sideways = overflowingTexts()
        println("TOME-OVERFLOW ${form.name} fontScale=$fontScale: $sideways")
        if (fontScale > 1f && (form === floor || form === compact || form === thorMainUpright)) {
            assertTrue("${form.name} x$fontScale: texts ellipsized or cut at the side: $sideways", sideways.isEmpty())
        }
    }

    /**
     * Every header or footer text that is ellipsized or as wide as its strip, from the unmerged tree. A plain `Text` reports
     * its layout, so an ellipsized line is read off it. The chrome texts (the footer's key legend) clear their text semantics
     * and report no layout, so a chrome text is judged by its box: one laid out at the strip's full width, or past it, was cut.
     * (`didOverflowWidth` and `didOverflowHeight` are not used: they read true for every text here, at 1.0 too.)
     */
    private fun overflowingTexts(): List<String> {
        val out = ArrayList<String>()
        fun walk(node: SemanticsNode, strip: Rect) {
            val text = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
            val spoken = node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
            val result = layoutOf(node)
            if (text != null && result != null) {
                if ((0 until result.lineCount).any { result.isLineEllipsized(it) }) out += text.joinToString { it.text }
            } else if (spoken != null && text == null) {
                val b = node.boundsInRoot
                if (b.width >= strip.width - 1f || b.right > strip.right + 1f || b.left < strip.left - 1f) {
                    out += "chrome " + spoken.joinToString { it }
                }
            }
            node.children.forEach { walk(it, strip) }
        }
        for (tag in listOf(HomeTomeLibraryHeaderTestTag, HomeTomeLibraryFooterTestTag)) {
            val strip = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
            walk(strip, strip.boundsInRoot)
        }
        return out
    }

    private fun textNodes(): List<SemanticsNode> {
        val out = ArrayList<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            if (node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text) != null) out += node
            node.children.forEach(::walk)
        }
        // The merged tree: what a user and a screen reader get. The header's invisible width sizer is cleared from it.
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::walk)
        return out
    }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult? {
        val results = ArrayList<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()
    }

    @Test fun `thor main is centred at font 1`() = check(thorMain, 1f)
    @Test fun `thor main is centred at font 1_3`() = check(thorMain, 1.3f)
    @Test fun `thor main is centred at font 2`() = check(thorMain, 2f)
    @Test fun `thor main upright is centred at font 1`() = check(thorMainUpright, 1f)
    @Test fun `thor main upright is centred at font 1_3`() = check(thorMainUpright, 1.3f)
    @Test fun `thor main upright is centred at font 2`() = check(thorMainUpright, 2f)
    @Test fun `compact handheld is centred at font 1`() = check(compact, 1f)
    @Test fun `compact handheld is centred at font 1_3`() = check(compact, 1.3f)
    @Test fun `compact handheld is centred at font 2`() = check(compact, 2f)
    @Test fun `the 700 by 350 floor is centred at font 1`() = check(floor, 1f)
    @Test fun `the 700 by 350 floor is centred at font 1_3`() = check(floor, 1.3f)
    @Test fun `the 700 by 350 floor is centred at font 2`() = check(floor, 2f)

    @Test
    fun `the strips grow with the font and never shrink below the designed height`() {
        assertEquals(1f, homeTomeLibraryChromeScale(0.85f), 0f)
        assertEquals(1f, homeTomeLibraryChromeScale(1f), 0f)
        assertEquals(1.3f, homeTomeLibraryChromeScale(1.3f), 0f)
        assertEquals(2f, homeTomeLibraryChromeScale(2f), 0f)
        assertEquals(2f, homeTomeLibraryChromeScale(3f), 0f)
        assertEquals(1f, homeTomeLibraryChromeScale(Float.NaN), 0f)
    }


    /** The DPAD order is the entry list, read by key from the slot list, not from where anything is laid out. */
    @Test
    fun `the focus slots and the pad's moves are the entry order whatever the layout`() {
        val list = entries()
        val slots = homeTomeLibraryFocusSlots(list)
        assertEquals(list.map(::homeTomeLibraryFocusKey), slots.map { it.key })
        var state = HomeTomeLibraryState(entries = list, invokingEntry = null, focusedIndex = 0)
        state = navigateHomeTomeLibrary(state, ControllerAction.NAV_RIGHT)
        assertEquals("RIGHT from Home enters the collection's first cell", 1, state.focusedIndex)
        state = navigateHomeTomeLibrary(state, ControllerAction.NAV_RIGHT)
        assertEquals("RIGHT moves a column", 2, state.focusedIndex)
        state = navigateHomeTomeLibrary(state, ControllerAction.NAV_DOWN)
        assertEquals("DOWN moves a row of three", 5, state.focusedIndex)
        state = navigateHomeTomeLibrary(state, ControllerAction.NAV_LEFT)
        assertEquals("LEFT moves a column", 4, state.focusedIndex)
    }

    private companion object {
        const val HostTag = "tome_center_host"
        const val OnePx = 1.5f
    }
}
