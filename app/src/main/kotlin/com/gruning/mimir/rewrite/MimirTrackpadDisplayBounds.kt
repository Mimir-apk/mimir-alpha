package com.gruning.mimir.rewrite

/**
 * What the platform reported for display 0 (Main) at one moment, as plain sizes in its CURRENT rotation. Two sources, because
 * they fail differently: the maximum window bounds of a window context bound to display 0 (rotation-aware, and the very frame
 * the injected strokes are placed in), and the display's real size (the older call, kept as the fallback).
 */
internal data class TrackpadDisplayReading(
    val windowBounds: Pair<Int, Int>?,
    val realSize: Pair<Int, Int>?,
)

/**
 * Display 0's full size for the pointer's clamp and every destination. The pointer is clamped to this rectangle, so it must be Main's WHOLE display in its current rotation and never
 * another display's: the window bounds win when the platform gave plausible ones, then the real size, then the last size
 * that was good. A size of 1 by 1 is never returned, and never the Bottom's: nothing here reads any display but display 0.
 */
internal fun mimirTrackpadDisplaySize(reading: TrackpadDisplayReading, lastGood: Pair<Int, Int>?): Pair<Int, Int> {
    fun plausible(size: Pair<Int, Int>?): Boolean = size != null && size.first >= MinPlausiblePx && size.second >= MinPlausiblePx
    return when {
        plausible(reading.windowBounds) -> reading.windowBounds!!
        plausible(reading.realSize) -> reading.realSize!!
        plausible(lastGood) -> lastGood!!
        else -> FallbackSize
    }
}

private const val MinPlausiblePx = 100

/** Used only when the platform has said nothing usable and nothing was ever good: a landscape handheld's Main. */
private val FallbackSize = 1920 to 1080
