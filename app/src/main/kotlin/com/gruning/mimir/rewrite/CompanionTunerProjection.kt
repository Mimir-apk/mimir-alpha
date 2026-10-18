package com.gruning.mimir.rewrite

/**
 * kHz as the performance card would say it.
 *
 * `performanceOverlayClockLabel` takes Hz, so the conversion happens here rather than by inventing a
 * second formatter that would immediately be a second dialect.
 */
internal fun companionTunerClockLabel(khz: Int?): String =
    performanceOverlayClockLabel(khz?.takeIf { it > 0 }?.toLong()?.times(1_000L))
