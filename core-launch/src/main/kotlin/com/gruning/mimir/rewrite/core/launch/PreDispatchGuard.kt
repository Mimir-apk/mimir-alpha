package com.gruning.mimir.rewrite.core.launch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What became of a pre-dispatch side effect (today: the cross-app Mimir Tweaker apply/restore that runs
 * just before a game launch). Dispatch proceeds on EVERY branch — this type exists so the caller can say
 * something honest about a degraded preparation, never to gate the launch.
 */
sealed class PreDispatchOutcome {
    /** Finished inside its budget; the "hook ran before dispatch" ordering held. */
    data object Completed : PreDispatchOutcome()

    /** Threw. The failure was contained here and never reached the launch. */
    data class Failed(val cause: Throwable) : PreDispatchOutcome()

    /**
     * Did not settle within [timeoutMillis] and was ABANDONED. It may still be running: a synchronous
     * Binder transaction into another process is not interruptible, so "cancelled" is a claim we cannot
     * make. Nothing waits on it, and whatever it eventually does cannot reach this launch.
     */
    data class TimedOut(val timeoutMillis: Long) : PreDispatchOutcome()
}

/**
 * Budget for pre-dispatch work: long enough for the Tweaker's bounded CPU observation windows and
 * Guardian/journal settlement to finish before emulator dispatch, short enough that a wedged service
 * remains contained. Odin proved that the former three-second budget could launch the emulator while
 * the guarded transaction was still verifying, allowing its launch boost to invalidate the profile.
 */
const val DEFAULT_PRE_DISPATCH_TIMEOUT_MILLIS: Long = 9_500L

/**
 * Run [block] ahead of a launch dispatch with both failure containment and a hard time bound, and report
 * which of those happened.
 *
 * [block] runs in a **detached** scope on purpose. Under structured concurrency the caller cannot complete
 * until its child does, and a timeout only *requests* cancellation — a thread blocked in a Binder
 * transaction never reaches a cancellation point, so `withTimeout { hook() }` would still wedge the launch
 * (and, at the Home call site, the enclosing `NonCancellable` block) for as long as the other process
 * misbehaves. Detaching makes the timeout real: this function returns, the launch proceeds, and the
 * abandoned job's fate is its own.
 */
suspend fun runContainedPreDispatch(
    dispatcher: CoroutineDispatcher,
    timeoutMillis: Long,
    block: suspend () -> Unit,
): PreDispatchOutcome {
    if (timeoutMillis <= 0L) return PreDispatchOutcome.TimedOut(timeoutMillis)
    val settled = CompletableDeferred<PreDispatchOutcome>()
    CoroutineScope(dispatcher + Job()).launch {
        val outcome = try {
            block()
            PreDispatchOutcome.Completed
        } catch (cancellation: CancellationException) {
            // Detached: no parent can cancel this job, so a cancellation came from inside [block] and is
            // this launch's business to report, not to re-throw into a scope nobody is watching.
            PreDispatchOutcome.Failed(cancellation)
        } catch (failure: Throwable) {
            PreDispatchOutcome.Failed(failure)
        }
        settled.complete(outcome)
    }
    return withTimeoutOrNull(timeoutMillis) { settled.await() }
        ?: PreDispatchOutcome.TimedOut(timeoutMillis)
}
