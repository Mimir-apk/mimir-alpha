package com.gruning.mimir.tweaker

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.UUID

/** Stable wire codec for the fail-closed LAB hardware witness. */
internal object CalibrationHardwareVectorJsonCodec {
    fun encode(vector: CalibrationHardwareVector): JSONObject = JSONObject()
        .put("schemaVersion", vector.schemaVersion)
        .put("capturedAtElapsedMs", vector.capturedAtElapsedMs)
        .put("bootId", vector.bootId)
        .put("buildFingerprint", vector.buildFingerprint)
        .putNullable("vendorPerformanceMode", vector.vendorPerformanceMode)
        .putNullable("vendorFanMode", vector.vendorFanMode)
        .put("cpuCurrentKhz", vector.cpuCurrentKhz.toJson())
        .put("cpuMinKhz", vector.cpuMinKhz.toJson())
        .put("cpuMaxKhz", vector.cpuMaxKhz.toJson())
        .put("cpuQosCeilingKhz", vector.cpuQosCeilingKhz.toJson())
        .put("cpuQosTransport", vector.cpuQosTransport.name)
        .putNullable("gpuClockHz", vector.gpuClockHz)
        .putNullable("gpuMinPowerLevel", vector.gpuMinPowerLevel)
        .putNullable("gpuMaxPowerLevel", vector.gpuMaxPowerLevel)
        .putNullable("fanDuty", vector.fanDuty)
        .putNullable("fanPeriod", vector.fanPeriod)
        .putNullable("fanRpm", vector.fanRpm)
        .putNullable("batteryLevelPercent", vector.batteryLevelPercent)
        .putNullable("batteryCurrentUa", vector.batteryCurrentUa)
        .putNullable("batteryVoltageMv", vector.batteryVoltageMv)
        .putNullable("batteryTempDeciC", vector.batteryTempDeciC)
        .putNullable("batteryStatus", vector.batteryStatus)
        .put("externalPowerState", vector.externalPowerState.name)
        .putNullable("androidThermalStatus", vector.androidThermalStatus)
        .putNullable("cpuThermalZone", vector.cpuThermalZone?.toJson())
        .putNullable("gpuThermalZone", vector.gpuThermalZone?.toJson())
        .putNullable("thermalInventory", vector.thermalInventory?.toJson())

    fun decode(json: JSONObject): CalibrationHardwareVector = CalibrationHardwareVector(
        schemaVersion = json.optInt("schemaVersion"),
        capturedAtElapsedMs = json.optLong("capturedAtElapsedMs"),
        bootId = json.optString("bootId"),
        buildFingerprint = json.optString("buildFingerprint"),
        vendorPerformanceMode = json.nullableInt("vendorPerformanceMode"),
        vendorFanMode = json.nullableInt("vendorFanMode"),
        cpuCurrentKhz = json.optJSONObject("cpuCurrentKhz")?.toIntMap().orEmpty(),
        cpuMinKhz = json.optJSONObject("cpuMinKhz")?.toIntMap().orEmpty(),
        cpuMaxKhz = json.optJSONObject("cpuMaxKhz")?.toIntMap().orEmpty(),
        cpuQosCeilingKhz = json.optJSONObject("cpuQosCeilingKhz")?.toIntMap().orEmpty(),
        cpuQosTransport = runCatching {
            CpuQosTransport.valueOf(json.optString("cpuQosTransport"))
        }.getOrDefault(CpuQosTransport.UNKNOWN),
        gpuClockHz = json.nullableLong("gpuClockHz"),
        gpuMinPowerLevel = json.nullableInt("gpuMinPowerLevel"),
        gpuMaxPowerLevel = json.nullableInt("gpuMaxPowerLevel"),
        fanDuty = json.nullableInt("fanDuty"),
        fanPeriod = json.nullableInt("fanPeriod"),
        fanRpm = json.nullableInt("fanRpm"),
        batteryLevelPercent = json.nullableInt("batteryLevelPercent"),
        batteryCurrentUa = json.nullableInt("batteryCurrentUa"),
        batteryVoltageMv = json.nullableInt("batteryVoltageMv"),
        batteryTempDeciC = json.nullableInt("batteryTempDeciC"),
        batteryStatus = json.nullableString("batteryStatus"),
        externalPowerState = runCatching {
            ExternalPowerState.valueOf(json.optString("externalPowerState"))
        }.getOrDefault(ExternalPowerState.UNKNOWN),
        androidThermalStatus = json.nullableInt("androidThermalStatus"),
        cpuThermalZone = json.optJSONObject("cpuThermalZone")?.toThermalZoneWinner(),
        gpuThermalZone = json.optJSONObject("gpuThermalZone")?.toThermalZoneWinner(),
        thermalInventory = json.optJSONObject("thermalInventory")?.toThermalInventory(),
    )

    private fun ThermalZoneWinner.toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("rawTemperature", rawTemperature)
        .put("celsius", celsius)
        .put("path", path)

    private fun JSONObject.toThermalZoneWinner(): ThermalZoneWinner? {
        val type = optString("type").takeIf(String::isNotBlank) ?: return null
        if (!has("rawTemperature") || isNull("rawTemperature")) return null
        return ThermalZoneWinner(
            type = type,
            rawTemperature = optLong("rawTemperature"),
            celsius = optInt("celsius"),
            path = optString("path"),
        )
    }

    private fun CalibrationThermalInventory.toJson(): JSONObject = JSONObject()
        .put("cpu", cpu.toJson())
        .put("gpu", gpu.toJson())

    private fun List<ThermalZoneIdentity>.toJson(): JSONArray = JSONArray().also { array ->
        forEach { zone -> array.put(JSONObject().put("family", zone.family).put("type", zone.type).put("path", zone.path)) }
    }

    private fun JSONObject.toThermalInventory(): CalibrationThermalInventory? {
        fun JSONArray?.identities(): List<ThermalZoneIdentity> = this?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { zone ->
                val family = zone.optString("family")
                val type = zone.optString("type")
                val path = zone.optString("path")
                ThermalZoneIdentity(family, type, path).takeIf {
                    it.family.isNotBlank() && it.type.isNotBlank() && it.path.isNotBlank()
                }
            } }
        }.orEmpty().distinct().sortedWith(compareBy(ThermalZoneIdentity::family, ThermalZoneIdentity::path))
        val cpu = optJSONArray("cpu").identities()
        val gpu = optJSONArray("gpu").identities()
        return CalibrationThermalInventory(cpu, gpu).takeIf { it.cpu.isNotEmpty() && it.gpu.isNotEmpty() }
    }

    private fun Map<Int, Int>.toJson(): JSONObject = JSONObject().also { json ->
        forEach { (key, value) -> json.put(key.toString(), value) }
    }

    private fun JSONObject.toIntMap(): Map<Int, Int> = keys().asSequence().mapNotNull { key ->
        key.toIntOrNull()?.let { it to optInt(key) }
    }.toMap()

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.nullableInt(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.nullableLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.nullableString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}

internal class SharedPreferencesCalibrationReportStore(context: Context) : CalibrationReportStore {
    private val preferences = context.getSharedPreferences("tweaker_calibration", Context.MODE_PRIVATE)
    private val lock = Any()

    override fun load(): CalibrationReport? = preferences.getString(KEY_LATEST, null)?.let { raw ->
        runCatching { JSONObject(raw).toCalibrationReport() }.getOrNull()
    }

    @SuppressLint("ApplySharedPref") // Observation reports remain durable for lab diagnostics only.
    override fun save(report: CalibrationReport): Boolean {
        // TW04-A: never write product-cert keys from local reports — CERTIFIED is non-authoritative.
        return preferences.edit().putString(KEY_LATEST, report.toJson().toString()).commit()
    }

    override fun beginObservation(report: CalibrationReport): Boolean = synchronized(lock) {
        save(report)
    }

    override fun saveIfCurrent(observationId: String, report: CalibrationReport): Boolean = synchronized(lock) {
        val current = load() ?: return false
        if (current.observationId != observationId) return false
        save(report)
    }

    /** Quarantined: unsigned local reports never authorize product profiles. */
    override fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport? = null

    override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> = emptySet()

    private fun CalibrationReport.toJson(): JSONObject = JSONObject()
        .put("disposition", disposition.name)
        .put("profileId", profileId)
        .put("packageName", packageName)
        .put("hardwareFingerprint", hardwareFingerprint)
        .put("startedAtEpochMs", startedAtEpochMs)
        .put("requestedDurationSeconds", requestedDurationSeconds)
        .put("observedDurationSeconds", observedDurationSeconds)
        .put("sampleCount", sampleCount)
        .put("qualificationSampleCount", qualificationSampleCount)
        .put("qualificationWindowSampleCount", qualificationWindowSampleCount)
        .putNullable("maxCpuTempC", maxCpuTempC)
        .putNullable("maxGpuTempC", maxGpuTempC)
        .putNullable("maxBatteryTempDeciC", maxBatteryTempDeciC)
        .putNullable("averageBatteryPowerMw", averageBatteryPowerMw)
        .putNullable("maxThermalStatus", maxThermalStatus)
        .put("chargingSampleCount", chargingSampleCount)
        .put("externalPowerSampleCount", externalPowerSampleCount)
        .put("batteryEfficiencyEligible", batteryEfficiencyEligible)
        .put("batteryEfficiencyBlockers", JSONArray(batteryEfficiencyBlockers))
        .put("attestedSampleCount", attestedSampleCount)
        .put("frameSampleCount", frameSampleCount)
        .put("frameAttestedSampleCount", frameAttestedSampleCount)
        .putNullable("medianFrameFpsMilli", medianFrameFpsMilli)
        .putNullable("maximumP95FrameTimeUs", maximumP95FrameTimeUs)
        .putNullable("averageSlow20FpsPercent", averageSlow20FpsPercent)
        .putNullable("averageSlow30FpsPercent", averageSlow30FpsPercent)
        .put("frameLayerNames", JSONArray(frameLayerNames))
        .put("candidateCpuMaxKhz", candidateCpuMaxKhz.toJson())
        .putNullable("candidateGpuMaxPowerLevel", candidateGpuMaxPowerLevel)
        .putNullable("candidateFanMode", candidateFanMode)
        .put("evidence", evidence.toJson())
        .put("blockers", JSONArray(blockers))
        .put("samples", JSONArray().also { array -> samples.forEach { array.put(it.toJson()) } })
        .put("observationId", observationId)
        .putNullable("baselineHardware", baselineHardware?.toJson())
        .putNullable("admissionHardware", admissionHardware?.toJson())
        .putNullable("terminalHardware", terminalHardware?.toJson())
        .put("preMutationBaselineVectors", JSONArray().also { array ->
            preMutationBaselineVectors.forEach { array.put(it.toJson()) }
        })
        .putNullable("preMutationBaselineBinding", preMutationBaselineBinding?.toJson())
        .putNullable("preMutationBaselinePlanHash", preMutationBaselinePlanHash)
        .putNullable("preMutationBaselineObservationId", preMutationBaselineObservationId)

    private fun JSONObject.toCalibrationReport(): CalibrationReport = CalibrationReport(
        disposition = runCatching { CalibrationDisposition.valueOf(getString("disposition")) }
            .getOrDefault(CalibrationDisposition.FAILED),
        profileId = optString("profileId"),
        packageName = optString("packageName"),
        hardwareFingerprint = optString("hardwareFingerprint"),
        startedAtEpochMs = optLong("startedAtEpochMs"),
        requestedDurationSeconds = optInt("requestedDurationSeconds"),
        observedDurationSeconds = optInt("observedDurationSeconds"),
        sampleCount = optInt("sampleCount"),
        qualificationSampleCount = optInt("qualificationSampleCount"),
        qualificationWindowSampleCount = optInt("qualificationWindowSampleCount"),
        maxCpuTempC = nullableInt("maxCpuTempC"),
        maxGpuTempC = nullableInt("maxGpuTempC"),
        maxBatteryTempDeciC = nullableInt("maxBatteryTempDeciC"),
        // Legacy reports had no independent external-power witness. Never surface their
        // attached-USB battery current as an efficiency result.
        averageBatteryPowerMw = nullableInt("averageBatteryPowerMw")
            .takeIf { optBoolean("batteryEfficiencyEligible") },
        maxThermalStatus = nullableInt("maxThermalStatus"),
        chargingSampleCount = optInt("chargingSampleCount"),
        externalPowerSampleCount = optInt("externalPowerSampleCount"),
        batteryEfficiencyEligible = optBoolean("batteryEfficiencyEligible"),
        batteryEfficiencyBlockers = optJSONArray("batteryEfficiencyBlockers").toStrings(),
        attestedSampleCount = optInt("attestedSampleCount"),
        frameSampleCount = optInt("frameSampleCount"),
        frameAttestedSampleCount = optInt("frameAttestedSampleCount"),
        medianFrameFpsMilli = nullableInt("medianFrameFpsMilli"),
        maximumP95FrameTimeUs = nullableInt("maximumP95FrameTimeUs"),
        averageSlow20FpsPercent = nullableInt("averageSlow20FpsPercent"),
        averageSlow30FpsPercent = nullableInt("averageSlow30FpsPercent"),
        frameLayerNames = optJSONArray("frameLayerNames").toStrings(),
        candidateCpuMaxKhz = optJSONObject("candidateCpuMaxKhz")?.toIntMap().orEmpty(),
        candidateGpuMaxPowerLevel = nullableInt("candidateGpuMaxPowerLevel"),
        candidateFanMode = nullableInt("candidateFanMode"),
        evidence = optJSONObject("evidence")?.toEvidence() ?: CalibrationSafetyEvidence(),
        blockers = optJSONArray("blockers").toStrings(),
        samples = optJSONArray("samples").toSamples(),
        observationId = optString("observationId"),
        baselineHardware = optJSONObject("baselineHardware")?.toHardwareVector(),
        admissionHardware = optJSONObject("admissionHardware")?.toHardwareVector(),
        terminalHardware = optJSONObject("terminalHardware")?.toHardwareVector(),
        preMutationBaselineVectors = optJSONArray("preMutationBaselineVectors").toHardwareVectors(),
        preMutationBaselineBinding = optJSONObject("preMutationBaselineBinding")?.toPreMutationBaselineBinding(),
        preMutationBaselinePlanHash = nullableString("preMutationBaselinePlanHash"),
        preMutationBaselineObservationId = nullableString("preMutationBaselineObservationId"),
    )

    private fun CalibrationSafetyEvidence.toJson(): JSONObject = JSONObject()
        .put("workloadAttested", workloadAttested)
        .put("frameTelemetryAttested", frameTelemetryAttested)
        .put("writesExercised", writesExercised)
        .put("rollbackAttempts", rollbackAttempts)
        .put("rollbackSuccesses", rollbackSuccesses)
        .put("rebootRestoreVerified", rebootRestoreVerified)

    private fun JSONObject.toEvidence(): CalibrationSafetyEvidence = CalibrationSafetyEvidence(
        workloadAttested = optBoolean("workloadAttested"),
        frameTelemetryAttested = optBoolean("frameTelemetryAttested"),
        writesExercised = optBoolean("writesExercised"),
        rollbackAttempts = optInt("rollbackAttempts"),
        rollbackSuccesses = optInt("rollbackSuccesses"),
        rebootRestoreVerified = optBoolean("rebootRestoreVerified"),
    )

    private fun CalibrationSample.toJson(): JSONObject = JSONObject()
        .put("elapsedMs", elapsedMs)
        .putNullable("cpuTempC", cpuTempC)
        .putNullable("gpuTempC", gpuTempC)
        .putNullable("batteryTempDeciC", batteryTempDeciC)
        .putNullable("batteryPowerMw", batteryPowerMw)
        .putNullable("batteryStatus", batteryStatus)
        .put("externalPowerState", externalPowerState.name)
        .putNullable("thermalStatus", thermalStatus)
        .putNullable("gpuBusyPercent", gpuBusyPercent)
        .putNullable("fanRpm", fanRpm)
        .putNullable("foregroundPackageName", foregroundPackageName)
        .putNullable("frameStatus", frameStatus)
        .putNullable("frameLayerName", frameLayerName)
        .putNullable("frameDiagnostic", frameDiagnostic)
        .putNullable("frameAverageFpsMilli", frameAverageFpsMilli)
        .putNullable("frameP50TimeUs", frameP50TimeUs)
        .putNullable("frameP95TimeUs", frameP95TimeUs)
        .putNullable("frameSlow20FpsPercent", frameSlow20FpsPercent)
        .putNullable("frameSlow30FpsPercent", frameSlow30FpsPercent)
        .put("targetContractValid", targetContractValid)
        .putNullable("hardware", hardware?.toJson())

    private fun JSONObject.toSample(): CalibrationSample = CalibrationSample(
        elapsedMs = optLong("elapsedMs"),
        cpuTempC = nullableInt("cpuTempC"),
        gpuTempC = nullableInt("gpuTempC"),
        batteryTempDeciC = nullableInt("batteryTempDeciC"),
        batteryPowerMw = nullableInt("batteryPowerMw"),
        batteryStatus = nullableString("batteryStatus"),
        externalPowerState = runCatching {
            ExternalPowerState.valueOf(optString("externalPowerState"))
        }.getOrDefault(ExternalPowerState.UNKNOWN),
        thermalStatus = nullableInt("thermalStatus"),
        gpuBusyPercent = nullableInt("gpuBusyPercent"),
        fanRpm = nullableInt("fanRpm"),
        foregroundPackageName = nullableString("foregroundPackageName"),
        frameStatus = nullableString("frameStatus"),
        frameLayerName = nullableString("frameLayerName"),
        frameDiagnostic = nullableString("frameDiagnostic"),
        frameAverageFpsMilli = nullableInt("frameAverageFpsMilli"),
        frameP50TimeUs = nullableInt("frameP50TimeUs"),
        frameP95TimeUs = nullableInt("frameP95TimeUs"),
        frameSlow20FpsPercent = nullableInt("frameSlow20FpsPercent"),
        frameSlow30FpsPercent = nullableInt("frameSlow30FpsPercent"),
        targetContractValid = optBoolean("targetContractValid"),
        hardware = optJSONObject("hardware")?.toHardwareVector(),
    )

    private fun CalibrationHardwareVector.toJson(): JSONObject = CalibrationHardwareVectorJsonCodec.encode(this)

    private fun JSONObject.toHardwareVector(): CalibrationHardwareVector =
        CalibrationHardwareVectorJsonCodec.decode(this)

    private fun LabPreMutationBaselineBinding.toJson(): JSONObject = JSONObject()
        .put("profileId", profileId)
        .put("packageName", packageName)
        .put("hardwareFingerprint", hardwareFingerprint)
        .put("buildFingerprint", buildFingerprint)
        .put("bootId", bootId)
        .put("generation", generation)
        .put("transactionId", transactionId)
        .put("requestId", requestId)
        .put("planHash", planHash)
        .put("leaseDeadlineElapsedMs", leaseDeadlineElapsedMs)

    private fun JSONObject.toPreMutationBaselineBinding(): LabPreMutationBaselineBinding? {
        val profileId = optString("profileId")
        val packageName = optString("packageName")
        val hardwareFingerprint = optString("hardwareFingerprint")
        val buildFingerprint = optString("buildFingerprint")
        val bootId = optString("bootId")
        val transactionId = optString("transactionId")
        val requestId = optString("requestId")
        val planHash = optString("planHash")
        val leaseDeadline = optLong("leaseDeadlineElapsedMs")
        if (profileId.isBlank() || packageName.isBlank() || hardwareFingerprint.isBlank() ||
            buildFingerprint.isBlank() || bootId.isBlank() || transactionId.isBlank() ||
            requestId.isBlank() || planHash.isBlank() || leaseDeadline <= 0L
        ) return null
        return LabPreMutationBaselineBinding(
            profileId = profileId,
            packageName = packageName,
            hardwareFingerprint = hardwareFingerprint,
            buildFingerprint = buildFingerprint,
            bootId = bootId,
            generation = optLong("generation"),
            transactionId = transactionId,
            requestId = requestId,
            planHash = planHash,
            leaseDeadlineElapsedMs = leaseDeadline,
        )
    }

    private fun Map<Int, Int>.toJson(): JSONObject = JSONObject().also { json ->
        forEach { (key, value) -> json.put(key.toString(), value) }
    }

    private fun JSONObject.toIntMap(): Map<Int, Int> = keys().asSequence().mapNotNull { key ->
        key.toIntOrNull()?.let { it to optInt(key) }
    }.toMap()

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.nullableInt(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.nullableLong(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
    private fun JSONObject.nullableString(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONArray?.toStrings(): List<String> = this?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotEmpty) }
    }.orEmpty()

    private fun JSONArray?.toSamples(): List<CalibrationSample> = this?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toSample() }
    }.orEmpty()

    private fun JSONArray?.toHardwareVectors(): List<CalibrationHardwareVector> = this?.let { array ->
        (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.toHardwareVector() }
    }.orEmpty()

    companion object {
        private const val KEY_LATEST = "latest_report"
    }
}

/** Separates cheap one-hertz safety sampling from bounded privileged provenance checkpoints. */
internal interface CalibrationTelemetryProbe {
    fun lightTelemetry(capabilities: HardwareCapabilities): TelemetrySnapshot
    fun fullTelemetry(capabilities: HardwareCapabilities): TelemetrySnapshot
}

internal enum class CalibrationObservationCompletion {
    CONTINUE,
    COMPLETE,
    ADMISSION_TIMEOUT,
}

/** Keeps the 300-second workload criterion separate from the bounded admission grace. */
internal object CalibrationObservationCompletionPolicy {
    const val ADMISSION_GRACE_MS = 60_000L

    fun decide(
        elapsedMs: Long,
        qualifiedDurationMs: Long,
        requiredDurationMs: Long,
    ): CalibrationObservationCompletion = when {
        qualifiedDurationMs >= requiredDurationMs -> CalibrationObservationCompletion.COMPLETE
        elapsedMs >= requiredDurationMs.saturatingAdd(ADMISSION_GRACE_MS) ->
            CalibrationObservationCompletion.ADMISSION_TIMEOUT
        else -> CalibrationObservationCompletion.CONTINUE
    }

    private fun Long.saturatingAdd(other: Long): Long =
        if (this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other
}

/** Small, pure cadence policy: privileged vectors are bounded but never optional at safety gates. */
internal object CalibrationVectorCheckpointPolicy {
    const val INTERVAL_MS = 15_000L
    const val MAX_CONSECUTIVE_MISSES = 3

    fun requiresFullVector(
        elapsedMs: Long,
        lastFullCheckpointAttemptElapsedMs: Long?,
        admissionCheckpoint: Boolean,
        terminalCheckpoint: Boolean,
        thermalCheckpoint: Boolean,
    ): Boolean =
        lastFullCheckpointAttemptElapsedMs == null ||
            elapsedMs - (lastFullCheckpointAttemptElapsedMs ?: elapsedMs) >= INTERVAL_MS ||
            admissionCheckpoint || terminalCheckpoint || thermalCheckpoint

    fun nextMissCount(previousMisses: Int): Int = previousMisses.coerceAtLeast(0) + 1

    fun mustAbortAfterMiss(requiredCheckpoint: Boolean, missCount: Int): Boolean =
        requiredCheckpoint || missCount >= MAX_CONSECUTIVE_MISSES
}

/**
 * Session-local structural proof from the latest full vector. Foreground and frame truth
 * are deliberately not cached; every light sample still supplies those independently.
 */
internal class CalibrationTargetContractLatch {
    private var validatedAtElapsedMs: Long? = null

    fun isFresh(elapsedMs: Long): Boolean = validatedAtElapsedMs?.let { validatedAt ->
        elapsedMs >= validatedAt &&
            elapsedMs - validatedAt < CalibrationVectorCheckpointPolicy.INTERVAL_MS
    } == true

    fun record(elapsedMs: Long, valid: Boolean) {
        validatedAtElapsedMs = elapsedMs.takeIf { valid }
    }

    fun invalidate() {
        validatedAtElapsedMs = null
    }
}

internal object CalibrationTargetContractDriftPolicy {
    /** Start proves the target contract before OBSERVING, so any later full-vector drift is terminal. */
    fun mustAbort(fullVectorValid: Boolean): Boolean = !fullVectorValid
}

/**
 * Immutable calibration authority derived from one authenticated APPLIED journal load.
 * It binds exact mutation projection truth to the observation without widening the wire API.
 */
internal data class CalibrationAppliedTargetOffer(
    val contract: AppliedTargetContract,
    val profileId: String,
    val packageName: String,
    val hardwareFingerprint: String,
    val generation: Long,
    val transactionId: String,
    val requestId: String,
    val planHash: String,
    val leaseDeadlineElapsedMs: Long,
) {
    fun matches(binding: LabPreMutationBaselineBinding): Boolean =
        profileId == binding.profileId &&
            packageName == binding.packageName &&
            hardwareFingerprint == binding.hardwareFingerprint &&
            contract.bootId == binding.bootId &&
            generation == binding.generation &&
            transactionId == binding.transactionId &&
            requestId == binding.requestId &&
            planHash == binding.planHash &&
            leaseDeadlineElapsedMs == binding.leaseDeadlineElapsedMs

    companion object {
        /** Caller must supply a record returned by [AuthenticatedTransactionJournal.load]. */
        fun fromAuthenticatedAppliedRecord(
            record: TransactionJournalRecord,
            profileId: String,
            packageName: String,
            capabilities: HardwareCapabilities,
            nowElapsedMs: Long,
        ): CalibrationAppliedTargetOffer? {
            if (record.phase != TransactionPhase.APPLIED ||
                record.profileId != profileId ||
                record.targetPackage != packageName ||
                record.hardwareFingerprint != HardwareFingerprint.create(capabilities) ||
                nowElapsedMs >= record.leaseDeadline
            ) return null
            val contract = AppliedTargetContract.fromAuthenticatedAppliedRecord(record) ?: return null
            return CalibrationAppliedTargetOffer(
                contract = contract,
                profileId = record.profileId,
                packageName = record.targetPackage,
                hardwareFingerprint = record.hardwareFingerprint,
                generation = record.generation,
                transactionId = record.transactionId,
                requestId = record.requestId,
                planHash = record.planHash,
                leaseDeadlineElapsedMs = record.leaseDeadline,
            )
        }
    }
}

internal class CalibrationSessionRunner(
    private val telemetryProbe: CalibrationTelemetryProbe,
    private val capabilities: () -> HardwareCapabilities,
    private val planner: TuningPlanner,
    private val evaluator: CalibrationEvaluator,
    private val store: CalibrationReportStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val foregroundPackage: () -> String? = { null },
    private val frameTelemetry: FrameTelemetryPort,
    private val onFinished: (CalibrationReport) -> Unit = {},
    private val preProfileBaselineSleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val externalBaseline: ((String, String, HardwareCapabilities) -> LabPreMutationBaselineOffer?)? = null,
    private val requiresExternalBaseline: (String) -> Boolean = { false },
    private val appliedTarget: ((String, String, HardwareCapabilities) -> CalibrationAppliedTargetOffer?)? = null,
    private val requiresAppliedTarget: (String) -> Boolean = { false },
) {
    private data class ActiveSession(
        val run: CalibrationRun,
        val plan: TuningPlan,
        val startedElapsedMs: Long,
        val samples: MutableList<CalibrationSample>,
        val appliedTargetContract: AppliedTargetContract?,
        var lastFullVectorElapsedMs: Long? = null,
        var lastFullCheckpointAttemptElapsedMs: Long? = null,
        var consecutiveFullVectorMisses: Int = 0,
        val targetContractLatch: CalibrationTargetContractLatch = CalibrationTargetContractLatch(),
    )

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mimir-tweaker-calibration").apply { isDaemon = true }
    }
    private var active: ActiveSession? = null
    private var future: ScheduledFuture<*>? = null
    private var pendingColdProcessTerminal: CalibrationReport? = null

    init {
        store.load()?.takeIf { it.disposition == CalibrationDisposition.OBSERVING }?.let { stale ->
            val terminal = stale.copy(
                disposition = CalibrationDisposition.FAILED,
                blockers = stale.blockers
                    .filterNot { it == OBSERVATION_RUNNING_BLOCKER }
                    .plus("Observation was interrupted: SERVICE_PROCESS_ENDED"),
            )
            if (!store.saveIfCurrent(stale.observationId, terminal)) {
                pendingColdProcessTerminal = terminal
            }
        }
    }

    @Synchronized
    fun start(profileId: String, packageName: String, durationSeconds: Int): CalibrationActionResult {
        if (!retryPendingColdProcessTerminal()) {
            return CalibrationActionResult(
                false,
                "PERSIST_FAILED",
                "The interrupted observation could not be terminalized; no new observation started.",
                statusWithoutRetry(),
            )
        }
        active?.let {
            return CalibrationActionResult(false, "OBSERVATION_ACTIVE", "Stop the active observation first.", report(it, true))
        }
        if (durationSeconds !in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
            return CalibrationActionResult(
                false,
                "INVALID_DURATION",
                "Duration must be between $MIN_DURATION_SECONDS and $MAX_DURATION_SECONDS seconds.",
                status(),
            )
        }
        val live = capabilities()
        if (live.status == CapabilityStatus.UNSUPPORTED) {
            return CalibrationActionResult(false, "UNSUPPORTED", "SM8250 handheld contract is unavailable.", status())
        }
        val plan = planner.plan(profileId, packageName, live)
            ?: return CalibrationActionResult(false, "UNKNOWN_PROFILE", "Unknown profile: $profileId", status())
        val targetOffer = appliedTarget?.invoke(profileId, packageName, live)
        if (requiresAppliedTarget(profileId) && targetOffer == null) {
            return CalibrationActionResult(
                false,
                "APPLIED_TARGET_AUTHORITY_REQUIRED",
                "An authenticated active-profile target is required; no observation started.",
                status(),
            )
        }
        if (targetOffer != null && targetOffer.planHash != PlanHash.of(plan)) {
            return CalibrationActionResult(
                false,
                "APPLIED_TARGET_PLAN_MISMATCH",
                "The authenticated applied target no longer matches the requested plan.",
                status(),
            )
        }
        val supplied = externalBaseline?.invoke(profileId, packageName, live)
        if (requiresExternalBaseline(profileId) && supplied == null) {
            return CalibrationActionResult(false, "PRE_MUTATION_BASELINE_REQUIRED", "A matching LAB pre-mutation baseline is required.", status())
        }
        if (supplied != null && supplied.binding.planHash != PlanHash.of(plan)) {
            return CalibrationActionResult(false, "PRE_MUTATION_BASELINE_PLAN_MISMATCH", "LAB baseline plan identity no longer matches the requested profile.", status())
        }
        if (supplied != null && targetOffer != null && !targetOffer.matches(supplied.binding)) {
            return CalibrationActionResult(
                false,
                "APPLIED_TARGET_BASELINE_MISMATCH",
                "The authenticated applied target does not belong to the captured pre-mutation baseline.",
                status(),
            )
        }
        val stockPlan = planner.plan("stock", packageName, live)
            ?: return CalibrationActionResult(false, "STOCK_BASELINE_UNAVAILABLE", "Stock baseline plan is unavailable.", status())
        val preProfileVectors = supplied?.vectors ?: buildList {
            repeat(CalibrationPreProfileBaselinePolicy.REQUIRED_READS) { index ->
                val vector = runCatching {
                    telemetryProbe.fullTelemetry(live).toCalibrationHardwareVector(
                        capturedAtElapsedMs = nowElapsedMs(),
                        buildFingerprint = live.buildFingerprint,
                    )
                }.getOrNull() ?: return@buildList
                add(vector)
                if (index < CalibrationPreProfileBaselinePolicy.REQUIRED_READS - 1) {
                    preProfileBaselineSleeper(PRE_PROFILE_BASELINE_DWELL_MS)
                }
            }
        }
        val baselineViolations = CalibrationPreProfileBaselinePolicy.violations(preProfileVectors, stockPlan)
        val preProfileHardware = preProfileVectors.lastOrNull().takeIf { baselineViolations.isEmpty() }
            ?: run {
            return CalibrationActionResult(
                false,
                "PRE_PROFILE_BASELINE_INVALID",
                buildString {
                    append("Three coherent pre-profile Stock baseline vectors are required.")
                    baselineViolations.take(MAX_BASELINE_DIAGNOSTIC_REASONS).forEach { reason ->
                        append(" ").append(reason)
                    }
                },
                status(),
            )
        }
        val initialTargetTelemetry = runCatching { telemetryProbe.fullTelemetry(live) }.getOrNull()
            ?: return CalibrationActionResult(
                false,
                "TARGET_CONTRACT_NOT_ACTIVE",
                "The requested profile target could not be read before observation; no observation started.",
                status(),
            )
        val initialTargetVector = initialTargetTelemetry.toCalibrationHardwareVector(
            capturedAtElapsedMs = 0L,
            buildFingerprint = live.buildFingerprint,
        )
        val initialTargetViolations = buildList {
            if (initialTargetVector.bootId != preProfileHardware.bootId) {
                add("Target boot identity does not match the pre-profile baseline.")
            }
            addAll(
                CalibrationProvenanceValidator.validate(
                    profileId = profileId,
                    candidate = plan,
                    admission = initialTargetVector,
                    terminal = initialTargetVector,
                    periodic = emptyList(),
                    expectedThermalInventory = preProfileHardware.thermalInventory,
                ),
            )
            targetOffer?.contract?.let { addAll(it.violations(initialTargetVector)) }
        }
        if (initialTargetViolations.isNotEmpty()) {
            return CalibrationActionResult(
                false,
                "TARGET_CONTRACT_NOT_ACTIVE",
                buildString {
                    append("The requested profile target is not active; no observation started.")
                    initialTargetViolations.take(MAX_TARGET_DIAGNOSTIC_REASONS).forEach { reason ->
                        append(" ").append(reason)
                    }
                },
                status(),
            )
        }
        val run = CalibrationRun(
            profileId = profileId,
            packageName = packageName,
            hardwareFingerprint = HardwareFingerprint.create(live),
            buildFingerprint = live.buildFingerprint,
            startedAtEpochMs = nowEpochMs(),
            requestedDurationSeconds = durationSeconds,
            samples = emptyList(),
            preProfileHardware = preProfileHardware,
            preMutationBaselineVectors = supplied?.vectors.orEmpty(),
            preMutationBaselineBinding = supplied?.binding,
            preMutationBaselinePlanHash = supplied?.binding?.planHash,
            preMutationBaselineObservationId = supplied?.observationId,
            completed = false,
            observationId = supplied?.observationId ?: UUID.randomUUID().toString(),
        )
        val session = ActiveSession(
            run = run,
            plan = plan,
            startedElapsedMs = nowElapsedMs(),
            samples = mutableListOf(),
            appliedTargetContract = targetOffer?.contract,
        )
        session.targetContractLatch.record(elapsedMs = 0L, valid = true)
        active = session
        val observing = report(session, true)
        if (!store.beginObservation(observing)) {
            active = null
            return CalibrationActionResult(false, "PERSIST_FAILED", "Could not persist observation start.", observing)
        }
        future = executor.scheduleWithFixedDelay(::sampleOnce, 0L, SAMPLE_DELAY_MS, TimeUnit.MILLISECONDS)
        return CalibrationActionResult(true, "OBSERVATION_STARTED", "Observe-only calibration started; hardware was not changed.", observing)
    }

    @Synchronized
    fun stop(): CalibrationActionResult {
        return abort("STOPPED_BY_CALLER")
    }

    @Synchronized
    fun abort(reason: String): CalibrationActionResult {
        require(reason.isNotBlank()) { "Calibration interruption reason must not be blank" }
        if (!retryPendingColdProcessTerminal()) {
            return CalibrationActionResult(
                false,
                "PERSIST_FAILED",
                "The interrupted observation still requires durable terminalization.",
                statusWithoutRetry(),
            )
        }
        val session = active
            ?: return CalibrationActionResult(true, "NO_ACTIVE_OBSERVATION", "No observation is active.", status())
        val report = finish(session, completed = false, interruptionReason = reason)
        val persisted = active !== session
        return CalibrationActionResult(
            persisted,
            if (persisted) "OBSERVATION_STOPPED" else "PERSIST_FAILED",
            if (persisted) {
                "Observation stopped; hardware was not changed."
            } else {
                "Hardware was not changed, but terminal observation truth could not be persisted."
            },
            report,
        )
    }

    @Synchronized
    fun status(): CalibrationReport {
        retryPendingColdProcessTerminal()
        return statusWithoutRetry()
    }

    @Synchronized
    fun shutdown() {
        active?.let { finish(it, completed = false, interruptionReason = "SERVICE_DESTROYED") }
        future?.cancel(false)
        future = null
        executor.shutdownNow()
    }

    private fun sampleOnce() {
        val session = synchronized(this) { active } ?: return
        val live = runCatching { capabilities() }.getOrElse {
            synchronized(this) {
                active?.takeIf { current -> current === session }?.let { current ->
                    finish(current, completed = false, interruptionReason = "CAPABILITY_READ_FAILED")
                }
            }
            return
        }
        val light = runCatching { telemetryProbe.lightTelemetry(live) }.getOrElse {
            synchronized(this) {
                active?.takeIf { current -> current === session }?.let { current ->
                    finish(current, completed = false, interruptionReason = "LIGHT_TELEMETRY_READ_FAILED")
                }
            }
            return
        }
        synchronized(this) {
            if (active !== session) return
            val elapsed = (nowElapsedMs() - session.startedElapsedMs).coerceAtLeast(0L)
            val foregroundPackageName = foregroundPackage()
            val frame = if (foregroundPackageName == session.run.packageName) {
                frameTelemetry.read(session.run.packageName)
            } else {
                FrameTelemetryReading(FrameTelemetryStatus.WORKLOAD_NOT_FOREGROUND)
            }
            fun sampleFrom(
                telemetry: TelemetrySnapshot,
                hardware: CalibrationHardwareVector? = null,
                targetContractValid: Boolean = false,
            ) = CalibrationSample(
                elapsedMs = elapsed,
                cpuTempC = telemetry.cpuTempC,
                gpuTempC = telemetry.gpuTempC,
                batteryTempDeciC = telemetry.batteryTempDeciC,
                batteryPowerMw = telemetry.batteryPowerMw,
                batteryStatus = telemetry.batteryStatus,
                externalPowerState = telemetry.externalPowerState,
                thermalStatus = telemetry.thermalStatus,
                gpuBusyPercent = telemetry.gpuBusyPercent,
                fanRpm = telemetry.fanRpm,
                foregroundPackageName = foregroundPackageName,
                frameStatus = frame.status.name,
                frameLayerName = frame.layerName,
                frameDiagnostic = frame.diagnostic,
                frameAverageFpsMilli = frame.averageFpsMilli,
                frameP50TimeUs = frame.p50FrameTimeUs,
                frameP95TimeUs = frame.p95FrameTimeUs,
                frameSlow20FpsPercent = frame.slow20FpsPercent,
                frameSlow30FpsPercent = frame.slow30FpsPercent,
                targetContractValid = targetContractValid,
                hardware = hardware,
            )
            var sample = sampleFrom(
                light,
                targetContractValid = session.targetContractLatch.isFresh(elapsed),
            )
            val prospective = session.run.copy(samples = session.samples + sample)
            val prospectiveWindow = CalibrationQualificationWindow.from(prospective)
            val requiredDurationMs = session.run.requestedDurationSeconds * 1_000L
            val prospectiveCompletion = CalibrationObservationCompletionPolicy.decide(
                elapsedMs = elapsed,
                qualifiedDurationMs = prospectiveWindow.qualifiedDurationMs,
                requiredDurationMs = requiredDurationMs,
            )
            val admissionCheckpoint = prospectiveWindow.admissionSample?.elapsedMs == elapsed
            val terminalCheckpoint = prospectiveCompletion != CalibrationObservationCompletion.CONTINUE
            val thermalCheckpoint = CalibrationThermalSafety.interruptionReason(sample) != null
            val targetFrameCheckpoint = foregroundPackageName == session.run.packageName &&
                frame.status == FrameTelemetryStatus.AVAILABLE &&
                prospectiveWindow.admissionSample == null
            val fullCheckpoint = CalibrationVectorCheckpointPolicy.requiresFullVector(
                elapsedMs = elapsed,
                lastFullCheckpointAttemptElapsedMs = session.lastFullCheckpointAttemptElapsedMs,
                admissionCheckpoint = admissionCheckpoint,
                terminalCheckpoint = terminalCheckpoint || targetFrameCheckpoint,
                thermalCheckpoint = thermalCheckpoint,
            )
            if (fullCheckpoint) {
                // A missed periodic privileged probe retries only at the next bounded checkpoint,
                // never on every light one-hertz safety tick.
                session.lastFullCheckpointAttemptElapsedMs = elapsed
                val full = runCatching { telemetryProbe.fullTelemetry(live) }.getOrNull()
                if (full == null) {
                    session.targetContractLatch.invalidate()
                    sample = sample.copy(targetContractValid = false)
                    session.consecutiveFullVectorMisses = CalibrationVectorCheckpointPolicy.nextMissCount(
                        session.consecutiveFullVectorMisses,
                    )
                    session.samples += sample
                    val requiredCheckpoint = session.lastFullVectorElapsedMs == null || admissionCheckpoint ||
                        terminalCheckpoint || thermalCheckpoint
                    if (CalibrationVectorCheckpointPolicy.mustAbortAfterMiss(
                            requiredCheckpoint,
                            session.consecutiveFullVectorMisses,
                        )
                    ) {
                        val thermalReason = if (thermalCheckpoint) CalibrationThermalSafety.interruptionReason(sample) else null
                        finish(
                            session,
                            completed = false,
                            interruptionReason = thermalReason ?: if (requiredCheckpoint) {
                                "PROVENANCE_VECTOR_REQUIRED_UNAVAILABLE"
                            } else {
                                "PROVENANCE_VECTOR_MISSED_3_TIMES"
                            },
                        )
                    }
                    return
                }
                session.lastFullVectorElapsedMs = elapsed
                session.consecutiveFullVectorMisses = 0
                // Raw safety fields come from the hottest-zone light sample. The full witness uses
                // the stable pre-profile sensors only for comparable provenance.
                val fullVector = full.toCalibrationHardwareVector(
                    capturedAtElapsedMs = elapsed,
                    buildFingerprint = session.run.buildFingerprint,
                )
                val preProfileHardware = session.run.preProfileHardware
                val targetIdentityValid = preProfileHardware != null &&
                    HardwareFingerprint.create(live) == session.run.hardwareFingerprint &&
                    fullVector.bootId == preProfileHardware.bootId &&
                    fullVector.buildFingerprint == session.run.buildFingerprint
                val targetVectorValid = targetIdentityValid &&
                    CalibrationProvenanceValidator.validate(
                        profileId = session.run.profileId,
                        candidate = session.plan,
                        admission = fullVector,
                        terminal = fullVector,
                        periodic = emptyList(),
                        expectedThermalInventory = session.run.preProfileHardware?.thermalInventory,
                    ).isEmpty() &&
                    session.appliedTargetContract?.violations(fullVector).orEmpty().isEmpty()
                session.targetContractLatch.record(elapsed, targetVectorValid)
                sample = sampleFrom(
                    light,
                    fullVector,
                    targetContractValid = targetVectorValid,
                )
                if (CalibrationTargetContractDriftPolicy.mustAbort(targetVectorValid)) {
                    session.samples += sample
                    finish(
                        session,
                        completed = false,
                        interruptionReason = CalibrationThermalSafety.interruptionReason(sample)
                            ?: "TARGET_CONTRACT_DRIFT",
                    )
                    return
                }
            }
            session.samples += sample
            CalibrationThermalSafety.interruptionReason(sample)?.let { reason ->
                finish(session, completed = false, interruptionReason = reason)
                return
            }
            val currentRun = session.run.copy(samples = session.samples.toList())
            val admittedWindow = CalibrationQualificationWindow.from(currentRun)
            when (
                CalibrationObservationCompletionPolicy.decide(
                    elapsedMs = elapsed,
                    qualifiedDurationMs = admittedWindow.qualifiedDurationMs,
                    requiredDurationMs = requiredDurationMs,
                )
            ) {
                CalibrationObservationCompletion.COMPLETE ->
                    finish(session, completed = true, interruptionReason = null)
                CalibrationObservationCompletion.ADMISSION_TIMEOUT ->
                    finish(
                        session,
                        completed = false,
                        interruptionReason = "QUALIFICATION_ADMISSION_TIMEOUT",
                    )
                CalibrationObservationCompletion.CONTINUE -> {
                    if (session.samples.size % PERSIST_EVERY_SAMPLES == 0) {
                        store.saveIfCurrent(session.run.observationId, report(session, true))
                    }
                }
            }
        }
    }

    private fun finish(
        session: ActiveSession,
        completed: Boolean,
        interruptionReason: String?,
    ): CalibrationReport {
        future?.cancel(false)
        future = null
        val completedRun = session.run.copy(
            samples = session.samples.toList(),
            completed = completed,
            interruptionReason = interruptionReason,
        )
        val attestation = CalibrationQualificationWindow.from(completedRun).toEvidence()
        val report = evaluator.evaluate(
            completedRun,
            session.plan,
            evidence = attestation,
        )
        if (!store.saveIfCurrent(session.run.observationId, report)) {
            // Keep the in-memory observation terminally blocked. A later explicit abort or
            // process restart retries/reconciles durable truth instead of claiming completion.
            return report
        }
        active = null
        onFinished(report)
        return report
    }

    private fun report(session: ActiveSession, observing: Boolean): CalibrationReport = evaluator.evaluate(
        run = session.run.copy(samples = session.samples.toList()),
        candidate = session.plan,
        observing = observing,
    )

    private fun retryPendingColdProcessTerminal(): Boolean {
        val terminal = pendingColdProcessTerminal ?: return true
        if (!store.saveIfCurrent(terminal.observationId, terminal)) return false
        pendingColdProcessTerminal = null
        return true
    }

    private fun statusWithoutRetry(): CalibrationReport =
        active?.let { report(it, true) }
            ?: pendingColdProcessTerminal
            ?: store.load()
            ?: evaluator.notStarted()

    companion object {
        const val MIN_DURATION_SECONDS = 30
        const val MAX_DURATION_SECONDS = 1_800
        // Hardware plus frame reads are synchronous; fixed delay prevents overlap while targeting ~1 Hz.
        private const val SAMPLE_DELAY_MS = 650L
        private const val PRE_PROFILE_BASELINE_DWELL_MS = 250L
        private const val MAX_BASELINE_DIAGNOSTIC_REASONS = 4
        private const val MAX_TARGET_DIAGNOSTIC_REASONS = 4
        private const val PERSIST_EVERY_SAMPLES = 15
        internal const val WORKLOAD_ATTESTATION_PERCENT = 90
        internal const val FRAME_ATTESTATION_PERCENT = 90
        private const val OBSERVATION_RUNNING_BLOCKER =
            "Observation is still running; no calibration decision is available."
    }
}
