package com.gruning.mimir.tweaker

import kotlin.math.roundToInt

/**
 * Structurally cooler profiles may re-arm while hot (fan-fence fix F5).
 *
 * On the Odin the Guardian handed Balanced back to Stock on a false stale verdict (F1); the vendor's
 * Performance floors (4.2 GHz) came back with it, the chip went to 103 C, and the one moment a
 * cooler profile was needed the re-arm was refused: `TELEMETRY_ADMISSION_FAILED
 * androidThermalStatus=3 cpuTempC=103`. The refusal left the device exactly as hot as it found it.
 *
 * So while Android's thermal status is at or past the trip, admission may accept a profile that is
 * **strictly cooler than the live state** -- no axis hotter, at least one axis lower (fence review
 * 2026-10-14: an identical plan, or Stock itself, would otherwise yield a working hold for nothing)
 * -- and nothing else:
 *
 *  - every planned CPU ceiling is at or below the live effective ceiling of its policy;
 *  - the planned GPU ceiling is at or below the live one (a slower-or-equal `max_pwrlevel` index,
 *    or a lower-or-equal devfreq policy in Hz when that transport governs);
 *  - the planned GPU floor never sits above the live floor (the renderer preserves or lowers the
 *    captured floor; only a profile-supplied floor could raise it, and it is compared here);
 *  - the planned vendor performance mode never sits above the live one. The Tweaker writes no CPU
 *    floor itself (`AndroidTransactionPorts.plannedMutations` refuses `scaling_min_freq`); the
 *    floors move only with the vendor mode, whose rows are ordered Standard < Performance < High
 *    performance (`TuningPlanner.VENDOR_MODE_*`). There is no typed per-mode floor table to compare
 *    kHz against, so the mode order is the structural proxy, and a plan that leaves the mode alone
 *    is "equal" on this axis;
 *  - the fan: a plan that leaves the fan to the vendor (SMART, or no fan write at all) is "equal"
 *    ONLY when the vendor already runs it (live SMART) -- over a Mimir-owned duty it is a hand-over
 *    to a loop that ran 37-47 % at 103 C on the Odin, never cooler (P1-2); a Mimir-owned plan
 *    (MANUAL) is judged by what its curve, with the bias the loop will run (P3-7), commands at the
 *    live temperature against the live duty.
 *
 * **Fails closed.** A plan that cannot be derived, a live state that cannot be read, or any single
 * axis whose live value is unknown refuses with a bounded token; the caller reports it under the
 * unchanged wire code `TELEMETRY_ADMISSION_FAILED` and the existing CPU/GPU/battery trips still
 * apply afterwards (policy B, 2026-10-14: those stay absolute). Nothing here is a new bridge
 * operation: the plan is the planner's own, the live state is the probe's own full telemetry, both
 * already typed.
 *
 * [fanTransitionRefusal] is the same fan judgement for a fan-mode change inside a session (thermal admission policy): at SEVERE the change is admitted when it does not lower cooling.
 */
internal data class LiveRearmState(
    /** Effective per-policy ceiling (shared QoS or `scaling_max_freq`), as the supervisor reads it. */
    val cpuCeilingKhz: Map<Int, Int>,
    val gpuMaxPowerLevel: Int?,
    /** devfreq policy ceiling in HERTZ when that transport exists on the chassis. */
    val gpuDevfreqMaxHz: Int?,
    val gpuMinPowerLevel: Int?,
    val vendorPerformanceMode: Int?,
    val vendorFanMode: Int?,
    val fanDuty: Int?,
    val fanPeriod: Int?,
    val cpuTempC: Int?,
) {
    companion object {
        fun from(snapshot: TelemetrySnapshot): LiveRearmState = LiveRearmState(
            cpuCeilingKhz = snapshot.cpuQosCeilingKhz,
            gpuMaxPowerLevel = snapshot.gpuMaxPowerLevel,
            gpuDevfreqMaxHz = snapshot.gpuDevfreqMaxHz,
            gpuMinPowerLevel = snapshot.gpuMinPowerLevel,
            vendorPerformanceMode = snapshot.vendorPerformanceMode,
            vendorFanMode = snapshot.vendorFanMode,
            fanDuty = snapshot.fanDuty,
            fanPeriod = snapshot.fanPeriod,
            cpuTempC = snapshot.cpuTempC,
        )
    }
}

internal object CoolerRearmAdmission {
    const val PLAN_UNAVAILABLE = "PLAN_UNAVAILABLE"
    const val LIVE_STATE_UNREADABLE = "LIVE_STATE_UNREADABLE"
    const val NOT_STRICTLY_COOLER = "NOT_STRICTLY_COOLER"
    const val CPU_CEILING_UNREADABLE = "CPU_CEILING_UNREADABLE"
    const val CPU_CEILING_ABOVE_LIVE = "CPU_CEILING_ABOVE_LIVE"
    const val GPU_CEILING_UNREADABLE = "GPU_CEILING_UNREADABLE"
    const val GPU_CEILING_ABOVE_LIVE = "GPU_CEILING_ABOVE_LIVE"
    const val GPU_FLOOR_UNREADABLE = "GPU_FLOOR_UNREADABLE"
    const val GPU_FLOOR_ABOVE_LIVE = "GPU_FLOOR_ABOVE_LIVE"
    const val VENDOR_MODE_UNREADABLE = "VENDOR_MODE_UNREADABLE"
    const val VENDOR_MODE_RAISES_FLOORS = "VENDOR_MODE_RAISES_FLOORS"
    const val FAN_MODE_UNREADABLE = "FAN_MODE_UNREADABLE"
    const val FAN_MODE_NOT_COOLER = "FAN_MODE_NOT_COOLER"
    const val FAN_DUTY_UNREADABLE = "FAN_DUTY_UNREADABLE"
    const val FAN_DUTY_BELOW_LIVE = "FAN_DUTY_BELOW_LIVE"

    /** One axis compared: the plan asks for less heat than the chip runs now, the same, or more. */
    private sealed interface Axis {
        data object Cooler : Axis
        data object Equal : Axis
        data class Hotter(val token: String) : Axis
    }

    /**
     * Null when [plan] is strictly cooler than [live]: no axis hotter or unreadable, at least one
     * lower. Otherwise the first refusal token, in a fixed axis order.
     */
    fun refusal(
        plan: TuningPlan?,
        live: LiveRearmState?,
        capabilities: HardwareCapabilities,
        bias: FanCurveBias = FanCurveBias(0),
    ): String? {
        if (plan == null) return PLAN_UNAVAILABLE
        if (live == null) return LIVE_STATE_UNREADABLE
        var cooler = false
        val axes = listOf(
            { cpuCeiling(plan, live) },
            { gpuCeiling(plan, live, capabilities) },
            { gpuFloor(plan, live, capabilities) },
            { vendorMode(plan, live) },
            { fan(plan.fanMode, plan.profileId, live, bias) },
        )
        for (axis in axes) {
            when (val verdict = axis()) {
                is Axis.Hotter -> return verdict.token
                Axis.Cooler -> cooler = true
                Axis.Equal -> Unit
            }
        }
        return if (cooler) null else NOT_STRICTLY_COOLER
    }

    /**
     * a fan-mode transition inside a live session, while hot, may only
     * raise cooling. Null admits: the fan [targetFanMode] commands, with [profileId]'s curve at
     * [bias], is at least the live duty, or the vendor keeps a fan it already runs. A change that
     * lowers cooling, or a live state that cannot be read, refuses with the axis's own token.
     */
    fun fanTransitionRefusal(
        targetFanMode: Int,
        profileId: String,
        live: LiveRearmState?,
        bias: FanCurveBias,
    ): String? {
        if (live == null) return LIVE_STATE_UNREADABLE
        return when (val verdict = fan(targetFanMode, profileId, live, bias)) {
            is Axis.Hotter -> verdict.token
            Axis.Cooler, Axis.Equal -> null
        }
    }

    private fun cpuCeiling(plan: TuningPlan, live: LiveRearmState): Axis {
        var lower = false
        for ((policyId, targetKhz) in plan.cpuMaxKhz) {
            val liveKhz = live.cpuCeilingKhz[policyId] ?: return Axis.Hotter(CPU_CEILING_UNREADABLE)
            if (targetKhz > liveKhz) return Axis.Hotter(CPU_CEILING_ABOVE_LIVE)
            if (targetKhz < liveKhz) lower = true
        }
        return if (lower) Axis.Cooler else Axis.Equal
    }

    /** True when the devfreq policy node is the ceiling this chassis writes for [target] -- the renderer's own test. */
    private fun devfreqGoverns(capabilities: HardwareCapabilities, target: Int): Boolean =
        capabilities.gpuCeilingPath != null &&
            GpuCeilingTransport.targetHzFor(capabilities.gpuFrequenciesKhz, target) != null

    private fun gpuCeiling(plan: TuningPlan, live: LiveRearmState, capabilities: HardwareCapabilities): Axis {
        val target = plan.gpuMaxPowerLevel ?: return Axis.Equal
        if (devfreqGoverns(capabilities, target)) {
            val targetHz = GpuCeilingTransport.targetHzFor(capabilities.gpuFrequenciesKhz, target)
                ?: return Axis.Hotter(GPU_CEILING_UNREADABLE)
            val liveHz = live.gpuDevfreqMaxHz?.toLong() ?: return Axis.Hotter(GPU_CEILING_UNREADABLE)
            return when {
                targetHz > liveHz -> Axis.Hotter(GPU_CEILING_ABOVE_LIVE)
                targetHz < liveHz -> Axis.Cooler
                else -> Axis.Equal
            }
        }
        // Qualcomm power levels are inverted: a LOWER index is a FASTER ceiling.
        val liveLevel = live.gpuMaxPowerLevel ?: return Axis.Hotter(GPU_CEILING_UNREADABLE)
        return when {
            target < liveLevel -> Axis.Hotter(GPU_CEILING_ABOVE_LIVE)
            target > liveLevel -> Axis.Cooler
            else -> Axis.Equal
        }
    }

    private fun gpuFloor(plan: TuningPlan, live: LiveRearmState, capabilities: HardwareCapabilities): Axis {
        val target = plan.gpuMaxPowerLevel ?: return Axis.Equal
        // Under the devfreq transport the capture owns a different node and no floor is written.
        if (devfreqGoverns(capabilities, target)) return Axis.Equal
        val liveMin = live.gpuMinPowerLevel ?: return Axis.Hotter(GPU_FLOOR_UNREADABLE)
        // The renderer keeps the captured floor when it admits the target and lowers it (to the
        // target, or the slowest level) when it does not -- never raises it. Only a profile-supplied
        // floor can, and it is the one value compared here.
        val slowest = capabilities.gpuFrequenciesKhz.lastIndex.coerceAtLeast(target)
        val profileFloor = TuningPlanner.profileSuppliedGpuMinPowerLevel(
            deviceProfileId = capabilities.deviceProfileId,
            profileId = plan.profileId,
            gpuMaxPowerLevel = target,
            slowestLevel = slowest,
        ) ?: return Axis.Equal
        return when {
            profileFloor < liveMin -> Axis.Hotter(GPU_FLOOR_ABOVE_LIVE)
            profileFloor > liveMin -> Axis.Cooler
            else -> Axis.Equal
        }
    }

    private fun vendorMode(plan: TuningPlan, live: LiveRearmState): Axis {
        val planned = plan.vendorMode ?: return Axis.Equal
        val liveMode = live.vendorPerformanceMode ?: return Axis.Hotter(VENDOR_MODE_UNREADABLE)
        return when {
            planned > liveMode -> Axis.Hotter(VENDOR_MODE_RAISES_FLOORS)
            planned < liveMode -> Axis.Cooler
            else -> Axis.Equal
        }
    }

    private fun fan(planned: Int?, profileId: String, live: LiveRearmState, bias: FanCurveBias): Axis {
        val liveMode = live.vendorFanMode ?: return Axis.Hotter(FAN_MODE_UNREADABLE)
        if (planned == null || planned == TuningPlanner.FAN_SMART) {
            // P1-2: leaving the fan to the vendor is "no change" only when the vendor already runs
            // it. Over a Mimir-owned duty it is a hand-over, never cooler.
            return if (liveMode == TuningPlanner.FAN_SMART) Axis.Equal else Axis.Hotter(FAN_MODE_NOT_COOLER)
        }
        if (planned !in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES) return Axis.Hotter(FAN_MODE_NOT_COOLER)
        val livePercent = livePercent(live) ?: return Axis.Hotter(FAN_DUTY_UNREADABLE)
        val tempC = live.cpuTempC ?: return Axis.Hotter(FAN_DUTY_UNREADABLE)
        val plannedPercent = plannedPercent(profileId, tempC, bias)
        return when {
            plannedPercent < livePercent -> Axis.Hotter(FAN_DUTY_BELOW_LIVE)
            plannedPercent > livePercent -> Axis.Cooler
            else -> Axis.Equal
        }
    }

    private fun livePercent(live: LiveRearmState): Int? {
        val duty = live.fanDuty?.takeIf { it >= 0 } ?: return null
        val period = live.fanPeriod?.takeIf { it > 0 } ?: return null
        return (duty.toLong() * 100L / period.toLong()).coerceIn(0L, 100L).toInt()
    }

    private fun plannedPercent(profileId: String, tempC: Int, bias: FanCurveBias): Int =
        (FanCurveDefaults.forProfile(profileId).percentAt(tempC.toDouble(), bias) * FanCurveDefaults.scaleForProfile(profileId))
            .roundToInt()
            .coerceIn(0, FanPwmBounds.MAX_PERCENT)
}
