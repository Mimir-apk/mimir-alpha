package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.model.library.GameRow
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * R01 "Land Warm" : the cold-start welcome lane no longer releases on the platforms-query
 * PROXY ([LibraryReadyGate] alone) — it awaits THIS ledger, which settles when every registered warm task has
 * finished (or spent its budget). The tasks front the cache work the first navigation would otherwise pay in
 * view — row builds, SAF art resolution, Coil cover decodes, picked-platform-art decodes — so the app LANDS
 * warm instead of hydrating in front of the user (the adversarial-review P0s: GL-01, LS-01, SPINE-1).
 *
 * Safety posture: per-task budgets + the lane's own hard cap bound the splash — a slow or huge library can
 * only ever land PARTIALLY warm, never hang. One-shot per process (a same-process activity re-create finds
 * the ledger already settled and holds only the 3.2s brand beat; a process-death relaunch starts fresh and
 * re-runs the warm pass — exactly the cold case it exists for).
 */
object WarmupLedger {
    /** TRUE once every warm task has completed or timed out — the welcome lane's release signal. */
    val settled = MutableStateFlow(false)

    /** The welcome card's live status line — the label of the first still-pending task. */
    val stage = MutableStateFlow("Refreshing your library…")

    private val begun = AtomicBoolean(false)
    /** Warm tasks are process work; contain one failed task at the ledger boundary. */
    internal val scopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        android.util.Log.e("MimirWarmup", "background warmup work failed in $context", throwable)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + scopeFailureHandler)

    /** Idempotent: only the first caller's task list runs (one cold pass per process). */
    fun begin(tasks: List<WarmupTask>) {
        if (!begun.compareAndSet(false, true)) return
        val begunAtMs = runCatching { android.os.SystemClock.uptimeMillis() }.getOrDefault(0L)
        if (tasks.isEmpty()) {
            settled.value = true
            logSettled(begunAtMs, "no-tasks")
            return
        }
        val remaining = MutableStateFlow(tasks.map { it.name }.toSet())
        fun refreshStage() {
            tasks.firstOrNull { it.name in remaining.value }?.let { stage.value = it.label }
        }
        refreshStage()
        // Lane mem (2026-10-14): each task's own settle offset, so the one felt-cold line below says WHICH task held
        // the mask (the Thor's restored return after a lowmemorykiller kill waited 1.5 s here with no way to tell).
        val settledAt = java.util.concurrent.ConcurrentHashMap<String, String>()
        tasks.forEach { task ->
            scope.launch {
                val finished = runCatching { withTimeoutOrNull(task.budgetMs) { task.block() } }
                val sinceBeginMs = runCatching { android.os.SystemClock.uptimeMillis() }.getOrDefault(0L) - begunAtMs
                // A task that threw and a task that ran out its budget both land here; the label tells them apart.
                val outcome = when {
                    finished.isFailure -> "ms(threw)"
                    finished.getOrNull() == null -> "ms(budget)"
                    else -> "ms"
                }
                settledAt[task.name] = "$sinceBeginMs$outcome"
                remaining.update { it - task.name }
                refreshStage()
                if (remaining.value.isEmpty()) {
                    settled.value = true
                    logSettled(begunAtMs, tasks.joinToString("+") { "${it.name}@${settledAt[it.name] ?: "?"}" })
                }
            }
        }
    }

    /**
     * PERF-E (2026-07-17): the ONE felt-cold marker. `am start -W` measures only the first frame; the
     * user-felt cold ends when THIS fires (the welcome gate's release signal). The delta runs from [begin],
     * which the app's warm-up calls when it first composes. Since O11b's splash-first composition that is no
     * longer the frame `Displayed` ends on a returning cold start (the app content mounts a handshake after the
     * splash's first frame), so place this line from the app's `MimirColdStart` "ledger begin" line, on the same
     * uptime clock, not from `Displayed`.
     */
    private fun logSettled(begunAtMs: Long, detail: String) {
        // runCatching: android.util.Log is unmocked on the JVM test path (the SC01 instrument lesson).
        runCatching {
            android.util.Log.i(
                "MimirColdSettle",
                "warm ledger settled +${android.os.SystemClock.uptimeMillis() - begunAtMs}ms after begin ($detail)",
            )
        }
    }
}

/** One named, budgeted warm task. [label] is user-visible on the welcome card while the task is pending. */
class WarmupTask(
    val name: String,
    val label: String,
    val budgetMs: Long,
    val block: suspend () -> Unit,
)

/**
 * Cross-module warm signals (the ledger's inputs that originate inside feature-library).
 * [firstGamelistRows] carries the LANDING platform's rows (the shelf centres index 0 on a cold start) the
 * moment the view-model's immediate warm caches them — non-null (possibly empty) means "resolved".
 */
object LibraryWarmSignals {
    val firstGamelistRows = MutableStateFlow<List<GameRow>?>(null)
}
