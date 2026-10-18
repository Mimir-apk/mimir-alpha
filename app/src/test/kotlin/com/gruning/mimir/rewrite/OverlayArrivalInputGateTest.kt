package com.gruning.mimir.rewrite

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Input gating for overlays on the page lead: the direct overlays' pixel-fade host and the game
 * card hold the page's input gate from a cold arrival until it first paints, so a double-tap A cannot launch from a
 * card still at alpha 0. The tile menu and the Folio's panel hold it inside rememberMimirGlassPanelProgress (core-ui's
 * MimirPanelArrivalInputGateTest).
 *
 * Alpha audit F1a (2026-09-30): Quick Launch's dock holds it from a cold arrival until it is above 15%
 * ([com.gruning.mimir.rewrite.core.ui.MimirPageMotion.ShownAlpha], the policy for the dock and its picker only),
 * because its first pinned slot is focused the frame it mounts and first paint is a 6% trace.
 *
 * Alpha audit F3 (2026-09-30): its picker takes the same 15% for the pad and a touch shield for the touch, and arrives on
 * the step tier instead of cutting in. The picker's host takes the app's bindings, which the JVM cannot build, so its
 * shape is pinned here as source, and where its arrival starts is proved in HomeQuickLaunchPickerStartAlphaTest.
 */
class OverlayArrivalInputGateTest {

    private val hold = "if (incomingAlpha.value <= 0f) holdMimirInputUntilShown(incomingAlpha) { incomingAlpha.value > 0f }"

    private fun host(): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
    ).first(File::isFile).readText()

    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        return substringAfter(after).substringBefore(before)
    }

    @Test
    fun `the pixel-fade host holds a cold arrival before it waits out the lead`() {
        val code = host()
        assertTrue(code.contains("import com.gruning.mimir.rewrite.core.ui.holdMimirInputUntilShown"))
        val pixelHost = code.sliceBetween("private fun <T : Any> MimirPixelFadeHost(", "private fun MimirDirectOverlayPixelFadeHost(")
        assertTrue(pixelHost.contains(hold))
        assertTrue(pixelHost.indexOf(hold) < pixelHost.indexOf("val lead = maxOf(holdMs, leadMs)"))
    }

    @Test
    fun `Quick Launch holds presses and touches until it is above 15 percent, on the specs it paints with`() {
        val dock = host().sliceBetween("private fun HomeQuickLaunchOverlayHost(", "private fun HomeQuickLaunchPickerOverlayHost(")
        assertTrue(
            "the gate probes the fade's own two specs and waits for the readable 15%, not first paint",
            dock.contains("MimirSwapPageGate(mimirRingSafeFadeAlpha(enterSpec, exitSpec), MimirPageMotion.ShownAlpha)"),
        )
        assertTrue("the paint runs the same two specs", dock.contains("else fadeIn(enterSpec),") && dock.contains("exit = fadeOut(exitSpec),"))
        assertTrue("both specs come from the one function each", dock.contains("val enterSpec = homeQuickLaunchEnterSpec(quickLaunchReducedMotion)") && dock.contains("val exitSpec = homeQuickLaunchExitSpec()"))
        assertTrue(
            "the gate sits inside the open generation's key, so a reopen remounts the panel and its focus scope",
            dock.indexOf("key(bindings.homeQuickLaunch.openEpoch)") in 0 until dock.indexOf("MimirSwapPageGate("),
        )
        assertTrue("and its Modifier is on a box around the whole overlay", dock.contains("Box(Modifier.fillMaxSize().then(pageInput)) {"))
        assertFalse("no hold of the card's first-paint kind: that would free at about 141 ms, at a trace", dock.contains("holdMimirInputUntilShown"))
    }

    @Test
    fun `the Quick Launch picker holds presses and touches until it is above 15 percent, and arrives on the step tier`() {
        val code = host()
        val picker = code.sliceBetween("internal fun HomeQuickLaunchPickerFadeHost(", "internal fun homeQuickLaunchPickerStartAlpha(")
        val thin = code.sliceBetween("private fun HomeQuickLaunchPickerOverlayHost(", "internal fun HomeQuickLaunchPickerFadeHost(")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "the app's host only reads the mode and hands the live content in",
            thin.contains("HomeQuickLaunchPickerFadeHost( activeMode = bindings.homeQuickLaunch.pickerMode.takeIf { visible }, reducedMotion = reducedMotion, ) { mode -> HomeQuickLaunchPickerLiveContent(bindings, mode) }"),
        )
        val arrival = picker.sliceBetween(
            "LaunchedEffect(activeMode, frame.epoch) {",
            "LaunchedEffect(activeMode, frame.epoch, frame.outgoingLayerIndex) {",
        )
        val shown = "holdMimirInputUntilShown(incomingAlpha) { incomingAlpha.value > MimirPageMotion.ShownAlpha }"
        assertTrue("the pad waits for the readable 15%", arrival.contains(shown))
        assertTrue("and only an arrival that starts at alpha 0 holds", arrival.contains("if (incomingAlpha.value <= 0f) {\n                $shown"))
        assertFalse("not the first-paint form: a trace is not a picker", arrival.contains("{ incomingAlpha.value > 0f }"))
        assertTrue("held before the fade is started", arrival.indexOf(shown) < arrival.indexOf("incomingAlpha.animateTo("))
        assertTrue(
            "the lead is the step tier's and the fade is its OverlayMs, as the close is",
            arrival.contains("HomeOverlayDissolveMs,") &&
                arrival.contains("delayMillis = if (incomingAlpha.value <= 0f) MimirMotion.OverlayDissolveCoverMs else 0,"),
        )

        assertTrue(
            "the touch half is a derived state on the same 15%, decided once per arrival, so the fade does not recompose the host",
            picker.contains("val shieldTouches by remember(activeMode, frame.epoch) {") &&
                picker.contains("val arrivesCold = incomingStartAlpha <= 0f") &&
                picker.contains("derivedStateOf { arrivesCold && incomingAlpha.value <= MimirPageMotion.ShownAlpha }"),
        )
        val shield = "if (shieldTouches) {\n            Box(Modifier.fillMaxSize().mimirPageTouchShield())"
        assertTrue("a sibling shield, not a modifier on the panel, so the scrim's own tap is stopped too", picker.contains(shield))
        assertTrue(
            "laid last, so it is hit first",
            picker.indexOf("if (shieldTouches) {") > picker.indexOf("drawLayer(outgoingLayer)"),
        )

        assertTrue("the start comes from the one function", picker.contains("val incomingStartAlpha = homeQuickLaunchPickerStartAlpha("))
        assertTrue("and reduced motion reaches it from the host's single read", picker.contains("reducedMotion = reducedMotion,"))
        assertFalse("the cut is gone", picker.contains("activeMode == null || frame.outgoingLayerIndex == null -> 1f"))
        assertTrue(
            "a cold null-to-picker entry still records no outgoing pixels (the ghost cache on older renderers)",
            picker.contains("epochs.prepare(activeMode) { from, to -> from != null && from != to }"),
        )
        assertTrue("the close is the same OverlayMs clock, scaled to the share a picker caught mid-dissolve has left", picker.contains("tween(mimirLingeringDepartMs(HomeOverlayDissolveMs, outgoingAlpha.value), easing = MimirMotion.Easing),"))

        val overlayHost = code.sliceBetween("private fun MimirAppOverlayHost(", "private fun <T : Any> MimirPixelFadeHost(")
            .replace(Regex("\\s+"), " ")
        assertTrue(
            "the overlay host hands the picker its threaded reducedMotion, not a second read",
            overlayHost.contains(
                "HomeQuickLaunchPickerOverlayHost( bindings, visible = active == MimirOverlayKind.HOME_QUICK_LAUNCH_PICKER, " +
                    "reducedMotion = reducedMotion, )",
            ),
        )
    }

    @Test
    fun `the picker's root carries no dead graphics layer`() {
        val overlay = listOf(
            File("app/src/main/kotlin/com/gruning/mimir/rewrite/HomeQuickLaunchPickerOverlay.kt"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/HomeQuickLaunchPickerOverlay.kt"),
        ).first(File::isFile).readText()
        assertFalse("a layer that pins alpha at its default recorded every frame for nothing", overlay.contains("graphicsLayer"))
    }

    @Test
    fun `the game card holds presses from its first frame until it paints`() {
        val card = host()
            .sliceBetween("private fun HomeLaunchCardOverlayHost(", "private fun HomeLaunchCardLiveContent(")
            .sliceBetween("LaunchedEffect(target, frame.epoch) {", "LaunchedEffect(target, frame.epoch, frame.outgoingLayerIndex) {")
        assertTrue(card.contains(hold))
        assertTrue("held before the frame beat", card.indexOf(hold) < card.indexOf("withFrameNanos { }"))
    }
}
