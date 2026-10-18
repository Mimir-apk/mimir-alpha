package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTransitionEpochLedger
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Tome Folio's return cover.
 *
 * The return transition fully hides the Home shelf. Previously the Folio's return
 * had **no cover at all** — panel and scrim both died inside `if (folio != null)` on the commit frame, so
 * the Home shelf was the only thing on screen for the whole 180 ms and it was animating for all of it.
 *
 * These are decision-level pins. Neither `app` nor `feature-library` configures a Compose harness that can
 * measure a dissolve, so what a unit test can hold is *which* decision the host makes and *on which
 * clocks* — that the return arms, that it arms only in one direction, that the hold is the house constant,
 * and that the shape matches the reference return length. Whether the return looks correct
 * still requires on-device visual validation.
 */
class TomeFolioReturnCoverTest {

    /**
     * A return is present → absent and nothing else. The open must keep
     * `rememberMimirGlassProgress`'s arrival ramp — arming there would replace the Folio's
     * materialisation with a held cover unwinding, which is the transition running backwards.
     */
    @Test
    fun onlyAFolioLeavingArmsTheHeldCover() {
        assertTrue(homeTomeFolioReturnArmed(folioPresentBefore = true, folioPresentAfter = false))
        assertFalse(homeTomeFolioReturnArmed(folioPresentBefore = false, folioPresentAfter = true))
        assertFalse(homeTomeFolioReturnArmed(folioPresentBefore = false, folioPresentAfter = false))
        assertFalse(homeTomeFolioReturnArmed(folioPresentBefore = true, folioPresentAfter = true))
    }

    /**
     * The held cover is a stricter condition than the departure, and the difference is reachable by an
     * impatient press: back out of a tome DURING its 180 ms materialisation and the scene is only part
     * blurred, but `rememberMimirGlassReturnProgress` is born at 1f by construction. Arming there would
     * step the blur and the scrim UP on the departing frame and record the panel at an alpha it never
     * reached — a flash-in followed by a dissolve, which is worse than the artefact the cover removes.
     *
     * The ring extinction is NOT gated on this: every departure gets it, covered or not.
     */
    @Test
    fun aFolioThatNeverFinishedOpeningDepartsWithoutTheHeldCover() {
        assertTrue(homeTomeFolioReturnCovered(folioDeparted = true, openProgressAtDeparture = 1f))
        assertFalse(
            "a tome backed out of mid-materialisation has nothing to hold",
            homeTomeFolioReturnCovered(folioDeparted = true, openProgressAtDeparture = 0.3f),
        )
        assertFalse(
            "no departure, no cover, whatever the scene is doing",
            homeTomeFolioReturnCovered(folioDeparted = false, openProgressAtDeparture = 1f),
        )
    }

    /**
     * Motion batch F put the Folio's panel on its own clock (130 ms lead + ShineMs) while the scene
     * kept the 180 ms ramp. The held cover was armed on the slower of the two clocks, so a back-out between
     * ~180 and ~670 ms was left UNARMED: the scene unwound with no hold while the recorded, half-arrived
     * panel dissolved on top of it (RC-25 / H-03c, the 08-15 mush). The cover now arms on the scene alone
     * (at 1.0 from 180 ms, so arming steps nothing), and what the old minimum protected -- the panel's
     * single recorded frame, which the born-at-1f return ramp painted at full alpha and lift -- is
     * protected by the panel keeping the alpha it had at the departure.
     */
    @Test
    fun theHeldCoverArmsOnTheSceneAloneAndAHalfArrivedPanelKeepsItsAlpha() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt").readText()
        val armed = source.substringAfter("val folioDeparture = remember(folioPresenceFrame.epoch) {")
            .substringBefore("val folioReturnRamp = rememberMimirGlassReturnProgress(")
        assertTrue(
            "the return arms on the scene's glass; the panel's alpha is captured beside it, once",
            armed.contains("glassAtDeparture = folioGlassRamp(),") && armed.contains("panelAtDeparture = folioPanelRamp(),"),
        )
        assertFalse("no minimum over the two clocks any more", source.contains("minOf(folioGlassRamp(), folioPanelRamp())"))
        assertTrue(
            "an armed return rides the return ramp only when the panel had finished arriving",
            source.contains("folioReturnArmed -> if (folioDeparture.panelFullyArrived) folioReturnRamp else folioDeparture.panelAlpha"),
        )
        // Scene settled at 1, panel still mid-arrival (a quick back): the cover arms, the panel does not jump to 1.
        val quickBack = homeTomeFolioDeparture(folioDeparted = true, glassAtDeparture = 1f, panelAtDeparture = 0.3f)
        assertTrue(quickBack.armed)
        assertFalse(quickBack.panelFullyArrived)
        assertEquals(0.3f, quickBack.panelAlpha(), 0f)
        // Scene still ramping (a back inside the first 180 ms): no cover, as before.
        assertFalse(homeTomeFolioDeparture(folioDeparted = true, glassAtDeparture = 0.6f, panelAtDeparture = 0f).armed)
        // Everything arrived: the panel rides the return ramp, as it always did.
        val settled = homeTomeFolioDeparture(folioDeparted = true, glassAtDeparture = 1f, panelAtDeparture = 1f)
        assertTrue(settled.armed)
        assertTrue(settled.panelFullyArrived)
        // No departure, no cover.
        assertFalse(homeTomeFolioDeparture(folioDeparted = false, glassAtDeparture = 1f, panelAtDeparture = 1f).armed)
    }

    /**
     * The host reads "armed" off the ledger frame rather than off a second boolean, so this drives the
     * real [MimirTransitionEpochLedger] exactly as `MimirAppHost` does and asserts the sequence a player
     * produces: open a tome, press B, open another.
     *
     * The epoch is the return's identity. It must change on the departure (or
     * `rememberMimirGlassReturnProgress` would inherit the previous return's in-flight `Animatable`) and
     * the reopen must DISARM (which is what fires the primitive's own `snapTo(0f)` guard, so an
     * interrupted return can never park the shelf between full blur and clear).
     */
    @Test
    fun theLedgerArmsOncePerReturnAndDisarmsOnReopen() {
        val ledger = MimirTransitionEpochLedger(false)

        val closed = ledger.prepare(false, ::homeTomeFolioReturnArmed)
        ledger.commit(closed)
        assertNull("a shelf with no folio has never departed", closed.outgoingLayerIndex)

        val opened = ledger.prepare(true, ::homeTomeFolioReturnArmed)
        ledger.commit(opened)
        assertNull("opening a tome is not a return", opened.outgoingLayerIndex)

        val returned = ledger.prepare(false, ::homeTomeFolioReturnArmed)
        ledger.commit(returned)
        assertNotEquals(opened.epoch, returned.epoch)
        assertTrue("pressing B out of a tome IS the return", returned.outgoingLayerIndex != null)

        val reopened = ledger.prepare(true, ::homeTomeFolioReturnArmed)
        ledger.commit(reopened)
        assertNotEquals(returned.epoch, reopened.epoch)
        assertNull("re-opening mid-return disarms, so the blur cannot park", reopened.outgoingLayerIndex)
    }

    /**
     * R-1's arithmetic, and it is the house's own rather than a tuned number: DEPART on
     * [MimirMotion.OverlayMs], HOLD on [MimirMotion.OverlayDissolveCoverMs] concurrently, CLEAR on
     * [MimirMotion.OverlayMs] starting where the hold ends. No new token — 130 is already the frame the
     * departing layer's alpha reaches .044 on the house curve, which is that constant's own derivation.
     *
     * 311 ms is the reference manager → shelf return measured on 2026-08-15. One language means one
     * clock, so landing within a frame of it is the point, not a coincidence.
     */
    @Test
    fun theReturnRunsOnHouseClocksAndLandsOnTheRuledGoodLength() {
        val ruledGoodManagerReturnMs = 311
        val total = MimirMotion.OverlayDissolveCoverMs + MimirMotion.OverlayMs
        assertEquals(310, total)
        assertTrue(
            "the Tome return must land within 10% of the reference manager return",
            kotlin.math.abs(total - ruledGoodManagerReturnMs) <= ruledGoodManagerReturnMs / 10,
        )
        // The hold is strictly shorter than the dissolve it covers, or the cover outlives its subject and
        // the player waits on an empty screen.
        assertTrue(MimirMotion.OverlayDissolveCoverMs < MimirMotion.OverlayMs)
        // Nothing about this gates input.
        assertEquals(0, MimirMotion.InputDelayMs)
    }

    /**
     * **Cold-open behavior, pinned against the ledger arithmetic.**
     *
     * A possible frame-1 cut with the themed floor restored would require
     * [mimirIncomingLayerStartAlpha] to return 1f
     * when `!hasOutgoing`. That branch is not on this path. `MimirAppOverlayHost` — and therefore
     * `MimirDirectOverlayPixelFadeHost` — is composed unconditionally, so its ledger is alive with
     * `presentedTarget == null` before any overlay opens, and its retain predicate is `from != to`, which
     * retains an outgoing layer even from a cold null. So `hasOutgoing` is TRUE on the open, the cached
     * layer target does not match, and the incoming alpha starts at **0** and ramps on
     * [MimirMotion.OverlayMs]. The Library's opaque floor is inside that recorded layer, so it arrives
     * with the surface on one clock.
     *
     * This drives the real ledger and the real predicate through the real sequence rather than asserting
     * an assumption about an unreachable branch.
     */
    @Test
    fun aColdOverlayOpenAlreadyRampsFromZero() {
        // The host's own state, mounted with no overlay active — MimirAppOverlayHost is unconditional.
        val epochs = MimirTransitionEpochLedger<MimirDirectOverlayTarget?>(null)
        val displayedLayerAlphas = floatArrayOf(0f, 0f)
        val layerTargets = arrayOfNulls<MimirDirectOverlayTarget>(2)

        val library = MimirDirectOverlayTarget(MimirOverlayKind.HOME_TOME_LIBRARY)
        assertTrue(mimirDirectOverlayUsesPixelFade(MimirOverlayKind.HOME_TOME_LIBRARY))

        val open = epochs.prepare(library) { from, to -> from != to }
        epochs.commit(open)
        assertTrue("a cold open still retains a layer, so hasOutgoing is true", open.outgoingLayerIndex != null)

        val startAlpha = mimirIncomingLayerStartAlpha(
            target = library,
            cachedLayerTarget = layerTargets[open.primaryLayerIndex],
            cachedLayerAlpha = displayedLayerAlphas[open.primaryLayerIndex],
            hasOutgoing = open.outgoingLayerIndex != null,
        )
        assertEquals(
            "the Tome Library opened from a bare Home destination materialises; it does not cut",
            0f,
            startAlpha,
            0f,
        )
    }

    /**
     * T-1's reuse, held as a count rather than as a promise. The Folio's departure is a second CALLER of
     * the record-and-fade shape, not a fourth copy of it, so wiring it must not have added a layer pair.
     *
     * Four pairs remain in this file and each is named: the shared [MimirPixelFadeHost] (direct overlays
     * AND the Folio), the launch card (bakes a title-edit blur into the content before the capture, and
     * runs asymmetric open/return durations), the quick-launch picker (owns presentation-ownership tokens
     * and deliberately refuses to retain a cold outgoing layer), and the root destination handoff.
     */
    @Test
    fun theFolioDepartureAddedNoNewPixelFadeCopy() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt").readText()
        assertEquals(
            "adding a fifth graphics-layer pair means the shared host was not reused",
            8,
            Regex("rememberGraphicsLayer\\(\\)").findAll(source).count(),
        )
        assertEquals(
            "one record-and-fade body per remaining pair",
            4,
            Regex("primaryLayer\\.record \\{").findAll(source).count(),
        )
        assertTrue(
            "the direct-overlay host must delegate to the shared one",
            source.contains("private fun <T : Any> MimirPixelFadeHost("),
        )
        // The two callers differ on exactly two policies, and both are NAMED at the Folio's call site
        // rather than inherited. The incoming pin is what stops the host's ramp multiplying with
        // `mimirGlassPanelEntry` into a squared curve; the retain policy is what stops a tome A -> B swap
        // growing an unruled crossfade. A shared host whose differences are implicit is drift with a
        // shorter diff.
        assertTrue(
            "the Folio's arrival is owned by mimirGlassPanelEntry, so this host must not ramp it too",
            source.contains("incomingStartAlpha = { _, _, _ -> 1f },"),
        )
        assertTrue(
            "the Folio retains pixels on a DEPARTURE only, and reuses the cover's own predicate to say so",
            source.contains(
                "retainOutgoing = { from, to -> homeTomeFolioReturnArmed(from != null, to != null) },",
            ),
        )
    }

    /**
     * Wiring that no other test in this suite can reach: no Compose harness in `app` or
     * `feature-library` can observe a blur ramp, so the pin is that the host uses the RETURN primitive on
     * the return, with the house hold, keyed to the same identity the departure is.
     *
     * All three clauses are load-bearing. `rememberMimirGlassProgress` (the OPEN primitive) unwinds
     * immediately with no hold, which is the shelf sharpening under a still-dissolving Folio — the defect.
     * A missing `holdMillis` is the same thing on a different line. And an identity that is not the
     * departure's epoch lets a second return inherit the first's in-flight `Animatable`.
     */
    @Test
    fun theShelfHoldsAtFullCoverOnTheHouseDissolveWindow() {
        val source = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt").readText()
        assertTrue(
            "the return uses the return primitive, not the arrival one",
            source.contains("rememberMimirGlassReturnProgress("),
        )
        assertTrue(
            "the hold is the house constant, not a new token",
            source.contains("holdMillis = MimirMotion.OverlayDissolveCoverMs"),
        )
        assertTrue(
            "the cover and the departure share one identity",
            source.contains("identity = folioPresenceFrame.epoch"),
        )
    }

    /**
     * the scrim must belong to a layer that SURVIVES the return. It used to be a `drawBehind` on the
     * Folio's own root, so it vanished in one frame with the panel — a hard luminance step under a ramp,
     * and on API 30 (`MimirGlass.supportsBlur` false) the scrim is the entire transition, so there the
     * step was the whole return.
     *
     * Pinned as a structural fact because it is one: the host paints it, the Folio does not.
     */
    @Test
    fun theHostOwnsTheFolioScrimSoItOutlivesTheDepartingPanel() {
        val host = File("src/main/kotlin/com/gruning/mimir/rewrite/MimirAppHost.kt").readText()
        val folio = File(
            "../feature-library/src/main/kotlin/com/gruning/mimir/rewrite/feature/library/HomeTomeFolio.kt",
        ).readText()
        assertTrue(
            "the host paints the Folio's scrim on the same lambda the panel rides",
            host.contains("MimirGlassScrim(progress = folioGlass)"),
        )
        assertFalse(
            "the Folio must not paint a scrim it cannot outlive",
            folio.contains("mimirGlassScrimColor"),
        )
    }
}
