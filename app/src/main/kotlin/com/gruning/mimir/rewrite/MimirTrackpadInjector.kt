package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.ui.MimirPointerPosition
import kotlin.math.hypot

/**
 * D34b -- what the injector needs from the platform to place a stroke. The real implementation builds
 * `GestureDescription.StrokeDescription`s and calls `dispatchGesture`; tests record strokes and complete them by hand.
 * Plain floats keep every rule below (session guard, release, clamp) testable without the framework.
 */
internal interface TrackpadStrokes<S> {
    /** A stroke from one point to another; [previous] non-null continues that stroke (the held press). */
    fun stroke(
        previous: S?,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        willContinue: Boolean,
    ): S

    /** Dispatch it; [done] gets true when it completed and false when it was cancelled or not accepted. */
    fun dispatch(stroke: S, what: String, done: (completed: Boolean) -> Unit)
}

/**
 * Pixels from each edge of display 0 inside which an injected stroke must never START: the system's gesture zones. A touch
 * that goes down inside the left or right zone is read as the Back gesture (and one inside the bottom zone as Home or
 * Recents), whatever the stroke does after. The pointer may sit anywhere; only where a stroke begins is moved.
 */
internal data class GestureMargins(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        val None = GestureMargins(0f, 0f, 0f, 0f)
    }
}

/** The named floors for [GestureMargins] when the platform reports nothing larger . */
internal object TrackpadGestureZones {
    const val EdgeMarginDp = 48f
    const val BottomMarginDp = 32f

    /** The floors in px for [density], or the platform's own insets where they are larger. */
    fun margins(density: Float, apiLeft: Float = 0f, apiTop: Float = 0f, apiRight: Float = 0f, apiBottom: Float = 0f) = GestureMargins(
        left = maxOf(EdgeMarginDp * density, apiLeft),
        top = apiTop.coerceAtLeast(0f),
        right = maxOf(EdgeMarginDp * density, apiRight),
        bottom = maxOf(BottomMarginDp * density, apiBottom),
    )
}

/** [x] and [y] moved inward to the nearest point outside every zone of [margins] (the display's middle where the zones meet). */
internal fun safeStrokeStart(x: Float, y: Float, width: Int, height: Int, margins: GestureMargins): Pair<Float, Float> {
    fun fit(v: Float, low: Float, high: Float, size: Int): Float =
        if (low > high) (size - 1) / 2f else v.coerceIn(low, high)
    return fit(x, margins.left, (width - 1) - margins.right, width) to fit(y, margins.top, (height - 1) - margins.bottom, height)
}

/**
 * The trackpad's injection rules, platform-free. All calls arrive on the main thread.
 *
 * Isolation (security): [activate] mints a session token and every entry point takes the token the CALLER captured at
 * activation; a command whose token is not the live one is dropped. A held press gets its own id, bound to its session,
 * and only a lift carrying that id can release it. The one thing that bypasses a dead session is the owed release of a
 * press that is already down, because a finger-up is owed to the app underneath.
 */
internal class MimirTrackpadInjector<S>(
    private val strokes: TrackpadStrokes<S>,
    private val displaySize: () -> Pair<Int, Int>,
    private val screenOn: () -> Boolean,
    private val naturalScroll: () -> Boolean,
    /** The keyguard is up (the device is locked) even if the screen is still on: injection stops exactly as it does for screen-off. */
    private val keyguardLocked: () -> Boolean = { false },
    /** Where a stroke may not start. Read at each stroke so a rotation or an inset change applies at once. */
    private val gestureMargins: () -> GestureMargins = { GestureMargins.None },
    /** One Back on Main (a global action, never a stroke or a key to the Bottom); true when it was performed. */
    private val performBack: () -> Boolean = { false },
) {
    private var position: MimirPointerPosition? = null
    private var scrollX = 0f
    private var scrollY = 0f

    // Held press: the open stroke, the id and session it belongs to, where its last SEGMENT ended, where the finger
    // wants it now, and what is in flight.
    private var held: S? = null
    private var heldId = 0L
    private var heldSession = 0L
    private var lastHoldId = 0L
    private var holdLast = MimirPointerPosition(0f, 0f)
    private var holdPending: MimirPointerPosition? = null
    private var holdInFlight = false
    private var holdEnding = false

    // Where the pressed point sits relative to the pointer: a press that would begin inside a system-gesture zone is begun
    // further in, and the whole drag carries that offset (a continuation must start where the last segment ended).
    private var holdShiftX = 0f
    private var holdShiftY = 0f

    private var session = 0L
    private var lastSession = 0L

    /** The token is the live session's (not revoked, not superseded by a later activation). */
    private fun fresh(token: Long): Boolean = token != 0L && token == session

    /** The lift names the press it ends. */
    private fun ownsHold(token: Long, id: Long): Boolean = id != 0L && id == heldId && token == heldSession

    /** Whether the press that is down still has a live session behind it and a screen that is on. */
    private fun holdSessionLive(): Boolean = fresh(heldSession) && usable()

    /** An interactive screen with no keyguard over it: the only state in which anything is injected. */
    private fun usable(): Boolean = screenOn() && !keyguardLocked()

    /** Whether [token] may inject right now: it is the live session's and the screen is on. */
    fun isLive(token: Long): Boolean = fresh(token) && usable()

    /** Whether some session is live and the screen is on: the host draws the pointer only then. */
    val active: Boolean get() = session != 0L && usable()

    /** The pointer's place, always inside display 0's live bounds. */
    val pointer: MimirPointerPosition? get() = currentPosition()

    /**
     * Starts a session and returns its token, which the caller keeps for every later call. A session still open is
     * ended first (its press released), so two surfaces can never share a token.
     */
    fun activate(): Long {
        if (session != 0L) end(session)
        session = ++lastSession
        // A swipe of the old session may never report back, or report late: the new session retires it (its callback is a no-op), and starts with none waiting.
        claimSlotForNonSwipe()
        val (w, h) = displaySize()
        position = (position ?: MimirPointerPosition.centre(w, h)).reclamped(w, h)
        return session
    }

    fun move(token: Long, dx: Float, dy: Float): Boolean {
        if (!isLive(token)) return false
        val (w, h) = displaySize()
        val base = position ?: MimirPointerPosition.centre(w, h)
        position = base.moved(dx, dy, w, h)
        return true
    }

    fun click(token: Long): Boolean = tap(token, "click")

    /** The pad's Back plate: gated like every other action (a live session, a screen that is on, no keyguard). */
    fun back(token: Long): Boolean {
        if (!isLive(token)) return false
        return performBack()
    }

    fun doubleClick(token: Long): Boolean = tap(token, "double click second tap")

    fun scrollBy(token: Long, dx: Float, dy: Float) {
        if (!isLive(token)) return
        scrollX += dx
        scrollY += dy
    }

    /**
     * pad14: one scroll swipe is in flight at a time. The platform cancels a gesture that is still being dispatched when the next one is
     * dispatched, so a two-finger scroll ending (or a drum burst going out) within a swipe's duration used to TRUNCATE the swipe in flight:
     * its steps were lost. A swipe that ends while one is in flight now waits and goes the moment that one lands (swipes that wait are
     * merged into one, so nothing is lost and nothing is sent twice). A session that ends or is revoked drops what waits.
     */
    private var swipeInFlight = false
    private var queuedX = 0f
    private var queuedY = 0f
    private var queuedSession = 0L

    /**
     * pad14 fix round: ONE in-flight slot for every dispatch kind (swipe, tap, hold begin and hold segments). [slot] counts dispatches; the swipe in
     * flight is [currentSwipeId] under [currentSwipeToken], and its callback is a no-op unless both are still current (a reactivation or any newer
     * dispatch retires it). A tap or a hold dispatch cancels a swipe in flight and means the user moved on: it drops the waiting swipes. The waiting
     * queue drains only when the swipe that just ended is still the newest dispatch, so a cancel our own newer dispatch caused never sends the queue on top of it.
     */
    private var slot = 0L
    private var currentSwipeId = 0L
    private var currentSwipeToken = 0L

    /** A non-swipe dispatch is about to go out: it claims the slot, retires the swipe in flight and drops the waiting swipes. */
    private fun claimSlotForNonSwipe() {
        slot += 1
        currentSwipeId = 0L
        currentSwipeToken = 0L
        swipeInFlight = false
        queuedX = 0f
        queuedY = 0f
        queuedSession = 0L
    }

    private fun onSwipeDone(token: Long, id: Long) {
        if (id != currentSwipeId || token != currentSwipeToken) return
        currentSwipeId = 0L
        currentSwipeToken = 0L
        swipeInFlight = false
        if (slot == id) sendQueuedSwipe()
    }

    fun scrollEnd(token: Long) {
        if (!isLive(token)) {
            scrollCancel(token)
            return
        }
        val sign = if (naturalScroll()) 1f else -1f
        val vx = scrollX * ScrollGain * sign
        val vy = scrollY * ScrollGain * sign
        scrollX = 0f
        scrollY = 0f
        if (hypot(vx, vy) <= 0f) return
        if (swipeInFlight) {
            // Merged with any swipe already waiting (one session's: a new session's first end starts clean).
            if (queuedSession != token) {
                queuedX = 0f
                queuedY = 0f
            }
            queuedSession = token
            queuedX += vx
            queuedY += vy
            return
        }
        sendSwipe(token, vx, vy)
    }

    /** Sends the swipe that waited behind the one that just landed, if its session is still live. */
    private fun sendQueuedSwipe() {
        val vx = queuedX
        val vy = queuedY
        val token = queuedSession
        queuedX = 0f
        queuedY = 0f
        queuedSession = 0L
        if (token == 0L || !isLive(token)) return
        sendSwipe(token, vx, vy)
    }

    private fun sendSwipe(token: Long, vx: Float, vy: Float) {
        val length = hypot(vx, vy)
        val at = currentPosition() ?: return
        if (length < MinSwipePx) return
        val (w, h) = displaySize()
        // Keep the whole swipe on the display: shift the start rather than shortening the stroke.
        var endX = at.x + vx
        var endY = at.y + vy
        var startX = at.x
        var startY = at.y
        val clampedEndX = endX.coerceIn(0f, (w - 1).toFloat())
        val clampedEndY = endY.coerceIn(0f, (h - 1).toFloat())
        startX += clampedEndX - endX
        startY += clampedEndY - endY
        endX = clampedEndX
        endY = clampedEndY
        // Never START in a system-gesture zone (an edge start is the Back gesture): move the whole stroke inward, keeping its
        // direction and length. Only if the display cannot hold the shifted stroke is the far end clamped.
        val (safeX, safeY) = safeStrokeStart(startX, startY, w, h, gestureMargins())
        endX = (endX + (safeX - startX)).coerceIn(0f, (w - 1).toFloat())
        endY = (endY + (safeY - startY)).coerceIn(0f, (h - 1).toFloat())
        startX = safeX
        startY = safeY
        val duration = (length / 1.8f).toLong().coerceIn(120L, 450L)
        val stroke = strokes.stroke(
            null,
            startX.coerceIn(0f, (w - 1).toFloat()),
            startY.coerceIn(0f, (h - 1).toFloat()),
            endX,
            endY,
            duration,
            false,
        )
        slot += 1
        val id = slot
        currentSwipeId = id
        currentSwipeToken = token
        swipeInFlight = true
        strokes.dispatch(stroke, "scroll") { onSwipeDone(token, id) }
    }

    fun scrollCancel(token: Long) {
        if (!fresh(token)) return
        scrollX = 0f
        scrollY = 0f
    }

    /** Presses and holds at the pointer. Returns the id of the new press, or 0 when nothing was pressed. */
    fun holdBegin(token: Long): Long {
        if (held != null || !isLive(token)) return 0L
        val at = currentPosition() ?: return 0L
        val id = ++lastHoldId
        val (w, h) = displaySize()
        val (pressX, pressY) = safeStrokeStart(at.x, at.y, w, h, gestureMargins())
        holdShiftX = pressX - at.x
        holdShiftY = pressY - at.y
        val stroke = strokes.stroke(null, pressX, pressY, pressX, pressY, HoldFirstMs, true)
        held = stroke
        heldId = id
        heldSession = token
        holdLast = MimirPointerPosition(pressX, pressY)
        holdPending = null
        holdEnding = false
        holdInFlight = true
        claimSlotForNonSwipe()
        strokes.dispatch(stroke, "hold begin") { completed ->
            holdInFlight = false
            // A lift or end that arrived while this first segment was in flight still gets its release.
            if (!completed && !holdEnding) resetHold() else pumpHold()
        }
        return id
    }

    fun holdMove(token: Long, id: Long, dx: Float, dy: Float) {
        if (held == null || holdEnding || !ownsHold(token, id) || !move(token, dx, dy)) return
        holdPending = position
        pumpHold()
    }

    /** The finger lifted: releases the press [id] names and no other. Never gated by the session: the release is owed. */
    fun holdEnd(token: Long, id: Long) {
        if (held == null || !ownsHold(token, id)) return
        holdEnding = true
        pumpHold()
    }

    /**
     * Ends the session [token] names (a stale token ends nothing), revoking it FIRST so nothing new can be injected,
     * then releases any held press. If a segment is still in flight the release goes the moment it lands. Returns
     * whether a live session ended.
     */
    fun end(token: Long): Boolean {
        if (!fresh(token)) return false
        session = 0L
        scrollX = 0f
        scrollY = 0f
        queuedX = 0f
        queuedY = 0f
        queuedSession = 0L
        holdPending = null
        if (held != null) {
            holdEnding = true
            pumpHold()
        }
        return true
    }

    /** Ends whichever session is live (the screen going off, the service going away). */
    fun endCurrent(): Boolean = end(session)

    /**
     * Lets go of a held press WITHOUT ending the session (the service was interrupted by Android): the owed finger-up
     * goes, nothing else changes, and the activation carries on.
     */
    fun releaseHold() {
        if (held == null) return
        holdEnding = true
        pumpHold()
    }

    private fun tap(token: Long, what: String): Boolean {
        if (!isLive(token)) return false
        val at = currentPosition() ?: return false
        claimSlotForNonSwipe()
        strokes.dispatch(strokes.stroke(null, at.x, at.y, at.x, at.y, TapMs, false), what) { }
        return true
    }

    /** A NEW destination is clamped to display 0's live bounds at the moment it is built (rotation, resize). */
    private fun live(point: MimirPointerPosition): MimirPointerPosition {
        val (w, h) = displaySize()
        return point.reclamped(w, h)
    }

    private fun pumpHold() {
        val base = held ?: return
        if (holdInFlight) return
        // Before any non-release segment: a dead session, a locked device or a screen that is off turns this into the owed
        // release. The queued move is dropped even when the press is ALREADY ending, so a lock followed by a finger-up
        // releases where the press is, never at the position the dead session wanted.
        if (!holdSessionLive()) {
            holdEnding = true
            holdPending = null
        }
        val release = holdEnding
        val pending = holdPending
        // A continuation starts exactly where the previous segment ENDED (Android rejects any other start); only the
        // new destination is clamped.
        val from = holdLast
        val target = when {
            pending != null -> live(MimirPointerPosition(pending.x + holdShiftX, pending.y + holdShiftY))
            release -> live(holdLast)
            else -> return
        }
        val next = strokes.stroke(base, from.x, from.y, target.x, target.y, if (release) HoldEndMs else HoldStepMs, !release)
        holdPending = null
        holdInFlight = true
        claimSlotForNonSwipe()
        strokes.dispatch(next, if (release) "hold end" else "hold move") { completed ->
            holdInFlight = false
            when {
                // The release is the last segment of the press, however it ended.
                release -> resetHold()
                // A segment landed: carry on from it. If a lift (or an end) arrived while it was in flight, that carries
                // on into the release.
                completed -> {
                    held = next
                    holdLast = target
                    pumpHold()
                }
                // A cancelled continuation with a lift owed still gets its release, from the last good stroke.
                holdEnding -> pumpHold()
                else -> resetHold()
            }
        }
    }

    private fun resetHold() {
        held = null
        heldId = 0L
        heldSession = 0L
        holdPending = null
        holdEnding = false
        holdInFlight = false
        holdShiftX = 0f
        holdShiftY = 0f
    }

    private fun currentPosition(): MimirPointerPosition? {
        val clamped = live(position ?: run { val (w, h) = displaySize(); MimirPointerPosition.centre(w, h) })
        position = clamped
        return clamped
    }

    companion object {
        const val TapMs = 40L
        const val HoldFirstMs = 60L
        const val HoldStepMs = 30L
        const val HoldEndMs = 10L
        const val ScrollGain = 2.2f
        const val MinSwipePx = 20f
    }
}
