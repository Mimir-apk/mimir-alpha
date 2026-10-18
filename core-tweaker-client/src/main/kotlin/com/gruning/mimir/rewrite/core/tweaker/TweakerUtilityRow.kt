package com.gruning.mimir.rewrite.core.tweaker

/**
 * The Utility dock's PERFORMANCE row is **always rendered**, whatever the Tweaker is doing.
 *
 * A row that only appears once a companion service answers is a row the player can never find when it
 * matters: an absent or wedged Tweaker would otherwise hide the tuning entry itself. So the row is permanent and the *value* carries the truth — installed-or-not, reachable-or-not,
 * and which profile is actually in force.
 */
enum class TweakerUtilityAvailability {
    /** The Tweaker package is not on the device. The row's value is the honest call to action. */
    NOT_INSTALLED,

    /**
     * The package IS installed, but nothing beyond stock can be selected right now — the bind failed,
     * the permission was refused, the report is UNSUPPORTED, or capabilities have not been read yet.
     * We do not tell the player to install something they already have.
     */
    UNAVAILABLE,

    /**
     * The installed service answered its capability query, but has explicitly classified this hardware
     * as unsupported. This is deliberately not [UNAVAILABLE]: transport worked, while tuning remains
     * stock-only.
     */
    RESPONDING_STOCK_ONLY,

    /**
     * The daemon is not serving profiles, but **the kernel publishes frequency tables and this row can
     * speak for them**.
     *
     * Deliberately outranks [UNAVAILABLE], [RESPONDING_STOCK_ONLY] and [NOT_INSTALLED]: all three are
     * claims that tuning cannot happen, and on a device whose kernel publishes tables and whose vendor
     * publishes a PServer, all three are false. the row shows the live ceiling
     * and steps it. The word UNAVAILABLE is reserved for a device that genuinely publishes no tables.
     */
    DISCOVERED_CEILING,

    /** Bound and reachable: stock plus any admitted profile may be selected. */
    READY,
}

/**
 * What the KERNEL publishes for the one policy this row speaks for, carried as primitives.
 *
 * **Primitives, because `core-tweaker-client` cannot see `MimirTuner` and must not learn to.** The
 * discovery reader, the write host and the kHz formatter all live in `app`; what crosses the boundary is
 * the answer, not the machinery — the same shape as the rail's other injected facts.
 *
 * [ceilingLabelCaps] arrives already formatted for the same reason: `companionTunerClockLabel` is the
 * product's single kHz spelling and it lives on the other side of this boundary. Re-deriving it here
 * would be a second dialect for one quantity, which the shared formatter avoids.
 */
data class TweakerDiscoveredCeiling(
    /** "PRIME", "CPU 3" — assigned by the reader from the silicon's shape, never parsed here. */
    val policyLabel: String,
    /** The ceiling in force right now, spelled by the product's one formatter. */
    val ceilingLabelCaps: String,
    /** Every frequency this policy accepts, ascending. Empty means the SoC publishes no table. */
    val steps: List<TweakerCeilingStep> = emptyList(),
    /** The cap in force right now, or null when the node could not be read. */
    val currentKhz: Int? = null,
    /** A privileged host is present, so a ceiling can actually be written. */
    val canWrite: Boolean = false,
) {
    /** The kernel published a table. This is the fact that outranks every "cannot tune" claim. */
    val canRead: Boolean get() = steps.isNotEmpty()

    /**
     * The top of the table. Applying it RELEASES Mimir's hold rather than pinning a ceiling at maximum
     * — see [TweakerUtilityStepOutcome.CeilingChanged.releasesHold].
     */
    val top: TweakerCeilingStep? get() = steps.lastOrNull()

    /**
     * Something is holding the ceiling BELOW the top of the table right now. The kernel says so whether or not
     * the Tweaker can be reached to name the profile behind it, which is why this outranks "the daemon did not
     * answer" in [tweakerUtilityAvailability]: a row that reads UNAVAILABLE over a held node is the one place
     * the row and the hardware disagree (Nova 2026-10-01 12:45, the CPU QoS node held at Quiet).
     */
    val ceilingHeld: Boolean
        get() {
            val current = currentKhz ?: return false
            val topKhz = top?.khz ?: return false
            return current < topKhz
        }
}

/**
 * One frequency the row can step to, carrying its own spelling.
 *
 * **The label travels with the number rather than being derived here.** `companionTunerClockLabel` is
 * the product's single kHz spelling and it lives in `app`, on the far side of this module boundary; a
 * formatter invented here to serve a toast would be the second dialect for one quantity that the shared formatter avoids.
 */
data class TweakerCeilingStep(val khz: Int, val labelCaps: String)

/**
 * Move [fromKhz] one place along [steps]. Clamps at both ends rather than wrapping.
 *
 * Profiles wrap because they are a short named ring; a frequency table is an ORDERED SCALE with a floor
 * and a ceiling, and wrapping from maximum straight to minimum is how a player asking for one more step
 * of headroom would instead throttle the device to its slowest clock.
 */
fun steppedTweakerCeilingKhz(
    fromKhz: Int?,
    direction: Int,
    steps: List<TweakerCeilingStep>,
): Int? {
    if (steps.isEmpty()) return null
    val index = steps.indexOfFirst { it.khz == fromKhz }
    // An unreadable or off-table current cap steps from the TOP, not from position zero: a null read
    // must never be answered by driving the device to its slowest clock.
    val from = if (index >= 0) index else steps.lastIndex
    return steps[(from + direction).coerceIn(0, steps.lastIndex)].khz
}

/** Capped value for a Tweaker that is not on the device. */
const val TWEAKER_UTILITY_INSTALL_CAPS = "INSTALL TWEAKER"

/** Capped value for a Tweaker that is installed but cannot serve certified tuning right now. */
const val TWEAKER_UTILITY_UNAVAILABLE_CAPS = "UNAVAILABLE"

/** Capped value for a connected Tweaker that has explicitly declined this hardware. */
const val TWEAKER_UTILITY_STOCK_ONLY_CAPS = "STOCK ONLY"

/**
 * Appended to the profile when heat has cut its ceilings. "REDUCED" over "DERATED" deliberately:
 * the row speaks to a player mid-game, and "derate" is our word, not theirs.
 */
const val TWEAKER_UTILITY_DERATED_CAPS = "REDUCED"

/**
 * The device is held by a restore that has not finished, so the profiles are real and none of them can
 * be selected right now.
 *
 * Additive, exactly like [TWEAKER_UTILITY_DERATED_CAPS]: it decorates the profile the session is really
 * on rather than replacing it, because "held" is a fact about whether the row can MOVE, not about what
 * it is showing. Both handhelds were observed showing no profiles for hours while the daemon still served the list.
 */
const val TWEAKER_UTILITY_HELD_CAPS = "HELD"

/**
 * The profile list is the daemon's last real answer and the daemon has not been heard from since. Neither a profile
 * (which may no longer be in force) nor UNAVAILABLE (which says nothing can be done): it is being checked.
 */
const val TWEAKER_UTILITY_CHECKING_CAPS = "CHECKING…"

/**
 * Installed-ness comes from the package, not from the bind. A failed bind can mean "not installed",
 * "permission refused", or "service wedged", and only the first of those may say INSTALL TWEAKER.
 *
 * A null [installed] means presence has not been read yet (the read is a Binder call and happens off the
 * UI path). That resolves to UNAVAILABLE, never NOT_INSTALLED: not having looked is not evidence of
 * absence, and telling a player to install what they already have is the one thing this row must not do.
 */
fun tweakerUtilityAvailability(
    installed: Boolean?,
    capabilities: TweakerCapabilities?,
    includeDiagnosticProfiles: Boolean = false,
    discovered: TweakerDiscoveredCeiling? = null,
    capabilitiesUnknown: Boolean = false,
): TweakerUtilityAvailability {
    // **The daemon's answer is consulted first and only its BEST answer wins outright.** The certified
    // path offers named profiles, fan curves and a settlement UI; discovery offers one clock ceiling.
    // Where the daemon is genuinely serving, it is the richer truth and this row keeps saying so.
    if (
        // `installed != null` is carried up here so hoisting READY changes NOTHING about the existing
        // ladder: presence is read separately from capabilities, and this function has never inferred
        // one from the other. Not having looked still resolves below, exactly as it always did.
        installed != null &&
        capabilities?.status?.isReachable() == true &&
        capabilities.status != TweakerCapabilityStatus.UNSUPPORTED &&
        selectableTweakerProfileIds(capabilities, includeDiagnosticProfiles).size > 1
    ) {
        return TweakerUtilityAvailability.READY
    }

    // **A daemon that did not ANSWER is unknown, not "no".** [capabilitiesUnknown] means the last query
    // timed out with nothing to fall back on. Every branch below is a claim that tuning cannot happen
    // (the discovered row, stock-only, not installed), and silence proves none of them -- on the Nova
    // 09-30 it read as "went back to Stock, Unavailable". UNAVAILABLE is the one honest value, and the
    // standing re-arm decision reads it as TRUTH_UNKNOWN, so the watch keeps watching.
    //
    // The one exception is a ceiling the kernel shows HELD below the top of its table: the hardware is being
    // limited right now, so the row says the real clock ([TweakerDiscoveredCeiling.ceilingHeld]) rather than
    // UNAVAILABLE. The standing re-arm still reads it as unknown, not as a no (see `standingRearmDecision`).
    if (
        capabilitiesUnknown && capabilities == null && installed == true &&
        discovered?.ceilingHeld != true
    ) {
        return TweakerUtilityAvailability.UNAVAILABLE
    }

    // **Every remaining branch is a claim that tuning cannot happen, and the kernel can falsify all of
    // them.** `PServerHost` binds the VENDOR's `PServerBinder` out of `ServiceManager`; it has no
    // dependency on the Tweaker package, so "not installed" does not imply "cannot act" either. A row
    // reading UNAVAILABLE — or INSTALL TWEAKER — while the kernel publishes a readable ceiling contradicts the device-visible contract: the rail and the TWEAKER surface must agree on one screen. What the row
    // cannot do is said by the supporting line, not by the value.
    if (discovered?.canRead == true) return TweakerUtilityAvailability.DISCOVERED_CEILING

    return when {
        // Not having looked is not evidence of absence — see this function's doc.
        installed == null -> TweakerUtilityAvailability.UNAVAILABLE
        !installed -> TweakerUtilityAvailability.NOT_INSTALLED
        capabilities?.status == TweakerCapabilityStatus.UNSUPPORTED ->
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY
        capabilities?.status?.isReachable() == true ->
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY
        // R3's reserved meaning: the device genuinely publishes no tables and nothing answers.
        else -> TweakerUtilityAvailability.UNAVAILABLE
    }
}

/** Everything the one capped Utility row needs to render and to answer a left/right/confirm press. */
data class TweakerUtilityRowState(
    val availability: TweakerUtilityAvailability,
    val selectedProfileId: String = TWEAKER_STOCK_PROFILE_ID,
    val selectableProfileIds: List<String> = listOf(TWEAKER_STOCK_PROFILE_ID),
    /** Debug-only owner qualification is active; this is never product-certification authority. */
    val qualificationTestMode: Boolean = false,
    /** Optional complete readback from an active Mimir-owned fan session. */
    val fanControl: TweakerFanControlReadout? = null,
    /** Set only when a thermal derate has actually reduced THIS session's ceilings. */
    val thermalDerate: TweakerThermalDerate? = null,
    /** What the kernel publishes, when this row is speaking for the discovered path. */
    val discovered: TweakerDiscoveredCeiling? = null,
    /**
     * The daemon answered with real capabilities, but its session is mid-restore or in recovery scope,
     * so nothing may be selected until that finishes.
     *
     * Kept as a fact about MOVEMENT rather than a new availability: the capability truth is genuine and
     * the profile list is the daemon's own, which is why they are shown. What is not true while this is
     * set is that a press would do anything -- see [canStep].
     */
    val heldByUnfinishedRestore: Boolean = false,

    /**
     * The row still holds the last capabilities the daemon gave, but the last attempt to ask it again got no
     * answer (a bind that failed on a pair that can bind, a query that timed out, a settlement that could not
     * take the token). The profile list is real and stays reachable, so [canStep] is unchanged, but nothing here
     * may SAY a profile is in force: the daemon may have restarted onto Stock since. The value reads
     * [TWEAKER_UTILITY_CHECKING_CAPS] until the daemon answers.
     */
    val answerPending: Boolean = false,
) {
    /**
     * A stored pick the row no longer offers is not honored at launch (preparation restores stock
     * for it), so the row must not display it either — showing a profile the device will not
     * actually apply is exactly the fake mode change this row exists to avoid.
     */
    val effectiveProfileId: String
        get() = if (
            availability == TweakerUtilityAvailability.READY &&
            selectedProfileId in selectableProfileIds
        ) {
            selectedProfileId
        } else {
            TWEAKER_STOCK_PROFILE_ID
        }

    /**
     * True only when there is an alternative to move to.
     *
     * The discovered branch needs BOTH halves — a table with more than one entry and a write host —
     * because read and write are independent facts on this path. A device that publishes tables with no
     * PServer shows its ceiling and cannot step it, and the supporting line says which half is missing.
     */
    val canStep: Boolean
        get() = when {
            // A held session refuses every selection at the daemon (SESSION_NOT_SETTLED). Offering the
            // step anyway is how a press becomes "TWEAKER IS BUSY" with nothing on screen to explain it.
            heldByUnfinishedRestore -> false
            else -> when (availability) {
            TweakerUtilityAvailability.READY -> selectableProfileIds.size > 1
            TweakerUtilityAvailability.DISCOVERED_CEILING ->
                discovered?.canWrite == true && discovered.steps.size > 1
            else -> false
            }
        }

    /** The single capped value the row shows: INSTALL TWEAKER · UNAVAILABLE · STOCK · a profile. */
    val valueLabelCaps: String
        get() = when (availability) {
            TweakerUtilityAvailability.NOT_INSTALLED -> TWEAKER_UTILITY_INSTALL_CAPS
            TweakerUtilityAvailability.UNAVAILABLE -> TWEAKER_UTILITY_UNAVAILABLE_CAPS
            TweakerUtilityAvailability.RESPONDING_STOCK_ONLY -> TWEAKER_UTILITY_STOCK_ONLY_CAPS
            // the live ceiling, with no status word in front of it. The row
            // names a real number the kernel is holding, which is the whole point of the branch.
            TweakerUtilityAvailability.DISCOVERED_CEILING ->
                discovered?.ceilingLabelCaps ?: TWEAKER_UTILITY_UNAVAILABLE_CAPS
            TweakerUtilityAvailability.READY -> if (answerPending) TWEAKER_UTILITY_CHECKING_CAPS else buildString {
                append(tweakerProfileDisplayCaps(effectiveProfileId))
                // A derate is a safety fact and is never hidden by a hold: both can be true at once,
                // and the reduced ceilings keep their word in front.
                if (deratedNow) append(" · ").append(TWEAKER_UTILITY_DERATED_CAPS)
                if (heldByUnfinishedRestore) append(" · ").append(TWEAKER_UTILITY_HELD_CAPS)
            }
        }

    /**
     * Is the profile this row is naming currently running on reduced ceilings?
     *
     * **`effectiveProfileId` is deliberately NOT changed by a derate.** The session really is still
     * `performance` — the daemon's own journal keeps the profile id nominal through a derate, and
     * every consumer of this id (launch policy, the stepper, the standing-profile rearm, the
     * displayed-truth log) depends on that. The derate is an ADDITIVE fact about the ceilings, not
     * a different profile, and modelling it as one would have a blast radius across all of them.
     *
     * The profile-id match is the safety: a derate recorded against another profile can never
     * decorate this row. the presence of the block is the
     * signal, and it is checked against the thing it claims to describe.
     */
    val deratedNow: Boolean
        get() = availability == TweakerUtilityAvailability.READY &&
            thermalDerate?.profileId == effectiveProfileId
}

/**
 * What a left/right/confirm press on the row actually did. Every branch is reportable: the press is never
 * silently swallowed, and only [Changed] may claim the mode moved.
 */
sealed class TweakerUtilityStepOutcome {
    /** A profile was genuinely selected. */
    data class Changed(val profileId: String) : TweakerUtilityStepOutcome()

    /** Nothing to talk to — the package is absent. */
    data object NotInstalled : TweakerUtilityStepOutcome()

    /** Installed but unreachable/unsupported right now. */
    data object Unavailable : TweakerUtilityStepOutcome()

    /** The service answered, but it has no supported controls for this hardware. */
    data object StockOnly : TweakerUtilityStepOutcome()

    /** Reachable, but this device offers nothing beyond stock, so there is nowhere to step. */
    data object NoOtherProfiles : TweakerUtilityStepOutcome()

    /**
     * A discovered clock ceiling was selected.
     *
     * [releasesHold] marks the TOP of the table, where applying must **withdraw** Mimir's hold rather
     * than write the maximum frequency as a cap. Verified against `MimirTunerWrite`, not assumed:
     * `requestCeiling` calls `captureOnce` and leaves `isHolding()` true whatever value it writes, so a
     * ceiling pinned at maximum keeps the device under a hold nobody can see. The rail has one action
     * seat and no room for a separate restore, so the top of the scale IS the way back: the row has no
     * other control that withdraws the hold.
     */
    data class CeilingChanged(
        val khz: Int,
        val labelCaps: String,
        val releasesHold: Boolean,
    ) : TweakerUtilityStepOutcome()

    /** The kernel publishes tables but no privileged host is present, so the row can read and not write. */
    data object CeilingReadOnly : TweakerUtilityStepOutcome()

    /** Already at the end of the table in the direction pressed. */
    data object CeilingAtLimit : TweakerUtilityStepOutcome()
}

/**
 * The ONE decision for a row press. The overlay dispatcher does not pre-judge whether a press "counts" —
 * it routes every left/right/confirm here through the coordinator, and this decides.
 */
fun TweakerUtilityRowState.stepOutcome(direction: Int): TweakerUtilityStepOutcome = when (availability) {
    TweakerUtilityAvailability.NOT_INSTALLED -> TweakerUtilityStepOutcome.NotInstalled
    TweakerUtilityAvailability.UNAVAILABLE -> TweakerUtilityStepOutcome.Unavailable
    TweakerUtilityAvailability.RESPONDING_STOCK_ONLY -> TweakerUtilityStepOutcome.StockOnly
    TweakerUtilityAvailability.DISCOVERED_CEILING -> {
        val ceiling = discovered
        when {
            ceiling == null || !ceiling.canRead -> TweakerUtilityStepOutcome.Unavailable
            !ceiling.canWrite -> TweakerUtilityStepOutcome.CeilingReadOnly
            else -> {
                val next = steppedTweakerCeilingKhz(ceiling.currentKhz, direction, ceiling.steps)
                val step = ceiling.steps.firstOrNull { it.khz == next }
                when {
                    step == null || next == ceiling.currentKhz -> TweakerUtilityStepOutcome.CeilingAtLimit
                    else -> TweakerUtilityStepOutcome.CeilingChanged(
                        khz = step.khz,
                        labelCaps = step.labelCaps,
                        releasesHold = step.khz == ceiling.top?.khz,
                    )
                }
            }
        }
    }
    TweakerUtilityAvailability.READY ->
        if (!canStep) {
            TweakerUtilityStepOutcome.NoOtherProfiles
        } else {
            val next = steppedTweakerProfileId(effectiveProfileId, direction, selectableProfileIds)
            if (next == effectiveProfileId) {
                TweakerUtilityStepOutcome.NoOtherProfiles
            } else {
                TweakerUtilityStepOutcome.Changed(profileId = next)
            }
        }
}

/** Concise capped confirmation grammar for the Utility row's own toast. */
const val TWEAKER_UTILITY_TOAST_PREFIX = "TWEAKER"

/**
 * One short capped line per press. A no-op says why it was a no-op instead of showing a profile name the
 * device never entered.
 */
fun TweakerUtilityStepOutcome.utilityToastCaps(): String = when (this) {
    is TweakerUtilityStepOutcome.Changed ->
        "$TWEAKER_UTILITY_TOAST_PREFIX · ${tweakerProfileDisplayCaps(profileId)}"
    TweakerUtilityStepOutcome.NotInstalled -> "TWEAKER NOT INSTALLED"
    TweakerUtilityStepOutcome.Unavailable -> "TWEAKER UNAVAILABLE"
    TweakerUtilityStepOutcome.StockOnly -> "TWEAKER CONNECTED · STOCK ONLY"
    TweakerUtilityStepOutcome.NoOtherProfiles -> "NO OTHER PROFILES"
    // The number, not a verdict about it. Releasing the hold says what it did in the product's own
    // restore language rather than naming a clock the device is no longer being held at.
    is TweakerUtilityStepOutcome.CeilingChanged ->
        if (releasesHold) {
            "$TWEAKER_UTILITY_TOAST_PREFIX · CEILING RELEASED"
        } else {
            "$TWEAKER_UTILITY_TOAST_PREFIX · $labelCaps"
        }
    TweakerUtilityStepOutcome.CeilingReadOnly -> "READ ONLY ON THIS DEVICE"
    TweakerUtilityStepOutcome.CeilingAtLimit -> "NO FURTHER STEP"
}
