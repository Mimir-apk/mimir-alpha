package com.gruning.mimir.tweaker

import android.os.Bundle

/**
 * Pure marshalling for the advanced-controls wire shape, split out of `MimirTweakerService` so it
 * has a seam a test can drive -- the same shape as `FpsLimiterPublicBundles`.
 *
 * The service still owns every impure decision: whether a session exists, whether manual fan entry
 * is available, what the live refresh rate supports, and which selection is current. All of that is
 * resolved there and handed here as plain values. What lives in this file is only the translation
 * of those values into keys, which is exactly the half that can silently drift away from the
 * client's parser -- and the half that had no daemon-side test until now, because it was buried in
 * a private member reading live supervisor state.
 */
internal fun advancedCapabilitiesBundle(
    apiVersion: Int,
    perGameOverridesAvailable: Boolean,
    frameLimitsHz: List<Int>,
    fanSectionAvailable: Boolean,
    manualModeAdvertised: Boolean,
    /**
     * The vendor `fan_mode` the ACTIVE CONTRACT actually holds, or null when no contract is
     * resolved. This is live hardware truth, and it is the whole point of this parameter: without
     * it the client has no way to tell a real Manual from a stale selection. See
     * `fanHardwareDutyOwned` below.
     */
    contractFanMode: Int?,
    selection: ActiveFanControlSelection?,
    lastRestoreReason: String? = null,
    /** P3-6: the generation [lastRestoreReason] belongs to; emitted only beside a reason. */
    lastRestoreGeneration: Long? = null,
    activeDerate: ActuatedDerate? = null,
): Bundle = Bundle().apply {
    putInt("apiVersion", apiVersion)
    putInt("fanControlContractVersion", FAN_CONTROL_CONTRACT_VERSION)
    putBoolean("manualFanControlAvailable", fanSectionAvailable)
    putIntegerArrayList("frameLimitsHz", ArrayList(frameLimitsHz))
    putBoolean("perGameOverridesAvailable", perGameOverridesAvailable)
    // a dead privileged transport must never be silent. Always present
    // (not gated on fanSectionAvailable, which this is unrelated to) so a client can tell a
    // player "Mimir returned to Stock because live thermal readings stopped" even when no fan
    // section is offered at all. Absent/null means no restore has been recorded this process
    // lifetime -- see GuardianRestoreObservability.lastRecorded, which this reads verbatim; it is
    // NOT cleared when a later session applies successfully, so a client should treat this as
    // "most recent restore reason, if any" rather than "current problem".
    putString("lastRestoreReason", lastRestoreReason)
    putBoolean(
        "lastRestoreIsTelemetryTransport",
        lastRestoreReason?.let(::isTelemetryTransportRestoreReason) == true,
    )
    // Fan-fence fix F4 (2026-10-14): the second class with an action the player can take. True
    // when the Tweaker's own guardian handed the hardware back (its heartbeat gate denied, its
    // watchdog expired, its receipt landed, a repair met its closed fence) -- every reason
    // GuardianHeartbeatRetirePolicy mints. Same contract shape as the transport flag above:
    // always present, absent on an older daemon means false, never a claim.
    putBoolean(
        "lastRestoreIsGuardianHandback",
        lastRestoreReason?.let(GuardianHeartbeatRetirePolicy::isGuardianHandbackReason) == true,
    )
    // P3-6 (fence review, 2026-10-14): the companion announces once per (reason, generation), so a
    // second hand-back with the same reason is a new incident and not swallowed. Travels with the
    // reason: absent when there is none, never a zero a client would have to interpret.
    if (lastRestoreReason != null && lastRestoreGeneration != null) {
        putLong("lastRestoreGeneration", lastRestoreGeneration)
    }
    // A thermal derate reduced this session's clock ceilings and the row must be able to say so.
    // Placed with lastRestoreReason above and BEFORE the fan-section early return for the same
    // reason: it is nothing to do with the fan, and a device with no fan axis can still be derated.
    //
    // ABSENCE IS THE SIGNAL. All four keys are omitted together when there is no actuated derate
    // for the CURRENT session, rather than written as zeroes -- a client must never be able to read
    // "rung 0 of 0" and have to guess whether that means nominal or missing.
    activeDerate?.let { derate ->
        putString("thermalDerateReason", derate.reason)
        putInt("thermalDerateRung", derate.rung)
        putInt("thermalDerateMaxRung", derate.maxRung)
        putString("thermalDerateProfileId", derate.profileId)
    }
    if (!fanSectionAvailable) return@apply
    putStringArrayList(
        "fanControlModes",
        // The manualEntryAvailable-only ["AUTO"] branch is unreachable once fanSectionAvailable is
        // true: every disjunct that can make fanSectionAvailable true also makes
        // manualFanControlModeAdvertised true -- manualEntryAvailable directly; a Mimir-duty-owned
        // contractFanMode directly; autoExitAvailable only when contractFanMode == FAN_MANUAL,
        // itself Mimir-duty-owned. Left in place as explicit defensive behaviour (see
        // manualFanControlModeAdvertised's kdoc) rather than asserted-unreachable, in case a future
        // contractFanMode shape reintroduces the gap.
        if (manualModeAdvertised) arrayListOf("AUTO", "MANUAL") else arrayListOf("AUTO"),
    )
    // **Live hardware ownership -- added 2026-08-26 to close a defect this bundle CAUSED.**
    //
    // `fanControlMode` below is published from `ActiveFanControlSelectionStore`, a durable
    // per-profile preference that deliberately outlives session teardown. After a recovery restore
    // the hardware contract comes back at vendor SMART while that preference still says MANUAL, so
    // the bundle advertised `fanControlMode=MANUAL` beside a `contractFanMode` of 4. The client,
    // having no way to tell those apart, rendered "Manual" at a player whose fan was on the vendor
    // curve and skipped the re-arm that would have fixed it -- observed on the Odin at
    // 12:31:14 (`fan_mode=4`, daemon `contractFan=4`, advertised `currentMode=MANUAL`).
    //
    // So: say which of the two is true. `fanControlMode` remains the SELECTION (unchanged, and
    // still what a re-selection restores); this says whether Mimir actually owns duty right now.
    //
    // Deliberately NOT behind a `fanControlContractVersion` bump. An older client ignores an
    // unknown key, and a newer client treats absence as "this daemon cannot attest ownership" and
    // falls back to not trusting `fanControlMode` at all -- so both install orders are safe and the
    // two packages never have to land together.
    putBoolean(
        "fanHardwareDutyOwned",
        contractFanMode in TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES,
    )
    putInt("fanCurveBiasMin", FAN_CURVE_BIAS_RANGE.first)
    putInt("fanCurveBiasMax", FAN_CURVE_BIAS_RANGE.last)
    putInt("fanCurveBiasStep", FAN_CURVE_BIAS_STEP)
    putString("fanCurveSensorType", "GPU")
    // Not optional once the section is offered. The client's parseManualFanCapability reads the
    // selection out of THIS bundle and returns no capability at all if any part of it is missing,
    // so a section advertised without its current selection would render a bias range with nothing
    // to anchor it -- which is the "control advertises what the layer beneath refuses" shape.
    putFanControlSelection(checkNotNull(selection) { "fan section offered without a live selection" })
}

internal fun Bundle.putFanControlSelection(selection: ActiveFanControlSelection): Bundle = apply {
    putString("fanControlMode", selection.mode.name)
    putInt("fanCurveBias", selection.curveBias)
    putLong("generation", selection.generation)
    putString("transactionId", selection.transactionId)
    putString("ownerRequestId", selection.requestId)
    putString("profileId", selection.profileId)
}
