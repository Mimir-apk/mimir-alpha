package com.gruning.mimir.rewrite.core.companionnative

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot
import kotlin.math.max

/**
 *  the single-display M badge rests on a screen edge instead of wherever a drag last dropped it
 * (mid-screen it sits over a launcher icon), and a single tap opens the same menu a hold opens.
 *
 * Both are the PRIMARY_SINGLE badge's behaviour only. The Bottom's badge (SECONDARY_COMPANION) keeps its free drag and its
 * tap does not open the menu: its existing dim, cover and menu behavior remains separate. Its tap
 * has its own meaning since the policy of 2026-10-03 ([mButtonTapOpensRecents]). Widening either one is a one-line
 * change to the predicates below.
 */
internal fun mButtonEdgeSnapApplies(role: CompanionOverlayRole): Boolean =
    role == CompanionOverlayRole.PRIMARY_SINGLE

internal fun mButtonTapOpensMenu(role: CompanionOverlayRole): Boolean =
    role == CompanionOverlayRole.PRIMARY_SINGLE

/**
 * on the single display a tap on the badge opens the music panel while
 * there is music to control, and the menu otherwise. A hold always opens the menu. [musicPanelAvailable] is the app's answer
 * (this module has no audio dependency); the Bottom badge never opens the panel.
 */
internal fun mButtonTapOpensMusic(role: CompanionOverlayRole, musicPanelAvailable: Boolean): Boolean =
    mButtonTapOpensMenu(role) && musicPanelAvailable

/**
 * a light press on the Bottom's
 * badge opens Android Recents on the MAIN screen. A hold still opens the menu, whose Tabs row maps to the same action. The
 * single-display badge is untouched: its tap stays the music panel or the menu.
 */
internal fun mButtonTapOpensRecents(role: CompanionOverlayRole): Boolean =
    role == CompanionOverlayRole.SECONDARY_COMPANION

/** How far a finger may wander (dp) before a touch on the badge is a drag and no longer a tap. */
internal const val MButtonDragSlopDp = 8

/**
 * Whether a touch that has moved ([dxPx], [dyPx]) from where it went down is a drag: strictly farther than [slopPx]. One
 * rule for both badges, so a nudge inside it is still a tap and a drag past it never is. Latched by the caller: once a touch
 * has been a drag, coming back inside the slop does not make it a tap.
 */
internal fun mButtonMoveIsDrag(dxPx: Float, dyPx: Float, slopPx: Float): Boolean =
    hypot(dxPx, dyPx) > slopPx

/** What the release of one touch on the badge means. Hold wins over everything, exactly as the old release branch did. */
internal enum class MButtonReleaseOutcome {
    /** The long press already opened the menu; the release only clears the latch. */
    HOLD_HANDLED,

    /** A drag ended: settle on the nearest edge, then save. */
    DRAG_SNAP,

    /** A drag ended on a badge that does not snap: save where it was dropped. */
    DRAG_KEEP,

    /** A plain tap on a badge whose tap opens the menu. */
    TAP_OPENS_MENU,

    /** A plain tap on the single-display badge while Your music or another player has a session to control: the music panel. */
    TAP_OPENS_MUSIC,

    /**
     * A plain tap on a badge whose role is SECONDARY_COMPANION, i.e. the coin lives on a second display. there,
     * a tap opens Recent apps on Main (replacing CR-8's inert tap). It is gated on the role, not on a device model or an SoC, so
     * any device that puts the coin on a second display behaves the same. Single-screen
     * devices (PRIMARY_SINGLE) never reach it.
     */
    TAP_OPENS_RECENTS,

    /**
     * A plain tap on the second-display coin while a menu is open, opening, closing or mid-swap, or while the coin's own touch is
     * disabled ([mButtonTapMayOpenRecents]). It does nothing: it is the old CR-8 inert tap, which is what a Bottom tap did in
     * those states previously. The menu has its own touch handling for dismissal; the coin does not take part in it.
     */
    TAP_IGNORED,
}

/**
 * Whether a tap on the second-display coin may open Recents right now. Not while a menu is open (`menuOpen`), opening or closing
 * (`swapInFlight`, `transitionOutgoing`, `collapseInFlight`: `menuOpen` itself only lands at the 130 ms hand-over, so a hold,
 * release and retap inside it would otherwise see a closed menu), or has a request pending (`menuRequestPending`), and never
 * while the coin's touch is disabled (`touchEnabled`, the outgoing root's flag; the coin is a child of that root and still
 * receives touches, so the flag alone does not stop the tap).
 */
internal fun mButtonTapMayOpenRecents(
    menuOpen: Boolean,
    swapInFlight: Boolean,
    transitionOutgoing: Boolean,
    collapseInFlight: Boolean,
    menuRequestPending: Boolean,
    touchEnabled: Boolean,
): Boolean = !menuOpen && !swapInFlight && !transitionOutgoing && !collapseInFlight && !menuRequestPending && touchEnabled

/** [recentsMayOpen] is [mButtonTapMayOpenRecents]; it only matters to the second-display coin's tap. */
internal fun classifyMButtonRelease(
    role: CompanionOverlayRole,
    longPressFired: Boolean,
    wasDragging: Boolean,
    musicPanelAvailable: Boolean = false,
    recentsMayOpen: Boolean = true,
): MButtonReleaseOutcome = when {
    longPressFired -> MButtonReleaseOutcome.HOLD_HANDLED
    wasDragging ->
        if (mButtonEdgeSnapApplies(role)) MButtonReleaseOutcome.DRAG_SNAP else MButtonReleaseOutcome.DRAG_KEEP
    mButtonTapOpensMusic(role, musicPanelAvailable) -> MButtonReleaseOutcome.TAP_OPENS_MUSIC
    mButtonTapOpensMenu(role) -> MButtonReleaseOutcome.TAP_OPENS_MENU
    mButtonTapOpensRecents(role) ->
        if (recentsMayOpen) MButtonReleaseOutcome.TAP_OPENS_RECENTS else MButtonReleaseOutcome.TAP_IGNORED
    else -> error("every overlay role's tap is classified above: $role")
}

internal enum class MButtonEdge { LEFT, RIGHT, TOP, BOTTOM }

internal data class MButtonEdgeSnap(val position: Pair<Int, Int>, val edge: MButtonEdge)

/**
 * The window position (top-left, px) the badge settles on: the nearest of the four screen edges, held [inset] px off that
 * edge and off the corners (the same safe inset the default position uses, so the default top-right spot is already a
 * snapped spot and snapping it changes nothing).
 *
 * Distance is measured from the clamped drop point to each window edge. A tie goes to the vertical sides first (left, right,
 * top, bottom), which is also why a drop in a corner lands on the side and not the top. A display too small to keep the inset
 * on both sides of the badge falls back to flush edges. Idempotent: snapping a snapped position returns it.
 *
 * Only pixels in and out, so it runs on the plain JVM.
 */
internal fun snapMButtonToNearestEdge(
    x: Int,
    y: Int,
    bounds: Pair<Int, Int>,
    width: Int,
    height: Int,
    inset: Int,
): MButtonEdgeSnap {
    val (px, py) = clampCompanionOverlayPosition(x, y, bounds, width, height)
    val maxX = max(0, bounds.first - width)
    val maxY = max(0, bounds.second - height)
    val gap = inset.coerceAtLeast(0)
    val edge = listOf(
        MButtonEdge.LEFT to px,
        MButtonEdge.RIGHT to maxX - px,
        MButtonEdge.TOP to py,
        MButtonEdge.BOTTOM to maxY - py,
    ).minBy { it.second }.first

    fun low(limit: Int): Int = if (limit >= 2 * gap) gap else 0
    fun high(limit: Int): Int = if (limit >= 2 * gap) limit - gap else limit
    fun along(value: Int, limit: Int): Int = if (limit >= 2 * gap) value.coerceIn(gap, limit - gap) else value.coerceIn(0, limit)

    val position = when (edge) {
        MButtonEdge.LEFT -> low(maxX) to along(py, maxY)
        MButtonEdge.RIGHT -> high(maxX) to along(py, maxY)
        MButtonEdge.TOP -> along(px, maxX) to low(maxY)
        MButtonEdge.BOTTOM -> along(px, maxX) to high(maxY)
    }
    return MButtonEdgeSnap(position, edge)
}

/**
 * Where the primary M badge rests, in display pixels: the 56 dp square (not the one-pixel idle watcher) at its saved spot.
 * The in-app corner reserve reads it from [CompanionOverlayController.primaryButtonBounds]. [displayWidth] and
 * [displayHeight] are the bounds the position was measured against, so a reader can tell which edge it sits on.
 */
data class OverlayButtonBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val displayWidth: Int,
    val displayHeight: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal fun overlayButtonBoundsOf(
    position: Pair<Int, Int>,
    size: Int,
    bounds: Pair<Int, Int>,
): OverlayButtonBounds = OverlayButtonBounds(
    left = position.first,
    top = position.second,
    right = position.first + size,
    bottom = position.second + size,
    displayWidth = bounds.first,
    displayHeight = bounds.second,
)

/** Where the music card sits: its top-left in the panel window's pixels. */
data class MusicPanelPlacement(val leftPx: Int, val topPx: Int)

/**
 * The music card's top-left, anchored to the badge the way the M menu is. The badge's edge is chosen exactly as
 * [snapMButtonToNearestEdge] chooses it (distance to each display edge, a tie going left, right, top, bottom). On the left or
 * right edge the card's outer side lines up with the badge's outer side and its top with the badge's top; on the top or bottom
 * edge the card's top (or bottom) lines up with the badge's and the card is centred on it. The card then stays [insetPx] clear of
 * every display edge (a card taller than the display minus both insets pins to the top inset and scrolls). With no [button]
 * (none mounted) it is centred.
 *
 * Pure pixels in and out, so it runs on the plain JVM. [displayWidthPx] and [displayHeightPx] are the panel window's size.
 */
fun musicPanelPlacement(
    button: OverlayButtonBounds?,
    displayWidthPx: Int,
    displayHeightPx: Int,
    cardWidthPx: Int,
    cardHeightPx: Int,
    insetPx: Int,
): MusicPanelPlacement {
    val gap = insetPx.coerceAtLeast(0)
    fun clamp(value: Int, size: Int, display: Int): Int {
        val high = display - gap - size
        return if (high < gap) gap else value.coerceIn(gap, high)
    }
    if (button == null) {
        return MusicPanelPlacement(
            leftPx = clamp((displayWidthPx - cardWidthPx) / 2, cardWidthPx, displayWidthPx),
            topPx = clamp((displayHeightPx - cardHeightPx) / 2, cardHeightPx, displayHeightPx),
        )
    }
    val edge = listOf(
        MButtonEdge.LEFT to button.left,
        MButtonEdge.RIGHT to button.displayWidth - button.right,
        MButtonEdge.TOP to button.top,
        MButtonEdge.BOTTOM to button.displayHeight - button.bottom,
    ).minBy { it.second }.first
    val left = when (edge) {
        MButtonEdge.LEFT -> button.left
        MButtonEdge.RIGHT -> button.right - cardWidthPx
        MButtonEdge.TOP, MButtonEdge.BOTTOM -> (button.left + button.right) / 2 - cardWidthPx / 2
    }
    val top = when (edge) {
        MButtonEdge.LEFT, MButtonEdge.RIGHT, MButtonEdge.TOP -> button.top
        MButtonEdge.BOTTOM -> button.bottom - cardHeightPx
    }
    return MusicPanelPlacement(
        leftPx = clamp(left, cardWidthPx, displayWidthPx),
        topPx = clamp(top, cardHeightPx, displayHeightPx),
    )
}

/** The music card's clearance from every display edge, in dp: the badge's own safe inset. */
const val MusicPanelEdgeClearanceDp: Int = MButtonSafeInsetDp

/** The contract's default: an overlay with no primary badge reports none. */
internal val NoOverlayButtonBounds: StateFlow<OverlayButtonBounds?> = MutableStateFlow(null)

/** The contract default for [CompanionOverlayController.quickMenuSwapInFlight]: nothing is ever swapping. */
internal val NoQuickMenuSwap: StateFlow<Boolean> = MutableStateFlow(false)
