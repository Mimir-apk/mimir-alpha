package com.gruning.mimir.rewrite.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The appearance pickers' fixed bands on the certified handheld viewports, as the shell reads them: the paddings and gaps, the tile (its image box, the band under the art that holds the
 * state word, its two-line name), the Dim and Blur rows (side by side on a wide field, stacked on a narrow one), the note and the footer. The header is measured by the shell from its own
 * lines and the stage is the `weight(1f)` that takes what every other band leaves, so neither has a height here to pin; [the shell reads every field of the geometry] says the geometry holds
 * nothing the shell does not read.
 */
class AppearancePickerGeometryTest {

    private fun solve(width: Float, fontScale: Float = 1f, rows: Int = 2, artFillsTile: Boolean = false) =
        solveAppearancePickerGeometry(width, fontScale, rows, artFillsTile = artFillsTile)

    @Test
    fun `the fixed bands on the Nova are what the shell lays out`() {
        val g = solve(640f)
        assertEquals(16f, g.horizontalPaddingDp, 0f)
        assertEquals(10f, g.verticalPaddingDp, 0f)
        assertEquals(8f, g.sectionGapDp, 0f)
        assertEquals("a tile is its image box, its name's two lines and the 2 dp between", g.tileImageHeightDp + g.tileLabelHeightDp + 2f, g.tileHeightDp, 0.01f)
        assertEquals("the wide tile's image", 64f, g.tileImageHeightDp, 0f)
        assertEquals(48f, g.rowsHeightDp, 0f)
        assertEquals(12f + 2f, g.noteHeightDp, 0f)
        assertEquals(14f + 2f, g.footerHeightDp, 0f)
        assertTrue(g.rowsSideBySide)
        assertFalse(g.compact)
    }

    @Test
    fun `the rows stack on the RG Rotate's square and the tiles shrink`() {
        val g = solve(360f)
        assertFalse(g.rowsSideBySide)
        assertTrue(g.compact)
        assertEquals(48f + 4f + 48f, g.rowsHeightDp, 0.01f)
        assertTrue(g.tileWidthDp >= 48f)
        assertTrue("the compact tile's image is no shorter than the 48 dp it was", g.tileImageHeightDp >= 48f)
    }

    @Test
    fun `a raised font scale grows the type bands and leaves the tile's art alone`() {
        for (width in listOf(640f, 360f)) {
            val normal = solve(width)
            val large = solve(width, fontScale = 1.3f)
            assertTrue(large.footerHeightDp > normal.footerHeightDp)
            assertTrue(large.noteHeightDp > normal.noteHeightDp)
            assertTrue(large.tileLabelHeightDp > normal.tileLabelHeightDp)
            assertTrue("the state word's band grows with its type", large.badgeBandDp > normal.badgeBandDp)
        }
        // Main's tile image is the 64 dp it was at both scales; the compact tile keeps the same 40 dp of art above the word's band at both.
        assertEquals(solve(640f).tileImageHeightDp, solve(640f, fontScale = 1.3f).tileImageHeightDp, 0f)
        assertEquals(
            solve(360f).tileImageHeightDp - solve(360f).badgeBandDp,
            solve(360f, fontScale = 1.3f).tileImageHeightDp - solve(360f, fontScale = 1.3f).badgeBandDp,
            0.01f,
        )
    }

    @Test
    fun `a picker without rows has no rows band`() {
        assertEquals(0f, solve(640f, rows = 0).rowsHeightDp, 0f)
        assertEquals(0f, solve(360f, rows = 1).rowsHeightDp - 48f, 0f)
        assertFalse("one row is never side by side", solve(640f, rows = 1).rowsSideBySide)
    }

    @Test
    fun `a compact screen shares the row among the tiles it has, and a wide one keeps the eight way share`() {
        val five = solveAppearancePickerGeometry(360f, 1f, 0, tileCount = 5)
        val eight = solveAppearancePickerGeometry(360f, 1f, 0, tileCount = 8)
        assertEquals("five tiles share 328 dp less four gaps", (328f - 8f * 4f) / 5f, five.tileWidthDp, 0.01f)
        assertTrue("room for a name and a badge the eight-way share did not leave", five.tileWidthDp > 56f)
        assertEquals("eight tiles keep the 48 dp floor", 48f, eight.tileWidthDp, 0f)
        assertEquals("never more than a 16:10 image", 48f * 1.6f, solveAppearancePickerGeometry(360f, 1f, 0, tileCount = 1).tileWidthDp, 0.01f)

        val wideFive = solveAppearancePickerGeometry(832f, 1f, 0, tileCount = 5)
        val wideEight = solveAppearancePickerGeometry(832f, 1f, 0, tileCount = 8)
        assertEquals("a wide screen's tiles are what they were, however many there are", wideEight.tileWidthDp, wideFive.tileWidthDp, 0f)
    }

    @Test
    fun `a tile's name has two lines at the user's font scale`() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            val g = solve(360f, fontScale = scale)
            assertEquals(
                "two lines of the 12 sp label, the face's own leading and the 2 dp above them at $scale",
                AppearancePickerLabelLines * 12f * scale + 3f * scale + 2f,
                g.tileLabelHeightDp,
                0.01f,
            )
        }
        assertEquals(2, AppearancePickerLabelLines)
    }

    @Test
    fun `a mark's state word has a band under the art and never stands on it`() {
        // The coin is 36 dp: the art box keeps 40 of its own above the band, at every font scale, on the compact square and on Main alike.
        for (scale in listOf(1f, 1.3f, 2f)) {
            for (width in listOf(360f, 832f)) {
                val g = solve(width, fontScale = scale)
                assertEquals("the band is the word's one line and 2 dp of air at $scale", 11f * scale + 2f, g.badgeBandDp, 0.01f)
                assertTrue("40 dp of art over the band at $scale on a $width dp screen", g.tileImageHeightDp - g.badgeBandDp >= AppearancePickerArtMinDp - 0.01f)
            }
        }
        // Main's tile is already tall enough for both, so it does not grow with the band; the compact one grows only as far as it must.
        assertEquals(64f, solve(832f, fontScale = 1.3f).tileImageHeightDp, 0f)
        assertEquals(AppearancePickerArtMinDp + appearancePickerBadgeBandDp(1.3f), solve(360f, fontScale = 1.3f).tileImageHeightDp, 0.01f)
        assertEquals(AppearancePickerArtMinDp + appearancePickerBadgeBandDp(1f), solve(360f).tileImageHeightDp, 0.01f)
    }

    @Test
    fun `a photo that fills its tile keeps the tile it had, and its word stands on it`() {
        for (scale in listOf(1f, 1.3f)) {
            val compact = solve(360f, fontScale = scale, artFillsTile = true)
            val wide = solve(832f, fontScale = scale, artFillsTile = true)
            assertEquals(0f, compact.badgeBandDp, 0f)
            assertEquals(48f, compact.tileImageHeightDp, 0f)
            assertEquals(0f, wide.badgeBandDp, 0f)
            assertEquals(64f, wide.tileImageHeightDp, 0f)
        }
    }

    @Test
    fun `the shell reads every field of the geometry`() {
        // The budget once held a header and a stage height the shell never read (the header is measured, the stage is the weight(1f) that is left); a field nothing reads is a number
        // this test would go on pinning for no one. Every field is named where the shell lays its bands out.
        val shell = source("AppearanceImagePicker.kt")
        val fields = AppearancePickerGeometry::class.java.declaredFields.map { it.name }.filterNot { it.contains("$") || it == "Companion" }
        assertTrue("the geometry has fields: $fields", fields.size >= 10)
        for (field in fields) {
            assertTrue("the shell reads geometry.$field", shell.contains("geometry.$field"))
        }
        assertFalse("no header height in the budget", fields.contains("headerHeightDp"))
        assertFalse("no stage height in the budget", fields.contains("stageHeightDp"))
        assertTrue("the stage is whatever the fixed bands leave", Regex("""\.weight\(1f\)\s*\.testTag\(AppearancePickerStageTag\)""").containsMatchIn(shell))
    }

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
        File("feature-settings/src/main/kotlin/com/gruning/mimir/rewrite/feature/settings/$name"),
    ).first(File::isFile).readText()
}
