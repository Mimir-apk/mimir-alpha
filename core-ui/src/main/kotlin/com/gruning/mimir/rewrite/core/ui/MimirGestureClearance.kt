package com.gruning.mimir.rewrite.core.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The strip at the bottom edge that the system's gesture handle (the white "home indicator" pill) is drawn in.
 *
 * Observed on Thor: the pill sat over the footer hints on every full-screen page that ends on one ("B BACK" in Settings, "A SELECT · B BACK" in the theme gallery, "REMOVE · Y ADJUST" in the
 * Mimir-button picker). Mimir is immersive-sticky, so the platform reports no bottom inset at all: `dumpsys window` on the Thor shows the navigation bar source with an empty frame and `visible=false`, the bottom
 * mandatory-gesture source empty too, and the Compose window insets are zero. The handle is a separate system window that stays up in gesture navigation, 55 px (24 dp at 369 dpi) tall, over whatever the app draws
 * there. So an inset-only fix would do nothing on the one device that has the defect.
 *
 * [mimirGestureHandleClearance] is what a footer keeps clear: the larger of the insets the platform does report (a navigation bar that is showing, the mandatory gesture zone) and, in gesture navigation, the handle's
 * strip, [MimirGestureHandleStripDp] (the platform's `navigation_bar_gesture_height` is not it: it is 48 dp in Android 14's framework, where the Thor's strip measures 24). It is 0 with three-button navigation and the bar hidden, and 0 where the platform reports nothing and navigation is not by gesture, so a handheld without
 * the handle, or not in gesture navigation, lays out exactly as it did. Home, Library and the dock do not use it: their layouts are fixed to the shelf and were not moved.
 */

/** Android's `navigation_mode` secure setting value for full gesture navigation (`Settings.Secure.NAVIGATION_MODE`, hidden API: the key is read by name). */
internal const val MimirNavigationModeGestural: Int = 2

/** The gesture-handle strip a footer clears, in dp: see the file comment. Re-read when the window or the density changes. */
@Composable
fun mimirGestureHandleClearance(): Dp {
    val density = LocalDensity.current
    val context = LocalContext.current
    val handlePx = remember(context) { mimirGestureHandleHeightPx(context) }
    val insetPx = max(WindowInsets.navigationBars.getBottom(density), WindowInsets.mandatorySystemGestures.getBottom(density))
    return with(density) { mimirGestureClearancePx(insetPx, handlePx).toDp() }
}

/** The clearance in px: the larger of the bottom inset the platform reports and the handle's strip ([handlePx], 0 when the device is not in gesture navigation). */
internal fun mimirGestureClearancePx(insetBottomPx: Int, handlePx: Int): Int = max(insetBottomPx.coerceAtLeast(0), handlePx.coerceAtLeast(0))

/**
 * The gesture handle's strip in px: [MimirGestureHandleStripDp] when the device navigates by gesture, else 0. The mode is the `navigation_mode` secure setting, or the platform's own
 * `config_navBarInteractionMode` when that key is absent; a platform that exposes neither reads as not gestural.
 */
internal fun mimirGestureHandleHeightPx(context: Context): Int {
    val resources = context.resources
    val mode = runCatching { Settings.Secure.getInt(context.contentResolver, "navigation_mode") }.getOrElse {
        val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        if (id == 0) -1 else runCatching { resources.getInteger(id) }.getOrDefault(-1)
    }
    return if (mode == MimirNavigationModeGestural) (MimirGestureHandleStripDp * resources.displayMetrics.density).roundToInt() else 0
}

/**
 * The handle's strip, 24 dp: what the Thor's navigation bar provides as its inset (55 px at 369 dpi, with the 111 px window it sits in, `dumpsys window`) and where the launch card's buttons
 * already end (55 px above the bottom edge on the Thor).
 */
internal const val MimirGestureHandleStripDp = 24
