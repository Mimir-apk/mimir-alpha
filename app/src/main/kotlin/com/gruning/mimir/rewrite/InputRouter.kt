package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.DefaultInputAuthority
import com.gruning.mimir.rewrite.core.input.InputAuthority
import com.gruning.mimir.rewrite.core.input.InputDecision
import com.gruning.mimir.rewrite.core.input.MimirDirectionalPace
import com.gruning.mimir.rewrite.core.ui.MimirFocusTraversal
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.input.PhysicalControl
import com.gruning.mimir.rewrite.core.input.RawInputEvent
import kotlin.math.abs

/**
 * The bridge from Android's raw window input to Mimir's logical [ControllerAction]s, and from there to
 * whichever Compose screen currently owns navigation.
 *
 * The [MainActivity] owns one [InputRouter] and forwards its `dispatchKeyEvent` /
 * `dispatchGenericMotionEvent` into [onKeyEvent] / [onMotionEvent]. Each raw event is normalized by
 * the single [InputAuthority]
 * (dual-source KEY + AXIS coalescing — the design constraint), and a
 * [InputDecision.Normalized] action is handed to the current [handler]. Once an input is normalized into a
 * controller action, this router consumes it regardless of the surface handler's result, so mapped buttons
 * can never fall through to Compose/platform focus (shipping MainActivity.java parity).
 *
 * This is the app-side realization of the "one focus authority" ruling: directional input reaches the
 * FocusAuthority only through this router, and the platform's own focus traversal is never invoked.
 * Every normalized action also pulses [interactionMotionGate]; [onControllerNavigation] remains the separate,
 * directional-only signal used for short focus-motion suppression.
 */
/** BM01: schedules one held-HAT repeat tick after a delay; returns a cancel handle. */
fun interface HatRepeatTicker {
    fun schedule(delayMs: Long, tick: () -> Unit): () -> Unit
}

/** Walk-32b: runs [block] once, after the next frame has read its pad input (production: a Choreographer frame callback). */
fun interface PadFrameScheduler {
    fun afterNextFrameInput(block: () -> Unit)
}

class InputRouter(
    private val inputAuthority: InputAuthority = DefaultInputAuthority(
        keymap = ControllerMappingStore::appliedKeymap,
    ),
    private val onControllerNavigation: () -> Unit = {},
    private val interactionMotionGate: InteractionMotionGate? = null,
    /**
     * Which display this router serves. NOT defaulted to PRIMARY: `PrimaryMButtonPanelHost` builds a
     * second router for a `NATIVE_WINDOW` panel, and a router that guessed PRIMARY would tell the
     * companion the player is driving the main screen when they are not.
     */
    private val interactionRoot: InteractionRoot,
    /**
     * Held-input repetition: HAT d-pads emit NO samples while held at a
     * constant value, so the authority's two-phase hold-repeat (DB01: 260ms deliberate first repeat,
     * then the 120ms cruise) NEVER RAN on the Thor/RP5 built-in pads — a 2.5s physical hold produced
     * exactly one move on device. While a HAT direction is held, this ticker re-feeds the held state
     * through the authority every [HatRepeatTickMs]; the authority's existing gates remain the ONLY
     * pacing decision, so HAT holds now repeat exactly like KEY-pad holds always have. Null (tests /
     * previews) = no synthesis — every existing test keeps its behavior.
     */
    private val hatRepeatTicker: HatRepeatTicker? = null,
    /** Uptime-base clock for synthesized samples (production: SystemClock.uptimeMillis). */
    private val hatRepeatClock: () -> Long = { 0L },
    /**
     * Physical-controller navigation haptics: the nav tick fires HERE, at the
     * single point every normalized directional crosses — Thor's built-in pad reports the D-pad as HAT
     * AXIS MotionEvents, so a KeyEvent-side tick (FUX-03..08) never saw physical presses at all. One
     * tick per emitted NAV action, key or axis, after the authority's dedup/deadzone coalescing. A non-directional press
     * ticks only when its surface recorded a changed value on [MimirNavFeedback.stepped] (a shoulder that sizes, X that resets).
     */
    private val onNavTick: () -> Unit = {},
    /**
     * Walk-32b: where a keyboard's A waits for the frame's pad input ([confirmWaitsForPadFrame]). Null (tests,
     * previews, the ᛗ panel's router) = A is never held.
     */
    private val padFrameScheduler: PadFrameScheduler? = null,
) {
    private val mappingOverlayAuthority = DefaultInputAuthority()
    private val controllerMappingInputGate =
        ControllerMappingInputGate { android.os.SystemClock.uptimeMillis() }
    private val controllerMappingMotionInputGate = ControllerMappingMotionInputGate()
    private val controllerRawInputGate = ControllerRawInputGate()
    // Shortcut capture never maps motion, but key and analog bundle tails share one post-capture
    // quarantine so a second macro key or delayed trigger/HAT edge cannot reach Settings.
    private val controllerShortcutCaptureQuarantine =
        ControllerShortcutCaptureQuarantine { android.os.SystemClock.uptimeMillis() }
    private val controllerShortcutInputGate = ControllerShortcutInputGate(
        clock = { android.os.SystemClock.uptimeMillis() },
        captureQuarantine = controllerShortcutCaptureQuarantine,
    )

    /**
     * The current consumer of normalized actions. `MimirApp` swaps this to the active screen's dispatcher.
     * The return value remains useful for surface-level bookkeeping, but mapped actions are consumed by the
     * router even when a surface reports false.
     */
    var handler: (ControllerAction) -> Boolean = { false }

    /**
     * BM03: snapshot of what a directional press could visibly move — production wires this to the
     * FocusAuthority's state value (a data class; compared structurally). The nav tick (sound +
     * haptic) fires only when this snapshot changes across the handler call OR a surface recorded
     * a non-focus action on [MimirNavFeedback] (value step, reducer-focus overlay, edge reveal).
     * Null (tests / previews) preserves the pre-BM03 tick-per-press behavior.
     */
    var navMoveProbe: (() -> Any?)? = null

    /**
     * Backspace could activate the previously focused letter after a directional press. The Nova's
     * pad reports its D-pad as hat axes on a
     * joystick source (dumpsys input: Retroid Pocket Controller, HAT_X/HAT_Y), and Android holds joystick motion until
     * the next frame reads it, while a button is handed over the moment it arrives. So while a frame is late -- a filter
     * re-listing after each letter -- RIGHT then A reached this router as A then RIGHT: A typed the letter still focused,
     * then the ring moved onto the key the player meant. The emulator's adb D-pad arrives as keys and can never show it.
     *
     * While this reads true (production: the on-screen keyboard owns focus) a key's A is held until the next frame has
     * read its pad input, and then goes in press order: an input stamped before it is dispatched first, and one that
     * acts, stamped at or after it, releases it first. A swallowed one (the A's own release, a duplicate, a hat sample
     * returning to centre) acts on nothing and leaves the A to its frame. The release matters most: it is a key too, so
     * on a long stall it is handed over together with the A, still ahead of the move. At most one frame; everywhere else
     * A is untouched. The one gap is not closed: if the keyboard closes with no input between the A and its frame, the
     * A lands on whatever took focus.
     */
    var confirmWaitsForPadFrame: () -> Boolean = { false }

    /**
     * The life of whatever an A is held for: read when the A is held and again when its frame releases it, and the A is DROPPED if the two differ. A surface that can end between
     * the press and the frame (Main's achievement page, closed by an OS Back) advances this when it ends, so its A never lands on the screen it gave back. Constant by default.
     */
    var confirmFence: () -> Long = { 0L }

    /** An A held for [confirmWaitsForPadFrame], with its own press time and the [confirmFence] it was held under. */
    private var heldConfirm: InputDecision.Normalized? = null
    private var heldConfirmTimeMs: Long = 0L
    private var heldConfirmFence: Long = 0L

    /** Scope-aware Home mode selection; false in Folios, Tome Library, and every modal. */
    var homeViewModeSelectEnabled: () -> Boolean = { false }

    /**
     * Directional Home mode selection: `right = false` selects Editor's Choice (L1), `right = true`
     * selects Pinned Tomes (R1). Applied immediately on key-down; held/repeat/release stay swallowed.
     */
    var onHomeViewModeSelect: (right: Boolean) -> Boolean = { _ -> false }

    /** Optional extra-button capture/dispatch. It never changes [InputAuthority]'s normal mappings. */
    internal var controllerShortcutCaptureActive: () -> Boolean = { false }
    internal var captureControllerShortcut: (ControllerPhysicalKey) -> Boolean = { false }
    internal var resolveControllerShortcut: (ControllerPhysicalKey) -> ControllerShortcutAction? = { null }
    internal var onControllerShortcut: (ControllerShortcutAction) -> Boolean = { false }

    /** Full console mapping is a hard modal and navigates on factory controls to prevent lockout. */
    internal var controllerMappingOverlayActive: () -> Boolean = { false }
    internal var controllerMappingCaptureActive: () -> Boolean = { false }
    internal var captureControllerMapping: (PhysicalControl) -> Boolean = { false }
    internal var captureControllerMappingKeys: (List<ControllerPhysicalKey>) -> Boolean = { false }
    internal var cancelControllerMappingCapture: () -> Boolean = { false }

    /** Feed a raw key event. Returns true if we consumed it (caller must NOT fall through to super). */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val physicalKey = controllerPhysicalKey(event)
        val mappingDecision = controllerMappingInputGate.handleKey(
            key = physicalKey,
            keyCode = event.keyCode,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            longPress = event.flags and KeyEvent.FLAG_LONG_PRESS != 0,
            captureActive = controllerMappingCaptureActive(),
            capture = captureControllerMappingKeys,
            cancel = {
                controllerMappingMotionInputGate.reset()
                cancelControllerMappingCapture()
            },
        )
        if (mappingDecision == ControllerMappingInputDecision.Consume) return true

        // While the full mapper is visible, optional macro shortcuts are suspended and the overlay
        // receives factory-normalized controls regardless of the applied/draft user map.
        if (controllerMappingOverlayActive()) {
            return dispatchRawKeyInput(
                authority = mappingOverlayAuthority,
                keyCode = event.keyCode,
                down = event.action == KeyEvent.ACTION_DOWN,
                repeat = event.repeatCount,
                sourceIsKeyboard = event.source and android.view.InputDevice.SOURCE_KEYBOARD ==
                    android.view.InputDevice.SOURCE_KEYBOARD,
                eventTimeMs = if (event.eventTime > 0L) {
                    event.eventTime
                } else {
                    android.os.SystemClock.uptimeMillis()
                },
            )
        }

        // An explicitly assigned optional shortcut owns its extra key before the full mapper's raw-key
        // lane. Without this order, a macro that had ever been assigned in Controller Mapping consumed
        // the DOWN edge before Settings > Controls could capture or dispatch Quick Launch / Utility.
        val shortcutDecision = controllerShortcutInputGate.handle(
            key = physicalKey,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            longPress = event.flags and KeyEvent.FLAG_LONG_PRESS != 0,
            // Always navigating: InputMode was retired 2026-09-06 (see InputAuthority.kt).
            navigationMode = true,
            captureActive = controllerShortcutCaptureActive(),
            capture = captureControllerShortcut,
            resolve = resolveControllerShortcut,
        )
        when (shortcutDecision) {
            ControllerShortcutInputDecision.Continue -> Unit
            ControllerShortcutInputDecision.Consume -> return true
            is ControllerShortcutInputDecision.Dispatch -> {
                interactionMotionGate?.pulse(InteractionSource.CONTROLLER, interactionRoot)
                onControllerShortcut(shortcutDecision.action)
                return true
            }
        }
        // The capture harness is deliberately broader than a mappable controller key. Virtual
        // keyboards, incomplete device descriptors, and vendor macro bundle tails cannot reach a
        // focused Settings row while listening.
        if (controllerShortcutCaptureActive()) return true

        val rawMappingDecision = controllerRawInputGate.handleKey(
            key = physicalKey,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeatCount = event.repeatCount,
            longPress = event.flags and KeyEvent.FLAG_LONG_PRESS != 0,
            enabled = true,
            bindings = ControllerMappingStore.appliedRawBindings(),
        )
        when (rawMappingDecision) {
            ControllerRawInputDecision.Continue -> Unit
            ControllerRawInputDecision.Consume -> return true
            is ControllerRawInputDecision.Dispatch -> {
                return dispatch(
                    InputDecision.Normalized(
                        action = rawMappingDecision.action,
                        reason = "explicit raw controller mapping",
                    ),
                    eventTimeMs = if (event.eventTime > 0L) {
                        event.eventTime
                    } else {
                        android.os.SystemClock.uptimeMillis()
                    },
                )
            }
            is ControllerRawInputDecision.ReplayStandard -> {
                val eventTime = if (event.eventTime > 0L) {
                    event.eventTime
                } else {
                    android.os.SystemClock.uptimeMillis()
                }
                val sourceIsKeyboard =
                    event.source and android.view.InputDevice.SOURCE_KEYBOARD ==
                        android.view.InputDevice.SOURCE_KEYBOARD
                dispatchRawKeyInput(
                    inputAuthority,
                    rawMappingDecision.keyCode,
                    down = true,
                    repeat = 0,
                    sourceIsKeyboard = sourceIsKeyboard,
                    eventTimeMs = eventTime,
                )
                return dispatchRawKeyInput(
                    inputAuthority,
                    rawMappingDecision.keyCode,
                    down = false,
                    repeat = 0,
                    sourceIsKeyboard = sourceIsKeyboard,
                    eventTimeMs = eventTime,
                )
            }
        }

        return onRawKeyInput(
            keyCode = event.keyCode,
            down = event.action == KeyEvent.ACTION_DOWN,
            repeat = event.repeatCount,
            sourceIsKeyboard = event.source and android.view.InputDevice.SOURCE_KEYBOARD == android.view.InputDevice.SOURCE_KEYBOARD,
            // ML01: the event's OWN time — every window downstream judges at event time, so presses
            // queued behind a janky frame are never mistaken for <50-60ms duplicates and eaten.
            // Panel E1: a missing stamp falls back to uptime HERE so production never mixes clock bases.
            eventTimeMs = if (event.eventTime > 0L) event.eventTime else android.os.SystemClock.uptimeMillis(),
        )
    }

    internal fun onRawKeyInput(
        keyCode: Int,
        down: Boolean,
        repeat: Int = 0,
        sourceIsKeyboard: Boolean = false,
        eventTimeMs: Long = 0L,
    ): Boolean {
        return dispatchRawKeyInput(inputAuthority, keyCode, down, repeat, sourceIsKeyboard, eventTimeMs)
    }

    private fun dispatchRawKeyInput(
        authority: InputAuthority,
        keyCode: Int,
        down: Boolean,
        repeat: Int,
        sourceIsKeyboard: Boolean,
        eventTimeMs: Long,
    ): Boolean {
        val raw = RawInputEvent(
            kind = RawInputEvent.Kind.KEY,
            keyCode = keyCode,
            down = down,
            repeat = repeat,
            sourceIsKeyboard = sourceIsKeyboard,
            eventTimeMs = eventTimeMs,
        )
        val decision = authority.interpret(raw)
        if (holdConfirmForPadFrame(decision, eventTimeMs)) return true
        return dispatch(decision, eventTimeMs)
    }

    /** Holds a keyboard's A until the frame's pad input is read ([confirmWaitsForPadFrame]); true when it did. */
    private fun holdConfirmForPadFrame(decision: InputDecision, eventTimeMs: Long): Boolean {
        val scheduler = padFrameScheduler ?: return false
        if (decision !is InputDecision.Normalized || decision.action != ControllerAction.CONFIRM) return false
        if (eventTimeMs <= 0L || !confirmWaitsForPadFrame()) return false
        // An earlier A still waiting was pressed first, so it goes first.
        releaseHeldConfirm()
        heldConfirm = decision
        heldConfirmTimeMs = eventTimeMs
        heldConfirmFence = confirmFence()
        scheduler.afterNextFrameInput(::releaseHeldConfirm)
        return true
    }

    private fun releaseHeldConfirm() {
        val held = heldConfirm ?: return
        heldConfirm = null
        // The surface this A was pressed on has ended since: it acts on nothing, least of all the screen that came back under it.
        if (confirmFence() != heldConfirmFence) return
        dispatch(held, heldConfirmTimeMs)
    }

    private fun performHomeModeSelection(right: Boolean): Boolean {
        interactionMotionGate?.pulse(InteractionSource.CONTROLLER, interactionRoot)
        onControllerNavigation()
        return if (onHomeViewModeSelect(right)) {
            onNavTick()
            true
        } else {
            false
        }
    }

    /**
     * Feed a raw generic-motion event (analog stick / hat). Returns true if we consumed it.
     *
     * ML01: Android BATCHES motion samples per frame — a brief HAT press whose press AND release land
     * inside one batch delivers ONE MotionEvent whose CURRENT sample already reads released, so the
     * press never existed for the authority (device-proven on the RP5: narrow injected pulses lost
     * 2-3 of 8 presses; real fast flick-taps under load hit the same collapse). The batch's earlier
     * edges live in the HISTORICAL samples — replay them through the authority in order, each with its
     * own timestamp, so every edge is seen and every window still judges at true event time.
     */
    fun onMotionEvent(event: MotionEvent): Boolean = onMotionEvent(event, permitted = null)

    /**
     * [onMotionEvent] for only the samples [permitted] flags (oldest first, the current sample last; null is all of them): a caller that has consumed some of a batch itself
     * (the Bottom's wake press) replays the rest and nothing it already took. False when no sample is permitted.
     */
    fun onMotionEvent(event: MotionEvent, permitted: BooleanArray?): Boolean {
        val history = event.historySize
        val samples = ArrayList<Map<Int, Float>>(history + 1)
        val times = ArrayList<Long>(history + 1)
        for (i in 0..history) {
            if (permitted != null && !permitted.getOrElse(i) { true }) continue
            if (i < history) {
                samples += motionAxes { axis -> event.getHistoricalAxisValue(axis, i) }
                times += event.getHistoricalEventTime(i)
            } else {
                samples += motionAxes { axis -> event.getAxisValue(axis) }
                times += event.eventTime
            }
        }
        if (samples.isEmpty()) return false
        return onMotionSamples(samples, times)
    }

    /** The samples themselves, oldest first with their event times; the current (newest) sample last. */
    internal fun onMotionSamples(samples: List<Map<Int, Float>>, times: List<Long>): Boolean {
        if (consumeControllerMappingMotionSamples(samples)) {
            // Capture is an isolated harness: every motion sample through neutral release is consumed,
            // and HAT repeat is never armed.
            return true
        }
        if (consumeControllerShortcutMotionSamples(samples)) return true
        var consumed = false
        // Reuse the maps built above rather than rebuilding every one. `samples` is the historical
        // samples in order followed by the current sample, so `samples[i]` IS historical sample i and
        // `samples.last()` is the current one -- same values, half the allocations. This runs per motion
        // event at controller poll rate, and each map is eight boxed Int keys and eight boxed Floats, so
        // the duplicate set was pure garbage on the input hot path.
        for (i in 0 until samples.size - 1) {
            consumed = onRawMotionSample(
                axes = samples[i],
                eventTimeMs = times[i],
            ) || consumed
        }
        val currentAxes = samples.last()
        val handled = onRawMotionSample(axes = currentAxes, eventTimeMs = times.last()) || consumed
        // BM01: the REAL sample stream drives the hold state — a press arms the repeater, the
        // release sample (or reset()) disarms it.
        updateHatHold(currentAxes)
        return handled
    }

    /** Test seam for the capture/release quarantine used by [onMotionEvent]. */
    internal fun consumeControllerMappingMotionSamples(samples: List<Map<Int, Float>>): Boolean =
        controllerMappingMotionInputGate.handle(
            samples = samples,
            captureActive = controllerMappingCaptureActive(),
            capture = captureControllerMapping,
        ) == ControllerMappingInputDecision.Consume

    /** Shortcut capture is key-only; motion is observed solely to retain isolation through neutral. */
    internal fun consumeControllerShortcutMotionSamples(samples: List<Map<Int, Float>>): Boolean {
        if (controllerShortcutCaptureActive()) {
            controllerShortcutCaptureQuarantine.observeCaptureMotion(samples)
            return true
        }
        return controllerShortcutCaptureQuarantine.consumeMotion(samples)
    }

    private companion object {
        /** HAT axes are digital ±1; anything past half is a held direction. */
        const val HatHeldThreshold = 0.5f

        /** Faster than every repeat gate (260/120ms) so the gates alone decide the cadence — and a
         *  DIVISOR of the 120ms cruise, so gate-eligible instants land exactly on the tick grid: the
         *  synthesized cruise is the ratified 120ms, not 120-rounded-up-to-the-next-tick (first
         *  repeat quantizes 260 → ≤280ms, imperceptible against the deliberate-delay intent). */
        const val HatRepeatTickMs = 40L
    }

    private inline fun motionAxes(value: (Int) -> Float): Map<Int, Float> = mapOf(
        MotionEvent.AXIS_HAT_X to value(MotionEvent.AXIS_HAT_X),
        MotionEvent.AXIS_HAT_Y to value(MotionEvent.AXIS_HAT_Y),
        MotionEvent.AXIS_X to value(MotionEvent.AXIS_X),
        MotionEvent.AXIS_Y to value(MotionEvent.AXIS_Y),
        MotionEvent.AXIS_LTRIGGER to value(MotionEvent.AXIS_LTRIGGER),
        MotionEvent.AXIS_RTRIGGER to value(MotionEvent.AXIS_RTRIGGER),
        MotionEvent.AXIS_BRAKE to value(MotionEvent.AXIS_BRAKE),
        MotionEvent.AXIS_GAS to value(MotionEvent.AXIS_GAS),
    )

    internal fun onRawMotionSample(axes: Map<Int, Float>, eventTimeMs: Long = 0L): Boolean {
        val raw = RawInputEvent(kind = RawInputEvent.Kind.MOTION, axisValues = axes, eventTimeMs = eventTimeMs)
        val authority = if (controllerMappingOverlayActive()) mappingOverlayAuthority else inputAuthority
        return dispatch(authority.interpret(raw), eventTimeMs)
    }

    /** Reset coalescing bookkeeping (call on window focus loss/resume — memory: stuck-armed-axis cure). */
    fun reset() {
        inputAuthority.reset()
        mappingOverlayAuthority.reset()
        // Panel F4: the pace resets WITH the authority so no spent budget bleeds across a surface
        // transition (a first-press-after-open must never be eaten by the outgoing surface's window).
        MimirDirectionalPace.reset()
        controllerShortcutInputGate.reset()
        controllerMappingInputGate.reset()
        controllerMappingMotionInputGate.reset()
        controllerRawInputGate.reset()
        stopHatRepeat()
        // Walk-32b: an A still waiting for its frame belonged to the window that just lost focus.
        heldConfirm = null
    }

    // ── BM01: held-HAT repeat synthesis ──────────────────────────────────────

    private var heldHatX = 0f
    private var heldHatY = 0f
    private var cancelHatRepeat: (() -> Unit)? = null

    private fun updateHatHold(axes: Map<Int, Float>) {
        val ticker = hatRepeatTicker ?: return
        heldHatX = axes[MotionEvent.AXIS_HAT_X] ?: 0f
        heldHatY = axes[MotionEvent.AXIS_HAT_Y] ?: 0f
        val held = abs(heldHatX) >= HatHeldThreshold || abs(heldHatY) >= HatHeldThreshold
        if (!held) {
            stopHatRepeat()
            return
        }
        if (cancelHatRepeat != null) return
        // Drift-corrected grid: each tick's delay is computed against an ABSOLUTE deadline, so
        // handler dispatch latency and busy frames don't stretch the effective grid (device truth:
        // naive relative rescheduling drifted the 120ms cruise to ~140-160ms under UI load).
        var nextDueMs = hatRepeatClock() + HatRepeatTickMs
        fun scheduleTick() {
            val delayMs = (nextDueMs - hatRepeatClock()).coerceAtLeast(1L)
            cancelHatRepeat = ticker.schedule(delayMs) {
                cancelHatRepeat = null
                val stillHeld = abs(heldHatX) >= HatHeldThreshold || abs(heldHatY) >= HatHeldThreshold
                if (stillHeld) {
                    // The synthetic sample mirrors the held state; the authority classifies it as a
                    // hold-repeat and its 260/120 gates decide whether it becomes a move.
                    onRawMotionSample(
                        axes = mapOf(
                            MotionEvent.AXIS_HAT_X to heldHatX,
                            MotionEvent.AXIS_HAT_Y to heldHatY,
                        ),
                        eventTimeMs = hatRepeatClock(),
                    )
                    nextDueMs += HatRepeatTickMs
                    scheduleTick()
                }
            }
        }
        scheduleTick()
    }

    /** Test seam: [updateHatHold] is normally driven by real MotionEvents in [onMotionEvent]. */
    internal fun updateHatHoldForTest(axes: Map<Int, Float>) = updateHatHold(axes)

    private fun stopHatRepeat() {
        cancelHatRepeat?.invoke()
        cancelHatRepeat = null
        heldHatX = 0f
        heldHatY = 0f
    }

    private fun dispatch(decision: InputDecision, eventTimeMs: Long = 0L): Boolean {
        // Walk-32b: an input that acts, pressed at or after a held A (or with no stamp), must not overtake it. A swallowed
        // one acts on nothing, and the A's own release must not let it go ahead of the frame's pad input.
        if (
            heldConfirm != null &&
            decision !is InputDecision.Swallowed &&
            (eventTimeMs <= 0L || eventTimeMs >= heldConfirmTimeMs)
        ) {
            releaseHeldConfirm()
        }
        return when (decision) {
        is InputDecision.Normalized -> {
            // while a page change still hides its new page, presses are
            // ignored; Back, the shoulders and the system keys always work, and Back or a shoulder reverses it.
            if (
                MimirTransitionInputGate.holding &&
                decision.action.waitsForPageChange()
            ) {
                return true
            }
            if (
                !controllerMappingOverlayActive() &&
                homeViewModeSelectEnabled() &&
                (decision.action == ControllerAction.PAGE_LEFT || decision.action == ControllerAction.PAGE_RIGHT)
            ) {
                performHomeModeSelection(decision.action == ControllerAction.PAGE_RIGHT)
                return true
            }
            interactionMotionGate?.pulse(InteractionSource.CONTROLLER, interactionRoot)
            //  GLOBAL d-pad speed limit — a directional that arrives before the
            // pace interval elapses is consumed and dropped for every surface, so traversal can
            // never outrun a focus animation. Ticks and nav signals fire only for real moves.
            // ML01: judged at EVENT time (see MimirDirectionalPace) — jank can no longer eat presses.
            val paced = !decision.action.isDirectional ||
                MimirDirectionalPace.tryAccept(eventTimeMs = eventTimeMs)
            if (paced) {
                val directional = decision.action.isDirectional
                if (directional) {
                    // The controller-nav pulse stays unconditional: even a dead-end press is
                    // controller activity (input-mode chrome must wake).
                    onControllerNavigation()
                    // Before the handler moves focus, so the rings that recompose for this move read it: a held
                    // pad keeps the fast ring clock (product-wide 2x). Uptime, like the stamps.
                    MimirFocusTraversal.onDirectionalMove(
                        if (eventTimeMs > 0L) eventTimeMs else android.os.SystemClock.uptimeMillis(),
                    )
                }
                // BM03: snapshot BOTH feedback truths before the handler runs (see navMoveProbe).
                val probe = navMoveProbe
                val focusBefore = if (directional && probe != null) probe() else null
                val feedbackBefore = MimirNavFeedback.epoch
                val steppedBefore = MimirNavFeedback.stepEpoch
                // PERF-F: the SYNCHRONOUS handler chain (focus write -> dispatch -> companion identity
                // publish) runs inside the input stage of the frame. Framestats showed 35-39ms
                // input+anim on press-commit frames but cannot split "this chain" from "the
                // recomposition it schedules" — this threshold-gated log can. Quiet in normal play.
                val handlerStartNs = System.nanoTime()
                handler(decision.action)
                val handlerCostMs = (System.nanoTime() - handlerStartNs) / 1_000_000
                if (handlerCostMs >= 8) {
                    runCatching {
                        android.util.Log.i("MimirInputCost", "handler=${handlerCostMs}ms action=${decision.action}")
                    }
                }
                if (directional) {
                    val moved = probe == null ||
                        probe() != focusBefore ||
                        MimirNavFeedback.epoch != feedbackBefore
                    if (moved) onNavTick()
                } else if (MimirNavFeedback.stepEpoch != steppedBefore) {
                    // A shoulder or face button that changed a value (MimirNavFeedback.stepped) sounds like a D-pad step; one that
                    // changed nothing, and every other press, stays silent.
                    onNavTick()
                }
            }
            true
        }
        // A directional/activation event that was intentionally consumed (key-up, dedup, deadzone) is
        // still "handled" so it never falls through to the platform focus engine (contract rule 4).
        is InputDecision.Swallowed -> true
        // Only genuinely-not-ours events (touch, text keystrokes) fall through to super.
        is InputDecision.Passthrough -> false
        }
    }
}

/**
 * The presses a page change holds back until its new page shows : the pad and the face buttons,
 * which would act on a page the player cannot see yet. Back, the shoulders, the triggers and the system and global
 * keys always pass.
 */
internal fun ControllerAction.waitsForPageChange(): Boolean = when (this) {
    ControllerAction.NAV_UP,
    ControllerAction.NAV_DOWN,
    ControllerAction.NAV_LEFT,
    ControllerAction.NAV_RIGHT,
    ControllerAction.CONFIRM,
    ControllerAction.SECONDARY,
    ControllerAction.TERTIARY,
    ControllerAction.SYSTEM_START,
    ControllerAction.SYSTEM_SELECT,
    -> true
    else -> false
}
