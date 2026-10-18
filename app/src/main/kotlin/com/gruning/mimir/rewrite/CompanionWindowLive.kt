package com.gruning.mimir.rewrite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the Bottom's Presentation window is up and lit: the one fact anything that takes the pad ahead of Main (the Activity page's route, [CompanionActivityPadRoute])
 * follows, so a window the player cannot see never keeps eating the floor's pad events.
 *
 * It is not a flag of its own: it reads the two states the Presentation already holds. [parkedBehindHost] is the one `parkBehindSecondaryHost` sets, the reveal
 * (`rebindRenderOperation`) clears and `onStop` sets again, so a window parked or hidden behind another app, and one dismissed, are not live; [screenOn] is the device's
 * screen (ACTION_SCREEN_OFF and ACTION_SCREEN_ON), which a window that is meant to stay drawn while the primary is in the background does not otherwise hear of.
 *
 * Two ways to ask, for two needs. [isLive] reads both NOW, at the moment a pad event arrives, so the answer is right on the very event that follows a park, before any
 * collector has run (the window's frame clock stops with its lifecycle, but a pad event is dispatched all the same). [liveFlow] is each change of the answer, for the
 * effect that gives the route back and resets the router: it needs no recomposition, so it still runs for a window whose frame clock is paused.
 */
internal class CompanionWindowLive(
    private val parkedBehindHost: StateFlow<Boolean>,
    private val screenOn: StateFlow<Boolean>,
) {
    val isLive: Boolean get() = !parkedBehindHost.value && screenOn.value

    val liveFlow: Flow<Boolean> = combine(parkedBehindHost, screenOn) { parked, on -> !parked && on }.distinctUntilChanged()

    companion object {
        /** A host that is not the Bottom's window (Main, a preview, a test): never parked, never dark. */
        val Always: CompanionWindowLive = CompanionWindowLive(MutableStateFlow(false), MutableStateFlow(true))
    }
}

/**
 * Whether a scene swap on the Activity page snaps instead of animating. Three reasons to snap, each of them "there is nothing to watch it":
 * the player asked for reduced motion; the pane is parked (D24: Now Playing closing under a swipe to the shelf); or the whole WINDOW is not live (the earlier update, Thor
 * 2026-10-08: parked behind an app the Bottom is leased to, or the screen is dark). A parked window's frame clock is stopped with its lifecycle, so a crossfade
 * begun there never ends: the receipts read `transition.unfinished runningMs=4001` and the swap sat half-faded until the window came back. Snapped, the scene is
 * settled the moment it changes, whatever the window does next.
 */
internal fun companionSwapSnaps(reducedMotion: Boolean, paneMotionLive: Boolean, windowLive: Boolean): Boolean =
    reducedMotion || !paneMotionLive || !windowLive

/** What the device's screen is after [action] (ACTION_SCREEN_ON, ACTION_SCREEN_OFF); [current] for anything else. One place, so the receiver below and a test agree. */
internal fun screenOnAfter(action: String?, current: Boolean): Boolean = when (action) {
    Intent.ACTION_SCREEN_OFF -> false
    Intent.ACTION_SCREEN_ON -> true
    else -> current
}

/**
 * Follows the device's screen into [target] (true while it is on) until the returned function is called, which unregisters. The Presentation stays RESUMED through a sleep on
 * purpose (its UI is meant to stay live while the primary is in the background), so the screen going off reaches it nowhere else; the music panel's own screen-off close
 * watches the same broadcasts the same way. A system broadcast: not exported, no permission. A receiver that cannot be registered leaves [target] as it was and says so.
 */
internal fun watchScreenState(context: Context, target: MutableStateFlow<Boolean>): () -> Unit {
    val appContext = context.applicationContext ?: context
    target.value = appContext.getSystemService(PowerManager::class.java)?.isInteractive != false
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            target.value = screenOnAfter(intent.action, target.value)
        }
    }
    val registered = runCatching {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }.onFailure { Log.w("MimirCompanionWindow", "the window could not watch for the screen going off", it) }.isSuccess
    return {
        if (registered) runCatching { appContext.unregisterReceiver(receiver) }
    }
}
