package com.gruning.mimir.rewrite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * REAL-LAYOUT regression suite: STANDARD onboarding pages fit without scrolling.
 * `COMPACT_SQUARE` (RG-Rotate-class handhelds, `MimirViewportProfile.isCompactSquare`) KEEPS
 * `verticalScroll`, because on that posture some screens' natural content genuinely cannot fit — the
 * property that matters there is that content is *reachable by scrolling*, with the focus-reveal machinery
 * (`OnboardingFocusRevealState`/`onboardingFocusRevealDeltaPx`/the `LaunchedEffect` calling
 * `scrollState.scrollTo`) actually carrying focus into view, not "scrolls, but focus can land off-screen
 * anyway." Renders the actual [OnboardingFlow] composable tree under Robolectric (same technique as
 * `HomeTileMenuPanelWidthCapTest`) — deliberately NOT a pure-function test, because "does it fit" / "is it
 * reachable" are real-layout questions a pure function cannot answer.
 *
 * **Two measurement instruments, deliberately different, because one production behaviour makes a naive
 * bounds walk lie:**
 *
 * 1. [naturalHeightPx] — a custom [Layout] that measures the SAME composable tree with the target width
 *    fixed but height UNBOUNDED (`Constraints.Infinity`), reproducing exactly what `verticalScroll` gives
 *    its content. This is the TRUTH about how tall a screen's content wants to be, independent of whatever
 *    the viewport does to it.
 * 2. The plain bounded harness (`setUpBoundedHarness`) — the ACTUAL production shape (`fillMaxSize()`,
 *    posture-branched scroll exactly as `OnboardingScaffold` does it). On STANDARD this proves no
 *    scrollable node exists and catches a real hazard: **when a Compose `Column` receives a BOUNDED
 *    max-height constraint smaller than its children's natural total, it does not let trailing children
 *    overflow with correct size — it measures each subsequent child with the REMAINING budget, which
 *    shrinks toward zero.** Verified empirically pre-fix: the DONE screen's "Enter Mimir" button (40dp
 *    minimum touch height) measured 40px under an unbounded probe and 24px (BELOW its own minimum) under
 *    the real bounded no-scroll tree at the identical Odin viewport — same code, only the measurement
 *    regime differed. **A bounds-in-viewport walk under the bounded harness cannot see this: it reads
 *    "fits" precisely when a control has been crushed to make it "fit."** That is why instrument (1), not a
 *    bounds walk, gates the STANDARD fit assertion.
 *
 * **Compact-square "zero bounds" is clipping, not compression — proven, not assumed.** The scroll
 * restoration was validated with a discriminating experiment before trusting any semantics-bounds number on
 * that posture: a clickable control reporting `boundsInRoot = (0,0,0,0)` while `scrollState.maxValue > 0`
 * turned out to measure at its full natural size (e.g. 40px, never shrunken) the instant
 * `SemanticsNodeInteraction.performScrollTo()` brought it into view — and, separately, `authority
 * .requestFocus(key)` ALONE (no manual scroll call) produced the identical result, proving the
 * `LaunchedEffect` reveal machinery genuinely fires. `boundsInRoot` is clipped to ancestor bounds
 * (`Modifier.verticalScroll` applies `clipScrollableContainer`); a plain `Column` clips nothing, which is
 * why the STANDARD/no-scroll dumps show real out-of-range coordinates for overflow instead of zeros. So on
 * compact-square this suite gates *reachability* (in view, or reachable by scrolling) and *reveal wiring*
 * (a focus change alone scrolls the target into view), not raw unscrolled bounds.
 *
 * Viewports: the reference handheld (Odin, 1920x1080px @ density 369 => 832.5dp x 468.3dp,
 * landscape — the same figure `HomeTileMenuPanelWidthCapTest` cites [V]) gates the STANDARD assertions: the
 * reference geometry is measured. The compact-square probe set already used by
 * `MimirViewportProfileTest` for RG-Rotate-class devices (360/411/480/720dp per side, square) gates the
 * COMPACT_SQUARE assertions — `COMPACT_SQUARE` is a first-class posture value, not a minor
 * edge case. Other handhelds' exact dp (RP5, Thor) are UNKNOWN in this tree and are not invented here; the
 * 700x350 row below is the RP5-class shelf box, gated as a layout floor (2026-09-28), not as a device.
 * Since 2026-09-22 the full matrix ALSO gates: every STANDARD-posture row (Odin, Nova, QA emulator) must
 * fit, with the only tolerated overflows named in [knownStandardOverflows] — because a gate on the Odin
 * alone let WELCOME ship 36.6px over a real 411dp screen (D-08) while this suite stayed green.
 *
 * Robolectric's text metrics approximate but do not certify a real device's font rasterizer — every
 * assertion here is [D], not a substitute for an on-device pass.
 *
 * CRITICAL: without NATIVE graphics mode, Robolectric's text layout does not wrap at all — a throwaway probe
 * showed a 17-char and a 130-char string both measuring as a single line at LEGACY/default mode; NATIVE gave
 * 16px vs 48px (3 lines), matching a real line-break. Every number in this file is meaningless without it.
 */
private val fitTestPendingAccess = OnboardingAccessUi(
    usageAvailable = true,
    usageGranted = false,
    homeRoleAvailable = true,
    homeRoleHeld = false,
    filesGranted = false,
)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingScreenFitTest {

    @get:Rule
    val compose = createComposeRule()

    private data class FitViewport(val label: String, val widthDp: Float, val heightDp: Float)

    // Shared viewport fixture: dp values now come from MimirCertifiedViewports
    // instead of being hand-copied here -- this suite and HomeShelfGeometryTest previously carried two
    // independently-typed Odin numbers (832.5x468.3 here, 833x444 there) with nothing telling a reader of
    // either file they named different boxes (the "832.5 vs 833" ambiguity the research flagged). Labels are
    // preserved byte-for-byte so every existing lookup below (.first(), .label.contains(...)) is unaffected;
    // only the float literals' single source of truth moved.
    private val viewports = listOf(
        FitViewport(
            "Odin 1920x1080@369dpi (832.5x468.3dp, landscape)",
            MimirCertifiedViewports.Odin.fullViewportDp!!.widthDp,
            MimirCertifiedViewports.Odin.fullViewportDp.heightDp,
        ),
        // Retroid Pocket Nova, read off the device 2026-08-21: model "Retroid Pocket Nova" (Moorechip
        // "kalama"), Android 13 / SDK 33, 960x1280 physical at density 320 -> 640x480dp landscape. Derived
        // the same way the Odin row above is (physical size converted at device density, no inset
        // subtraction) so the two are comparable.
        //
        // This is NOT just another row. Its landscape aspect is 640/480 = 1.333, and
        // MimirViewportProfile's CompactSquareMaximumAspect is 1.12 -- so the Nova is STANDARD, which
        // means it takes the NO-SCROLL branch and every screen must FIT in 480dp. It is 11.7dp TALLER
        // than the Odin but 192.5dp NARROWER, and narrow is the hazard here: less width means more line
        // wrapping, which makes content TALLER. A screen that fits the Odin's wider viewport is not
        // thereby proven to fit this one.
        FitViewport(
            "Retroid Pocket Nova 960x1280@320dpi (640x480dp, landscape)",
            MimirCertifiedViewports.RetroidPocketNova.fullViewportDp!!.widthDp,
            MimirCertifiedViewports.RetroidPocketNova.fullViewportDp.heightDp,
        ),
        FitViewport(
            "compact-square 720dp",
            MimirCertifiedViewports.CompactSquare720.solverInputDp.widthDp,
            MimirCertifiedViewports.CompactSquare720.solverInputDp.heightDp,
        ),
        FitViewport(
            "compact-square 480dp",
            MimirCertifiedViewports.CompactSquare480.solverInputDp.widthDp,
            MimirCertifiedViewports.CompactSquare480.solverInputDp.heightDp,
        ),
        FitViewport(
            "compact-square 411dp",
            MimirCertifiedViewports.CompactSquare411.solverInputDp.widthDp,
            MimirCertifiedViewports.CompactSquare411.solverInputDp.heightDp,
        ),
        FitViewport(
            "compact-square 360dp",
            MimirCertifiedViewports.CompactSquare360.solverInputDp.widthDp,
            MimirCertifiedViewports.CompactSquare360.solverInputDp.heightDp,
        ),
        // The QA emulator, added 2026-09-22 after the Alpha pass watched WELCOME's Begin pill get sliced in
        // half on emulator-5580 while this whole suite stayed green. At 411.4dp it is the SHORTEST STANDARD
        // viewport in this list — shorter than the Odin (468.3) and the Nova (480) — and its 2.22 aspect is
        // nowhere near CompactSquareMaximumAspect, so it takes the NO-SCROLL branch where an overflow has
        // nothing to hide behind.
        //
        // The gap this closes is specific: every STANDARD row here was ~468dp or taller, so "fits the
        // shortest box we test" meant 468dp, and a page that needed 460 passed while a real 411dp screen
        // clipped it. Appended last so `viewports.first()` and every `label.contains(...)` lookup below are
        // unaffected.
        FitViewport(
            "QA emulator 2400x1080@420dpi (914.3x411.4dp, landscape)",
            MimirCertifiedViewports.QaEmulator.fullViewportDp!!.widthDp,
            MimirCertifiedViewports.QaEmulator.fullViewportDp.heightDp,
        ),
        // The RG Rotate (compact review 2026-09-27), appended so `viewports.first()` and every label lookup
        // above keep their rows: bar hidden, then under the reported (unmeasured) 24dp bar.
        FitViewport(
            "RG Rotate 720x720@204dpi, bar hidden (564.7x564.7dp)",
            MimirCertifiedViewports.RgRotate.fullViewportDp!!.widthDp,
            MimirCertifiedViewports.RgRotate.fullViewportDp.heightDp,
        ),
        FitViewport(
            "RG Rotate 720x720@204dpi, under a 24dp bar (564.7x540.7dp)",
            MimirCertifiedViewports.RgRotate.postInsetShelfDp!!.widthDp,
            MimirCertifiedViewports.RgRotate.postInsetShelfDp.heightDp,
        ),
        // An explicit viewport floor, not a measured device.
        // Rp5Shelf's 700x350 is a post-inset shelf box whose device identity is disputed; onboarding is gated
        // on it anyway, as the shortest STANDARD box a page must fit. At 350dp it is 61dp shorter than
        // the QA emulator. Appended last, like that row, so every positional lookup above is unaffected.
        FitViewport(
            "RP5-class shelf box 700x350dp (specified floor)",
            MimirCertifiedViewports.Rp5Shelf.solverInputDp.widthDp,
            MimirCertifiedViewports.Rp5Shelf.solverInputDp.heightDp,
        ),
    )
    private val odin = viewports.first()

    /** The font scales the matrix measures every pair at.
     *
     * Android's font size slider is not a rare accessibility corner — it is a first-screen Settings
     * control, and `sp` text grows with it while the surrounding `dp` spacing does not. Until 2026-09-23
     * this whole suite pinned `fontScale = 1f` at every one of its measurement sites, which is exactly why
     * the D-08 repair did not generalise: WELCOME was fixed at 1.0 and stayed broken everywhere above it.
     *
     * `1.30` and `2.0` are the two the device sweep on 2026-09-23 actually measured (see D-14 and D-15 in
     * the Alpha evidence run), so they are the two with on-glass photographs to check this instrument
     * against. `1.0` stays first so the default posture is still measured by the same code path.
     *
     * `1.30` is the TOP STOP of the system font-size slider on the SDK 33 fixture these numbers were
     * measured on: `entryvalues_font_size` in that build's Settings reads `["0.85", "1.0", "1.15",
     * "1.30"]`, verified 2026-09-23 by dumping the installed Settings package. So everything the 1.30
     * rows describe is reachable by a player dragging one control.
     *
     * `2.0` is NOT a slider stop there - it was set with `settings put system font_scale 2.0`. It is
     * measured anyway, for two reasons: SDK 34+ raises the slider's top stop into that range, so the
     * 2.0 rows are where this suite will be standing when the app meets a newer chassis; and a screen
     * that loses both of its controls somewhere between 1.30 and 2.0 has a problem whose threshold this
     * axis does not yet locate. Do not read a 2.0 row as "a player did this by dragging a slider" on
     * SDK 33 - that claim belongs to the 1.30 rows only. */
    private val fitFontScales = listOf(1f, 1.3f, 2f)

    /** A STANDARD-posture scenario x viewport pair that is KNOWN to overflow, measured, and deliberately
     * tolerated by the matrix gate — for now. Both labels are exact-match lookups against [Scenario.label]
     * and [FitViewport.label], so renaming either side orphans the entry, and the gate fails on an orphan
     * rather than letting the exception quietly stop applying to anything. */
    private data class KnownStandardOverflow(
        val scenarioLabel: String,
        val viewportLabel: String,
        /** The overflow measured when the entry was written, in px at density 1. The gate holds the pair
         * to THIS number: worse fails (the exception may not rot), and "fits now" ALSO fails (a fixed
         * screen must have its exception deleted, or the list tells the next reader a lie). */
        val measuredOverflowPx: Float,
        val why: String,
    )

    // The matrix below gates every STANDARD pair on zero overflow. This list is the whole of what it
    // tolerates, and each entry is a debt with a number on it, not a permission. An entry is legitimate
    // only when closing it needs a decision the test cannot make — a content cut or a reopened ruling —
    // and it names that decision so the person who eventually makes it knows what to delete.
    //
    // Why a list here and not a wider tolerance or a skipped viewport: a tolerance wide enough to admit
    // 39.6px would admit the 36.6px WELCOME overflow that shipped as D-08, and dropping the QA emulator
    // row would put the suite back where it was when D-08 went green. Naming the one pair keeps every
    // other STANDARD pair under a real gate.
    // Emptied 2026-09-28: its one entry was DONE/TAIL_DEVICE on the QA emulator (39.6px over), which the layout change closed by folding DEVICE, MEDIA and TOMES into FINISH. Every STANDARD pair is now gated on zero overflow.
    private val knownStandardOverflows = emptyList<KnownStandardOverflow>()

    /** The measured raised-font-scale overflow debt for one STANDARD viewport at one non-default scale.
     *
     * The default scale ([1f]) is gated pair by pair above, because it is nearly clean and every remaining
     * exception is individually arguable. The raised scales are not: on 2026-09-23 a device sweep found
     * the whole onboarding flow overflowing at 1.30 and 2.0, and listing 87 individual exceptions would
     * be a capitulation wearing a ratchet's clothes — nobody reads an 87-entry allow-list, and one more
     * line slipping into it would never be noticed.
     *
     * So the raised scales are gated by a COUNT with a number on it, per viewport per scale. The number
     * may not grow and it may not silently shrink; either way the test goes red and says what the new
     * number is. That is the property that matters while the cluster is open: a repair lands, the count
     * drops, the suite fails, and whoever landed it has to write the smaller number down. When the count
     * reaches zero the entry is deleted and that viewport/scale joins the pair-by-pair gate.
     *
     * The open defects these numbers describe are **D-14 (P2, `font_scale 1.30`)** and **D-15 (P1,
     * `font_scale 2.0`)** in the Alpha evidence run. D-15 blocks the gate; these numbers are the debt,
     * not permission to keep it. */
    private data class RaisedScaleDebt(
        val viewportLabel: String,
        val fontScale: Float,
        /** How many scenarios overflowed this viewport at this scale when the entry was written. Held
         * EXACTLY: more is a regression, fewer means a repair landed and the number is now a lie. */
        val overflowingScenarios: Int,
        /** The worst single overflow in that group, in px. Held one-directionally - it may not grow,
         * because a screen can get dramatically worse without the count moving at all - but it is not
         * failed for shrinking, since a one-line copy edit moves it and the count is the load-bearing
         * half of this ratchet.
         *
         * KNOWN BLIND SPOT, accepted deliberately: a SWAP is invisible here. Repair one screen while
         * another regresses by the same kind of amount, in one commit, and the count is unchanged and
         * the maximum is unchanged, so nothing fires. Closing it means holding the SET of overflowing
         * labels per group, which is the 87 strings this ledger exists to avoid. The trade is worth
         * naming rather than hiding: while the cluster is open this gate answers "did the debt grow",
         * not "is it the same debt". Once the counts come down far enough that the sets are small, hold
         * the labels instead. */
        val worstOverflowPx: Float,
    )

    // Measured 2026-09-23 on this machine, by this test, at density 1f. Not device numbers: the device
    // sweep measured a DIFFERENT thing (whether a control rendered at all), and the two are related but
    // not equal — overflow is the upstream condition, and a control disappears once the overflow exceeds
    // its own height. This probe is therefore the stricter, earlier signal, which is what a gate wants.
    private val raisedScaleDebt = listOf(
        // Re-measured 2026-09-28 when the DONE tail folded into FINISH (policy Q3): the tail's pages were
        // the worst overflow in every group, and FINISH replaces four of them with one.
        // Re-measured again the same day when the header became one 24 dp strip (policy Q4, slice 12), and
        // once more when WELCOME's controller lines were cut. Groups that reached 0 keep an entry at 0, because
        // the gate requires one entry per raised group.
        // The footer reserves the pill's ring and flare (OnboardingFooterClearance, 16 -> 23 dp), so
        // every page is 7 dp taller and WELCOME (two gaps sm -> xs) 1 dp shorter. Each change below was diffed
        // against the parent commit's matrix: every worst grew by exactly 7.0, and each page that joined was at
        // most 2 px over (the tolerance) before. QA 2.0 +1 (SPECIAL_DEVICE_ACCESS, 2.6); RP5 1.3 +3 (USAGE and
        // TWEAKER_USAGE 6.0, FINISH 4.0); RP5 2.0 +1 (LIBRARY idle, 2.0 -> 9.0).
        // A page per action during scan and four permission cards on ORIENTATION added content
        // (2026-09-28). Every group's worst is unchanged, and the joiners are those
        // rows alone. QA 2.0 +1 and RP5 1.3 +1 (ORIENTATION, 4.6 and 4.0 over); RP5 2.0 +5 (the five pages, 18.0 each;
        // ORIENTATION was already there). The probe measures each picture at its minimum; on a device at a raised
        // scale ORIENTATION's cards drop their pictures first (onboardingPermissionSceneHeight), so it is shorter
        // there than measured here.
        // The seven permission screens use fewer words and a picture (2026-09-28). Each
        // lost its how-to sentences, so the debt came down: Odin 2.0 9 -> 3, Nova 2.0 8 -> 3, QA 2.0 13 -> 5, and the
        // floor at 1.3 10 -> 5 and at 2.0 22 -> 20. The probe measures each picture at its minimum. On a
        // device at 2.0 the permission screens drop their pictures (onboardingAccessSceneHeight returns null), so
        // they are shorter there than measured here.
        RaisedScaleDebt("Odin 1920x1080@369dpi (832.5x468.3dp, landscape)", 1.3f, 0, 0f),
        // Large-font layout task, 2026-10-06 evening (the recorded debt was 3 -> 4, the worst 138.7 held): the header strip keeps its
        // ruled height at a raised scale (26 dp, where it was 44 dp at 2.0), which takes DONE/FINISH (7.7 over) back under, and the capability rows set their
        // pill on the title's line where that is shorter. Back to 3: WELCOME (5.7 over, no strip), METADATA (both). The worst is METADATA with an
        // account, 112.7 -> 66.7 (measured by the gate run).
        // WELCOME at a raised scale: the lockup keeps its default type and sits tighter, the promise and the verse take the width,
        // so WELCOME fits here now (5.7 over before). Two now: METADATA (both). The worst is unchanged.
        RaisedScaleDebt("Odin 1920x1080@369dpi (832.5x468.3dp, landscape)", 2.0f, 2, 66.7f),
        RaisedScaleDebt("Retroid Pocket Nova 960x1280@320dpi (640x480dp, landscape)", 1.3f, 0, 0f),
        // Large-font layout task, 2026-10-06 evening: the worst was METADATA 169.0 (the recorded debt was 161.0 -> 169.0, the third
        // row, RetroAchievements). The header strip's 18 dp and the capability rows' flow take it to 89.0, the figure measured by the gate run;
        // the count did not move: WELCOME (28.0 over, no strip) and METADATA (both).
        // WELCOME fits here now (28.0 over before). Two now: METADATA (both). The worst is unchanged.
        RaisedScaleDebt("Retroid Pocket Nova 960x1280@320dpi (640x480dp, landscape)", 2.0f, 2, 89.0f),
        RaisedScaleDebt("QA emulator 2400x1080@420dpi (914.3x411.4dp, landscape)", 1.3f, 0, 0f),
        // Large-font layout task, 2026-10-06 evening (the recorded debt was 5 -> 6, the worst 195.6 held): the header strip's 18 dp
        // takes ACCESS/ORIENTATION (4.6 over) back under. Back to 5: WELCOME, METADATA (both), DONE/FINISH (46.6 over) and DONE/FINISH without
        // the M button (8.6 over). The worst is METADATA with an account, 169.6 -> 123.6.
        // WELCOME fits here now (62.6 over before). Four now: METADATA (both), DONE/FINISH and DONE/FINISH without the M button. The worst is unchanged.
        RaisedScaleDebt("QA emulator 2400x1080@420dpi (914.3x411.4dp, landscape)", 2.0f, 4, 123.6f),
        // The 700x350 layout floor, first measured 2026-09-28. Raised scales there are debt like the rows
        // above (recorded as layout debt, like Odin); the default scale fits.
        // FINISH as a list  fits at 1.0 and briefly joined this row at 1.3, 25px over;
        // dropping its SELECT row (policy the same day) brought it back under at 1.3, and under the QA
        // emulator's 2.0 as well. The hint carousel took LIBRARY's ask off its DONE page (it is answered by
        // then), which brought LIBRARY (done) under at 2.0: 17 -> 16.
        // The updated wording (2026-09-28), diffed against the parent's matrix (3cec7c822): at
        // 2.0, FILES joined (0 -> 33.0; "Choose folders in Mimir, with box art beside your games." wraps), and the A and
        // M action pages grew 18.0 -> 52.0 as their where-lines wrap. Nothing else moved, and the worst is unchanged.
        // Large-font layout task, 2026-10-06 evening (the recorded debt was 5 -> 6, the worst 51.0 held): the header strip is 26 dp
        // at 1.3 rather than 30, which takes ACCESS/ORIENTATION and DONE/FINISH without the M button (both 4.0 over) back under. Four now:
        // WELCOME (38.0, no strip), METADATA (both) and DONE/FINISH. The held worst, 45.0, is METADATA, as the gate run measured it.
        // WELCOME fits here now (38.0 over before). Three now: METADATA (both) and DONE/FINISH. The worst, METADATA, is unchanged.
        RaisedScaleDebt("RP5-class shelf box 700x350dp (specified floor)", 1.3f, 3, 45.0f),
        // The Cocoon/iiSU wording rows (2026-09-28) shortened more than they lengthened: at 2.0 the count fell
        // 21 -> 20 (USAGE now measures 349 of 350 and LIBRARY idle 325), and the worst, METADATA with an account,
        // fell 291.0 -> 257.0 as the SteamGridDB line lost a line.
        // The update removed the built-in controls screen (2026-09-29), one of those twenty: 20 -> 19, worst unchanged.
        // OR-12 moved WRITE_SETTINGS (both brightness modes) and TWEAKER_USAGE out of setup, three of those rows:
        // 19 -> 16. The content pass shortened METADATA's connected ScreenScraper line, and the worst, still METADATA
        // with an account, fell 257.0 -> 223.0.
        // Large-font layout task, 2026-10-06 evening: the worst, METADATA with an account, was 265.0 (the recorded debt; 223.0 before
        // the RetroAchievements row made the page three rows tall). The header strip takes 18 dp off it and the capability rows' flow, which sets a
        // pill on the title's line and runs the detail the row's width, takes more: to 219.0 as the gate run measured it, under the 223.0 it was. The count did not move; every page
        // that overflowed still does, 13.0 over at the least.
        RaisedScaleDebt("RP5-class shelf box 700x350dp (specified floor)", 2.0f, 16, 219.0f),
    )

    private data class Scenario(
        val label: String,
        val step: OnboardingStep,
        val accessScreenId: OnboardingAccessScreenId? = null,
        /** A distinctive substring this scenario, and ONLY this scenario, must actually render — a stale
         * render (harness bug: same sub-screen composed under a different label) fails loudly instead of
         * silently producing a plausible-looking wrong number. */
        val contentPin: String,
        val access: OnboardingAccessUi = fitTestPendingAccess,
        val libraryStatus: OnboardingLibraryStatus = OnboardingLibraryStatus.IDLE,
        /** LIBRARY only: the action page on screen, or null once the pages have ended. */
        val tourPage: Int? = null,
        val gameCount: Int = 0,
        val platformCount: Int = 0,
        val account: String? = null,
        /** FINISH only: whether the legend names the M button. Production decides this with
         * [onboardingTeachesMButton]; the matrix carries BOTH variants because both are reachable and they
         * measure differently. Defaults to the fuller legend so every other scenario is unaffected. */
        val teachMButton: Boolean = true,
        val settlement: OnboardingSettlementUi = OnboardingSettlementUi(
            phase = OnboardingSettlementPhase.IDENTIFYING,
            title = "Finishing your library",
            detail = "No artwork is downloaded.",
        ),
    )

    private val scenarios = listOf(
        Scenario("WELCOME", OnboardingStep.WELCOME, contentPin = "Mimir organizes the games you already have."),
        Scenario("PROFILE", OnboardingStep.PROFILE, contentPin = "playing?"),
        Scenario(
            "ACCESS/ORIENTATION",
            OnboardingStep.ACCESS,
            OnboardingAccessScreenId.ORIENTATION,
            contentPin = "A few optional permissions",
        ),
        Scenario(
            "ACCESS/FILES (not granted)",
            OnboardingStep.ACCESS,
            OnboardingAccessScreenId.FILES,
            contentPin = "Let Mimir use your game folders",
        ),
        Scenario(
            "ACCESS/USAGE (not granted)",
            OnboardingStep.ACCESS,
            OnboardingAccessScreenId.USAGE,
            contentPin = "see when a game is running",
        ),
        // OVERLAY (QA-10) joined the walk without joining this list, so it had not been measured at any viewport.
        // Its grant defaults to held in OnboardingAccessUi, which would render Allowed + Continue, so the
        // scenario holds it back.
        Scenario(
            "ACCESS/OVERLAY (not granted)",
            OnboardingStep.ACCESS,
            OnboardingAccessScreenId.OVERLAY,
            contentPin = "display over other apps",
            access = fitTestPendingAccess.copy(overlayGranted = false),
        ),
        Scenario(
            "ACCESS/HOME_ROLE (not granted)",
            OnboardingStep.ACCESS,
            OnboardingAccessScreenId.HOME_ROLE,
            // Its title, "Make Mimir your Home app", is also the start of FINISH's row; this line is its own.
            contentPin = "land straight in Mimir",
        ),
        Scenario(
            "LIBRARY (idle)",
            OnboardingStep.LIBRARY,
            libraryStatus = OnboardingLibraryStatus.IDLE,
            contentPin = "Show Mimir your games",
        ),
        //  a page per action during the scan, under the scan's status line. A page's
        // picture has no viewport in a natural-height probe and measures at its floor here. Every page is a row,
        // because each has its own words and picture.
        Scenario(
            "LIBRARY (scanning)",
            OnboardingStep.LIBRARY,
            libraryStatus = OnboardingLibraryStatus.SCANNING,
            tourPage = 0,
            contentPin = onboardingTestResources().getString(R.string.app_onboarding_library_building_promise),
        ),
        Scenario(
            "LIBRARY (page 2, Y edit)",
            OnboardingStep.LIBRARY,
            libraryStatus = OnboardingLibraryStatus.SCANNING,
            tourPage = 1,
            contentPin = "Edit a game",
        ),
        Scenario(
            "LIBRARY (page 3, X Tomes)",
            OnboardingStep.LIBRARY,
            libraryStatus = OnboardingLibraryStatus.SCANNING,
            tourPage = 2,
            contentPin = "Open Tomes",
        ),
        Scenario(
            "LIBRARY (page 4, the dock)",
            OnboardingStep.LIBRARY,
            libraryStatus = OnboardingLibraryStatus.SCANNING,
            tourPage = 3,
            contentPin = "Library and Settings",
        ),
        Scenario(
            "LIBRARY (page 5, M menu; the scan already done)",
            OnboardingStep.LIBRARY,
            libraryStatus = OnboardingLibraryStatus.DONE,
            tourPage = 4,
            gameCount = 128,
            platformCount = 6,
            contentPin = "Hold for menu",
        ),
        Scenario(
            "LIBRARY (scanning, pages ended)",
            OnboardingStep.LIBRARY,
            libraryStatus = OnboardingLibraryStatus.SCANNING,
            contentPin = onboardingTestResources().getString(R.string.app_onboarding_library_building_promise),
        ),
        Scenario(
            "LIBRARY (done)",
            OnboardingStep.LIBRARY,
            libraryStatus = OnboardingLibraryStatus.DONE,
            gameCount = 128,
            platformCount = 6,
            contentPin = "across 6 platforms",
        ),
        Scenario(
            "METADATA (no account)",
            OnboardingStep.METADATA,
            account = null,
            // Read "Identify your library" until this screen was retitled 2026-09-17: it is the
            // accounts screen, and that title made it read as a duplicate of the SETTLING screen that
            // follows it. The pin is an identity anchor -- it only has to prove the harness rendered THIS
            // screen -- so it moves with the title rather than pinning a sentence the product no longer says.
            contentPin = "Add game details and art",
        ),
        Scenario(
            "METADATA (connected account)",
            OnboardingStep.METADATA,
            account = "player123",
            contentPin = "Connected as player123",
        ),
        // SETTLING's bare "Working" was replaced with
        // a real "N of M checked" counter, and this matrix probed SETTLING only in its FAILED phase — so
        // nothing here ever composed the screen in the state the new counter actually renders in, and the
        // suite would have gone green whether the counter fitted or not. Four-digit done AND total give the
        // widest string the counter can produce for a plausible library, so this is the worst case, not a
        // typical one.
        Scenario(
            "SETTLING (IDENTIFYING, real fraction, widest 4-digit counter)",
            OnboardingStep.SETTLING,
            contentPin = "of 1284 checked",
            settlement = OnboardingSettlementUi(
                phase = OnboardingSettlementPhase.IDENTIFYING,
                title = "Finishing your library",
                detail = "No artwork is downloaded.",
                done = 947,
                total = 1284,
            ),
        ),
        Scenario(
            "SETTLING (FAILED, real production copy, canRetry) — OUT OF SCOPE, must not silently overflow",
            OnboardingStep.SETTLING,
            contentPin = "Library setup needs attention",
            settlement = OnboardingSettlementUi(
                phase = OnboardingSettlementPhase.FAILED,
                title = "Library setup needs attention",
                detail = "Choose your games folder again.",
                canRetry = true,
            ),
        ),
        // DONE is one FINISH page since 2026-09-28 (policy Q3: DEVICE, MEDIA and TOMES folded into it).
        // Its legend and home row vary with the device and with what the player granted, so the matrix carries
        // the tallest reachable variant (home row offered, M named) and the two that drop a row.
        Scenario(
            "DONE/FINISH",
            OnboardingStep.DONE,
            contentPin = "Default apps",
        ),
        Scenario(
            "DONE/FINISH (no M button on this chassis)",
            OnboardingStep.DONE,
            contentPin = "Next: your Home shelf",
            teachMButton = false,
        ),
        Scenario(
            "DONE/FINISH (Mimir already home)",
            OnboardingStep.DONE,
            contentPin = "Next: your Home shelf",
            access = fitTestPendingAccess.copy(homeRoleHeld = true),
        ),
    )

    /** Measures [content] with a FIXED width ([widthDp]) but UNBOUNDED height, and reports the natural
     * height it needs in [onMeasured] (px, at density 1 so px == dp numerically in this harness). This is
     * what `verticalScroll` used to give its content — the true content height, independent of viewport. */
    @androidx.compose.runtime.Composable
    private fun NaturalHeightProbe(widthDp: Float, onMeasured: (Int) -> Unit, content: @androidx.compose.runtime.Composable () -> Unit) {
        val density = LocalDensity.current
        val widthPx = with(density) { widthDp.dp.roundToPx() }
        Layout(content = content) { measurables, _ ->
            val placeable = measurables.first().measure(
                Constraints(minWidth = widthPx, maxWidth = widthPx, minHeight = 0, maxHeight = Constraints.Infinity),
            )
            onMeasured(placeable.height)
            layout(widthPx, placeable.height) { placeable.placeRelative(0, 0) }
        }
    }

    private class OnboardingFlowState {
        val authority = DefaultFocusAuthority()
        val actionTour = OnboardingActionTourState()
        val profile = OnboardingProfileState()
        val library = OnboardingLibraryState()
        val accessReview = OnboardingAccessReviewState()
        val focusIntent = OnboardingFocusIntentState()
    }

    @androidx.compose.runtime.Composable
    private fun RenderScenario(state: OnboardingFlowState, s: Scenario) {
        OnboardingFlow(
            authority = state.authority,
            step = s.step,
            profile = state.profile,
            accessReview = state.accessReview,
            focusIntent = state.focusIntent,
            access = s.access,
            library = state.library,
            actionTour = state.actionTour,
            libraryGameCount = s.gameCount,
            libraryPlatformCount = s.platformCount,
            artworkAccount = s.account,
            steamGridDbKeyPresent = false,
            settlement = s.settlement,
            currentThemeId = MimirThemeId.DEFAULT,
            teachMButton = s.teachMButton,
            onAdvance = {},
            onCommitName = {},
            onRequestFilesAccess = {},
            onRequestUsageAccess = {},
            onRequestHomeRole = {},
            onChooseFolder = {},
            onOpenArtworkSetup = {},
            onOpenSteamGridDbKey = {},
            onRetrySettlement = {},
            onContinueLocally = {},
            onApplyTheme = {},
        )
    }

    private fun applyScenario(state: OnboardingFlowState, sc: Scenario) {
        state.library.status = sc.libraryStatus
        sc.tourPage?.let(state.actionTour::showForTest) ?: state.actionTour.skip()
        state.accessReview.goToScreen(sc.accessScreenId ?: OnboardingAccessScreenId.ORIENTATION)
    }

    private fun assertContentPin(sc: Scenario, vpLabel: String) {
        compose.onNode(
            hasText(sc.contentPin, substring = true) or hasContentDescription(sc.contentPin, substring = true),
            useUnmergedTree = true,
        ).assertExists(
            "scenario '${sc.label}' at $vpLabel did not compose its content pin \"${sc.contentPin}\" — the " +
                "harness rendered something else",
        )
    }

    // ── Instrument 1: natural content height (Layout-based, unbounded height) ──────────────────────────

    @Test
    fun `natural content height fits the Odin viewport for every onboarding screen`() {
        val density = Density(density = 1f, fontScale = 1f)
        var heightPx by mutableIntStateOf(0)
        var scenario by mutableStateOf(scenarios.first())
        val state = OnboardingFlowState()

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    NaturalHeightProbe(widthDp = odin.widthDp, onMeasured = { heightPx = it }) {
                        RenderScenario(state, scenario)
                    }
                }
            }
        }

        val report = StringBuilder()
        var failures = 0
        for (sc in scenarios) {
            applyScenario(state, sc)
            scenario = sc
            compose.waitForIdle()
            assertContentPin(sc, odin.label)

            val overflowPx = (heightPx - odin.heightDp).coerceAtLeast(0f)
            report.appendLine(
                "%-70s | naturalHeight=%6dpx | viewport=%.1fpx | overflow=%6.1fpx"
                    .format(sc.label, heightPx, odin.heightDp, overflowPx),
            )

            // The old DONE-only residual ratchet (DoneKnownOdinResidualMinPx/MaxPx) belonged to the 5-cell
            // primer grid; removing the grid  removed that
            // pressure entirely, so DONE (one FINISH page since 2026-09-28) is gated by the same plain overflow
            // check as every other onboarding screen — no carve-out.
            if (overflowPx > 2f) {
                failures += 1
            }
        }

        println("=== Onboarding NATURAL content height vs Odin viewport (832.5x468.3dp) ===")
        println(report.toString())

        assertEquals(
            "$failures onboarding screen(s) have natural content taller than the Odin reference viewport " +
                "— see the report above.",
            0,
            failures,
        )
    }

    @Test
    fun `full natural-height fit matrix across every probed viewport`() {
        var heightPx by mutableIntStateOf(0)
        var scenario by mutableStateOf(scenarios.first())
        var widthDp by mutableStateOf(odin.widthDp)
        // Density stays 1f so px and dp remain numerically interchangeable in this harness, as every
        // comment in this file assumes; only fontScale moves. That isolates the axis: a row that grows
        // here grew because its TEXT grew, not because the ruler changed under it.
        var density by mutableStateOf(Density(density = 1f, fontScale = fitFontScales.first()))
        val state = OnboardingFlowState()

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    NaturalHeightProbe(widthDp = widthDp, onMeasured = { heightPx = it }) {
                        RenderScenario(state, scenario)
                    }
                }
            }
        }

        // "Fits" means the same thing here as in the Odin gate above (`overflowPx > 2f` fails there): the
        // 2px is the text-metric jitter this file already accepted, and a screen must not be green under
        // one test and red under the other for the same 1px. The same 2px bounds the exception checks in
        // both directions, so a listed pair is judged by the same ruler as an unlisted one.
        val fitTolerancePx = 2f

        val report = StringBuilder()
        val overflowing = mutableListOf<String>()
        val violations = mutableListOf<String>()
        val exceptionsSeen = mutableSetOf<KnownStandardOverflow>()
        // (viewportLabel, fontScale) -> the overflowing scenarios measured for that group this run.
        val raisedScaleMeasured = mutableMapOf<Pair<String, Float>, MutableList<Pair<String, Float>>>()
        for (sc in scenarios) {
            applyScenario(state, sc)
            scenario = sc
            for (vp in viewports) {
                widthDp = vp.widthDp
                for (fontScale in fitFontScales) {
                    density = Density(density = 1f, fontScale = fontScale)
                    compose.waitForIdle()
                    assertContentPin(sc, "${vp.label} @ fontScale $fontScale")
                    val overflowPx = (heightPx - vp.heightDp).coerceAtLeast(0f)
                    // Classify by the SAME function production branches on — the Nova and the QA emulator
                    // are STANDARD and used to print here as "scrollDistance" because the label keyed on
                    // identity with the Odin. On STANDARD a nonzero number is a defect: there is no scroll to
                    // reveal what fell off the bottom. On compact-square, ruling 1 makes overflow the
                    // EXPECTED, legitimate case — verticalScroll is there specifically to carry it — so it's
                    // not "overflow" in the defect sense, it's how far the reveal machinery has to scroll.
                    val standard = !mimirViewportProfile(vp.widthDp, vp.heightDp).isCompactSquare
                    val metricLabel = if (standard) "overflow" else "scrollDistance"
                    val line = "%-70s | %-42s | fontScale=%.2f | naturalHeight=%6dpx | viewport=%5.1fpx | %s=%6.1fpx"
                        .format(sc.label, vp.label, fontScale, heightPx, vp.heightDp, metricLabel, overflowPx)
                    report.appendLine(line)
                    if (overflowPx > fitTolerancePx) overflowing += line
                    if (!standard) continue

                    // Above the default scale the pair-by-pair gate is replaced by the counted debt
                    // ledger below - see [RaisedScaleDebt] for why an 87-entry allow-list would have been
                    // worse than no gate at all.
                    if (fontScale != 1f) {
                        if (overflowPx > fitTolerancePx) {
                            raisedScaleMeasured
                                .getOrPut(vp.label to fontScale) { mutableListOf() }
                                .add(sc.label to overflowPx)
                        }
                        continue
                    }

                    val exception = knownStandardOverflows.firstOrNull {
                        it.scenarioLabel == sc.label && it.viewportLabel == vp.label
                    }
                    when {
                        exception == null -> if (overflowPx > fitTolerancePx) {
                            violations += "REGRESSION  $line  <<< a STANDARD viewport has no scroll to " +
                                "reveal this; fix the screen (spacing first, as WELCOME was) — do not add " +
                                "it to knownStandardOverflows unless closing it needs an owner decision"
                        }
                        else -> {
                            exceptionsSeen += exception
                            val recorded = "%.1fpx".format(exception.measuredOverflowPx)
                            if (overflowPx > exception.measuredOverflowPx + fitTolerancePx) {
                                violations += "WORSE       $line  <<< knownStandardOverflows records $recorded " +
                                    "for this pair; it has grown, and the exception does not cover the growth"
                            } else if (overflowPx <= fitTolerancePx) {
                                violations += "STALE       $line  <<< knownStandardOverflows still records " +
                                    "$recorded for this pair, but it now fits; delete the entry so the list stays true"
                            }
                        }
                    }
                }
            }
        }
        // An exception that matched nothing is the same lie as a stale one, with a different cause: a
        // label was renamed (or the row removed) and the entry went on describing a pair the matrix no
        // longer measures. The lookups above are exact-string, deliberately, so this is where a rename
        // surfaces instead of silently unhooking the exception.
        for (orphan in knownStandardOverflows - exceptionsSeen) {
            val recorded = "%.1fpx".format(orphan.measuredOverflowPx)
            violations += "ORPHAN      knownStandardOverflows entry ('${orphan.scenarioLabel}' at " +
                "'${orphan.viewportLabel}', $recorded) matched no scenario x STANDARD viewport pair at " +
                "the default font scale — a label changed; rename the entry or delete it"
        }

        // -- The raised-font-scale debt gate ----------------------------------------------------------
        // Every STANDARD viewport x raised scale must be described by exactly one ledger entry, and the
        // entry must still be true. A group with no entry is a scale that was added to fitFontScales and
        // never accounted for - that fails too, rather than measuring silently and gating nothing, which
        // is the exact failure mode this whole test was rewritten to remove.
        val standardViewportLabels = viewports
            .filter { !mimirViewportProfile(it.widthDp, it.heightDp).isCompactSquare }
            .map { it.label }
        val raisedGroups = standardViewportLabels.flatMap { label ->
            fitFontScales.filter { it != 1f }.map { label to it }
        }
        for ((vpLabel, fontScale) in raisedGroups) {
            val measured = raisedScaleMeasured[vpLabel to fontScale].orEmpty()
            val worst = measured.maxOfOrNull { it.second } ?: 0f
            val entry = raisedScaleDebt.firstOrNull {
                it.viewportLabel == vpLabel && it.fontScale == fontScale
            }
            val where = "'$vpLabel' @ fontScale $fontScale"
            val counted = "${measured.size} overflowing screen(s)"
            when {
                entry == null ->
                    violations += "UNLEDGERED  $where measured $counted, worst %.1fpx, and raisedScaleDebt "
                        .format(worst) + "has no entry for it. Add one with these numbers, or drop the " +
                        "scale from fitFontScales - a measured-but-ungated row is how D-08 shipped"
                measured.size > entry.overflowingScenarios ->
                    violations += "WORSE       $where now has $counted; raisedScaleDebt records " +
                        "${entry.overflowingScenarios}. Currently overflowing: " +
                        measured.map { it.first }.sorted().joinToString(", ")
                measured.size < entry.overflowingScenarios ->
                    violations += "STALE       $where now has $counted; raisedScaleDebt still records " +
                        "${entry.overflowingScenarios}. A repair landed - write the smaller number down " +
                        "(worst is now %.1fpx), or delete the entry outright if the count reached 0"
                            .format(worst)
                worst > entry.worstOverflowPx + fitTolerancePx ->
                    violations += "WORSE       $where still has $counted, but the worst overflow grew to "
                        .plus("%.1fpx from the recorded %.1fpx".format(worst, entry.worstOverflowPx)) +
                        " - a screen got materially worse without the count moving: " +
                        (measured.maxByOrNull { it.second }?.first ?: "?")
            }
        }

        println("=== Onboarding NATURAL-height fit matrix (all probed viewports) ===")
        println(report.toString())
        println("=== Screens/viewports with nonzero overflow/scrollDistance: ${overflowing.size} ===")
        overflowing.forEach(::println)
        println("=== STANDARD fit-gate violations: ${violations.size} ===")
        violations.forEach(::println)

        assertTrue("fit matrix must run for every scenario x viewport pair", report.isNotEmpty())
        // The gate. Compact-square rows are printed above but never counted here: ruling 1 (2026-08-20)
        // lets that posture scroll, and its reachability and reveal-wiring are gated by the tests below.
        // Every STANDARD row IS counted, against the exception list, in both directions — so the measured
        // number in each failure line is the number the next person starts from, not one to re-derive.
        assertEquals(
            "${violations.size} STANDARD-posture fit-gate failure(s). Owner " +
                "STANDARD onboarding pages fit without scrolling; the only tolerated " +
                "overflows are the ${knownStandardOverflows.size} named in knownStandardOverflows, each held " +
                "to its measured number; ABOVE the default scale each viewport/scale group is held to the " +
                "count in raisedScaleDebt (open defects D-14 and D-15). " +
                "to its measured number.\n" + violations.joinToString("\n"),
            0,
            violations.size,
        )
    }

    // ── Instrument 2: the real bounded (production) harness — proves no scroll, and surfaces compression ──

    private fun setUpBoundedHarness(): BoundedHarness {
        var fontScale by mutableStateOf(1f)
        var widthDp by mutableStateOf(odin.widthDp)
        var heightDp by mutableStateOf(odin.heightDp)
        var scenario by mutableStateOf(scenarios.first())
        val state = OnboardingFlowState()

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = fontScale)) {
                MimirTheme {
                    Box(Modifier.requiredWidth(widthDp.dp).requiredHeight(heightDp.dp)) {
                        RenderScenario(state, scenario)
                    }
                }
            }
        }
        return BoundedHarness(
            state = state,
            setWidth = { widthDp = it },
            setHeight = { heightDp = it },
            setScenario = { scenario = it },
            setFontScale = { fontScale = it },
        )
    }

    private class BoundedHarness(
        val state: OnboardingFlowState,
        val setWidth: (Float) -> Unit,
        val setHeight: (Float) -> Unit,
        val setScenario: (Scenario) -> Unit,
        val setFontScale: (Float) -> Unit,
    )

    private fun BoundedHarness.select(sc: Scenario, vp: FitViewport) {
        applyScenario(state, sc)
        setScenario(sc)
        setWidth(vp.widthDp)
        setHeight(vp.heightDp)
    }

    @Test
    fun `no onboarding screen composes a scrollable surface at any STANDARD viewport`() {
        val harness = setUpBoundedHarness()
        var scrollableFound = 0

        // Every STANDARD viewport, not just the Odin. The no-scroll contract is about posture rather than one device. These tests cover two: the Odin (832.5x468.3dp) and the Retroid Pocket
        // Nova (640x480dp). The Nova is 11.7dp taller but 192.5dp narrower, and narrower wraps text onto
        // more lines -- so fitting the Odin does not prove fitting the Nova.
        val standardViewports = viewports.filter {
            !mimirViewportProfile(it.widthDp, it.heightDp).isCompactSquare
        }

        for (sc in scenarios) for (vp in standardViewports) {
            harness.select(sc, vp)
            compose.waitForIdle()
            assertContentPin(sc, vp.label)

            val scrollables = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
            if (scrollables.isNotEmpty()) {
                scrollableFound += 1
                println(
                    "REGRESSION: '${sc.label}' at ${odin.label} composed ${scrollables.size} scrollable " +
                        "node(s) — non-scrolling applies to STANDARD viewports; " +
                        "Odin/STANDARD must never scroll",
                )
            }
        }

        assertEquals(
            "$scrollableFound onboarding screen(s) composed a scrollable surface at the Odin/STANDARD " +
                "viewport. STANDARD-posture onboarding pages must never scroll.",
            0,
            scrollableFound,
        )
    }

    /**
     * after WELCOME the header is one strip in one seat, so it
     * reads as a header that stays while the step under it changes. Held on every probed viewport at the default
     * scale: every later step composes exactly one strip, at the same bounds as every other step; WELCOME keeps
     * its ceremony lockup and composes none.
     */
    @Test
    fun `the header strip holds one seat on every step after WELCOME`() {
        val harness = setUpBoundedHarness()
        val failures = mutableListOf<String>()
        for (vp in viewports) {
            var seat: Pair<String, androidx.compose.ui.geometry.Rect>? = null
            for (sc in scenarios) {
                harness.select(sc, vp)
                compose.waitForIdle()
                assertContentPin(sc, vp.label)
                val strips = compose.onAllNodes(hasTestTag(OnboardingHeaderStripTag), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                if (sc.step == OnboardingStep.WELCOME) {
                    if (strips.isNotEmpty()) failures += "'${sc.label}' at ${vp.label} shows the strip; WELCOME keeps the lockup"
                    continue
                }
                if (strips.size != 1) {
                    failures += "'${sc.label}' at ${vp.label} composed ${strips.size} header strips, expected 1"
                    continue
                }
                val bounds = strips.single().boundsInRoot
                val reference = seat
                if (reference == null) {
                    seat = sc.label to bounds
                } else if (
                    kotlin.math.abs(bounds.top - reference.second.top) > 0.5f ||
                    kotlin.math.abs(bounds.left - reference.second.left) > 0.5f ||
                    kotlin.math.abs(bounds.height - reference.second.height) > 0.5f
                ) {
                    failures += "'${sc.label}' at ${vp.label} puts the strip at $bounds; " +
                        "'${reference.first}' put it at ${reference.second}"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * FINISH carries "one glass legend line". Held on every STANDARD
     * viewport at the default scale, with the fullest legend (M named, and since 2026-09-30 the Library's hints row):
     * every entry's verb sits on one baseline.
     * The compact square may wrap it, and is not held here.
     */
    @Test
    fun `FINISH's hints are a list whose labels share one left edge, on every viewport`() {
        // The finish hints use a concise list.
        val harness = setUpBoundedHarness()
        val finish = scenarios.single { it.label == "DONE/FINISH" }
        val failures = mutableListOf<String>()
        for (vp in viewports) {
            harness.select(finish, vp)
            compose.waitForIdle()
            assertContentPin(finish, vp.label)
            val labels = compose.onAllNodes(hasTestTag(OnboardingFinishLegendLabelTag), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .map { it.boundsInRoot }
            if (labels.size != 4) failures += "${vp.label}: ${labels.size} rows"
            if (labels.map { it.left }.distinct().size != 1) failures += "${vp.label}: left edges ${labels.map { it.left }}"
            if (labels.zipWithNext().any { (a, b) -> b.top <= a.top }) failures += "${vp.label}: rows out of order"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * §8 — the reserved control-seat contract. Every clickable exposed by each measured onboarding scenario
     * must retain a spoken label, its minimum touch height, and a fully visible seat after the applicable body
     * scroll has been asked to reveal it. This is deliberately structural: a trailing control crushed by a
     * bounded Column can make a bounds-only fit walk look green while still being unusable.
     */
    @Test
    fun `every onboarding control outside a scroll container stays on screen labelled and touch sized`() {
        val harness = setUpBoundedHarness()
        val minimumTouchPx = 39f
        val failures = mutableListOf<String>()
        // D-14/D-15 pass rule. A control that no scroll container can
        // reach must sit fully inside the viewport at every scale. Controls inside a scrolling body are reached by
        // the reveal machinery (the focused-body reveal test below) and are not seat-checked here; checking them
        // by scrolling each one into view cost hours per run.
        val unscrollableControl = hasClickAction() and !hasAnyAncestor(hasScrollAction())
        // The reserved slot is selected by its tag and must hold exactly the controls each scenario names, none of them
        // inside a scroll container: excluding scroll-contained controls alone could hide a control left in the body.
        val slotControl = hasClickAction() and hasAnyAncestor(hasTestTag(OnboardingControlSlotTag))
        assertEquals(
            "every scenario must name its reserved-slot controls",
            scenarios.map { it.label }.toSet(),
            expectedSlotLabels.keys,
        )

        for (sc in scenarios) {
            for (vp in viewports) {
                for (fontScale in fitFontScales) {
                    harness.select(sc, vp)
                    harness.setFontScale(fontScale)
                    compose.waitForIdle()
                    val where = "${sc.label} @ ${vp.label} @ $fontScale"
                    assertContentPin(sc, where)
                    // Bounds are taken relative to the scaffold's own viewport node: the Robolectric root is narrower
                    // than most certified viewports, so root coordinates run negative for a control that is on screen.
                    val seat = compose.onNodeWithTag(OnboardingScaffoldViewportTag, useUnmergedTree = true)
                        .fetchSemanticsNode().boundsInRoot
                    val slotLabels = compose.onAllNodes(slotControl, useUnmergedTree = true)
                        .fetchSemanticsNodes().map(::semanticsLabel)
                    val expectedLabels = expectedSlotLabels.getValue(sc.label)
                    if (slotLabels != expectedLabels) failures += "$where: slot holds $slotLabels, expected $expectedLabels"
                    val scrolledSlot = compose.onAllNodes(slotControl and hasAnyAncestor(hasScrollAction()), useUnmergedTree = true)
                        .fetchSemanticsNodes().size
                    if (scrolledSlot != 0) failures += "$where: $scrolledSlot slot control(s) sit inside a scroll container"
                    compose.onAllNodes(unscrollableControl or slotControl, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .forEachIndexed { index, node ->
                            val label = semanticsLabel(node)
                            if (label.isBlank()) failures += "$where: control#$index has no label"
                            val b = node.boundsInRoot.translate(-seat.left, -seat.top)
                            if (b.height < minimumTouchPx ||
                                b.left < -1f || b.right > vp.widthDp + 1f ||
                                b.top < -1f || b.bottom > vp.heightDp + 1f
                            ) {
                                failures += "$where: control#$index '$label' bounds=$b " +
                                    "(min=${minimumTouchPx}px, viewport=${vp.widthDp}x${vp.heightDp}px)"
                            }
                        }
                }
            }
        }

        failures.forEach(::println)
        assertTrue(
            "${failures.size} onboarding control-seat violation(s) across scenario x viewport x font-scale " +
                "matrix — see the report above.",
            failures.isEmpty(),
        )
    }

    /** The controls each scenario's reserved slot holds, in order (D-14/D-15; focus-reveal ordering review). */
    private val expectedSlotLabels: Map<String, List<String>> = mapOf(
        "WELCOME" to listOf("Begin"),
        "PROFILE" to listOf("Continue", "Not now"),
        "ACCESS/ORIENTATION" to listOf("Continue"),
        "ACCESS/FILES (not granted)" to listOf("Allow", "Not now"),
        "ACCESS/USAGE (not granted)" to listOf("Allow", "Not now"),
        "ACCESS/OVERLAY (not granted)" to listOf("Allow", "Not now"),
        "ACCESS/HOME_ROLE (not granted)" to listOf("Allow", "Not now"),
        "LIBRARY (idle)" to listOf("Choose folder"),
        "LIBRARY (scanning)" to listOf("Next", "Skip"),
        "LIBRARY (page 2, Y edit)" to listOf("Next", "Skip"),
        "LIBRARY (page 3, X Tomes)" to listOf("Next", "Skip"),
        "LIBRARY (page 4, the dock)" to listOf("Next", "Skip"),
        "LIBRARY (page 5, M menu; the scan already done)" to listOf("Done", "Skip"),
        "LIBRARY (scanning, pages ended)" to emptyList(),
        "LIBRARY (done)" to listOf("Continue", "Choose another folder"),
        "METADATA (no account)" to listOf("Continue"),
        "METADATA (connected account)" to listOf("Continue"),
        "SETTLING (IDENTIFYING, real fraction, widest 4-digit counter)" to emptyList(),
        "SETTLING (FAILED, real production copy, canRetry) — OUT OF SCOPE, must not silently overflow" to
            listOf("Retry"),
        "DONE/FINISH" to listOf("Enter Mimir"),
        "DONE/FINISH (no M button on this chassis)" to listOf("Enter Mimir"),
        "DONE/FINISH (Mimir already home)" to listOf("Enter Mimir"),
    )

    @Test
    fun `focused metadata provider action is revealed from an overflowing raised STANDARD body`() {
        val harness = setUpBoundedHarness()
        val scenario = scenarios.first { it.label == "METADATA (no account)" }
        val viewport = viewports.first { it.label.startsWith("QA emulator") }
        harness.select(scenario, viewport)
        harness.setFontScale(2f)
        compose.waitForIdle()

        assertTrue(
            "raised-scale metadata must expose a body scroll surface before focus reveal is exercised",
            compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )

        compose.runOnIdle { harness.state.authority.requestFocus(OnboardingSteamGridDbKey) }
        compose.waitForIdle()
        compose.waitForIdle()

        val labelNode = compose.onNode(
            hasText("Add key", substring = true) or hasContentDescription("Add key", substring = true),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val pillNode = generateSequence(labelNode) { it.parent }
            .first { it.config.contains(SemanticsActions.OnClick) }
        val bounds = pillNode.boundsInRoot
        assertTrue(
            "focused Add key pill must remain at least touch-sized after reveal: $bounds",
            bounds.height >= 39f,
        )
        assertTrue(
            "focused Add key pill must be fully within the QA viewport after reveal: $bounds",
            bounds.left >= -0.5f && bounds.right <= viewport.widthDp + 0.5f &&
                bounds.top >= -0.5f && bounds.bottom <= viewport.heightDp + 0.5f,
        )
    }

    @Test
    fun `every onboarding screen composes a scrollable surface at every compact-square viewport`() {
        // The other direction of ruling 1: COMPACT_SQUARE keeps verticalScroll UNCONDITIONALLY (it's a
        // structural branch in OnboardingScaffold, not a "scroll only if content overflows" decision) so
        // that any screen whose content doesn't fit that posture's small viewport stays reachable. This
        // holds regardless of whether a given scenario's natural height actually exceeds the given
        // compact-square viewport (some, e.g. ACCESS/ORIENTATION at 720dp, don't) — the mechanism must be
        // present either way, matching what OnboardingScaffold actually does.
        val harness = setUpBoundedHarness()
        val compactSquareViewports = viewports.filter {
            // Classify by the SAME function production branches on, not by "everything that isn't
            // the Odin". That positional shortcut silently mislabelled the Retroid Pocket Nova the
            // moment it was added: at 640x480dp its aspect is 1.333, well past
            // CompactSquareMaximumAspect (1.12), so it is STANDARD and must NOT be asserted to scroll.
            mimirViewportProfile(it.widthDp, it.heightDp).isCompactSquare
        }
        var missing = 0

        for (sc in scenarios) {
            for (vp in compactSquareViewports) {
                harness.select(sc, vp)
                compose.waitForIdle()
                assertContentPin(sc, vp.label)

                val scrollables = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
                if (scrollables.isEmpty()) {
                    missing += 1
                    println(
                        "REGRESSION: '${sc.label}' at ${vp.label} composed NO scrollable surface — compact viewport rule " +
                            "1 (2026-08-20) requires COMPACT_SQUARE to keep verticalScroll",
                    )
                }
            }
        }

        assertEquals(
            "$missing scenario x compact-square-viewport combination(s) did NOT compose a scrollable " +
                "surface. COMPACT_SQUARE must keep verticalScroll.",
            0,
            missing,
        )
    }

    /** Every OnClick semantics node's height, keyed by scenario+viewport — used to detect the
     * budget-starvation compression hazard: a Column measuring under a bounded max height gives trailing
     * children the REMAINING budget, which can crush a control below its own minimum touch height (40dp
     * compact pill) or all the way to zero. */
    private fun collectClickableHeights(node: SemanticsNode, into: MutableList<Float>) {
        if (node.config.contains(SemanticsActions.OnClick)) into += node.boundsInRoot.height
        node.children.forEach { collectClickableHeights(it, into) }
    }

    private fun semanticsLabel(node: SemanticsNode): String {
        val own = buildList {
            addAll(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
            addAll(node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text })
        }.joinToString(" ").trim()
        return own.ifBlank {
            node.children.asSequence().map { child -> semanticsLabel(child) }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        }
    }

    @Test
    fun `no focusable control is compressed below its minimum touch height at Odin`() {
        val harness = setUpBoundedHarness()
        val minTouchPx = 39f // 40dp compact-pill minimum, 1px rounding tolerance
        var failures = 0
        val report = StringBuilder()

        for (sc in scenarios) {
            harness.select(sc, odin)
            compose.waitForIdle()
            assertContentPin(sc, odin.label)

            val heights = mutableListOf<Float>()
            collectClickableHeights(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), heights)
            val shortest = heights.minOrNull()
            report.appendLine("%-70s | shortest clickable = %s px (of %d)".format(sc.label, shortest, heights.size))
            // DONE previously needed a carve-out here (its "Enter Mimir" button compressed to 24px under
            // the pre-reflow residual). Ruling 2's copy trim closed DONE's Odin overflow to 3.7px and its
            // button now measures a healthy 42px (verified below) — no carve-out needed; if DONE ever
            // regresses past its ratchet window above, this assertion should catch the button compression
            // too, not mask it.
            if (shortest != null && shortest < minTouchPx) failures += 1
        }

        println("=== Shortest clickable control per onboarding screen at Odin ===")
        println(report.toString())

        assertEquals(
            "$failures onboarding screen(s) had a clickable control compressed below the ${minTouchPx}px " +
                "minimum touch height at Odin — see the report above. This is the budget-starvation " +
                "compression hazard, not simple overflow.",
            0,
            failures,
        )
    }

    /**
     * `performScrollTo`, bounded. The library's own is `do { scrollToNode } while (shouldContinueScroll)`, and `scrollToNode` answers
     * "scroll again" whenever the target is outside the viewport and the container has a scroll axis, whether or not the scroll moved
     * anything. A control the container cannot bring into view (already at its end, or outside what it can reach) therefore looped
     * forever, each pass cancelling its own scroll animation: the 1 h 45 m hang of 2026-10-06 (OR-15 hung the same way). This takes
     * the library's one-step arithmetic (the smaller of the two edge deltas, nothing when they point opposite ways), sends it through
     * the same `ScrollBy` semantics action, and stops when the control did not move or [maxSteps] passes are spent. What the test
     * asserts is unchanged: the control's height is read after this, so a control that could not be scrolled into view is counted
     * UNREACHABLE at once, instead of never. (Onboarding's scrollers are vertical and LTR, so the library's reversal handling is
     * not repeated.)
     */
    private fun scrollIntoViewBounded(interaction: SemanticsNodeInteraction, maxSteps: Int = 8) {
        repeat(maxSteps) {
            val node = interaction.fetchSemanticsNode()
            val scrollable = generateSequence(node.parent) { it.parent }
                .firstOrNull { SemanticsActions.ScrollBy in it.config } ?: return
            val coordinates = scrollable.layoutInfo.coordinates
            val viewport = coordinates.boundsInParent()
                .translate(coordinates.parentLayoutCoordinates?.positionInRoot() ?: Offset.Zero)
            val target = Rect(node.positionInRoot, node.size.toSize())
            fun delta(a: Float, b: Float): Float =
                if (Math.signum(a) == Math.signum(b)) (if (Math.abs(a) < Math.abs(b)) a else b) else 0f
            val dx = delta(target.left - viewport.left, target.right - viewport.right)
            val dy = delta(target.top - viewport.top, target.bottom - viewport.bottom)
            if (dx == 0f && dy == 0f) return
            val before = node.positionInRoot
            compose.runOnUiThread { scrollable.config[SemanticsActions.ScrollBy].action?.invoke(dx, dy) }
            compose.waitForIdle()
            if (interaction.fetchSemanticsNode().positionInRoot == before) return
        }
    }

    @Test
    fun `every clickable control on compact-square viewports is reachable at or above minimum touch height`() {
        // Ruling 1  requires COMPACT_SQUARE to keep verticalScroll so overflowing
        // content is reachable by scrolling, not compressed or lost. A prior version of this test walked
        // unscrolled semantics bounds and reported "collapsed to zero" as compression — but the
        // discriminating experiment in this file's KDoc proved those zeros are `clipScrollableContainer`
        // clipping off-screen content, not compression: `scrollState.maxValue` was already correct (only
        // possible if the child measured at full natural height), and in-view controls always measured at
        // full natural size, never shrunken. So this gates the property that actually matters: every
        // clickable, at every compact-square viewport, is either already on-screen at full touch height, or
        // reachable by scrolling to it.
        val harness = setUpBoundedHarness()
        val minTouchPx = 39f
        val compactSquareViewports = viewports.filter {
            // Classify by the SAME function production branches on, not by "everything that isn't
            // the Odin". That positional shortcut silently mislabelled the Retroid Pocket Nova the
            // moment it was added: at 640x480dp its aspect is 1.333, well past
            // CompactSquareMaximumAspect (1.12), so it is STANDARD and must NOT be asserted to scroll.
            mimirViewportProfile(it.widthDp, it.heightDp).isCompactSquare
        }
        var unreachable = 0
        val report = StringBuilder()

        for (sc in scenarios) {
            for (vp in compactSquareViewports) {
                harness.select(sc, vp)
                compose.waitForIdle()

                val clickables = compose.onAllNodes(hasClickAction(), useUnmergedTree = true)
                val count = clickables.fetchSemanticsNodes().size
                // Only a control with a scroll container above it can be scrolled to. performScrollTo on one without
                // waited forever (OR-15, 2026-09-29: a clipped RetroAchievements row hung the run for hours), so a
                // clipped, unscrollable control now fails here at once, as unreachable.
                val scrollable = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasScrollAction()), useUnmergedTree = true)
                    .fetchSemanticsNodes().map { it.id }.toSet()
                var shortest = Float.MAX_VALUE
                for (i in 0 until count) {
                    val interaction = clickables[i]
                    var bounds = interaction.fetchSemanticsNode().boundsInRoot
                    if (bounds.height < minTouchPx && interaction.fetchSemanticsNode().id in scrollable) {
                        runCatching { scrollIntoViewBounded(interaction) }
                        compose.waitForIdle()
                        bounds = interaction.fetchSemanticsNode().boundsInRoot
                    }
                    if (bounds.height < shortest) shortest = bounds.height
                }
                val ok = count == 0 || shortest >= minTouchPx
                if (!ok) unreachable += 1
                report.appendLine(
                    "%-70s | %-24s | clickables=%2d | shortest-after-scroll=%.1fpx%s"
                        .format(sc.label, vp.label, count, shortest, if (ok) "" else "  <<< UNREACHABLE"),
                )
            }
        }

        println("=== Compact-square clickable reachability (scrolled into view if needed) ===")
        println(report.toString())

        assertEquals(
            "$unreachable scenario x compact-square-viewport combination(s) have a clickable control that " +
                "remains below the ${minTouchPx}px minimum touch height even after scrolling to it — a " +
                "real reachability defect, not just off-screen content. See the report above.",
            0,
            unreachable,
        )
    }

    @Test
    fun `requestFocus on any reserved-slot control keeps every slot control seated and leaves the body scroll alone`() {
        // Compact-square keeps a scrolling body, while the ACCESS controls live in the reserved, non-scrolling slot
        // under it. Focusing ANY slot control -- the primary action or Not now -- must leave every slot control
        // fully seated and must not move the body: the slot is already on screen, so a reveal that scrolls the body
        // toward it is the defect (focus-reveal ordering review). Driven by authority.requestFocus(key) alone, as a
        // real controller focus move drives it; the raised-scale METADATA test above is the body-control proof.
        //
        // 411dp is the representative overflowing compact-square viewport; 360dp overflows further along
        // the identical code path, not a different one. At font scale 1.3 since 2026-09-28: the header strip
        // (policy Q4) freed enough height that the ACCESS bodies FIT this square at 1.0, which left the
        // check below with no scroll to disturb. 1.3 is the D-14 scale, and every body here overflows at it.
        // At 2.0 since the permission-copy update (2026-09-28): the permission screens lost their how-to sentences and fit this square
        // at 1.3 too. 2.0 is the D-15 scale, and every body here still overflows at it.
        val vp = viewports.first { it.label.contains("compact-square 411") }
        val accessScenarios = scenarios.filter {
            it.step == OnboardingStep.ACCESS && it.accessScreenId != null &&
                it.accessScreenId != OnboardingAccessScreenId.ORIENTATION
        }
        val minTouchPx = 39f
        val failures = mutableListOf<String>()

        val density = Density(density = 1f, fontScale = 2.0f)
        var currentScenario by mutableStateOf(accessScenarios.first())
        var currentAuthority by mutableStateOf(DefaultFocusAuthority())

        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    Box(Modifier.requiredWidth(vp.widthDp.dp).requiredHeight(vp.heightDp.dp)) {
                        // key() on the screen id + a fresh authority instance per scenario forces a wholly
                        // new subtree — a fresh rememberScrollState() and no stale focus/scroll carried over
                        // from the previous scenario's screen (Robolectric's ComposeTestRule allows only one
                        // setContent per test, so scenarios share one composition; key() is what makes that
                        // safe instead of leaking state between iterations).
                        key(currentScenario.accessScreenId, currentAuthority) {
                            val accessReview = remember { OnboardingAccessReviewState() }
                            LaunchedEffect(currentScenario.accessScreenId) {
                                accessReview.goToScreen(requireNotNull(currentScenario.accessScreenId))
                            }
                            OnboardingFlow(
                                authority = currentAuthority,
                                step = OnboardingStep.ACCESS,
                                profile = remember { OnboardingProfileState() },
                                accessReview = accessReview,
                                focusIntent = remember { OnboardingFocusIntentState() },
                                access = currentScenario.access,
                                library = remember { OnboardingLibraryState() },
                                libraryGameCount = 0,
                                libraryPlatformCount = 0,
                                artworkAccount = null,
                                steamGridDbKeyPresent = false,
                                settlement = currentScenario.settlement,
                                currentThemeId = MimirThemeId.DEFAULT,
                                onAdvance = {}, onCommitName = {}, onRequestFilesAccess = {},
                                onRequestUsageAccess = {},
                                onRequestHomeRole = {},
                                onChooseFolder = {}, onOpenArtworkSetup = {}, onOpenSteamGridDbKey = {},
                                onRetrySettlement = {}, onContinueLocally = {}, onApplyTheme = {},
                            )
                        }
                    }
                }
            }
        }

        for (sc in accessScenarios) {
            val id = requireNotNull(sc.accessScreenId)
            currentScenario = sc
            currentAuthority = DefaultFocusAuthority()
            compose.waitForIdle()
            assertContentPin(sc, vp.label)
            // An ACCESS body holds nothing focusable, so it stays at the top wherever focus sits in the slot. The
            // landing focus is checked too: it already lands on a slot control, and a reveal toward it would have
            // scrolled the body before any explicit requestFocus.
            val (landingScroll, maxScroll) = bodyScroll()
            if (maxScroll <= 0f) {
                failures += "${sc.label}: the compact body must overflow, or this check is vacuous"
            }
            if (landingScroll != 0f) failures += "${sc.label}: the landing focus moved the body scroll to $landingScroll"

            for (key in onboardingAccessScreenFocusKeys(id, sc.access)) {
                compose.runOnIdle { currentAuthority.requestFocus(key) }
                compose.waitForIdle()
                compose.waitForIdle()
                if (currentAuthority.state.value.focused != key) failures += "${sc.label}: focus did not land on $key"
                val (scroll, _) = bodyScroll()
                if (scroll != 0f) failures += "${sc.label}: focusing $key moved the body scroll to $scroll"
                for ((label, b) in slotControls()) {
                    if (b.height < minTouchPx || b.left < -1f || b.right > vp.widthDp + 1f ||
                        b.top < -1f || b.bottom > vp.heightDp + 1f
                    ) {
                        failures += "${sc.label}: focusing $key left '$label' outside its slot: $b"
                    }
                }
            }
        }

        failures.forEach(::println)
        assertTrue(
            "${failures.size} reserved-slot focus violation(s) at ${vp.label} -- see the report above.",
            failures.isEmpty(),
        )
    }

    /**
     * The large-font layout task. The five raised-scale
     * ratchet rows were raised as known debt (ad82b7737); this holds each of them to its PRE-regression value, and
     * prints the whole natural-height table and, for every overflowing page, where each text and control sits, so the
     * numbers the ledger needs are read off a run rather than guessed.
     */
    @Test
    fun `raised-scale ratchet rows are back at their pre-regression values`() {
        // viewport label prefix, scale, count before the 2026-10-06 regressions, worst before them.
        data class Target(val vpPrefix: String, val scale: Float, val count: Int, val worst: Float)
        val targets = listOf(
            Target("Odin", 2.0f, 3, 138.7f),
            Target("Retroid Pocket Nova", 2.0f, 3, 161.0f),
            Target("QA emulator", 2.0f, 5, 195.6f),
            Target("RP5-class", 1.3f, 5, 51.0f),
            Target("RP5-class", 2.0f, 16, 219.0f),
        )
        var heightPx by mutableIntStateOf(0)
        var scenario by mutableStateOf(scenarios.first())
        var widthDp by mutableStateOf(odin.widthDp)
        var density by mutableStateOf(Density(density = 1f, fontScale = 1f))
        val state = OnboardingFlowState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    NaturalHeightProbe(widthDp = widthDp, onMeasured = { heightPx = it }) {
                        RenderScenario(state, scenario)
                    }
                }
            }
        }

        fun dump(node: SemanticsNode, out: StringBuilder) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }
                ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")
            val clickable = node.config.contains(SemanticsActions.OnClick)
            if (text != null || clickable) {
                val b = node.boundsInRoot
                out.appendLine(
                    "      %s[%4d..%4d x %4d..%4d] %s%s".format(
                        if (clickable) "*" else " ", b.top.toInt(), b.bottom.toInt(), b.left.toInt(), b.right.toInt(),
                        (text ?: "").take(48), if (text != null && text.length > 48) "~" else "",
                    ),
                )
            }
            node.children.forEach { dump(it, out) }
        }

        // Report ellipsized text; the header strip's rune line may yield.
        fun clippedTexts(node: SemanticsNode, out: MutableList<String>) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                action(results)
                if (results.any { (0 until it.lineCount).any { line -> it.isLineEllipsized(line) } }) {
                    out += node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }.orEmpty()
                }
            }
            node.children.forEach { clippedTexts(it, out) }
        }

        val failures = mutableListOf<String>()
        val table = StringBuilder()
        val dumps = StringBuilder()
        for (target in targets) {
            val vp = viewports.first { it.label.startsWith(target.vpPrefix) }
            val over = mutableListOf<Pair<String, Float>>()
            table.appendLine("=== ${vp.label} @ ${target.scale} (pre-regression: ${target.count} screens, worst ${target.worst}) ===")
            for (sc in scenarios) {
                applyScenario(state, sc)
                scenario = sc
                widthDp = vp.widthDp
                density = Density(density = 1f, fontScale = target.scale)
                compose.waitForIdle()
                assertContentPin(sc, vp.label)
                val overflow = (heightPx - vp.heightDp).coerceAtLeast(0f)
                table.appendLine("%-72s natural=%5dpx overflow=%6.1f".format(sc.label, heightPx, overflow))
                if (overflow > 2f) {
                    over += sc.label to overflow
                    dumps.appendLine("--- ${sc.label} @ ${vp.label} @ ${target.scale}: natural $heightPx, overflow %.1f".format(overflow))
                    dump(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), dumps)
                }
                val clipped = mutableListOf<String>()
                clippedTexts(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), clipped)
                if (clipped.isNotEmpty()) table.appendLine("    ellipsized: ${sc.label}: $clipped")
            }
            val worst = over.maxOfOrNull { it.second } ?: 0f
            table.appendLine("--> ${over.size} overflowing, worst %.1f: ${over.map { it.first }}".format(worst))
            if (over.size > target.count || worst > target.worst + 2f) {
                failures += "NOT BACK ${vp.label} @ ${target.scale}: ${over.size} screens (was ${target.count}), worst %.1f (was ${target.worst})".format(worst)
            }
        }
        println(table.toString())
        println(dumps.toString())
        failures.forEach(::println)
        assertTrue("${failures.size} raised-scale layout failure(s):\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /** Every `Text` node in the unmerged tree, with the node's bounds, text and whether a line ellipsized or the text overflowed. */
    private fun dumpTree(node: SemanticsNode, out: StringBuilder) {
        val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }
            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("|")
        val clickable = node.config.contains(SemanticsActions.OnClick)
        if (text != null || clickable) {
            val b = node.boundsInRoot
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            val lines = layouts.firstOrNull()?.lineCount
            out.appendLine(
                "      %s[%4d..%4d x %4d..%4d] lines=%s %s".format(
                    if (clickable) "*" else " ", b.top.toInt(), b.bottom.toInt(), b.left.toInt(), b.right.toInt(),
                    lines ?: "-", (text ?: "").take(60),
                ),
            )
        }
        node.children.forEach { dumpTree(it, out) }
    }

    /** The STANDARD viewports a raised-scale WELCOME is judged on, and the scales: where it is held to fit. */
    private val welcomeMustFitAtRaisedScale = listOf(
        // viewport label prefix, scale
        "Odin" to 1.3f, "Retroid Pocket Nova" to 1.3f, "QA emulator" to 1.3f, "RP5-class" to 1.3f,
        "Odin" to 2.0f, "Retroid Pocket Nova" to 2.0f, "QA emulator" to 2.0f,
    )

    /**
     * Large-font debt (layout change 2026-10-06): WELCOME overflowed every STANDARD row at a raised scale, so its body scrolled
     * with Begin pinned. It tightens at a raised scale only and fits where that is reasonably possible
     * ([welcomeMustFitAtRaisedScale]); at 1.0 it is exactly as it was. The whole table is printed, 1.0 included, so the
     * 1.0 heights can be read against the parent's.
     */
    @Test
    fun `WELCOME fits the STANDARD viewports at a raised font scale where it reasonably can`() {
        val welcome = scenarios.first { it.step == OnboardingStep.WELCOME }
        var heightPx by mutableIntStateOf(0)
        var widthDp by mutableStateOf(odin.widthDp)
        var density by mutableStateOf(Density(density = 1f, fontScale = 1f))
        val state = OnboardingFlowState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    NaturalHeightProbe(widthDp = widthDp, onMeasured = { heightPx = it }) {
                        RenderScenario(state, welcome)
                    }
                }
            }
        }
        applyScenario(state, welcome)
        val table = StringBuilder()
        val dumps = StringBuilder()
        val failures = mutableListOf<String>()
        for (vp in viewports.filter { !mimirViewportProfile(it.widthDp, it.heightDp).isCompactSquare }) {
            widthDp = vp.widthDp
            for (scale in fitFontScales) {
                density = Density(density = 1f, fontScale = scale)
                compose.waitForIdle()
                assertContentPin(welcome, vp.label)
                val overflow = (heightPx - vp.heightDp).coerceAtLeast(0f)
                table.appendLine("WELCOME-FIT %-62s @ %.1f natural=%4dpx viewport=%5.1f overflow=%6.1f".format(vp.label, scale, heightPx, vp.heightDp, overflow))
                if (scale > 1f && overflow > 2f) {
                    dumps.appendLine("--- WELCOME @ ${vp.label} @ $scale: natural $heightPx, overflow %.1f".format(overflow))
                    dumpTree(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), dumps)
                }
                // The default size is untouched: 329 dp at every width, as measured before the large-font change.
                if (scale == 1f && heightPx != 329) failures += "${vp.label} @ 1.0: WELCOME is $heightPx dp, was 329 before the large-font change"
                val mustFit = welcomeMustFitAtRaisedScale.any { vp.label.startsWith(it.first) && it.second == scale }
                if (mustFit && overflow > 2f) failures += "${vp.label} @ $scale: WELCOME is %.1f dp over".format(overflow)
            }
        }
        println(table.toString())
        println(dumps.toString())
        failures.forEach(::println)
        assertTrue("WELCOME overflows where it was held to fit:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Where WELCOME cannot fit at a raised scale, its body scrolls and Begin stays pinned inside the viewport, reachable. Run
     * in the real bounded harness on every STANDARD viewport at 1.3 and 2.0.
     */
    @Test
    fun `WELCOME keeps Begin pinned and reachable at a raised font scale`() {
        val welcome = scenarios.first { it.step == OnboardingStep.WELCOME }
        val harness = setUpBoundedHarness()
        val failures = mutableListOf<String>()
        for (vp in viewports.filter { !mimirViewportProfile(it.widthDp, it.heightDp).isCompactSquare }) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                harness.setFontScale(scale)
                harness.select(welcome, vp)
                compose.waitForIdle()
                assertContentPin(welcome, vp.label)
                val controls = slotControls()
                val begin = controls.firstOrNull { it.first.contains("Begin") }
                if (begin == null) {
                    failures += "${vp.label} @ $scale: no Begin control in the slot (${controls.map { it.first }})"
                    continue
                }
                val b = begin.second
                if (b.top < -1f || b.bottom > vp.heightDp + 1f || b.left < -1f || b.right > vp.widthDp + 1f) {
                    failures += "${vp.label} @ $scale: Begin sits outside the viewport at $b"
                }
                val scrollables = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes().size
                println("WELCOME-BEGIN ${vp.label} @ $scale: Begin=$b scrollSurfaces=$scrollables")
            }
        }
        failures.forEach(::println)
        assertTrue("WELCOME's Begin is not reachable:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    /**
     * Large-font debt: a label ellipsized at a raised scale hides words the player is meant to read. Every `Text` the flow
     * lays out is checked at 1.3 and 2.0 on every probed viewport, from the unmerged tree (chrome texts clear their text
     * semantics in the merged one); the header strip's rune line is the one text that gives way by ruling. The picture
     * frames' drawn mock-ups are checked too: their words are part of the picture, drawn at the default size.
     */
    @Test
    fun `no onboarding text is ellipsized at a raised font scale`() {
        var heightPx by mutableIntStateOf(0)
        var scenario by mutableStateOf(scenarios.first())
        var widthDp by mutableStateOf(odin.widthDp)
        var density by mutableStateOf(Density(density = 1f, fontScale = 1.3f))
        val state = OnboardingFlowState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    NaturalHeightProbe(widthDp = widthDp, onMeasured = { heightPx = it }) {
                        RenderScenario(state, scenario)
                    }
                }
            }
        }

        fun ellipsized(node: SemanticsNode, out: MutableList<String>) {
            if (node.config.getOrNull(SemanticsProperties.TestTag) == OnboardingHeaderStripTag) return
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action
            if (action != null) {
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                action(results)
                if (results.any { (0 until it.lineCount).any { line -> it.isLineEllipsized(line) } }) {
                    out += node.config.getOrNull(SemanticsProperties.Text)?.joinToString("|") { it.text }.orEmpty()
                }
            }
            node.children.forEach { ellipsized(it, out) }
        }

        val failures = mutableListOf<String>()
        for (sc in scenarios) {
            applyScenario(state, sc)
            scenario = sc
            for (vp in viewports) {
                widthDp = vp.widthDp
                // What the default size already ellipsizes is not the raised scale's doing (a one-word book title on a 33 dp
                // mock-up cover is cut at 1.0 as well); the raised scale may add none.
                density = Density(density = 1f, fontScale = 1f)
                compose.waitForIdle()
                val atDefault = mutableListOf<String>()
                ellipsized(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), atDefault)
                for (scale in fitFontScales.filter { it > 1f }) {
                    density = Density(density = 1f, fontScale = scale)
                    compose.waitForIdle()
                    assertContentPin(sc, vp.label)
                    val found = mutableListOf<String>()
                    ellipsized(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), found)
                    val added = found.distinct() - atDefault.toSet()
                    if (found.isNotEmpty()) println("ELLIPSIS-SEEN ${sc.label} @ ${vp.label} @ $scale: raised=${found.distinct()} default=${atDefault.distinct()}")
                    if (added.isNotEmpty()) failures += "ELLIPSIZED ${sc.label} @ ${vp.label} @ $scale: $added"
                }
            }
        }
        failures.forEach(::println)
        assertTrue(
            "${failures.size} (page, viewport, scale) combination(s) ellipsize a text at a raised font scale:\n" +
                failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    /** The header strip is one 24 dp row by ruling; a raised font scale must not grow it (it was 44 dp at 2.0). */
    @Test
    fun `the header strip keeps one height at every font scale`() {
        val profile = scenarios.first { it.step == OnboardingStep.PROFILE }
        var density by mutableStateOf(Density(density = 1f, fontScale = 1f))
        var heightPx by mutableIntStateOf(0)
        val state = OnboardingFlowState()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides density) {
                MimirTheme {
                    NaturalHeightProbe(widthDp = odin.widthDp, onMeasured = { heightPx = it }) {
                        RenderScenario(state, profile)
                    }
                }
            }
        }
        applyScenario(state, profile)
        val heights = fitFontScales.map { scale ->
            density = Density(density = 1f, fontScale = scale)
            compose.waitForIdle()
            compose.onNodeWithTag(OnboardingHeaderStripTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        }
        println("header strip height at $fitFontScales: $heights")
        assertTrue("the strip's height must not move with the font scale: $heights", heights.distinct().size == 1)
    }

    /** The one scrolling body's (value, maxValue). */
    private fun bodyScroll(): Pair<Float, Float> {
        val ranges = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) }
        assertEquals("exactly one scrolling body", 1, ranges.size)
        return ranges.single().let { it.value() to it.maxValue() }
    }

    /** Every clickable control in the reserved slot, with bounds relative to the scaffold's viewport node. */
    private fun slotControls(): List<Pair<String, androidx.compose.ui.geometry.Rect>> {
        val seat = compose.onNodeWithTag(OnboardingScaffoldViewportTag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        return compose.onAllNodes(
            hasClickAction() and hasAnyAncestor(hasTestTag(OnboardingControlSlotTag)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { semanticsLabel(it) to it.boundsInRoot.translate(-seat.left, -seat.top) }
    }
}
