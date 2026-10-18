package com.gruning.mimir.rewrite.core.tweaker

enum class TweakerCapabilityStatus {
    UNSUPPORTED,
    CALIBRATION_REQUIRED,
    SUPPORTED,
    UNAVAILABLE,
}

enum class TweakerPosture {
    SAFE,
    LAB,
    PRODUCTION,
    UNKNOWN,
}

enum class TweakerQualificationSource {
    NONE,
    LOCAL_REPORT,
    SIGNED_MANIFEST,
    UNKNOWN,
}

/** Informational family treatment. This never authorizes a profile or mutation. */
enum class TweakerProfileTreatment {
    GENERAL_LIVE_OPP,
    MIMIR_CERTIFIED_REFERENCE,
    UNKNOWN,
}

data class TweakerCapabilities(
    val status: TweakerCapabilityStatus,
    val deviceProfileId: String?,
    val hardwareFingerprint: String?,
    val supportedAxes: Set<String>,
    val certifiedProfileIds: Set<String> = emptySet(),
    /**
     * Chipset-support wave: quiet/balanced/performance ids the daemon's chipset family supports
     * with a live-OPP-validated but UNCERTIFIED (no signed manifest) vector.
     *
     * **Disjoint from [certifiedProfileIds], and as of stage 3 that is enforced rather than
     * asserted** -- the daemon subtracts the certified set at its own bundle-assembly seam, and
     * [parseTweakerCapabilitiesBundle] subtracts again on this side. An id in both buckets would
     * make the honesty label a coin flip, so certified always wins.
     *
     * These feed [selectableTweakerProfileIds] through [allowsUncertifiedTunableProfiles] and can be
     * selected and applied. No surface shows certified/uncertified jargon or changes lifetime based
     * on certification. This set decides only what may be PICKED; provenance stays engineering data.
     */
    val supportedUncertifiedProfileIds: Set<String> = emptySet(),
    val notes: List<String>,
    val posture: TweakerPosture = TweakerPosture.UNKNOWN,
    val mutationAvailable: Boolean? = null,
    val dryRun: Boolean? = null,
    val usageAccessGranted: Boolean? = null,
    val qualificationSource: TweakerQualificationSource = TweakerQualificationSource.UNKNOWN,
    val hardwareFamilyId: String? = null,
    val profileTreatment: TweakerProfileTreatment = TweakerProfileTreatment.UNKNOWN,
    /** LAB-only owner-validation profiles; never product qualification authority. */
    val diagnosticProfileIds: Set<String> = emptySet(),
    val diagnosticMutationAvailable: Boolean = false,
)

data class TweakerApplyResult(
    val success: Boolean,
    val applied: Boolean,
    val restored: Boolean,
    val code: String,
    val message: String,
    val profileId: String?,
) {
    companion object {
        fun fromBundle(bundle: android.os.Bundle): TweakerApplyResult = TweakerApplyResult(
            success = bundle.getBoolean("success"),
            applied = bundle.getBoolean("applied"),
            restored = bundle.getBoolean("restored"),
            code = bundle.getString("code").orEmpty(),
            message = bundle.getString("message").orEmpty(),
            profileId = bundle.getString("profileId"),
        )
    }
}

/** Optional read-only live telemetry. Missing fields stay null and are never projected as zero. */
data class TweakerTelemetry(
    /**
     * Stamped by the daemon after the foreground attestation and before the SurfaceFlinger frame
     * read: the foreground package is never fresher than this claims (freshness gates on it can
     * only over-reject), while the frame pipeline's cost counts against freshness. The frame
     * DATA can be up to [frameLatestPresentAgeMs] older still.
     */
    val sampledAtElapsedRealtimeMs: Long?,
    val foregroundPackageName: String?,
    val frameStatus: String?,
    val frameAverageFpsMilli: Int?,
    val cpuTempC: Int?,
    val gpuTempC: Int?,
    val cpuBusyPercent: Int?,
    val gpuBusyPercent: Int?,
    val batteryPowerMw: Int?,
    val batteryStatus: String?,
    /**
     * MEAN of [cpuPackageTempZoneCount] classified CPU thermal zones (the daemon's device profile
     * may narrow the family) — not a single package sensor. [cpuTempC] remains hottest safety truth.
     */
    val cpuPackageTempC: Int? = null,
    /** Current CPU policy clocks in ascending policy-id order. An invalid vector is rejected whole. */
    val cpuPolicyClocksHz: List<Long> = emptyList(),
    val gpuClockHz: Long? = null,
    val fanControl: TweakerFanControlReadout? = null,
    /** Frames behind [frameAverageFpsMilli]; a 6-frame average must not read like a 200-frame one. */
    val frameCount: Int? = null,
    /** Wall-clock span of the frame window, ms. */
    val frameWindowDurationMs: Int? = null,
    /** Age of the newest frame at the daemon's read, ms; add to the stamp age for true FPS staleness. */
    val frameLatestPresentAgeMs: Int? = null,
    /** How many thermal zones fed the [cpuPackageTempC] mean. */
    val cpuPackageTempZoneCount: Int? = null,
)

enum class TweakerFanControlStrategy {
    HOLD_TARGET,
    AUTO_CURVE,
}

/** Complete, validated read-only truth for an active Mimir-owned fan session. */
data class TweakerFanControlReadout(
    val profileId: String,
    val strategy: TweakerFanControlStrategy,
    val targetTempC: Int,
    /** Calibration intent only; this is not presented as a hard live watt limiter. */
    val calibrationPowerReferenceMw: Int,
    val dutyPercent: Int,
    val rpm: Int?,
)

fun parseTweakerTelemetryBundle(bundle: android.os.Bundle): TweakerTelemetry = TweakerTelemetry(
    sampledAtElapsedRealtimeMs = bundle.longOrNull("sampledAtElapsedRealtimeMs"),
    foregroundPackageName = bundle.getString("foregroundPackageName")?.takeIf(String::isNotBlank),
    frameStatus = bundle.getString("frameStatus")?.takeIf(String::isNotBlank),
    frameAverageFpsMilli = bundle.intOrNull("frameAverageFpsMilli")?.takeIf { it > 0 },
    cpuTempC = bundle.intOrNull("cpuTempC")?.takeIf { it in 1..150 },
    cpuPackageTempC = bundle.intOrNull("cpuPackageTempC")?.takeIf { it in 1..150 },
    gpuTempC = bundle.intOrNull("gpuTempC")?.takeIf { it in 1..150 },
    cpuBusyPercent = bundle.intOrNull("cpuBusyPercent")?.takeIf { it in 0..100 },
    gpuBusyPercent = bundle.intOrNull("gpuBusyPercent")?.takeIf { it in 0..100 },
    batteryPowerMw = bundle.intOrNull("batteryPowerMw"),
    batteryStatus = bundle.getString("batteryStatus")?.takeIf(String::isNotBlank),
    cpuPolicyClocksHz = parseCpuCurrentKhzRows(bundle.getStringArrayList("cpuCurrentKhz").orEmpty()),
    gpuClockHz = bundle.longOrNull("gpuClockHz")?.takeIf { it in 1..MAX_REASONABLE_CLOCK_HZ },
    fanControl = parseFanControlReadout(bundle),
    frameCount = bundle.intOrNull("frameCount")?.takeIf { it in 1..1_000_000 },
    frameWindowDurationMs = bundle.intOrNull("frameWindowDurationMs")?.takeIf { it in 1..600_000 },
    frameLatestPresentAgeMs = bundle.intOrNull("frameLatestPresentAgeMs")?.takeIf { it in 0..60_000 },
    cpuPackageTempZoneCount = bundle.intOrNull("cpuPackageTempZoneCount")?.takeIf { it in 1..256 },
)

private val CPU_CURRENT_KHZ_ROW = Regex("^policy([0-9]+)=([0-9]+)$")
private const val KHZ_TO_HZ = 1_000L
private const val MAX_REASONABLE_CLOCK_HZ = 10_000_000_000L
/**
 * Vendor fan modes under which Mimir's daemon directly owns PWM duty, so a duty readout in the
 * telemetry bundle is actually Mimir's own value rather than the vendor's independent loop.
 * SPORT (5) is the mode Odin sessions run as of `95f6fa7b`; MANUAL (6) is the legacy/explicit
 * passthrough. Vendor SMART (4) is deliberately excluded -- Mimir does not own duty there, so a
 * duty reading would not be Mimir's to report.
 *
 * Source of truth: `TweakerVendorFanModes.MIMIR_DUTY_OWNED` in the `:mimir-tweaker-contract`
 * module, which both Gradle builds already depend on. This was previously a hand-copied
 * `setOf(5, 6)` carrying a note that drift was not automatically caught and the copy had to be
 * updated by hand; `TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES` now references the same declaration,
 * so a change is a compile-time change on both sides rather than a silent divergence.
 */
private val FAN_READOUT_ACCEPTED_VENDOR_MODES: Set<Int> =
    com.gruning.mimir.tweaker.TweakerVendorFanModes.MIMIR_DUTY_OWNED
private val FAN_PROFILE_IDS = setOf("quiet", "balanced", "performance")

private fun parseFanControlReadout(bundle: android.os.Bundle): TweakerFanControlReadout? {
    val profileId = bundle.getString("activeFanProfileId")
        ?.takeIf { it in FAN_PROFILE_IDS }
        ?: return null
    val strategy = bundle.getString("fanControlStrategy")
        ?.let { runCatching { TweakerFanControlStrategy.valueOf(it) }.getOrNull() }
        ?: return null
    val targetTempC = bundle.intOrNull("fanTargetTempC")?.takeIf { it in 40..85 }
        ?: return null
    val referenceMw = bundle.intOrNull("fanPowerReferenceMw")?.takeIf { it in 1..100_000 }
        ?: return null
    if (bundle.intOrNull("vendorFanMode") !in FAN_READOUT_ACCEPTED_VENDOR_MODES) return null
    val period = bundle.intOrNull("fanPeriod")?.takeIf { it in 1_000..1_000_000 }
        ?: return null
    val duty = bundle.intOrNull("fanDuty")?.takeIf { it in 0..period }
        ?: return null
    val rpm = bundle.intOrNull("fanRpm")?.takeIf { it >= 0 }
    return TweakerFanControlReadout(
        profileId = profileId,
        strategy = strategy,
        targetTempC = targetTempC,
        calibrationPowerReferenceMw = referenceMw,
        dutyPercent = ((duty.toLong() * 100L) / period.toLong()).toInt().coerceIn(0, 100),
        rpm = rpm,
    )
}

private fun parseCpuCurrentKhzRows(rows: List<String>): List<Long> {
    if (rows.isEmpty()) return emptyList()
    val parsed = rows.map { row ->
        val match = CPU_CURRENT_KHZ_ROW.matchEntire(row) ?: return emptyList()
        val policyId = match.groupValues[1].toIntOrNull() ?: return emptyList()
        val clockKhz = match.groupValues[2].toLongOrNull() ?: return emptyList()
        val clockHz = clockKhz.takeIf {
            it in 1..MAX_REASONABLE_CLOCK_HZ / KHZ_TO_HZ
        }?.times(KHZ_TO_HZ) ?: return emptyList()
        policyId to clockHz
    }
    if (parsed.map(Pair<Int, Long>::first).distinct().size != parsed.size) return emptyList()
    return parsed.sortedBy(Pair<Int, Long>::first).map(Pair<Int, Long>::second)
}

private fun android.os.Bundle.intOrNull(key: String): Int? =
    if (containsKey(key)) getInt(key) else null

private fun android.os.Bundle.longOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null

/** Typed outcome for the pre-dispatch Tweaker preparation hook. */
sealed class TweakerPreparationResult {
    data object Unavailable : TweakerPreparationResult()

    /** Stock restore or no-op; launch proceeds without active tuning. */
    data object StockNoOp : TweakerPreparationResult()

    /** Profile was actively applied before external launch. */
    data class Applied(val profileId: String) : TweakerPreparationResult()

    /** LAB owner-validation was accepted and will apply only after the target reaches foreground. */
    data class Scheduled(val profileId: String) : TweakerPreparationResult()

    /** Launch proceeds; Tweaker was skipped with an honest reason. */
    data class Skipped(val reason: String) : TweakerPreparationResult()
}

val TWEAKER_PROFILE_IDS = listOf("stock", "quiet", "balanced", "performance")

/** Exact-device signed-manifest candidates; this set alone never makes a profile selectable. */
val TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS = setOf("quiet", "balanced", "performance")

/**
 * Tunable profiles are accepted only for PRODUCTION + SIGNED_MANIFEST + mutationAvailable.
 * Unknown/missing posture or source fields fail closed to Stock only.
 */
fun TweakerCapabilities.allowsProductTunableProfiles(): Boolean =
    status != TweakerCapabilityStatus.UNSUPPORTED &&
        status != TweakerCapabilityStatus.UNAVAILABLE &&
        posture == TweakerPosture.PRODUCTION &&
        qualificationSource == TweakerQualificationSource.SIGNED_MANIFEST &&
        mutationAvailable == true &&
        certifiedProfileIds.any { it in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS }

/**
 * Prefer [selectableTweakerProfileIds] with [TweakerCapabilities]. The Set overload assumes the
 * IDs were already fail-closed at parse ([parseTweakerCapabilitiesBundle]).
 */
fun selectableTweakerProfileIds(certifiedProfileIds: Set<String>): List<String> =
    listOf("stock") + TWEAKER_PROFILE_IDS.filter {
        it != "stock" && it in certifiedProfileIds && it in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS
    }

fun selectableTweakerProfileIds(capabilities: TweakerCapabilities): List<String> =
    selectableTweakerProfileIds(capabilities, includeDiagnosticProfiles = false)

/**
 * Chipset-supported profiles derive from live OPP tables: a device on a Mimir-certified chipset family whose daemon
 * derived a live-OPP-validated vector may select those profiles even with no signed manifest.
 *
 * Deliberately does NOT require [TweakerQualificationSource.SIGNED_MANIFEST].
 *
 * Selection also does not require a specific compiled posture.
 *
 * A `posture == PRODUCTION` clause was the last allow-list standing, and it was the one that made the
 * whole chain pointless on the measured device. Measured on the AYN Thor, 2026-08-31, with the daemon built
 * from this repository and bound:
 *
 * ```
 * MimirTweakerIdentity: profile=ayn-qcs8550-pserver family=qualcomm-8gen2/mimir-certified-reference
 *   pserver=true cpuPolicies=3 gpu=true fan=true posture=LAB
 *   certified=[] uncertified=[quiet, balanced, performance]
 * MimirTweakerBridge:   availability=RESPONDING_STOCK_ONLY profiles=[stock]
 * ```
 *
 * The daemon had already done every piece of work: recognised the family, read three live CPU policies,
 * confirmed PServer, derived all three profiles from the device's OWN OPP tables and validated each
 * target against them. The client threw all of it away because the build flavour was LAB. That is a
 * question about how the APK was compiled, not about what the hardware can do.
 *
 * **Selection depends on discovered facts**, and every one of them is discovered:
 * `mutationAvailable` is the daemon's own answer and already ANDs hardware-writes-compiled, not-dry-run,
 * status-not-UNSUPPORTED and **pserver alive**; `supportedUncertifiedProfileIds` is non-empty only when
 * the family resolved AND `TuningPlanner` derived a vector from the live OPP table AND every value
 * survived validation against it. Status reachability stays because an unreachable daemon cannot honour
 * a pick at all.
 *
 * Fail-closed is unchanged: every clause still has to be positively true, and a missing or unknown
 * field is still not true.
 *
 * Safety does not live here. The daemon re-derives and re-validates the plan before it can reach
 * hardware (`SupportedUncertifiedProfiles.admitsPlan`), and `PlanTargetValidator` re-checks every
 * target against the live OPP table on every apply. This predicate decides what the player may
 * *pick*; it is not what makes the pick safe.
 */
fun TweakerCapabilities.allowsUncertifiedTunableProfiles(): Boolean =
    status != TweakerCapabilityStatus.UNSUPPORTED &&
        status != TweakerCapabilityStatus.UNAVAILABLE &&
        mutationAvailable == true &&
        supportedUncertifiedProfileIds.any { it in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS }

/**
 * **Whether a tunable profile may be OFFERED — one law over both sets.**
 *
 *
 * Scoped to selection; the parser's own gates stay exactly where
 * they are, because they answer a different question (below).
 *
 * Offering used to ask twice — [allowsProductTunableProfiles] over `certifiedProfileIds` and
 * [allowsUncertifiedTunableProfiles] over `supportedUncertifiedProfileIds` — and the first additionally
 * demanded `qualificationSource == SIGNED_MANIFEST`. That is the per-device manifest, retired from
 * ADMISSION on 2026-08-22 and still standing on the
 * offer side a fortnight later. **Measured 2026-09-03 across all three handhelds: `certified=[]` on the
 * Thor, the Nova AND the Odin**, each with `uncertified=[quiet, balanced, performance]`. The clause it
 * guarded had never once admitted a profile.
 *
 * **What is deliberately NOT collapsed, because both turned out to be load-bearing:**
 * - `parseTweakerCapabilitiesBundle` keeps [allowsProductTunableProfiles] to decide whether to trust a
 *   bundle's *certified* claim. That is PROVENANCE, not offering — collapsing it let a bundle with an
 *   unknown posture and source expose a profile, which `TweakerCapabilitiesParseTest` caught.
 * - `decidePerGameTweakerLaunch` requires PRODUCTION posture explicitly now, rather than inheriting it
 *   from this predicate. A per-game launch is an AUTHORISATION and it keeps refusing a LAB daemon; the
 *   rule now lives where it is enforced instead of arriving as a side effect of an offer list.
 *
 * Reachability and `mutationAvailable` still gate here, so an unreachable or read-only daemon offers
 * Stock alone — pinned by `TweakerModelsTest` with a hand-built, un-parsed capabilities object, which is
 * why this is a gate and not a bare union. Safety never lived here in any case: the daemon re-derives
 * every plan through chip family plus this unit's live OPP tables, and `PlanTargetValidator` re-checks
 * each target on every apply.
 */
fun TweakerCapabilities.allowsTunableProfiles(): Boolean =
    status != TweakerCapabilityStatus.UNSUPPORTED &&
        status != TweakerCapabilityStatus.UNAVAILABLE &&
        mutationAvailable == true &&
        (certifiedProfileIds + supportedUncertifiedProfileIds)
            .any { it in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS }

fun selectableTweakerProfileIds(
    capabilities: TweakerCapabilities,
    includeDiagnosticProfiles: Boolean,
): List<String> =
    selectableTweakerProfileIds(
        buildSet {
            // One gate over both sets — see [allowsTunableProfiles]. Policy 2026-09-03.
            if (capabilities.allowsTunableProfiles()) {
                addAll(capabilities.certifiedProfileIds)
                addAll(capabilities.supportedUncertifiedProfileIds)
            }
            if (includeDiagnosticProfiles && capabilities.allowsLabOwnerValidation()) {
                addAll(capabilities.diagnosticProfileIds)
            }
        },
    )

fun TweakerCapabilities.allowsLabOwnerValidation(): Boolean =
    posture == TweakerPosture.LAB &&
        diagnosticMutationAvailable &&
        dryRun == false &&
        usageAccessGranted == true &&
        status.isReachable() &&
        diagnosticProfileIds.isNotEmpty()

fun parseTweakerCapabilitiesBundle(bundle: android.os.Bundle): TweakerCapabilities {
    val posture = parseTweakerPosture(bundle.getString("posture"))
    val qualificationSource = parseTweakerQualificationSource(bundle.getString("qualificationSource"))
    val mutationAvailable = if (bundle.containsKey("mutationAvailable")) {
        bundle.getBoolean("mutationAvailable")
    } else {
        null
    }
    val dryRun = if (bundle.containsKey("dryRun")) bundle.getBoolean("dryRun") else null
    val usageAccessGranted = when {
        bundle.containsKey("hasUsageAccessGranted") && !bundle.getBoolean("hasUsageAccessGranted") -> null
        bundle.containsKey("usageAccessGranted") -> bundle.getBoolean("usageAccessGranted")
        else -> null
    }
    val rawCertified = bundle.getStringArrayList("certifiedProfileIds").orEmpty().toSet() +
        bundle.getStringArrayList("qualifiedProfileIds").orEmpty().toSet()
    val rawSupportedUncertified =
        bundle.getStringArrayList("supportedUncertifiedProfileIds").orEmpty().toSet()
    val rawDiagnostic = bundle.getStringArrayList("diagnosticProfileIds").orEmpty().toSet()
    val diagnosticMutationAvailable = bundle.getBoolean("diagnosticMutationAvailable", false)
    val draft = TweakerCapabilities(
        status = parseTweakerStatus(bundle.getString("status")),
        deviceProfileId = bundle.getString("deviceProfileId"),
        hardwareFingerprint = bundle.getString("hardwareFingerprint"),
        supportedAxes = bundle.getStringArrayList("supportedAxes").orEmpty().toSet(),
        certifiedProfileIds = rawCertified.filter { it in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS }.toSet(),
        supportedUncertifiedProfileIds = rawSupportedUncertified
            .filter { it in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS }
            .toSet(),
        notes = bundle.getStringArrayList("notes").orEmpty(),
        posture = posture,
        mutationAvailable = mutationAvailable,
        dryRun = dryRun,
        usageAccessGranted = usageAccessGranted,
        qualificationSource = qualificationSource,
        hardwareFamilyId = bundle.getString("hardwareFamilyId"),
        profileTreatment = parseTweakerProfileTreatment(bundle.getString("profileTreatment")),
        diagnosticProfileIds = if (posture == TweakerPosture.LAB) {
            rawDiagnostic.filter { it in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS }.toSet()
        } else {
            emptySet()
        },
        diagnosticMutationAvailable = posture == TweakerPosture.LAB && diagnosticMutationAvailable,
    )
    // Stale/local CERTIFIED injection cannot expose product profiles through the client model.
    val gatedIds = if (draft.allowsProductTunableProfiles()) draft.certifiedProfileIds else emptySet()
    // Same fail-closed treatment for the uncertified set (chipset-support stage 3): a bundle that
    // carries uncertified ids without the posture/status/mutation truth to back them exposes
    // nothing. `- gatedIds` re-asserts disjointness on this side too -- the daemon already
    // subtracts, but the client must not depend on a remote process having done so, and certified
    // membership must win so the row cannot label a signed profile "UNCERTIFIED".
    val gatedUncertifiedIds = if (draft.allowsUncertifiedTunableProfiles()) {
        draft.supportedUncertifiedProfileIds - gatedIds
    } else {
        emptySet()
    }
    return draft.copy(
        certifiedProfileIds = gatedIds,
        supportedUncertifiedProfileIds = gatedUncertifiedIds,
    )
}

fun parseTweakerPosture(raw: String?): TweakerPosture = when (raw?.trim()?.uppercase()) {
    "SAFE" -> TweakerPosture.SAFE
    "LAB" -> TweakerPosture.LAB
    "PRODUCTION" -> TweakerPosture.PRODUCTION
    else -> TweakerPosture.UNKNOWN
}

fun parseTweakerQualificationSource(raw: String?): TweakerQualificationSource = when (raw?.trim()?.uppercase()) {
    "NONE" -> TweakerQualificationSource.NONE
    "LOCAL_REPORT" -> TweakerQualificationSource.LOCAL_REPORT
    "SIGNED_MANIFEST" -> TweakerQualificationSource.SIGNED_MANIFEST
    else -> TweakerQualificationSource.UNKNOWN
}

fun parseTweakerProfileTreatment(raw: String?): TweakerProfileTreatment =
    when (raw?.trim()?.lowercase()) {
        "general-live-opp" -> TweakerProfileTreatment.GENERAL_LIVE_OPP
        "mimir-certified-reference" -> TweakerProfileTreatment.MIMIR_CERTIFIED_REFERENCE
        else -> TweakerProfileTreatment.UNKNOWN
    }

fun tweakerProfileDisplayCaps(profileId: String): String =
    when (profileId) {
        "stock" -> "STOCK"
        // The protocol id stays "quiet" -- it is the daemon's wire value and
        // renaming it would invalidate every signed qualification manifest that names the profile.
        "quiet" -> "EFFICIENCY"
        "balanced" -> "BALANCED"
        "performance" -> "PERFORMANCE"
        else -> profileId.uppercase()
    }

fun formatTweakerLaunchConfirmation(profileId: String): String =
    "TWEAKER · ${tweakerProfileDisplayCaps(profileId)} · ACTIVE"

fun steppedTweakerProfileId(
    currentId: String,
    direction: Int,
    selectableIds: List<String>,
): String {
    if (selectableIds.isEmpty()) return "stock"
    if (selectableIds.size <= 1) return selectableIds.first()
    val current = selectableIds.indexOf(currentId).let { if (it >= 0) it else 0 }
    return selectableIds[(current + direction).mod(selectableIds.size)]
}

fun tweakerProfileCanStepLeft(currentId: String, selectableIds: List<String>): Boolean =
    steppedTweakerProfileId(currentId, -1, selectableIds) != currentId

fun tweakerProfileCanStepRight(currentId: String, selectableIds: List<String>): Boolean =
    steppedTweakerProfileId(currentId, 1, selectableIds) != currentId

/** Typed overlay for the cross-app launch-status presenter on game launch. */
sealed class TweakerLaunchOverlayMessage {
    data class Confirmation(
        val headline: String,
        val supportingText: String,
    ) : TweakerLaunchOverlayMessage()

    data class Warning(val text: String) : TweakerLaunchOverlayMessage()
}

/** Pre-dispatch preparation is never authoritative evidence that a launch-time session is active. */
fun TweakerPreparationResult.launchConfirmation(): String? = null

fun TweakerCapabilityStatus.isReachable(): Boolean =
    this == TweakerCapabilityStatus.CALIBRATION_REQUIRED || this == TweakerCapabilityStatus.SUPPORTED

fun parseTweakerStatus(raw: String?): TweakerCapabilityStatus = runCatching {
    TweakerCapabilityStatus.valueOf(raw.orEmpty())
}.getOrDefault(TweakerCapabilityStatus.UNAVAILABLE)
