package com.gruning.mimir.rewrite.core.tweaker

/** A future per-game override. INHERIT is intentionally mutation-free. */
enum class PerGameTweakerSelection(
    val profileId: String?,
) {
    INHERIT(null),
    POWER_SAVING("quiet"),
    BALANCED("balanced"),
    MAX("performance"),
}

/**
 * Pure admission result for a per-game profile request. This contract neither binds the helper nor
 * mutates hardware; the existing API-v4 owner/journal/Guardian path remains the only apply path.
 */
sealed interface PerGameTweakerDecision {
    data object NoMutation : PerGameTweakerDecision

    data class ScheduleV4(
        val profileId: String,
        val workloadPackageName: String,
    ) : PerGameTweakerDecision

    data class BlockedBySystemProfile(
        val requestedProfileId: String,
        val activeSystemProfileId: String,
    ) : PerGameTweakerDecision

    data class Rejected(val reason: Reason) : PerGameTweakerDecision {
        enum class Reason {
            PACKAGE_UNRESOLVED,
            PROFILE_NOT_CERTIFIED,
            SESSION_BUSY,
            SESSION_UNCERTAIN,
            TWEAKER_UNAVAILABLE,
        }
    }
}

/**
 * Resolves a request against already-parsed, authoritative service truth.
 *
 * A system-wide profile always wins: selecting the same profile is a no-op and selecting a
 * different profile is blocked. Only exact Stock truth can schedule an API-v4 launch session.
 */
fun decidePerGameTweakerLaunch(
    selection: PerGameTweakerSelection,
    workloadPackageName: String?,
    capabilities: TweakerCapabilities?,
    session: TweakerV4SessionAssessment?,
    tweakerReachable: Boolean = true,
): PerGameTweakerDecision {
    if (selection == PerGameTweakerSelection.INHERIT) {
        return decideInheritedTweakerLaunch(session)
    }

    val packageName = workloadPackageName
        ?.trim()
        ?.takeIf(::isValidTweakerWorkloadPackageName)
        ?: return PerGameTweakerDecision.Rejected(
            PerGameTweakerDecision.Rejected.Reason.PACKAGE_UNRESOLVED,
        )
    if (!tweakerReachable || capabilities == null || !capabilities.status.isReachable()) {
        return PerGameTweakerDecision.Rejected(
            PerGameTweakerDecision.Rejected.Reason.TWEAKER_UNAVAILABLE,
        )
    }

    // A per-game launch is an AUTHORISATION, not an offer, and it requires PRODUCTION posture. This
    // used to arrive as a side effect of `selectableTweakerProfileIds` consulting
    // `allowsProductTunableProfiles`, which also demanded a signed manifest. The manifest is no longer required on the OFFER side; the posture requirement was never the manifest and
    // has to be stated here rather than lost with it. A LAB daemon has its own explicit route
    // (`allowsLabOwnerValidation` + `diagnosticProfileIds`) and must not reach hardware through this
    // one — pinned by `non-production capabilities cannot authorize a per-game profile`.
    if (capabilities.posture != TweakerPosture.PRODUCTION) {
        return PerGameTweakerDecision.Rejected(
            PerGameTweakerDecision.Rejected.Reason.PROFILE_NOT_CERTIFIED,
        )
    }

    val requestedProfileId = checkNotNull(selection.profileId)
    if (requestedProfileId !in selectableTweakerProfileIds(capabilities)) {
        return PerGameTweakerDecision.Rejected(
            PerGameTweakerDecision.Rejected.Reason.PROFILE_NOT_CERTIFIED,
        )
    }

    return when (session) {
        is TweakerV4SessionAssessment.Stock -> PerGameTweakerDecision.ScheduleV4(
            profileId = requestedProfileId,
            workloadPackageName = packageName,
        )
        is TweakerV4SessionAssessment.Blocked -> {
            val truth = session.truth
            if (
                truth.state == TweakerV4SessionState.ACTIVE &&
                truth.scope == TweakerSessionScope.SYSTEM &&
                truth.activeProfileId in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS
            ) {
                if (truth.activeProfileId == requestedProfileId) {
                    PerGameTweakerDecision.NoMutation
                } else {
                    PerGameTweakerDecision.BlockedBySystemProfile(
                        requestedProfileId = requestedProfileId,
                        activeSystemProfileId = checkNotNull(truth.activeProfileId),
                    )
                }
            } else {
                PerGameTweakerDecision.Rejected(
                    PerGameTweakerDecision.Rejected.Reason.SESSION_BUSY,
                )
            }
        }
        is TweakerV4SessionAssessment.Uncertain,
        null,
        -> PerGameTweakerDecision.Rejected(
            PerGameTweakerDecision.Rejected.Reason.SESSION_UNCERTAIN,
        )
    }
}

private fun decideInheritedTweakerLaunch(
    session: TweakerV4SessionAssessment?,
): PerGameTweakerDecision = when (session) {
    is TweakerV4SessionAssessment.Stock -> PerGameTweakerDecision.NoMutation
    is TweakerV4SessionAssessment.Blocked -> {
        val truth = session.truth
        if (
            truth.state == TweakerV4SessionState.ACTIVE &&
            truth.scope == TweakerSessionScope.SYSTEM &&
            truth.activeProfileId in TWEAKER_PRODUCT_QUALIFIABLE_PROFILE_IDS
        ) {
            PerGameTweakerDecision.NoMutation
        } else {
            PerGameTweakerDecision.Rejected(
                PerGameTweakerDecision.Rejected.Reason.SESSION_BUSY,
            )
        }
    }
    is TweakerV4SessionAssessment.Uncertain,
    null,
    -> PerGameTweakerDecision.Rejected(
        PerGameTweakerDecision.Rejected.Reason.SESSION_UNCERTAIN,
    )
}

private val TWEAKER_WORKLOAD_PACKAGE_NAME_PATTERN =
    Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

internal fun isValidTweakerWorkloadPackageName(packageName: String): Boolean =
    packageName.matches(TWEAKER_WORKLOAD_PACKAGE_NAME_PATTERN)
