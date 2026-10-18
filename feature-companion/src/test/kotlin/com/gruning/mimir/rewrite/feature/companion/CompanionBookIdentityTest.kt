package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionBookIdentityTest {
    @Test
    fun `all nine companion books consume authored hero roles`() {
        val treatments = MimirThemePalette.all.map { palette ->
            val treatment = companionBookTreatment(palette)
            val hero = palette.materials.hero

            assertEquals(hero.coverTop, treatment.coverTop)
            assertEquals(hero.coverMiddle, treatment.coverMiddle)
            assertEquals(hero.coverBottom, treatment.coverBottom)
            assertEquals(hero.onCover, treatment.onCover)
            assertEquals(hero.hardware, treatment.hardware)
            assertEquals(palette.materials.counterAccent, treatment.counterAccent)
            assertEquals(palette.runeMark, treatment.runeInk)
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
    fun `book silhouette and open page fit compact companion envelope`() {
        // the TILE face is square now, like every other tile on the lattice — the
        // tile-squaring change the console arm got and the tome arm did not. The 3:4 book
        // proportion is not retired from the product, only from the tile: the OPEN page below still
        // solves at it, which is what the rest of this test measures.
        assertEquals(1f, CompanionTomeTileAspectRatio, 0f)

        val geometry = solveCompanionOpenBookGeometry(
            viewportWidthDp = 538f,
            viewportHeightDp = 444f,
            fontScale = 1.3f,
            renaming = false,
        )

        assertEquals(408.88f, geometry.panelWidthDp, 0.01f)
        assertEquals(417.36f, geometry.panelHeightDp, 0.01f)
        assertTrue(geometry.panelWidthDp < 538f)
        assertTrue(geometry.panelHeightDp < 444f)
        assertTrue(geometry.childViewportMaxHeightDp in 96f..240f)
        assertTrue(
            geometry.reservedChromeHeightDp + geometry.childViewportMaxHeightDp <=
                geometry.panelHeightDp,
        )

        val renaming = solveCompanionOpenBookGeometry(538f, 444f, 1.3f, renaming = true)
        assertTrue(renaming.panelWidthDp <= 538f)
        assertTrue(renaming.panelHeightDp <= 444f)
        assertEquals(0f, renaming.childViewportMaxHeightDp, 0f)
    }

    @Test
    fun `quicklaunch height is unchanged, and HOME no longer excepted`() {
        assertEquals(86f, CompanionQuickLaunchHeightDp, 0f)
        assertEquals(
            444f,
            companionQuickLaunchContentHeightDp(444f, quickLaunchVisible = true),
            0f,
        )
        // **The HOME exception was removed on 2026-08-31**: the rail is now on every surface. The layout uses the 86 dp because the alternative was the
        // instruments switch living in HOME's footer beside Create Tome — two different kinds of
        // thing at equal weight, in a row where it slid sideways on every cursor move. These
        // assertions are INVERTED rather than deleted, because "HOME is not special here" is now
        // the rule and it deserves a pin as much as the exception did.
        CompanionMode.entries.forEach { mode ->
            assertTrue("$mode carries the rail", companionQuickLaunchAvailable(CompanionSceneTarget.Payload(mode)))
        }
        // The Route target was retired with the route title card. SETTINGS now resolves to a
        // payload like anything else, and the surrounding loop above already covers every mode -- so the
        // guarantee this line used to add is now a special case of the line above it.
    }
}
