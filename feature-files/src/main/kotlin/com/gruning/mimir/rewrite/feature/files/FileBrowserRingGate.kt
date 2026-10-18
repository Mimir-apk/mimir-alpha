package com.gruning.mimir.rewrite.feature.files

import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver

/**
 * **When the focus ring may be painted.** After a directory swap, wait for the geometry to settle;
 * continuous D-pad movement remains immediate. A ring that always waits would lag behind D-pad travel.
 *
 * ## What this is NOT
 *
 * **The ring is not drawn from [com.gruning.mimir.rewrite.core.input.FocusAuthority] geometry, so this is
 * not the `virtualBounds ?: physicalBounds` shadowing defect.** Every ring on this surface is painted by
 * the focused element's OWN modifier chain against its OWN layout bounds -- `Modifier.mimirFocusRing` on
 * the commit row and the file tiles, `mimirFocusTileRing` inside `MimirNorseBookCover` for the tomes. The
 * authority's `Entry.bounds` is read only by `moveFocus` and by the removal fallback; neither paints
 * anything. Virtual-shadows-physical remains a real *navigation* hazard -- it is simply not this defect.
 *
 * ## What it is
 *
 * Focus flips **synchronously** the moment a command is dispatched, and the ring's own 140 ms animation
 * starts on that same frame. A directory swap, however, is not finished on that frame: the destination
 * glide ([fileBrowserNavigationGlideApplies]) runs for `MimirMotion.ModeTransitionMs` *after* a
 * `withFrameNanos {}` beat, translating and fading the whole listing. So the ring is painted, at full
 * strength, over a listing that is still arriving -- drawn against geometry the tile is about to leave.
 * The ring therefore appears stale.
 *
 * ## Why gating the SWAP is exactly "settle unless movement is exercised"
 *
 * This gate is armed by one thing only: a bump of [FileBrowserNavigationMotion.sequence], performed by
 * `FileBrowserController.armNavigationGlide` from a closed set of callers enumerated in that function's
 * own KDoc. Therefore:
 *
 *  - **A directional focus move never arms it.** Not by a heuristic that could misfire -- by construction,
 *    because nothing a D-pad press touches appears in [fileBrowserRingEpoch]. Continuous travel is
 *    structurally ungated, so the ring keeps up. That is the "unless movement is exercised" clause,
 *    honoured without having to *detect* movement at all.
 *  - **A refresh never arms it.** `FileBrowserController.refresh` reloads the same directory without
 *    bumping the sequence (contract A, after an extraction), so the epoch is unchanged and the ring stays
 *    lit through the reload rather than blinking off.
 *  - **A first mount never arms it.** `open()` nulls the motion, so [fileBrowserNavigationGlideApplies] is
 *    false and the ring is eligible from frame one. There is no previous listing to have arrived from and
 *    therefore nothing to wait for.
 *  - **Entering the same child twice arms it both times**, for the same reason `armNavigationGlide` bumps
 *    on every call rather than keying on the path.
 *  - **A navigation arms it** -- `Enter`, `Up`, F-08's `revealPath`, and the `Return` a wandered
 *    destination pick unwinds with. The original two cases, and the swap this gate was written for.
 *  - **O-7, 2026-08-14 -- the commit row appearing or disappearing under a STATIONARY player arms it.**
 *    The third case, and the first that is not a navigation at all. In `FileBrowserMode.MANAGE` the pinned
 *    commit row does not exist before a destination pick and does during it, so the reload inside
 *    `FileBrowserController.loadAfterPickChange` inserts a row at index 0 and shifts **every tile behind
 *    it down by a whole row** -- then lands focus on that brand-new row, whose `mimirFocusRing` paints at
 *    full strength on its first composed frame because `animateFloatAsState` starts *at* its target.
 *    Ending the pick runs the same change backwards, and the tiles shift up. That is this gate's own
 *    defect class on a listing nobody navigated, and it was reachable in this tree before the fix.
 *
 *    **It is armed rather than special-cased, and the difference is load-bearing.** `glideSettled` is
 *    re-created by the surface on the identity the ANIMATION is keyed on (see [fileBrowserRingEpoch]), so
 *    the gate and the animation arm together or not at all: *a gate armed with no animation behind it has
 *    nothing to release it*, and teaching this predicate to answer `false` for a structural change would
 *    have suppressed the ring for a window with no end. Giving the structural change the arrival it
 *    genuinely is arms both halves at once and releases through the glide's own completion. The rule that
 *    decides it -- a change in the commit row's EXISTENCE -- is still something no directional press can
 *    touch, so the clause above survives intact; and `FileBrowserMode.PICK`, where the row already exists
 *    and only its wording changes, deliberately does **not** arm.
 *
 * The gate is deliberately narrow: it suppresses PAINT for one bounded, self-cancelling window and touches
 * nothing else. It never gates dispatch, focus, or scroll -- `MimirMotion.InputDelayMs` is 0 and *"motion
 * never delays the input path"*.
 *
 * ## What a passing test here does and does not prove
 *
 * `feature-files` has **no Compose harness**, so nothing in this module can assert a rendered ring, a
 * clipped stroke, or the length of the 160 ms window. These tests pin the DECISION -- when the ring is
 * eligible, against which identity -- and nothing more. Whether the ring then looks right is device-only.
 */

/**
 * The identity of one "listing arrival", shared by the glide and by the ring gate.
 *
 * **Its whole job is to be the SAME key both of them are remembered against.** If the glide re-armed on a
 * key the gate did not, the ring would stay suppressed with no animation left to wait for; if the gate
 * re-armed on a key the glide did not, it would suppress the ring and never be released. Naming the pair
 * once and having both call sites use *this function* is what makes that class of desync unreachable,
 * rather than a comment asking two `remember` calls to stay in step.
 *
 * **Honest note on the two halves:** `sequence` alone would very nearly do, because every path change goes
 * through `armNavigationGlide` and every such call bumps it. `currentPath` is in here because the glide's
 * own applicability test ([fileBrowserNavigationGlideApplies]) compares paths, so an identity that ignored
 * the path could not express "this motion is for the directory on screen". It is not claimed to be
 * independently load-bearing -- it is claimed to match the predicate this gate is built on.
 */
internal fun fileBrowserRingEpoch(
    currentPath: String,
    motion: FileBrowserNavigationMotion?,
): Any = currentPath to motion?.sequence

/**
 * Whether the focus ring may be painted for the listing currently on screen.
 *
 * Pure and total: the two motion inputs decide whether a swap is in flight at all, and [glideSettled] is
 * the surface's record of whether the glide for *this* epoch has finished. Read as: **a ring is eligible
 * unless a directory glide is still arriving and the player has not moved since it began.**
 *
 * A Nova regression (2026-09-29) delayed visible feedback for movement during an arrival. Going up, the parent waits for the child to clear and then dips in,
 * and a move made during the dip landed with no ring until the dip had finished -- a press that seemed to do nothing.
 * Movement now reveals the ring during an arrival too: the first move shows the ring
 * where it landed. A player who waits still sees the ring once the arrival settles.
 *
 * @param currentPath the directory the browser is showing.
 * @param motion the armed glide, or null when none applies (a first mount).
 * @param glideSettled whether the glide for [fileBrowserRingEpoch]`(currentPath, motion)` has completed.
 *   The surface holds this in state re-created on that same epoch, so it is `true` from the outset for any
 *   arrival that does not glide.
 * @param movedDuringArrival whether an accepted D-pad move was pressed since this arrival began.
 */
internal fun fileBrowserRingEligible(
    currentPath: String,
    motion: FileBrowserNavigationMotion?,
    glideSettled: Boolean,
    movedDuringArrival: Boolean = false,
): Boolean = glideSettled || movedDuringArrival || !fileBrowserNavigationGlideApplies(currentPath, motion)

/**
 * What a folder or file tile reports of its ring: whether the cover is being handed `focused = true`, which is the flag it draws its ring from. A module-internal key of
 * its own, the way the coverflow reports `CoverflowEdgeAlpha`, and deliberately NOT Compose's `SemanticsProperties.Focused`: this surface's focus belongs to the single
 * `FocusAuthority`, and that vocabulary would tell an accessibility service the tile took input focus on every move. Audit 2026-10-14, RC-21: it is what lets a test say
 * "the ring is on this tile" without reading pixels the surface's recorded layers cannot give a software canvas.
 */
internal val FileBrowserRingLitSemantics = SemanticsPropertyKey<Boolean>("FileBrowserRingLit")
internal var SemanticsPropertyReceiver.fileBrowserRingLit by FileBrowserRingLitSemantics
