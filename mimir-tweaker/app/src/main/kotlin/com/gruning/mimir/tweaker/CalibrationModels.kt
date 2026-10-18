package com.gruning.mimir.tweaker

import java.security.MessageDigest
import kotlin.math.abs

internal enum class CalibrationDisposition {
    NOT_STARTED,
    OBSERVING,
    OBSERVED,
    FAILED,
    CERTIFIED,
}

internal data class CalibrationSample(
    val elapsedMs: Long,
    val cpuTempC: Int?,
    val gpuTempC: Int?,
    val batteryTempDeciC: Int?,
    val batteryPowerMw: Int?,
    val batteryStatus: String?,
    val thermalStatus: Int?,
    val gpuBusyPercent: Int?,
    val fanRpm: Int?,
    val foregroundPackageName: String? = null,
    val frameStatus: String? = null,
    val frameLayerName: String? = null,
    val frameDiagnostic: String? = null,
    val frameAverageFpsMilli: Int? = null,
    val frameP50TimeUs: Int? = null,
    val frameP95TimeUs: Int? = null,
    val frameSlow20FpsPercent: Int? = null,
    val frameSlow30FpsPercent: Int? = null,
    /** Fresh target frame plus full vector passing the active profile contract. */
    val targetContractValid: Boolean = false,
    /** Raw one-hertz hardware vector; retained to expose vendor rebound rather than averaging it away. */
    val hardware: CalibrationHardwareVector? = null,
    val externalPowerState: ExternalPowerState = ExternalPowerState.UNKNOWN,
)

/** Read-only device state captured at baseline, admission, terminal, and bounded periodic checkpoints. */
internal data class CalibrationHardwareVector(
    val schemaVersion: Int,
    val capturedAtElapsedMs: Long,
    val bootId: String,
    val buildFingerprint: String,
    val vendorPerformanceMode: Int?,
    val vendorFanMode: Int?,
    val cpuCurrentKhz: Map<Int, Int>,
    val cpuMinKhz: Map<Int, Int>,
    val cpuMaxKhz: Map<Int, Int>,
    val cpuQosCeilingKhz: Map<Int, Int>,
    val cpuQosTransport: CpuQosTransport,
    val gpuClockHz: Long?,
    val gpuMinPowerLevel: Int?,
    val gpuMaxPowerLevel: Int?,
    val fanDuty: Int?,
    val fanPeriod: Int?,
    val fanRpm: Int?,
    val batteryLevelPercent: Int?,
    val batteryCurrentUa: Int?,
    val batteryVoltageMv: Int?,
    val batteryTempDeciC: Int?,
    val batteryStatus: String?,
    val androidThermalStatus: Int?,
    val cpuThermalZone: ThermalZoneWinner?,
    val gpuThermalZone: ThermalZoneWinner?,
    val thermalInventory: CalibrationThermalInventory?,
    val externalPowerState: ExternalPowerState = ExternalPowerState.UNKNOWN,
)

internal fun TelemetrySnapshot.toCalibrationHardwareVector(
    capturedAtElapsedMs: Long,
    buildFingerprint: String,
): CalibrationHardwareVector =
    CalibrationHardwareVector(
        schemaVersion = CALIBRATION_HARDWARE_VECTOR_SCHEMA,
        capturedAtElapsedMs = capturedAtElapsedMs,
        bootId = bootId.orEmpty(),
        buildFingerprint = buildFingerprint,
        vendorPerformanceMode = vendorPerformanceMode,
        vendorFanMode = vendorFanMode,
        cpuCurrentKhz = cpuCurrentKhz.toSortedMap(),
        cpuMinKhz = cpuMinKhz.toSortedMap(),
        cpuMaxKhz = cpuMaxKhz.toSortedMap(),
        cpuQosCeilingKhz = cpuQosCeilingKhz.toSortedMap(),
        cpuQosTransport = cpuQosTransport,
        gpuClockHz = gpuClockHz,
        gpuMinPowerLevel = gpuMinPowerLevel,
        gpuMaxPowerLevel = gpuMaxPowerLevel,
        fanDuty = fanDuty,
        fanPeriod = fanPeriod,
        fanRpm = fanRpm,
        batteryLevelPercent = batteryLevelPercent,
        batteryCurrentUa = batteryCurrentUa,
        batteryVoltageMv = batteryVoltageMv,
        batteryTempDeciC = batteryTempDeciC,
        batteryStatus = batteryStatus,
        androidThermalStatus = thermalStatus,
        cpuThermalZone = cpuThermalZone,
        gpuThermalZone = gpuThermalZone,
        thermalInventory = thermalInventory,
        externalPowerState = externalPowerState,
    )

internal const val CALIBRATION_HARDWARE_VECTOR_SCHEMA = 2

internal object CalibrationProvenanceValidator {
    /** Pre-profile baseline is structural evidence only; it must never be judged against a later profile target. */
    fun validateBaseline(baseline: CalibrationHardwareVector?): List<String> {
        if (baseline == null) return listOf("Validated pre-profile hardware baseline is missing.")
        val observedPlan = TuningPlan(
            profileId = "baseline",
            packageName = "",
            cpuMaxKhz = baseline.cpuQosCeilingKhz,
            gpuMaxPowerLevel = baseline.gpuMaxPowerLevel,
            fanMode = null,
            calibrated = false,
        )
        return validate(
            profileId = "baseline",
            candidate = observedPlan,
            admission = baseline,
            terminal = baseline,
            periodic = emptyList(),
            expectedThermalInventory = baseline.thermalInventory,
        )
    }

    fun validate(
        profileId: String,
        candidate: TuningPlan,
        admission: CalibrationHardwareVector?,
        terminal: CalibrationHardwareVector?,
        periodic: List<CalibrationHardwareVector>,
        expectedThermalInventory: CalibrationThermalInventory? = null,
    ): List<String> = buildList {
        if (admission == null) add("Calibration admission hardware vector is missing.")
        if (terminal == null) add("Calibration terminal hardware vector is missing.")
        val vectors = listOfNotNull(admission, terminal) + periodic
        if (vectors.isEmpty()) return@buildList
        vectors.forEachIndexed { index, vector ->
            val prefix = "Hardware vector[$index]"
            if (vector.schemaVersion != CALIBRATION_HARDWARE_VECTOR_SCHEMA) add("$prefix schema is unsupported.")
            if (vector.capturedAtElapsedMs < 0L) add("$prefix capture time is invalid.")
            if (vector.bootId.isBlank()) add("$prefix boot identity is missing.")
            if (vector.buildFingerprint.isBlank()) add("$prefix build fingerprint is missing.")
            if (vector.cpuCurrentKhz.keys != vector.cpuMinKhz.keys ||
                vector.cpuCurrentKhz.keys != vector.cpuMaxKhz.keys ||
                vector.cpuCurrentKhz.any { (id, current) ->
                    current <= 0 || vector.cpuMinKhz.getValue(id) <= 0 ||
                        vector.cpuMaxKhz.getValue(id) <= 0 ||
                        vector.cpuMinKhz.getValue(id) > current || current > vector.cpuMaxKhz.getValue(id)
                }
            ) add("$prefix CPU live vector is incoherent.")
            val exactCandidateQos = vector.cpuQosCeilingKhz == candidate.cpuMaxKhz
            val stockUnboundedSharedQos = profileId == "stock" &&
                vector.cpuQosTransport == CpuQosTransport.SHARED_QOS &&
                vector.cpuQosCeilingKhz.keys == candidate.cpuMaxKhz.keys &&
                vector.cpuQosCeilingKhz.values.all { it == Int.MAX_VALUE }
            if (vector.cpuQosCeilingKhz.isEmpty() ||
                vector.cpuQosCeilingKhz.keys != candidate.cpuMaxKhz.keys ||
                (!exactCandidateQos && !stockUnboundedSharedQos)
            ) {
                add("$prefix CPU QoS ceiling does not match the candidate contract.")
            }
            if ((vector.gpuMinPowerLevel == null) != (vector.gpuMaxPowerLevel == null) ||
                (vector.gpuMinPowerLevel != null && vector.gpuMinPowerLevel < 0) ||
                (vector.gpuMaxPowerLevel != null && vector.gpuMaxPowerLevel < 0) ||
                (vector.gpuClockHz != null && vector.gpuClockHz <= 0L) ||
                (candidate.gpuMaxPowerLevel != null && vector.gpuMaxPowerLevel != candidate.gpuMaxPowerLevel)
            ) add("$prefix GPU live vector is incoherent.")
            // fanRpm is deliberately NOT required. Neither supported handheld has a tachometer
            // (FanNodes.tachometerPath), so demanding one here would mark every vector on every
            // shipping device incoherent -- the exact inversion where "absent" gets read as
            // "broken". Duty and period ARE required: those are real, written and read back.
            if (vector.fanDuty == null || vector.fanPeriod == null ||
                vector.fanDuty !in 0..vector.fanPeriod || vector.fanPeriod <= 0
            ) add("$prefix fan live vector is incoherent.")
            // A present reading still has to be sane. Separate message so "no tachometer" and
            // "tachometer talking nonsense" can never be confused in an evidence file.
            if (vector.fanRpm != null && vector.fanRpm < 0) {
                add("$prefix fan tachometer reading is negative.")
            }
            if (vector.batteryLevelPercent != null && vector.batteryLevelPercent !in 0..100) {
                add("$prefix battery state of charge is invalid.")
            }
            if (vector.batteryTempDeciC != null && vector.batteryTempDeciC !in 1..600 ||
                vector.androidThermalStatus != null && vector.androidThermalStatus !in 0..6
            ) add("$prefix battery or Android thermal state is invalid.")
            val zones = listOf(vector.cpuThermalZone, vector.gpuThermalZone)
            if (zones.any { it == null }) add("$prefix thermal-zone witness is incomplete.")
            val inventory = vector.thermalInventory
            if (inventory == null || inventory.cpu.isEmpty() || inventory.gpu.isEmpty()) {
                add("$prefix thermal-zone inventory is incomplete.")
            } else {
                vector.cpuThermalZone?.takeIf { !inventory.containsCpu(it) }?.let {
                    add("$prefix CPU thermal winner is outside its inventory.")
                }
                vector.gpuThermalZone?.takeIf { !inventory.containsGpu(it) }?.let {
                    add("$prefix GPU thermal winner is outside its inventory.")
                }
            }
            zones.filterNotNull().forEach { zone ->
                val expectedCelsius = if (zone.rawTemperature > 1_000L) {
                    (zone.rawTemperature / 1_000L).toInt()
                } else {
                    zone.rawTemperature.toInt()
                }
                if (zone.type.isBlank() || zone.path.isBlank() || zone.rawTemperature <= 0L || zone.celsius !in 1..150 ||
                    zone.celsius != expectedCelsius
                ) {
                    add("$prefix thermal-zone witness is invalid.")
                }
            }
            // Stock never writes the vendor mode (the player's own mode stands), so it requires none.
            // Quiet, Balanced and Performance require the plan's OWN vendorMode, exactly as fan does
            // below; a plan without the axis (vendorMode == null) requires nothing.
            val requiredVendorMode = if (profileId == "stock") null else candidate.vendorMode
            if (requiredVendorMode != null && vector.vendorPerformanceMode != requiredVendorMode) {
                add("$prefix $profileId requires vendor performance_mode=$requiredVendorMode.")
            }
            val requiredFanMode = if (profileId == "stock") {
                TuningPlanner.FAN_SMART
            } else {
                candidate.fanMode
            }
            if (requiredFanMode != null && vector.vendorFanMode != requiredFanMode) {
                add("$prefix $profileId requires vendor fan_mode=$requiredFanMode.")
            }
        }
        val identity = vectors.first()
        if (vectors.any { it.bootId != identity.bootId || it.buildFingerprint != identity.buildFingerprint }) {
            add("Calibration hardware vectors cross boot or firmware identities.")
        }
        if (expectedThermalInventory != null && vectors.any { vector ->
                vector.thermalInventory != expectedThermalInventory ||
                    vector.cpuThermalZone?.let { !expectedThermalInventory.containsCpu(it) } == true ||
                    vector.gpuThermalZone?.let { !expectedThermalInventory.containsGpu(it) } == true
            }
        ) {
            add("Calibration thermal inventory changed or winner left the frozen family set.")
        }
        if (periodic.zipWithNext().any { (previous, next) ->
                next.capturedAtElapsedMs < previous.capturedAtElapsedMs
            }
        ) add("Periodic hardware vector capture times are not monotonic.")
    }
}

/** Pre-workload baseline contract: three coherent full Stock vectors, never a cold first read. */
internal object CalibrationPreProfileBaselinePolicy {
    const val REQUIRED_READS = 3

    /**
     * Exact reasons a candidate baseline cannot be used. The observation start path keeps
     * the last durable report for context, so these reasons are the only trustworthy way
     * for a LAB operator to distinguish a new admission rejection from that older report.
     */
    fun violations(
        vectors: List<CalibrationHardwareVector>,
        stockPlan: TuningPlan,
    ): List<String> = buildList {
        if (vectors.size != REQUIRED_READS) {
            add("baseline read count ${vectors.size} != $REQUIRED_READS")
            return@buildList
        }
        vectors.forEachIndexed { index, vector ->
            CalibrationProvenanceValidator.validateBaseline(vector).distinct().forEach { reason ->
                add("baseline[$index]: $reason")
            }
            CalibrationProvenanceValidator.validate(
                profileId = "stock",
                candidate = stockPlan,
                admission = vector,
                terminal = vector,
                periodic = emptyList(),
                expectedThermalInventory = vector.thermalInventory,
            ).distinct().forEach { reason ->
                add("stock[$index]: $reason")
            }
        }
        val first = vectors.first()
        fun requireCoherent(label: String, selector: (CalibrationHardwareVector) -> Any?) {
            if (vectors.any { selector(it) != selector(first) }) add("baseline $label changed between reads")
        }
        requireCoherent("boot identity") { it.bootId }
        requireCoherent("build fingerprint") { it.buildFingerprint }
        requireCoherent("CPU QoS transport") { it.cpuQosTransport }
        requireCoherent("CPU minimums") { it.cpuMinKhz }
        requireCoherent("CPU maximums") { it.cpuMaxKhz }
        requireCoherent("CPU QoS ceilings") { it.cpuQosCeilingKhz }
        requireCoherent("GPU minimum power level") { it.gpuMinPowerLevel }
        requireCoherent("GPU maximum power level") { it.gpuMaxPowerLevel }
        requireCoherent("vendor performance mode") { it.vendorPerformanceMode }
        requireCoherent("vendor fan mode") { it.vendorFanMode }
        // Stock Smart mode owns PWM dynamically. Every duty sample must remain valid,
        // but requiring one identical duty across the baseline would reject the vendor
        // controller precisely when it is responding normally to temperature.
        requireCoherent("fan period") { it.fanPeriod }
        requireCoherent("thermal inventory") { it.thermalInventory }
    }.distinct()

    fun select(
        vectors: List<CalibrationHardwareVector>,
        stockPlan: TuningPlan,
    ): CalibrationHardwareVector? {
        if (violations(vectors, stockPlan).isNotEmpty()) return null
        return vectors.last()
    }
}

internal object LabPreMutationBaselineDiagnosticValidator {
    fun validate(run: CalibrationRun): List<String> {
        val vectors = run.preMutationBaselineVectors
        val binding = run.preMutationBaselineBinding
        if (vectors.isEmpty() && binding == null) return emptyList()
        if (vectors.size != CalibrationPreProfileBaselinePolicy.REQUIRED_READS || binding == null) {
            return listOf("LAB pre-mutation baseline diagnostic is incomplete.")
        }
        val baseline = run.preProfileHardware
        if (vectors.last() != baseline || run.observationId.isBlank() ||
            run.preMutationBaselineObservationId != run.observationId ||
            binding.planHash != run.preMutationBaselinePlanHash ||
            binding.profileId != run.profileId || binding.packageName != run.packageName ||
            binding.hardwareFingerprint != run.hardwareFingerprint || binding.buildFingerprint != run.buildFingerprint ||
            binding.bootId != baseline?.bootId
        ) return listOf("LAB pre-mutation baseline binding does not match its observation.")
        return emptyList()
    }
}

internal data class CalibrationSafetyEvidence(
    val workloadAttested: Boolean = false,
    val frameTelemetryAttested: Boolean = false,
    val writesExercised: Boolean = false,
    val rollbackAttempts: Int = 0,
    val rollbackSuccesses: Int = 0,
    val rebootRestoreVerified: Boolean = false,
)

internal data class CalibrationRun(
    val profileId: String,
    val packageName: String,
    val hardwareFingerprint: String,
    val buildFingerprint: String = "",
    val startedAtEpochMs: Long,
    val requestedDurationSeconds: Int,
    val samples: List<CalibrationSample>,
    /** Validated full vector captured before this observation owns any profile/workload state. */
    val preProfileHardware: CalibrationHardwareVector? = null,
    /** LAB diagnostic-only raw Stock vectors supplied by the one-shot pre-mutation handoff. */
    val preMutationBaselineVectors: List<CalibrationHardwareVector> = emptyList(),
    val preMutationBaselineBinding: LabPreMutationBaselineBinding? = null,
    /** Exact APPLIED plan identity checked again by the runner before observation starts. */
    val preMutationBaselinePlanHash: String? = null,
    /** The handoff-issued identity must equal this run's fresh observation identity. */
    val preMutationBaselineObservationId: String? = null,
    val completed: Boolean,
    val interruptionReason: String? = null,
    /** Ephemeral observation identity; fences stale terminal writes after a relaunch. */
    val observationId: String = "",
)

internal data class CalibrationReport(
    val disposition: CalibrationDisposition,
    val profileId: String,
    val packageName: String,
    val hardwareFingerprint: String,
    val startedAtEpochMs: Long,
    val requestedDurationSeconds: Int,
    val observedDurationSeconds: Int,
    /** All captured samples, including pre-workload diagnostics retained as raw evidence. */
    val sampleCount: Int,
    /** Samples in the post-first-attested window that qualify for workload aggregates. */
    val qualificationSampleCount: Int = 0,
    /** Raw samples from the first fully attested sample through the end of the run. */
    val qualificationWindowSampleCount: Int = 0,
    val maxCpuTempC: Int?,
    val maxGpuTempC: Int?,
    val maxBatteryTempDeciC: Int?,
    val averageBatteryPowerMw: Int?,
    val maxThermalStatus: Int?,
    val chargingSampleCount: Int,
    val attestedSampleCount: Int,
    val frameSampleCount: Int,
    val frameAttestedSampleCount: Int,
    val medianFrameFpsMilli: Int?,
    val maximumP95FrameTimeUs: Int?,
    val averageSlow20FpsPercent: Int?,
    val averageSlow30FpsPercent: Int?,
    val frameLayerNames: List<String>,
    val candidateCpuMaxKhz: Map<Int, Int>,
    val candidateGpuMaxPowerLevel: Int?,
    val candidateFanMode: Int?,
    val evidence: CalibrationSafetyEvidence,
    val blockers: List<String>,
    val samples: List<CalibrationSample>,
    val observationId: String = "",
    /** Earliest available full vector (not claimed as pre-profile unless supplied by a caller). */
    val baselineHardware: CalibrationHardwareVector? = null,
    /** First stable target+frame admission vector and the final raw vector for A/B provenance. */
    val admissionHardware: CalibrationHardwareVector? = null,
    val terminalHardware: CalibrationHardwareVector? = null,
    val preMutationBaselineVectors: List<CalibrationHardwareVector> = emptyList(),
    val preMutationBaselineBinding: LabPreMutationBaselineBinding? = null,
    val preMutationBaselinePlanHash: String? = null,
    val preMutationBaselineObservationId: String? = null,
    /** Explicitly separate unplugged battery evidence from thermal/fan observation. */
    val externalPowerSampleCount: Int = 0,
    val batteryEfficiencyEligible: Boolean = false,
    val batteryEfficiencyBlockers: List<String> = emptyList(),
)

internal data class CalibrationActionResult(
    val success: Boolean,
    val code: String,
    val message: String,
    val report: CalibrationReport,
)

/** Raw hardware safety is never conditional on foreground or frame attestation. */
internal object CalibrationThermalSafety {
    const val MAX_CPU_TEMP_C = 85
    const val MAX_GPU_TEMP_C = 85
    const val MAX_BATTERY_TEMP_DECI_C = 450
    const val MAX_ANDROID_THERMAL_STATUS = 2

    fun interruptionReason(sample: CalibrationSample): String? = when {
        (sample.cpuTempC ?: Int.MIN_VALUE) > MAX_CPU_TEMP_C -> "THERMAL_CPU_LIMIT_EXCEEDED"
        (sample.gpuTempC ?: Int.MIN_VALUE) > MAX_GPU_TEMP_C -> "THERMAL_GPU_LIMIT_EXCEEDED"
        (sample.batteryTempDeciC ?: Int.MIN_VALUE) > MAX_BATTERY_TEMP_DECI_C ->
            "THERMAL_BATTERY_LIMIT_EXCEEDED"
        (sample.thermalStatus ?: Int.MIN_VALUE) > MAX_ANDROID_THERMAL_STATUS ->
            "THERMAL_ANDROID_STATUS_EXCEEDED"
        else -> null
    }
}

internal interface CalibrationReportStore {
    fun load(): CalibrationReport?
    fun save(report: CalibrationReport): Boolean

    /** Replaces any terminal/stale report before a newly admitted observation is sampled. */
    fun beginObservation(report: CalibrationReport): Boolean = save(report)

    /**
     * A late sampler may only update the report it began. Implementations with durable state
     * override this atomically; the conservative default still refuses a mismatched identity.
     */
    fun saveIfCurrent(observationId: String, report: CalibrationReport): Boolean =
        load()?.observationId == observationId && save(report)

    fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport?
    fun certifiedProfileIds(hardwareFingerprint: String): Set<String>
}

internal object HardwareFingerprint {
    fun create(capabilities: HardwareCapabilities): String {
        val canonical = buildString {
            append(capabilities.manufacturer.trim().lowercase())
            append('|').append(capabilities.model.trim().lowercase())
            append('|').append(capabilities.socModel.trim().lowercase())
            append('|').append(capabilities.deviceProfileId.trim().lowercase())
            append('|').append(capabilities.buildFingerprint.trim())
            capabilities.cpuPolicies.sortedBy { it.id }.forEach { policy ->
                append("|cpu").append(policy.id).append('=')
                append(policy.cpuIds.joinToString(","))
                append(':').append(policy.availableFrequenciesKhz.joinToString(","))
            }
            append("|gpu=").append(capabilities.gpuFrequenciesKhz.joinToString(","))
            append("|display=").append(capabilities.refreshRatesHz.joinToString(","))
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal class CalibrationEvaluator(
    private val criteria: Criteria = Criteria(),
) {
    data class Criteria(
        val minimumDurationSeconds: Int = 300,
        val minimumSampleCoveragePercent: Int = 90,
        val maximumCpuTempC: Int = CalibrationThermalSafety.MAX_CPU_TEMP_C,
        val maximumGpuTempC: Int = CalibrationThermalSafety.MAX_GPU_TEMP_C,
        val maximumBatteryTempDeciC: Int = CalibrationThermalSafety.MAX_BATTERY_TEMP_DECI_C,
        val maximumThermalStatus: Int = CalibrationThermalSafety.MAX_ANDROID_THERMAL_STATUS,
        val minimumRollbackAttempts: Int = 3,
    )

    fun evaluate(
        run: CalibrationRun,
        candidate: TuningPlan,
        evidence: CalibrationSafetyEvidence = CalibrationSafetyEvidence(),
        observing: Boolean = false,
    ): CalibrationReport {
        val qualification = CalibrationQualificationWindow.from(run)
        val durationSeconds = qualification.qualifiedDurationMs
            .div(1_000L)
            .toInt()
        // These maxima are safety evidence, not workload-quality aggregates: retain every raw
        // sample, including startup and later attestation gaps.
        val maxCpu = run.samples.mapNotNull { it.cpuTempC }.maxOrNull()
        val maxGpu = run.samples.mapNotNull { it.gpuTempC }.maxOrNull()
        val maxBattery = run.samples.mapNotNull { it.batteryTempDeciC }.maxOrNull()
        val maxThermal = run.samples.mapNotNull { it.thermalStatus }.maxOrNull()
        val chargingSamples = qualification.samples.count {
            !it.batteryStatus.equals("Discharging", ignoreCase = true)
        }
        val externalPowerSamples = qualification.samples.count {
            it.externalPowerState != ExternalPowerState.OFFLINE
        }
        val powerSamples = qualification.samples.mapNotNull { sample ->
            sample.batteryPowerMw
                ?.takeIf {
                    sample.batteryStatus.equals("Discharging", ignoreCase = true) &&
                        sample.externalPowerState == ExternalPowerState.OFFLINE
                }
                ?.let(::abs)
        }
        val batteryEfficiencyBlockers = batteryEfficiencyBlockers(
            chargingSamples = chargingSamples,
            externalPowerSamples = externalPowerSamples,
            powerSampleCount = powerSamples.size,
            qualificationSampleCount = qualification.samples.size,
        )
        val averagePower = powerSamples
            .takeIf { it.isNotEmpty() && batteryEfficiencyBlockers.isEmpty() }
            ?.average()
            ?.toInt()
        val attestedSamples = run.samples.count { it.foregroundPackageName == run.packageName }
        val frameSamples = run.samples.filter { it.frameStatus == FrameTelemetryStatus.AVAILABLE.name }
        val frameAttestedSamples = frameSamples.count { it.foregroundPackageName == run.packageName }
        val qualifiedFrameSamples = qualification.samples.filter {
            it.frameStatus == FrameTelemetryStatus.AVAILABLE.name
        }
        val frameFps = qualifiedFrameSamples.mapNotNull { it.frameAverageFpsMilli }.sorted()
        val medianFrameFps = frameFps.takeIf(List<Int>::isNotEmpty)?.let(::median)
        val maximumP95FrameTime = qualifiedFrameSamples.mapNotNull { it.frameP95TimeUs }.maxOrNull()
        val averageSlow20 = qualifiedFrameSamples.mapNotNull { it.frameSlow20FpsPercent }.averageInt()
        val averageSlow30 = qualifiedFrameSamples.mapNotNull { it.frameSlow30FpsPercent }.averageInt()
        val frameLayerNames = qualifiedFrameSamples.mapNotNull { it.frameLayerName }.distinct()
        val baselineHardware = run.preProfileHardware
        val admissionHardware = qualification.admissionSample?.hardware
        val terminalHardware = run.samples.lastOrNull()?.hardware
        val provenanceBlockers = LabPreMutationBaselineDiagnosticValidator.validate(run) +
            CalibrationProvenanceValidator.validateBaseline(baselineHardware) +
            CalibrationProvenanceValidator.validate(
            profileId = run.profileId,
            candidate = candidate,
            admission = admissionHardware,
            terminal = terminalHardware,
            periodic = run.samples.mapNotNull { it.hardware },
            expectedThermalInventory = baselineHardware?.thermalInventory,
        )
        val blockers = if (observing) {
            listOf("Observation is still running; no calibration decision is available.")
        } else {
            blockers(
                run = run,
                durationSeconds = durationSeconds,
                maxCpu = maxCpu,
                maxGpu = maxGpu,
                maxBattery = maxBattery,
                maxThermal = maxThermal,
                qualificationSampleCount = qualification.samples.size,
                evidence = evidence,
            ) + provenanceBlockers
        }
        val thresholdFailure = (maxCpu ?: 0) > criteria.maximumCpuTempC ||
            (maxGpu ?: 0) > criteria.maximumGpuTempC ||
            (maxBattery ?: 0) > criteria.maximumBatteryTempDeciC ||
            (maxThermal ?: 0) > criteria.maximumThermalStatus ||
            (run.completed && durationSeconds < run.requestedDurationSeconds) ||
            (!observing && provenanceBlockers.isNotEmpty()) ||
            run.interruptionReason != null
        // TW04-A: local observation/evidence may never emit CERTIFIED. Cap at OBSERVED/FAILED.
        val disposition = when {
            observing -> CalibrationDisposition.OBSERVING
            thresholdFailure -> CalibrationDisposition.FAILED
            else -> CalibrationDisposition.OBSERVED
        }
        return CalibrationReport(
            disposition = disposition,
            profileId = run.profileId,
            packageName = run.packageName,
            hardwareFingerprint = run.hardwareFingerprint,
            startedAtEpochMs = run.startedAtEpochMs,
            requestedDurationSeconds = run.requestedDurationSeconds,
            observedDurationSeconds = durationSeconds,
            sampleCount = run.samples.size,
            qualificationSampleCount = qualification.samples.size,
            qualificationWindowSampleCount = qualification.windowSampleCount,
            maxCpuTempC = maxCpu,
            maxGpuTempC = maxGpu,
            maxBatteryTempDeciC = maxBattery,
            averageBatteryPowerMw = averagePower,
            maxThermalStatus = maxThermal,
            chargingSampleCount = chargingSamples,
            attestedSampleCount = attestedSamples,
            frameSampleCount = frameSamples.size,
            frameAttestedSampleCount = frameAttestedSamples,
            medianFrameFpsMilli = medianFrameFps,
            maximumP95FrameTimeUs = maximumP95FrameTime,
            averageSlow20FpsPercent = averageSlow20,
            averageSlow30FpsPercent = averageSlow30,
            frameLayerNames = frameLayerNames,
            candidateCpuMaxKhz = candidate.cpuMaxKhz,
            candidateGpuMaxPowerLevel = candidate.gpuMaxPowerLevel,
            candidateFanMode = candidate.fanMode,
            evidence = evidence,
            blockers = blockers,
            samples = run.samples,
            observationId = run.observationId,
            baselineHardware = baselineHardware,
            admissionHardware = admissionHardware,
            terminalHardware = terminalHardware,
            preMutationBaselineVectors = run.preMutationBaselineVectors,
            preMutationBaselineBinding = run.preMutationBaselineBinding,
            preMutationBaselinePlanHash = run.preMutationBaselinePlanHash,
            preMutationBaselineObservationId = run.preMutationBaselineObservationId,
            externalPowerSampleCount = externalPowerSamples,
            batteryEfficiencyEligible = batteryEfficiencyBlockers.isEmpty(),
            batteryEfficiencyBlockers = batteryEfficiencyBlockers,
        )
    }

    fun notStarted(): CalibrationReport = CalibrationReport(
        disposition = CalibrationDisposition.NOT_STARTED,
        profileId = "",
        packageName = "",
        hardwareFingerprint = "",
        startedAtEpochMs = 0L,
        requestedDurationSeconds = 0,
        observedDurationSeconds = 0,
        sampleCount = 0,
        qualificationSampleCount = 0,
        qualificationWindowSampleCount = 0,
        maxCpuTempC = null,
        maxGpuTempC = null,
        maxBatteryTempDeciC = null,
        averageBatteryPowerMw = null,
        maxThermalStatus = null,
        chargingSampleCount = 0,
        attestedSampleCount = 0,
        frameSampleCount = 0,
        frameAttestedSampleCount = 0,
        medianFrameFpsMilli = null,
        maximumP95FrameTimeUs = null,
        averageSlow20FpsPercent = null,
        averageSlow30FpsPercent = null,
        frameLayerNames = emptyList(),
        candidateCpuMaxKhz = emptyMap(),
        candidateGpuMaxPowerLevel = null,
        candidateFanMode = null,
        evidence = CalibrationSafetyEvidence(),
        blockers = listOf("No SM8250 handheld calibration observation has been recorded."),
        samples = emptyList(),
    )

    private fun blockers(
        run: CalibrationRun,
        durationSeconds: Int,
        maxCpu: Int?,
        maxGpu: Int?,
        maxBattery: Int?,
        maxThermal: Int?,
        qualificationSampleCount: Int,
        evidence: CalibrationSafetyEvidence,
    ): List<String> = buildList {
        run.interruptionReason?.let { add("Observation was interrupted: $it") }
        if (!run.completed) add("Observation did not reach its requested duration.")
        if (durationSeconds < criteria.minimumDurationSeconds) {
            add("Observe for at least ${criteria.minimumDurationSeconds} seconds under a stable workload.")
        }
        val requiredSamples = criteria.minimumDurationSeconds * criteria.minimumSampleCoveragePercent / 100
        if (qualificationSampleCount < requiredSamples) {
            add("At least $requiredSamples attested one-second telemetry samples are required.")
        }
        if (maxCpu == null) add("CPU temperature telemetry is missing.")
        if (maxGpu == null) add("GPU temperature telemetry is missing.")
        if (maxBattery == null) add("Battery temperature telemetry is missing.")
        if ((maxCpu ?: 0) > criteria.maximumCpuTempC) add("CPU temperature exceeded ${criteria.maximumCpuTempC} C.")
        if ((maxGpu ?: 0) > criteria.maximumGpuTempC) add("GPU temperature exceeded ${criteria.maximumGpuTempC} C.")
        if ((maxBattery ?: 0) > criteria.maximumBatteryTempDeciC) {
            add("Battery temperature exceeded ${criteria.maximumBatteryTempDeciC / 10.0} C.")
        }
        if ((maxThermal ?: 0) > criteria.maximumThermalStatus) add("Android thermal status exceeded MODERATE.")
        if (!evidence.workloadAttested) add("The requested workload package has not been attested in the foreground.")
        if (!evidence.frameTelemetryAttested) add("Trustworthy foreground frame telemetry coverage is insufficient.")
        if (!evidence.writesExercised) add("The candidate profile has not been exercised through the protected write path.")
        if (evidence.rollbackAttempts < criteria.minimumRollbackAttempts) {
            add("At least ${criteria.minimumRollbackAttempts} rollback drills are required.")
        }
        if (evidence.rollbackSuccesses != evidence.rollbackAttempts) add("Every rollback drill must restore and verify exactly.")
        if (!evidence.rebootRestoreVerified) add("Restore-after-process-death/reboot has not been verified.")
    }

    private fun batteryEfficiencyBlockers(
        chargingSamples: Int,
        externalPowerSamples: Int,
        powerSampleCount: Int,
        qualificationSampleCount: Int,
    ): List<String> = buildList {
        val requiredSamples = criteria.minimumDurationSeconds * criteria.minimumSampleCoveragePercent / 100
        if (qualificationSampleCount < requiredSamples) {
            add("Battery-efficiency evidence needs at least $requiredSamples qualified samples.")
        }
        if (externalPowerSamples > 0) {
            add("Battery-efficiency evidence requires external power offline; Charge Separation is not sufficient.")
        }
        if (chargingSamples > 0) {
            add("Battery-efficiency evidence must be captured while the battery is discharging.")
        }
        if (powerSampleCount < requiredSamples) {
            add("Unplugged battery-power telemetry coverage is insufficient.")
        }
    }

    private fun median(sorted: List<Int>): Int = if (sorted.size % 2 == 1) {
        sorted[sorted.size / 2]
    } else {
        ((sorted[sorted.size / 2 - 1].toLong() + sorted[sorted.size / 2]) / 2L).toInt()
    }

    private fun List<Int>.averageInt(): Int? = takeIf(List<Int>::isNotEmpty)?.average()?.toInt()
}

/**
 * The initial service/UI sample often predates the actual game Surface. Keep it as evidence, but
 * begin qualification only once three consecutive samples prove both the requested foreground
 * and a live frame layer. Any later loss of either proof remains in the window and counts
 * against coverage.
 */
internal data class CalibrationQualificationWindow(
    val samples: List<CalibrationSample>,
    val windowSampleCount: Int,
    val preflightSamples: List<CalibrationSample>,
    val qualifiedDurationMs: Long,
    val admissionSample: CalibrationSample?,
) {
    companion object {
        fun from(run: CalibrationRun): CalibrationQualificationWindow {
            val firstAttested = run.samples.indices.firstOrNull { start ->
                start + MIN_CONSECUTIVE_ATTESTED_SAMPLES <= run.samples.size &&
                    (start until start + MIN_CONSECUTIVE_ATTESTED_SAMPLES).all { index ->
                        val sample = run.samples[index]
                        sample.foregroundPackageName == run.packageName &&
                            sample.frameStatus == FrameTelemetryStatus.AVAILABLE.name &&
                            sample.targetContractValid
                    }
            }
            if (firstAttested == null) {
                return CalibrationQualificationWindow(
                    samples = emptyList(),
                    windowSampleCount = 0,
                    preflightSamples = run.samples,
                    qualifiedDurationMs = 0L,
                    admissionSample = null,
                )
            }
            val window = run.samples.drop(firstAttested)
            val qualified = window.filter { sample ->
                sample.foregroundPackageName == run.packageName &&
                    sample.frameStatus == FrameTelemetryStatus.AVAILABLE.name &&
                    sample.targetContractValid
            }
            return CalibrationQualificationWindow(
                samples = qualified,
                windowSampleCount = window.size,
                preflightSamples = run.samples.take(firstAttested),
                qualifiedDurationMs = qualified.lastOrNull()?.elapsedMs
                    ?.minus(qualified.firstOrNull()?.elapsedMs ?: 0L)
                    ?.coerceAtLeast(0L)
                    ?: 0L,
                // Admission is earned only by the third confirming target/frame sample.
                admissionSample = run.samples[firstAttested + MIN_CONSECUTIVE_ATTESTED_SAMPLES - 1],
            )
        }

        private const val MIN_CONSECUTIVE_ATTESTED_SAMPLES = 3
    }
}

internal fun CalibrationQualificationWindow.toEvidence(): CalibrationSafetyEvidence =
    CalibrationSafetyEvidence(
        workloadAttested = windowSampleCount > 0 &&
            samples.size * 100 / windowSampleCount >= CalibrationSessionRunner.WORKLOAD_ATTESTATION_PERCENT,
        frameTelemetryAttested = windowSampleCount > 0 &&
            samples.size * 100 / windowSampleCount >= CalibrationSessionRunner.FRAME_ATTESTATION_PERCENT,
    )
