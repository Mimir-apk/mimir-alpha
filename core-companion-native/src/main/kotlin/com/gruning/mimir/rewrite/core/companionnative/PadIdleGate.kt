package com.gruning.mimir.rewrite.core.companionnative

import android.view.MotionEvent

/** The controller axes whose movement counts as someone using the device: the hat, both sticks' primary pair, the triggers and the gas and brake pair. */
private val IdleWakeAxes = intArrayOf(
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_LTRIGGER,
    MotionEvent.AXIS_RTRIGGER,
    MotionEvent.AXIS_BRAKE,
    MotionEvent.AXIS_GAS,
)

/** The dead zone that decides "held" (the wake press, what is eaten, what the lane gets): an axis at or past this magnitude (either sign) is deflected, anything inside it is at rest. */
private const val PAD_IDLE_DEAD_ZONE = 0.25f

/**
 * The magnitude a deflected axis must fall BELOW before the hold CAP counts it as released. The cap's hysteresis only: an axis that entered at [PAD_IDLE_DEAD_ZONE] stays in the same
 * hold while it hovers between this and the dead zone, so a stick drifting around the edge cannot clear its hold and restart it with every sample. "Held" is not affected by it.
 */
private const val PAD_HOLD_RELEASE_BELOW = 0.20f

/** The positive-sign bits of a direction code (one pair of bits per axis in [IdleWakeAxes] order: positive, then negative) and the negative-sign bits. */
private const val PositiveBits = 0x5555
private const val NegativeBits = 0xAAAA

/**
 * One sample's quantised direction, packed: a bit per axis and sign that is set when the axis is at or past [PAD_IDLE_DEAD_ZONE] (the low half; 0 is rest) and, in the high half, the
 * same at [PAD_HOLD_RELEASE_BELOW] (the hold cap's hysteresis; always a superset of the low half). The hat's RIGHT, the left stick's RIGHT, a trigger and a diagonal are each their own
 * code; the size of a deflection, and jitter inside the dead zone, never change it.
 */
private inline fun quantisedIdleSample(axisValue: (axis: Int) -> Float): Long {
    var entered = 0
    var sustained = 0
    for (index in IdleWakeAxes.indices) {
        val value = axisValue(IdleWakeAxes[index])
        if (value >= PAD_IDLE_DEAD_ZONE) entered = entered or (1 shl (2 * index)) else if (value <= -PAD_IDLE_DEAD_ZONE) entered = entered or (2 shl (2 * index))
        if (value >= PAD_HOLD_RELEASE_BELOW) sustained = sustained or (1 shl (2 * index)) else if (value <= -PAD_HOLD_RELEASE_BELOW) sustained = sustained or (2 shl (2 * index))
    }
    return (entered.toLong() and 0xFFFFFFFFL) or (sustained.toLong() shl 32)
}

/**
 * The samples one motion event carries, oldest first and the current one last. As a list of booleans each is whether the input is held (past the dead zone), which is all most
 * callers ask; [direction] adds, for the hold cap, in WHICH quantised direction (0 at rest, any other value a direction, equal values the same direction), and [sustained] the same
 * read at the lower magnitude the cap uses to decide a deflection has been let go.
 */
class IdleWakeSamples internal constructor(private val directions: IntArray, private val sustains: IntArray) : AbstractList<Boolean>() {
    override val size: Int get() = directions.size

    override fun get(index: Int): Boolean = directions[index] != 0

    /** Sample [index]'s quantised direction: 0 at rest, otherwise a code equal to another sample's exactly when the input is held the same way. */
    fun direction(index: Int): Int = directions[index]

    /** Sample [index]'s direction read at the cap's release magnitude: every bit of [direction] and those axes still deflected inside the band under the dead zone. */
    fun sustained(index: Int): Int = sustains[index]

    companion object {
        /** Samples given as direction codes directly (a pure test, or a source that already quantised its axes): 0 is rest, any other value a direction, with no band under it. */
        fun ofDirections(vararg directions: Int): IdleWakeSamples = directions.copyOf().let { IdleWakeSamples(it, it) }

        /** Samples that only know whether they are held: every held one is the same, single, direction. */
        fun ofHeld(held: BooleanArray): IdleWakeSamples = IntArray(held.size) { if (held[it]) 1 else 0 }.let { IdleWakeSamples(it, it) }

        /** Samples read from plain axis values instead of a MotionEvent (a pure test): each map is one sample, oldest first, and an axis it does not name is 0. */
        fun ofAxes(vararg samples: Map<Int, Float>): IdleWakeSamples = fromPacked(samples.size) { index -> quantisedIdleSample { axis -> samples[index][axis] ?: 0f } }

        internal inline fun fromPacked(count: Int, packedAt: (index: Int) -> Long): IdleWakeSamples {
            val entered = IntArray(count)
            val sustained = IntArray(count)
            for (index in 0 until count) {
                val packed = packedAt(index)
                entered[index] = packed.toInt()
                sustained[index] = (packed ushr 32).toInt()
            }
            return IdleWakeSamples(entered, sustained)
        }
    }
}

/**
 * Every sample this event carries, oldest first and the current one last, as whether the input is held (past the dead zone) at that sample and in which quantised direction. A batch
 * can hold a press, a return to rest and a new press: [PadIdleGate.motion] walks them in order, so a rest anywhere releases the wake press and a press after it is a press of its
 * own. Main's own lane, the Bottom's Activity page pad and the Bottom's ᛗ menu pad all count the same inputs as activity through this one definition.
 */
fun MotionEvent.idleWakeSamples(): IdleWakeSamples {
    val past = historySize
    return IdleWakeSamples.fromPacked(past + 1) { sample ->
        if (sample < past) quantisedIdleSample { axis -> getHistoricalAxisValue(axis, sample) } else quantisedIdleSample { axis -> getAxisValue(axis) }
    }
}

/** How long a wake press may go quiet before the gate stops eating for it (a key repeat or a stick sample arrives well inside this; a lost key up never does). */
const val PAD_WAKE_LATCH_MS: Long = 600L

/**
 * A hold that does not change earns activity credit for at most 2 minutes. A key stuck down repeats, and a drifting or stuck stick sends motion
 * events without end; either would keep the screen awake for ever if every one of them re-armed the idle deadline. From the moment a hold began, its credit stops after this long.
 */
const val PAD_HOLD_ACTIVITY_CAP_MS: Long = 120_000L

/**
 * The one screen-idle rule for a pad lane: Main's own key and motion lane, the Bottom's Activity page pad and the Bottom's ᛗ menu pad all run through this class.
 * The Bottom's two pad routes sit AHEAD of Main's sink on the floor (or, for the menu, in a window of their own), so Main's rule never saw what they consume; the gate is that
 * rule, in three steps:
 *
 * 1. Every event the lane is offered that counts as a press (a key down, a repeat of it included; a hat, stick or trigger past the dead zone) goes to the existing idle controller
 *    ([interaction], `MimirScreenIdleController.onUserInteraction`) ONCE, whether the gate then eats it or forwards it: it re-arms the deadline, and it is the only thing that
 *    ever wakes or dims the screen. Eating only affects dispatch, never the idle refresh, so a wake press that is held keeps the screen awake for as long as it is held, up to
 *    [PAD_HOLD_ACTIVITY_CAP_MS] (below).
 * 2. When that press woke a dimmed screen it is the wake press and does nothing else: eaten, and the lane's pad router reset ONCE, there ([onWake]). Its release only ends the
 *    latch and never resets anything: a separate press that has queued in the router since (an A waiting for its frame) is not the wake press's to clear.
 * 3. Any other event goes on to the lane's own route.
 *
 * The wake press is eaten for as long as it is the wake press. While a wake KEY is latched every key event is eaten, a different key's down and up included (Main's rule since
 * before the gate: a second key pressed while the wake key is held is not a press of its own), and the latch ends at the first of: the wake key's own up, [PAD_WAKE_LATCH_MS]
 * without an event from it (its release went to someone else), and any change of pad ownership ([ownership] differs from when the press woke the screen: the ᛗ menu opened or
 * closed, and took the release). The wake MOTION press ends at the first sample of a batch, oldest first, that is back at rest, or by the same quiet spell and ownership change.
 * Callers that own a lane for a lifetime of their own ([reset]) clear it when they lose the pad (Main losing window focus).
 *
 * **The hold cap** changes step 1 and nothing else: a hold that does not change stops earning activity credit [PAD_HOLD_ACTIVITY_CAP_MS] after it began, so a key stuck down or a
 * stick that drifts cannot keep the screen awake for ever. It decides only whether [interaction] is told, never whether an event is eaten or forwarded, so the latch rules above
 * are untouched. A KEY hold is one key's repeats with nothing between them: its up, ANY other key's down, [PAD_WAKE_LATCH_MS] of quiet, or a change of pad ownership ends it, and
 * the next down of any key is a new hold with a fresh cap. So does [holdBoundary] moving (another route took or gave back the pad while this lane kept it), which starts credit
 * fresh and, unlike [ownership], touches no wake latch. A MOTION hold is the samples staying in the same quantised direction (the same hat or stick direction, past the dead
 * zone: jitter inside the same direction is not a change; an axis that entered the hold stays in it until it falls below [PAD_HOLD_RELEASE_BELOW], so one hovering at the edge of
 * the dead zone is still one hold); a real change of direction, or a return to rest followed by a new deflection, is a new hold. "Held" itself, which decides the wake press and
 * what is eaten, is read at the dead zone alone.
 *
 * Pure over primitives (no framework event), so the rule is tested without a window.
 */
class PadIdleGate(
    private val interaction: () -> Boolean,
    private val onWake: () -> Unit,
    /** Changes whenever who owns the pad changes; a latch made under another value is dropped. */
    private val ownership: () -> Long = { 0L },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    /**
     * Changes whenever a route that does not own the pad takes it or gives it back (the Activity page's, while this lane keeps its own). It ends the HOLD CAP's tracking only, so
     * credit starts fresh after it; it never touches a wake latch (that is [ownership]'s). Defaults to never.
     */
    private val holdBoundary: () -> Long = { 0L },
) {
    private var wakeKeyCode: Int? = null
    private var keyLatchedAt = 0L
    private var keyOwnership = 0L

    private var wakeMotionLatched = false
    private var motionLatchedAt = 0L
    private var motionOwnership = 0L

    /** The key whose hold is in progress (null: none), when that hold began, the last event the key sent (a down or a repeat) and the pad owner it began under. */
    private var keyHoldCode: Int? = null
    private var keyHoldDownAt = 0L
    private var keyHoldLastAt = 0L
    private var keyHoldOwnership = 0L
    private var keyHoldBoundary = 0L

    /** The quantised direction the motion hold is in (0: none), with the cap's hysteresis, when it began and under which pad owner. */
    private var motionHoldDirection = 0
    private var motionHoldSince = 0L
    private var motionHoldOwnership = 0L
    private var motionHoldBoundary = 0L

    /** Forgets any wake press and any hold in progress, as when the lane's window lost focus and its release will never arrive. */
    fun reset() {
        wakeKeyCode = null
        wakeMotionLatched = false
        keyHoldCode = null
        motionHoldDirection = 0
    }

    private fun dropStaleLatches(now: Long) {
        val owner = ownership()
        if (wakeKeyCode != null && (owner != keyOwnership || now - keyLatchedAt >= PAD_WAKE_LATCH_MS)) wakeKeyCode = null
        if (wakeMotionLatched && (owner != motionOwnership || now - motionLatchedAt >= PAD_WAKE_LATCH_MS)) wakeMotionLatched = false
    }

    /**
     * Whether a key DOWN of [keyCode] earns activity credit. The hold in progress is that key's own repeats: ANY other key's down ends it, so is a new hold with a fresh cap, and the
     * first key's next repeat or down is a new hold too. A down with no live hold of that key is a new hold and earns; a repeat earns until the hold is [PAD_HOLD_ACTIVITY_CAP_MS] old. A
     * hold is also ended by the key's up, a quiet spell of [PAD_WAKE_LATCH_MS] or a change of pad owner (its up went elsewhere).
     */
    private fun keyDownEarnsCredit(keyCode: Int, now: Long): Boolean {
        val owner = ownership()
        val boundary = holdBoundary()
        val live = keyHoldCode == keyCode && keyHoldOwnership == owner && keyHoldBoundary == boundary && now - keyHoldLastAt < PAD_WAKE_LATCH_MS
        if (!live) {
            keyHoldCode = keyCode
            keyHoldDownAt = now
            keyHoldOwnership = owner
            keyHoldBoundary = boundary
        }
        keyHoldLastAt = now
        return now - keyHoldDownAt < PAD_HOLD_ACTIVITY_CAP_MS
    }

    /**
     * Whether one motion sample earns activity credit, from its [entered] direction (the axes at or past the dead zone, which is what "held" is) and [sustained] one (the axes still
     * deflected at the cap's lower release magnitude). The hold's direction is the entered axes plus those axes of the hold before it that are still sustained: an axis hovering between
     * the two magnitudes stays in the same hold, and only one that falls below the lower magnitude, or a different direction, is a change. A direction of none ends the hold; a new
     * direction begins one; a sample earns only if it is held itself and its hold is under the cap.
     */
    private fun motionSampleEarnsCredit(entered: Int, sustained: Int, now: Long, owner: Long, boundary: Long): Boolean {
        val before = if (owner == motionHoldOwnership && boundary == motionHoldBoundary) motionHoldDirection else 0
        val enteredAxes = (entered and PositiveBits) or ((entered and NegativeBits) ushr 1)
        val direction = entered or (before and sustained and (enteredAxes or (enteredAxes shl 1)).inv())
        if (direction == 0) {
            motionHoldDirection = 0
            return false
        }
        if (direction != before) {
            motionHoldDirection = direction
            motionHoldSince = now
            motionHoldOwnership = owner
            motionHoldBoundary = boundary
        }
        return entered != 0 && now - motionHoldSince < PAD_HOLD_ACTIVITY_CAP_MS
    }

    /** One key event: [down] and [up] are its action, [keyCode] its key. True when the gate or [route] consumed it. */
    fun key(down: Boolean, up: Boolean, keyCode: Int, route: () -> Boolean): Boolean {
        val now = clock()
        dropStaleLatches(now)
        if (up && keyHoldCode == keyCode) keyHoldCode = null
        // A key press, a repeat or another key's included, is activity whether it is then eaten or not: a held wake key keeps the screen awake (until its hold is capped). Its answer
        // matters only when no wake press is latched (a latched press means the screen is awake already).
        val woke = down && keyDownEarnsCredit(keyCode, now) && interaction()
        val latched = wakeKeyCode
        if (latched != null) {
            // The wake press is not over: everything is eaten. Its own repeats keep it alive and its release ends it (and resets nothing); another key's events neither refresh nor end it.
            if (keyCode == latched) {
                if (up) wakeKeyCode = null else keyLatchedAt = now
            }
            return true
        }
        if (woke) {
            wakeKeyCode = keyCode
            keyLatchedAt = now
            keyOwnership = ownership()
            onWake()
            return true
        }
        return route()
    }

    /** One motion event whose samples only know whether the input is held: every held one is the same direction (see [IdleWakeSamples.ofHeld]). */
    fun motion(samples: BooleanArray, route: (permitted: BooleanArray?) -> Boolean): Boolean = motion(IdleWakeSamples.ofHeld(samples), route)

    /**
     * One motion event as its [samples] (oldest first, the current one last; see [idleWakeSamples]), each whether the input is held. They are walked in order: while the wake
     * press is latched a held sample is eaten and a rest sample releases it (the release resets nothing), and a held sample after a rest is a press like any other; with
     * nothing latched a rest goes to the lane and a held sample is activity (or the wake press). The idle controller hears of a held event once, as Main's lane always did, whether
     * its held samples are then eaten or not (a later held sample in the same event finds it already re-armed); an event that is all rest never reaches it, and neither does a sample
     * whose hold is capped ([PAD_HOLD_ACTIVITY_CAP_MS]: the first sample that still earns credit is the one it hears of). [route] is asked once, if any sample was the lane's, with
     * WHICH ones (null when all of them): a sample the gate ate is never replayed to the lane, so a batch that is a wake press then a rest then a press moves the lane once. True
     * when anything was consumed.
     */
    fun motion(samples: IdleWakeSamples, route: (permitted: BooleanArray?) -> Boolean): Boolean {
        val now = clock()
        dropStaleLatches(now)
        val owner = ownership()
        val boundary = holdBoundary()
        val permitted = BooleanArray(samples.size)
        var consumed = false
        var interactionNoted = false
        for (index in 0 until samples.size) {
            val held = samples[index]
            val earnsCredit = motionSampleEarnsCredit(samples.direction(index), samples.sustained(index), now, owner, boundary)
            // The first sample of the event that earns credit is the press the idle controller hears of, eaten or not. Its answer matters only for a sample that is not already the wake press's.
            var woke = false
            if (earnsCredit && !interactionNoted) {
                interactionNoted = true
                woke = interaction()
            }
            if (wakeMotionLatched) {
                consumed = true
                if (held) motionLatchedAt = now else wakeMotionLatched = false
            } else if (woke) {
                wakeMotionLatched = true
                motionLatchedAt = now
                motionOwnership = ownership()
                consumed = true
                onWake()
            } else {
                permitted[index] = true
            }
        }
        if (permitted.none { it }) return consumed
        return route(if (permitted.all { it }) null else permitted) || consumed
    }
}
