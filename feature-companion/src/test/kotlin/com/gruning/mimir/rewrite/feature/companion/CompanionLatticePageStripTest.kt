package com.gruning.mimir.rewrite.feature.companion

import android.app.Application
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Two small fixes to the Bottom lattice's page strip, on the REAL [CompanionSurface] at the Thor Bottom's size (1240 x 1080 px at 369 dpi, 537.7 x 468.3 dp):
 *
 *  - ledger D2: the page diamonds are capped at [CompanionPageRunesMaxPages]; past it the strip says "n / m" in words. At any page count it stands clear of the dock's 96 dp
 *    handle and inside the scene, at font 1.0 and 2.0.
 *  - ledger D1: the swipe hint's row is the same height on every page, so the grid's cell height does not change as the player pages before the first swipe, at font 1.0, 1.3,
 *    1.5 and 2.0.
 *
 * Geometry only, from the semantics tree (the real surface cannot be rasterised under Robolectric; see [CompanionLatticeFootprintRenderTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w538dp-h468dp-369dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionLatticePageStripTest {

    @get:Rule
    val compose = createComposeRule()

    private val widthDp = 1240f / Density369
    private val heightDp = 1080f / Density369
    private var density = 0f

    /** [pages] pages of nine: the last holds one tile. */
    private fun payloadOf(pages: Int): CompanionPayload {
        val tiles = (1..(9 * (pages - 1) + 1)).map { CompanionHomeTile("p$it", CompanionHomeTileKind.PLATFORM, "Platform number $it") }
        return CompanionPayload(
            mode = CompanionMode.HOME,
            statsLabel = "${tiles.size} platforms",
            homeLanes = listOf(CompanionHomeLane(id = "emulation", title = "Emulation", hint = "", tiles = tiles)),
        )
    }

    private fun mount(pages: Int, fontScale: Float, hintShown: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(Density369, fontScale)) {
                MimirTheme {
                    density = LocalDensity.current.density
                    CompanionSurface(
                        payload = payloadOf(pages),
                        cursorId = "p1",
                        onWorkspaceDrag = { _: Float -> 0f },
                        showActivitySwipeHint = hintShown,
                        modifier = Modifier.requiredSize(widthDp.dp, heightDp.dp),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000L)
        compose.waitForIdle()
    }

    /** A node's own position and size in dp, not clipped to its ancestors. */
    private fun rawBoundsDp(tag: String): FloatArray {
        val n = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return floatArrayOf(n.positionInRoot.x / density, n.positionInRoot.y / density, n.size.width / density, n.size.height / density)
    }

    private fun description(): String =
        compose.onNodeWithTag(CompanionPageRunesTag, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()

    // ------------------------------------------------------------------------------------------------------------------------------
    // D2: the page strip never overlaps the dock handle and never runs off the scene.
    // ------------------------------------------------------------------------------------------------------------------------------

    private fun assertStrip(pages: Int, fontScale: Float) {
        mount(pages, fontScale, hintShown = false)
        val tag = "pages=$pages font=$fontScale"
        assertEquals("$tag: one node says where the page is", "Page 1 of $pages", description())
        val runes = rawBoundsDp(CompanionPageRunesTag)
        val left = runes[0]
        val right = runes[0] + runes[2]
        println("D2-STRIP $tag node=${runes[2]}x${runes[3]}dp left=$left right=$right scene=$widthDp handleRight=${widthDp / 2f + 48f}")
        // The strip is the diamonds up to the cap, the words beyond it.
        if (pages <= CompanionPageRunesMaxPages) {
            // 7 dp and 8 dp are whole pixels at this density (16 and 18 px of 2.306 px/dp), so each of the n diamonds and n-1 gaps can be up to 0.3 dp narrower than its dp value.
            assertEquals("$tag: the diamonds are 7 dp squares 8 dp apart (15 dp each, less the last gap)", 15f * pages - 8f, runes[2], 0.3f * pages)
            assertTrue("$tag: the diamonds row is measured whole: ${runes[3]}", runes[3] >= 6.5f)
        } else {
            assertTrue("$tag: past the cap the words stand in for the diamonds: ${runes[2]} dp wide is no row of $pages diamonds", runes[2] < 15f * pages - 8f)
            assertTrue("$tag: the words fit the 12 dp scrim at any font scale: ${runes[3]}", runes[3] <= CompanionPageRunesScrimHeightDp + 0.6f)
            assertTrue("$tag: the words are a real line, not nothing: ${runes[2]} x ${runes[3]}", runes[2] > 10f && runes[3] > 6f)
        }
        // Clear of the dock handle (96 dp, centred), with the scrim's padding to spare and the 8 dp the footprint test keeps.
        assertTrue("$tag: clear of the dock handle: scrim left ${left - CompanionPageRunesScrimPadDp} vs handle right ${widthDp / 2f + 48f}", left - CompanionPageRunesScrimPadDp >= widthDp / 2f + 48f + 8f)
        // Inside the scene at the trailing end: never clipped at the left edge, never past the right, the scrim ending at its end inset.
        assertTrue("$tag: not clipped at the scene's left edge: $left", left >= 0f)
        assertTrue("$tag: inside the scene's right edge: $right of $widthDp", right + CompanionPageRunesScrimPadDp <= widthDp - CompanionPageRunesEndInsetDp + 0.8f)
    }

    @Test
    fun `the diamonds up to the cap and the words past it clear the dock handle at font 1_0`() {
        assertStrip(CompanionPageRunesMaxPages, 1.0f)
    }

    @Test
    fun `eleven pages are words and clear the dock handle at font 1_0`() {
        assertStrip(CompanionPageRunesMaxPages + 1, 1.0f)
    }

    @Test
    fun `twelve pages, which overlapped the handle as diamonds, are words that clear it at font 2_0`() {
        assertStrip(12, 2.0f)
    }

    @Test
    fun `a very large page count is words inside the scene at font 1_0 and 2_0`() {
        assertStrip(40, 1.0f)
    }

    @Test
    fun `a very large page count is words inside the scene at font 2_0`() {
        assertStrip(40, 2.0f)
    }

    @Test
    fun `the cap is the last count whose diamonds clear the handle on the Bottom`() {
        // The arithmetic the cap rests on: n diamonds are 15n - 8 dp, the scrim adds its padding either side and stands [CompanionPageRunesEndInsetDp] from the scene's right edge.
        fun scrimLeft(n: Int) = widthDp - CompanionPageRunesEndInsetDp - (15f * n - 8f) - 2f * CompanionPageRunesScrimPadDp
        val handleRight = widthDp / 2f + 48f
        assertTrue("the cap clears the handle with the 8 dp the footprint test keeps: ${scrimLeft(CompanionPageRunesMaxPages)}", scrimLeft(CompanionPageRunesMaxPages) >= handleRight + 8f)
        assertTrue("one more diamond would still clear it (11 is the last that does): ${scrimLeft(11)}", scrimLeft(11) >= handleRight)
        assertTrue("twelve overlap the handle: ${scrimLeft(12)} vs $handleRight", scrimLeft(12) < handleRight)
    }

    @Test
    fun `the words say the page counted from one`() {
        assertEquals("1 / 14", companionPageCountWords(0, 14))
        assertEquals("14 / 14", companionPageCountWords(13, 14))
        assertEquals("a page past the end is held to the last", "14 / 14", companionPageCountWords(99, 14))
        assertEquals("a page before the start is held to the first", "1 / 14", companionPageCountWords(-3, 14))
        assertEquals("no pages is one", "1 / 1", companionPageCountWords(0, 0))
    }

    // ------------------------------------------------------------------------------------------------------------------------------
    // D1: the hint's row, and so the cell, is the same height on every page.
    // ------------------------------------------------------------------------------------------------------------------------------

    private fun assertFooterStableAcrossPages(fontScale: Float) {
        mount(pages = 3, fontScale = fontScale, hintShown = true)
        val tag = "font=$fontScale"
        val hint = "Swipe right for Activity →"
        assertEquals("$tag: page 1", "Page 1 of 3", description())
        val footerFirst = rawBoundsDp(CompanionFooterTag)[3]
        val cellFirst = rawBoundsDp(companionSlotTag(0))[3]
        assertEquals(
            "$tag: the hint is on the first page",
            1,
            compose.onAllNodes(hasText(hint) or hasContentDescription(hint), useUnmergedTree = true).fetchSemanticsNodes().size,
        )

        compose.onNodeWithTag(CompanionDragRootTag, useUnmergedTree = true).performTouchInput {
            swipeWithVelocity(Offset(width * 0.9f, centerY), Offset(width * 0.05f, centerY), endVelocity = 4_000f, durationMillis = 120)
        }
        compose.mainClock.advanceTimeBy(600L)
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals("$tag: paged", "Page 2 of 3", description())
        val footerSecond = rawBoundsDp(CompanionFooterTag)[3]
        val cellSecond = rawBoundsDp(companionSlotTag(9))[3]
        println("D1-FOOTER $tag footer ${footerFirst}dp -> ${footerSecond}dp, cell ${cellFirst}dp -> ${cellSecond}dp")
        assertEquals("$tag: the hint's row keeps its height when the page turns", footerFirst, footerSecond, 0.01f)
        assertEquals("$tag: so the grid's cell does not change height when the page turns", cellFirst, cellSecond, 0.05f)
        assertEquals(
            "$tag: the hint is drawn on the first page only; a turned page says nothing to a screen reader",
            0,
            compose.onAllNodes(hasText(hint) or hasContentDescription(hint), useUnmergedTree = true).fetchSemanticsNodes().size,
        )
        assertTrue("$tag: the row is at least its 13 dp minimum: $footerSecond", footerSecond >= 13f - 0.1f)
    }

    @Test
    fun `the footer and the cell keep their height as the page turns at font 1_0`() = assertFooterStableAcrossPages(1.0f)

    @Test
    fun `the footer and the cell keep their height as the page turns at font 1_3`() = assertFooterStableAcrossPages(1.3f)

    @Test
    fun `the footer and the cell keep their height as the page turns at font 1_5`() = assertFooterStableAcrossPages(1.5f)

    @Test
    fun `the footer and the cell keep their height as the page turns at font 2_0`() = assertFooterStableAcrossPages(2.0f)

    private companion object {
        /** The Thor Bottom's density: 369 dpi / 160. */
        const val Density369 = 369f / 160f
    }
}
