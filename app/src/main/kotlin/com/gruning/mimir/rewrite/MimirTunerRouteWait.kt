package com.gruning.mimir.rewrite

import kotlinx.coroutines.delay

/*
 * Waiting for the clock route to resolve, for the ONE caller that is allowed to wait: the dev hook.
 *
 * **The device trace this exists for — AYN Thor, 2026-09-11, three lines inside 130 ms:**
 *
 * ```
 * 08:30:00.003  route presence pairCanBind=true apiVersion=null … decision=Refuse(reason=TWEAKER BUSY)
 * 08:30:00.069  cap PRIME requested=1708800 route=REFUSE outcome=REFUSED(ROUTE_REFUSED)
 * 08:30:00.130  route presence … apiVersion=10 … decision=Daemon
 * ```
 *
 * `MainActivity.maybeRequestCeiling` runs from `onCreate`'s intent handling, and the synchronous half
 * of the cold start has by then published only the pair fact — `apiVersion` is deliberately null until
 * the daemon answers, because the route must never be guessed. The hook therefore asked 61 ms before
 * the only answer that could have said yes. Nothing was wrong with the refusal; what was wrong was the
 * QUESTION, asked at a moment when it had one possible answer.
 *
 * **Why a wait is legitimate here and nowhere else.** A player pressing a stepper must be told what is
 * true NOW — a surface that blocked for three seconds to avoid printing "busy" would be lying about
 * latency to flatter its own log. This is a debug intent from `adb`, whose whole purpose is to measure
 * the write path on a device that has one; the operator's alternative is to sleep in the shell script
 * and re-send, which is the same wait with worse evidence. So the bound is short, it is polled rather
 * than signalled (the route is a `@Volatile` field with no observer and inventing one for a dev hook
 * would be production surface built for a test), and expiry PROCEEDS rather than aborting, so a device
 * that really is refusing still prints the refusal that names the reason.
 *
 * Nothing else in the app calls this, and nothing else should: a production surface that wants the
 * route to settle wants [TweakerBridge]'s own answer, not a spin.
 */

/** Three seconds: longer than every observed bind on the three handhelds, shorter than an operator's patience. */
const val MimirTunerRouteWaitTimeoutMs: Long = 3_000

/** 50 ms: about one frame at 20 Hz, and 60 polls is a cheap ceiling for a debug path. */
const val MimirTunerRouteWaitPollMs: Long = 50

/**
 * Poll [decision] until it stops being [MimirTunerRouteDecision.Refuse], or until [timeoutMs] of polling.
 *
 * **The budget is counted in polls, not read off a clock, and that is a testability decision with a
 * device consequence.** `SystemClock.elapsedRealtime()` returns 0 under a plain JVM unit test
 * (`isReturnDefaultValues = true`), so a deadline computed from it never expires and the loop that is
 * supposed to be bounded becomes the one that hangs the suite. Counting the delays makes the bound
 * exact in virtual time — [MimirTunerRouteWaitTimeoutMs] / [MimirTunerRouteWaitPollMs] polls, no more —
 * and on the device it means the wall-clock bound is the nominal one plus whatever the dispatcher adds,
 * which for a debug hook is a distinction without a difference.
 *
 * @return the decision as it stood at the end, refusal included.
 */
internal suspend fun awaitMimirTunerRouteResolved(
    timeoutMs: Long = MimirTunerRouteWaitTimeoutMs,
    pollMs: Long = MimirTunerRouteWaitPollMs,
    decision: () -> MimirTunerRouteDecision = { MimirTunerDaemonRoute.decision() },
): MimirTunerRouteDecision {
    var current = decision()
    var waitedMs = 0L
    while (current is MimirTunerRouteDecision.Refuse && waitedMs < timeoutMs) {
        delay(pollMs)
        waitedMs += pollMs
        current = decision()
    }
    return current
}
