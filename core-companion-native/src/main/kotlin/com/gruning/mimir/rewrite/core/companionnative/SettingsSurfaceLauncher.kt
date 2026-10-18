package com.gruning.mimir.rewrite.core.companionnative

import android.app.Activity
import android.content.Context
import android.content.Intent
import java.lang.ref.WeakReference

/**
 * The Mimir Activity that is RESUMED right now, if any. Weakly held; set from `onResume`, released from
 * `onPause` (identity-guarded, so a successor copy that already registered is never cleared by the old one).
 *
 * Why this exists: the system Settings screens Mimir sends the player to (Draw over other apps, Usage
 * Access) used to be started from the application context with `FLAG_ACTIVITY_NEW_TASK`, which gives each
 * its own task and its own Recents card -- device-observed 2026-10-01, Usage Access reopened from Recents
 * two minutes after the grant. Started from Mimir's own Activity without NEW_TASK, the screen joins Mimir's
 * task, as the All-files screen already does.
 */
object ResumedHostActivity {
    @Volatile
    private var ref: WeakReference<Activity>? = null

    fun onResumed(activity: Activity) {
        ref = WeakReference(activity)
    }

    fun onPaused(activity: Activity) {
        if (ref?.get() === activity) ref = null
    }

    /** A resumed Activity that is still alive, or null. */
    fun current(): Activity? = ref?.get()?.takeUnless { it.isFinishing || it.isDestroyed }
}

/**
 * The flags a Settings surface starts with. From an Activity: NEW_TASK is removed, so the screen stays in
 * Mimir's task. From the application context there is no task to join, so NEW_TASK is required -- that is
 * the fallback, and the only behaviour before this change.
 */
internal fun settingsSurfaceFlags(baseFlags: Int, hasHostActivity: Boolean): Int =
    if (hasHostActivity) {
        baseFlags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
    } else {
        baseFlags or Intent.FLAG_ACTIVITY_NEW_TASK
    }

/**
 * Start [intent] from Mimir's resumed Activity when there is one, otherwise from [appContext] as before.
 * A host start that throws falls through to the application-context start, so a grant is never lost to
 * this change. Throws only when that fallback throws, exactly as the old direct start did. Public so the app's Settings rows
 * (Notification access) start their surfaces the same way the Usage Access and Draw-over-apps requests do.
 */
fun startSettingsSurface(
    appContext: Context,
    intent: Intent,
    host: Activity? = ResumedHostActivity.current(),
) {
    if (host != null) {
        val hosted = Intent(intent).also { it.flags = settingsSurfaceFlags(it.flags, hasHostActivity = true) }
        if (runCatching { host.startActivity(hosted) }.isSuccess) return
    }
    val detached = Intent(intent).also { it.flags = settingsSurfaceFlags(it.flags, hasHostActivity = false) }
    appContext.startActivity(detached)
}
