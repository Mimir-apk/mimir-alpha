package com.gruning.mimir.rewrite.feature.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F4 (alpha audit 2026-09-30): the Library's filter keyboard rides the shared sheet host, so it goes inert as it leaves.
 * It used to be a bare `AnimatedVisibility` that stayed composed and touchable through its 180 ms exit, and a tap on a key
 * in that time typed into a field whose keyboard was closed. What the host does is proved in core-ui's MimirSheetHostTest
 * (`feature-library` has no Compose harness), so this pins that the keyboard is on it, and that it still leaves the way it
 * did: composed through the exit with its controller withdrawn.
 */
class LibraryKeyboardSheetHostTest {

    private val source = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
    ).first(File::isFile).readText()

    private val sheet = source
        .substringAfter("private fun MimirKeyboardSheet(")
        .substringBefore("@Composable\nprivate fun NativeAddPickerHeader(")

    @Test
    fun `the keyboard sheet is the shared host, not a bare AnimatedVisibility`() {
        assertTrue(sheet.contains("MimirSheetHost(visible = visible, reducedMotion = reducedMotion, modifier = modifier) {"))
        assertFalse("the host owns the transition now", sheet.contains("AnimatedVisibility("))
        assertFalse(sheet.contains("mimirSheetEnter("))
        assertFalse(sheet.contains("mimirSheetExit("))
    }

    @Test
    fun `it stays composed through its exit with the pad's controller withdrawn`() {
        assertTrue(sheet.contains("val host = rememberLibraryMimirKeyboardHost(authority, visible)"))
        assertTrue("null once `visible` is false, which pops the keyboard's focus scope in that frame", sheet.contains("controller = host?.controller,"))
        assertTrue(sheet.contains("keyDecorator = host?.keyDecorator,"))
        assertTrue("the owner of the routing is told the host only while the sheet is open", sheet.contains("onKeyboardHostChanged(host.takeIf { visible })"))
    }

    @Test
    fun `both call sites keep placing it on the bottom edge`() {
        val calls = Regex("MimirKeyboardSheet\\(\\s*visible = [\\s\\S]*?modifier = Modifier\\.align\\(Alignment\\.BottomCenter\\),\\s*\\)")
            .findAll(source.substringBefore("private fun MimirKeyboardSheet("))
            .count()
        assertEquals("the gamelist's filter and the native add picker's", 2, calls)
    }
}
