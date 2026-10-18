package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle

const val TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION = 6

enum class TweakerFanMode {
    AUTO,
    MANUAL,
}

data class TweakerManualFanCapability(
    val modes: Set<TweakerFanMode>,
    val curveBiasRange: IntRange,
    val curveBiasStep: Int,
    val curveSensorType: String,
    val currentMode: TweakerFanMode,
    val currentCurveBias: Int,
    val ownerRequestId: String,
    val generation: Long,
    val transactionId: String,
    val profileId: String,
    /**
     * Does Mimir actually own fan duty right now?
     *
     * `true`/`false` are the daemon's attestation of LIVE HARDWARE. **`null` means the daemon did
     * not say** -- an older Tweaker that predates the key -- and must never be read as `false`.
     *
     * This exists because [currentMode] alone cannot be trusted: the daemon publishes it from a
     * durable per-profile preference that outlives session teardown, so after a recovery restore it
     * says MANUAL while the hardware sits on vendor SMART. When this field is `false` the parser
     * has already forced [currentMode] to AUTO, so consumers see the mode that is IN EFFECT rather
     * than the one that is merely stored. When it is `null`, nothing about [currentMode] may be
     * assumed.
     *
     * Deliberately NOT part of [isSameSessionAs]: it is live state, exactly like [currentMode], and
     * a fan change exists to mutate it.
     */
    val hardwareDutyOwned: Boolean? = null,
) {
    /**
     * Is [other] the SAME fan-control session as this one -- same owner, same generation, same
     * transaction, same profile, over the same advertised control surface?
     *
     * **This exists because whole-object equality answers a different question, and the difference
     * shipped as a bug (2026-08-25).** `TweakerBridge.setFanControl` guarded its mutation with
     * `authoritative != capability`, meaning to ask "has the session moved under the row the player
     * is looking at?" Data-class equality also compares [currentMode] and [currentCurveBias] -- the
     * two fields a fan change EXISTS to mutate. So every successful change left the UI holding a
     * capability that differed from the daemon's in exactly those fields, and the next press was
     * refused client-side with "Fan control session changed before selection" and never reached the
     * daemon at all. The player saw "FAN RESPONSE NOT APPLIED" on a press that had not been
     * attempted.
     *
     * [[kind-encoded-as-nullness-inverts]] is the same family: a comparison that silently carried
     * more meaning than the question being asked.
     *
     * The mutable state fields are deliberately excluded. The immutable shape of the control
     * surface ([modes], [curveBiasRange], [curveBiasStep], [curveSensorType]) is NOT excluded: if
     * the daemon started advertising a different surface, the attempt the row composed against the
     * old one must still be refused.
     */
    fun isSameSessionAs(other: TweakerManualFanCapability): Boolean =
        ownerRequestId == other.ownerRequestId &&
            generation == other.generation &&
            transactionId == other.transactionId &&
            profileId == other.profileId &&
            modes == other.modes &&
            curveBiasRange == other.curveBiasRange &&
            curveBiasStep == other.curveBiasStep &&
            curveSensorType == other.curveSensorType
}

data class TweakerAdvancedCapabilities(
    val manualFan: TweakerManualFanCapability?,
    val thermalDerate: TweakerThermalDerate? = null,
    val frameLimitsHz: List<Int>,
    val perGameOverridesAvailable: Boolean,
    /**
     * Why the daemon last handed the hardware back to Stock, if it has. Null means no restore has
     * been recorded this session -- **not** that everything is fine, only that nothing is known.
     */
    val lastRestore: TweakerLastRestore? = null,
)

/**
 * The daemon's own account of the last silent fallback to Stock.
 *
 *  The daemon has computed and
 * shipped this for a long time (`GuardianRestoreObservability` ->
 * `AdvancedControlsPublicBundles.lastRestoreReason`), and `MimirCopy` has carried the player-facing
 * sentences for it since that change. Nothing read either. `TweakerAdvancedControlsContract`
 * meanwhile declared both keys `CONSUMED_BY_CLIENT`, so the contract drift test was honestly red on
 * this branch -- the declaration had run ahead of the surface by a fortnight.
 *
 * Parsing this without rendering it would have turned the test green while reproducing the exact
 * defect one layer up, so the value is threaded to a real message in `AppBrightnessCoordinator`.
 *
 * @param isTelemetryTransport the daemon's classification, not the client's: true when the restore
 * was caused by live thermal readings going away rather than by an ordinary session end. Only that
 * class gets a player-facing explanation, because only that class has an action the player can take.
 * @param isGuardianHandback the daemon's second classification (fan-fence fix F4, 2026-10-14): true
 * when the Tweaker's own guardian process handed the hardware back to Stock -- its heartbeat gate
 * denied, its watchdog expired, or its terminal receipt landed -- while the player thought a profile
 * was running. A measured Odin sat at 103 C under vendor floors for twelve minutes that way with
 * nothing on screen. This class has an action too: re-apply the profile. Absent on an older daemon
 * means false, never a claim.
 */
data class TweakerLastRestore(
    val reason: String,
    val isTelemetryTransport: Boolean,
    val isGuardianHandback: Boolean = false,
    /**
     * The session generation the restore ended (0 on a daemon that does not send it). A restore
     * is one incident per (reason, generation): the same reason on a later generation is a new
     * drop and is announced again; the same pair is the same drop and is not.
     */
    val generation: Long = 0L,
) {
    /** The once-per-incident key the companion latches on. */
    val incidentKey: String get() = "$reason#$generation"
}

class TweakerFanControlAttempt internal constructor(
    val callerRequestId: String,
    val requestDeadlineElapsedRealtimeMs: Long,
    val ownerRequestId: String,
    val generation: Long,
    val transactionId: String,
    val priorMode: TweakerFanMode,
    val profileId: String,
    val mode: TweakerFanMode,
    val curveBias: Int,
) {
    init {
        require(callerRequestId.matches(ADVANCED_REQUEST_ID))
        require(ownerRequestId.matches(ADVANCED_REQUEST_ID))
        require(requestDeadlineElapsedRealtimeMs > 0L)
        require(generation > 0L)
        require(transactionId.isNotBlank())
        require(profileId in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS)
    }
}

sealed interface TweakerFanControlAssessment {
    data class Accepted(
        val changed: Boolean,
        val mode: TweakerFanMode,
        val curveBias: Int,
    ) : TweakerFanControlAssessment

    data class Rejected(val code: String, val message: String) : TweakerFanControlAssessment
    data class Uncertain(val reason: String) : TweakerFanControlAssessment
}

fun parseTweakerAdvancedCapabilitiesBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerAdvancedCapabilities {
    val frameLimits = parseTweakerFrameLimitsHz(bundle, expectedApiVersion)
    val perGameOverrides = parseTweakerPerGameOverridesAvailable(bundle, expectedApiVersion)
    val manualFan = parseTweakerManualFanCapability(bundle, expectedApiVersion)
    return TweakerAdvancedCapabilities(
        manualFan = manualFan,
        thermalDerate = parseTweakerThermalDerate(bundle, expectedApiVersion),
        frameLimitsHz = frameLimits,
        perGameOverridesAvailable = perGameOverrides,
        lastRestore = parseTweakerLastRestore(bundle, expectedApiVersion),
    )
}

/**
 * Deliberately independent of the fan section. The daemon's own contract note says these two keys
 * are *"always present, independent of the fan section"* precisely because a dead privileged
 * transport must never be silent -- so parsing them must not inherit the fan block's null-ness
 * either. A device with no fan axis can still lose telemetry and drop to Stock.
 */
private fun parseTweakerLastRestore(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerLastRestore? = runCatching {
    require(expectedApiVersion >= TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION)
    require(bundle.requiredAdvancedInt("apiVersion") == expectedApiVersion)
    val reason = bundle.getString("lastRestoreReason")?.trim().orEmpty()
    require(reason.isNotEmpty())
    TweakerLastRestore(
        reason = reason,
        isTelemetryTransport = bundle.getBoolean("lastRestoreIsTelemetryTransport", false),
        isGuardianHandback = bundle.getBoolean("lastRestoreIsGuardianHandback", false),
        generation = bundle.getLong("lastRestoreGeneration", 0L),
    )
}.getOrNull()

/**
 * A thermal derate that reduced this session's clock ceilings. The effective profile remains
 * `performance`, while the row must also report the reduced caps, cause and session-long duration
 * rather than continuing to show the nominal profile limits without explanation.
 */
data class TweakerThermalDerate(
    val reason: String,
    /** 1-based resulting position; the daemon never sends 0. */
    val rung: Int,
    val maxRung: Int,
    /** The NOMINAL profile the derate applies to. A derate never renames the profile. */
    val profileId: String,
)

/**
 * All four keys or nothing. The daemon omits the whole block when there is no actuated derate for
 * the live session, so a partial or defaulted read is a malformed bundle, not a nominal one — and
 * "rung 0 of 0" is unrepresentable by construction rather than by convention.
 */
private fun parseTweakerThermalDerate(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerThermalDerate? = runCatching {
    require(expectedApiVersion >= TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION)
    require(bundle.requiredAdvancedInt("apiVersion") == expectedApiVersion)
    val reason = bundle.getString("thermalDerateReason")?.trim().orEmpty()
    require(reason.isNotEmpty())
    val rung = bundle.getInt("thermalDerateRung", 0)
    val maxRung = bundle.getInt("thermalDerateMaxRung", 0)
    require(rung >= 1 && maxRung >= rung)
    val profileId = bundle.getString("thermalDerateProfileId")?.trim().orEmpty()
    require(profileId.isNotEmpty())
    TweakerThermalDerate(reason, rung, maxRung, profileId)
}.getOrNull()

private fun parseTweakerFrameLimitsHz(
    bundle: Bundle,
    expectedApiVersion: Int,
): List<Int> = runCatching {
    require(expectedApiVersion >= TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION)
    require(bundle.requiredAdvancedInt("apiVersion") == expectedApiVersion)
    val frameLimits = bundle.getIntegerArrayList("frameLimitsHz").orEmpty()
    require(frameLimits == frameLimits.distinct().sorted())
    require(frameLimits.all { it in 24..240 })
    frameLimits
}.getOrDefault(emptyList())

private fun parseTweakerPerGameOverridesAvailable(
    bundle: Bundle,
    expectedApiVersion: Int,
): Boolean = runCatching {
    require(expectedApiVersion >= TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION)
    require(bundle.requiredAdvancedInt("apiVersion") == expectedApiVersion)
    bundle.requiredAdvancedBoolean("perGameOverridesAvailable")
}.getOrDefault(false)

private fun parseTweakerManualFanCapability(
    bundle: Bundle,
    expectedApiVersion: Int,
): TweakerManualFanCapability? = runCatching {
    require(expectedApiVersion >= TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION)
    require(bundle.requiredAdvancedInt("apiVersion") == expectedApiVersion)
    require(bundle.requiredAdvancedInt("fanControlContractVersion") == 2)
    val manualAvailable = bundle.requiredAdvancedBoolean("manualFanControlAvailable")
    if (manualAvailable) parseManualFanCapability(bundle) else null
}.getOrNull()

private fun parseManualFanCapability(bundle: Bundle): TweakerManualFanCapability {
    val modes = bundle.getStringArrayList("fanControlModes").orEmpty().map {
        TweakerFanMode.valueOf(it)
    }.toSet()
    // Mimir-duty-owned contracts (TuningPlanner.MIMIR_DUTY_OWNED_FAN_MODES on the service side)
    // may offer only the Auto curve-bias preset without qualified MANUAL (raw PWM) entry -- the
    // autoExit/duty-owned shape sends fanControlModes = ["AUTO"]. Accept any non-empty modes set
    // that contains AUTO; still reject empty (malformed/no capability) and anything that isn't a
    // real TweakerFanMode (TweakerFanMode.valueOf above already throws for that, caught below).
    require(modes.isNotEmpty() && TweakerFanMode.AUTO in modes)
    val minimum = bundle.requiredAdvancedInt("fanCurveBiasMin")
    val maximum = bundle.requiredAdvancedInt("fanCurveBiasMax")
    val step = bundle.requiredAdvancedInt("fanCurveBiasStep")
    require(minimum == -100 && maximum == 100 && step == 10)
    val currentMode = TweakerFanMode.valueOf(bundle.requiredAdvancedString("fanControlMode"))
    require(currentMode in modes) { "currentMode $currentMode is not one of the advertised modes $modes" }
    val currentBias = bundle.requiredAdvancedInt("fanCurveBias")
    require(currentBias in minimum..maximum && currentBias % step == 0)
    if (currentMode == TweakerFanMode.AUTO) require(currentBias == 0)
    val ownerRequestId = bundle.requiredAdvancedString("ownerRequestId")
    require(ownerRequestId.matches(ADVANCED_REQUEST_ID))
    val generation = bundle.requiredAdvancedLong("generation")
    require(generation > 0L)
    val transactionId = bundle.requiredAdvancedString("transactionId")
    require(transactionId.isNotBlank())
    val profileId = bundle.requiredAdvancedString("profileId")
    require(profileId in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS)
    val sensor = bundle.requiredAdvancedString("fanCurveSensorType")
    require(sensor == "GPU")
    // Absent on any Tweaker older than 2026-08-26. Absence is meaningful and is NOT false: it means
    // this daemon cannot attest ownership, so `currentMode` stays as published and the re-arm gate
    // declines to trust it. `getBoolean` cannot express that, hence the explicit containsKey.
    val dutyOwned = if (bundle.containsKey("fanHardwareDutyOwned")) {
        bundle.getBoolean("fanHardwareDutyOwned")
    } else {
        null
    }
    // The whole point of the attestation: when Mimir does not own duty, the mode IN EFFECT is the
    // vendor's, whatever the stored selection says. Overriding here -- at the single place a
    // capability is constructed -- is what lets every consumer (the panel's mode label, the
    // toggles, the re-arm gate) see the truth without a call-site sweep.
    //
    // The bias must be zeroed with it. Consumers fall back to `currentCurveBias` when they have no
    // remembered bias of their own, and a stale -100 riding a forced-AUTO capability would leak the
    // dead selection back into a fresh request. The daemon holds AUTO to bias 0 for exactly this
    // reason (`parseManualFanCapability` requires it above); a forced AUTO must honour the same rule.
    val effectiveMode = if (dutyOwned == false) TweakerFanMode.AUTO else currentMode
    val effectiveBias = if (effectiveMode == TweakerFanMode.AUTO) 0 else currentBias
    return TweakerManualFanCapability(
        modes = modes,
        curveBiasRange = minimum..maximum,
        curveBiasStep = step,
        curveSensorType = sensor,
        currentMode = effectiveMode,
        currentCurveBias = effectiveBias,
        ownerRequestId = ownerRequestId,
        generation = generation,
        transactionId = transactionId,
        profileId = profileId,
        hardwareDutyOwned = dutyOwned,
    )
}

fun parseTweakerFanControlBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
    attempt: TweakerFanControlAttempt,
    observedAtElapsedRealtimeMs: Long,
): TweakerFanControlAssessment = runCatching {
    require(expectedApiVersion >= TWEAKER_V6_ADVANCED_CONTROLS_MIN_API_VERSION)
    require(attempt.requestDeadlineElapsedRealtimeMs > observedAtElapsedRealtimeMs)
    require(bundle.requiredAdvancedInt("apiVersion") == expectedApiVersion)
    require(bundle.requiredAdvancedString("callerRequestId") == attempt.callerRequestId)
    require(
        bundle.requiredAdvancedLong("callerDeadlineElapsedRealtimeMs") ==
            attempt.requestDeadlineElapsedRealtimeMs,
    )
    val success = bundle.requiredAdvancedBoolean("success")
    val code = bundle.requiredAdvancedString("code")
    val message = bundle.requiredAdvancedString("message")
    require(code.isNotBlank() && message.isNotBlank())
    if (!success) {
        TweakerFanControlAssessment.Rejected(code, message)
    } else {
        require(code == "FAN_CONTROL_UPDATED" || code == "FAN_CONTROL_UNCHANGED")
        require(bundle.requiredAdvancedString("sessionState") == "ACTIVE")
        require(bundle.requiredAdvancedBoolean("mutationMayBeActive"))
        require(bundle.requiredAdvancedString("sessionScope") == "SYSTEM")
        require(bundle.requiredAdvancedString("requestId") == attempt.ownerRequestId)
        require(bundle.requiredAdvancedString("ownerRequestId") == attempt.ownerRequestId)
        require(bundle.requiredAdvancedString("profileId") == attempt.profileId)
        val settledGeneration = bundle.requiredAdvancedLong("generation")
        val settledTransactionId = bundle.requiredAdvancedString("transactionId")
        // **This required the transaction NOT to advance, and that design was overturned two days
        // before it broke (2026-08-25).** The comment here used to read "API-v6 contract 2 keeps
        // Auto and Manual inside one qualified mode-6 Guardian transaction", which described the
        // world where AUTO and MANUAL were bias presets inside one session. Release spec §1.2 R1
        // was OVERTURNED 2026-08-23: *"Selecting Manual now transitions the device to vendor mode 6
        // and hands Mimir the PWM duty, family-wide."* A mode transition is a complete Guardian
        // transaction, so it mints a fresh generation (`journal.nextGeneration()`) and a fresh
        // `transactionId` (`UUID.randomUUID()`), and the response is stamped from the NEW session.
        //
        // The equality could therefore never hold for a transitioning change. It threw inside this
        // function's `runCatching`, degraded to Uncertain, and the row told the player "FAN RESPONSE
        // NOT APPLIED" for four consecutive changes that had all applied and verified on hardware.
        //
        // **The client cannot tell the two cases apart, and must not try.** `requiresTransition` is
        // derived daemon-side from the *contract's* fan mode, which never crosses the wire, and
        // `attempt.priorMode` is the durable per-profile preference, not the live hardware mode --
        // device-measured 2026-08-25, a `priorMode=MANUAL mode=AUTO` press ran with
        // `requiresTransition=false` because the hardware was already in vendor SMART. So this
        // checks COHERENCE instead of predicting: either the identity is untouched, or it is a
        // whole successor. Both fields move together or neither does.
        //
        // Never an ordering test on `transactionId`: it is a random UUID, and `>=` on those passes
        // about half the time, which would trade a deterministic bug for a flaky one.
        val identityUntouched = settledGeneration == attempt.generation &&
            settledTransactionId == attempt.transactionId
        val identitySucceeded = settledGeneration > attempt.generation &&
            settledTransactionId != attempt.transactionId
        require(identityUntouched || identitySucceeded)
        require(bundle.requiredAdvancedString("fanControlMode") == attempt.mode.name)
        val settledBias = bundle.requiredAdvancedInt("fanCurveBias")
        val expectedBias = if (attempt.mode == TweakerFanMode.AUTO) 0 else attempt.curveBias
        require(settledBias == expectedBias)
        TweakerFanControlAssessment.Accepted(
            changed = code == "FAN_CONTROL_UPDATED",
            mode = attempt.mode,
            curveBias = settledBias,
        )
    }
}.getOrElse { error ->
    TweakerFanControlAssessment.Uncertain(
        error.message?.takeIf(String::isNotBlank) ?: "Malformed fan control truth",
    )
}

private fun Bundle.requiredAdvancedString(key: String): String =
    getString(key)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing $key")

private fun Bundle.requiredAdvancedInt(key: String): Int {
    require(containsKey(key)) { "Missing $key" }
    return getInt(key)
}

private fun Bundle.requiredAdvancedLong(key: String): Long {
    require(containsKey(key)) { "Missing $key" }
    return getLong(key)
}

private fun Bundle.requiredAdvancedBoolean(key: String): Boolean {
    require(containsKey(key)) { "Missing $key" }
    return getBoolean(key)
}

private val ADVANCED_REQUEST_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
