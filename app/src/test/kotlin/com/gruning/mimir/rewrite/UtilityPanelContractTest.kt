package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gates for three fixes of 2026-08-07 that shipped with no coverage at all — the rail scroll keying
 * (`78a0b82a`), the tool-panel focus landing (`22326230`), and moving the frame-cap telemetry read
 * off the input path (`226ed7e1`). Each passed the full suite both before and after its fix.
 *
 * Two of the three are Compose-lifetime properties: *which* `remember` holds the scroll state, and
 * *when* an effect re-runs. There is no Compose harness in this module to observe either, so those
 * are asserted against source, like the caps gate in [OverlayConsistencyContractTest]. A source
 * assertion is weaker than a behavioural one and it is worth being honest about the gap: it pins the
 * mechanism the fix used, not the user-visible outcome. If someone achieves the same outcome another
 * way, these fail and should be updated — deliberately, which is the point.
 */
class UtilityPanelContractTest {

    // ── 78a0b82a — the rail keeps no scroll position ────────────────────────────────────────────
    /**
     * Two ways an offset used to survive. Across the surface flip: `open()` changes `surface` with
     * `isOpen` still true, so the overlay never leaves composition. Across a close/reopen:
     * `rememberSaveable` restores through the saved-state registry. A tool panel opens at the top, so
     * the state must be keyed on surface AND must not be saveable — plain `remember` is discarded
     * when the panel closes, which is exactly the wanted behaviour.
     */
    @Test
    fun `the rail scroll state is keyed on surface and never saveable`() {
        val source = normalized(read("BrightnessOverlay.kt"))

        assertTrue(
            "the rail scroll must be remember(surface) { ScrollState(0) } — an unkeyed state carries " +
                "the Utility offset into the Tweaker rail, which is corrections §5",
            source.contains("val railScroll = remember(surface) { ScrollState(0) }"),
        )
        assertTrue(
            "rememberSaveable restores the offset on reopen, so the panel reopens where it was left",
            !source.contains("rememberSaveable") || !source.contains("ScrollState.Saver"),
        )
        assertTrue(
            "rememberScrollState() is unkeyed; it is what shipped the defect",
            !source.contains("val railScroll = rememberScrollState()"),
        )
    }

    // ── 22326230 — a tool panel opens at its first option ───────────────────────────────────────
    /**
     * The behavioural half — WHICH row each surface lands on — lives in
     * [UtilityControlOrderTest], next to the `state()` fixture it needs.
     *
     * The landing rule must live where it can actually take effect. `FocusAuthority.requestFocus`
     * silently drops a key that is not registered (`DefaultFocusAuthority.kt:113`), and when `open()`
     * runs, the incoming surface has not composed — so a request made there is a no-op and focus stays
     * on the one control registered across the swap, the footer's CLOSE. It has to be an effect keyed
     * on `surface`, in the host, after composition.
     */
    @Test
    fun `the landing rule is a surface-keyed effect in the host, not in open`() {
        val host = normalized(read("AppBrightnessHost.kt"))
        assertTrue(
            "the landing effect must be keyed on surface so it re-runs on open and on every flip",
            host.contains("LaunchedEffect(authority, surface, focusEnabled)"),
        )
        assertTrue(
            "the landing effect must focus whatever brightnessLandingControl names for this surface. " +
                "It used to read `visibleControls.firstOrNull()`, which was the same thing while the " +
                "landing row and the first row were the same row. They stopped being the same on " +
                "2026-08-15, when the Tweaker and Files routes took the head of the Utility rail: he " +
                "defines where the rows SIT, not where the panel LANDS, so the two had to be separated " +
                "rather than one silently dragging the other.",
            host.contains("brightnessLandingControl(visibleControls, surface)") &&
                host.contains("?.let { authority.requestFocus("),
        )

        val coordinator = normalized(read("AppBrightnessCoordinator.kt"))
        assertTrue(
            "open() must not also claim the landing rule — two writers for one behaviour, and its " +
                "request is a no-op anyway because the incoming surface has not registered yet",
            !coordinator.contains("if (!wasOpen ||"),
        )
    }

    // ── 226ed7e1 — no telemetry IPC on the input path ───────────────────────────────────────────
    /**
     * `tweakerFpsCapRow` was a getter calling `TweakerBridge.attestedForegroundGamePackage()` →
     * `session.getTelemetry()`, a cross-process call. `dispatch()` evaluates it eagerly to build the
     * focus order, so EVERY controller press paid for it — measured at ~152 ms on an Odin 3, on both
     * surfaces. It must stay a published snapshot refreshed off the main thread.
     */
    @Test
    fun `the frame-cap row is published state, never read live on the input path`() {
        val source = normalized(read("AppBrightnessCoordinator.kt"))

        assertTrue(
            "must be a published snapshot; a getter puts a cross-process read on every controller press",
            source.contains("internal var tweakerFpsCapRow by mutableStateOf"),
        )
        assertTrue(
            "a `val ... get()` form is the shape that shipped the 152 ms input cost",
            !source.contains("internal val tweakerFpsCapRow: TweakerFpsCapRow get()"),
        )
        assertEquals(
            "the attested-foreground read must have exactly one call site, inside the off-thread " +
                "refresh — a second one is a live read someone put back on a hot path",
            1,
            Regex("TweakerBridge\\.attestedForegroundGamePackage\\(\\)").findAll(source).count(),
        )
        assertTrue(
            "the refresh must hop off the main thread before reading telemetry",
            source.contains("withContext(privilegedIoDispatcher) { readTweakerFpsCapRow() }"),
        )
    }

    /** Collapses runs of whitespace so formatting changes do not fail these. */
    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun read(fileName: String): String {
        val file = listOf(
            File("src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
            File("../app/src/main/kotlin/com/gruning/mimir/rewrite/$fileName"),
        ).firstOrNull { it.isFile }
        assertTrue("$fileName not reachable from the test working directory", file != null)
        return file!!.readText()
    }
}
