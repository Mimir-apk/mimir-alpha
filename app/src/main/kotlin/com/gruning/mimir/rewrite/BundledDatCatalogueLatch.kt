package com.gruning.mimir.rewrite

import android.content.Context

internal data class BundledDatCatalogueReconcileResult(
    val changed: Boolean,
    val clearedUnmatched: Int,
)

/**
 * Applies a bundled-catalogue stamp at least once: reset first, persist second. A missing stored value is
 * deliberately a change because an upgrade from a pre-latch build is indistinguishable from a fresh install.
 */
internal suspend fun reconcileBundledDatCatalogue(
    storedStamp: String?,
    currentStamp: String,
    resetUnmatchedIdentity: suspend () -> Int,
    persistStamp: (String) -> Boolean,
): BundledDatCatalogueReconcileResult {
    require(currentStamp.isNotBlank()) { "bundled DAT catalogue stamp must not be blank" }
    if (storedStamp == currentStamp) {
        return BundledDatCatalogueReconcileResult(changed = false, clearedUnmatched = 0)
    }

    val cleared = resetUnmatchedIdentity()
    check(persistStamp(currentStamp)) { "failed to persist bundled DAT catalogue stamp" }
    return BundledDatCatalogueReconcileResult(changed = true, clearedUnmatched = cleared)
}

internal object BundledDatCatalogueLatch {
    private const val PreferencesName = "mimir_rewrite_dat_catalogue"
    private const val StampKey = "bundled_dat_stamp_v1"

    suspend fun reconcile(
        context: Context,
        currentStamp: String,
        resetUnmatchedIdentity: suspend () -> Int,
    ): BundledDatCatalogueReconcileResult {
        val preferences = context.applicationContext
            .getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
        return reconcileBundledDatCatalogue(
            storedStamp = preferences.getString(StampKey, null),
            currentStamp = currentStamp,
            resetUnmatchedIdentity = resetUnmatchedIdentity,
            // commit() is intentional: the stamp must not become visible before it is durably written.
            persistStamp = { stamp -> preferences.edit().putString(StampKey, stamp).commit() },
        )
    }
}
