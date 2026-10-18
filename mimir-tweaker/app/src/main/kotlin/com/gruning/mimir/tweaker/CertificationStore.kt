package com.gruning.mimir.tweaker

import java.security.MessageDigest

/**
 * Product-plan certification authority for Tweaker.
 *
 * TW04-A quarantines unsigned SharedPreferences / local-lab CERTIFIED reports: they may still
 * exist on disk for diagnostics, but they never authorize capabilities or plan apply.
 * TW04-E can inject only a successfully verified signed-manifest authority. The shipping
 * constructor remains unavailable until an owner trust anchor and exact-device manifest exist.
 */
internal class CertificationStore(
    private val calibrationStore: CalibrationReportStore,
    productAuthority: ProductQualificationAuthority = ProductQualificationAuthority.Unavailable,
) {
    private val productAuthority =
        if (
            TweakerPostureTruth.posture == TweakerPostureTruth.PRODUCTION &&
            TweakerPostureTruth.hardwareWritesCompiled &&
            TweakerPostureTruth.signedManifestRequired &&
            !TweakerPostureTruth.diagnosticsCompiled
        ) {
            productAuthority
        } else {
            ProductQualificationAuthority.Unavailable
        }

    fun isPlanCertified(plan: TuningPlan, capabilities: HardwareCapabilities): Boolean =
        productAuthority.qualifiedPlan(plan.profileId, plan.packageName, capabilities)?.let { exact ->
            exact.cpuMaxKhz == plan.cpuMaxKhz &&
                exact.gpuMaxPowerLevel == plan.gpuMaxPowerLevel &&
                exact.fanMode == plan.fanMode &&
                exact.calibrated
        } == true

    fun isProfileCertified(hardwareFingerprint: String, profileId: String): Boolean =
        profileId in productAuthority.certifiedProfileIds(hardwareFingerprint)

    fun certifiedProfileIds(hardwareFingerprint: String): Set<String> =
        productAuthority.certifiedProfileIds(hardwareFingerprint)
            .intersect(TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS)

    /** Exact manifest-sourced plan; callers must not recompute percentage plans in production. */
    fun qualifiedPlan(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
    ): TuningPlan? = productAuthority.qualifiedPlan(profileId, packageName, capabilities)

    /** Informational only: whether a local calibration report exists (never authoritative). */
    fun hasLocalCalibrationReport(): Boolean = calibrationStore.load() != null

    companion object {
        /** Historical tunable IDs; product-qualified set is Quiet/Performance only (Balanced hidden). */
        val TUNABLE_PROFILE_IDS = setOf("quiet", "balanced", "performance")

        val NoOp = CertificationStore(
            object : CalibrationReportStore {
                override fun load(): CalibrationReport? = null
                override fun save(report: CalibrationReport): Boolean = true
                override fun loadCertified(hardwareFingerprint: String, profileId: String): CalibrationReport? = null
                override fun certifiedProfileIds(hardwareFingerprint: String): Set<String> = emptySet()
            },
        )
    }
}

internal fun planIdentityHash(
    cpuMaxKhz: Map<Int, Int>,
    gpuMaxPowerLevel: Int?,
    fanMode: Int?,
): String {
    val canonical = buildString {
        cpuMaxKhz.toSortedMap().forEach { (policyId, khz) -> append("|cpu").append(policyId).append('=').append(khz) }
        append("|gpu=").append(gpuMaxPowerLevel ?: -1)
        append("|fan=").append(fanMode ?: -1)
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
