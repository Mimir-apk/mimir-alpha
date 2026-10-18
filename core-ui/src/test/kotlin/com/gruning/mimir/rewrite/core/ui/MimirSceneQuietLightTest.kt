package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * on a compact square the Default theme's sun lands under the dock's
 * last icons, and it dims there while retaining its position, size and presence.
 */
class MimirSceneQuietLightTest {

    @Test
    fun `the quiet light is about half`() {
        assertEquals(0.5f, MimirSceneQuietLightScale, 0f)
    }

    @Test
    fun `a quiet bake is its own raster, never a palette swap of the full-light one`() {
        val full = MimirShelfBackgroundCacheKey(
            size = Size(720f, 720f),
            density = 204f / 160f,
            layoutDirection = LayoutDirection.Ltr,
            palette = MimirThemePalette.forId(MimirThemeId.entries.first()),
            showAtmosphericClouds = true,
        )
        val quiet = full.copy(quietSceneLight = true)

        assertFalse("every existing surface bakes at full light", full.quietSceneLight)
        assertNotEquals(full, quiet)
        assertFalse(full.differsOnlyInPaletteFrom(quiet))
    }

    @Test
    fun `only the sun and its ring dust dim, never the stars or the nebula`() {
        val painter = sceneSource()
            .substringAfter("fun DrawScope.drawWellSceneV2")
            .substringBefore("internal fun DrawScope.drawGoldenHall")

        // Both ring-dust tiers and the sun carry the scale.
        assertEquals(2, Regex("""WellRingTierAlpha\[tier\] \* light""").findAll(painter).count())
        assertTrue(Regex("""0\.90f \+ pulse \* 0\.08f\) \* light""").containsMatchIn(painter))
        // The field keeps its light.
        listOf("WellStarDimAlpha", "WellStarBrightAlpha", "WellShineAlpha", "WellNebulaAlphaScale").forEach { field ->
            assertFalse(
                "$field must not dim",
                Regex("""$field\s*\*\s*light""").containsMatchIn(painter),
            )
        }
    }

    private fun sceneSource(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSceneAtmosphereV2.kt"),
            File("core-ui/src/main/kotlin/com/gruning/mimir/rewrite/core/ui/MimirSceneAtmosphereV2.kt"),
        )
        return candidates.first(File::isFile).readText()
    }
}
