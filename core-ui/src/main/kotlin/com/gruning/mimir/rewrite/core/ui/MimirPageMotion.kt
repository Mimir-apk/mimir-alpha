package com.gruning.mimir.rewrite.core.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.sign

/**
 * The page tier: opening a page or switching modes.
 *
 * Motion contract:
 * - **Tempo:** a quick exit and a slow arrival. The departing page fades and glides out on [ExitMs]; the arriving one
 *   starts at the measured [LeadMs] lead, where the departing page is at alpha 0.044, and fades and glides in on
 *   [ArriveMs]. That is about 670 ms from release to rest. Returns stay as quick as today ([ReturnMs], the
 *   2026-09-04 reference measured at 201 ms): the departing page glides back as it dissolves over a destination at rest.
 * - **Presses:** ignored until the new page shows; Back and the shoulders always work and reverse the move
 *   ([MimirTransitionInputGate]).
 * - **Settling:** a page may hold its predecessor still while its first covers decode, up to [SettleCapMs], so it
 *   arrives wearing its art instead of fading covers in after it shows.
 *
 * Direction: +1 is forward (the new page arrives from the right, the old one leaves to the left), -1 is back.
 */
object MimirPageMotion {
    const val ExitMs = MimirMotion.OverlayMs
    const val LeadMs = MimirMotion.OverlayDissolveCoverMs
    const val ArriveMs = MimirMotion.ShineMs
    const val ReturnMs = MimirMotion.ModeTransitionMs
    const val SettleCapMs = 400

    /**
     * Where the swap tier counts a page as shown (S1, policy 2026-09-30, "until 15% visible"): a hold that frees on
     * the first frame above alpha 0 hands the pad a panel that is still a trace. On the house curve the 540 ms arrival
     * is at 1% by 11 ms, 15% by 71 ms and 25% by 97 ms, so a press waits for a page the player can read.
     *
     * It is for Quick Launch and its picker ONLY (the dock's second A launched an app nobody had seen). Every other cold
     * hold keeps the law [holdMimirInputUntilShown] states, first paint: the app host's pixel-fade and launch-card
     * holds, the glass transition, and the Library's three. These hosts retain the first-paint boundary.
     */
    const val ShownAlpha = 0.15f

    /** How long a hold can keep presses out even if its owner never releases it: settle cap, lead and a margin. */
    const val InputGateMaxMs = SettleCapMs + LeadMs + ExitMs

    /** Where an arriving page starts: on the side it comes from. */
    fun arrivalTravelPx(direction: Int, travelPx: Float): Float = direction.sign * travelPx

    /** Where a departing page goes on an entry: the other way. */
    fun exitTravelPx(direction: Int, travelPx: Float): Float = -direction.sign * travelPx

    /** Where a departing page goes on a return: back toward the side it came from. */
    fun returnTravelPx(direction: Int, travelPx: Float): Float = direction.sign * travelPx
}

/**
 * Presses during a page change : ignored until the new page shows. The host holds the gate from the
 * press until its arriving page first paints; the app's input router drops every press but Back, the shoulders and the
 * system keys while it is held. It is process-wide like [MimirFocusTraversal], one owner at a time (a newer hold
 * replaces an older one), and it expires on its own after [MimirPageMotion.InputGateMaxMs], so a host that dies
 * mid-change can never leave the pad deaf.
 *
 * That deadline is sized for the wait, the lead and the exit added up, so a wait that ends late leaves the arrival
 * less than its lead and exit to first paint. A page that waits before it arrives calls [rearm] when the wait ends
 * (the Library's page entry and its two gamelist swaps).
 */
object MimirTransitionInputGate {
    /** Uptime, like the input router's stamps. Injectable for tests. */
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    @Volatile private var owner: Any? = null

    @Volatile private var untilMs: Long = 0L

    fun hold(owner: Any, maxMs: Int = MimirPageMotion.InputGateMaxMs) {
        untilMs = clock() + maxMs
        this.owner = owner
    }

    /**
     * Gives the hold [owner] still has [maxMs] from now, and never less than it already had (RG-2, alpha audit
     * 2026-09-30). Without it a capped settle that ended late, a first frame after it a little slow, let the pad reach a
     * page still at alpha 0: the deadline ran out on the arrival's lead. Only the owner can: a hold that was released or
     * replaced by a newer one changes nothing, so a page finishing its wait never takes the gate from another. The owner
     * still releases the hold itself, and it still lapses on its own.
     */
    fun rearm(owner: Any, maxMs: Int) {
        if (this.owner !== owner) return
        val until = clock() + maxMs
        if (until > untilMs) untilMs = until
    }

    fun release(owner: Any) {
        if (this.owner === owner) this.owner = null
    }

    val holding: Boolean
        get() = owner != null && clock() < untilMs

    fun reset() {
        owner = null
        untilMs = 0L
    }
}

/**
 * Batch F put the overlays and the in-place swaps on the page lead (130 ms at alpha 0, then the arrival), so they wait
 * for presses the way a page does : [MimirTransitionInputGate] is held for [owner] until [shown]
 * first reads true -- the first frame the incoming content paints -- and released on cancellation too, so an arrival
 * that is interrupted never leaves the pad deaf. Back and the shoulders pass the gate as ever.
 *
 * Call it from the arrival's own LaunchedEffect before its first suspension, and only for a COLD arrival (alpha still
 * 0): it starts UNDISPATCHED, so the hold lands on the effect's frame and the release is armed before anything can
 * cancel it. [shown] must read snapshot state (an Animatable's value). A reduced-motion snap paints one frame later,
 * so the gate still holds and lets go at once.
 */
fun CoroutineScope.holdMimirInputUntilShown(owner: Any, shown: () -> Boolean): Job =
    launch(start = CoroutineStart.UNDISPATCHED) {
        MimirTransitionInputGate.hold(owner)
        try {
            snapshotFlow(shown).first { it }
        } finally {
            MimirTransitionInputGate.release(owner)
        }
    }

/**
 * A wait on the animation clock: it returns on the first frame at or after [ms], and it
 * scales with the animator setting exactly as the fade it covers does. A coroutine `delay()` does not scale, so under a
 * slowed animator a hold and the fade it covers drift apart, and under a stopped one (scale 0) the hold still waits out
 * its whole time after the fade it covers is gone. This one runs slower with them and returns at once at 0. It cancels
 * with its coroutine.
 *
 * Wherever a fade can carry the wait as its own delay, that is the better form (`tween(..., delayMillis = lead)`, the
 * arrival's own); this is for a hold that has no animation of its own to carry it. It is a snap after a delay, and not
 * a zero-length tween, because the literal-duration pin reads any `tween(` with a bare number as a second clock.
 */
suspend fun mimirHold(ms: Int) {
    if (ms <= 0) return
    Animatable(0f).animateTo(1f, snap(delayMillis = ms))
}

/**
 * False while the page around it is still arriving. A focused game's gleam waits for it, so its three sweeps are seen
 * rather than spent behind the departing page (motion audit, ring spec §5).
 */
val LocalMimirPageArrived = compositionLocalOf { true }

/**
 * True while the page around it is held behind its predecessor. A cover that lands then snaps in, so the page arrives
 * wearing it; covers that land after the page shows keep their fade.
 */
val LocalMimirPageSettling = staticCompositionLocalOf<() -> Boolean> { { false } }

/**
 * A surface whose content is replaced in place and hands over on the page tier (motion audit batch D: Home's L1/R1
 * switch). The content is recorded every frame into one of two layers. When [epoch] changes, the last recording stays
 * on screen as the departing page -- pixels only, with no semantics and no input -- and fades out on
 * [MimirPageMotion.ExitMs] while it glides to [exitTravelPx]. The live content is drawn at [incomingAlpha] and
 * [incomingTravelPx], which the caller runs on its own clock and reads in the draw phase only. With [departs] false
 * (a restore rather than a press) nothing departs and the new content simply fades in.
 *
 * The departing page starts from the alpha and travel actually painted, so it never jumps. A press that turns back
 * to the page still leaving is recognised only with a [target] (what the content shows, such as Home's mode): the
 * caller then reads [MimirPageHandoffState.returningAlpha] from its hoisted [state] before this composes the turn
 * and starts its incoming clock there, so the returning page carries on instead of blinking out and arriving again.
 * With no [target] nothing is ever a reversal and the handoff behaves as it always has.
 * [exitMs] is the departing page's clock: [MimirPageMotion.ExitMs] for an entry or a swap, [MimirPageMotion.ReturnMs]
 * for a return (batch F: the file browser's ascend). With [vertical] the travel is on Y instead of X (batch F: the
 * gamelist's Carousel/List swap arrives from below).
 * [exitReleased] holds the departing page at rest -- as painted, in its seat -- until it reads true (the gamelist's L2/R2 waits for the new platform's rows and center covers). The default lets it go at once.
 * [modifier] must size the surface (Home passes `fillMaxSize()`): both layers match it.
 *
 * The departing page is a display list, not a bitmap: content drawn inline is frozen, but a child with its own
 * layer (`graphicsLayer`, a `GraphicsLayer`) is only referenced. A layered child disposed on the flip keeps its last
 * recording and departs correctly; one that survives the flip re-records in place, so the departing page shows its
 * live (new) content. Key any layered child whose content changes with the page on [epoch].
 *
 * The layer transform is paint only, so the live content hit-tests at rest even while it is invisible or
 * gliding. While [shieldTouches] reads true a touch anywhere on the surface reaches nothing -- the touch half of
 * [MimirTransitionInputGate] (presses wait until the new page shows). The caller drops it
 * where it releases the gate. It is read in composition, here, so only this surface recomposes when it flips.
 */
@Composable
fun MimirPageHandoff(
    epoch: Any,
    departs: Boolean,
    exitTravelPx: Float,
    incomingAlpha: () -> Float,
    incomingTravelPx: () -> Float,
    reducedMotion: Boolean,
    exitMs: Int = MimirPageMotion.ExitMs,
    vertical: Boolean = false,
    modifier: Modifier = Modifier,
    target: Any? = null,
    state: MimirPageHandoffState = remember { MimirPageHandoffState() },
    probe: MimirPageHandoffProbe? = null,
    shieldTouches: () -> Boolean = { false },
    exitReleased: () -> Boolean = { true },
    content: @Composable BoxScope.() -> Unit,
) {
    val first = rememberGraphicsLayer()
    val second = rememberGraphicsLayer()
    val layers = remember(first, second) { listOf(first, second) }
    val ledger = remember { MimirTransitionEpochLedger(epoch) }
    val frame = remember(epoch) { ledger.prepare(epoch) { _, _ -> departs } }
    SideEffect {
        if (ledger.commit(frame)) state.commit(frame.primaryLayerIndex, frame.outgoingLayerIndex, target)
    }
    // Draw-thread snapshots of what each layer last painted, hoisted so the caller can seed a reversal from them.
    val displayedAlpha = state.displayedAlpha
    val displayedTravel = state.displayedTravel
    val outgoingAlpha = remember(frame) {
        Animatable(frame.outgoingLayerIndex?.let(displayedAlpha::get)?.coerceIn(0f, 1f) ?: 0f)
    }
    val outgoingTravel = remember(frame) { Animatable(frame.outgoingLayerIndex?.let(displayedTravel::get) ?: 0f) }
    var outgoingVisible by remember(frame) { mutableStateOf(frame.outgoingLayerIndex != null) }
    val currentExitReleased by rememberUpdatedState(exitReleased)
    LaunchedEffect(frame) {
        val leaving = frame.outgoingLayerIndex ?: return@LaunchedEffect
        // Held at rest until the caller lets it go; the default returns at once, without suspending. Read through
        // the updated state, so a caller whose flag is re-created mid-hold is still heard.
        snapshotFlow { currentExitReleased() }.first { it }
        val exit = tween<Float>(
            durationMillis = if (reducedMotion) 0 else exitMs,
            easing = MimirMotion.Easing,
        )
        coroutineScope {
            // Also noted on the frame clock, so a reversal in this frame's composition reads what the frame paints
            // even before its draw runs.
            launch { outgoingTravel.animateTo(exitTravelPx, exit) { displayedTravel[leaving] = value } }
            outgoingAlpha.animateTo(0f, exit) { displayedAlpha[leaving] = value }
        }
        if (ledger.acceptsCompletion(frame.epoch)) {
            outgoingVisible = false
            state.layerShowing[leaving] = false
        }
    }
    if (probe != null) {
        SideEffect {
            probe.departing = { outgoingVisible && frame.outgoingLayerIndex != null }
            probe.outgoingAlpha = { if (outgoingVisible) outgoingAlpha.value else 0f }
            probe.outgoingTravel = { outgoingTravel.value }
            probe.travelAxisVertical = { vertical }
        }
    }
    Box(modifier) {
        val primary = layers[frame.primaryLayerIndex]
        Box(
            Modifier
                .matchParentSize()
                .drawWithContent {
                    val live = this
                    primary.alpha = 1f
                    primary.record { live.drawContent() }
                    val alpha = incomingAlpha().coerceIn(0f, 1f)
                    val travel = incomingTravelPx()
                    primary.alpha = alpha
                    if (vertical) primary.translationY = travel else primary.translationX = travel
                    displayedAlpha[frame.primaryLayerIndex] = alpha
                    displayedTravel[frame.primaryLayerIndex] = travel
                    drawLayer(primary)
                },
            content = content,
        )
        val outgoingIndex = frame.outgoingLayerIndex
        if (outgoingVisible && outgoingIndex != null) {
            val outgoing = layers[outgoingIndex]
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics { }
                    .drawWithContent {
                        val alpha = outgoingAlpha.value
                        val travel = outgoingTravel.value
                        outgoing.alpha = alpha
                        if (vertical) outgoing.translationY = travel else outgoing.translationX = travel
                        displayedAlpha[outgoingIndex] = alpha
                        displayedTravel[outgoingIndex] = travel
                        drawLayer(outgoing)
                    },
            )
        }
        // Last, so it is hit first: a standalone shield rather than one on the departing layer, which can be gone
        // while the arriving page is still hidden.
        if (shieldTouches()) {
            Box(Modifier.matchParentSize().mimirPageTouchShield())
        }
    }
}

/**
 * An overlay's arrival on the page tier (shared with Main's Quick Launch dock so the Bottom dock and panels use the same clock): the [MimirPageMotion.LeadMs] lead at alpha 0, then
 * [MimirPageMotion.ArriveMs] on the house easing, about 670 ms from the press. Reduced motion snaps.
 *
 * One function feeds both the painting (`fadeIn`) and a [MimirSwapPageGate]'s probe, so the two run the same clock and
 * cannot drift.
 */
fun mimirOverlayArriveSpec(reducedMotion: Boolean): FiniteAnimationSpec<Float> = tween(
    durationMillis = if (reducedMotion) 0 else MimirPageMotion.ArriveMs,
    delayMillis = if (reducedMotion) 0 else MimirPageMotion.LeadMs,
    easing = MimirMotion.Easing,
)

/**
 * An overlay's departure: [MimirMotion.OverlayMs] on the house easing, as every dissolving overlay leaves. It takes no
 * reduced-motion parameter because Main's dock exit is not reduced-motion aware, and this matches it.
 */
fun mimirOverlayLeaveSpec(): FiniteAnimationSpec<Float> = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)

/**
 * While a page change hides its new page, or a page is leaving, a touch on the surface reaches nothing: every pointer
 * event is consumed in the Initial pass, before any child's `clickable` sees one. It is the touch half of
 * [MimirTransitionInputGate], which only the pad's router honours. [MimirPageHandoff] lays it over its surface, and
 * [MimirSwapPageGate] puts it on a swap page.
 *
 * It is keyed on its own object, never `Unit`. Compose matches two `pointerInput` elements with equal keys as the same
 * node whatever their lambdas are, so a shield followed in one modifier chain by another `pointerInput(Unit)` (the
 * Utility panel's bounds catcher) that drops out of the chain when the page shows could leave the surviving node still
 * running the shield's consume-everything loop: a panel that paints and answers nothing.
 *
 * It is an ANCESTOR's Initial-pass consume, so it stops a child that reads a consumed pointer as not its own
 * (`clickable`, `detectTapGestures`) and not one that does not (`awaitFirstDown(requireUnconsumed = false)`, such as the
 * keyboard's Backspace and Space keys). A shield that must stop those too is a sibling laid over the content, as
 * [MimirPageHandoff] lays it.
 */
fun Modifier.mimirPageTouchShield(): Modifier = pointerInput(MimirTouchShieldKey) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

private object MimirTouchShieldKey

/**
 * What each of [MimirPageHandoff]'s two layers last showed and painted, hoisted so its caller can see a reversal before
 * the handoff composes it (observed L1/R1 reversal: the Library's strip carried one on and Home did not).
 * Plain arrays written on the frame clock and in draw; a caller reads them in a `remember`, never through an
 * Animatable, so an exit never recomposes its caller.
 */
class MimirPageHandoffState {
    internal val displayedAlpha = floatArrayOf(1f, 0f)
    internal val displayedTravel = floatArrayOf(0f, 0f)
    internal val layerTargets = arrayOfNulls<Any>(2)
    internal val layerShowing = booleanArrayOf(true, false)
    private var primaryLayerIndex = 0

    internal fun commit(primary: Int, outgoing: Int?, target: Any?) {
        primaryLayerIndex = primary
        layerTargets[primary] = target
        layerShowing[primary] = true
        // A restore departs nothing, so whatever was leaving is gone with it.
        if (outgoing == null) layerShowing[1 - primary] = false
    }

    /**
     * The alpha the page still leaving was last painted at, when [target] is what it shows; null when this is not a
     * reversal (no target, another target, or that page has already left).
     */
    fun returningAlpha(target: Any?): Float? {
        if (target == null) return null
        val leaving = 1 - primaryLayerIndex
        if (!layerShowing[leaving] || layerTargets[leaving] != target) return null
        return displayedAlpha[leaving].coerceIn(0f, 1f).takeIf { it > 0f }
    }

    /** Where that returning page was last painted, on the handoff's travel axis; null when [returningAlpha] is. */
    fun returningTravel(target: Any?): Float? =
        if (returningAlpha(target) == null) null else displayedTravel[1 - primaryLayerIndex]
}

/** A test seam: what [MimirPageHandoff]'s departing page is doing, read on the test's clock. */
class MimirPageHandoffProbe {
    var departing: () -> Boolean = { false }
    var outgoingAlpha: () -> Float = { 0f }
    var outgoingTravel: () -> Float = { 0f }
    var travelAxisVertical: () -> Boolean = { false }
}
