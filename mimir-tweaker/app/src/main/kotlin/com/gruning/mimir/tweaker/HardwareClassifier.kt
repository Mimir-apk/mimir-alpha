package com.gruning.mimir.tweaker

import java.util.Locale

internal data class TweakerHardwareFamily(
    val id: String,
    val label: String,
    val profileTreatment: String,
)

/**
 * Read-contract families only. Family membership never itself authorizes a hardware mutation or
 * product profile; authority comes from an exact-device signed manifest or, for a chipset-
 * supported family, [SupportedUncertifiedProfiles]'s own code-resident admission (policy
 * 2026-08-21/22 retired signed qualification as the only route).
 *
 * ClusterTune-style live-OPP discovery is the general-purpose CPU path. The three supported
 * Snapdragon generations get a stable Mimir certified-reference identity and may receive exact
 * family target candidates. Every other SoC retains the general live-OPP treatment, unless the
 * family catalog in force (a bundled or signed-downloaded `tweaker-families.json`) carries an
 * entry for it.
 */
internal object TweakerHardwareFamilies {
    // Chipset-support wave stage 1 : these ids are
    // the additive, informational "support family" tag -- carried on HardwareCapabilities as
    // hardwareFamilyId, never part of HardwareFingerprint or a signed manifest's deviceProfile.
    // Exposed as named constants (not just inline literals) so the family catalog's reserved ids,
    // HandheldDeviceProfiles.profileId and the planner share one spelling instead of
    // independently hand-maintained ones. The families themselves (aliases, labels, treatments and
    // every per-profile number) are data: see FamilyCatalog and FamilyCatalogCompiledDefault.
    const val SM8250_FAMILY_ID = "qualcomm-sm8250"
    const val SNAPDRAGON_8_ELITE_FAMILY_ID = "qualcomm-8-elite"
    const val SNAPDRAGON_8_GEN_2_FAMILY_ID = "qualcomm-8gen2"

    private val qualcommCandidate = TweakerHardwareFamily(
        id = "qualcomm-adreno-candidate",
        label = "Qualcomm Adreno handheld candidate",
        profileTreatment = GENERAL_LIVE_OPP,
    )
    private val generalLiveOpp = TweakerHardwareFamily(
        id = "general-live-opp",
        label = "General live-OPP CPU topology",
        profileTreatment = GENERAL_LIVE_OPP,
    )

    /**
     * The catalog in force: the one the application installed (the bundled asset or a promoted
     * download), else the compiled default. Both the main and the `:guardian` process install it
     * (see [FamilyCatalogProcess.usesLiveCatalogIn]); the compiled default is what a process whose
     * store could not be read, or a plain JVM test with nothing installed, gets. Never null, and
     * that default carries the same three families the compiled `when` used to.
     */
    internal fun catalog(): FamilyCatalog =
        FamilyCatalogRegistry.current() ?: FamilyCatalogCompiledDefault.catalog

    /**
     * The per-profile numbers of [familyId] (CPU targets or anchor ratios, GPU levels or position
     * fractions, vendor mode), or null for an id the catalog in force does not carry -- the two
     * compiled fallbacks always, since they are never data.
     */
    internal fun tables(familyId: String): FamilyTables? = catalog().tables(familyId)

    /**
     * Same signature and same answers as the compiled `when` it replaced, now read from the
     * catalog: the SoC model first (exact beats prefix), then `ro.board.platform`, and the
     * compiled `qualcomm-adreno-candidate` / `general-live-opp` when nothing matches.
     *
     * The board platform is read only on a SoC miss, so a device that matches by SoC (every
     * handheld Mimir ships for) never pays the one-time `getprop` and the hot path (this runs five
     * times per plan) is a trim, an upper-case and a map lookup.
     */
    fun resolve(socModel: String): TweakerHardwareFamily =
        resolveIn(catalog(), socModel) { BoardPlatform.current }

    /**
     * [resolve] against [catalog], which the caller read once. A derivation that asks the family question
     * more than once (the planner, [SupportedUncertifiedProfiles]) holds one catalog for all of them, so a
     * promotion that lands in between cannot give it two different answers (F-P3-4, 2026-10-13 review).
     */
    internal fun resolve(catalog: FamilyCatalog, socModel: String): TweakerHardwareFamily =
        resolveIn(catalog, socModel) { BoardPlatform.current }

    /** [resolve] with the board platform given, for callers and tests that must not spawn `getprop`. */
    internal fun resolve(socModel: String, boardPlatform: String?): TweakerHardwareFamily =
        resolveIn(catalog(), socModel) { boardPlatform }

    private inline fun resolveIn(
        catalog: FamilyCatalog,
        socModel: String,
        boardPlatform: () -> String?,
    ): TweakerHardwareFamily {
        val entry = catalog.resolve(socModel)
            ?: boardPlatform()?.let { platform -> catalog.resolve(socModel, platform) }
        if (entry != null) return TweakerHardwareFamily(entry.id, entry.label, entry.treatment)
        val canonical = socModel.trim().uppercase(Locale.US)
        return if (canonical.matches(QUALCOMM_SOC_PATTERN)) qualcommCandidate else generalLiveOpp
    }

    private val QUALCOMM_SOC_PATTERN = Regex("^(?:SM|QCS|CQ|MSM|SDM)[0-9][A-Z0-9-]*$")
    const val GENERAL_LIVE_OPP = "general-live-opp"
    const val MIMIR_CERTIFIED_REFERENCE = "mimir-certified-reference"
}

internal object HardwareClassifier {
    fun classify(
        manufacturer: String,
        model: String,
        socModel: String,
        deviceProfileId: String,
        pServerAvailable: Boolean,
        cpuPolicyIds: Set<Int>,
        gpuNodeAvailable: Boolean,
        fanNodeAvailable: Boolean,
        refreshRatesHz: List<Int>,
    ): Pair<CapabilityStatus, List<String>> {
        val notes = mutableListOf<String>()
        val family = TweakerHardwareFamilies.resolve(socModel)
        if (!pServerAvailable) {
            notes += "PServerBinder is missing; protected writes are unavailable."
        }
        if (cpuPolicyIds.isEmpty()) {
            notes += "No CPU cpufreq policies were detected."
        }
        if (!gpuNodeAvailable) {
            notes += "GPU tuning is unavailable; live-OPP CPU cluster tuning remains available."
        }
        if (!fanNodeAvailable) notes += "Fan nodes were not detected; fan tuning will be omitted."
        if (refreshRatesHz.isEmpty()) notes += "No display refresh rates were reported."
        val contractPresent = pServerAvailable &&
            cpuPolicyIds.isNotEmpty()
        if (!contractPresent) return CapabilityStatus.UNSUPPORTED to notes
        notes += "Hardware family: ${family.label} (${family.profileTreatment})."
        notes += "Device profile: $deviceProfileId."
        notes += "Discovered ${cpuPolicyIds.size} CPU policy/policies for general live-OPP tuning."
        // Scoped to non-chipset-supported families: for those, the ONLY route to a product
        // profile is still an exact-device signed manifest. A chipset-supported family (Odin,
        // Nova and siblings on the same three supported Snapdragon generations) is not owed
        // this -- SupportedUncertifiedProfiles' code-resident admission already offers all three
        // profiles there with or without a manifest (policy 2026-08-21/22), so asserting
        // qualification/rollback proof as still-owed on that family reads as a blocker that no
        // longer exists. Kept as the pre-existing wording (not reworded) so old bug reports that
        // grepped it still match on the class it still describes.
        if (family.profileTreatment != TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE) {
            notes += "Exact-device signed product qualification and rollback proof remain owed."
        }
        when (TweakerPostureTruth.posture) {
            TweakerPostureTruth.SAFE ->
                notes += "Hardware mutation is compile-time disabled in the SAFE posture."
            TweakerPostureTruth.LAB ->
                notes += "LAB posture: diagnostic writes only; no product-qualified profiles."
            TweakerPostureTruth.PRODUCTION ->
                notes += "PRODUCTION posture: product profiles come from a signed manifest or, " +
                    "for a chipset-supported family, the code-resident envelope " +
                    "(SupportedUncertifiedProfiles)."
        }
        // The transport contract is present, so the device is reachable and supported. Whether a
        // profile may actually be applied is decided per plan (SupportedUncertifiedProfiles), never
        // by this status: every reader compares only against UNSUPPORTED. CALIBRATION_REQUIRED
        // stays in the enum so an older client that parses it still works.
        return CapabilityStatus.SUPPORTED to notes
    }
}
