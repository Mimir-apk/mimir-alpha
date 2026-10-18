package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cert 2026-10-08 (A1, e4-01/02/02b/40/41): the Library's EMULATION / ANDROID GAMES tabs ignored a finger. A tap on the
 * inactive tab must run the switch R1/L1 run -- [selectShelfModeWithFocusStamp] over `cycleShelfModeWithFocusStamp` -- and a
 * tap on the active tab must do nothing. The differential pins compare a tap against the shoulder press on twin fixtures,
 * field by field, so the tap cannot drift into a bare `setShelfMode` (which leaves the outgoing ring's key as live focus).
 */
class ShelfModeTapTest {

    private val ps1Key = FocusKey("library/shelf/ps1")
    private val platformRows = listOf(PlatformRow(PlatformId.PS1, "PlayStation", gameCount = 1))
    private val nativeRows = listOf(nativeRow("com.example.alpha"), nativeRow("com.example.beta"))

    /** A shelf on the EMULATION ring with one tile registered and focused, as the screen leaves it at rest. */
    private inner class Shelf(startMode: LibraryShelfMode = LibraryShelfMode.EMULATION) {
        val viewModel = LibraryViewModel(FakeLibraryRepository()).apply { setShelfMode(startMode) }
        val authority = DefaultFocusAuthority().apply {
            pushScope(LibraryShelfScope)
            register(ps1Key, LibraryShelfScope, FocusBounds(0f, 0f, 80f, 100f))
            requestFocus(ps1Key)
        }

        fun press(action: ControllerAction) {
            assertTrue(
                dispatchToLibrary(
                    authority = authority,
                    viewModel = viewModel,
                    action = action,
                    onExit = {},
                    onRescanFolder = {},
                    platformRows = platformRows,
                    nativeGameRows = nativeRows,
                ),
            )
        }

        fun tap(mode: LibraryShelfMode): Boolean =
            selectShelfModeWithFocusStamp(authority, viewModel, platformRows, nativeRows, mode)

        /** Everything a mode switch is allowed to leave behind. */
        fun fingerprint() = listOf(
            viewModel.shelfMode.value,
            viewModel.lastShelfModeStep,
            viewModel.centeredShelfKey.value,
            authority.state.value.scope,
            authority.state.value.focused,
            authority.rememberedFocusIn(LibraryShelfScope),
        )
    }

    @Test
    fun `a tap on the inactive Android tab leaves exactly what R1 leaves`() {
        val pressed = Shelf().apply { press(ControllerAction.PAGE_RIGHT) }
        val tapped = Shelf()

        assertTrue(tapped.tap(LibraryShelfMode.ANDROID_GAMES))

        assertEquals(LibraryShelfMode.ANDROID_GAMES, tapped.viewModel.shelfMode.value)
        assertEquals(pressed.fingerprint(), tapped.fingerprint())
        // The focus-stamp path's own marks, spelled out: live focus dropped, the destination ring's first key stamped.
        assertNull(tapped.authority.state.value.focused)
        assertEquals(nativeShelfKey("com.example.alpha"), tapped.viewModel.centeredShelfKey.value)
        assertEquals(1, tapped.viewModel.lastShelfModeStep)
    }

    @Test
    fun `a tap back on the Emulation tab leaves exactly what L1 leaves, gliding from the other side`() {
        val pressed = Shelf(LibraryShelfMode.ANDROID_GAMES).apply { press(ControllerAction.PAGE_LEFT) }
        val tapped = Shelf(LibraryShelfMode.ANDROID_GAMES)

        assertTrue(tapped.tap(LibraryShelfMode.EMULATION))

        assertEquals(LibraryShelfMode.EMULATION, tapped.viewModel.shelfMode.value)
        assertEquals(pressed.fingerprint(), tapped.fingerprint())
        assertEquals(ps1Key, tapped.viewModel.centeredShelfKey.value)
        assertEquals(-1, tapped.viewModel.lastShelfModeStep)
    }

    @Test
    fun `a tap on the active tab is a no-op that does not even clear focus`() {
        listOf(LibraryShelfMode.EMULATION, LibraryShelfMode.ANDROID_GAMES).forEach { active ->
            val shelf = Shelf(active)
            val before = shelf.fingerprint()

            assertFalse("$active is already showing", shelf.tap(active))

            assertEquals(before, shelf.fingerprint())
        }
    }

    @Test
    fun `a tap into an empty Android ring still flips the mode and drops stale focus, as R1 does`() {
        val emptyRows = emptyList<NativeShelfRow>()
        val shelf = Shelf()

        assertTrue(selectShelfModeWithFocusStamp(shelf.authority, shelf.viewModel, platformRows, emptyRows, LibraryShelfMode.ANDROID_GAMES))

        assertEquals(LibraryShelfMode.ANDROID_GAMES, shelf.viewModel.shelfMode.value)
        assertNull("the outgoing ring's key must not stay live", shelf.authority.state.value.focused)
        assertNull("an empty ring has no key to stamp", shelf.viewModel.centeredShelfKey.value)
    }

    @Test
    fun `a tap while the dock owns input changes nothing, like R1`() {
        val shelf = Shelf()
        val dockScope = FocusScope("library/rail")
        val dockKey = FocusKey("library/rail/library")
        shelf.authority.register(dockKey, dockScope, FocusBounds(0f, 300f, 80f, 380f))
        shelf.authority.pushScope(dockScope)
        shelf.authority.requestFocus(dockKey)
        val before = shelf.fingerprint()

        assertFalse(shelf.tap(LibraryShelfMode.ANDROID_GAMES))

        assertEquals("the dock keeps its focus", dockKey, shelf.authority.state.value.focused)
        assertEquals(before, shelf.fingerprint())
    }

    @Test
    fun `the strip label resolves to its mode and an unknown label to none`() {
        LibraryShelfMode.entries.forEach { mode ->
            assertEquals(mode, LibraryShelfMode.fromStripLabel(mode.stripLabel))
        }
        assertNull(LibraryShelfMode.fromStripLabel("ANDROID APPS"))
    }

    @Test
    fun `the screen's tabs reach the focus-stamped switch, never the bare setter`() {
        val screen = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/LibraryScreen.kt"),
        ).first(File::isFile).readText()

        assertTrue(screen.contains("onSelectShelfMode = { selectShelfModeWithFocusStamp(authority, viewModel,"))
        assertTrue(screen.contains("ShelfModeStrip(active = shelfMode.stripLabel, compact = compact) {"))
        assertFalse(screen.contains("setShelfMode("))
    }

    private fun nativeRow(packageName: String) = NativeShelfRow(
        packageName = packageName,
        label = packageName.substringAfterLast('.'),
        launchActivity = null,
        isGame = true,
        isFavorite = false,
    )
}
