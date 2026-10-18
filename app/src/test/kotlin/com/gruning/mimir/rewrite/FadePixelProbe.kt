package com.gruning.mimir.rewrite

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color

/**
 * The way this repo rasterises a frame for the fade proofs (`captureToImage` waits on a redraw the paused looper never delivers): the activity's view hierarchy drawn into a
 * software bitmap, once per stepped frame. Pixels are the JVM's; the handheld's are device-owed.
 */
internal fun Activity.shootWindow(): Bitmap {
    val view = window.decorView
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    return bitmap
}

/** The grey level (0..255) of the pixel at ([x], [y]), by the usual luma weights. */
internal fun Bitmap.gray(x: Int, y: Int): Float {
    val p = getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
    return 0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
}

/**
 * How much of a dark thing is showing at a pixel over a white ground, as a fraction of how much shows when it is whole: 1 when the pixel is as dark as [whole], 0 when it is
 * as white as the ground. A fade of the dark thing from 1 to 0 reads as this fraction falling from 1 to 0.
 */
internal fun presenceOverWhite(gray: Float, whole: Float): Float = ((255f - gray) / (255f - whole)).coerceAtLeast(0f)
