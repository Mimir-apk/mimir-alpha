package com.gruning.mimir.tweaker

/**
 * Supported chipset-family profiles derive from each device's live OPP tables. [compute] describes
 * selectable IDs and [admitsPlan] re-derives through the same [TuningPlanner] to admit their plans.
 * Neither uses a deviceProfileId allow-list. Chip-family membership plus live-OPP self-validation
 * is the sole ordinary apply-admission route, including on Odin; signed manifests no longer
 * control that route. The retained ProductQualificationRuntime/QualificationManifest machinery
 * still supports qualifiedDerateRungPlan, qualifiedManualFanPlan and internal provenance.
 *
 * Selectable and appliable share one predicate. Downstream safety checks remain:
 * - WRITE_GATE_DISABLED before mutations;
 * - corrupt/active transaction-journal checks;
 * - ProductOwnerHardLeasePolicy.HARD_LEASE_MS, twelve hours;
 * - PlanTargetValidator.validate on every apply, independently rejecting off-table or out-of-range targets;
 * - Guardian, exact-Stock rollback and the 105C trip.
 *
 * DiagnosticsCalibrationHoldLeasePolicy's 360-second lease is reachable only through LAB_APPLY_HOLD
 * and is compile-dead in Production when DIAGNOSTICS_COMPILED is false. It never bounded these
 * profiles. Product SYSTEM sessions instead take the 43_200_000L hard deadline from
 * ProductOwnerHardLeasePolicy through ActiveSessionLeaseDeadline.initial; leaseRenewable()
 * refuses SYSTEM. Profiles with different internal certification provenance have the same lifetime.
 * The former attended lease fields and DeviceAttendedPort are removed.
 *
 * Live-OPP derivation and independent validation establish target legality, not sustained thermal
 * calibration. The twelve-hour bound permits an unmeasured sustained envelope. Android's thermal
 * HAL and SupervisorPolicy.androidThermalTrip (THERMAL_STATUS_SEVERE) remain thermal safeguards.
 * This object describes admitted profiles; it makes no hardware certification claim.
 */
internal object SupportedUncertifiedProfiles {
    private val PLANNER = TuningPlanner()
    private val PROFILE_IDS = setOf("quiet", "balanced", "performance")

    /**
     * [compute] is a **pure function of its three arguments**, and it is not cheap: it runs six
     * [TuningPlanner] derivations (three [certifiedCpuTargetsKhz] and three full [TuningPlanner.plan]
     * fan probes). `HardwareProbe.probe()` calls it on every probe, and the telemetry path probes at
     * up to 1 Hz, so on the measured device those six derivations ran once a second forever for an
     * answer that cannot change unless the hardware does.
     *
     * Memoised on the exact argument tuple. Because the function is pure, the memo is correct **by
     * construction** rather than by a freshness argument: any input that could change the answer is
     * part of the key, so a changed input is a miss, and an unchanged input cannot have a different
     * answer. That is deliberately a different mechanism from a TTL -- a TTL would re-derive work
     * whose result provably has not moved, and would still serve a stale answer inside its window.
     *
     * One entry is the realistic steady state (one chassis, one topology). The map is bounded anyway
     * so a pathological caller cannot grow it without limit; on overflow it is cleared rather than
     * evicted piecewise, because every entry is equally cheap to re-derive and an eviction policy
     * here would be machinery with no measured need.
     */
    private val computeMemo = LinkedHashMap<ComputeKey, Set<String>>()

    /**
     * The catalog INSTANCE [computeMemo] was filled under (guarded by the memo's monitor). Family
     * membership and treatment -- the admission authority here -- come from the catalog in force, so the
     * memo is valid for one catalog object only: a [compute] under a different one empties it first, and
     * never stores an answer derived from a catalog that is no longer the memo's (F-P3-4, 2026-10-13
     * review: a miss that straddled a promotion used to store the old catalog's answer under the new one).
     */
    private var memoCatalog: FamilyCatalog? = null

    /**
     * **Deliberately not the whole [CpuPolicy].** `HardwareProbe.probe()` refreshes `currentKhz`
     * live on every single call ("a value literally named current is never served frozen"), so a
     * key that used data-class equality on [CpuPolicy] would differ on nearly every probe and the
     * memo would never hit -- a cache that is pure overhead, which is worse than no cache.
     *
     * Keyed on exactly what [compute] can actually read: `id`, `path`, `cpuIds`,
     * `availableFrequenciesKhz`, `minKhz`, `maxKhz`. Verified rather than assumed --
     * `currentKhz` is written by the probe and read only by `BundleAdapters`' log line; no
     * [TuningPlanner] path touches it, so it provably cannot move this answer. If a future change
     * makes a derivation read `currentKhz`, `compute is memoised on topology, not on live
     * frequency` in the test file is the gate that should redden.
     */
    private data class ComputeKey(
        val status: CapabilityStatus,
        val socModel: String,
        val topology: List<PolicyTopology>,
    )

    private data class PolicyTopology(
        val id: Int,
        val path: String,
        val cpuIds: List<Int>,
        val availableFrequenciesKhz: List<Int>,
        val minKhz: Int,
        val maxKhz: Int,
    )

    private fun computeKey(
        status: CapabilityStatus,
        socModel: String,
        cpuPolicies: List<CpuPolicy>,
    ) = ComputeKey(
        status = status,
        socModel = socModel,
        topology = cpuPolicies.map {
            PolicyTopology(
                id = it.id,
                path = it.path,
                cpuIds = it.cpuIds,
                availableFrequenciesKhz = it.availableFrequenciesKhz,
                minKhz = it.minKhz,
                maxKhz = it.maxKhz,
            )
        },
    )

    private const val COMPUTE_MEMO_MAX_ENTRIES = 8

    /**
     * Drop the memo. The key is (status, socModel, topology) and the memo is bound to the catalog
     * instance it was filled under ([memoCatalog]), so a promoted catalog is already a miss; this stays as
     * the explicit reset [FamilyCatalogPromotion] calls after installing one, and for tests.
     */
    internal fun clearComputeMemo() {
        synchronized(computeMemo) {
            computeMemo.clear()
            memoCatalog = null
        }
    }

    /** Test seam only: the memo is process-lifetime state and unit tests must not inherit it. */
    internal fun clearComputeMemoForTest() = clearComputeMemo()

    internal fun computeMemoSizeForTest(): Int = synchronized(computeMemo) { computeMemo.size }

    /**
     * All three of quiet/balanced/performance, or none -- atomic, matching the existing
     * [TuningPlanner]-internal certified-family self-validation convention (a partially-supported
     * family would misrepresent itself as much as a partially-signed one would). Every id this
     * returns is guaranteed to resolve, via [TuningPlanner.certifiedCpuTargetsKhz], to a CPU vector
     * whose every value already exists in that policy's own live `availableFrequenciesKhz` -- by
     * construction, since that resolution only ever returns values [TuningPlanner.snapAtOrBelow]
     * selected from that exact live list.
     *
     * **Fan-axis admission.** [admitsPlan] now admits a
     * [TuningPlanner.FAN_SMART]-bearing plan (narrowed from refusing every fan-bearing plan
     * outright), so this function's own silence about the fan axis stopped being harmless: this
     * object's opening KDoc requires "the predicate that makes a profile selectable must be the
     * same truth that makes it appliable," and this is family/socModel-scoped by design (chipset
     * support, not exact-chassis), so it has no `deviceProfileId` to gate on directly. It asks the
     * SAME question [admitsPlan] will ask, on a synthetic family-generic device (a non-Odin
     * placeholder id, since [compute] never distinguishes the literal Odin chassis from a sibling
     * on the same silicon): does [TuningPlanner] ever derive a fan mode other than null or
     * [TuningPlanner.FAN_SMART] for one of [PROFILE_IDS] on this family? Today the answer is always
     * no -- only the exact Odin device profile id ever derives a non-Smart mode
     * ([TuningPlanner.selectedFanMode]), and this probe deliberately never uses that id, matching
     * this function's own family-wide semantics. Kept anyway, belt-and-braces, the same posture
     * this object's [admitsPlan] KDoc already uses for its own fan check: a future profile-shape
     * edit that reintroduced SILENT/SPORT/MANUAL on a base (non-Odin-gated) tier would be caught
     * here too, not only at [admitsPlan]'s separate, redundant fan check.
     *
     * The literal Odin chassis reaching this function is no longer a special case at all, resolved
     * (not merely narrowed) by the 2026-08-22 (late) retirement: [compute] describes the Odin's own
     * family exactly like any other member, unconditionally, and [admitsPlan] admits it the same
     * way, because a signed manifest is no longer consulted for admission regardless of whether one
     * resolves, transiently or otherwise. The old dual-admission ambiguity is resolved: there is no longer a signed route for an uncertified derivation to shadow, race
     * or widen, so the tension they described no longer exists. `UncertifiedProfileApplyAdmissionTest`'s
     * `a signed device is still refused if its manifest resolution transiently reads unavailable` is
     * rewritten to assert the new invariant directly rather than staying RED against a premise the admission change removed.
     */
    fun compute(
        status: CapabilityStatus,
        socModel: String,
        cpuPolicies: List<CpuPolicy>,
    ): Set<String> = compute(TweakerHardwareFamilies.catalog(), status, socModel, cpuPolicies)

    /** [compute] against [catalog], which the caller read once (see [admitsPlan]). */
    internal fun compute(
        catalog: FamilyCatalog,
        status: CapabilityStatus,
        socModel: String,
        cpuPolicies: List<CpuPolicy>,
    ): Set<String> {
        val key = computeKey(status, socModel, cpuPolicies)
        synchronized(computeMemo) {
            if (memoCatalog !== catalog) {
                computeMemo.clear()
                memoCatalog = catalog
            }
            computeMemo[key]
        }?.let { return it }
        val computed = computeUncached(catalog, status, socModel, cpuPolicies)
        synchronized(computeMemo) {
            // Another caller may have moved the memo to a different catalog while this one was deriving:
            // then this answer belongs to a catalog the memo no longer serves, so it is returned, not stored.
            if (memoCatalog === catalog) {
                if (computeMemo.size >= COMPUTE_MEMO_MAX_ENTRIES) computeMemo.clear()
                computeMemo[key] = computed
            }
        }
        return computed
    }

    private fun computeUncached(
        catalog: FamilyCatalog,
        status: CapabilityStatus,
        socModel: String,
        cpuPolicies: List<CpuPolicy>,
    ): Set<String> {
        if (status == CapabilityStatus.UNSUPPORTED) return emptySet()
        val family = TweakerHardwareFamilies.resolve(catalog, socModel)
        if (family.profileTreatment != TweakerHardwareFamilies.MIMIR_CERTIFIED_REFERENCE) {
            return emptySet()
        }
        val allValidated = PROFILE_IDS.all { profileId ->
            PLANNER.certifiedCpuTargetsKhz(profileId, socModel, cpuPolicies, catalog = catalog) != null
        }
        if (!allValidated) return emptySet()
        val fanModeAdmissible = PROFILE_IDS.all { profileId ->
            val syntheticFanMode = PLANNER.plan(
                profileId = profileId,
                packageName = FAN_PROBE_PACKAGE_NAME,
                capabilities = fanProbeCapabilities(status, socModel, cpuPolicies),
                catalog = catalog,
            )?.fanMode
            syntheticFanMode == null || syntheticFanMode == TuningPlanner.FAN_SMART
        }
        return if (fanModeAdmissible) PROFILE_IDS else emptySet()
    }

    /**
     * A minimal, non-Odin, family-generic device carrying only what [TuningPlanner.plan] actually
     * reads to derive `.fanMode` -- [socModel] (for the CPU-cert branch, unused by the fan
     * derivation itself but harmless to include) and a fan axis, so [TuningPlanner.selectedFanMode]
     * takes its real `else -> vendorMode` branch instead of short-circuiting on a missing axis.
     * [FAN_PROBE_DEVICE_PROFILE_ID] is deliberately not any real device's id -- see [compute]'s
     * KDoc for why that is the correct generic stand-in, not a gap.
     */
    private fun fanProbeCapabilities(
        status: CapabilityStatus,
        socModel: String,
        cpuPolicies: List<CpuPolicy>,
    ) = HardwareCapabilities(
        status = status,
        manufacturer = "",
        model = "",
        socModel = socModel,
        buildFingerprint = "",
        deviceProfileId = FAN_PROBE_DEVICE_PROFILE_ID,
        supportedAxes = setOf(TuningAxis.CPU, TuningAxis.FAN),
        pServerAvailable = false,
        cpuPolicies = cpuPolicies,
        gpuFrequenciesKhz = emptyList(),
        gpuNodeAvailable = false,
        fanNodeAvailable = true,
        refreshRatesHz = emptyList(),
        notes = emptyList(),
    )

    private const val FAN_PROBE_PACKAGE_NAME = "com.gruning.mimir.tweaker.internal.fan-probe"
    private const val FAN_PROBE_DEVICE_PROFILE_ID = "mimir-uncertified-fan-probe"

    /**
     * Stage 3 apply admission: may [plan] reach hardware? **This is the only apply admission** -- `MimirTweakerService.planAdmittedForApply` no longer consults
     * `ProductQualificationResolver.planMatches` at all, for any device. There is no second,
     * signed route left for this to run "alongside" or "instead of".
     *
     * Fails closed on every axis, and each refusal is load-bearing rather than defensive padding.
     * `MANIFEST_GOVERNED_DEVICE_PROFILE_IDS` -- a same-day, device-specific gate that refused the
     * exact Odin id unconditionally so an uncertified derivation could never race a transiently
     * unresolved signed one -- is DELETED, not widened: with no signed route consulted for
     * admission any more, there is nothing left for it to race. See the class KDoc above for what
     * still keeps the Odin's `manualFanOverride`/`derateRungs` machinery alive without this gate.
     *
     * The remaining gates, in order:
     *  - PRODUCTION posture with hardware writes compiled, and NOT a diagnostics build. LAB already
     *    short-circuits `calibrationLookup` to true for its own rung programme; it must not also
     *    acquire a second, quieter route to the same permission.
     *  - **[TuningPlan.fanMode] must be null, [TuningPlanner.FAN_SMART], or -- since 2026-08-23 and
     *    only through the explicit [TuningPlanner.FanIntent.MANUAL_OVERRIDE] re-derivation --
     *    [TuningPlanner.FAN_MANUAL].** SILENT and SPORT remain refused outright on every device;
     *    they are the modes that stop the vendor loop without handing Mimir the duty, which is the
     *    measured Odin defect this whole family of gates exists to prevent. MANUAL is different in
     *    kind: it stops the vendor loop AND hands Mimir the duty, under a player-initiated opt-in,
     *    with `SmartFanThermalController` driving it. The original narrowing text follows, and its
     *    reasoning about null-vs-SMART is unchanged. Narrowed 2026-08-22
     *    (fan-axis narrowing) from a blanket "must be null": `HandheldDeviceProfiles.supportsFanMode` widened
     *    to the whole RP/AYN family the same day, so a fan-bearing uncertified device (the Nova, once
     *    wired) is now the ordinary case here, not an impossibility -- a blanket refusal would close
     *    the code-resident route on every device §3 exists to open it for. This is strictly SAFER
     *    than the old all-or-nothing, not a relaxation: SMART is the only vendor mode that leaves the
     *    vendor's own regulation loop running (R2/R3), and SILENT/SPORT/MANUAL -- every mode that
     *    would stop it -- are still refused outright. "Ambiguous branches fail toward cooling" is
     *    satisfied BY this narrowing, not weakened by it. [compute] enforces the identical rule (see
     *    its own KDoc) so the two can never describe a profile this gate would then refuse.
     *  - [compute] must already describe this id, so selectable and appliable cannot diverge.
     *  - **Exact re-derivation.** The plan must equal, on all three axes, what [TuningPlanner]
     *    itself derives for this profile on this live hardware. A bare `TuningPlanner()` defaults
     *    its own `calibrationLookup` to `{ _, _ -> false }`, so this re-derivation cannot recurse
     *    and yields the uncalibrated candidate for comparison. Equality-against-recompute is what
     *    keeps hand-built plans, LAB rung plans and manual-fan plans out: none of them reproduce
     *    the profile's own derived vector -- including a MANUAL/SPORT plan, refused first by the
     *    fan-mode leg above regardless.
     */
    /**
     * **Every refusal below now names itself — policy 2026-08-31.**
     *
     * This function had eight silent `return false`s and one caller that logged nothing, so a rejected
     * apply on the Thor produced no evidence whatsoever: the source said which EIGHT things
     * could refuse and the device said nothing about which one did. Four rounds of reading code went
     * into the client-side equivalent of this before one added log line answered it in a single look.
     *
     * The log is unconditional rather than DEBUG-gated: an apply is a rare, player-initiated act, and a
     * refusal is exactly the moment somebody needs the reason. A tag nobody can read is the same defect
     * as no tag at all.
     */
    private fun refuse(reason: String): Boolean {
        android.util.Log.w("MimirTweakerAdmit", "plan refused: $reason")
        return false
    }

    fun admitsPlan(
        plan: TuningPlan,
        capabilities: HardwareCapabilities,
    ): Boolean {
        if (!TweakerPostureTruth.hardwareWritesCompiled) return refuse("hardware writes not compiled")
        if (TweakerPostureTruth.diagnosticsCompiled) return refuse("diagnostics compiled in")
        if (TweakerPostureTruth.posture != TweakerPostureTruth.PRODUCTION) {
            return refuse("posture=${TweakerPostureTruth.posture}")
        }
        if (capabilities.status == CapabilityStatus.UNSUPPORTED) return refuse("status UNSUPPORTED")
        if (!capabilities.pServerAvailable) return refuse("pserver unavailable")
        // 2026-08-23, manual-fan lane: [TuningPlanner.FAN_MANUAL] joins null/SMART as an
        // admissible fan mode, through its OWN route rather than by relaxing the base check.
        // The base fan-mode restriction is NOT weakened -- SILENT and SPORT both stop the vendor regulation
        // loop and are still refused outright, on every device, which is the whole of what this base restriction protects. MANUAL is admitted on a different footing: it is not a profile shape any
        // ordinary apply can derive (a PROFILE_AUTO re-derivation yields SMART and would refuse it
        // at the equality leg below), only the explicit, player-initiated
        // [TuningPlanner.FanIntent.MANUAL_OVERRIDE] opt-in reaches it, and while it is held Mimir
        // owns duty and the thermal duty of care that comes with it; vendor SMART remains vendor-controlled.
        //
        // The re-derivation below is run with the SAME intent the plan claims, so equality-against-
        // recompute keeps its full strength: a hand-built MANUAL plan still cannot pass unless it
        // reproduces, on all three axes, exactly what this planner derives for MANUAL_OVERRIDE on
        // this live hardware.
        val fanIntent = when (plan.fanMode) {
            null, TuningPlanner.FAN_SMART -> TuningPlanner.FanIntent.PROFILE_AUTO
            TuningPlanner.FAN_MANUAL -> {
                if (TuningAxis.FAN !in capabilities.supportedAxes) {
                    refuse("fan axis absent: axes=${capabilities.supportedAxes}")
                    return false
                }
                if (!capabilities.fanNodeAvailable) {
                    refuse("fan node unavailable")
                    return false
                }
                TuningPlanner.FanIntent.MANUAL_OVERRIDE
            }
            else -> return refuse("fan mode ${plan.fanMode} is neither null, SMART nor MANUAL")
        }
        // One catalog for the describable set and the re-derivation: they must agree about the family.
        val catalog = TweakerHardwareFamilies.catalog()
        val describable = compute(catalog, capabilities.status, capabilities.socModel, capabilities.cpuPolicies)
        if (plan.profileId !in describable) {
            return refuse("profile ${plan.profileId} not in computed set $describable")
        }
        val derived = PLANNER.plan(plan.profileId, plan.packageName, capabilities, fanIntent, catalog)
            ?: return refuse("planner derived nothing for ${plan.profileId} intent=$fanIntent")
        // vendorMode joins the equality (the vendor-mode transport, 2026-10-13): the planner derives it
        // from the live capability and the family table, so a hand-built plan that names a different
        // mode -- or one that omits a mode the live device would plan -- must not be admitted.
        val matches = derived.cpuMaxKhz == plan.cpuMaxKhz &&
            derived.gpuMaxPowerLevel == plan.gpuMaxPowerLevel &&
            derived.fanMode == plan.fanMode &&
            derived.vendorMode == plan.vendorMode
        if (!matches) {
            refuse(
                "re-derivation differs: cpu ${plan.cpuMaxKhz} vs ${derived.cpuMaxKhz}; " +
                    "gpu ${plan.gpuMaxPowerLevel} vs ${derived.gpuMaxPowerLevel}; " +
                    "fan ${plan.fanMode} vs ${derived.fanMode}; " +
                    "vendorMode ${plan.vendorMode} vs ${derived.vendorMode}",
            )
        }
        return matches
    }
}
