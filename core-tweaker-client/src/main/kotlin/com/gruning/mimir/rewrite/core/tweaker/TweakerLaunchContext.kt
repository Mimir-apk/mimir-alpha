package com.gruning.mimir.rewrite.core.tweaker

const val TWEAKER_STOCK_PROFILE_ID = "stock"

/** User selection facts needed to decide whether an ordinary launch may surface feedback. */
data class TweakerLaunchSelectionContext(
    val selectedProfileId: String,
    val certifiedProfileIds: Set<String> = emptySet(),
    val tweakerReachable: Boolean = true,
) {
    val isStockSelection: Boolean
        get() = selectedProfileId == TWEAKER_STOCK_PROFILE_ID

    /** Utility dock only offers stock plus certified profiles; this is an intentional non-stock pick. */
    val isIntentionalNonStockCertifiedSelection: Boolean
        get() = !isStockSelection && selectedProfileId in certifiedProfileIds
}

fun TweakerLaunchSelectionContext.allowsLaunchWarningFor(result: TweakerPreparationResult): Boolean {
    if (!isIntentionalNonStockCertifiedSelection) return false
    return when (result) {
        TweakerPreparationResult.Unavailable -> true
        // A skip reports on the OPERATION, and preflight is read-only -- it is "never authoritative
        // evidence that a launch-time session is active" by this file's own rule. Its `reason` is an
        // internal string, so interpolating it put things like "Tweaker owner is still settling" in
        // front of a player, where "owner" means the transport owner. Launch says which profile is
        // ACTIVE, or says nothing; the post-dispatch confirmation is the only thing that speaks.
        is TweakerPreparationResult.Skipped,
        TweakerPreparationResult.StockNoOp,
        is TweakerPreparationResult.Applied,
        is TweakerPreparationResult.Scheduled,
        -> false
    }
}

fun formatTweakerLaunchReadyLine(gameTitle: String): String =
    "READY FOR ${gameTitle.trim()}."

internal fun ensureLaunchContinuesWithoutTuningCopy(reason: String): String =
    if (reason.contains("launch continues without tuning", ignoreCase = true)) {
        reason
    } else {
        "$reason — launch continues without tuning"
    }

fun TweakerPreparationResult.launchWarning(context: TweakerLaunchSelectionContext): String? {
    if (!context.allowsLaunchWarningFor(this)) return null
    return when (this) {
        TweakerPreparationResult.Unavailable -> "Tweaker unavailable — launch continues without tuning"
        // Skipped is deliberately silent -- see allowsLaunchWarningFor. Its reason is internal.
        else -> null
    }
}

/**
 * Copy for a read-only pre-dispatch admission that never settled. C5E permits no apply/restore/service
 * start in this phase on any API version, so we can state that no new tuning session was started without
 * making a broader claim about pre-existing service or hardware state.
 */
fun tweakerPreDispatchDegradedWarning(
    context: TweakerLaunchSelectionContext,
    timedOut: Boolean,
): String? {
    if (!context.isIntentionalNonStockCertifiedSelection) return null
    return if (timedOut) {
        "Tweaker didn't respond — launch continues; no new tuning session started"
    } else {
        "Tweaker preparation failed — launch continues; no new tuning session started"
    }
}

fun TweakerPreparationResult.toLaunchOverlayOrNull(
    context: TweakerLaunchSelectionContext,
    @Suppress("UNUSED_PARAMETER") gameTitle: String,
): TweakerLaunchOverlayMessage? = when (this) {
    is TweakerPreparationResult.Applied,
    is TweakerPreparationResult.Scheduled,
    -> null
    else -> launchWarning(context)?.let { TweakerLaunchOverlayMessage.Warning(it) }
}
