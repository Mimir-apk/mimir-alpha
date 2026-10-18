package com.gruning.mimir.rewrite.core.launch

import android.content.Intent
import com.gruning.mimir.rewrite.core.model.LaunchOutcome
import com.gruning.mimir.rewrite.core.model.LaunchProof
import com.gruning.mimir.rewrite.core.model.LaunchReadiness
import com.gruning.mimir.rewrite.core.model.LaunchTarget

/**
 * Dispatches a resolved [LaunchTarget] to the platform (the native launch bridge in the app layer)
 * and reports the SYNCHRONOUS outcome — intent sent, or a typed dispatch failure. This is the seam
 * over the native `launchPlacedGameWithTarget` bridge.
 *
 * Scope discipline : the outcome
 * is dispatch-only. There is deliberately NO "did the emulator actually stay open" verdict — silent
 * post-dispatch death detection is outside this dispatch-only contract.
 */
fun interface LaunchDispatcher {
    /** Fire the launch. Returns the synchronous dispatch outcome. */
    fun dispatch(target: LaunchTarget): LaunchOutcome

    /** Explicit per-attempt destination. Main keeps the historical dispatcher behavior. */
    fun prepare(target: LaunchTarget, destination: LaunchDispatchDestination): PreparedLaunch =
        prepare(target)

    /**
     * Prepare platform work that must finish before any package-bound performance policy is applied.
     *
     * The default keeps test/fake dispatchers source-compatible. Android's real dispatcher overrides it
     * to build the Intent and reap the exact target package before returning a one-shot dispatch.
     */
    fun prepare(target: LaunchTarget): PreparedLaunch =
        PreparedLaunch.dispatchable(action = { dispatch(target) })

    /** Optional diagnostic context from the most recent exact-target preflight. */
    fun preflightContext(): LaunchTargetPreflightContext? = null
}

/** A launch destination is carried by one attempt; it is never process-global mutable state. */
sealed interface LaunchDispatchDestination {
    data object Main : LaunchDispatchDestination

    /** The app owns the secondary host transaction and receives the complete built game Intent. */
    data class Secondary(val dispatch: (Intent) -> Boolean) : LaunchDispatchDestination
}

/**
 * One prepared launch attempt. [dispatch] is idempotent: accidental repeated calls return the first
 * synchronous outcome and can never start the emulator twice.
 */
class PreparedLaunch private constructor(
    val dispatchable: Boolean,
    val preflight: LaunchTargetPreflightContext?,
    private val action: () -> LaunchOutcome,
) {
    @Volatile
    private var settled: LaunchOutcome? = null
    private var attempted = false

    @Synchronized
    fun dispatch(): LaunchOutcome {
        settled?.let { return it }
        check(!attempted) { "Prepared launch was already dispatched without a settled outcome" }
        attempted = true
        return action().also { settled = it }
    }

    companion object {
        fun dispatchable(
            preflight: LaunchTargetPreflightContext? = null,
            action: () -> LaunchOutcome,
        ): PreparedLaunch = PreparedLaunch(
            dispatchable = true,
            preflight = preflight,
            action = action,
        )

        fun failed(outcome: LaunchOutcome.Failed): PreparedLaunch = PreparedLaunch(
            dispatchable = false,
            preflight = null,
            action = { outcome },
        )
    }
}

data class LaunchTargetPreflightContext(
    val packageName: String,
    val result: LaunchTargetReapResult,
)

/**
 * Orchestrates a launch attempt end-to-end from a [LaunchReadiness]:
 *  - Only [LaunchReadiness.Ready] can dispatch; any other state is a no-op (the UI already routed the
 *    user: only "no emulator"→settings, "no file"→detail).
 *  - On dispatch, a [LaunchProof] is built and recorded (durable, locator-scoped failure lifetime).
 *
 * The clock is injected so proof timestamps are testable.
 */
class LaunchCoordinator(
    private val dispatcher: LaunchDispatcher,
    private val proofRecorder: LaunchProofRecorder,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * Attempt a launch for a resolved readiness. Returns the recorded [LaunchProof] on a Ready
     * dispatch, or null when [readiness] is not Ready (nothing was dispatched).
     */
    fun launch(readiness: LaunchReadiness): LaunchProof? {
        val target = (readiness as? LaunchReadiness.Ready)?.target ?: return null
        val outcome = dispatcher.dispatch(target)
        val proof = buildProof(target, outcome)
        proofRecorder.record(proof)
        return proof
    }

    private fun buildProof(target: LaunchTarget, outcome: LaunchOutcome): LaunchProof = LaunchProof(
        gameId = target.gameId,
        platformId = target.platformId,
        emulatorId = target.emulator.id,
        locator = target.locator,
        outcome = outcome,
        timestampMillis = clockMillis(),
    )
}
