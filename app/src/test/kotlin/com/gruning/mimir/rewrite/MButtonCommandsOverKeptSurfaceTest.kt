package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adjacent half of walk-32k. The M button's Home, Settings, Utility and Tweaker commands sweep with
 * `dismissConflictingModals()` and no keep set, and the sweep sent the folder browser the same single CANCEL that
 * Quick launch's did. A browser at depth reads that as "up one directory", the loop's no-progress break fired with the
 * browser still on top, and the command landed underneath it: Home navigated beneath a Files screen one folder
 * shallower, and Utility opened buried below it (FOLDER_BROWSER ranks above BRIGHTNESS).
 *
 * The rule for those commands is that a browser is closed ENTIRELY, never stepped back. It is implemented in the sweep:
 * the coordinator takes a `closeEntirely` hook, production wires it to `FileBrowserStore.close` for the folder browser,
 * and the three call sites are untouched. Quick launch passes a keep set and never reaches the hook.
 *
 * Real [com.gruning.mimir.rewrite.feature.files.FileBrowserController] on a real authority; the sweep, the stack and the
 * order of the call sites' steps are the production ones ([OverlayStackHarness]). The hook's production body is
 * `FileBrowserStore.close`, which needs an Application and is pinned at source level below.
 */
class MButtonCommandsOverKeptSurfaceTest {

    /** The browser one level deep, wired the way production wires the hook: close the controller, clear the flag. */
    private class Case {
        val authority = DefaultFocusAuthority()
        var dismissed = 0
        val browser = browserAtDepth(authority) { dismissed += 1 }

        fun harness(
            vararg open: MimirOverlayKind,
            yielding: Set<MimirOverlayKind> = emptySet(),
            withFullClose: Boolean = true,
        ) = OverlayStackHarness(
            *open,
            yielding = yielding,
            browser = { action -> dispatchFolderBrowser(browser, action) },
            closedEntirely = if (withFullClose) setOf(MimirOverlayKind.FOLDER_BROWSER) else emptySet(),
            onClosedEntirely = { browser.close() },
        )

        /** The browser is shut: every scope it pushed is popped, so none is active, and it answers nothing. */
        fun assertClosed() {
            assertEquals("every scope the browser pushed is gone", FocusScope.Root, authority.state.value.scope)
            assertFalse("and it answers no press", browser.dispatch(ControllerAction.CANCEL))
        }
    }

    // ── the defect, as it was ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `without a full close the sweep steps a browser at depth back and leaves it on top of Home`() {
        val c = Case()
        val s = c.harness(MimirOverlayKind.FOLDER_BROWSER, withFullClose = false)

        s.pressMNavigation()

        assertEquals("one CANCEL is one folder up", "/root", c.browser.currentPath)
        assertEquals("the browser is still on top", MimirOverlayKind.FOLDER_BROWSER, s.top)
        assertEquals("and Home navigated underneath it anyway", "applyMButtonHomeNavigation", s.settled.last())
    }

    // ── the rule ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `M to Home from a browser at depth ends with no browser and Home navigated`() {
        val c = Case()
        val s = c.harness(MimirOverlayKind.FOLDER_BROWSER)

        s.pressMNavigation()

        assertEquals("the browser was closed in one step", listOf(MimirOverlayKind.FOLDER_BROWSER), s.fullCloses)
        assertEquals("and was never sent a CANCEL to step back with", emptyList<MimirOverlayKind>(), s.cancels)
        assertNull("nothing is left on the stack", s.top)
        c.assertClosed()
        assertEquals(
            "the existing sequence ran after the sweep, ending in the Home navigation",
            listOf("sweepDone", "closeUtility", "applyMButtonHomeNavigation"),
            s.settled,
        )
    }

    @Test
    fun `M to Settings from a browser at depth closes it the same way`() {
        val c = Case()
        val s = c.harness(MimirOverlayKind.FOLDER_BROWSER)

        s.pressMNavigation(settings = true)

        assertEquals(listOf(MimirOverlayKind.FOLDER_BROWSER), s.fullCloses)
        assertNull(s.top)
        c.assertClosed()
        assertEquals("openSettings", s.settled.last())
    }

    @Test
    fun `M to Utility from a browser at depth ends with the browser closed and Utility open on top`() {
        val c = Case()
        val s = c.harness(MimirOverlayKind.FOLDER_BROWSER)

        s.pressMUtility()

        assertEquals(listOf(MimirOverlayKind.FOLDER_BROWSER), s.fullCloses)
        assertEquals(emptyList<MimirOverlayKind>(), s.cancels)
        c.assertClosed()
        assertEquals("Utility is on top, not buried beneath the browser", listOf(MimirOverlayKind.BRIGHTNESS), s.entries)
    }

    @Test
    fun `M to Utility from Files that was opened from Utility closes the browser, then Utility's own Back, then reopens it`() {
        val c = Case()
        // Files' only door is the Utility row, so this is the reported stack: browser over Utility.
        val s = c.harness(
            MimirOverlayKind.FOLDER_BROWSER,
            MimirOverlayKind.BRIGHTNESS,
            yielding = setOf(MimirOverlayKind.BRIGHTNESS),
        )

        s.pressMUtility()

        assertEquals(listOf(MimirOverlayKind.FOLDER_BROWSER), s.fullCloses)
        assertEquals("the browser got none; Utility got its own Back", listOf(MimirOverlayKind.BRIGHTNESS), s.cancels)
        c.assertClosed()
        assertEquals(listOf(MimirOverlayKind.BRIGHTNESS), s.entries)
        assertEquals("openUtility", s.settled.last())
    }

    @Test
    fun `a browser inside its search is closed entirely too, not peeled one layer per press`() {
        // The browser's CANCEL peels one inner layer per press (keyboard, search, then up a folder); the full close does
        // not go through CANCEL at all, so no inner layer can absorb the command.
        val c = Case()
        val s = c.harness(MimirOverlayKind.FOLDER_BROWSER)
        c.browser.openSearch()
        assertEquals("the search is the active scope", c.browser.searchScope, c.authority.state.value.scope)

        s.pressMNavigation()

        assertEquals(listOf(MimirOverlayKind.FOLDER_BROWSER), s.fullCloses)
        assertEquals(emptyList<MimirOverlayKind>(), s.cancels)
        assertNull(s.top)
        c.assertClosed()
    }

    @Test
    fun `without a full close a browser inside its search only loses the search, and the command lands beneath it`() {
        val c = Case()
        val s = c.harness(MimirOverlayKind.FOLDER_BROWSER, withFullClose = false)
        c.browser.openSearch()

        s.pressMNavigation()

        assertEquals("the one CANCEL closed the search and nothing more", "/root/Games", c.browser.currentPath)
        assertEquals(c.browser.scope, c.authority.state.value.scope)
        assertEquals(MimirOverlayKind.FOLDER_BROWSER, s.top)
        assertEquals("applyMButtonHomeNavigation", s.settled.last())
    }

    // ── what the rule does not reach ───────────────────────────────────────────────────────────────────

    @Test
    fun `Get art and a settled fetch card close on the one CANCEL the sweep sends them`() {
        val s = OverlayStackHarness(
            MimirOverlayKind.ARTWORK_GET,
            MimirOverlayKind.ARTWORK_FETCH,
            yielding = setOf(MimirOverlayKind.ARTWORK_GET, MimirOverlayKind.ARTWORK_FETCH),
        )

        s.pressMNavigation()

        assertEquals(listOf(MimirOverlayKind.ARTWORK_GET, MimirOverlayKind.ARTWORK_FETCH), s.cancels)
        assertEquals("no full-close step is needed for them", emptyList<MimirOverlayKind>(), s.fullCloses)
        assertNull(s.top)
    }

    @Test
    fun `a fetch or lookup still in flight declines CANCEL, so it is the one kept surface the commands cannot close`() {
        // Recorded as a limit, not a decision: cancelOrCloseFetch on a Running fetch only starts the cancel (Stopping),
        // and the job posts its own Cancelled summary later, from five write sites. Closing the card under a live job
        // would let one of them bring it back, so the sweep does what Back always did and the command lands beneath it.
        val s = OverlayStackHarness(MimirOverlayKind.ARTWORK_FETCH)

        s.pressMUtility()

        assertEquals("one CANCEL, which is the cancel request", listOf(MimirOverlayKind.ARTWORK_FETCH), s.cancels)
        assertEquals(MimirOverlayKind.ARTWORK_FETCH, s.top)
    }

    @Test
    fun `quick launch is unchanged with the full close installed`() {
        val c = Case()
        val s = c.harness(MimirOverlayKind.FOLDER_BROWSER)

        assertEquals(QuickLaunchInPlaceOutcome.OPENED, s.press())

        assertEquals("the browser is neither closed nor stepped back", emptyList<MimirOverlayKind>(), s.fullCloses)
        assertEquals(emptyList<MimirOverlayKind>(), s.cancels)
        assertEquals("/root/Games", c.browser.currentPath)
        assertEquals(0, c.dismissed)
        assertEquals(listOf(MimirOverlayKind.HOME_QUICK_LAUNCH, MimirOverlayKind.FOLDER_BROWSER), s.entries)
    }

    // ── the wiring, at source level ────────────────────────────────────────────────────────────────────

    @Test
    fun `the three call sites are untouched and the factory wires the browser's own full close`() {
        val effect = sourceFile("MimirAppCoordinator.kt").run {
            assertTrue("anchor missing", contains("LaunchedEffect(mButtonNavigationRequest?.id)"))
            substringAfter("LaunchedEffect(mButtonNavigationRequest?.id)").substringBefore("val bindings = MimirAppBindings(")
        }
        val utility = effect
            .substringAfter("request.command == MButtonPrimaryCommand.UTILITY ||")
            .substringBefore("runPrimaryMRequestAfterTomeCancel(")
        val navigation = effect.substringAfter("runPrimaryMRequestAfterTomeCancel(homeShelf.cancelTomeReturn) {")

        listOf("Utility/Tweaker" to utility, "Home/Settings" to navigation).forEach { (name, branch) ->
            assertTrue(
                "$name sweeps with no keep set, so a kept surface is closed entirely",
                branch.contains("overlays.dismissConflictingModals()"),
            )
            assertFalse(
                "$name must not pass stopAt or keep",
                Regex("""dismissConflictingModals\((?!\))""").containsMatchIn(branch.substringBefore("MimirColdStartTrace")),
            )
        }
        assertTrue(
            "Home and Settings: the sweep runs before Utility is closed and before the navigation",
            navigation.indexOf("overlays.dismissConflictingModals()") < navigation.indexOf("core.brightness.close()") &&
                navigation.indexOf("core.brightness.close()") < navigation.indexOf("navigation.applyMButtonHomeNavigation()"),
        )
        assertTrue(
            "Utility and Tweaker: the sweep runs before Utility opens",
            utility.indexOf("overlays.dismissConflictingModals()") < utility.indexOf("core.brightness.open("),
        )

        val factory = sourceFile("MimirOverlayCoordinator.kt")
        assertTrue(
            "the browser's own full close is the default hook",
            factory.contains("closeFolderBrowser: () -> Unit = FileBrowserStore::close,"),
        )
        assertTrue(
            "and it is wired to the folder browser only",
            factory.contains("closeEntirely = { kind ->") &&
                factory.contains("if (kind == MimirOverlayKind.FOLDER_BROWSER) {") &&
                factory.contains("closeFolderBrowser()"),
        )
        assertTrue(
            "the snapshot reads the store's live value too, so the sweep sees the browser gone at once",
            factory.contains("folderBrowser = folderBrowser() != null && FileBrowserStore.controller.value != null,"),
        )
    }

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
