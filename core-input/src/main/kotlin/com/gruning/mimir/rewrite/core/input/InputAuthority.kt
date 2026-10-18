package com.gruning.mimir.rewrite.core.input

/**
 * The raw physical event, as delivered by the Android input system to the host `ComponentActivity`,
 * reduced to just the fields the authority needs to make its decision. Kept as a small data class so
 * the authority (and its unit tests) never depend on constructing real `KeyEvent`/`MotionEvent`.
 *
 * @property kind whether this arrived as a discrete key or a continuous motion sample.
 * @property keyCode the `android.view.KeyEvent.KEYCODE_*` value (meaningful only when [kind] == KEY).
 * @property down true on ACTION_DOWN, false on ACTION_UP (meaningful only when [kind] == KEY).
 * @property repeat the key auto-repeat counter (0 = the fresh press). Directional repeats are cadence-limited;
 *   face/action repeats are swallowed.
 * @property sourceIsKeyboard true when the event's source flags include `SOURCE_KEYBOARD`. This is the
 *   dual-source signal: on the AYN Thor the "Odin Controller" reports face buttons as
 *   `GAMEPAD or KEYBOARD` and the KEYBOARD-flagged duplicate is what corrupted typing / dropped the
 *   key-up in the old product . The authority uses this flag
 *   to COALESCE the duplicate rather than let both copies act.
 * @property axisValues for MOTION samples: the relevant analog axes, keyed by `MotionEvent.AXIS_*`.
 *   Typically AXIS_HAT_X/AXIS_HAT_Y (D-pad-as-hat) and AXIS_X/AXIS_Y (left stick).
 * @property eventTimeMs the event's OWN timestamp (`KeyEvent`/`MotionEvent.eventTime`, uptime base), or
 *   0 when unknown (tests).  every timing window in this
 *   stack must be judged at EVENT time, never at processing time — a janky frame queues input, so two
 *   presses made 200ms apart can PROCESS <50-60ms apart; processing-time windows then eat the second
 *   press as a false duplicate/pace violation. The felt skip and the jank are the same event.
 */
data class RawInputEvent(
    val kind: Kind,
    val keyCode: Int = 0,
    val down: Boolean = false,
    val repeat: Int = 0,
    val sourceIsKeyboard: Boolean = false,
    val axisValues: Map<Int, Float> = emptyMap(),
    val eventTimeMs: Long = 0L,
) {
    enum class Kind { KEY, MOTION }
}

/**
 * The verdict the authority reached for one [RawInputEvent], mirroring the old product's native
 * `nativeDecision` trace (NORMALIZED / SWALLOWED / PASSTHROUGH) that was essential for debugging the
 * dual-source mess. Making the decision an explicit value (not just "return an action or null") means
 * the reason is testable and traceable.
 */
sealed interface InputDecision {
    /** The event became exactly one logical [ControllerAction]; dispatch it. */
    data class Normalized(val action: ControllerAction, val reason: String) : InputDecision

    /**
     * The event was intentionally consumed and produced NO action — e.g. a key-up, an auto-repeat we
     * throttle, a dual-source duplicate we coalesced, or an axis sample still inside the deadzone /
     * not yet re-centered. Swallowed events must NOT fall through to any platform focus engine.
     */
    data class Swallowed(val reason: String) : InputDecision

    /**
     * The event is not ours to interpret (e.g. a touch, a text-entry keystroke while a soft keyboard
     * owns input). Only these may pass through. Directional gamepad input is NEVER Passthrough — that
     * was the old product's core focus bug .
     */
    data class Passthrough(val reason: String) : InputDecision
}

// Retired: `enum class InputMode { NAVIGATION, TEXT_ENTRY }` and the
// authority's `var mode` are gone, along with the five guards that read them.
//
// The contract was inherited from the retired WebView product (`{mode:'navigation'|'text_entry'}`,
// legacy input contract) and said the on-screen keyboard is pure touch with NO controller role. Mimir's keyboard
// is the opposite by design: `MimirKeyboardFocusBinding.decorateKey` hands every key
// `Modifier.mimirFocusable`, and `MimirKeyboardFocus` exists solely to drive those keys with the stick.
// Honouring the old contract would have meant taking the controller away from the keyboard on a handheld.
//
// Nothing in production ever assigned the mode -- not once, anywhere -- so it was permanently
// NAVIGATION, all five swallow branches were unreachable, and both of `InputRouter`'s reads were
// constants. Removing it changes no behaviour; it deletes branches from the input hot path and stops
// this file documenting an invariant the code does not keep.

/**
 * THE input authority: the one place raw device events become logical [ControllerAction]s.
 *
 * ### The dual-source KEY + AXIS coalescing contract (the hard-won rules this interface guarantees)
 * An implementation MUST honor all of the following. They are the distilled lessons of the current
 * product's input reconciliation, promoted from scattered native/JS patches to an explicit contract:
 *
 * 1. **Activations fire on the key-DOWN edge, never the up edge.** The dual-source face button drops
 *    its key-up, so any action waiting for release "appears dead" while touch works. A key-UP → [InputDecision.Swallowed].
 *
 * 2. **Coalesce dual-source duplicates.** When the same logical press arrives twice (once GAMEPAD,
 *    once with [RawInputEvent.sourceIsKeyboard]), emit the action ONCE and [InputDecision.Swallowed]
 *    the duplicate. The [RawInputEvent.sourceIsKeyboard] flag + a short same-keycode debounce window
 *    is how the duplicate is identified.
 *
 * 3. **One direction per gesture — coalesce KEY and AXIS.** A D-pad press can arrive as a
 *    `KEYCODE_DPAD_*` KEY *and* as an `AXIS_HAT_*` MOTION for the same physical push; the left stick
 *    arrives as `AXIS_X/AXIS_Y`. All three feed the same four [ControllerAction] directions. Digital
 *    HAT holds share the keycode repeat cadence and coalescing stream. Analog-stick directions fire once
 *    per activation-threshold crossing and re-arm only after returning inside the deadzone (see
 *    [InputTuning.axisActivate]/[InputTuning.axisRelease]).
 *
 * 4. **Directional input is never Passthrough.** Every directional event is
 *    either [InputDecision.Normalized] to a NAV_* action or [InputDecision.Swallowed]. It must never be
 *    handed to a platform directional-focus engine — Mimir's [FocusAuthority] is the single source of
 *    focus truth (the single-authority contract).
 *
 * 5. **The on-screen keyboard is controller-driven, not swallowed.** Its keys are focusable through
 *    the FocusAuthority like any other surface; there is no separate "text entry" input mode, and an
 *    authority must not invent one. See the retirement note above [InputAuthority].
 *
 * Implementations should be pure and synchronous (no coroutines): they are called on the input
 * dispatch thread and their only state is small coalescing/repeat bookkeeping (last-source timestamps,
 * held direction, per-axis armed flag). Keeping them pure makes the contract JVM-unit-testable.
 */
interface InputAuthority {

    /**
     * Interpret one raw event. Returns the [InputDecision] — the caller dispatches the action on
     * [InputDecision.Normalized], drops it on [InputDecision.Swallowed], and only forwards to super
     * on [InputDecision.Passthrough]. Pure w.r.t. everything except the coalescing bookkeeping.
     */
    fun interpret(event: RawInputEvent): InputDecision

    /**
     * Reset all coalescing bookkeeping (armed axes, last-key timestamps). Call on window
     * focus-loss/resume so a press that started off-window can't leave a stuck armed axis. This is the
     * equivalent of the old product re-pinning focus to its sentinel on resume/picker-return.
     */
    fun reset()
}

/**
 * Tunable thresholds for the coalescing contract. Defaults are conservative, human-cadence values
 * (real thumbs move at ~280–500ms with pauses, not frame-rate).
 * Provisional — pending physical Thor/RP5 feel validation.
 *
 * @property analogDeadzone below this |axis| magnitude the stick is considered centered (re-arms direction).
 * @property axisActivate the |axis| magnitude that triggers a directional action (must exceed deadzone).
 * @property axisRelease the |axis| magnitude the axis must fall back under to re-arm (hysteresis; < activate).
 * @property dualSourceCoalesceMs within this window a same-keycode second edge is treated as the
 *   dual-source duplicate and swallowed.
 * @property directionalRepeatMs auto-repeat cadence for a held key or digital HAT direction
 *   (0 disables auto-repeat).
 */
// Tuning values are provisional and still need a physical Thor/RP5 feel pass.
data class InputTuning(
    val analogDeadzone: Float = 0.30f,
    val axisActivate: Float = 0.55f,
    val axisRelease: Float = 0.35f,
    val dualSourceCoalesceMs: Long = 50L,
    /** |trigger| magnitude that fires TRIGGER_LEFT/RIGHT once (HP-6 — Thor L2/R2 are analog axes). */
    val triggerActivate: Float = 0.55f,
    /** |trigger| magnitude the axis must fall back under to re-arm (hysteresis; < activate). */
    val triggerRelease: Float = 0.25f,
    // Retired: `triggerPaceMs = 280L` is gone. It was added 2026-08-14 as
    // the anti-misfire guard for the L2/R2 keycode twin, and it did close one of the three orderings that
    // twin can arrive in. The union latch in `DefaultInputAuthority` closes all of them and is correct at
    // ANY separation between the two edges — which a fixed window can never be, and is why 280ms left two
    // orderings open. Behind the latch the pace could no longer catch a twin; the only actuation still
    // reaching it was a genuine re-press after a full physical disengage, i.e. a deliberate fast
    // double-pull. Retired rather than kept as a backstop, because a guard that can only eat real input
    // is not a backstop. Do not reintroduce a trigger timer without first showing the latch is
    // insufficient — the latch subsumes it.
    /** Auto-repeat CADENCE once a hold is repeating (the flat 220 ms single-phase
     *  repeat read as jank against tap rhythm — now two-phase: deliberate first delay, brisk interval). */
    val directionalRepeatMs: Long = 120L,
    /** Delay before the FIRST auto-repeat of a hold, so a single deliberate tap can never double-step. */
    val directionalRepeatDelayMs: Long = 260L,
) {
    init {
        require(axisActivate > analogDeadzone) { "axisActivate must exceed the deadzone" }
        require(axisRelease in analogDeadzone..axisActivate) { "axisRelease must sit between deadzone and activate (hysteresis)" }
        require(triggerRelease < triggerActivate) { "triggerRelease must sit under triggerActivate (hysteresis)" }
    }
}
