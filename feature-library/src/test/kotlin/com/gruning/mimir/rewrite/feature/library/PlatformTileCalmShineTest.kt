package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calm shine removes the platform-icon shine. A platform tile's light is
 * the plain settle sweep, which core-ui's `rememberMimirFocusSettleGlint` removes under `LocalMimirCalmShine`
 * (core-ui's MimirCalmShineTest proves that), so the tiles must take that primitive, or the ring's default, and never
 * the game gleam or a crossing count of their own. This module has no Compose harness, so the wiring is characterised
 * from source text, the module's idiom.
 */
class PlatformTileCalmShineTest {

    private fun sourceOf(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun `the platform hero tile's ring takes the plain settle sweep, which calm shine removes`() {
        val hero = sourceOf("PlatformHeroTile.kt")
        assertTrue(hero.contains("val ringGlint = rememberMimirFocusSettleGlint(if (ringLit) Unit else null)"))
        assertTrue(hero.contains("glint = ringGlint"))
        assertFalse("a platform is not a game", hero.contains("rememberMimirFocusGameGleam"))
        assertFalse("and it asks for no crossings of its own", hero.contains("calmPasses"))
        assertFalse("nor reads the switch itself", hero.contains("LocalMimirCalmShine"))
    }

    @Test
    fun `the platform card rings with the default light, so calm shine leaves it dark`() {
        val tile = sourceOf("LibraryComponents.kt").substringAfter("fun PlatformTile(").substringBefore("@Composable")
        val card = ".mimirSelectionRing(focused = focused, identity = label, " +
            "shape = MimirRingShape.Card(MimirSpacing.radiusCard))"
        assertTrue(tile.contains(card))
        assertFalse("the default gleam is the plain sweep calm removes", tile.contains("gleam ="))
        assertFalse(tile.contains("LocalMimirCalmShine"))
    }
}
