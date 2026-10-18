package com.gruning.mimir.rewrite.core.tweaker

import android.os.Bundle

const val TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION = 7

data class TweakerFpsCapability(
    val packageName: String,
    val frameLimitsHz: List<Int>,
)

data class TweakerFpsOwner(
    val requestId: String,
    val generation: Long,
    val transactionId: String,
    val packageName: String,
    val targetFps: Int,
)

class TweakerFpsApplyAttempt internal constructor(
    val requestId: String,
    val requestDeadlineElapsedRealtimeMs: Long,
    val packageName: String,
    val targetFps: Int,
) {
    init {
        require(requestId.matches(FPS_REQUEST_ID))
        require(requestDeadlineElapsedRealtimeMs > 0L)
        require(packageName.matches(FPS_PACKAGE_NAME))
        require(targetFps in FPS_FRAME_LIMITS)
    }
}

sealed interface TweakerFpsApplyAssessment {
    data class Applied(val owner: TweakerFpsOwner) : TweakerFpsApplyAssessment
    data class Rejected(val code: String, val message: String) : TweakerFpsApplyAssessment
    data class Uncertain(val reason: String, val restoreDebtRetained: Boolean) : TweakerFpsApplyAssessment
}

sealed interface TweakerFpsQueryAssessment {
    data object Stock : TweakerFpsQueryAssessment
    data class Active(val owner: TweakerFpsOwner) : TweakerFpsQueryAssessment
    data object Recovery : TweakerFpsQueryAssessment
    data class Uncertain(val reason: String) : TweakerFpsQueryAssessment
}

sealed interface TweakerFpsRestoreAssessment {
    data object Restored : TweakerFpsRestoreAssessment
    data class Retained(val code: String, val message: String) : TweakerFpsRestoreAssessment
    data class Uncertain(val reason: String) : TweakerFpsRestoreAssessment
}

fun parseTweakerFpsCapabilityBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
    expectedPackageName: String,
): TweakerFpsCapability? = runCatching {
    require(expectedApiVersion >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION)
    require(expectedPackageName.matches(FPS_PACKAGE_NAME))
    require(bundle.requiredFpsInt("apiVersion") == expectedApiVersion)
    require(bundle.requiredFpsString("packageName") == expectedPackageName)
    val success = bundle.requiredFpsBoolean("success")
    val code = bundle.requiredFpsString("code")
    if (!success) {
        require(code == "FPS_LIMIT_UNAVAILABLE")
        return null
    }
    require(code == "FPS_LIMIT_AVAILABLE")
    val limits = bundle.getIntegerArrayList("frameLimitsHz").orEmpty()
    require(limits.isNotEmpty() && limits == limits.distinct().sorted())
    require(limits.all { it in FPS_FRAME_LIMITS })
    TweakerFpsCapability(expectedPackageName, limits)
}.getOrNull()

fun parseTweakerFpsApplyBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
    attempt: TweakerFpsApplyAttempt,
    observedAtElapsedRealtimeMs: Long,
): TweakerFpsApplyAssessment = runCatching {
    require(expectedApiVersion >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION)
    require(attempt.requestDeadlineElapsedRealtimeMs > observedAtElapsedRealtimeMs)
    require(bundle.requiredFpsInt("apiVersion") == expectedApiVersion)
    require(bundle.requiredFpsString("callerRequestId") == attempt.requestId)
    require(
        bundle.requiredFpsLong("callerDeadlineElapsedRealtimeMs") ==
            attempt.requestDeadlineElapsedRealtimeMs,
    )
    val success = bundle.requiredFpsBoolean("success")
    val code = bundle.requiredFpsString("code")
    val message = bundle.requiredFpsString("message")
    val mutationMayBeActive = bundle.requiredFpsBoolean("mutationMayBeActive")
    val restoreDebtRetained = bundle.requiredFpsBoolean("restoreDebtRetained")
    if (success) {
        require(code == "FPS_LIMIT_APPLIED" && mutationMayBeActive && restoreDebtRetained)
        TweakerFpsApplyAssessment.Applied(
            parseFpsOwner(bundle).also { owner ->
                require(owner.requestId == attempt.requestId)
                require(owner.packageName == attempt.packageName)
                require(owner.targetFps == attempt.targetFps)
            },
        )
    } else if (mutationMayBeActive || restoreDebtRetained) {
        require(mutationMayBeActive && restoreDebtRetained)
        TweakerFpsApplyAssessment.Uncertain(message, restoreDebtRetained = true)
    } else {
        TweakerFpsApplyAssessment.Rejected(code, message)
    }
}.getOrElse { error ->
    TweakerFpsApplyAssessment.Uncertain(
        reason = error.message?.takeIf(String::isNotBlank) ?: "Malformed frame-limit apply truth",
        restoreDebtRetained = true,
    )
}

fun parseTweakerFpsQueryBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
    expectedPackageName: String,
): TweakerFpsQueryAssessment = runCatching {
    require(expectedApiVersion >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION)
    require(bundle.requiredFpsInt("apiVersion") == expectedApiVersion)
    require(bundle.requiredFpsBoolean("success"))
    require(bundle.requiredFpsString("requestedPackageName") == expectedPackageName)
    when (bundle.requiredFpsString("state")) {
        "STOCK" -> {
            require(!bundle.requiredFpsBoolean("mutationMayBeActive"))
            TweakerFpsQueryAssessment.Stock
        }
        "ACTIVE" -> {
            require(bundle.requiredFpsBoolean("mutationMayBeActive"))
            TweakerFpsQueryAssessment.Active(
                parseFpsOwner(bundle).also { require(it.packageName == expectedPackageName) },
            )
        }
        "RECOVERY" -> {
            require(bundle.requiredFpsBoolean("mutationMayBeActive"))
            TweakerFpsQueryAssessment.Recovery
        }
        else -> error("Unknown frame-limit state")
    }
}.getOrElse { error ->
    TweakerFpsQueryAssessment.Uncertain(
        error.message?.takeIf(String::isNotBlank) ?: "Malformed frame-limit query truth",
    )
}

fun parseTweakerFpsRestoreBundle(
    bundle: Bundle,
    expectedApiVersion: Int,
    owner: TweakerFpsOwner,
    expectedDeadlineElapsedRealtimeMs: Long,
    observedAtElapsedRealtimeMs: Long,
): TweakerFpsRestoreAssessment = runCatching {
    require(expectedApiVersion >= TWEAKER_V7_FPS_LIMITER_MIN_API_VERSION)
    require(expectedDeadlineElapsedRealtimeMs > observedAtElapsedRealtimeMs)
    require(bundle.requiredFpsInt("apiVersion") == expectedApiVersion)
    require(bundle.requiredFpsString("callerRequestId") == owner.requestId)
    require(bundle.requiredFpsLong("callerDeadlineElapsedRealtimeMs") == expectedDeadlineElapsedRealtimeMs)
    val success = bundle.requiredFpsBoolean("success")
    val code = bundle.requiredFpsString("code")
    val message = bundle.requiredFpsString("message")
    val mutationMayBeActive = bundle.requiredFpsBoolean("mutationMayBeActive")
    val restoreDebtRetained = bundle.requiredFpsBoolean("restoreDebtRetained")
    if (success) {
        require(code == "FPS_LIMIT_RESTORED" || code == "FPS_LIMIT_STOCK")
        require(!mutationMayBeActive && !restoreDebtRetained)
        TweakerFpsRestoreAssessment.Restored
    } else {
        require(mutationMayBeActive && restoreDebtRetained)
        TweakerFpsRestoreAssessment.Retained(code, message)
    }
}.getOrElse { error ->
    TweakerFpsRestoreAssessment.Uncertain(
        error.message?.takeIf(String::isNotBlank) ?: "Malformed frame-limit restore truth",
    )
}

private fun parseFpsOwner(bundle: Bundle): TweakerFpsOwner {
    val requestId = bundle.requiredFpsString("ownerRequestId")
    require(requestId.matches(FPS_REQUEST_ID))
    val generation = bundle.requiredFpsLong("generation")
    require(generation > 0L)
    val transactionId = bundle.requiredFpsString("transactionId")
    require(transactionId.matches(FPS_REQUEST_ID))
    val packageName = bundle.requiredFpsString("packageName")
    require(packageName.matches(FPS_PACKAGE_NAME))
    val targetFps = bundle.requiredFpsInt("targetFps")
    require(targetFps in FPS_FRAME_LIMITS)
    return TweakerFpsOwner(requestId, generation, transactionId, packageName, targetFps)
}

private fun Bundle.requiredFpsString(key: String): String =
    getString(key)?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Missing $key")

private fun Bundle.requiredFpsInt(key: String): Int {
    require(containsKey(key)) { "Missing $key" }
    return getInt(key)
}

private fun Bundle.requiredFpsLong(key: String): Long {
    require(containsKey(key)) { "Missing $key" }
    return getLong(key)
}

private fun Bundle.requiredFpsBoolean(key: String): Boolean {
    require(containsKey(key)) { "Missing $key" }
    return getBoolean(key)
}

private val FPS_REQUEST_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
private val FPS_PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
private val FPS_FRAME_LIMITS = setOf(30, 40, 60, 90, 120)
