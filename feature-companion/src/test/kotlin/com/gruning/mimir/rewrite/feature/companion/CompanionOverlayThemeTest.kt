package com.gruning.mimir.rewrite.feature.companion

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Every overlay the HOST composes must open its own `MimirTheme`.**
 *
 * `CompanionSurface` opens one for its own content, but the recents drawer is composed as its SIBLING by
 * `MimirCompanionPresentation` — outside that scope. Reading `LocalMimirPalette` from out there throws
 * *"LocalMimirPalette not provided"*.
 *
 * This is not hypothetical. The Bottom instruments panel (retired on 2026-09-12) shipped
 * without it on 2026-08-31 and **hard-crashed when opened on a device** — while
 * every unit test stayed green,
 * because none of them composed it. The drawer had carried the parameter for exactly this reason since
 * it was written; the second overlay was built beside the first without copying the one thing that
 * made the first one work.
 *
 * A source pin rather than a render test: the defect is a missing WRAPPER, which is structural, and a
 * composition test for it would need the very host scope whose absence is the bug.
 */
class CompanionOverlayThemeTest {

    private fun sourceOf(file: String): String =
        java.io.File("src/main/kotlin/com/gruning/mimir/rewrite/feature/companion/$file").readText()

    @Test
    fun `every host-composed overlay opens its own theme`() {
        listOf(
            "CompanionRecentsDrawer.kt" to "CompanionRecentsDrawer",
        ).forEach { (file, composable) ->
            val source = sourceOf(file)
            assertTrue(
                "$composable takes a themeId -- it is composed outside CompanionSurface's theme",
                source.contains("themeId: String?"),
            )
            assertTrue(
                "$composable opens MimirTheme itself, or LocalMimirPalette throws at first paint",
                source.contains("MimirTheme(themeId = MimirThemeId.fromStorageKey(themeId))"),
            )
        }
    }
}
