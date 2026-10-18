package com.gruning.mimir.rewrite.feature.library

import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRailDefaults
import kotlin.math.min

internal const val HomeShelfSelectedScale = 1.071f
internal val HomeShelfQuickRailHeight = MimirRunicQuickRailDefaults.RailHeight.value
private val HomeShelfQuickRailActionBoxSide = MimirRunicQuickRailDefaults.ActionBoxDiameter.value
private val HomeShelfQuickRailMaxActionSide = MimirRunicQuickRailDefaults.MaxActionDiameter.value
private const val HomeShelfQuickRailFocusedScale = MimirRunicQuickRailDefaults.FocusedScale
/** The molten plinth crest thickness at the rail's coin/label seam (MimirRunicQuickRail draws 1.5dp). */
internal const val HomeShelfQuickRailCrestBand = 1.5f

internal data class HomeShelfBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(left <= right) { "left must not exceed right" }
        require(top <= bottom) { "top must not exceed bottom" }
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun intersects(other: HomeShelfBounds): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun contains(other: HomeShelfBounds): Boolean =
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom

    fun outset(amount: Float): HomeShelfBounds = HomeShelfBounds(
        left = left - amount,
        top = top - amount,
        right = right + amount,
        bottom = bottom + amount,
    )
}

internal const val HomeShelfPrimarySeatCount = 4
internal const val HomeShelfCompactSeatCount = 3

/**
 * The seat-size floor a count solve is decided against — the ONE authored constant in the shelf seat
 * solve . Everything else in
 * [mimirShelfSeatCount]/[solvePlatformRowPlan] is derived, not tuned.
 *
 * Bracketed by two recorded layout constraints, so any value in (132.07, 144.45] reproduces both
 * constraints at every certified viewport — 138 is the midpoint, furthest from both:
 * - **Lower, excluded for legibility:** 132.07dp — what FOUR seats solve to on the Nova (640dp), the width the
 *   700f band this constant replaces was raised specifically to avoid landing on.
 * - **Upper, preserved:** 144.45dp — what FOUR seats solve to on the RP5 (700dp), the width the 700f band
 *   deliberately preserved at four seats.
 *
 * Supersedes the former width-threshold constant `HomeShelfPrimaryWidthDp = 700f` (raised 640f -> 700f on
 * 2026-08-21 because the Nova tiles were too small).
 * That threshold was itself a provisional band; the durable answer, landed here, is a seat count solved
 * from this floor rather than a hand-picked width. See [mimirShelfSeatCount]'s own KDoc for the solve and
 * the proof it reproduces the 700f band bit-identically at every certified viewport.
 */
internal const val MimirSeatFloorDp = 138f

/**
 * Post-inset geometry for the finite, left-anchored game shelf, the one the Home shelf is. All values are dp.
 * Renamed from `HomeShelfGeometry` when the strip became the shared `GameShelf`; [HomeShelfGeometry] stays as its alias.
 */
internal data class GameShelfGeometry(
    val viewport: HomeShelfBounds,
    val gutter: Float,
    val bloomOutset: Float,
    val centerAnchor: Float,
    val contentLeft: Float,
    val seatEnvelope: Float,
    val restingSide: Float,
    val heroSide: Float,
    val slotGap: Float,
    val quickActionDiameter: Float,
    val quickBottom: Float,
    val quickActionOffsetY: Float,
    val quickActionBounds: HomeShelfBounds,
    val titleBand: HomeShelfBounds,
    val gameRailBand: HomeShelfBounds,
    val navigationHintBand: HomeShelfBounds,
    val hintToastBand: HomeShelfBounds,
    val quickRailBand: HomeShelfBounds,
    val visibleSlotRadius: Int,
    val visibleSlotCount: Int,
    val fullyVisibleSlotCount: Int,
) {
    val selectedScale: Float get() = heroSide / restingSide
    val slotStride: Float get() = seatEnvelope + slotGap
    val quickActionFocusedPaintBounds: HomeShelfBounds
        get() {
            val focusOutset = quickActionDiameter *
                (MimirRunicQuickRailDefaults.FocusedScale - 1f) / 2f
            return quickActionBounds.outset(focusOutset)
        }

    fun slotBounds(slotIndex: Int): HomeShelfBounds {
        val left = contentLeft + slotIndex * slotStride
        return HomeShelfBounds(
            left = left,
            top = gameRailBand.top,
            right = left + seatEnvelope,
            bottom = gameRailBand.bottom,
        )
    }

    fun artBounds(slotIndex: Int, selected: Boolean): HomeShelfBounds {
        val slot = slotBounds(slotIndex)
        val side = if (selected) heroSide else restingSide
        val horizontalInset = (seatEnvelope - side) / 2f
        val verticalInset = seatEnvelope - side
        return HomeShelfBounds(
            left = slot.left + horizontalInset,
            top = slot.top + verticalInset,
            right = slot.left + horizontalInset + side,
            bottom = slot.bottom,
        )
    }

    // the earlier update rev B : the veiled peek seat sits at the UNIFORM slotStride like every other
    // seat, aligned relative to the centered hero tile. The earlier gutter-clamped tuck
    // (peekTuckOffset) compressed the outermost gap against the screen edge and is retired.
}

internal typealias HomeShelfGeometry = GameShelfGeometry

/**
 * Solves the SH02 bands once from post-inset constraints. The rail and title never depend on the selected
 * title or item count, so focus and localized copy cannot remeasure the surface.
 */
internal const val HomeShelfEyebrowLane = 20f
/** Upper-right space reserved for the status pill so the full-width title never runs beneath it. */
internal const val HomeShelfStatusPillReserve = 190f

/**
 * The status pill's reserve on compact. Compact review 2026-09-27 (HOME-1): the two 190 dp wide-screen reserves
 * (this pill's and the status chip's) left the RG's title 149.7 dp, so "Conker's Bad Fur Day" read "Conker's
 * Bad F…". The HUD measured 401.6-549 dp on the RG's 564.7 dp square (147.4 wide, 16 dp from the edge, "100%"
 * being its widest text), so 160 clears it by 14.4 dp. The chip no longer shares the line on compact either;
 * see `HomeShelfTitle`'s `stackStatus`.
 */
internal const val HomeShelfCompactStatusPillReserve = 160f
internal const val HomeShelfNavigationHintHeight = 24f

/**
 * The real paint reach of the tile focus chrome beyond a tile's bounds: 2dp ring outset + the 10dp
 * bloom outset + half its 7dp stroke ([mimirFocusTileRing]'s three strokes), rounded up. The solved
 * [GameShelfGeometry.bloomOutset] (8–12dp) is the SCENE bloom, which is smaller — band vacancy checks
 * must clear this value, not that one.
 */
internal const val HomeShelfFocusRingReach = 14f

/** the mode hint's compact reserve inside the hint/toast lane. */
internal const val HomeShelfNavigationHintWidth = 196f
internal const val HomeShelfNavigationHintLaneInset = 6f

/**
 * Where the quick rail's top edge sits, given its already-solved coin diameter and bottom clamp. Pulled out of
 * [solveHomeShelfGeometry] so [homeShelfQuickRailBottomGapDp] can share the exact same arithmetic instead of a
 * second hand-copied formula that can silently drift from this one.
 */
private fun homeShelfQuickRailTopDp(heightDp: Float, quickDiameter: Float, quickBottom: Float): Float {
    val quickActionCenterY = heightDp - quickBottom - quickDiameter / 2f
    val idealQuickRailTop = quickActionCenterY - HomeShelfQuickRailActionBoxSide / 2f
    return min(idealQuickRailTop, heightDp - HomeShelfQuickRailHeight)
}

/**
 * The vertical gap between the quick rail's bottom edge and the viewport's bottom edge — Home's own dock
 * placement formula, reusable so the Library shelf's dock lands at the same height instead of carrying its
 * own independently-authored constant.  Root cause: Home solves this gap from a height-scaled
 * coin-bottom clamp ([quickBottom]/[quickDiameter] above), while `LibraryScreen.kt`'s Column carried a flat
 * `ShelfBottomPaddingDp = 16` that only happened to be close, not equal, at any real viewport. This is now
 * the one shared source both [solveHomeShelfGeometry] and `solveLibraryShelfGeometry` read.
 */
internal fun homeShelfQuickRailBottomGapDp(heightDp: Float): Float {
    val quickDiameter = clamp(48f, 0.137f * heightDp, HomeShelfQuickRailMaxActionSide)
    val quickBottom = clamp(16f, 0.074f * heightDp, 36f)
    val quickRailTop = homeShelfQuickRailTopDp(heightDp, quickDiameter, quickBottom)
    return heightDp - (quickRailTop + HomeShelfQuickRailHeight)
}

/**
 * How far above the viewport's bottom edge the quick rail's TOP edge stands, in dp: [homeShelfQuickRailBottomGapDp] plus the rail's own height.
 * Home's dock and the Library shelf's dock are both placed from [homeShelfQuickRailBottomGapDp] (the Library says so in its own solve), so this one
 * number is where both of them begin. Public so the app's Main message host can keep its rail clear of the dock on either screen  instead of
 * carrying a second copy of the arithmetic that could drift from it.
 */
fun homeShelfQuickRailClearanceDp(heightDp: Float): Float =
    homeShelfQuickRailBottomGapDp(heightDp) + HomeShelfQuickRailHeight

/**
 * The seat ENVELOPE [seatCount] fixed seats solve to on a [widthDp] viewport, using [solveHomeShelfGeometry]'s
 * own gutter/gap/edge-inset terms verbatim (see that function's body — this is the same arithmetic, kept as
 * one shared implementation rather than two hand-copies that can drift). Self-contained (recomputes gutter
 * etc. from scratch) so [mimirShelfSeatCount] never depends on [solveHomeShelfGeometry]'s internal variable
 * order.
 */
private fun homeShelfSeatEnvelopeDp(widthDp: Float, seatCount: Int): Float {
    val gutter = clamp(16f, 0.031f * widthDp, 28f)
    val safeWidth = widthDp - gutter * 2f
    val gap = 0.02f * safeWidth
    val edgeInset = 0.03f * safeWidth
    val span = widthDp - 2f * (gutter + edgeInset)
    return (span - gap * (seatCount - 1)) / seatCount
}

/**
 * Shelf seat-COUNT authority  — decided once,
 * product-wide, from [widthDp] alone. Four seats when they would each clear [MimirSeatFloorDp]; three
 * otherwise. Seat SIZE is a separate concern ([solveHomeShelfGeometry]'s own `seatEnvelope`,
 * [solvePlatformRowPlan] for the platform row) — this function only ever answers 3 or 4.
 *
 * Replaces the former `widthDp < HomeShelfPrimaryWidthDp` (700f) threshold. Proven bit-identical to it at
 * every certified viewport `[D]`: in the unclamped gutter range (516.1dp < W < 903.2dp) this reduces to
 * `env(4) = 0.20636 * W`, so the 3-vs-4 boundary solves to W* = 138 / 0.20636 = 668.7dp — strictly between
 * the Nova (640dp, 28.7dp below) and the RP5 (700dp, 31.3dp above), so this removes the RP5's exact 0.0dp
 * knife edge on the old 700f band without moving either device's seat count. Every other certified
 * viewport (Odin 833dp, the 538dp companion, all four compact-square probes, the 1080dp square, the
 * 320x300 floor) sits far enough from 668.7dp that the clamped-gutter cases never need separate checking —
 * verified numerically against all ten rows before this function replaced the threshold it supersedes.
 */
internal fun mimirShelfSeatCount(widthDp: Float): Int =
    if (homeShelfSeatEnvelopeDp(widthDp, HomeShelfPrimarySeatCount) >= MimirSeatFloorDp) {
        HomeShelfPrimarySeatCount
    } else {
        HomeShelfCompactSeatCount
    }

internal fun solveHomeShelfGeometry(widthDp: Float, heightDp: Float, compact: Boolean = false): GameShelfGeometry {
    require(widthDp.isFinite() && heightDp.isFinite()) { "viewport dimensions must be finite" }
    require(widthDp >= 320f && heightDp >= 300f) {
        "Home Shelf requires at least 320 x 300 dp post-inset bounds"
    }

    val gutter = clamp(16f, 0.031f * widthDp, 28f)
    val bloom = clamp(8f, 0.025f * heightDp, 12f)
    val safeWidth = widthDp - gutter * 2f
    val gap = 0.02f * safeWidth
    val quickDiameter = clamp(48f, 0.137f * heightDp, HomeShelfQuickRailMaxActionSide)
    val quickBottom = clamp(16f, 0.074f * heightDp, 36f)

    val quickActionCenterY = heightDp - quickBottom - quickDiameter / 2f
    val quickRailTop = homeShelfQuickRailTopDp(heightDp, quickDiameter, quickBottom)
    val quickRailBottom = quickRailTop + HomeShelfQuickRailHeight
    // FUX-D: the rail's molten plinth crest sits at the fixed coin/label seam (ActionBoxSide, 1.5dp
    // band). The offset that centers the coins on short viewports must never push the FOCUSED coin
    // across that crest — the ledge visibly breaking under a coin defeats the material. The focus
    // scale pivots on the UN-offset box center, so the offset itself rides inside the scale.
    val quickSeamCeilingOffset = (
        HomeShelfQuickRailActionBoxSide - HomeShelfQuickRailCrestBand / 2f -
            HomeShelfQuickRailActionBoxSide / 2f
        ) / HomeShelfQuickRailFocusedScale - quickDiameter / 2f
    val quickActionOffsetY = (
        quickActionCenterY - (quickRailTop + HomeShelfQuickRailActionBoxSide / 2f)
        ).coerceAtMost(quickSeamCeilingOffset)
    val hintHeight = if (widthDp - gutter * 2f < 480f) 32f else 36f
    val hintTop = quickRailTop - hintHeight
    val gameBottom = hintTop - bloom
    // FUX-02 R1: the header is a compact upper-LEFT corner block (eyebrow lane + one smaller title
    // line), no longer a full-width lane above the shelf. The FUX-C reserved eyebrow lane survives
    // INSIDE the block (fs-1.3 collision rule). The status pill (R3) owns the upper-right corner.
    val titleHeight = HomeShelfEyebrowLane + clamp(30f, 0.09f * heightDp, 40f)
    // Header layout: the title always renders FULL — the block spans to the status pill's reserve
    // (the HUD floats over the scene; hero-art overlap is accepted like the side tiles).
    val statusPillReserve = if (compact) HomeShelfCompactStatusPillReserve else HomeShelfStatusPillReserve
    val titleWidth = (widthDp - gutter * 2f - statusPillReserve).coerceAtLeast(96f)
    val titleRight = gutter + titleWidth
    // FUX-05: the header is a floating HUD by ratified model — no stacked fallback, covers keep the
    // raised caps on every viewport and the block overlaps the scene where titles run long.
    val visibleSlotCount = mimirShelfSeatCount(widthDp)
    val edgeInset = 0.03f * safeWidth
    val contentLeft = gutter + edgeInset
    val contentRight = widthDp - gutter - edgeInset
    val seatEnvelope = (
        contentRight - contentLeft - gap * (visibleSlotCount - 1)
        ) / visibleSlotCount
    // Compatibility name: backdrop helpers treat this as seat-zero's centre, not the viewport centre.
    val centerAnchor = contentLeft + seatEnvelope / 2f
    val heroSide = min(seatEnvelope, 0.48f * heightDp)
    val restingSide = heroSide / HomeShelfSelectedScale
    val titleBottom = gutter + titleHeight
    // the seats are solved from width, and on
    // a square the height they leave was all above them -- 158.3 dp of empty sky on the RG under its bar, 182.3
    // with the bar hidden. Compact keeps three seats and centres the band between the title and the hint lane
    // instead of seating it on the lane. Only when there is spare height: a band that already fills the gap keeps
    // its bottom-anchored seat, as every standard screen does.
    val spareAboveSeats = gameBottom - titleBottom - seatEnvelope
    val gameTop = if (compact && spareAboveSeats > 0f) {
        titleBottom + spareAboveSeats / 2f
    } else {
        gameBottom - seatEnvelope
    }
    val gameRailBottom = gameTop + seatEnvelope
    // the upper-right shoulder is NOT vacant — the focused Tome's
    // ring paint ([HomeShelfFocusRingReach] above gameTop) intersects any 24dp band that also clears
    // the status pill on the certified 700x350 viewport, so the prior placement clipped closed-Tome
    // art. The one rectangle vacant on every certified viewport is the RIGHT END of the hint/toast
    // lane: below the strip's ring reach (the lane starts a full bloomOutset under gameBottom and the
    // hint sits a further lane inset down, clearing the ring's bottom bloom), above the quick rail,
    // clear of title/status (far above), continuation runes (strip mid-height), and the centred toast
    // (which additionally never paints while the hint shows — the screen gates hint on toast == null).
    val navigationHintWidth = min(HomeShelfNavigationHintWidth, safeWidth * 0.52f)
    val navigationHintTop = hintTop + HomeShelfNavigationHintLaneInset
    val navigationHintBottom = navigationHintTop + HomeShelfNavigationHintHeight
    val navigationHintRight = widthDp - gutter
    val navigationHintLeft = navigationHintRight - navigationHintWidth
    // Compatibility field only. The Phase-B strip has seats 0..count-1 and never a centre radius.
    val visibleSlotRadius = (visibleSlotCount - 1) / 2

    return GameShelfGeometry(
        viewport = HomeShelfBounds(0f, 0f, widthDp, heightDp),
        gutter = gutter,
        bloomOutset = bloom,
        centerAnchor = centerAnchor,
        contentLeft = contentLeft,
        seatEnvelope = seatEnvelope,
        restingSide = restingSide,
        heroSide = heroSide,
        slotGap = gap,
        quickActionDiameter = quickDiameter,
        quickBottom = quickBottom,
        quickActionOffsetY = quickActionOffsetY,
        quickActionBounds = HomeShelfBounds(
            left = (widthDp - quickDiameter) / 2f,
            top = quickActionCenterY - quickDiameter / 2f,
            right = (widthDp + quickDiameter) / 2f,
            bottom = quickActionCenterY + quickDiameter / 2f,
        ),
        titleBand = HomeShelfBounds(gutter, gutter, titleRight, titleBottom),
        gameRailBand = HomeShelfBounds(0f, gameTop, widthDp, gameRailBottom),
        navigationHintBand = HomeShelfBounds(
            navigationHintLeft,
            navigationHintTop,
            navigationHintRight,
            navigationHintBottom,
        ),
        hintToastBand = HomeShelfBounds(gutter, hintTop, widthDp - gutter, quickRailTop),
        quickRailBand = HomeShelfBounds(0f, quickRailTop, widthDp, quickRailBottom),
        visibleSlotRadius = visibleSlotRadius,
        visibleSlotCount = visibleSlotCount,
        fullyVisibleSlotCount = visibleSlotCount,
    )
}

internal data class HomeShelfVisibleSlot<T>(
    val item: T,
    val itemIndex: Int,
    val slotOffset: Int,
)

/**
 * Stable finite-page origin with one truthful look-ahead seat.
 *
 * The shelf remains finite and content ordered. While more content exists, landing on a visible edge
 * advances the window on that same input so the next full tile is already visible; at the true list
 * terminus the window clamps and the last item honestly occupies the terminal seat.
 */
internal fun homeShelfWindowStart(
    previousStart: Int,
    focusedIndex: Int,
    itemCount: Int,
    visibleSeatCount: Int,
): Int {
    require(visibleSeatCount > 0) { "visibleSeatCount must be > 0" }
    if (itemCount <= visibleSeatCount) return 0
    val focus = focusedIndex.coerceIn(0, itemCount - 1)
    val maximumStart = itemCount - visibleSeatCount
    val start = previousStart.coerceIn(0, maximumStart)
    val last = itemCount - 1
    return when {
        focus <= start && focus > 0 -> focus - 1
        focus < start -> focus
        focus >= start + visibleSeatCount - 1 && focus < last -> focus - visibleSeatCount + 2
        focus >= start + visibleSeatCount -> focus - visibleSeatCount + 1
        else -> start
    }.coerceIn(0, maximumStart)
}

/** Current four-seat page plus one non-painting identity on each available edge. */
internal fun <T> homeShelfBufferedWindow(
    items: List<T>,
    windowStart: Int,
    visibleSeatCount: Int,
): List<HomeShelfVisibleSlot<T>> {
    require(visibleSeatCount > 0) { "visibleSeatCount must be > 0" }
    if (items.isEmpty()) return emptyList()
    val maximumStart = (items.size - visibleSeatCount).coerceAtLeast(0)
    val start = windowStart.coerceIn(0, maximumStart)
    val visibleEndExclusive = minOf(items.size, start + visibleSeatCount)
    val mountedStart = (start - 1).coerceAtLeast(0)
    val mountedEndExclusive = (visibleEndExclusive + 1).coerceAtMost(items.size)
    return (mountedStart until mountedEndExclusive).map { index ->
        HomeShelfVisibleSlot(
            item = items[index],
            itemIndex = index,
            slotOffset = index - start,
        )
    }
}

internal data class HomeShelfContinuation(
    val left: Boolean,
    val right: Boolean,
)

/** Truthful finite-strip continuation: a rune appears only when focus can move that direction. */
internal fun homeShelfContinuation(
    selectedIndex: Int?,
    itemCount: Int,
    active: Boolean = true,
): HomeShelfContinuation {
    if (!active || selectedIndex == null || itemCount <= 1) {
        return HomeShelfContinuation(left = false, right = false)
    }
    val selected = selectedIndex.coerceIn(0, itemCount - 1)
    return HomeShelfContinuation(
        left = selected > 0,
        right = selected < itemCount - 1,
    )
}

/**
 * A finite, contiguous window in true content order. When the whole shelf fits, its layout is stable and
 * focus-independent: a singleton owns the centre seat, while a partial multi-item shelf fills from the
 * leftmost solved seat. On a longer shelf the window clamps at each list edge instead of exposing the
 * opposite end. The focused item is emphasized in place, so LEFT and RIGHT always preserve physical order.
 */
internal fun <T> centeredHomeShelfWindow(
    items: List<T>,
    focusedIndex: Int,
    slotRadius: Int,
): List<HomeShelfVisibleSlot<T>> {
    require(slotRadius >= 0) { "slotRadius must be >= 0" }
    if (items.isEmpty()) return emptyList()

    if (items.size <= 1 + slotRadius * 2) {
        val anchorIndex = minOf(slotRadius, items.lastIndex)
        return items.mapIndexed { index, item ->
            HomeShelfVisibleSlot(
                item = item,
                itemIndex = index,
                slotOffset = index - anchorIndex,
            )
        }
    }

    val visibleCount = minOf(items.size, 1 + slotRadius * 2)
    val focus = focusedIndex.coerceIn(0, items.lastIndex)
    val start = (focus - slotRadius).coerceIn(0, items.size - visibleCount)
    return (start until start + visibleCount).map { index ->
        HomeShelfVisibleSlot(
            item = items[index],
            itemIndex = index,
            slotOffset = index - focus,
        )
    }
}

private fun clamp(minimum: Float, value: Float, maximum: Float): Float =
    value.coerceIn(minimum, maximum)

/**
 * FUX-12b: a Home content strip must behave as a list.
 * The viewport centre has HYSTERESIS — it holds still while focus walks inside [focusSeatRadius], then
 * slides one stride per move. The centre itself is clamped one full seat inside either terminal edge, so
 * the first visible page fills left-to-right and the last page fills through the right edge.
 */
internal fun linearHomeShelfCenterIndex(
    previousCenter: Int,
    focusedIndex: Int,
    itemCount: Int,
    focusSeatRadius: Int,
): Int {
    if (itemCount <= 0) return 0
    require(focusSeatRadius >= 0) { "focusSeatRadius must be >= 0" }
    val focus = focusedIndex.coerceIn(0, itemCount - 1)
    val last = itemCount - 1
    val minimumPageCenter = minOf(focusSeatRadius, last)
    val maximumPageCenter = maxOf(minimumPageCenter, last - focusSeatRadius)
    val minimumFocusCenter = maxOf(minimumPageCenter, focus - focusSeatRadius)
    val maximumFocusCenter = minOf(maximumPageCenter, focus + focusSeatRadius)
    return previousCenter.coerceIn(
        minimumValue = minimumFocusCenter,
        maximumValue = maximumFocusCenter,
    )
}

/**
 * Linear shelf window around [centerIndex], with one non-painting seat mounted on either available edge.
 *
 * When [centerIndex] advances, the next identity already exists at `slotRadius + 1` with alpha zero;
 * it moves into the edge seat while the prior edge moves inward. This prevents a newly composed tile
 * from being initialized directly on top of the old edge tile for the first transition frame.
 */
internal fun <T> linearHomeShelfBufferedWindow(
    items: List<T>,
    centerIndex: Int,
    slotRadius: Int,
): List<HomeShelfVisibleSlot<T>> {
    require(slotRadius >= 0) { "slotRadius must be >= 0" }
    if (items.isEmpty()) return emptyList()
    val center = centerIndex.coerceIn(0, items.lastIndex)
    val mountedStart = (center - slotRadius - 1).coerceAtLeast(0)
    val mountedEnd = minOf(items.lastIndex, center + slotRadius + 1)
    return (mountedStart..mountedEnd).map { index ->
        HomeShelfVisibleSlot(
            item = items[index],
            itemIndex = index,
            slotOffset = index - center,
        )
    }
}
