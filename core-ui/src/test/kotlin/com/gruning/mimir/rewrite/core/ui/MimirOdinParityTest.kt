package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIMIR ODIN-PARITY HARNESS. To compare relative geometry across viewports, it names a small set of proportions per surface, computes each from the
 * surface's own real solver at every [MimirCertifiedViewports] row, and asserts every non-Odin row stays
 * within tolerance of the Odin's value — printing a full surface × viewport × proportion table so a
 * failure names *what* diverged and by how much, not just red.
 *
 * **Duplicated verbatim into `feature-library`'s test tree** (same rationale as `MimirCertifiedViewports`
 * itself — see that file's own KDoc: no cross-module test-fixture wiring exists in this build). This
 * copy's harness types ([MimirSurfaceProportion], [MimirProportionReading], [mimirProportionReport]) are
 * byte-identical to feature-library's; only the per-surface proportions below differ, because this module
 * owns [MimirSpacing]/[MimirRunicQuickRailDefaults] and the tokens the Settings proportion needs, while
 * the other four named surfaces (Home shelf, Library platform shelf, gamelist carousel, Launch Card) all
 * live in `feature-library`.
 *
 * ## Why three assertion FORMS, not one
 *
 * The FOUR_THREE spec's three classes take opposite instructions at 640dp (its §1 table), so they cannot
 * share one assertion shape:
 * - **Class 1 — reservations** (rails, gutters, hint lanes): "become fractions of width, or shrink." The
 *   right check is a **ratio pinned to the Odin's own solved value**, tight tolerance — see the
 *   derivation below.
 * - **Class 2 — chrome affordances** (dock coins, badges): "may shrink, floored at `MimirSpacing.minTarget`
 *   (48dp)." The right check is **the floor itself**, not an Odin ratio — a smaller-but-still-legal coin on
 *   a narrow device is compliance, not a defect, so pinning its *ratio* to the Odin would flag accepted
 *   geometry as red. This harness's class-1/class-3 proportions do not include a class-2 example this
 *   pass (none of the five named surfaces' chrome affordances are included in this pure-JVM floor check;
 *   see the Home dock note below).
 * - **Class 3 — type and art** ("HOLD. Never scale by width."): the right check is **the dp value holding**,
 *   not a fraction of width — a fraction-of-width assertion on art would *demand* the art shrink on a
 *   narrower device, which is the exact wrong move FOUR_THREE §3 R3-10 warns against. Where a class-3
 *   quantity is architecturally still solved as a class-1 fraction today (see the Home shelf note in the
 *   sibling file), this harness measures what the code DOES, and says so, rather than what the spec wants.
 *
 * ## Tolerance derivation
 *
 * Every class-1 proportion below is, algebraically, a `clamp(min, k·dim, max)` composition — linear in the
 * viewport dimension whenever no floor/ceiling binds. Computed over the reference pair (Odin 833×444dp and the RP5-class shelf 700×350dp, both certified STANDARD, both in
 * the unclamped regime for these formulas), the spread between them is **effectively zero** (< 0.02
 * percentage points — float rounding only; see [MimirCertifiedViewportsSelfTest]'s sibling arithmetic).
 * Tolerance is set to **0.5 percentage points (0.005)** — roughly 25× that measured accepted-pair spread —
 * so it absorbs floating-point noise and a synthetic boundary viewport's clamp engaging (e.g. the
 * 1080×1080 square pushes `gutter`'s clamp to its 28dp ceiling) without laundering a real, reported
 * divergence, which is one to two **orders of magnitude** larger everywhere this harness finds one (the
 * Settings/Nova rail-only cell below is 1.8 points off, its total-chrome sibling 5.6 points off (was 3.7
 * before the 2026-08-21 edge-margin change made the Odin leaner and widened this gap — see
 * [SettingsOdinParityBaseline]'s own KDoc); Launch Card's banded chrome share is over 4 points off). The
 * "6.1 points" this paragraph cited in an earlier draft was the flat-168dp rail's pre-RC-8 Nova delta
 * (26.3% − 20.2%) — stale the moment RC-8 A1 landed live during harness development; corrected here rather
 * than left to drift further from the source, same as the 3.7-points figure just above.
 * Structurally-invariant ratios (pinned by construction, not by width arithmetic) get a tighter 0.01 —
 * they are provably exact and a departure means the constants themselves changed, not the viewport.
 *
 * ## Posture scoping
 *
 * Every proportion below runs over [MimirCertifiedViewports.standard] only. COMPACT_SQUARE changes
 * *composition*, not just geometry (FOUR_THREE §2 — Settings collapses to single-pane, for one) — comparing
 * a COMPACT_SQUARE cell against a STANDARD Odin baseline would manufacture fake reds for an intentionally
 * different layout, not measure parity within one.
 *
 * ## Device work
 *
 * Every proportion here is [D] — solver arithmetic. It predicts **geometry**, not **appearance**: a green
 * cell proves the numbers match: it does not prove the Settings rail *reads* right, that the M-anchor lane
 * is wide enough for the orb as actually parked, or that a proportion within tolerance still looks correct
 * side by side. Visual parity needs device evidence.
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

/** One printable table, in fixture declaration order — a failure names the exact cell and its delta. */
internal fun mimirProportionReport(readings: List<MimirProportionReading>): String =
    readings.joinToString("\n") { it.toString() }

/**
 * ── SETTINGS (RC-8) — literal-cited, not solver arithmetic ──────────────────────────────────────────
 *
 * **No CALLABLE production solver exists for this proportion from `core-ui`** — `feature-settings` is
 * not available through a test dependency in the direction this test
 * would need, so a real solver there can never be `import`ed here, unlike this file's own module's
 * functions. This proportion is therefore always a **hand copy of a cited formula**, never a call into
 * production code, no matter how solved `SettingsHome.kt` becomes — flagged so it reads as such.
 *
 * **Original implementation:** `SettingsHome.kt:289` then wrote a bare `.width(168.dp)`
 * literal directly in the `@Composable` body — exactly the anti-pattern
 * the shared geometry rule forbids. It was replaced during harness development, with `settingsRailWidthDp(widthDp) = (0.22f × widthDp).coerceIn(132f,
 * 168f)` (`SettingsHome.kt`, RC-8 A1) — precisely FOUR_THREE §3 R2-5's proposed formula. **That change was committed at `733cd647`** — no longer a live-tree risk
 * this file needs to hedge against; the literal below hand-replicates the now-committed formula. If a
 * future change to `settingsRailWidthDp`/`settingsDetailEdgeMarginDp` diverges from this hand copy, the
 * next re-run of this harness against a re-grounded literal is what catches the drift — not a git-status
 * check at review time.
 *
 * Grounded to **20.2% at the Odin / 22.0% at the Nova/RP5-class shelf** with the new formula (both fall in
 * its unclamped 132–168dp band) — a real, if partial, improvement over the flat-168 literal's 20.2%/26.3%,
 * and still outside this harness's 0.5-point tolerance (1.83 points off), so still RED, honestly.
 *
 * **The 34.6%/45.0% reproduction gap the earlier analysis flagged is now closed, not still open.** Those
 * figures were never rail-only — they are [settingsTotalChromeShare] below (rail + both detail-pane edge
 * margins + the section card's own `contentPadding` + the row's own horizontal padding), which this rail-
 * only proportion never claimed to measure. Verified: `288.0dp / 833dp = 34.59%` at the Odin and (pre-RC-8,
 * flat-168 rail + flat-32 margins on both sides) `288.0dp / 640dp = 45.00%` at the Nova — both reproduce
 * the reference figures to the reported precision. See [settingsTotalChromeShare]'s own KDoc for the full
 * arithmetic; kept as a correction here rather than silently reconciled, so the metric distinction remains explicit.
 *
 * **UPDATE 2026-08-21 — the 132dp floor this paragraph and the literal above once cited was itself a
 * shipped defect** (device-confirmed on the Nova, build `54ff6193`: the rail label `"APPEARANCE"` rendered
 * `"APPEARAN…"`, ellipsized). The floor only ever counted the label's own ink and silently dropped the
 * 64dp of column/item padding the label sits inside — see `settingsRailWidthDp`'s own KDoc in
 * `SettingsHome.kt` for the corrected derivation (82dp ink + 64dp chrome = 146dp true minimum, floored to
 * 154dp with an 8dp margin). This hand copy now reads `coerceIn(154f, 168f)`.
 *
 * Consequence for THIS proportion: the Nova and the 538dp companion (both below `SettingsNarrowThreshold-
 * WidthDp`, i.e. both floor-governed) get a WIDER rail than before, so their delta from the Odin *grows* —
 * a real cost of the clip fix, not noise. Re-grounded: Nova is now `154/640 = 24.06%` (was 22.00%, delta
 * from the Odin's unmoved 20.17% widens from 1.83 to **3.89 points**); the 538dp companion is now
 * `154/538 = 28.62%` (was `132/538 = 24.54%`, delta widens from 4.37 to **8.46 points**). The Odin
 * (168/833 = 20.17%, ceiling-clamped, untouched by the floor) and the RP5-class shelf (`700 * 0.22 = 154`,
 * unclamped both before and after — 154 was already its natural value under the OLD floor too, so this
 * fix moves it 0.0dp) are unaffected; the 1080dp boundary is also unaffected (ceiling-clamped like the
 * Odin). See [SettingsOdinParityBaseline] for the updated, re-measured baseline rows.
 */
private fun settingsRailWidthDp(widthDp: Float): Float = (0.22f * widthDp).coerceIn(154f, 168f)

private fun settingsRailChromeShare(viewport: MimirDpBox): Float =
    settingsRailWidthDp(viewport.widthDp) / viewport.widthDp

private val SettingsRailChromeShareProportion = MimirSurfaceProportion(
    name = "settings.railChromeShare",
    odinValue = settingsRailChromeShare(MimirCertifiedViewports.Odin.solverInputDp),
    tolerance = 0.005f,
)

/**
 * Hand-copy of the production gate, same caveat as [settingsRailWidthDp] (no callable cross-module
 * solver): `SettingsHome.kt:672`. This is the detail pane's own start AND end padding
 * (`SettingsHome.kt:328-330`, `SettingsDetailPane` call site) — the SAME value on both edges of that
 * pane, which is what makes it usable both as a margin-symmetry denominator (below) and as a chrome term
 * counted on both sides of the pane (in [settingsTotalChromeShare]).
 *
 * **Retired 2026-08-21 by policy.** Until this ticket the production gate branched on
 * `SettingsNarrowThresholdWidthDp` (680f): `xxl` (32dp, the Odin's pre-existing value) at/above it, `xl`
 * (24dp) below. This harness's own `settings.marginSymmetry` check is what surfaced the problem with that
 * split — it flagged the *Nova* red (24/24 = 1.00 against an Odin baseline of 24/32 = 0.75), because the
 * harness measures parity-to-Odin and the symmetric device is the one that diverges from an asymmetric
 * reference. The reference device now uses the same symmetric margins: production now returns `xl` (24dp) unconditionally,
 * at every width, matching the rail's own hardcoded `start = xl`. The Odin's detail pane moves 8dp on the
 * right (32dp -> 24dp) — the first deliberate change to the reference device's own appearance this pass has
 * made. `widthDp` stays in this hand-copy's signature only to keep call sites below unchanged; the value is
 * now width-invariant.
 */
private fun settingsDetailEdgeMarginDp(widthDp: Float): Float = 24f

/**
 * Margin symmetry — **RESOLVED to a universal 1.0 by policy, 2026-08-21.** Previously pinned to the
 * Odin's own value (0.75, i.e. 24/32) rather than a universal 1.0, because the Odin itself (833dp ≥ 680)
 * was asymmetric BY THE THEN-CURRENT SOURCE: the detail pane's own edge margin
 * [settingsDetailEdgeMarginDp] read 32dp against the rail's own unconditional `start = MimirSpacing.xl`
 * (24dp, `SettingsHome.kt:300`, unchanged by this margin change). That earlier revision of this file flagged the
 * gap as an open design question, since the source's own KDoc ("untouched by design") cited no explicit
 * policy the way FOUR_THREE §3 R1-2/R1-3 does for the Launch Card's M-anchor asymmetry.
 *
 * **This check exposed the asymmetric reference.** Once RC-1's harness was wired
 * up, `settings.marginSymmetry` flagged the *Nova* red (1.00 measured against the then-Odin-pinned 0.75
 * target) for being symmetric against an asymmetric reference — the harness measures parity-to-Odin, so
 * the symmetric device read as the outlier. The reference device now uses symmetric margins, matching the Nova:
 * production's [settingsDetailEdgeMarginDp] now returns `xl` (24dp) unconditionally, at every width, so
 * this proportion is now 24/24 = 1.0 everywhere INCLUDING the Odin — a true universal target, not a
 * per-device pin. The Odin's detail pane moves 8dp on the right (32dp -> 24dp) as a direct consequence.
 */
private fun settingsMarginSymmetry(viewport: MimirDpBox): Float {
    val leftMarginDp = 24f
    val rightMarginDp = settingsDetailEdgeMarginDp(viewport.widthDp)
    return leftMarginDp / rightMarginDp
}

private val SettingsMarginSymmetryProportion = MimirSurfaceProportion(
    name = "settings.marginSymmetry",
    odinValue = settingsMarginSymmetry(MimirCertifiedViewports.Odin.solverInputDp),
    tolerance = 0.01f,
)

/**
 * ── SETTINGS TOTAL CHROME SHARE (RC-8) ──
 *
 * [settingsRailChromeShare] measures the rail alone. The acceptance criterion is about what pushes ink
 * toward the middle of the pane, which is **every fixed term together**, not the rail alone: the rail
 * lane's full footprint (its `.width()` box already subsumes its own internal start/end padding — see
 * `SettingsHome.kt:298-300`, so nothing from inside that box is double-counted here) PLUS the detail
 * pane's own start-and-end edge margin [settingsDetailEdgeMarginDp] (both sides — `SettingsHome.kt:328-330`)
 * PLUS the section card's own `contentPadding` (`MimirSpacing.lg` = 16dp, both sides —
 * `SettingsComponents.kt:93`) PLUS each row's own horizontal padding (`MimirSpacing.md` = 12dp, both sides
 * — `SettingsComponents.kt:169`).
 *
 * `totalChromeDp(w) = railWidthDp(w) + 2×edgeMargin(w) + 2×16 + 2×12`. Verified against the ticket's own
 * table by first-principles derivation from the Row's actual child chain (rail column, then detail's
 * margin → card padding → row padding → ink), not just numeric match:
 *
 * | | Odin (833dp) | Nova, flat-168/32 (pre-RC-8) | Nova, current formula (post-RC-8) |
 * |---|---|---|---|
 * | rail | 168.0dp | 168.0dp | 140.8dp |
 * | 2×edgeMargin | 64.0dp | 64.0dp | 48.0dp |
 * | 2×cardPad + 2×rowPad | 56.0dp | 56.0dp | 56.0dp |
 * | **total chrome** | **288.0dp = 34.59%** | **288.0dp = 45.00%** | **244.8dp = 38.25%** |
 *
 * All three reproduce the ticket's cited figures exactly. The Odin baseline below is computed against the
 * SAME `MimirCertifiedViewports.Odin.solverInputDp` (833dp) every other proportion in this file uses, so
 * it reads 34.57% rather than the ticket's 34.59% (which divides by the full-viewport 832.5dp instead) —
 * a 0.5dp rounding difference in the denominator, not a different chrome figure; see
 * [MimirCertifiedViewports]'s own KDoc for why both boxes are real and non-conflicting.
 *
 * **Tolerance — re-derived, not reused.** The file's blanket accepted-pair method (top KDoc) computes a
 * spread between the proposed reference pair (Odin, RP5-class shelf) and sets
 * tolerance to ~25× that spread. That method **degenerates for this proportion**: RP5 is not an
 * uncontested viewport for Settings chrome share — recomputed here it is 39.14% against the Odin's
 * 34.57%, a 4.6-point gap, i.e. RP5 is itself one of this cell's red rows, not a quiet reference. Odin is
 * the only uncontested viewport in the standard set for this specific quantity (that is the RC-8 defect
 * this proportion exists to catch, not a flaw in the derivation). Falling back to the file's own
 * established Class-1 tolerance (0.005 / 0.5 percentage points, used for [SettingsRailChromeShareProportion]
 * and every Class-1 proportion in the sibling `feature-library` copy) instead: it sits **7×–19× smaller**
 * than every real divergence this proportion finds (3.68pp at the Nova, up to 9.29pp at 538dp — see the
 * baseline table below), so it cannot launder a real one, while remaining far above Float noise at this
 * magnitude (single-digit ULPs — see [MimirOdinParityBaselineEpsilon] for the separate, smaller epsilon
 * used to compare a red cell's delta against its own recorded baseline). Tolerance itself (0.005) is
 * untouched by the 2026-08-21 update below — every real divergence it must not launder shrank in absolute
 * terms too (smallest is now 4.20pp, still 8× the tolerance), so the same margin still holds.
 *
 * **UPDATE 2026-08-21 — table and figures above are RC-8-era (before the margin change), left as a dated record rather
 * than rewritten.** The 2026-08-21 policy (see [settingsDetailEdgeMarginDp]'s own KDoc) dropped the
 * Odin's own `2×edgeMargin` term from 64.0dp to 48.0dp — its total chrome is now **272.0dp = 32.65%**
 * (computed against this file's own 833dp `solverInputDp`, same convention as the 34.57% figure above),
 * not 34.57%/34.59%. The RP5 gap cited above (39.14% vs 34.57% = 4.6 points) is also stale: RP5's own
 * `2×edgeMargin` dropped the same way (it sat on the `xxl` side of the old branch), so it is now 36.86%
 * against the new 32.65% Odin baseline — a 4.20-point gap, smaller, not larger. Current, re-measured
 * deltas for every viewport live in [SettingsOdinParityBaseline]'s own comments, not here — this paragraph
 * only flags that the walkthrough above predates them.
 *
 * **UPDATE 2026-08-21 (second) — the Nova/538dp rows above are ALSO stale**, for an unrelated reason: the
 * rail-floor clip fix (see [settingsRailWidthDp]'s own "UPDATE" KDoc above). Both viewports are below
 * `SettingsNarrowThresholdWidthDp`, so both are floor-governed and both now carry a wider rail (154dp, was
 * 140.8dp at the Nova / 132dp at 538dp) — their own chrome total grows, on top of the Odin's unrelated
 * edge-margin drop, so their gap from the Odin widens on TWO independent axes at once. The Odin, the
 * RP5-class shelf, and the 1080dp boundary are untouched by the rail-floor fix (see that same KDoc for why),
 * so their totalChromeShare rows are unaffected by this second update. Re-measured deltas live in
 * [SettingsOdinParityBaseline]'s own comments.
 */
private fun settingsTotalChromeDp(viewport: MimirDpBox): Float {
    val rail = settingsRailWidthDp(viewport.widthDp)
    val edgeMargin = settingsDetailEdgeMarginDp(viewport.widthDp)
    val cardContentPaddingDp = 16f // MimirSpacing.lg [V SettingsComponents.kt:93]
    val rowHorizontalPaddingDp = 12f // MimirSpacing.md [V SettingsComponents.kt:169]
    return rail + 2f * edgeMargin + 2f * cardContentPaddingDp + 2f * rowHorizontalPaddingDp
}

private fun settingsTotalChromeShare(viewport: MimirDpBox): Float =
    settingsTotalChromeDp(viewport) / viewport.widthDp

private val SettingsTotalChromeShareProportion = MimirSurfaceProportion(
    name = "settings.totalChromeShare",
    odinValue = settingsTotalChromeShare(MimirCertifiedViewports.Odin.solverInputDp),
    tolerance = 0.005f,
)

private fun settingsReadings(): List<MimirProportionReading> =
    MimirCertifiedViewports.standard.flatMap { viewport ->
        listOf(
            MimirProportionReading(
                surface = "Settings (RC-8)",
                viewportName = viewport.name,
                proportion = SettingsRailChromeShareProportion,
                measured = settingsRailChromeShare(viewport.solverInputDp),
            ),
            MimirProportionReading(
                surface = "Settings (RC-8)",
                viewportName = viewport.name,
                proportion = SettingsMarginSymmetryProportion,
                measured = settingsMarginSymmetry(viewport.solverInputDp),
            ),
            MimirProportionReading(
                surface = "Settings (RC-8)",
                viewportName = viewport.name,
                proportion = SettingsTotalChromeShareProportion,
                measured = settingsTotalChromeShare(viewport.solverInputDp),
            ),
        )
    }

/**
 * ── THE BASELINE — a shrinking debt register, not a suppression list ────────────────────────────────────
 *
 * A bare `redCells.isEmpty()` assertion does not track known divergences: a permanently-red
 * assertion is a gate nobody reads, same failure mode as a green one that proves nothing. This entry type
 * is what makes a red cell a **tracked, ticketed, shrinkable** debt instead of either extreme.
 *
 * A cell in this file has exactly three states relative to its baseline row, and each is a distinct
 * failure the build must catch — all three use the same comparison mechanism:
 * 1. **NEW** — a red cell with no baseline row. A regression; this is the gate's entire purpose.
 * 2. **WORSENED** — a red cell whose baseline row exists but whose current `delta` now exceeds the row's
 *    recorded `delta` by more than [MimirOdinParityBaselineEpsilon]. A known divergence got worse without
 *    anyone updating its ticket.
 * 3. **STALE** — a baseline row whose cell is no longer red (fixed, or the proportion/viewport pair no
 *    longer exists in the current reading set). Forces a deletion, which is what makes the list shrink —
 *    without this, fixing a surface and forgetting to delete its row leaves a permanent excuse behind.
 */
internal data class MimirOdinParityBaselineEntry(
    val proportion: String,
    val viewport: String,
    val delta: Float,
    val ticket: String,
)

/**
 * Baseline deltas are hand-transcribed from this file's own `%.4f`-formatted report output,
 * which rounds to the nearest 1e-4. That rounding is the only noise source here — the underlying formulas
 * are pure/deterministic Float arithmetic with no RNG, IO or threading, so a rerun against an unchanged
 * source reproduces the exact same Float bit-for-bit. Recording a delta at 4 decimal places can therefore
 * differ from the exact recomputed Float by up to ±0.00005 (half the last printed digit's step). Epsilon
 * is set to **0.0001 — 2× that rounding bound** — enough to never flag a WORSENED failure on transcription
 * rounding alone, while staying two to three orders of magnitude below every real divergence this harness
 * has found (smallest is 0.0183, i.e. 183× this epsilon).
 */
internal const val MimirOdinParityBaselineEpsilon = 0.0001f

/** Shared by both modules' copies — see the sibling `feature-library` file's identical function for the
 * cross-check instruction ("diff them before trusting a claim sourced from just one"). */
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
 * The Settings debt register. Every row here is a real divergence measured from this file's own report at
 * the time of this ticket (2026-08-21) — none are suppressed, all are RC-8 (Settings inconsistencies),
 * except where a row's own comment marks it as re-measured under a later fix.
 *
 * **`settings.marginSymmetry` has NO rows below as of 2026-08-21.** The RC-8-era Nova and 538-companion
 * rows (both 0.2500) are DELETED, not just updated — production's [settingsDetailEdgeMarginDp] no longer
 * varies by width, so this proportion now measures 24/24 = 1.0 at every certified viewport including the
 * Odin, and every cell PASSES. This is the STALE mechanism working as designed: the baseline claimed red,
 * the cell no longer is, so the row is deleted rather than left as a permanent excuse.
 *
 * **`settings.totalChromeShare`'s four rows below all carry a NEW delta, re-measured against the new Odin
 * baseline (32.65%, down from 34.57%) after the margin change made the Odin's own chrome leaner by 16dp** (both
 * detail-pane edge margins dropped 32dp -> 24dp). Two different mechanisms are at work, named per row:
 * - **Nova and the 538 companion (both below [SettingsNarrowThresholdWidthDp]) WORSENED** by exactly the
 *   Odin's own 1.92-point share drop (32.65% vs 34.57%) — their own measured chrome share is bit-identical
 *   to before (their edge margin was already `xl` before the change), so a leaner denominator-of-comparison alone
 *   widens the gap.
 * - **The RP5-class shelf and the 1080 boundary (both AT/ABOVE the threshold) IMPROVED** — their own
 *   measured chrome share also dropped 8pp-of-margin-worth when the margin change landed (they were on the `xxl`
 *   side of the old branch too), which partially offsets the Odin's own drop rather than compounding it.
 *   Nothing in [mimirOdinParityBaselineFailures] forces these two down on its own (an improved-but-still-
 *   red cell still passes against a stale higher baseline) — ratcheted down here deliberately, per this
 *   file's own "a shrinking debt register" KDoc above.
 *
 * **SECOND UPDATE 2026-08-21 — four rows below (Nova and the 538 companion, both proportions) WORSENED
 * again**, this time by the rail-floor clip fix (device regression, build `54ff6193`; see
 * `settingsRailWidthDp`'s own "UPDATE" KDoc above for the full derivation). Both viewports are
 * floor-governed (below [SettingsNarrowThresholdWidthDp]), and the corrected floor (154dp, was 132dp) is
 * wider, so both viewports' own rail — and therefore their own chrome — genuinely grew; this is the real
 * cost of no longer clipping `"APPEARANCE"`, not measurement noise. Deltas below are hand-transcribed from
 * this file's own `%.4f` report output on an actual run (`:core-ui:testDebugUnitTest --tests
 * MimirOdinParityTest`), same as this file's own methodology requires — not recomputed by hand. The Odin,
 * the RP5-class shelf, and the 1080dp boundary are untouched by the rail-floor fix (Odin/1080 stay
 * ceiling-clamped at 168dp; the RP5-class shelf's unclamped 154dp value was already its natural value
 * under the OLD floor too — 0.22 * 700 = 154 lands exactly on the new floor by coincidence), so their rows
 * are unaffected and not repeated here.
 */
private val SettingsOdinParityBaseline = listOf(
    MimirOdinParityBaselineEntry(
        // WORSENED 2026-08-21 (rail-floor fix): grew from 0.0183 to 0.0389 -- the Nova's rail widened from
        // 140.8dp (uncoerced, clipped APPEARANCE) to the corrected 154dp floor. See list KDoc.
        "settings.railChromeShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.0389f, "RC-8",
    ),
    MimirOdinParityBaselineEntry(
        "settings.railChromeShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0183f, "RC-8",
    ),
    MimirOdinParityBaselineEntry(
        // WORSENED 2026-08-21 (rail-floor fix): grew from 0.0437 to 0.0846 -- the 538dp companion's rail
        // widened from 132dp (old, wrong floor) to the corrected 154dp floor. See list KDoc.
        "settings.railChromeShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.0846f, "RC-8",
    ),
    MimirOdinParityBaselineEntry(
        "settings.railChromeShare", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.0461f, "RC-8",
    ),
    // settings.marginSymmetry: RetroidPocketNova (was 0.2500) and OdinHeightCompanion538 (was 0.2500) rows
    // DELETED 2026-08-21 -- STALE, no longer red (see this list's own KDoc above).
    MimirOdinParityBaselineEntry(
        // WORSENED 2026-08-21 (rail-floor fix): grew from 0.0560 to 0.0766 -- same mechanism as the
        // railChromeShare row above, propagated through totalChromeShare. See list KDoc.
        "settings.totalChromeShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.0766f, "RC-8",
    ),
    MimirOdinParityBaselineEntry(
        // IMPROVED 2026-08-21: shrank from 0.0457 to 0.0420 -- ratcheted down deliberately (not forced by
        // the WORSENED check). This viewport is itself at/above the threshold, so its own measured share
        // also dropped when the margin change landed, partially offsetting the Odin's own drop. See list KDoc.
        // Unaffected by the later rail-floor fix (unclamped, already 154dp under the old floor too).
        "settings.totalChromeShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0420f, "RC-8",
    ),
    MimirOdinParityBaselineEntry(
        // WORSENED 2026-08-21 (rail-floor fix): grew from 0.1121 to 0.1530 -- same mechanism as the
        // railChromeShare row above, propagated through totalChromeShare. See list KDoc.
        "settings.totalChromeShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.1530f, "RC-8",
    ),
    MimirOdinParityBaselineEntry(
        // IMPROVED 2026-08-21: shrank from 0.0791 to 0.0747 -- ratcheted down deliberately, same mechanism
        // as the RP5-class shelf row above (at/above threshold, own measured share also dropped).
        // Unaffected by the later rail-floor fix (ceiling-clamped, same as the Odin).
        "settings.totalChromeShare", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.0747f, "RC-8",
    ),
)

/**
 * ── DOCK / QUICK RAIL — geometry coverage ─────────────────────────────────────────────────────────────
 *
 * `MimirRunicQuickRail.kt` was excluded from RC-1 step 4's first pass while mid-rewrite; the seat-width
 * unification landed at `5127f68a` ("One seat width for all six dock actions") and is now committed and
 * stable, so it joins the harness here. Every function called below (`mimirQuickRailTargetWidth`,
 * `mimirQuickRailSlotWidths`, `MimirRunicQuickRailDefaults`, `MimirQuickRailAction`) lives in THIS module,
 * same package (`core-ui`'s own `MimirRunicQuickRail.kt`) — real solver calls, not hand copies, unlike the
 * Settings section above which is fenced out of `feature-settings`.
 *
 * **Ground truth this section is checked against** (reported measurements from 2026-08-21): Nova seat pitch **61.4dp** (measured 123/123/122/124/122 px at
 * density 2.0, mean 61.4dp) and Odin seat pitch **88.2dp** (measured on the panel). The solver
 * below computes 61.333dp (Nova) and 88.0dp (Odin) — both within 0.2dp of the panel reading, i.e. pixel-
 * rounding-level agreement, not a divergence. See [dockSeatPitchSpreadWouldHaveCaughtThePreFixThreeWidthEra]
 * for the differential proof that this same solver call would NOT have agreed with a symmetric device
 * reading before `5127f68a` landed.
 *
 * ## Three proportions, and why each earns its place
 *
 * - **`dock.railWidthShare`** — `railWidth / viewportWidth`. Class 1 (reservation): the rail is exactly
 *   what [MimirRunicQuickRailDefaults.RailEndReserve] names a reservation for, and this is the proportion
 *   the RC-1 register's own coverage table cites by name: *"the dock was 82.5% of the Nova before
 *   `7a056f26` and 63.4% of the Odin, which was the original defect"* — the Odin's 63.4% was the reference
 *   ([mimirQuickRailTargetWidth]'s own KDoc calls it "fine"), Nova's divergence from it was. Post-fix Nova
 *   is 57.5%, narrower than before but still real distance from the Odin — recorded in the baseline below,
 *   not laundered.
 * - **`dock.seatPitchSpreadDp`** — `max(slotWidths) - min(slotWidths)`, in dp, across all six seats.
 *   Universal target **0.0**, not the Odin's own value — an uneven dock is wrong at every viewport, exactly
 *   [MimirSurfaceProportion]'s `settings.marginSymmetry` precedent (also resolved to a universal target by
 *   policy this same day). By construction every seat now shares one `preferredSlotWidth`
 *   ([MimirRunicQuickRailDefaults.ActionSlotWidth]) and one scale factor
 *   ([mimirQuickRailSlotWidths]'s uniform-scale-then-floor), so this reads bit-identical 0.0 at every
 *   certified viewport today — a real, currently-holding invariant, not a vacuous one; see the differential
 *   test below for proof it would have failed before `5127f68a`.
 *
 *   **Deliberate overlap with `MimirCertifiedViewportMatrixTest.kt`:**
 *   `quickRailSlotsAreEqualWidthAtEveryCertifiedViewport()` in that file ALREADY pins the identical fact —
 *   `distinct.size == 1` across all six seats' widths — and does so over EVERY certified viewport
 *   ([MimirCertifiedViewports.all], including COMPACT_SQUARE, wider coverage than this proportion's
 *   [MimirCertifiedViewports.standard]-only scope). That test is the invariant's canonical owner; it does
 *   not disappear or get superseded here. `dock.seatPitchSpreadDp` is kept anyway, for one reason only: this
 *   instrument provides a SINGLE printable coverage table across every named surface
 *   ([mimirProportionReport]), and the boolean pin does not feed into it — seat-pitch uniformity belongs in this printable table even
 *   though the fact itself was already true and already guarded. No new regression protection is claimed
 *   from this proportion beyond what the matrix test already provides.
 * - **Label-lane headroom — CLOSED 2026-08-21, no longer UNMEASURABLE.** This paragraph previously recorded
 *   that no pure function existed in this tree from (a resolved `TextUnit`, a label string) to a rendered
 *   ink width-or-height in dp, and that `core-ui`'s test tree had no Compose/Robolectric text-measurement
 *   harness to build one with. Both gaps are closed by `MimirTextMetricsHarnessTest.kt` (same package, same
 *   module) — real Compose text shaping under Robolectric `@GraphicsMode(NATIVE)` against the shipped
 *   `ibm_plex_sans_*.ttf` binaries (kerning/ligatures/letterSpacing included, not approximated), verified
 *   against an independent hand-derived `"APPEARANCE"`-at-`RunicEyebrow` ink figure (harness:
 *   82.00dp; hand `hmtx`-table derivation: ~82dp — 0.00dp delta). That file's
 *   `dock.labelLaneHeadroomShare` proportion — `(slotWidth - inkWidth) / slotWidth` for the worst seat,
 *   driven through this module's own [mimirQuickRailTargetWidth]/[mimirQuickRailSlotWidths]/
 *   [mimirQuickRailLabelFontSize] solver chain — is what was UNMEASURABLE here; it is now measured, gated
 *   through the same baseline-debt-register mechanism as every other proportion in this file, and
 *   currently GREEN AT THE FLOOR: no certified STANDARD viewport measures a negative (clipping) headroom
 *   today, though several (Nova, RP5-class shelf, the 538dp companion) diverge from the Odin's own headroom
 *   *ratio* by more than tolerance and are honestly baseline-tracked red there, same as this file's own
 *   `dock.railWidthShare`. Not duplicated into this plain-JUnit file because measuring ink needs the
 *   Robolectric/Compose harness — putting `@RunWith(RobolectricTestRunner)` on this file would change every
 *   other test's runner here; see that file's own top KDoc for the full "why Robolectric, not a font-table
 *   reader" derivation.
 *
 * ## Tolerance derivation
 *
 * `dock.railWidthShare` — **the accepted-pair method degenerates here, because the available reference pair already diverges.** The proposed reference pair is Odin and the
 * RP5-class shelf; but Rp5Shelf's own `railWidthShare` is 0.0224 off the Odin's (61.1% vs 63.4%) — two
 * orders of magnitude above the "<0.0002, float rounding only" spread the method requires of an
 * uncontested pair, so Rp5Shelf is itself one of this proportion's red rows, not a quiet reference (the
 * same failure mode `settings.totalChromeShare` hit above). Falls back to this file's own established
 * Class-1 constant, **0.005**, for consistency with every other Class-1 proportion in this file rather than
 * a fresh derivation with no clean pair to derive it from.
 *
 * `dock.seatPitchSpreadDp` — a structural invariant (bit-identical Float arithmetic across all six seats
 * whenever it holds at all), so it gets the tight **0.001** tolerance: generous enough to absorb any stray
 * float rounding, two to three orders of magnitude below the >8dp spread the pre-`5127f68a` shape produced
 * (see the differential test).
 *
 * ## Posture scoping
 *
 * [MimirCertifiedViewports.standard] only, same restriction and same reasoning as the Settings section
 * above (COMPACT_SQUARE changes composition, not just geometry).
 */
private fun dockSlotWidths(viewport: MimirDpBox): List<Dp> {
    val targetWidth = mimirQuickRailTargetWidth(viewport.widthDp.dp)
    return mimirQuickRailSlotWidths(MimirQuickRailAction.entries, targetWidth)
}

private fun dockRailWidthDp(viewport: MimirDpBox): Float =
    dockSlotWidths(viewport).fold(0.dp) { width, slot -> width + slot }.value

private fun dockRailWidthShare(viewport: MimirDpBox): Float =
    dockRailWidthDp(viewport) / viewport.widthDp

private val DockRailWidthShareProportion = MimirSurfaceProportion(
    name = "dock.railWidthShare",
    odinValue = dockRailWidthShare(MimirCertifiedViewports.Odin.solverInputDp),
    tolerance = 0.005f,
)

/** `max - min` across the six seats' own solved widths, in dp. See this section's own KDoc. */
private fun dockSeatPitchSpreadDp(viewport: MimirDpBox): Float {
    val widths = dockSlotWidths(viewport).map { it.value }
    return (widths.maxOrNull() ?: 0f) - (widths.minOrNull() ?: 0f)
}

private val DockSeatPitchSpreadProportion = MimirSurfaceProportion(
    name = "dock.seatPitchSpreadDp",
    odinValue = 0f,
    tolerance = 0.001f,
)

private fun dockReadings(): List<MimirProportionReading> =
    MimirCertifiedViewports.standard.flatMap { viewport ->
        listOf(
            MimirProportionReading(
                surface = "Dock / Quick Rail",
                viewportName = viewport.name,
                proportion = DockRailWidthShareProportion,
                measured = dockRailWidthShare(viewport.solverInputDp),
            ),
            MimirProportionReading(
                surface = "Dock / Quick Rail",
                viewportName = viewport.name,
                proportion = DockSeatPitchSpreadProportion,
                measured = dockSeatPitchSpreadDp(viewport.solverInputDp),
            ),
        )
    }

/**
 * The dock's debt register. Every row is a real `dock.railWidthShare` divergence measured from this file's
 * own report output at ticket commission (2026-08-21) — none suppressed. `dock.seatPitchSpreadDp` carries
 * NO rows: every certified viewport passes it today (see this section's own KDoc for why that is a real,
 * currently-true invariant rather than an unmeasured one). Ticketed against `5127f68a`/`7a056f26` — the
 * commits that produced the current [mimirQuickRailTargetWidth]/[mimirQuickRailSlotWidths] shape .
 */
private val DockOdinParityBaseline = listOf(
    MimirOdinParityBaselineEntry(
        "dock.railWidthShare", MimirCertifiedViewports.RetroidPocketNova.name, 0.0589f, "DOCK (5127f68a/7a056f26)",
    ),
    MimirOdinParityBaselineEntry(
        "dock.railWidthShare", MimirCertifiedViewports.Rp5Shelf.name, 0.0224f, "DOCK (5127f68a/7a056f26)",
    ),
    MimirOdinParityBaselineEntry(
        "dock.railWidthShare", MimirCertifiedViewports.OdinHeightCompanion538.name, 0.0985f, "DOCK (5127f68a/7a056f26)",
    ),
    MimirOdinParityBaselineEntry(
        "dock.railWidthShare", MimirCertifiedViewports.LargeSquareBoundary1080.name, 0.1450f, "DOCK (5127f68a/7a056f26)",
    ),
)

class MimirOdinParityTest {

    @Test
    fun mimirOdinParitySettingsReport() {
        val readings = settingsReadings()
        val report = mimirProportionReport(readings)
        println(report)
        val failures = mimirOdinParityBaselineFailures(readings, SettingsOdinParityBaseline)
        assertTrue(
            "Odin-parity baseline violations (Settings) — each is a NEW divergence (no baseline row), a " +
                "WORSENED one (delta grew past its baseline row), or a STALE row (baseline claims red, " +
                "cell now passes — delete the row). See SettingsOdinParityBaseline for the debt register.\n" +
                failures.joinToString("\n") + "\n\n$report",
            failures.isEmpty(),
        )
    }

    /**
     * Falsifiability demonstration: mutating an Odin baseline makes a passing cell fail. Done entirely inside the harness — no production file is touched — by
     * corrupting a [MimirSurfaceProportion]'s own `odinValue` and re-comparing the SAME measured value
     * against it.
     */
    @Test
    fun harnessProportionComparisonIsFalsifiable() {
        val odinBox = MimirCertifiedViewports.Odin.solverInputDp
        val measured = settingsRailChromeShare(odinBox)
        val genuine = SettingsRailChromeShareProportion
        val corrupted = genuine.copy(odinValue = genuine.odinValue + 0.5f)

        val genuineReading = MimirProportionReading("Settings (RC-8)", "Odin", genuine, measured)
        val corruptedReading = MimirProportionReading("Settings (RC-8)", "Odin", corrupted, measured)

        assertTrue(
            "sanity: the real Odin baseline must pass AT the Odin (it is defined from the Odin's own value)",
            genuineReading.pass,
        )
        assertTrue(
            "mutating the Odin baseline by +0.5 must flip an otherwise-passing cell to failing: $corruptedReading",
            !corruptedReading.pass,
        )
    }

    /**
     * Falsifiability demonstration for the STALE-entry rule: a baseline row for a passing cell
     * must fail the build, or the baseline could never shrink. Uses a synthetic baseline, never [SettingsOdinParityBaseline] itself, so the real
     * debt register stays untouched by this test.
     */
    @Test
    fun harnessBaselineStaleEntryFailsForAPassingCell() {
        val odinBox = MimirCertifiedViewports.Odin.solverInputDp
        val passingReading = MimirProportionReading(
            "Settings (RC-8)",
            "Odin",
            SettingsRailChromeShareProportion,
            settingsRailChromeShare(odinBox),
        )
        assertTrue(
            "sanity: the Odin cell must actually pass, or this test proves nothing",
            passingReading.pass,
        )

        val staleBaseline = listOf(
            MimirOdinParityBaselineEntry(SettingsRailChromeShareProportion.name, "Odin", 0.05f, "RC-TEST"),
        )
        val failures = mimirOdinParityBaselineFailures(listOf(passingReading), staleBaseline)

        assertTrue(
            "a baseline row claiming a now-passing cell as red must fail as STALE: $failures",
            failures.any { it.contains("STALE") },
        )
    }

    /** Dock / quick rail coverage extension — see this file's own "DOCK / QUICK RAIL" section KDoc. */
    @Test
    fun mimirOdinParityDockReport() {
        val readings = dockReadings()
        val report = mimirProportionReport(readings)
        println(report)
        val failures = mimirOdinParityBaselineFailures(readings, DockOdinParityBaseline)
        assertTrue(
            "Odin-parity baseline violations (Dock / Quick Rail) — each is a NEW divergence (no baseline " +
                "row), a WORSENED one (delta grew past its baseline row), or a STALE row (baseline claims " +
                "red, cell now passes -- delete the row). See DockOdinParityBaseline for the debt register.\n" +
                failures.joinToString("\n") + "\n\n$report",
            failures.isEmpty(),
        )
    }

    /**
     * Falsifiability demonstration for the dock (same mechanism as
     * [harnessProportionComparisonIsFalsifiable] above, applied to the dock proportion). No
     * production file is touched -- corrupts a [MimirSurfaceProportion]'s own `odinValue` and re-compares
     * the SAME measured value against it.
     */
    @Test
    fun dockHarnessProportionComparisonIsFalsifiable() {
        val odinBox = MimirCertifiedViewports.Odin.solverInputDp
        val measured = dockRailWidthShare(odinBox)
        val genuine = DockRailWidthShareProportion
        val corrupted = genuine.copy(odinValue = genuine.odinValue + 0.5f)

        val genuineReading = MimirProportionReading("Dock / Quick Rail", "Odin", genuine, measured)
        val corruptedReading = MimirProportionReading("Dock / Quick Rail", "Odin", corrupted, measured)

        assertTrue(
            "sanity: the real Odin baseline must pass AT the Odin (it is defined from the Odin's own value)",
            genuineReading.pass,
        )
        assertTrue(
            "mutating the Odin baseline by +0.5 must flip an otherwise-passing cell to failing: $corruptedReading",
            !corruptedReading.pass,
        )
    }

    /**
     * Differential proof that [DockSeatPitchSpreadProportion] is a real, currently-holding invariant, not
     * a vacuous one -- reconstructs the PRE-`5127f68a` per-action preferred widths (Odin
     * `[88,88,88,88,104,72]`, Nova `[52,52,52,52,96,64]`, RP5 `[67,67,67,67,96,64]`, hand-transcribed from
     * that commit's own message, which grounds them against a measured Nova panel read: coin-centre gaps
     * of 104/104/104/148/160px matched the [52,52,52,52,96,64]dp model exactly at the Nova's 2.0 density)
     * as a LOCAL test double -- no production file is touched -- and shows the exact same spread metric
     * this file uses today would have read nonzero on ALL THREE devices under the old shape, while the
     * CURRENT production [mimirQuickRailSlotWidths] reads bit-identical 0.0 on the same three at the same
     * viewports. This is the falsifiability the ticket asks for, grounded in a real historical regression
     * rather than a synthetic corruption.
     */
    @Test
    fun dockSeatPitchSpreadWouldHaveCaughtThePreFixThreeWidthEra() {
        fun spread(widths: List<Float>): Float = widths.max() - widths.min()

        val preFixOdin = listOf(88f, 88f, 88f, 88f, 104f, 72f)
        val preFixNova = listOf(52f, 52f, 52f, 52f, 96f, 64f)
        val preFixRp5 = listOf(67f, 67f, 67f, 67f, 96f, 64f)

        assertTrue("pre-fix Odin seats must have spread under the OLD shape: ${spread(preFixOdin)}", spread(preFixOdin) > 1f)
        assertTrue("pre-fix Nova seats must have spread under the OLD shape: ${spread(preFixNova)}", spread(preFixNova) > 1f)
        assertTrue("pre-fix RP5 seats must have spread under the OLD shape: ${spread(preFixRp5)}", spread(preFixRp5) > 1f)

        val currentOdin = dockSeatPitchSpreadDp(MimirCertifiedViewports.Odin.solverInputDp)
        val currentNova = dockSeatPitchSpreadDp(MimirCertifiedViewports.RetroidPocketNova.solverInputDp)
        val currentRp5 = dockSeatPitchSpreadDp(MimirCertifiedViewports.Rp5Shelf.solverInputDp)

        assertTrue("current Odin spread must be exactly 0.0 today: $currentOdin", currentOdin == 0f)
        assertTrue("current Nova spread must be exactly 0.0 today: $currentNova", currentNova == 0f)
        assertTrue("current RP5 spread must be exactly 0.0 today: $currentRp5", currentRp5 == 0f)
    }
}
