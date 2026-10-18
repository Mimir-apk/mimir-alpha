package com.gruning.mimir.rewrite.core.input

/**
 * Directional traversal: the d-pad has a GLOBAL maximum speed. Every normalized directional action —
 * any direction, any surface, key or axis — passes through this pace before it may actuate again,
 * so fast traversal can never outrun a surface's focus animation ("looks like clipping"). One move
 * per [MimirDirectionalPaceMs]; rejected presses are consumed and never stretch the window.
 *
 * The clock is injectable for JVM tests; the default is a pure monotonic JVM clock (no Android
 * dependency, safe in unit tests that forget to override it).
 */
object MimirDirectionalPace {
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private var lastAcceptedMs: Long = Long.MIN_VALUE / 2

    /**
     * Event-time pacing: the pace judges at EVENT time when the caller provides
     * the input's own timestamp ([eventTimeMs] > 0, uptime base — the router passes
     * `KeyEvent`/`MotionEvent.eventTime`). At processing time, presses queued behind a janky frame
     * arrived <60ms apart HERE despite a real ~200ms gap at the thumb — and were eaten. All production
     * dispatch flows through the router (which always stamps), so the base never mixes; the no-arg form
     * (JVM tests) keeps the pure JVM clock. A NEGATIVE delta (clock weirdness / a fresh base) re-seeds
     * instead of rejecting, so one bad stamp can never wedge the pace shut.
     */
    fun tryAccept(minIntervalMs: Long = MimirDirectionalPaceMs, eventTimeMs: Long = 0L): Boolean {
        val now = if (eventTimeMs > 0L) eventTimeMs else clock()
        val delta = now - lastAcceptedMs
        if (delta in 0 until minIntervalMs) return false
        // Panel A1: a negative delta ACCEPTS but must never rewind the stamp — a rewound stamp would
        // let the NEXT well-spaced press fall inside a window it already cleared.
        lastAcceptedMs = maxOf(lastAcceptedMs, now)
        return true
    }

    fun reset() {
        lastAcceptedMs = Long.MIN_VALUE / 2
    }
}

/**
 * Binary selection: with binary selection there is no animation left to protect — the pace is now a
 * pure ANTI-BURST floor (~16 moves/sec). Deliberate input, held repeats and fast walks all flow
 * untouched; only key-jam hammering is absorbed.
 */
const val MimirDirectionalPaceMs = 60L
