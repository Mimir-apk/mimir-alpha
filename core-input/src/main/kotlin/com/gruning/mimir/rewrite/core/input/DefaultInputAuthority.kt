package com.gruning.mimir.rewrite.core.input

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

/**
 * Reference [InputAuthority] implementing the dual-source KEY + AXIS coalescing contract.
 *
 * Pure and synchronous except for its small input bookkeeping: dual-source timestamps, the active
 * held-direction repeat stream, and per-direction axis hysteresis. It reads `KeyEvent.KEYCODE_*` /
 * `MotionEvent.AXIS_*` constants but constructs neither, so its logic is fully unit-testable by feeding
 * [RawInputEvent]s (see the module's tests).
 *
 * @param tuning thresholds (deadzone/hysteresis/coalesce window) — provisional, see [InputTuning].
 * @param clock injectable monotonic time source (ms) for dual-source and directional-repeat windows.
 */
class DefaultInputAuthority(
    private val tuning: InputTuning = InputTuning(),
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    private val keymap: () -> ControllerKeymap = { defaultControllerKeymap() },
) : InputAuthority {


    /** Last DOWN time per keycode, for dual-source duplicate coalescing (contract rule 2). */
    private val lastKeyDownAt = HashMap<Int, Long>()

    private enum class DirectionalSource { KEY, AXIS }

    private data class DirectionalStamp(
        val at: Long,
        val source: DirectionalSource,
    )

    /** Last normalized physical control and source, for KEY+AXIS duplicate coalescing. */
    private val lastPhysical = HashMap<PhysicalControl, DirectionalStamp>()

    /**
     *
     * Keyed by [PhysicalControl] rather than by action or globally, both deliberate:
     *  - **Not global** (unlike [MimirDirectionalPace]): a shared stamp would make an L2 pull eat the R2
     *    press that follows it, and correcting an over-jump by immediately pulling the other trigger is
     *    the single most likely thing a player does next.
     *  - **Not by [ControllerAction]**: the keymap is user-remappable, so two controls can carry the same
     *    action; the thing being paced is the physical trigger that misfires, not the verb it produces.
     */

    /** Last logical direction accepted by the authority; throttled raw repeats never advance this clock. */
    private var lastAcceptedDirectionAt: Long? = null

    /** Directional keycodes currently held, in press order, so the newest direction owns auto-repeat. */
    private val heldDirectionalKeys = LinkedHashSet<Int>()

    private var activeRepeatKeyCode: Int? = null

    /** Newest held direction across KEY and HAT; stale opposite-source repeats cannot regain ownership. */
    private var activeRepeatAction: ControllerAction? = null

    /** Current digital HAT control. Analog-stick axes retain one-crossing hysteresis below. */
    private var activeHatControl: PhysicalControl? = null

    /** Two-phase repeat: false until the FIRST auto-repeat of the current hold fires, so
     *  that repeat gates on [InputTuning.directionalRepeatDelayMs] and later ones on the brisk interval. */
    private var repeatFiredSinceArm = false

    /** Whether each directional axis is "armed" (has returned inside the deadzone since last firing). */
    private val axisArmed = HashMap<PhysicalControl, Boolean>().apply {
        put(PhysicalControl.DPAD_UP, true); put(PhysicalControl.DPAD_DOWN, true)
        put(PhysicalControl.DPAD_LEFT, true); put(PhysicalControl.DPAD_RIGHT, true)
        // HP-6: on the Thor pad L2/R2 are ANALOG axes (LTRIGGER/RTRIGGER, aliased BRAKE/GAS) — same
        // armed-latch hysteresis as the directions, so a held pull fires exactly once.
        // TM-2 (below): for L2/R2 this latch is now SHARED with the keycode twin, so it is the
        // engage/disengage state of the whole physical trigger, not of the axis alone.
        put(PhysicalControl.L2, true); put(PhysicalControl.R2, true)
    }

    /**
     * the union trigger latch. Which of L2/R2 currently has its `KEYCODE_BUTTON_L2/R2` twin held.
     *
     * A trigger is ENGAGED when *either* source says so — axis above [InputTuning.triggerActivate] **or**
     * keycode down — and it does not DISENGAGE (re-arm) until *both* say otherwise. One physical pull
     * therefore produces exactly one actuation no matter which source reports first or how far apart the
     * two edges land, which is what the timer it replaces could not do. See [triggerDisengaged].
     */
    private val heldTriggerKeys = HashSet<PhysicalControl>()

    /**
     * Last observed analog magnitude per trigger, so a keycode UP can evaluate the same disengage
     * predicate the motion path uses. Absent (digital-only pad that never sends a trigger axis) reads as
     * 0f, so key UP alone re-arms and such a pad can never end up with a dead trigger.
     */
    private val lastTriggerAxis = HashMap<PhysicalControl, Float>()

    override fun interpret(event: RawInputEvent): InputDecision = when (event.kind) {
        RawInputEvent.Kind.KEY -> interpretKey(event)
        RawInputEvent.Kind.MOTION -> interpretMotion(event)
    }

    override fun reset() {
        lastKeyDownAt.clear()
        lastPhysical.clear()
        lastAcceptedDirectionAt = null
        heldDirectionalKeys.clear()
        activeRepeatKeyCode = null
        activeRepeatAction = null
        activeHatControl = null
        repeatFiredSinceArm = false
        axisArmed.keys.forEach { axisArmed[it] = true }
        heldTriggerKeys.clear()
        lastTriggerAxis.clear()
    }

    /**
     * The ONE disengage predicate, evaluated identically from the motion path and from a keycode UP.
     *
     * Both halves are load-bearing. Dropping the `heldTriggerKeys` half is the subtle way to make the union latch a
     * no-op: on a key-first slow pull the pad's digital edge fires at ~0.15 depth and the *rising* axis
     * samples that follow it (0.18, 0.22 …) are still under [InputTuning.triggerRelease] (0.25), so an
     * axis-only re-arm rule would re-open the latch before the pull ever reaches
     * [InputTuning.triggerActivate] (0.55) — and the axis would then actuate a second time exactly as it
     * does today. The trigger axes on both certified pads report `flat 0` (no hardware deadzone), so those
     * small rising samples genuinely arrive.
     */
    private fun triggerDisengaged(control: PhysicalControl): Boolean =
        (lastTriggerAxis[control] ?: 0f) <= tuning.triggerRelease && control !in heldTriggerKeys

    /** Keycode UP for a trigger: drop the key half of the union, then re-arm if the axis half agrees. */
    private fun releaseTriggerKey(control: PhysicalControl?) {
        if (control != PhysicalControl.L2 && control != PhysicalControl.R2) return
        heldTriggerKeys.remove(control)
        if (triggerDisengaged(control)) axisArmed[control] = true
    }

    /**
     * the authority's "now" is the event's OWN timestamp when the host provides one (uptime base),
     * falling back to [clock] for tests that feed bare [RawInputEvent]s. Processing-time judging made
     * queued presses (behind a janky frame) look like <50ms duplicates of each other and eat the second.
     */
    private fun nowFor(e: RawInputEvent): Long = if (e.eventTimeMs > 0L) e.eventTimeMs else clock()

    /** The repeat gate for the current hold: first repeat waits the deliberate delay, later ones the interval. */
    private fun repeatGateMs(): Long =
        if (repeatFiredSinceArm) tuning.directionalRepeatMs else tuning.directionalRepeatDelayMs



    private fun interpretKey(e: RawInputEvent): InputDecision {
        // Rule 1: activations (and directions) fire on DOWN. Any UP is swallowed so nothing waits on it.
        if (!e.down) {
            releaseDirectionalKey(e.keyCode)
            // TM-2: the UP edge is the key half of the trigger's disengage. It still produces no action.
            releaseTriggerKey(physicalControlForKeyCode(e.keyCode))
            return InputDecision.Swallowed("key_up")
        }

        // Android Back/Home remain escape hatches regardless of the user's console map.
        val fixedAction = when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> ControllerAction.CANCEL
            KeyEvent.KEYCODE_HOME -> ControllerAction.SYSTEM_HOME
            else -> null
        }
        val control = physicalControlForKeyCode(e.keyCode)
        val action = fixedAction ?: control?.let { keymap()[it] }
        if (action == null) {
            return if (control?.isDirectionalSource == true) {
                InputDecision.Swallowed("unbound_directional_control_$control")
            } else {
                InputDecision.Passthrough("unmapped_key_${e.keyCode}")
            }
        }

        // Face/action repeats never fire. Directional repeats are handled as one cadence-controlled
        // logical stream below, independently of the much shorter dual-source coalescing window.
        if (e.repeat > 0 && !action.isDirectional) return InputDecision.Swallowed("key_repeat")

        val now = nowFor(e)
        if (e.repeat > 0) return interpretDirectionalRepeat(e, action, now)

        if (action.isDirectional) armDirectionalKey(e.keyCode, action)

        // TM-2: register the KEY half of the union before any dedupe can return. The dedupes still run
        // first and still own their reason strings, but a twin they swallow is a keycode that is now
        // physically DOWN — if that goes unrecorded, an axis dip mid-hold reads as a full disengage and
        // re-arms a trigger the user never let go of. The UP edge always removes it (releaseTriggerKey
        // runs before every other branch of interpretKey), so this cannot leak.
        if (control == PhysicalControl.L2 || control == PhysicalControl.R2) heldTriggerKeys.add(control)

        // Rule 2: dual-source coalescing — a same-keycode DOWN within the window is the duplicate.
        // Panel B1: windows are [0, window) — an out-of-order stamp (negative delta, e.g. two
        // controllers with skewed delivery) must never read as a duplicate and eat a real press.
        val prev = lastKeyDownAt[e.keyCode]
        if (prev != null && now - prev in 0 until tuning.dualSourceCoalesceMs) {
            lastKeyDownAt[e.keyCode] = now
            return InputDecision.Swallowed("dual_source_duplicate_keycode_${e.keyCode}")
        }
        lastKeyDownAt[e.keyCode] = now
        if (
            control?.hasAxisSource == true &&
            isRecentPhysicalDuplicate(control, now, DirectionalSource.KEY)
        ) {
            recordPhysical(control, now, DirectionalSource.KEY)
            return InputDecision.Swallowed("dual_source_duplicate_direction_$action")
        }

        // TM-2: the union trigger latch. This is the edge that closes the reported misfire, and it needs
        // no timing constant — see the class KDoc on heldTriggerKeys. Since 2026-08-21 it is
        // also the ONLY thing gating a trigger actuation; the 280ms pace that used to sit behind it is
        // gone, so there is no longer a second, time-based opinion to order against.
        if (control == PhysicalControl.L2 || control == PhysicalControl.R2) {
            if (axisArmed[control] != true) return InputDecision.Swallowed("trigger_latched_$action")
            axisArmed[control] = false
        }

        // the 2026-08-14 anti-misfire pace is GONE, not kept as a backstop. The
        // union latch above is correct at ANY separation between the keycode and axis edges, which is
        // exactly what a 280ms window could never be — and once the latch catches the twin, the pace's
        // only remaining live effect was to floor a genuine re-press after a full physical disengage.
        // That is indistinguishable from a deliberate fast double-pull, so it had stopped guarding
        // anything and started eating real input.
        if (control?.hasAxisSource == true) recordPhysical(control, now, DirectionalSource.KEY)
        if (action.isDirectional) lastAcceptedDirectionAt = now
        return InputDecision.Normalized(action, "key_${e.keyCode}")
    }

    private fun interpretDirectionalRepeat(
        e: RawInputEvent,
        action: ControllerAction,
        now: Long,
    ): InputDecision {
        if (e.keyCode !in heldDirectionalKeys) {
            return InputDecision.Swallowed("directional_repeat_unarmed_$action")
        }
        if (activeRepeatKeyCode != e.keyCode || activeRepeatAction != action) {
            return InputDecision.Swallowed("directional_repeat_inactive_$action")
        }

        // A noisy HAT twin arriving beside this key-repeat remains part of the same physical hold.
        physicalControlForKeyCode(e.keyCode)
            ?.takeIf { it.hasAxisSource }
            ?.let { recordPhysical(it, now, DirectionalSource.KEY) }
        if (tuning.directionalRepeatMs <= 0L) {
            return InputDecision.Swallowed("directional_repeat_disabled_$action")
        }

        val lastAcceptedAt = lastAcceptedDirectionAt
            ?: return InputDecision.Swallowed("directional_repeat_unseeded_$action")
        if (now - lastAcceptedAt in 0 until repeatGateMs()) {
            return InputDecision.Swallowed("directional_repeat_throttled_$action")
        }

        lastAcceptedDirectionAt = now
        repeatFiredSinceArm = true
        return InputDecision.Normalized(action, "key_repeat_${e.keyCode}")
    }

    private fun armDirectionalKey(keyCode: Int, action: ControllerAction) {
        heldDirectionalKeys.remove(keyCode)
        heldDirectionalKeys.add(keyCode)
        activeRepeatKeyCode = keyCode
        activeRepeatAction = action
        repeatFiredSinceArm = false
    }

    private fun releaseDirectionalKey(keyCode: Int) {
        val releasedAction = keyToAction(keyCode)?.takeIf { it.isDirectional }
        val releasedOwnedRepeat = activeRepeatKeyCode == keyCode && activeRepeatAction == releasedAction
        heldDirectionalKeys.remove(keyCode)
        if (activeRepeatKeyCode == keyCode) activeRepeatKeyCode = heldDirectionalKeys.lastOrNull()
        if (releasedOwnedRepeat) {
            activeRepeatAction = activeRepeatKeyCode
                ?.let(::keyToAction)
                ?.takeIf { it.isDirectional }
                ?: activeHatControl?.let { keymap()[it] }?.takeIf { it.isDirectional }
        }
    }

    private fun interpretMotion(e: RawInputEvent): InputDecision {
        val now = nowFor(e)
        // Rule 3: KEY and digital HAT share cadence-limited holds. Analog stick axes fire once per
        // threshold crossing and re-arm only after returning inside the deadzone (hysteresis).
        val hasHatAxis =
            e.axisValues.containsKey(MotionEvent.AXIS_HAT_X) ||
                e.axisValues.containsKey(MotionEvent.AXIS_HAT_Y)
        val x = e.axisValues[MotionEvent.AXIS_HAT_X] ?: e.axisValues[MotionEvent.AXIS_X] ?: 0f
        val y = e.axisValues[MotionEvent.AXIS_HAT_Y] ?: e.axisValues[MotionEvent.AXIS_Y] ?: 0f

        // HP-6: trigger pulls (analog L2/R2). Aliased axes per shipping handleTriggerRoutes: some pads
        // report BRAKE/GAS instead of LTRIGGER/RTRIGGER — take the max of each pair.
        val l2 = maxOf(e.axisValues[MotionEvent.AXIS_LTRIGGER] ?: 0f, e.axisValues[MotionEvent.AXIS_BRAKE] ?: 0f)
        val r2 = maxOf(e.axisValues[MotionEvent.AXIS_RTRIGGER] ?: 0f, e.axisValues[MotionEvent.AXIS_GAS] ?: 0f)
        // record the axis half of the union first, then re-arm only when BOTH halves are released.
        // The `heldTriggerKeys` term is what stops a rising soft pull from re-arming itself between its
        // own keycode edge and its axis edge — see triggerDisengaged.
        //
        // a sample that does not CARRY a trigger axis says nothing about where that trigger is —
        // absence of data is not "released". `InputRouter`'s synthesized HAT-repeat tick
        // (`InputRouter.kt`, `scheduleTick`) sends AXIS_HAT_X/Y **only**, so l2/r2 read 0f from it. Acting
        // on that zero re-arms a trigger the user is still physically holding, and the next real sample
        // (still at 0.9) actuates a second time — a misfire produced by holding a direction while pulling
        // a trigger, which is ordinary play. The earlier code re-armed unconditionally and had this same
        // hole; gating on presence closes it for both halves of the union.
        val hasL2Axis = e.axisValues.containsKey(MotionEvent.AXIS_LTRIGGER) ||
            e.axisValues.containsKey(MotionEvent.AXIS_BRAKE)
        val hasR2Axis = e.axisValues.containsKey(MotionEvent.AXIS_RTRIGGER) ||
            e.axisValues.containsKey(MotionEvent.AXIS_GAS)
        if (hasL2Axis) {
            lastTriggerAxis[PhysicalControl.L2] = l2
            if (triggerDisengaged(PhysicalControl.L2)) axisArmed[PhysicalControl.L2] = true
        }
        if (hasR2Axis) {
            lastTriggerAxis[PhysicalControl.R2] = r2
            if (triggerDisengaged(PhysicalControl.R2)) axisArmed[PhysicalControl.R2] = true
        }

        // Re-arm directions whose axis has fallen back under the release threshold.
        if (abs(x) < tuning.axisRelease) { axisArmed[PhysicalControl.DPAD_LEFT] = true; axisArmed[PhysicalControl.DPAD_RIGHT] = true }
        if (abs(y) < tuning.axisRelease) { axisArmed[PhysicalControl.DPAD_UP] = true; axisArmed[PhysicalControl.DPAD_DOWN] = true }

        // Trigger fire (before the directional read — shipping runs handleTriggerRoutes first): once per
        // pull, armed-latched, coalesced against the KEYCODE_BUTTON_L2/R2 twin some pads also send.
        val triggerControl = when {
            l2 > tuning.triggerActivate && axisArmed[PhysicalControl.L2] == true -> PhysicalControl.L2
            r2 > tuning.triggerActivate && axisArmed[PhysicalControl.R2] == true -> PhysicalControl.R2
            else -> null
        }
        if (triggerControl != null) {
            axisArmed[triggerControl] = false
            val trigger = keymap()[triggerControl]
                ?: return InputDecision.Passthrough("unbound_axis_control_$triggerControl")
            if (isRecentPhysicalDuplicate(triggerControl, now, DirectionalSource.AXIS)) {
                recordPhysical(triggerControl, now, DirectionalSource.AXIS)
                return InputDecision.Swallowed("dual_source_duplicate_direction_$trigger")
            }
            // NO pace on the axis path, and that is a measured decision rather than an omission.
            // The armed-latch above already guarantees ONE actuation per physical pull: firing clears
            // `axisArmed[control]`, and only an axis reading back under `triggerRelease` re-arms it. So a
            // second axis actuation REQUIRES a real release, which makes it a deliberate second pull, not
            // a misfire. Pacing here would swallow exactly that -- and `heldAnalogTriggersFireOnce...`
            // (DefaultInputAuthorityHumanMappingTest) proves the point by failing when it is added.
            //
            // If device evidence shows the axis oscillating across the whole 0.25..0.55 band on one pull,
            // the honest fix is widening THAT band, not a timer. The `trigger_pace_` swallow reason below
            // is what will tell the two apart in logcat.
            //
            // It does STAMP the pace, though: the twin that follows this pull has to have something to be
            // measured against, or it sails through the gate on the key path. Record, do not check.
            recordPhysical(triggerControl, now, DirectionalSource.AXIS)
            return InputDecision.Normalized(trigger, "axis_trigger")
        }

        if (hasHatAxis) return interpretHat(x, y, now)

        val firedControl = fireAxisDirection(x, horizontal = true) ?: fireAxisDirection(y, horizontal = false)
        if (firedControl != null) {
            val fired = keymap()[firedControl]
                ?: return InputDecision.Swallowed("unbound_directional_control_$firedControl")
            if (isRecentPhysicalDuplicate(firedControl, now, DirectionalSource.AXIS)) {
                recordPhysical(firedControl, now, DirectionalSource.AXIS)
                return InputDecision.Swallowed("dual_source_duplicate_direction_$fired")
            }
            recordPhysical(firedControl, now, DirectionalSource.AXIS)
            if (fired.isDirectional) lastAcceptedDirectionAt = now
            return InputDecision.Normalized(fired, "axis")
        }
        // Rule 4: directional input is NEVER passthrough — an in-deadzone / not-re-armed sample is swallowed.
        return InputDecision.Swallowed("axis_deadzone_or_not_rearmed")
    }

    private fun interpretHat(x: Float, y: Float, now: Long): InputDecision {
        val previousHat = activeHatControl
        val control = axisDirection(x, horizontal = true)
            ?: axisDirection(y, horizontal = false)
            ?: previousHat?.takeIf { isHatDirectionHeld(it, x, y) }

        if (control == null) {
            releaseHatDirection(previousHat)
            return InputDecision.Swallowed("axis_deadzone_or_not_rearmed")
        }

        val action = keymap()[control]
            ?: return InputDecision.Swallowed("unbound_directional_control_$control")
        if (control == previousHat) {
            return if (action.isDirectional) {
                interpretHatRepeat(control, action, now)
            } else {
                InputDecision.Swallowed("key_repeat")
            }
        }

        activeHatControl = control
        axisArmed[control] = false

        // A second source joining the currently active direction is part of that hold, not a fresh
        // gesture. A real reversal takes ownership immediately, before repeat cadence is considered.
        if (action.isDirectional && activeRepeatAction == action) {
            if (isRecentPhysicalDuplicate(control, now, DirectionalSource.AXIS)) {
                recordPhysical(control, now, DirectionalSource.AXIS)
                return InputDecision.Swallowed("dual_source_duplicate_direction_$action")
            }
            return interpretHatRepeat(control, action, now)
        }

        activeRepeatAction = action.takeIf { it.isDirectional }
        repeatFiredSinceArm = false
        if (isRecentPhysicalDuplicate(control, now, DirectionalSource.AXIS)) {
            recordPhysical(control, now, DirectionalSource.AXIS)
            return InputDecision.Swallowed("dual_source_duplicate_direction_$action")
        }

        recordPhysical(control, now, DirectionalSource.AXIS)
        if (action.isDirectional) lastAcceptedDirectionAt = now
        return InputDecision.Normalized(action, "axis")
    }

    private fun interpretHatRepeat(
        control: PhysicalControl,
        action: ControllerAction,
        now: Long,
    ): InputDecision {
        if (activeRepeatAction != action) {
            return InputDecision.Swallowed("axis_repeat_inactive_$action")
        }

        recordPhysical(control, now, DirectionalSource.AXIS)
        if (tuning.directionalRepeatMs <= 0L) {
            return InputDecision.Swallowed("axis_repeat_disabled_$action")
        }

        val lastAcceptedAt = lastAcceptedDirectionAt
            ?: return InputDecision.Swallowed("axis_repeat_unseeded_$action")
        if (now - lastAcceptedAt in 0 until repeatGateMs()) {
            return InputDecision.Swallowed("axis_repeat_throttled_$action")
        }

        lastAcceptedDirectionAt = now
        repeatFiredSinceArm = true
        return InputDecision.Normalized(action, "axis_repeat")
    }

    private fun releaseHatDirection(control: PhysicalControl?) {
        val action = control?.let { keymap()[it] }
        activeHatControl = null
        if (activeRepeatAction == action) {
            activeRepeatAction = activeRepeatKeyCode
                ?.let(::keyToAction)
                ?.takeIf { it.isDirectional }
        }
    }

    private fun axisDirection(value: Float, horizontal: Boolean): PhysicalControl? {
        if (abs(value) < tuning.axisActivate) return null
        return when {
            horizontal && value < 0 -> PhysicalControl.DPAD_LEFT
            horizontal -> PhysicalControl.DPAD_RIGHT
            value < 0 -> PhysicalControl.DPAD_UP
            else -> PhysicalControl.DPAD_DOWN
        }
    }

    private fun isHatDirectionHeld(
        control: PhysicalControl,
        x: Float,
        y: Float,
    ): Boolean = when (control) {
        PhysicalControl.DPAD_LEFT -> x <= -tuning.axisRelease
        PhysicalControl.DPAD_RIGHT -> x >= tuning.axisRelease
        PhysicalControl.DPAD_UP -> y <= -tuning.axisRelease
        PhysicalControl.DPAD_DOWN -> y >= tuning.axisRelease
        else -> false
    }

    private fun isRecentPhysicalDuplicate(
        control: PhysicalControl,
        now: Long,
        source: DirectionalSource,
    ): Boolean {
        val previous = lastPhysical[control] ?: return false
        // Panel B1: [0, window) — a negative delta (out-of-order cross-device stamp) is NOT a duplicate.
        return previous.source != source && now - previous.at in 0 until tuning.dualSourceCoalesceMs
    }

    private fun recordPhysical(
        control: PhysicalControl,
        now: Long,
        source: DirectionalSource,
    ) {
        lastPhysical[control] = DirectionalStamp(at = now, source = source)
    }

    private fun fireAxisDirection(value: Float, horizontal: Boolean): PhysicalControl? {
        if (abs(value) < tuning.axisActivate) return null
        val control = when {
            horizontal && value < 0 -> PhysicalControl.DPAD_LEFT
            horizontal -> PhysicalControl.DPAD_RIGHT
            value < 0 -> PhysicalControl.DPAD_UP
            else -> PhysicalControl.DPAD_DOWN
        }
        if (axisArmed[control] != true) return null
        axisArmed[control] = false
        return control
    }

    private fun keyToAction(keyCode: Int): ControllerAction? = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> ControllerAction.CANCEL
        KeyEvent.KEYCODE_HOME -> ControllerAction.SYSTEM_HOME
        else -> physicalControlForKeyCode(keyCode)?.let { keymap()[it] }
    }
}
