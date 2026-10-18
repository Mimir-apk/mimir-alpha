package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.files.FileSearchFilter
import com.gruning.mimir.rewrite.core.ui.MimirSearchGlyphSize
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep

/**
 * **F-S1 — the search screen's width solve.**  It had not been:
 * The earlier coverage register listed no symbol from
 * `FileBrowserSearchSurface.kt` anywhere in the RC register.
 *
 * ## The defect this file exists to make impossible
 *
 * The control row is a `weight(1f)` query field beside five intrinsic-width chips, and the scope card
 * beside it holds a FIXED [FileBrowserInspectorWidthDp]. Chips measure first; the weighted field takes
 * whatever is left. On the Nova there is nothing left: the field solved to **8.5dp** — a focus ring
 * around an empty slit, no ⌕, no placeholder, and **dead to touch**, so a touch player could not search
 * by name at all (the query field's activation is the only opener —
 * [FileBrowserSearchCommand.OpenKeyboard] fires from [FileBrowserSearchFocus.QueryKey] alone).
 *
 * ## Why a clearance function and NOT a width bucket
 *
 * The failure is **continuous in width** — a fixed-reservation defect class: a fixed reservation meeting intrinsic content with a weighted remainder, no floor. A
 * threshold constant cannot see it. Concretely: a `680f`-style gate would have left **RP5-class (700dp)
 * on a 73.5dp query field** — measurably broken on a certified viewport, and green. So the rule is a
 * function of the solved remainder against the field's own floor, on the shipped precedent
 * `homeShelfTomeShortcutHintClearanceDp` (`a1143679`):
 *
 * ```
 * queryWidthDp(W, seated) = W − 36 − (seated ? 270 + 14 : 0) − chipsIntrinsicDp − 5·7
 * clearanceDp(W)          = queryWidthDp(W, seated = true) − queryFloorDp
 * scope card is seated   ⇔ clearanceDp(W) >= 0
 * ```
 *
 * **A reader can predict an unseen viewport from that alone**: below `W ≈ 799dp` the card yields, above
 * it the card stays. Nothing here is bucketed, so a device this tree has never seen gets the same rule.
 *
 * ## Every term is measured or named, none are picked
 *
 * The width terms are [FileBrowserSurface]'s own constructs, already named by [FileBrowserGrid]'s
 * chrome constants. The two TEXT terms cannot be computed on the JVM (no font metrics in a unit test),
 * so they are **measured on hardware and scaled by the type step**, never frozen:
 * [FileBrowserSearchChipInk] and [FileBrowserSearchQueryPlaceholderInkDp] carry the step they were
 * measured at, and every consumer divides by it. Change a chip's type and the clearance moves with it —
 * which is exactly what F-S1's contract demands ("`chipsIntrinsicDp` ... **recomputed, not frozen**").
 *
 * **The model reproduces three independent measurements it was not fitted to.** Calibrated only on the
 * Nova's chip run, it predicts the Odin's query field at 201.5dp against **205.5dp measured**, and the
 * spec's own [D] figure for RP5-class (73.5dp) exactly. See `FileBrowserSearchGeometryTest`.
 */

// ──────────────────────────────────────────────────────────── The control row's own constructs ──

/** [FileBrowserSearchControls]' `horizontalArrangement = Arrangement.spacedBy(7.dp)`. */
internal const val FileBrowserSearchControlGapDp = 7f

/** One [FileBrowserSearchChip]'s `.padding(horizontal = 11.dp)`, both sides. */
internal const val FileBrowserSearchChipHorizontalPaddingDp = 22f

/** [FileBrowserSearchQueryField]'s `.padding(horizontal = 12.dp)`, both sides. */
internal const val FileBrowserSearchQueryHorizontalPaddingDp = 24f

/** [FileBrowserSearchQueryField]'s `horizontalArrangement = Arrangement.spacedBy(8.dp)` — ⌕ to text. */
internal const val FileBrowserSearchQueryGlyphGapDp = 8f

/**
 * The line box a bare `Text(fontSize = …)` is given on this surface — **[M], and it does NOT scale with
 * the font size.**
 *
 * Measured 2026-08-22 on the Nova (`nova_before_02_search.png`, `nova_before_03b_games.png`, APK sha256
 * `ef15a775639672fd8f3c190c60b4304aeea9e6a597c62acd0f1dad72d9abf5a7`), four independent readings that a
 * proportional line box cannot fit simultaneously:
 *
 * | site | type | painted | flat-24 predicts | 2.09em predicts |
 * |---|---|---|---|---|
 * | filter chip | 11.5sp + 12dp padding | **36.0** | 36.0 ✓ | 36.0 ✓ |
 * | query field | 15sp glyph + 13sp text + 12dp padding | **36.0** | 36.0 ✓ | 43.3 ✗ |
 * | result row | 13sp + 10sp + 14dp padding | **62.0** | 62.0 ✓ | 62.0 ✓ |
 * | scope copy | 11.5sp, three wrapped lines | **24.0/line** | 24.0 ✓ | 24.0 ✓ |
 *
 * The query field is the discriminator: a proportional box at 15sp would paint it 43.3dp tall and it
 * paints 36.0. A `Text` that passes a full [com.gruning.mimir.rewrite.core.ui.MimirTypeScale] style
 * instead does NOT get this box — the status line (`RunicEyebrow`, 11sp) paints 14.5dp, the font's own
 * ~1.32em — because a `style =` argument replaces the ambient text style rather than merging with it.
 *
 * **Mechanism: an inherited `lineHeight` on the ambient text style. Its provider is UNKNOWN in this
 * tree** — `MimirTheme` (`MimirTheme.kt:75`) copies `LocalTextStyle.current` forward with only the font
 * family changed, so whatever sets 24sp sits above it and was not found by grep. Recorded as a measured
 * behaviour, not as a claim about a source line. Load-bearing consequence, and the reason it is written
 * down here rather than left implicit: **F-S3's type conformance moves no height on this screen**, since
 * 14sp and 11sp are both still under the flat box.
 */
internal const val FileBrowserSearchTextLineBoxDp = 24f

// ──────────────────────────────────────────────────────────── Type steps this surface speaks ──

/**
 * F-S3: the chips conform to `Pill`'s own step. Named here as well as at the call site because
 * [fileBrowserSearchChipWidthDp] divides the measured ink by the step it was measured at — the two must
 * move together or the ink table silently rescales against the wrong denominator.
 */
internal val FileBrowserSearchChipStepSp: Float = MimirTypeStep.Label.value

/** F-S3: the query's placeholder conforms to `Body`. (Its magnifier is core-ui's drawn mark at [MimirSearchGlyphSize], not type.) */
internal val FileBrowserSearchQueryStepSp: Float = MimirTypeStep.Body.value

// ──────────────────────────────────────────────────────────── Measured ink, and its calibration ──

/**
 * The size the ink tables below were measured at. Every consumer scales by
 * `step / FileBrowserSearchInkMeasuredChipStepSp`, so conforming the chips (11.5 → 11) narrows them
 * rather than leaving a frozen number behind.
 */
internal const val FileBrowserSearchInkMeasuredChipStepSp = 11.5f

/** The size the query placeholder's ink was measured at. */
internal const val FileBrowserSearchInkMeasuredQueryTextStepSp = 13f

/**
 * Each chip's label ink in dp at [FileBrowserSearchInkMeasuredChipStepSp] — **[M] on the Nova
 * 2026-08-22**, chip plate run minus [FileBrowserSearchChipHorizontalPaddingDp].
 *
 * Painted chip plates measured 38.5 / 61.0 / 71.0 / 51.0 / 55.0 dp. Those five close the row EXACTLY at
 * 640dp — `18 + 8.5 + 5·7 + 276.5 + 14 + 270 + 18 = 640` — which is why this table is calibrated on the
 * Nova rather than on the Odin: the Odin's identical chips paint ~1dp narrower each (271.3 total, same
 * arithmetic closure at 832.5dp), because text advances round to whole PIXELS and the Odin has 2.30625
 * of them per dp against the Nova's 2.0. **Calibrating on the wider device is the conservative
 * direction**: the model then never under-states what the chips cost, so it never leaves the card seated
 * on a field that is narrower than the model believes.
 *
 * Keyed by [FileSearchFilter] rather than positionally, so re-ordering the enum cannot silently reassign
 * one chip's measurement to another. `FileBrowserSearchGeometryTest` pins the five LABELS this table was
 * measured against, so a re-wording in `core-files` reddens here instead of drifting.
 */
internal val FileBrowserSearchChipInk: Map<FileSearchFilter, Float> = mapOf(
    FileSearchFilter.ALL to 16.5f,
    FileSearchFilter.GAMES to 39.0f,
    FileSearchFilter.ARCHIVES to 49.0f,
    FileSearchFilter.BIOS to 29.0f,
    FileSearchFilter.SAVES to 33.0f,
)

/**
 * `"Search this volume"`'s own ink in dp at [FileBrowserSearchInkMeasuredQueryTextStepSp] — **[M] on the
 * Odin 2026-08-22** (`odin_before_03_search.png`), where the field is healthy enough to paint it: ink ran
 * 48.56 → 162.19dp inside the field, plus the side bearings its neighbours' positions imply, giving a
 * 115.45dp text node.
 *
 * **This is the term the spec estimated and the hardware corrected.** F-S1's contract guessed *"text ~95
 * dp at the current 13 sp (~102 at Body 14) ... floor 137–144dp"*; measured, the placeholder is 115.45dp
 * at 13sp and the floor is ~157dp today, ~165dp at Body 14. The spec's own sign table is unaffected —
 * every certified viewport lands on the same side of the boundary under either number (§ this file's
 * tests) — but the boundary itself moves from ~763dp to ~799dp, and a floor set 20dp light would have
 * left a field that ellipsises its own placeholder counting as "cleared".
 */
internal const val FileBrowserSearchQueryPlaceholderInkDp = 115.45f

// ──────────────────────────────────────────────────────────── The solve ──

/**
 * One chip's painted width: its measured ink rescaled to the step it now speaks, plus its own padding —
 * then **floored at [MimirSpacing.minTarget] (F-S4)**.
 *
 * The floor is a LAYOUT floor, and it is deliberately inside the width model rather than bolted on at
 * the call site: expanding `All`'s hit box from 37.8dp to 48dp costs the control row 10dp of real width,
 * and a clearance function that did not know that would hand the query field 10dp it never gets.
 */
internal fun fileBrowserSearchChipWidthDp(
    filter: FileSearchFilter,
    stepSp: Float = FileBrowserSearchChipStepSp,
    hitFloorDp: Float = MimirSpacing.minTarget.value,
): Float {
    val ink = FileBrowserSearchChipInk[filter] ?: 0f
    val painted = ink * (stepSp / FileBrowserSearchInkMeasuredChipStepSp) +
        FileBrowserSearchChipHorizontalPaddingDp +
        fileBrowserSearchChipRescaleAllowanceDp(stepSp)
    return maxOf(painted, hitFloorDp)
}

/**
 * The allowance a RESCALED chip carries, and zero for a chip at the size it was measured at.
 *
 * **Bought with a measurement, not assumed.** Once the conformed build was on the Nova the painted chips
 * came out **282.0dp against a modelled 279.5** — the model was 2.5dp light across five chips, i.e. ~0.5
 * each, because a text advance is laid out in whole PIXELS: scaling 11.5sp ink down to 11sp does not
 * scale the rounding with it, and every chip rounds back up. 2.5dp is small, but its SIGN is wrong — a
 * model that over-states the query field is a model that can leave the scope card seated on a device
 * sitting just above the boundary, which is the exact failure mode this file exists to prevent. Erring
 * toward wide chips errs toward yielding, and yielding is the recoverable direction.
 *
 * Zero at [FileBrowserSearchInkMeasuredChipStepSp] because there is no rescale there: the ink table IS
 * that measurement, and adding an allowance to it would corrupt the before-state reconstruction that
 * reproduces the reported 8.5dp slit.
 */
internal fun fileBrowserSearchChipRescaleAllowanceDp(stepSp: Float): Float =
    if (stepSp == FileBrowserSearchInkMeasuredChipStepSp) 0f else 0.5f

/**
 * What all five chips cost the control row together — recomputed from [fileBrowserSearchChipWidthDp],
 * never frozen.
 *
 * **Both terms are parameterised, and that is not a test hook.** A fix to a defect this size has to be
 * able to state its own before and after at every certified viewport, and "before" is this surface at
 * its shipped chip step with no touch floor. Defaults are what production uses; passing
 * [FileBrowserSearchInkMeasuredChipStepSp] with `hitFloorDp = 0f` reconstructs the geometry that was
 * measured on hardware on 2026-08-22, so the two states are one function apart instead of one
 * hand-arithmetic apart.
 */
internal fun fileBrowserSearchChipsIntrinsicDp(
    stepSp: Float = FileBrowserSearchChipStepSp,
    hitFloorDp: Float = MimirSpacing.minTarget.value,
): Float = FileSearchFilter.entries
    .sumOf { fileBrowserSearchChipWidthDp(it, stepSp, hitFloorDp).toDouble() }
    .toFloat()

/**
 * The narrowest query field that still shows what it is for: the ⌕, the gap, the whole placeholder, and
 * the field's own padding. Below this the field is not small, it is **lying** — a ring around a control
 * whose purpose has been ellipsised away, which is the state the Nova shipped in.
 */
internal fun fileBrowserSearchQueryFloorDp(stepSp: Float = FileBrowserSearchQueryStepSp): Float =
    FileBrowserSearchQueryHorizontalPaddingDp +
        MimirSearchGlyphSize.value +
        FileBrowserSearchQueryGlyphGapDp +
        FileBrowserSearchQueryPlaceholderInkDp * (stepSp / FileBrowserSearchInkMeasuredQueryTextStepSp)

/**
 * What the weighted query field actually solves to at [surfaceWidthDp], with the scope card either
 * seated beside the results or yielded.
 *
 * Every subtracted term names a construct: [FileBrowserContentRowHorizontalPaddingDp] is
 * [FileBrowserSurface]'s content `Row` padding, [FileBrowserContentRowGapDp] its arrangement, and
 * [FileBrowserInspectorWidthDp] the card's fixed width — the same three [fileBrowserSolveGrid] uses, so
 * the folio and the search screen can never disagree about the chrome between them.
 */
internal fun fileBrowserSearchQueryWidthDp(
    surfaceWidthDp: Float,
    scopeCardSeated: Boolean,
    chipStepSp: Float = FileBrowserSearchChipStepSp,
    chipHitFloorDp: Float = MimirSpacing.minTarget.value,
): Float {
    val cardCost = if (scopeCardSeated) {
        FileBrowserInspectorWidthDp + FileBrowserContentRowGapDp
    } else {
        0f
    }
    return surfaceWidthDp -
        FileBrowserContentRowHorizontalPaddingDp -
        cardCost -
        fileBrowserSearchChipsIntrinsicDp(chipStepSp, chipHitFloorDp) -
        FileSearchFilter.entries.size * FileBrowserSearchControlGapDp
}

/**
 * **THE clearance.** How much room a SEATED scope card leaves the query field over and above the field's
 * own floor. Negative means the card is taking room the primary control needs.
 *
 * Sign-pinned at every certified viewport by `FileBrowserSearchGeometryTest` — Odin positive, Nova and
 * RP5-class negative — so the pin reproduces today's defect and a half-fix fails loudly.
 */
internal fun fileBrowserSearchQueryClearanceDp(surfaceWidthDp: Float): Float =
    fileBrowserSearchQueryWidthDp(surfaceWidthDp, scopeCardSeated = true) - fileBrowserSearchQueryFloorDp()

/**
 * **The layout boundary, as one predicate: when the clearance goes negative the scope card yields.**
 *
 * The card leaves the right column and its verbatim sentence re-seats full-width under the status line.
 * *The static promise must never outrank the feature's primary control.* The sentence itself is
 * untouchable and untouched — [com.gruning.mimir.rewrite.core.files.FILE_SEARCH_SCOPE_COPY] stays
 * rendered verbatim from its `core-files` const; only its seat moves. Dropping it was considered and
 * rejected upstream: it is a privacy promise, and copy is product truth.
 */
internal fun fileBrowserSearchScopeCardSeated(surfaceWidthDp: Float): Boolean =
    fileBrowserSearchQueryClearanceDp(surfaceWidthDp) >= 0f

/**
 * The width at which the card yields, stated so a reader can place an unseen device without running the
 * solve. Derived, never compared against: [fileBrowserSearchScopeCardSeated] is the predicate, and a
 * second threshold constant used AS a gate is the bucket this whole file refuses.
 */
internal fun fileBrowserSearchScopeCardYieldWidthDp(): Float =
    FileBrowserContentRowHorizontalPaddingDp +
        FileBrowserInspectorWidthDp +
        FileBrowserContentRowGapDp +
        fileBrowserSearchChipsIntrinsicDp() +
        FileSearchFilter.entries.size * FileBrowserSearchControlGapDp +
        fileBrowserSearchQueryFloorDp()

// ──────────────────────────────────────────────────────────── F-S2: the screen's own two constants ──

/**
 * The control row's painted height — **[MimirSpacing.minTarget] (F-S4), because both of its element
 * kinds are under the floor without it.**
 *
 * The query field's authored `min-height:34px` and the chip's `30` are the DESIGN MOCK's numbers, and
 * the mock is not authority over a standing 48dp touch floor. The painted content is 36dp
 * ([FileBrowserSearchTextLineBoxDp] + the field's 12dp of vertical padding), so this is a real 12dp of
 * hit extent the row did not have, not a restatement.
 */
internal val FileBrowserSearchControlRowHeightDp: Float =
    maxOf(MimirSpacing.minTarget.value, FileBrowserSearchTextLineBoxDp + 12f)

/**
 * **F-S2 (half one): the search screen's own content viewport, solved from the box it is in.**
 *
 * Was a frozen `307f` derived from the folio's frozen `350f` — while the folio itself, since RC-12,
 * solves [FileBrowserGrid.contentViewportDp] from its measured box. The two errors partially cancelled
 * on the Nova (304dp real against 307 assumed), which is luck, not correctness, and nothing cancelled
 * them on any other device.
 *
 * ```
 * search viewport = grid.contentViewportDp                  the folio's, now SOLVED
 *                 - FileBrowserSearchControlRowHeightDp     the control row this screen adds
 *                 - FileBrowserRowGapDp                     the content Column's own spacing
 * ```
 *
 * The KDoc this replaces already wrote that derivation; only its inputs were frozen. Note the spec's §4
 * pin says `contentViewportDp − 43` — that was written against the old 34dp field, before its own F-S4
 * put the control row on the 48dp floor. The derivation is the contract; the constant is not.
 */
internal fun fileBrowserSearchViewportDp(grid: FileBrowserGrid): Float =
    grid.contentViewportDp - FileBrowserSearchControlRowHeightDp - FileBrowserRowGapDp

/**
 * **F-S2 (half two): one result row's painted height, from its REAL two-line metrics.**
 *
 * Was a frozen `46f` floor that assumed a single-line row. The row renders two lines — the name and
 * where it lives — and measures **62.0dp on the Nova** [M 2026-08-22, four consecutive rows, pitch
 * 71.0dp], which is 16dp per row the lattice never knew about.
 *
 * ```
 * row height = 14   the row's own `.padding(vertical = 7.dp)`, both sides
 *            + 24   the name line   ([FileBrowserSearchTextLineBoxDp])
 *            + 24   the where line  (same box — it does not scale with the step)
 *            = 62 dp,  floored at MimirSpacing.minTarget (F-S4; 62 already clears it)
 * ```
 *
 * Because the line box is flat, **F-S3's 13→14 and 10→11 move this by zero** — the fix is the lattice
 * catching up with the paint, and the paint does not move.
 */
internal val FileBrowserSearchResultRowHeightDp: Float =
    maxOf(MimirSpacing.minTarget.value, 14f + 2f * FileBrowserSearchTextLineBoxDp)
