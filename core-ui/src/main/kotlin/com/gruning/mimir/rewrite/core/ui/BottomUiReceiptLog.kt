package com.gruning.mimir.rewrite.core.ui

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Schedules [block] after [delayMs] on whatever thread the host chooses; the returned function cancels it. */
fun interface BottomUiReceiptTimer {
    fun after(delayMs: Long, block: () -> Unit): () -> Unit
}

/**
 * The stateful half of the Bottom receipts: it decides WHEN a line is written so that every line is an edge.
 *
 *  - A surface line is written only when that surface's visibility actually changes.
 *  - A tile's art-plate line is written only when its (hasArt, plate) pair changes.
 *  - A control line is rate-limited per id; the suppressed count rides on the next line that is written.
 *  - A transition is a begin and an end with a measured `ms=`; a surface edge that NAMES a transition and never
 *    sees its begin produces `transition.missing`, which is how an unwired transition is made visible.
 *
 * Android-free: the clock, the sink and the timer are constructor parameters. The process instance, with the real
 * ones, is [BottomUiReceipts].
 */
class BottomUiReceiptLog(
    private val sink: (line: String, warn: Boolean) -> Unit,
    private val nowMs: () -> Long,
    private val timer: BottomUiReceiptTimer,
    private val missingTransitionAfterMs: Long = DEFAULT_MISSING_AFTER_MS,
    private val unfinishedTransitionAfterMs: Long = DEFAULT_UNFINISHED_AFTER_MS,
    private val controlWindowMs: Long = DEFAULT_CONTROL_WINDOW_MS,
    private val controlMaxPerWindow: Int = DEFAULT_CONTROL_MAX_PER_WINDOW,
    private val artWindowMs: Long = DEFAULT_ART_WINDOW_MS,
    private val artMaxPerWindow: Int = DEFAULT_ART_MAX_PER_WINDOW,
    /**
     * Whether the log also writes `surface.state` (the surface stack on a display) after each surface edge, at `presentation.ready`
     * and on the heartbeat. The process log ([BottomUiReceipts]) turns it on; a log built to test the edge grammar alone leaves
     * it off, so its exact line lists stay the edges.
     */
    private val stackSnapshots: Boolean = false,
) {
    private val lock = Any()

    private val _surfaceEdges = MutableSharedFlow<Int>(extraBufferCapacity = SURFACE_EDGE_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * The display of every edge that changed which surfaces are visible on it, as it happens (a surface entered or exited, or a window's owned surfaces were closed).
     * A reader that acts on the stack ([surfaceStack]) re-reads it when one arrives: a dropped older tick only ever repeats the display of a newer one, and the stack it
     * reads is the current one, so a burst loses nothing. Never suspends or blocks the writer.
     */
    val surfaceEdges: SharedFlow<Int> = _surfaceEdges.asSharedFlow()

    /**
     * Insertion-ordered: a surface that enters is moved to the end, so iteration order is the order surfaces were entered. Keyed by
     * [surfaceKey]: the display, the surface and, for a surface composed inside another window, that window, so the same surface
     * composed in two windows is two entries that neither dedupe nor close each other.
     */
    private val surfaceVisible = LinkedHashMap<String, Boolean>()
    private val artTiles = LinkedHashMap<String, ArtTile>()
    private val focusOwners = HashMap<Int, String?>()
    private val themes = HashMap<Int, String?>()
    private val backdropCuts = HashMap<Int, HashMap<Any, Boolean>>()
    private val running = HashMap<String, RunningTransition>()
    private val unfinishedCancel = HashMap<String, () -> Unit>()
    private val pending = HashMap<String, PendingTransition>()
    private val lastBeginMs = HashMap<String, Long>()
    private val controlWindows = HashMap<String, ControlWindow>()

    /** A baseless `surface.state` held back for [STACK_SETTLE_MS]; [cancel] drops its timer. One per display. */
    private class SettlingStack { var cancel: () -> Unit = {} }

    private val settlingStack = HashMap<Int, SettlingStack>()

    private class PendingTransition(val surface: String, val sinceMs: Long, val cancel: () -> Unit)

    /** [depth] counts coalesced begins: two backdrops composed at once both run one theme crossfade, and that is one run. */
    private class RunningTransition(val startedMs: Long, var depth: Int)
    private class ControlWindow(var startMs: Long, var count: Int, var suppressed: Int)

    private data class ArtObservation(
        val tile: String,
        val display: Int,
        val hasArt: Boolean,
        val state: BottomUiArtState,
        val plateVisible: Boolean,
    )

    /** One tile's art bookkeeping: the latest state seen, the last one written, and its rate-limit window. */
    private class ArtTile(var windowStartMs: Long) {
        var observed: ArtObservation? = null
        var written: ArtObservation? = null
        var count = 0
        var suppressed = 0
        var flush: (() -> Unit)? = null
    }

    private fun write(line: String, warn: Boolean = false) {
        runCatching { sink(line, warn) }
    }

    /**
     * Surface [surface] is now [visible] on [display]. Writes nothing when that is not a change. [transition] is the
     * name of the animation WIRED for this edge, or null when the edge snaps. A named transition arms the
     * `transition.missing` watch; the matching [transitionBegin] disarms it. An edge that names none may say WHY in [reason]
     * (`reduced_motion`: the animator scale is zero and the edge is a deliberate cut; `initial`: the surface was mounted already
     * showing, so nothing moved): the line reads `transition=none reason=...`, as `theme.change` does, and no watch is armed.
     *
     * [host] names the surface whose window composes this one, for the few that can be composed inside another: the picker, pin
     * actions and launch prompt are composed either in the Presentation's own surface group (null) or inside the Tabs drawer's window
     * ([BottomSurface.TABS]), and the two sort differently ([BottomSurface.drawingLayer]). It is remembered only while the surface is visible.
     */
    fun surface(
        surface: String,
        display: Int,
        visible: Boolean,
        transition: String? = null,
        other: String? = null,
        reason: String? = null,
        host: String? = null,
    ): Boolean {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return false
        synchronized(lock) {
            val key = surfaceKey(display, surface, host)
            val previous = surfaceVisible[key]
            if (previous == visible) return false
            // A surface that was never seen and is reported hidden has not exited anything.
            if (previous == null && !visible) {
                surfaceVisible[key] = false
                return false
            }
            // An entering surface goes to the end of the iteration order: it is the newest, so the highest of the ones above the base.
            if (visible) surfaceVisible.remove(key)
            surfaceVisible[key] = visible
            if (visible) {
                write(BottomUiReceiptFormat.surfaceEnter(surface, display, from = other, transition = transition, reason = reason, host = host))
            } else {
                write(BottomUiReceiptFormat.surfaceExit(surface, display, to = other, transition = transition, reason = reason, host = host))
            }
            if (transition != null) armMissing(transition, surface, display)
            _surfaceEdges.tryEmit(display)
            // An EXIT of anything but a scene is never held (A5 re-check): the hold below bridges the gap where a scene has left and
            // the next has not yet entered, and an overlay leaving is not that gap. Held, it left the previous snapshot, still naming
            // the exited surface as the top, standing for the whole settle interval: the stale line the hold exists to prevent.
            writeStack(display, REASON_CHANGE, immediate = !visible && surface !in BottomSurface.SCENE_BASE)
            return true
        }
    }

    /**
     * The surfaces visible on [display], bottom to top IN DRAWING ORDER: each surface's layer ([BottomSurface.drawingLayer], derived
     * from where it is composed), and the order it entered only to break a tie inside one layer. The LAST element is therefore what
     * is drawn on top, which is what a reader that taps the top of the stack relies on. A surface the table does not know draws
     * above everything.
     */
    fun surfaceStack(display: Int): List<String> = synchronized(lock) {
        val prefix = "$display:"
        val visible = surfaceVisible.entries.filter { it.value && it.key.startsWith(prefix) }.map { keyParts(it.key.substring(prefix.length)) }
        // Only the Presentation's own surface group is raised by an open picker (its z 0 -> 3): one composed inside another window raises nothing.
        val raised = BottomSurface.raisesSurfaceGroup(visible.filter { it.second == null }.map { it.first })
        visible.withIndex()
            .sortedWith(compareBy({ BottomSurface.drawingLayer(it.value.first, raised, it.value.second) }, { it.index }))
            .map { it.value.first }
    }

    /**
     * One `surface.state` line for [display]: [REASON_READY] at `presentation.ready`, [REASON_HEARTBEAT] on the timer. A surface
     * edge writes its own ([REASON_CHANGE]). Written only when [stackSnapshots] is on, and never off a Bottom display.
     */
    fun stackSnapshot(display: Int, reason: String) {
        synchronized(lock) { writeStack(display, reason) }
    }

    /**
     * Writes the stack, except that a [REASON_CHANGE] snapshot with surfaces in it and no base under them is HELD for
     * [STACK_SETTLE_MS] instead. That is the instant between a scene's exit and the next scene's enter (two receipts, one commit):
     * a line written there would read `stack=dock`, a stack with no floor, and the reader that trusts the last element would act
     * on it. The enter that follows writes the one real line and drops the held one; a display that genuinely has no scene (idle,
     * with the dock up) is stated when the hold runs out. Every other write cancels a held one, so a line is never written twice.
     *
     * [immediate] skips the hold: the exit of a surface that is not a scene publishes the current membership at once, and drops any
     * held line the way every other write does, so an exit is never left behind a snapshot that still names the surface.
     */
    private fun writeStack(display: Int, reason: String, immediate: Boolean = false) {
        if (!stackSnapshots || display == BottomUiReceiptFormat.NO_DISPLAY) return
        settlingStack.remove(display)?.cancel?.invoke()
        val stack = surfaceStack(display)
        if (!immediate && reason == REASON_CHANGE && stack.isNotEmpty() && stack.none { it in BottomSurface.SCENE_BASE }) {
            val held = SettlingStack()
            settlingStack[display] = held
            held.cancel = timer.after(STACK_SETTLE_MS) {
                synchronized(lock) {
                    if (settlingStack[display] !== held) return@after
                    settlingStack.remove(display)
                    write(BottomUiReceiptFormat.surfaceState(display, surfaceStack(display), reason))
                }
            }
            return
        }
        write(BottomUiReceiptFormat.surfaceState(display, stack, reason))
    }

    /**
     * Writes a `surface.state` line for [display] every [intervalMs] until the caller's coroutine is cancelled, so a log ring that
     * rotated the surface edges out still names the stack within one interval. One timer, no per-frame work. [wait] is the clock:
     * `delay` in production, a fake in a test.
     */
    suspend fun runStackHeartbeat(
        display: Int,
        intervalMs: Long = STACK_HEARTBEAT_MS,
        wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
    ) {
        if (!stackSnapshots || display == BottomUiReceiptFormat.NO_DISPLAY) return
        while (true) {
            wait(intervalMs)
            stackSnapshot(display, REASON_HEARTBEAT)
        }
    }

    /**
     * The secondary-home floor's startup cover is gone: the Presentation's first frame is committed, and its window (with its own
     * backing) is what the player sees. The floor Activity itself never leaves, so its receipt cannot write this edge; the host
     * that commits the frame does. `to=` is the scene that now stands over the cover (`none` when none is entered yet), and
     * `transition=none` because THIS edge names no fade: the window's own backing replaces the floor's cover on that frame. The Presentation may wear its own
     * copy of the cover over that first frame and fade it out on `OverlayMs` (`MimirCompanionPresentation.coldStartHandoff`); that fade is the window's and writes
     * no receipt, so the stream is the same with or without it.
     * Idempotent: a later `ready` (a reclaim that recreated the window) finds the cover already gone and writes nothing.
     */
    fun coldStartCoverGone(display: Int): Boolean = synchronized(lock) {
        val to = surfaceStack(display).lastOrNull { it != BottomSurface.COLD_START && it in BottomSurface.SCENE_BASE }
        surface(BottomSurface.COLD_START, display, visible = false, other = to)
    }

    /**
     * Whether the log believes [surface] is visible on [display], composed in [host]'s window (null: the Presentation's own). Exactly
     * that one: the same surface composed in another window is another entry ([surfaceKey]).
     */
    fun isSurfaceVisible(surface: String, display: Int, host: String? = null): Boolean =
        synchronized(lock) { surfaceVisible[surfaceKey(display, surface, host)] == true }

    /** The entry for [surface] on [display]: unhosted as it always was, and with the window that composes it when it is not the Presentation's own. */
    private fun surfaceKey(display: Int, surface: String, host: String?): String =
        if (host == null) "$display:$surface" else "$display:$surface$HOST_SEPARATOR$host"

    /** A [surfaceKey] without its display prefix, back into the surface and the host (null when unhosted). */
    private fun keyParts(key: String): Pair<String, String?> =
        if (HOST_SEPARATOR in key) key.substringBefore(HOST_SEPARATOR) to key.substringAfter(HOST_SEPARATOR) else key to null

    private fun armMissing(name: String, surface: String, display: Int) {
        val key = "$display:$name"
        pending.remove(key)?.cancel?.invoke()
        val since = nowMs()
        // The animation's own effect can run a frame BEFORE the surface edge that names it; that is a begin that
        // already arrived, not a missing one.
        lastBeginMs[key]?.let { began -> if (since - began <= BEGIN_BEFORE_EDGE_MS) return }
        val cancel = timer.after(missingTransitionAfterMs) {
            synchronized(lock) {
                val entry = pending[key] ?: return@after
                pending.remove(key)
                write(
                    BottomUiReceiptFormat.transitionMissing(name, entry.surface, display, nowMs() - entry.sinceMs),
                    warn = true,
                )
            }
        }
        pending[key] = PendingTransition(surface, since, cancel)
    }

    /** An animation named [name] started on [display]. A begin for a name still running ends the old run as cancelled. */
    fun transitionBegin(name: String, display: Int) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return
        synchronized(lock) {
            val key = "$display:$name"
            pending.remove(key)?.cancel?.invoke()
            val now = nowMs()
            lastBeginMs[key] = now
            val current = running[key]
            if (current != null && now - current.startedMs <= COALESCE_BEGIN_MS) {
                // The same animation reported twice in the same moment (two composables watching one change).
                current.depth += 1
                return
            }
            if (current != null) {
                write(BottomUiReceiptFormat.transitionEnd(name, display, now - current.startedMs, "cancelled"))
            }
            unfinishedCancel.remove(key)?.invoke()
            running[key] = RunningTransition(now, 1)
            write(BottomUiReceiptFormat.transitionBegin(name, display))
            unfinishedCancel[key] = timer.after(unfinishedTransitionAfterMs) {
                synchronized(lock) {
                    val started = running[key] ?: return@after
                    write(BottomUiReceiptFormat.transitionUnfinished(name, display, nowMs() - started.startedMs), warn = true)
                }
            }
        }
    }

    /** The animation ended. An end with no begin is written as `result=orphan` rather than dropped. */
    fun transitionEnd(name: String, display: Int, result: String = "done") {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return
        synchronized(lock) {
            val key = "$display:$name"
            val current = running[key]
            if (current == null) {
                write(BottomUiReceiptFormat.transitionEnd(name, display, 0L, "orphan"), warn = true)
                return
            }
            if (current.depth > 1) {
                current.depth -= 1
                return
            }
            running.remove(key)
            unfinishedCancel.remove(key)?.invoke()
            write(BottomUiReceiptFormat.transitionEnd(name, display, nowMs() - current.startedMs, result))
        }
    }

    /** Ends [name] only if it is running; for a transition whose end is observed by a signal that also fires at rest. */
    fun transitionEndIfRunning(name: String, display: Int, result: String = "done") {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return
        val running = synchronized(lock) { this.running.containsKey("$display:$name") }
        if (running) transitionEnd(name, display, result)
    }

    /** Runs [block] as one named transition: begin, then end `done`, or end `cancelled` if the coroutine is cancelled. */
    suspend fun <T> transition(name: String, display: Int, block: suspend () -> T): T {
        transitionBegin(name, display)
        try {
            val value = block()
            transitionEnd(name, display, "done")
            return value
        } catch (cancelled: CancellationException) {
            transitionEnd(name, display, "cancelled")
            throw cancelled
        }
    }

    /** A Bottom control was activated. Rate-limited per id so a held or hammered control cannot flood the log. */
    fun control(id: String, display: Int, surface: String? = null, target: String? = null) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return
        synchronized(lock) {
            val key = "$display:$id"
            val now = nowMs()
            val window = controlWindows.getOrPut(key) { ControlWindow(now, 0, 0) }
            if (now - window.startMs >= controlWindowMs) {
                window.startMs = now
                window.count = 0
            }
            if (window.count >= controlMaxPerWindow) {
                window.suppressed += 1
                return
            }
            window.count += 1
            val suppressed = window.suppressed
            window.suppressed = 0
            write(BottomUiReceiptFormat.control(id, display, surface, suppressed, target))
            if (controlWindows.size > MAX_TRACKED) controlWindows.clear()
        }
    }

    /**
     * An art-bearing tile's state, written when `(hasArt, state, plate)` changes for [tile]; the first observation is written.
     * `plate=visible hasArt=true` is written as a warning and carries `breach=true`.
     *
     * Rate-limited per tile: at most [artMaxPerWindow] lines per [artWindowMs]. A lazy list that scrolls a tile away and back
     * remounts it, which re-reports an unchanged tile's loading and loaded states; the limit bounds that. What is held back is
     * counted, and the tile's LATEST state is written when the window ends if it differs from the last line, carrying
     * `suppressed=N`, so the log never ends a window on a stale state.
     */
    fun artPlate(tile: String, display: Int, hasArt: Boolean, state: BottomUiArtState, plateVisible: Boolean) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return
        synchronized(lock) {
            val key = "$display:$tile"
            val effectiveState = if (hasArt) state else BottomUiArtState.NONE
            val observed = ArtObservation(tile, display, hasArt, effectiveState, plateVisible)
            val now = nowMs()
            val entry = artTiles.getOrPut(key) { ArtTile(windowStartMs = now) }
            if (entry.observed == observed) return
            entry.observed = observed
            if (now - entry.windowStartMs >= artWindowMs) {
                entry.windowStartMs = now
                entry.count = 0
            }
            if (entry.count >= artMaxPerWindow) {
                entry.suppressed += 1
                armArtFlush(key, entry, now)
            } else {
                writeArt(entry, observed)
            }
            if (artTiles.size > MAX_TRACKED) {
                val iterator = artTiles.entries.iterator()
                repeat(artTiles.size - MAX_TRACKED / 2) {
                    if (iterator.hasNext()) {
                        iterator.next().value.flush?.invoke()
                        iterator.remove()
                    }
                }
            }
        }
    }

    private fun writeArt(entry: ArtTile, observed: ArtObservation) {
        entry.count += 1
        entry.written = observed
        val suppressed = entry.suppressed
        entry.suppressed = 0
        write(
            BottomUiReceiptFormat.artPlate(
                observed.tile, observed.display, observed.hasArt, observed.state, observed.plateVisible, suppressed,
            ),
            warn = BottomUiReceiptFormat.isArtPlateBreach(observed.hasArt, observed.plateVisible),
        )
    }

    /** At the end of the tile's window, write the latest state if the held-back lines left the log on a different one. */
    private fun armArtFlush(key: String, entry: ArtTile, now: Long) {
        if (entry.flush != null) return
        val delay = (entry.windowStartMs + artWindowMs - now).coerceAtLeast(1L)
        entry.flush = timer.after(delay) {
            synchronized(lock) {
                if (artTiles[key] !== entry) return@after
                entry.flush = null
                val latest = entry.observed
                entry.windowStartMs = nowMs()
                entry.count = 0
                if (latest != null && latest != entry.written) writeArt(entry, latest) else entry.suppressed = 0
            }
        }
    }

    /** The focus owner on [display] changed to [owner]. Written only on a change. */
    fun focusOwner(owner: String?, display: Int) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return
        synchronized(lock) {
            val previous = focusOwners[display]
            if (focusOwners.containsKey(display) && previous == owner) return
            focusOwners[display] = owner
            write(BottomUiReceiptFormat.focusOwner(owner, previous, display))
        }
    }

    /**
     * A scene backdrop on [display] says whether ITS theme switch is a cut ([reducedMotion]) or a crossfade. The backdrop remembers
     * that once per attach and drives its swap from the remembered value, so this is the value the screen will actually follow; a
     * receipt that sampled the live setting instead could disagree with it after the setting changes. [owner] identifies the
     * backdrop; [unregisterBackdropMotion] removes it when it leaves composition.
     */
    fun registerBackdropMotion(display: Int, owner: Any, reducedMotion: Boolean) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return
        synchronized(lock) { backdropCuts.getOrPut(display) { HashMap() }[owner] = reducedMotion }
    }

    fun unregisterBackdropMotion(display: Int, owner: Any) {
        synchronized(lock) {
            val owners = backdropCuts[display] ?: return
            owners.remove(owner)
            if (owners.isEmpty()) backdropCuts.remove(display)
        }
    }

    /**
     * Whether the theme switch on [display] is a cut, as the backdrops mounted there will perform it: a cut only if EVERY mounted
     * backdrop cuts (one crossfading backdrop is a crossfade that really runs). With no backdrop mounted there is nothing to follow,
     * and [live] (the current setting) is the answer.
     */
    fun themeSwitchIsCut(display: Int, live: Boolean): Boolean = synchronized(lock) {
        backdropCuts[display]?.values?.takeIf { it.isNotEmpty() }?.all { it } ?: live
    }

    /**
     * The Bottom's theme. Written only on a change, and not for the first observation of the same theme twice.
     *
     * [transition] names the crossfade WIRED for the switch. Under [reducedMotion] (the animator scale is zero) that crossfade is a
     * deliberate cut: the line says `transition=none reason=reduced_motion`, no watch is armed, and the backdrop writes no begin or
     * end, so a cut is never reported as a missing transition.
     */
    fun theme(themeId: String?, display: Int, transition: String? = null, reducedMotion: Boolean = false) {
        if (display == BottomUiReceiptFormat.NO_DISPLAY) return
        synchronized(lock) {
            val seen = themes.containsKey(display)
            val previous = themes[display]
            if (seen && previous == themeId) return
            themes[display] = themeId
            // The first theme a display shows is not a switch, so it names no transition and arms no watch.
            val wired = if (seen) transition else null
            val cut = wired != null && reducedMotion
            val named = if (cut) null else wired
            write(
                BottomUiReceiptFormat.theme(
                    themeId, display, previous = if (seen) previous else null, transition = named,
                    reason = if (cut) REASON_REDUCED_MOTION else null,
                ),
            )
            if (named != null) armMissing(named, BottomSurface.THEME_SWITCH, display)
        }
    }

    /** Presentation window lifecycle. These are already edges at their call sites, so they are written as given. */
    fun presentation(action: String, display: Int, reason: String?, op: String? = null, extra: Pair<String, Any?>? = null) {
        write(BottomUiReceiptFormat.presentation(action, display, reason, op, extra), warn = action == "fail")
    }

    /** A dual-screen game's session on the Bottom: park, park_failed (warns), return, exit or reclaim. */
    fun session(action: String, sessionId: String?, display: Int?, reason: String?, pkg: String? = null) {
        write(BottomUiReceiptFormat.session(action, sessionId, display, reason, pkg), warn = action == "park_failed")
    }

    /** A Tabs close outcome. Already an edge at its call sites. */
    fun tabClose(outcome: String, display: Int?, target: String?, op: String?) {
        write(BottomUiReceiptFormat.tabClose(outcome, display, target, op), warn = outcome == "failed")
    }

    fun displaySelect(selected: Int?, eligible: List<Int>) {
        write(BottomUiReceiptFormat.displaySelect(selected, eligible))
    }

    /**
     * A new Presentation window starts from "not seen" for the surfaces ITS composition owns ([owned], see
     * [BottomSurface.PRESENTATION_OWNED]) and for the theme it wears. A surface another window owns on the same display (the
     * secondary-home floor's cold-start cover, the return guard, the native menu, the Tabs drawer window) keeps its state, so its
     * own exit is still written when it goes.
     *
     * An owned surface that is still visible is CLOSED here with a proper `surface.exit` rather than forgotten, so the log never
     * holds an enter whose exit was swallowed, and the new window's enter that follows is a real edge.
     * The display owner is the runtime's, not the window's, so it is kept; so are art plates, whose own dedupe is per tile.
     */
    fun resetWindow(display: Int, owned: Set<String>) {
        synchronized(lock) {
            var closed = false
            for (surface in owned) {
                // Only the entry the Presentation's own composition made (no host): a picker composed inside the Tabs drawer or the quick menu
                // belongs to that window, which is still there, so its state and its own exit are kept.
                if (surfaceVisible.remove(surfaceKey(display, surface, null)) == true) {
                    write(BottomUiReceiptFormat.surfaceExit(surface, display))
                    closed = true
                }
            }
            // Stated at once, like any exit that is not a scene's (see [surface]): the window that went took the scene with it, and what is
            // left (the return guard, the quick menu, a hosted picker) is the whole stack, not a gap a scene is about to fill.
            if (closed) {
                _surfaceEdges.tryEmit(display)
                writeStack(display, REASON_CHANGE, immediate = true)
            }
            themes.remove(display)
            val prefix = "$display:"
            pending.entries
                .filter { (key, entry) -> key.startsWith(prefix) && entry.surface in owned }
                .forEach { (key, entry) ->
                    pending.remove(key)
                    entry.cancel()
                }
        }
    }

    companion object {
        /** Joins a surface to the window that composes it in an entry key ([surfaceKey]); no surface or host name contains it. */
        private const val HOST_SEPARATOR = '@'

        /**
         * Long enough for a shelf to wait for its first content frame before its enter animation starts (the
         * Presentation holds that up to ~400 ms), so a slow-but-wired transition is not reported missing.
         */
        const val DEFAULT_MISSING_AFTER_MS = 1_500L

        /** A transition that began this recently before its surface edge was written counts as that edge's. */
        const val BEGIN_BEFORE_EDGE_MS = 500L

        /** A second begin of a running transition within this long is the same run, not a restart. */
        const val COALESCE_BEGIN_MS = 100L
        const val DEFAULT_UNFINISHED_AFTER_MS = 4_000L
        const val DEFAULT_CONTROL_WINDOW_MS = 1_000L

        /** `reason=` on a theme line whose wired crossfade is a cut because the system's animator scale is zero. */
        const val REASON_REDUCED_MOTION = "reduced_motion"

        /** `reason=` on a surface edge for a surface mounted already showing: it entered nothing, so no transition ran. */
        const val REASON_INITIAL = "initial"

        /** `reason=` on `surface.state`: a surface edge, the Presentation's first frame, and the timer. */
        const val REASON_CHANGE = "change"
        const val REASON_READY = "ready"
        const val REASON_HEARTBEAT = "heartbeat"

        /**
         * How long a snapshot with no base under its overlays waits for the scene that is about to arrive. A scene swap's exit and
         * enter are one composition commit, so this is generous; it only bounds how long a baseless display (idle) goes unstated.
         */
        const val STACK_SETTLE_MS = 250L

        /** How many surface edges [surfaceEdges] keeps for a reader that is momentarily behind; older ones are dropped (see there). */
        private const val SURFACE_EDGE_BUFFER = 64

        /** How often the stack is restated while a Presentation is shown: five minutes. */
        const val STACK_HEARTBEAT_MS = 5 * 60 * 1_000L
        const val DEFAULT_CONTROL_MAX_PER_WINDOW = 6

        /** A tile writes at most this many art lines per window; the rest are counted and the latest state is flushed at its end. */
        const val DEFAULT_ART_WINDOW_MS = 5_000L
        const val DEFAULT_ART_MAX_PER_WINDOW = 3
        private const val MAX_TRACKED = 512
    }
}
