package com.gruning.mimir.rewrite

import android.accessibilityservice.AccessibilityService
import android.util.Log

/**
 * The trackpad's Back plate : ONE Back on Main.
 *
 * The route is the service's global action, `GLOBAL_ACTION_BACK`, which needs no capability beyond the service's own and
 * reads nothing. On Android 13 it has no display argument: the framework injects a Back key press that goes to the FOCUSED
 * display. The pad is its own window on the Bottom and is `FLAG_NOT_FOCUSABLE` (see `trackpadPadWindowParams`), so touching
 * it does not take focus from Main, and the focused display stays whichever app the player is using on Main. Mimir never
 * injects KEYCODE_BACK to the Bottom's display itself.
 *
 * `FLAG_NOT_FOCUSABLE` preserves whichever display had focus; it does not force Main. What this cannot prove off the device
 * is which display the system calls focused at that moment (a tap on a Bottom activity just before opening the pad may have
 * moved it), and it cannot be read here (that needs window content, which the service never reads). The Thor proof is that
 * Main's app receives the Back. If it ever
 * lands on the Bottom, the proposed fallback is an injected edge swipe on display 0, which is the system's own Back gesture;
 * it is not built.
 */
internal fun mimirTrackpadPerformBack(service: AccessibilityService?): Boolean {
    if (service == null) return false
    val done = runCatching { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }.getOrDefault(false)
    // The focused display cannot be learned without reading the screen's windows, which this service never does, so the line
    // says only that the action was sent. The device check is whether Main's app received it.
    Log.i("MimirTrackpad", "back sent performed=$done")
    return done
}
