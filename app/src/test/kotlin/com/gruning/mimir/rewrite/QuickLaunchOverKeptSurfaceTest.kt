package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On a Retroid Pocket Nova (2026-09-30), three presses of the M button's Quick Launch
 * from inside the Files browser each toasted "Quick launch isn't ready yet".
 *
 * `executeQuickLaunchInPlace` swept BEFORE it checked for an obstruction, and the sweep has no rank check of its own: it
 * sent one CANCEL to the folder browser per press, which the browser reads as "close the keyboard", "close the search",
 * or "up one directory" depending on where the player was. Only then did the obstruction check see the browser still on
 * top and refuse. So the press both failed AND moved the player.
 *
 * These tests run the real [MimirOverlayCoordinator] sweep and stack against the same wiring the app coordinator
 * gives [executeQuickLaunchInPlace], so the order, the refusals and what each overlay is sent are the production ones.
 * What they cannot see, stated rather than implied: Compose. That the panel DRAWS over the browser and that the
 * browser's composition survives it is the host's `presented` read, pinned at source level in
 * [MButtonQuickLaunchInPlaceContractTest]; only the device settles the pixels.
 */
class QuickLaunchOverKeptSurfaceTest {

    private val keptKinds = listOf(
        MimirOverlayKind.FOLDER_BROWSER,
        MimirOverlayKind.ARTWORK_GET,
        MimirOverlayKind.ARTWORK_FETCH,
    )

    // ── the panel opens over a kept surface ────────────────────────────────────────────────────────────

    @Test
    fun `over the folder browser the press opens the panel and the browser is never sent a CANCEL`() {
        val s = OverlayStackHarness(MimirOverlayKind.FOLDER_BROWSER)

        assertEquals(QuickLaunchInPlaceOutcome.OPENED, s.press())

        assertEquals("the browser must not receive the press at all", emptyList<MimirOverlayKind>(), s.cancels)
        assertEquals(MimirOverlayKind.HOME_QUICK_LAUNCH, s.top)
        assertEquals(
            listOf(MimirOverlayKind.HOME_QUICK_LAUNCH, MimirOverlayKind.FOLDER_BROWSER),
            s.entries,
        )
    }

    @Test
    fun `the same holds over the Get art and fetch cards`() {
        listOf(
            MimirOverlayKind.ARTWORK_GET,
            MimirOverlayKind.ARTWORK_FETCH,
        ).forEach { flow ->
            val s = OverlayStackHarness(flow)

            assertEquals("$flow", QuickLaunchInPlaceOutcome.OPENED, s.press())

            assertEquals("$flow must not be sent a CANCEL", emptyList<MimirOverlayKind>(), s.cancels)
            assertEquals("$flow", MimirOverlayKind.HOME_QUICK_LAUNCH, s.top)
            assertEquals("$flow keeps being presented beneath the panel", flow, s.coordinator.presented)
        }
    }

    @Test
    fun `closing the panel leaves the same kept surface on top`() {
        keptKinds.forEach { kept ->
            val s = OverlayStackHarness(kept)
            assertEquals(QuickLaunchInPlaceOutcome.OPENED, s.press())

            s.closePanel()

            assertEquals("closing the panel returns to $kept", kept, s.top)
            assertEquals(listOf(kept), s.entries)
            assertEquals("and never touched it on the way", emptyList<MimirOverlayKind>(), s.cancels)
        }
    }

    @Test
    fun `the kept surface keeps being presented while the panel, and its picker, sit on top of it`() {
        val s = OverlayStackHarness(MimirOverlayKind.FOLDER_BROWSER)
        assertEquals(MimirOverlayKind.FOLDER_BROWSER, s.coordinator.presented)

        s.press()
        assertEquals("the panel is on top, the browser is what the host presents", MimirOverlayKind.HOME_QUICK_LAUNCH, s.top)
        assertEquals(MimirOverlayKind.FOLDER_BROWSER, s.coordinator.presented)

        s.snapshot = s.snapshot.copy(homeQuickLaunchPicker = true)
        assertEquals(MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER, s.top)
        assertEquals("the picker is the panel's child, so the same holds", MimirOverlayKind.FOLDER_BROWSER, s.coordinator.presented)
    }

    @Test
    fun `the kept surface receives no controller input while the panel is up`() {
        keptKinds.forEach { kept ->
            val s = OverlayStackHarness(kept)
            s.press()

            ControllerAction.entries.forEach { action -> s.coordinator.dispatch(action) }

            assertTrue(
                "every action must be routed to the panel, never to $kept underneath it",
                s.received.all { (kind, _) -> kind == MimirOverlayKind.HOME_QUICK_LAUNCH },
            )
            assertEquals(ControllerAction.entries.size, s.received.size)
        }
    }

    // ── what lies beneath the kept surface is part of where the player returns to ──────────────────────

    @Test
    fun `Files opened from Utility keeps Utility beneath it, so Back out of Files still lands on Utility`() {
        // Files' only door is the Utility row, and the browser deliberately does not close Utility
        // (AppBrightnessCoordinator.openFilesManager). The log of walk-32k shows the press closing it anyway.
        val s = OverlayStackHarness(MimirOverlayKind.FOLDER_BROWSER, MimirOverlayKind.BRIGHTNESS)

        assertEquals(QuickLaunchInPlaceOutcome.OPENED, s.press())

        assertFalse("Utility must not be closed beneath the browser", "closeUtility" in s.settled)
        assertFalse("nor the transient menus", "hardCloseMenus" in s.settled)
        assertEquals(
            listOf(
                MimirOverlayKind.HOME_QUICK_LAUNCH,
                MimirOverlayKind.FOLDER_BROWSER,
                MimirOverlayKind.BRIGHTNESS,
            ),
            s.entries,
        )
        s.closePanel()
        assertEquals(MimirOverlayKind.FOLDER_BROWSER, s.top)
        s.snapshot = s.snapshot.withOverlay(MimirOverlayKind.FOLDER_BROWSER, on = false)
        assertEquals("Back out of Files returns to Utility", MimirOverlayKind.BRIGHTNESS, s.top)
    }

    @Test
    fun `with no kept surface the press still settles Utility and the Home menus as it always did`() {
        val s = OverlayStackHarness(MimirOverlayKind.BRIGHTNESS, MimirOverlayKind.HOME_TILE_MENU)
        // BRIGHTNESS is below HOME_QUICK_LAUNCH, HOME_TILE_MENU below that; both must be gone before the open.
        assertEquals(QuickLaunchInPlaceOutcome.OPENED, s.press())

        assertEquals(listOf("hardCloseMenus", "cancelTomeReturn", "disarmLaunchReturns", "closeUtility", "openPanel"), s.settled)
        assertEquals(listOf(MimirOverlayKind.HOME_QUICK_LAUNCH), s.entries)
    }

    // ── what still refuses, and what it says ───────────────────────────────────────────────────────────

    private val refusing = listOf(
        MimirOverlayKind.RETURN_COVER,
        MimirOverlayKind.COMPANION_PERMISSION,
        MimirOverlayKind.MIGRATION,
        MimirOverlayKind.CONTROLLER_MAPPING,
        MimirOverlayKind.CONTROLLER_SHORTCUT_CAPTURE,
        MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER,
    )

    /** The text-entry modals and the ScreenScraper sign-in (credential entry, not a kept surface: lead 2026-09-30). */
    private val textEntry = listOf(
        MimirOverlayKind.PROFILE_NAME,
        MimirOverlayKind.STEAMGRIDDB_KEY,
        MimirOverlayKind.RETROACHIEVEMENTS_KEY,
        MimirOverlayKind.GAME_TITLE_EDIT,
        MimirOverlayKind.ARTWORK_CONNECT,
    )

    @Test
    fun `an overlay that declines CANCEL still refuses the press, with a browser beneath it left untouched`() {
        (refusing + textEntry).forEach { blocker ->
            val s = OverlayStackHarness(blocker, MimirOverlayKind.FOLDER_BROWSER) // yielding is empty: the blocker declines CANCEL

            assertEquals("$blocker", QuickLaunchInPlaceOutcome.REFUSED_OBSTRUCTED, s.press())

            assertFalse("$blocker: the panel must not open buried", "openPanel" in s.settled)
            assertEquals("$blocker stays on top", blocker, s.top)
            assertFalse(
                "$blocker: a refusal must not reach past it to the browser",
                MimirOverlayKind.FOLDER_BROWSER in s.cancels,
            )
            assertEquals(
                "$blocker refuses with the copy for what retrying cannot cure, except the self-clearing cover",
                if (blocker == MimirOverlayKind.RETURN_COVER) QuickLaunchNotReadyCopy else QuickLaunchBlockedCopy,
                quickLaunchObstructionCopy(s.top),
            )
        }
    }

    @Test
    fun `a text-entry modal that yields to CANCEL is dismissed and the panel opens over the browser beneath it`() {
        // Unchanged behaviour, recorded because the brief called these "refused": they are refused only while CANCEL
        // fails to close them (accepted as it stands, lead 2026-09-30). The ScreenScraper sign-in is in this group:
        // its Back is closeConnect, which always closes, so the sweep dismisses it and the panel opens over what is
        // beneath. It is never presented beneath the panel.
        textEntry.forEach { modal ->
            val s = OverlayStackHarness(modal, MimirOverlayKind.FOLDER_BROWSER, yielding = setOf(modal))

            assertEquals("$modal", QuickLaunchInPlaceOutcome.OPENED, s.press())

            assertEquals("$modal is sent its own Back; the browser is not", listOf(modal), s.cancels)
            assertEquals(MimirOverlayKind.HOME_QUICK_LAUNCH, s.top)
        }
    }

    @Test
    fun `the cold-start restore keeps the retry sentence and every other refusal stops promising a retry`() {
        assertEquals(QuickLaunchNotReadyCopy, quickLaunchObstructionCopy(null))
        assertEquals(QuickLaunchNotReadyCopy, quickLaunchObstructionCopy(MimirOverlayKind.RETURN_COVER))
        MimirOverlayKind.entries
            .filter { mimirOverlayOutranksQuickLaunch(it) && it != MimirOverlayKind.RETURN_COVER }
            .forEach { kind ->
                val copy = quickLaunchObstructionCopy(kind)
                assertEquals("$kind", QuickLaunchBlockedCopy, copy)
                assertFalse("$kind: a retry does not cure this, so the copy must not say it does", "Try again" in copy)
            }
    }

    // ── the old ordering, and focus ────────────────────────────────────────────────────────────────────

    @Test
    fun `the old sweep moved a browser at depth up a directory, which is what the three presses did`() {
        val authority = DefaultFocusAuthority()
        val browser = browserAtDepth(authority)
        val coordinator = MimirOverlayCoordinator(
            snapshot = { snapshotOf(MimirOverlayKind.FOLDER_BROWSER) },
            dispatchTop = { _, action -> dispatchFolderBrowser(browser, action) },
        )

        // executeQuickLaunchInPlace's sweep as it was before walk-32k: no keep set, so the browser was sent CANCEL
        // before the obstruction check ran, and refused only afterwards.
        coordinator.dismissConflictingModals(stopAt = MimirOverlayKind.HOME_QUICK_LAUNCH)

        assertEquals("one sweep step is one player-visible move", "/root", browser.currentPath)
    }

    @Test
    fun `the press over a browser at depth leaves the same folder and gives focus back intact when the panel closes`() {
        val authority = DefaultFocusAuthority()
        var dismissed = 0
        val browser = browserAtDepth(authority) { dismissed += 1 }
        val focusBefore = authority.state.value.focused
        assertNotNull("the browser has a focused tile to lose", focusBefore)
        val s = OverlayStackHarness(MimirOverlayKind.FOLDER_BROWSER, browser = { action -> dispatchFolderBrowser(browser, action) })

        assertEquals(QuickLaunchInPlaceOutcome.OPENED, s.press())
        // What HomeQuickLaunchOverlay's DisposableEffect does when it mounts: its scope goes on top of the browser's.
        authority.pushScope(HomeQuickLaunchScope)
        authority.registerVirtual(homeQuickLaunchSlotKey(0), HomeQuickLaunchScope, FocusBounds(0f, 0f, 400f, 90f))
        authority.requestFocus(homeQuickLaunchSlotKey(0))

        assertEquals("/root/Games", browser.currentPath)
        assertEquals(0, dismissed)
        assertEquals(HomeQuickLaunchScope, authority.state.value.scope)
        assertFalse(
            "the browser is beneath the panel's scope, so it cannot act on a press",
            browser.dispatch(ControllerAction.CANCEL),
        )
        assertEquals("a refused press moved nothing", "/root/Games", browser.currentPath)

        // navigation.closeHomeQuickLaunch(): the picker scope, then the panel's, discarded; the flag goes.
        unwindHomeQuickLaunchFocus(authority, includeQuickLaunch = true)
        s.closePanel()

        assertEquals(MimirOverlayKind.FOLDER_BROWSER, s.top)
        assertEquals(browser.scope, authority.state.value.scope)
        assertEquals("focus is exactly where the player left it", focusBefore, authority.state.value.focused)
        assertEquals("/root/Games", browser.currentPath)
        assertTrue("and the browser answers again", browser.dispatch(ControllerAction.CANCEL))
        assertEquals("/root", browser.currentPath)
    }
}
