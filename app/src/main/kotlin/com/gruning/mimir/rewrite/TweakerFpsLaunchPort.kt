package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.tweaker.TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION
import com.gruning.mimir.rewrite.core.tweaker.TweakerFpsApplyAssessment
import com.gruning.mimir.rewrite.core.tweaker.TweakerFpsApplyAttempt
import com.gruning.mimir.rewrite.core.tweaker.TweakerFpsOwner
import com.gruning.mimir.rewrite.core.tweaker.TweakerFpsQueryAssessment
import com.gruning.mimir.rewrite.core.tweaker.TweakerFpsRestoreAssessment
import com.gruning.mimir.rewrite.core.tweaker.TweakerSession
import kotlinx.coroutines.delay

internal enum class TweakerFpsLaunchPortDisposition {
    ACTIVE,
    STOCK,
    REJECTED,
    RETAINED,
    UNCERTAIN,
}

internal data class TweakerFpsLaunchPortOutcome(
    val disposition: TweakerFpsLaunchPortDisposition,
    val reason: String,
    val owner: TweakerFpsOwner? = null,
    val mutationMayBeActive: Boolean = false,
) {
    val isActive: Boolean get() = disposition == TweakerFpsLaunchPortDisposition.ACTIVE && owner != null
    val isStockConfirmed: Boolean get() = disposition == TweakerFpsLaunchPortDisposition.STOCK
    val mayBeActive: Boolean get() = mutationMayBeActive ||
        disposition == TweakerFpsLaunchPortDisposition.ACTIVE ||
        disposition == TweakerFpsLaunchPortDisposition.RETAINED
}

/** Stateless transport adapter. [TweakerV4LaunchLifecycle] owns all FPS state and settlement. */
internal interface TweakerFpsLaunchPort {
    suspend fun activate(
        packageName: String,
        targetFps: Int,
        expectedSystemProfileId: String,
        profileOwnerActive: Boolean,
        bindTimeoutMs: Long,
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerFpsLaunchPortOutcome

    suspend fun settle(
        packageName: String,
        expectedOwner: TweakerFpsOwner?,
        bindTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerFpsLaunchPortOutcome

    suspend fun releaseTransport()
}

internal object DisabledTweakerFpsLaunchPort : TweakerFpsLaunchPort {
    override suspend fun activate(
        packageName: String,
        targetFps: Int,
        expectedSystemProfileId: String,
        profileOwnerActive: Boolean,
        bindTimeoutMs: Long,
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ) = TweakerFpsLaunchPortOutcome(
        TweakerFpsLaunchPortDisposition.REJECTED,
        "Frame limiting is unavailable",
    )

    override suspend fun settle(
        packageName: String,
        expectedOwner: TweakerFpsOwner?,
        bindTimeoutMs: Long,
        operationTimeoutMs: Long,
    ) = TweakerFpsLaunchPortOutcome(TweakerFpsLaunchPortDisposition.STOCK, "Frame limit is Stock")

    override suspend fun releaseTransport() = Unit
}

internal class AndroidTweakerFpsLaunchPort(
    private val session: TweakerSession,
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
) : TweakerFpsLaunchPort {
    override suspend fun activate(
        packageName: String,
        targetFps: Int,
        expectedSystemProfileId: String,
        profileOwnerActive: Boolean,
        bindTimeoutMs: Long,
        queryTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerFpsLaunchPortOutcome {
        if (!session.isBound && !session.bindV4Owner(bindTimeoutMs)) {
            return uncertain("Tweaker service did not bind for frame limiting")
        }
        val apiVersion = session.getApiVersionAsync()
            ?.takeIf { it >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION }
            ?: return rejected("Tweaker API v7 is required for frame limiting")
        if (!profileOwnerActive) {
            val systemTruth = session.querySessionV4Async(apiVersion, queryTimeoutMs)
            if (authoritativeProfileId(systemTruth) != expectedSystemProfileId) {
                return rejected("System profile changed before frame limiting")
            }
        }
        when (session.queryFpsLimiterV7(apiVersion, packageName, operationTimeoutMs)) {
            TweakerFpsQueryAssessment.Stock -> Unit
            is TweakerFpsQueryAssessment.Active,
            TweakerFpsQueryAssessment.Recovery,
            -> return retained("Another frame-limit obligation is already active")
            is TweakerFpsQueryAssessment.Uncertain ->
                return uncertain("Frame-limit baseline is unconfirmed")
        }
        val capability = session.getFpsLimiterCapabilityV7(apiVersion, packageName, operationTimeoutMs)
            ?.takeIf { targetFps in it.frameLimitsHz }
            ?: return rejected("Selected frame limit is no longer supported")
        if (!session.beginFpsLimitSessionV7()) {
            return uncertain("Frame-limit service lifetime did not start")
        }

        repeat(FPS_ADMISSION_ATTEMPTS) { attemptIndex ->
            val attempt = session.createFpsApplyAttemptV7(
                packageName = capability.packageName,
                targetFps = targetFps,
                timeoutMs = operationTimeoutMs,
            ) ?: return rejected("Frame-limit request could not be created")
            val applied = session.setFpsLimitV7(apiVersion, attempt)
            if (applied is TweakerFpsApplyAssessment.Rejected &&
                applied.code == FPS_SERVICE_NOT_STARTED &&
                attemptIndex + 1 < FPS_ADMISSION_ATTEMPTS
            ) {
                delayMs(FPS_ADMISSION_RETRY_MS * (attemptIndex + 1L))
            } else {
                return reconcileApply(apiVersion, attempt, applied, operationTimeoutMs)
            }
        }
        return uncertain("Frame-limit admission did not settle")
    }

    override suspend fun settle(
        packageName: String,
        expectedOwner: TweakerFpsOwner?,
        bindTimeoutMs: Long,
        operationTimeoutMs: Long,
    ): TweakerFpsLaunchPortOutcome {
        if (!session.isBound && !session.bindV4Owner(bindTimeoutMs)) {
            return retained("Tweaker service did not bind for frame-limit restore", expectedOwner)
        }
        val apiVersion = session.getApiVersionAsync()
            ?.takeIf { it >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION }
            ?: return retained("Tweaker API v7 restore authority is unavailable", expectedOwner)
        val active = when (
            val query = session.queryFpsLimiterV7(apiVersion, packageName, operationTimeoutMs)
        ) {
            TweakerFpsQueryAssessment.Stock -> return stock()
            is TweakerFpsQueryAssessment.Active -> query.owner
            TweakerFpsQueryAssessment.Recovery ->
                return retained("Frame-limit recovery remains active", expectedOwner)
            is TweakerFpsQueryAssessment.Uncertain ->
                return uncertain(
                    "Frame-limit restore truth is unconfirmed",
                    expectedOwner,
                    mutationMayBeActive = true,
                )
        }
        if (expectedOwner == null || active != expectedOwner) {
            return retained("Frame-limit owner identity changed", expectedOwner)
        }
        return when (val restored = session.restoreFpsLimitV7(apiVersion, active, operationTimeoutMs)) {
            TweakerFpsRestoreAssessment.Restored -> when (
                session.queryFpsLimiterV7(apiVersion, packageName, operationTimeoutMs)
            ) {
                TweakerFpsQueryAssessment.Stock -> stock()
                else -> retained("Frame-limit restore did not settle to Stock", active)
            }
            is TweakerFpsRestoreAssessment.Retained -> retained(restored.message, active)
            is TweakerFpsRestoreAssessment.Uncertain -> uncertain(
                restored.reason,
                active,
                mutationMayBeActive = true,
            )
        }
    }

    override suspend fun releaseTransport() = session.unbindV4Owner()

    private fun reconcileApply(
        apiVersion: Int,
        attempt: TweakerFpsApplyAttempt,
        applied: TweakerFpsApplyAssessment,
        operationTimeoutMs: Long,
    ): TweakerFpsLaunchPortOutcome = when (applied) {
        is TweakerFpsApplyAssessment.Applied -> active(applied.owner)
        is TweakerFpsApplyAssessment.Rejected -> reconcileAttempt(
            apiVersion,
            attempt,
            applied.message,
            mutationMayBeActive = false,
            operationTimeoutMs = operationTimeoutMs,
        )
        is TweakerFpsApplyAssessment.Uncertain -> reconcileAttempt(
            apiVersion,
            attempt,
            applied.reason,
            mutationMayBeActive = applied.restoreDebtRetained,
            operationTimeoutMs = operationTimeoutMs,
        )
    }

    private fun reconcileAttempt(
        apiVersion: Int,
        attempt: TweakerFpsApplyAttempt,
        stockReason: String,
        mutationMayBeActive: Boolean,
        operationTimeoutMs: Long,
    ): TweakerFpsLaunchPortOutcome = when (
        val query = session.queryFpsLimiterV7(apiVersion, attempt.packageName, operationTimeoutMs)
    ) {
        TweakerFpsQueryAssessment.Stock -> stock(stockReason)
        is TweakerFpsQueryAssessment.Active -> if (
            query.owner.requestId == attempt.requestId &&
            query.owner.packageName == attempt.packageName &&
            query.owner.targetFps == attempt.targetFps
        ) {
            active(query.owner)
        } else {
            retained("Frame-limit owner identity changed")
        }
        TweakerFpsQueryAssessment.Recovery -> retained("Frame-limit recovery remains active")
        is TweakerFpsQueryAssessment.Uncertain -> uncertain(
            query.reason,
            mutationMayBeActive = mutationMayBeActive,
        )
    }

    private fun active(owner: TweakerFpsOwner) = TweakerFpsLaunchPortOutcome(
        TweakerFpsLaunchPortDisposition.ACTIVE,
        "Frame limit is active",
        owner,
    )

    private fun stock(reason: String = "Frame limit is Stock") = TweakerFpsLaunchPortOutcome(
        TweakerFpsLaunchPortDisposition.STOCK,
        reason,
    )

    private fun rejected(reason: String) = TweakerFpsLaunchPortOutcome(
        TweakerFpsLaunchPortDisposition.REJECTED,
        reason,
    )

    private fun retained(reason: String, owner: TweakerFpsOwner? = null) = TweakerFpsLaunchPortOutcome(
        TweakerFpsLaunchPortDisposition.RETAINED,
        reason,
        owner,
    )

    private fun uncertain(
        reason: String,
        owner: TweakerFpsOwner? = null,
        mutationMayBeActive: Boolean = false,
    ) = TweakerFpsLaunchPortOutcome(
        disposition = TweakerFpsLaunchPortDisposition.UNCERTAIN,
        reason = reason,
        owner = owner,
        mutationMayBeActive = mutationMayBeActive,
    )

    private companion object {
        const val FPS_ADMISSION_ATTEMPTS = 3
        const val FPS_ADMISSION_RETRY_MS = 100L
        const val FPS_SERVICE_NOT_STARTED = "FPS_SERVICE_NOT_STARTED"
    }
}
