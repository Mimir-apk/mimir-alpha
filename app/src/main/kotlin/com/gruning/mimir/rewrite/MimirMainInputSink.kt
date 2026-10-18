package com.gruning.mimir.rewrite

import android.view.KeyEvent
import android.view.MotionEvent

/**
 * The volume/power set the platform owns even while an app is modal.
 *
 * Lifted out of [MainActivity] (it was `private fun isSystemKey`) so the display-4 floor decides the
 * same way: a system key must reach `super.dispatchKeyEvent` on whichever window received it, because
 * the framework — not Mimir — implements it. Everything else is Mimir's.
 */
internal fun isMimirSystemKey(keyCode: Int): Boolean = when (keyCode) {
    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
    KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
    -> true
    else -> false
}

/** What the secondary-display floor did with one event; the third value is the only one that logs. */
internal enum class MimirSecondaryInputRoute {
    /** Main was resumed and its authority consumed the event. */
    FORWARDED_TO_MAIN,

    /** Main was resumed and declined it. Absorbed anyway — see [MimirMainInputSink]. */
    ABSORBED_MAIN_DECLINED,

    /** No resumed Main to forward to. Absorbed, and logged once per keycode. */
    ABSORBED_NO_MAIN,
}

/**
 * Process-wide handle on the resumed [MainActivity]'s input authority, for windows that have no
 * authority of their own.
 *
 * ## Why this exists (AYN Thor, 2026-09-11 09:34, Mimir `468807603de9`)
 *
 * Display 4's floor ([CompanionSecondaryHomeActivity]) is the `SECONDARY_HOME`, and everything Mimir
 * puts on that display was non-focusable: the floor, the `Presentation`
 * (`MimirCompanionPresentation` — `FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCH_MODAL`) and the ᛗ overlay.
 * When Chrome closed itself and took the last foreign focusable window off display 4, display 4 stayed
 * the input-focused display (`dumpsys input` → `FocusedDisplayId: 4`, `FocusedWindows:` empty) with a
 * focused *app* and no focused *window*, so `InputDispatcher` parked every key and joystick event on
 * it — `"Waiting because no window has focus but ActivityRecord{… CompanionSecondaryHomeActivity}
 * may eventually add a window when it finishes starting"` — then pruned the queue and ANR'd the whole
 * process five seconds later: `"ANR in com.gruning.mimir.rewrite … Input dispatching timed out
 * (Application does not have a focused window)"`. An earlier trace of the same shape is on the device
 * from 2026-09-10 23:15, so this is a repeat, not a one-off.
 *
 * The floor is now focusable (and still `FLAG_NOT_TOUCHABLE`), which gives those events a window to
 * land on. This object is what makes landing there mean something: the floor has no shelf, no focus
 * ring and no router, so a key that arrives on it is routed to the resumed Main's authority — the same
 * entry point display 0's own `dispatchKeyEvent` uses, not a parallel copy of it.
 *
 * ## Absorb rather than fall through
 *
 * When nothing is registered here — Main paused, or between `onPause` and a successor's `onResume` —
 * the floor consumes the event instead of calling `super`. There is nothing underneath the floor to
 * receive a game/controller key, and the entire point of the fix is that no event is ever left
 * waiting. [ABSORBED_NO_MAIN][MimirSecondaryInputRoute.ABSORBED_NO_MAIN] is logged once per keycode so
 * absorbing is visible without a per-frame log during a held D-pad.
 *
 * ## Registration discipline
 *
 * `onResume` registers, `onPause` releases — but only the lambdas it registered itself
 * ([release] compares identity). Android can run a successor Activity's `onResume` before the outgoing
 * instance's `onPause`, and an unconditional clear there would unregister the live instance and leave
 * display 4 silently absorbing everything. Same guard, and same reason, as `MainActivity.onDestroy`'s
 * `ControllerMappingStore.onApplied` and `LaunchStore.dualScreenLaunchListener` releases.
 */
internal object MimirMainInputSink {

    /** The resumed Main's key lane: its `dispatchKeyEvent` body minus the `super` fallbacks. */
    @Volatile
    var keys: ((KeyEvent) -> Boolean)? = null

    /**
     * The resumed Main's generic-motion lane. Not optional: the Thor's D-pad arrives as HAT-axis
     * `MotionEvent`s, not key events (see `MainActivity.dispatchKeyEvent`'s FUX-11 note), so a
     * key-only sink would forward buttons and absorb every direction.
     */
    @Volatile
    var motion: ((MotionEvent) -> Boolean)? = null

    fun register(keys: (KeyEvent) -> Boolean, motion: (MotionEvent) -> Boolean) {
        this.keys = keys
        this.motion = motion
    }

    /**
     * The resumed Main's idle controller, as `MimirScreenIdleController.onUserInteraction`: re-arms the idle deadline and answers whether it woke a dimmed screen (that press is
     * then the wake press). For a pad route that runs AHEAD of [keys] and [motion] on the floor (the Activity page's), which would otherwise never reach Main's own idle rule.
     */
    @Volatile
    var interaction: (() -> Boolean)? = null

    fun registerInteraction(interaction: () -> Boolean) {
        this.interaction = interaction
    }

    /** Identity-guarded like [release]: a successor's registration survives the outgoing instance's. */
    fun releaseInteraction(interaction: () -> Boolean) {
        if (this.interaction === interaction) this.interaction = null
    }

    /** One press by a pad route ahead of Main: true when it woke a dimmed screen. False, and nothing done, when no Main is resumed. Reads the field once. */
    fun noteInteraction(): Boolean = interaction?.invoke() ?: false

    /** Release only what [register] was given by the same caller — see the class KDoc. */
    fun release(keys: (KeyEvent) -> Boolean, motion: (MotionEvent) -> Boolean) {
        if (this.keys === keys) this.keys = null
        if (this.motion === motion) this.motion = null
    }

    fun routeKey(event: KeyEvent): MimirSecondaryInputRoute = route(keys, event)

    fun routeMotion(event: MotionEvent): MimirSecondaryInputRoute = route(motion, event)

    /**
     * The absorb rule itself, in the one place both lanes call.
     *
     * Generic over the event so a JVM unit test can exercise the real rule with a stand-in event —
     * neither `KeyEvent` nor `MotionEvent` can be constructed meaningfully off-device, and a rule this
     * small is not worth a Robolectric window stack. [routeKey]/[routeMotion] are one-line
     * delegations, pinned as source by `CompanionSecondaryHomeFocusTest`.
     *
     * The sink is passed in rather than read here so each dispatch reads the `@Volatile` field exactly
     * once: a second read could return null after the first returned non-null and turn a forward into
     * an absorb mid-event.
     */
    fun <E : Any> route(sink: ((E) -> Boolean)?, event: E): MimirSecondaryInputRoute = when {
        sink == null -> MimirSecondaryInputRoute.ABSORBED_NO_MAIN
        sink(event) -> MimirSecondaryInputRoute.FORWARDED_TO_MAIN
        else -> MimirSecondaryInputRoute.ABSORBED_MAIN_DECLINED
    }
}
