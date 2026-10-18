package com.gruning.mimir.tweaker

import android.util.Log

/**
 * Pure apply admission + ordered orchestration. FGS establishment and Usage/telemetry
 * admission must succeed before any transaction capture/persist/first command.
 */
internal enum class ApplyGateStep {
    ESTABLISH_FGS,
    ADMIT_USAGE_AND_TELEMETRY,
    TRANSACTION_APPLY,
}

internal sealed class ApplyAdmissionResult {
    data object Admitted : ApplyAdmissionResult()
    data class Rejected(val code: String, val message: String) : ApplyAdmissionResult()
}

/**
 * TR-02: [evaluate]/[evaluateSystemWide] return the single wire `code` `TELEMETRY_ADMISSION_FAILED`
 * for seven distinct causes (null telemetry, incomplete telemetry, out-of-range telemetry, and
 * [thermalAdmissionTrip]'s own sub-reasons). That `code` is preserved exactly as-is here -- other
 * modules and tests match on it -- but every cause is now also logged via [logTelemetryDecline],
 * always-on in every posture including Production (the same "always-compiled seam" posture as
 * GuardianRestoreObservability/GuardianDerateObservability). A `TELEMETRY_ADMISSION_FAILED` with no
 * further attribution once cost real time: eliminating six of the seven causes by hand across two
 * subsystems was the only way to learn a profile refusal was a correct thermal refusal (Android
 * thermal status 3, CPUs at 89.9C while charging), not a bug.
 */
internal object ApplyAdmission {
    private const val TAG = "MimirApplyAdmission"

    /**
     * [coolerRearm] -- allows structurally cooler profiles to re-arm while hot: consulted ONLY when the Android thermal status is at or past [SupervisorPolicy.androidThermalTrip].
     * Null means no candidate comparison is available and the status refuses exactly as before; a
     * lambda answering null means the candidate is structurally cooler than the live state on every
     * axis ([CoolerRearmAdmission]) and the status trip is waived for it -- the CPU/GPU/battery
     * trips below still run. Any refusal token keeps the single wire code and names itself in the log.
     */
    fun evaluate(
        usageAccessGranted: Boolean?,
        telemetry: ThermalTelemetrySample?,
        policy: SupervisorPolicy = SupervisorPolicy(),
        coolerRearm: (() -> String?)? = null,
    ): ApplyAdmissionResult {
        if (usageAccessGranted != true) {
            return ApplyAdmissionResult.Rejected(
                code = "USAGE_ACCESS_REQUIRED",
                message = "Usage Access must be granted before apply admission.",
            )
        }
        val sample = telemetry ?: run {
            logTelemetryDecline("TELEMETRY_MISSING", detail = null)
            return ApplyAdmissionResult.Rejected(
                code = "TELEMETRY_ADMISSION_FAILED",
                message = "Complete Android/CPU/GPU/battery telemetry is required before apply.",
            )
        }
        if (!sample.isComplete) {
            logTelemetryDecline("TELEMETRY_INCOMPLETE", detail = telemetryDetail(sample))
            return ApplyAdmissionResult.Rejected(
                code = "TELEMETRY_ADMISSION_FAILED",
                message = "Incomplete Android/CPU/GPU/battery telemetry blocks apply admission.",
            )
        }
        if (!TelemetryRanges.allValid(sample)) {
            logTelemetryDecline("TELEMETRY_OUT_OF_RANGE", detail = telemetryDetail(sample))
            return ApplyAdmissionResult.Rejected(
                code = "TELEMETRY_ADMISSION_FAILED",
                message = "Telemetry values outside safe admission ranges.",
            )
        }
        thermalAdmissionTrip(sample, policy, coolerRearm)?.let { reason ->
            logTelemetryDecline(reason, detail = telemetryDetail(sample))
            return ApplyAdmissionResult.Rejected(
                code = "TELEMETRY_ADMISSION_FAILED",
                message = "Thermal admission rejected: $reason",
            )
        }
        return ApplyAdmissionResult.Admitted
    }

    /** System scope has no workload package, but retains the complete thermal admission gate. */
    fun evaluateSystemWide(
        telemetry: ThermalTelemetrySample?,
        policy: SupervisorPolicy = SupervisorPolicy(),
        coolerRearm: (() -> String?)? = null,
    ): ApplyAdmissionResult {
        val sample = telemetry ?: run {
            logTelemetryDecline("TELEMETRY_MISSING", detail = null)
            return ApplyAdmissionResult.Rejected(
                code = "TELEMETRY_ADMISSION_FAILED",
                message = "Complete Android/CPU/GPU/battery telemetry is required before apply.",
            )
        }
        if (!sample.isComplete || !TelemetryRanges.allValid(sample)) {
            // Same single branch/return as before (unchanged control flow) -- this only decides
            // which of the two already-possible causes the LOG line names; the wire code and
            // message are identical either way.
            val reason = if (!sample.isComplete) "TELEMETRY_INCOMPLETE" else "TELEMETRY_OUT_OF_RANGE"
            logTelemetryDecline(reason, detail = telemetryDetail(sample))
            return ApplyAdmissionResult.Rejected(
                code = "TELEMETRY_ADMISSION_FAILED",
                message = "Complete in-range Android/CPU/GPU/battery telemetry is required before apply.",
            )
        }
        thermalAdmissionTrip(sample, policy, coolerRearm)?.let { reason ->
            logTelemetryDecline(reason, detail = telemetryDetail(sample))
            return ApplyAdmissionResult.Rejected(
                code = "TELEMETRY_ADMISSION_FAILED",
                message = "Thermal admission rejected: $reason",
            )
        }
        return ApplyAdmissionResult.Admitted
    }

    private fun thermalAdmissionTrip(
        sample: ThermalTelemetrySample,
        policy: SupervisorPolicy,
        coolerRearm: (() -> String?)?,
    ): String? {
        val androidStatus = sample.androidThermalStatus ?: return "ANDROID_MISSING"
        if (androidStatus >= policy.androidThermalTrip) {
            // a profile structurally cooler than what the chip is
            // running right now may be admitted past the status trip -- refusing it leaves the
            // device exactly as hot as it is (the Odin: 103 C on vendor floors, re-arm refused).
            // No comparison available, or any axis not cooler/unreadable: refuse as before.
            if (coolerRearm == null) return "THERMAL_ANDROID"
            val refusal = coolerRearm()
            if (refusal != null) return "THERMAL_ANDROID/$refusal"
            logCoolerRearmAdmitted(telemetryDetail(sample))
        }
        val cpu = sample.cpuTempC ?: return "CPU_MISSING"
        if (cpu > policy.cpuTripCExclusive) return "THERMAL_CPU"
        val gpu = sample.gpuTempC ?: return "GPU_MISSING"
        if (gpu > policy.gpuTripCExclusive) return "THERMAL_GPU"
        val battery = sample.batteryTempDeciC ?: return "BATTERY_MISSING"
        if (battery > policy.batteryTripDeciCExclusive) return "THERMAL_BATTERY"
        return null
    }

    /** Raw telemetry numbers only -- never a package name, path, or other private-library content. */
    private fun telemetryDetail(sample: ThermalTelemetrySample): String =
        "androidThermalStatus=${sample.androidThermalStatus} cpuTempC=${sample.cpuTempC} " +
            "gpuTempC=${sample.gpuTempC} batteryTempDeciC=${sample.batteryTempDeciC}"

    /**
     * Always-on, every posture including Production -- `code` on the wire stays the single
     * `TELEMETRY_ADMISSION_FAILED` string every caller already matches on; this is the only place
     * the specific cause becomes reachable. `runCatching`-guarded: a diagnostic call must never
     * throw or change apply-admission behavior.
     */
    private fun logTelemetryDecline(reason: String, detail: String?) {
        val message = "TELEMETRY_ADMISSION_FAILED reason=$reason" +
            if (detail == null) "" else " $detail"
        runCatching { Log.w(TAG, message) }
    }

    /** F5: the status trip was waived for a structurally cooler candidate -- said out loud, every posture. */
    private fun logCoolerRearmAdmitted(detail: String) {
        runCatching { Log.w(TAG, "THERMAL_ANDROID_COOLER_REARM_ADMITTED $detail") }
    }
}

/**
 * Ordered apply gate used by the service. Admission failure short-circuits with zero
 * transaction work. Steps are recorded for deterministic JVM proofs.
 */
internal object ApplyOrchestrator {
    fun run(
        establishFgs: () -> Boolean,
        admit: () -> ApplyAdmissionResult,
        apply: () -> TransactionResult,
        onStep: (ApplyGateStep) -> Unit = {},
    ): TransactionResult {
        onStep(ApplyGateStep.ESTABLISH_FGS)
        if (!establishFgs()) {
            return TransactionResult(
                success = false,
                applied = false,
                restored = false,
                code = "FOREGROUND_START_FAILED",
                message = "Foreground service could not be established before apply.",
            )
        }
        onStep(ApplyGateStep.ADMIT_USAGE_AND_TELEMETRY)
        when (val admission = admit()) {
            is ApplyAdmissionResult.Rejected -> {
                return TransactionResult(
                    success = false,
                    applied = false,
                    restored = false,
                    code = admission.code,
                    message = admission.message,
                )
            }
            ApplyAdmissionResult.Admitted -> Unit
        }
        onStep(ApplyGateStep.TRANSACTION_APPLY)
        return apply()
    }
}
