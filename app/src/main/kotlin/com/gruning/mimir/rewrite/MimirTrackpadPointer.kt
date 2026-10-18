package com.gruning.mimir.rewrite

import android.content.Context
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.gruning.mimir.rewrite.core.ui.MimirAppearanceOverrides
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirTrackpadPointerPainter
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerColor
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerFill
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerGeometry
import com.gruning.mimir.rewrite.core.ui.TrackpadPointerShape
import com.gruning.mimir.rewrite.core.ui.trackpadPointerFill

/**
 * The pointer drawn on Main. Pure drawing: no touch, no focus, nothing about what is under it. What it looks like is the
 * player's choice (Settings > Controls > Trackpad); [applyStyle] lays it out and [onDraw] only paints, allocating nothing.
 */
internal class MimirPointerView(context: Context) : View(context) {
    private val painter = MimirTrackpadPointerPainter()

    fun applyStyle(shape: TrackpadPointerShape, windowPx: Int, fill: TrackpadPointerFill) {
        painter.configure(shape, windowPx, fill)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        painter.draw(canvas)
    }
}

/** Below Android's 0.8 maximum obscuring opacity for touches, with margin, and still plainly visible. */
internal const val FallbackPointerAlpha = 0.75f

/**
 * The pointer window's layout. Pure so the flags, the opacity and the size are testable.
 *
 * Why neither shape can swallow the click it places. Verified on Android 13 (android13-release `InputDispatcher.cpp`
 * `canBeObscuredBy` and `computeTouchOcclusionInfoLocked`, `WindowState.getTouchOcclusionMode`,
 * `InputMonitor.isTrustedOverlay`) and read again on Android 14:
 *  - FLAG_NOT_TOUCHABLE keeps this window out of the targets of any touch, injected or real.
 *  - Whether it can still BLOCK the touch to the app beneath is the obscuring rule, and that reads the window's frame and its
 *    layer alpha, never what it has drawn. A TYPE_ACCESSIBILITY_OVERLAY window is a trusted overlay: it is never an occluder.
 *    The TYPE_APPLICATION_OVERLAY fallback is an untrusted overlay in opacity mode: a touch under its frame is dropped only
 *    if the opacity there exceeds the 0.8 maximum, and [FallbackPointerAlpha] is 0.75.
 * So the dot's window may sit on the click point on the accessibility overlay only (a hole in its middle would change nothing,
 * since the frame is what counts). The fallback's opacity adds up per app across every untrusted overlay Mimir has up, and a
 * player or vendor can lower the 0.8 maximum, so there the dot takes the arrow's off-click origin and its frame never contains
 * the click: see [mimirTrackpadPointerOrigin].
 */
internal fun mimirTrackpadPointerParams(type: Int, sizePx: Int): WindowManager.LayoutParams =
    WindowManager.LayoutParams(
        sizePx,
        sizePx,
        type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "MimirTrackpadPointer"
        // The application-overlay fallback is an UNTRUSTED overlay: Android blocks touches to apps beneath one drawn above
        // the maximum obscuring opacity (0.8), which would swallow the very click the trackpad injects at the pointer.
        // The accessibility overlay is trusted and keeps full opacity.
        if (type == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) alpha = FallbackPointerAlpha
    }

/**
 * Where the pointer window's top-left goes for a click at ([x], [y]) when the window is of [windowType]: the slim arrow's
 * window one pixel down and right of it (so the window never covers the click point and the tip is at the click); the dot's
 * window centred on it on the accessibility overlay, a trusted overlay Android never counts as an occluder, and one pixel
 * down and right of it, like the arrow's, on any other window type (the fallback), so that window's frame never contains
 * the click.
 */
internal fun mimirTrackpadPointerOrigin(
    shape: TrackpadPointerShape,
    x: Float,
    y: Float,
    windowPx: Int,
    windowType: Int,
): Pair<Int, Int> = TrackpadPointerGeometry.origin(
    shape,
    x,
    y,
    windowPx,
    mayCoverClick = windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
)

/**
 * The fill for [color] under the palette the app's covers draw with, appearance overrides applied (the lookup the companion
 * overlay's theme push uses), so the pointer is the player's accent and the theme's metal.
 */
internal fun mimirTrackpadPointerFill(
    color: TrackpadPointerColor,
    themeId: MimirThemeId = ThemeStore.themeId.value,
): TrackpadPointerFill = trackpadPointerFill(color, MimirAppearanceOverrides.effectivePalette(themeId))
