package com.gruning.mimir.rewrite

import android.content.Context

internal data class IdentityContainerCapabilityResult(
    val changed: Boolean,
    val reopened: Int,
)

internal suspend fun reconcileIdentityContainerCapability(
    storedStamp: String?,
    currentStamp: String,
    resetContainerIdentity: suspend (storedStamp: String?) -> Int,
    persistStamp: (String) -> Boolean,
): IdentityContainerCapabilityResult {
    require(currentStamp.isNotBlank()) { "container capability stamp must not be blank" }
    if (storedStamp == currentStamp) {
        return IdentityContainerCapabilityResult(changed = false, reopened = 0)
    }
    // The reset sees which capability era the rows were stamped under, so a one-fact transition can
    // replay narrowly while unknown/older stamps keep the full container closure.
    val reopened = resetContainerIdentity(storedStamp)
    check(persistStamp(currentStamp)) { "failed to persist container capability stamp" }
    return IdentityContainerCapabilityResult(changed = true, reopened = reopened)
}

/**
 * Clear → durable enqueue → stamp latch for A1C readers. A failed enqueue or preference commit leaves the old
 * stamp visible so process restart repeats the idempotent narrow reset.
 */
internal object IdentityContainerCapabilityLatch {
    private const val PreferencesName = "mimir_rewrite_identity_container"
    private const val StampKey = "athena_container_capability_v1"

    suspend fun reconcile(
        context: Context,
        currentStamp: String,
        resetContainerIdentity: suspend (storedStamp: String?) -> Int,
    ): IdentityContainerCapabilityResult {
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        return reconcileIdentityContainerCapability(
            storedStamp = preferences.getString(StampKey, null),
            currentStamp = currentStamp,
            resetContainerIdentity = resetContainerIdentity,
            persistStamp = { stamp -> preferences.edit().putString(StampKey, stamp).commit() },
        )
    }
}
