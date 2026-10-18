package com.gruning.mimir.rewrite.core.companionnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class CompanionOverlayThemeTest {
    @Test
    fun tomeGeometryIsStableCompactAndTouchSafe() {
        assertEquals(244, CompanionMenuWidthDp)
        assertTrue(CompanionMenuOuterCornerDp <= 8)
        // The spine rail and the gutter that cleared it are both RETIRED . What is
        // pinned now is that the gutter stayed small enough to be padding rather than reopening as a
        // seat for something else: 12 dp is inside §4's 16 dp gutter, and a row's own left margin adds
        // to it. A gutter that grows back is the ornament growing back.
        assertTrue(CompanionMenuContentStartDp in 8..16)
        // §4's floor is 48. This said 44 and only survived because the ROW is what gets touched and
        // happens to be 54 -- a minimum rescued by a neighbour is one edit from being wrong in fact.
        assertTrue(CompanionMenuMinimumTouchTargetDp >= 48)
        // The SLOT is what gets touched now, not a list row -- coin, gap and label band together.
        assertTrue(CompanionMenuSlotHeightDp >= CompanionMenuMinimumTouchTargetDp)
        // And the coin alone must still clear the floor, because it is what a player aims at.
        assertTrue(CompanionMenuCoinSizeDp >= CompanionMenuMinimumTouchTargetDp)

        // **THE GRID'S SHAPE IS PINNED AS A FACT, not recomputed from the same constants that build
        // it.** The version of this test that shipped in August recomputed `title + rowHeight * rows`
        // and compared it to a height derived the same way -- so it agreed with the code whatever the
        // code said, and stayed green while the Thor clipped `Utility` through the middle with its last
        // two rows unreachable. A test that re-derives its own premise is not weak, it is a test of
        // something else.
        //
        // These numbers come from the two menus that actually exist: five commands lay out 3+2 and four
        // lay out 2+2. Four across 244 dp would shrink the coin below the touch floor, and 3+1 leaves a
        // lone cell under a full row that reads as a mistake rather than a command.
        assertEquals(2, companionOverlayMenuGridRows(5))
        assertEquals(3, companionOverlayMenuGridColumns(5))
        assertEquals(2, companionOverlayMenuGridRows(4))
        assertEquals(2, companionOverlayMenuGridColumns(4))
        // No line may be wider than the grid it belongs to, at any count either menu could grow to.
        (1..9).forEach { count ->
            val columns = companionOverlayMenuGridColumns(count)
            assertTrue("$count items would need $columns across", columns <= 3)
            assertTrue(
                "$count items must all get a cell",
                companionOverlayMenuGridRows(count) * columns >= count,
            )
        }

        CompanionOverlayRole.entries.forEach { role ->
            val items = companionOverlayMenuRows(role).size
            val gridRows = companionOverlayMenuGridRows(items)
            val needed = CompanionMenuVerticalInsetDp * 2 +
                CompanionMenuTitleHeightDp +
                CompanionMenuSlotHeightDp * gridRows +
                CompanionMenuCoinRowGapDp * (gridRows - 1)
            assertTrue(
                "$role has $items commands over $gridRows grid rows needing $needed dp but the " +
                    "window is ${companionOverlayMenuHeightDp(items)} dp",
                companionOverlayMenuHeightDp(items) >= needed,
            )
            // And it must still fit the panel it is drawn on. The companion display is 468 dp tall.
            assertTrue(
                "$role's menu must fit a 468 dp panel",
                companionOverlayMenuHeightDp(items) <= 468,
            )
            // MEASURED on the Thor, display 4: the grid is shorter than the list it replaced (324 dp).
            assertTrue(
                "$role's menu must not have grown back to the list's height",
                companionOverlayMenuHeightDp(items) < 324,
            )
        }
    }

    @Test
    fun defaultMaterialHasDeterministicReadableBookRoles() {
        val theme = CompanionOverlayTheme.Default

        assertEquals(0xff16191d.toInt(), theme.coverArgb)
        assertEquals(0xfff1e7ce.toInt(), theme.pageArgb)
        assertEquals(0xff16737d.toInt(), theme.accentArgb)
        assertTrue(theme.allRoles().all { it ushr 24 == 0xff })
        assertTrue(contrastRatio(theme.inkArgb, theme.pageArgb) >= 7.0)
        assertTrue(contrastRatio(theme.mutedInkArgb, theme.pageArgb) >= 4.5)
        assertTrue(contrastRatio(theme.toolingArgb, theme.coverArgb) >= 4.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun translucentThemeRolesAreRejected() {
        CompanionOverlayTheme.Default.copy(accentArgb = 0x8016737d.toInt())
    }

    @Test
    fun changedThemeRebuildsTheSameOpenMenuAtTheSameCoordinates() {
        val current = CompanionOverlayTheme.Default
        val next = current.copy(
            coverArgb = 0xff202b24.toInt(),
            accentArgb = 0xffb44f4a.toInt(),
        )

        val plan = planCompanionOverlayThemeChange(
            current = current,
            next = next,
            attached = true,
            menuOpen = true,
            position = 611 to 37,
        )

        assertTrue(plan.changed)
        assertTrue(plan.rebuildAttachedView)
        assertEquals(CompanionOverlayChrome.MENU, plan.chrome)
        assertEquals(611 to 37, plan.position)
    }

    @Test
    fun unchangedOrDetachedMaterialNeverCreatesAnotherWindow() {
        val current = CompanionOverlayTheme.Default
        val unchanged = planCompanionOverlayThemeChange(
            current = current,
            next = current,
            attached = true,
            menuOpen = false,
            position = 12 to 18,
        )
        assertFalse(unchanged.changed)
        assertFalse(unchanged.rebuildAttachedView)
        assertEquals(CompanionOverlayChrome.BUTTON, unchanged.chrome)

        val detached = planCompanionOverlayThemeChange(
            current = current,
            next = current.copy(accentArgb = 0xffa2463d.toInt()),
            attached = false,
            menuOpen = true,
            position = null,
        )
        assertTrue(detached.changed)
        assertFalse(detached.rebuildAttachedView)
    }

    /**
     * Concept A: the player's mark rides in the theme, so the controller's one rebuild-on-change plan is the only path
     * that repaints the live button. A new mark rebuilds an attached button, the same content-named file does not
     * re-add the window, and the rune is what every theme carries until the app says otherwise.
     */
    @Test
    fun aMarkChangeAloneRebuildsTheAttachedButtonAndAnEqualMarkDoesNot() {
        val current = CompanionOverlayTheme.Default
        assertEquals(CompanionButtonMark.Rune, current.mark)

        val star = CompanionButtonMark.Image(id = "a".repeat(32), path = "/data/appearance/marks/${"a".repeat(32)}.png")
        val bolt = CompanionButtonMark.Image(id = "b".repeat(32), path = "/data/appearance/marks/${"b".repeat(32)}.png")

        val committed = planCompanionOverlayThemeChange(
            current = current,
            next = current.copy(mark = star),
            attached = true,
            menuOpen = false,
            position = 12 to 18,
        )
        assertTrue("a mark change alone is a change", committed.changed)
        assertTrue("it repaints the attached button", committed.rebuildAttachedView)
        assertEquals(CompanionOverlayChrome.BUTTON, committed.chrome)

        val swapped = planCompanionOverlayThemeChange(current.copy(mark = star), current.copy(mark = bolt), true, false, null)
        assertTrue(swapped.rebuildAttachedView)
        val reset = planCompanionOverlayThemeChange(current.copy(mark = star), current, true, false, null)
        assertTrue("choosing the rune again rebuilds too", reset.rebuildAttachedView)

        val same = planCompanionOverlayThemeChange(
            current = current.copy(mark = star),
            next = current.copy(mark = star.copy()),
            attached = true,
            menuOpen = false,
            position = null,
        )
        assertFalse("the same mark never re-adds the window", same.changed)
        assertFalse(same.rebuildAttachedView)
    }

    @Test
    fun alphaReplacementKeepsRgbAndClamps() {
        val color = 0xff16737d.toInt()
        assertEquals(0x0016737d, argbWithAlpha(color, 0))
        assertEquals(0x2a16737d, argbWithAlpha(color, 42))
        assertEquals(0xff16737d.toInt(), argbWithAlpha(color, 999))
    }

    private fun CompanionOverlayTheme.allRoles(): List<Int> = listOf(
        coverArgb,
        coverEdgeArgb,
        pageArgb,
        pageEdgeArgb,
        inkArgb,
        mutedInkArgb,
        accentArgb,
        toolingArgb,
        metalLightArgb,
        metalHotArgb,
        metalMidArgb,
        metalDeepArgb,
    )

    private fun contrastRatio(first: Int, second: Int): Double {
        val firstLuminance = luminance(first)
        val secondLuminance = luminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        fun linear(channel: Int): Double {
            val value = channel / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        val red = linear(argb ushr 16 and 0xff)
        val green = linear(argb ushr 8 and 0xff)
        val blue = linear(argb and 0xff)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }
}
