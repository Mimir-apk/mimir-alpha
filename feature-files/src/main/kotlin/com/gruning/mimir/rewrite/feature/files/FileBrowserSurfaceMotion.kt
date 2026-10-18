package com.gruning.mimir.rewrite.feature.files

import com.gruning.mimir.rewrite.core.ui.MimirMotion

/**
 * **V-1, 2026-08-14 — which arrival the browser is currently playing, and how it moves.**
 *
 * ## Why a model instead of a second animation
 *
 * [FileBrowserSurface] had exactly one transition — `fileBrowserNavigationGlide`, the earlier change's
 * destination-owned page glide for entering and leaving a folder. Every other way the browser changes what
 * it is showing (search, the queue, the keyboard, and the return out of each) snapped. Wiring those one at
 * a time would have produced a second curve, then a third, each with its own `remember` key and its own
 * opinion about when the focus ring may paint. This file is the single answer to *"what just arrived?"*;
 * `FileBrowserSurface` owns the one applicator that plays it.
 *
 * **Pure and Compose-free on purpose.** The whole precedence question — which of two simultaneous arrivals
 * wins — is decided here, by a class with no Compose in it, so it is asserted rather than trusted. That
 * matters because the collision is real and is reached by an ordinary press: see [FileBrowserArrivalHistory
 * .arrive]'s reveal case.
 *
 * ## The two arrival kinds, and why they move differently
 *
 * - **[FileBrowserArrivalKind.DIRECTORY]** — the listing changed under a header that did not. It travels
 *   ([FileBrowserGlideTravelDp], signed) *and* dips in alpha, exactly as the earlier change shipped it and exactly as
 *   `feature-library`'s `GamelistDestinationPageTransition` does. **Unchanged in every observable respect
 *   by this item**: same tokens, same numbers, same direction convention.
 * - **[FileBrowserArrivalKind.SCREEN]** — one screen replaced another in the content slot (browse ⇄ search
 *   ⇄ queue, in any direction) and **nothing is left underneath**. It **dips and does not travel**, from
 *   [FileBrowserGlideStartAlpha].
 *
 * (There used to be a third kind, `SCREEN_OVER`, for a layer over a screen that stays composed, and its one user was
 * the search keyboard. **F4, 2026-09-30:** the keyboard is a sheet now, on the sheet tier's
 * `MimirSheetHost` like the Library's, so it has no arrival in this file and the kind went with it.)
 *
 * **That asymmetry is a ring-safety decision, not a taste one, and it is the load-bearing sentence in this
 * file.** The ring-safety requirement is that *the focus ring must not paint before its geometry
 * has settled*. On the browse folio that rule is honoured by a gate ([fileBrowserRingEligible]) which
 * suppresses the ring until the travelling glide reports settled, and the surface can install it there
 * because it owns the call site that decides which key gets a ring. **It cannot install it on the search or
 * queue screens.** `FileBrowserSearchSurface.kt` and `FileBrowserQueueSurface.kt` both feed one
 * `focusedKey` parameter to two consumers at once — the settlement scroll that decides *where the list
 * lands* (`FileBrowserSearchSurface.kt:377`, `FileBrowserQueueSurface.kt:170`) and the ring painted on the
 * focused row — so gating their ring by narrowing that key would trade a clipped ring for an off-screen
 * landing, which is the trade `FileBrowserContent`'s own `ringKey` comment refuses. Splitting the parameter
 * needs an edit in those two files, and remains deferred.
 *
 * An arrival that **never moves geometry** needs no gate: the ring is painted against bounds that were
 * final on the first frame and merely fades up with everything around it. So the surfaces whose rings this
 * lane cannot gate are given the one entrance that cannot reproduce the defect. **This is a disclosed
 * downgrade, not a preference** — a travelling entrance would read stronger. The two-line `ringKey` split
 * those screens need remains an integration requirement; once it lands, [fileBrowserArrivalTravelDp] is the
 * single function that changes.
 *
 * **RETRACTED 2026-08-18, and the retraction is the point of this paragraph.** It used to argue that
 * alpha 0 for a screen swap was *"grounded, not picked"*, on the ground that `MimirAppHost` starts an
 * incoming layer at `0f` whenever there is an outgoing one to replace (`mimirIncomingLayerStartAlpha`).
 * The citation was right and the inference was wrong: **that value assumes an outgoing layer, and
 * `FileBrowserSurface`'s `if`/`else` chain holds none.** It swaps composables directly, so the outgoing
 * screen leaves composition in one frame and an alpha-0 start left the content slot literally empty for
 * two frames. That missing content produced the reported snap between the transfer screen and the warning.
 *
 * So a SWAP now dips from [FileBrowserGlideStartAlpha] for the folio's own stated reason (*"the listing is
 * arriving, not being born"*). The one case that did meet the app host's condition, the keyboard over the search
 * screen, is a sheet on `MimirSheetHost` now (F4). The old paragraph also claimed the dip *"would be invisible
 * without the travel that accompanies it"* — it is not, and that claim was never measured.
 *
 * ## What is deliberately NOT animated
 *
 * - **Departures.** Every arrival here is destination-owned: only the incoming tree moves, and the outgoing
 *   one is already gone. Fading a screen *out* means keeping it composed after its focus scope has been
 *   popped — a dead tree still taking pointer input, and for the search screen a second live focus graph
 *   over one list, which is half of a D-pad defect this browser already shipped once
 *   (`FileBrowserSurface.kt:186`). the earlier change made the same choice: backing out of a folder glides the
 *   *parent* in; the child does not glide out.
 *   **Since motion audit batch F (2026-09-28) the ascend is the exception, and it keeps the rule:** the
 *   child's last frame departs as RECORDED PIXELS through `MimirPageHandoff` (no tree, no input, no
 *   focus) ([fileBrowserArrivalDeparts]). **The parent stands at rest UNDER the clearing child**
 *   ([FileBrowserChildClearMs]), opaque from its first frame, and the child fades off the top of it: the pane is
 *   `a*child + (1-a)*parent`, never less than one whole page. (The 2026-09-29 version held the parent hidden
 *   until the child had cleared, to avoid blur from superimposed grids; the Thor drive of
 *   2026-10-15 measured what that costs: 513 ms and one near-empty frame, the dip the bar forbids. The earlier blur
 *   had two causes since fixed -- an unkeyed layer that re-recorded the parent as the "child", and a child
 *   with no ground of its own -- and the overlap that is left is a crossfade of two opaque pages. Whether it reads as
 *   a blur on the handheld is a device-check item.)
 * - **The inspector's own content swap** when the ring moves from tile to tile. That is movement being
 *   exercised, which [FileBrowserRingGate]'s rule explicitly protects from waiting; an inspector that
 *   animated per D-pad press would lag continuous travel. It fades with the page on a SCREEN arrival
 *   because it is part of the page, and does not move on a DIRECTORY one, which is the earlier change's behaviour
 *   preserved.
 * - **A first mount.** [FileBrowserController.open] nulls the motion for exactly this reason: there is no
 *   predecessor to have arrived from.
 * - **A refresh.** `FileBrowserController.refresh` reloads the same directory without bumping the sequence
 *   (contract A, after an extraction), so no arrival is produced and the ring does not blink off.
 */
/**
 * **V-4, 2026-08-14 — THE GHOST-FREE IDENTITY RULE.**
 *
 * This object records the invariant where the next person to add a list to this module will read
 * it, and [FileBrowserGhostFreeIdentityTest] exists so a new list cannot be added without classifying it.
 *
 * ## The rule
 *
 * **A composable that can paint a focus treatment must have a COMPOSITION identity derived from its
 * CONTENT — never from its position in a collection.**
 *
 * ## Why that is the property, and not "gate the ring better"
 *
 * Every focus treatment in this app is an `animate*AsState` on `MimirMotion.FocusMs`:
 * [com.gruning.mimir.rewrite.core.ui.mimirFocusRing] holds four (`MimirTokens.kt:380-404`) and
 * [com.gruning.mimir.rewrite.core.ui.MimirNorseBookCover] three (`MimirNorseBook.kt:231-245`). Those live
 * in `remember`, so they belong to a composition SLOT. Give a slot a positional identity and a listing
 * that changes wholesale — which is what every open and every return in this browser is — hands the
 * incoming content the outgoing content's in-flight focus animation. The new occupant then animates
 * *away from* a focus it never had: a gold ring fading out over 140 ms on an element the player never
 * selected, while the arrival glide translates the page underneath it. **That is the ghosting trail, and
 * it is structural.**
 *
 * **No arming rule can reach it.** [fileBrowserRingEligible] and [FileBrowserArrivalHistory] both decide
 * *when a ring may paint for the FOCUSED key*. The ghost is painted by a key that is not focused and never
 * was. Widening the arming fixed other cases but left this defect, because the gate was never being asked about the element that was ghosting.
 *
 * ## Two corollaries that are easy to get wrong
 *
 *  - **A positional key is not made safe by the slot "having no state today."** The rows of this folio
 *    hold no `remember` at all, and they are still content-keyed, because the next `remember` added to one
 *    of them would inherit the defect silently. State is added far more often than keys are audited.
 *  - **"Cannot ghost by construction" is a real answer and must name the construction.** A subtree that is
 *    freshly mounted per appearance (the extract chooser, the search keyboard) starts every
 *    `animate*AsState` AT its target and therefore cannot inherit anything. A collection with a fixed
 *    order and fixed length (the search filter chips, from `FileSearchFilter.entries`) can never reassign
 *    a slot. Both are exempt; both must say which one they are.
 *
 * ## What this rule does NOT do
 *
 * It does not make an arrival look right, and it is not a substitute for the gate — the gate is still what
 * keeps a ring from painting over geometry that is still moving. This rule removes the SECOND ring, the
 * one nobody asked for. And, like everything else in this module, it is pinned as a decision and as source
 * text: `feature-files` configures no Compose harness, so **whether the trail is gone is device-only.**
 */
internal object FileBrowserGhostFreeIdentity

internal enum class FileBrowserScreen {
    /** The tome/file folio plus the standing inspector. */
    BROWSE,

    /** F-08's search screen. **Replaces** the folio rather than overlaying it. */
    SEARCH,

    /** F-01's operation queue. Replaces the folio on the same reasoning. */
    QUEUE,

    /** R6-F-05 SEAM: not yet produced by [fileBrowserScreenOf] -- see that function's own KDoc. One
     *  value for all five of the extract chooser, the transfer chooser, the destination list, the
     *  disc-set prompt and the action overlay: [FileBrowserArrivalHistory]'s `screenArrived` only ever
     *  needs inequality against [FileBrowserArrivalHistory.presentedScreen], never which one, and the
     *  controller keeps the five mutually exclusive by construction (each screen's own call site in
     *  `FileBrowserSurface.kt` cites it). */
    OVERLAY,
}

/**
 * Which screen occupies the content slot.
 *
 * **`overlayOpen` is tested FIRST, and the order is copied from the surface's own branch rather than
 * re-argued here** (`FileBrowserSurface`'s `if (discSetPrompt != null) … else if (destinationList !=
 * null) … else if (extractChooser != null) … else if (transferChooser != null) … else if (actionOverlay
 * != null) … else if (controller.queueOpen) … else if (controller.searchOpen) …`). All six of these are
 * mutually exclusive by construction — each screen's own call site cites the invariant that keeps it so
 * — so this is not a tie-break; it is stated so that if one of those invariants is ever broken upstream,
 * the motion model and the render agree about which screen arrived instead of fading a page that is not
 * the one on screen.
 *
 * **R6-F-05 (spec §5).** The extract chooser, the transfer chooser, the destination list, the disc-set
 * prompt and the action overlay all render, and all five replace the folio's slot exactly as queue and
 * search do — but until this ticket, none of them were visible here. Closing one re-mounted
 * `FileBrowserContent` with the surface-level arrival unchanged: after a plain Enter or Up, the stale
 * DIRECTORY arrival replayed its glide and re-gated the ring (`fileBrowserRingEligible`); after a first
 * mount or a refresh, it was `NONE`, and the folio flashed at the top exactly as an unfixed search return
 * did (R6-P2-04). One value, [FileBrowserScreen.OVERLAY], stands for all five: [FileBrowserArrivalHistory]
 * only ever needs to know THAT the slot changed, never which of the five took it.
 */
internal fun fileBrowserScreenOf(
    queueOpen: Boolean,
    searchOpen: Boolean,
    overlayOpen: Boolean = false,
): FileBrowserScreen = when {
    overlayOpen -> FileBrowserScreen.OVERLAY
    queueOpen -> FileBrowserScreen.QUEUE
    searchOpen -> FileBrowserScreen.SEARCH
    else -> FileBrowserScreen.BROWSE
}

/** What kind of change the player is being shown. See this file's KDoc for why the two move differently. */
internal enum class FileBrowserArrivalKind {
    /** Nothing arrived: a first mount, a refresh, a re-composition, or a state change that alters no
     * surface. Renders as a completely idle layer — alpha 1, no travel, no animation started. */
    NONE,

    /** The listing changed beneath an unchanged header (enter, up, reveal, abandon-to-origin). */
    DIRECTORY,

    /**
     * **One screen replaced another in the content slot, and nothing is left underneath.**
     *
     * `FileBrowserSurface`'s chain swaps composables directly — no `AnimatedContent`, no held outgoing
     * layer — so the screen being left drops out of composition in a single frame. The incoming tree is
     * the only thing in the slot from that frame on, which is why it **dips** from
     * [FileBrowserGlideStartAlpha] rather than fading up from nothing: there is nothing to fade up over.
     */
    SCREEN,
}

/**
 * One arrival, as the surface's applicator consumes it.
 *
 * @param direction `+1` / `-1` for a [FileBrowserArrivalKind.DIRECTORY] arrival — the sign
 *   [FileBrowserNavigationMotion.direction] carries, which is what makes entering and returning read as
 *   opposite movements rather than as one symmetric fade. `0` for every other kind.
 * @param epoch a monotonic counter bumped once per real arrival. **Its only job is to be the identity two
 *   `remember` calls key on** — the animation's `Animatable` and the ring gate's settled-flag — for the
 *   reason [fileBrowserRingEpoch] states at length: two keys that can disagree produce either a ring
 *   suppressed with no animation left to release it, or an animation with no gate. Entering the same child
 *   twice is two arrivals and therefore two epochs, matching `armNavigationGlide`'s own bump-every-call
 *   rule.
 */
internal data class FileBrowserArrival(
    val kind: FileBrowserArrivalKind,
    val direction: Int = 0,
    val epoch: Long = 0L,
    val reveal: Boolean = false,
    /** A reload where the listing stands ([FileBrowserNavigationMotion.inPlace]): a dip, no departure, no travel. */
    val inPlace: Boolean = false,
)

/** The resting value: no arrival in flight. A shared constant so consecutive idle frames compare equal and
 * no `remember` keyed on an arrival is re-armed by a recomposition that changed nothing. */
internal val FileBrowserNoArrival = FileBrowserArrival(FileBrowserArrivalKind.NONE)

/** The gamelist page glide's own start alpha, `LibraryScreen.kt:158` (`LibraryGamelistPageStartAlpha`).
 * A dip, not a fade from nothing: the listing is arriving, not being born. Moved here from
 * `FileBrowserSurface.kt` unchanged, so the value that decides the animation can be asserted. */
internal const val FileBrowserGlideStartAlpha: Float = 0.88f

/** The page travel, `MimirMotion.GlideTravelDp` (the same 22 dp the gamelist's page glide uses). Signed by
 * [FileBrowserNavigationMotion.direction]. A token since motion audit batch F; the value is unchanged. */
internal val FileBrowserGlideTravelDp: Float = MimirMotion.GlideTravelDp.toFloat()

/**
 * Going UP, the child clears off the top of the parent (Nova reproduction, 2026-09-29). The departing child is the recorded page above a parent that stands at rest and
 * opaque from its first frame ([fileBrowserArrivalGlide] animates nothing for a return): this is the clock the child fades
 * 1 -> 0 on, `MimirMotion.Easing`, and with it the whole of the return's visible length, give or take a frame beat. The
 * parent no longer waits hidden for it and then dips in (Thor, 2026-10-15: 180 + 160 + the ring's 140 = 513 ms, with one
 * near-empty frame between the child's last and the parent's first).
 *
 * The overlay clock ([MimirMotion.OverlayMs]) on [MimirMotion.Easing] since the 2026-10-14 audit: the common clear (the 130 ms
 * hold is the host's, `mimirOutgoingChromeHoldMs`, and is not this file's -- nor on this path, which never leaves the Files surface), with no clock of
 * Files' own. It was previously 50 ms linear after a Nova input-delay reproduction on 2026-09-30; the overlay clock replaces that earlier timing. The press never waits for it (below), so
 * the length of the clear is a thing the player sees and never a thing the player waits on.
 *
 * **Presses do not wait for it.** Until then
 * they did: the ascent held the input gate while the parent was hidden , ~66 ms after 106. Going up
 * takes a press at once now, and the host hold applies only where no page is available to press yet. It can, because there is nothing to hold
 * back from: the child's last frame is recorded pixels with no tree, no input and no focus, and the parent's focus graph
 * (registered by [FileBrowserController]'s tracker in the call that goes up, not by the composition) is the only one, with
 * the ring on the folder the player came out of. A press lands on it that frame. Going INTO a folder was never held: it
 * keeps its ring gate, which waits for the glide unless focus has moved.
 */
internal const val FileBrowserChildClearMs: Int = MimirMotion.OverlayMs

/** How far the clearing child glides back the way it came: half the swap tier's short travel (4 dp), over [FileBrowserChildClearMs] -- about 22 dp/s. (It was
 * the same 4 dp over the earlier 50 ms clear, 80 dp/s; 22 dp in 90 ms, 244 dp/s, had read as a flick rather than a glide in the earlier version. The travel is unchanged and the clear is
 * longer, so the glide is slower than the previous device-observed version: a device-check item.) */
internal val FileBrowserChildClearTravelDp: Float = MimirMotion.SwapTravelDp / 2f

/** How far [arrival]'s incoming tree travels, in dp, signed. **Zero for a screen arrival, and that is the
 * ring-safety decision this file's KDoc explains** — not a placeholder. */
internal fun fileBrowserArrivalTravelDp(arrival: FileBrowserArrival?): Float = when (arrival?.kind) {
    // Batch F: going UP is a page return. The parent is at rest from its first frame; the child's last frame carries
    // the travel ([fileBrowserArrivalDeparts]). Only a descent still moves the arriving listing.
    FileBrowserArrivalKind.DIRECTORY ->
        if (fileBrowserArrivalDeparts(arrival) || arrival.inPlace) 0f else arrival.direction * FileBrowserGlideTravelDp
    // Still zero for a screen arrival. A travelling screen arrival is excluded: a moving entrance can clip the focus ring, and the dip fixes the reported
    // snap without touching geometry.
    FileBrowserArrivalKind.SCREEN,
    FileBrowserArrivalKind.NONE,
    null,
    -> 0f
}

/** The alpha [arrival]'s incoming tree starts at. `1f` for [FileBrowserArrivalKind.NONE], so an idle frame
 * is literally unchanged rather than merely equivalent. */
internal fun fileBrowserArrivalStartAlpha(arrival: FileBrowserArrival?): Float = when (arrival?.kind) {
    // Going up, the parent stands opaque from its first frame, under the child that is clearing ([FileBrowserChildClearMs]):
    // nothing dips, so the pane is never less than one whole page (Thor, 2026-10-15). A descent, and a reload where the
    // listing stands, dip in from here.
    FileBrowserArrivalKind.DIRECTORY -> if (fileBrowserArrivalDeparts(arrival)) 1f else FileBrowserGlideStartAlpha
    // A swap dips, exactly as a directory arrival does -- policy 2026-08-18. The slot is never
    // empty, because there is nothing underneath to be empty over.
    FileBrowserArrivalKind.SCREEN -> FileBrowserGlideStartAlpha
    FileBrowserArrivalKind.NONE, null -> 1f
}

/**
 * Whether [arrival] starts an animation at all. True for a RETURN too, though the parent itself animates nothing: it
 * stands at rest under the clearing child, `onSettled` releases the ring gate on the frame beat so the ring's own fade
 * finishes inside the clear, and the seat gate still hides the one mis-seated frame (under the child, which is whole then).
 */
internal fun fileBrowserArrivalAnimates(arrival: FileBrowserArrival?): Boolean =
    arrival != null && arrival.kind != FileBrowserArrivalKind.NONE

/**
 * Whether [arrival] hands the listing over as a page RETURN (motion audit batch F, 2026-09-28): going up a directory.
 * The child's last frame stays on screen as recorded pixels and glides back toward the side it arrived from
 * ([FileBrowserChildClearTravelDp]) as it clears ([FileBrowserChildClearMs]); the parent stands at rest and opaque under
 * it from the first frame. A descent keeps the destination-owned glide already observed on device.
 */
internal fun fileBrowserArrivalDeparts(arrival: FileBrowserArrival?): Boolean =
    arrival?.kind == FileBrowserArrivalKind.DIRECTORY && arrival.direction == FileBrowserGlideReturn && !arrival.inPlace

/** Whether [arrival]'s listing travels from its origin onto the focused row after it is seated --
 *  today only a search reveal (R6-P2-02). A plain Enter/Up/screen return seats and stops. */
internal fun fileBrowserArrivalTravels(arrival: FileBrowserArrival?): Boolean =
    arrival?.kind == FileBrowserArrivalKind.DIRECTORY && arrival.reveal

/**
 * The surface's memory of what it last showed, and the one place that decides what just arrived.
 *
 * **A plain class, not snapshot state.** Nothing here is snapshot-observable, so nothing here can be
 * written during composition and read back in the same frame.
 *
 * **Prepare in `remember`, commit in `SideEffect`** — [prepare] answers what *would* arrive and mutates
 * nothing the next answer depends on; only [commit] advances the memory. That split is
 * [com.gruning.mimir.rewrite.core.ui.MimirTransitionEpochLedger]'s, copied rather than re-invented, and
 * its KDoc states exactly why: *"A prepared frame may be abandoned; only commit changes the presented
 * target."* Compose may invoke a `remember` calculation and then throw the composition away — an
 * arrival recorded inside that block would be marked played for a frame that never reached the screen,
 * and the animation for it would never run. The first draft of this file did precisely that.
 *
 * **It must be remembered on the CONTROLLER, at [FileBrowserSurface] level — never inside
 * `FileBrowserContent`.** The content column is unmounted whenever search or the queue takes the slot, and
 * a history that died with it would forget that the directory motion it is holding has already been played.
 */
internal class FileBrowserArrivalHistory {
    private var seen = false
    private var presentedScreen: FileBrowserScreen? = null
    private var playedSequence: Long? = null
    private var issuedEpoch: Long = 0L

    /**
     * What would arrive if this frame reaches the screen. Repeatable: called twice with the same inputs
     * and no [commit] between, it answers the same kind and direction both times (a fresh epoch each
     * time, exactly as the reference ledger issues one — an abandoned identity costs nothing, since an
     * epoch's only job is to be unique).
     *
     * ## The precedence rule: a directory arrival outranks a screen arrival
     *
     * **Both can be true on the same frame, and the path that does it is an ordinary press.** F-08's
     * `FileBrowserController.revealPath` — CONFIRM on a search result — calls `closeSearch()` *and*
     * `armNavigationGlide(parent, Enter)` *and* `load(parent)` inside one synchronous dispatch, so the very
     * next composition sees the screen change from SEARCH to BROWSE **and** a brand-new motion sequence for
     * a brand-new path. `abandonDestinationPick` does the same shape when the pick had wandered off its
     * origin. Without a stated precedence the surface would play both: a fade from 0 on the outer slot
     * multiplied by a 22 dp travel on the listing inside it, two animations describing one event.
     *
     * The directory wins because it is the more specific truth — the player pressed something and *went
     * somewhere*, and the signed travel is what says which way. Losing does not mean the screen change is
     * unacknowledged: the same listing it would have faded is the one now gliding in.
     *
     * ## Consume-once, and the defect it closes
     *
     * `fileBrowserNavigationGlideApplies` is `motion?.path == currentPath`, and **that stays true forever
     * after a navigation** — the motion is not cleared when its glide finishes. Nothing was wrong with that
     * while the folio was the only screen, because the listing is never re-composed from scratch inside one
     * directory. It stopped being harmless the moment search and the queue began *replacing* the content:
     * closing either one re-mounts `FileBrowserContent`, its `remember` starts over, and the predicate
     * still says yes — so returning from search replayed the last *navigation's* glide, in whatever
     * direction that unrelated navigation happened to have, and did not animate at all for a player who
     * had never left the root. A transition that fires by accident, in a direction that means nothing, is
     * the same defect class as one that does not fire.
     *
     * Comparing the sequence against the last one seen makes each armed motion playable exactly once. The
     * predicate itself is untouched — it lives in `FileBrowserController.kt`, a separate source file, and
     * `FileBrowserRingGateTest` pins it directly.
     *
     * @param screen the screen currently occupying the content slot ([fileBrowserScreenOf]).
     * @param currentPath [FileBrowserController.currentPath] — the directory being shown.
     * @param motion [FileBrowserController.navigationMotion] — the armed glide, or null.
     */
    fun prepare(
        screen: FileBrowserScreen,
        currentPath: String,
        motion: FileBrowserNavigationMotion?,
    ): FileBrowserArrival {
        val sequence = motion?.sequence
        val directoryArrived = sequence != null &&
            sequence != playedSequence &&
            fileBrowserNavigationGlideApplies(currentPath, motion)
        // A first frame has no predecessor to have arrived from -- the same reason `open()` nulls the
        // motion. It is recorded, never animated.
        val screenArrived = seen && screen != presentedScreen

        return when {
            !seen -> FileBrowserNoArrival
            directoryArrived -> {
                issuedEpoch += 1
                FileBrowserArrival(
                    FileBrowserArrivalKind.DIRECTORY,
                    motion!!.direction,
                    issuedEpoch,
                    motion.reveal,
                    inPlace = motion.inPlace,
                )
            }
            screenArrived -> {
                issuedEpoch += 1
                FileBrowserArrival(FileBrowserArrivalKind.SCREEN, direction = 0, epoch = issuedEpoch)
            }
            else -> FileBrowserNoArrival
        }
    }

    /**
     * This frame reached the screen: remember it.
     *
     * **Idempotent**, because a `SideEffect` runs on every successful recomposition and will re-commit the
     * same arrival many times over one frame's lifetime.
     *
     * **A motion is marked played only when its arrival is actually the one being shown** — or on the very
     * first commit, where any motion in flight belongs to a session this surface did not witness and must
     * not animate for. Marking it unconditionally would let a motion armed for a directory that is not on
     * screen yet consume its own animation before the listing catches up.
     */
    fun commit(
        arrival: FileBrowserArrival,
        screen: FileBrowserScreen,
        motion: FileBrowserNavigationMotion?,
    ) {
        if (!seen || arrival.kind == FileBrowserArrivalKind.DIRECTORY) playedSequence = motion?.sequence
        seen = true
        presentedScreen = screen
    }
}

// The search keyboard has no arrival here any more. It is a sheet, on
// `MimirSheetHost` like the Library's: it slides in from the bottom edge and fades on the sheet tier's one clock, and it
// stays composed through its exit, inert. It used to be the one `SCREEN_OVER` arrival, a fade from nothing with no travel
// and then a cut out. The ring argument that kept it from travelling stands for the SCREENS above, whose rings this lane
// cannot gate. A keyboard may travel where they may not: the sheet tier's header (MimirSheetMotion.kt) records that its
// dense key rings stay inside the sheet's own fade layer.
