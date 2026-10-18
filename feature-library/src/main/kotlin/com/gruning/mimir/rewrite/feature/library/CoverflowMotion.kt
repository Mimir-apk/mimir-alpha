package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirHold
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.roundToInt

// Every coverflow item owns the same measured hero-sized slot. Resting items are reduced only with a
// graphics-layer scale, so a focus move never changes a slot's width or asks the row to remeasure.
internal const val CoverflowMotionRestWidthDp = 123
internal const val CoverflowMotionFocusWidthDp = 224
internal const val CoverflowMotionFocusHeightDp = 140
internal const val CoverflowMotionGapDp = 18
// the resting neighbour reads a notch larger
// (0.55 → 0.62 of the hero) so the ghost tiles flanking the centre hero have more presence — still clearly
// subordinate to it, still bottom-anchored on the shared ledge. NOT native-row-exclusive: PlatformHeroTile
// applies this (via [coverflowBasePose]'s `bookHero = false` branch) to BOTH NativeShelfCoverflow's row AND
// GamelistCarousel.kt's per-platform GameCard rest pose (its `presentationAlpha`/graphicsLayer `pose.scale`,
// both `bookHero = false`) — do not change this value for a native-only concern; see
// [NativeShelfCoverflowRestScale] below for the isolated, native-only lever this session's radius-2 fix uses
// instead, precisely to avoid silently re-tuning the gamelist's separately tuned (GL-RP5/GL-FR1/RCP-03)
// rest-tile geometry.
internal const val CoverflowMotionRestScale = 0.62f

// Emulation keeps its standing-book silhouette, but uses the same fixed-slot contract as native rows.
internal const val CoverflowMotionBookFocusWidthDp = 144
internal const val CoverflowMotionBookFocusHeightDp = 164
internal const val CoverflowMotionBookGapDp = 20

// RC-19 v2 replaces the earlier four-tile off-centre book row with a three-tile row.
// see [ShelfCoverflow]'s `CoverflowWindowRadius` (now 1, was 3) for the paint-
// count half of this change; these two constants are the SIZE half.
//
// [CoverflowMotionBookRestScale] was 0.58 (REST paints at 58% of [CoverflowMotionBookFocusWidthDp]) --
// the tuning [coverflowRowRecenterOffsetDp]'s own doc pinned at "144.2/40.2dp -> 92.2/92.2dp" and the RC-19
// v1 change did not alter, precisely because a width-PROPORTIONAL shrink would have made the Nova's tiles
// smaller still while Home went the opposite way (+36%, fewer/bigger seats,
// `325ce4fa`). Instead of a proportional shrink, the REST tile grows toward the FOCUSED tile's size.
// That is a separate lever from overall tile size.
//
// The shared relationship is: adopt Home's OWN hero/rest differentiation, not the size
// in dp (Home's tiles are width-solved from the viewport; this row's are not, and this ticket does not make
// them so). `HomeShelfGeometry.kt`'s `HomeShelfSelectedScale`
// (1.071 -- the hero is only 7% bigger than a resting seat; the rest of Home's focus cue is alpha + ring, the
// SAME mechanism this row already has via [CoverflowMotionRestAlpha] and `mimirFocusTileRing`) is `internal`,
// same module, and IS reused directly here rather than re-derived: `restScale = 1f / HomeShelfSelectedScale`
// (0.9337). This is the one number this ticket imports from Home; nothing else about Home's geometry (its
// width-solved `seatEnvelope`, gutter, gap, or seat count) is reusable AS A CALL without dragging in Home's
// own title/quick-rail/gutter concerns this row does not have.
//
// The resulting focus-to-rest ratio (the harness's own formula, `(focusWidthDp + 2*bloomOutset) *
// focusScale / (focusWidthDp * restScale)`) is NOT 1.071 -- it is ~1.256, because the harness's numerator
// carries the focused tile's bloom + focus-scale inflation (+20dp, x1.03) that Home's own heroSide/restingSide
// pair never had to account for; importing Home's PRE-bloom ratio does not reproduce Home's POST-bloom one.
// This is not an exact match.
//
// [CoverflowMotionBookRestWidthDp] is the row's per-slot SPACING term (`coverflowSlotOffsetDp`'s own
// `(focusWidthDp + restWidthDp) / 2 + gapDp`), kept at `round(focusWidthDp * restScale)` as it always has
// been (84 approximated 144*0.58=83.52 before) so spacing tracks the TRUE painted rest width instead of
// quietly under- or over-booking it (the exact defect RC-7 found and flagged, unaffected in shape by this
// change, still owed against [coverflowNeighborClearanceDp]).
//
// Size calibration, 2026-08-21. The earlier version shipped restScale = 1f/HomeShelfSelectedScale = 0.9337
// (rest 134.45dp, row fill 483dp) and offered two alternatives with their costs. Shown Home's own row fill
// on the Nova -- 564dp of 640 -- against 483dp built, 503dp at restScale 1.0, and 545dp for a literal match
// to Home's 168dp rest tile, the selected fill is 503dp.
//
// So restScale is 1.0: the REST tile paints at exactly [CoverflowMotionBookFocusWidthDp], the box
// PlatformHeroTile already measures it in. Nothing structural moves -- which is the whole reason this rung
// was reachable and the 545dp one was not. That option needs the tile to paint OUTSIDE its own 144dp box,
// a PlatformHeroTile change, and its row no longer fits the narrowest certified probe; it stays a separate
// ticket rather than a knob.
//
// Rest and focus differ now only by the focused tile's bloom and focus-scale, so the focus-to-rest ratio
// falls 2.0225 -> 1.1731 ((144+20)*1.03 / 144). The changed ratio follows from the size relationship: matching Home's tile size necessarily compresses it, because Home's own
// seats are near-uniform.
internal const val CoverflowMotionBookRestWidthDp = 144
internal const val CoverflowMotionBookRestScale = 1.0f

// coverflowBoundedWindowRadius (see its own
// doc) narrowed NativeShelfCoverflow's row from radius 3 to 1 on BOTH certified devices, because radius 2's
// TRUE painted edge — coverflowSlotOffsetDp(2)=332dp + focusWidthDp*CoverflowMotionRestScale/2 — sat at
// 401.44dp against the Odin's measured 396.25dp half-viewport at the shared 0.62 scale: over by 5.19dp.
// (The diagnosis that shipped with the radius fix said 401.94dp/5.69dp; the discrepancy is
// coverflowSlotOffsetDp's own `(focusWidthDp + restWidthDp) / 2` doing INTEGER division — 173, not 173.5 —
// which this comment corrects rather than repeats.) Radius 2 is restored on the Odin by
// shrinking the native row's rest tiles rather than accept one neighbour.
//
// This is a SEPARATE constant from [CoverflowMotionRestScale], not a replacement for it, because that one is
// shared with GamelistCarousel.kt's GameCard (also `bookHero = false`) — changing it would have silently
// re-shrunk gamelist rest tiles too, a separate surface with its own RP5 art-height floor for small resting tiles (GL-RP5). NativeShelfCoverflow.kt applies
// this value as an ADDITIONAL outer graphicsLayer multiplier on top of the shared 0.62 pose PlatformHeroTile
// already renders internally (`NativeShelfCoverflowRestScale / CoverflowMotionRestScale`, interpolated to 1×
// at full focus exactly like [coverflowBasePose] interpolates its own scale) — so gamelist, the book row, and
// the focused NATIVE hero itself (this factor is 1 there) are all unaffected; only native's RESTING
// neighbours end up smaller, converging to a TRUE combined rest scale of 0.56.
//
// 0.56 (not the razor-thin 0.573660714... breakeven) leaves the Odin's radius-2 edge at 394.72dp — a 1.53dp
// margin, not zero — and, as a side effect neither required nor targeted here, also surfaced a PRE-EXISTING
// defect: coverflowNeighborClearanceDp below approximates the resting neighbour's half-width as
// `restWidthDp / 2` (61.5dp) instead of the true `focusWidthDp * restScale / 2` this file now uses
// everywhere else. Recomputed with the TRUE half-width, the focused hero's bloom vs. its first native
// neighbour was actually at -3.8dp (an existing, undisclosed overlap) at the original 0.62 scale, not the
// +4.64dp that function reported; this fix's shrink happens to raise it to +2.92dp as a side effect, but
// coverflowNeighborClearanceDp itself is UNCHANGED and still returns the same wrong (approximate) number for
// any caller. Owed, not deferred: this is a found defect in this same file, queued for its own pass rather
// than folded into this radius-only diff.
//
// The NOVA does not move: closing its 101.44dp gap at radius 2 would need an EFFECTIVE rest scale below
// zero (even a fully invisible tile only recovers 69.44dp of it — the rest 32dp is pure inter-slot SPACING,
// `coverflowSlotOffsetDp(2)`'s own distance, which no rest-scale value can shrink). Reaching radius 2 on the
// Nova needs restWidthDp/gapDp cut roughly in half, which conflicts with keeping its small tiles
// legible. The constraints need reconciliation rather than composition; CoverflowMotionTest pins both.
internal const val NativeShelfCoverflowRestScale = 0.56f

internal const val CoverflowMotionFocusScale = 1.03f

/**
 * How solid the RESTING coverflow tiles flanking the hero paint.
 *
 * Was 0.48. Now `HomeShelfRestingAlpha` (0.82) — the value Home's own resting seats have always used, taken
 * rather than re-authored so the two shelves stop disagreeing about how present an unfocused tile is. At
 * 0.48 a neighbour was reading as a hint of a tile against whatever the scene painted behind it; the focus
 * cue is the ring, the scale and the bloom, and it does not need the neighbours to be half-erased to work.
 */
internal const val CoverflowMotionRestAlpha = 0.82f
internal const val CoverflowMotionBloomOutsetDp = 10
internal const val CoverflowMotionDurationMs = 140
internal const val CoverflowRingSeamFadeMs = 32
// Title acknowledgement uses the shared 140ms focus clock, matching tile emphasis and outgoing fade.
internal const val CoverflowTitleDurationMs = MimirMotion.FocusMs
internal const val CoverflowOutgoingDurationMs = 140
internal const val CoverflowOutgoingStartAlpha = 0.68f
internal const val CoverflowTitleStartAlpha = 0.72f

internal const val CoverflowOutgoingHeroTestTag = "library_shelf_outgoing_hero"
internal const val CoverflowDestinationTitleTestTag = "library_shelf_destination_title"
internal val CoverflowVisualPositionSemantics = SemanticsPropertyKey<Float>("CoverflowVisualPosition")
internal var SemanticsPropertyReceiver.coverflowVisualPosition by CoverflowVisualPositionSemantics
internal val CoverflowEdgeAlphaSemantics = SemanticsPropertyKey<Float>("CoverflowEdgeAlpha")
internal var SemanticsPropertyReceiver.coverflowEdgeAlpha by CoverflowEdgeAlphaSemantics
internal val CoverflowPresentationAlphaSemantics = SemanticsPropertyKey<Float>("CoverflowPresentationAlpha")
internal var SemanticsPropertyReceiver.coverflowPresentationAlpha by CoverflowPresentationAlphaSemantics

// Emulated and native shelves share these exact bands. Mode changes can swap content without moving the
// center seat, title baseline, hint lane, or future quick-rail lane.
internal const val CoverflowArtBandHeightDp = 190
internal const val CoverflowTitleBandHeightDp = 48
internal const val CoverflowHeroBandHeightDp =
    CoverflowArtBandHeightDp + CoverflowTitleBandHeightDp

// Shipping's cubic-bezier(0.25, 0.1, 0.25, 1) remains the house easing. Seat offsets still snap in the input
// frame; this curve is used only by compositor emphasis, outgoing fade, and title acknowledgement.
internal val CoverflowMotionEasing: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * BM02: SEAT positions retarget with
 * a critically-damped SPRING instead of a fresh 210ms tween. Tweens ignore initial velocity, so every
 * mid-burst retarget restarted the bezier's slow phase — an ~8Hz velocity ripple at the 120ms hold
 * cruise, a one-slot catch-up glide after release, and sluggish startup from rest (each smash restarted from zero velocity; a ramp built it gradually). The spring
 * preserves velocity across retargets: smash-from-rest accelerates continuously, holds cruise at
 * constant speed, and a SINGLE step settles in ≈210ms (stiffness 500, critically damped) — the same
 * duration the ratified tween had. FADES keep the house tween; only seat positions spring.
 */
internal val CoverflowSeatSpring: androidx.compose.animation.core.SpringSpec<Float> =
    androidx.compose.animation.core.spring(
        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
        stiffness = 500f,
        visibilityThreshold = 0.005f,
    )

/**
 * Nav-01:
 * a spring retargeted by discrete +1 seats DECELERATES into every integer seat then gets yanked to the
 * next — a ~74% velocity ripple that reads as a per-seat pulse during a rapid hold. A spring can't fix it
 * (softer smears the single-tap; stiffer pulses harder; a velocity-lead goes unstable). The validated fix
 * (simulation checks): during a sustained hold, cruise the centre at a FIXED velocity matched to the input repeat
 * rate, so the centre stays ~1 tile behind the (momentarily static) target and NEVER catches it → never
 * brakes → constant-velocity glide, zero dip. Only the final tile eases to a clean stop.
 *
 * These three values can be tuned on-device:
 * - Cruise velocity MUST match the hold cadence (1000 / InputTuning.directionalRepeatMs = 1000/120 ≈ 8.33
 *   tiles/s). Overshoot it and the centre catches the static target between steps → the dip returns.
 * - Ease rate governs the single-tap + release settle (higher = snappier stop).
 * - Ease threshold is the distance under which cruise hands off to the ease-to-stop.
 */
internal const val CoverflowGlideCruiseTilesPerSec = 8.33f
internal const val CoverflowGlideEaseRatePerSec = 18f
internal const val CoverflowGlideEaseThresholdTiles = 0.8f
private const val CoverflowGlideSnapEpsilon = 0.005f

/** How fast the speed a lift hands a glide falls back to the cruise, per second (a fling that coasts, then settles). */
private const val CoverflowReleaseDecayPerSec = 3.5f

/**
 * Hard bound on how far the painted centre may trail the focused index. The cruise above is matched to the
 * hold cadence, but if input EVER outruns it (a faster repeat rate, a device that reports differently, an
 * input burst) the gap would grow without limit: the hero and its focus ring sail off-screen and the title
 * names a game nowhere near the visible covers. Clamping the centre to `target ∓ this` drags it along at the
 * INPUT rate once the bound is hit — still constant-velocity, still no braking into seats, just faster than
 * the nominal cruise — so the focused card always stays on screen. Never engages during a matched hold.
 */
internal const val CoverflowGlideMaxTrailTiles = 1.5f

internal data class CoverflowBasePose(
    val scale: Float,
    val alpha: Float,
)

/**
 * Interpolates the visual role from a resting neighbour (0) to the centre hero (1). Logical focus may
 * commit immediately, but the painted role follows the same continuous position as the tile body so an
 * incoming item is never pre-rendered as a full hero while it is still sitting in a neighbour seat.
 */
internal fun coverflowBasePose(centerProgress: Float, bookHero: Boolean): CoverflowBasePose {
    val progress = centerProgress.coerceIn(0f, 1f)
    val restScale = if (bookHero) CoverflowMotionBookRestScale else CoverflowMotionRestScale
    return CoverflowBasePose(
        scale = restScale + (1f - restScale) * progress,
        alpha = CoverflowMotionRestAlpha + (1f - CoverflowMotionRestAlpha) * progress,
    )
}

internal fun coverflowBasePose(centered: Boolean, bookHero: Boolean): CoverflowBasePose =
    coverflowBasePose(centerProgress = if (centered) 1f else 0f, bookHero = bookHero)

/** The nearest cyclic representation of [focusedIndex] to a prior unwrapped logical target. */
internal fun nearestCoverflowRingTarget(
    referenceCenter: Float,
    focusedIndex: Int,
    itemCount: Int,
): Float {
    if (itemCount <= 0) return 0f
    val canonical = ((focusedIndex % itemCount) + itemCount) % itemCount
    val cycle = round((referenceCenter - canonical) / itemCount.toFloat())
    return canonical + cycle * itemCount
}

/**
 * Signed position of one identity around a cyclic visual centre. At the half-ring boundary the identity
 * switches between equal outer-edge representations; it never interpolates through centre to wrap sides.
 */
internal fun coverflowRingVisualPosition(
    itemIndex: Int,
    visualCenter: Float,
    itemCount: Int,
): Float {
    if (itemCount <= 0) return 0f
    var position = itemIndex - visualCenter
    val half = itemCount / 2f
    while (position > half) position -= itemCount
    while (position < -half) position += itemCount
    return position
}

/**
 * Alpha at the invisible wrap seam for an odd-sized ring. Odd rings cross the seam halfway between
 * two stable integer seats, so the wrapping identity can disappear at the outside edge without ever
 * being hidden at rest. Even rings need the stateful handoff in [CoverflowRingMotionState] because
 * their antipodal identity sits exactly on the seam at every settled stop.
 */
internal fun coverflowRingSeamAlpha(position: Float, itemCount: Int): Float {
    if (itemCount < 3) return 1f
    val halfRing = itemCount / 2f
    return ((halfRing - abs(position)) / 0.5f).coerceIn(0f, 1f)
}

internal fun coverflowOddRingSeamAlpha(position: Float, itemCount: Int): Float =
    if (itemCount % 2 == 0) 1f else coverflowRingSeamAlpha(position, itemCount)

/** Alpha role for the extra mounted viewport seat. The buffer is structural, never painted. */
internal fun coverflowViewportAlphaTarget(targetPosition: Float, paintedRadius: Int): Float {
    require(paintedRadius >= 0) { "paintedRadius must be >= 0" }
    return if (abs(targetPosition) > paintedRadius.toFloat()) 0f else 1f
}

/**
 * One unwrapped centre drives every mounted identity, so all bodies move in parallel and a 2-item
 * shelf cannot swap head-on. While an even ring moves, its two outer seam representations fade to zero;
 * this makes every antipodal rebase invisible in either direction, including multi-seat input bursts.
 * The settled antipode is restored after motion, so 2/4/6-item shelves never lose a tile at rest.
 */
@Stable
internal class CoverflowRingMotionState internal constructor(initialCenter: Float) {
    internal val center = Animatable(initialCenter)
    internal var targetCenter = initialCenter
    internal var evenSeamActive by mutableStateOf(false)
    internal var evenSeamPrepared by mutableStateOf(false)
    internal var evenSeamDeadlineNanos = 0L
    internal var centerMoving by mutableStateOf(false)

    /**
     * Touch (see CarouselTouch.kt). While [touchHeld] the finger owns the centre: it follows [touchCenter] one for one
     * and no glide runs. [touchEngaged] runs from the first move until the settle after the lift has finished, and is
     * what a row reads in composition to mount and fade around where it is PAINTED rather than where focus is.
     * [touchReleaseGeneration] re-keys the glide for a release that changes no focus; [releaseSpeedTilesPerSec] is the
     * speed the settle starts at, decaying to the cruise; [instantSettle] is reduced motion: the settle is a snap.
     */
    internal var touchHeld by mutableStateOf(false)
    internal var touchEngaged by mutableStateOf(false)
    internal var touchCenter by mutableStateOf(initialCenter)
    internal var touchReleaseGeneration by mutableIntStateOf(0)
    internal var touchFlush = false
    internal var releaseSpeedTilesPerSec = 0f
    internal var instantSettle = false

    fun visualPosition(itemIndex: Int, itemCount: Int): Float =
        coverflowRingVisualPosition(itemIndex, center.value, itemCount)

    fun edgeAlphaTarget(position: Float, itemCount: Int): Float {
        if (itemCount % 2 != 0) return coverflowOddRingSeamAlpha(position, itemCount)
        if (itemCount <= 2 || !evenSeamActive) return 1f
        return coverflowRingSeamAlpha(position, itemCount)
    }
}

/** Shared latest-wins motion for rings whose complete, ordered identity set remains mounted. */
@Composable
internal fun rememberCoverflowRingMotion(
    focusedIndex: Int,
    itemCount: Int,
    identityOrder: Any,
    skipEvenSeamPreparation: Boolean = false,
    motionEnabled: Boolean = true,
): CoverflowRingMotionState {
    val safeIndex = if (itemCount > 0) ((focusedIndex % itemCount) + itemCount) % itemCount else 0
    // A same-sized reorder is a different ring. Rebase at the selected durable identity instead of
    // animating from a stale numeric index that now belongs to another tile.
    val motion = remember(itemCount, identityOrder, skipEvenSeamPreparation, motionEnabled) {
        CoverflowRingMotionState(safeIndex.toFloat())
    }
    // Touch (CarouselTouch.kt): while a finger holds the ring the centre follows it one for one. The write is made
    // here, from a coroutine, like every other write to the centre; the gesture only states where it wants it.
    LaunchedEffect(motion) {
        snapshotFlow { if (motion.touchHeld) motion.touchCenter else Float.NaN }.collect { wanted ->
            if (!wanted.isNaN()) motion.center.snapTo(wanted)
        }
    }
    // [touchReleaseGeneration] re-keys a lift that lands on the item already focused, which changes no other key here.
    LaunchedEffect(safeIndex, itemCount, motion, skipEvenSeamPreparation, motionEnabled, motion.touchReleaseGeneration) {
        // The finger owns the centre while it is down; its lift moves focus (or bumps the generation) and re-keys this.
        if (motion.touchHeld) return@LaunchedEffect
        // The last place the finger asked for may not have reached the centre yet: it settles from where it was lifted.
        if (motion.touchFlush) {
            motion.touchFlush = false
            motion.center.snapTo(motion.touchCenter)
        }
        if (!motionEnabled || motion.instantSettle) {
            motion.instantSettle = false
            motion.releaseSpeedTilesPerSec = 0f
            motion.touchEngaged = false
            motion.center.snapTo(safeIndex.toFloat())
            motion.targetCenter = safeIndex.toFloat()
            motion.centerMoving = false
            motion.evenSeamActive = false
            motion.evenSeamPrepared = false
            motion.evenSeamDeadlineNanos = 0L
            return@LaunchedEffect
        }
        if (itemCount <= 0) {
            motion.center.snapTo(0f)
            return@LaunchedEffect
        }

        val target = when (itemCount) {
            // There is only one neighbour and no third identity to wrap. Keeping 0 <-> 1 canonical
            // makes both bodies translate in parallel and preserves the visible neighbour at rest.
            1, 2 -> safeIndex.toFloat()
            // Unwrap against the prior logical target, not the lagging painted frame. A rapid
            // 0→1→2→3→4 burst therefore remains 0→1→2→3→4 even if paint is still near zero.
            else -> nearestCoverflowRingTarget(motion.targetCenter, safeIndex, itemCount)
        }
        motion.targetCenter = target
        val delta = target - motion.center.value
        if (abs(delta) < 0.0001f) {
            motion.center.snapTo(safeIndex.toFloat())
            motion.targetCenter = safeIndex.toFloat()
            motion.centerMoving = false
            motion.touchEngaged = false
            // A reversal can cancel the even-ring preparation before paint ever moves. Restore the
            // settled antipode instead of leaving the seam mask active forever on this no-op target.
            motion.evenSeamActive = false
            motion.evenSeamPrepared = false
            motion.evenSeamDeadlineNanos = 0L
            return@LaunchedEffect
        }

        val usesEvenSeam = itemCount >= 4 && itemCount % 2 == 0
        if (usesEvenSeam) {
            if (!motion.evenSeamActive) {
                motion.evenSeamActive = true
                motion.evenSeamPrepared = false
                motion.evenSeamDeadlineNanos = 0L
            }
            // A destination-first surface cannot hold its old centre for 32 ms after title/detail commit.
            // Such callers start body motion immediately and let the far-edge seam mask apply in the same
            // paint epoch. The default remains available to any surface that intentionally owns a pre-roll.
            if (skipEvenSeamPreparation) {
                motion.evenSeamPrepared = true
                motion.evenSeamDeadlineNanos = 0L
            }
            // At rest the antipode is intentionally visible. Fade that edge before the first move;
            // the deadline survives LaunchedEffect cancellation, so accepted rapid inputs wait only
            // the remaining time instead of restarting this preparation indefinitely.
            if (!motion.evenSeamPrepared) {
                motion.centerMoving = false
                val nowNanos = withFrameNanos { it }
                if (motion.evenSeamDeadlineNanos == 0L) {
                    motion.evenSeamDeadlineNanos =
                        nowNanos + CoverflowRingSeamFadeMs * 1_000_000L
                }
                val remainingNanos = motion.evenSeamDeadlineNanos - nowNanos
                if (remainingNanos > 0L) {
                    delay((remainingNanos + 999_999L) / 1_000_000L)
                }
                motion.evenSeamPrepared = true
            }
        }

        motion.centerMoving = true
        // Nav-01 (see CoverflowGlide* above): constant-velocity glide instead of the BM02 spring. Each
        // accepted move cancels+restarts this LaunchedEffect; the glide resumes from the preserved
        // centre and continues toward the newer target, so a sustained hold cruises without braking into
        // intermediate seats, and a single tap / the last step of a burst eases to a clean stop.
        val glided = glideCoverflowCenter(
            center = motion.center,
            target = target,
            startSpeedTilesPerSec = motion.releaseSpeedTilesPerSec,
            abort = { motion.touchHeld },
        )
        motion.releaseSpeedTilesPerSec = 0f
        motion.centerMoving = false
        // A finger took the ring back mid-glide: it owns the centre again and the lift settles it.
        if (!glided) return@LaunchedEffect

        if (usesEvenSeam) {
            // Let the per-identity alpha animation reach zero at the final antipode. Canonicalization
            // can change only that identity's signed edge; it happens while the identity is invisible.
            //
            // A finger may take the ring at any point in this wait. Each wait is a point where this sequence could go on to
            // snap the centre to the item it was settling on and clear the engagement while the finger still holds the row,
            // so after every one a finger that has the ring ends the whole sequence: its lift settles the ring.
            withFrameNanos { }
            if (motion.touchHeld) return@LaunchedEffect
            // The fade it waits for is a tween, so the wait rides the animator scale too (alpha audit N3).
            mimirHold(CoverflowRingSeamFadeMs)
            if (motion.touchHeld) return@LaunchedEffect
            motion.center.snapTo(safeIndex.toFloat())
            motion.targetCenter = safeIndex.toFloat()
            withFrameNanos { }
            if (motion.touchHeld) return@LaunchedEffect
            motion.evenSeamActive = false
            motion.evenSeamPrepared = false
            motion.evenSeamDeadlineNanos = 0L
        } else {
            // Odd cyclic values paint identically because no identity rests at the half-ring seam.
            motion.center.snapTo(safeIndex.toFloat())
            motion.targetCenter = safeIndex.toFloat()
        }
        // Settled: after a touch, the row goes back to mounting and fading around focus.
        motion.touchEngaged = false
    }
    return motion
}

/**
 * Nav-01 slice 3 — the LINEAR counterpart to [CoverflowRingMotionState] for a WINDOWED carousel (the large
 * gamelist / >ring shelves that mount only ~2R+1 cards around focus and so cannot use a cyclic centre).
 * One centre lives in ABSOLUTE item-index space; a mounted card's continuous seat is simply
 * `itemIndex - center`. No wrap / even-seam: a windowed list never mounts its own antipode.
 */
@Stable
internal class CoverflowLinearMotionState internal constructor(initialCenter: Float) {
    internal val center = Animatable(initialCenter)

    /**
     * Latest logical destination. The driver reads this FRESH each frame instead of being cancelled and
     * restarted per move — a restart costs a frame before it steps again, and at one move per
     * `directionalRepeatMs` that systematic under-run made the centre fall further behind on every tile
     * (an unbounded, ever-growing trail).
     */
    internal var targetCenter by mutableStateOf(initialCenter)

    /**
     * The DISCRETE index the mounted window centres on — the rounded PAINTED centre, NOT the focused index.
     * A windowed carousel mounts only ~2R+1 cards; with a trailing centre, a focus-centred window slides off
     * the visible region and the screen empties out mid-hold. Centring the mount on the paint keeps whatever is visible mounted at ZERO extra
     * cost. It changes at most once per tile crossed, so composition recomposes no more often than the old
     * focus-keyed window did (PERF-D's per-frame-no-recompose contract is untouched).
     */
    internal var mountIndex by mutableStateOf(initialCenter.roundToInt())

    /**
     * Touch (see CarouselTouch.kt). While [touchHeld] the driver follows [touchCenter] one for one instead of gliding to
     * [targetCenter]. [touchEngaged] runs from the first move until the settle after the lift has finished: what a row
     * reads in composition to mount and fade around where it is PAINTED rather than where focus is. The lift sets
     * [targetCenter] to the item it settles on and [releaseSpeedTilesPerSec], the speed the glide starts at, decaying to
     * the cruise; [longGlide] lifts the trail bound for that glide (the bound is for held pad input, and a fling
     * is a deliberate long trail); [instantSettle] is reduced motion, a snap.
     */
    internal var touchHeld by mutableStateOf(false)
    internal var touchEngaged by mutableStateOf(false)
    internal var touchCenter by mutableStateOf(initialCenter)
    internal var instantSettle by mutableStateOf(false)
    internal var releaseSpeedTilesPerSec = 0f
    internal var longGlide = false

    /** Continuous signed seat of [itemIndex] around the gliding centre (absolute-index space). */
    fun visualPosition(itemIndex: Int): Float = itemIndex - center.value

    internal fun syncMountIndex() {
        val rounded = center.value.roundToInt()
        if (rounded != mountIndex) mountIndex = rounded
    }
}

/**
 * Glides ONE absolute-index centre toward [focusedIndex] with the SAME constant-velocity primitive the ring
 * uses ([glideCoverflowCenter]), so the windowed gamelist gets the ring's dead-flat cruise instead of the
 * per-card [CoverflowSeatSpring] that braked into every integer seat (Nav-01's felt "1-2-3" dip). At settle
 * `center == focusedIndex`, so each card's seat equals its old discrete slot position EXACTLY (parity with
 * the shipped stops); mid-hold the centre cruises at the input rate and never catches the target, so it
 * never brakes. A new identity set (platform switch / list rebuild) re-seats at the landing index rather
 * than gliding from a stale absolute index that now points at a different game.
 */
@Composable
internal fun rememberCoverflowLinearMotion(
    focusedIndex: Int,
    itemCount: Int,
    identityOrder: Any,
    motionEnabled: Boolean = true,
): CoverflowLinearMotionState {
    val safeIndex = if (itemCount > 0) focusedIndex.coerceIn(0, itemCount - 1) else 0
    val motion = remember(identityOrder, motionEnabled) {
        CoverflowLinearMotionState(safeIndex.toFloat())
    }
    // Retarget ONLY — this must never restart the driver below, or every move loses a frame of motion and
    // the trail grows without bound (disappearing-cards repro).
    LaunchedEffect(safeIndex, motion) { motion.targetCenter = safeIndex.toFloat() }

    LaunchedEffect(motion, motionEnabled, itemCount) {
        if (!motionEnabled || itemCount <= 0) {
            motion.center.snapTo(motion.targetCenter)
            motion.syncMountIndex()
            return@LaunchedEffect
        }
        driveCoverflowLinearGlide(motion)
    }
    return motion
}

/**
 * The long-lived linear-glide driver. Unlike the ring's per-retarget [glideCoverflowCenter], this loop is
 * started ONCE and reads [CoverflowLinearMotionState.targetCenter] fresh on every frame, so a mid-hold
 * retarget costs zero frames and the centre tracks the input rate instead of steadily falling behind it.
 * When settled it parks on a snapshot wait, so a resting list costs no frame callbacks.
 */
private suspend fun driveCoverflowLinearGlide(motion: CoverflowLinearMotionState) {
    var lastNanos = 0L
    while (true) {
        if (motion.touchHeld) {
            // A finger holds the centre: it goes where the finger asks, one write a frame, and nothing glides.
            motion.center.snapTo(motion.touchCenter)
            motion.syncMountIndex()
            withFrameNanos { }
            lastNanos = 0L
            continue
        }
        if (motion.instantSettle) {
            // Reduced motion: the lift settles on its item at once.
            motion.instantSettle = false
            motion.releaseSpeedTilesPerSec = 0f
            motion.longGlide = false
            motion.center.snapTo(motion.targetCenter)
            motion.syncMountIndex()
            motion.touchEngaged = false
            continue
        }
        if (abs(motion.targetCenter - motion.center.value) <= CoverflowGlideSnapEpsilon) {
            motion.center.snapTo(motion.targetCenter)
            motion.syncMountIndex()
            motion.releaseSpeedTilesPerSec = 0f
            motion.longGlide = false
            motion.touchEngaged = false
            // A touch that began and ended between two frames leaves the row engaged with nowhere to go: waking on that
            // lets this loop clear it above, instead of leaving the row painting around a finger that has gone.
            snapshotFlow {
                motion.touchHeld || motion.instantSettle || motion.touchEngaged ||
                    abs(motion.targetCenter - motion.center.value) > CoverflowGlideSnapEpsilon
            }.first { it }
            lastNanos = 0L
            continue
        }
        val now = withFrameNanos { it }
        if (lastNanos == 0L) {
            lastNanos = now
            continue
        }
        val dt = ((now - lastNanos).coerceAtLeast(0L)) / 1_000_000_000f
        lastNanos = now
        val target = motion.targetCenter
        val d = target - motion.center.value
        val absD = abs(d)
        if (absD <= CoverflowGlideSnapEpsilon) {
            motion.center.snapTo(target)
            motion.syncMountIndex()
            continue
        }
        // A lift hands the glide the speed the finger had; it decays to the cruise, so a fling coasts and then settles.
        val cruise = maxOf(CoverflowGlideCruiseTilesPerSec, motion.releaseSpeedTilesPerSec)
        motion.releaseSpeedTilesPerSec *= exp(-CoverflowReleaseDecayPerSec * dt)
        val step = if (absD <= CoverflowGlideEaseThresholdTiles) {
            d * CoverflowGlideEaseRatePerSec * dt
        } else {
            (if (d > 0f) 1f else -1f) * cruise * dt
        }
        val next = motion.center.value + step
        val clamped = if (d > 0f) next.coerceAtMost(target) else next.coerceAtLeast(target)
        // Bound the trail (see CoverflowGlideMaxTrailTiles): if input outran the cruise, ride the bound at
        // the input rate instead of falling ever further behind.
        val bounded = if (motion.longGlide) {
            clamped
        } else if (d > 0f) {
            clamped.coerceAtLeast(target - CoverflowGlideMaxTrailTiles)
        } else {
            clamped.coerceAtMost(target + CoverflowGlideMaxTrailTiles)
        }
        motion.center.snapTo(bounded)
        motion.syncMountIndex()
    }
}

/**
 * Nav-01 glide driver (see the CoverflowGlide* constants). Advances [center] one frame at a time toward
 * [target]: a constant-velocity cruise while more than [CoverflowGlideEaseThresholdTiles] away — during a
 * sustained hold the caller keeps retargeting to a farther seat before the centre closes the gap, so it
 * stays in the cruise branch and never brakes into an intermediate seat — then a proportional ease-to-stop
 * for the final tile (single tap, or the last step of a burst). Never overshoots [target]. When a new
 * accepted move retargets, the caller cancels+restarts this suspend fun; [center] keeps its current value,
 * so the next glide simply continues from there — that is the cross-retarget continuity that keeps a
 * hold smooth.
 */
private suspend fun glideCoverflowCenter(
    center: Animatable<Float, *>,
    target: Float,
    /** A lift hands the glide the speed the finger had (items per second); it decays to the cruise. */
    startSpeedTilesPerSec: Float = 0f,
    /** A finger that lands mid-glide takes the centre: the glide ends and returns false, writing nothing more. */
    abort: () -> Boolean = { false },
): Boolean {
    var lastNanos = withFrameNanos { it }
    var speed = startSpeedTilesPerSec
    while (true) {
        val now = withFrameNanos { it }
        if (abort()) return false
        val dt = ((now - lastNanos).coerceAtLeast(0L)) / 1_000_000_000f
        lastNanos = now
        val d = target - center.value
        val absD = abs(d)
        if (absD <= CoverflowGlideSnapEpsilon) {
            center.snapTo(target)
            return true
        }
        val cruise = maxOf(CoverflowGlideCruiseTilesPerSec, speed)
        speed *= exp(-CoverflowReleaseDecayPerSec * dt)
        val step = if (absD <= CoverflowGlideEaseThresholdTiles) {
            d * CoverflowGlideEaseRatePerSec * dt
        } else {
            (if (d > 0f) 1f else -1f) * cruise * dt
        }
        val next = center.value + step
        val clamped = if (d > 0f) next.coerceAtMost(target) else next.coerceAtLeast(target)
        center.snapTo(clamped)
    }
}

private data class CoverflowSelectedSnapshot<T>(
    val key: String,
    val item: T,
)

/**
 * Captures only the immediately previous selection. A new key replaces the prior handoff in the same
 * composition, so rapid reversals can never queue more than one outgoing center layer.
 */
@Composable
internal fun <T> rememberCoverflowOutgoing(
    currentKey: String,
    currentItem: T,
): T? {
    var previous by remember { mutableStateOf(CoverflowSelectedSnapshot(currentKey, currentItem)) }
    val outgoing = remember(currentKey) {
        previous.takeIf { it.key != currentKey }?.item
    }
    SideEffect {
        previous = CoverflowSelectedSnapshot(currentKey, currentItem)
    }
    return outgoing
}

internal fun coverflowSlotOffsetDp(
    position: Int,
    restWidthDp: Int = CoverflowMotionRestWidthDp,
    focusWidthDp: Int = CoverflowMotionFocusWidthDp,
    gapDp: Int = CoverflowMotionGapDp,
): Int = coverflowSlotOffsetDp(
    position = position.toFloat(),
    restWidthDp = restWidthDp,
    focusWidthDp = focusWidthDp,
    gapDp = gapDp,
).roundToInt()

/**
 * Continuous seat geometry used by every moving coverflow. Integer positions preserve the shipping
 * stops exactly; fractional positions interpolate through the centre without the old two-body overlap.
 */
internal fun coverflowSlotOffsetDp(
    position: Float,
    restWidthDp: Int = CoverflowMotionRestWidthDp,
    focusWidthDp: Int = CoverflowMotionFocusWidthDp,
    gapDp: Int = CoverflowMotionGapDp,
): Float {
    if (position == 0f) return 0f

    val absolutePosition = abs(position)
    // Preserve the established integer stop exactly (native: (224 + 123) / 2 + 18 == 191).
    val firstNeighborDistance = ((focusWidthDp + restWidthDp) / 2 + gapDp).toFloat()
    val distance = if (absolutePosition <= 1f) {
        firstNeighborDistance * absolutePosition
    } else {
        firstNeighborDistance + (absolutePosition - 1f) * (restWidthDp + gapDp)
    }
    return if (position < 0f) -distance else distance
}

/**
 * All-Float overload for the SEAT-SOLVED platform row . [solvePlatformRowPlan]'s seat/gap are
 * fractional dp (e.g. the Odin's 142.74dp) — rounding them to the Int-typed overload above would quantize
 * every sub-cap viewport by up to ~1dp for no reason. Native/gamelist callers are UNCHANGED and keep using
 * the Int overload above with the shared [CoverflowMotionRestWidthDp]/[CoverflowMotionFocusWidthDp] family.
 * Same shape, same integer-stop-preserving branch at `|position| <= 1`, so a caller that happens to pass a
 * whole-number-valued Float here reproduces the Int overload's own result to float precision.
 */
internal fun coverflowSlotOffsetDp(
    position: Float,
    restWidthDp: Float,
    focusWidthDp: Float,
    gapDp: Float,
): Float {
    if (position == 0f) return 0f

    val absolutePosition = abs(position)
    val firstNeighborDistance = (focusWidthDp + restWidthDp) / 2f + gapDp
    val distance = if (absolutePosition <= 1f) {
        firstNeighborDistance * absolutePosition
    } else {
        firstNeighborDistance + (absolutePosition - 1f) * (restWidthDp + gapDp)
    }
    return if (position < 0f) -distance else distance
}

/**
 * [coverflowSlotOffsetDp]'s slot geometry is
 * built from fixed dp constants that never reference the measured viewport, so a fixed opacity-visibility
 * radius (the shipped `CoverflowWindowRadius` / `NativeCoverflowWindowRadius`, both 3) painted a fully
 * opaque tile past the edge of any viewport narrower than the one it was implicitly tuned against — the
 * platform carousel's antipodal tile at a 6-item rest, and any native row tile at radius 3, both landed
 * outside the Nova's visible half-width while staying fully opaque.
 *
 * This walks outward from the centre using the SAME [coverflowSlotOffsetDp] geometry every tile is placed
 * with, and returns the largest radius (capped at [maxRadius], the shipped design radius) whose outer edge
 * still fits inside the MEASURED half-viewport. It can only ever return [maxRadius] or FEWER, never more: a
 * rule that cannot overflow at any width, rather than one tuned so one certified width happens to work.
 *
 * The outer edge uses `focusWidthDp * restScale` — NOT [restWidthDp] — as the tile's rendered width at rest.
 * [restWidthDp] is a separate, independently-tuned SPACING constant ([coverflowSlotOffsetDp]'s own per-slot
 * increment); [PlatformHeroTile] actually lays every tile out at the full `focusWidthDp` and paints the rest
 * state by scaling that box down via a graphics layer ([coverflowBasePose]'s `scale`, applied as
 * `artScale = basePose.scale * focusNudge`, and `focusNudge == 1` for a non-focused resting tile) — so the
 * TRUE painted half-width at rest is `focusWidthDp * restScale / 2`. For the book row [restWidthDp] is kept
 * deliberately close to this true value by construction (134 vs 144×0.9337≈134.5 today, RC-19 v2; was 84 vs
 * 144×0.58≈83.5 before it) — not a coincidence, see [CoverflowMotionBookRestWidthDp]'s own doc; at the native
 * row's ORIGINAL 0.62 scale it did not (123 vs 224×0.62≈139, an ~16dp gap) — using [restWidthDp] there under-
 * bounded the real paint by
 * enough to leave a 5.19dp overflow at radius 2 on the certified Odin (`coverflowSlotOffsetDp(2)`'s own
 * integer-division distance of 332dp + 224×0.62/2=69.44dp = 401.44dp, against the Odin's 396.25dp measured
 * half-viewport). The 2026-08-20 size change closed that specific gap on
 * the NATIVE row by having NativeShelfCoverflow.kt pass [NativeShelfCoverflowRestScale] (0.56, see its own
 * doc comment for the full arithmetic) here instead of the shared [CoverflowMotionRestScale] — this
 * function's bound is unchanged, it simply now receives a smaller `restScale` for that one caller.
 * Scaled by [tileScale] exactly as the tile itself is.
 */
internal fun coverflowBoundedWindowRadius(
    viewportWidthDp: Float,
    maxRadius: Int,
    restWidthDp: Int,
    focusWidthDp: Int,
    gapDp: Int,
    restScale: Float,
    tileScale: Float = 1f,
): Int {
    if (maxRadius <= 0 || viewportWidthDp <= 0f || tileScale <= 0f) return 0
    val halfViewportDp = viewportWidthDp / 2f
    val halfPaintedWidthDp = focusWidthDp * restScale / 2f
    var radius = 0
    while (radius < maxRadius) {
        val candidate = radius + 1
        val outerEdgeDp = (
            coverflowSlotOffsetDp(
                position = candidate.toFloat(),
                restWidthDp = restWidthDp,
                focusWidthDp = focusWidthDp,
                gapDp = gapDp,
            ) + halfPaintedWidthDp
            ) * tileScale
        if (outerEdgeDp > halfViewportDp) break
        radius = candidate
    }
    return radius
}

/**
 * All-Float overload for [solvePlatformRowPlan]'s seat/gap. Same shape and same guarantee as the Int
 * overload above (can only ever return [maxRadius] or fewer). [CoverflowGeometryEpsilonDp] absorbs pure
 * Float rounding noise at an EXACT boundary — [solvePlatformRowPlan] solves seat/gap so the painted edge
 * at `maxRadius` lands EXACTLY on the half-band by construction (the row is built to fill the band), so
 * without this tolerance a few ULPs of accumulated rounding can flip `outerEdgeDp > halfViewportDp` at that
 * exact boundary and silently clip a real, intended tile. Verified against all ten certified viewports
 * : the worst observed overshoot without
 * this tolerance was ~3e-5dp (CS720/CS411) — five orders of magnitude below one Android density-independent
 * pixel, so a 0.01dp tolerance costs nothing visually while making the guard's "never binds" promise safe
 * against Float, not just against the real numbers.
 */
internal fun coverflowBoundedWindowRadius(
    viewportWidthDp: Float,
    maxRadius: Int,
    restWidthDp: Float,
    focusWidthDp: Float,
    gapDp: Float,
    restScale: Float,
): Int {
    if (maxRadius <= 0 || viewportWidthDp <= 0f) return 0
    val halfViewportDp = viewportWidthDp / 2f
    val halfPaintedWidthDp = focusWidthDp * restScale / 2f
    var radius = 0
    while (radius < maxRadius) {
        val candidate = radius + 1
        val outerEdgeDp = coverflowSlotOffsetDp(
            position = candidate.toFloat(),
            restWidthDp = restWidthDp,
            focusWidthDp = focusWidthDp,
            gapDp = gapDp,
        ) + halfPaintedWidthDp
        if (outerEdgeDp > halfViewportDp + CoverflowGeometryEpsilonDp) break
        radius = candidate
    }
    return radius
}

/**
 * Float-noise guard for [coverflowBoundedWindowRadius]'s Float overload — see that function's own doc for
 * the measured worst case (~3e-5dp) this absorbs. Not a design/tuning constant.
 */
internal const val CoverflowGeometryEpsilonDp = 0.01f

/**
 * Painted-row centering correction.
 *
 * [coverflowBoundedWindowRadius] bounds how far the ring paints on either side, but [ShelfCoverflow] places
 * every tile's SLOT ZERO at the art band's own centre (`BoxWithConstraints(contentAlignment =
 * Alignment.Center)`) and offsets every OTHER tile from there via [coverflowSlotOffsetDp] alone. That is
 * fine while the ring paints the same radius on both sides, but a 4-item ring's own modulo wrap (see
 * [coverflowRingVisualPosition]'s doc, pinned in
 * `CoverflowMotionTest.ringVisualPositionTiesTheAntipodeToOneSideForEveryEvenItemCount`) ties the antipode
 * to ONE side once the painted radius reaches a full lap -- one more tile right than left (or vice-versa) --
 * so the FOCUSED tile is centred but the painted ROW is not: measured 145dp left / 40dp right on the Nova.
 *
 * This returns the single horizontal correction to add to every tile's existing [coverflowSlotOffsetDp]
 * offset that recentres the PAINTED extent -- the focused tile itself never moves; the whole row (focused
 * tile included) shifts by this same amount so the group's bounding box does. [leftRadius] / [rightRadius]
 * are the farthest position actually PAINTED on each side (the caller already computes this from the same
 * alpha gate [coverflowBoundedWindowRadius] and the ring feed -- this function re-derives nothing about
 * WHICH tiles paint, only where the group sits). A partial window -- radius strictly inside the ring, so it
 * never reaches the wrap seam -- already has `leftRadius == rightRadius` and returns exactly 0f: only a
 * full-lap wrap that lands off-centre is corrected.
 *
 * Reuses [coverflowSlotOffsetDp] for the distance term and the same TRUE painted rest half-width
 * [coverflowBoundedWindowRadius] already uses (`focusWidthDp * restScale / 2` -- not `restWidthDp / 2`, see
 * that function's own doc for why) -- no new geometry constant, no second solver. A side with NO painted
 * neighbour ([leftRadius] or [rightRadius] == 0, reachable at itemCount == 2) is bounded by the FOCUSED
 * tile's own edge instead, not by zero -- [coverflowFocusedVisualWidthDp]'s half, the same painted-hero
 * envelope [coverflowNeighborClearanceDp] uses for the identical reason (the focused tile's bloom scales
 * with it; a rest tile has none, so the two sides are genuinely NOT symmetric functions of radius alone).
 *

 * Provably never grows the row: both sides' corrected extent becomes the AVERAGE of the two original
 * extents, and the average of two values each already <= the measured half-viewport
 * ([coverflowBoundedWindowRadius]'s own guarantee for whichever radius it returned) is itself
 * <= that half-viewport.
 *
 * Deliberately viewport-independent: [coverflowSlotOffsetDp]'s per-step distance beyond the first neighbour
 * is the constant `restWidthDp + gapDp`, so for the one-tile-off-by-one split the ring wrap always produces,
 * the correction reduces to the SAME `(restWidthDp + gapDp) / 2` at every certified viewport, Odin included
 * -- 77dp for the book row's current 134+20 (was 52dp at the pre-RC-19-v2 84+20). The row's placement
 * corrects on every viewport where the split is asymmetric, per the acceptance criterion's own margin-
 * symmetry target (equal margins at every viewport).
 *
 * RC-19 v2 ([ShelfCoverflow]'s `CoverflowWindowRadius` capped
 * to 1): for the book/platform row specifically, [leftRadius] and [rightRadius] are now ALWAYS equal (1/1)
 * whenever `itemCount >= 3` -- a radius-1 window can only ever paint exactly one neighbour on each side, so
 * this function is a structural no-op there (`if (leftRadius == rightRadius) return 0f` fires every time).
 * It still earns its place at `itemCount == 2`, where one side has zero painted neighbours and the split
 * stays genuinely asymmetric (`extent(0)` vs `extent(1)`) -- this function, and the row-shift it drives in
 * [ShelfCoverflow], are UNCHANGED by RC-19 v2 for that reason; only the two size constants moved.
 */
internal fun coverflowRowRecenterOffsetDp(
    leftRadius: Int,
    rightRadius: Int,
    restWidthDp: Int,
    focusWidthDp: Int,
    gapDp: Int,
    restScale: Float,
): Float {
    if (leftRadius == rightRadius) return 0f
    val halfPaintedWidthDp = focusWidthDp * restScale / 2f
    val halfFocusedWidthDp = coverflowFocusedVisualWidthDp(focusWidthDp) / 2f
    fun extent(radius: Int): Float =
        if (radius <= 0) {
            // No painted neighbour on this side -- the nearest painted edge is the focused tile's OWN
            // (larger, bloom-scaled) edge, not zero.
            halfFocusedWidthDp
        } else {
            coverflowSlotOffsetDp(
                position = radius,
                restWidthDp = restWidthDp,
                focusWidthDp = focusWidthDp,
                gapDp = gapDp,
            ) + halfPaintedWidthDp
        }
    val leftExtentDp = extent(leftRadius)
    val rightExtentDp = extent(rightRadius)
    return (leftExtentDp - rightExtentDp) / 2f
}

// The platform row uses its natural ring and measured viewport bound. Earlier symmetric-radius
// capping painted only3 tiles for4 platforms; mirror-stop painting drew5, duplicating the antipode.
// Neither mechanism remains. Do not reintroduce coverflowSymmetricRingRadiusCap or
// coverflowRingMirrorStopIndex: they recreate those hidden/duplicated-tile shapes.
// ShelfCoverflow caps CoverflowWindowRadius at1, giving one neighbor per side without reaching
// the modulo-wrap seam that caused a1-vs-2 split. This changes maxRadius alone, not ring math.

/**
 * The gap between the focused hero's TRUE painted extent (bloom included) and its first resting neighbour's
 * TRUE painted extent -- an instrument only ([CoverflowMotionTest.kt] is its sole caller tree-wide, verified
 * before this fix; changes zero rendered pixels). Three corrected defects (RC-7,
 * found and flagged, not yet fixed, in `7f80b480`):
 *  1. **Centre distance** now reuses [coverflowSlotOffsetDp] itself instead of re-deriving it with Float
 *     division -- `(focusWidthDp + restWidthDp) / 2f` silently disagreed with the Int division
 *     [coverflowSlotOffsetDp] actually lays every tile out with (347/2 = 173, not 173.5, for the native row).
 *  2. **Resting neighbour's half-width** is now `focusWidthDp * restScale / 2` -- the TRUE painted half-width
 *     ([PlatformHeroTile] always measures at `focusWidthDp` and paints the rest pose via a graphicsLayer
 *     scale; `restWidthDp` is a separate, independently-tuned SPACING constant, see
 *     [coverflowBoundedWindowRadius]'s own doc comment) -- not `restWidthDp / 2`, which silently assumed the
 *     spacing constant and the painted width were the same thing.
 *  3. **Focused hero's half-width** now comes from [coverflowFocusedVisualWidthDp] -- the bloom Box is a
 *     CHILD of the `artScale` graphics layer (`PlatformHeroTile.kt`'s outer Box wraps the bloom Box, both
 *     inside the SAME `.graphicsLayer { scaleX = artScale; scaleY = artScale }` node), so its outset SCALES
 *     too. The old inline math (`focusWidthDp * FocusScale / 2f + BloomOutsetDp`) added the bloom outset
 *     AFTER scaling -- correct only for a bloom that is a SIBLING of the scale layer, which is not what this
 *     tree renders.
 *
 * Recomputed with all three corrections: native @ restScale 0.56 (the shipped [NativeShelfCoverflowRestScale])
 * = **+2.62dp**; book/platform row, at the ORIGINAL RC-7 restScale 0.58, was **+7.78dp**. RC-19 v2
 *  grew [CoverflowMotionBookRestScale] to 0.9337 and
 * [CoverflowMotionBookFocusWidthDp]'s neighbour spacing constant to 134 in step, which moves this to
 * **+7.31dp** -- still comfortably positive (no new overlap), reported here rather than left silently stale.
 * All three numbers are independently re-derived from the constants above, not transcribed from a commit
 * message.
 */
internal fun coverflowNeighborClearanceDp(
    restWidthDp: Int,
    focusWidthDp: Int,
    gapDp: Int,
    restScale: Float,
): Float {
    val centerDistance = coverflowSlotOffsetDp(
        position = 1,
        restWidthDp = restWidthDp,
        focusWidthDp = focusWidthDp,
        gapDp = gapDp,
    )
    val focusedHalfWidthDp = coverflowFocusedVisualWidthDp(focusWidthDp) / 2f
    val restHalfWidthDp = focusWidthDp * restScale / 2f
    return centerDistance - focusedHalfWidthDp - restHalfWidthDp
}

internal fun coverflowFocusedVisualHeightDp(focusHeightDp: Int): Float =
    (focusHeightDp + CoverflowMotionBloomOutsetDp * 2) * CoverflowMotionFocusScale

internal fun coverflowFocusedVisualWidthDp(focusWidthDp: Int): Float =
    (focusWidthDp + CoverflowMotionBloomOutsetDp * 2) * CoverflowMotionFocusScale

/** Float overload for the seat-solved platform row — see [coverflowSlotOffsetDp]'s Float overload doc. */
internal fun coverflowFocusedVisualHeightDp(focusHeightDp: Float): Float =
    (focusHeightDp + CoverflowMotionBloomOutsetDp * 2) * CoverflowMotionFocusScale

/** Float overload for the seat-solved platform row — see [coverflowSlotOffsetDp]'s Float overload doc. */
internal fun coverflowFocusedVisualWidthDp(focusWidthDp: Float): Float =
    (focusWidthDp + CoverflowMotionBloomOutsetDp * 2) * CoverflowMotionFocusScale

/** Float overload for the seat-solved platform row — see [coverflowSlotOffsetDp]'s Float overload doc. */
internal fun coverflowRowRecenterOffsetDp(
    leftRadius: Int,
    rightRadius: Int,
    restWidthDp: Float,
    focusWidthDp: Float,
    gapDp: Float,
    restScale: Float,
): Float {
    if (leftRadius == rightRadius) return 0f
    val halfPaintedWidthDp = focusWidthDp * restScale / 2f
    val halfFocusedWidthDp = coverflowFocusedVisualWidthDp(focusWidthDp) / 2f
    fun extent(radius: Int): Float =
        if (radius <= 0) {
            halfFocusedWidthDp
        } else {
            coverflowSlotOffsetDp(
                position = radius.toFloat(),
                restWidthDp = restWidthDp,
                focusWidthDp = focusWidthDp,
                gapDp = gapDp,
            ) + halfPaintedWidthDp
        }
    val leftExtentDp = extent(leftRadius)
    val rightExtentDp = extent(rightRadius)
    return (leftExtentDp - rightExtentDp) / 2f
}

// ── SHELF SEAT SOLVE  ─────────────────────────────
//
// A device-model cap underfilled the Odin platform row with three tiles where at least five fit:
// Seat count follows viewport size and aspect ratio rather than a device-model cap
//
// Seat COUNT is decided once, product-wide, by [mimirShelfSeatCount] (`HomeShelfGeometry.kt`) — the
// platform row simply INHERITS it (spec §3b, proven a theorem there, not a convenience: for every ratio in
// the legal window a coupled floor reduces to plain count inheritance). Seat SIZE then solves to FILL the
// band, capped at the configured 144dp tile — so a chosen count can never overflow the band and never clears it
// by a hair; the fit question is dissolved, not tolerated.

/**
 * ρ — the configured gap:seat pair, device-free. `CoverflowMotionBookGapDp` (20) and `CoverflowMotionBookFocusWidthDp`
 * (144) are today's shipped Nova numbers; this is their RATIO, reused rather than re-authored, so the
 * platform-row solve reproduces them bit-exactly whenever it hits the cap (`ρ * 144 == 20` — verified in
 * Float by `PlatformRowSeatSolveTest`, not merely trusted algebraically).
 */
internal val CoverflowBookGapToSeatRatio: Float =
    CoverflowMotionBookGapDp.toFloat() / CoverflowMotionBookFocusWidthDp.toFloat()

/**
 * The largest book-tile SEAT width whose scaled focused visual fits inside an [artBandHeightDp]-tall art
 * band, with bloom ([CoverflowMotionBloomOutsetDp], fixed/additive — NOT part of the seat scale) and focus
 * scale ([CoverflowMotionFocusScale]) both accounted for. Derived, not authored — recovers the configured
 * 144dp tile at the standard 190dp art band with an honest (not hit-exactly) margin:
 * `(190 / 1.03 - 2*10) * (144/164) = 144.41` `[D]`, so the AUTHORED `CoverflowMotionBookFocusWidthDp`
 * (144) sits safely inside this bound rather than defining it.
 *
 * This is the GENERALIZED form of that same derivation over the ACTUAL art band a viewport solves
 * (`solveLibraryShelfGeometry`'s `artBandHeightDp` — 190dp everywhere except the compact-square posture,
 * where two certified probes, CS360 and the 320x300 floor, solve a SHORTER art band: 139.62dp and 102.0dp
 * respectively). At those two rows the standard 144dp cap would let the focused tile's bloom-inflated
 * height overflow its own art band by up to ~24dp — a real, found defect this generalization closes
 * as a byproduct of implementing the solve correctly, not a separate detour.
 *
 * [solvePlatformRowPlan] caps at `min(CoverflowMotionBookFocusWidthDp, coverflowBookSeatCapDp(art))` — the
 * lesser of the AUTHORED ceiling (so the Nova/538dp/1080sq rows keep today's exact 144/20 whenever their
 * band-fill solve would otherwise want something larger) and this PHYSICAL ceiling (which only binds
 * tighter than 144 at the two short-art-band compact rows above).
 */
internal fun coverflowBookSeatCapDp(artBandHeightDp: Float): Float =
    (artBandHeightDp / CoverflowMotionFocusScale - CoverflowMotionBloomOutsetDp * 2) *
        (CoverflowMotionBookFocusWidthDp.toFloat() / CoverflowMotionBookFocusHeightDp.toFloat())

/** The platform row's solved plan: how many seats, how wide each one is, and the gap between them. */
internal data class PlatformRowPlan(
    val seatCount: Int,
    val seatDp: Float,
    val gapDp: Float,
)

/**
 * Solves the platform row's seat count and size from the measured band alone (spec §3b/§3c).
 *
 * [homeSeatCount] MUST be [mimirShelfSeatCount] evaluated at the FULL Library viewport width (the same
 * width `solveLibraryShelfGeometry` receives) — NOT this row's own narrower post-gutter [bandDp]. Home's
 * own seat count is calibrated against the full viewport (`HomeShelfGeometry.kt`'s own `widthDp`), and the
 * platform row's window radius is a THEOREM off that same count (spec §3b: `r = homeSeatCount - 2`), so
 * feeding it a different (narrower) width silently decouples the two and can flip the inherited count —
 * e.g. the RP5-class shelf: `env(4)` solves to 144.45dp at its 700dp viewport width (>= the 138dp floor,
 * 4 seats) but only 136.20dp at its 660dp post-gutter band width (< the floor, 3 seats) — the wrong answer.
 *
 * `seatCount = 2*(homeSeatCount-2)+1`: 3 seats (radius 1) when Home solves 3, 5 seats (radius 2) when Home
 * solves 4. Seat width then solves the closed form `n*seat + (n-1)*ρ*seat == bandDp` for an EXACT band
 * fill, capped at [coverflowBookSeatCapDp] (art-aware; reduces to the configured 144dp everywhere the art band
 * is the standard 190dp, per that function's own doc) — below the cap this is a straight algebraic solve,
 * at the cap `gap = ρ*seat` reproduces today's exact 20dp gap (`ρ*144 == 20`, verified in Float).
 *
 * The `rowWidth > bandDp` correction below is a pure defensive backstop — verified NOT to fire at any of
 * the ten certified viewports (`PlatformRowSeatSolveTest`) — for a viewport this solve has never been
 * checked against.
 */
internal fun solvePlatformRowPlan(
    bandDp: Float,
    homeSeatCount: Int,
    artBandHeightDp: Float,
    /**
     * The configured 144dp book, or null to let the seat fill the band up to the art band's physical limit. Compact
     * squares pass null (RG Rotate measurement, 2026-09-27): there the 144dp cap left the row
     * ~34dp short of each edge with the band free to hold 165dp books.
     */
    authoredSeatCapDp: Float? = CoverflowMotionBookFocusWidthDp.toFloat(),
): PlatformRowPlan {
    require(bandDp > 0f) { "bandDp must be positive" }
    require(homeSeatCount == HomeShelfCompactSeatCount || homeSeatCount == HomeShelfPrimarySeatCount) {
        "homeSeatCount must be mimirShelfSeatCount's own output (3 or 4), was $homeSeatCount"
    }
    // seat count = 2*(homeSeatCount-2)+1 (spec §3b's own formula, verbatim): the platform row's window
    // RADIUS is homeSeatCount-2 (1 or 2), and a radius-r window always paints 2r+1 seats.
    val seatCount = 2 * (homeSeatCount - 2) + 1
    val rho = CoverflowBookGapToSeatRatio
    val physicalCap = coverflowBookSeatCapDp(artBandHeightDp)
    val seatCap = authoredSeatCapDp?.let { minOf(it, physicalCap) } ?: physicalCap
    val rawSeat = bandDp / (seatCount * (1f + rho) - rho)
    var seat = minOf(rawSeat, seatCap)
    var gap = rho * seat
    val rowWidth = seatCount * seat + (seatCount - 1) * gap
    if (rowWidth > bandDp) {
        val correction = bandDp / rowWidth
        seat *= correction
        gap *= correction
    }
    return PlatformRowPlan(seatCount = seatCount, seatDp = seat, gapDp = gap)
}

/**
 * Spec §5 — when the whole platform ring already fits inside the solved window (`itemCount <= 2r+1`) AND
 * the count is EVEN, the ring's own modulo wrap ties its antipode to one side at every settled stop (see
 * [coverflowRingVisualPosition]'s own doc) — there is nothing left to slide, so [ShelfCoverflow] renders a
 * STATIC centred strip instead (importing `centeredHomeShelfWindow`'s static-strip grammar) rather than animate a
 * ring that can never re-centre itself. Odd full rings stay a sliding ring — they are symmetric at every
 * stop already. This is now ALSO where `itemCount == 2` lands (it is even and always fits), which retires
 * [coverflowRowRecenterOffsetDp]'s last live book-row case — that function stays for the ring branch's
 * runtime-observed radii, but itemCount == 2 no longer reaches it for this row.
 */
internal fun coverflowStaticStripEligible(itemCount: Int, windowRadius: Int): Boolean =
    itemCount <= 2 * windowRadius + 1 && itemCount % 2 == 0

/**
 * The static strip's per-stop position — the CENTROID of `0 until itemCount`, not an integer window anchor.
 * [ShelfCoverflow]'s art band is CENTRE-anchored (`BoxWithConstraints(contentAlignment = Alignment.Center)`,
 * slot-offset 0 = band centre), unlike Home's LEFT-anchored strip (`contentLeft + slotIndex * slotStride`) —
 * reusing Home's own integer anchor (`minOf(windowRadius, itemCount-1)`) in a centre-anchored container
 * shifts the whole group by up to half a stride (found while implementing this spec: 4 platforms at radius 2
 * shifts ~81dp left, 2 platforms leaves the right half of the band empty) — exactly the asymmetry spec §5
 * cites as already rejected. The centroid is symmetric BY CONSTRUCTION for any itemCount (sums to zero), and
 * costs nothing extra: `coverflowSlotOffsetDp`'s Float overload is exactly linear when `restWidthDp ==
 * focusWidthDp` (the book row's own uniform seat), so a fractional position (e.g. ±0.5, ±1.5 for
 * itemCount == 4) is not a special case.
 */
internal fun coverflowStaticStripPosition(stopIndex: Int, itemCount: Int): Float =
    stopIndex - (itemCount - 1) / 2f
