package com.gruning.mimir.rewrite.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import kotlin.math.abs

/**
 * Where the speed gauge's one slim bar sits inside the gauge, in the gauge's own pixels, for the tests that touch it. The bar is 4 dp tall but its touch
 * target is the whole 48 dp gauge row, so a test touches the GAUGE at the bar's x, at mid-height.
 */
internal class GaugeSpot(val left: Float, val width: Float, val height: Float) {
    /** One step of travel: the bar's width over the five steps. */
    val stepPx: Float get() = width / TrackpadSpeed.Steps

    /** The middle of step [n]'s fifth of the bar (1..5). */
    fun stepX(n: Int): Float = left + stepPx * (n - 0.5f)

    val midY: Float get() = height / 2f
}

internal fun ComposeContentTestRule.gaugeSpot(): GaugeSpot {
    val gauge = onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).fetchSemanticsNode()
    val bar = onNodeWithTag(MimirTrackpadSpeedBarTag, useUnmergedTree = true).fetchSemanticsNode()
    return GaugeSpot(
        left = bar.boundsInRoot.left - gauge.boundsInRoot.left,
        width = bar.size.width.toFloat(),
        height = gauge.size.height.toFloat(),
    )
}

/** A tap on the bar at step [n]'s fifth. */
internal fun ComposeContentTestRule.tapGaugeStep(n: Int) {
    val spot = gaugeSpot()
    onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).performTouchInput {
        down(Offset(spot.stepX(n), spot.midY))
        up()
    }
    waitForIdle()
}

/**
 * A swipe along the gauge by [wholeSteps] steps of travel (negative is left, slower; positive is right, faster), starting at [startX] in the gauge's pixels.
 * The finger first leaves the touch slop ([slopPx], read from the running view configuration, not assumed), then travels a whole step and a tenth more at a
 * time, so the swipe is already begun when the first step's worth of travel is reached and each later step is reported as it is crossed.
 */
internal fun ComposeContentTestRule.swipeGauge(startX: Float, wholeSteps: Int, slopPx: Float) {
    val spot = gaugeSpot()
    val direction = if (wholeSteps < 0) -1f else 1f
    onNodeWithTag(MimirTrackpadSpeedGaugeTag, useUnmergedTree = true).performTouchInput {
        down(Offset(startX, spot.midY))
        moveTo(Offset(startX + direction * (slopPx + 2f), spot.midY))
        for (k in 1..abs(wholeSteps)) moveTo(Offset(startX + direction * (k + 0.1f) * spot.stepPx, spot.midY))
        up()
    }
    waitForIdle()
}
