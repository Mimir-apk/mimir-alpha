package com.gruning.mimir.rewrite

import android.view.Display

/**
 * K1: on the Bottom the input method's window fills the whole display (the keyboard grows to the screen instead of
 * sitting as a 720 dp strip). The size is of the display the input method is on, in dp, so the keyboard can derive its
 * key height from it; Main and phones get null and keep today's keyboard.
 */
internal data class ImeBottomFill(val widthDp: Float, val heightDp: Float, val heightPx: Int)

/** The fill for the display the IME is on, or null on Main (display 0) or when the size is not known yet. */
internal fun imeBottomFill(displayId: Int, widthPx: Int, heightPx: Int, density: Float): ImeBottomFill? {
    if (displayId == Display.DEFAULT_DISPLAY) return null
    if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return null
    return ImeBottomFill(widthDp = widthPx / density, heightDp = heightPx / density, heightPx = heightPx)
}

/**
 * The insets a filling keyboard reports. The whole frame is touchable, and the content/visible tops sit at the frame's
 * bottom edge, so an app hosted on the Bottom is not resized to the zero height that "the keyboard covers all of it"
 * would otherwise mean. Plain ints, applied to the framework's `Insets` by the service.
 */
internal data class ImeFillInsets(val contentTop: Int, val visibleTop: Int)

internal fun imeFillInsets(frameHeightPx: Int): ImeFillInsets =
    ImeFillInsets(contentTop = frameHeightPx, visibleTop = frameHeightPx)

/** Writes a filling keyboard's insets into the framework's; a null [fill] (Main, phones) leaves the platform's untouched. */
internal fun applyBottomFillInsets(out: android.inputmethodservice.InputMethodService.Insets, fill: ImeBottomFill?) {
    fill ?: return
    val insets = imeFillInsets(fill.heightPx)
    out.contentTopInsets = insets.contentTop
    out.visibleTopInsets = insets.visibleTop
    out.touchableInsets = android.inputmethodservice.InputMethodService.Insets.TOUCHABLE_INSETS_FRAME
}

/**
 * Keyboard sizing: one display as read from one source, its id and real size in px.
 * The vendor shows the input method on the second display for a field on MAIN, and then the IME window's decor view still reports display 0 while the
 * service's own context is on the second display. So both are read, and the Bottom is decided from either.
 */
internal data class ImeDisplayRead(val id: Int, val widthPx: Int, val heightPx: Int)

/** The two places the display the input method is on can be read: its window's decor view and the service's own context (the window context, API 30+). */
internal data class ImeDisplayReads(val decor: ImeDisplayRead?, val ctx: ImeDisplayRead?)

/**
 * The display the input method is REALLY on, when it is not Main's: the decor's if that is a non-default display, otherwise the service context's if that is.
 * Null when both are Main's (or unreadable): phones and a Main-only device keep the default keyboard and the default fullscreen rule. No display id and no
 * device model is assumed: any non-default display that hosts the input method counts.
 */
internal fun imeShownOnBottom(reads: ImeDisplayReads): ImeDisplayRead? =
    listOfNotNull(reads.decor, reads.ctx).firstOrNull { it.id != Display.DEFAULT_DISPLAY }

/** The fill for the display the input method is really on (the size is that display's, not Main's), or null on Main. */
internal fun imeFillFor(reads: ImeDisplayReads, density: Float): ImeBottomFill? =
    imeShownOnBottom(reads)?.let { imeBottomFill(it.id, it.widthPx, it.heightPx, density) }

/** The one line logged per fill decision: ids and sizes only, never a field's content. */
internal fun imeFillLogLine(reads: ImeDisplayReads, fill: ImeBottomFill?, fullscreen: Boolean): String =
    "ime fill decided decor=${reads.decor?.id ?: "na"} ctx=${reads.ctx?.id ?: "na"} " +
        "fill=${fill?.let { "${Math.round(it.widthDp)}x${Math.round(it.heightDp)}dp" } ?: "none"} fullscreen=$fullscreen"
