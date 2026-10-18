package com.gruning.mimir.rewrite.core.companionnative

import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent

/**
 * R9a : when the Bottom's ᛗ menu opens, the PAD walks it and everything it opens. The rest of the Bottom
 * stays touch-first, and system BACK to display 4 stays out (R9).
 *
 * Who holds the Bottom's pad at this moment. Null: nobody, the Bottom is touch-only and its windows are not focusable. A
 * value: that surface owns the pad, and the ᛗ menu overlay window is focusable for exactly as long as it is set (the study's
 * Option 2). Slice 1 sets only [MENU]; the panel owners are named here so the one field and the one focus rule do not change
 * shape when slice 2 adds them.
 *
 * The owner is never a request for focus. It is set only by the existing touch long-press on the Bottom's ᛗ, and nothing
 * calls `requestFocus` across displays, so a game running on Main keeps the pad whenever the platform has focus on display 0.
 */
internal enum class BottomPadOwner {
    MENU,
    UTILITY_TWEAKER,
    QUICK_LAUNCH,
    TABS,
}

/**
 * The Bottom floor's way to the pad owner (`CompanionSecondaryHomeActivity`).
 *
 * The floor is the one window on display 4 that is always focusable (the 09-11 ANR guard), so a pad event can land there even
 * while the menu owns the pad: before the menu window has taken key focus, or in the gap after a window swap. The floor asks
 * this holder FIRST, ahead of Main's input sink, so such an event still reaches the menu instead of walking Main's shelf.
 * With no owner registered both calls answer false and the floor behaves exactly as it did.
 *
 * Main thread only: the floor dispatches on it and so does the controller that registers here. Each call reads the
 * `@Volatile` field once, as `MimirMainInputSink.route` does, so a release between a null check and the call cannot turn a
 * forward into a crash.
 */
object BottomPadInput {
    @Volatile
    private var keys: ((KeyEvent) -> Boolean)? = null

    @Volatile
    private var motion: ((MotionEvent) -> Boolean)? = null

    /**
     * Advances whenever the pad's owner changes (the menu or a panel takes it, or gives it back). A route that keeps a press-in-progress across events (the Activity page's
     * wake press) compares it with the value it saw, so a press the owner took the release of is not still being waited for after the owner has gone.
     */
    @Volatile
    var ownerEpoch: Long = 0L
        private set

    /**
     * Advances when a route that is NOT the pad's owner takes the pad or gives it back (the Activity page's route, which the floor asks ahead of Main's sink while Main keeps its
     * own lane). It is a boundary for the idle hold cap ONLY: a hold another lane has been earning activity credit for starts fresh after it. It is not a change of who owns the
     * pad, so it never touches a wake press in progress (that is [ownerEpoch]'s, and moves only for real ownership changes).
     */
    @Volatile
    var holdEpoch: Long = 0L
        private set

    fun noteHoldBoundary() {
        holdEpoch += 1L
    }

    internal fun register(keys: (KeyEvent) -> Boolean, motion: (MotionEvent) -> Boolean) {
        this.keys = keys
        this.motion = motion
        ownerEpoch += 1L
    }

    /** Releases only what [register] was given by the same caller, so a stale release cannot unplug a successor. */
    internal fun release(keys: (KeyEvent) -> Boolean, motion: (MotionEvent) -> Boolean) {
        if (this.keys === keys || this.motion === motion) ownerEpoch += 1L
        if (this.keys === keys) this.keys = null
        if (this.motion === motion) this.motion = null
    }

    /** True when the owner consumed [event]; false (and nothing done) when no owner is registered. */
    fun routeKey(event: KeyEvent): Boolean {
        val sink = keys ?: return false
        return sink(event)
    }

    fun routeMotion(event: MotionEvent): Boolean {
        val sink = motion ?: return false
        return sink(event)
    }
}

/**
 * Where a pad A waits for the frame's pad input. Android hands a button over the moment it arrives and holds joystick
 * (hat) motion until the next frame reads it, so the A can reach the menu ahead of a hat press that really came first
 * (see [QuickMenuPadOrder]). The block runs after the frame's input stage, which is where the batched motion is delivered.
 * Returns the cancel for the pending callback.
 */
internal fun interface MenuPadFrameScheduler {
    fun afterNextFrameInput(block: () -> Unit): () -> Unit
}

/** Main's own pattern (`MainActivity.inputRouter`'s `padFrameScheduler`): a Choreographer frame callback. */
internal object ChoreographerMenuPadFrameScheduler : MenuPadFrameScheduler {
    override fun afterNextFrameInput(block: () -> Unit): () -> Unit {
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { block() }
        choreographer.postFrameCallback(callback)
        return { choreographer.removeFrameCallback(callback) }
    }
}
