package com.gruning.mimir.rewrite

import android.content.res.Resources

internal data class OnboardingSteamGridDbCopy(
    val detail: String,
    val status: String?,
    val action: String,
)

internal fun onboardingSteamGridDbCopy(res: Resources, keyPresent: Boolean): OnboardingSteamGridDbCopy =
    if (keyPresent) {
        OnboardingSteamGridDbCopy(
            // Content review 2026-08-21 (A9): was the same sentence as the METADATA screen subtitle
            // (recycled verbatim, ~120dp apart on one screen) and never said what a key actually gets you.
            // 2026-09-28: this told a player whose key reads "Added" to add one later.
            detail = res.getString(R.string.app_onboarding_metadata_steamgriddb_with_key),
            status = res.getString(R.string.app_onboarding_metadata_steamgriddb_added),
            action = res.getString(R.string.app_onboarding_metadata_steamgriddb_update_key),
        )
    } else {
        OnboardingSteamGridDbCopy(
            // Content review 2026-08-21 (A9): was the same sentence as the METADATA screen subtitle
            // (recycled verbatim, ~120dp apart on one screen) and never said what a key actually gets you.
            detail = res.getString(R.string.app_onboarding_metadata_steamgriddb_without_key),
            status = null,
            action = res.getString(R.string.app_onboarding_metadata_steamgriddb_add_key),
        )
    }

/**
 * OR-15: the RetroAchievements row's words, in the SteamGridDB row's shape. The not-connected line names what the
 * player needs, as OR-14's key step does; where the key lives is said on that step, not here, so the row stays one
 * wrapped line wider than its neighbours at most. Connected reads like Settings' own row ("Progress will be read
 * for"), and a key RetroAchievements stopped accepting says so, the way Settings does.
 */
internal data class OnboardingRetroAchievementsCopy(val detail: String, val status: String?, val action: String)

internal fun onboardingRetroAchievementsCopy(
    res: Resources,
    user: String?,
    keyRejected: Boolean,
): OnboardingRetroAchievementsCopy {
    val name = user?.trim()?.takeIf(String::isNotEmpty)
    return when {
        name == null -> OnboardingRetroAchievementsCopy(
            detail = res.getString(R.string.app_onboarding_metadata_ra_detail),
            status = null,
            action = res.getString(R.string.app_common_connect),
        )
        keyRejected -> OnboardingRetroAchievementsCopy(
            detail = res.getString(R.string.app_onboarding_metadata_ra_rejected),
            status = null,
            action = res.getString(R.string.app_common_update),
        )
        else -> OnboardingRetroAchievementsCopy(
            detail = res.getString(R.string.app_onboarding_metadata_ra_connected, name),
            status = res.getString(R.string.app_common_connected),
            action = res.getString(R.string.app_common_update),
        )
    }
}
