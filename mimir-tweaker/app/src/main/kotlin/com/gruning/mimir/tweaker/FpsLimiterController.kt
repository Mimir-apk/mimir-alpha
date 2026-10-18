package com.gruning.mimir.tweaker

import java.util.UUID

internal fun interface GameModeFpsPackageIdentityPort {
    fun exactGame(packageName: String): GameModeFpsPackageIdentity?
}

internal interface GameModeFpsControlPort {
    val available: Boolean
    fun observe(packageName: String): GameModeFpsReadback?
    fun executeChecked(command: String): CheckedCommandResult
}

internal fun interface ActiveRefreshRatePort {
    fun activeRefreshHz(): Int?
}

internal data class GameModeFpsCapability(
    val packageIdentity: GameModeFpsPackageIdentity,
    val frameLimitsHz: List<Int>,
) {
    init {
        require(frameLimitsHz.isNotEmpty() && frameLimitsHz == frameLimitsHz.distinct().sorted())
    }
}

internal data class FpsLimiterRequest(
    val requestId: String,
    val ownerUid: Int,
    val packageName: String,
    val targetFps: Int,
    val deadlineElapsedRealtimeMs: Long,
)

internal sealed interface FpsLimiterApplyResult {
    data class Applied(val record: FpsLimiterJournalRecord) : FpsLimiterApplyResult
    data class Rejected(val code: String) : FpsLimiterApplyResult
    data class Uncertain(val code: String, val restoreDebtRetained: Boolean) : FpsLimiterApplyResult
}

internal sealed interface FpsLimiterRestoreResult {
    data object Restored : FpsLimiterRestoreResult
    data object NothingToRestore : FpsLimiterRestoreResult
    data class Retained(val code: String) : FpsLimiterRestoreResult
}

/**
 * One reversible GameManager intervention. This controller never shares the hardware transaction
 * journal or Guardian: GameManager state is user-unlock scoped and has its own signed replay debt.
 */
internal class FpsLimiterController(
    private val journal: FpsLimiterJournal,
    private val packageIdentity: GameModeFpsPackageIdentityPort,
    private val control: GameModeFpsControlPort,
    private val refreshRate: ActiveRefreshRatePort,
    private val userUnlocked: () -> Boolean,
    private val bootIdentity: () -> String?,
    private val elapsedRealtimeMs: () -> Long,
    private val activeHardDeadlineElapsedRealtimeMs: (Long) -> Long? =
        ProductOwnerHardLeasePolicy::hardDeadlineElapsedRealtimeMs,
    private val transactionId: () -> String = { "mimir.fps.${UUID.randomUUID()}" },
    private val sleepMs: (Long) -> Unit = Thread::sleep,
) {
    @Synchronized
    fun capability(packageName: String): GameModeFpsCapability? {
        if (!userUnlocked() || !control.available || journal.load() !is FpsLimiterJournalLoad.Empty) return null
        val identity = packageIdentity.exactGame(packageName) ?: return null
        if (identity.userId != 0) return null
        val options = refreshRate.activeRefreshHz()?.let(::supportedGameModeFrameLimits).orEmpty()
        if (options.isEmpty() || options.any { GameModeFpsCommands.apply(packageName, 0, it) == null }) return null
        val baseline = stableReadback(packageName)?.takeIf(GameModeFpsReadback::isExactBaseline) ?: return null
        if (baseline.modes.availableModes.let { GAME_MODE_STANDARD !in it || GAME_MODE_CUSTOM !in it }) return null
        if (packageIdentity.exactGame(packageName) != identity) return null
        return GameModeFpsCapability(identity, options)
    }

    @Synchronized
    fun apply(request: FpsLimiterRequest): FpsLimiterApplyResult {
        val now = elapsedRealtimeMs()
        if (request.ownerUid < 0 || request.deadlineElapsedRealtimeMs <= now) {
            return FpsLimiterApplyResult.Rejected("REQUEST_EXPIRED")
        }
        val activeHardDeadline = activeHardDeadlineElapsedRealtimeMs(now)
            ?.takeIf { it > now }
            ?: return FpsLimiterApplyResult.Uncertain("FPS_HARD_LIFETIME_UNAVAILABLE", false)
        val capability = capability(request.packageName)
            ?: return FpsLimiterApplyResult.Rejected("FPS_LIMIT_UNAVAILABLE")
        if (request.targetFps !in capability.frameLimitsHz) {
            return FpsLimiterApplyResult.Rejected("FPS_LIMIT_UNSUPPORTED")
        }
        val generation = (journal.nextGeneration() as? FpsLimiterGeneration.Ok)?.value
            ?: return FpsLimiterApplyResult.Uncertain("FPS_GENERATION_UNAVAILABLE", false)
        val boot = bootIdentity()?.trim()?.takeIf(FPS_LIMITER_RUNTIME_ID::matches)
            ?: return FpsLimiterApplyResult.Uncertain("FPS_BOOT_ID_UNAVAILABLE", false)
        val tx = transactionId().takeIf(FPS_LIMITER_RUNTIME_ID::matches)
            ?: return FpsLimiterApplyResult.Uncertain("FPS_TRANSACTION_ID_INVALID", false)
        val prepared = runCatching {
            FpsLimiterJournalRecord(
                generation = generation,
                requestId = request.requestId,
                transactionId = tx,
                ownerUid = request.ownerUid,
                packageIdentity = capability.packageIdentity,
                targetFps = request.targetFps,
                phase = FpsLimiterJournalPhase.PREPARED,
                createdAtElapsedRealtimeMs = now,
                // The caller deadline admits this RPC only. It must never control how long a
                // mutation may remain active after the call returns.
                hardDeadlineElapsedRealtimeMs = activeHardDeadline,
                bootIdentity = boot,
            )
        }.getOrNull() ?: return FpsLimiterApplyResult.Rejected("FPS_REQUEST_INVALID")
        if (packageIdentity.exactGame(request.packageName) != prepared.packageIdentity) {
            return FpsLimiterApplyResult.Rejected("FPS_PACKAGE_CHANGED")
        }
        if (!journal.persist(prepared)) {
            return FpsLimiterApplyResult.Uncertain("FPS_PREPARED_NOT_DURABLE", false)
        }

        val command = GameModeFpsCommands.apply(request.packageName, 0, request.targetFps)
            ?: return retainFailure(prepared, "FPS_COMMAND_UNAVAILABLE")
        val execution = control.executeChecked(command)
        val readback = boundedReadback(request.packageName)
        return when {
            readback?.isExactTarget(request.targetFps) == true -> {
                val applied = prepared.copy(phase = FpsLimiterJournalPhase.APPLIED)
                if (journal.persist(applied)) {
                    FpsLimiterApplyResult.Applied(applied)
                } else {
                    FpsLimiterApplyResult.Uncertain("FPS_APPLIED_NOT_DURABLE", true)
                }
            }
            readback?.isExactBaseline() == true -> {
                if (!journal.clear(prepared.transactionId)) {
                    FpsLimiterApplyResult.Uncertain("FPS_BASELINE_CLEAR_FAILED", true)
                } else {
                    FpsLimiterApplyResult.Rejected(
                        if (execution.status == CheckedCommandStatus.CONFIRMED) {
                            "FPS_APPLY_NOT_OBSERVED"
                        } else {
                            "FPS_APPLY_REJECTED"
                        },
                    )
                }
            }
            else -> retainFailure(
                prepared,
                if (execution.status == CheckedCommandStatus.AMBIGUOUS) {
                    "FPS_APPLY_AMBIGUOUS"
                } else {
                    "FPS_APPLY_READBACK_UNCERTAIN"
                },
            )
        }
    }

    @Synchronized
    fun restore(expectedGeneration: Long? = null, expectedTransactionId: String? = null): FpsLimiterRestoreResult {
        val loaded = when (val load = journal.load()) {
            FpsLimiterJournalLoad.Empty -> return FpsLimiterRestoreResult.NothingToRestore
            is FpsLimiterJournalLoad.Corrupt -> return FpsLimiterRestoreResult.Retained("FPS_JOURNAL_CORRUPT")
            is FpsLimiterJournalLoad.Loaded -> load.record
        }
        if (expectedGeneration != null && loaded.generation != expectedGeneration) {
            return FpsLimiterRestoreResult.Retained("FPS_GENERATION_CHANGED")
        }
        if (expectedTransactionId != null && loaded.transactionId != expectedTransactionId) {
            return FpsLimiterRestoreResult.Retained("FPS_TRANSACTION_CHANGED")
        }
        val restoring = if (loaded.phase == FpsLimiterJournalPhase.RESTORING) loaded else {
            loaded.copy(phase = FpsLimiterJournalPhase.RESTORING, lastFailure = null).also {
                if (!journal.persist(it)) return FpsLimiterRestoreResult.Retained("FPS_RESTORE_NOT_DURABLE")
            }
        }
        if (!userUnlocked()) return retainRestoreFailure(restoring, "FPS_USER_LOCKED")
        if (packageIdentity.exactGame(restoring.packageIdentity.packageName) != restoring.packageIdentity) {
            return retainRestoreFailure(restoring, "FPS_PACKAGE_IDENTITY_CHANGED")
        }
        val reset = GameModeFpsCommands.reset(restoring.packageIdentity.packageName, restoring.packageIdentity.userId)
            ?: return retainRestoreFailure(restoring, "FPS_RESET_COMMAND_UNAVAILABLE")
        val standard = GameModeFpsCommands.standard(restoring.packageIdentity.packageName, restoring.packageIdentity.userId)
            ?: return retainRestoreFailure(restoring, "FPS_STANDARD_COMMAND_UNAVAILABLE")
        control.executeChecked(reset)
        control.executeChecked(standard)
        val readback = boundedReadback(restoring.packageIdentity.packageName)
        if (readback?.isExactBaseline() != true) {
            return retainRestoreFailure(restoring, "FPS_RESTORE_READBACK_UNCERTAIN")
        }
        return if (journal.clear(restoring.transactionId)) {
            FpsLimiterRestoreResult.Restored
        } else {
            FpsLimiterRestoreResult.Retained("FPS_RESTORE_CLEAR_FAILED")
        }
    }

    @Synchronized
    fun activeRecord(): FpsLimiterJournalRecord? =
        (journal.load() as? FpsLimiterJournalLoad.Loaded)?.record

    private fun stableReadback(packageName: String): GameModeFpsReadback? {
        val first = control.observe(packageName) ?: return null
        val second = control.observe(packageName) ?: return null
        return first.takeIf { it == second }
    }

    private fun boundedReadback(packageName: String): GameModeFpsReadback? {
        repeat(FPS_READBACK_ATTEMPTS) { attempt ->
            stableReadback(packageName)?.let { return it }
            if (attempt + 1 < FPS_READBACK_ATTEMPTS) sleepMs(FPS_READBACK_SETTLE_MS)
        }
        return null
    }

    private fun retainFailure(
        prepared: FpsLimiterJournalRecord,
        code: String,
    ): FpsLimiterApplyResult.Uncertain {
        val retained = journal.persist(
            prepared.copy(
                phase = FpsLimiterJournalPhase.RESTORE_FAILED,
                lastFailure = code.take(240),
            ),
        )
        return FpsLimiterApplyResult.Uncertain(code, retained)
    }

    private fun retainRestoreFailure(
        restoring: FpsLimiterJournalRecord,
        code: String,
    ): FpsLimiterRestoreResult.Retained {
        journal.persist(
            restoring.copy(
                phase = FpsLimiterJournalPhase.RESTORE_FAILED,
                lastFailure = code.take(240),
            ),
        )
        return FpsLimiterRestoreResult.Retained(code)
    }

    companion object {
        private val FPS_LIMITER_RUNTIME_ID = Regex("[A-Za-z0-9._:-]{1,128}")
        private const val GAME_MODE_STANDARD = "standard"
        private const val GAME_MODE_CUSTOM = "custom"
        private const val FPS_READBACK_ATTEMPTS = 4
        private const val FPS_READBACK_SETTLE_MS = 50L
    }
}
