package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.companionnative.OverlayScreenActivity

/** Schedules one idle deadline and returns a cancellation handle. */
fun interface ScreenIdleSchedule {
    fun schedule(delayMs: Long, onDeadline: () -> Unit): () -> Unit
}

/**
 * Activity-local screen-idle and wake-ownership authority.
 *
 * Lifecycle/window-focus are collapsed at the call site: [onResume] means resumed and focused,
 * [onPause] means either was lost. This controller never changes Android's global timeout or
 * brightness setting. A normalized timeout of zero clears keep-screen-on and delegates to the
 * system; a nonzero timeout owns keep-screen-on, then dims mounted Mimir displays to 1% after the
 * interval. The next key, controller motion, or touch restores the exact prior window/system
 * authority before normal input dispatch continues.
 */
internal class MimirScreenIdleController(
    private val readTimeoutSeconds: () -> Int,
    private val applyDimmed: (Boolean) -> Unit,
    private val applyKeepScreenOn: (Boolean) -> Unit,
    private val scheduler: ScreenIdleSchedule,
    /**
     * Ends the session on a chassis that can be woken again. Returns whether the device was actually
     * put to sleep; false means the platform's own timeout has to take the screen instead.
     *
     * Defaulted to "no enforcement" so every existing caller and test keeps the old single-stage
     * shape until it opts in.
     */
    private val enforceDeviceSleep: () -> Boolean = { false },
) {
    private var resumed = false
    private var cancelDeadline: (() -> Unit)? = null
    private var deadlineGeneration = 0L
    /** Null until first reconcile so a stale host flag is still cleared when policy is false. */
    private var lastKeepScreenOn: Boolean? = null

    var isDimmed: Boolean = false
        private set

    fun onResume() {
        if (resumed) return
        resumed = true
        reconcile()
    }

    fun onPause() {
        if (!resumed) return
        resumed = false
        reconcile()
    }

    /** Returns true when this interaction woke dimmed displays and must be consumed as wake-only. */
    fun onUserInteraction(): Boolean {
        if (!resumed) return false
        val wasDimmed = isDimmed
        reconcile()
        return wasDimmed
    }

    /**
     * Round-5 triage B2: activity Mimir only observed, such as a touch inside a foreign app on the Bottom. It re-arms an
     * awake session and never wakes a dimmed one. The same observation also arrives for a touch on Mimir's own
     * Presentation, whose first touch after a dim is ruled wake-only (QA D10); waking here would end that swallow.
     */
    fun onPassiveActivity() {
        if (!resumed || isDimmed) return
        cancelDeadline()
        armDeadline()
    }

    /**
     * Round-5 triage B2: a touch on a native overlay window (the Bottom's ᛗ, its menu, the overlay-hosted Tabs drawer).
     * [OverlayScreenActivity.DIRECT] is interaction and may wake; the wake-only answer is dropped because that window
     * keeps handling its touch exactly as before. [OverlayScreenActivity.OUTSIDE] is [onPassiveActivity].
     */
    fun onOverlayScreenActivity(activity: OverlayScreenActivity) {
        when (activity) {
            OverlayScreenActivity.DIRECT -> onUserInteraction()
            OverlayScreenActivity.OUTSIDE -> onPassiveActivity()
        }
    }

    /** Called immediately after the Utility setting commits so the new interval owns this session. */
    fun onTimeoutChanged() {
        reconcile()
    }

    private fun reconcile() {
        cancelDeadline()
        wake()
        armDeadline()
    }

    private fun armDeadline() {
        val seconds = BrightnessMath.normalizeScreenTimeoutSeconds(readTimeoutSeconds())
        val keepScreenOn = resumed && seconds > 0
        if (lastKeepScreenOn != keepScreenOn) {
            lastKeepScreenOn = keepScreenOn
            applyKeepScreenOn(keepScreenOn)
        }
        if (!keepScreenOn) return
        val generation = ++deadlineGeneration
        cancelDeadline = scheduler.schedule(seconds * 1_000L) {
            if (!resumed || generation != deadlineGeneration) return@schedule
            cancelDeadline = null
            if (!isDimmed) {
                isDimmed = true
                applyDimmed(true)
            }
            armSleepDeadline(seconds, generation)
        }
    }

    /**
     * **Stage two: dimming precedes suspension.** Release keep-screen-on, then attempt privileged
     * sleep enforcement, including on Thor where the device can be awakened again.
     *
     * Before this, a non-zero timeout dimmed to 1% and then held [applyKeepScreenOn] for as long as
     * Mimir stayed focused. Nothing ever released it: not this method, not the deadline, not
     * [cancelDeadline]. Only [onPause] did, by way of `resumed` going false. So on the app that is
     * foreground by default, the display wakelock never dropped, `SystemSuspend`'s counter never
     * reached zero, `mem` was never written to `/sys/power/state`, and the device could not suspend.
     * At 600 s the setting reads "dim after ten minutes" and behaved as "dim after ten minutes, then
     * stay awake indefinitely".
     *
     * The second interval reuses the same configured value, so this needs no new preference and reads
     * as "dim after N, sleep N later".
     *
     * **Releasing comes first, and unconditionally.** [enforceDeviceSleep] is the privilege and may
     * legitimately be unavailable -- an unqualified chassis, a revoked grant, a poisoned transport.
     * Giving the screen back is not a privilege, so it happens either way and the platform's own
     * timeout becomes the fallback. Ordered release-then-enforce because a sleep that lands while
     * Mimir still holds keep-screen-on is a race the window flag can win.
     */
    private fun armSleepDeadline(seconds: Int, dimGeneration: Long) {
        cancelDeadline = scheduler.schedule(seconds * 1_000L) {
            if (!resumed || dimGeneration != deadlineGeneration) return@schedule
            cancelDeadline = null
            // The dim is still in force; only the hold on the screen ends here. A wake still goes
            // through wake(), which restores exactly what the dim overrode.
            if (lastKeepScreenOn != false) {
                lastKeepScreenOn = false
                applyKeepScreenOn(false)
            }
            enforceDeviceSleep()
        }
    }

    private fun wake() {
        if (!isDimmed) return
        isDimmed = false
        applyDimmed(false)
    }

    private fun cancelDeadline() {
        deadlineGeneration++
        cancelDeadline?.invoke()
        cancelDeadline = null
    }
}
