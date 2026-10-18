package com.gruning.mimir.tweaker

/**
 * Names the client that is hammering [MimirTweakerService.getCapabilities], and does it in
 * PRODUCTION, where every other diagnostic in this daemon is compiled out.
 *
 * ## Why this exists
 *
 * On 2026-08-30 the reference Nova was measured doing a full daemon `capabilities()` — a sysfs
 * probe, a SHA-256 over the whole capability set, an AppOps query and a calibration-store read —
 * on a metronomic **1.13–1.14 s** cadence, three separate times, while sitting idle on Home. Two
 * hypotheses were refuted by measurement (a daemon-internal ticker: force-stopping Mimir took the
 * daemon to zero; observation reopen-churn: 346 instrumented beats showed one open and zero exits),
 * a source census excluded seven more candidates with file:line, and the last surviving candidate
 * was excluded on the render-gate evidence. **No candidate survives, and the cadence stopped
 * reproducing before it could be caught.**
 *
 * Every instrument built during that hunt was client-side, and a client-side instrument can only
 * ever exonerate the surface it is attached to. Meanwhile `getCapabilities` is the one door that
 * can still be driven at any rate, has no rate limit and no cross-call memo — and as of the
 * negative-resolution cache it went **completely silent**, because the two `MimirQualification`
 * warnings that used to make the cadence visible now sit behind a cache hit. Fixed on one side,
 * unmeasurable on the other.
 *
 * This is the instrument that ends that: it sits on the door itself and reports
 * `Binder.getCallingPid()`, so a recurrence names its own caller instead of us clearing one
 * suspect at a time.
 *
 * ## What it costs, because that is the whole point
 *
 * Two longs and two ints of state, and one subtraction per call — on a call that already pays for
 * a SHA-256. **It logs on EDGES only: once when a sustained fast cadence begins, once when it
 * ends.** Never per call. That is not a style preference: RC-47 removed a once-a-second identity
 * line from `HardwareProbe.probe()` for exactly this reason, and an instrument that becomes its own
 * noise source would repeat the earlier per-beat overlay probe failure.
 *
 * [SUSTAINED_CALLS] consecutive fast calls are required before anything is logged, so a legitimate
 * burst — a panel opening, a profile apply, a launch handshake — passes in silence. A single fast
 * pair is normal; five in a row at under two seconds is a client in a loop.
 */
internal class CapabilityCallCadenceWatch(
    private val fastIntervalMs: Long = FAST_INTERVAL_MS,
    private val sustainedCalls: Int = SUSTAINED_CALLS,
) {
    /** Edge events only. A quiet daemon and a daemon in a steady burst both produce nothing. */
    sealed interface Event {
        /** A sustained sub-[fastIntervalMs] cadence just began. */
        data class Entered(
            val callingPid: Int,
            val intervalMs: Long,
            val afterCalls: Int,
        ) : Event

        /** It ended. [calls] and [fastestIntervalMs] describe the episode that just closed. */
        data class Settled(
            val callingPid: Int,
            val episodeMs: Long,
            val calls: Int,
            val fastestIntervalMs: Long,
            val multiplePids: Boolean,
        ) : Event
    }

    private var lastCallAtMs: Long? = null
    private var consecutiveFast: Int = 0
    private var inEpisode: Boolean = false
    private var episodeStartMs: Long = 0L
    private var episodeCalls: Int = 0
    private var episodeFastestMs: Long = Long.MAX_VALUE
    private var episodePid: Int = -1
    private var episodeMultiplePids: Boolean = false

    /**
     * Call once per binder entry, on the binder thread, with `Binder.getCallingPid()`.
     * Returns the one line worth writing, or null — which is the overwhelmingly common case.
     */
    @Synchronized
    fun onCall(nowElapsedRealtimeMs: Long, callingPid: Int): Event? {
        val previous = lastCallAtMs
        lastCallAtMs = nowElapsedRealtimeMs
        if (previous == null) return null

        val interval = nowElapsedRealtimeMs - previous
        // A non-monotonic or zero-length step is not evidence of anything. elapsedRealtime should
        // never go backwards, but a watch that would mis-report if it did is a watch nobody can
        // trust the one time it fires.
        if (interval < 0L) {
            reset()
            return null
        }

        if (interval >= fastIntervalMs) {
            val closing = if (inEpisode) {
                Event.Settled(
                    callingPid = episodePid,
                    episodeMs = previous - episodeStartMs,
                    calls = episodeCalls,
                    fastestIntervalMs = episodeFastestMs,
                    multiplePids = episodeMultiplePids,
                )
            } else {
                null
            }
            inEpisode = false
            consecutiveFast = 0
            return closing
        }

        consecutiveFast += 1
        if (inEpisode) {
            episodeCalls += 1
            if (interval < episodeFastestMs) episodeFastestMs = interval
            if (callingPid != episodePid) episodeMultiplePids = true
            return null
        }
        if (consecutiveFast < sustainedCalls) return null

        inEpisode = true
        // The episode began at the first fast call of the run, not at the one that tripped the
        // threshold -- otherwise every reported episodeMs is short by sustainedCalls intervals.
        episodeStartMs = nowElapsedRealtimeMs - interval * consecutiveFast
        episodeCalls = consecutiveFast + 1
        episodeFastestMs = interval
        episodePid = callingPid
        episodeMultiplePids = false
        return Event.Entered(
            callingPid = callingPid,
            intervalMs = interval,
            afterCalls = consecutiveFast,
        )
    }

    private fun reset() {
        consecutiveFast = 0
        inEpisode = false
        episodeCalls = 0
        episodeFastestMs = Long.MAX_VALUE
        episodeMultiplePids = false
    }

    internal companion object {
        /**
         * Two seconds. The sanctioned pollers on this door are far slower — the standing rearm
         * watch is 15 s recovering and 60 s confirming — and the cadence under investigation was
         * 1.13–1.14 s, so this sits between them with room on both sides.
         */
        const val FAST_INTERVAL_MS = 2_000L

        /** Five in a row. A burst is normal; a loop is not. */
        const val SUSTAINED_CALLS = 5
    }
}
