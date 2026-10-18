package com.gruning.mimir.rewrite.feature.companion

import kotlin.math.abs
import kotlin.math.hypot

internal const val CompanionQuickLaunchHeightDp = 86f
internal const val CompanionQuickLaunchTag = "companion-quick-launch"
internal const val CompanionQuickLaunchHandleTag = "companion-quick-launch-handle"

/** The visible handle stays small; its full width is the only collapsed dock chrome. */
internal const val CompanionQuickLaunchHandleHeightDp = 18f

/** The visible handle's width. */
internal const val CompanionQuickLaunchHandleWidthDp = 96f

/** The tag of the visible 96 x 18 handle; [CompanionQuickLaunchHandleTag] is its (wider, taller) hit zone, which carries the gestures and the semantics. */
internal const val CompanionQuickLaunchHandleVisualTag = "companion-quick-launch-handle-visual"

/**
 * The collapsed handle's hit zone, widened along the edge. A 48 dp target cannot fit under the lattice's last row without
 * covering a tile (measured: 22.1 dp free with the swipe hint retired, 41.2 dp with it shown), so the zone takes what is free: [CompanionQuickLaunchHandleZoneWidthDp] wide, centred on
 * the bottom edge, and as tall as the free strip under the last row less [CompanionHandleZoneClearDp] so it never reaches a tile's hit box. The visible handle does not change.
 */
internal const val CompanionQuickLaunchHandleZoneWidthDp = 200f

/** The collapsed handle's zone width: [CompanionQuickLaunchHandleZoneWidthDp] on the bare lattice at rest, else the visible handle's own width. */
internal fun companionQuickLaunchHandleZoneWidthDp(widened: Boolean): Float =
    if (widened) CompanionQuickLaunchHandleZoneWidthDp else CompanionQuickLaunchHandleWidthDp

/** The hit zone stops this far short of the last row's tile boxes. */
internal const val CompanionHandleZoneClearDp = 1f

/** `CompanionShell`'s bottom padding. */
internal const val CompanionShellBottomPaddingDp = 10f

/** The swipe hint's footer: its 6 dp spacer and its 13 dp minimum row (the row is 15.2 dp at font scale 1.3 on page 0; the minimum is the floor on every page). */
internal const val CompanionHintFooterFloorDp = 6f + 13f

/**
 * The height of the collapsed handle's hit zone. The strip under the lattice's last row is the shell's bottom padding plus the frame's inner rail inset (the last row's cells end that far
 * above the frame's bottom edge, which is the shell's), plus the swipe hint's footer while the hint shows. Anywhere else (a host whose scene is not the lattice) the zone is the visible handle.
 */
internal fun companionQuickLaunchHandleZoneHeightDp(latticeShown: Boolean, hintFooterShown: Boolean): Float =
    if (!latticeShown) {
        CompanionQuickLaunchHandleHeightDp
    } else {
        maxOf(
            CompanionQuickLaunchHandleHeightDp,
            CompanionShellBottomPaddingDp + CompanionRailInsetDp + (if (hintFooterShown) CompanionHintFooterFloorDp else 0f) - CompanionHandleZoneClearDp,
        )
    }

internal enum class CompanionQuickLaunchDockGesture {
    TAP,
    SWIPE_UP,
    SWIPE_DOWN,
    NONE,
}

internal fun companionQuickLaunchDockGesture(
    deltaX: Float,
    deltaY: Float,
    touchSlop: Float,
): CompanionQuickLaunchDockGesture {
    val boundedThreshold = touchSlop.coerceAtLeast(0f)
    val distance = hypot(deltaX.toDouble(), deltaY.toDouble()).toFloat()
    return when {
        distance == 0f -> CompanionQuickLaunchDockGesture.TAP
        distance < boundedThreshold -> CompanionQuickLaunchDockGesture.TAP
        abs(deltaY) > abs(deltaX) && deltaY < 0f -> CompanionQuickLaunchDockGesture.SWIPE_UP
        abs(deltaY) > abs(deltaX) && deltaY > 0f -> CompanionQuickLaunchDockGesture.SWIPE_DOWN
        else -> CompanionQuickLaunchDockGesture.NONE
    }
}

/** The dock floats over the scene, so it never reserves layout height in either state. */
internal fun companionQuickLaunchDockBottomPaddingDp(
    @Suppress("UNUSED_PARAMETER") expanded: Boolean,
): Float = 0f

internal fun companionQuickLaunchDockHandleEnabled(
    hideableDock: Boolean,
    railVisible: Boolean,
    interactionBlocked: Boolean,
    railCovered: Boolean,
    launchLocked: Boolean,
    pickerOpen: Boolean,
    pinActionsOpen: Boolean,
    choiceOpen: Boolean,
    instrumentsShown: Boolean?,
): Boolean = hideableDock && railVisible && !interactionBlocked && !railCovered && !launchLocked &&
    !pickerOpen && !pinActionsOpen && !choiceOpen && instrumentsShown != true

/** The struck disc every rail seat is. One value, so the rail keeps one rhythm across seat kinds. */
internal const val CompanionQuickSeatDp = 56f

/** The gap between two seats. */
internal const val CompanionQuickSeatGapDp = 10f

/** The rail's own horizontal inset, taken off both edges of the panel. */
internal const val CompanionQuickRailInsetDp = 16f

/**
 * The companion panel's logical WIDTH in dp on the certified surface — 538 x 468 at density 369
 * (`CompanionSurface.kt`, the shell inset's KDoc). Written here because the rail is the one thing on
 * this surface whose content can grow SIDEWAYS past it.
 */
internal const val CompanionPanelWidthDp = 538f

internal fun companionQuickLaunchContentHeightDp(
    viewportHeightDp: Float,
    @Suppress("UNUSED_PARAMETER") quickLaunchVisible: Boolean,
): Float = viewportHeightDp.coerceAtLeast(0f)

/**
 * How wide a rail of [seats] measures, in dp.
 *
 * **Written as the arithmetic rather than as its answer**, the way this module's other derived
 * dimension already is — S2-01 keeps the rail at SEVEN seats (General + Tweaker + five pins), and the
 * row wraps nothing and clips nothing, so an overflow would simply push the outer seats off the panel
 * edges where nobody is looking for them.
 *
 * **This is a COMPUTATION and not a measurement**, and geometry changes still need measured pixel bounds. It bounds the
 * arithmetic so a future eighth seat trips a test instead of glass; the seat's real pixel bounds are
 * owed a capture on the device.
 */
internal fun companionQuickRailWidthDp(seats: Int): Float = when {
    seats <= 0 -> 0f
    else -> seats * CompanionQuickSeatDp + (seats - 1) * CompanionQuickSeatGapDp
}

/** What the rail has to spend, after its own inset comes off both edges of the panel. */
internal fun companionQuickRailBudgetDp(): Float =
    CompanionPanelWidthDp - 2f * CompanionQuickRailInsetDp
