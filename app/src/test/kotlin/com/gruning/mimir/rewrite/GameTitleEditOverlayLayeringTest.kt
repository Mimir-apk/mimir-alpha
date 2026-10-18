package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layering fix. `GameTitleEditOverlay`'s own scrim
 * used to be an unrelated flat `palette.surfaceBase` wash at a bespoke 0.95 alpha, inherited unchanged
 * from `ProfileNameEditOverlay`'s shape even though THIS overlay always has a launch card composed behind
 * it that the flat wash was not designed to preserve. It now reuses `mimirGlassScrimColor()` -- the same
 * token the launch card's own outer surface already paints itself with -- so this reads as one more
 * stacked liquid-glass layer, not a mismatched opaque colour. A source characterization test rather than
 * a Compose test rule, matching the pattern `HomeLaunchCardTest` already uses for this class of assertion
 * in this codebase.
 */
class GameTitleEditOverlayLayeringTest {

    @Test
    fun scrimReusesTheSharedGlassTokenNotTheBespokeFlatWash() {
        val source = gameTitleEditOverlaySource()

        assertTrue(source.contains(".background(mimirGlassScrimColor())"))
        assertFalse(source.contains("palette.surfaceBase.copy(alpha = 0.95f)"))
    }

    private fun gameTitleEditOverlaySource(): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/GameTitleEditOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/GameTitleEditOverlay.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("GameTitleEditOverlay.kt not found from ${File(".").absolutePath}")
    }
}
