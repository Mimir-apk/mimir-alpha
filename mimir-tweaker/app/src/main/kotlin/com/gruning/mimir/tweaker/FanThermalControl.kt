package com.gruning.mimir.tweaker

import java.util.ArrayDeque
import kotlin.math.floor
import kotlin.math.roundToInt

internal fun minimumDutyForPercent(period: Int, percent: Int): Int {
    require(period in FanPwmBounds.PERIOD_RANGE)
    require(percent in 0..FanPwmBounds.MAX_PERCENT)
    if (percent == 0) return 0
    return ((period.toLong() * percent.toLong() + 99L) / 100L).toInt()
}

/** Fixed bounds for the vendor PWM interface. Period and tach remain read-only. */
internal object FanPwmBounds {
    val PERIOD_RANGE: IntRange = 1_000..1_000_000
    // the 20% minimum-spin floor was scaffolding for power-draw
    // testing, never thermal policy. Retiring it is NOT limited to balanced/performance: quiet
    // never overrode FanThermalProfile.minSpinPercent (see FanThermalProfiles below), so quiet's
    // own controller floor was also 20 before this ruling and drops to 0 here too. That changes
    // quiet's own snap-to-floor, hold-at-target, and drift-reassert duty (e.g. its steady-state
    // "at target" duty moves from 20% to a genuine 0). This floor retirement left curve demand
    // untouched for every profile at the time; only the manual-handoff floor (AndroidFanDutyPort.kt)
    // and the curve knees were already identical to quiet -- the controller floor was not. (The
    // curve knees themselves were separately retuned by the 2026-08-08 low-temp ramp below --
    // see POWER_SAVING_BASELINE's kdoc -- which is unrelated to this floor ruling.)
    const val MIN_SPIN_PERCENT = 0
    const val MAX_PERCENT = 100
}

internal enum class PowerEnvelopeIntent {
    AT_OR_BELOW,
    ABOVE_REFERENCE,
}

/**
 * Product thermal intent for one fixed profile.
 *
 * [calibrationPowerReferenceMw] records the calibration measurement target. It is
 * deliberately not a hardware promise: the current static CPU/GPU plan has no closed-loop
 * watt controller and therefore cannot enforce these figures.
 */
internal data class FanThermalProfile(
    val profileId: String,
    val targetTempC: Int,
    val calibrationPowerReferenceMw: Int,
    val powerEnvelopeIntent: PowerEnvelopeIntent,
    val riseSlewPercentPerSecond: Int,
    val fallSlewPercentPerSecond: Int,
    val minSpinPercent: Int = FanPwmBounds.MIN_SPIN_PERCENT,
    val fullFanTempC: Int = FULL_FAN_TEMP_C,
    val startBelowTargetC: Int = 2,
    val stopBelowTargetC: Int = 6,
    val kp: Double = DEFAULT_PROPORTIONAL_GAIN,
    val ki: Double = DEFAULT_INTEGRAL_GAIN,
) {
    init {
        require(profileId in setOf("quiet", "balanced", "performance"))
        require(targetTempC in 40 until fullFanTempC)
        require(calibrationPowerReferenceMw > 0)
        require(riseSlewPercentPerSecond in 1..50)
        require(fallSlewPercentPerSecond in 1..50)
        require(minSpinPercent in 0 until FanPwmBounds.MAX_PERCENT)
        require(startBelowTargetC in 0 until stopBelowTargetC)
        require(kp > 0.0 && ki >= 0.0)
    }

    companion object {
        /**
         * Loop gains used by profiles that do not set their own, in fan-percent per degree
         * (proportional) and fan-percent per degree-second (integral). These are the tuning
         * values the existing tests pin. keep them unchanged for the
         * closed beta, and retune them afterwards from measurements taken on the Odin.
         */
        const val DEFAULT_PROPORTIONAL_GAIN = 4.0
        const val DEFAULT_INTEGRAL_GAIN = 0.15

        /**
         * Below the SUPERVISOR's CPU/GPU trip (`SupervisorPolicy.cpuTripCExclusive` /
         * `gpuTripCExclusive`), leaving one full-fan intervention band before a thermal signal
         * fires. Corrected 2026-08-08: the old wording named "Guardian's >95 C restore trip"
         * with no pointer, which reads as the Guardian PROCESS -- that process carries no
         * temperature threshold at all and restores only on ownership, integrity and liveness
         * (causes: OWNER_CANCELLED, OWNER_DIED, HEARTBEAT_REJECTED, WATCHDOG_EXPIRED,
         * PROCESS_RECOVERY). The 95 C was WAVE1C's SupervisorPolicy trip, superseded by the
         * WAVE2 policy to 105 C, so the band here is 21 C rather than the 11 C implied.
         *
         * OWED: 84 itself has no in-tree policy re-anchoring it after WAVE2. It remains
         * functionally coherent -- package-temp emergency and the curve's endpoint, 1 C under the
         * 85 C GPU witness -- but its documented rationale was the superseded 95, and
         * no replacement rationale has been established. Do not assert one.
         */
        const val FULL_FAN_TEMP_C = 84
    }
}

internal object FanThermalProfiles {
    private val profiles = mapOf(
        "quiet" to FanThermalProfile(
            profileId = "quiet",
            targetTempC = 55,
            calibrationPowerReferenceMw = 8_000,
            powerEnvelopeIntent = PowerEnvelopeIntent.AT_OR_BELOW,
            riseSlewPercentPerSecond = 12,
            fallSlewPercentPerSecond = 8,
        ),
        "balanced" to FanThermalProfile(
            profileId = "balanced",
            targetTempC = 65,
            calibrationPowerReferenceMw = 11_250,
            powerEnvelopeIntent = PowerEnvelopeIntent.AT_OR_BELOW,
            riseSlewPercentPerSecond = 16,
            fallSlewPercentPerSecond = 8,
        ),
        "performance" to FanThermalProfile(
            profileId = "performance",
            // Begin the aggressive Max response before the independent 85 C GPU override. The
            // product ceiling remains 80 C; this lower controller target absorbs actuation lag.
            targetTempC = 70,
            calibrationPowerReferenceMw = 14_000,
            powerEnvelopeIntent = PowerEnvelopeIntent.ABOVE_REFERENCE,
            riseSlewPercentPerSecond = 35,
            fallSlewPercentPerSecond = 12,
            minSpinPercent = FanPwmBounds.MIN_SPIN_PERCENT,
            kp = 8.0,
            ki = 0.25,
        ),
    )

    /**
     * * every fan-bearing RP/AYN device now drives the vendor's own SMART loop rather than a
     * Mimir-owned PI duty controller, and R3 explicitly forbids polling -- the closed loop stays in
     * the vendor's controller, so [SmartFanThermalController] never ticks for these devices at all
     * (`MimirTweakerService.fanControlDecision`'s own `contract.fanMode == FAN_SMART` branch resets
     * the controller and returns before this table is ever consulted for control purposes). What
     * survives this table, for a SMART-driven device, is the SPECIFICATION the vendor curve is
     * configured/compared against -- `targetTempC` and friends describe the intended thermal
     * behaviour Mimir asks the vendor's own SMART curve to approximate, never live controller
     * inputs to a loop Mimir runs itself.
     *
     * Previously gated to `deviceProfileId == CpuCeilingTransport.ODIN_3_PROFILE` only, which meant
     * a fan-bearing non-Odin session (the Nova, once wired) got `null` here and its caller's
     * `?: return active.fanRestore("FAN_PROFILE_UNAVAILABLE")` rolled the apply back to Stock for
     * the mere absence of a table row -- on exactly the device this rewrite is for. Widened to
     * every profile id this table defines, independent of device: the Odin's own SPORT-owned
     * session (still duty-driven this wave, TR-08 territory) is the one case where this table's
     * values genuinely ARE live controller inputs, and it is unaffected by the widening -- it
     * always resolved a row here and still does.
     */
    fun forAppliedProfile(
        profileId: String,
        @Suppress("UNUSED_PARAMETER") deviceProfileId: String,
    ): FanThermalProfile? = profiles[profileId]
}

internal data class FanControlSample(
    /**
     * The CPU-side witness for the full-fan safety intervention: **the hottest classified CPU
     * zone**, not the user-facing package mean.
     *
     * Renamed from `packageTempC` on 2026-08-23. `HardwareCapabilities`' own KDoc has always said
     * it: *"[cpuPackageTempC] ... a MEAN of classified CPU zones ... Safety continues to use hottest
     * [cpuTempC]."* The service was nonetheless feeding the MEAN into this safety path, and the
     * field's name is what made that read as correct at every call site.
     *
     * Two consequences of using the mean, both closed by this rename plus the feed change:
     *  - **It under-reports.** A mean across CPU zones sits below the hottest core by construction,
     *    so a full-fan intervention keyed to it fires late -- on the reference Odin, CPU0/CPU1
     *    measured 89.9C while a mean over more zones is materially lower.
     *  - **It is family-scoped to one chassis.** `cpuPackageTempC` is only populated for the
     *    Snapdragon-8-Elite family, so on every other RP/AYN device it is null and the controller
     *    fails safe on its first tick -- which is exactly why manual fan was still unavailable on
     *    the Nova after nine blockers moved.
     */
    val cpuSafetyTempC: Int?,
    val hottestCpuTempC: Int?,
    val gpuTempC: Int?,
    val vendorFanMode: Int?,
    val duty: Int?,
    val period: Int?,
    val rpm: Int?,
)

/** Cooler is negative, Quieter is positive; zero preserves the authored curve. */
@JvmInline
internal value class FanCurveBias(val value: Int) {
    init { require(value in -100..100) }
}

internal data class FanCurveKnee(
    val tempC: Int,
    val percent: Int,
) {
    init {
        require(tempC in 20..FanThermalProfile.FULL_FAN_TEMP_C)
        require(percent in 0..FanPwmBounds.MAX_PERCENT)
    }
}

/** Immutable monotonic fan curve. UI drag operations replace one bounded knee. */
internal data class FanCurve(
    val knees: List<FanCurveKnee>,
) {
    init {
        require(knees.size in 3..8)
        require(knees.zipWithNext().all { (left, right) ->
            left.tempC < right.tempC && left.percent <= right.percent
        })
        require(knees.last().percent == FanPwmBounds.MAX_PERCENT)
        require(knees.last().tempC <= FanThermalProfile.FULL_FAN_TEMP_C)
    }

    fun percentAt(tempC: Double, bias: FanCurveBias = FanCurveBias(0)): Int {
        // A full-scale bias shifts the authored curve by eight degrees without changing
        // its monotonic shape: Cooler evaluates hotter; Quieter evaluates cooler.
        val effectiveTempC = tempC - (bias.value * MAX_BIAS_SHIFT_C / 100.0)
        val lower = knees.lastOrNull { it.tempC <= effectiveTempC }
        val upper = knees.firstOrNull { it.tempC >= effectiveTempC }
        if (lower == null) return knees.first().percent
        if (upper == null) return knees.last().percent
        if (lower.tempC == upper.tempC) return lower.percent
        val fraction = (effectiveTempC - lower.tempC) / (upper.tempC - lower.tempC)
        return (lower.percent + fraction * (upper.percent - lower.percent))
            .roundToInt()
            .coerceIn(0, FanPwmBounds.MAX_PERCENT)
    }

    fun movedKnee(index: Int, tempC: Int, percent: Int): FanCurve {
        require(index in 1 until knees.lastIndex) { "Only interior fan-curve knees are draggable" }
        val prior = knees[index - 1]
        val next = knees[index + 1]
        val moved = FanCurveKnee(
            tempC = tempC.coerceIn(prior.tempC + 1, next.tempC - 1),
            percent = percent.coerceIn(prior.percent, next.percent),
        )
        return copy(knees = knees.toMutableList().also { it[index] = moved })
    }

    private companion object {
        const val MAX_BIAS_SHIFT_C = 8
    }
}

/**
 * What the VENDOR's own SMART controller was seen to do, learned by watching it.
 *
 * The manual fan curve needs to limit sustained heat; its duty may be too low for that load.
 * Measured on the Odin 3, same device, same moment, vendor against
 * Mimir: **30 C -> 28% vs 11%; 33 C -> 29% vs ~14.5%.** Manual was commanding about half the duty
 * the vendor's own controller had chosen, and [FanCurveDefaults.POWER_SAVING_BASELINE]'s shelf
 * (48 C -> 62 C gains six points across fourteen degrees) sits exactly where a sustained emulation
 * load lives.
 *
 * **Why learned rather than authored.** Authoring a vendor curve per chassis would mean
 * reverse-engineering `com.odin.settings` for the Odin and something else again for the Retroid
 * Nova, and it would be stale the first time a vendor OTA retuned it. Mimir is already watching the
 * vendor drive this fan every tick it spends in Auto -- `fanMode` 4 is the shipped default on all
 * three profiles -- so the honest source for "what would SMART have done here" is SMART itself.
 *
 * The staircase is monotone from below by construction: [percentAt] answers with the highest duty
 * ever seen at or BELOW the queried temperature, so it can never claim the vendor would run slower
 * when hotter.
 */
internal data class VendorSmartFanFloor(
    /** Whole-degree bucket -> the highest duty percent the vendor was seen using at that bucket. */
    val observedPercentByTempC: Map<Int, Int> = emptyMap(),
) {
    init {
        require(observedPercentByTempC.values.all { it in 0..FanPwmBounds.MAX_PERCENT })
    }

    /** Fold one vendor observation in, keeping the hottest response seen for that degree. */
    fun observing(tempC: Double, percent: Int): VendorSmartFanFloor {
        if (!tempC.isFinite()) return this
        val bucket = floor(tempC).toInt()
        val clamped = percent.coerceIn(0, FanPwmBounds.MAX_PERCENT)
        if ((observedPercentByTempC[bucket] ?: -1) >= clamped) return this
        return copy(observedPercentByTempC = observedPercentByTempC + (bucket to clamped))
    }

    /**
     * The floor at [tempC].
     *
     * Below the coldest observation, and with nothing observed at all, this is 0 -- a device that
     * has never run Auto gets exactly today's behaviour rather than a number Mimir invented.
     *
     * **Above the hottest observation it ramps linearly to 100% at [FanThermalProfile.FULL_FAN_TEMP_C].**
     * The first Manual session on a cold device has seen nothing near 75 C, and guessing the
     * vendor's shape up there would be inventing the very data this type exists to avoid inventing.
     * Ramping hard instead is deliberate: [[stock-revert-is-never-the-thermal-safeguard]] --
     * ambiguous branches fail toward cooling. Over-cooling costs noise; under-cooling costs the
     * clock derate reported at 75-78 C, or the hardware.
     */
    fun percentAt(tempC: Double): Int {
        if (observedPercentByTempC.isEmpty() || !tempC.isFinite()) return 0
        val hottestSeen = observedPercentByTempC.keys.max()
        val atOrBelow = observedPercentByTempC.entries
            .filter { it.key <= tempC }
            .maxOfOrNull { it.value }
            ?: return 0
        if (tempC <= hottestSeen) return atOrBelow
        val ceiling = FanThermalProfile.FULL_FAN_TEMP_C.toDouble()
        if (tempC >= ceiling || hottestSeen >= ceiling) return FanPwmBounds.MAX_PERCENT
        val span = ceiling - hottestSeen
        val progress = ((tempC - hottestSeen) / span).coerceIn(0.0, 1.0)
        return (atOrBelow + progress * (FanPwmBounds.MAX_PERCENT - atOrBelow))
            .roundToInt()
            .coerceIn(atOrBelow, FanPwmBounds.MAX_PERCENT)
    }
}

internal object FanCurveDefaults {
    /**
     * below 20 C (the lowest legal knee temperature) the curve is flat
     * at 0%. From 20 C the curve ramps gently and continuously -- an incremental percent per
     * degree, not a stop/start step -- reaching ~14.5% (rounds to 15%) by 33 C and 32% by 48 C.
     * This is a normalize-temp-delta / earlier-thermal-balance-point change, not a reintroduction
     * of a minimum-spin floor: FanPwmBounds.MIN_SPIN_PERCENT stays 0, and the fan can still be
     * commanded to a genuine 0% at or below 20 C. The top three knees -- (62 C, 38%), (72 C, 60%),
     * (79 C, 80%) -- are unchanged and remain device-validated.
     */
    val POWER_SAVING_BASELINE = FanCurve(
        listOf(
            FanCurveKnee(20, 0),
            FanCurveKnee(30, 11),
            FanCurveKnee(48, 32),
            FanCurveKnee(62, 38),
            FanCurveKnee(72, 60),
            FanCurveKnee(79, 80),
            FanCurveKnee(FanThermalProfile.FULL_FAN_TEMP_C, 100),
        ),
    )

    /**
     * Legacy name retained for source compatibility: Balanced/Max share the exact same authored
     * knees as Power Saving/quiet (value-equal, MIN_SPIN_PERCENT == 0 makes the old
     * coerceAtLeast(MIN_SPIN_PERCENT) mapping a no-op -- policy 2026-08-08). There is no
     * longer a 0% low-temp region above 20 C: the curve ramps from (20 C, 0%) through the
     * low-temp knees described on POWER_SAVING_BASELINE up to the unchanged, device-validated
     * (62 C, 38%), (72 C, 60%), (79 C, 80%) knees.
     */
    val SHARED_BASELINE = POWER_SAVING_BASELINE

    /** Legacy name retained for source compatibility; Power Saving behavior is unchanged. */
    val SMART_BASELINE = POWER_SAVING_BASELINE

    fun forProfile(profileId: String): FanCurve = when (profileId) {
        "balanced", "performance" -> SHARED_BASELINE
        else -> POWER_SAVING_BASELINE
    }

    /**
     * How hard this profile drives the shared curve.
     *
     * Previously profiles
     * did not scale it by anything: [forProfile] hands every profile the same curve object, and the
     * Manual path reads ONLY `curve.percentAt(temp, bias)` -- the per-profile `targetTempC`
     * (quiet 55, balanced 65, performance 70) belongs to the PID path and is never consulted there.
     * So the profile had no effect on the fan in Manual at all, on any device, ever.
     *
     * Ordered by how much heat the profile's own CPU/GPU ceilings licence: `performance` asks the
     * most of the same curve because it generates the most to remove.
     *
     * **These three numbers are provisional tuning values.** They ship as defaults so the mechanism
     * is testable and adjustable by ear on the device; final calibration remains pending.
     */
    fun scaleForProfile(profileId: String): Double = when (profileId) {
        "performance" -> 1.30
        "balanced" -> 1.15
        else -> 1.00
    }
}

internal sealed interface FanThermalStrategy {
    data object HoldTarget : FanThermalStrategy
    data class AutoCurve(
        val curve: FanCurve = FanCurveDefaults.SMART_BASELINE,
        val bias: FanCurveBias = FanCurveBias(0),
    ) : FanThermalStrategy
}

internal data class FanCalibrationObservation(val percent: Int, val rpm: Int)

internal data class LearnedFanRange(
    val minimumFunctionalPercent: Int,
    val maximumObservedRpm: Int,
)

/** Pure result reducer for a guarded 0→100% sweep; it never performs a hardware write. */
internal object FanAutoCalibration {
    val SWEEP_PERCENTAGES: List<Int> = (0..100 step 5).toList()

    fun learn(observations: List<FanCalibrationObservation>): LearnedFanRange? {
        if (observations.map { it.percent } != SWEEP_PERCENTAGES ||
            observations.any { it.rpm < 0 }
        ) return null
        val maxRpm = observations.maxOfOrNull { it.rpm }?.takeIf { it > 0 } ?: return null
        val functionalThreshold = maxOf(MIN_FUNCTIONAL_RPM, (maxRpm * 5) / 100)
        val minimum = observations.firstOrNull { it.percent > 0 && it.rpm >= functionalThreshold }
            ?: return null
        return LearnedFanRange(minimum.percent, maxRpm)
    }

    private const val MIN_FUNCTIONAL_RPM = 200
}

internal sealed interface FanControlDecision {
    data object Hold : FanControlDecision

    data class WriteDuty(
        val percent: Int,
        val duty: Int,
        val period: Int,
        val reason: String,
    ) : FanControlDecision

    data class FailSafe(val reason: String) : FanControlDecision
}

/**
 * Epoch-bound PI temperature controller for an already-authenticated Mimir-owned fan session
 * (vendor mode SPORT or MANUAL; see [TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES]). It never writes
 * `fan_mode`; transaction/Guardian boundaries alone own vendor mode.
 *
 * Hold-target mode regulates the package temperature with an incremental (velocity-form)
 * proportional-integral loop whose output is always kept inside the profile's duty limits; the
 * result is then rate-limited before it reaches the fan. Targets, ownership, deadman and
 * Guardian semantics are Mimir's own. The idea of temperature-driven manual PWM fan control is
 * credited, as a courtesy, to keiretrogaming/PULSE; see NOTICE.md.
 *
 * Behaviour change, by the earlier loop bounded
 * its stored integral, which held the quiet and balanced profiles near 65% however long the error
 * lasted. The velocity form keeps no integral store, so there is nothing to bound: under a
 * sustained error the output now climbs on to 100% for every profile. No cap is reinstated on
 * purpose; the test `sustained error on quiet reaches full fan` pins this.
 */
internal class SmartFanThermalController {
    /**
     * What the vendor's own SMART controller was seen doing, accumulated while it owned the fan.
     * Read on the Manual path as a floor; see [VendorSmartFanFloor] for the ruling and the device
     * measurement behind it. Survives [reset] deliberately -- it describes the CHASSIS, not the
     * session, and throwing it away on every profile change would blind the very first Manual entry
     * after one, which is exactly when it is needed.
     */
    @Volatile
    private var vendorFloor: VendorSmartFanFloor = VendorSmartFanFloor()
    private var activeProfileId: String? = null
    private var lastTickMs: Long? = null
    private var filteredTempC: Double? = null

    // Memory of the hold-target loop: the last output it settled on (already inside its limits)
    // and the temperature error it saw on that update. Null means "no history"; the next update
    // then starts from the duty floor with a zero error.
    private var lastLoopOutputPercent: Double? = null
    private var lastLoopErrorC: Double? = null
    private var expectedDuty: Int? = null
    private var pendingDuty: Int? = null
    // a duty readback
    // mismatch must never permanently surrender the fan. This deque replaces the old two-strike
    // `pendingMismatchCount` counter that fed a terminal `FailSafe`. It tracks the CPU-awake uptime
    // of each reassert WRITE attempt (not each mismatched sample) in a rolling window, mirroring
    // the existing shape at the fan_mode layer -- HardwareSessionSupervisor's
    // `admitFanReassert` / `SupervisorPolicy.fanReassertWindowMs` (S4.3) -- which this class cannot
    // reference directly (out of this lane's bounded scope, and it governs a different axis:
    // vendor `fan_mode` Settings drift, not the PWM duty node). See [admitDutyReassert].
    private val reassertAttempts: ArrayDeque<Long> = ArrayDeque()

    /**
     * Fold one observation of the VENDOR driving its own fan into the learned floor. Call only when
     * telemetry shows the vendor owns duty (`vendorFanMode == FAN_SMART`) and the sample is
     * complete -- an observation taken while Mimir is driving would teach the floor Mimir's own
     * output and make it a self-fulfilling mirror rather than a floor.
     */
    @Synchronized
    fun observeVendorDuty(tempC: Double?, dutyPercent: Int?) {
        val temp = tempC ?: return
        val percent = dutyPercent ?: return
        vendorFloor = vendorFloor.observing(temp, percent)
    }

    /** Test/diagnostic read of the learned floor. */
    @Synchronized
    fun learnedVendorFloor(): VendorSmartFanFloor = vendorFloor

    @Synchronized
    fun reset() {
        activeProfileId = null
        lastTickMs = null
        filteredTempC = null
        clearLoopMemory()
        expectedDuty = null
        pendingDuty = null
        creditableAttemptMs = null
        reassertAttempts.clear()
    }

    /** All tick timestamps share uptimeMillis; suspend must not age controller progress. */
    @Synchronized
    fun tick(
        profile: FanThermalProfile,
        sample: FanControlSample,
        nowElapsedRealtimeMs: Long,
        strategy: FanThermalStrategy = FanThermalStrategy.HoldTarget,
    ): FanControlDecision {
        // Device-measured (see TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES): the vendor's duty loop
        // only fights SMART. A missing reading fails closed exactly as it always has; SPORT and
        // MANUAL both proceed -- MANUAL stays acceptable so ownership via mode 6 is never a
        // one-way door.
        if (sample.vendorFanMode == null || sample.vendorFanMode == TuningPlanner.FAN_SMART) {
            return FanControlDecision.FailSafe("FAN_OWNERSHIP_LOST")
        }
        val period = sample.period
            ?.takeIf { it in FanPwmBounds.PERIOD_RANGE }
            ?: return FanControlDecision.FailSafe("FAN_PERIOD_UNAVAILABLE")
        val duty = sample.duty
            ?.takeIf { it in 0..period }
            ?: return FanControlDecision.FailSafe("FAN_DUTY_UNAVAILABLE")
        val cpuSafetyTemp = sample.cpuSafetyTempC
            ?.takeIf { it in TelemetryRanges.CPU_MIN_C..TelemetryRanges.CPU_MAX_C }
            ?: return FanControlDecision.FailSafe("FAN_CPU_SAFETY_TEMP_UNAVAILABLE")
        val gpuTemp = sample.gpuTempC
            ?.takeIf { it in TelemetryRanges.GPU_MIN_C..TelemetryRanges.GPU_MAX_C }
        // The raw GPU intervention must outrank an older pending PWM write. Guardian still owns
        // full restoration; this local band merely prevents one reconciliation tick from delaying
        // full fan while a valid GPU witness is already hot.
        val gpuEmergency = gpuTemp?.let { it >= GPU_WITNESS_FULL_FAN_C } == true
        val cpuSafetyEmergency = cpuSafetyTemp >= profile.fullFanTempC
        val safetyFull = cpuSafetyEmergency || gpuEmergency
        // The Mimir curve follows the hottest validated GPU zone. Package
        // temperature remains independently authoritative for the 84 C full-fan intervention,
        // but it no longer biases ordinary curve demand. A missing GPU witness fails closed
        // instead of silently changing the curve's sensor authority.
        val thermalDemandTemp = when (strategy) {
            FanThermalStrategy.HoldTarget -> maxOf(cpuSafetyTemp, gpuTemp ?: cpuSafetyTemp)
            is FanThermalStrategy.AutoCurve -> gpuTemp
                ?: return FanControlDecision.FailSafe("FAN_GPU_TEMP_UNAVAILABLE")
        }
        if (activeProfileId != profile.profileId) {
            resetForProfile(profile.profileId, nowElapsedRealtimeMs, thermalDemandTemp, duty)
        }
        val priorTick = lastTickMs
        if (priorTick != null && nowElapsedRealtimeMs < priorTick) {
            return FanControlDecision.FailSafe("FAN_CLOCK_REGRESSION")
        }
        val elapsedMs = priorTick?.let { nowElapsedRealtimeMs - it } ?: DEFAULT_TICK_MS
        if (elapsedMs > MAX_CONTROL_GAP_MS) {
            return FanControlDecision.FailSafe("FAN_CONTROLLER_STALLED")
        }
        lastTickMs = nowElapsedRealtimeMs

        if (!safetyFull) {
            pendingDuty?.let { expected ->
                if (duty == expected) {
                    pendingDuty = null
                    expectedDuty = duty
                    creditConfirmedReassert()
                } else {
                    // Still drifted since our last reassert write. Keep [pendingDuty] set (do NOT
                    // clear it) so a genuine confirmation on a later tick still lands normally, and
                    // so a later admitted retry re-targets the SAME expected value rather than
                    // adopting the drifted one. Budget exhaustion degrades to a bounded, recoverable
                    // backoff -- never the old terminal FailSafe.
                    return admitDutyReassert(expected, period, nowElapsedRealtimeMs)
                        ?: FanControlDecision.FailSafe("FAN_DUTY_REASSERT_BACKOFF")
                }
            }
        }

        val filtered = filteredTempC?.let { previous ->
            previous + FILTER_ALPHA * (thermalDemandTemp - previous)
        } ?: thermalDemandTemp.toDouble()
        filteredTempC = filtered
        val currentPercent = dutyToPercent(duty, period)
        // Odin's exposed tach is not a reliable runtime witness: it can report zero while
        // manual PWM is audibly and physically active. Mode, period, duty readback, package
        // temperature and the independent thermal trips remain authoritative. Tach remains
        // available to the explicit auto-calibration sweep, where a zero range rejects the
        // calibration instead of restoring an otherwise healthy runtime session.
        // The explicit GPU threshold remains an unslewed intervention band. Guardian still owns
        // restoration if the bounded controller cannot keep either independent witness safe.
        if (!safetyFull) {
            expectedDuty?.takeIf { it != duty }?.let { expected ->
                val decision = admitDutyReassert(expected, period, nowElapsedRealtimeMs)
                if (decision != null) {
                    pendingDuty = expected
                    return decision
                }
                // Budget exhausted before we could even start tracking this mismatch: leave
                // [pendingDuty] null so the very next tick re-evaluates this same fresh drift
                // against the (by-then-rolled-forward) window, rather than pinning a half-started
                // verification cycle.
                return FanControlDecision.FailSafe("FAN_DUTY_REASSERT_BACKOFF")
            }
        }
        val desiredPercent = if (safetyFull) {
            // Thermal trip: go straight to full and forget the loop's history so recovery
            // starts from a clean slate.
            clearLoopMemory()
            FanPwmBounds.MAX_PERCENT
        } else {
            when (strategy) {
                FanThermalStrategy.HoldTarget -> holdTargetPercent(
                    profile,
                    filtered,
                    currentPercent,
                    elapsedMs,
                )
                is FanThermalStrategy.AutoCurve -> {
                    // The curve owns the output, so the hold-target loop has nothing to remember.
                    clearLoopMemory()
                    //  The scale is applied BEFORE the floor so a profile can drive the curve
                    // above the vendor but never below it, and the floor wins outright -- which
                    // means a Quieter bias can no longer take the fan under the vendor's own
                    // behaviour. That is what "floor it" costs, it is real, and it is the side the
                    // ruling chose: manual fan exists so a toasty device can be tuned COOLER.
                    val scaled = strategy.curve.percentAt(filtered, strategy.bias) *
                        FanCurveDefaults.scaleForProfile(profile.profileId)
                    maxOf(scaled.roundToInt(), vendorFloor.percentAt(filtered))
                        .coerceIn(0, FanPwmBounds.MAX_PERCENT)
                }
            }
        }
        val settledPercent = if (safetyFull) {
            FanPwmBounds.MAX_PERCENT
        } else {
            rampToward(
                from = currentPercent,
                goal = desiredPercent,
                profile = profile,
                elapsedMs = elapsedMs,
            )
        }
        val targetDuty = percentToDuty(settledPercent, period)
        if (targetDuty == duty) {
            expectedDuty = duty
            pendingDuty = null
            return FanControlDecision.Hold
        }
        expectedDuty = targetDuty
        pendingDuty = targetDuty
        return writeDutyDecision(
            duty = targetDuty,
            period = period,
            reason = when {
                gpuEmergency -> "FAN_GPU_THERMAL_OVERRIDE"
                cpuSafetyEmergency -> "FAN_THERMAL_OVERRIDE"
                else -> "FAN_TARGET_TEMP"
            },
        )
    }

    private fun resetForProfile(profileId: String, nowMs: Long, cpuSafetyTempC: Int, duty: Int) {
        activeProfileId = profileId
        lastTickMs = nowMs
        filteredTempC = cpuSafetyTempC.toDouble()
        clearLoopMemory()
        expectedDuty = duty
        pendingDuty = null
        reassertAttempts.clear()
    }

    private fun clearLoopMemory() {
        lastLoopOutputPercent = null
        lastLoopErrorC = null
    }

    /**
     * Whether the hold-target loop should be driving the fan at [tempC]. The fan joins the loop
     * once the temperature comes within [FanThermalProfile.startBelowTargetC] of the target and,
     * having joined, stays with it until the temperature falls more than
     * [FanThermalProfile.stopBelowTargetC] below the target; the wider leave margin keeps the
     * fan from chattering around the join point.
     */
    private fun loopEngaged(profile: FanThermalProfile, tempC: Double, fanPercent: Int): Boolean {
        val marginC = if (fanPercent > 0) profile.stopBelowTargetC else profile.startBelowTargetC
        return tempC >= profile.targetTempC - marginC
    }

    /**
     * One update of the hold-target loop, in incremental (velocity) form.
     *
     * The new output is the previous output plus `kp * (error - previousError) + ki * error * dt`,
     * clamped to `[profile.minSpinPercent, 100]`. Only the clamped value is remembered, so a loop
     * sitting at a limit carries no extra history: while the error keeps pushing into the limit the
     * output simply stays there, and as soon as the error turns around the very next update moves
     * it off the limit. The integral term still removes steady-state error because a non-zero
     * error always keeps nudging the output until the temperature reaches the target.
     *
     * With no history the loop starts from the duty floor and a zero error, which is also what
     * every reset and thermal trip leaves behind. Returns the desired fan percent before rate
     * limiting.
     */
    private fun holdTargetPercent(
        profile: FanThermalProfile,
        tempC: Double,
        fanPercent: Int,
        elapsedMs: Long,
    ): Int {
        if (!loopEngaged(profile, tempC, fanPercent)) {
            clearLoopMemory()
            return 0
        }
        val lowerLimitPercent = profile.minSpinPercent.toDouble()
        val errorC = tempC - profile.targetTempC
        val stepSeconds = (elapsedMs / 1_000.0).coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
        val startFrom = lastLoopOutputPercent ?: lowerLimitPercent
        val errorChange = errorC - (lastLoopErrorC ?: 0.0)
        val settled = (startFrom + profile.kp * errorChange + profile.ki * errorC * stepSeconds)
            .coerceIn(lowerLimitPercent, FanPwmBounds.MAX_PERCENT.toDouble())
        lastLoopOutputPercent = settled
        lastLoopErrorC = errorC
        return settled.roundToInt()
    }

    /**
     * Moves the fan from [from] toward [goal] at no more than the profile's rise or fall rate for
     * the [elapsedMs] since the last update. A move that has to happen is never smaller than one
     * percent, and a move never carries past the goal.
     */
    private fun rampToward(from: Int, goal: Int, profile: FanThermalProfile, elapsedMs: Long): Int {
        if (from == goal) return goal
        val rising = goal > from
        val ratePerSecond = if (rising) {
            profile.riseSlewPercentPerSecond
        } else {
            profile.fallSlewPercentPerSecond
        }
        val allowedChange = maxOf(1, (ratePerSecond * elapsedMs / 1_000.0).roundToInt())
        val moved = if (rising) minOf(goal, from + allowedChange) else maxOf(goal, from - allowedChange)
        // A profile with a stall band never lingers inside it: a fan starting from below the
        // minimum spin jumps straight to it, and a fan heading for a full stop drops to zero once
        // the next step would land under it. Every current profile has a minimum spin of 0, which
        // makes both cases inert.
        val minSpin = profile.minSpinPercent
        return when {
            rising && from < minSpin && goal >= minSpin -> minSpin
            goal == 0 && moved < minSpin -> 0
            else -> moved
        }.coerceIn(0, FanPwmBounds.MAX_PERCENT)
    }

    private fun dutyToPercent(duty: Int, period: Int): Int =
        ((duty.toLong() * 100L) / period.toLong()).toInt().coerceIn(0, 100)

    private fun percentToDuty(percent: Int, period: Int): Int =
        ((period.toLong() * percent.coerceIn(0, 100)) / 100L).toInt()

    private fun writeDutyDecision(duty: Int, period: Int, reason: String) =
        FanControlDecision.WriteDuty(
            percent = dutyToPercent(duty, period),
            duty = duty,
            period = period,
            reason = reason,
        )

    /**
     * admits or withholds
     * one reassert WRITE of [expected] against a rolling window/burst budget, shaped exactly like
     * [HardwareSessionSupervisor.admitFanReassert]'s S4.3 fan_mode-layer mechanism (same window/
     * burst sizes, same "degrade honestly instead of escalating" posture) -- this is the duty-layer
     * sibling of that mechanism, kept local to the duty controller rather than coupled
     * to `HardwareSessionSupervisor.kt`.
     *
     * Returns the write to issue, or `null` if the budget is exhausted for THIS tick only. `null`
     * is never terminal: [reassertAttempts] is a rolling window, so it always has room again once
     * its oldest entry ages past [DUTY_REASSERT_WINDOW_MS] or out of the current
     * [DUTY_REASSERT_BURST_WINDOW_MS] -- the caller does not need to call [reset] to recover, and
     * must not.
     */
    /**
     * The attempt currently awaiting confirmation, so a repair that WORKS can be credited back.
     *
     * POLICY 2026-08-13, after a picker-flow capture on the Odin: count events, not attempts.
     */
    private var creditableAttemptMs: Long? = null

    /**
     * Give the budget back for a reassert the hardware confirmed.
     *
     * WHY: the burst cap is 2 per 5 s, and it was written for a controller THRASHING -- writing the
     * same duty over and over while the fan refuses to take it. It was never meant to ration repairs
     * that succeed. Device-measured on the Odin: six separate vendor excursions in fourteen
     * seconds while hopping to the folder picker, the first two repaired in ~800 ms, and then the
     * budget was spent -- so the sixth sat at 50% for **ten seconds** with the controller forbidden
     * from touching it.
     *
     * So a confirmed repair costs nothing lasting, and an unresolved excursion still does: retries
     * against a fan that will not take the value stay charged, which is exactly the thrash the cap
     * exists to stop. The 1 Hz tick independently bounds this to at most one write per second.
     */
    private fun creditConfirmedReassert() {
        val admittedAtMs = creditableAttemptMs ?: return
        creditableAttemptMs = null
        // java.util.ArrayDeque removes by VALUE, and the value is a distinct elapsed-realtime stamp.
        reassertAttempts.remove(admittedAtMs)
    }

    private fun admitDutyReassert(
        expected: Int,
        period: Int,
        nowElapsedRealtimeMs: Long,
    ): FanControlDecision.WriteDuty? {
        val windowCutoff = nowElapsedRealtimeMs - DUTY_REASSERT_WINDOW_MS
        while (reassertAttempts.isNotEmpty() && reassertAttempts.first() <= windowCutoff) {
            reassertAttempts.removeFirst()
        }
        val burstCutoff = nowElapsedRealtimeMs - DUTY_REASSERT_BURST_WINDOW_MS
        val burstAttempts = reassertAttempts.count { it > burstCutoff }
        if (reassertAttempts.size >= MAX_DUTY_REASSERT_ATTEMPTS_PER_WINDOW ||
            burstAttempts >= MAX_DUTY_REASSERT_ATTEMPTS_PER_BURST
        ) {
            return null
        }
        reassertAttempts.addLast(nowElapsedRealtimeMs)
        creditableAttemptMs = nowElapsedRealtimeMs
        return writeDutyDecision(expected, period, "FAN_DUTY_REASSERT")
    }

    internal companion object {
        const val DEFAULT_TICK_MS = 1_000L
        // Exact Odin evidence: one warm monitor sample is ~0.7 s and one checked vendor
        // PWM actuation can add ~2.5 s. Six seconds bounds two missed control opportunities
        // without treating a confirmed, slewed write as controller death.
        const val MAX_CONTROL_GAP_MS = 6_000L
        // replaces the old
        // MAX_READBACK_MISMATCHES = 2 two-strike terminal latch (FAN_DUTY_READBACK_MISMATCH,
        // permanent until reset()). A readback mismatch now gets a bounded, recurring reassert
        // budget instead of a one-shot allowance -- values match HardwareSessionSupervisor's
        // SupervisorPolicy.fanReassertWindowMs/maxFanReassertAttemptsPerWindow (S4.3), the
        // existing shape for the sibling fan_mode-layer mechanism this class cannot
        // reach directly (out of this lane's bounded scope). See [admitDutyReassert].
        const val DUTY_REASSERT_WINDOW_MS = 30_000L
        const val MAX_DUTY_REASSERT_ATTEMPTS_PER_WINDOW = 4
        const val DUTY_REASSERT_BURST_WINDOW_MS = 5_000L
        const val MAX_DUTY_REASSERT_ATTEMPTS_PER_BURST = 2
        // An absolute 85C temperature at which the fan must already be full -- not an offset from any trip. This is intentionally an emergency
        // full-fan threshold, not a second temperature target or curve authority.
        //
        // Corrected 2026-08-08. The old wording said "ten degrees below Guardian's exclusive
        // 95 C GPU restore trip". It named the right CONSTANT -- it pointed at
        // SupervisorPolicy.gpuTripCExclusive, and "guardian" is used loosely across this codebase
        // for the guardian SUPERVISOR (cf. GuardianDerateObservability.kt) rather than the
        // Guardian process -- but the VALUE was stale: WAVE2's policy raised that trip
        // 95 -> 105. The margin is therefore 20 C today, not 10 C, which is strictly more
        // conservative since the fan commits earlier relative to the trip. (Distinct from the
        // Guardian PROCESS, which has no temperature threshold at all.)
        //
        // Pinned by `GPU witness full fan threshold stays at least 10 C below the supervisor's
        // exclusive GPU trip` in FanThermalControlTest, which deliberately asserts the MARGIN
        // rather than equality: exact equality would drag this constant up to 95 on the next
        // recalibration and silently make the fan LAZIER than the fixed emergency threshold.
        const val GPU_WITNESS_FULL_FAN_C = 85
        const val FILTER_ALPHA = 0.35
        const val MIN_DT_SECONDS = 0.25
        const val MAX_DT_SECONDS = 1.5
    }
}
