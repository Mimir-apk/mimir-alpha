package com.gruning.mimir.rewrite.feature.library

/**
 * CP01-b's layer ledger — **one opaque bed, one ramping veil**
 * .
 *
 * The Tome plate's rotation used a `Crossfade`, which fades BOTH children by construction. Two `src-over`
 * layers at complementary alpha do not sum to one image: total art weight is `e + (1−e)²`, bottoming at
 * 0.75 at mid-fade, and the missing quarter is the ground showing through — the book leaf under the plate
 * (measured mean art weight 0.775 on the Thor, 9 of 10 lossless mid-fade frames BELOW their own two
 * endpoints' luma envelope, which a real blend can never be). The paired 1080-class Home backdrop carries
 * the identical arithmetic on the identical 240 ms tick over a transparent stage, so it went translucent
 * to 75% mid-swap instead of dark.
 *
 * The corrected shape, which this ledger holds for both surfaces:
 *  - the **bed** is the last committed art and draws at **alpha 1, always** — the outgoing art has no
 *    out-fade because the model cannot express one;
 *  - the **veil** is the incoming art, drawn above the bed, ramping 0 → 1 on the surface's existing
 *    clock (no new duration, no new curve, no second clock);
 *  - the composite at every instant is `α·B + (1−α)·A` — art weight identically 1, ground weight 0;
 *  - on completion the caller [commit]s: bed := veil in ONE state write, visually a no-op because both
 *    slots hold the same identity on that frame.
 *
 * Interruption follows the interruption rule `AuroraBackdrop` already states in writing: only the latest renderable
 * is revealed — a retarget replaces the veil and the half-arrived art vanishes at the swap; the bed keeps
 * the last committed art, so something fully opaque owns the seat at every instant regardless of timing.
 *
 * Deliberately a dumb value type + two transitions, so the structure is pinnable from a JVM test — the
 * composite itself is a device fact (the spec's G1/G3 gates on the Thor, not these tests).
 */
internal data class HomeTomeBedVeil<T : Any>(
    val bed: T? = null,
    val veil: T? = null,
)

/**
 * The bed's draw alpha, named so "the bed draws fully opaque at every instant" is a pinned fact rather
 * than an inline literal two surfaces could drift on. Read inside `graphicsLayer` blocks only.
 */
internal val HomeTomeBedAlpha: () -> Float = { 1f }

/**
 * Applies an arriving decode-ready identity. Never changes an existing bed — the bed advances only via
 * [commit], which is what makes the outgoing art un-fadeable by construction.
 *
 * - First arrival lands directly in the bed: the plate's first appearance is a hard cut today and the
 *   spec deliberately leaves that moment alone (§10.4 — different moment, unmeasured, board item).
 * - An arrival matching the **bed** drops any half-arrived veil: the latest renderable already owns the
 *   seat at full opacity (Aurora interruption rule), and it keeps bed/veil identities distinct so the two
 *   `key()`ed sibling layers can never collide.
 * - An arrival matching the **veil** refreshes its payload without restarting the ramp.
 * - Anything else replaces the veil and leaves the bed alone (retarget continues from current alpha —
 *   the caller's `Animatable` is not reset by this transition).
 */
internal fun <T : Any> HomeTomeBedVeil<T>.arrive(
    incoming: T,
    identityOf: (T) -> Any,
): HomeTomeBedVeil<T> {
    val bedCurrent = bed ?: return HomeTomeBedVeil(bed = incoming)
    val incomingIdentity = identityOf(incoming)
    return when {
        incomingIdentity == identityOf(bedCurrent) ->
            if (veil == null && incoming == bedCurrent) this else HomeTomeBedVeil(bed = incoming)
        veil != null && incomingIdentity == identityOf(veil) ->
            if (incoming == veil) this else copy(veil = incoming)
        else -> copy(veil = incoming)
    }
}

/**
 * The end-of-ramp commit: bed := veil, veil := empty, in one returned value the caller writes as ONE
 * snapshot state write. On the commit frame bed and veil hold the same identity, so no pixel changes —
 * the hazard §3.2 names (swapping the bed first and clearing the veil later shows the bed alone for one
 * frame, a flicker exactly where the player is already looking) cannot be expressed through this ledger.
 */
internal fun <T : Any> HomeTomeBedVeil<T>.commit(): HomeTomeBedVeil<T> =
    veil?.let { HomeTomeBedVeil(bed = it) } ?: this

/**
 * The plate's ramp clock — the bed/veil form of the old `if (reducedMotion) snap() else tween(240)`.
 * Spec §5: under reduced motion the veil ramp has duration 0 and the commit is immediate, which with the
 * atomic [commit] makes reduced motion a clean one-frame cut instead of a one-frame double-draw. The
 * rotation loop's own early-return (`HomeTomeFace.kt`) stays the load-bearing suppression and is not
 * this function's concern. The paired backdrop does NOT call this — its clock is `presenceFadeMs`,
 * which already answers 0 for reduced motion and at-rest upstream (`homeTomeStageHandoffMs`); one
 * decision, threaded, never re-derived.
 */
internal fun homeTomeVeilRampMs(reducedMotion: Boolean): Int =
    if (reducedMotion) 0 else HomeTomePreviewFadeMs
