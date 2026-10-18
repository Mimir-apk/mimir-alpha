package com.gruning.mimir.rewrite

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.View

/**
 * Opening another player's app from the music panel's artwork : the session's own activity when it offers one,
 * else the package's launch intent, with the window scaling up out of the artwork it was tapped from. The panel detaches in the same
 * frame; Back from that app returns to what was underneath, because nothing here adds a task flag that would bring Mimir forward
 * (the launch intent carries only the flag the package manager gives it, which is what any launcher's tap carries).
 */
internal enum class OtherPlayerLaunchKind {
    /** The session's own activity ([android.media.session.MediaController.getSessionActivity]): the player's now-playing screen. */
    SESSION_ACTIVITY,

    /** The package's launcher activity, when the session offers no activity of its own. */
    LAUNCH_INTENT,

    /** Nothing to open (a session with no activity, and a package with no launch intent visible to Mimir). */
    NONE,
}

/** The session's activity wins; the launch intent is the fallback; neither is [OtherPlayerLaunchKind.NONE]. */
internal fun otherPlayerLaunchKind(hasSessionActivity: Boolean, hasLaunchIntent: Boolean): OtherPlayerLaunchKind = when {
    hasSessionActivity -> OtherPlayerLaunchKind.SESSION_ACTIVITY
    hasLaunchIntent -> OtherPlayerLaunchKind.LAUNCH_INTENT
    else -> OtherPlayerLaunchKind.NONE
}

/**
 * The rectangle the new window grows out of, in the source view's own pixels: the artwork's bounds, never negative and at least one
 * pixel on each side. With no bounds known (the art has not been laid out) it grows from the view's centre.
 */
internal data class ScaleUpOrigin(val startX: Int, val startY: Int, val width: Int, val height: Int)

internal fun scaleUpOrigin(bounds: Rect?, viewWidth: Int, viewHeight: Int): ScaleUpOrigin {
    if (bounds == null || bounds.isEmpty) {
        return ScaleUpOrigin(startX = (viewWidth / 2).coerceAtLeast(0), startY = (viewHeight / 2).coerceAtLeast(0), width = 1, height = 1)
    }
    return ScaleUpOrigin(
        startX = bounds.left.coerceAtLeast(0),
        startY = bounds.top.coerceAtLeast(0),
        width = bounds.width().coerceAtLeast(1),
        height = bounds.height().coerceAtLeast(1),
    )
}

private const val Tag = "MimirOtherPlayerLaunch"

/**
 * Starts the other player's app and returns whether it was started. Never throws: a dead pending intent or a refused start is a
 * false the panel answers with a toast, and the panel stays up.
 */
internal fun launchOtherPlayer(
    context: Context,
    sourceView: View?,
    bounds: Rect?,
    sessionActivity: PendingIntent?,
    launchIntent: Intent?,
): Boolean {
    val kind = otherPlayerLaunchKind(hasSessionActivity = sessionActivity != null, hasLaunchIntent = launchIntent != null)
    if (kind == OtherPlayerLaunchKind.NONE) return false
    val options = if (sourceView != null) {
        val origin = scaleUpOrigin(bounds, sourceView.width, sourceView.height)
        ActivityOptions.makeScaleUpAnimation(sourceView, origin.startX, origin.startY, origin.width, origin.height)
    } else {
        ActivityOptions.makeBasic()
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        // The panel is a visible window of Mimir's, over whatever is on screen: the pending intent may start its activity from it.
        runCatching {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        }
    }
    return try {
        when (kind) {
            OtherPlayerLaunchKind.SESSION_ACTIVITY -> sessionActivity!!.send(context, 0, null, null, null, null, options.toBundle())
            OtherPlayerLaunchKind.LAUNCH_INTENT -> context.startActivity(launchIntent!!, options.toBundle())
            OtherPlayerLaunchKind.NONE -> return false
        }
        true
    } catch (error: PendingIntent.CanceledException) {
        Log.w(Tag, "the session's activity was cancelled", error)
        fallBack(kind, context, sourceView, bounds, launchIntent)
    } catch (error: RuntimeException) {
        Log.w(Tag, "the player could not be opened", error)
        fallBack(kind, context, sourceView, bounds, launchIntent)
    }
}

/** A session activity that would not start leaves the package's launch intent to try; a launch intent that failed has nothing after it. */
private fun fallBack(kind: OtherPlayerLaunchKind, context: Context, sourceView: View?, bounds: Rect?, launchIntent: Intent?): Boolean =
    kind == OtherPlayerLaunchKind.SESSION_ACTIVITY && launchIntent != null &&
        launchOtherPlayer(context, sourceView, bounds, sessionActivity = null, launchIntent = launchIntent)
