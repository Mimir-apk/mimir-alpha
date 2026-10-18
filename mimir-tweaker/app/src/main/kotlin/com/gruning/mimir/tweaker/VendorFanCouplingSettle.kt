package com.gruning.mimir.tweaker

/**
 * The AYN vendor's fan-and-mode coupling, and how a vendor-mode write waits it out.
 *
 * **What the vendor does (device-proven, Thor test unit, 2026-10-13).** Both AYN devices
 * ship `Settings.System is_quick_set_performance_and_fan_enable = 1`. SystemUI's
 * `Tile.FanTile.updateFanFromPerformance` observes `performance_mode` and, on every change
 * (`selfChange = false`), rewrites `fan_mode` from its own memory of the fan tile. The value it writes
 * is NOT a function of the mode alone: a Tweaker write of mode 1 or 2 was followed by fan 4 both
 * times, a Tweaker rollback to 0 at 18:37:47 by fan 4, and a Tweaker write of 0 at 18:51:36 and
 * 18:51:45 -- after Standard was manually selected in the vendor UI -- by fan 1. So nothing here
 * assumes a mapping; the only fact used is that SystemUI reacts to a mode CHANGE and to nothing
 * else (a `fan_mode` write never produced a tile line in the whole 20-minute window).
 *
 * **The timing the constants are taken from.** The put itself is a shell command with no log line,
 * but it is bracketed on both sides: the manual fan entry's last settle read at 18:37:40.014 (the
 * entry returns within 300 ms of it) and the post-cooling admission dwell that started at 18:51:36.55
 * (431 ms, ending 18:51:36.980) sit immediately before and after the vendor write, and the tile's
 * line landed at 18:37:40.360, 18:51:36.476 and 18:51:44.023 -- within about 50-150 ms of the put
 * every time -- with the vendor fan service's own loop line (`FanBase: mSmartAction`) 11 ms after
 * it. No second rewrite appeared inside any of the 1.5 s verify windows that followed.
 *
 * - [POLL_MS] 50 ms: a Settings read, cheaper than any sysfs read this module already polls at 75 ms.
 * - [STABLE_POLLS] 6 (300 ms): once a change is seen, the value must stay put for ~3x the slowest
 *   observed reaction before it is called settled -- the same 3x rule `FAN_ENTRY_VENDOR_REACTION_MS`
 *   applies to the Nova's one-shot duty write.
 * - [DEADLINE_POLLS] 20 (1000 ms): with no change seen, give up waiting at ~7-20x the observed
 *   reaction. This is the COMMON case on the Thor, not the odd one: from stock Smart (4) a mode write
 *   makes SystemUI write 4 over 4, which no poll can observe. It is logged as `coupled=unchanged`,
 *   never as a fault.
 * - [MAX_POLLS] 30 (1500 ms): the hard cap on the whole wait, so a value that keeps moving cannot
 *   hold a transaction (the deadline plus a full stable window, and no longer).
 *
 * **Why the deadline proceeds rather than fails.** SystemUI's rewrite is the vendor exercising its
 * own setting, not something Mimir can verify or refuse. After the wait the Tweaker's own fan write
 * runs through its normal path; a rewrite that lands later than the cap is caught exactly where any
 * other external fan change is -- the apply's verify window (rollback), the supervisor's bounded
 * fan reassert in session, and the restore's final fan step and the Guardian's double readback.
 *
 * Count-bounded, not clock-bounded: the loop is `repeat` over [sleeper], so a rig with a no-op
 * sleeper and a frozen clock terminates; the clock only feeds the `settledMs=` figure on the log line.
 *
 * Every read goes through the app's own `Settings.System` reads ([couplingRaw], [fanModeRaw]); the
 * flag is never written and nothing here reaches the privileged bridge.
 */
internal class VendorFanCouplingSettle(
    private val couplingRaw: () -> String?,
    private val fanModeRaw: () -> String?,
    private val sleeper: (Long) -> Unit,
    private val elapsedRealtimeMs: () -> Long,
    private val log: (String) -> Unit = { line -> runCatching { android.util.Log.i(TAG, line) } },
) {
    enum class Outcome {
        /** The flag does not read exactly "1": SystemUI does not couple, nothing waited. */
        NOT_COUPLED,

        /** `fan_mode` is unreadable (fanless chassis, or no key): nothing to settle. */
        NO_FAN,

        /** A change was observed and the value then held for [STABLE_POLLS]. */
        SETTLED,

        /** No change observed within [DEADLINE_POLLS]: SystemUI rewrote the same value, or did not react. */
        UNCHANGED,

        /** The value was still moving when [MAX_POLLS] ran out. */
        UNSTABLE,
    }

    data class Result(
        val outcome: Outcome,
        val flag: String?,
        val preFan: Int?,
        val coupledFan: Int?,
        val elapsedMs: Long,
        val polls: Int,
    ) {
        val waited: Boolean get() = polls > 0
    }

    /** Exactly "1", as the brief requires -- a read of the key, never a write, never a parse. */
    fun armed(): Boolean = couplingRaw() == "1"

    /** The fan as it reads immediately BEFORE the vendor-mode write, or null when there is no fan. */
    fun preFan(): Int? = fanModeRaw()?.trim()?.toIntOrNull()

    /**
     * Wait for SystemUI's coupled `fan_mode` rewrite after a vendor-mode write that CHANGED the key
     * from [fromMode] to [toMode]. Callers skip this entirely when the write was elided as redundant:
     * SystemUI observes changes only.
     */
    fun settle(fromMode: Int, toMode: Int, preFan: Int?): Result {
        val flag = couplingRaw()
        val startedAt = elapsedRealtimeMs()
        if (flag != "1") {
            return Result(Outcome.NOT_COUPLED, flag, preFan, preFan, 0L, 0).also { emit(fromMode, toMode, it) }
        }
        // A caller that did not read the fan before the write (the flag was not armed then) still
        // gets an honest baseline: the value now, before the first poll, is the best pre-write read left.
        val pre = preFan ?: fanModeRaw()?.trim()?.toIntOrNull()
            ?: return Result(Outcome.NO_FAN, flag, null, null, 0L, 0).also { emit(fromMode, toMode, it) }
        var last: Int? = pre
        var changed = false
        var stablePolls = 0
        var polls = 0
        while (polls < MAX_POLLS) {
            sleeper(POLL_MS)
            polls += 1
            val observed = fanModeRaw()?.trim()?.toIntOrNull()
            if (observed != last) {
                changed = true
                stablePolls = 0
                last = observed
            } else if (changed) {
                stablePolls += 1
                if (stablePolls >= STABLE_POLLS) {
                    return Result(Outcome.SETTLED, flag, pre, last, elapsedRealtimeMs() - startedAt, polls)
                        .also { emit(fromMode, toMode, it) }
                }
            } else if (polls >= DEADLINE_POLLS) {
                return Result(Outcome.UNCHANGED, flag, pre, last, elapsedRealtimeMs() - startedAt, polls)
                    .also { emit(fromMode, toMode, it) }
            }
        }
        return Result(Outcome.UNSTABLE, flag, pre, last, elapsedRealtimeMs() - startedAt, polls)
            .also { emit(fromMode, toMode, it) }
    }

    private fun emit(fromMode: Int, toMode: Int, result: Result) {
        if (result.outcome == Outcome.NOT_COUPLED) return
        log(describe(fromMode, toMode, result))
    }

    companion object {
        /** PRODUCTION-visible, like `MimirVerifyWindow`: one line per settle, hardware numbers only. */
        const val TAG = "MimirVendorMode"
        const val POLL_MS = 50L
        const val STABLE_POLLS = 6
        const val DEADLINE_POLLS = 20
        const val MAX_POLLS = DEADLINE_POLLS + STABLE_POLLS + 4

        /** `VENDOR_FAN_COUPLING flag=1 mode 0->1 fan pre=4 coupled=6 settledMs=350`, or `coupled=unchanged settledMs=timeout`. */
        fun describe(fromMode: Int, toMode: Int, result: Result): String {
            val coupled = when (result.outcome) {
                Outcome.NO_FAN -> "none"
                Outcome.UNCHANGED -> "unchanged"
                else -> result.coupledFan?.toString() ?: "null"
            }
            val settled = when (result.outcome) {
                Outcome.SETTLED -> result.elapsedMs.toString()
                Outcome.NO_FAN, Outcome.NOT_COUPLED -> "0"
                Outcome.UNCHANGED, Outcome.UNSTABLE -> "timeout"
            }
            return "VENDOR_FAN_COUPLING flag=${result.flag ?: "absent"} mode $fromMode->$toMode " +
                "fan pre=${result.preFan ?: "null"} coupled=$coupled settledMs=$settled" +
                if (result.outcome == Outcome.UNSTABLE) " outcome=unstable" else ""
        }
    }
}
