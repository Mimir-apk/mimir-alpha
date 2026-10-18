package com.gruning.mimir.rewrite.feature.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeGalleryIdentityTest {
    @Test
    fun `gallery keeps controller and touch teaching in distinct casing lanes`() {
        // The controller footer is a string resource now; ThemeGalleryTextTest pins it in English and Spanish.
        assertEquals("Touch a theme to select it.", ThemeGalleryTouchHint)
    }

    @Test
    fun `all nine previews consume authored books textures and signatures`() {
        val treatments = MimirThemePalette.all.map { palette ->
            val treatment = themePreviewBookTreatment(palette)
            val hero = palette.materials.hero

            assertEquals(hero.coverTop, treatment.coverTop)
            assertEquals(hero.coverMiddle, treatment.coverMiddle)
            assertEquals(hero.coverBottom, treatment.coverBottom)
            assertEquals(hero.onCover, treatment.onCover)
            assertEquals(hero.hardware, treatment.hardware)
            assertEquals(palette.materials.counterAccent, treatment.counterAccent)
            assertEquals(palette.runeMark, treatment.runeInk)
            assertEquals(palette.materials.scene.texture, treatment.texture)
            assertTrue(
                "${palette.id} collapsed into one color",
                listOf(
                    treatment.coverTop,
                    treatment.coverMiddle,
                    treatment.coverBottom,
                    treatment.hardware,
                    treatment.counterAccent,
                    treatment.runeInk,
                ).distinct().size >= 4,
            )
            treatment
        }

        assertEquals(9, treatments.size)
        assertEquals(9, treatments.map { it.texture }.distinct().size)
        assertEquals(9, treatments.map { it.signature }.distinct().size)
        assertEquals(
            9,
            treatments.map {
                listOf(
                    it.coverTop,
                    it.coverMiddle,
                    it.coverBottom,
                    it.hardware,
                    it.counterAccent,
                    it.runeInk,
                )
            }.distinct().size,
        )
    }

    @Test
    fun `metallic app themes retain distinct dark readable book volumes`() {
        listOf(MimirThemeId.FOLKVANGR, MimirThemeId.BALDR).forEach { id ->
            val palette = MimirThemePalette.forId(id)
            val treatment = themePreviewBookTreatment(palette)

            assertTrue("$id app field is not dark metallic", palette.surfaceBase.luminance() < 0.02f)
            assertTrue("$id hero top is not dark", treatment.coverTop.luminance() < 0.12f)
            assertTrue("$id hero middle is not dark", treatment.coverMiddle.luminance() < 0.08f)
            assertTrue("$id hero bottom is not dark", treatment.coverBottom.luminance() < 0.03f)
            listOf(treatment.coverTop, treatment.coverMiddle, treatment.coverBottom).forEachIndexed { index, cover ->
                assertContrast("$id hero ink/$index", treatment.onCover, cover, 4.5f)
            }
        }
    }

    @Test
    fun `gallery chrome stays readable over the shared scrim for all themes`() {
        MimirThemePalette.all.forEach { palette ->
            val treatment = themeGalleryChromeTreatment(palette)
            val background = palette.scrim.compositeOver(palette.surfaceBase)

            assertEquals(palette.materials.onScrimPrimary, treatment.primary)
            assertEquals(palette.materials.onScrimSecondary, treatment.secondary)
            assertContrast("${palette.id} gallery primary", treatment.primary, background, 4.5f)
            assertContrast("${palette.id} gallery secondary", treatment.secondary, background, 4.5f)
        }
    }

    @Test
    fun `538x444 at font scale 1_3 contains the full three by three gallery`() {
        val geometry = solveThemeGalleryGeometry(
            viewportWidthDp = 538f,
            viewportHeightDp = 444f,
            fontScale = 1.3f,
        )

        assertEquals(444f, geometry.occupiedHeightDp, 0.01f)
        assertEquals(
            538f,
            geometry.horizontalPaddingDp * 2f + geometry.cardWidthDp * 3f +
                geometry.gridGapDp * 2f,
            0.01f,
        )
        assertEquals(
            geometry.gridHeightDp,
            geometry.cardHeightDp * 3f + geometry.gridGapDp * 2f,
            0.01f,
        )
        assertEquals(
            ThemePreviewBookAspectRatio,
            geometry.bookWidthDp / geometry.bookHeightDp,
            0.0001f,
        )
        assertTrue(geometry.bookHeightDp + 16f <= geometry.cardHeightDp)
        assertTrue(geometry.textColumnWidthDp >= 84f)
        assertTrue(geometry.headerHeightDp >= 52f * 1.3f)
        assertTrue(geometry.footerHeightDp >= 14f * 1.3f)
        assertTrue(!geometry.stackedCards)
    }

    @Test
    fun `360 square uses stacked book identities without clipping the three by three wall`() {
        val geometry = solveThemeGalleryGeometry(
            viewportWidthDp = 360f,
            viewportHeightDp = 360f,
            fontScale = 1.3f,
        )

        assertTrue(geometry.stackedCards)
        assertEquals(360f, geometry.occupiedHeightDp, 0.01f)
        assertEquals(
            360f,
            geometry.horizontalPaddingDp * 2f + geometry.cardWidthDp * 3f + geometry.gridGapDp * 2f,
            0.01f,
        )
        assertTrue(
            geometry.bookHeightDp + themeGalleryStackedTextReserveDp(1.3f) <=
                geometry.cardHeightDp + 0.01f,
        )
        assertTrue(geometry.textColumnWidthDp >= 80f)
    }

    @Test
    fun `authored labels and moods stay compact and complete`() {
        // The mood tags are string resources now; ThemeGalleryTextTest pins their words in English and Spanish.
        MimirThemeId.entries.forEach { id ->
            assertTrue(id.label.isNotBlank())
            assertTrue(id.label.length <= 9)
        }
    }

    private fun assertContrast(label: String, foreground: Color, background: Color, minimum: Float) {
        val light = maxOf(foreground.luminance(), background.luminance())
        val dark = minOf(foreground.luminance(), background.luminance())
        val ratio = (light + 0.05f) / (dark + 0.05f)
        assertTrue("$label contrast was $ratio, expected >= $minimum", ratio >= minimum)
    }
}
