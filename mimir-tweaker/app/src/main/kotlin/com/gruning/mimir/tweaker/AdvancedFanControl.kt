package com.gruning.mimir.tweaker

import android.util.Log

internal enum class PublicFanControlMode {
    AUTO,
    MANUAL,
}

internal data class ActiveFanControlSelection(
    val generation: Long,
    val transactionId: String,
    val requestId: String,
    val profileId: String,
    val mode: PublicFanControlMode,
    val curveBias: Int,
) {
    fun strategy(): FanThermalStrategy = when (mode) {
        PublicFanControlMode.AUTO -> FanThermalStrategy.AutoCurve(
            curve = FanCurveDefaults.forProfile(profileId),
            bias = FanCurveBias(0),
        )
        PublicFanControlMode.MANUAL -> FanThermalStrategy.AutoCurve(
            curve = FanCurveDefaults.forProfile(profileId),
            bias = FanCurveBias(curveBias),
        )
    }
}

/**
 * One profile's durable (mode, curveBias) preference. Deliberately carries no session identity --
 * see [ActiveFanControlSelectionStore].
 */
internal data class FanControlProfilePreference(
    val mode: PublicFanControlMode,
    val curveBias: Int,
) {
    companion object {
        // Mode MANUAL is Mimir's actuator ownership, not evidence that the player customized the
        // curve. A profile never seen before therefore resumes the authored neutral curve.
        val DEFAULT = FanControlProfilePreference(PublicFanControlMode.AUTO, 0)
    }
}

/** Durable per-profileId preference persistence, injected into [ActiveFanControlSelectionStore]. */
internal interface FanControlPreferenceStore {
    fun load(): Map<String, FanControlProfilePreference>
    fun save(profileId: String, preference: FanControlProfilePreference)
}

/**
 * Process-memory-only backing. Used before the daemon's credential-protected SharedPreferences are
 * available (pre-unlock) and by tests; a fresh process therefore starts every profile at
 * [FanControlProfilePreference.DEFAULT] until [SharedPreferencesFanControlPreferenceStore] takes
 * over -- see `MimirTweakerService.ensureCredentialStores`.
 */
internal class InMemoryFanControlPreferenceStore : FanControlPreferenceStore {
    private val values = mutableMapOf<String, FanControlProfilePreference>()

    @Synchronized
    override fun load(): Map<String, FanControlProfilePreference> = values.toMap()

    @Synchronized
    override fun save(profileId: String, preference: FanControlProfilePreference) {
        values[profileId] = preference
    }
}

/**
 * Durable per-profile (mode, curveBias) preference backed by the daemon's own credential-protected
 * SharedPreferences (`ensureCredentialStores()` / `prefs()`). Keyed to
 * [TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS] -- the only profiles a Mimir-owned fan
 * session can ever apply to. A read failing [validFanControlSelection], naming an unknown
 * profileId, or missing either half of the pair is treated as absent -- falls back to
 * [FanControlProfilePreference.DEFAULT] on the next [ActiveFanControlSelectionStore.current] call
 * -- rather than thrown: a corrupted or hand-edited preference file must never crash the daemon or
 * inject an out-of-range bias.
 */
internal class SharedPreferencesFanControlPreferenceStore(
    private val preferences: android.content.SharedPreferences,
) : FanControlPreferenceStore {
    override fun load(): Map<String, FanControlProfilePreference> =
        TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS.mapNotNull { profileId ->
            val mode = preferences.getString(modeKey(profileId), null)
                ?.let { raw -> runCatching { PublicFanControlMode.valueOf(raw) }.getOrNull() }
                ?: return@mapNotNull null
            if (!preferences.contains(biasKey(profileId))) return@mapNotNull null
            val curveBias = preferences.getInt(biasKey(profileId), 0)
            if (!validFanControlSelection(mode, curveBias)) return@mapNotNull null
            profileId to FanControlProfilePreference(mode, curveBias)
        }.toMap()

    override fun save(profileId: String, preference: FanControlProfilePreference) {
        if (profileId !in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS) return
        if (!validFanControlSelection(preference.mode, preference.curveBias)) return
        preferences.edit()
            .putString(modeKey(profileId), preference.mode.name)
            .putInt(biasKey(profileId), preference.curveBias)
            .apply()
    }

    private fun modeKey(profileId: String) = "fan_control_mode_$profileId"
    private fun biasKey(profileId: String) = "fan_control_bias_$profileId"
}

/**
 * Per-profileId (mode, curveBias) preference, durable across profile switches and daemon restarts
 * Each profileId remembers its own setting independently; switching the active profile
 * restores that profile's own (mode, curveBias) rather than resetting to Auto.
 *
 * The RETURNED [ActiveFanControlSelection] always carries the CURRENT session's
 * generation/transactionId/requestId, stamped fresh from [ActiveSessionIdentity] on every call --
 * never a cached identity from a prior, possibly dead, session. Only the (mode, curveBias)
 * preference itself is durable and keyed by profileId; the identity half of the returned value is
 * never persisted, so a selection from a dead session can never be mistaken for a live one.
 */
internal class ActiveFanControlSelectionStore(
    private val preferenceStore: FanControlPreferenceStore = InMemoryFanControlPreferenceStore(),
) {
    @Synchronized
    fun current(active: ActiveSessionIdentity): ActiveFanControlSelection {
        val profileId = checkNotNull(active.appliedTargetContract).profileId
        return toSelection(active, profileId, preferenceFor(profileId))
    }

    /**
     * The durable (mode, curveBias) a session on [profileId] would resume -- what [current] reads
     * once the session exists. Re-validated here, defense in depth: whatever loaded a preference
     * must never be able to inject an out-of-range/unstepped bias into a live selection,
     * independent of which FanControlPreferenceStore implementation is wired in. Admission reads
     * it BEFORE the session exists (P3-7, fence review 2026-10-14) to judge the Manual curve with
     * the bias the fan loop will actually run.
     */
    @Synchronized
    fun preferenceFor(profileId: String): FanControlProfilePreference =
        preferenceStore.load()[profileId]
            ?.takeIf { validFanControlSelection(it.mode, it.curveBias) }
            ?: FanControlProfilePreference.DEFAULT

    @Synchronized
    fun update(
        active: ActiveSessionIdentity,
        mode: PublicFanControlMode,
        curveBias: Int,
    ): ActiveFanControlSelection? {
        if (!validFanControlSelection(mode, curveBias)) return null
        val profileId = active.appliedTargetContract?.profileId ?: return null
        val preference = FanControlProfilePreference(
            mode = mode,
            curveBias = if (mode == PublicFanControlMode.AUTO) 0 else curveBias,
        )
        preferenceStore.save(profileId, preference)
        return toSelection(active, profileId, preference)
    }

    /**
     * Retained for existing fan-control-loop reset call sites (`fanControlDecision`'s Smart
     * branch, `stopFanControl`). The durable per-profile preference map intentionally does NOT
     * reset here: losing live duty ownership for a tick (a Smart-transitional moment, a session
     * teardown between launches) is not the player asking to forget a profile's Manual setting --
     * see the class kdoc. There is deliberately nothing session-scoped left in this store to
     * clear; only an explicit [update] (or an invalid/corrupt persisted read) ever changes a
     * profile's stored preference.
     */
    @Synchronized
    fun clear() {}

    private fun toSelection(
        active: ActiveSessionIdentity,
        profileId: String,
        preference: FanControlProfilePreference,
    ) = ActiveFanControlSelection(
        generation = active.generation,
        transactionId = active.transactionId,
        requestId = active.requestId,
        profileId = profileId,
        mode = preference.mode,
        curveBias = preference.curveBias,
    )
}

internal val FAN_CURVE_BIAS_RANGE = -100..100
internal const val FAN_CURVE_BIAS_STEP = 10
internal const val FAN_CONTROL_CONTRACT_VERSION = 2

internal fun validFanControlSelection(
    @Suppress("UNUSED_PARAMETER") mode: PublicFanControlMode,
    curveBias: Int,
): Boolean = curveBias in FAN_CURVE_BIAS_RANGE && curveBias % FAN_CURVE_BIAS_STEP == 0

/**
 * Whether `advancedCapabilitiesV6` should offer the Fan control section (Auto plus the curve
 * bias) at all. This is deliberately broader than [manualEntryAvailable] / raw PWM duty entry
 * (`TuningPlanner.FAN_MANUAL`, mode 6): the curve bias is an `AutoCurve` preset that needs no
 * signed manual plan, only that Mimir already directly owns fan duty for the active contract --
 * [TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES] (SPORT or MANUAL). `manualEntryAvailable` alone
 * still gates whether raw PWM duty entry (the MANUAL wire mode) is *also* offered -- see
 * `fanControlModes` at the call site, which is untouched by this function.
 */
internal fun fanControlSectionAvailable(
    manualEntryAvailable: Boolean,
    autoExitAvailable: Boolean,
    contractFanMode: Int?,
): Boolean = manualEntryAvailable ||
    autoExitAvailable ||
    contractFanMode in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES

/**
 * Whether `advancedCapabilitiesV6` should include `"MANUAL"` in the advertised `fanControlModes`
 * list (as opposed to offering only `["AUTO"]`). [PublicFanControlMode.MANUAL] here is the
 * curve-bias preset (see [ActiveFanControlSelection.strategy] -- both AUTO and MANUAL resolve to
 * [FanThermalStrategy.AutoCurve], only the bias differs), never [TuningPlanner.FAN_MANUAL] (raw
 * vendor PWM passthrough, mode 6).
 *
 * **CORRECTED 2026-08-25.** This paragraph used to continue *"Selecting it never by itself requests
 * a vendor `fan_mode` transition -- see `setFanControlV6`'s `requiresTransition = hardwareMode ==
 * TuningPlanner.FAN_SMART` gate"*, and BOTH halves of that are now false: release spec S1.2 R1 was
 * overturned 2026-08-23 (selecting Manual against a vendor-SMART contract DOES transition the
 * device to mode 6), and the one-line gate it cites no longer exists -- the live form is the
 * two-disjunct `entersDutyOwnership || exitsToVendorAuto`. It mattered because this KDoc is the
 * stated justification for advertising MANUAL at all. The justification still holds, for the reason
 * below rather than the retired one: MANUAL is safe to advertise whenever
 * EITHER [manualEntryAvailable] is true (the SMART-staged transition path is itself qualified, so
 * `setFanControlV6` will drive the transition) OR the active contract already directly owns duty
 * ([TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES] -- SPORT or MANUAL), in which case
 * `requiresTransition` is false and `setFanControlV6` falls straight through to
 * `fanControlSelections.update(...)` with no transition or extra qualification needed at all.
 *
 * Before this, `advancedCapabilitiesV6` advertised MANUAL only when [manualEntryAvailable] --
 * hiding a selection `setFanControlV6` would have happily accepted for any SPORT-owned session
 * (the Odin's automatic-entry default at the time, retired by TR-08), the same gap
 * [fanControlSectionAvailable] above was extended to close for the section itself.
 */
internal fun manualFanControlModeAdvertised(
    manualEntryAvailable: Boolean,
    contractFanMode: Int?,
): Boolean = manualEntryAvailable || contractFanMode in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES

/**
 * `PublicFanControlMode.MANUAL` here is a CURVE-BIAS preset (see [ActiveFanControlSelection
 * .strategy] -- both AUTO and MANUAL resolve to the same [FanThermalStrategy.AutoCurve], only the
 * bias differs), never [TuningPlanner.FAN_MANUAL] (raw vendor PWM passthrough, mode 6).
 *
 * **CORRECTED 2026-08-25**, same retired claim as [manualFanControlModeAdvertised] above: this said
 * *"Selecting it never requests a vendor `fan_mode` transition"* and cited a one-line
 * `requiresTransition` form that no longer exists. A MANUAL selection against a SMART contract does
 * transition (spec S1.2 R1, overturned 2026-08-23); it is `requiresTransition=false` only for an
 * already-duty-owned contract, which is the case this paragraph actually needed. So the eligibility
 * gate below must recognise EVERY contract fanMode that can legitimately reach this call: the
 * pre-handoff Smart baseline (a session captured/staged before this lane, or on an older build,
 * or a non-Odin profile shape whose baseline is Smart) plus every mode Mimir already directly
 * owns ([TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES] -- SPORT and MANUAL alike). Before this was
 * extended to SPORT, `advancedCapabilitiesV6` reported `manualFanControlAvailable=false` and an
 * empty `fanControlModes` for every SPORT-owned session -- the Odin's automatic-entry default at
 * the time, retired by TR-08 -- hiding the curve-bias UI for a purely additive, hardware-mode-safe
 * selection that `setFanControlV6` itself would have happily accepted.
 */
internal fun manualFanControlAdmitted(
    active: ActiveSessionIdentity?,
    capabilities: HardwareCapabilities,
    hardwareWritesCompiled: Boolean,
    manualPlanMatchesContract: Boolean,
    cpuSafetySensorAvailable: Boolean,
    gpuSensorAvailable: Boolean,
    mutationPortAvailable: Boolean,
    guardianJournalBound: Boolean = false,
): Boolean {
    val exact = active ?: return false
    val contract = exact.appliedTargetContract ?: return false
    return hardwareWritesCompiled &&
        exact.scope == ActiveSessionScope.SYSTEM &&
        // 2026-08-23: the `contract.deviceProfile == ODIN_3_PROFILE` conjunct is retired (family not device). The identity conjunct below is KEPT and is a
        // different question -- it asserts the live hardware is still the one the contract was
        // applied against, which must hold on every device, not only the Odin.
        capabilities.deviceProfileId == contract.deviceProfile &&
        capabilities.pServerAvailable &&
        capabilities.fanNodeAvailable &&
        TuningAxis.FAN in capabilities.supportedAxes &&
        (
            contract.fanMode == TuningPlanner.FAN_SMART ||
                contract.fanMode in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES
            ) &&
        contract.profileId in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS &&
        manualPlanMatchesContract &&
        cpuSafetySensorAvailable &&
        gpuSensorAvailable &&
        mutationPortAvailable &&
        guardianJournalBound
}

/**
 * RETIRED: ODIN 3 VENDOR FAN CONTENTION HOLDOFF (formerly `ODIN_3_VENDOR_FAN_CONTENTION_HOLDOFF` /
 * `odin3FanContentionHoldoffActive`, grep those names in history for the removed code).
 *
 * The holdoff shipped on a misdiagnosis: it read a charger-plug-triggered `fan_mode` rewrite by
 * `com.odin.settings`' `FanTrigger` (a `BATTERY_CHANGED` receiver, on by default) as the vendor
 * reacting to Mimir's own `fan_mode=6` write. Device evidence refuted that -- mode 6 held 50/50
 * samples over 20 s on battery with zero vendor reassertion; the device was plugged in at the
 * moment the original trace's reassert fired.
 *
 * The current implementation supersedes the holdoff on both of its own premises: [TuningPlanner.selectedFanMode]
 * now gives Odin 3 direct mode-5 (SPORT) ownership at entry -- no signed vendor-Smart staging
 * plan, no second mode-6 transition, so there is no handoff left to hold off -- and fan-only
 * drift no longer discards the CPU+GPU vector at all (`HardwareSessionSupervisor`'s
 * `soleRepairableFanDrift`/bounded re-assert), so `TARGET_CONTRACT_DRIFT_FAN` is no longer a
 * live Restore reason for it to guard against either.
 */

/**
 * May the staged Manual handoff proceed for this contract, on this hardware?
 *
 * **2026-08-23, and this is the ninth manual-fan blocker — the one the first pass missed.**
 * `MimirTweakerService.transitionFanControlModeOnWorker` carried its OWN copy of the signed-manifest
 * dependency (`productQualificationResolver.qualifiedManualFanPlan(...) ?: return publicFailure(
 * "FAN_CONTROL_UNAVAILABLE", "Manual fan control is not signed for this profile.")`), separate from
 * the one [manualFanControlAdmitted]'s caller used. Retiring only the availability predicate made
 * the row OFFER Manual and the actuation still refuse it — which matches the reported device failure: switching from Auto to Manual
 * applied no fan response in both Balanced and Efficient profiles. The row was live, the switch was silently declined, and because this path has never had
 * a single `Log` call in PRODUCTION there was nothing in logcat to read.
 *
 * Two lessons are worth keeping attached to this function rather than a commit message.
 * [[instrument-the-whole-chain-not-the-leaf]]: the availability predicate and the actuation each
 * asked the manifest independently, so fixing the one that renders the UI produced a build that
 * looked fixed and was not. And gates in series defeat partial fixes silently — the reason the
 * original ticket insisted all of them move in one landing.
 *
 * Same property the signed comparison enforced, asked of code this tree owns: **the manual plan is
 * the same CPU/GPU vector as the contract already applied, differing only in the fan axis.** Manual
 * fan changes the fan and nothing else. The duty floor is not checked here because it is not
 * enforceable here — `PServerFanDutyMutationPort.write` refuses any
 * `minimumDutyPercent != FanPwmBounds.MIN_SPIN_PERCENT` on every single duty write, which is where
 * it can actually be observed against hardware.
 */
internal fun manualFanHandoffPlanAdmitted(
    derivedManualPlan: TuningPlan?,
    contract: AppliedTargetContract,
    capabilities: HardwareCapabilities,
): Boolean = derivedManualPlan != null &&
    derivedManualPlan.fanMode == TuningPlanner.FAN_MANUAL &&
    manualFanPlanCpuMatchesContract(derivedManualPlan, contract, capabilities) &&
    manualFanPlanGpuMatchesContract(derivedManualPlan, contract, capabilities)

/**
 * Does the derived manual plan ask for the SAME CPU envelope the contract already applied?
 *
 * The CPU-envelope comparison must account for admission clamping. This was `derivedManualPlan.cpuMaxKhz == contract.cpuQosCeilingKhz`,
 * which asks a question the pipeline cannot answer with `==`, because the plan that gets JOURNALLED
 * is not the plan the planner derives. `TransactionCoordinator` rewrites it once more before
 * anything is written:
 *
 * ```
 * if (capture.admittedCpuMaxKhz.isNotEmpty()) plan = plan.copy(cpuMaxKhz = capture.admittedCpuMaxKhz)
 * ```
 *
 * Admission clamps any target below its policy's
 * live floor UP to that floor rather than refusing the apply. So the contract is post-clamp and a
 * re-derivation is pre-clamp, and on any chassis whose live floor sits above a derived ceiling the
 * two are never equal. Device-measured on the Nova, one process, one boot, quiet:
 *
 * ```
 * cpuPlan    ={0=1017600, 3=1286400, 7=1478400}   <- TuningPlanner, raw
 * cpuContract={0=1017600, 3=1651200, 7=1843200}   <- journalled, floor-clamped
 * ```
 *
 * `CpuWindowAdmissionPolicy.targetAdmission`'s own comment names those two floors as the Nova's,
 * measured on the device. Odin does not hit this mismatch because its literal 8-Elite table
 * sits above its live floors.
 *
 * **Why a relation and not a re-clamp.** Reproducing the clamp here is not merely expensive (it
 * would copy the admission dwell's cost into a call the frontend makes every 60 s), it is ill-defined: the clamp input is the live floor AT
 * CAPTURE TIME, and that floor moves with the charger ({0=902400, 3=1651200, 7=1843200} on battery
 * against {0=1459200, 3=1920000, 7=1843200} on USB, same device, same day). A check-time floor read
 * is a different number than the one that produced the contract, so "was this a floor clamp?" is
 * unanswerable after the fact and any gate built on it would flap with the cable.
 *
 * Agreement between derivation and journal was ESTABLISHED AT APPLY, by the admission policy that
 * owns the transformation — the same argument [manualFanPlanGpuMatchesContract] makes for the
 * devfreq translation one function down. What is left to check here is coherence, and the relation
 * below still has teeth: the clamp only ever raises, and never above the policy's own stock
 * maximum, so a contract BELOW the current derivation (stale, or another profile's) refuses, one
 * ABOVE stock refuses, and a topology that no longer has the same policies refuses on the key set.
 */
internal fun manualFanPlanCpuMatchesContract(
    derivedManualPlan: TuningPlan,
    contract: AppliedTargetContract,
    capabilities: HardwareCapabilities,
): Boolean {
    val derived = derivedManualPlan.cpuMaxKhz
    val applied = contract.cpuQosCeilingKhz
    if (derived.isEmpty() || derived.keys != applied.keys) return false
    val stockMaxKhz = capabilities.cpuPolicies.associate { it.id to it.maxKhz }
    return derived.all { (policyId, derivedKhz) ->
        val appliedKhz = applied[policyId] ?: return@all false
        val stockMax = stockMaxKhz[policyId] ?: return@all false
        derivedKhz <= appliedKhz && appliedKhz <= stockMax
    }
}

/**
 * Does the derived manual plan ask for the SAME GPU ceiling the contract already applied?
 *
 * **Manual-fan regression, 2026-08-25.** The GPU
 * ceiling grew a second transport (`GpuCeilingTransport` -- a devfreq POLICY in Hz, written instead
 * of `max_pwrlevel` on any chassis exposing `devfreq/max_freq`, which is how the Nova's GPU drift
 * storm was closed). `AppliedTargetContract.fromAuthenticatedAppliedRecord` populates
 * [AppliedTargetContract.gpuMaxPowerLevel] ONLY from a `gpu:max_pwrlevel` projection, so a
 * devfreq-applied contract carries `gpuMaxPowerLevel = null` by construction. Both manual-fan
 * gates then compared a live power-level INDEX against that null, which is never equal -- so the
 * fan section reported `manualFanControlAvailable=false` and the row rendered "UNAVAILABLE" on
 * every devfreq chassis, after the GPU transport change.
 *
 * [[narrowing-by-name-deletes-the-second-duty]] in reverse: the projection was widened for the GPU
 * axis and the fan axis read it as a disappearance.
 *
 * The comparison is made transport-aware rather than the contract made to carry both: populating
 * `gpuMaxPowerLevel` on a devfreq contract would re-arm `HardwareSessionSupervisor`'s drift
 * comparator against `max_pwrlevel`, which the vendor stack reasserts to 0 on this hardware -- the
 * exact restore storm the devfreq transport was introduced to end.
 *
 * Equality on the devfreq side is exact, not approximate: apply and this check run the same
 * [GpuCeilingTransport.targetHzFor] translation over the same live frequency table, so a matching
 * plan produces the identical Hz the journal recorded.
 */
internal fun manualFanPlanGpuMatchesContract(
    derivedManualPlan: TuningPlan,
    contract: AppliedTargetContract,
    capabilities: HardwareCapabilities,
): Boolean {
    val devfreqHz = contract.gpuDevfreqMaxHz ?: return derivedManualPlan.gpuMaxPowerLevel ==
        contract.gpuMaxPowerLevel
    // A devfreq contract and a plan with no GPU target are not the same vector, and must not be
    // read as one by a null-vs-null accident one field over.
    val level = derivedManualPlan.gpuMaxPowerLevel ?: return false
    val plannedHz = GpuCeilingTransport.targetHzFor(capabilities.gpuFrequenciesKhz, level)
        ?: return false
    return plannedHz == devfreqHz.toLong()
}

/**
 * D2 : plan-only eligibility for
 * entering Mimir MANUAL directly from Stock on the select path
 * ([MimirTweakerService.selectSystemProfileOnWorker]'s `applyBody`), BEFORE any contract exists to
 * compare against -- the ordinary select applies MANUAL in the SAME generation as the profile
 * apply itself, so there is no already-applied SMART contract yet the way
 * [manualFanControlAudit]'s post-apply question has one.
 *
 * The four conjuncts are exactly [manualFanControlAudit]'s NON-contract ones: [profileId] in
 * [TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS], [capabilities.fanNodeAvailable],
 * [TuningAxis.FAN] in [capabilities.supportedAxes], and a bare [TuningPlanner] deriving a manual
 * plan for this exact profile/capabilities pair (same call shape [manualFanControlAudit] uses --
 * a bare `TuningPlanner()` deliberately, for the same recursion reason its own comment documents:
 * its `calibrationLookup` defaults to `{ _, _ -> false }`, so re-deriving here cannot recurse back
 * into `planAdmittedForApply`). [manualFanHandoffPlanAdmitted]'s own `derivedManualPlan.fanMode ==
 * TuningPlanner.FAN_MANUAL` check is mirrored here rather than a bare non-null check, so a plan
 * that derives (e.g. `fanNodeAvailable=false` with the axis still present, which
 * [TuningPlanner.selectedFanMode] answers with the untouched vendor mode, not [TuningPlanner
 * .FAN_MANUAL]) is not mistaken for an eligible one.
 *
 * Deliberately reads no contract, no [ActiveSessionIdentity], and no journal -- the select path
 * runs this BEFORE any of those exist for this apply. Left independent of [manualFanControlAudit]
 * / [manualFanControlAdmitted] rather than sharing code with them: those already have their own
 * exhaustively-pinned contract-relative behaviour, and threading a not-yet-applied plan-only
 * question through a function whose every other caller has a contract in hand is a bigger risk
 * than the small duplication of re-listing four conjuncts here.
 */
internal fun manualFanPlanEligible(profileId: String, capabilities: HardwareCapabilities): Boolean {
    if (profileId !in TweakerPostureTruth.PRODUCT_QUALIFIABLE_PROFILE_IDS) return false
    if (!capabilities.fanNodeAvailable) return false
    if (TuningAxis.FAN !in capabilities.supportedAxes) return false
    val manualPlan = TuningPlanner().plan(
        profileId = profileId,
        packageName = SystemWideProfileTransition.TARGET_PACKAGE_ID,
        capabilities = capabilities,
        fanIntent = TuningPlanner.FanIntent.MANUAL_OVERRIDE,
    )
    return manualPlan != null && manualPlan.fanMode == TuningPlanner.FAN_MANUAL
}

/**
 * D2's own outer decision, once a MANUAL_OVERRIDE [TransactionResult] from the select path's
 * `applyBody` has already returned `applied = false`: does the SAME applyBody retry with
 * `PROFILE_AUTO` (in the same registration/`onAppliedAdopt`/`isCancelled`), or does it return the
 * MANUAL failure unchanged and let [SystemWideProfileTransition.execute]'s own `verifiedStock()` /
 * `restore()` handle a non-Empty journal?
 *
 * Extracted as a pure decision (rather than pinned by driving
 * [MimirTweakerService.selectSystemProfileOnWorker] itself) because that method has no host-test
 * seam for `transaction`/`journal` today -- see [FanManualReentryTest]/[FanOwnershipRenewalTest]
 * for the seams that DO exist (both pure-function level, never a live `MimirTweakerService`
 * instance). [manualApplied] is carried explicitly, not assumed false, so the function states its
 * own contract in full: an applied MANUAL result is never retried, matching D2's own "if applied,
 * return it" clause.
 */
internal enum class SelectPathFanEntryFallback {
    /** Return the MANUAL [TransactionResult] exactly as received -- no second apply. */
    RETURN_MANUAL_RESULT,

    /** Retry with `PROFILE_AUTO` in the same applyBody/registration. */
    FALLBACK_TO_AUTO,
}

internal fun selectPathFanEntryFallback(
    manualApplied: Boolean,
    journalEmptyAfterManualFailure: Boolean,
): SelectPathFanEntryFallback = if (!manualApplied && journalEmptyAfterManualFailure) {
    SelectPathFanEntryFallback.FALLBACK_TO_AUTO
} else {
    SelectPathFanEntryFallback.RETURN_MANUAL_RESULT
}

/**
 * D3's renewal-begin code when the select path degrades to `PROFILE_AUTO` and the settle worker
 * schedules a renewal ([FanOwnershipSettleRoute.SCHEDULE_RENEWAL]): the D2 fallback code recorded
 * for this request, or [SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE] when D2 never attempted MANUAL
 * at all (the profile/hardware were ineligible before any hardware read).
 */
internal const val SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE = "MANUAL_NOT_ATTEMPTED"

internal fun selectPathFanEntryRenewalCode(recordedCode: String?): String =
    recordedCode ?: SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE

/** Routing outcome for the staged fan-ownership settle worker. */
internal enum class FanOwnershipSettleRoute {
    /**
     * The staged contract already directly owns duty (SPORT or MANUAL --
     * [TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES]): adopt Auto locally, no transition needed.
     *
     * TR-08 retired the Odin 3's automatic-entry SPORT override -- [TuningPlanner.selectedFanMode]
     * no longer derives SPORT for any device, so this route is no longer the Odin's ordinary case.
     * It remains reachable for a genuinely duty-owned contract:
     * [TuningPlanner.FanIntent.MANUAL_OVERRIDE]'s direct `FAN_MANUAL` entry (still duty-owned,
     * unaffected by this ticket), and a contract that captured SPORT before this ticket's build and
     * has not yet been re-applied.
     */
    ADOPT_ALREADY_OWNED,

    /**
     * The device exposes **no fan axis at all**, and the staged contract correctly carries no fan
     * mode. Nothing to own, nothing to hand off: the profile is complete as applied.
     *
     * Added 2026-08-22 for the Nova, which reported
     * `Rejected(code=FAN_PROFILE_STAGING_INVALID)` on every profile it advertised
     * `[V] device logcat 08-22 03:37:28.771, Nova test unit`. Its identity line reads
     * `fan=false ... axes=[CPU, GPU]`, and `TuningPlanner.selectedFanMode` therefore returns null
     * by design (`vendorMode == null || TuningAxis.FAN !in capabilities.supportedAxes -> null`).
     * So the *plan* was right and this router was wrong: its vocabulary had no case for a
     * fan-less chassis, and a null fan mode fell through `contractFanMode != FAN_SMART` into
     * [REJECT_STAGING_INVALID] — advertise, apply, then roll the apply back.
     *
     * **This is not a relaxation of the fail-toward-cooling guard.** The branch below requires a
     * null contract mode *and* an absent fan axis. A device with a live fan whose contract lost
     * its fan mode — a genuine anomaly — still reaches [REJECT_STAGING_INVALID] exactly as
     * before, and a fan-less device that somehow presents a fan-bearing contract does too.
     */
    ADOPT_NO_FAN_AXIS,

    /**
     * **Policy 2026-09-07 supersedes this route's original R2/R3 default.** During an active
     * session Mimir renews control after vendor fan changes. Previously, a staged SMART contract
     * was adopted unconditionally because the vendor regulation loop was left running. The 08-21 finding (vendor
     * loop at 65% duty with the GPU at 88C) and the
     * 2026-09-07 Odin rearm window (contract fanMode=4, hardware `fan_mode=6`, duty pinned at
     * 25000 through a 28C climb and again at 30C) are why leaving that as the
     * permanent, ordinary case for an active session is retired.
     *
     * **This route means manual fan control is genuinely UNAVAILABLE for this device/session
     * state, not merely that an entry attempt was refused.** [fanOwnershipSettleRoute]'s SMART leg
     * reaches here only when [manualFanControlAvailable] returns false -- see [SCHEDULE_RENEWAL]
     * for the sibling case that claims an available device. Unavailability here is
     * SESSION-STABLE: [manualFanControlAdmitted]'s own conjuncts (hardware identity, capability,
     * sensor, mutation-port, signed-plan-match) do not change between now and the next tick just
     * because a tick elapses, so retrying buys nothing. [MimirTweakerService
     * .settleMimirFanOwnershipAfterProfileApplyOnWorker] therefore returns `stagedResult`
     * UNMODIFIED and begins NO renewal on this route -- adopt Auto locally (same "no second
     * transaction" disposition as [ADOPT_NO_FAN_AXIS]) and stop.
     *
     * **CORRECTED (second adversarial review, spec §5 finding 1): this KDoc used to say the
     * settle worker "also begins a generation-keyed renewal" here, immediately followed by a
     * paragraph saying D3 "supersedes ... this route's own renewal begun here wiring above" --
     * two claims about the SAME route that contradicted each other, left in place after D3 rather
     * than resolved. There never was a renewal begun by D3's own commit; the paragraph above
     * states what actually ships.** Renewal ([FanOwnershipRenewalPolicy]) exists for a REFUSED
     * manual entry a later tick may retry ([SCHEDULE_RENEWAL] -- D2's own MANUAL attempt on this
     * apply failed, but nothing here says it will fail again), never for an UNAVAILABLE one a
     * retry cannot change.
     *
     * The 2026-09-07 update moved manual entry off the settle worker entirely: the ordinary select applies
     * MANUAL directly from Stock, in the SAME generation as the profile apply, before this route
     * can even be reached. A SMART contract reaching [fanOwnershipSettleRoute]'s available leg
     * therefore means D2's own MANUAL attempt on THIS apply either failed (refused --
     * [SCHEDULE_RENEWAL]) or was never eligible to attempt at all (unavailable -- this route) --
     * see [SCHEDULE_RENEWAL] for what the settle worker does with the former.
     *
     * The pre-2026-08-22 history this route's own comment used to cite (falling through to
     * [REJECT_STAGING_INVALID] or [REJECT_OWNERSHIP_UNAVAILABLE] and rolling every SMART apply
     * back to Stock) is unchanged by this update -- this route still closes that rollback for the
     * genuinely-unavailable case, it simply no longer also claims the available one.
     */
    ADOPT_VENDOR_SMART,

    /** The staged contract never reached the qualified Smart handoff. */
    REJECT_STAGING_INVALID,

    /** Manual fan control is not qualified/available for this device/session state. */
    REJECT_OWNERSHIP_UNAVAILABLE,

    /**
     * A SMART-staged contract for which manual fan control IS available -- schedule a renewal
     * for a later tick, rather than transitioning to Mimir MANUAL inline from the settle worker.
     *
     * **D3
     * replaces this route's own former `TRANSITION_TO_MANUAL`,** which ran the signed mode-6
     * transition inline from this settle worker -- three Guardian transactions per apply
     * (restore-to-Stock, then the profile re-apply, then the mode-6 transition), with a
     * post-restore refusal landing the profile at Stock (F1). D2
     * closes that: the ordinary select applies MANUAL directly from
     * Stock, in the SAME generation as the profile apply, via
     * [MimirTweakerService.selectSystemProfileOnWorker]'s own `applyBody` -- see
     * [manualFanPlanEligible] for the plan-only eligibility gate D2 checks before that MANUAL
     * apply, and [selectPathFanEntryFallback] for what happens when it fails.
     *
     * A SMART contract reaching [fanOwnershipSettleRoute]'s available leg therefore means D2's own
     * MANUAL attempt on THIS apply already failed or was ineligible before this route is ever
     * reached -- there is nothing left to transition here. Reaching here just means: adopt Auto
     * locally (same disposition as [ADOPT_VENDOR_SMART]/[ADOPT_NO_FAN_AXIS] -- no second
     * transaction, no rollback), and begin a generation-keyed renewal
     * (`fanOwnershipRenewal = FanOwnershipRenewalPolicy.begin(...)`, carrying D2's own recorded
     * fallback code via [selectPathFanEntryRenewalCode] -- see [SELECT_PATH_FAN_ENTRY_NOT_ATTEMPTED_CODE]
     * for what is recorded when D2 never attempted MANUAL at all) for a later tick to retry
     * entry via [MimirTweakerService.renewMimirFanOwnershipOnWorker] (P3). Logged via
     * `FanOwnershipObservability.onSettleDeferred`, same as [ADOPT_VENDOR_SMART]'s own degrade.
     */
    SCHEDULE_RENEWAL,
}

/**
 * Pure routing decision for
 * `MimirTweakerService.settleMimirFanOwnershipAfterProfileApplyOnWorker`. Factored out so it is
 * host-testable without a live Service/Binder/Guardian session. Mirrors that function's branch
 * order exactly -- if the checks there are ever reordered, update this function to match; they
 * must never diverge.
 *
 * [manualFanControlAvailable] is evaluated lazily (only in the branch that actually needs it) to
 * preserve the original short-circuit: it probes live telemetry and must not run for a contract
 * that is already owned or invalid.
 *
 * @param fanAxisAvailable `TuningAxis.FAN in capabilities.supportedAxes` for the live hardware —
 * deliberately the **same expression** `TuningPlanner.selectedFanMode` consults when it decides
 * whether to put a fan mode on the plan in the first place. Reading a different fan predicate here
 * (`fanNodeAvailable`, `HandheldDeviceProfiles.supportsFanMode`, the vendor mode reading) would
 * reintroduce, one field over, exactly the producer/consumer divergence that produced
 * [FanOwnershipSettleRoute.ADOPT_NO_FAN_AXIS]'s incident: the planner's rule and the settler's
 * rule must be one truth. Non-fatal by construction — see that route's kdoc for why passing it
 * cannot widen the guard for any fan-bearing device.
 */
internal fun fanOwnershipSettleRoute(
    contractFanMode: Int?,
    fanAxisAvailable: Boolean,
    manualFanControlAvailable: () -> Boolean,
): FanOwnershipSettleRoute = when {
    contractFanMode in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES ->
        FanOwnershipSettleRoute.ADOPT_ALREADY_OWNED
    contractFanMode == null && !fanAxisAvailable -> FanOwnershipSettleRoute.ADOPT_NO_FAN_AXIS
    // During an active Mimir session, vendor fan changes trigger renewal of Mimir control.
    // This supersedes the
    // 2026-08-22 R2/R3 default immediately below (a vendor-SMART contract owned nothing to hand
    // off, so Mimir never took duty from SMART) for the duration of an active Mimir session: the
    // 08-21 finding (vendor loop at 65% duty with the GPU at 88C) and the 2026-09-07 Odin rearm window (contract fanMode=4,
    // hardware fan_mode=6, duty pinned at 25000 through a 28C climb and again at 30C) are why a SMART contract must
    // not be left as the ordinary, permanent case while a session is active. So this leg now
    // probes manual availability itself. The 2026-09-07 update changes what
    // "available" now means here: D2 already tried to enter MANUAL directly from Stock on THIS
    // apply, before this settle worker ever runs, so a SMART contract reaching this leg means
    // that attempt already failed or was ineligible -- there is no second inline transition left
    // to run. This leg now SCHEDULES A RENEWAL for a later tick when manual is available, and
    // adopts vendor SMART only as the DEGRADED case (manual unavailable) with renewal left
    // pending either way. See ADOPT_VENDOR_SMART's and SCHEDULE_RENEWAL's own KDocs for the full
    // account.
    contractFanMode == TuningPlanner.FAN_SMART ->
        if (manualFanControlAvailable()) FanOwnershipSettleRoute.SCHEDULE_RENEWAL
        else FanOwnershipSettleRoute.ADOPT_VENDOR_SMART
    contractFanMode != TuningPlanner.FAN_SMART -> FanOwnershipSettleRoute.REJECT_STAGING_INVALID
    !manualFanControlAvailable() -> FanOwnershipSettleRoute.REJECT_OWNERSHIP_UNAVAILABLE
    else -> FanOwnershipSettleRoute.SCHEDULE_RENEWAL
}

/**
 * Mode-6 transition outcomes: the three ways a
 * mode-6 transition attempt (once via [MimirTweakerService.transitionFanControlModeOnWorker]) can
 * leave things.
 *
 * **This enum's only current caller is the tick-driven renewal path.** Until D3, both
 * [MimirTweakerService.settleMimirFanOwnershipAfterProfileApplyOnWorker]'s (then-)
 * `TRANSITION_TO_MANUAL` route and [MimirTweakerService.renewMimirFanOwnershipOnWorker]'s tick
 * renewal classified their transition attempt through this same enum. D2 moved manual entry off
 * the settle worker entirely (the ordinary select applies MANUAL directly from Stock instead), so
 * [MimirTweakerService.renewMimirFanOwnershipOnWorker] (P3) is this enum's only caller now -- the
 * settle worker's [FanOwnershipSettleRoute.SCHEDULE_RENEWAL] route never runs a transition at all,
 * it only records a pending renewal for that tick worker to attempt.
 *
 * 0b203f3e's settle worker treated every non-success identically -- `return stagedResult`
 * unmodified, degraded to vendor SMART, renewal pending. That is honest ONLY while the STAGED
 * generation this apply just committed is still the one running: the transition's `proceed` runs
 * `transaction.restoreBound(...)` then `supervisor.clearSession()` BEFORE it re-applies, so a
 * failure after that point (the re-apply itself never lands, or lands as a different generation)
 * leaves the hardware at Stock with no active session at all -- reporting the staged apply as
 * applied would misreport a profile that is not running. [SESSION_LOST] is exactly that gap.
 */
internal enum class FanOwnershipSettleOutcome {
    /** The transition landed: the settled contract is genuinely [TuningPlanner.FAN_MANUAL]. */
    SETTLED_MANUAL,

    /**
     * The transition did not land, but the STAGED generation this apply committed is still the
     * one running (`settledGeneration == stagedGeneration`) -- the pre-restore rejection case
     * (P1's admission, or a hand-to-4/handoff/owner decline that returns before `proceed` ever
     * runs `restoreBound`/`clearSession`). I4: never lose the profile over the fan.
     */
    DEGRADED_VENDOR_SMART,

    /**
     * The transition failed AFTER `restoreBound(...)` + `clearSession()` ran: the running
     * generation is no longer the staged one (or there is none at all). The hardware is at Stock
     * and `supervisor.activeSession()` does not carry the staged apply -- reporting it as applied
     * would be dishonest. The caller must report the pre-0b203f3e honest failure, not degrade.
     */
    SESSION_LOST,
}

/**
 * Pure decision for a mode-6 transition attempt's outcome -- since D3, only
 * [MimirTweakerService.renewMimirFanOwnershipOnWorker]'s (P3) tick renewal (see
 * [FanOwnershipSettleOutcome]'s own KDoc for the settle worker's former use of this, retired by
 * D2 moving manual entry onto the select path). See [FanOwnershipSettleOutcome] for what each
 * case means and why.
 *
 * **E2  widens this from a
 * generation-only comparison to a scope+profile-aware one.** F2 (same spec's own adversarial
 * review): `PostTransactionDisposition.RetainRecovery` re-adopts the SAME generation with
 * `scope = RECOVERY`, `appliedTargetContract = null` -- the old generation-only check misread
 * that as [DEGRADED_VENDOR_SMART] and kept retrying a recovery session forever. [settledScope]
 * must now be [ActiveSessionScope.SYSTEM] for either a settle or a degrade; anything else
 * (RECOVERY included) is [FanOwnershipSettleOutcome.SESSION_LOST]. E1's own A' rollback
 * re-apply is the other half of why generation equality can no longer gate the degrade case: a
 * MANUAL entry that fails and rolls forward into a PROFILE_AUTO re-apply lands under a NEW
 * generation (a fresh Guardian transaction), not the staged one, so [settledGeneration] and
 * [stagedGeneration] legitimately differ on E1's own degrade path. [settledProfileId] ==
 * [stagedProfileId] is what actually proves this is still the SAME apply's session, not a
 * different one that merely happens to share a scope and fan mode -- generation can no longer do
 * that job once E1 makes it float. [settledGeneration]/[stagedGeneration] are kept on the
 * signature for call-site parity with the spec and because the caller still needs them (to
 * re-key a renewal state onto the settled identity -- see
 * [rekeyRenewalToSettledGeneration]); this function's own branching no longer reads them.
 *
 * @param transitionSucceeded `response.getBoolean("success")` from
 * [MimirTweakerService.transitionFanControlModeOnWorker].
 * @param settledFanMode `supervisor.activeSession()?.appliedTargetContract?.fanMode`, read AFTER
 * the transition call.
 * @param settledScope `supervisor.activeSession()?.scope`, read AFTER the transition call --
 * null when the transition left no active session at all.
 * @param settledProfileId `supervisor.activeSession()?.appliedTargetContract?.profileId`, read
 * AFTER the transition call.
 * @param stagedProfileId the profile the settle worker staged BEFORE calling the transition
 * (`staged.appliedTargetContract?.profileId`, the same identity passed as `active = staged` to
 * the transition).
 * @param settledGeneration `supervisor.activeSession()?.generation`, read AFTER the transition
 * call -- null when the transition left no active session at all. Unused by this function's own
 * decision since E2; retained for the caller (see above).
 * @param stagedGeneration the generation the settle worker staged BEFORE calling the transition
 * (`staged.generation`, the same identity passed as `active = staged` to the transition). Unused
 * by this function's own decision since E2; retained for the caller (see above).
 */
internal fun fanOwnershipSettleOutcome(
    transitionSucceeded: Boolean,
    settledFanMode: Int?,
    settledScope: ActiveSessionScope?,
    settledProfileId: String?,
    stagedProfileId: String?,
    @Suppress("UNUSED_PARAMETER") settledGeneration: Long?,
    @Suppress("UNUSED_PARAMETER") stagedGeneration: Long,
): FanOwnershipSettleOutcome = when {
    transitionSucceeded &&
        settledFanMode == TuningPlanner.FAN_MANUAL &&
        settledScope == ActiveSessionScope.SYSTEM &&
        settledProfileId == stagedProfileId ->
        FanOwnershipSettleOutcome.SETTLED_MANUAL
    settledScope == ActiveSessionScope.SYSTEM &&
        settledFanMode == TuningPlanner.FAN_SMART &&
        settledProfileId == stagedProfileId ->
        FanOwnershipSettleOutcome.DEGRADED_VENDOR_SMART
    else -> FanOwnershipSettleOutcome.SESSION_LOST
}

/**
 * E1/E2 : re-keys a pending
 * renewal state onto the SETTLED generation/transactionId before
 * [FanOwnershipRenewalPolicy.afterAttempt] records this attempt, for the case E1's own A'
 * rollback re-apply creates: a MANUAL entry that failed and rolled forward into a PROFILE_AUTO
 * re-apply lands under a NEW generation, not the one [state] was minted for. Without this,
 * `afterAttempt` would carry the OLD (now-stale) generation forward forever --
 * [FanOwnershipRenewalPolicy.due]'s own `state.generation == activeGeneration` check would then
 * never match the session's real, current generation again, and the renewal would silently stop
 * retrying. A null settled identity (no active session) leaves [state] untouched -- there is
 * nothing to re-key onto, and [fanOwnershipSettleOutcome] has already routed that case to
 * [FanOwnershipSettleOutcome.SESSION_LOST], which never reaches this function.
 */
internal fun rekeyRenewalToSettledGeneration(
    state: FanOwnershipRenewalState,
    settledGeneration: Long?,
    settledTransactionId: String?,
): FanOwnershipRenewalState =
    if (settledGeneration != null && settledTransactionId != null) {
        state.copy(generation = settledGeneration, transactionId = settledTransactionId)
    } else {
        state
    }

/**
 * Renewal identity: generation-keyed state
 * for a pending renewal of Mimir fan ownership under an active SMART-staged contract. Keyed to the
 * exact generation/transactionId the degrade happened under (or the tick observed an external
 * fan-mode drift under) so a stale renewal from a PRIOR session can never be acted on inside a new
 * one -- see [FanOwnershipRenewalPolicy.forActiveGeneration].
 */
internal data class FanOwnershipRenewalState(
    val generation: Long,
    val transactionId: String,
    val code: String,
    val attempts: Int,
    val nextAttemptAtElapsedMs: Long,
    /**
     * True once [FanOwnershipRenewalPolicy.afterAttempt] has recorded
     * [FanOwnershipRenewalPolicy.MAX_CONSECUTIVE_ATTEMPTS] consecutive DEGRADED attempts for this
     * renewal. Marks the state for [FanOwnershipObservability.onRenewalExhausted] (logged once, on
     * the transition into this state) and for [FanOwnershipRenewalPolicy.onExternalDrift] (an
     * external fan-mode drift is the only thing that resets an exhausted renewal back onto the
     * ordinary retry cadence before its own backoff window elapses). Deliberately NOT consulted by
     * [FanOwnershipRenewalPolicy.due] -- see that function's own kdoc for why the backoff alone
     * (not this flag) is what governs eligibility.
     */
    val exhausted: Boolean = false,
    /**
     * G (found in review of executor F's commit); narrowed by I-1 (third review, R3-1): the last
     * observed VENDOR-HELD, NON-Smart live `fan_mode` recorded by
     * [FanOwnershipRenewalPolicy.observeWhileExhausted] while this renewal is [exhausted]. NEVER
     * holds [TuningPlanner.FAN_SMART] (4) -- the daemon itself writes 4 on every failed attempt
     * (the renewal hand-to-4, or the E1 rollback landing at 4), so a live read of 4 can never be
     * told apart from the daemon's own write; recording it would let the vendor's next re-assert
     * of the level it actually holds read as a "change" from the daemon's own stale 4 and reset
     * the hold at the vendor's re-assert rate, unbounded by [FanOwnershipRenewalPolicy
     * .MAX_CONSECUTIVE_ATTEMPTS] (H's original "follows the LAST observed mode, Smart included"
     * is withdrawn for this reason).
     *
     * Distinguishes a merely-HELD external level (the vendor still refusing
     * [TuningPlanner.FAN_SMART], tick after tick -- e.g. a game or the vendor UI keeps writing
     * SPORT/SILENT and [MimirTweakerService.handFanToVendorSmartForRenewal] can never win the
     * mode back) from a genuinely NEW external write (the level CHANGES) -- only the latter is
     * new information that should reset the exhausted hold early; the former must not re-arm the
     * 10-minute hold on every tick. Null until the first post-exhaustion NON-Smart observation;
     * reset to null by [FanOwnershipRenewalPolicy.afterAttempt] whenever it (re)exhausts the
     * renewal (the next tick's observation starts fresh), and left null by
     * [FanOwnershipRenewalPolicy.begin] / [FanOwnershipRenewalPolicy.onExternalDrift] -- a
     * freshly begun or just-reset renewal has not observed anything yet. Documented cost (I-1): a
     * player's Sport -> Smart -> Sport no longer resets the hold -- the intervening Smart is
     * indistinguishable from the daemon's own write, so it is simply not recorded, and the SECOND
     * Sport observation is compared against the still-recorded first Sport and found unchanged;
     * the hold is waited out (<= 10 min).
     */
    val exhaustedLiveFanMode: Int? = null,
)

/**
 * Pure backoff/eligibility policy over [FanOwnershipRenewalState]. No I/O, no clock reads of its
 * own -- every function takes `nowElapsedMs` as a parameter so it is host-testable without
 * `SystemClock`.
 */
internal object FanOwnershipRenewalPolicy {
    /** Delay before the FIRST retry after a degrade or an observed external fan-mode drift. */
    const val FIRST_RETRY_MS = 15_000L

    /** Delay before every retry after the first. */
    const val RETRY_MS = 30_000L

    /**
     * Consecutive DEGRADED attempts (this [generation]/[transactionId], no intervening success or
     * external drift) after which [afterAttempt] marks the state [FanOwnershipRenewalState
     * .exhausted] and backs off to [EXHAUSTED_HOLD_MS] instead of [RETRY_MS]. Without a cap, a
     * persistent post-restore MANUAL refusal renews every 30 s forever, each cycle spending
     * several seconds with the compute axes at Stock mid-restore -- the measured
     * cost.
     */
    const val MAX_CONSECUTIVE_ATTEMPTS = 3

    /**
     * F4: the backoff [afterAttempt] schedules once a renewal reaches [MAX_CONSECUTIVE_ATTEMPTS]
     * -- ten minutes, the same window the bridge caps its own standing rearm to. Not an indefinite
     * hold by itself ([due] does not consult [FanOwnershipRenewalState.exhausted] -- see that
     * function's own kdoc): an exhausted renewal is simply due again, at the ordinary reduced
     * rate, once this window elapses, OR immediately on an observed external fan-mode drift via
     * [onExternalDrift].
     */
    const val EXHAUSTED_HOLD_MS = 600_000L

    /** Begins tracking a new renewal for [generation], due after [FIRST_RETRY_MS]. */
    fun begin(
        generation: Long,
        transactionId: String,
        code: String,
        nowElapsedMs: Long,
    ): FanOwnershipRenewalState = FanOwnershipRenewalState(
        generation = generation,
        transactionId = transactionId,
        code = code,
        attempts = 0,
        nextAttemptAtElapsedMs = nowElapsedMs + FIRST_RETRY_MS,
        exhausted = false,
    )

    /**
     * Whether a retry is due now: [state] must exist, must still be keyed to the session's own
     * [activeGeneration] (never a stale one, and never with no active session at all), and the
     * backoff must have elapsed.
     *
     * Deliberately does NOT also require `!state.exhausted`. F4's cap
     * ([MAX_CONSECUTIVE_ATTEMPTS]) is a RATE reduction, not a standing freeze: an exhausted
     * renewal's own [FanOwnershipRenewalState.nextAttemptAtElapsedMs] is pushed out to
     * [EXHAUSTED_HOLD_MS] by [afterAttempt], and this function's ordinary backoff check alone
     * governs when it becomes due again -- exactly the "3rd failed attempt -> next due at +10 min"
     * pin. [FanOwnershipRenewalState.exhausted] itself is consulted elsewhere (once, for
     * [FanOwnershipObservability.onRenewalExhausted]'s dedup, and by
     * [MimirTweakerService.fanControlDecision] to route an observed external drift through
     * [onExternalDrift] instead of minting a fresh state) -- it marks the state for those callers,
     * it does not gate this one.
     */
    fun due(
        state: FanOwnershipRenewalState?,
        activeGeneration: Long?,
        nowElapsedMs: Long,
    ): Boolean = state != null &&
        activeGeneration != null &&
        state.generation == activeGeneration &&
        nowElapsedMs >= state.nextAttemptAtElapsedMs

    /**
     * Records one more failed attempt. Reschedules after [RETRY_MS] and clears
     * [FanOwnershipRenewalState.exhausted] while `attempts` stays under [MAX_CONSECUTIVE_ATTEMPTS];
     * once it reaches the cap, reschedules after [EXHAUSTED_HOLD_MS] instead and marks the state
     * exhausted -- and stays there on every subsequent call (a 4th, 5th, ... attempt against an
     * already-exhausted state is still `>= MAX_CONSECUTIVE_ATTEMPTS`), until [onExternalDrift]
     * resets it.
     */
    fun afterAttempt(
        state: FanOwnershipRenewalState,
        code: String,
        nowElapsedMs: Long,
    ): FanOwnershipRenewalState {
        val attempts = state.attempts + 1
        val exhausted = attempts >= MAX_CONSECUTIVE_ATTEMPTS
        return state.copy(
            attempts = attempts,
            code = code,
            nextAttemptAtElapsedMs = nowElapsedMs + if (exhausted) EXHAUSTED_HOLD_MS else RETRY_MS,
            exhausted = exhausted,
            // G: an attempt that (re-)exhausts the renewal starts a fresh exhausted period --
            // any level recorded by observeWhileExhausted during a PRIOR exhausted period (e.g.
            // one that survived to become `due` again after EXHAUSTED_HOLD_MS with no drift in
            // between) must not leak forward and be mistaken for an observation of THIS period.
            // Harmless when attempts < MAX_CONSECUTIVE_ATTEMPTS too: exhaustedLiveFanMode is only
            // ever non-null while exhausted, so it is already null on every such call.
            exhaustedLiveFanMode = null,
        )
    }

    /**
     * External drift: an external fan-mode drift observed while
     * [state] is [FanOwnershipRenewalState.exhausted] resets it back onto the ordinary cadence
     * immediately, rather than waiting out [EXHAUSTED_HOLD_MS] -- a fresh external event is new
     * information a timer alone cannot have. Re-arms exactly ONE attempt (`attempts` =
     * [MAX_CONSECUTIVE_ATTEMPTS] - 1, deliberately NOT a fresh 0): each external change is
     * answered by exactly one renewal try. Under a persistent post-restore MANUAL refusal, each
     * attempt is a restore + AUTO re-apply (several seconds with the compute axes at Stock) --
     * re-arming a full run of [MAX_CONSECUTIVE_ATTEMPTS] on a single external change (e.g. one
     * player toggle) would spend up to that many Stock windows before the hold could resume. One
     * attempt is sufficient: if it fails, [afterAttempt] re-exhausts immediately (`attempts`
     * reaches [MAX_CONSECUTIVE_ATTEMPTS] on the very next call -- see that function's own kdoc)
     * and the 10-minute hold resumes; a success clears the whole renewal state on the
     * SETTLED_MANUAL path instead. Sets `nextAttemptAtElapsedMs` to [nowElapsedMs] (due now,
     * matching [MimirTweakerService.fanControlDecision]'s own immediate-mint precedent for a
     * first-observed drift -- see that call site's own comment). [observeWhileExhausted] is the
     * only caller now; it holds [state] non-null (the exhausted check that reaches this function
     * already required a non-null renewal).
     */
    fun onExternalDrift(state: FanOwnershipRenewalState, nowElapsedMs: Long): FanOwnershipRenewalState =
        state.copy(
            attempts = MAX_CONSECUTIVE_ATTEMPTS - 1,
            nextAttemptAtElapsedMs = nowElapsedMs,
            exhausted = false,
            // G: a reset renewal has observed nothing yet on its fresh cadence -- the NEXT
            // exhaustion (if any) must start its own observeWhileExhausted recording over, not
            // inherit the level that triggered THIS reset.
            exhaustedLiveFanMode = null,
        )

    /**
     * G (found in review of executor F's commit); refined by H (found in review of executor G's
     * commit); narrowed by I-1 (third review, R3-1): observes the live vendor `fan_mode` while
     * [state] is [FanOwnershipRenewalState.exhausted] and resets the hold EDGE-triggered, on a
     * CHANGE of the last recorded VENDOR-HELD, NON-Smart mode -- never LEVEL-triggered on it
     * merely being non-Smart, and [TuningPlanner.FAN_SMART] itself is NEVER recorded. Precondition
     * `state.exhausted` (the only caller, [MimirTweakerService.fanControlDecision], already holds
     * that -- see that call site's own comment).
     *
     * Pre-G, the caller reset an exhausted renewal (via [onExternalDrift]) whenever
     * `liveFanMode != FAN_SMART` on ANY tick. If the reason a renewal keeps failing is that
     * [MimirTweakerService.handFanToVendorSmartForRenewal] can never win mode 4 back from the
     * vendor (a game or the vendor UI keeps re-writing SPORT/SILENT), the live mode is `!= 4` on
     * EVERY tick -- so the 10-minute exhausted hold was re-armed every ~1s tick and the daemon
     * slammed full PWM + mode 4 at the fan every ~20s (the renewal's own [FIRST_RETRY_MS]/
     * [RETRY_MS] cadence) for the rest of the session, defeating [MAX_CONSECUTIVE_ATTEMPTS]
     * entirely.
     *
     * `null` (a failed telemetry read, not an observed write -- same narrower reading the
     * fresh-mint branch above uses) leaves [state] untouched. [TuningPlanner.FAN_SMART] itself
     * ALSO leaves [state] completely untouched -- checked BEFORE the `last == null` branch --
     * because the daemon writes 4 itself on every failed attempt (the renewal hand-to-4, or the
     * E1 rollback landing at 4): nothing distinguishes that write, observed on the very next tick,
     * from the vendor genuinely landing on Smart, so recording it (H's original behaviour) let the
     * vendor's next re-assert of the level it actually holds read as a change from the daemon's
     * own stale record and reset the hold at the vendor's re-assert rate -- unbounded by
     * [MAX_CONSECUTIVE_ATTEMPTS] (R3-1). H's "follows the LAST observed mode" claim is WITHDRAWN
     * as it applies to Smart; it still holds among NON-Smart levels, which is what the rest of
     * this kdoc (H's own account, minus the Smart case) describes.
     *
     * [FanOwnershipRenewalState.exhaustedLiveFanMode] (`last`) tracks the last observed
     * VENDOR-HELD, NON-Smart live mode, not merely the first: a level that is null (nothing
     * recorded yet) is simply recorded; a level that repeats the last one is a no-op (still just
     * HELD, nothing new); only a NON-Smart level that differs from `last` is a genuinely NEW
     * external write and resets via [onExternalDrift]. H (found in review of executor G's
     * commit): G's fix compared every new observation against only the FIRST level recorded after
     * exhaustion, so a Sport(5) -> Smart(4) -> Sport(5) sequence compared the second Sport
     * observation against the stale first-recorded Sport(5) and missed the genuine external write
     * hiding behind the vendor's intervening Smart -- tracking `last` instead of "first" was
     * meant to close that gap. I-1 withdraws that specific fix: since Smart is never recorded any
     * more, the intervening Smart never updates `last` either, so the second Sport observation is
     * compared against the STILL-recorded first Sport and found unchanged -- documented cost
     * (I-1): a player's Sport -> Smart -> Sport now waits out the hold (<= 10 min) rather than
     * resetting it.
     */
    fun observeWhileExhausted(
        state: FanOwnershipRenewalState,
        liveFanMode: Int?,
        nowElapsedMs: Long,
    ): FanOwnershipRenewalState {
        require(state.exhausted)
        if (liveFanMode == null) return state
        if (liveFanMode == TuningPlanner.FAN_SMART) return state
        val last = state.exhaustedLiveFanMode
        return when {
            last == null -> state.copy(exhaustedLiveFanMode = liveFanMode)
            liveFanMode == last -> state
            else -> onExternalDrift(state, nowElapsedMs)
        }
    }

    /**
     * Drops [state] when it no longer matches [activeGeneration] (a different session, or no
     * session at all) -- a renewal minted for one session must never be carried into, or acted on
     * behalf of, another.
     */
    fun forActiveGeneration(
        state: FanOwnershipRenewalState?,
        activeGeneration: Long?,
    ): FanOwnershipRenewalState? =
        state?.takeIf { activeGeneration != null && it.generation == activeGeneration }
}

/**
 * The ordered steps a MANUAL
 * fan re-entry (renewal or ordinary reassert) must pass through, from ANY live hardware state, on
 * its way back to a verified [TuningPlanner.FAN_MANUAL] write. Driven by
 * [MimirTweakerService.prepareManualFanReentryForReassert].
 */
internal enum class FanManualReentryStep { HAND_TO_VENDOR_SMART, CAPTURE_HANDOFF, REASSERT }

/**
 * A re-entry from ANY
 * hardware state passes through vendor Smart at full PWM first (I3 step (1),
 * [PServerHardwareCommandPort.handFanToVendorSmartForRenewal]) before it may sample and capture a
 * fresh handoff, and only a re-entry that already holds a prepared handoff at live vendor Smart may
 * proceed straight to the write ([TransactionCoordinator.reassertAppliedFanBound] ->
 * [PServerHardwareCommandPort.execute] -> `enterManualFanMode`). Every failure along the way
 * therefore leaves the vendor driving -- cooling-positive by construction, never a parked fan.
 *
 * [liveFanMode] `!= TuningPlanner.FAN_SMART` deliberately includes `null` (a failed telemetry read
 * is not proof the hardware is already at Smart) -- the same non-optimistic posture
 * `fanControlDecision`'s own P3 branch documents for its narrower `liveFanMode != null &&` gate.
 */
internal fun fanManualReentryStep(liveFanMode: Int?, handoffPrepared: Boolean): FanManualReentryStep = when {
    liveFanMode != TuningPlanner.FAN_SMART -> FanManualReentryStep.HAND_TO_VENDOR_SMART
    !handoffPrepared -> FanManualReentryStep.CAPTURE_HANDOFF
    else -> FanManualReentryStep.REASSERT
}

/**
 * Owner
 * precheck required immediately before ANY Mimir-initiated fan hardware write in
 * [MimirTweakerService.transitionFanControlModeOnWorker]'s entry branch (inside
 * `FanTransitionOrchestrator.run`'s `proceed`, so admission has already run) --
 * [PublicOwnerRegistry.snapshot] read FRESH at write time, not the (possibly stale by then) owner
 * snapshot either caller was originally invoked with: still alive, not cancelled, and bound to
 * the EXACT token this write is being performed on behalf of.
 *
 * Previously the entry hand-to-4 write ran before any
 * owner check at all, and [PublicOwnerRegistry.activeOwnerForRenewal] (the renewal's own owner
 * lookup) omitted the cancel check entirely -- see that function's own KDoc for the matching fix
 * on the registry side. This predicate is [prepareManualFanReentryForReassert]'s and the entry
 * branch's own belt on top of that: even a registry that returns a stale snapshot to a caller
 * that queued the write earlier cannot make the write land on a dead/cancelled/mismatched owner.
 */
internal fun ownerEligibleForFanHardwareWrite(
    owner: PublicOwnerSnapshot?,
    expectedTokenId: Long,
): Boolean = owner != null && owner.alive && !owner.cancelled && owner.tokenId == expectedTokenId

/** E4's suppression window: 15 s from a confirmed Mimir-initiated `fan_mode` write. */
internal const val VENDOR_FLOOR_LEARNING_SUPPRESSION_MS = 15_000L

/**
 * Whether [MimirTweakerService.observeVendorSmartDuty] may fold a sample into the learned vendor
 * floor right now. Every Mimir-initiated `fan_mode` write commands full PWM at mode 4
 * (`handFanToVendorSmartForRenewal`, `forceCoolingSmartReassert`, the fan-first restore) -- a
 * cooling-positive write by construction (I3), never a genuine vendor-chosen duty. Folding one of
 * those instants into [VendorSmartFanFloor] would teach the floor "the vendor always drives
 * 100%", inflating [VendorSmartFanFloor.percentAt] for every later Manual session on this
 * chassis. [nowElapsedMs] `>= suppressedUntilElapsedMs` is inclusive at the boundary -- the
 * instant the suppression window elapses, learning resumes on that same tick.
 */
internal fun vendorFloorLearningAllowed(nowElapsedMs: Long, suppressedUntilElapsedMs: Long): Boolean =
    nowElapsedMs >= suppressedUntilElapsedMs

/**
 * E5 : a renewal state is
 * begun/kept only while the active session's own scope is [ActiveSessionScope.SYSTEM].
 *
 * **LOAD-BEARING, not belt-and-braces (second adversarial review, spec §5 finding 3).** This
 * predicate was originally written -- and this KDoc originally described it -- as unreachable
 * defence for a single caller, [MimirTweakerService.renewMimirFanOwnershipOnWorker]'s own
 * `NOT_SYSTEM_SCOPE` decline, on the reasoning that the transition it drives always re-applies
 * under `sessionScope = ActiveSessionScope.SYSTEM` regardless of the session it was minted for.
 * That reasoning never covered the step BEFORE the transition: at 90f4dbd5,
 * [MimirTweakerService.fanControlDecision]'s own SMART branch minted and dispatched a renewal
 * for ANY scope, with no call to this function at all -- so a per-game (LAUNCH) session with an
 * external vendor fan change would have been minted a renewal by the daemon's OWN 1 Hz tick, with
 * no player action, before `renewMimirFanOwnershipOnWorker`'s decline ever got a chance to run.
 * Had the OWED, pre-existing LAUNCH-scope fan-toggle gap this KDoc already named (`setFanControlV6`
 * has no scope check, so the transition re-applies as SYSTEM regardless) closed on the transition
 * side first, that daemon-minted renewal is exactly what would have converted a per-game session
 * into a standing SYSTEM one behind the player's back.
 *
 * [MimirTweakerService.fanControlDecision] now calls this function directly to gate its own
 * mint-and-dispatch sub-branch: a non-SYSTEM session's SMART branch never mints, dispatches, or
 * logs a renewal decision in the first place. [MimirTweakerService.renewMimirFanOwnershipOnWorker]'s
 * own `NOT_SYSTEM_SCOPE` decline (state cleared, no reschedule) remains as defence in depth for a
 * renewal that reaches it some other way -- and for the fan-toggle gap above, until that closes.
 */
internal fun renewalScopeAdmitted(scope: ActiveSessionScope): Boolean = scope == ActiveSessionScope.SYSTEM

/**
 * The MANUAL branch's
 * three non-healthy outcomes -- `fanControlDegraded`'s call sites for `FAN_TELEMETRY_UNAVAILABLE`,
 * a `FanControlDecision.FailSafe` reason, and a failed duty write -- were silent on production.
 * The device proof of the mechanism could not say which of the three ran in the window
 * between a successful reassert and the next heartbeat denial; this makes each speak, exactly
 * once per sustained condition. Always-on (every posture), `Log` wrapped in `runCatching` so a
 * diagnostic can never throw or change behaviour -- same register as [FanOwnershipObservability].
 *
 * **The dedup key is deliberately NOT always the rendered text.** [MimirTweakerService.fanControlDecision]'s
 * two healthy outcomes (`Hold`, a confirmed `WriteDuty`) render two DIFFERENT lines for the SAME
 * underlying fact -- "the controller made healthy progress this tick" -- and a PI controller
 * legitimately alternates between them tick to tick. Keying dedup on the literal text would
 * re-emit on every `Hold`<->`WriteDuty` flip forever, which is exactly the flood this seam exists
 * to prevent. So every healthy call shares one constant key ([HEALTHY_KEY]); every non-healthy
 * call keys on its own rendered text, since a literal reason/status change there IS a new fact
 * worth a fresh line. One shared `lastKey` slot across both kinds means the transition into a healthy outcome after ANY
 * non-healthy one always emits, because the healthy key can never equal a non-healthy key.
 */
internal object ManualFanTickObservability {
    private const val TAG = "MimirFanControl"
    private const val HEALTHY_KEY = "manual tick=healthy"

    /** The most recent rendered line, set on every call -- including suppressed duplicates. */
    @Volatile
    var lastRecorded: String? = null
        private set

    private var lastKey: String? = null

    @Synchronized
    private fun emit(key: String, line: String): Boolean {
        lastRecorded = line
        if (key == lastKey) return false
        lastKey = key
        runCatching { Log.w(TAG, line) }
        return true
    }

    fun describeDegraded(reason: String): String = "manual tick=degraded reason=$reason"
    fun describeFailSafe(reason: String): String = "manual tick=failSafe reason=$reason"
    fun describeWriteFailed(status: String): String = "manual tick=writeFailed status=$status"
    fun describeHealthy(decision: String): String = "manual tick=healthy decision=$decision"

    /**
     * Pure classification for [MimirTweakerService.fanControlDegraded]'s single `reason`
     * parameter, shared by all three of its call sites -- the pinned source-text tests in
     * `HardwareSessionSupervisorTest` require every one of them to keep calling
     * `fanControlDegraded` literally (one is a single-expression `when` arm with no room for a
     * sibling statement), so the `FailSafe` label can only be decided from inside this function,
     * by reason value: the two call sites that pass a hardcoded literal get their own named
     * outcome, and everything else (i.e. every `FanControlDecision.FailSafe.reason`, none of
     * which collide with either literal -- see `FanThermalControl.kt`) renders as `failSafe`.
     *
     * `FAN_DUTY_WRITE_FAILED` renders nothing here: [onWriteFailed] already logs the richer
     * `status=` line at its own call site, immediately before `fanControlDegraded` runs, and this
     * object's dedup key is a single shared slot -- a second line for the same tick would mean a
     * sustained failure never goes quiet (see this object's own kdoc).
     */
    fun onDegradedReason(reason: String): Boolean = when (reason) {
        "FAN_TELEMETRY_UNAVAILABLE" -> describeDegraded(reason).let { emit(it, it) }
        "FAN_DUTY_WRITE_FAILED" -> false
        else -> describeFailSafe(reason).let { emit(it, it) }
    }

    fun onWriteFailed(status: String): Boolean = describeWriteFailed(status).let { emit(it, it) }

    fun onHealthy(decision: String): Boolean = emit(HEALTHY_KEY, describeHealthy(decision))
}
