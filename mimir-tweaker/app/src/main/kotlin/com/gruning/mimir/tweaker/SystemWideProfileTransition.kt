package com.gruning.mimir.tweaker

/**
 * Ordered B01 transition policy. It owns no hardware authority: every mutation still flows through
 * [TransactionCoordinator], and Stock is accepted only when the caller's authoritative verifier says so.
 */
internal object SystemWideProfileTransition {
    const val STOCK_PROFILE_ID = "stock"
    /** Durable nonblank journal identity for a profile whose scope is the whole device. */
    const val TARGET_PACKAGE_ID = "com.gruning.mimir.tweaker.system"

    fun execute(
        requestedProfileId: String,
        activeMutation: Boolean,
        activeSystemProfileId: String?,
        restore: () -> TransactionResult,
        verifiedStock: () -> Boolean,
        onVerifiedStock: () -> Unit,
        onUnverifiedRestore: (TransactionResult) -> Unit,
        apply: () -> TransactionResult,
    ): TransactionResult {
        if (activeMutation && activeSystemProfileId == requestedProfileId) {
            return TransactionResult(
                success = true,
                applied = false,
                restored = false,
                code = "SYSTEM_PROFILE_UNCHANGED",
                message = "The requested system-wide profile is already active; zero commands executed.",
            )
        }

        if (activeMutation) {
            val restored = restore()
            if (!verifiedStock()) {
                onUnverifiedRestore(restored)
                return restored.copy(
                    success = false,
                    applied = false,
                    restored = false,
                    code = "SYSTEM_STOCK_NOT_VERIFIED",
                    message = "The active profile could not be verified at Stock; no replacement was applied.",
                )
            }
            onVerifiedStock()
            if (requestedProfileId == STOCK_PROFILE_ID) return restored
        } else if (requestedProfileId == STOCK_PROFILE_ID) {
            return TransactionResult(
                success = true,
                applied = false,
                restored = false,
                code = "SYSTEM_STOCK_UNCHANGED",
                message = "System-wide tuning is already off; zero commands executed.",
            )
        }

        val applied = apply()
        if (applied.applied) return applied
        if (verifiedStock()) return applied

        val rollback = restore()
        if (verifiedStock()) {
            onVerifiedStock()
            return applied.copy(restored = true)
        }
        onUnverifiedRestore(rollback)
        return rollback.copy(
            success = false,
            applied = false,
            restored = false,
            code = "SYSTEM_STOCK_NOT_VERIFIED",
            message = "Profile selection failed and Stock could not be verified; recovery remains armed.",
        )
    }
}
