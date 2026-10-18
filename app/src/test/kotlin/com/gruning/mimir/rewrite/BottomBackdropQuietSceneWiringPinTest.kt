package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1 of the Thor Bottom revamp, read as source because the call sites cannot run in a unit test: both Bottom backdrops --
 * the lattice stage and the platform shelf -- bake the Default sun at the quiet light and without the spike specks.
 * The pixels themselves are `core-ui`'s `MimirBottomBackdropBakeTest`.
 */
class BottomBackdropQuietSceneWiringPinTest {
    private fun source(path: String): String =
        listOf(File(path), File("../$path")).first(File::isFile).readText()

    @Test
    fun `the Bottom lattice stage bakes the quiet, speck-free scene`() {
        val src = source("feature-companion/src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/CompanionSurface.kt")
        assertEquals(
            1,
            Regex("""MimirThemeSceneBackdrop\(\s*Modifier\.matchParentSize\(\), quietSceneLight = true, shineStars = false\)""")
                .findAll(src).count(),
        )
        assertEquals("the Bottom has one scene backdrop", 1, Regex("""MimirThemeSceneBackdrop\(""").findAll(src).count())
    }

    @Test
    fun `the Bottom platform shelf bakes the quiet, speck-free scene`() {
        val src = source("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/CompanionPlatformShelf.kt")
        assertTrue(".mimirThemeSceneBackdrop(palette, quietSceneLight = true, shineStars = false)" in src)
        assertEquals(1, Regex("""mimirThemeSceneBackdrop\(""").findAll(src).count())
    }
}
