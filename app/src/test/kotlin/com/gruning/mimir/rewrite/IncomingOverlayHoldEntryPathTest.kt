package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [mimirIncomingOverlayHoldMsForPresentation] — the entry-path wrapper for [mimirIncomingOverlayHoldMs].
 *
 * `HOME_TOME_LIBRARY` presents to `MimirDirectOverlayPixelFadeHost` as the identical cold
 * `null -> HOME_TOME_LIBRARY` transition on BOTH of its entry paths:
 *
 * 1. **The Home shelf's `X` press** (`onTomes()`, `MimirAppInputDispatch.kt`) — the entry path where the defect was first measured
 *    and the 130ms hold first added.
 * 2. **A Tome Folio closing back to the browser** (`closeTomeSurface`, `MimirAppInputDispatch.kt`, which
 *    republishes `HomeTomeLibraryState` non-null BEFORE `HomeShelfCoordinator.kt`'s `closeTomeToLibrary`
 *    tears the Folio down).
 *
 * **Folio-return correction:**
 * path 2 was originally given `0` here, on the reasoning that `closeTomeToLibrary`'s own four-frame hold
 * already covered it. Subsequent measurement invalidated that assumption
 *  — path 2 carries the identical
 * double-exposure defect, measured 3/3. It now gets the same
 * `OverlayDissolveCoverMs` hold as path 1, made safe only because `closeTomeToLibrary`'s four-frame count
 * was replaced, in the same change, by a release keyed on the Library's own arrival settling rather than
 * a frame count (`HomeTomeFolioReturnTeardownTest`). **The two changes are one change** — this file pins
 * only the value half.
 *
 * The discriminator is [HomeTomeLibraryState.openOrigin], a real signal set explicitly at both of that
 * state's construction sites — never inferred from timing (a second clock) or
 * from whether `tomeSession`/the Folio happens to be non-null (racy: the Folio is deliberately kept
 * alive across the handover AFTER the browser republishes, so its liveness alone cannot tell the two
 * paths apart). The wrapper keeps the discriminator even though both arms now agree numerically — see
 * `the FOLIO_RETURN arm still discriminates in source` below for why that is pinned separately from the
 * value.
 */
class IncomingOverlayHoldEntryPathTest {

    @Test
    fun `the Home shelf's cold open keeps the full CP01 hold`() {
        assertEquals(
            "The measured defect -- Home shelf, X press, HOME_TOME_LIBRARY presented for the first time.",
            MimirMotion.OverlayDissolveCoverMs,
            mimirIncomingOverlayHoldMsForPresentation(
                kind = MimirOverlayKind.HOME_TOME_LIBRARY,
                homeTomeLibraryOpenOrigin = HomeTomeLibraryOpenOrigin.HOME_SHELF,
            ),
        )
    }

    @Test
    fun `CP02, 2026-09-02 -- the Folio's return now carries the SAME corrected hold as the cold open`() {
        // Reversed from the original assertion here (0), which this file's own kdoc used to justify with
        // "this ticket's evidence never measured that hop" -- true when CP01 shipped, FALSE since CP02's
        // BEFORE (3/3 trials, the same
        // double-exposure defect measured here). HOLD =
        // MimirMotion.OverlayDissolveCoverMs, mirroring the open. This is safe ONLY because
        // HomeShelfCoordinator.kt's closeTomeToLibrary was changed IN THE SAME PATCH from a four-frame
        // count to a release keyed on the Library's own arrival settling -- see
        // HomeTomeFolioReturnTeardownTest for that half.
        assertEquals(
            "CP02 spec section 8, item 1: HOLD = MimirMotion.OverlayDissolveCoverMs (130), mirroring the " +
                "existing open hop and the reference 311ms return from " +
                "2026-08-15 -- so the Tome Library's two directions cost the same and speak the same " +
                "language.",
            MimirMotion.OverlayDissolveCoverMs,
            mimirIncomingOverlayHoldMsForPresentation(
                kind = MimirOverlayKind.HOME_TOME_LIBRARY,
                homeTomeLibraryOpenOrigin = HomeTomeLibraryOpenOrigin.FOLIO_RETURN,
            ),
        )
    }

    @Test
    fun `the FOLIO_RETURN arm still discriminates in source -- CP02 changed its value, not the mechanism`() {
        // A value assertion alone cannot tell a real per-origin branch apart from this function silently
        // collapsing to `mimirIncomingOverlayHoldMs(kind)` unconditionally -- both arms return the
        // identical constant now that CP02 landed, so a collapse would pass the test above too. Spec
        // section 3.3, verbatim: "The FOLIO_RETURN arm changes value; the mechanism does not change at
        // all." This pin is what makes THAT sentence checkable: if a future edit deletes the discriminant
        // and stops reading homeTomeLibraryOpenOrigin for this kind, this fails even though the numbers
        // still happen to agree today.
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt")
        val body = code.substringAfter("internal fun mimirIncomingOverlayHoldMsForPresentation(")
            .substringBefore("internal fun mimirDirectOverlayUsesPixelFade")
        org.junit.Assert.assertTrue(
            "mimirIncomingOverlayHoldMsForPresentation must still explicitly branch on " +
                "HomeTomeLibraryOpenOrigin.FOLIO_RETURN -- CP02 changed the returned value, not whether " +
                "the entry path is distinguished from the cold open",
            body.contains("HomeTomeLibraryOpenOrigin.FOLIO_RETURN"),
        )
    }

    @Test
    fun `an unidentifiable origin defaults to the measured behaviour, not silently to zero`() {
        assertEquals(
            "A null openOrigin means the caller could not read HomeTomeLibraryState at all -- treat it " +
                "as the shipped, measured path (the hold applies) rather than silently disabling CP01's " +
                "fix for an unrecognised case.",
            MimirMotion.OverlayDissolveCoverMs,
            mimirIncomingOverlayHoldMsForPresentation(
                kind = MimirOverlayKind.HOME_TOME_LIBRARY,
                homeTomeLibraryOpenOrigin = null,
            ),
        )
    }

    @Test
    fun `every other overlay kind holds nothing regardless of the Library's own open origin`() {
        val origins = listOf(null, HomeTomeLibraryOpenOrigin.HOME_SHELF, HomeTomeLibraryOpenOrigin.FOLIO_RETURN)
        (MimirOverlayKind.entries - setOf(MimirOverlayKind.HOME_TOME_LIBRARY)).forEach { kind ->
            origins.forEach { origin ->
                assertEquals(
                    "$kind must hold nothing no matter what the (irrelevant, for this kind) Library " +
                        "openOrigin reads as -- the entry-path gate must only ever narrow HOME_TOME_" +
                        "LIBRARY's own hold, never widen any other kind's.",
                    0,
                    mimirIncomingOverlayHoldMsForPresentation(kind = kind, homeTomeLibraryOpenOrigin = origin),
                )
            }
        }
        origins.forEach { origin ->
            assertEquals(
                0,
                mimirIncomingOverlayHoldMsForPresentation(kind = null, homeTomeLibraryOpenOrigin = origin),
            )
        }
    }

    @Test
    fun `mimirIncomingOverlayHoldMs itself is untouched -- the gate layers on top, it does not widen the when`() {
        // The boundary is explicit: do not widen mimirIncomingOverlayHoldMs's when to more kinds or
        // conditions. This asserts the pure per-kind function still ignores origin entirely and still
        // ships with exactly one non-zero member -- see IncomingOverlayHoldTest for the exhaustive pin.
        assertEquals(
            MimirMotion.OverlayDissolveCoverMs,
            mimirIncomingOverlayHoldMs(MimirOverlayKind.HOME_TOME_LIBRARY),
        )
    }

    // -- Structural pins: both real construction sites must set the signal explicitly, never rely on the
    // -- default. A value test cannot see a deleted `openOrigin = ...` line fall back silently to the
    // -- default (HOME_SHELF) -- for the FOLIO_RETURN site that fallback IS the regression.

    private fun source(relativePath: String): String {
        val file = java.io.File(relativePath)
        org.junit.Assert.assertTrue(
            "$relativePath not found at ${file.absolutePath} -- if this file moved, move this pin too",
            file.exists(),
        )
        return file.readText()
    }

    @Test
    fun `closeTomeSurface's republished state explicitly marks itself a Folio return`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppInputDispatch.kt")
        val returnStateSite = code.substringAfter("val returnState = HomeTomeLibraryState(")
            .substringBefore("bindings.navigation.homeTomeLibrary = returnState")
        org.junit.Assert.assertTrue(
            "closeTomeSurface's returnState must set openOrigin = FOLIO_RETURN explicitly",
            returnStateSite.contains("openOrigin = HomeTomeLibraryOpenOrigin.FOLIO_RETURN"),
        )
    }

    @Test
    fun `onTomes's fresh state explicitly marks itself a Home shelf open`() {
        val code = source("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppInputDispatch.kt")
        val onTomesSite = code.substringAfter("onTomes = {")
            .substringBefore("onExit = { closeTomeSurface(bindings) }")
        org.junit.Assert.assertTrue(
            "the onTomes() cold-open construction must set openOrigin = HOME_SHELF explicitly",
            onTomesSite.contains("openOrigin = HomeTomeLibraryOpenOrigin.HOME_SHELF"),
        )
    }
}
