package com.gruning.mimir.tweaker

import android.os.Bundle

internal fun HardwareCapabilities.toBundle(): Bundle = Bundle().apply {
    putString("status", status.name)
    putString("manufacturer", manufacturer)
    putString("model", model)
    putString("socModel", socModel)
    putString("buildFingerprint", buildFingerprint)
    putString("hardwareFingerprint", HardwareFingerprint.create(this@toBundle))
    putString("deviceProfileId", deviceProfileId)
    hardwareFamilyId?.let { putString("hardwareFamilyId", it) }
    profileTreatment?.let { putString("profileTreatment", it) }
    putStringArrayList("supportedAxes", ArrayList(supportedAxes.map { it.name }))
    putBoolean("pServerAvailable", pServerAvailable)
    putBoolean("gpuNodeAvailable", gpuNodeAvailable)
    putBoolean("fanNodeAvailable", fanNodeAvailable)
    putIntegerArrayList("refreshRatesHz", ArrayList(refreshRatesHz))
    putStringArrayList(
        "cpuPolicies",
        ArrayList(cpuPolicies.map { policy ->
            "policy${policy.id} cpus=${policy.cpuIds.joinToString(",")} " +
                "min=${policy.minKhz} max=${policy.maxKhz} current=${policy.currentKhz} " +
                "opps=${policy.availableFrequenciesKhz.size}"
        }),
    )
    putStringArrayList("notes", ArrayList(notes))
    // Fail-closed product IDs only; never mirror local/lab CERTIFIED reports.
    putStringArrayList("certifiedProfileIds", ArrayList(certifiedProfileIds))
    putStringArrayList("qualifiedProfileIds", ArrayList(certifiedProfileIds))
    // Chipset-support wave stage 1: disjoint from certifiedProfileIds -- honesty-labelled
    // uncertified support, never a signed-manifest claim. See SupportedUncertifiedProfiles.
    putStringArrayList("supportedUncertifiedProfileIds", ArrayList(supportedUncertifiedProfileIds))
    putString("posture", posture)
    putBoolean("mutationAvailable", mutationAvailable)
    putBoolean("dryRun", dryRun)
    putString("qualificationSource", qualificationSource)
    val diagnosticProfiles = if (
        posture == TweakerPostureTruth.LAB &&
        BuildConfig.DIAGNOSTICS_COMPILED &&
        BuildConfig.HARDWARE_WRITES_COMPILED &&
        !dryRun &&
        usageAccessGranted == true &&
        status != CapabilityStatus.UNSUPPORTED &&
        pServerAvailable
    ) {
        TuningPlanner().profileIds()
            .filter { it in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS }
            .sorted()
    } else {
        emptyList()
    }
    // Explicitly separate owner-validation authority from product certification. Only a
    // debuggable Mimir may consume this LAB field; SAFE/Production always emit an empty list.
    putStringArrayList("diagnosticProfileIds", ArrayList(diagnosticProfiles))
    putBoolean("diagnosticMutationAvailable", diagnosticProfiles.isNotEmpty())
    if (usageAccessGranted != null) {
        putBoolean("usageAccessGranted", usageAccessGranted)
        putBoolean("hasUsageAccessGranted", true)
    } else {
        putBoolean("hasUsageAccessGranted", false)
    }
}

internal fun TelemetrySnapshot.toBundle(): Bundle = Bundle().apply {
    putStringArrayList("cpuCurrentKhz", cpuCurrentKhz.toRows())
    putStringArrayList("cpuMinKhz", cpuMinKhz.toRows())
    putStringArrayList("cpuMaxKhz", cpuMaxKhz.toRows())
    gpuClockHz?.let { putLong("gpuClockHz", it) }
    gpuBusyPercent?.let { putInt("gpuBusyPercent", it) }
    gpuMinPowerLevel?.let { putInt("gpuMinPowerLevel", it) }
    gpuMaxPowerLevel?.let { putInt("gpuMaxPowerLevel", it) }
    fanDuty?.let { putInt("fanDuty", it) }
    fanPeriod?.let { putInt("fanPeriod", it) }
    fanRpm?.let { putInt("fanRpm", it) }
    vendorFanMode?.let { putInt("vendorFanMode", it) }
    cpuTempC?.let { putInt("cpuTempC", it) }
    cpuPackageTempC?.let { putInt("cpuPackageTempC", it) }
    cpuPackageTempZoneCount?.let { putInt("cpuPackageTempZoneCount", it) }
    gpuTempC?.let { putInt("gpuTempC", it) }
    batteryLevelPercent?.let { putInt("batteryLevelPercent", it) }
    batteryCurrentUa?.let { putInt("batteryCurrentUa", it) }
    batteryVoltageMv?.let { putInt("batteryVoltageMv", it) }
    batteryPowerMw?.let { putInt("batteryPowerMw", it) }
    batteryTempDeciC?.let { putInt("batteryTempDeciC", it) }
    batteryStatus?.let { putString("batteryStatus", it) }
    putString("externalPowerState", externalPowerState.name)
    thermalStatus?.let { putInt("thermalStatus", it) }
}

internal fun TransactionResult.toBundle(): Bundle = Bundle().apply {
    putBoolean("success", success)
    putBoolean("applied", applied)
    putBoolean("restored", restored)
    putString("code", code)
    putString("message", message)
    transactionId?.let { putString("transactionId", it) }
    requestId?.let { putString("requestId", it) }
    generation?.let { putLong("generation", it) }
    requestDeadlineElapsedRealtimeMs?.let { putLong("requestDeadlineElapsedRealtimeMs", it) }
    hardLeaseDeadlineElapsedRealtimeMs?.let { putLong("hardLeaseDeadlineElapsedRealtimeMs", it) }
    plan?.let { plan ->
        putString("profileId", plan.profileId)
        putString("packageName", plan.packageName)
        putStringArrayList("cpuMaxKhz", plan.cpuMaxKhz.toRows())
        plan.gpuMaxPowerLevel?.let { putInt("gpuMaxPowerLevel", it) }
        plan.fanMode?.let { putInt("fanMode", it) }
        putBoolean("calibrated", plan.calibrated)
    }
}

/**
 * Versioned service result. Deliberately excludes provisional plan/profile details while
 * returning exact request/session/lease truth.
 */
internal fun TransactionResult.toPublicBundle(
    callerRequestId: String,
    callerDeadlineElapsedRealtimeMs: Long,
    truth: PublicSessionTruth,
): Bundle = publicStatusBundle(
    success = success,
    code = code,
    message = message,
    callerRequestId = callerRequestId,
    callerDeadlineElapsedRealtimeMs = callerDeadlineElapsedRealtimeMs,
    truth = truth,
).apply {
    putBoolean("applied", applied)
    putBoolean("restored", restored)
    // A successful restore result still describes the transaction that was just retired. Never
    // backfill that stale identity into authoritative Stock truth: clients correctly reject Stock
    // carrying an owner, generation, transaction, or lease. Active/recovery truth may use the result
    // identity only when the current service state says a mutation can still exist.
    if (truth.mutationMayBeActive) {
        val resultRequestId = this@toPublicBundle.requestId
        val resultGeneration = this@toPublicBundle.generation
        val resultTransactionId = this@toPublicBundle.transactionId
        val resultRequestDeadline = this@toPublicBundle.requestDeadlineElapsedRealtimeMs
        val resultHardLease = this@toPublicBundle.hardLeaseDeadlineElapsedRealtimeMs
        if (!containsKey("requestId") && resultRequestId != null) putString("requestId", resultRequestId)
        if (!containsKey("generation") && resultGeneration != null) putLong("generation", resultGeneration)
        if (!containsKey("transactionId") && resultTransactionId != null) {
            putString("transactionId", resultTransactionId)
        }
        if (!containsKey("requestDeadlineElapsedRealtimeMs") && resultRequestDeadline != null) {
            putLong("requestDeadlineElapsedRealtimeMs", resultRequestDeadline)
        }
        if (!containsKey("hardLeaseDeadlineElapsedRealtimeMs") && resultHardLease != null) {
            putLong("hardLeaseDeadlineElapsedRealtimeMs", resultHardLease)
        }
    }
}

internal fun publicStatusBundle(
    success: Boolean,
    code: String,
    message: String,
    callerRequestId: String,
    callerDeadlineElapsedRealtimeMs: Long,
    truth: PublicSessionTruth,
): Bundle = Bundle().apply {
    putBoolean("success", success)
    putBoolean("applied", false)
    putBoolean("restored", false)
    putString("code", code)
    putString("message", message)
    putString("callerRequestId", callerRequestId)
    putLong("callerDeadlineElapsedRealtimeMs", callerDeadlineElapsedRealtimeMs)
    putString("sessionState", truth.state.name)
    putBoolean("mutationMayBeActive", truth.mutationMayBeActive)
    putBoolean("leaseRenewable", truth.leaseRenewable)
    truth.requestId?.let { putString("requestId", it) }
    truth.generation?.let { putLong("generation", it) }
    truth.transactionId?.let { putString("transactionId", it) }
    truth.requestDeadlineElapsedRealtimeMs?.let {
        putLong("requestDeadlineElapsedRealtimeMs", it)
    }
    truth.leaseDeadlineElapsedRealtimeMs?.let {
        putLong("leaseDeadlineElapsedRealtimeMs", it)
    }
    truth.hardLeaseDeadlineElapsedRealtimeMs?.let {
        putLong("hardLeaseDeadlineElapsedRealtimeMs", it)
    }
    putString("sessionScope", truth.scope?.name ?: "NONE")
    truth.profileId?.let { putString("activeProfileId", it) }
}

internal fun CalibrationReport.toBundle(): Bundle = Bundle().apply {
    putString("disposition", disposition.name)
    putString("profileId", profileId)
    putString("packageName", packageName)
    putString("hardwareFingerprint", hardwareFingerprint)
    putLong("startedAtEpochMs", startedAtEpochMs)
    putInt("requestedDurationSeconds", requestedDurationSeconds)
    putInt("observedDurationSeconds", observedDurationSeconds)
    putInt("sampleCount", sampleCount)
    putInt("qualificationSampleCount", qualificationSampleCount)
    putInt("qualificationWindowSampleCount", qualificationWindowSampleCount)
    maxCpuTempC?.let { putInt("maxCpuTempC", it) }
    maxGpuTempC?.let { putInt("maxGpuTempC", it) }
    maxBatteryTempDeciC?.let { putInt("maxBatteryTempDeciC", it) }
    averageBatteryPowerMw?.let { putInt("averageBatteryPowerMw", it) }
    maxThermalStatus?.let { putInt("maxThermalStatus", it) }
    putInt("chargingSampleCount", chargingSampleCount)
    putInt("externalPowerSampleCount", externalPowerSampleCount)
    putBoolean("batteryEfficiencyEligible", batteryEfficiencyEligible)
    putStringArrayList("batteryEfficiencyBlockers", ArrayList(batteryEfficiencyBlockers))
    putInt("attestedSampleCount", attestedSampleCount)
    putInt("frameSampleCount", frameSampleCount)
    putInt("frameAttestedSampleCount", frameAttestedSampleCount)
    medianFrameFpsMilli?.let { putInt("medianFrameFpsMilli", it) }
    maximumP95FrameTimeUs?.let { putInt("maximumP95FrameTimeUs", it) }
    averageSlow20FpsPercent?.let { putInt("averageSlow20FpsPercent", it) }
    averageSlow30FpsPercent?.let { putInt("averageSlow30FpsPercent", it) }
    putStringArrayList("frameLayerNames", ArrayList(frameLayerNames))
    putStringArrayList("candidateCpuMaxKhz", candidateCpuMaxKhz.toRows())
    candidateGpuMaxPowerLevel?.let { putInt("candidateGpuMaxPowerLevel", it) }
    candidateFanMode?.let { putInt("candidateFanMode", it) }
    putBoolean("workloadAttested", evidence.workloadAttested)
    putBoolean("frameTelemetryAttested", evidence.frameTelemetryAttested)
    putBoolean("writesExercised", evidence.writesExercised)
    putInt("rollbackAttempts", evidence.rollbackAttempts)
    putInt("rollbackSuccesses", evidence.rollbackSuccesses)
    putBoolean("rebootRestoreVerified", evidence.rebootRestoreVerified)
    putStringArrayList("blockers", ArrayList(blockers))
}

internal fun CalibrationActionResult.toBundle(): Bundle = report.toBundle().apply {
    putBoolean("success", this@toBundle.success)
    putString("code", this@toBundle.code)
    putString("message", this@toBundle.message)
}

private fun Map<Int, Int>.toRows(): ArrayList<String> =
    ArrayList(toSortedMap().map { (id, value) -> "policy$id=$value" })
