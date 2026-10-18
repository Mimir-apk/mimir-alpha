package com.gruning.mimir.rewrite

import androidx.compose.ui.graphics.toArgb
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Test

/** OR-11: the native menu's key-focus ring wears the same token the shelf rings do, so the player's ring colour reaches it. */
class CompanionOverlayFocusRingColorTest {
    @Test
    fun everyThemeCarriesItsTileFocusRingAsAnOpaqueRingColour() {
        MimirThemeId.entries.forEach { themeId ->
            val expected = MimirAppearanceOverrides.effectivePalette(themeId).tileFocusRing.toArgb() or 0xFF000000.toInt()
            assertEquals("$themeId ring colour", expected, companionOverlayTheme(themeId).focusRingArgb)
            assertEquals(0xFF, companionOverlayTheme(themeId).focusRingArgb ushr 24)
        }
    }
}
