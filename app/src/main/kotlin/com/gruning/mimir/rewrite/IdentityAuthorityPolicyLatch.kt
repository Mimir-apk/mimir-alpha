package com.gruning.mimir.rewrite

import android.content.Context

internal data class IdentityAuthorityPolicyReconcileResult(
    val changed: Boolean,
    val clearedLegacy: Int,
)

/**
 * Replays durable identity when Athena's evidence-authority constitution changes.
 *
 * Reset and trailing WorkManager insertion complete before the new stamp is committed. Any failure leaves the
 * old stamp visible so the next process start retries; repeating the bounded DAO update is idempotent.
 */
internal suspend fun reconcileIdentityAuthorityPolicy(
    storedStamp: String?,
    currentStamp: String,
    resetLegacyIdentity: suspend () -> Int,
    persistStamp: (String) -> Boolean,
): IdentityAuthorityPolicyReconcileResult {
    require(currentStamp.isNotBlank()) { "identity authority policy stamp must not be blank" }
    if (storedStamp == currentStamp) {
        return IdentityAuthorityPolicyReconcileResult(changed = false, clearedLegacy = 0)
    }

    val cleared = resetLegacyIdentity()
    check(persistStamp(currentStamp)) { "failed to persist identity authority policy stamp" }
    return IdentityAuthorityPolicyReconcileResult(changed = true, clearedLegacy = cleared)
}

internal object IdentityAuthorityPolicyLatch {
    private const val PreferencesName = "mimir_rewrite_identity_authority"
    private const val StampKey = "athena_identity_authority_policy_v1"

    suspend fun reconcile(
        context: Context,
        currentStamp: String,
        resetLegacyIdentity: suspend () -> Int,
    ): IdentityAuthorityPolicyReconcileResult {
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        return reconcileIdentityAuthorityPolicy(
            storedStamp = preferences.getString(StampKey, null),
            currentStamp = currentStamp,
            resetLegacyIdentity = resetLegacyIdentity,
            persistStamp = { stamp -> preferences.edit().putString(StampKey, stamp).commit() },
        )
    }
}
