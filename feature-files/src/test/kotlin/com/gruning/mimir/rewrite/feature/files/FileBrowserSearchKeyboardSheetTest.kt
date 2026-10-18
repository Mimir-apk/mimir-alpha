package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.MimirKeyboardFocusScope
import com.gruning.mimir.rewrite.core.input.createMimirKeyboardFocusBindingForTests
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the Files search keyboard is a sheet on `MimirSheetHost`, so
 * it slides in and out like the Library's and stays composed for its 180 ms exit. `feature-files` composes nothing in its
 * tests, so this pins two things. The wiring, as source: the host's own `visible`, and a keyboard that keeps drawing when
 * its binding is withdrawn. And the controller half of the risk F4 named for this browser, "a history of a second live
 * focus graph" (`FileBrowserSurfaceMotion.kt`): from the call that lowers the flag the pad is the search screen's, so a
 * keyboard that is still leaving cannot be reached by it. What a leaving MimirKeyboard registers with the authority, and
 * what a touch on it does, is composed and proved in core-ui (`MimirSheetHostKeyboardTest`).
 */
class FileBrowserSearchKeyboardSheetTest {

    private fun source(name: String): String = listOf(
        File("src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
        File("feature-files/src/main/kotlin/com/gruning/mimir/rewrite/feature/files/$name"),
    ).first(File::isFile).readText()

    private val root = "/v"

    private val listing = mapOf(
        "/v" to listOf(FileItem(name = "Roms", path = "/v/Roms", isDirectory = true)),
        "/v/Roms" to listOf(FileItem(name = "Sonic.gba", path = "/v/Roms/Sonic.gba", isDirectory = false)),
    )

    private fun controller(authority: DefaultFocusAuthority) = FileBrowserController(
        authority = authority,
        rootPath = root,
        mode = FileBrowserMode.MANAGE,
        listDirectory = { listing[it].orEmpty() },
        onChoose = {},
        onDismiss = {},
    )

    // ─────────────────────────────────────────────────────────────── the wiring, as source ──

    @Test
    fun `the surface hands the sheet host the controller's own flag and the bottom edge, and no page arrival`() {
        val surface = source("FileBrowserSurface.kt")
        val host = surface.substringAfter("MimirSheetHost(").substringBefore("FileBrowserSearchKeyboard(")
        assertTrue("its own visibility, so the keyboard can leave", host.contains("visible = controller.searchKeyboardOpen,"))
        assertTrue("the surface's single reduced-motion read", host.contains("reducedMotion = reducedMotion,"))
        assertTrue(host.contains(".align(Alignment.BottomCenter)"))
        assertTrue("its width is what the keyboard always had", host.contains(".fillMaxWidth(0.94f)"))
        assertFalse("no page arrival on top of the sheet's own transition", surface.contains("fileBrowserArrivalGlide(FileBrowserKeyboardArrival)"))
        assertFalse("and it is not mounted only while open, which is what made it cut out", surface.contains("if (controller.searchKeyboardOpen) {"))
    }

    @Test
    fun `the keyboard keeps drawing when its binding is withdrawn, with no controller and no decorator`() {
        val keyboard = source("FileBrowserSearchSurface.kt")
            .substringAfter("internal fun FileBrowserSearchKeyboard(")
            .substringBefore("/** Reports whether a search provider was wired at all")
        assertFalse("it used to return on a null binding, which cut the sheet's exit", keyboard.contains("if (binding == null) return"))
        assertTrue(keyboard.contains("val binding = rememberMimirKeyboardFocusBinding(authority, active = controller.searchKeyboardOpen)"))
        assertTrue("the controller is the binding's, or nothing", keyboard.contains("val keyboardController = remember(binding) {\n        binding?.let { open ->"))
        assertTrue("so is the key decorator", keyboard.contains("val keyDecorator: MimirKeyboardKeyDecorator? = remember(binding) {\n        binding?.let { open ->"))
        assertTrue(keyboard.contains("controller = keyboardController,") && keyboard.contains("keyDecorator = keyDecorator,"))
        assertTrue("the controller is still told of the binding, and of its going", keyboard.contains("controller.attachSearchKeyboard(binding)"))
    }

    // ───────────────────────────────────────────── the controller half of "one focus graph" ──

    @Test
    fun `the call that lowers the keyboard hands the pad to the search screen, so a leaving sheet cannot be reached by it`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val binding = createMimirKeyboardFocusBindingForTests(authority)
        c.attachSearchKeyboard(binding)
        c.openSearchKeyboard()
        binding.enter()
        assertEquals(MimirKeyboardFocusScope, authority.state.value.scope)

        // The frame the flag turns false: the sheet starts to leave, `FileBrowserSearchKeyboard`'s binding is null and its
        // effect detaches it.
        c.closeSearchKeyboard()
        c.attachSearchKeyboard(null)

        assertEquals("the keyboard's scope is popped in that call, not when the fade ends", c.searchScope, authority.state.value.scope)
        assertFalse(c.searchKeyboardOpen)
        assertTrue("a press while it slides away is the search screen's and is absorbed there", c.dispatch(ControllerAction.NAV_RIGHT))
        assertEquals("and the pad is still on the search screen, never back in the keyboard's scope", c.searchScope, authority.state.value.scope)
        c.close()
    }

    @Test
    fun `the keyboard can be raised again inside its exit, and lowered again`() {
        val authority = DefaultFocusAuthority()
        val c = controller(authority)
        c.open()
        c.setSearchAvailable(true)
        c.openSearch()
        val first = createMimirKeyboardFocusBindingForTests(authority)
        c.attachSearchKeyboard(first)
        c.openSearchKeyboard()
        first.enter()
        c.closeSearchKeyboard()
        c.attachSearchKeyboard(null)

        // Raised inside the 180 ms: the host is asked to come back, and hands a fresh binding down.
        c.openSearchKeyboard()
        val second = createMimirKeyboardFocusBindingForTests(authority)
        c.attachSearchKeyboard(second)
        second.enter()
        assertEquals(MimirKeyboardFocusScope, authority.state.value.scope)
        assertTrue(c.searchKeyboardOpen)

        assertTrue("B lowers it again", c.dispatch(ControllerAction.CANCEL))
        assertFalse(c.searchKeyboardOpen)
        assertEquals(c.searchScope, authority.state.value.scope)
        c.close()
    }
}
