package com.gruning.mimir.rewrite.core.ui

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.animation.core.Transition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * The process-wide Bottom receipt log: the real clock, `android.util.Log` on [BottomUiReceiptFormat.TAG] and the main
 * looper as the timer. Every receipt is always on and one line; see [BottomUiReceiptFormat] for the grammar and
 * [BottomUiReceiptLog] for when a line is written. Writing never throws into the caller.
 */
object BottomUiReceipts {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    val log: BottomUiReceiptLog = BottomUiReceiptLog(
        sink = { line, warn ->
            if (warn) Log.w(BottomUiReceiptFormat.TAG, line) else Log.i(BottomUiReceiptFormat.TAG, line)
        },
        nowMs = { System.nanoTime() / 1_000_000L },
        timer = { delayMs, block ->
            val runnable = Runnable { block() }
            handler.postDelayed(runnable, delayMs)
            ({ handler.removeCallbacks(runnable) })
        },
        stackSnapshots = true,
    )
}

/**
 * The display a composition is hosted on, when that display is a Bottom. The Presentation (and each Bottom overlay
 * window) provides it; the default is [BottomUiReceiptFormat.NO_DISPLAY], under which no receipt is written, so a
 * surface that is also mounted on Main (or in a preview) stays silent there.
 */
val LocalBottomUiDisplay: ProvidableCompositionLocal<Int> = compositionLocalOf { BottomUiReceiptFormat.NO_DISPLAY }

/**
 * Reports a Bottom surface's visibility to [BottomUiReceipts] on the edge. Leaving the composition while visible is
 * reported as an exit, so a surface torn down with its host is not left "entered" forever.
 *
 * A surface that is REPLACED in place (the same call site now reports a different [surface], as the scene host's does when
 * Home gives way to Activity) is told apart from one that is torn down: the exit it writes names the successor
 * (`to=`) and the [transition] the successor entered through, because that transition is what carries the old surface away.
 * [from] is the surface this one replaced, for the enter line. A teardown with no successor writes a plain exit.
 *
 * Emits from an effect, never from composition, so a recomposition cannot write a line.
 */
@Composable
fun BottomUiSurfaceReceipt(
    surface: String,
    visible: Boolean,
    transition: String? = null,
    display: Int = LocalBottomUiDisplay.current,
    from: String? = null,
    log: BottomUiReceiptLog = BottomUiReceipts.log,
    reason: String? = null,
    host: String? = null,
) {
    val latestTransition by rememberUpdatedState(transition)
    val latestReason by rememberUpdatedState(reason)
    val latestFrom by rememberUpdatedState(from)
    // Composition writes these before the replaced surface's onDispose runs (apply comes after compose), so at that moment
    // they already hold the successor's values; when nothing replaces the surface they still hold its own.
    val latestSurface by rememberUpdatedState(surface)
    // The host is part of the entry's identity (a picker in the Tabs window is not the Presentation's picker), so it keys both effects and both
    // edges name it: an exit that named none would close the wrong entry, or none.
    LaunchedEffect(surface, display, visible, host) {
        log.surface(
            surface,
            display,
            visible,
            transition = latestTransition,
            other = if (visible) latestFrom else null,
            reason = latestReason,
            host = host,
        )
    }
    DisposableEffect(surface, display, host) {
        onDispose {
            if (log.isSurfaceVisible(surface, display, host)) {
                val successor = latestSurface.takeIf { it != surface }
                log.surface(
                    surface,
                    display,
                    false,
                    transition = if (successor != null) latestTransition else null,
                    other = successor,
                    host = host,
                )
            }
        }
    }
}

/**
 * Restates the Bottom's surface stack every [BottomUiReceiptLog.STACK_HEARTBEAT_MS] (`surface.state reason=heartbeat`) for as long
 * as it is composed, so a log ring that has rotated the surface edges out still says what is on top. Place it in the Presentation's
 * content: the coroutine lives and dies with the window, so nothing is written once the Presentation is dismissed. One timer,
 * never per frame. Silent off a Bottom display. [wait] is the clock (a test's fake).
 */
@Composable
fun BottomUiStackHeartbeat(
    display: Int = LocalBottomUiDisplay.current,
    log: BottomUiReceiptLog = BottomUiReceipts.log,
    intervalMs: Long = BottomUiReceiptLog.STACK_HEARTBEAT_MS,
    wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) {
    LaunchedEffect(display, log, intervalMs) { log.runStackHeartbeat(display, intervalMs, wait) }
}

/**
 * Names the transition a Bottom surface edge ran. Call from the same effect that drives the animation, around
 * the `animateTo`:
 * `BottomUiReceipts.log.transition(name, display) { animatable.animateTo(...) }`.
 */
suspend fun <T> bottomUiTransition(name: String, display: Int, block: suspend () -> T): T =
    BottomUiReceipts.log.transition(name, display, block)

/**
 * A Compose [Transition] (`AnimatedContent` / `AnimatedVisibility` expose theirs as `transition`) reported as one named
 * receipt: `transition.begin` when it leaves rest, `transition.end` when it settles. Measured, not assumed: it watches the
 * real animation, so an animation that is not wired produces no begin and the surface edge that named it reports
 * `transition.missing`.
 *
 * Place it INSIDE the animated content, under the child that is the current target, so a child that is fading out does
 * not report a second run for the same transition.
 */
@Composable
fun BottomUiTransitionProbe(
    name: String,
    transition: Transition<*>,
    display: Int = LocalBottomUiDisplay.current,
) {
    LaunchedEffect(transition, name, display) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return@LaunchedEffect
        var running = false
        try {
            snapshotFlow { transition.currentState != transition.targetState }.collect { moving ->
                if (moving && !running) BottomUiReceipts.log.transitionBegin(name, display)
                if (!moving && running) BottomUiReceipts.log.transitionEnd(name, display, "done")
                running = moving
            }
        } finally {
            // The child can leave composition in the very snapshot that ends its animation (an AnimatedVisibility
            // exit), so the flow never sees the settle; a transition that is at rest when we are cancelled is done.
            if (running) {
                val settled = transition.currentState == transition.targetState
                BottomUiReceipts.log.transitionEnd(name, display, if (settled) "done" else "cancelled")
            }
        }
    }
}

/**
 * An `animateFloatAsState` (or any animated float) reported as one named receipt: begin when [target] changes to
 * something [value] has not reached, end when it gets there. A target the value already equals (a `snap()`, or no change)
 * writes nothing, because no animation ran. Cancelled by the next target, which writes `result=cancelled`.
 */
@Composable
fun BottomUiFloatTransitionReceipt(
    name: String,
    target: Float,
    value: () -> Float,
    display: Int = LocalBottomUiDisplay.current,
) {
    val latestValue = rememberUpdatedState(value)
    LaunchedEffect(target, name, display) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return@LaunchedEffect
        if (latestValue.value() == target) return@LaunchedEffect
        bottomUiTransition(name, display) { snapshotFlow { latestValue.value() }.first { it == target } }
    }
}

/**
 * A control activation reporter bound to the display this composition is hosted on. `val control = rememberBottomUiControl(
 * BottomSurface.TABS)` then `control("drawer.tab.launch", packageName)` from the press handler. Silent off a Bottom display.
 */
@Composable
fun rememberBottomUiControl(surface: String? = null): (id: String, target: String?) -> Unit {
    val display = LocalBottomUiDisplay.current
    return remember(display, surface) { { id: String, target: String? -> BottomUiReceipts.log.control(id, display, surface, target) } }
}

/**
 * Where a tile's art load has got to, held OUTSIDE composition on purpose.
 *
 * A failed decode leaves the tile's bitmap `null` exactly as a load in progress does, so nothing the tile reads can tell the two
 * apart, and reading anything new in composition to find out would add a recomposition to a surface that must not get one. The
 * decode's own coroutine (or Coil's `onState`) writes the outcome here instead; [BottomUiArtReceipt] reads it only inside
 * `snapshotFlow` in an effect, which invalidates that collector and never the tile.
 */
class BottomUiArtProbe {
    enum class Outcome { PENDING, LOADED, FAILED }

    private var outcome by mutableStateOf(Outcome.PENDING)

    /** The decode finished: [loaded] says whether it produced a bitmap. */
    fun settle(loaded: Boolean) {
        outcome = if (loaded) Outcome.LOADED else Outcome.FAILED
    }

    internal fun current(): Outcome = outcome
}

/** A probe per art SOURCE: a tile whose source changes starts again from pending. Key it like the decode is keyed. */
@Composable
fun rememberBottomUiArtProbe(vararg sourceKeys: Any?): BottomUiArtProbe = remember(*sourceKeys) { BottomUiArtProbe() }

/**
 * One art-bearing tile's receipt: `art.plate tile= hasArt= state= plate=`.
 *
 *  - [hasArt]: art is WIRED (a path or a reference exists). It keys the effect, so a tile that gains an icon path while mounted
 *    reports again.
 *  - [loaded] / [failed]: what the caller already knows from what it draws, so they add no read. When the caller cannot know
 *    (a null bitmap is both "loading" and "failed"), [probe] says.
 *  - [plateVisible]: a fallback (glyph, letter, console art, book) is drawn in place of the art.
 *  - [enabled]: false for a tile outside the decode window, so a page the player is not on stays silent.
 *
 * Emits from an effect, never from composition. A tile's lines are rate-limited and deduplicated by [BottomUiReceiptLog], so a
 * lazy list that remounts it cannot write a burst.
 */
@Composable
fun BottomUiArtReceipt(
    tile: String,
    hasArt: Boolean,
    loaded: Boolean,
    plateVisible: Boolean,
    probe: BottomUiArtProbe? = null,
    failed: Boolean = false,
    enabled: Boolean = true,
    display: Int = LocalBottomUiDisplay.current,
    log: BottomUiReceiptLog = BottomUiReceipts.log,
) {
    LaunchedEffect(tile, display, hasArt, loaded, failed, plateVisible, enabled, probe) {
        if (!enabled || display == BottomUiReceiptFormat.NO_DISPLAY) return@LaunchedEffect
        if (!hasArt || loaded || failed || probe == null) {
            log.artPlate(tile, display, hasArt, BottomUiArtState.of(hasArt, loaded, failed), plateVisible)
            return@LaunchedEffect
        }
        snapshotFlow { probe.current() }.collect { outcome ->
            log.artPlate(
                tile, display, hasArt,
                BottomUiArtState.of(
                    hasArt = hasArt,
                    loaded = outcome == BottomUiArtProbe.Outcome.LOADED,
                    failed = outcome == BottomUiArtProbe.Outcome.FAILED,
                ),
                plateVisible,
            )
        }
    }
}

/**
 * Whether the system's animators are off (animator scale 0), the same test the product's own reduced-motion gate uses
 * ([mimirReducedMotion]), for a receipt that has to ask from an effect rather than from composition.
 */
fun bottomUiReducedMotion(): Boolean = !android.animation.ValueAnimator.areAnimatorsEnabled()
