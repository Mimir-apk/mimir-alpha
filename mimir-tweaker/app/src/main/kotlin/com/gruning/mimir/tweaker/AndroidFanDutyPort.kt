package com.gruning.mimir.tweaker

internal fun interface FanDutyMutationPort {
    fun write(duty: Int, expectedPeriod: Int, minimumDutyPercent: Int): CheckedCommandResult
}

/**
 * Where a [ManualFanHandoffBaseline]/[ManualFanHandoffIdentity] traces its authority back to.
 *
 * **D1 .** Before this, every
 * handoff carried a bare `sourceGeneration: Long` / `sourceTransactionId: String` pair naming the
 * active session it was captured under -- fine for the transition entry and the P4 reassert, both
 * of which always have one, but the select path (D2) captures a [FreshApply] handoff directly from
 * Stock, before any session/generation exists to name. A nullable pair with a zero/blank sentinel
 * would have let a `Session(0, "")` slip through unchecked; a sealed source makes the two shapes
 * exhaustive and keeps each one's own validity requirement local to itself.
 */
internal sealed interface ManualFanHandoffSource {
    /** Captured under an already-identified session -- the transition entry, the P4 reassert. */
    data class Session(val generation: Long, val transactionId: String) : ManualFanHandoffSource {
        init {
            require(generation > 0L && transactionId.isNotBlank())
        }
    }

    /**
     * Captured directly from Stock on the select path (D2), with no session generation to name
     * yet. Carries nothing of its own: [ManualFanHandoffStore.consume] still matches by identity
     * equality, and [ManualFanHandoffBaseline.ownerRequestId] is unique per public request, the
     * store is single-slot, and [FAN_HANDOFF_MAX_AGE_MS] bounds how long a prepared baseline can
     * sit unconsumed -- the same three facts that protected a `Session` baseline now protect this
     * one, with no generation/transactionId needed to do it.
     */
    data object FreshApply : ManualFanHandoffSource
}

internal data class ManualFanHandoffBaseline(
    val profileId: String,
    val ownerRequestId: String,
    val source: ManualFanHandoffSource,
    val observedDuty: Int,
    val entryDuty: Int,
    val period: Int,
    /** Hottest classified CPU zone at capture; see [FanControlSample.cpuSafetyTempC]. */
    val cpuSafetyTempC: Int,
    val minimumDutyPercent: Int,
    val capturedElapsedRealtimeMs: Long,
) {
    val identity: ManualFanHandoffIdentity
        get() = ManualFanHandoffIdentity(
            profileId = profileId,
            ownerRequestId = ownerRequestId,
            source = source,
        )

    init {
        require(profileId in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS)
        // `source`'s own requires (Session's generation > 0L / transactionId.isNotBlank()) run at
        // its own construction, before it can ever reach here -- nothing left to re-check on it.
        require(ownerRequestId.isNotBlank())
        require(period in FanPwmBounds.PERIOD_RANGE)
        require(observedDuty in 0..period)
        require(entryDuty == observedDuty)
        require(cpuSafetyTempC in TelemetryRanges.CPU_MIN_C..TelemetryRanges.CPU_MAX_C)
        // the 20% floor is retired for every profile, so this
        // manual-handoff floor is unconditionally FanPwmBounds.MIN_SPIN_PERCENT (0) now. This
        // specific field was already 0 for "quiet" (unchanged for quiet here); balanced/
        // performance move from 20 to 0. Quiet's separate CONTROLLER floor
        // (FanThermalProfile.minSpinPercent, see FanThermalControl.kt) was still 20 before this
        // ruling and changes too -- do not read this as "quiet's behavior is unchanged".
        require(minimumDutyPercent == FanPwmBounds.MIN_SPIN_PERCENT)
        require(capturedElapsedRealtimeMs >= 0L)
    }
}

internal data class ManualFanHandoffIdentity(
    val profileId: String,
    val ownerRequestId: String,
    val source: ManualFanHandoffSource,
)

internal class ManualFanHandoffStore {
    private var pending: ManualFanHandoffBaseline? = null

    @Synchronized
    fun prepare(baseline: ManualFanHandoffBaseline): Boolean {
        if (pending != null) return false
        pending = baseline
        return true
    }

    @Synchronized
    fun consume(expected: ManualFanHandoffIdentity): ManualFanHandoffBaseline? =
        pending.also { pending = null }?.takeIf { it.identity == expected }

    @Synchronized
    fun clear() { pending = null }
}

internal object ManualFanHandoffCapture {
    fun capture(
        profileId: String,
        ownerRequestId: String,
        source: ManualFanHandoffSource,
        minimumDutyPercent: Int,
        sample: FanControlSample,
        nowElapsedRealtimeMs: Long,
    ): ManualFanHandoffBaseline? {
        if (sample.vendorFanMode != TuningPlanner.FAN_SMART) return null
        val period = sample.period?.takeIf { it in FanPwmBounds.PERIOD_RANGE } ?: return null
        val duty = sample.duty?.takeIf { it in 0..period } ?: return null
        val cpuSafetyTemp = sample.cpuSafetyTempC
            ?.takeIf { it in TelemetryRanges.CPU_MIN_C..TelemetryRanges.CPU_MAX_C }
            ?: return null
        // the 20% floor is retired for every profile, so this
        // manual-handoff floor is unconditionally FanPwmBounds.MIN_SPIN_PERCENT (0) now. This
        // specific field was already 0 for "quiet" (unchanged for quiet here); balanced/
        // performance move from 20 to 0. Quiet's separate CONTROLLER floor
        // (FanThermalProfile.minSpinPercent, see FanThermalControl.kt) was still 20 before this
        // ruling and changes too -- do not read this as "quiet's behavior is unchanged".
        val requiredFloor = FanPwmBounds.MIN_SPIN_PERCENT
        if (minimumDutyPercent != requiredFloor ||
            duty < minimumDutyForPercent(period, minimumDutyPercent)
        ) return null
        return ManualFanHandoffBaseline(
            profileId = profileId,
            ownerRequestId = ownerRequestId,
            source = source,
            observedDuty = duty,
            entryDuty = duty,
            period = period,
            cpuSafetyTempC = cpuSafetyTemp,
            minimumDutyPercent = minimumDutyPercent,
            capturedElapsedRealtimeMs = nowElapsedRealtimeMs,
        )
    }
}
/**
 * The sole runtime PWM actuator. It accepts no path from callers, never writes period or
 * tach, and refuses to operate unless the live transaction still owns the fan axis under a mode
 * Mimir directly drives duty on ([TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES]).
 *
 * **Widened off the exact Odin device id, 2026-08-23** to admit other AYN/Retroid devices by live capabilities. The device-id test that stood in the guard
 * below carried no authority the two capability conjuncts beside it did not already carry: the fan
 * axis is admitted by `FanMutationAdmissionPolicy`, and `fanNodeAvailable` is derived in
 * `AndroidHardware` as `profile.fanNodes != null && HandheldDeviceProfiles.fanPresent(...)` -- a
 * LIVE existence check of this unit's own `gpio5_pwm2` duty/period/speed nodes. The fixed-allowlist
 * topology comparison further down is unchanged and still refuses any path but those three.
 */
internal class PServerFanDutyMutationPort(
    private val pServer: PServerClient,
    private val reader: SystemReader,
    private val capabilities: () -> HardwareCapabilities,
    private val fanModeRaw: () -> String?,
    private val sleeper: (Long) -> Unit = Thread::sleep,
) : FanDutyMutationPort {
    override fun write(duty: Int, expectedPeriod: Int, minimumDutyPercent: Int): CheckedCommandResult {
        val live = capabilities()
        if (TuningAxis.FAN !in live.supportedAxes || !live.fanNodeAvailable) {
            return failed("Manual fan duty is unavailable on this hardware")
        }
        if (fanModeRaw()?.toIntOrNull() !in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES) {
            return failed("Manual fan ownership is not active")
        }
        val nodes = HandheldDeviceProfiles.resolve(
            manufacturer = live.manufacturer,
            model = live.model,
            socModel = live.socModel,
            reader = reader,
        ).fanNodes ?: return failed("Manual fan topology is unavailable")
        if (nodes.dutyPath != HardwareProbe.FAN_DUTY ||
            nodes.periodPath != HardwareProbe.FAN_PERIOD ||
            nodes.speedPath != HardwareProbe.FAN_SPEED
        ) {
            return failed("Manual fan topology did not match the fixed allowlist")
        }
        val livePeriod = reader.read(nodes.periodPath)?.toIntOrNull()
        if (expectedPeriod !in FanPwmBounds.PERIOD_RANGE || livePeriod != expectedPeriod) {
            return failed("Manual fan period changed")
        }
        // 0 is the only qualified floor now, for every profile.
        if (minimumDutyPercent != FanPwmBounds.MIN_SPIN_PERCENT) {
            return failed("Manual fan duty floor is not qualified")
        }
        val minimumDuty = minimumDutyForPercent(expectedPeriod, minimumDutyPercent)
        if (duty !in minimumDuty..expectedPeriod) {
            return failed("Manual fan duty is below the qualified profile floor")
        }

        val result = pServer.executeChecked("echo $duty > ${nodes.dutyPath}")
        if (result.status != CheckedCommandStatus.CONFIRMED) {
            val observed = reader.read(nodes.dutyPath)?.toIntOrNull()
            return result.copy(
                detail = diagnostic(
                    requested = duty,
                    observed = observed,
                    receiptDetail = result.detail,
                ),
            )
        }

        var observed: Int? = null
        repeat(READBACK_ATTEMPTS) { attempt ->
            if (fanModeRaw()?.toIntOrNull() !in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES) {
                return failed(
                    diagnostic(duty, observed, "Manual fan ownership changed during readback"),
                )
            }
            val settledPeriod = reader.read(nodes.periodPath)?.toIntOrNull()
            if (settledPeriod != expectedPeriod) {
                return failed(
                    diagnostic(duty, observed, "Manual fan period changed during readback"),
                )
            }
            observed = reader.read(nodes.dutyPath)?.toIntOrNull()
            if (observed == duty) return result
            if (attempt < READBACK_ATTEMPTS - 1) {
                try {
                    sleeper(READBACK_SETTLE_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return failed(diagnostic(duty, observed, "Readback settlement interrupted"))
                } catch (_: RuntimeException) {
                    return failed(diagnostic(duty, observed, "Readback settlement interrupted"))
                }
            }
        }
        return failed(diagnostic(duty, observed, "Confirmed receipt did not settle"))
    }

    private fun diagnostic(requested: Int, observed: Int?, receiptDetail: String): String =
        "requestedDuty=$requested observedDuty=${observed ?: "missing"} receiptDetail=$receiptDetail"

    private fun failed(detail: String) = CheckedCommandResult(
        status = CheckedCommandStatus.FAILED,
        detail = detail,
    )

    private companion object {
        const val READBACK_ATTEMPTS = 3
        const val READBACK_SETTLE_MS = 50L
    }
}
