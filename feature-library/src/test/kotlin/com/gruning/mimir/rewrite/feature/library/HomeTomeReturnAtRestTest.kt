package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing the Folio member grid returns to the Home shelf.
 *
 * ## The mechanism: two animations, one press
 *
 * Two return animations end to end create a visible seam. The destination is remembered, so it
 * arrives at rest while the departure carries the transition.
 *
 * The Tome return is that shape exactly. `closeTome` → `restoreRootEntry` sets `tomeSession = null`
 * synchronously, so `tomeFolioActive` is false on the commit frame and `homeShelfTomeStageArt` reverts
 * from the focused member's hero to the collection lead art immediately. Because RC-34 merged the two
 * Tome stages into ONE mounted host, that URI change is no longer a remount — it is a real `Crossfade`
 * on `HomeTomePreviewFadeMs` (**240 ms** when this was written; the overlay clock now), running underneath a Folio that is still dissolving. The
 * destination announces itself while the departure has not finished leaving.
 *
 * ## Why "at rest" here means snap, and why that is not a pop
 *
 * The swap lands on the FIRST frame of the return — the single most-covered moment the transition has,
 * with the departing Folio still at full alpha, its panel over 90% x 90% and its scrim over the rest.
 * Every later frame is strictly more exposed. Holding the member art until the dissolve completes would
 * move the same swap into the open, which is strictly worse and is the arrangement the spec ranks last.
 *
 * ## What this cannot prove
 *
 * No Compose harness in this module. These pin the decision function and the wiring; whether the return
 * now reads as one event needs device evidence on both handhelds.
 */
class HomeTomeReturnAtRestTest {

    private val shelf: String by lazy { source("HomeShelfScreen.kt") }
    private val backdrop: String by lazy { source("HomeShelfBackdrop.kt") }

    // ── The decision ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pending return with the folio gone is the only at-rest case`() {
        assertTrue(
            "The reported press: the Folio has closed and a return is in flight. This is the case the " +
                "hero crossfade must not run in.",
            homeShelfTomeStageArrivesAtRest(folioActive = false, tomeReturnPending = true),
        )
    }

    @Test
    fun `an open folio never suppresses its own art changes`() {
        assertFalse(
            "While a Folio is OPEN, an art change is a game-to-game focus move inside it -- ordinary " +
                "navigation, not a return. RC-34's whole argument is that those must crossfade; " +
                "snapping them would reinstate the art churn its kdoc warns about at length. This is " +
                "the term that keeps the return fix from eating RC-34.",
            homeShelfTomeStageArrivesAtRest(folioActive = true, tomeReturnPending = true),
        )
    }

    @Test
    fun `no return means no suppression, however the art changed`() {
        assertFalse(
            "Walking the Home shelf between tome tiles changes the collection lead art with no return " +
                "in flight. That crossfade is untouched.",
            homeShelfTomeStageArrivesAtRest(folioActive = false, tomeReturnPending = false),
        )
        assertFalse(
            homeShelfTomeStageArrivesAtRest(folioActive = true, tomeReturnPending = false),
        )
    }

    // ── The clock ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `at rest is zero, and it is independent of reduced motion rather than derived from it`() {
        assertEquals(
            "A return's destination arrives at rest: one frame, no crossfade.",
            0,
            homeTomeStageHandoffMs(HomeTomePreviewFadeMs, reducedMotion = false, atRest = true),
        )
        assertEquals(
            "Reduced motion still answers zero on its own -- the two are independent reasons, and " +
                "neither may be spelled in terms of the other.",
            0,
            homeTomeStageHandoffMs(HomeTomePreviewFadeMs, reducedMotion = true, atRest = false),
        )
        assertEquals(
            0,
            homeTomeStageHandoffMs(HomeTomePreviewFadeMs, reducedMotion = true, atRest = true),
        )
    }

    @Test
    fun `the ordinary tome crossfade is untouched when neither reason applies`() {
        assertEquals(
            "This is the existing RC-34 crossfade for focus moves inside an open " +
                "Tome. The return grammar " +
                "explicitly does not change any duration token.",
            HomeTomePreviewFadeMs,
            homeTomeStageHandoffMs(HomeTomePreviewFadeMs, reducedMotion = false, atRest = false),
        )
        assertEquals(
            "and that token is the overlay clock (previously 240)",
            MimirMotion.OverlayMs,
            HomeTomePreviewFadeMs,
        )
    }

    // ── The wiring ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the shelf computes the decision from the return it already receives`() {
        val call = shelf.sliceBetween(
            after = "val tomeStageArrivesAtRest = homeShelfTomeStageArrivesAtRest(",
            before = ")",
        )
        assertTrue(
            "Both terms must come from live parameters, not a local latch. A remembered flag here " +
                "would be a second piece of transition state beside FocusAuthority's, which is how the " +
                "burst cases in RC-34 were originally lost. Found: <${call.trim()}>",
            call.contains("folioActive = tomeFolioActive") &&
                call.contains("tomeReturnPending = tomeReturn != null"),
        )
        assertTrue(
            "and it must actually reach the mounted Tome stage",
            shelf.contains("tomeStageArrivesAtRest = tomeStageArrivesAtRest"),
        )
    }

    @Test
    fun `only the synchronized tome presence clock is suppressed, never the decoder reveal`() {
        assertTrue(
            "The Tome presence/media clock takes the at-rest decision.",
            backdrop.contains("atRest = tomeStageArrivesAtRest"),
        )
        assertTrue(
            "stillRevealMs must keep the plain reduced-motion form. It is the DECODER's hydrate " +
                "reveal, not a transition between two known states -- suppressing it would make an " +
                "undecoded hero pop in rather than arrive, which is a new defect wearing this fix's name.",
            backdrop.contains("val stillRevealMs = homeBackdropHandoffMs(MimirMotion.OverlayMs, reducedMotion)"),
        )
    }

    /** Presence-checked slice: a missing anchor fails loudly instead of degrading to the whole file. */
    private fun String.sliceBetween(after: String, before: String): String {
        assertTrue("anchor missing: $after", contains(after))
        assertTrue("anchor missing: $before", substringAfter(after).contains(before))
        return substringAfter(after).substringBefore(before)
    }

    private fun source(name: String): String {
        val candidates = listOf(
            File("feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
            File("src/main/kotlin/com/gruning/mimir/rewrite/feature/library/$name"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("$name not found from ${File(".").absolutePath}")
    }
}
