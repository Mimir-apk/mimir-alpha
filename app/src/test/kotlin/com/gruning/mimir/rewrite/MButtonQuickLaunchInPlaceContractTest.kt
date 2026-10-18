package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BUG-1 wiring contracts (Quick Launch opens IN PLACE, both entry paths,
 * no carve-outs), house source-contract pattern — the routing lives in a `LaunchedEffect` and the
 * navigation coordinator is not JVM-constructible. The settle SEQUENCE itself has a real unit test
 * ([QuickLaunchInPlaceExecutionTest]); these pin only that the effect is wired to it.
 *
 * Hardened per the 2026-08-11 review's three brittleness findings: every anchor's presence is
 * asserted before slicing (substringAfter silently returns the whole input on a missing anchor),
 * negative contracts run on comment-stripped source (a comment naming a banned identifier must not
 * fail the suite), and the destination contract matches the assignment token, not the substring of
 * `==` comparisons.
 */
class MButtonQuickLaunchInPlaceContractTest {

    @Test
    fun `M command effect routes QUICK_LAUNCH through the in-place executor, never navigation`() {
        val source = appCoordinatorSource()
        val effect = source.sliceBetween(
            "LaunchedEffect(mButtonNavigationRequest?.id)",
            "val bindings = MimirAppBindings(",
        )
        val quickLaunchBranch = effect.sliceBetween(
            "request.command == MButtonPrimaryCommand.QUICK_LAUNCH",
            "request.command == MButtonPrimaryCommand.UTILITY",
        )
        val code = quickLaunchBranch.withoutLineComments()

        assertTrue(
            "the QUICK_LAUNCH branch must run the tested in-place executor",
            code.contains("executeQuickLaunchInPlace("),
        )
        assertTrue(
            "the executor's open must be the rail-reference in-place open",
            code.contains("openPanel = navigation::openHomeQuickLaunch"),
        )
        // Each settle lambda must be wired to the real state, not stubs — the executor test pins
        // the sequence, these pin what the sequence acts ON (each names its review defect).
        listOf(
            "cancelTomeReturn = homeShelf.cancelTomeReturn",
            "navigation.disarmLibraryLaunchReturn()",
            "core.libraryViewModel.clearPendingLaunchReturnTarget()",
            "nativeShelf.supersedeLaunchReturn()",
            "navigation.homeTileMenu = null",
            "navigation.homeTomeLibrary = null",
            "navigation.platformTileMenu = null",
            "closePicker = navigation::closeHomeQuickLaunchPicker",
            "startupOriginApplied = navigation.startupOriginApplied",
            "mimirOverlayOutranksQuickLaunch",
            "stopAt = MimirOverlayKind.HOME_QUICK_LAUNCH",
            // walk-32k: the sweep is told which overlays it may not CANCEL, and the executor is told whether one is
            // live. QuickLaunchOverKeptSurfaceTest wires these same three lambdas and exercises them.
            "keep = ::mimirOverlayStaysUnderQuickLaunch",
            "keptSurfaceLive = {",
            "overlays.stack.entries.any(::mimirOverlayStaysUnderQuickLaunch)",
        ).forEach { wiring ->
            assertTrue("settle wiring missing: $wiring", code.contains(wiring))
        }
        assertFalse(
            "QUICK_LAUNCH must never reach the navigation path (owner: it moved him to Home)",
            code.contains("applyMButtonHomeNavigation"),
        )
        assertFalse(
            "QUICK_LAUNCH must not assign the destination",
            Regex("""\bdestination\s*=(?!=)""").containsMatchIn(code),
        )
    }

    /**
     * walk-32k. The panel draws in its own band above the direct-overlay host, and the host's target was the TOP
     * overlay: with the panel on top it would have been null, which disposes the folder browser (its own
     * DisposableEffect closes it in FileBrowserStore) and clears the scene glass behind a still-translucent modal.
     * Composition cannot be driven here, so the two reads that keep the kept surface presented are pinned at source.
     */
    @Test
    fun `the host keeps presenting a kept surface beneath the panel`() {
        val host = sourceFile("MimirAppHost.kt")

        val overlayHost = host.sliceBetween("private fun MimirAppOverlayHost(", "MimirDirectOverlayPixelFadeHost(bindings, directTarget")
        assertTrue("anchor missing: val directTarget = active", overlayHost.contains("val directTarget = active"))
        val directTarget = overlayHost.substringAfter("val directTarget = active")
        assertTrue(
            "the direct host's target must be the presented overlay, not the top",
            directTarget.contains("bindings.overlays.presented"),
        )
        assertTrue(
            "and it still only presents kinds that host really draws",
            directTarget.contains("takeIf(::mimirDirectOverlayUsesPixelFade)"),
        )

        val chrome = host.sliceBetween("val activeOverlay = bindings.overlays.top", "val reducedMotion = mimirReducedMotion()")
        assertTrue(
            "the scene glass follows the presented overlay, or it would clear behind the browser when the panel arrives",
            chrome.contains("val chromeOverlay = bindings.overlays.presented"),
        )
        val chromeEffect = host.sliceBetween(
            "var presentedOverlayChrome by remember { mutableStateOf(chromeOverlay) }",
            "val outgoingDuration = mimirOutgoingChromeHoldMs(presentedOverlayChrome)",
        )
        assertTrue(chromeEffect.contains("LaunchedEffect(chromeOverlay, bindings.homeLaunchCard.closing)"))
        assertTrue(chromeEffect.contains("presentedOverlayChrome = chromeOverlay"))
        assertTrue(
            "input and Back still follow the real top",
            host.contains("MimirAppOverlayHost(bindings, activeOverlay, reducedMotion)"),
        )
        assertTrue(
            "the browser's own composition arm is still where the direct host draws it",
            host.contains("MimirOverlayKind.FOLDER_BROWSER -> MimirFolderBrowserContent(bindings)"),
        )
    }

    @Test
    fun `the navigate path serves HOME only and no longer opens Quick Launch`() {
        val source = navigationCoordinatorSource()
        val body = source.sliceBetween("fun applyMButtonHomeNavigation(", "fun onShelfQuickAction(")
        val code = body.withoutLineComments()

        assertFalse(
            "the navigate path must not open Quick Launch — that pairing dragged him Home",
            code.contains("openHomeQuickLaunch()"),
        )
        assertTrue(
            "HOME still navigates and closes any open panel on the way",
            Regex("""\bdestination\s*=(?!=)\s*MimirDestination\.HOME""").containsMatchIn(code) &&
                code.contains("closeHomeQuickLaunch()"),
        )
    }

    @Test
    fun `the shelf quick rail reference behaviour the owner picked is unchanged`() {
        val rail = navigationCoordinatorSource()
            .sliceBetween("fun onShelfQuickAction(", "fun openSettingsFromCurrent(")
        assertTrue(
            "the rail's in-place open is the shared implementation",
            rail.contains("MimirQuickRailAction.QUICK_LAUNCH -> openHomeQuickLaunch()"),
        )
    }

    @Test
    fun `reopening bumps the epoch and the host keys the panel on it`() {
        val open = navigationCoordinatorSource()
            .sliceBetween("fun openHomeQuickLaunch()", "fun openHomeQuickLaunchGeneral()")
        assertTrue(
            "every open must advance the generation (fade-window remount, 2026-08-11 review)",
            open.contains("homeQuickLaunchEpoch += 1"),
        )
        val host = sourceFile("MimirAppHost.kt")
            .sliceBetween("private fun HomeQuickLaunchOverlayHost(", "private fun HomeQuickLaunchPickerOverlayHost(")
        assertTrue(
            "the overlay host must key the panel composition on the open generation",
            host.contains("key(bindings.homeQuickLaunch.openEpoch)"),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole input. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun String.withoutLineComments(): String =
        lineSequence().joinToString("\n") { it.substringBefore("//") }

    private fun appCoordinatorSource(): String = sourceFile("MimirAppCoordinator.kt")

    private fun navigationCoordinatorSource(): String = sourceFile("MimirNavigationCoordinator.kt")

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
