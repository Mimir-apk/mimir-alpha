package com.gruning.mimir.rewrite

import androidx.compose.ui.graphics.toArgb
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 *  every reclaim holds the restore guard on the Bottom for 108-294 ms before the lattice's
 * first frame, and the guard was black. It stays exactly as functional; only its ground now comes from the theme
 * the Bottom's Presentation is about to draw. Pins the colour choice and where the theme is read from -- the
 * window itself is device-only.
 */
class CompanionReturnGuardGroundTest {

    @Test
    fun `every theme grounds the guard in its own stage base token, opaque`() {
        MimirThemeId.entries.forEach { id ->
            val ground = companionReturnGuardGroundArgb(id.storageKey)
            assertEquals("$id", MimirThemePalette.forId(id).surfaceBase.copy(alpha = 1f).toArgb(), ground)
            assertEquals("$id guard must stay opaque", 0xFF, ground ushr 24)
        }
    }

    @Test
    fun `an empty or unknown theme key grounds the guard in Default, as the Presentation resolves it`() {
        val default = MimirThemePalette.Default.surfaceBase.copy(alpha = 1f).toArgb()
        assertEquals(default, companionReturnGuardGroundArgb(""))
        assertEquals(default, companionReturnGuardGroundArgb(null))
        assertEquals(default, companionReturnGuardGroundArgb("not-a-theme"))
    }

    @Test
    fun `no theme paints the guard plain black`() {
        MimirThemeId.entries.forEach { id ->
            assertNotEquals("$id", 0xFF000000.toInt(), companionReturnGuardGroundArgb(id.storageKey))
        }
    }

    @Test
    fun `the guard reads the theme from the payload channel the Bottom's Presentation wears`() {
        val file = File("src/main/kotlin/com/gruning/mimir/rewrite/CompanionReturnActivity.kt")
        assertTrue("CompanionReturnActivity.kt must be readable from the module dir", file.isFile)
        val source = file.readText()
        assertTrue(source.contains("companionReturnGuardGroundArgb(CompanionState.payload.value.themeId)"))
        assertFalse("the black window background is retired", source.contains("android.R.color.black"))
    }
}
