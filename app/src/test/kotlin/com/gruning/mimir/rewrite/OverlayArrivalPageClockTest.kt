package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirArrivalFrameCapMs
import com.gruning.mimir.rewrite.core.ui.MimirCrossSwapMs
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion audit 2026-09-28, batch F: an overlay's panel arrives on the
 * page clock, [MimirMotion.ShineMs] from the [MimirMotion.OverlayDissolveCoverMs] lead, and still leaves on
 * [MimirMotion.OverlayMs]. The one exception is the Tome Library arriving on a Folio RETURN, which keeps today's pace:
 * CP02's Folio teardown waits on that arrival settling, and 670 ms there would hold the Folio's pixels over the Library.
 */
class OverlayArrivalPageClockTest {

    private fun host(): String = listOf(
        File("app/src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
        File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt"),
    ).first(File::isFile).readText()

    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", contains(before))
        return substringAfter(after).substringBefore(before)
    }

    @Test
    fun `every direct overlay but the three Appearance pages arrives on the slow clock from the lead`() {
        (MimirOverlayKind.entries + null).filter { it !in AppearanceSiblings }.forEach { kind ->
            assertEquals(
                "$kind arrives over ShineMs",
                MimirMotion.ShineMs,
                mimirIncomingOverlayArriveMs(kind, homeTomeLibraryOpenOrigin = null, reducedMotion = false),
            )
        }
        assertEquals(
            "the Tome Library opened from the shelf keeps its CP01 lead and takes the slow arrival",
            MimirMotion.ShineMs,
            mimirIncomingOverlayArriveMs(
                MimirOverlayKind.HOME_TOME_LIBRARY,
                HomeTomeLibraryOpenOrigin.HOME_SHELF,
                reducedMotion = false,
            ),
        )
        assertEquals(MimirMotion.OverlayDissolveCoverMs, mimirIncomingOverlayLeadMs(reducedMotion = false))
    }

    private val AppearanceSiblings = setOf(MimirOverlayKind.THEME_PICKER, MimirOverlayKind.BACKGROUND_PICKER, MimirOverlayKind.MBUTTON_PICKER)

    /**
     * Audit 2026-10-15, Thor check 7: the Theme gallery, the Background picker and the Mimir-button picker are Settings sub-pages under APPEARANCE, so each arrives on the
     * sub-pages' swap clock, one [MimirCrossSwapMs] with no lead, and leaves on it. [AppearancePickerSwapFrameTest] holds the frames; this holds the policy and that the host feeds it.
     */
    @Test
    fun `the three Appearance pages arrive on the sub-pages' swap clock with no lead, and no other kind does`() {
        assertEquals(MimirMotion.OverlayMs, MimirCrossSwapMs)
        AppearanceSiblings.forEach { kind ->
            assertEquals("$kind arrives on the swap clock", MimirCrossSwapMs, mimirIncomingOverlayArriveMs(kind, null, reducedMotion = false))
            assertEquals("$kind takes no lead", 0, mimirIncomingOverlayLeadMs(reducedMotion = false, kind = kind))
            assertEquals("$kind leaves on the same clock", MimirCrossSwapMs, mimirDirectOverlayDepartMs(kind, null))
            // None of the three has scene glass (Theme:; the image pickers stand on the same opaque backing): a swap, not a soften-then-arrive.
            assertFalse("$kind draws no scene glass", mimirOverlayUsesGlass(kind))
        }
        // The image browser an Upload opens keeps its own arrival and the picker's hand-over to it (AppearancePickerFilesHandoffTest).
        assertEquals(MimirMotion.ShineMs, mimirIncomingOverlayArriveMs(MimirOverlayKind.FOLDER_BROWSER, null, reducedMotion = false))
        assertEquals(MimirMotion.OverlayDissolveCoverMs, mimirIncomingOverlayLeadMs(reducedMotion = false, kind = MimirOverlayKind.FOLDER_BROWSER))
        val host = host()
        val arrive = host.sliceBetween("internal fun mimirIncomingOverlayArriveMs(", "internal fun mimirIncomingOverlayLeadMs(")
        assertTrue(arrive.contains("kind == MimirOverlayKind.THEME_PICKER || mimirOverlayIsAppearanceImagePicker(kind) -> MimirCrossSwapMs"))
        val lead = host.sliceBetween("internal fun mimirIncomingOverlayLeadMs(", "internal fun mimirOverlayStepArriveMs(")
        assertTrue(lead.contains("kind == MimirOverlayKind.THEME_PICKER || mimirOverlayIsAppearanceImagePicker(kind) -> 0"))
    }

    /**
     * Thor drive 3 (combined45): the three Appearance pages opened in 30 ms, one frame and 50 ms on the device because a long first frame moves the fade's clock by its whole length. The host runs their arrival on
     * `withMimirStallGuard`'s frame clock, and only theirs; [AppearancePickerSwapFrameTest] holds the frames (a 125 ms frame after the clock starts), `MimirStallGuardedClockTest` the clock.
     */
    @Test
    fun `the host runs the Appearance arrival on the guarded frame clock and no other`() {
        val code = host()
        val pixelHost = code.sliceBetween("private fun <T : Any> MimirPixelFadeHost(", "private fun MimirDirectOverlayPixelFadeHost(")
        assertTrue(pixelHost.contains("arrivalStallCapMs: Int = 0,"))
        val guard = pixelHost.indexOf("withMimirStallGuard(arrivalStallCapMs) {")
        assertTrue("the arrival runs inside the guard", guard > 0)
        assertTrue("the whole arrival, hold and fade, is inside it", guard < pixelHost.indexOf("instantIncoming -> incomingAlpha.snapTo(1f)") &&
            guard < pixelHost.indexOf("tween(incomingArriveMs, delayMillis = lead, easing = MimirMotion.Easing)") &&
            guard < pixelHost.indexOf("else -> incomingAlpha.animateTo(1f, tween(incomingArriveMs, easing = MimirMotion.Easing))"))
        assertTrue("and the input gate stays outside it, before the lead", pixelHost.indexOf("holdMimirInputUntilShown(incomingAlpha) { incomingAlpha.value > 0f }") < guard)
        val direct = code.sliceBetween("private fun MimirDirectOverlayPixelFadeHost(", "private fun MimirDirectOverlayContent(")
        assertTrue(direct.contains("arrivalStallCapMs = mimirIncomingOverlayStallCapMs(active?.kind, reducedMotion),"))
        // The Folio host (the other caller) keeps the clock it has.
        assertFalse("no other caller passes a cap", code.split("arrivalStallCapMs = ").size > 2)
        assertEquals(MimirArrivalFrameCapMs, mimirIncomingOverlayStallCapMs(MimirOverlayKind.BACKGROUND_PICKER, reducedMotion = false))
    }

    @Test
    fun `a Folio return to the Tome Library takes no lead, and opening it from Home keeps it`() {
        assertEquals(
            0,
            mimirIncomingOverlayLeadMs(
                reducedMotion = false,
                kind = MimirOverlayKind.HOME_TOME_LIBRARY,
                homeTomeLibraryOpenOrigin = HomeTomeLibraryOpenOrigin.FOLIO_RETURN,
            ),
        )
        assertEquals(
            MimirMotion.OverlayDissolveCoverMs,
            mimirIncomingOverlayLeadMs(
                reducedMotion = false,
                kind = MimirOverlayKind.HOME_TOME_LIBRARY,
                homeTomeLibraryOpenOrigin = HomeTomeLibraryOpenOrigin.HOME_SHELF,
            ),
        )
    }

    @Test
    fun `the Tome Library keeps today's pace on a Folio return, so the Folio's teardown is not held for 670 ms`() {
        assertEquals(
            MimirMotion.OverlayMs,
            mimirIncomingOverlayArriveMs(
                MimirOverlayKind.HOME_TOME_LIBRARY,
                HomeTomeLibraryOpenOrigin.FOLIO_RETURN,
                reducedMotion = false,
            ),
        )
    }

    @Test
    fun `one step of an open panel handing to the next takes the swap tier and an opening the full arrival`() {
        val step = mimirOverlayStepArriveMs(reducedMotion = false)
        assertEquals(MimirMotion.OverlayMs, step)
        assertEquals("310 ms from the press", 310, MimirMotion.OverlayDissolveCoverMs + step)
        assertEquals(0, mimirOverlayStepArriveMs(reducedMotion = true))
        assertEquals(
            "a step hands over on the swap clock",
            MimirMotion.OverlayMs,
            mimirOverlayArrivalClock(handsOver = true, arriveMs = MimirMotion.ShineMs, stepArriveMs = step),
        )
        assertEquals(
            "an overlay opening over the scene keeps the full arrival",
            MimirMotion.ShineMs,
            mimirOverlayArrivalClock(handsOver = false, arriveMs = MimirMotion.ShineMs, stepArriveMs = step),
        )
        assertEquals(
            "a host with no step clock is unchanged",
            MimirMotion.ShineMs,
            mimirOverlayArrivalClock(handsOver = true, arriveMs = MimirMotion.ShineMs, stepArriveMs = null),
        )
        val code = host()
        val direct = code.sliceBetween(
            "private fun MimirDirectOverlayPixelFadeHost(",
            "private fun MimirDirectOverlayContent(",
        )
        assertTrue(direct.contains("stepArriveMs = mimirOverlayStepArriveMs(reducedMotion),"))
        val pixelHost = code.sliceBetween(
            "private fun <T : Any> MimirPixelFadeHost(",
            "private fun MimirDirectOverlayPixelFadeHost(",
        )
        assertTrue(
            "a step is a departing layer that showed a target of its own",
            pixelHost.contains("handsOver = frame.outgoingLayerIndex?.let { layerTargets[it] } != null,"),
        )
        assertTrue("decided once per arrival", pixelHost.contains("val incomingArriveMs = remember(target, frame.epoch) {"))
        val card = code.sliceBetween("private fun HomeLaunchCardOverlayHost(", "private fun HomeLaunchCardLiveContent(")
        assertTrue(card.contains("mimirOverlayArrivalClock(cardHandsOver, MimirMotion.ShineMs, MimirMotion.OverlayMs)"))
    }

    @Test
    fun `reduced motion snaps the arrival`() {
        assertEquals(0, mimirIncomingOverlayArriveMs(MimirOverlayKind.THEME_PICKER, null, reducedMotion = true))
        assertEquals(0, mimirIncomingOverlayLeadMs(reducedMotion = true))
    }

    @Test
    fun `the direct host feeds both to the shared pixel-fade seam, and the exit keeps OverlayMs`() {
        val code = host()
        val direct = code.sliceBetween(
            "private fun MimirDirectOverlayPixelFadeHost(",
            "private fun MimirDirectOverlayContent(",
        )
        assertTrue(direct.contains("durationMs = MimirMotion.OverlayMs,"))
        assertTrue(direct.contains("arriveMs = mimirIncomingOverlayArriveMs("))
        assertTrue(
            direct.contains(
                "leadMs = mimirIncomingOverlayLeadMs(\n" +
                    "            reducedMotion = reducedMotion,\n" +
                    "            kind = active?.kind,\n" +
                    "            homeTomeLibraryOpenOrigin = bindings.navigation.homeTomeLibrary?.openOrigin,\n" +
                    "        ),",
            ),
        )
        val pixelHost = code.sliceBetween(
            "private fun <T : Any> MimirPixelFadeHost(",
            "private fun MimirDirectOverlayPixelFadeHost(",
        )
        assertTrue("the lead and the CP01 hold are one wait", pixelHost.contains("val lead = maxOf(holdMs, leadMs)"))
        assertTrue("only a cold arrival takes it", pixelHost.contains("lead > 0 && incomingAlpha.value <= 0f"))
        // The departing layer leaves on the overlay clock (the file browser included since 2026-10-15, Q3 siblings matched),
        // except an appearance picker's hand-over to the
        // browser, which stands for the browser's lead and dissolves on its arrival clock (AppearancePickerFilesHandoffTest).
        assertTrue(pixelHost.contains("departMs: (from: T?, to: T?) -> Int = { _, _ -> durationMs },"))
        assertTrue(pixelHost.contains("tween(departureMs, delayMillis = hold, easing = MimirMotion.Easing)"))
        assertTrue(pixelHost.contains("val departureMs = departMs(from, target).let { full ->"))
        assertTrue(direct.contains("departMs = { from, to -> mimirDirectOverlayDepartMs(from?.kind, to?.kind, reducedMotion) },"))
    }

    @Test
    fun `the game card, the menu and the Folio's panel take the same clock, the Folio's gates keep the blur ramp`() {
        val code = host()
        val card = code.sliceBetween("private fun HomeLaunchCardOverlayHost(", "private fun HomeLaunchCardLiveContent(")
        val cardArrival = card.sliceBetween(
            "LaunchedEffect(target, frame.epoch) {",
            "LaunchedEffect(target, frame.epoch, frame.outgoingLayerIndex) {",
        )
        assertTrue(cardArrival.contains("MimirMotion.ShineMs"))
        assertTrue(
            cardArrival.contains("delayMillis = if (incomingAlpha.value <= 0f) MimirMotion.OverlayDissolveCoverMs else 0"),
        )
        assertTrue(
            "the close is the next item's, not this one's",
            card.contains("if (target == null) HomeLaunchCardReturnDissolveMs else HomeOverlayDissolveMs"),
        )
        val menu = code.sliceBetween("private fun HomeTileMenuOverlayHost(", "private fun PlatformTileMenuOverlayContent(")
        assertTrue(menu.contains("rememberMimirGlassPanelProgress(visible = true, reducedMotion = reducedMotion)"))
        assertFalse(menu.contains("rememberMimirGlassProgress(visible = true)"))
        assertTrue(
            code.contains(
                "val folioPanelRamp = rememberMimirGlassPanelProgress(visible = folio != null, reducedMotion = homeReducedMotion)",
            ),
        )
        assertTrue(code.contains("glassProgress = folioPanel,"))
        assertTrue(
            "activation waits on the blur, not the slow panel",
            code.contains("folioGlass() >= 1f && currentFolio?.focusSettledForActivation == true"),
        )
        assertTrue(
            "the browser's release waits on the blur, not the slow panel",
            code.contains("snapshotFlow { folioGlass() >= 1f }.first { it }"),
        )
        assertTrue("the scene half keeps the 180 ms ramp", code.contains(".mimirGlassBackdropProgress(folioGlass),"))
    }
}
