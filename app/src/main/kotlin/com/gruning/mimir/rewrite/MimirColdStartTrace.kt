package com.gruning.mimir.rewrite

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Logcat tag for Main's cold-start timeline (O11b). */
internal const val MimirColdStartLogTag = "MimirColdStart"

/**
 * O11b : the cold-start timeline the splash-first A/B reads on the Thor.
 *
 * **One tag, one clock.** Every line is taken on `SystemClock.uptimeMillis()`, which is CLOCK_MONOTONIC: the same clock
 * `System.nanoTime()` reads (the welcome beat's own clock in [MimirColdStartWelcome]) and the one `adb logcat -v monotonic`
 * prints, so a line can be placed against ActivityTaskManager's `Displayed` in the same capture. Each line carries
 * `t=+<ms after process start>` against `Process.getStartUptimeMillis()` and the absolute `up=<ms>` beside it. The
 * process-start line is written once, before the first event, with the start the system requested on API 33+.
 *
 * **Behaviour-neutral, so both arms of the A/B carry it.** Each event is one `Log.i`. A frame mark registers a
 * `ViewTreeObserver` frame-commit callback whose only work is that `Log.i`. Nothing here is read back by product code.
 * Release-safe: the release build is not minified and no `-assumenosideeffects` rule strips `Log`.
 */
internal object MimirColdStartTrace {
    private val processLineWritten = AtomicBoolean(false)
    private val spansClosed = AtomicBoolean(false)
    private val spansLogged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun mark(event: String, detail: String = "") {
        // runCatching: android.util.Log is unmocked on the plain-JVM test path (the WarmupLedger.logSettled lesson).
        runCatching {
            val nowMs = SystemClock.uptimeMillis()
            val processStartMs = Process.getStartUptimeMillis()
            if (processLineWritten.compareAndSet(false, true)) {
                val requestedMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Process.getStartRequestedUptimeMillis()
                } else {
                    -1L
                }
                Log.i(
                    MimirColdStartLogTag,
                    "process start up=$processStartMs requestedUp=$requestedMs clock=uptimeMillis",
                )
            }
            val suffix = if (detail.isEmpty()) "" else " $detail"
            Log.i(MimirColdStartLogTag, "$event t=+${nowMs - processStartMs}ms up=$nowMs$suffix")
        }
    }

    /**
     * A cold-start span (the first-launch stall): [mark]s [event] the first time it is reached in
     * this process, and never once [closeSpans] has run. The calls sit between the app root's coordinators, the
     * destination and the overlay host, so the one "content composition" block splits into named pieces; each line's
     * `t=` is when that piece FINISHED composing. Subcomposed content (BoxWithConstraints, lazy layouts) composes during
     * the first measure, after "content composition applied", and is not inside any span. Behaviour-neutral like
     * [mark]: one set insert per call until the spans close, one `Log.i` per span per process. Returns whether it logged.
     */
    fun markOnce(event: String): Boolean {
        if (spansClosed.get() || !spansLogged.add(event)) return false
        mark(event)
        return true
    }

    /** Ends the spans. The content probe calls it once its first composition is applied, so recompositions log nothing. */
    fun closeSpans() {
        spansClosed.set(true)
    }

    /** JVM tests only: the spans are process state. */
    internal fun resetSpansForTest() {
        spansClosed.set(false)
        spansLogged.clear()
    }

    /**
     * Logs [event] when the next frame [view]'s window draws has been committed to the swap chain. Hardware rendering
     * only: the platform runs frame-commit callbacks for nothing else (ViewTreeObserver.registerFrameCommitCallback), so
     * a software-rendered window logs nothing.
     */
    fun markNextFrameCommit(view: View, event: String, detail: String = "") {
        if (!view.isHardwareAccelerated) return
        runCatching { view.viewTreeObserver.registerFrameCommitCallback { mark(event, detail) } }
    }
}

/**
 * Places the app content on Main's cold-start timeline: the start and end of its first composition, the moment that
 * composition's effects were applied, and the commit of the first frame that draws it. First composition only.
 */
@Composable
internal fun MimirColdStartContentProbe(content: @Composable () -> Unit) {
    remember {
        MimirColdStartTrace.mark("content composition begin")
        true
    }
    content()
    remember {
        MimirColdStartTrace.mark("content composition end")
        true
    }
    val view = LocalView.current
    DisposableEffect(view) {
        MimirColdStartTrace.mark("content composition applied")
        // The spans measure the first composition only; its effects applying ends them.
        MimirColdStartTrace.closeSpans()
        MimirColdStartTrace.markNextFrameCommit(view, "content first frame committed")
        onDispose { }
    }
}
