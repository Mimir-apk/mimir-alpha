package com.gruning.mimir.rewrite

import android.content.Context

/**
 * The single production reset pair for catalogue changes. Clearing stamps without a guaranteed trailing
 * worker leaves a library inert; enqueuing without clearing leaves no rows for that worker to revisit.
 */
internal suspend fun resetUnmatchedIdentityForCatalogueChange(
    context: Context,
    clearUnmatchedIdentity: suspend () -> Int,
): Int = resetUnmatchedIdentityForCatalogueChange(
    clearUnmatchedIdentity = clearUnmatchedIdentity,
    enqueueTrailingPass = { IdentityEnrichmentWorker.enqueueAfterIdentityReset(context) },
)

internal suspend fun resetUnmatchedIdentityForCatalogueChange(
    clearUnmatchedIdentity: suspend () -> Int,
    enqueueTrailingPass: suspend () -> Unit,
): Int {
    val cleared = clearUnmatchedIdentity()
    enqueueTrailingPass()
    return cleared
}

/** A policy migration uses the same durable clear→enqueue ordering, but supplies a narrower DAO reset. */
internal suspend fun resetLegacyIdentityForAuthorityPolicyChange(
    context: Context,
    clearLegacyIdentity: suspend () -> Int,
): Int = resetLegacyIdentityForAuthorityPolicyChange(
    clearLegacyIdentity = clearLegacyIdentity,
    enqueueTrailingPass = { IdentityEnrichmentWorker.enqueueAfterIdentityReset(context) },
)

internal suspend fun resetLegacyIdentityForAuthorityPolicyChange(
    clearLegacyIdentity: suspend () -> Int,
    enqueueTrailingPass: suspend () -> Unit,
): Int {
    val cleared = clearLegacyIdentity()
    enqueueTrailingPass()
    return cleared
}

/** A reader-capability migration uses the same durable clear→enqueue ordering with its own bounded reset. */
internal suspend fun resetContainerIdentityForCapabilityChange(
    context: Context,
    clearContainerIdentity: suspend () -> Int,
): Int = resetContainerIdentityForCapabilityChange(
    clearContainerIdentity = clearContainerIdentity,
    enqueueTrailingPass = { IdentityEnrichmentWorker.enqueueAfterIdentityReset(context) },
)

internal suspend fun resetContainerIdentityForCapabilityChange(
    clearContainerIdentity: suspend () -> Int,
    enqueueTrailingPass: suspend () -> Unit,
): Int {
    val cleared = clearContainerIdentity()
    enqueueTrailingPass()
    return cleared
}
