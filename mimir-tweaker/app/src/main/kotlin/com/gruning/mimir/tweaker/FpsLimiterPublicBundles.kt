package com.gruning.mimir.tweaker

import android.os.Bundle

internal fun fpsCapabilityBundle(
    apiVersion: Int,
    packageName: String,
    capability: GameModeFpsCapability?,
): Bundle = Bundle().apply {
    putInt("apiVersion", apiVersion)
    putBoolean("success", capability != null)
    putString("code", if (capability != null) "FPS_LIMIT_AVAILABLE" else "FPS_LIMIT_UNAVAILABLE")
    putString(
        "message",
        if (capability != null) "Frame limits are available for this game." else
            "Frame limits are not available for this game right now.",
    )
    putString("packageName", packageName)
    putIntegerArrayList("frameLimitsHz", ArrayList(capability?.frameLimitsHz.orEmpty()))
}

internal fun fpsApplyBundle(
    apiVersion: Int,
    callerRequestId: String,
    callerDeadlineElapsedRealtimeMs: Long,
    result: FpsLimiterApplyResult,
): Bundle = Bundle().apply {
    putInt("apiVersion", apiVersion)
    putString("callerRequestId", callerRequestId)
    putLong("callerDeadlineElapsedRealtimeMs", callerDeadlineElapsedRealtimeMs)
    when (result) {
        is FpsLimiterApplyResult.Applied -> {
            putBoolean("success", true)
            putString("code", "FPS_LIMIT_APPLIED")
            putString("message", "Frame limit applied.")
            putBoolean("mutationMayBeActive", true)
            putBoolean("restoreDebtRetained", true)
            putFpsRecord(result.record)
        }
        is FpsLimiterApplyResult.Rejected -> {
            putBoolean("success", false)
            putString("code", result.code)
            putString("message", fpsFailureMessage(result.code))
            putBoolean("mutationMayBeActive", false)
            putBoolean("restoreDebtRetained", false)
        }
        is FpsLimiterApplyResult.Uncertain -> {
            putBoolean("success", false)
            putString("code", result.code)
            putString("message", fpsFailureMessage(result.code))
            putBoolean("mutationMayBeActive", result.restoreDebtRetained)
            putBoolean("restoreDebtRetained", result.restoreDebtRetained)
        }
    }
}

internal fun fpsQueryBundle(
    apiVersion: Int,
    requestedPackageName: String,
    terminalTruth: FpsLimiterTerminalTruth,
    record: FpsLimiterJournalRecord?,
): Bundle = Bundle().apply {
    putInt("apiVersion", apiVersion)
    putBoolean("success", true)
    putString("requestedPackageName", requestedPackageName)
    when {
        record != null -> {
            putString("code", "FPS_LIMIT_ACTIVE")
            putString("message", "A frame limit is active.")
            putString("state", "ACTIVE")
            putBoolean("mutationMayBeActive", true)
            putFpsRecord(record)
        }
        terminalTruth.journalEmpty -> {
            putString("code", "FPS_LIMIT_STOCK")
            putString("message", "No frame limit is active.")
            putString("state", "STOCK")
            putBoolean("mutationMayBeActive", false)
        }
        else -> {
            putString("code", "FPS_LIMIT_RECOVERY_REQUIRED")
            putString("message", "Frame-limit recovery is pending.")
            putString("state", "RECOVERY")
            putBoolean("mutationMayBeActive", true)
        }
    }
}

internal fun fpsRestoreBundle(
    apiVersion: Int,
    callerRequestId: String,
    callerDeadlineElapsedRealtimeMs: Long,
    result: FpsLimiterRestoreResult,
): Bundle = Bundle().apply {
    putInt("apiVersion", apiVersion)
    putString("callerRequestId", callerRequestId)
    putLong("callerDeadlineElapsedRealtimeMs", callerDeadlineElapsedRealtimeMs)
    when (result) {
        FpsLimiterRestoreResult.Restored -> {
            putBoolean("success", true)
            putString("code", "FPS_LIMIT_RESTORED")
            putString("message", "Frame limit removed.")
            putBoolean("mutationMayBeActive", false)
            putBoolean("restoreDebtRetained", false)
        }
        FpsLimiterRestoreResult.NothingToRestore -> {
            putBoolean("success", true)
            putString("code", "FPS_LIMIT_STOCK")
            putString("message", "No frame limit is active.")
            putBoolean("mutationMayBeActive", false)
            putBoolean("restoreDebtRetained", false)
        }
        is FpsLimiterRestoreResult.Retained -> {
            putBoolean("success", false)
            putString("code", result.code)
            putString("message", fpsFailureMessage(result.code))
            putBoolean("mutationMayBeActive", true)
            putBoolean("restoreDebtRetained", true)
        }
    }
}

private fun Bundle.putFpsRecord(record: FpsLimiterJournalRecord) {
    putString("packageName", record.packageIdentity.packageName)
    putInt("targetFps", record.targetFps)
    putLong("generation", record.generation)
    putString("transactionId", record.transactionId)
    putString("ownerRequestId", record.requestId)
    putString("phase", record.phase.name)
}

private fun fpsFailureMessage(code: String): String = when (code) {
    "FPS_SERVICE_NOT_STARTED", "FPS_START_EXPIRED" ->
        "Frame-limit setup expired. Try again."
    "FPS_SESSION_BUSY", "FPS_OWNER_BUSY", "FPS_OWNER_REUSED" ->
        "Another frame-limit change is still active."
    "FPS_CLIENT_TOKEN_DEAD" -> "The requesting screen closed before the change completed."
    "FPS_LIMIT_UNAVAILABLE" -> "Frame limits are not available for this game right now."
    "FPS_LIMIT_UNSUPPORTED" -> "That frame limit is not supported at the current refresh rate."
    "FPS_STALE_OWNER", "FPS_GENERATION_CHANGED", "FPS_TRANSACTION_CHANGED" ->
        "The active frame-limit session changed."
    "FPS_USER_LOCKED" -> "Frame-limit recovery will continue after the device is unlocked."
    else -> "Frame-limit state could not be confirmed. Recovery remains protected."
}
