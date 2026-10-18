package com.gruning.mimir.rewrite.core.ui

import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The signature glass motion: the material ITSELF arriving.
 *
 * : blur, scrim, panel alpha and panel scale advance
 * together on ONE [MimirMotion.OverlayMs] curve. No stagger, no second clock.
 *
 * ## Why this file exists at all
 *
 * [MimirLiquidGlass] was 190 lines of pure material — blur radius, scrim alphas, gradient, sheen, border —
 * with **no animation of any kind**. Its only state-dependent line was
 * `if (active && supportsBlur) blur(...) else this`, an `if`, so the glass hard-cut in and hard-cut out.
 * There was no signature transition to reuse; there was a signature *look* with a cut. This is the motion
 * that material should have had.
 *
 * ## Return is the parent arriving, never the child leaving
 *
 * House rule, and it is not stylistic: fading a screen *out* means keeping it composed after its scope is
 * popped — a dead tree still taking pointer input. So a caller closing a glass surface runs this transition
 * on the **parent it is returning to**; it does not animate the panel away.
 *
 * ## The design is copied from the one transition in this tree that is already correct
 *
 * `fileBrowserArrivalGlide` (feature-files) is the reference: identity-keyed state, a frame beat before any
 * pixel moves, progress read only inside draw blocks, and a single clock. Every one of those is reproduced
 * here for the same reasons its KDoc gives.
 */
@Composable
fun rememberMimirGlassProgress(
    visible: Boolean,
    identity: Any? = Unit,
    /** The clearing clock; 0 clears on the next frame (no host asks for it since the file browser adopted the common close). Opening always ramps. */
    closeMs: Int = MimirMotion.OverlayMs,
    /**
     * Asked once, as an opening begins (after its frame beat): true makes THIS opening a settled state at its first frame
     * instead of a ramp. For a surface restored under an opaque cover (a launch return puts the player's card back before the
     * cover lifts), where a ramp would play out behind the cover's fade and read as the glass arriving late. Read inside the
     * effect, so it is the answer at the moment the glass opens, whatever has been written since. Closing is unaffected.
     */
    settleOpen: () -> Boolean = { false },
    /**
     * reduced motion is exempt from the no-cut standard, but the glass must be CONSISTENT with the panels it sits
     * behind. A host that collapses its tweens to 0 under reduced motion (`tween(if (reduced) 0 else ...)`, `snap()`) cuts its panel
     * in and out, and a glass that still ramped over [MimirMotion.OverlayMs] beside it is the one moving thing on the screen. True makes
     * the glass a settled STATE: the blur and the scrim are at their target on the first frame, in both directions, with no frame beat
     * (nothing animates, so there is no handle for a rebuilding subtree to discard). "Do not move", not "do not use the material": the
     * material carries the legibility. The default is false, so a caller opts in with the value its host already threads to its panels,
     * and no existing caller changes.
     */
    reducedMotion: Boolean = false,
): () -> Float {
    // Keyed on identity so re-opening a DIFFERENT surface cannot inherit the previous one's in-flight
    // progress -- the same guarantee FileBrowserArrival.epoch provides for directory arrivals.
    //
    // ALWAYS starts at 0, never at `if (visible) 1f else 0f`. That initialiser was a MOUNT-TIME HARD CUT
    // hiding in the primitive: the ordinary way to present a glass panel in this tree is to mount it when
    // it opens, so `visible` is ALREADY true at first composition. The Animatable would be born at 1f, the
    // LaunchedEffect would animate 1f -> 1f, and the surface would cut in exactly as it did before -- a
    // transition that compiles, runs, and plays nothing. That recreates the mount-time hard cut.
    // Starting at 0 makes "mounted and visible" the same event as "opened", which is what a caller means.
    //
    // The cost is that an identity change while a surface is already settled-open replays the ramp. Callers
    // that keep ONE always-composed scene (the two scene-side sites) pass a constant identity, so `remember`
    // never re-runs and the case cannot arise. Callers that mount per open WANT the replay -- it is the
    // open.
    val progress = remember(identity) { Animatable(0f) }
    LaunchedEffect(identity, visible, reducedMotion) {
        // Reduced motion is a settled state and animates nothing, so it takes no frame beat (there is no animation handle for a
        // rebuilding subtree to discard): the glass is at its target on the first frame the panel beside it is.
        if (reducedMotion) {
            progress.snapTo(if (visible) 1f else 0f)
            return@LaunchedEffect
        }
        // The frame beat, before any pixel moves. This tree's UI-01: a subtree still rebuilding discards
        // every in-flight animation handle, and each one restarts ALREADY AT ITS TARGET -- a hard cut
        // wearing an animation's name. fileBrowserArrivalGlide takes this same beat for this same reason.
        withFrameNanos { }
        if (!visible && closeMs <= 0) {
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        if (visible && progress.value < 1f && settleOpen()) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = tween(if (visible) MimirMotion.OverlayMs else closeMs, easing = MimirMotion.Easing),
        )
    }
    return { progress.value }
}

/**
 * Glass beneath a departing modal clears only after its dissolve, preventing a blink during launch-option switches. True while [requested], and for [holdMs] after it ends,
 * on the animation clock; the caller feeds this, not the raw request, to the glass that sits behind the modal
 * ([rememberMimirGlassProgress]'s `visible`, or a blur's target), so the modal's own dissolve plays out over a steady backdrop
 * and the backdrop clears only once the modal is gone. Released at once on a changed [cancelKey] (a transition cancellation), the
 * way the Library card's own chrome hold is.
 *
 * Without it the glass starts clearing on the frame the modal's request ends, so the surface behind un-blurs while the modal is
 * still half-visible: two detailed images part-way at once, the 08-15 midpoint "mush". [holdMs] defaults to
 * [MimirMotion.OverlayDissolveCoverMs], where a modal fading out on [MimirMotion.OverlayMs] is at alpha .044 and gone to the eye.
 */
@Composable
fun rememberMimirGlassHold(
    requested: Boolean,
    cancelKey: Any? = null,
    holdMs: Int = MimirMotion.OverlayDissolveCoverMs,
): Boolean {
    var presented by remember { mutableStateOf(requested) }
    var holding by remember { mutableStateOf(false) }
    // The key AS THE HOLD BEGAN. Only a key that moves AFTER that is a cancellation (the Library card's chrome hold keeps its
    // `detailChromeDismissStartEpoch` for the same reason): every Library press bumps the transition epoch BEFORE it is handled,
    // so the press that closes the picker moves the key in the very frame the request ends, and a hold keyed on "the key changed
    // while holding" was released by the press that began it (observed: the glass cleared at once, picker over sharp text).
    var holdStartKey by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(requested) {
        if (requested) {
            presented = true
            holding = false
        } else if (presented) {
            val startKey = cancelKey
            holdStartKey = startKey
            holding = true
            mimirHold(holdMs)
            if (holding && holdStartKey == startKey) {
                presented = false
                holding = false
            }
        }
    }
    LaunchedEffect(cancelKey) {
        if (holding && cancelKey != holdStartKey) {
            presented = false
            holding = false
        }
    }
    // `requested ||`: the request's own first frame is held too, not one frame late (the effect above runs after that composition).
    return requested || presented
}

/**
 * A glass PANEL's own arrival: its alpha and its
 * [MimirGlassMotion.PanelEnterScale] lift arrive over [MimirMotion.ShineMs] from the [MimirMotion.OverlayDissolveCoverMs]
 * lead, while the scene's blur and scrim beneath it keep [rememberMimirGlassProgress]'s [MimirMotion.OverlayMs] ramp.
 * Soften, then materialise: the glass-materialization sequence with the page tempo on the panel half. Same frame
 * beat and identity rule as [rememberMimirGlassProgress]; read it only inside draw blocks.
 *
 * Only a COLD arrival (progress still at 0) takes the lead; a panel re-shown mid-flight carries on from where it is,
 * the retarget rule. Closing is not this clock's business: the parent arrives. Under [reducedMotion] the panel is a
 * settled state from its first frame.
 */
@Composable
fun rememberMimirGlassPanelProgress(
    visible: Boolean,
    identity: Any? = Unit,
    reducedMotion: Boolean = false,
    /** False for a host on the Bottom: the gate is process-wide, so a Bottom panel that held it would mute Main's controller for the lead. Main keeps the default. */
    holdPad: Boolean = true,
): () -> Float {
    val progress = remember(identity) { Animatable(0f) }
    LaunchedEffect(identity, visible, reducedMotion) {
        // a panel arriving cold holds presses until it first paints; a re-show mid-flight is already seen.
        if (holdPad && visible && progress.value <= 0f) holdMimirInputUntilShown(progress) { progress.value > 0f }
        withFrameNanos { }
        val target = if (visible) 1f else 0f
        if (reducedMotion) {
            progress.snapTo(target)
            return@LaunchedEffect
        }
        progress.animateTo(
            targetValue = target,
            animationSpec = tween(
                durationMillis = MimirMotion.ShineMs,
                delayMillis = if (visible && progress.value <= 0f) MimirMotion.OverlayDissolveCoverMs else 0,
                easing = MimirMotion.Easing,
            ),
        )
    }
    return { progress.value }
}

/**
 * The dual of [rememberMimirGlassProgress] — a RETURN glass, for a surface that is already blurred the
 * moment it mounts and must clear rather than arrive.
 *
 * [rememberMimirGlassProgress] always starts at 0 because starting at its target would be a hard cut for
 * an ARRIVING panel — see that primitive's own kdoc. A return is that rule's dual, not an exception to it:
 * the destination is born already soft, under an opaque outgoing layer so there is nothing to cut, and
 * starting THIS one at 0 is what would cut, the instant [armed] goes true with no held frame of blur at
 * all. So this one starts at 1 (full [MimirGlass.backdropBlurRadius] once combined with
 * [mimirGlassBackdropProgress]) and animates DOWN to 0.
 *
 * [clearMillis] is how long the unwind itself takes, defaulting to [MimirMotion.OverlayMs]. A caller
 * gives it a value only to stop the clear outlasting the point where its own residual blur has stopped
 * being visible — the same argument [MimirMotion.OverlayDissolveCoverMs] makes for a cover, applied to
 * the blur rather than to the surface under it. The blur is one fixed radius ([MimirGlass.backdropBlurRadius]) at the strength (1 −
 * eased(t)), and the house ease is slow at the tail, so the last fifth of the nominal is spent under a few percent of strength — a
 * blur that [mimirGlassBackdropProgress] cross-fades into the sharp layer all but invisibly.
 *
 * [holdMillis] lets the destination stay pinned at full blur for exactly as long as the caller's own
 * OUTGOING layer takes to finish dissolving over it — the launch-card return's two strictly SEQUENTIAL
 * phases (dissolve, THEN clear), never a second clock running concurrently with the first. Running the
 * unwind concurrently with an outgoing dissolve was tried and refuted on device
 * (`mimirOutgoingChromeHoldMs`'s kdoc, app module): compositing a half-faded sharp layer over a
 * half-unblurred different one left neither interface legible at the transition's midpoint, so the sequence is dissolve, then clear. Defaults to 0 for a caller with no outgoing dissolve of its own to wait out.
 *
 * [armed] is a per-call decision, not a per-frame one — callers decide once, at the moment their own
 * transition begins, whether this occurrence counts as a return, and hold that decision for the whole
 * transition (typically via `remember(identity) { ... }` at the call site, mirroring [identity] itself).
 * Flipping [armed] from true to false mid-flight — a caller-side cancellation — snaps the progress to 0
 * immediately rather than leaving it parked mid-unwind: an interrupted return must never leave the
 * destination permanently blurred. This is the primitive's own terminal-state guard; callers do not need a
 * second one.
 *
 * Progress must be read only inside `graphicsLayer`/draw blocks, exactly as [rememberMimirGlassProgress]
 * requires — never at composition scope, where a per-frame value would force full recomposition instead of
 * cheap draw invalidation.
 */
@Composable
fun rememberMimirGlassReturnProgress(
    armed: Boolean,
    identity: Any? = Unit,
    holdMillis: Int = 0,
    clearMillis: Int = MimirMotion.OverlayMs,
): () -> Float {
    val progress = remember(identity) { Animatable(if (armed) 1f else 0f) }
    LaunchedEffect(identity, armed) {
        if (!armed) {
            // Never armed, or un-armed mid-flight by a caller-side cancellation: a return's blur may
            // never park between full and clear -- that is an unreadable terminal state, not a paused
            // one, and unlike rememberMimirGlassProgress's arrival case there is no "closed" visual for
            // a stuck blur to resemble. Snap closed immediately.
            progress.snapTo(0f)
            return@LaunchedEffect
        }
        // The frame beat, before any pixel moves -- same reason as rememberMimirGlassProgress's.
        withFrameNanos { }
        // Phase 1 (the caller's own outgoing dissolve) owns this window uncontested; phase 2 -- this
        // unwind -- starts only once it hands off. Not a second clock: strictly sequential with the first.
        // The hold is the tween's own delay, not a coroutine `delay()`: it scales with the animator setting like the
        // dissolve it covers, and cancels with the unwind.
        progress.animateTo(
            targetValue = 0f,
            animationSpec = tween(clearMillis, delayMillis = holdMillis.coerceAtLeast(0), easing = MimirMotion.Easing),
        )
    }
    return { progress.value }
}

internal data class MimirGlassBlurPlan(val radiusPx: Float, val blurAlpha: Float)

/**
 * The glass at [progress]: ONE blur radius, the peak, and a strength that rides the progress.
 *
 * The blur softens by cross-fading rather than sweeping its radius; 120 Hz Odin captures showed
 * a blink during launch-card option switches. The plan formerly ramped the downsampled minimum radius
 * ([MimirGlass.BlurDownsampledMinRadiusPx], 7 px) up to the peak at full alpha. A Gaussian of 7 px already erases a card's type, and the
 * house ease is front-loaded (15 % of the progress inside 22 ms), so the whole visible change, sharp to featureless, happened in
 * three frames and the other 150 ms of the ramp swept a smear that only got smearier; the clear mirrored it, still a smear for 90 ms
 * and then sharp in one frame. Frame measurements: sharpness 45 to 6 in 3 frames opening, 6.7 to 21.1 in ONE frame closing.
 * A cross-fade of the sharp layer into one fixed-radius blurred copy changes what the eye reads in proportion to the progress, so
 * the soften takes the whole clock in both directions and no frame is a step.
 *
 * It is also the cheaper draw. HWUI convolves a small radius at full resolution (the 2026-09-10 Bottom framestats, 20 to 45 ms a frame)
 * and downsamples a large one; this holds the large one for the whole ramp, so the effect is built once and only the layer's alpha
 * moves.
 */
internal fun mimirGlassBlurPlan(progress: Float, maxRadiusPx: Float): MimirGlassBlurPlan {
    val p = progress.coerceIn(0f, 1f)
    // Below ~0.1 px of the peak's reach nothing would show: a BlurEffect is then an expensive no-op, and the sharp layer alone is drawn.
    if (maxRadiusPx * p <= 0.1f) return MimirGlassBlurPlan(0f, 0f)
    return MimirGlassBlurPlan(radiusPx = maxRadiusPx, blurAlpha = p)
}

/**
 * What one glass frame draws: the sharp layer at [sharp] and the blurred copy at [blur]. While both show ([crossfade]) they are
 * SUMMED inside one offscreen group ([sharp] + [blur] = 1) and the group is composited once over whatever is behind, so the frame is
 * (1 − p)·sharp + p·blur there too: a translucent surface (the launch cards carry a 0.52 scrim) fades, and does not stack two scrims.
 */
internal data class MimirGlassDrawWeights(val sharp: Float, val blur: Float) {
    /** Both layers are up, so the frame needs the group. At rest (0 or 1) it is one plain layer and no group. */
    val crossfade: Boolean get() = sharp > 0f && blur > 0f
}

/**
 * Per-frame draw weights for [plan]. On Thor, OPEN WITH option switches blinked because the sharp
 * layer stayed at full weight beneath the blur until the peak, then left in one frame:
 * over the launch cards' translucent scrim the type showed through the whole ramp and two scrims stacked (about 0.77 cover instead of
 * 0.52), and at p = 1 the text vanished and one scrim went in a single frame, a hard cut to frost with a +10 luma flash; the close was
 * its mirror. Now the sharp weight is 1 − p and the blur weight is p on every frame, so the eye reads a change in proportion to the
 * progress from the first frame to the last, in both directions. The 0.1 px cut-off is [mimirGlassBlurPlan]'s: below it the blur weight
 * is 0 and the sharp layer alone is drawn.
 */
internal fun mimirGlassDrawWeights(plan: MimirGlassBlurPlan): MimirGlassDrawWeights {
    val p = plan.blurAlpha.coerceIn(0f, 1f)
    return MimirGlassDrawWeights(sharp = 1f - p, blur = p)
}

/**
 * The three paints of the crossfade group, built once with the draw cache: the group's own (SrcOver at 1, the one composite with what
 * is behind), the sharp layer's weight (the only one that moves, per frame), and the additive copy (Plus at 1). The 1s are explicit:
 * the group and the sum never scale what they carry.
 */
internal class MimirGlassCrossfadePaints {
    val group: Paint = Paint().apply { alpha = 1f }
    val sharpWeight: Paint = Paint()
    val blurSum: Paint = Paint().apply {
        blendMode = BlendMode.Plus
        alpha = 1f
    }
}

/**
 * The crossfade group as one plan, which the draw executes and a test records ([MimirGlassCrossfadeDrawTest]): inside one layer
 * ([MimirGlassCrossfadePaints.group], composited SrcOver once), the sharp layer through its own weight layer at
 * [MimirGlassDrawWeights.sharp], then the blurred copy (its layer alpha already [MimirGlassDrawWeights.blur]) through the additive layer,
 * so the two are summed and neither shows through the other. Inline: the draw passes its canvas and layers as lambdas and allocates
 * nothing for it per frame.
 */
internal inline fun mimirGlassCrossfadeDraw(
    weights: MimirGlassDrawWeights,
    paints: MimirGlassCrossfadePaints,
    saveLayer: (Paint) -> Unit,
    drawSharp: () -> Unit,
    drawBlurred: () -> Unit,
    restore: () -> Unit,
) {
    paints.sharpWeight.alpha = weights.sharp
    saveLayer(paints.group)
    saveLayer(paints.sharpWeight)
    drawSharp()
    restore()
    saveLayer(paints.blurSum)
    drawBlurred()
    restore()
    restore()
}

/**
 * Scene-side half: the blur ramps 0 -> [MimirGlass.backdropBlurRadius] on the shared curve.
 *
 * **Deliberately NOT `Modifier.blur(radius)`.** That takes a value, so an animating radius is read at
 * modifier-construction time and recomposes this subtree every frame — at 120 Hz, twice as often as this
 * design was ever measured at. The progress lambda is read only inside the draw pass, so the identical ramp
 * costs draw invalidation only. This is the same "progress is read inside the layer block" rule
 * `fileBrowserArrivalGlide` states explicitly.
 *
 * On API 30 the platform cannot blur at all ([MimirGlass.supportsBlur]); there the scrim carries the whole
 * transition, which is why [MimirGlassScrim]'s alpha ramps on the same progress rather than snapping.
 *
 * The blurred layer is one fixed-radius copy (the peak) cross-faded over the sharp layer at the progress ([mimirGlassBlurPlan]): the soften
 * takes the whole clock instead of finishing in the first frames, and HWUI never convolves a small radius at full resolution
 * (2026-09-10 Bottom framestats: 20–45 ms). Progress is read only during drawing; unsupported devices keep the scrim-only transition.
 */
fun Modifier.mimirGlassBackdropProgress(progress: () -> Float): Modifier =
    mimirGlassBackdropProgress(MimirGlass.backdropBlurRadius, progress)

/**
 * [mimirGlassBackdropProgress] for a surface whose glass was costed at its own peak instead of
 * [MimirGlass.backdropBlurRadius] -- the companion lattice's 24 dp (`CompanionTomeBackdropBlurDp`, measured on that
 * panel). Same progress contract, same two-layer path, same one fixed radius cross-faded at the progress ([mimirGlassBlurPlan]);
 * only that radius differs. the companion scene and its tome pager ramped a raw 24 dp x progress blur
 * straight through the small-radius regime, which HWUI convolves at full resolution.
 */
fun Modifier.mimirGlassBackdropProgress(maxRadius: Dp, progress: () -> Float): Modifier =
    mimirGlassBackdropProgress({ maxRadius }, progress)

/**
 * [mimirGlassBackdropProgress] whose peak radius is read in the DRAW phase, for a surface whose modals blur it by different amounts (Home's
 * launch card: 18 dp behind the disc menu and Open with, 34 dp behind the rename editor). The radius is a radius, and [progress] is how
 * much of that blur shows: a caller that carried the size in the progress (0.53 of a 34 dp ceiling) drew a 34 dp blur at half strength over
 * the sharp layer for as long as the modal was open, the card's text legible through the panel (review 2026-10-14). The effect is rebuilt
 * only when the radius moves, so a caller must change it only while the glass is clear (progress 0) or the blur steps at its current
 * strength.
 */
fun Modifier.mimirGlassBackdropProgress(maxRadius: () -> Dp, progress: () -> Float): Modifier =
    mimirGlassBackdropProgress(maxRadius, progress, snapshot = { false })

/**
 * [mimirGlassBackdropProgress] whose blurred subject is a SNAPSHOT while the glass is at its PEAK (one live blur
 * under an open picker). This is the one freeze primitive; a surface that blurs its own subtree behind a modal (Home's launch card, the
 * Library's detail card) passes its intent in [snapshot] and the rule below is the same everywhere.
 *
 * While [snapshot] reads true AND [progress] is at 1 ([mimirGlassSurfaceFrozen]) the surface is not re-recorded: both layers keep the
 * pixels they last recorded and are only drawn again. `drawContent()` is not called, so the subtree behind the glass neither re-records
 * nor damages the frame for as long as the modal is up; it is an image of the surface as it stood when the glass settled, and the single
 * live blur under the modal belongs to the scene. The freeze starts at the peak and not on the first ramp frame, so the open ramp (and
 * a press dip inside it) records live; it ends on the first frame the progress leaves 1, so the first clearing frame records the
 * surface afresh. Whatever changed behind the modal while it was frozen first shows under a near-full blur and sharpens with the clear,
 * never as a step.
 *
 * [snapshot] and [progress] are read in the draw phase only, so a change of either costs a draw invalidation and no recomposition. A
 * surface that has never been recorded is recorded whatever they say. On a device that cannot blur ([MimirGlass.supportsBlur] false)
 * the modifier is a no-op as before.
 *
 * **The ramp is one composite, not two** ([mimirGlassDrawWeights]; observed on Thor OPEN WITH, 2026-10-15). Between the ends the
 * sharp layer at weight 1 − p and the blurred copy at weight p are summed (additive blending) inside ONE offscreen group, and the group
 * is composited SrcOver once, so what lands on the screen is (1 − p)·sharp + p·blur over whatever is behind. Drawing them one over the
 * other was a cross-fade only over an opaque ground: over a translucent surface the sharp layer showed through the blur all ramp long,
 * two scrims stacked, and at p = 1 the sharp layer left in one frame (the hard cut and the luma flash on the launch cards). Over an
 * opaque surface the group draws the old frames away from the edges; within the blur's reach of the layer's edge (the Decal blur at
 * [MimirGlass.backdropBlurRadius], 18 dp, is partly transparent there) the mid-ramp alpha dips: a smooth fade toward the peak's own
 * edge, never a step. The sharp
 * layer's weight is a canvas layer of its own and never the layer's alpha, because the blurred copy records the same layer and would
 * fade with it; the blurred copy keeps the alpha it had. The group exists only while 0 < p < 1 (the 180 ms ramp): at rest, and under the
 * freeze, the frame is one plain layer, exactly as before, so a frozen peak and a live peak are the same pixels. Its paints and bounds are
 * built with the cache, not per frame.
 */
fun Modifier.mimirGlassBackdropProgress(maxRadius: () -> Dp, progress: () -> Float, snapshot: () -> Boolean): Modifier =
    if (!MimirGlass.supportsBlur) {
        this
    } else {
        graphicsLayer { clip = true }
            .drawWithCache {
                val sharp = obtainGraphicsLayer()
                val blurred = obtainGraphicsLayer()
                // The crossfade group: the one composite with what is behind, the sharp layer's own weight, and the additive copy.
                // The group's bounds are this layer's own, which the graphicsLayer above already clips to, so nothing new is cut.
                val groupBounds = Rect(0f, 0f, size.width, size.height)
                val paints = MimirGlassCrossfadePaints()
                var appliedRadiusPx = -1f
                var recorded = false
                var blurredRecorded = false
                onDrawWithContent {
                    val level = progress()
                    val plan = mimirGlassBlurPlan(level, maxRadius().toPx())
                    val rerecord = mimirGlassSurfaceNeedsRecording(
                        frozen = mimirGlassSurfaceFrozen(snapshot = snapshot(), progress = level),
                        recorded = recorded,
                    )
                    if (rerecord) {
                        sharp.record { this@onDrawWithContent.drawContent() }
                        recorded = true
                    }
                    // Below ~0.1px a BlurEffect is an expensive no-op; drawing the sharp layer alone
                    // releases the effect so a closed surface costs nothing.
                    if (plan.blurAlpha <= 0f) {
                        drawLayer(sharp)
                        return@onDrawWithContent
                    }
                    // The peak never changes through a ramp, so the effect is built once and only the layer's alpha moves.
                    if (appliedRadiusPx != plan.radiusPx) {
                        blurred.renderEffect = BlurEffect(plan.radiusPx, plan.radiusPx, TileMode.Decal)
                        appliedRadiusPx = plan.radiusPx
                    }
                    val weights = mimirGlassDrawWeights(plan)
                    blurred.alpha = weights.blur
                    if (rerecord || !blurredRecorded) {
                        blurred.record { drawLayer(sharp) }
                        blurredRecorded = true
                    }
                    // The peak, frozen or live: the frost alone, one plain layer.
                    if (!weights.crossfade) {
                        drawLayer(blurred)
                        return@onDrawWithContent
                    }
                    // 0 < p < 1: (1 - p) * sharp + p * blur, summed in one group and composited once; the plan is mimirGlassCrossfadeDraw's.
                    drawIntoCanvas { canvas ->
                        mimirGlassCrossfadeDraw(
                            weights = weights,
                            paints = paints,
                            saveLayer = { paint -> canvas.saveLayer(groupBounds, paint) },
                            drawSharp = { drawLayer(sharp) },
                            drawBlurred = { drawLayer(blurred) },
                            restore = { canvas.restore() },
                        )
                    }
                }
            }
    }

/** Below this a pixel-fade layer is off the screen: its last painted alpha is the end of its fade, not a page still dissolving. */
const val MimirLingeringLayerAlphaFloor: Float = 1f / 255f

/**
 * Keeps a page that is STILL DISSOLVING when its host is asked to present again. A two-layer pixel host that
 * goes A to nothing to B inside A's fade hands the layer A is still fading on to B, and A's fade is cut: the ledger only knows the
 * previous target (nothing) and recycles the "other" layer. This looks at what the layers actually still show, from the hosts' own
 * draw-thread snapshots ([layerTargetAt] and [layerAlphas], the alpha each layer last painted):
 *  - the layer still carries the SAME target as the new one: that is a reversal, and it becomes the primary again, carrying on from the
 *    alpha it painted (the blink of reopening a picker inside its own close);
 *  - it carries a DIFFERENT target: it stays on screen as the outgoing layer and the new target takes the other one (a hand-over
 *    inside the fade, A's dissolve finished from where it was, never cut).
 * A frame the ledger already built with a real outgoing page (a hand-over it decided), the host's first frame, and a frame that
 * presents nothing are left alone. Layers the final frame no longer shows are forgotten (their painted alpha is zeroed), so a fade
 * that was abandoned cannot come back as a ghost in a later frame. Pure but for that zeroing.
 */
fun <T> mimirRetainLingeringLayers(
    frame: MimirTransitionFrame<T>,
    layerTargetAt: (Int) -> T?,
    layerAlphas: FloatArray,
): MimirTransitionFrame<T> {
    val outgoing = frame.outgoingLayerIndex
    val hasRealOutgoing = outgoing != null && layerTargetAt(outgoing) != null
    val lingering = if (frame.epoch == 0L || frame.target == null || hasRealOutgoing) {
        null
    } else {
        (0..1).firstOrNull { layerTargetAt(it) != null && layerAlphas[it] > MimirLingeringLayerAlphaFloor }
    }
    val kept = if (lingering == null) {
        frame
    } else if (layerTargetAt(lingering) == frame.target) {
        frame.copy(primaryLayerIndex = lingering, outgoingLayerIndex = 1 - lingering)
    } else {
        frame.copy(primaryLayerIndex = 1 - lingering, outgoingLayerIndex = lingering)
    }
    for (index in 0..1) {
        if (index != kept.primaryLayerIndex && index != kept.outgoingLayerIndex) layerAlphas[index] = 0f
    }
    return kept
}

/**
 * Whether the glass surface must be (re)recorded on this draw: always, except while it is frozen AND already has a recording to keep
 * ([mimirGlassBackdropProgress]'s `snapshot`). Pure, so the rule is pinned without drawing a layer.
 */
internal fun mimirGlassSurfaceNeedsRecording(frozen: Boolean, recorded: Boolean): Boolean = !(frozen && recorded)

/**
 * The one freeze rule: the surface is a snapshot when its caller wants one ([snapshot]) AND the glass is at its peak ([progress] at 1).
 * Not on the first frame of the ramp (the press dip stays live) and not on the first frame of the clear (progress has left 1, so the
 * surface records afresh). Pure, so Home's card and the Library's detail card cannot disagree about where it starts and ends.
 */
internal fun mimirGlassSurfaceFrozen(snapshot: Boolean, progress: Float): Boolean = snapshot && progress >= 1f

/**
 * The dissolve clock of a layer a pixel host kept on screen ([mimirRetainLingeringLayers]): [departMs] scaled by the alpha the layer was
 * painted at, so a page already at 0.2 finishes in a fifth of the time and does not restart the whole eased fade from there (it stalled,
 * then lingered about 270 ms in all for A to nothing to B at 90 ms). A layer painted at 1 keeps the full clock, and 0 ms stays 0.
 */
fun mimirLingeringDepartMs(departMs: Int, paintedAlpha: Float): Int =
    if (departMs <= 0) 0 else (departMs * paintedAlpha.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)

/**
 * One-frame warm-up for the glass blur pipeline. The first RenderEffect blur a process draws pays Skia's
 * program compilation and layer allocation on the RenderThread (2026-09-11 Bottom framestats: 59 ms of
 * `issue` on the third frame of the first gamelist open; every later open in the same process ~8 ms).
 * HWUI shares one RenderThread/GrContext per process, so one warm-up covers every window. Draws a
 * 256 dp box through the same two-layer path as [mimirGlassBackdropProgress] at the one radius the blur plan
 * ever uses (the peak: [mimirGlassBlurPlan] cross-fades a fixed-radius copy and never sweeps the radius), at
 * alpha 1/255 so HWUI does not cull it, for exactly one frame, then composes nothing. It used to warm a
 * second, minimum radius ([MimirGlass.BlurDownsampledMinRadiusPx]) that no plan draws any more: a blur
 * program compiled and a layer allocated for nothing.
 *
 * **Once per PROCESS, not once per host**. Main and the Bottom Presentation each compose this, and
 * each used to carry its own `remember`ed flag, so a Bottom recreated while MainActivity lives (a Bottom-only restart) warmed again and
 * its first glass still arrived cold. Every window of the process shares the one RenderThread and GrContext, so the first host to warm
 * warms all: each host asks the process's [MimirGlassWarmupGate] once, as it first composes, and only the first is answered yes.
 */
@Composable
fun MimirGlassWarmup() {
    if (!MimirGlass.supportsBlur) return
    val gate = LocalMimirGlassWarmupGate.current
    // Only CHECKED here: a host that finds the process warmed composes nothing. The gate is claimed by the draw below, once the frame
    // has drawn, so a host recreated before its first draw (a config change on the way in) spends nothing and the next host warms.
    if (!gate.isOpen()) return
    var done by remember { mutableStateOf(false) }
    if (done) return
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        done = true
    }
    Box(
        Modifier
            .testTag(MimirGlassWarmupTag)
            .size(256.dp)
            .graphicsLayer { alpha = 1f / 255f }
            .drawWithCache {
                val sharp = obtainGraphicsLayer()
                val high = obtainGraphicsLayer()
                val maxPx = MimirGlass.backdropBlurRadius.toPx()
                onDrawWithContent {
                    mimirGlassWarmupDraw(gate) {
                        sharp.record { this@onDrawWithContent.drawContent() }
                        high.renderEffect = BlurEffect(maxPx, maxPx, TileMode.Decal)
                        high.record { drawLayer(sharp) }
                        drawLayer(high)
                    }
                }
            }
            .drawBehind { drawRect(Color.White, alpha = 0.5f) },
    )
}

/**
 * The gate [MimirGlassWarmup] asks (RC-59): open until one host's warm-up frame has DRAWN, closed to every host after. Composition only
 * asks; the draw claims ([mimirGlassWarmupDraw]), so a host recreated before its first draw leaves the gate open (the RC-59 review).
 * The process holds one ([MimirProcessGlassWarmupGate]); a test provides its own through [LocalMimirGlassWarmupGate] or resets it.
 */
class MimirGlassWarmupGate {
    private val claimed = AtomicBoolean(false)

    /** True until a warm-up frame has drawn. Composition asks this and claims nothing. */
    fun isOpen(): Boolean = !claimed.get()

    /** Closes the gate: true for the first caller, false ever after. The draw calls it after the warm-up frame; nothing else does. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** For tests only: a Robolectric class that composed a host reopens the gate, so the next class's host warms as a fresh process would. */
    @VisibleForTesting
    fun resetForTests() = claimed.set(false)
}

/**
 * The process's one warm-up gate: HWUI has one RenderThread and one GrContext per process, so one warm-up covers every window. Public so
 * a test in any module can [MimirGlassWarmupGate.resetForTests] it between classes, or provide a fresh one through [LocalMimirGlassWarmupGate].
 */
val MimirProcessGlassWarmupGate: MimirGlassWarmupGate = MimirGlassWarmupGate()

/** Which gate [MimirGlassWarmup] asks: the process's, unless a test provides a fresh one. */
val LocalMimirGlassWarmupGate = staticCompositionLocalOf { MimirProcessGlassWarmupGate }

/** The warm-up frame's tag, so a test can see whether a host warms. */
const val MimirGlassWarmupTag: String = "mimir.glass.warmup"

/**
 * The warm-up's one draw, and the only place the gate closes: nothing when another host has warmed the process already, else [draw] and
 * THEN the claim. Claimed after the frame has drawn, never at composition, so a host that composes and is recreated before it draws (a
 * config change on the way in) spends nothing and the next host warms. Inline, so the draw allocates nothing for it.
 */
internal inline fun mimirGlassWarmupDraw(gate: MimirGlassWarmupGate, draw: () -> Unit) {
    if (!gate.isOpen()) return
    draw()
    gate.claim()
}

/**
 * Panel-side half: alpha 0 -> 1 and a [MimirGlassMotion.PanelEnterScale] -> 1.0 lift, both at draw time.
 */
fun Modifier.mimirGlassPanelEntry(progress: () -> Float): Modifier =
    graphicsLayer {
        val settled = progress().coerceIn(0f, 1f)
        alpha = settled
        val lift = MimirGlassMotion.PanelEnterScale +
            ((1f - MimirGlassMotion.PanelEnterScale) * settled)
        scaleX = lift
        scaleY = lift
    }

/**
 * Full-screen counterpart to [mimirGlassPanelEntry]: alpha only, no lift.
 *
 * Full-screen surfaces use alpha only. [MimirGlassMotion.PanelEnterScale] is 0.96, and 4% reads as a **lift** on a
 * panel floating over a scene and as a **zoom** when the thing scaling is the entire screen. Same
 * number, different meaning at two sizes — so the scale is the part that does not generalise.
 *
 * This is still one language: identical clock, identical curve, identical progress source. Only the
 * channel differs, and it differs because the surface does.
 */
fun Modifier.mimirGlassSurfaceEntry(progress: () -> Float): Modifier =
    graphicsLayer { alpha = progress().coerceIn(0f, 1f) }

/**
 * The ramping scrim — the animated counterpart to [MimirLiquidGlassScrim], which stays untouched so the
 * nineteen existing glass call sites are not forced into one commit.
 */
@Composable
fun MimirGlassScrim(progress: () -> Float, modifier: Modifier = Modifier) {
    val color = mimirGlassScrimColor()
    Box(
        modifier
            .fillMaxSize()
            .drawBehind { drawRect(color = color, alpha = progress().coerceIn(0f, 1f)) },
    )
}

object MimirGlassMotion {
    /** The panel's starting scale. Small on purpose: a lift, not a zoom. */
    const val PanelEnterScale: Float = 0.96f
}
