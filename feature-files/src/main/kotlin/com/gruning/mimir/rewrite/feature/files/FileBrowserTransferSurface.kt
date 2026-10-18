package com.gruning.mimir.rewrite.feature.files

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing

/**
 * **Move/Copy slice 1 — the transfer chooser's door.**
 * This screen consumes the controller/dispatch/decision contract; store wiring lives in `app/`.
 *
 * Slice 1 is deliberately narrow (spec §10a): **two rows, no smart platform default** — `Move into…` and
 * `Copy into…`, never a `<CODE>`-suffixed row. That is [FileBrowserTransferKind] being a two-arm enum
 * rather than the three-arm shape [FileBrowserExtractOptionKind] has, and it is why this screen renders
 * one line per row rather than [FileBrowserExtractOptionRow]'s optional two — there is no per-row detail
 * to show yet ([FileBrowserTransferOption] carries a label and a kind, nothing else). Slice 2 (the smart
 * `Move into <CODE>` / `Copy into <CODE>` rows) is gated on a device measurement nobody has taken (spec
 * §11 M1: this `Column` has no scroll and no `LazyListState`, so a four-row overflow clips silently) and
 * is explicitly **not** this file's job.
 *
 * ## It REPLACES the browse content, on [FileBrowserExtractScreen]'s own argument
 *
 * Not re-argued here — the mechanism is identical and checkable the same way, because both choosers
 * occupy the exact same content slot in `FileBrowserSurface.kt`: this surface's ring and the whole
 * standing inspector are derived from `focusState.takeIf { it.scope == controller.scope }?.focused`,
 * which is null for as long as this chooser's own scope is pushed. An overlay would leave a ringless
 * folio and an empty inspector behind a two-row question, for the same reason [FileBrowserExtractScreen]
 * gives at length — see that KDoc rather than a second copy of the argument.
 *
 * ## What this file does NOT own, and does not build
 *
 * The row list, the destination pick, the mutual exclusion with the Extract chooser (and with search and
 * the queue), and the two commit-row purposes this screen hands off to (`MOVE_DESTINATION` /
 * `COPY_DESTINATION`, threaded into `FileBrowserSurface.kt`'s `fileBrowserCommitCopy`) are
 * [FileBrowserController] / `FileBrowserDispatch.kt` / `FileBrowserTransferChoice.kt`'s contract. This screen consumes it as published, confirmed against the real symbols
 * rather than a prose summary:
 * ```
 * FileBrowserTransferKind { MOVE, COPY }
 * controller.transferScope       : FocusScope                     // FileBrowserController.kt:334
 * controller.transferChooserOpen : Boolean
 * controller.transferChooser     : FileBrowserTransferChooser?    // FileBrowserTransferChooser(path, rows)
 * controller.handleTransferTap(key: FocusKey)
 * FileBrowserTransferFocus.optionKey(kind): FocusKey
 * FileBrowserTransferFocus.optionOf(key): FileBrowserTransferKind?
 * fileBrowserTransferOptions(): List<FileBrowserTransferOption>   // label + kind, no destination
 * ```
 * **[transferChoiceAvailable] is MANAGE-only**, unlike [extractChoiceAvailable] — its own KDoc on
 * [FileBrowserController] gives the reason (a move deletes its source, so it is gated like
 * [FileBrowserController.deleteAvailable] rather than like extraction). Nothing in this file re-decides
 * that; it only renders whatever [FileBrowserController.transferChooser] is non-null with, and that field
 * cannot be non-null outside MANAGE.
 *
 * ## Motion
 *
 * [FileBrowserTransferArrival] is a constant, on [FileBrowserExtractArrival]'s precedent (and the search
 * keyboard's before F4 made it a sheet): this subtree is composed only while
 * [FileBrowserController.transferChooser] is non-null, so its **mount is the event**. It fades and does
 * not travel — this lane cannot gate a ring it does not own the key for, and an entrance that never moves
 * geometry needs no gate. The same two arrivals [FileBrowserExtractScreen] discloses as OWED (closing the
 * chooser snaps; an abandoned pick spends a directory glide nothing plays) are inherited here rather than
 * re-raised — both need `fileBrowserScreenOf` in `FileBrowserSurfaceMotion.kt`, and remain deferred integration requirements.
 *
 * ## What a passing test in this module does NOT prove about this file
 *
 * Same disclosure as [FileBrowserExtractScreen]: `feature-files` configures no Compose harness — no
 * Robolectric, no `createComposeRule`. Nothing here can certify that a row reaches the screen, that the
 * ring lands where it is drawn, or that a hit area covers what it looks like it covers. The pure rules
 * are pinned and the call sites are pinned as source text; hardware is still the gate, and as of this
 * writing this screen is BUILT and unit-verified only — DEVICE-UNCERTIFIED.
 */
@Composable
internal fun FileBrowserTransferScreen(
    state: FileBrowserTransferChooser,
    controller: FileBrowserController,
    authority: FocusAuthority,
    focusedKey: FocusKey?,
    palette: MimirThemePalette,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = stringResource(FileBrowserTransferEyebrow),
            style = MimirTypeScale.RunicEyebrow,
            color = palette.textMuted,
            maxLines = 1,
        )
        Text(
            // Kept extension and all, [fileBrowserTransferSubjectLabel]'s own KDoc argues why — same
            // referent-collision reason [fileBrowserExtractArchiveLabel] gives for the archive heading,
            // even though slice 1's rows never print a folder name that could collide with it.
            text = fileBrowserTransferSubjectLabel(state.path),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 19.sp,
            color = palette.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = FileBrowserTransferRowWidthDp.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // Never empty, on the same contract [FileBrowserExtractScreen] states: `openTransferChooser`
            // refuses an empty row list outright (`FileBrowserController.kt`'s own guard, `rows.isEmpty()
            // -> return`), so "the chooser is open" and "there is something to press" stay one fact.
            //
            // V-4: `key(option.kind)`, [FileBrowserGhostFreeIdentity] applied, mirroring
            // [FileBrowserExtractScreen]'s own reasoning: this screen mounts fresh each time the chooser
            // opens and `state.rows` is fixed for the life of one open, so it could not ghost today either
            // — the wrapper is here so a later change to either exemption does not silently need it.
            state.rows.forEach { option ->
                val optionKey = FileBrowserTransferFocus.optionKey(option.kind)
                key(option.kind) {
                    FileBrowserTransferOptionRow(
                        option = option,
                        focused = optionKey == focusedKey,
                        key = optionKey,
                        controller = controller,
                        authority = authority,
                        palette = palette,
                    )
                }
            }
        }
    }
}

/**
 * One option row — `Move into…` or `Copy into…`, slice 1's whole vocabulary.
 *
 * **One line, not [FileBrowserExtractOptionRow]'s optional two.** [FileBrowserTransferOption] carries a
 * label and a kind and nothing else (the binding contract's own `// label + kind`) — there is no per-row
 * destination to show yet, because slice 1 has no smart default and both rows always open the same
 * destination-pick mode. Rendering a blank second line here would be the row telling the player it has
 * more to say than it does; the extract row's own comment makes the same call for its picker row's blank
 * detail, one line instead of a whole line's absence.
 *
 * **Both rows styled identically, deliberately** — [FileBrowserExtractOptionRow]'s own reasoning: making
 * `Move into…` louder than `Copy into…` would encode a recommendation neither this surface nor the engine has
 * grounds for, and spec §13 OWED-5 leaves the row order itself to the player rather than to which one this
 * treatment favours.
 *
 * `Modifier.mimirFocusable` + `detectTapGestures` + `.semantics { onClick }`, never `clickable` — the
 * design standing's §3b sanctioned three-part pattern, in full: `clickable` beside `mimirFocusable` on
 * one element is the defect the invariant gate cannot see from shipped bytecode, so it is refused here
 * too, and the semantics leg restores the accessibility click action `clickable` would have carried for
 * free rather than leaving it dropped. Focus stays compositor-cheap (invariant #7): only the fill colour
 * and the ring change.
 */
@Composable
private fun FileBrowserTransferOptionRow(
    option: FileBrowserTransferOption,
    focused: Boolean,
    key: FocusKey,
    controller: FileBrowserController,
    authority: FocusAuthority,
    palette: MimirThemePalette,
) {
    val optionLabel = stringResource(option.labelRes)
    Row(
        modifier = Modifier
            // widthIn OUTSIDE fillMaxWidth -- FileBrowserCommitRow's / FileBrowserExtractOptionRow's own
            // ordering: the cap has to narrow the incoming constraints before the fill expands into them.
            .widthIn(max = FileBrowserTransferRowWidthDp.dp)
            .fillMaxWidth()
            .heightIn(min = FileBrowserTransferRowHeightDp.dp)
            // The one ring (ring migration, 2026-09-28): a row ring on the rows' shared corner. The ring paints the
            // row's own grey lift so the family's wash lands over it. Keyed on the row's own focus key.
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(FileBrowserRowCorner),
                restingOutline = true,
                fill = SolidColor(if (focused) palette.surfaceHigh else palette.surfaceRaised),
            )
            .clip(RoundedCornerShape(FileBrowserRowCorner))
            // No second scroll owner to fight -- this screen has no LazyListState at all, mirroring
            // FileBrowserExtractOptionRow's own reasoning for the same `false`.
            .mimirFocusable(authority, key, controller.transferScope, autoBringIntoView = false)
            // Focus first, then the controller's own CONFIRM path -- handleTransferTap does both
            // (FileBrowserController.kt: `authority.requestFocus(key); dispatch(ControllerAction.CONFIRM)`),
            // on FileBrowserExtractOptionRow's precedent, so touch and the D-pad cannot disagree about
            // what activating a row means.
            .pointerInput(key) { detectTapGestures(onTap = { controller.handleTransferTap(key) }) }
            // The design standing's §3b THIRD leg -- found missing from this exact row by review, and
            // fixed here rather than left as the inherited gap the extract chooser's three shipped rows
            // still carry (that separate chooser remains unchanged here). mimirFocusable + detectTapGestures
            // restores the focus registration and the gesture a Compose click modifier would have given
            // for free, but NOT the accessibility click action that modifier also publishes --
            // `HomeLaunchCard.kt:1648-1653`'s own comment: the relevant node type hardcodes descendant
            // semantics merging on in shipped `foundation-android`, so that modifier merges a row's label
            // into one TalkBack node with a click action on it. Without this leg, TalkBack lands on a
            // label with no action -- an accessibility regression the invariant gate's own source-literal
            // check cannot catch, because it knows nothing about a missing `semantics` block. Same action
            // as the tap above, so TalkBack and touch cannot disagree about what activating a row does.
            .semantics(mergeDescendants = true) {
                onClick(label = optionLabel) {
                    controller.handleTransferTap(key)
                    true
                }
            }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            // The decision layer's own word, rendered rather than restated -- fileBrowserTransferOptions'
            // strings are "Move into…" and "Copy into…", and a paraphrase here would be a second place for them to
            // drift, exactly FileBrowserExtractOptionRow's own reasoning for option.label.
            text = optionLabel,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The screen's eyebrow is **`PLACE`**. One word covering both verbs, claiming neither — `EXTRACT` would be wrong on the row
 * that copies, and this screen never says "Move" or "Copy" until a row is read, on
 * [FileBrowserExtractEyebrow]'s own argument for why the heading does not re-answer what the rows already
 * answer each in their own words.
 */
@StringRes
internal val FileBrowserTransferEyebrow: Int = R.string.files_transfer_eyebrow

/**
 * The file this chooser is about, as the player reads it — **its own file name, extension included.**
 *
 * Mirrors [fileBrowserExtractArchiveLabel] exactly (deliberately not [fileBrowserDisplayName], which
 * strips the extension for a tile) rather than reusing that function by name: this screen's subject is a
 * plain FILE, not necessarily an archive, and a function named for "the archive" is the wrong word to call
 * on one even when the logic underneath is identical. Falls back to the whole path when there is no name
 * to take, so this can never render a blank heading over a live question.
 */
internal fun fileBrowserTransferSubjectLabel(path: String): String =
    path.substringAfterLast('/').ifBlank { path }

/**
 * The chooser's arrival — a constant, on [FileBrowserExtractArrival]'s own precedent and for the identical
 * reason: this screen's MOUNT is its arrival, so there is no history to track. [FileBrowserArrivalKind
 * .SCREEN], so it dips and does not travel — the rows' ring is painted from a `focusedKey` this screen
 * has no way to gate either.
 */
internal val FileBrowserTransferArrival: FileBrowserArrival =
    FileBrowserArrival(FileBrowserArrivalKind.SCREEN, epoch = 0L)

/**
 * How wide an option row is allowed to get — **identical to [FileBrowserExtractRowWidthDp], referenced
 * rather than re-derived.** Both choosers occupy the exact same content-column slot in
 * `FileBrowserSurface.kt` (the extract chooser, the transfer chooser, and the ordinary folio+inspector Row
 * are mutually exclusive occupants of one `Row` weighted region), so the 508 dp arithmetic
 * [FileBrowserExtractRowWidthDp]'s own KDoc derives applies unchanged. A `const val` referencing another
 * `const val` rather than a second `508f` literal is deliberate: spec §11 M6 asks for row-width/height
 * parity between the two choosers, and this makes the parity a compile-time fact rather than two numbers
 * that happen to agree today and could silently drift apart tomorrow.
 */
internal const val FileBrowserTransferRowWidthDp: Float = FileBrowserExtractRowWidthDp

/**
 * An option row's resting height — **identical to [FileBrowserExtractRowHeightDp], referenced rather than
 * re-derived**, for the same slot-identity reason [FileBrowserTransferRowWidthDp] gives. Also above
 * [com.gruning.mimir.rewrite.core.ui.MimirSpacing.minTarget] by the same construction, since it IS that
 * value. Spec §11 M6 names this exact parity as a device measurement still owed — this constant is why
 * that measurement is meaningful to take: today, by construction, the two choosers cannot disagree about
 * it.
 */
internal const val FileBrowserTransferRowHeightDp: Float = FileBrowserExtractRowHeightDp
