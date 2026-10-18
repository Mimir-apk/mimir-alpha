package com.gruning.mimir.tweaker

import kotlin.math.roundToInt

internal class TuningPlanner(
    private val calibrationLookup: (TuningPlan, HardwareCapabilities) -> Boolean = { _, _ -> false },
) {
    internal enum class FanIntent {
        PROFILE_AUTO,
        MANUAL_OVERRIDE,
    }
    private enum class CpuTier {
        STOCK,
        LARGE_UNDERCLOCK,
        MEDIUM_UNDERCLOCK,
        SMALL_UNDERCLOCK,
    }

    private data class ProfileShape(
        val cpuTier: CpuTier,
        val fanMode: Int?,
        /**
         * The vendor `performance_mode` this profile asks for by default, or null = never written
         * (Stock leaves the player's mode alone). A family may differ through [VendorModeTable].
         */
        val vendorMode: Int?,
    )

    /*
     * Static topology-relative envelopes, not an AutoTDP controller.
     *
     * CPU cluster tuning is general-purpose: every admitted topology is derived only
     * from its live OPP tables. Quiet, Balanced, and Performance map to Large, Medium,
     * and Small underclocks respectively. Mimir-certified reference families may replace
     * that generic tier with a pinned target, but only when the exact OPP exists live.
     *
     * The prior PULSE translation supplies the portable Power Saving/Balanced CPU
     * starting factors. ClusterTune supplies the live-policy/OPP posture and pinned tier
     * data for CQ8725S and QCS8550. The one-live-step Small fallback and SM8250 reference
     * targets are Mimir-owned translations, not claims about upstream bundled profiles.
     * General devices retain KGSL max_pwrlevel=0. Exact Odin 3 calibration additionally caps
     * quiet at explicit GPU level 5 (734 MHz) -- sequence 9 replaced the prior factor-derived
     * ceilings (525 MHz / 660 MHz) with explicit `gpuMaxPowerLevel` indices; see the companion
     * object. Sequence 9 also capped balanced at level 2 (967 MHz); **that cap is retired
     * (2026-10-13)**: the uncapped Balanced/Performance GPU configuration is now used on this family too, so balanced plans
     * level 0 exactly like performance and only quiet carries a GPU ceiling. Performance keeps the
     * full live GPU table (level 0) and takes its entire allocation out of the CPU instead: its own
     * six-core cluster is pinned to the 1,996,800 kHz live OPP while the prime cluster keeps
     * 3,072,000 kHz. Sequence 9 also moves balanced's six-core cluster onto that same
     * 1,996,800 kHz clamp, spending the freed envelope on its own prime cluster instead. Balanced
     * and Performance KEEP these CPU clamps; the vendor performance mode ([VendorModeTable]) moves the floors,
     * never the ceilings.
     * Those topology-relative
     * factors are applied only when the exact Odin device profile is present; they neither
     * pin the current GPU clock nor constitute an AutoTDP feedback controller. Every result
     * remains non-authoritative until it is admitted -- by exact-device signed qualification and its
     * rollback gates, OR, with certification removed as a behavioral gate,
     * by the code-resident envelope in [SupportedUncertifiedProfiles]. Both routes re-validate every
     * target against the device's own live OPP tables before a single command is dispatched; the
     * signature is a second witness to the numbers, never their only home.
     *
     * No Mimir profile raises a minimum: CPU scaling_min_freq ownership is rejected outright and
     * the captured KGSL min_pwrlevel is preserved, never elevated. Every Odin ceiling therefore
     * stays at or below the device's own Stock envelope on every axis.
     */
    /**
     * * *"All three drive SMART."* Before this, quiet/balanced/performance carried
     * SILENT/SMART/SPORT respectively -- and SILENT and SPORT both **stop** the vendor's own fan
     * regulation loop (`TweakerVendorFanModes.java`'s documented purpose for those two modes),
     * while Mimir historically never reliably took over duty for them in a production build. That
     * measured defect -- duty frozen, `speed = 0`, CPUs at 89.9C, thermal status SEVERE on a measured Odin -- is what the SMART default avoids. SMART is the only mode whose vendor loop
     * keeps regulating, so the mode is now constant across every profile and the profile
     * difference lives entirely in the curve/sensor ([FanThermalProfiles]), not the mode.
     *
     * This is the vendorMode fed to [selectedFanMode] below. TR-08 retires the exact Odin 3 device
     * profile's SPORT override that used to sit here -- it was deliberately kept one wave behind
     * this architecture change because the since-removed `ProductQualificationRuntime.planMatches` compared `fanMode` against the
     * signed manifest and sequence-10 signed `fanMode=5` (SPORT) on all three Odin profiles;
     * flipping the derivation alone, before a sequence-11 re-sign carries `fanMode=4`, would have
     * made the derived plan unequal to the signed plan and taken the Odin stock-only. That
     * re-sign is queued alongside this change, so the Odin now falls through to the same SMART
     * derivation every other RP/AYN device takes -- no device-profile exception remains here.
     * [FanIntent.MANUAL_OVERRIDE] on the Odin is unaffected: it is a separate, still-signed branch
     * in [selectedFanMode] below that returns [FAN_MANUAL] regardless of `vendorMode`.
     */
    private val profiles = mapOf(
        "stock" to ProfileShape(
            cpuTier = CpuTier.STOCK,
            fanMode = null,
            vendorMode = null,
        ),
        "quiet" to ProfileShape(
            cpuTier = CpuTier.LARGE_UNDERCLOCK,
            fanMode = FAN_SMART,
            vendorMode = VENDOR_MODE_STANDARD,
        ),
        "balanced" to ProfileShape(
            cpuTier = CpuTier.MEDIUM_UNDERCLOCK,
            fanMode = FAN_SMART,
            vendorMode = VENDOR_MODE_PERFORMANCE,
        ),
        "performance" to ProfileShape(
            cpuTier = CpuTier.SMALL_UNDERCLOCK,
            fanMode = FAN_SMART,
            vendorMode = VENDOR_MODE_HIGH_PERFORMANCE,
        ),
    )

    fun profileIds(): Set<String> = profiles.keys

    /**
     * [catalog] is read ONCE by the caller (the default reads it here, once) and used for every family question
     * this plan asks -- the family, the CPU and GPU tables and the vendor-mode row -- so a catalog promoted while
     * the plan is being derived cannot give it a family from one revision and a table from another (F-P3-4).
     */
    fun plan(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
        fanIntent: FanIntent = FanIntent.PROFILE_AUTO,
        catalog: FamilyCatalog = TweakerHardwareFamilies.catalog(),
    ): TuningPlan? {
        val shape = profiles[profileId] ?: return null
        val certifiedTargets = certifiedCpuTargetsKhz(
            profileId = profileId,
            socModel = capabilities.socModel,
            policies = capabilities.cpuPolicies,
            settledFloorsKhz = capabilities.cpuSettledFloorsKhz,
            catalog = catalog,
        )
        val cpu = capabilities.cpuPolicies.associate { policy ->
            val cpuTarget = certifiedTargets?.getValue(policy.id) ?: generalTierCpuTargetKhz(
                tier = shape.cpuTier,
                policy = policy,
                settledFloorKhz = capabilities.cpuSettledFloorsKhz[policy.id],
            )
            policy.id to cpuTarget
        }
        val gpuLevel = if (
            TuningAxis.GPU in capabilities.supportedAxes &&
            capabilities.gpuNodeAvailable
        ) {
            odin3ExplicitGpuMaxPowerLevel(profileId, capabilities, catalog)
                ?: odinAnchoredTransposedGpuMaxPowerLevel(
                    profileId = profileId,
                    familyId = TweakerHardwareFamilies.resolve(catalog, capabilities.socModel).id,
                    availableAscendingKhz = capabilities.gpuFrequenciesKhz,
                    catalog = catalog,
                )
                ?: gpuPowerLevel(
                    availableAscendingKhz = capabilities.gpuFrequenciesKhz,
                    factor = FULL_GPU_CEILING_FACTOR,
                )
        } else {
            null
        }
        val candidate = TuningPlan(
            profileId = profileId,
            packageName = packageName,
            cpuMaxKhz = cpu,
            gpuMaxPowerLevel = gpuLevel,
            fanMode = selectedFanMode(shape.fanMode, capabilities, fanIntent),
            calibrated = false,
            vendorMode = selectedVendorMode(profileId, shape, capabilities, catalog),
        )
        if (calibrationLookup(candidate, capabilities)) return candidate.copy(calibrated = true)

        // Do not silently fall back from the vendor Smart contract to either manual PWM or
        // a CPU/GPU-only plan. A profile whose exact Smart target is not signed remains uncalibrated and
        // therefore unavailable until the matching qualification is installed.
        return candidate
    }

    /**
     * LAB-only rung verification: like [plan], but substitutes [rung]'s `policy0`/
     * `policy6` clamps and GPU power level for the profile's own tier calculation instead of
     * deriving them from [CpuTier]. Fan mode, [profiles]-existence, and the [calibrationLookup]
     * gate are all UNCHANGED from [plan] -- this reuses the exact same calibrated/certified check
     * an ordinary apply goes through and neither reads nor bypasses any signed derate-rung
     * qualification state (that is [GenerationBoundDerater]'s concern, not this one's).
     *
     * `policy0`/`policy6` are hardcoded rather than derived from [HardwareCapabilities.cpuPolicies]
     * because [ThermalDerateRung] itself is those two exact Odin 3 policy IDs, not a generic
     * per-policy table (see [ThermalDerateLadder]'s class doc). A device whose live topology does
     * not expose exactly those two policy IDs returns null here -- never a partial or defaulted
     * clamp on whatever policies happen to exist.
     */
    fun rungPlan(
        profileId: String,
        packageName: String,
        capabilities: HardwareCapabilities,
        rung: ThermalDerateRung,
        fanIntent: FanIntent = FanIntent.PROFILE_AUTO,
        catalog: FamilyCatalog = TweakerHardwareFamilies.catalog(),
    ): TuningPlan? {
        val shape = profiles[profileId] ?: return null
        // Exactly {0, 6} or nothing: a device exposing only one of the pair would otherwise get a
        // silently PARTIAL clamp (e.g. policy0 only, policy6 left at whatever it was), which is
        // exactly the "partial or defaulted clamp" this diagnostic must never produce.
        if (capabilities.cpuPolicies.map { it.id }.toSet() != setOf(0, 6)) return null
        val cpu = mapOf(0 to rung.policy0MaxKhz, 6 to rung.policy6MaxKhz)
        val candidate = TuningPlan(
            profileId = profileId,
            packageName = packageName,
            cpuMaxKhz = cpu,
            gpuMaxPowerLevel = rung.gpuMaxPwrLevel,
            fanMode = selectedFanMode(shape.fanMode, capabilities, fanIntent),
            calibrated = false,
            vendorMode = selectedVendorMode(profileId, shape, capabilities, catalog),
        )
        if (calibrationLookup(candidate, capabilities)) return candidate.copy(calibrated = true)
        return candidate
    }

    /**
     * TR-08: the Odin 3's automatic-entry SPORT override is retired. It used to write mode 5
     * (SPORT) directly as an ordinary field of the Odin's own signed plan -- device evidence (see
     * [MIMIR_DUTY_OWNED_FAN_MODES] below) showed SPORT stops the vendor's duty-regulation loop, and
     * in a production build nothing ever reliably replaced it: duty frozen, `speed = 0`, CPUs at
     * 89.9C, thermal status SEVERE on device. That measured defect is exactly
     * what the SMART default above [profiles] closes for
     * every other device; the Odin was deliberately left one wave behind because
     * the since-removed `ProductQualificationRuntime.planMatches` required the derived plan's `fanMode` to equal the
     * signed manifest's, and sequence-10 signed `fanMode=5` on every Odin profile. This ticket's
     * APK ships together with a sequence-11 re-sign that carries `fanMode=4`, so the override can
     * now come out: the Odin falls through to the same `vendorMode` (SMART) every other RP/AYN
     * device already takes via the final `else -> vendorMode` arm below.
     *
     * [FanIntent.MANUAL_OVERRIDE] is the one exception this retirement does NOT touch. Mode 6
     * (MANUAL) remains reachable through this explicit, separately signed path -- `SIGNING.md`
     * keeps it deliberately reachable and removing it is a one-way door this ticket does not open.
     * Stock alone (`vendorMode == null`) still leaves vendor/USB fan policy authoritative.
     *
     * **Family-wide capability admission.** The exact-Odin
     * device-id conjunct that stood here is replaced by a **capability** conjunct
     * ([HardwareCapabilities.fanNodeAvailable]) -- the axis guard above already refuses a device
     * with no fan axis, and `HandheldDeviceProfiles.resolve` only ever populates `fanNodes` for an
     * RP/AYN id whose `gpio5_pwm2` duty/period/speed nodes are live on THIS unit. So the widening
     * cannot hand mode 6 to a chassis that has no PWM to drive; it fails toward cooling exactly as
     * the device-id test did, one field over.
     *
     * The six sibling gates this branch used to be "in lockstep with" moved in the same commit
     * (`PlanTargetValidator`, `PServerFanDutyMutationPort.write`, `AndroidTransactionPorts`'
     * manual-fan node resolution, `captureFanNode`'s manual admission,
     * [AdvancedFanControl.manualFanControlAdmitted], and `manualFanControlAvailable`'s
     * manifest-resolved `signedManualPlanMatches`, which is now code-resident) -- widening this one
     * alone would still have been inert, which is why they are one landing.
     *
     * **What did NOT change: the profile-apply default.** Release-spec `1.2 R2/R3 (all three
     * profiles drive vendor SMART; Mimir never stops the vendor regulation loop and never polls
     * duty) governs `FanIntent.PROFILE_AUTO`, is untouched here, and is what
     * `FanOwnershipSettleRoute.ADOPT_VENDOR_SMART` implements. Mode 6 remains reachable ONLY
     * through this explicit, player-initiated `MANUAL_OVERRIDE` opt-in.
     */
    private fun selectedFanMode(
        vendorMode: Int?,
        capabilities: HardwareCapabilities,
        fanIntent: FanIntent,
    ): Int? = when {
        vendorMode == null || TuningAxis.FAN !in capabilities.supportedAxes -> null
        fanIntent == FanIntent.MANUAL_OVERRIDE && capabilities.fanNodeAvailable -> FAN_MANUAL
        else -> vendorMode
    }

    /**
     * The vendor `performance_mode` this plan drives, or null when Mimir must not write the key.
     *
     * **Capability gates the axis; the family only picks the table.** The mode is planned iff
     * [TuningAxis.VENDOR_MODE] is in [HardwareCapabilities.supportedAxes] (the probe admits it when
     * the key reads 0..2 on a pserver device), exactly as fan is planned iff `FAN in supportedAxes`.
     * A device without the key therefore plans `vendorMode = null` and is byte-identical to before
     * this axis existed, in behaviour and in [PlanHash]; so is every device while
     * [VENDOR_MODE_TRANSPORT_ENABLED] is false. [VendorModeTable] never decides presence. Stock is
     * always null.
     */
    private fun selectedVendorMode(
        profileId: String,
        shape: ProfileShape,
        capabilities: HardwareCapabilities,
        catalog: FamilyCatalog,
    ): Int? {
        if (TuningAxis.VENDOR_MODE !in capabilities.supportedAxes) return null
        val familyId = TweakerHardwareFamilies.resolve(catalog, capabilities.socModel).id
        // The family's catalog row (one value per profile) replaces the profile default for that
        // family; a profile with no row, and a family with no catalog entry, take the default.
        val catalogMode = catalog.tables(familyId)?.vendorMode?.get(profileId)
        return VendorModeTable.modeFor(
            familyId = familyId,
            profileId = profileId,
            profileDefault = catalogMode ?: shape.vendorMode,
        )
    }

    /**
     * Sequence 9 (spec S2): the 8-Elite family's quiet/balanced/performance GPU ceiling is an
     * explicit `gpuMaxPowerLevel` index, not a factor applied to whatever GPU table happens to be
     * live -- the Odin's own measured levels, anchor-family values rather than a device-id secret.
     *
     * **2026-10-13: balanced is dispatched as [UNCAPPED_GPU_MAX_POWER_LEVEL], not as
     * [ODIN_3_BALANCED_GPU_MAX_POWER_LEVEL].** Uncapped Balanced and Performance with a mild quiet cap had only been applied to transposed devices
     * (see [UNCAPPED_GPU_MAX_POWER_LEVEL]); this family kept level 2 (967 MHz) solely
     * because the retired sequence-10 manifest pinned it. The dispatch changes, not the constant:
     * the level-2 value stays truthful as the anchor hardware in
     * the anchor ratios in [FamilyCatalogCompiledDefault]. Only quiet now carries a GPU ceiling on this family.
     * Returns null for every other profile (`stock`) or family, so [plan] falls through to
     * [odinAnchoredTransposedGpuMaxPowerLevel] (excluded for this same family; see its own KDoc)
     * and then the full live table.
     *
     * **Family-based indices:** widened from
     * `capabilities.deviceProfileId == CpuCeilingTransport.ODIN_3_PROFILE` to chip-family
     * membership -- any AYN/Retroid chassis on this silicon gets these indices, not only the
     * literal Odin id. Self-limiting, not a new trust: [PlanTargetValidator] independently refuses
     * an index outside a device's own live GPU level count on every apply (TransactionJournal.kt),
     * so a family member whose table is shorter than Odin's 14 levels fails closed rather than
     * writing an out-of-range `max_pwrlevel`.
     */
    private fun odin3ExplicitGpuMaxPowerLevel(
        profileId: String,
        capabilities: HardwareCapabilities,
        catalog: FamilyCatalog,
    ): Int? {
        // The family's catalog GPU table, when it is the explicit-levels kind (today only the 8 Elite).
        // A profile with no level (stock) returns null and the planner falls through, exactly as before.
        val familyId = TweakerHardwareFamilies.resolve(catalog, capabilities.socModel).id
        val gpu = catalog.tables(familyId)?.gpu as? FamilyGpuTable.Levels ?: return null
        return gpu.levelFor(profileId)
    }

    /**
     * Public/product-authority CPU target for [profileId] on this device's own chipset family and
     * live topology, or null if neither the literal certified-family row nor the Odin-anchored
     * transposition (§3) apply -- in which case [plan] falls through to the generic PULSE
     * [generalTierCpuTargetKhz] tier, and [SupportedUncertifiedProfiles] must not claim [profileId]
     * as chipset-supported. Internal (not private) so [SupportedUncertifiedProfiles] can run the
     * exact same self-validating resolution the daemon's own plan derivation uses, rather than a
     * second parallel check that could drift from it.
     */
    internal fun certifiedCpuTargetsKhz(
        profileId: String,
        socModel: String,
        policies: List<CpuPolicy>,
        settledFloorsKhz: Map<Int, Int> = emptyMap(),
        catalog: FamilyCatalog = TweakerHardwareFamilies.catalog(),
    ): Map<Int, Int>? {
        val familyId = TweakerHardwareFamilies.resolve(catalog, socModel).id
        // NOT floor-corrected, deliberately and named so it does not read as covered:
        // [certifiedFamilyCpuTargetsKhz] is the literal 8-Elite table, which validates against
        // `policy.minKhz..policy.maxKhz` on its own (see its body) and never goes through
        // [liveOpps]. The same live-floor mismatch is therefore latent one family over. It has not
        // been observed -- the Odin's live floor has never been measured above its hardware floor --
        // so it is left alone rather than changed on speculation.
        return certifiedFamilyCpuTargetsKhz(profileId, familyId, policies, catalog)
            ?: odinAnchoredTransposedCpuTargetsKhz(profileId, familyId, policies, settledFloorsKhz, catalog)
    }

    private fun certifiedFamilyCpuTargetsKhz(
        profileId: String,
        familyId: String,
        policies: List<CpuPolicy>,
        catalog: FamilyCatalog,
    ): Map<Int, Int>? {
        val cpu = catalog.tables(familyId)?.cpu as? FamilyCpuTable.Khz ?: return null
        val targets = cpu.targets(profileId) ?: return null
        val policiesById = policies.associateBy { it.id }
        if (targets.keys != policiesById.keys) return null
        return targets.takeIf { candidate ->
            candidate.all { (policyId, target) ->
                val policy = policiesById.getValue(policyId)
                target in policy.minKhz..policy.maxKhz &&
                    target in policy.availableFrequenciesKhz
            }
        }
    }

    /**
     * Chipset-support wave stage 1 : expresses
     * Sequence 10's signed Odin 3 vector as each profile's little/prime fraction of Odin's OWN
     * live max (the family catalog's anchor ratios), maps cluster role by ordinal position on the
     * TARGET's own live maxima (ascending -- lowest max is "little", highest is "prime"), linearly
     * interpolates a fraction for any cluster in between (the 2-cluster anchor has no direct data
     * for a middle cluster; ASSUMED per spec §3.2, falsifiable against real thermal measurement),
     * and snaps every result down to an OPP the target's own live table actually admits via the
     * same [snapAtOrBelow] every other tier already uses -- so a derived value can never fail to
     * exist on the device it was computed for.
     *
     * [TweakerHardwareFamilies.SNAPDRAGON_8_ELITE_FAMILY_ID] is deliberately excluded: it IS the
     * anchor family. Its own literal catalog `khz` row (Odin's real
     * numbers) stays authoritative rather than transposing onto itself (spec §3.5) -- an 8-Elite
     * device whose topology does not exactly match Odin's own {0,6} gets no CPU vector from this
     * path, same as today.
     *
     * Returns null for a single-policy topology (no little/prime split is meaningful) or any
     * family whose catalog CPU table is not the anchor kind.
     */
    private fun odinAnchoredTransposedCpuTargetsKhz(
        profileId: String,
        familyId: String,
        policies: List<CpuPolicy>,
        settledFloorsKhz: Map<Int, Int>,
        catalog: FamilyCatalog,
    ): Map<Int, Int>? {
        val anchor = catalog.tables(familyId)?.cpu as? FamilyCpuTable.Anchor ?: return null
        if (policies.size < 2) return null
        val (littleFraction, primeFraction) = anchor.fractions(profileId) ?: return null
        val ordered = policies.sortedBy { policy ->
            liveOpps(policy, settledFloorsKhz[policy.id]).lastOrNull() ?: policy.maxKhz
        }
        val lastOrdinal = ordered.size - 1
        return ordered.mapIndexed { ordinal, policy ->
            val position = ordinal.toDouble() / lastOrdinal
            val fraction = littleFraction + position * (primeFraction - littleFraction)
            val live = liveOpps(policy, settledFloorsKhz[policy.id])
            val liveMax = live.lastOrNull() ?: policy.maxKhz
            val target = (fraction * liveMax).roundToInt()
            policy.id to snapAtOrBelow(live, target, liveMax)
        }.toMap()
    }

    /**
     * Same idea as [odinAnchoredTransposedCpuTargetsKhz], for the single GPU axis (spec §3.4):
     * Odin's own quiet/balanced/performance levels ([ODIN_3_QUIET_GPU_MAX_POWER_LEVEL] etc.)
     * expressed as a position fraction against Odin's own established LIVE level count
     * (the catalog's `anchorLevels`, 14 -- the real 14-entry table pinned in
     * `TuningPlannerTest`/`OdinCpuQosCeilingTest`, not the 6-level table the design spec assumed), then applied to the target's own level count. No
     * cluster-role ambiguity applies -- there is exactly one GPU per device.
     *
     * This transposition governs **Efficiency only**: balanced and
     * performance short-circuit to [UNCAPPED_GPU_MAX_POWER_LEVEL] via
     * the catalog's `uncapped` profiles. The size guard still runs first, so a device with no
     * usable GPU table returns null and falls through exactly as it did before.
     */
    private fun odinAnchoredTransposedGpuMaxPowerLevel(
        profileId: String,
        familyId: String,
        availableAscendingKhz: List<Int>,
        catalog: FamilyCatalog,
    ): Int? {
        val gpu = catalog.tables(familyId)?.gpu as? FamilyGpuTable.Transposed ?: return null
        val sorted = availableAscendingKhz.filter { it > 0 }.distinct().sorted()
        if (sorted.size < 2) return null
        if (profileId in gpu.uncapped) return UNCAPPED_GPU_MAX_POWER_LEVEL
        val fraction = gpu.fraction(profileId) ?: return null
        return (fraction * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
    }

    /**
     * Shared by [generalTierCpuTargetKhz] and [odinAnchoredTransposedCpuTargetsKhz]: the OPPs this
     * policy can actually be driven to right now.
     *
     * **2026-08-23: [settledFloorKhz] added.** Before this, the filter used `policy.minKhz` alone --
     * `cpuinfo_min_freq`, the immutable hardware floor -- so a derived ceiling could land legally
     * inside the OPP table and still be **below the floor the device was actually holding**. The
     * apply-time window admission compares against live `scaling_min_freq` and refused it, correctly
     * and permanently: `cpu policy 3 target 1401600 is below settled live floor 1651200` on the
     * Retroid Pocket Nova, which is why that device had never applied a single profile.
     *
     * Taking the higher of the two floors makes the snap land on the lowest OPP the device will
     * accept instead of one it will refuse. It never lowers a floor and never widens the range --
     * `maxOf` can only remove OPPs from consideration, so on any device whose live floor equals or
     * sits below the hardware floor (the Odin, every device before this) the result is byte-identical
     * to what it was. Null/absent floor means "not settled" and the old behaviour stands, so an
     * unreadable floor fails closed at admission exactly as it does today rather than guessing.
     */
    /**
     * Which floor bounds the OPPs a profile may target.
     *
     * Extracted 2026-08-23 so the WITHDRAWN branch keeps coverage. When
     * [CPU_SETTLED_FLOOR_STEERS_DERIVATION] was turned off, the tests that exercised it were
     * deleted along with it -- leaving the re-armable mechanism with none, so re-arming it would
     * have meant flipping a flag into untested code. A `const val` cannot be varied from a test, so
     * the decision itself is a pure function of its inputs and both branches are pinned directly.
     * The flag stays the single switch; this only makes the switch's other position observable.
     */
    internal fun effectiveCpuFloorKhz(policyMinKhz: Int, settledFloorKhz: Int?, steers: Boolean): Int =
        if (steers) maxOf(policyMinKhz, settledFloorKhz ?: policyMinKhz) else policyMinKhz

    private fun liveOpps(policy: CpuPolicy, settledFloorKhz: Int?): List<Int> {
        val floor = effectiveCpuFloorKhz(
            policyMinKhz = policy.minKhz,
            settledFloorKhz = settledFloorKhz,
            steers = CPU_SETTLED_FLOOR_STEERS_DERIVATION,
        )
        return policy.availableFrequenciesKhz
            .filter { it in floor..policy.maxKhz }
            .distinct()
            .sorted()
    }


    private fun generalTierCpuTargetKhz(tier: CpuTier, policy: CpuPolicy, settledFloorKhz: Int?): Int {
        val live = liveOpps(policy, settledFloorKhz)
        val liveMax = live.lastOrNull() ?: policy.maxKhz
        return when (tier) {
            CpuTier.STOCK -> liveMax
            CpuTier.LARGE_UNDERCLOCK -> snapAtOrBelow(
                available = live,
                target = (liveMax * LARGE_UNDERCLOCK_CPU_FACTOR).roundToInt(),
                fallback = liveMax,
            )
            CpuTier.MEDIUM_UNDERCLOCK -> snapAtOrBelow(
                available = live,
                target = (liveMax * MEDIUM_UNDERCLOCK_CPU_FACTOR).roundToInt(),
                fallback = liveMax,
            )
            // A portable Small tier is the nearest live OPP below Stock. If the
            // topology exposes no lower OPP, stay at Stock instead of inventing one.
            CpuTier.SMALL_UNDERCLOCK -> live.lastOrNull { it < liveMax } ?: liveMax
        }
    }

    internal fun snapAtOrBelow(available: List<Int>, target: Int, fallback: Int): Int {
        val sorted = available.filter { it > 0 }.distinct().sorted()
        return sorted.lastOrNull { it <= target } ?: sorted.firstOrNull() ?: fallback
    }

    internal fun gpuPowerLevel(availableAscendingKhz: List<Int>, factor: Double): Int? {
        val sorted = availableAscendingKhz.filter { it > 0 }.distinct().sorted()
        if (sorted.size < 2) return null
        val target = (sorted.last() * factor).roundToInt()
        val frequency = snapAtOrBelow(sorted, target, sorted.last())
        val ascendingIndex = sorted.indexOf(frequency)
        return sorted.lastIndex - ascendingIndex
    }

    companion object {
        const val LARGE_UNDERCLOCK_CPU_FACTOR = 0.55
        const val MEDIUM_UNDERCLOCK_CPU_FACTOR = 0.78
        const val FULL_GPU_CEILING_FACTOR = 1.0
        // KGSL `max_pwrlevel` is INVERTED with respect to frequency. [gpuPowerLevel] returns
        // `sorted.lastIndex - ascendingIndex`, so level 0 is the FASTEST live OPP and a larger level
        // is slower. On the live Odin 3 table that is: 0 = 1,100 MHz, 1 = 1,050 MHz, 2 = 967 MHz,
        // 3 = 900 MHz, 4 = 832 MHz, 5 = 734 MHz.
        //
        // Performance (`performance`) takes its thermal/power allocation out of the CPU, never the
        // GPU. Sequence 7 tried the opposite and the device got measurably worse. Measured on Odin 3
        // reference device across three allocations, GPU ceiling -> CPU mean / samples above 85 C / samples
        // the profile held: 1,100 MHz -> 70.6 C / 3 / 152, and 1,050 MHz -> 73.5 C / 11 / 115.
        // Slowing the GPU by 5% made the CPU HOTTER and shortened the profile's life, because the
        // capped GPU pushes work back onto the CPU and relocates the heat into the domain that
        // actually trips. The GPU is not what trips: it peaked at 76.5 C, far below any limit.
        // Sequence 8 therefore returned the GPU to the full live table and clamped the CPU instead;
        // sequence 9 does not change that for Performance -- it stays at level 0, exactly Stock's
        // own GPU ceiling.
        //
        // Sequence 9  replaces Quiet's
        // and Balanced's ceiling FACTORS with explicit `gpuMaxPowerLevel` indices, because the
        // exact target frequencies (734 MHz, 967 MHz) cannot be expressed as a factor
        // without depending on rounding, and an explicit index is what the qualification request
        // grammar (`gpuMaxPowerLevel=<index>`, see SIGNING.md) binds -- so the code and the signed
        // manifest now state the same thing in the same units. Quiet moves from the old
        // factor-derived level 8 (525 MHz) to explicit level 5 (734 MHz); Balanced moves from the
        // old factor-derived level 6 (660 MHz) to explicit level 2 (967 MHz). Both remain below
        // Performance's level 0, so every Odin ceiling here still remains at or below the device's
        // Stock envelope on every axis. **Balanced's level 2 was retired on 2026-10-13** for an uncapped GPU on this family: the constant below
        // survives only as the anchor-hardware value behind the catalog's GPU position fractions and
        // is no longer what [odin3ExplicitGpuMaxPowerLevel] returns for balanced. [odin3ExplicitGpuMaxPowerLevel] returns these as raw
        // indices, not values computed from whatever GPU table happens to be live -- the same
        // convention [selectedFanMode] already uses for this exact device profile -- because they
        // are grounded directly in the device's own `freq_table_mhz`, not derived from it at
        // runtime.
        const val ODIN_3_QUIET_GPU_MAX_POWER_LEVEL = 5
        const val ODIN_3_BALANCED_GPU_MAX_POWER_LEVEL = 2
        const val ODIN_3_PERFORMANCE_GPU_MAX_POWER_LEVEL = 0

        /**
         * The T-0 balanced WLE capture showed the
         * governor swinging across the entire admitted band (967/900/832/734/660 -- indices
         * 2..6), i.e. using exactly the room the vendor's captured `min_pwrlevel` 6 gave it.
         * The floor change is one value: `min_pwrlevel` 6 -> 3, pinning balanced's swing room to
         * 967..900 MHz. Explicit index, not a factor -- the same exact-device-profile-ID convention as the three ceiling levels directly above.
         */
        const val ODIN_3_BALANCED_GPU_MIN_POWER_LEVEL = 3

        /**
         * **The 2026-08-18 floor is WITHDRAWN. The Odin's firmware will not hold it.**
         *
         * After the floor was measured livelocking the reference device on 2026-08-22,
         * floor writes were disabled. Balanced kept its 967 MHz ceiling and its CPU clamps; it no longer
         * asked for a GPU minimum. (The 967 MHz ceiling was itself retired 2026-10-13 -- balanced's
         * GPU is uncapped, level 0 -- which also removes the cap-faster-than-the-vendor-floor
         * collision the vendor performance mode would otherwise have created.)
         *
         * What was measured on Odin `reference device`, sampling `/sys/class/kgsl/kgsl-3d0` through several
         * cycles: Mimir writes `min_pwrlevel=3`, and within five seconds the kernel resets it to the
         * vendor's own `6` while leaving `max_pwrlevel` untouched. That is not drift and not
         * tampering -- it is the firmware declining a floor *above* its stock floor, every time.
         * The write even passes apply-time readback (11 samples over ~225 ms), so nothing catches it
         * until the supervisor re-reads the node a few seconds later and correctly calls it contract
         * drift, restores stock, and the frontend correctly re-arms the standing balanced profile.
         * The device had cycled that loop past **generation 1244**.
         *
         * Returning null here restores behaviour byte-identical to before the 2026-08-18 floor change:
         * both consulting sites fall back to preserving the vendor's captured bound, which is also
         * the cooling direction and so satisfies T-B. Note the wider invariant this floor was always
         * in tension with -- this class's own header states *"No Mimir profile raises a minimum ...
         * the captured KGSL min_pwrlevel is preserved, never elevated."*
         *
         * Kept as a flag rather than deleted, deliberately. The mechanism is correct and the 08-18
         * thermal intent still stands; what is missing is a transport that can hold a floor against
         * firmware that reasserts it -- the GPU analogue of the CPU's QoS composition. Flip this
         * back to true once such a transport exists, and re-measure before trusting it.
         */
        const val ODIN_3_BALANCED_GPU_FLOOR_ENFORCEABLE = false

        /**
         * **WITHDRAWN 2026-08-23 after device measurements — kept re-armable in the
         * same shape as [ODIN_3_BALANCED_GPU_FLOOR_ENFORCEABLE].**
         *
         * The repair was real and the mechanism is sound: derivation read `cpuinfo_min_freq` while
         * apply-time admission enforces live `scaling_min_freq`, so a plan could name a ceiling the
         * device would refuse, and the Nova had never applied a profile because of it. Teaching
         * derivation the settled floor did work — the Nova applied `balanced` for the first time, with
         * `policy3 scaling_max_freq` written at 1651200 and zero admission refusals.
         *
         * **What it did not survive is what happened next.** With the Nova finally applying, the device
         * showed the rest of the story: it does not HOLD a Mimir CPU ceiling. Within seconds every
         * policy reverted to its stock maximum (`observed={0=2016000, 3=2707200, 7=3187200}`), the
         * supervisor saw `TARGET_CONTRACT_DRIFT_CPU`, restored, and the bridge re-applied — 21 restores
         * and 12 generations in four minutes, each cycle re-arming the Guardian foreground service. This
         * churn disrupted fan mode, brightness and joystick-colour controls, pserver binding and profile
         * changes: a session
         * torn down and rebuilt every twenty seconds cannot hold an applied contract for the fan row to
         * attach to, and the repeated arm/restore traffic starves the very transport the other controls
         * ride on. The Odin, on identical code, logged **zero** drift events.
         *
         * **And the premise itself was falsified.** The floor measured 1651200, stable over five
         * hours and identical at `performance_mode` 0 and 1, which initially suggested a persistent value.
         * Every one of those readings was taken with the device idle at stock. Under a live Mimir
         * session the derived vector moved to `{3=1920000}` — so the floor moves too, exactly when it
         * matters. A measurement that never varied the condition the value depends on is not a
         * measurement of that value.
         *
         * Before re-arming this, the question to answer is not about the floor at all: **why does this
         * chassis revert `scaling_max_freq` to stock within the drift window?** Until that is understood,
         * allowing the Nova to apply disrupts other controls, while refusing is
         * stable and leaves every other control working, which is where this flag now puts it.
         *
         * The settled-floor probe and [HardwareCapabilities.cpuSettledFloorsKhz] are deliberately KEPT
         * and still populated: they cost one bounded read per topology cache fill and they are now
         * reported in the admission refusal log, so subsequent investigation starts with the
         * numbers rather than re-deriving them.
         */
        internal const val CPU_SETTLED_FLOOR_STEERS_DERIVATION = false

        /**
         * The profile-supplied GPU floor for [profileId] on [deviceProfileId], or null when the
         * derived behaviour (preserve the vendor's captured lower-performance bound) must stay
         * byte-identical to before the 2026-08-18 floor change. Non-null ONLY for the exact Odin 3
         * device profile's balanced plan: quiet's signed ceiling (level 5, 734 MHz) is itself
         * below 900 MHz so the floor cannot admit it, and performance's envelope belongs to
         * T-2. Deliberately NOT a [TuningPlan] field: `PlanHash` and the signed qualification
         * manifest's `planIdentityHash` never see the floor, so the installed signed sequence
         * remains valid and every plan identity is unchanged.
         *
         * Guards fail toward the captured bound -- the cooler direction (T-B: ambiguous
         * branches fail toward cooling): the floor must admit the requested ceiling
         * ([gpuMaxPowerLevel], inverted indices -- larger is slower) and sit inside the live
         * table ([slowestLevel]), or the caller keeps today's derived value.
         *
         * Both the snapshot-capture site (`HardwareProbe.captureForPlan`, which decides whether
         * the min node is owned at all) and the mutation-planning site
         * (`PServerHardwareCommandPort.plannedMutations`, which decides the admitted value)
         * MUST consult this one function so ownership and emission never diverge.
         *
         * **This remains an exact-device predicate.** [ODIN_3_BALANCED_GPU_FLOOR_ENFORCEABLE]
         * `= false` makes this function return null unconditionally today, for every input --
         * widening the device check below has zero observable effect while the flag stays false,
         * and would still require a signature change (`deviceProfileId` -> `socModel`) touching
         * two call sites and eight pinned test assertions for a branch that cannot currently run.
         * Deferred rather than churned; convert together with re-enabling the floor if a transport
         * that can hold it against firmware reset ever exists (see the flag's own KDoc).
         */
        fun profileSuppliedGpuMinPowerLevel(
            deviceProfileId: String,
            profileId: String,
            gpuMaxPowerLevel: Int,
            slowestLevel: Int,
        ): Int? {
            if (deviceProfileId != CpuCeilingTransport.ODIN_3_PROFILE) return null
            if (profileId != "balanced") return null
            // - see ODIN_3_BALANCED_GPU_FLOOR_ENFORCEABLE. The firmware
            // resets this node within ~5s of a successful, readback-verified write, and the
            // supervisor then restores stock forever. Ask for no floor at all.
            if (!ODIN_3_BALANCED_GPU_FLOOR_ENFORCEABLE) return null
            return ODIN_3_BALANCED_GPU_MIN_POWER_LEVEL.takeIf {
                it in gpuMaxPowerLevel..slowestLevel
            }
        }

        /**
         * KGSL `max_pwrlevel` 0 -- the full live GPU table, i.e. no Mimir-imposed ceiling at all.
         * Named rather than written as a bare `0` because in this inverted index space zero is the
         * FASTEST level, which reads backwards to anyone expecting "0 = off".
         *
         * The 2026-08-22 policy followed a Nova observation: Balanced and Performance use uncapped
         * GPU, while Efficiency carries a mild cap proportional to the Snapdragon Elite setting.
         * Balanced and performance are therefore level 0 on every family and only quiet carries
         * a GPU ceiling. This is consistent with, not a relaxation of, the thermal
         * finding recorded above: on the Odin a GPU ceiling made the CPU *hotter*, because capped
         * GPU work relocates into the domain that actually trips.
         *
         * **The per-family numbers are data now.** Which families have a literal CPU row, which are
         * transposed from the Odin anchor, the anchor ratios, the GPU levels and fractions and the
         * vendor-mode row all live in the family catalog (`assets/tweaker-families.json`, compiled
         * default [FamilyCatalogCompiledDefault]) and are read through
         * [TweakerHardwareFamilies.tables]. They used to be private constants here
         * (`MIMIR_CERTIFIED_FAMILY_CPU_TARGETS_KHZ`, `ODIN_TRANSPOSITION_ELIGIBLE_FAMILY_IDS`,
         * `ODIN_ANCHOR_CPU_FRACTIONS`, `ODIN_ANCHOR_GPU_POSITION_FRACTIONS`,
         * `TRANSPOSED_UNCAPPED_GPU_PROFILE_IDS`, `ODIN_3_LIVE_GPU_LEVEL_COUNT`); their provenance is
         * in [FamilyCatalogCompiledDefault]'s KDoc, and `FamilyCatalogPlanIdentityTest` pins that
         * every plan is byte-identical to the one they produced.
         */
        const val UNCAPPED_GPU_MAX_POWER_LEVEL = 0

        // Declared in the :contract module both builds share, so the client cannot drift from
        // these by value -- see TweakerVendorFanModes.
        const val FAN_SILENT = TweakerVendorFanModes.SILENT
        const val FAN_SMART = TweakerVendorFanModes.SMART
        const val FAN_SPORT = TweakerVendorFanModes.SPORT
        /** Vendor manual passthrough; duty is owned only by the authenticated fan controller. */
        const val FAN_MANUAL = TweakerVendorFanModes.MANUAL
        val KNOWN_FAN_MODES: Set<Int> = setOf(FAN_SILENT, FAN_SMART, FAN_SPORT, FAN_MANUAL)

        /**
         * Modes under which Mimir's authenticated fan controller directly drives PWM duty.
         * Device-measured, commit 87f02042: the vendor's own
         * duty-regulation loop is a 4000 ms `postDelayed` runnable that only re-posts while
         * `fan_mode == FAN_SMART`; both SPORT and MANUAL stop that loop, and a direct duty write
         * persists under either. SMART is deliberately excluded -- it is the one mode whose loop
         * still fights a direct duty write.
         */
        val MIMIR_DUTY_OWNED_FAN_MODES: Set<Int> = TweakerVendorFanModes.MIMIR_DUTY_OWNED

        /**
         * Conservative explicit vendor fan_mode range for captured-snapshot restore authority.
         * Apply targets remain limited to [KNOWN_FAN_MODES]; stock `0` is in-range.
         *
         * This range is intentionally left admitting 5 (SPORT) and 6 (MANUAL) -- narrowing it was
         * considered and scoped OUT  and
         * left here as a deliberate, documented choice, not an oversight:
         * - This constant is a capture-time validator with seven call sites across
         *   `AndroidTransactionPorts`, `TransactionJournal`, `AndroidHardware`,
         *   `QualificationManifest`, and `RestoreGuardianEnvelope` -- not just the restore-execute
         *   path. Excluding 5/6 here would reject snapshots as *invalid captures* everywhere,
         *   including journal/envelope validation that has nothing to do with how a later restore
         *   is executed.
         * - Whether a snapshot can legitimately capture 5 or 6 (e.g. process-death recovery
         *   re-capturing mid-session) is itself UNKNOWN -- one-writer discipline should exclude
         *   it, but nothing here proves it never happens, and capture-of-5 was device-observed (see `PServerHardwareCommandPort.restoreCapturedFanMode`'s kdoc).
         * - The actual danger this range enabled -- a plain-put restore silently re-entering
         *   MANUAL without the handoff protocol -- is neutralized at the point of execution
         *   instead: `restoreCapturedFanMode` now refuses `value == FAN_MANUAL` outright, so this
         *   range admitting 6 can no longer reach an unsafe write. Tightening the range too would
         *   be redundant defense with a real risk of breaking capture-time validation elsewhere
         *   for a case (5) that is not itself unsafe to capture -- SPORT needs no handoff.
         */
        const val VENDOR_FAN_MODE_MIN = 0
        const val VENDOR_FAN_MODE_MAX = 7

        fun isCapturedVendorFanMode(value: Int): Boolean =
            value in VENDOR_FAN_MODE_MIN..VENDOR_FAN_MODE_MAX

        /**
         * The vendor's three `settings system performance_mode` values, named as the vendor UI
         * names them. The profile mapping is
         * 0 = Standard, 1 = Performance, 2 = High performance, and the profile map is
         * quiet -> Standard, balanced -> Performance, performance -> High performance
         * (stock -> never written).
         *
         * The key is consumed by the ODM `pservice` (about every 10 s), which moves the CPU and GPU
         * FLOORS; Mimir keeps owning the CEILINGS. Earlier notes called mode 1 "the Nova's stock"
         * and mode 0 "the Odin's stock" -- those are what each unit happened to ship with, not a
         * ranking.
         */
        const val VENDOR_MODE_STANDARD = 0
        const val VENDOR_MODE_PERFORMANCE = 1
        const val VENDOR_MODE_HIGH_PERFORMANCE = 2

        /**
         * Apply targets for the vendor mode are limited to these, exactly as apply fan targets are
         * limited to [KNOWN_FAN_MODES]. A restore may still carry a captured value (the honest
         * origin, see [VendorModeOriginRecord]) -- the axis is only present when the live key reads
         * inside this set, so the origin is inside it too.
         */
        val KNOWN_VENDOR_MODES: Set<Int> =
            setOf(VENDOR_MODE_STANDARD, VENDOR_MODE_PERFORMANCE, VENDOR_MODE_HIGH_PERFORMANCE)
    }
}

/**
 * Which vendor `performance_mode` each profile drives, per hardware family.
 *
 * The profile default is [TuningPlanner]'s `ProfileShape.vendorMode` (stock null, quiet Standard,
 * balanced Performance, performance High performance; see
 * [TuningPlanner.VENDOR_MODE_STANDARD]). A family appears in [FAMILY_OVERRIDES], keyed by
 * [TweakerHardwareFamilies]'s `id`, only when a device check shows it must differ; none does yet, so
 * every family that has the key (the Odin's 8 Elite, the Thor's 8 Gen 2, the Nova's SM8250, and any
 * other pserver device whose key reads 0..2) takes the default row.
 *
 * **This table selects values; it never gates the axis.** Presence is
 * `TuningAxis.VENDOR_MODE in supportedAxes`. A family absent from the overrides still
 * plans its default row, because the family is not a reason to withhold the mode.
 */
internal object VendorModeTable {
    /** `familyId -> (profileId -> mode)`; a value here replaces the profile default for that family. */
    val FAMILY_OVERRIDES: Map<String, Map<String, Int>> = emptyMap()

    /**
     * Stock is never written, whatever the table says: it is the player's own mode. Any resolved
     * value outside [TuningPlanner.KNOWN_VENDOR_MODES] is dropped to null rather than planned.
     */
    fun modeFor(
        familyId: String,
        profileId: String,
        profileDefault: Int?,
        overrides: Map<String, Map<String, Int>> = FAMILY_OVERRIDES,
    ): Int? {
        if (profileId == "stock") return null
        val mode = overrides[familyId]?.get(profileId) ?: profileDefault
        return mode?.takeIf { it in TuningPlanner.KNOWN_VENDOR_MODES }
    }
}
