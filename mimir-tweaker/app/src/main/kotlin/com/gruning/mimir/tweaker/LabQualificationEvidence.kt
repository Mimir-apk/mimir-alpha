package com.gruning.mimir.tweaker

/** Deterministic, logcat-safe evidence records for manual LAB qualification. */
internal object LabQualificationEvidence {
    fun selection(
        requestedProfileId: String,
        result: TransactionResult,
        truth: PublicSessionTruth,
    ): String = fields(
        "event" to "selection",
        "requested" to requestedProfileId,
        "result" to result.code,
        "success" to result.success,
        "applied" to result.applied,
        "restored" to result.restored,
        "session" to truth.state.name,
        "active" to activeProfile(truth),
        "cpu_target_khz" to result.plan?.cpuMaxKhz.asEvidenceMap(),
        "gpu_target_pwrlevel" to result.plan?.gpuMaxPowerLevel,
        "fan_target" to result.plan?.fanMode,
        "generation" to (result.generation ?: truth.generation),
        "transaction" to (result.transactionId ?: truth.transactionId),
        "hard_deadline_ms" to (
            result.hardLeaseDeadlineElapsedRealtimeMs ?: truth.hardLeaseDeadlineElapsedRealtimeMs
        ),
    )

    fun sample(
        telemetry: TelemetrySnapshot,
        live: LivePerformanceTelemetry,
        truth: PublicSessionTruth,
    ): String = fields(
        "event" to "sample",
        "elapsed_ms" to live.sampledAtElapsedRealtimeMs,
        "session" to truth.state.name,
        "active" to activeProfile(truth),
        "cpu_current_khz" to telemetry.cpuCurrentKhz.asEvidenceMap(),
        "cpu_qos_khz" to telemetry.cpuQosCeilingKhz.asEvidenceMap(),
        "cpu_max_khz" to telemetry.cpuMaxKhz.asEvidenceMap(),
        "cpu_busy_pct" to live.cpuBusyPercent,
        "gpu_clock_hz" to telemetry.gpuClockHz,
        "gpu_busy_pct" to telemetry.gpuBusyPercent,
        "gpu_min_pwrlevel" to telemetry.gpuMinPowerLevel,
        "gpu_max_pwrlevel" to telemetry.gpuMaxPowerLevel,
        "cpu_package_c" to telemetry.cpuPackageTempC,
        "cpu_safety_c" to telemetry.cpuTempC,
        "gpu_c" to telemetry.gpuTempC,
        "battery_pct" to telemetry.batteryLevelPercent,
        "battery_mw" to telemetry.batteryPowerMw,
        "battery_status" to telemetry.batteryStatus,
        "external_power_state" to telemetry.externalPowerState.name,
        "fan_mode" to telemetry.vendorFanMode,
        "fan_rpm" to telemetry.fanRpm,
        "fps_status" to live.frame.status.name,
        "fps_milli" to live.frame.averageFpsMilli,
        "foreground" to live.foregroundPackageName,
        "generation" to truth.generation,
        "transaction" to truth.transactionId,
        "hard_deadline_ms" to truth.hardLeaseDeadlineElapsedRealtimeMs,
    )

    fun guardianRestore(decision: SupervisorDecision.Restore): String = fields(
        "event" to "guardian_restore",
        "reason" to decision.reason,
        "generation" to decision.generation,
        "transaction" to decision.transactionId,
    )

    fun targetRepair(
        decision: SupervisorDecision.RepairCpuTarget,
        attempt: TransactionResult?,
        settlement: TransactionResult?,
    ): String = fields(
        "event" to "target_repair",
        "reason" to decision.reason,
        "attempt" to attempt?.code,
        "attempt_success" to attempt?.success,
        "settlement" to settlement?.code,
        "restored" to settlement?.restored,
        "generation" to decision.generation,
        "transaction" to decision.transactionId,
    )

    private fun activeProfile(truth: PublicSessionTruth): String = when (truth.state) {
        PublicSessionState.STOCK -> "stock"
        else -> truth.profileId ?: "unknown"
    }

    private fun fields(vararg entries: Pair<String, Any?>): String = entries.joinToString(" ") {
        (key, value) -> "$key=${value.asEvidenceToken()}"
    }

    private fun Any?.asEvidenceToken(): String = when (this) {
        null -> "na"
        is String -> replace(NON_TOKEN_CHARACTER, "_").ifBlank { "na" }
        else -> toString()
    }

    private fun Map<Int, Int>?.asEvidenceMap(): String =
        this?.entries
            ?.sortedBy { it.key }
            ?.joinToString(",") { (policyId, value) -> "$policyId:$value" }
            ?.ifBlank { "na" }
            ?: "na"

    private val NON_TOKEN_CHARACTER = Regex("[^A-Za-z0-9._:/,-]")
}
