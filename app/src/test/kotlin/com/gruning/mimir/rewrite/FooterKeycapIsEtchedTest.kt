package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the footer keycap badges (A, B, X, Y, L1/R1 ...) must not be tinted; they are the etched chip family's plate with the key in the chip ink, drawn ONCE, in the shared
 * `MimirFooterKeycap`, so every footer follows it. Source pins, this module's idiom for a rule about which composable a surface calls: the shared keycap is built on the etched surface and
 * the chip ink and holds no tint, and the three footers that draw keycaps (the Settings and Browse shell's, the shelf and gamelist legends', the Files footer's) call it and keep no
 * badge of their own. Pixel truth is the render tests' (`EtchedButtonCaptureTest`'s footer pictures); this is the door.
 */
class FooterKeycapIsEtchedTest {

    private fun sourceOf(module: String, path: String): String {
        val base = listOf(File("."), File("..")).first { File(it, "feature-files/src/main").isDirectory }
        return File(base, "$module/src/main/kotlin/com/gruning/mimir/rewrite/$path").readText()
    }

    /** The code of [text] with its line comments blanked, so a rule's own explanation never trips a pin. */
    private fun code(text: String): String = text.lines().joinToString("\n") { line -> line.substringBefore("//") }

    @Test
    fun `the shared keycap is the etched plate with the chip ink and no tint`() {
        val chip = code(sourceOf("core-ui", "core/ui/MimirEtchedChip.kt"))
        val keycap = chip.substringAfter("fun MimirFooterKeycap(").substringBefore("private val FooterKeycapPlate")
        assertTrue(keycap.contains(".mimirEtchedChipSurface(palette)"))
        assertTrue(keycap.contains("color = palette.etchedChipInk()"))
        assertFalse("never warmed, primary or focused: a key is a label", keycap.contains("warmed") || keycap.contains("primary") || keycap.contains("focused"))
        assertFalse("no accent tint", keycap.contains(".accent") || keycap.contains("accentBright") || keycap.contains(".background(") || keycap.contains("Brush."))
    }

    @Test
    fun `the Settings and Browse shell footer draws its keys through it`() {
        val shell = code(sourceOf("app", "AppShellScaffold.kt"))
        val cap = shell.substringAfter("private fun ShellCap(").substringBefore("private fun ShellLabel(")
        assertTrue(cap.contains("MimirFooterKeycap("))
        assertFalse("no tinted badge of its own", cap.contains("accent.copy") || cap.contains(".background(") || cap.contains("accentBright"))
    }

    @Test
    fun `the shelf and gamelist legends draw their keys through it`() {
        val chrome = code(sourceOf("feature-library", "feature/library/MimirShelfChrome.kt"))
        assertTrue(chrome.contains("MimirFooterKeycap(key = projectedKey"))
        assertFalse("the private tinted FooterKeycap is gone", chrome.contains("fun FooterKeycap("))
    }

    @Test
    fun `the Files footer draws its keys through it and no longer paints them in the ember`() {
        val surface = code(sourceOf("feature-files", "feature/files/FileBrowserSurface.kt"))
        val legend = surface.substringAfter("private fun FileBrowserFooterLegend(")
        assertTrue(legend.contains("MimirFooterKeycap(key = it, palette = palette)"))
        assertFalse("the key is not a gold word any more", legend.contains("palette.emberHot"))
    }
}
