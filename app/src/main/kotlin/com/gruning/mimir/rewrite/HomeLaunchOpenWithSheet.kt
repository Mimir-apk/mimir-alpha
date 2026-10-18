package com.gruning.mimir.rewrite

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardOpenWithKey
import com.gruning.mimir.rewrite.feature.library.HomeLaunchCardScope
import com.gruning.mimir.rewrite.feature.library.OpenWithStandingSheet
import com.gruning.mimir.rewrite.feature.library.libraryCardSnapshotWanted

/*
 * OPEN WITH on Home's launch card takes the Library door's path.
 *
 * The Library built its sheet ahead of A, held the glass the same way, and closed without recomposing the sheet; Home mounted the sheet cold on the press frame inside the
 * shared direct overlay host, kept a second Back handler on it, and held its card as a snapshot through a pick's hold. What now stands on this door, each the Library's:
 *
 *  1. **Built ahead.** [MimirAppOverlayHost] keeps the sheet composed and measured, not placed, in the direct host's `standby` slot from a moment after the card has
 *     settled (or the pad is on the chip), until the card closes or another overlay takes the host. Not drawn, not hit-tested, not in the semantics tree, no row registered,
 *     no Back owned (`OpenWithOverlay`'s `active`).
 *  2. **One activation.** [HomeLaunchCardCoordinator.openWithOpen] is the one fact A changes (with the scope push beside it); the slot places the sheet when it is true.
 *  3. **One Back handler.** The sheet's own, enabled only while it is open. The second one this door added (`BackHandler(enabled = true)` on the overlay) sat under the
 *     sheet's own, which was always the later and so always the one that ran: it answered nothing, and it was live while the sheet only stood built.
 *  4. **A pick does not recompose the standing sheet in its exit** (`rememberOpenWithSheetOptions`): the optimistic write and the reload reach it one deadline after the pop.
 *  5. **The card is a snapshot under the picker by the Library's rule** ([homeLaunchCardSnapshotWanted]).
 *
 * Items 1, 2 and 4 are one composable shared with the Library's pieces (`OpenWithStandingSheet`); `HomeOpenWithPrewarmTest` holds this door to what the Library's holds.
 */

/**
 * Whether the card's OPEN WITH sheet is to be kept built ahead: an emulated card is up (not closing, not held for Settings), its options are loaded, and the top overlay is the
 * card or the sheet itself. Any other overlay over the card (the disc menu, the rename editor, the achievements) takes the host, drops the sheet and ends the wait; the sheet
 * is built again after the next settle, so nothing re-prewarms in the frame such an overlay closes in.
 */
internal fun homeLaunchOpenWithWanted(card: HomeLaunchCardCoordinator, active: MimirOverlayKind?): Boolean {
    val session = card.session ?: return false
    return session.emulated && !card.closing && !card.suspendedForSettings && card.openWithOptions != null &&
        (active == MimirOverlayKind.HOME_LAUNCH_CARD || active == MimirOverlayKind.HOME_OPEN_WITH)
}

/** The pad is on the card's OPEN WITH chip: read once per frame while the sheet waits to be built, never in composition. */
internal fun homeLaunchOpenWithChipFocused(authority: FocusAuthority): Boolean =
    authority.state.value.let { it.scope == HomeLaunchCardScope && it.focused == HomeLaunchCardOpenWithKey }

/**
 * The OPEN WITH sheet of Home's launch card, whether it is being shown or standing built (see the note at the top of this file). Handed [card] and [authority] and nothing else
 * of the app's bindings, which are a new object on every pass of the app coordinator: the host re-runs this call each time, and only what it is handed decides whether the
 * sheet below re-runs with it. The card's callbacks are bound once per card, so they are the same instances on every pass.
 */
@Composable
internal fun HomeLaunchOpenWithOverlay(card: HomeLaunchCardCoordinator, authority: FocusAuthority) {
    val latest = card.openWithOptions ?: return
    // Read where the sheet is placed and where its Back and its first row are decided, never in a body.
    val open = remember(card) { { card.openWithOpen } }
    val onChooseAuto = remember(card) { card::chooseAutomatic }
    val onChoose = remember(card) { card::choose }
    val onDismiss = remember(card) { card::dismissOpenWith }
    OpenWithStandingSheet(
        latest = latest,
        open = open,
        authority = authority,
        onChooseAuto = onChooseAuto,
        onChoose = onChoose,
        onDismiss = onDismiss,
    )
}

/**
 * Whether Home's launch card is held as a snapshot under its glass. For a picker (OPEN WITH, the disc / version menu) it is the Library's one rule
 * ([libraryCardSnapshotWanted]): the picker's REQUEST, not the held glass, so a committed pick thaws the card at once and the emulator row that changes behind the clearing
 * glass is seen; the card fully arrived; nothing leaving it. Home used `{ true }`, which kept the card frozen through the hold after a pick and cut the new label in on the
 * first frame of the clear. The rename editor ([titleEditGlass]: the glass in force is its own, the 34 dp one) keeps what it had.
 */
internal fun homeLaunchCardSnapshotWanted(
    titleEditGlass: Boolean,
    pickerRequested: Boolean,
    arrived: Boolean,
    leaving: Boolean,
): Boolean = titleEditGlass || libraryCardSnapshotWanted(pickerRequested, arrived, leaving)
