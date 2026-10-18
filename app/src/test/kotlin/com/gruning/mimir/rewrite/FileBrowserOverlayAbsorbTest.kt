package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.files.FileItem
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.feature.files.FileBrowserController
import com.gruning.mimir.rewrite.feature.files.FileBrowserMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `FOLDER_BROWSER` overlay branch's absorb polarity to prevent permanent touch and physical-input lockup.
 *
 * The branch used to read `folderBrowser()?.dispatch(action); true`. That `true` is correct for the
 * one-frame gap between the store holding a controller (which makes FOLDER_BROWSER top of the overlay
 * stack) and `FileBrowserSurface`'s `DisposableEffect` pushing that controller's `FocusScope`. It is
 * catastrophic for the OTHER case that makes `dispatch` return false — a browser that stayed mounted
 * while something else took the active scope — because `MimirAppInputDispatch.kt`'s
 * `bindings.overlays.dispatch(action)?.let { return it }` turns it into a permanent input sink, and
 * every touch affordance in `FileBrowserSurface` sits behind the same scope guard, so both input paths
 * die together with no way back but killing the process.
 *
 * Real [FileBrowserController] against a real [DefaultFocusAuthority] — no fakes for either — following
 * `feature-files`' own `FileBrowserControllerTest` precedent. Only the filesystem is faked.
 *
 * **What these tests cannot see**, stated rather than implied: they prove the DECISION, not the app. They
 * do not exercise Compose, so they say nothing about whether a stray tap still reaches the destination
 * underneath (that is `FileBrowserSurface`'s pointer absorber, which no unit test in this tree can drive)
 * and nothing about whether this explains the observed lockup. Only the device settles either.
 */
class FileBrowserOverlayAbsorbTest {

    private val tree: Map<String, List<FileItem>> = mapOf(
        "/root" to listOf(
            FileItem(name = "Games", path = "/root/Games", isDirectory = true),
            FileItem(name = "game.gba", path = "/root/game.gba", extension = "gba"),
        ),
    )

    private fun newController(
        authority: DefaultFocusAuthority,
        onChoose: (String) -> Unit = {},
        onDismiss: () -> Unit = {},
    ): FileBrowserController = FileBrowserController(
        authority = authority,
        rootPath = "/root",
        mode = FileBrowserMode.MANAGE,
        listDirectory = { path -> tree[path].orEmpty() },
        onChoose = onChoose,
        onDismiss = onDismiss,
    )

    @Test
    fun `a mounted browser consumes its own input`() {
        val authority = DefaultFocusAuthority()
        val browser = newController(authority)
        browser.open()

        assertEquals(browser.scope, authority.state.value.scope)
        assertTrue(dispatchFolderBrowser(browser, ControllerAction.NAV_DOWN))
    }

    @Test
    fun `the pre-mount gap still absorbs, so no press leaks to the destination underneath`() {
        val authority = DefaultFocusAuthority()
        var chosen: String? = null
        var dismissed = 0
        // The store holds this controller — FOLDER_BROWSER is already top of the overlay stack — but
        // FileBrowserSurface's DisposableEffect has not run, so open() has not pushed the scope yet.
        val browser = newController(
            authority,
            onChoose = { chosen = it },
            onDismiss = { dismissed += 1 },
        )

        assertFalse("precondition: this mount has not opened", browser.hasOpened)
        assertFalse("precondition: the browser's scope is not active", authority.state.value.scope == browser.scope)

        for (action in ControllerAction.entries) {
            assertTrue(
                "a pre-mount $action must be absorbed, never leaked to the destination underneath",
                dispatchFolderBrowser(browser, action),
            )
        }
        // Absorbed, not acted on: the browser itself must not have run anything either.
        assertEquals(null, chosen)
        assertEquals(0, dismissed)
    }

    @Test
    fun `a mounted browser divorced from its focus scope refuses instead of swallowing forever`() {
        val authority = DefaultFocusAuthority()
        val browser = newController(authority)
        browser.open()

        // Exactly what a fall-through tap on the Settings screen underneath used to do: a row's
        // onActivate() runs unconditionally (only its requestFocus is scope-guarded) and pushes a scope
        // for an overlay — theme/accent/backdrop/focus-ring — that ranks BELOW FOLDER_BROWSER in
        // resolveMimirOverlayStack, so this browser stays top of the stack and keeps being asked.
        authority.pushScope(FocusScope("test/thief"))

        assertTrue("precondition: the browser is still mounted", browser.hasOpened)
        assertFalse("precondition: the browser has lost the active scope", authority.state.value.scope == browser.scope)

        for (action in ControllerAction.entries) {
            assertFalse(
                "a divorced browser must never absorb $action — that is the total input lockup",
                dispatchFolderBrowser(browser, action),
            )
        }
    }

    @Test
    fun `CANCEL specifically must escape a divorced browser — it is the player's only way out`() {
        val authority = DefaultFocusAuthority()
        val browser = newController(authority)
        browser.open()
        authority.pushScope(FocusScope("test/thief"))

        assertFalse(dispatchFolderBrowser(browser, ControllerAction.CANCEL))
        assertFalse(dispatchFolderBrowser(browser, ControllerAction.SYSTEM_BACK))
    }

    @Test
    fun `a closed browser stops claiming the pre-mount absorb`() {
        val authority = DefaultFocusAuthority()
        val browser = newController(authority)
        browser.open()
        browser.close()

        assertTrue("hasOpened latches -- close must not make this look un-mounted", browser.hasOpened)
        assertFalse(
            "a closed controller still reachable through a stale store reference must not become a sink " +
                "-- MimirAppHost's own wrapping DisposableEffect exists to stop that state arising, and " +
                "this is the polarity that keeps it recoverable if it ever does",
            dispatchFolderBrowser(browser, ControllerAction.CONFIRM),
        )
    }

    @Test
    fun `a null browser absorbs — unreachable by construction, but never a leak if it happens`() {
        assertTrue(dispatchFolderBrowser(null, ControllerAction.CONFIRM))
    }
}
