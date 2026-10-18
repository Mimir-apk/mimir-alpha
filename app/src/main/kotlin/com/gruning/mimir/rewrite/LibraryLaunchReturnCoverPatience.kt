package com.gruning.mimir.rewrite

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * The Library return's patience: how long the return cover may hold the screen for one return (combined45 emulator sweep, N1,
 * 2026-10-15: "Welcome back" for 24-26 s, `return-wait shown waitedMs=25904` and `23993`; the Thor settles the same return in about a second).
 *
 * What the cover waited on. `MimirLibraryLaunchReturnEffect` (MimirNavigationCoordinator.kt) lifts the cover only once focus has landed
 * on the restored card and two more frames have drawn: eight `withFrameNanos` attempts, then `awaitLibraryLaunchReturnSurfaceDrawn`. That
 * is bounded in FRAMES, never in time, and the effect restarts from the top on every change of its keys (the ledger's revision at
 * `app_ended`, a Room emission, a pause and resume). On the emulator each frame cost seconds: the process's one main thread was building
 * the Bottom's restore guard (`Displayed CompanionReturnActivity: +13s857ms`) and composing the companion Presentation under JIT
 * (`Compiler allocated 8650KB to compile MimirCompanionPresentation...`, `Skipped 420 frames`) through two reclaim-deadline cycles, and the
 * cover lifted 30 ms after the second guard paused. The card itself was installed and in scope 600 ms in (`scope=library/gameDetail
 * library=shelf+card`): the player waited 25 s behind a finished restore for frames. The Thor's Bottom acknowledges, so its cycles are
 * short; a loaded or JIT-cold device shares the same main thread and the same shape, so the wait is bounded here in time.
 *
 * The bound: the cover's Library arm holds at most [LibraryLaunchReturnCoverGraceMs] from the first resumed run of a real return, the
 * cover's ONE patience budget ([HomeLaunchReturnRenderableGraceMs], the Home arm's), expressed once. Past it the cover lifts to the Library
 * as it stands (the card, or the shelf still loading) and the restore goes on behind it: the focus landing, the pending target and the
 * armed flag are untouched, so a late landing still puts the player on the card. The deadline is absolute ([LibraryLaunchReturnCoverClock]):
 * a restart of the restore effect never extends it, and a restart past it never raises the cover again. The cold-start welcome beat
 * (`WelcomeBackColdStartMs`, 3.2 s) is another layer and keeps its ruling.
 */
internal const val LibraryLaunchReturnCoverGraceMs = HomeLaunchReturnRenderableGraceMs

/**
 * The one absolute deadline of the pending Library return's cover: minted by the restore effect on its first run for a REAL return (past
 * `libraryLaunchReturnMayRestore`, never at the launch, which arms the same flags hours earlier), kept across the effect's restarts, and
 * reset with the return (armed, disarmed, completed). [deadlineMs] is Compose state so [MimirLibraryLaunchReturnCoverBound] keys on it.
 */
internal class LibraryLaunchReturnCoverClock(private val graceMs: Long = LibraryLaunchReturnCoverGraceMs) {
    var deadlineMs: Long? by mutableStateOf(null)
        private set

    /** Whether the cover may (still) hold at [nowMs]; the first ask mints the deadline at [nowMs] + grace. */
    fun holds(nowMs: Long): Boolean {
        val deadline = deadlineMs ?: (nowMs + graceMs).also { deadlineMs = it }
        return nowMs < deadline
    }

    fun reset() {
        deadlineMs = null
    }
}

internal fun libraryLaunchReturnCoverBoundLogLine(graceMs: Long): String =
    "return-wait bound lifted the cover graceMs=$graceMs cause=library-return: the restore goes on behind the Library"

/** The bound's one log line, beside the status's own `return-wait shown` line. */
internal fun logLibraryLaunchReturnCoverLifted() {
    // runCatching: android.util.Log is unmocked on the plain-JVM test path.
    runCatching { Log.i(ReturnWaitLogTag, libraryLaunchReturnCoverBoundLogLine(LibraryLaunchReturnCoverGraceMs)) }
}

/**
 * The clock on the Library arm's hold. With a [deadlineMs] minted and the host [resumed], waits out what is left of it and calls
 * [onLift]; a reset (the restore's own lift, a new return) or a pause cancels the wait, and a deadline already past lifts at once.
 * `delay` runs on the composition's dispatcher, so a main thread held by other work fires it when it is free, as a frame would.
 */
@Composable
internal fun MimirLibraryLaunchReturnCoverBound(
    deadlineMs: Long?,
    resumed: Boolean,
    onLift: () -> Unit,
    nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    val lift by rememberUpdatedState(onLift)
    LaunchedEffect(deadlineMs, resumed) {
        if (deadlineMs == null || !resumed) return@LaunchedEffect
        val remaining = deadlineMs - nowMs()
        if (remaining > 0L) delay(remaining)
        lift()
    }
}
