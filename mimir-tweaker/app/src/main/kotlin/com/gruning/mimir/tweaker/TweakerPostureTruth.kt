package com.gruning.mimir.tweaker

/**
 * Compile-time posture truth for TW04-A.
 *
 * SAFE: observe/preview only; product apply fails closed with [WRITE_GATE_DISABLED].
 * LAB: diagnostic writes may be compiled; never product-qualifies profiles.
 * PRODUCTION: writes compiled, diagnostics off; product profiles require signed authority (TW04-E).
 */
internal object TweakerPostureTruth {
    const val SAFE = "SAFE"
    const val LAB = "LAB"
    const val PRODUCTION = "PRODUCTION"

    const val QUALIFICATION_NONE = "NONE"
    const val QUALIFICATION_LOCAL_REPORT = "LOCAL_REPORT"
    const val QUALIFICATION_SIGNED_MANIFEST = "SIGNED_MANIFEST"

    /** Profiles eligible for exact-device signed qualification; none are exposed by this set alone. */
    val PRODUCT_QUALIFIABLE_PROFILE_IDS: Set<String> =
        setOf("quiet", "balanced", "performance")

    val posture: String = BuildConfig.POSTURE

    val hardwareWritesCompiled: Boolean = BuildConfig.HARDWARE_WRITES_COMPILED

    val diagnosticsCompiled: Boolean = BuildConfig.DIAGNOSTICS_COMPILED

    val signedManifestRequired: Boolean = BuildConfig.SIGNED_MANIFEST_REQUIRED

    fun isKnownPosture(raw: String?): Boolean =
        raw == SAFE || raw == LAB || raw == PRODUCTION

    /**
     * Product-qualified IDs are empty here, always and by construction -- this returns
     * `emptySet()` unconditionally. The authoritative set is resolved far downstream in
     * `MimirTweakerService` and never reaches this function.
     *
     * That is not a placeholder awaiting TW04-E. It is a live trap: on 2026-08-22 this value was
     * read off the `MimirTweakerIdentity` log line as evidence that a device had lost its
     * certification, which produced a P-level finding that had to be retracted. A field that can
     * only ever print one value cannot testify about the other.
     * Local SharedPreferences reports and lab observation never authorize.
     */
    fun productQualifiedProfileIds(): Set<String> = emptySet()

    /**
     * Qualification source advertised to clients. LOCAL_REPORT is informational only and
     * never authorizes capabilities or plan apply.
     */
    fun qualificationSource(hasLocalCalibrationReport: Boolean): String =
        if (hasLocalCalibrationReport) QUALIFICATION_LOCAL_REPORT else QUALIFICATION_NONE

    /**
     * "This daemon can mutate hardware right now", NOT "this daemon is certified".
     *
     * Chipset-support stage 3 widens the qualification leg from
     * signed-manifest-only to *either* a signed manifest *or* a chipset-supported-but-uncertified
     * derivation ([SupportedUncertifiedProfiles]). The four environmental legs -- writes compiled,
     * not dry-run, hardware not UNSUPPORTED, pserver alive -- are unchanged and still ANDed.
     *
     * Widening in place rather than adding a parallel bundle key was chosen after enumerating every
     * reader on both sides. There is exactly one semantic consumer: the client's
     * `allowsProductTunableProfiles()`, which independently ANDs
     * `qualificationSource == SIGNED_MANIFEST`. So a Nova reporting `mutationAvailable = true` with
     * `qualificationSource = NONE` cannot make that predicate true, and the certified path -- and
     * therefore the Odin -- is arithmetically unmoved. The daemon itself never branches on this
     * value; it computes it and marshals it. A new key would also have had to be classified in both
     * `TweakerCapabilityContract` drift tests for no behavioural gain.
     */
    fun mutationAvailable(
        hardwareWritesCompiled: Boolean,
        dryRun: Boolean,
        capabilityStatus: CapabilityStatus,
        pServerAvailable: Boolean,
        qualificationSource: String,
        qualifiedProfileIds: Set<String>,
        supportedUncertifiedProfileIds: Set<String> = emptySet(),
    ): Boolean = hardwareWritesCompiled &&
        !dryRun &&
        capabilityStatus != CapabilityStatus.UNSUPPORTED &&
        pServerAvailable &&
        (
            (
                qualificationSource == QUALIFICATION_SIGNED_MANIFEST &&
                    qualifiedProfileIds.isNotEmpty()
                ) ||
                supportedUncertifiedProfileIds.isNotEmpty()
            )
}
