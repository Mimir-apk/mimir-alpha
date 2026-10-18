package com.gruning.mimir.rewrite.core.tweaker

/** Stock is confirmed when restore succeeded or there was nothing active to restore. */
fun isStockConfirmed(restore: TweakerApplyResult?): Boolean =
    restore != null && restore.success &&
        (restore.code == "RESTORED" || restore.code == "NO_ACTIVE_SESSION")

/** Confirmed active tuning — only this path may return [TweakerPreparationResult.Applied]. */
fun isConfirmedApply(apply: TweakerApplyResult?): Boolean =
    apply != null && apply.success && apply.applied && apply.code == "APPLIED"

fun interpretStockRestore(restore: TweakerApplyResult?): TweakerPreparationResult = when {
    isStockConfirmed(restore) -> TweakerPreparationResult.StockNoOp
    restore == null -> TweakerPreparationResult.Skipped(
        "Tweaker restore failed — stock settings could not be confirmed",
    )
    else -> TweakerPreparationResult.Skipped(
        restore.message.ifBlank { "Tweaker restore failed — stock settings could not be confirmed" },
    )
}

fun interpretTunableApply(
    profileId: String,
    apply: TweakerApplyResult?,
    restoreFallback: () -> TweakerApplyResult?,
): TweakerPreparationResult {
    if (isConfirmedApply(apply)) {
        return TweakerPreparationResult.Applied(profileId)
    }
    return when (apply?.code) {
        "DRY_RUN" -> TweakerPreparationResult.Skipped("Tweaker dry-run — hardware unchanged")
        "WRITE_GATE_DISABLED" -> TweakerPreparationResult.Skipped(
            "Tweaker write gate disabled — hardware unchanged",
        )
        // PLAN_NOT_ADMITTED is the current refusal code; CALIBRATION_REQUIRED is what a daemon
        // built before the rename still sends. Both mean the same thing, so both are accepted.
        "PLAN_NOT_ADMITTED", "CALIBRATION_REQUIRED" -> skippedTunableFailure(
            isStockConfirmed(restoreFallback()),
            certifiedFailure = "Performance profile apply did not take — stock settings restored",
            unconfirmedFailure =
                "Performance profile apply did not take — stock restore could not be confirmed",
        )
        null -> skippedTunableFailure(
            isStockConfirmed(restoreFallback()),
            certifiedFailure = "Tweaker apply failed — stock settings restored",
            unconfirmedFailure = "Tweaker apply failed — stock restore could not be confirmed",
        )
        else -> skippedTunableFailure(
            isStockConfirmed(restoreFallback()),
            certifiedFailure = apply.message.ifBlank { "Tweaker apply failed — stock settings restored" },
            unconfirmedFailure = apply.message.ifBlank { "Tweaker apply failed — stock restore could not be confirmed" },
        )
    }
}

private fun skippedTunableFailure(
    stockConfirmed: Boolean,
    certifiedFailure: String,
    unconfirmedFailure: String,
): TweakerPreparationResult.Skipped = TweakerPreparationResult.Skipped(
    if (stockConfirmed) certifiedFailure else unconfirmedFailure,
)
