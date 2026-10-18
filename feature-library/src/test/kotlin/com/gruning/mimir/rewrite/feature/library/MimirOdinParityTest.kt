package com.gruning.mimir.rewrite.feature.library

import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIMIR ODIN-PARITY HARNESS. Compares relative geometry for the four named surfaces that live in `feature-library`: Home shelf (RC-2),
 * Library platform shelf (RC-18), gamelist carousel (RC-7) and the Launch Card (RC-3). Settings (RC-8) is
 * `core-ui`'s copy of this file, because it needs `MimirSpacing` and has no `feature-library` solver of
 * its own.
 *
 * **Harness types below ([MimirSurfaceProportion], [MimirProportionReading], [mimirProportionReport]) are
 * a verbatim duplicate of `core-ui`'s copy** — same rationale as [MimirCertifiedViewports]' own duplication
 * (see that file's KDoc: no cross-module test-fixture wiring exists in this build). Diff them against each
 * other before trusting a claim sourced from just one. See the `core-ui` copy's KDoc for the full tolerance
 * derivation and the three-assertion-form mapping (Class 1 → Odin ratio, Class 2 → floor, Class 3 → dp
 * hold); it is not repeated in full here.
 *
 * ## Scope note: `MimirRunicQuickRail.kt` deliberately excluded this pass
 *
 * `core-ui/.../MimirRunicQuickRail.kt` now uses equal dock-seat widths measured on Nova
 * (`5127f68a`). A proportion built on `MimirRunicQuickRailDefaults` is no longer blocked by that
 * implementation change, but remains unadded here: this harness covers the shrinking-
 * baseline mechanism for the proportions this file already carried, not new proportion coverage. The Home
 * dock hint/rail collision (FOUR_THREE §3 R1-1) stays **owed** for a follow-up pass, now unblocked.
 *
 * ## Posture scoping
 *
 * Every proportion below runs over [MimirCertifiedViewports.standard] only, for the same reason as the
 * `core-ui` copy: COMPACT_SQUARE changes composition, not just geometry, so it is out of this contract's
 * scope rather than a viewport this harness silently skips.
 */
internal data class MimirSurfaceProportion(
    val name: String,
    val odinValue: Float,
    val tolerance: Float,
)

internal data class MimirProportionReading(
    val surface: String,
    val viewportName: String,
    val proportion: MimirSurfaceProportion,
    val measured: Float,
) {
    val delta: Float get() = abs(measured - proportion.odinValue)
    val pass: Boolean get() = delta <= proportion.tolerance

    override fun toString(): String {
        val mark = if (pass) "PASS" else "RED "
        return "[$mark] %-28s / %-42s / %-24s  measured=%+.4f  odin=%+.4f  delta=%.4f  tol=%.4f".format(
            surface,
            viewportName,
            proportion.name,
            measured,
            proportion.odinValue,
            delta,
            proportion.tolerance,
        )
    }
}

internal fun mimirProportionReport(readings: List<MimirProportionReading>): String =
    readings.joinToString("\n") { it.toString() }

private const val ClassOneTolerance = 0.005f
private const val StructuralTolerance = 0.01f

// ── HOME SHELF (RC-2) — solveHomeShelfGeometry, HomeShelfGeometry.kt (untouched, stable this session) ──

/** Class 1 — fixed-chrome-before-content-ink share: `contentLeft / widthDp`. */
private fun homeChromeShare(vp: MimirDpBox): Float {
    val g = solveHomeShelfGeometry(vp.widthDp, vp.heightDp)
    return g.contentLeft / vp.widthDp
}

/**
 * The primary-content share: `heroSide / widthDp`. FOUR_THREE §1 classifies cover art (which `heroSide`
 * IS — the focused shelf tile) as Class 3 (hold dp size independently of width) — but `solveHomeShelfGeometry`
 * (`HomeShelfGeometry.kt:229`, `heroSide = min(seatEnvelope, 0.48f × heightDp)`) implements it as a
 * fraction of `seatEnvelope`, which is itself a fraction of width. **This harness measures what the code
 * does (a Class-1-shaped proportional share), not what the spec wants (a Class-3 dp hold)** — the gap
 * between the two is itself a finding, not a bug in this test. Also carries the seat-count threshold
 * (`HomeShelfGeometry.kt:65`, `widthDp < 700f → 3 seats else 4`, raised from 640f by policy
 * 2026-08-21 specifically because 4 seats read "quite tiny" on the Nova): the Nova (640dp) now solves 3
 * seats where the Odin (833dp) and the RP5-class shelf (700dp) both solve 4, so a real divergence on this
 * proportion at the Nova is an **accepted, deliberate design outcome of the seat-count threshold — not an open defect**.
 * Recomputed against the CURRENT (current threshold) threshold, the Nova's `heroSide` is actually 4.7% LARGER
 * than the Odin's, not smaller — the opposite of FOUR_THREE §3 R3-10's finding, which predates this
 * threshold change. The earlier smaller-tile finding predates d64b970f.
 */
private fun homeHeroWidthShare(vp: MimirDpBox): Float {
    val g = solveHomeShelfGeometry(vp.widthDp, vp.heightDp)
    return g.heroSide / vp.widthDp
}

/**
 * Margin symmetry — target 1.0 always (an asymmetry is a defect at every viewport, per the ticket). Left
 * margin is the exposed `contentLeft` field; right margin is independently RECONSTRUCTED from three
 * different exposed fields (`seatEnvelope`, `visibleSlotCount`, `slotGap`) rather than re-deriving it from
 * `gutter` again, so this is a genuine cross-check and not the same arithmetic restated. Algebraically
 * this must equal 1.0 for any seat count (the reconstruction solves `solveHomeShelfGeometry`'s own
 * `seatEnvelope` equation backwards) — a provably-true regression tripwire: if a future change (e.g. an
 * M-anchor reservation added to Home, proposed for all surfaces drawing content to the top or right edge) makes the lane asymmetric, this is the cell that
 * catches it.
 */
private fun homeMarginSymmetry(vp: MimirDpBox): Float {
    val g = solveHomeShelfGeometry(vp.widthDp, vp.heightDp)
    val contentRight = g.contentLeft + g.visibleSlotCount * g.seatEnvelope + (g.visibleSlotCount - 1) * g.slotGap
    val rightMargin = vp.widthDp - contentRight
    if (rightMargin <= 0f) return Float.NaN
    return g.contentLeft / rightMargin
}

private val HomeOdinBox = MimirCertifiedViewports.Odin.solverInputDp
private val HomeChromeShareProportion =
    MimirSurfaceProportion("home.chromeShare", homeChromeShare(HomeOdinBox), ClassOneTolerance)
private val HomeHeroWidthShareProportion =
    MimirSurfaceProportion("home.heroWidthShare", homeHeroWidthShare(HomeOdinBox), ClassOneTolerance)
private val HomeMarginSymmetryProportion =
    MimirSurfaceProportion("home.marginSymmetry", 1f, StructuralTolerance)

private fun homeShelfReadings(): List<MimirProportionReading> =
    MimirCertifiedViewports.standard.flatMap { viewport ->
        val box = viewport.solverInputDp
        listOf(
            MimirProportionReading("Home Shelf (RC-2)", viewport.name, HomeChromeShareProportion, homeChromeShare(box)),
            MimirProportionReading(
                "Home Shelf (RC-2)",
                viewport.name,
                HomeHeroWidthShareProportion,
                homeHeroWidthShare(box),
            ),
            MimirProportionReading(
                "Home Shelf (RC-2)",
                viewport.name,
                HomeMarginSymmetryProportion,
                homeMarginSymmetry(box),
            ),
        )
    }

// ── LIBRARY PLATFORM SHELF (RC-18) — solveLibraryShelfGeometry + CoverflowMotion book-hero constants ──

/**
 * Focus-to-rest ratio, "invariant by construction — pinned so a future per-device retune fails loudly."
 * `PlatformHeroTile` (`bookHero = true`) always LAYS OUT at `CoverflowMotionBookFocusWidthDp` (144dp) and
 * paints the rest pose by scaling that fixed box down [V `PlatformHeroTile.kt:394,452-455`]; the painted
 * envelope (including the ring/bloom outset the eye reads as "the tile") is
 * `(focusWidth + 2·bloomOutset) × focusScale` at the hero and `focusWidth × restScale` at rest. Neither
 * term is a function of `widthDp`/`heightDp` at STANDARD posture (`tileScale == 1`, see
 * `solveLibraryShelfGeometry`'s non-compact branch), so this ratio is the SAME number on every certified
 * STANDARD viewport — Odin, Nova and the RP5-class shelf alike. `(144+20)×1.03 / (144×0.58) ≈ 2.022`,
 * matching RC-18's own device measurement of "a 2.03x focus jump" (commit `41c1c677`) to within capture
 * rounding.
 */
private fun libraryFocusToRestRatio(): Float {
    val paintedFocus = (CoverflowMotionBookFocusWidthDp + 2 * CoverflowMotionBloomOutsetDp) * CoverflowMotionFocusScale
    val paintedRest = CoverflowMotionBookFocusWidthDp * CoverflowMotionBookRestScale
    return paintedFocus / paintedRest
}

/**
 * Vertical chrome share — `(fixedLaneBudgetDp − heroBandHeightDp) / heightDp`: everything the shelf
 * reserves (top/bottom padding, identity header, header-to-tools gap, head row, quick rail) EXCLUDING the
 * coverflow band itself, which is content. `fixedLaneBudgetDp` is not wired into the production
 * composable's own layout modifiers directly, but it is an existing, already-relied-upon contract:
 * `MimirCertifiedViewportMatrixTest.kt` and `ShelfHeadGeometryTest.kt` already assert
 * `fixedLaneBudgetDp <= viewportHeight` for every certified row — this proportion reuses that same
 * production quantity for a new purpose rather than inventing a fresh one.
 */
private fun libraryChromeShareVertical(vp: MimirDpBox): Float {
    val g = solveLibraryShelfGeometry(vp.widthDp, vp.heightDp)
    val chrome = g.fixedLaneBudgetDp - g.heroBandHeightDp
    return chrome / vp.heightDp
}

private val LibraryFocusToRestProportion =
    MimirSurfaceProportion("library.focusToRestRatio", libraryFocusToRestRatio(), StructuralTolerance)
private val LibraryOdinBox = MimirCertifiedViewports.Odin.solverInputDp
private val LibraryChromeShareVerticalProportion = MimirSurfaceProportion(
    "library.chromeShareVertical",
    libraryChromeShareVertical(LibraryOdinBox),
    ClassOneTolerance,
)

private fun libraryPlatformShelfReadings(): List<MimirProportionReading> =
    MimirCertifiedViewports.standard.flatMap { viewport ->
        val box = viewport.solverInputDp
        listOf(
            MimirProportionReading(
                "Library platform shelf (RC-18)",
                viewport.name,
                LibraryFocusToRestProportion,
                libraryFocusToRestRatio(),
            ),
            MimirProportionReading(
                "Library platform shelf (RC-18)",
                viewport.name,
                LibraryChromeShareVerticalProportion,
                libraryChromeShareVertical(box),
            ),
        )
    }

// ── GAMELIST CAROUSEL (RC-7) ─────────────────────────────────────────────────────────────────────────

/**
 * Focus-to-rest ratio only. Same painted-envelope formula as the platform shelf, native-row constants
 * (`GameCard` calls `coverflowBasePose(bookHero = false)` [V `GamelistCarousel.kt:465,552`], i.e.
 * `CoverflowMotionFocusWidthDp`/`CoverflowMotionRestScale`, NOT the book-hero pair, and NOT
 * `NativeShelfCoverflowRestScale` — that lever is `NativeShelfCoverflow.kt`-only per its own doc comment,
 * a different composable from this one). Structurally invariant for the same reason as the platform shelf.
 *
 * **Chrome share / content share: UNMEASURABLE TODAY, but less than when this pass started.**
 * `GamelistCarousel` is composed at `Modifier.fillMaxSize()` inside a `Modifier.weight(1f)` Column slot
 * (`LibraryScreen.kt:1472-1514`), and its card scale (`viewportScale`) is `availableCardHeight /
 * gamelistCardBodyHeightDp(coverAspect, wideArtworkLane)`. **Live-tree finding, mid-session:** a
 * concurrent, UNCOMMITTED RC-7 lane hoisted that divisor from an inline private computation into the
 * new top-level `gamelistCardArtHeightDp(coverAspect): Dp` / `gamelistCardBodyHeightDp(coverAspect,
 * wideArtworkLane): Dp` (`GamelistCarousel.kt`) while this harness was being written — both are now pure
 * and callable. They are NOT wired into this harness: neither takes a viewport dimension at all (only
 * `coverAspect`), so a "does it hold across certified viewports" proportion on either would be
 * tautologically green — not a measurement, a fabrication. What is still genuinely missing, and still
 * blocks a real chrome/content-share cell, is `availableCardHeight` itself: `maxHeight.value` read out of
 * a `BoxWithConstraints`, i.e. the Column slot's resolved height, which requires tracing every sibling row
 * above the carousel (header, mode strip, search-bar visibility) — not attempted this pass. What it would
 * take: a pure function for that Column's available-height budget (the platform shelf's `fixedLaneBudgetDp`
 * is the precedent shape); once it exists, this file's `gamelistFocusToRestRatio` neighbour above is the
 * pattern to copy.
 */
private fun gamelistFocusToRestRatio(): Float {
    val paintedFocus = (CoverflowMotionFocusWidthDp + 2 * CoverflowMotionBloomOutsetDp) * CoverflowMotionFocusScale
    val paintedRest = CoverflowMotionFocusWidthDp * CoverflowMotionRestScale
    return paintedFocus / paintedRest
}

private val GamelistFocusToRestProportion =
    MimirSurfaceProportion("gamelist.focusToRestRatio", gamelistFocusToRestRatio(), StructuralTolerance)

private fun gamelistCarouselReadings(): List<MimirProportionReading> =
    MimirCertifiedViewports.standard.map { viewport ->
        MimirProportionReading(
            "Gamelist carousel (RC-7)",
            viewport.name,
            GamelistFocusToRestProportion,
            gamelistFocusToRestRatio(),
        )
    }

// ── LAUNCH CARD — homeLaunchCardLayout, HomeLaunchCard.kt ──────────────────────────────────────
// The card remained 43% smaller in the art proportion despite converged chromeShare.
// A single converged ratio does not characterize the whole surface; launchCardArtHeightShare
// and launchCardArtWidthShare add the visible art proportions to this instrument.

/**
 * Class 1 chrome share — `outerHorizontal × 2 / widthDp`. RC-3 (2026-08-21) converged `outerHorizontal` /
 * `outerVertical` from `homeLaunchCardLayout`'s four independent per-band constants
 * (`HomeLaunchCard.kt:475-487`, historical) into a single Odin-anchored continuous function
 * (`homeLaunchCardOuterHorizontal`, `HomeLaunchCard.kt`). A follow-up the same day extended convergence to Nova and RP5 and replaced the piecewise version (which existed only to reproduce
 * four now-confirmed-synthetic pins in `HomeLaunchCardTest.kt`) with a single global proportional law; see
 * that function's own KDoc for the per-pin synthetic/real verdict and the derivation. Because
 * `outerHorizontal*2/width` reduces algebraically to the constant `2 * ratio` under a pure proportional law,
 * this proportion now converges FULLY at every certified width, not just the two that converged under the
 * piecewise version — see `HomeLaunchCardLayoutGoldenTest.chromeShareConvergesFullyUnderThePureLaw` for the
 * pinned proof. Both `launchCard.chromeShare` baseline rows (Nova, RP5-class shelf) are deleted below.
 */
private fun launchCardChromeShare(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    return (layout.outerHorizontal.value * 2f) / vp.widthDp
}

/**
 * Class 1 — art height share: `coverHeight / heightDp`. Measures the reserved cover-art BOX, not a
 * per-platform rendered pixel size (`homeLaunchCardArtSize` further scales down within this box, uniformly,
 * to hold a fixed target area *and* the focused platform's own cover aspect — a real, INTENDED per-platform
 * variation this harness must not grade as drift). The box is still the right thing to measure: it is what
 * the pixel captures actually found bounding the visible
 * art on both devices — `176.dp / 480.dp` (the Nova, pre-fix) is 36.67%, against that doc's own measured
 * 36.7%; `300.dp / 468.3.dp` (the Odin) is 64.06% against its measured 63.9% — close enough that the box IS
 * the binding constraint for the platforms captured, not merely a generous, non-binding upper bound. Using
 * `MeasuredCoverAspects`' runtime-learned per-platform aspect here (`GamelistCarousel.kt`) would make this
 * proportion depend on a mutable, test-order-sensitive singleton for no gain — the defect lived in the box,
 * not in any one platform's render, and `homeLaunchCardCoverHeight` is now purely height-anchored (see its
 * KDoc, `HomeLaunchCard.kt`), so this share is CONSTANT at every certified STANDARD viewport by construction
 * — full convergence, the same mechanism `launchCardChromeShare` already relies on for its own proportion.
 */
private fun launchCardArtHeightShare(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    return layout.coverHeight.value / vp.heightDp
}

/**
 * Class 1 — art width share: `coverColumnWidth / widthDp`. Same box-not-pixel reasoning as
 * [launchCardArtHeightShare] above.
 *
 * **UPDATED (width-bound correction) —
 * superseded, not merely re-tuned.** `coverColumnWidth` was PURELY height-anchored (the starved-text-column
 * defect: see `LaunchCardCoverColumnWidthCapRatio`'s KDoc in `HomeLaunchCard.kt`); it is now
 * `minOf(heightAnchored, widthAnchored)`, the width term using the SAME Odin fixed point
 * (`288/832.5`) as this proportion's own `odinValue`. Wherever the width term binds — every viewport
 * NARROWER (relative to its own height) than the Odin's 832.5/468.3 = 1.7778 aspect — `coverColumnWidth /
 * widthDp` reduces algebraically to the Odin's own ratio, so this proportion now CONVERGES FULLY there
 * (delta 0.0000), the same mechanism [launchCardChromeShare] already relies on. Only viewports at or past
 * the Odin's own aspect (RP5-class shelf, 2.0) still take the height term and still diverge — carried below
 * as a graded proportion, same as before, so a FUTURE regression widening that one remaining divergence
 * still fails loudly.
 *
 * **RENAMED IN MEANING, not in value, by S2 (2026-08-21).** This row still measures
 * `coverColumnWidth / widthDp` and every number it reports is unchanged — but `coverColumnWidth` is no
 * longer a painted box. Since the layout replaced the centred-in-a-wider-box law (see
 * [launchCardCoverBoxCentreXShare] and `homeLaunchCardCoverBoxWidth`'s KDoc in `HomeLaunchCard.kt`), it is
 * the two-ratio BOUND the art solves inside, and the box that actually paints is the art's own width.
 * Read this row as "the width budget the art is allowed", never as "the width the card devotes to art" —
 * the painted quantity is [launchCardCoverBoxCentreXShare] and [launchCardCoverArtMarginAsymmetryDp].
 */
private fun launchCardArtWidthShare(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    return layout.coverColumnWidth.value / vp.widthDp
}

/**
 * Class 1 — text lane share (NEW, Regression 1, this ticket,
 * Nova capture3add68a0):
 * `homeLaunchCardTextColumnWidth(...) / widthDp`, the metadata/title column's own claim on the viewport, at
 * whichever skeleton this viewport actually renders. **This is the metric the captured regression was
 * actually measured against** — the geometry doc's own closing line is why this proportion set gained a
 * row instead of trusting `chromeShare`/`artWidthShare`/`artHeightShare` alone to cover a starved TEXT
 * column none of the three measure. Convergence of one proportion does not establish convergence
 * of the surface; the set must cover its visible features.
 *
 * **Not fully converged, and not expected to be.** `columnGap` is still a per-band literal
 * (`homeLaunchCardContentBands` — 20dp under 720dp, 36dp at/above 800dp — OWED, out of this ticket's
 * scope), so this share carries a small, honest divergence at some viewports even after Regression 1's
 * `coverColumnWidth` fix converges `artWidthShare` there. Tracked with a baseline row where red, not
 * silently accepted; deltas transcribed from this file's own `%.4f` report, one real run.
 *
 * **RE-DERIVED AGAIN 2026-08-21 — this instrument now models the lane that actually RENDERS.** It briefly
 * subtracted a 66dp M-anchor inset on every reading, modelling the card as though it reserved a lane for the
 * primary M orb. **Production never rendered that lane**: every call site passed `false`, so the composable's
 * own inset was `0.dp` at every viewport on both certified devices. The reservation is now retired
 * product-wide (no surface reserves for the orb at all), and with the parameter gone
 * this function has no way to model the counterfactual any more. All five values (the Odin reference plus the
 * four measured rows) move back, and the baselines below are re-transcribed from a real run — the seam this
 * file's own baseline note called "a ticket of its own" is closed by that removal, not by tuning.
 */
private fun launchCardTextLaneShare(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    val wideSkeleton = homeLaunchCardUsesWideSkeleton(vp.widthDp.dp, vp.heightDp.dp, layout)
    val textColumnWidth = homeLaunchCardTextColumnWidth(vp.widthDp.dp, layout, wideSkeleton)
    return textColumnWidth.value / vp.widthDp
}

/**
 * The reference cover for every content-dependent launch-card row below. `"gamecube"` is 0.72 in
 * `GamelistCarousel.kt`'s own CURATED map (`PlatformCoverAspects`), which always wins over the runtime
 * `MeasuredCoverAspects` store — so these rows are deterministic in a JVM test, not dependent on a
 * SharedPreferences-backed snapshot map that is empty here anyway. 0.72 is also the aspect every device
 * measurement behind S1/S2 was captured at ,
 * so the derived margins in these KDocs are comparable to the frames. Pinned by
 * [coverBoxRowsReferencePlatformStillCarriesTheCapturedAspect] so a curated-map edit renumbers these rows
 * loudly instead of silently.
 *
 * **Why a content variable enters a viewport-parity harness at all:** S2 made the cover box's width a
 * function of the platform's cover aspect (it is the ART's rendered width now, not the two-ratio column).
 * A row that measured the box without naming an aspect would be measuring nothing in particular.
 */
private const val LaunchCardReferencePlatformId = "gamecube"
private const val LaunchCardReferenceCoverAspect = 0.72f

/**
 * Class 1 — cover BOX centre share (NEW, launch-card content-offset ticket 2026-08-21, formula for coverBoxCentreXShare): `(boxLeft + boxRight) / 2 /
 * widthDp`. `boxRight` is **E**, the content box's own trailing edge — `widthDp - outerHorizontal`,
 * the right edge of the cover `Box` [HomeLaunchCardWide] places as the trailing, fixed-width child of a
 * `Row` that exactly fills its parent (`HomeLaunchCard.kt`); `boxLeft` is `E - boxWidth`.
 *
 * **RE-DERIVED 2026-08-21 (art-hugging box change).
 * Two terms of this formula moved, and the old text is replaced rather than amended because it now
 * describes a box that does not render.** Both changes are design-rule reversals, not tuning:
 *
 * 1. `boxWidth` is [homeLaunchCardCoverBoxWidth] — **the art's own rendered width** — not
 *    `layout.coverColumnWidth`. The art-hugging box replaces the centered-in-a-wider-box layout; see that production
 *    function's KDoc for the full reversal. Measuring the old box here would leave this instrument pinning
 *    a rectangle nothing paints — §2b's own recurring failure, which that spec records three times.
 * 2. No M-anchor term. It was `0dp` here at every viewport -- the production truth -- and the reservation
 *    is now retired product-wide, so there is no term to carry. Modelling 66dp would have put E 66dp left of
 *    where the `Row` actually ends, i.e. a box that hugs the art but floats.
 *
 * **This row gets WORSE under S2, deliberately, and its baselines are renumbered rather than its tolerance
 * widened.** The equal-area law makes the art's width near-constant in absolute dp across devices (Odin
 * 216.0, Nova 217.99 at 0.72), so once the box IS that width, its centre as a FRACTION of a 832.5dp
 * viewport and of a 640dp one must diverge more than a proportional box did. That is the arithmetic price
 * of the box change, recorded here as the honest red it is — the alternative (keep a proportional box) is the previous box model. **Never widened to green.**
 */
private fun launchCardCoverBoxCentreXShare(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    val boxRight = vp.widthDp - layout.outerHorizontal.value
    val boxLeft = boxRight - homeLaunchCardCoverBoxWidth(
        emulated = true,
        platformId = LaunchCardReferencePlatformId,
        layout = layout,
        // Explicit, not defaulted: this row measures the BOX COVER branch, and the 2026-08-22 cartridge-art change added a second branch whose box is a different width. Every baseline below is a cover
        // number; naming the branch here is what keeps them measuring the thing they were derived from.
        physicalMedia = false,
    ).value
    return ((boxLeft + boxRight) / 2f) / vp.widthDp
}

/**
 * **Structural — the launch card's own margin symmetry, in dp, target 0.0 at every viewport** (includes the Odin's former35.2dp residual). Mirrors [homeMarginSymmetry]'s shape —
 * an asymmetry is a defect at every viewport, so the expected value is a hand-written literal, not a
 * function of the Odin.
 *
 * `rightMargin - leftMargin`, where `leftMargin` is `outerHorizontal` (the text lane hangs off reference L
 * = content start) and `rightMargin` is `widthDp - artRightEdge`. It reduces to
 * `(boxWidth - artWidth) / 2` — the art's centring slack inside its box. It used to carry a leading M-lane
 * term as well; that term measured `0dp` at every viewport (production never reserved) and the reservation
 * is now retired product-wide, so only the slack remains, and it is zero:
 *
 * | | left | right BEFORE S2 | right AFTER | asymmetry before -> after |
 * |---|---|---|---|---|
 * | Odin 832.5x468.3 | 44.00 | 80.00 | **44.00** | 36.00 -> **0.00** |
 * | Nova 640x480 | 33.83 | 35.53 | **33.83** | 1.71 -> **0.00** |
 *
 * `[D]` at [LaunchCardReferenceCoverAspect]; the pre-S2 right margins are this same formula with
 * `boxWidth = layout.coverColumnWidth`.
 *
 * **What this row can and cannot catch, stated so silence is not read as proof.** `boxWidth` comes from
 * [homeLaunchCardCoverBoxWidth] (the BOX law) and `artWidth` from [homeLaunchCardRenderedArtSize] (the ART
 * law) — two distinct production functions. Reverting the box law to `layout.coverColumnWidth`, which is
 * precisely the design rule the art-hugging layout replaced, makes this row go red on the Odin by 36dp. It CANNOT
 * see a composable that stops calling the box law and hardcodes a width — the same limit every solver row
 * in this file carries, and the reason the render tree is device capture, not this harness
 * (§8 of the alignment spec). What it must never become is the two parity cells this repo shipped with the
 * SAME expression as expected and measured, asserting `66 == 66`: the expected here is a literal `0f`
 * hand-derived from the table above, the measured comes from the production path.
 */
private fun launchCardCoverArtMarginAsymmetryDp(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    val boxWidth = homeLaunchCardCoverBoxWidth(
        emulated = true,
        platformId = LaunchCardReferencePlatformId,
        layout = layout,
        physicalMedia = false,
    ).value
    val artWidth = homeLaunchCardRenderedArtSize(
        emulated = true,
        platformId = LaunchCardReferencePlatformId,
        maxWidth = layout.coverColumnWidth,
        maxHeight = layout.coverHeight,
        physicalMedia = false,
    ).width.value
    val leftMargin = layout.outerHorizontal.value
    val boxRight = vp.widthDp - leftMargin
    val artRight = boxRight - (boxWidth - artWidth) / 2f
    return (vp.widthDp - artRight) - leftMargin
}

/**
 * Metadata/art separation — 1.0/0.0
 * structural invariant, not a ratio: whether Metadata's vertical START is independent of the ART's height
 * (the Wide skeleton, `HomeLaunchCardWide` — art sits in its own `fillMaxHeight` column, a sibling of
 * header+metadata) rather than GATED by it (the stacked skeleton, `HomeLaunchCardCompact` — a `Row` whose
 * own box is as tall as the taller of [header, art], so Metadata's next-sibling position moved with the
 * ART's height even though only the HEADER's height should matter). Before this ticket the Nova sat on the
 * gated shape while the art alone grew 176→307.5dp (Defect A), measured (structurally, not device-rendered)
 * at ~71.6dp of metadata visible before scroll against a ~387.1dp weighted budget. The Odin is 1.0 by construction (Wide, unmoved by this ticket) —
 * asserted with ZERO tolerance so ANY certified STANDARD viewport that regresses to the gated shape goes
 * RED immediately, not only the three [homeLaunchCardUsesWideSkeleton] moves here.
 */
private fun launchCardMetadataIndependentOfArtHeight(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    return if (homeLaunchCardUsesWideSkeleton(vp.widthDp.dp, vp.heightDp.dp, layout)) 1f else 0f
}

/**
 * RC-3 Defect C fault #1  — the action
 * row's share of width. `homeLaunchCardActionRowWidth`'s own KDoc grounds why this is Class 2/3 ("chrome
 * affordances"/"type and art") HOLD content, not a Class-1 reservation that should become a fraction of
 * width: the 368dp sum is the row's own fixed-button content width, and every certified STANDARD viewport's
 * `contentWidth` already exceeds it, so `minOf` always resolves to the content sum, never the viewport.
 * Tracked here as an HONEST, ACCEPTED divergence (same shape as `artWidthShare`/`home.heroWidthShare`
 * above) — never silently converged, never widened.
 */
private fun launchCardActionRowWidthShare(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    return homeLaunchCardActionRowWidth(vp.widthDp.dp, layout).value / vp.widthDp
}

/**
 * RC-3 Defect C fault #2, FIXED (this ticket) — the action row's absolute dp offset from the CONTENT box's
 * own centre (not the M-inset-narrowed box the footer used to render inside). Reproduces the render tree's
 * own arithmetic — the Footer's `Box(fillMaxWidth, contentAlignment = Center)` is not narrowed by any
 * M-lane inset (there is none left to narrow it: no surface reserves for the orb, policy 2026-08-21),
 * so `(contentWidth - rowWidth) / 2` is the SAME slack on both sides by construction. Dp-held, target 0.0,
 * not a fraction. The old
 * (buggy) formula and its reproduction of the geometry doc's own measured -33.0/-32.8dp is characterized
 * separately, as a differential test, in `HomeLaunchCardTest.kt` — this function is the CURRENT production
 * formula only.
 */
private fun launchCardActionRowCentreOffsetDp(vp: MimirDpBox): Float {
    val layout = homeLaunchCardLayout(vp.widthDp.dp, vp.heightDp.dp)
    val contentWidth = vp.widthDp - layout.outerHorizontal.value * 2
    val rowWidth = homeLaunchCardActionRowWidth(vp.widthDp.dp, layout).value
    val rowLeft = (contentWidth - rowWidth) / 2f
    return (rowLeft + rowWidth / 2f) - contentWidth / 2f
}

/**
 * `HomeLaunchCard` is `fillMaxSize()` in an UN-INSET host [V `HomeLaunchCard.kt:609-615`] — unlike the
 * other three surfaces this file measures (which all solve against a post-inset box), this card receives
 * the FULL viewport. [MimirCertifiedViewport.launchCardInputDp] (defined once, in this module's
 * `HomeLaunchCardLayoutGoldenTest.kt`) is the mirror-image preference of `solverInputDp` — full viewport
 * first, post-inset box only as a fallback. For every row but the Odin this is numerically inert (the
 * other rows carry only one of the two boxes), but for the Odin it is the difference between 468.3dp (the
 * real production input) and 444dp (a different, real box for a different consumer). `chromeShare` itself
 * is width-only so this swap moves nothing measurable here (832.5dp vs 833dp is a 0.06% width difference,
 * well inside tolerance) — this is a column-selection correction for accuracy, not a renumbering of an
 * existing pin (see `MimirCertifiedViewports`' own KDoc on that distinction).
 */
private val LaunchCardOdinBox = MimirCertifiedViewports.Odin.launchCardInputDp()
private val LaunchCardChromeShareProportion = MimirSurfaceProportion(
    "launchCard.chromeShare",
    launchCardChromeShare(LaunchCardOdinBox),
    ClassOneTolerance,
)
private val LaunchCardArtHeightShareProportion = MimirSurfaceProportion(
    "launchCard.artHeightShare",
    launchCardArtHeightShare(LaunchCardOdinBox),
    ClassOneTolerance,
)
private val LaunchCardArtWidthShareProportion = MimirSurfaceProportion(
    "launchCard.artWidthShare",
    launchCardArtWidthShare(LaunchCardOdinBox),
    ClassOneTolerance,
)
private val LaunchCardTextLaneShareProportion = MimirSurfaceProportion(
    "launchCard.textLaneShare",
    launchCardTextLaneShare(LaunchCardOdinBox),
    ClassOneTolerance,
)
private val LaunchCardCoverBoxCentreXShareProportion = MimirSurfaceProportion(
    "launchCard.coverBoxCentreXShare",
    launchCardCoverBoxCentreXShare(LaunchCardOdinBox),
    ClassOneTolerance,
)

/**
 * Expected is a hand-written `0f` — the symmetry target, not a reading taken off the Odin. Same shape as
 * [HomeMarginSymmetryProportion]'s literal `1f`: symmetry is owed at every viewport, so deriving the
 * expectation from one device would let a shared defect pass on all of them. Dp-held with
 * [StructuralTolerance] (0.01dp), matching [LaunchCardActionRowCentreOffsetDpProportion].
 */
private val LaunchCardCoverArtMarginAsymmetryDpProportion = MimirSurfaceProportion(
    "launchCard.coverArtMarginAsymmetryDp",
    0f,
    StructuralTolerance,
)
private val LaunchCardMetadataIndependentOfArtHeightProportion = MimirSurfaceProportion(
    "launchCard.metadataIndependentOfArtHeight",
    1f,
    0f,
)
private val LaunchCardActionRowWidthShareProportion = MimirSurfaceProportion(
    "launchCard.actionRowWidthShare",
    launchCardActionRowWidthShare(LaunchCardOdinBox),
    ClassOneTolerance,
)
private val LaunchCardActionRowCentreOffsetDpProportion = MimirSurfaceProportion(
    "launchCard.actionRowCentreOffsetDp",
    launchCardActionRowCentreOffsetDp(LaunchCardOdinBox),
    0.01f,
)

private fun launchCardReadings(): List<MimirProportionReading> =
    MimirCertifiedViewports.standard.flatMap { viewport ->
        val box = viewport.launchCardInputDp()
        listOf(
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardChromeShareProportion,
                launchCardChromeShare(box),
            ),
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardArtHeightShareProportion,
                launchCardArtHeightShare(box),
            ),
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardArtWidthShareProportion,
                launchCardArtWidthShare(box),
            ),
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardTextLaneShareProportion,
                launchCardTextLaneShare(box),
            ),
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardCoverBoxCentreXShareProportion,
                launchCardCoverBoxCentreXShare(box),
            ),
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardCoverArtMarginAsymmetryDpProportion,
                launchCardCoverArtMarginAsymmetryDp(box),
            ),
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardMetadataIndependentOfArtHeightProportion,
                launchCardMetadataIndependentOfArtHeight(box),
            ),
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardActionRowWidthShareProportion,
                launchCardActionRowWidthShare(box),
            ),
            MimirProportionReading(
                "Launch Card (RC-3)",
                viewport.name,
                LaunchCardActionRowCentreOffsetDpProportion,
                launchCardActionRowCentreOffsetDp(box),
            ),
        )
    }

// ── THE BASELINE — a shrinking debt register, not a suppression list ───────────────────────────────────
//
// RC-1 step 4's own commissioning ticket named the problem with `redCells.isEmpty()`: a permanently-red
// assertion is a gate nobody reads, same failure mode as a green one that proves nothing. This entry type
// is what makes a red cell a tracked, ticketed, shrinkable debt instead of either extreme.
//
// A cell has exactly three states relative to its baseline row, and each is a distinct failure the build
// must catch — one mechanism, not three:
// 1. NEW — a red cell with no baseline row. A regression; this is the gate's entire purpose.
// 2. WORSENED — a red cell whose baseline row exists but whose current delta now exceeds the row's
//    recorded delta by more than [MimirOdinParityBaselineEpsilon]. A known divergence got worse without
//    anyone updating its ticket.
// 3. STALE — a baseline row whose cell is no longer red (fixed, or the pair no longer exists). Forces a
//    deletion, which is what makes the list shrink — without this, fixing a surface and forgetting to
//    delete its row leaves a permanent excuse behind.
//
// Verbatim duplicate of the `core-ui` copy's mechanism (same rationale as this file's other duplicated
// harness types) — diff them before trusting a claim sourced from just one.

internal data class MimirOdinParityBaselineEntry(
    val proportion: String,
    val viewport: String,
    val delta: Float,
    val ticket: String,
)

/**
 * Baseline deltas are hand-transcribed from this file's own `%.4f`-formatted report output from the two tests, recording only the actual failing cells and their deltas,
 * which rounds to the nearest 1e-4. That rounding is the only noise source here — the underlying formulas
 * are pure/deterministic Float arithmetic with no RNG, IO or threading, so a rerun against an unchanged
 * source reproduces the exact same Float bit-for-bit. Recording a delta at 4 decimal places can therefore
 * differ from the exact recomputed Float by up to ±0.00005 (half the last printed digit's step). Epsilon
 * is set to **0.0001 — 2× that rounding bound** — enough to never flag a WORSENED failure on transcription
 * rounding alone, while staying two to three orders of magnitude below every real divergence this harness
 * has found (smallest is 0.0275, i.e. 275× this epsilon).
 */
internal const val MimirOdinParityBaselineEpsilon = 0.0001f

internal fun mimirOdinParityBaselineFailures(
    readings: List<MimirProportionReading>,
    baseline: List<MimirOdinParityBaselineEntry>,
    epsilon: Float = MimirOdinParityBaselineEpsilon,
): List<String> {
    val redCells = readings.filterNot { it.pass }
    val baselineByKey = baseline.associateBy { it.proportion to it.viewport }
    val redByKey = redCells.associateBy { it.proportion.name to it.viewportName }

    val failures = mutableListOf<String>()
    redCells.forEach { red ->
        val entry = baselineByKey[red.proportion.name to red.viewportName]
        when {
            entry == null -> failures += "NEW DIVERGENCE (no baseline row): $red"
            red.delta > entry.delta + epsilon -> failures += "WORSENED (baseline delta=${entry.delta}, " +
                "ticket=${entry.ticket}, now delta=${red.delta}): $red"
        }
    }
    baseline.forEach { entry ->
        if (redByKey[entry.proportion to entry.viewport] == null) {
            failures += "STALE baseline row (no longer red — delete it): $entry"
        }
    }
    return failures
}

/**
 * The feature-library debt register. Every row is a real divergence measured from this file's own report
 * at the time of this ticket (2026-08-21):
 * - `home.heroWidthShare` (RC-2) — the Nova/538 hero is genuinely wider (proportionally) than the Odin's
 *   under the current seat-count threshold; see [homeHeroWidthShare]'s own KDoc for why this
 *   is a real, if surprising, divergence and not a computation bug.
 * - `library.chromeShareVertical` (RC-18) — the platform shelf's fixed vertical lane share still diverges
 *   at the Nova/RP5-class shelf/1080-square; RC-18 is the open ticket.
 * - `launchCard.chromeShare` (RC-3) — CONVERGED 2026-08-21, all four rows: `homeLaunchCardLayout`'s
 *   `outerHorizontal` is now a single global Odin-anchored proportional law (`homeLaunchCardOuterHorizontal`,
 *   `HomeLaunchCard.kt`), replacing independent per-band constants and the intermediate piecewise function.
 *   HomeLaunchCardTest.kt's 380/480/720/960dp pins were synthetic fixtures, not device measurements,
 *   and now match the proportional law (see homeLaunchCardOuterHorizontal's KDoc). A pure proportional law makes `outerHorizontal*2/width` constant
 *   at every width by construction, so all four previously-red rows (538dp companion, 1080dp large-square,
 *   Nova, RP5-class shelf) now pass `ClassOneTolerance` and are deleted below — see
 *   `HomeLaunchCardLayoutGoldenTest.chromeShareConvergesFullyUnderThePureLaw` for the pinned proof.
 * - `launchCard.artHeightShare` — art geometry still diverged while chromeShare was converged. FIXED, all four rows, FULLY: `homeLaunchCardCoverHeight` is now a single
 *   Odin-anchored proportional law over height (mirroring `homeLaunchCardOuterHorizontal`'s shape over
 *   width — see that function's KDoc, `HomeLaunchCard.kt`), so `coverHeight / heightDp` reduces
 *   algebraically to a constant regardless of height, the same mechanism that converged `chromeShare`. No
 *   baseline row needed — every viewport passes at delta 0.0000, confirmed from a real run.
 * - `launchCard.artWidthShare` — Further convergence: the RC-3
 *   entry below documented this row as "deliberately not expected to converge," an axis-decision cost, not
 *   shrinkable debt — that framing is now WRONG for three of its four rows. `coverColumnWidth` was PURELY
 *   height-anchored, and that is what starved `homeLaunchCardTextColumnWidth` on the Nova (295.2dp column on
 *   a 640dp screen, 46.1% of width vs the Odin's own 34.6%) into the truncation the regression report described. FIXED by
 *   a second, width-anchored Odin term taken as the tighter of two constraints (`minOf`; see
 *   `LaunchCardCoverColumnWidthCapRatio`'s KDoc, `HomeLaunchCard.kt`) — wherever that width term binds
 *   (every viewport narrower, relative to its own height, than the Odin's own 1.7778 aspect), this share
 *   reduces algebraically to the Odin's own ratio, delta 0.0000, the same mechanism that converged
 *   `chromeShare`. Three of the four rows below (Nova, the 538dp companion, the 1080dp square) go STALE and
 *   are DELETED, not re-zeroed. `Rp5Shelf` (aspect 2.0, past the Odin's 1.7778) still takes the OLD height
 *   term, unmoved by this ticket, and stays as the one remaining row. Disclosed, not silently absorbed: this
 *   fix narrows `coverColumnWidth` wherever it binds, which reduces the RENDERED art size (not the box) for
 *   platforms whose cover aspect exceeds ~0.743 (GB/GBA/PS1/SNES/N64/3DS/NDS families) below their own
 *   already-sub-64%-share baseline; this proportion measures the COLUMN, not per-platform pixels, so it does not and structurally
 *   cannot see that cost (same disclaimer [launchCardArtHeightShare]'s own KDoc already carries).
 *   **The word was "BOX" until S2 (2026-08-21) made that false:** `coverColumnWidth` is the art's solve
 *   BOUND now, painted nowhere — see this row's own KDoc and the two cover-box rows below for the box that
 *   does paint.
 * - `launchCard.textLaneShare` — Text-lane metric used for the Nova capture — none of `chromeShare`/`artWidthShare`/`artHeightShare` above
 *   would have caught a starved TEXT column, the exact "one converged proportion stood for a whole surface"
 *   failure the geometry doc names. Honestly RED at all four non-Odin STANDARD rows even after the
 *   `coverColumnWidth` fix, because `columnGap` is still a per-band literal (OWED, out of scope) — see
 *   `launchCardTextLaneShare`'s own KDoc for the full accounting. **RE-DERIVED** once
 *   `homeLaunchCardTextColumnWidth` stopped being blind to the M-anchor inset (see that function's own
 *   KDoc) — the instrument had been under-reporting this row's own starvation five-fold; all five values
 *   (the Odin reference plus all four measured rows) moved, deltas re-transcribed below from a real run.
 *   **DISCLOSED SEAM after S2 (2026-08-21), not fixed here and OWED:** this row now reports a LOWER BOUND
 *   of the rendered lane, twice over. (a) `homeLaunchCardTextColumnWidth` still subtracts
 *   `layout.coverColumnWidth`, but the cover box now renders at the ART's width, so the weighted text
 *   child actually receives the difference — on the Odin at aspect 0.72 the rendered lane is 492.5dp
 *   against the 420.5dp this function models, and at `snes`'s 1.36 the two agree exactly, because the
 *   quantity is now platform-dependent and this function has no platform input. (b) It still passes
 *   a 66dp M lane that production never rendered. **(b) is now CLOSED** — the reservation is retired
 *   product-wide  and the instrument models the rendered lane; that closure is what
 *   moved this row's four baselines below. (a) remains OWED and untouched.
 *   Recorded here so the number is read as what it is.
 * - `launchCard.coverBoxCentreXShare` — Recomputed after the art-hugging box change, the one row in this register whose debt
 *   deliberately GREW. The alignment spec §3/§7 kept the cover box at the full two-ratio column with the
 *   art centred inside it and tracked this row's divergence as accepted; the box was changed because on the Odin that
 *   centring slack is 36.0dp per side — an 80.0dp right margin against a 44.0dp left one. The box now hugs
 *   the art, so this row measures a near-constant absolute width against two different viewport widths and
 *   diverges MORE (Nova 0.0238 -> 0.0406, 1080 square 0.0182 -> 0.0288, Rp5Shelf newly red at 0.0144, the
 *   538 companion down slightly to 0.0432). Tolerance untouched; only the recorded debt moved. See the
 *   baseline block's own comment for the per-row transcription and `homeLaunchCardCoverBoxWidth`'s KDoc
 *   (`HomeLaunchCard.kt`) for the reversal in full.
 * - `launchCard.coverArtMarginAsymmetryDp` — Symmetric-margin metric, including the Odin's former 35.2dp residual. No baseline row: 0.0000dp at all
 *   five certified STANDARD viewports, i.e. the right margin equals the left one everywhere. This is the
 *   row the visible margin defect is measured against, and it is the reason the row above is allowed
 *   to get worse — the two are the same fix read from two different references (S = the viewport's centre
 *   vs L/E = the content edges), and the schema's own rule is that the element declares its reference.
 * - `launchCard.metadataIndependentOfArtHeight` (RC-3 Defect B, this ticket) — no row: converges FULLY at
 *   every certified STANDARD viewport (delta 0.0000). See `launchCardMetadataIndependentOfArtHeight`'s own
 *   KDoc — this is the actual structural fix for the "dead space under the header" defect, not a padding
 *   tweak, and it holds at the Odin (unmoved, Wide by construction) as well as the three viewports this
 *   ticket newly moves to Wide (Nova, RP5-class shelf, 538dp companion).
 * - `launchCard.actionRowWidthShare` (RC-3 Defect C fault #1, this ticket) — a NEW, honestly RED row at
 *   every non-Odin STANDARD viewport. `homeLaunchCardActionRowWidth`'s own KDoc grounds why this is
 *   intentional Class 2/3 HOLD content (the 368dp sum IS the row's own fixed-button content width, not an
 *   arbitrary cap) — tracked here rather than "fixed" by a formula change that would either shrink the
 *   buttons below their content or, worse, stretch gaps between them on wide screens. Deltas transcribed
 *   from this file's own `%.4f` report, one real run, 2026-08-21.
 * - `launchCard.actionRowCentreOffsetDp` (RC-3 Defect C fault #2, this ticket) — no row: converges FULLY,
 *   0.0000dp at every certified STANDARD viewport. This is the actual fix for the "row sits 33dp left of
 *   centre" defect — `HomeLaunchCard`'s composable body no longer applies the M-anchor's `primaryMButtonEndInset`
 *   to the Footer's own width, only to the header+art content above it (see that composable's own KDoc).
 *   `HomeLaunchCardTest.kt`'s `actionRowCentresInTheFullContentBoxNowThatTheMInsetNoLongerNarrowsTheFooter`
 *   differential-tests the OLD formula too, reproducing the geometry doc's own measured -33.0/-32.8dp.
 */
private val FeatureLibraryOdinParityBaseline = listOf(
    MimirOdinParityBaselineEntry(
        "home.heroWidthShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.0750f, "RC-2",
    ),
    MimirOdinParityBaselineEntry(
        "home.heroWidthShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.0750f, "RC-2",
    ),
    MimirOdinParityBaselineEntry(
        "library.chromeShareVertical", MimirCertifiedViewports.RetroidPocketNova.name, 0.0275f, "RC-18",
    ),
    MimirOdinParityBaselineEntry(
        "library.chromeShareVertical", MimirCertifiedViewports.Rp5Shelf.name, 0.0932f, "RC-18",
    ),
    MimirOdinParityBaselineEntry(
        "library.chromeShareVertical", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.2569f, "RC-18",
    ),
    // launchCard.chromeShare (RC-3): both rows (Nova, RP5-class shelf) CONVERGED 2026-08-21 under the pure
    // proportional law and are deleted -- see this file's own KDoc above and
    // HomeLaunchCardLayoutGoldenTest.chromeShareConvergesFullyUnderThePureLaw for the pinned proof. A stale
    // row here would fail the build as STALE (mimirOdinParityBaselineFailures), which is why it is gone
    // rather than merely re-zeroed.
    // launchCard.artHeightShare: no row -- converges FULLY at every viewport (delta 0.0000), see this file's
    // own KDoc above.
    // launchCard.artWidthShare: Nova/538-companion/1080-square rows CONVERGED FULLY 2026-08-21 (Regression 1,
    // this ticket) under the new minOf(heightAnchored, widthAnchored) law and are deleted here -- see this
    // file's own KDoc above. Rp5Shelf (aspect 2.0, past the Odin's own 1.7778) still takes the old height
    // term and is the one row left.
    MimirOdinParityBaselineEntry(
        "launchCard.artWidthShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0385f, "RC-3",
    ),
    // launchCard.textLaneShare: RESTORED to its pre-reservation values 2026-08-21, and every one of the
    // four is bit-exact against what this register itself pinned at `ddff2bb8` -- BEFORE the M-anchor inset
    // was ever threaded into this instrument (0.0120 / 0.0531 / 0.0061 / 0.0099, verified with `git show`,
    // not re-derived from this run). The Odin's own reference value likewise returns to 0.5051, exactly the
    // figure the superseded note below the S4 change recorded it moving away from (`0.5051 -> 0.4258`).
    //
    // **This is a corrected instrument, NOT a layout change.** S4 made `launchCardTextLaneShare` pass a
    // 66dp M lane on every reading, modelling the card as though it reserved for the orb. Production never
    // did: every call site passed `false`, so the rendered inset was `0.dp` at every viewport on both
    // certified devices. The reservation is now retired product-wide (no surface
    // reserves at all) and the parameter is gone, so this instrument can only model what renders. The
    // reservation-era rows (0.0119 / 0.0381 / 0.0373 / 0.0281) measured a counterfactual; these measure the
    // shipping layout, which has not moved a single dp in this pass.
    //
    // Still honestly RED at all four non-Odin rows: `columnGap`'s per-band literal (OWED, out of scope) is
    // the remaining mechanism, unchanged and untouched by any of this.
    MimirOdinParityBaselineEntry(
        "launchCard.textLaneShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.0120f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.textLaneShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0531f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.textLaneShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.0061f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.textLaneShare", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.0099f, "RC-3",
    ),
    // launchCard.coverBoxCentreXShare -- RENUMBERED 2026-08-21 (S2, POLICY, not tuning).
    //
    // The box model changed from centered-in-a-wider-box to art-hugging, so these deltas moved.
    // The cover box is now the ART's rendered width (`homeLaunchCardCoverBoxWidth`), and this row's E is
    // the production M lane -- 0dp since S1 (`a31256dc`) -- instead of a modelled 66dp. Both terms are
    // re-derived in launchCardCoverBoxCentreXShare's own KDoc.
    //
    // THE ROW GETS WORSE AND THAT IS THE ACCEPTED PRICE. The equal-area law keeps the art near-constant in
    // absolute dp across devices (Odin 216.0, Nova 217.99 at 0.72), so a box that IS that width cannot hold
    // a constant SHARE of two different viewport widths. The tolerance is untouched at ClassOneTolerance;
    // only the recorded debt moved, and every row below is transcribed from this file's own `%.4f` report,
    // one real run, 2026-08-21, post-S2:
    //   Nova            0.0238 -> 0.0406 (measured +0.7768 vs odin +0.8174)
    //   538 companion   0.0434 -> 0.0432 (measured +0.7742) -- shrank slightly; renumbered down, not left
    //                   stale at a debt that is no longer owed
    //   1080 square     0.0182 -> 0.0288 (measured +0.8462)
    //   Rp5Shelf        (no row) -> 0.0144 (measured +0.8318) -- NEW. The old comment here recorded it as
    //                   passing at 0.0042 "closer to partial cancellation than a clean convergence"; S2
    //                   removed the cancelling term (the 66dp lane both boxes carried), so what was left is
    //                   the real divergence. Not a regression this ticket introduced -- a cancellation this
    //                   ticket stopped hiding.
    // What CONVERGED instead is the margin-symmetry metric: launchCard.coverArtMarginAsymmetryDp
    // reads 0.0000dp at all five viewports (no rows below), i.e. the right margin now equals the left one
    // on every certified viewport. This row and that one are the same fix seen from two references.
    MimirOdinParityBaselineEntry(
        "launchCard.coverBoxCentreXShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.0406f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.coverBoxCentreXShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0144f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.coverBoxCentreXShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.0432f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.coverBoxCentreXShare", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.0288f, "RC-3",
    ),
    // launchCard.coverArtMarginAsymmetryDp: no row -- converges FULLY at every certified viewport
    // (0.0000dp), which is S2's whole deliverable. A row appearing here later means the cover box stopped
    // hugging its art.
    // launchCard.metadataIndependentOfArtHeight: no row -- converges FULLY at every viewport (delta 0.0000),
    // see this file's own KDoc above.
    // set the launch card's action gap at 12 dp (nominal row 376 dp); the sweep of 10-09 found the focus ring's halo touching the next chip across it, so the
    // gap is now the ring's whole reach, MimirFocusTileRingReach (14 dp), and the nominal row is 380 dp (368 before the gap change). This share was already red against Odin (the row is
    // wider than Odin's); the 4 dp it grew by widens it by 0.001-0.003 at each viewport. Accepted and tracked on the same ticket.
    MimirOdinParityBaselineEntry(
        "launchCard.actionRowWidthShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.1373f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.actionRowWidthShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0864f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.actionRowWidthShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.2499f, "RC-3",
    ),
    MimirOdinParityBaselineEntry(
        "launchCard.actionRowWidthShare", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.1046f, "RC-3",
    ),
    // launchCard.actionRowCentreOffsetDp: no row -- converges FULLY at every viewport (delta 0.0000dp), see
    // this file's own KDoc above.
)

// ── THE FULL REPORT ──────────────────────────────────────────────────────────────────────────────────

class MimirOdinParityTest {

    @Test
    fun mimirOdinParityReport() {
        val readings = homeShelfReadings() + libraryPlatformShelfReadings() +
            gamelistCarouselReadings() + launchCardReadings()
        val report = mimirProportionReport(readings)
        println(report)
        val failures = mimirOdinParityBaselineFailures(readings, FeatureLibraryOdinParityBaseline)
        assertTrue(
            "Odin-parity baseline violations (feature-library surfaces) — each is a NEW divergence (no " +
                "baseline row), a WORSENED one (delta grew past its baseline row), or a STALE row " +
                "(baseline claims red, cell now passes — delete the row). See " +
                "FeatureLibraryOdinParityBaseline for the debt register and each proportion's own KDoc " +
                "for which reds are accepted-and-tracked vs. owed.\n" +
                failures.joinToString("\n") + "\n\n$report",
            failures.isEmpty(),
        )
    }

    /**
     * S2 guard: the two cover-box rows are the first in this file whose value depends on CONTENT (a
     * platform's cover aspect) and not on the viewport alone, because S2 made the box the art's own
     * rendered width. If `PlatformCoverAspects` is ever re-measured for `gamecube` — the curated map is
     * explicitly a measured, revisable table (`GamelistCarousel.kt`) — both rows renumber. This fails
     * loudly at the source of the drift instead of surfacing as an unexplained WORSENED cell in a later
     * ticket's report.
     */
    @Test
    fun coverBoxRowsReferencePlatformStillCarriesTheCapturedAspect() {
        assertEquals(
            "launchCard.coverBoxCentreXShare / .coverArtMarginAsymmetryDp are derived at " +
                "$LaunchCardReferenceCoverAspect (`$LaunchCardReferencePlatformId`, the aspect every S1/S2 " +
                "device frame was captured at). If this changed, re-derive both rows' KDoc tables and " +
                "baselines from a real run — do not adjust the tolerance.",
            LaunchCardReferenceCoverAspect,
            platformCoverAspect(LaunchCardReferencePlatformId),
            0.0001f,
        )
    }

    /**
     * Falsifiability demonstration (ticket requirement), using the structurally-invariant platform-shelf
     * focus-to-rest ratio: corrupt its own [MimirSurfaceProportion.odinValue] and re-compare the same
     * measured value. No production file is touched.
     */
    @Test
    fun harnessProportionComparisonIsFalsifiable() {
        val measured = libraryFocusToRestRatio()
        val genuine = LibraryFocusToRestProportion
        val corrupted = genuine.copy(odinValue = genuine.odinValue + 1f)

        val genuineReading = MimirProportionReading("Library platform shelf (RC-18)", "Odin", genuine, measured)
        val corruptedReading = MimirProportionReading("Library platform shelf (RC-18)", "Odin", corrupted, measured)

        assertTrue(
            "sanity: the real Odin baseline must pass (it is defined from this same measured value)",
            genuineReading.pass,
        )
        assertTrue(
            "mutating the Odin baseline by +1 must flip an otherwise-passing cell to failing: $corruptedReading",
            !corruptedReading.pass,
        )
    }

    /**
     * Falsifiability demonstration for the STALE-entry rule specifically (ticket requirement: prove that
     * "a baseline row for a cell that passes must fail the build" — without this rule the baseline could
     * never shrink). Uses a synthetic baseline, never [FeatureLibraryOdinParityBaseline] itself, so the
     * real debt register stays untouched by this test.
     */
    @Test
    fun harnessBaselineStaleEntryFailsForAPassingCell() {
        val measured = libraryFocusToRestRatio()
        val passingReading = MimirProportionReading(
            "Library platform shelf (RC-18)",
            "Odin",
            LibraryFocusToRestProportion,
            measured,
        )
        assertTrue(
            "sanity: this cell must actually pass, or this test proves nothing",
            passingReading.pass,
        )

        val staleBaseline = listOf(
            MimirOdinParityBaselineEntry(LibraryFocusToRestProportion.name, "Odin", 5f, "RC-TEST"),
        )
        val failures = mimirOdinParityBaselineFailures(listOf(passingReading), staleBaseline)

        assertTrue(
            "a baseline row claiming a now-passing cell as red must fail as STALE: $failures",
            failures.any { it.contains("STALE") },
        )
    }
}
