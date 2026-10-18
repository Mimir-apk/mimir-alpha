package com.gruning.mimir.rewrite.core.model

import kotlin.math.ceil
import kotlin.math.floor

/*
 * The geometry of the player's own image on the ᛗ button, pure arithmetic, so the two places that draw it (the floating
 * button's native coin in :core-companion-native, and the Compose coin of Settings' Mimir-button picker in :feature-settings)
 * read ONE definition instead of two copies that could drift. the custom mark must be bigger, and the picker's
 * small tiles must show it the way the live button does.
 *
 * The numbers are dp on the reference coin ([MimirMarkReferenceCoinDp], the floating button's 56 dp). A coin of any other
 * size scales them by its diameter over that: [MimirMarkReferenceBoxDp] / [MimirMarkReferenceCoinDp] of the diameter is the
 * box the trimmed image is fitted into (aspect kept, centred), and [MimirMarkReferenceFaceRadiusDp] / [MimirMarkReferenceCoinDp]
 * of the diameter is the radius of the coin face the image is clipped to, so no ink ever crosses the rim.
 */

/** The floating ᛗ button's coin, in dp: the size every other number here is measured on. */
const val MimirMarkReferenceCoinDp: Float = 56f

/** The box the player's own image is fitted into on the reference coin, in dp: 1.47x the shipping rune's 30 dp box. */
const val MimirMarkReferenceBoxDp: Float = 44f

/** The coin face the image is clipped to on the reference coin, in dp: the circle the 1.25 dp rim leaves (28 - 1.25 - 0.625). */
const val MimirMarkReferenceFaceRadiusDp: Float = 26.125f

/** Alpha at or below this is the mask's padding, not its ink (about 3%: invisible once the metal is poured through it). */
const val MimirMarkInkAlphaFloor: Int = 8

/** What is left around the ink after the trim, per side, in mask pixels, so the edge is not cut on its anti-aliasing. */
const val MimirMarkTrimMarginPx: Int = 2

/**
 * The bounding box of the ink in an ARGB [pixels] image of [width] x [height]: `[left, top, right, bottom]`, all inclusive, or
 * null when nothing is inked. Ink is a pixel whose alpha is above [alphaFloor].
 */
fun mimirMarkInkBounds(pixels: IntArray, width: Int, height: Int, alphaFloor: Int = MimirMarkInkAlphaFloor): IntArray? {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            if ((pixels[row + x] ushr 24) > alphaFloor) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                bottom = y
            }
        }
    }
    return if (right < 0) null else intArrayOf(left, top, right, bottom)
}

/**
 * The crop that trims a [width] x [height] mask to its ink: `[left, top, right, bottom]`, right and bottom EXCLUSIVE (the
 * shape `Bitmap.createBitmap(source, left, top, right - left, bottom - top)` takes). The rectangle is the [ink] box
 * ([mimirMarkInkBounds]) widened by [margin] on each side and held inside the image.
 *
 * Null when there is nothing to crop: [ink] is null (a mask with no ink is left as it came), or the widened box already reaches
 * every edge.
 */
fun mimirMarkTrimCrop(ink: IntArray?, width: Int, height: Int, margin: Int = MimirMarkTrimMarginPx): IntArray? {
    if (ink == null) return null
    val left = (ink[0] - margin).coerceAtLeast(0)
    val top = (ink[1] - margin).coerceAtLeast(0)
    val right = (ink[2] + 1 + margin).coerceAtMost(width)
    val bottom = (ink[3] + 1 + margin).coerceAtMost(height)
    if (left == 0 && top == 0 && right == width && bottom == height) return null
    return intArrayOf(left, top, right, bottom)
}

// ---- the player's own placement of the image () ----

/** The smallest the image may be sized, as a share of the [MimirMarkReferenceBoxDp] box. */
const val MimirMarkScaleMin: Float = 0.60f

/** The largest the image may be sized, as a share of the [MimirMarkReferenceBoxDp] box. The face clip still bounds the ink. */
const val MimirMarkScaleMax: Float = 1.60f

/** How far the image's centre may stand from the coin's centre on each axis, in dp on the reference coin: it can never be lost off the face. */
const val MimirMarkOffsetLimitDp: Float = 12f

/** One pad press moves the image this far, in dp on the reference coin. */
const val MimirMarkNudgeDp: Float = 0.5f

/** One L1 or R1 press changes the size by this share of the box (5%). */
const val MimirMarkScaleStep: Float = 0.05f

/** [MimirMarkScaleStep] as whole steps per 1.0, so a size is held as a whole number of steps and never drifts on repeated presses. */
private const val MimirMarkScaleStepsPerUnit: Int = 20

/** Slack when a size is read as a step count, so 1.05f (which is not exactly 21 / 20) still counts as step 21. */
private const val MimirMarkScaleStepEpsilon: Float = 1e-3f

/**
 * Where, and how large, the player's own image sits on the coin: [scale] of the 44 dp box (0.60 to 1.60), and [offsetX] / [offsetY], the image's
 * centre from the coin's centre in dp on the 56 dp reference coin (x to the right, y down; within [MimirMarkOffsetLimitDp]). The default is the
 * image centred at 100%, which is how the button has always drawn it, so a sidecar that records nothing reads as no change.
 *
 * Any value can be built, and none of it throws: [sanitized] is the one rule for what a stored or dragged value becomes, and the geometry below
 * applies it first. A value that is not a finite number is the field's default; a finite one outside its range is held at the range's edge.
 */
data class MimirMarkPlacement(
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    /** True when the image is centred at 100% (after [sanitized]): what an untouched image is, and what Reset returns to. */
    val isDefault: Boolean get() = sanitized() == Default

    /** This placement with every field a finite number inside its range. */
    fun sanitized(): MimirMarkPlacement = MimirMarkPlacement(
        scale = if (scale.isFinite()) scale.coerceIn(MimirMarkScaleMin, MimirMarkScaleMax) else 1f,
        offsetX = if (offsetX.isFinite()) offsetX.coerceIn(-MimirMarkOffsetLimitDp, MimirMarkOffsetLimitDp) else 0f,
        offsetY = if (offsetY.isFinite()) offsetY.coerceIn(-MimirMarkOffsetLimitDp, MimirMarkOffsetLimitDp) else 0f,
    )

    /** Moved by [dxDp] and [dyDp] on the reference coin, held inside the limit. */
    fun movedBy(dxDp: Float, dyDp: Float): MimirMarkPlacement {
        val base = sanitized()
        return base.copy(
            offsetX = base.offsetX + (if (dxDp.isFinite()) dxDp else 0f),
            offsetY = base.offsetY + (if (dyDp.isFinite()) dyDp else 0f),
        ).sanitized()
    }

    /** Sized by [factor] (a pinch's ratio), held inside the range. A factor that is not a positive finite number changes nothing. */
    fun scaledBy(factor: Float): MimirMarkPlacement {
        val base = sanitized()
        return if (factor.isFinite() && factor > 0f) base.copy(scale = base.scale * factor).sanitized() else base
    }

    /**
     * One [MimirMarkScaleStep] larger ([larger]) or smaller: to the next whole 5% line in that direction, so a size the pad has reached
     * stays on the lines (20 presses back return to exactly 100%) and a size a pinch left between two lines moves to the nearest one.
     */
    fun steppedScale(larger: Boolean): MimirMarkPlacement {
        val base = sanitized()
        val units = base.scale * MimirMarkScaleStepsPerUnit
        val next = if (larger) {
            floor(units + MimirMarkScaleStepEpsilon).toInt() + 1
        } else {
            ceil(units - MimirMarkScaleStepEpsilon).toInt() - 1
        }
        return base.copy(scale = next / MimirMarkScaleStepsPerUnit.toFloat()).sanitized()
    }

    companion object {
        /** Centred at 100%. */
        val Default = MimirMarkPlacement()
    }
}

/** A rectangle in the coin's own pixels (or dp), with the origin at the coin's top-left; the unit is whatever the coin size was given in. */
data class MimirMarkRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * The square the image is fitted into, for a coin [coinSize] across (any unit; the rect comes back in it): the 44 dp box of the reference coin
 * sized by [placement]'s scale and centred [placement]'s offset from the coin's centre, both scaled to this coin. Not the image's own rectangle
 * (see [mimirMarkDrawRect]): this is what the metal's ramp is laid across. A coin with no size answers an empty rect at the origin.
 */
fun mimirMarkBoxRect(coinSize: Float, placement: MimirMarkPlacement = MimirMarkPlacement.Default): MimirMarkRect {
    if (!coinSize.isFinite() || coinSize <= 0f) return MimirMarkRect(0f, 0f, 0f, 0f)
    val p = placement.sanitized()
    val unit = coinSize / MimirMarkReferenceCoinDp
    val half = MimirMarkReferenceBoxDp * p.scale * unit / 2f
    val cx = coinSize / 2f + p.offsetX * unit
    val cy = coinSize / 2f + p.offsetY * unit
    return MimirMarkRect(cx - half, cy - half, cx + half, cy + half)
}

/**
 * Where the trimmed [imageWidth] x [imageHeight] image is drawn on a coin [coinSize] across: [mimirMarkBoxRect] with the image fitted inside
 * it, aspect kept and centred. THE one definition of the placed image: the floating button's native coin, the Settings picker's tiles and its
 * stage's coins all draw this rect, so a placement looks the same in all of them. The coin's face clip (radius [MimirMarkReferenceFaceRadiusDp]
 * on the reference coin) is NOT applied here: it bounds the ink wherever this rect lands, and the callers clip.
 *
 * An image with no size, or a coin with no size, answers the box.
 */
fun mimirMarkDrawRect(
    coinSize: Float,
    imageWidth: Int,
    imageHeight: Int,
    placement: MimirMarkPlacement = MimirMarkPlacement.Default,
): MimirMarkRect {
    val box = mimirMarkBoxRect(coinSize, placement)
    if (imageWidth <= 0 || imageHeight <= 0 || box.width <= 0f) return box
    val aspect = imageWidth.toFloat() / imageHeight
    val fitWidth = if (aspect >= 1f) box.width else box.width * aspect
    val fitHeight = if (aspect >= 1f) box.width / aspect else box.height
    return MimirMarkRect(
        left = box.centerX - fitWidth / 2f,
        top = box.centerY - fitHeight / 2f,
        right = box.centerX + fitWidth / 2f,
        bottom = box.centerY + fitHeight / 2f,
    )
}
