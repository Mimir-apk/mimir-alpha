package com.gruning.mimir.tweaker

import java.util.ArrayDeque

/**
 * A hot device could remain uncooled after restore. Recovery evidence showed the fan was not
 * physically stuck: charging and reapplying a profile re-commanded it and it spun. The real defect is
 * that once [HardwareSessionSupervisor] restores to Stock (for ANY reason -- see
 * `HardwareSessionSupervisor.kt`'s many [SupervisorDecision.Restore] reasons), the whole
 * `MimirTweakerService` monitor executor is cancelled (`stopMonitor()`) and NOTHING watches the
 * device again until a fresh profile apply. If the vendor's own SMART duty-regulation loop then
 * fails to spin the fan up on its own -- device-observed on the Odin, mechanism not fully
 * explained from this tree's source alone (the vendor loop is closed-source) -- the device sits
 * hot and uncooled with no owner at all, until the player notices and re-applies a profile by
 * hand.
 *
 * This class is that missing owner, scoped to post-control thermal recovery:
 *
 * - **Session-independent by construction.** There is no [HardwareSessionSupervisor] session left
 *   to be bound to by the time this class's caller would ever consult it (`stopMonitor()` has
 *   already run) -- so this is deliberately NOT a mode of that class. It is a second, small, pure
 *   decision function [MimirTweakerService] consults on its own reduced-footprint timer once the
 *   ordinary monitor has stopped, mirroring [SmartFanThermalController]'s own shape (ports in,
 *   pure decision out, JVM-testable, no Android dependency).
 * - **Read-mostly.** [evaluate] takes only already-sampled values; it performs no I/O itself.
 * - **Its first actuation is a bounded, rate-limited request to re-issue the exact fan_mode(+duty)
 *   write shape already established as safe** by
 *   `PServerHardwareCommandPort.restoreCapturedFanMode`'s duty-owned branch (force full PWM in
 *   the same checked shell transaction as the fan_mode write) -- see
 *   `PServerHardwareCommandPort.forceCoolingSmartReassert`'s own kdoc for why
 *   [restoreCapturedFanMode] itself cannot be reused unmodified here (its own same-value skip
 *   would elide exactly this call: by definition this watch only ever fires once the device is
 *   ALREADY sitting at vendor Smart).
 * - **Hold full fan until cool.** Observed on Odin,
 *   2026-10-14 03:19 to 12:06: an orphaned wine process kept the prime cores at 101-103 C with no
 *   session, the vendor at Smart drove duty 23500/50000, and every [ForceCooling] write was pulled
 *   back to that curve by the vendor's own loop within seconds -- 4,199 forces and 14,686
 *   budget-exhausted ticks, the fan pulsing 100% <-> 47% about eight times a minute. A force that
 *   does not stick (duty below full again inside [windowMs] of a confirmed force, the device still
 *   at or above emergency) now escalates ONCE to [EnterHold]: the fan is taken as Mimir MANUAL
 *   ([TuningPlanner.FAN_MANUAL]) at the full period -- the one mode the vendor's loop does not
 *   fight (`TweakerVendorFanModes`, device-measured 87f02042) and the one the Tweaker's own
 *   sessions already drive duty under -- and held there until the device reads cool with
 *   hysteresis for the same debounce the disarm uses, then handed back to vendor Smart through the
 *   same checked full-PWM write a captured restore uses. Writes stay bounded by the one budget
 *   below: one force, one entry, at most [maxHoldCorrections] corrective re-writes if the held
 *   duty drifts, one release. A hold that is lost (live mode no longer MANUAL), that will not
 *   hold (drift bound reached) or that could not be entered (an unconfirmed entry) counts
 *   against [maxHoldReentriesPerWindow]; once that is spent the watch is **left to the vendor**
 *    for the rest of the window: no force and no hold, vendor Smart
 *   runs the fan, one [PostControlThermalDecision.LeftToVendor] line and then silence until the
 *   window ends. A cool release never counts: a device that reheats is held again.
 * - **The cool exit follows the channels witnessed at entry.** A hold is entered on one hot
 *   channel; it releases once every channel that was PRESENT at entry reads cool with hysteresis
 *   for the debounce. A channel absent at entry never gates the exit (it would otherwise keep a
 *   device held forever on ignorance), and a channel that was present and then reads null for
 *   [HOLD_TELEMETRY_LOST_TICKS] consecutive ticks releases to Smart as `telemetry_lost` -- unless
 *   another witnessed channel still reads at or above its emergency: a hold with one eye open
 *   stays, its streak running, until that channel is below emergency too. A `telemetry_lost`
 *   release counts toward the re-entry cap, so a flapping sensor cannot pulse the fan through it.
 *   Ignorance still never counts toward "cool", and the disarm still demands complete telemetry.
 * - **Never writes duty directly under Smart, and never leaves a hold silently.** Every write
 *   pairs duty with the mode in the shapes the command port already owns. The caller
 *   ([PostControlHoldController]) records a marker before the entry write and clears it only
 *   after a confirmed release, so a daemon that dies mid-hold releases it on its next start.
 * - **Unconditionally cooling-positive.** Every actuation forces duty to the full period -- the
 *   single safest fan state -- regardless of whether it also revives a stalled vendor regulation
 *   loop.
 * - **Self-disarming once confirmed cool, never while holding.** This watch arms after EVERY
 *   genuine Restore, not only thermal ones -- most are ordinary session ends (the player closed
 *   the game), not heat events. [PostControlThermalEvaluation.shouldDisarm] tells
 *   [MimirTweakerService] when to stop the timer, so a normal cool device is not polled forever.
 *   While a hold is live the answer is false by construction: stopping the timer with the fan
 *   parked at MANUAL would be exactly an orphaned manual-fan hold.
 */
internal sealed interface PostControlThermalDecision {
    data object Idle : PostControlThermalDecision

    /** Hot, vendor-Smart, and duty is not already full -- but the re-kick budget is exhausted
     * this tick (or a hold may not be re-entered yet). Always still worth surfacing: this is
     * exactly the silent-abandonment condition the policy is about, whether or not this
     * tick can also act on it. */
    data class Escalate(val reason: String) : PostControlThermalDecision

    /** Hot, vendor-Smart, duty is not already full, and the bounded budget admits a re-kick this
     * tick. Always also escalated by the caller -- see [MimirTweakerService]'s wiring. */
    data class ForceCooling(val reason: String) : PostControlThermalDecision

    /** A confirmed force did not stick: take the fan as MANUAL at the full period and hold it. */
    data class EnterHold(val reason: String) : PostControlThermalDecision

    /** The hold is in place; nothing is written this tick. Never logged per tick. */
    data object Holding : PostControlThermalDecision

    /** The held duty drifted below full under MANUAL: re-write the full period (bounded). */
    data class CorrectHold(val reason: String) : PostControlThermalDecision

    /** Hand the fan back to vendor Smart at full PWM: [REASON_COOL] after the debounced cool
     * window, [REASON_DRIFT] once the correction bound is spent, [REASON_TELEMETRY_LOST] once a
     * channel witnessed at entry has been unreadable for the bounded streak. */
    data class ReleaseHold(val reason: String) : PostControlThermalDecision {
        companion object {
            const val REASON_COOL = "cool"
            const val REASON_DRIFT = "drift"
            const val REASON_TELEMETRY_LOST = "telemetry_lost"
        }
    }

    /** The re-entry cap is spent: nothing fires for [remainingMs] (policy 2026-10-14, "leave it
     * to the vendor"). Answered once per lockout; the ticks that follow read [Idle]. */
    data class LeftToVendor(val reason: String, val remainingMs: Long) : PostControlThermalDecision

    /** The live mode is no longer MANUAL -- something else owns the fan. Dropped without a write. */
    data class HoldLost(val liveMode: Int) : PostControlThermalDecision
}

/** Mirrors [FanControlSample] but this class only reads the subset it needs; kept separate so a
 * caller cannot accidentally satisfy this contract with a stale, session-bound sample instance. */
internal data class PostControlThermalSample(
    /**
     * Hottest classified CPU zone. Renamed from `packageTempC` on 2026-08-23 alongside
     * [FanControlSample.cpuSafetyTempC], and for the same reason: this is a SAFETY witness, and
     * `HardwareCapabilities` reserves safety to hottest `cpuTempC` rather than the user-facing
     * package mean. The service was feeding it `cpuPackageTempC`, which is populated only for the
     * Snapdragon-8-Elite family -- so on the Nova and the RP5 this recovery path had **no CPU-side
     * emergency at all** and could only ever fire on the GPU witness. Found while closing the same
     * defect one class over; both are corrected together because they are one mistake.
     */
    val cpuSafetyTempC: Int?,
    val gpuTempC: Int?,
    val vendorFanMode: Int?,
    val duty: Int?,
    val period: Int?,
)

internal fun interface PostControlClock {
    fun elapsedRealtime(): Long
}

/**
 * [evaluate]'s full result: the ordinary actuation decision, plus whether THIS watch instance has
 * now seen enough consecutive confirmed-safe ticks that [MimirTweakerService] should disarm it
 * (stop the timer, let the service settle terminally like any other idle period). Bundled into one
 * return so the disarm signal can never drift out of sync with the decision it was computed
 * alongside -- see [PostControlThermalWatch.evaluate]'s own kdoc for what "confirmed safe" means
 * and why missing/incomplete telemetry is never eligible.
 */
internal data class PostControlThermalEvaluation(
    val decision: PostControlThermalDecision,
    val shouldDisarm: Boolean,
)

/**
 * Pure, JVM-testable, stateful only in its own bounded write budget, its hold phase, and its
 * consecutive-safe / consecutive-cool debounce counters (the budget mirrors
 * [HardwareSessionSupervisor.admitTargetRepair] / [SmartFanThermalController.admitDutyReassert]'s
 * existing window/burst shape exactly, same numbers, so this class introduces no new
 * tuning surface for it). One instance is owned by [MimirTweakerService] for the lifetime of the
 * service process; [reset] clears all of it when a fresh session starts and this watch's whole
 * reason for running goes away.
 *
 * State moves only on what the caller confirms through [acknowledge]: an unconfirmed entry leaves
 * the watch watching, an unconfirmed release leaves it holding. The decision and the hardware are
 * never allowed to disagree about which phase the watch is in.
 */
internal class PostControlThermalWatch(
    private val clock: PostControlClock,
    private val windowMs: Long = WINDOW_MS,
    private val maxAttemptsPerWindow: Int = MAX_ATTEMPTS_PER_WINDOW,
    private val burstWindowMs: Long = BURST_WINDOW_MS,
    private val maxAttemptsPerBurst: Int = MAX_ATTEMPTS_PER_BURST,
    private val disarmDebounceTicks: Int = DISARM_DEBOUNCE_TICKS,
    private val maxHoldCorrections: Int = MAX_HOLD_CORRECTIONS,
    private val holdReentryWindowMs: Long = HOLD_REENTRY_WINDOW_MS,
    private val maxHoldReentriesPerWindow: Int = MAX_HOLD_REENTRIES_PER_WINDOW,
    private val holdExitHysteresisC: Int = HOLD_EXIT_HYSTERESIS_C,
    private val holdTelemetryLostTicks: Int = HOLD_TELEMETRY_LOST_TICKS,
) {
    private enum class Phase { WATCHING, HOLDING }

    private val attempts = ArrayDeque<Long>()
    private var consecutiveConfirmedSafe = 0
    private var phase = Phase.WATCHING
    private var lastConfirmedForceMs: Long? = null
    private var holdEnteredMs: Long? = null
    private var holdCorrections = 0
    private var consecutiveCoolUnderHold = 0
    private val holdReentries = ArrayDeque<Long>()
    private var lockoutAnnounced = false
    /** The channels present in the sample the pending / confirmed entry was decided on. */
    private var pendingWitness: HoldWitness? = null
    private var holdWitness: HoldWitness = HoldWitness(cpu = true, gpu = true)
    private var telemetryLostStreak = 0

    private data class HoldWitness(val cpu: Boolean, val gpu: Boolean)

    @Synchronized
    fun reset() {
        attempts.clear()
        consecutiveConfirmedSafe = 0
        phase = Phase.WATCHING
        lastConfirmedForceMs = null
        holdEnteredMs = null
        holdCorrections = 0
        consecutiveCoolUnderHold = 0
        holdReentries.clear()
        lockoutAnnounced = false
        pendingWitness = null
        holdWitness = HoldWitness(cpu = true, gpu = true)
        telemetryLostStreak = 0
    }

    /** True between a confirmed [PostControlThermalDecision.EnterHold] and its confirmed release or loss. */
    @Synchronized
    fun isHolding(): Boolean = phase == Phase.HOLDING

    /** Milliseconds the current hold has been live, or null when not holding. */
    @Synchronized
    fun heldMs(): Long? = holdEnteredMs?.let { clock.elapsedRealtime() - it }

    /**
     * [sample] must be freshly read this tick -- this class performs no I/O and trusts the
     * caller's freshness exactly as every other port in this codebase does.
     *
     * "Confirmed safe" (the [PostControlThermalEvaluation.shouldDisarm] debounce input) requires
     * COMPLETE, valid telemetry -- every channel present and in range, vendor mode read as exactly
     * Smart -- showing EITHER duty already at full (the fan is actively responding; vendor Smart
     * or something else is doing its job) OR both temperature channels genuinely below their
     * emergency bar. Missing/incomplete telemetry, or a vendor mode that is not exactly Smart, is
     * NEVER eligible: ignorance must never count toward "confirmed cool," or this watch would
     * disarm itself out of the exact incident it exists to catch. A single non-eligible tick resets
     * the counter to zero -- this is a debounce over CONSECUTIVE good ticks, not a cumulative one.
     * While holding, the answer is false regardless of the sample.
     */
    @Synchronized
    fun evaluate(sample: PostControlThermalSample): PostControlThermalEvaluation {
        val decision = when (phase) {
            Phase.WATCHING -> watchingDecision(sample)
            Phase.HOLDING -> holdingDecision(sample)
        }
        val confirmedSafe = phase == Phase.WATCHING &&
            decision == PostControlThermalDecision.Idle && isConfirmedSafe(sample)
        consecutiveConfirmedSafe = if (confirmedSafe) consecutiveConfirmedSafe + 1 else 0
        return PostControlThermalEvaluation(
            decision = decision,
            shouldDisarm = phase == Phase.WATCHING && consecutiveConfirmedSafe >= disarmDebounceTicks,
        )
    }

    /**
     * What the caller did with the last decision. Only a confirmed write moves the phase; an
     * attempted correction is counted whether or not it confirmed, so the bound is on attempts.
     */
    @Synchronized
    fun acknowledge(decision: PostControlThermalDecision, confirmed: Boolean) {
        val now = clock.elapsedRealtime()
        when (decision) {
            is PostControlThermalDecision.ForceCooling -> if (confirmed) lastConfirmedForceMs = now
            is PostControlThermalDecision.EnterHold -> if (confirmed) {
                phase = Phase.HOLDING
                holdEnteredMs = now
                holdCorrections = 0
                consecutiveCoolUnderHold = 0
                consecutiveConfirmedSafe = 0
                lastConfirmedForceMs = null
                holdWitness = pendingWitness ?: HoldWitness(cpu = true, gpu = true)
                telemetryLostStreak = 0
            } else {
                // An entry that did not land is a hold that will not hold: it counts toward the
                // cap exactly like a lost one, so a chassis that refuses the entry cannot be
                // asked twice a minute for ten minutes.
                holdReentries.addLast(now)
            }
            is PostControlThermalDecision.CorrectHold -> holdCorrections += 1
            is PostControlThermalDecision.ReleaseHold -> if (confirmed) {
                if (decision.reason == PostControlThermalDecision.ReleaseHold.REASON_DRIFT ||
                    decision.reason == PostControlThermalDecision.ReleaseHold.REASON_TELEMETRY_LOST
                ) {
                    holdReentries.addLast(now)
                }
                leaveHold()
            }
            else -> Unit
        }
    }

    /**
     * The caller released the hold outside a tick (a session is taking the fan, or a stale hold
     * from an earlier process life was released on start). Never counts against re-entry.
     */
    @Synchronized
    fun holdReleasedExternally() {
        leaveHold()
    }

    private fun leaveHold() {
        phase = Phase.WATCHING
        holdEnteredMs = null
        holdCorrections = 0
        consecutiveCoolUnderHold = 0
        lastConfirmedForceMs = null
        telemetryLostStreak = 0
    }

    private fun watchingDecision(sample: PostControlThermalSample): PostControlThermalDecision {
        // Only vendor Smart is this watch's business. Mimir owning the fan (SPORT/MANUAL) means a
        // real session exists after all -- this watch should not even be running then (the
        // service disarms it on a fresh session, see MimirTweakerService's wiring) -- and any
        // other value is not a state this class knows how to interpret safely.
        if (sample.vendorFanMode != TuningPlanner.FAN_SMART) return PostControlThermalDecision.Idle

        val period = sample.period?.takeIf { it > 0 } ?: return PostControlThermalDecision.Idle
        val duty = sample.duty?.takeIf { it in 0..period } ?: return PostControlThermalDecision.Idle
        // Already at (or essentially at) full -- vendor Smart is doing its job, or already
        // reached the one state this watch would otherwise force. Nothing to do.
        if (dutyPercentOf(duty, period) >= FULL_DUTY_PERCENT) return PostControlThermalDecision.Idle

        val cpuHot = sample.cpuSafetyTempC?.let { it >= PACKAGE_EMERGENCY_C } == true
        val gpuHot = sample.gpuTempC?.let { it >= GPU_EMERGENCY_C } == true
        if (!cpuHot && !gpuHot) return PostControlThermalDecision.Idle

        val reason = when {
            gpuHot -> "POST_CONTROL_GPU_HOT_FAN_UNRESPONSIVE"
            else -> "POST_CONTROL_PACKAGE_HOT_FAN_UNRESPONSIVE"
        }
        val now = clock.elapsedRealtime()
        // with the re-entry cap spent, nothing
        // fires -- not the force either -- until the window ends. Said once, then silent.
        val lockedOutUntil = holdReentryLockedOutUntil(now)
        if (lockedOutUntil != null) {
            if (lockoutAnnounced) return PostControlThermalDecision.Idle
            lockoutAnnounced = true
            return PostControlThermalDecision.LeftToVendor(reason, remainingMs = lockedOutUntil - now)
        }
        lockoutAnnounced = false
        // A confirmed force inside the window and the duty is below full again while still hot:
        // the vendor's loop pulled it back. That is the observed Odin case, and the answer is a
        // hold, not another force.
        val forceDidNotStick = lastConfirmedForceMs?.let { now - it <= windowMs } == true
        if (forceDidNotStick) {
            if (!admitWrite(now)) return PostControlThermalDecision.Escalate(reason)
            pendingWitness = HoldWitness(cpu = sample.cpuSafetyTempC != null, gpu = sample.gpuTempC != null)
            return PostControlThermalDecision.EnterHold(reason)
        }
        if (!admitWrite(now)) return PostControlThermalDecision.Escalate(reason)
        return PostControlThermalDecision.ForceCooling(reason)
    }

    private fun holdingDecision(sample: PostControlThermalSample): PostControlThermalDecision {
        val mode = sample.vendorFanMode
        if (mode == null) {
            // Ignorance never acts and never counts toward cool.
            consecutiveCoolUnderHold = 0
            return PostControlThermalDecision.Holding
        }
        val now = clock.elapsedRealtime()
        if (mode != TuningPlanner.FAN_MANUAL) {
            holdReentries.addLast(now)
            leaveHold()
            return PostControlThermalDecision.HoldLost(mode)
        }
        val witnessedLost = (holdWitness.cpu && sample.cpuSafetyTempC == null) ||
            (holdWitness.gpu && sample.gpuTempC == null)
        telemetryLostStreak = if (witnessedLost) telemetryLostStreak + 1 else 0
        val witnessedHot = (holdWitness.cpu && sample.cpuSafetyTempC?.let { it >= PACKAGE_EMERGENCY_C } == true) ||
            (holdWitness.gpu && sample.gpuTempC?.let { it >= GPU_EMERGENCY_C } == true)
        if (telemetryLostStreak >= holdTelemetryLostTicks && !witnessedHot) {
            return if (admitWrite(now)) {
                PostControlThermalDecision.ReleaseHold(PostControlThermalDecision.ReleaseHold.REASON_TELEMETRY_LOST)
            } else {
                PostControlThermalDecision.Holding
            }
        }
        consecutiveCoolUnderHold = if (isCoolUnderHold(sample)) consecutiveCoolUnderHold + 1 else 0
        if (consecutiveCoolUnderHold >= disarmDebounceTicks) {
            return if (admitWrite(now)) {
                PostControlThermalDecision.ReleaseHold(PostControlThermalDecision.ReleaseHold.REASON_COOL)
            } else {
                PostControlThermalDecision.Holding
            }
        }
        val period = sample.period?.takeIf { it > 0 } ?: return PostControlThermalDecision.Holding
        val duty = sample.duty?.takeIf { it in 0..period } ?: return PostControlThermalDecision.Holding
        if (dutyPercentOf(duty, period) >= FULL_DUTY_PERCENT) return PostControlThermalDecision.Holding
        // The held duty drifted. Correct it within the bound; past the bound the hold is not
        // holding and the vendor's own regulation is the safe fallback.
        if (holdCorrections >= maxHoldCorrections) {
            return if (admitWrite(now)) {
                PostControlThermalDecision.ReleaseHold(PostControlThermalDecision.ReleaseHold.REASON_DRIFT)
            } else {
                PostControlThermalDecision.Holding
            }
        }
        if (!admitWrite(now)) return PostControlThermalDecision.Holding
        return PostControlThermalDecision.CorrectHold("POST_CONTROL_HOLD_DUTY_DRIFT")
    }

    /** The one write budget, shared by every actuation: window and burst, same numbers as the siblings. */
    private fun admitWrite(now: Long): Boolean {
        val cutoff = now - windowMs
        while (attempts.isNotEmpty() && attempts.first() <= cutoff) attempts.removeFirst()
        val burstCutoff = now - burstWindowMs
        val burstAttempts = attempts.count { it > burstCutoff }
        if (attempts.size >= maxAttemptsPerWindow || burstAttempts >= maxAttemptsPerBurst) return false
        attempts.addLast(now)
        return true
    }

    /** When the lockout ends, or null when the cap is not spent. */
    private fun holdReentryLockedOutUntil(now: Long): Long? {
        val cutoff = now - holdReentryWindowMs
        while (holdReentries.isNotEmpty() && holdReentries.first() <= cutoff) holdReentries.removeFirst()
        if (holdReentries.size < maxHoldReentriesPerWindow) return null
        return holdReentries.first() + holdReentryWindowMs
    }

    /**
     * Every channel witnessed at entry present and below its emergency bar by the hysteresis;
     * never on ignorance, and never gated by a channel the entry could not see.
     */
    private fun isCoolUnderHold(sample: PostControlThermalSample): Boolean {
        val cpuCool = if (holdWitness.cpu) {
            (sample.cpuSafetyTempC ?: return false) < PACKAGE_EMERGENCY_C - holdExitHysteresisC
        } else {
            true
        }
        val gpuCool = if (holdWitness.gpu) {
            (sample.gpuTempC ?: return false) < GPU_EMERGENCY_C - holdExitHysteresisC
        } else {
            true
        }
        return cpuCool && gpuCool
    }

    private fun isConfirmedSafe(sample: PostControlThermalSample): Boolean {
        if (sample.vendorFanMode != TuningPlanner.FAN_SMART) return false
        val period = sample.period?.takeIf { it > 0 } ?: return false
        val duty = sample.duty?.takeIf { it in 0..period } ?: return false
        val cpuSafetyTempC = sample.cpuSafetyTempC ?: return false
        val gpuTempC = sample.gpuTempC ?: return false
        val dutyFull = dutyPercentOf(duty, period) >= FULL_DUTY_PERCENT
        val cool = cpuSafetyTempC < PACKAGE_EMERGENCY_C && gpuTempC < GPU_EMERGENCY_C
        return dutyFull || cool
    }

    private fun dutyPercentOf(duty: Int, period: Int): Int =
        ((duty.toLong() * 100L) / period.toLong()).toInt()

    internal companion object {
        // Same shape, same numbers, as HardwareSessionSupervisor.admitTargetRepair /
        // SmartFanThermalController.admitDutyReassert -- not independently tuned; see this
        // class's own kdoc for why matching the existing, existing budget is deliberate.
        const val WINDOW_MS = 30_000L
        const val MAX_ATTEMPTS_PER_WINDOW = 4
        const val BURST_WINDOW_MS = 5_000L
        const val MAX_ATTEMPTS_PER_BURST = 2

        const val FULL_DUTY_PERCENT = 100

        /**
         * Reuses [FanThermalProfile.FULL_FAN_TEMP_C] and
         * [SmartFanThermalController.GPU_WITNESS_FULL_FAN_C] verbatim -- both are already the
         * profile-independent absolute
         * emergency thresholds an ACTIVE session enforces (`FanThermalControl.tick()`'s own
         * `packageEmergency`/`gpuEmergency`). This class enforces the exact same bar for the one
         * case an active session cannot: when there is no session left to enforce it.
         */
        const val PACKAGE_EMERGENCY_C = FanThermalProfile.FULL_FAN_TEMP_C
        const val GPU_EMERGENCY_C = SmartFanThermalController.GPU_WITNESS_FULL_FAN_C

        /**
         * At the ~1 Hz cadence [MimirTweakerService] schedules this watch on, five consecutive
         * confirmed-safe ticks is ~5s -- quick enough that a normal, cool session end (the common
         * case: this watch is armed after every genuine Restore, most of which are ordinary,
         * non-emergency session ends, not heat events) does not poll the device far longer than
         * needed, slow enough not to flap disarmed/armed on one noisy reading. Not device-tuned;
         * flagged as such, same posture as this file's other borrowed-not-measured constants.
         * The hold's cool exit uses the same count over consecutive complete cool ticks.
         */
        const val DISARM_DEBOUNCE_TICKS = 5

        /**
         * A hold releases only once BOTH channels sit this far below their emergency bar (84 -> 79 C
         * package, 85 -> 80 C GPU): a release at the bar itself would hand a device at 83.9 C to
         * the vendor's curve and be back at the bar within the minute. NOT device-tuned -- chosen
         * so the vendor's own Smart curve is already well into its ramp at the exit point; Odin and Thor device measurements are needed before retuning it.
         */
        const val HOLD_EXIT_HYSTERESIS_C = 5

        /** Corrective re-writes of the held duty before the hold is judged not to hold. */
        const val MAX_HOLD_CORRECTIONS = 2

        /**
         * A hold that was lost (live mode no longer MANUAL), released for drift, or refused at
         * entry may be re-entered this many times per [HOLD_REENTRY_WINDOW_MS]; past that the
         * watch is left to the vendor for the rest of the window . A cool
         * release never counts: a device that genuinely reheats is held again.
         */
        const val HOLD_REENTRY_WINDOW_MS = 10 * 60_000L
        const val MAX_HOLD_REENTRIES_PER_WINDOW = 2

        /**
         * A channel present at entry that reads null for this many consecutive ~1 Hz ticks (about a
         * minute) releases the hold as `telemetry_lost`: long enough to ride out a transient read
         * failure, short enough that a blind hold does not run for the whole heat event.
         */
        const val HOLD_TELEMETRY_LOST_TICKS = 60
    }
}
