package com.gruning.mimir.rewrite

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.MimirNavFeedback
import com.gruning.mimir.rewrite.core.ui.LocalMimirControllerHintLabels
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirHintLineSeparator
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardHeight
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirMannazPin
import com.gruning.mimir.rewrite.core.ui.MimirMannazPinHeightDp
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirSegmentedHintSwap
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.drawMimirVerticalBeam
import com.gruning.mimir.rewrite.core.ui.glassRestingOutline
import com.gruning.mimir.rewrite.core.ui.mimirGlassRow
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.feature.library.HomeShelfEntry
import com.gruning.mimir.rewrite.feature.library.HomeShelfScope
import com.gruning.mimir.rewrite.feature.library.HomeShelfTome
import com.gruning.mimir.rewrite.feature.library.HomeTomeFace
import com.gruning.mimir.rewrite.feature.library.HomeTomeFolioScope
import com.gruning.mimir.rewrite.feature.library.HomeTomePreview
import com.gruning.mimir.rewrite.feature.library.TomeId
import com.gruning.mimir.rewrite.feature.library.VideoBackdrop
import com.gruning.mimir.rewrite.feature.library.homeTomeLibraryBackdropArt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlin.math.abs
import com.gruning.mimir.rewrite.core.ui.mimirSwapEnterSpec
import com.gruning.mimir.rewrite.core.ui.mimirSwapExitSpec

internal enum class HomeTomeLibrarySection { HOME, PINNED, CUSTOM, PLATFORM, ACTION }
internal val HomeTomeLibraryScope = FocusScope("home/tome/library")

private val HomeTomeChildScopes = setOf(HomeTomeLibraryScope, HomeTomeFolioScope)

/**
 * The pin/unpin **acknowledgement** clock: how long a state CHANGE takes to register as having
 * happened. Drives the per-Tome pin marker (`pinMarkerPresence`), which is the reference standard for this acknowledgement. **Do not
 * retune this clock to fix a text surface.**
 */
internal const val HomeTomeMutationAckMs = 110

/**
 * The header-status label's own clock. **Split from [HomeTomeMutationAckMs] on 2026-08-14** after repeated reports that the changing label was too fast to read.
 *
 * Why a split and not a bump. The two surfaces shared 110ms because both are the same *event*; the
 * mistake was assuming they are therefore the same *task*. The pin marker is a 7dp diamond appearing
 * or vanishing -- a presence change, which the eye resolves as fast as it is drawn, and which uses
 * its existing 110ms clock. The header status is a WORD REPLACING A WORD ("PINNED TO
 * HOME" -> "TOMES"), which is not noticed, it is *read* -- and 110ms is below the dwell a reader needs
 * to take a changed label in. The changed text needs time to be read.
 * Raising the shared constant would have dragged the marker's existing timing; the two demands are genuinely
 * different, so they get two clocks.
 *
 * Why [MimirMotion.OverlayMs] and not a bespoke number: the token ladder already classifies this
 * surface -- OverlayMs is documented as the "upper bound for transient overlays, hints, and toast
 * acknowledgement". Referencing it rather than restating 180 keeps this on the ladder instead of
 * beside it. **This is the ceiling of the system's own class for a hint.** If it still reads fast on
 * device, the next step is not another literal here -- it is an policy that amends the ladder's
 * stated upper bound, because at that point the whole overlay/hint class is slower than the tokens say.
 */
internal const val HomeTomeStatusLegibilityMs = MimirMotion.OverlayMs

/**
 * How long the pin/unpin header status ("PINNED TO HOME", "UPDATE FAILED") is held before it is consumed and the header returns to the focused entry's own label. A hold, not a
 * transition, so it is not on the motion ladder: it was an unnamed literal delay in the pin-feedback effect (audit 2026-10-14, the P3 draw pass) and is named here, at the
 * same value, so it can be found and ruled on. The word's own change is [HomeTomeStatusLegibilityMs].
 */
internal const val HomeTomeLibraryPinFeedbackHoldMs = 1_500L

/**
 * Every string resource the header-status label can render (`headerStatusRes`, below), in one place so the
 * width-reservation sizer that keeps that label from displacing its `weight(1f)` sibling can enumerate
 * them exhaustively -- see the sizer's own comment for why a fixed-width OUTER reservation is what keeps
 * the sibling stable while the `SizeTransform` inside it animates smoothly (tween, not `snap()`). The sizer
 * measures each one in the player's language, so a longer Spanish status reserves its own width.
 */
internal val HomeTomeLibraryHeaderStatusCandidates = listOf(
    R.string.app_tome_library_status_update_failed,
    R.string.app_tome_library_status_pinned_to_home,
    R.string.app_tome_library_status_unpinned_from_home,
    R.string.app_tome_library_status_home_shelf,
    R.string.app_tome_library_status_pinned,
    R.string.app_tome_library_status_custom,
    R.string.app_tome_library_status_platforms,
    R.string.app_tome_library_status_create,
    R.string.app_tome_library_status_tomes,
)

/** Coordinator-owned child-scope entry. Composables register slots; they never mutate the scope stack. */
internal fun enterHomeTomeScope(
    authority: FocusAuthority,
    target: FocusScope,
    focusKey: FocusKey? = null,
): Boolean {
    require(target in HomeTomeChildScopes) { "Unsupported Home Tome scope: ${target.value}" }
    focusKey?.let { authority.rememberFocusForScope(target, it) }
    return when (authority.state.value.scope) {
        target -> true
        HomeShelfScope -> {
            authority.pushScope(target)
            true
        }
        else -> false
    }.also { entered ->
        if (entered && focusKey != null) authority.requestFocus(focusKey)
    }
}

/** Atomic sibling replacement: Library <-> Folio always traverses Home once and never nests itself. */
internal fun replaceHomeTomeScope(
    authority: FocusAuthority,
    from: FocusScope,
    target: FocusScope,
    focusKey: FocusKey? = null,
): Boolean {
    require(from in HomeTomeChildScopes && target in HomeTomeChildScopes && from != target)
    if (authority.state.value.scope != from) return false
    focusKey?.let { authority.rememberFocusForScope(target, it) }
    authority.popScope()
    if (authority.state.value.scope != HomeShelfScope) return false
    authority.pushScope(target)
    focusKey?.let(authority::requestFocus)
    return true
}

/**
 * Take a Home Tome scope off the focus stack, wherever it currently sits.
 *
 * **Total by construction, and it has to be.** This used to be a guarded pop — `if (active != scope)
 * return false` — so when the Folio scope was NOT the active one, nothing was popped and the scope
 * stayed on the stack for the rest of the process. That orphan is the anomalous scope a later
 * [enterHomeTomeScope] refuses on, which is the origin of the return-path defect found by the PR 171
 * review at `MimirAppInputDispatch.kt`: the rollback there is the symptom, this was the cause. The same
 * guarded-pop shape was retired from `MimirKeyboardFocus` on 2026-09-06 for the same reason.
 *
 * It is reachable because two of this function's call sites — `HomeShelfCoordinator`'s
 * `restoreRootEntry` and `closeTomeToLibrary` — run during a close that can be racing a surface which
 * pushed its own scope over the Folio.
 *
 * [FocusAuthority.discardScope] removes a buried scope with **no state emission**, so leaving a covered
 * Folio cannot move focus or disturb whatever is on top. When the scope IS active it delegates to
 * `popScope()` and behaves exactly as before.
 *
 * @return whether the scope is now off the stack AND focus rests on [HomeShelfScope]. This is an
 * observation, not an error code: **every production call site discards it**, and since the function is
 * total there is nothing for a caller to recover from. Do not add error handling around it on the
 * strength of the type alone — check what a false actually means here first.
 */
internal fun leaveHomeTomeScope(authority: FocusAuthority, scope: FocusScope): Boolean {
    require(scope in HomeTomeChildScopes)
    authority.discardScope(scope)
    return authority.state.value.scope == HomeShelfScope
}

internal data class HomeTomeLibraryFocusSlot(
    val key: FocusKey,
    val bounds: FocusBounds,
)

internal const val HomeTomeLibraryGridColumns = 3
internal const val HomeTomeLibraryGridVisibleRows = 2
internal const val HomeTomeLibraryHomeFaceScale = 0.90f
internal const val HomeTomeLibraryHomeFaceWidthDp = 162f * HomeTomeLibraryHomeFaceScale
internal const val HomeTomeLibraryHomeFaceHeightDp = 216f * HomeTomeLibraryHomeFaceScale
internal const val HomeTomeLibraryCompactFocusScale = 1.02f
/** Two-row collection face max at the authored 3:4 Tome ratio. */
internal const val HomeTomeLibraryCompactMaxFaceWidthDp = 112.5f
internal const val HomeTomeLibraryCompactMaxFaceHeightDp = 150f
internal const val HomeTomeLibraryCompactMinFaceHeightDp = 64f
internal const val HomeTomeLibraryGridRowGapDp = 15f
internal const val HomeTomeLibraryGridColumnGapDp = 15f
/** Outer grid padding and whole-book focus-ring clearance (same 15dp budget). */
internal const val HomeTomeLibraryGridOuterPaddingDp = 15f
internal const val HomeTomeLibraryFocusRingClearanceDp = 15f
/**
 * Centered Collection content width ≈ 15 + 3×112.5 + 2×15 + 15 = 397.5dp.
 * The bounded band enlarges all six seats without stretching them across the full remainder.
 */
internal const val HomeTomeLibraryGridContentWidthDp = 398f
/**
 * Sizing budget only. No Tome grows on focus (policy: the ring alone marks focus), so the
 * cells pass `focusScale = 1f`; this value and [HomeTomeLibraryCompactFocusScale] still size the face.
 */
internal const val HomeTomeLibraryFocusScale = 1.03f
internal const val HomeTomeLibraryCompactRevealOutsetDp = 15f
internal const val HomeTomeLibraryMaxProjectedRows = 2

/** 48dp separator lane: beam centered with equal 24dp optical space on each side. */
internal val HomeTomeLibrarySeparatorLane = MimirSpacing.xxl + MimirSpacing.lg
internal val HomeTomeLibraryEdgePadding = MimirSpacing.xl
internal val HomeTomeLibraryDividerFrameInset = MimirSpacing.xs

/** Centers the complete Home + separator + Collection composition with equal outer side insets. */
internal fun homeTomeLibraryCenteredCompositionWidthDp(
    bodyWidthDp: Float,
    homeFaceWidthDp: Float,
    showsHome: Boolean,
): Float {
    val homeBand = if (showsHome) {
        homeFaceWidthDp + HomeTomeLibrarySeparatorLane.value
    } else {
        0f
    }
    return minOf(bodyWidthDp, homeBand + HomeTomeLibraryGridContentWidthDp).coerceAtLeast(0f)
}

/** Locates the Tome Library's glass panel for the centring geometry tests. */
internal const val HomeTomeLibraryPanelTestTag = "home_tome_library_panel"
internal const val HomeTomeLibraryHeaderTestTag = "home_tome_library_header"
internal const val HomeTomeLibraryFooterTestTag = "home_tome_library_footer"
/** The footer's key-hint line, tagged for the test that steps its swap. */
internal const val HomeTomeLibraryFooterHintTestTag = "home_tome_library_footer_hint"
/** The Home book + beam + collection band, the thing the menu centres. */
internal const val HomeTomeLibraryCompositionTestTag = "home_tome_library_composition"

/** The most of the screen the panel may take on either axis; the rest is the hero art around it. */
internal const val HomeTomeLibraryPanelViewportFraction = 0.94f
internal const val HomeTomeLibraryPanelMaxWidthDp = 960f
internal const val HomeTomeLibraryHeaderHeightDp = 22f
internal const val HomeTomeLibraryFooterHeightDp = 38f

/**
 * The header and the footer are fixed-height strips, so they scale with the font: at 2.0 a 22 dp strip cut the
 * eyebrow in half. One scale for both, never below 1 (a shrunk font keeps the designed strip) and never past the
 * 2.0 the system offers.
 */
internal fun homeTomeLibraryChromeScale(fontScale: Float): Float =
    if (fontScale.isFinite()) fontScale.coerceIn(1f, 2f) else 1f

internal fun homeTomeLibraryCenteredOuterInsetDp(
    bodyWidthDp: Float,
    compositionWidthDp: Float,
): Float = ((bodyWidthDp - compositionWidthDp) / 2f).coerceAtLeast(0f)

/** H=1 iff entry 0 is the fixed Home Overview; collection cell = flatIndex - H. */
internal fun homeTomeLibraryHeroOffset(entries: List<HomeTomeLibraryEntry>): Int =
    if (entries.firstOrNull()?.isHomeShelf == true) 1 else 0

internal fun homeTomeLibraryCollectionCount(entries: List<HomeTomeLibraryEntry>): Int =
    (entries.size - homeTomeLibraryHeroOffset(entries)).coerceAtLeast(0)

internal fun homeTomeLibraryCellOf(flatIndex: Int, heroOffset: Int): Int =
    (flatIndex - heroOffset).coerceAtLeast(0)

internal fun homeTomeLibraryRowOf(flatIndex: Int, heroOffset: Int): Int =
    homeTomeLibraryCellOf(flatIndex, heroOffset) / HomeTomeLibraryGridColumns

internal fun homeTomeLibraryColumnOf(flatIndex: Int, heroOffset: Int): Int =
    homeTomeLibraryCellOf(flatIndex, heroOffset) % HomeTomeLibraryGridColumns

internal fun homeTomeLibraryFlatIndex(cell: Int, heroOffset: Int): Int = heroOffset + cell

/**
 * Default sticky column when callers rebuild with entries + focusedIndex only.
 * Collection-grid seats derive their 3-column column; Home / empty / OOB stay 0.
 */
internal fun homeTomeLibraryDefaultDesiredColumn(
    entries: List<HomeTomeLibraryEntry>,
    focusedIndex: Int,
): Int {
    val hero = homeTomeLibraryHeroOffset(entries)
    if (focusedIndex < hero || focusedIndex !in entries.indices) return 0
    return homeTomeLibraryColumnOf(focusedIndex, hero)
}

/**
 * Default last-grid identity when callers rebuild with entries + focusedIndex only.
 * Collection-grid seats stamp their stableKey; Home / empty / OOB stay null.
 */
internal fun homeTomeLibraryDefaultLastGridStableKey(
    entries: List<HomeTomeLibraryEntry>,
    focusedIndex: Int,
): String? {
    val hero = homeTomeLibraryHeroOffset(entries)
    if (focusedIndex < hero || focusedIndex !in entries.indices) return null
    return entries[focusedIndex].stableKey
}

/** Honest 2-D virtual bounds for Home + collection grid. Separator is never a slot. */
internal fun homeTomeLibraryFocusSlots(entries: List<HomeTomeLibraryEntry>): List<HomeTomeLibraryFocusSlot> {
    val hero = homeTomeLibraryHeroOffset(entries)
    val homeWidth = HomeTomeLibraryHomeFaceWidthDp
    val homeHeight = HomeTomeLibraryHomeFaceHeightDp
    val gridOriginX = homeWidth + HomeTomeLibrarySeparatorLane.value
    val cellW = HomeTomeLibraryCompactMaxFaceWidthDp
    val cellH = HomeTomeLibraryCompactMaxFaceHeightDp
    val colPitch = cellW + HomeTomeLibraryGridColumnGapDp
    val rowPitch = cellH + HomeTomeLibraryGridRowGapDp
    return entries.mapIndexed { index, entry ->
        val bounds = if (hero > 0 && index == 0) {
            FocusBounds(left = 0f, top = 0f, right = homeWidth, bottom = homeHeight)
        } else {
            val cell = index - hero
            val col = cell % HomeTomeLibraryGridColumns
            val row = cell / HomeTomeLibraryGridColumns
            val left = gridOriginX + col * colPitch
            val top = row * rowPitch
            FocusBounds(left = left, top = top, right = left + cellW, bottom = top + cellH)
        }
        HomeTomeLibraryFocusSlot(key = homeTomeLibraryFocusKey(entry), bounds = bounds)
    }
}

internal class HomeTomeLibraryFocusRegistry {
    private val registered = linkedSetOf<FocusKey>()

    fun update(authority: FocusAuthority, slots: List<HomeTomeLibraryFocusSlot>) {
        val desired = slots.mapTo(linkedSetOf(), HomeTomeLibraryFocusSlot::key)
        // Incoming identities register first. A create/delete update therefore never empties the live grid.
        slots.forEach { slot ->
            authority.registerVirtual(slot.key, HomeTomeLibraryScope, slot.bounds)
        }
        authority.unregisterVirtualAll(registered.filterNot(desired::contains))
        registered.clear()
        registered.addAll(desired)
    }

    fun clear(authority: FocusAuthority) {
        authority.unregisterVirtualAll(registered)
        registered.clear()
    }
}

internal fun homeTomeLibraryFocusKey(entry: HomeTomeLibraryEntry): FocusKey =
    FocusKey("home/tome/library/${entry.stableKey}")

internal fun homeTomeLibraryRestoredIndex(
    entries: List<HomeTomeLibraryEntry>,
    rememberedFocus: FocusKey?,
    fallbackIndex: Int = 0,
): Int {
    val exact = entries.indexOfFirst { homeTomeLibraryFocusKey(it) == rememberedFocus }
    return if (exact >= 0) exact else fallbackIndex.coerceIn(0, entries.lastIndex.coerceAtLeast(0))
}

/**
 * Resolve a remembered collection identity after pin reorder/removal. Exact stable key wins;
 * otherwise the nearest remaining collection seat (then first) — never raw-index-only.
 */
internal fun homeTomeLibraryResolveCollectionIndex(
    entries: List<HomeTomeLibraryEntry>,
    preferredStableKey: String?,
    preferredFlatHint: Int = -1,
): Int {
    val hero = homeTomeLibraryHeroOffset(entries)
    if (entries.size <= hero) return 0.coerceAtMost(entries.lastIndex.coerceAtLeast(0))
    preferredStableKey?.let { key ->
        val exact = entries.indexOfFirst { it.stableKey == key }
        if (exact >= hero) return exact
    }
    if (preferredFlatHint in hero until entries.size) return preferredFlatHint
    if (preferredFlatHint >= 0) {
        return (hero until entries.size).minBy { abs(it - preferredFlatHint) }
    }
    return hero
}

internal fun homeTomeLibraryReconcileLastGridKey(
    entries: List<HomeTomeLibraryEntry>,
    previousKey: String?,
    previousFlatHint: Int,
): String? {
    val hero = homeTomeLibraryHeroOffset(entries)
    if (entries.size <= hero) return null
    val index = homeTomeLibraryResolveCollectionIndex(entries, previousKey, previousFlatHint)
    return entries.getOrNull(index)?.stableKey?.takeIf { index >= hero }
}

/** Exact edge trim for a selected Tome that is composed but still clipped by the viewport. */
internal fun homeTomeLibraryFocusScrollDelta(
    itemStart: Int,
    itemEnd: Int,
    viewportStart: Int,
    viewportEnd: Int,
    visualOutset: Int = 0,
    revealBefore: Int = 0,
    revealAfter: Int = 0,
): Int = when {
    itemStart - visualOutset - revealBefore < viewportStart ->
        itemStart - visualOutset - revealBefore - viewportStart
    itemEnd + visualOutset + revealAfter > viewportEnd ->
        itemEnd + visualOutset + revealAfter - viewportEnd
    else -> 0
}

/**
 * Signed scroll that seats a focused Collection row at the leading reading edge.
 *
 * Unlike [homeTomeLibraryFocusScrollDelta], this is intentionally non-zero for a fully visible row:
 * vertical focus movement must carry the Tome sheet, not merely change a logical key inside a static
 * viewport. The grid's ScrollableState clamps the first/last rows at their honest terminals.
 */
internal fun homeTomeLibraryFocusSettlementDelta(
    itemStart: Int,
    viewportStart: Int,
    visualOutset: Int = 0,
): Int = itemStart - (viewportStart + visualOutset.coerceAtLeast(0))

/** Ordinary adjacent D-pad travel may project an uncomposed target; deep restore seeks directly. */
internal fun homeTomeLibraryCanProjectReveal(targetIndex: Int, anchorIndex: Int): Boolean =
    abs(targetIndex - anchorIndex) <= HomeTomeLibraryMaxProjectedRows

/** Whole-card capacity after reserving both outside-ring reveal bands; never below one. */
internal fun homeTomeLibraryVisibleCount(
    viewportLength: Int,
    itemStride: Int,
    itemSize: Int = itemStride,
    visualOutset: Int = 0,
): Int {
    if (itemStride <= 0 || itemSize <= 0) return 1
    val safeViewport = (viewportLength - visualOutset.coerceAtLeast(0) * 2).coerceAtLeast(0)
    if (safeViewport <= itemSize) return 1
    return (1 + (safeViewport - itemSize) / itemStride).coerceAtLeast(1)
}

/** A one-visible-item measurement still includes the authored inter-Tome gap in offscreen estimates. */
internal fun homeTomeLibraryMeasuredStride(
    firstOffset: Int,
    secondOffset: Int?,
    itemSize: Int,
    itemGap: Int,
): Int = secondOffset
    ?.minus(firstOffset)
    ?.takeIf { it > 0 }
    ?: (itemSize + itemGap.coerceAtLeast(0)).coerceAtLeast(1)

/** Distinguishes layout restoration from ordinary focus travel without keying on pin/style changes. */
internal class HomeTomeLibraryTravelHistory {
    private var initialized = false
    private var entryStableKeys: List<String> = emptyList()

    fun consumeLayout(stableKeys: List<String>): Boolean {
        val restoring = !initialized || entryStableKeys != stableKeys
        initialized = true
        entryStableKeys = stableKeys.toList()
        return restoring
    }
}

internal data class HomeTomeLibraryLayoutGeometry(
    val viewportLength: Int,
    val itemSize: Int,
    val itemStride: Int,
)

internal fun homeTomeLibraryLayoutGeometry(
    viewportStart: Int,
    viewportEnd: Int,
    firstOffset: Int?,
    secondOffset: Int?,
    itemSize: Int?,
    itemGap: Int,
): HomeTomeLibraryLayoutGeometry? {
    val size = itemSize?.takeIf { it > 0 } ?: return null
    val first = firstOffset ?: return null
    val viewportLength = (viewportEnd - viewportStart).takeIf { it > 0 } ?: return null
    return HomeTomeLibraryLayoutGeometry(
        viewportLength = viewportLength,
        itemSize = size,
        itemStride = homeTomeLibraryMeasuredStride(first, secondOffset, size, itemGap),
    )
}

/** Fixed separator lane midpoint for the draw-only beam (never an entry). */
internal fun homeTomeLibraryDividerCenterPx(
    homeWidthPx: Int,
    separatorLanePx: Int,
): Int = homeWidthPx + separatorLanePx / 2

internal fun homeTomeLibraryFlatFirstVisibleIndex(gridFirstVisible: Int, heroOffset: Int): Int =
    (gridFirstVisible.coerceAtLeast(0) + heroOffset.coerceAtLeast(0))

internal fun homeTomeLibraryGridFirstVisibleIndex(flatFirstVisible: Int, heroOffset: Int): Int =
    (flatFirstVisible - heroOffset.coerceAtLeast(0)).coerceAtLeast(0)

/**
 * Compact collection face height from live constraints. Never branches on device name.
 * Solves for exactly [HomeTomeLibraryGridVisibleRows] rows inside ring clearance + gaps,
 * capped at 150dp / floored at 64dp by the resolver. Width is taken from the centered ~398dp
 * content band so the six visible seats stay at the authored 3:4 Tome ratio.
 */
internal fun homeTomeLibraryCompactFaceHeightDp(
    bodyHeightDp: Float,
    gridWidthDp: Float,
    focusScale: Float = HomeTomeLibraryCompactFocusScale,
): Float {
    val rows = HomeTomeLibraryGridVisibleRows.toFloat()
    val fromHeight = (
        bodyHeightDp -
            2f * HomeTomeLibraryFocusRingClearanceDp -
            (rows - 1f) * HomeTomeLibraryGridRowGapDp
        ) / (rows * focusScale.coerceAtLeast(0.01f))
    val contentW = minOf(gridWidthDp, HomeTomeLibraryGridContentWidthDp)
    val innerW = (contentW - 2f * HomeTomeLibraryGridOuterPaddingDp).coerceAtLeast(0f)
    val cellWidth = (
        innerW - (HomeTomeLibraryGridColumns - 1) * HomeTomeLibraryGridColumnGapDp
        ) / HomeTomeLibraryGridColumns.toFloat()
    val fromWidth = cellWidth / 0.75f
    return minOf(HomeTomeLibraryCompactMaxFaceHeightDp, fromHeight, fromWidth)
}

/**
 * Viewport height matching LazyVerticalGrid layout:
 * `2 * outerClearance + visibleRows * unscaled faceHeight + (visibleRows - 1) * rowGap`.
 * Do not multiply rows by focusScale — the 15dp clearance already budgets whole ring + 1.02 scale.
 */
internal fun homeTomeLibraryGridViewportHeightDp(
    faceHeightDp: Float,
    visibleRows: Int = HomeTomeLibraryGridVisibleRows,
    rowGapDp: Float = HomeTomeLibraryGridRowGapDp,
    clearanceDp: Float = HomeTomeLibraryFocusRingClearanceDp,
): Float {
    val rows = visibleRows.coerceAtLeast(1)
    return 2f * clearanceDp + faceHeightDp * rows + rowGapDp * (rows - 1)
}

internal fun homeTomeLibraryResolvedCompactFaceHeightDp(
    bodyHeightDp: Float,
    gridWidthDp: Float,
    focusScale: Float = HomeTomeLibraryCompactFocusScale,
): Float {
    val derived = homeTomeLibraryCompactFaceHeightDp(bodyHeightDp, gridWidthDp, focusScale)
    return if (derived < HomeTomeLibraryCompactMinFaceHeightDp) {
        HomeTomeLibraryCompactMinFaceHeightDp
    } else {
        derived
    }
}

/**
 * How many compact LazyGrid rows fit in [bodyHeightDp] at unscaled face height
 * after reserving both clearance bands and inter-row gaps (never below one).
 */
internal fun homeTomeLibraryVisibleRowCapacity(
    bodyHeightDp: Float,
    faceHeightDp: Float,
    rowGapDp: Float = HomeTomeLibraryGridRowGapDp,
    clearanceDp: Float = HomeTomeLibraryFocusRingClearanceDp,
): Int {
    if (faceHeightDp <= 0f) return 1
    val usable = (bodyHeightDp - 2f * clearanceDp).coerceAtLeast(0f)
    val n = ((usable + rowGapDp) / (faceHeightDp + rowGapDp)).toInt()
    return n.coerceAtLeast(1)
}

/** Fixed Home book height: slightly smaller than the old full-row seat, full ring retained. */
internal fun homeTomeLibraryHomeFaceHeightDp(bodyHeightDp: Float): Float =
    minOf(HomeTomeLibraryHomeFaceHeightDp, (bodyHeightDp - 8f).coerceAtLeast(160f))

/** Legacy landscape envelope retained for callers/tests that still size the Home book lane. */
internal fun homeTomeLibraryFaceHeightDp(viewportHeightDp: Float): Float =
    minOf(248f, (viewportHeightDp * 0.94f - 104f).coerceAtLeast(176f))

internal fun homeTomeLibraryContainedFaceHeightDp(
    laneHeightDp: Float,
    requestedFaceHeightDp: Float,
): Float {
    val envelope = laneHeightDp - 8f - 14f * 2f
    return minOf(
        requestedFaceHeightDp,
        (envelope / HomeTomeLibraryFocusScale).coerceAtLeast(1f),
    )
}

internal data class HomeTomeLibrarySettlementState(
    val zeroFrameObserved: Boolean = false,
    val settled: Boolean = false,
)

/** Two consecutive composed zero-delta frames are required; absence or a correction disarms. */
internal fun advanceHomeTomeLibrarySettlement(
    state: HomeTomeLibrarySettlementState,
    scrollDelta: Int?,
): HomeTomeLibrarySettlementState = when {
    scrollDelta == null || scrollDelta != 0 -> HomeTomeLibrarySettlementState()
    state.zeroFrameObserved -> HomeTomeLibrarySettlementState(zeroFrameObserved = true, settled = true)
    else -> HomeTomeLibrarySettlementState(zeroFrameObserved = true)
}

internal sealed interface HomeTomeLibraryEntry {
    val stableKey: String
    val section: HomeTomeLibrarySection

    data class Tome(
        val model: HomeShelfTome,
        val pinned: Boolean,
        override val section: HomeTomeLibrarySection,
    ) : HomeTomeLibraryEntry {
        override val stableKey: String = model.tomeId.stableBrowserKey()
    }

    data object CreateCustom : HomeTomeLibraryEntry {
        override val stableKey: String = "action/create-custom-tome"
        override val section: HomeTomeLibrarySection = HomeTomeLibrarySection.ACTION
    }
}

internal val HomeTomeLibraryEntry.isHomeShelf: Boolean
    get() = this is HomeTomeLibraryEntry.Tome && model.tomeId == TomeId.HomeOverview

internal data class HomeTomePinMutation(
    val tomeId: TomeId,
    val pinned: Boolean,
)

internal data class HomeTomePinFeedback(
    val eventId: Long,
    val tomeId: TomeId,
    val pinned: Boolean,
    val saved: Boolean,
)

internal data class HomeTomeLibraryState(
    val entries: List<HomeTomeLibraryEntry>,
    val invokingEntry: HomeShelfEntry?,
    /** Copy-stable, process-local identity; a closed/reopened browser is never the same async target. */
    val sessionToken: HomeTomeLibrarySessionToken = HomeTomeLibrarySessionToken(),
    val focusedIndex: Int = 0,
    /** Visual settlement telemetry only; controller actions use FocusAuthority's logical destination. */
    val settledFocusedIndex: Int? = null,
    val firstVisibleIndex: Int = 0,
    /** Process-local sticky desired grid column; omitted rebuilds derive from focused seat. */
    val desiredColumn: Int = homeTomeLibraryDefaultDesiredColumn(entries, focusedIndex),
    /** Stable last collection-grid identity for Home RIGHT round-trips. */
    val lastGridStableKey: String? = homeTomeLibraryDefaultLastGridStableKey(entries, focusedIndex),
    val draft: String? = null,
    val editingTomeId: TomeId.Custom? = null,
    val draftError: String? = null,
    val confirmDelete: Boolean = false,
    val deletePending: Boolean = false,
    val pendingFocusTomeId: TomeId.Custom? = null,
    val pinMutation: HomeTomePinMutation? = null,
    val pinFeedback: HomeTomePinFeedback? = null,
    /**
     * The browser has opened a Tome and is now HOLDING ITS PIXELS until the Folio has finished
     * materialising underneath it.
     *
     *
     *
     * Measured on a Nova test device at 60fps before this existed: CONFIRM nulled this state on the spot, so the
     * browser began dissolving on one clock while the Folio ramped in on another, and for ELEVEN frames
     * (~185 ms) neither surface was opaque — scene sharpness fell from 1563 to 83, which is the blurred
     * Home shelf showing through the hole between them.
     *
     * Holding is not a second animation and deliberately not a longer cross-fade. The dissolve-then-clear requirement
     * for this class of defect followed evidence showing that compositing two
     * detailed UIs at partial alpha is mush ([mimirOutgoingChromeHoldMs]). This keeps the browser fully
     * OPAQUE, so no frame composites anything, and the dissolve it already had now runs over a FINISHED
     * Folio instead of over a hole.
     *
     * While true the browser is inert: it paints and swallows input, because the surface that owns the
     * screen is the one underneath it.
     */
    val handingOff: Boolean = false,
    /**
     * Which of the browser's two entry paths produced THIS presentation — the real signal CP01's
     * incoming-pixel hold discriminates on, preventing the Folio return from inheriting it.
     *
     * `HOME_SHELF` is CP01's measured defect — a cold `X` press on the Home shelf
     * (`onTomes()`, `MimirAppInputDispatch.kt`) — and is what the 130ms hold exists for.
     *
     * `FOLIO_RETURN` is the browser re-presenting as a Tome Folio closes back to it
     * (`closeTomeSurface`, `MimirAppInputDispatch.kt`, which republishes this state BEFORE
     * `HomeShelfCoordinator.kt`'s `closeTomeToLibrary` actually tears the Folio down). That hop already
     * has its own frame-driven coordination — FOUR frames. Stacking CP01's hold would slow it
     * further; the measured defect covered only the Home-shelf open, not this hop.
     *
     * Defaults to `HOME_SHELF` so an unwired construction keeps applying the hold (today's shipped
     * behaviour for the one path CP01 actually measured), but both real construction sites set this
     * explicitly rather than relying on the default — see `MimirAppInputDispatch.kt`.
     */
    val openOrigin: HomeTomeLibraryOpenOrigin = HomeTomeLibraryOpenOrigin.HOME_SHELF,
)

/** See [HomeTomeLibraryState.openOrigin]. */
internal enum class HomeTomeLibraryOpenOrigin { HOME_SHELF, FOLIO_RETURN }

internal class HomeTomeLibrarySessionToken

/**
 * Rebuild an active browser after an async pin write without replaying a captured index. The token
 * rejects a closed/reopened session; stable entry identities let the acted Tome follow its authored
 * section move without replaying a captured list index.
 */
internal fun reconcilePinnedHomeTomeLibrary(
    live: HomeTomeLibraryState?,
    expectedSession: HomeTomeLibrarySessionToken,
    entries: List<HomeTomeLibraryEntry>,
    actedTomeId: TomeId? = null,
): HomeTomeLibraryState? {
    val current = live?.takeIf { it.sessionToken === expectedSession } ?: return null
    if (entries.isEmpty()) {
        return current.copy(
            entries = emptyList(),
            focusedIndex = 0,
            firstVisibleIndex = 0,
            desiredColumn = 0,
            lastGridStableKey = null,
        )
    }
    fun remap(index: Int): Int {
        val stableKey = current.entries.getOrNull(index)?.stableKey
        return entries.indexOfFirst { it.stableKey == stableKey }
            .takeIf { it >= 0 }
            ?: index.coerceIn(entries.indices)
    }
    val actedIndex = actedTomeId?.let { tomeId ->
        entries.indexOfFirst { entry ->
            entry is HomeTomeLibraryEntry.Tome && entry.model.tomeId == tomeId
        }.takeIf { it >= 0 }
    }
    val focusedIndex = actedIndex ?: remap(current.focusedIndex)
    val hero = homeTomeLibraryHeroOffset(entries)
    val previousHint = current.entries.indexOfFirst { it.stableKey == current.lastGridStableKey }
        .takeIf { it >= 0 }
        ?: current.focusedIndex
    val lastGridStableKey = homeTomeLibraryReconcileLastGridKey(
        entries = entries,
        previousKey = current.lastGridStableKey,
        previousFlatHint = previousHint,
    )
    val desiredColumn = when {
        focusedIndex >= hero -> homeTomeLibraryColumnOf(focusedIndex, hero)
        else -> current.desiredColumn.coerceIn(0, HomeTomeLibraryGridColumns - 1)
    }
    return current.copy(
        entries = entries,
        focusedIndex = focusedIndex,
        settledFocusedIndex = null,
        firstVisibleIndex = actedIndex ?: remap(current.firstVisibleIndex),
        desiredColumn = desiredColumn,
        lastGridStableKey = lastGridStableKey,
    )
}

internal fun homeTomeLibraryEntries(
    document: HomeTomeDocument,
    availableTomes: Map<TomeId, HomeShelfTome>,
    homeOverview: HomeShelfTome? = null,
): List<HomeTomeLibraryEntry> {
    val home = homeOverview?.let {
        HomeTomeLibraryEntry.Tome(it, pinned = false, section = HomeTomeLibrarySection.HOME)
    }
    // The browser is the custom-Tome cabinet: platform and pin records remain durable projection
    // data, but must not duplicate the loose Home/library experience here.
    val pins = document.pinOrder.toSet()
    val custom = document.customTomes.withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<CustomHomeTome>> { it.value.createdAtMillis != null }
                .thenByDescending { it.value.createdAtMillis ?: Long.MIN_VALUE }
                .thenBy { it.index },
        )
        .mapNotNull { indexed ->
        val tome = indexed.value
        availableTomes[tome.id]?.let { model ->
            HomeTomeLibraryEntry.Tome(model, pinned = tome.id in pins, section = HomeTomeLibrarySection.CUSTOM)
        }
    }
    return listOfNotNull(home) +
        custom.distinctBy(HomeTomeLibraryEntry::stableKey) +
        HomeTomeLibraryEntry.CreateCustom
}

/**
 * 3-column spatial reducer for the Collection grid beside fixed Home.
 * Home UP/DOWN/LEFT are inert and never claim nav feedback. Terminal directions no-op silently.
 */
internal fun navigateHomeTomeLibrary(
    state: HomeTomeLibraryState,
    action: ControllerAction,
): HomeTomeLibraryState {
    val entries = state.entries
    if (entries.isEmpty()) return state
    val hero = homeTomeLibraryHeroOffset(entries)
    val focused = state.focusedIndex.coerceIn(0, entries.lastIndex)
    val onHome = hero > 0 && focused == 0
    val collectionCount = homeTomeLibraryCollectionCount(entries)
    val desired = state.desiredColumn.coerceIn(0, HomeTomeLibraryGridColumns - 1)

    fun focusGrid(flat: Int, retainDesired: Boolean = false): HomeTomeLibraryState {
        val col = homeTomeLibraryColumnOf(flat, hero)
        return state.copy(
            focusedIndex = flat,
            settledFocusedIndex = null,
            desiredColumn = if (retainDesired) desired else col,
            lastGridStableKey = entries[flat].stableKey,
        )
    }

    return when (action) {
        ControllerAction.NAV_RIGHT -> {
            if (onHome) {
                if (collectionCount <= 0) return state
                val target = homeTomeLibraryResolveCollectionIndex(
                    entries = entries,
                    preferredStableKey = state.lastGridStableKey,
                    preferredFlatHint = hero,
                )
                if (target == focused) return state
                MimirNavFeedback.consumed()
                focusGrid(target)
            } else {
                val cell = focused - hero
                val col = cell % HomeTomeLibraryGridColumns
                val row = cell / HomeTomeLibraryGridColumns
                if (col >= HomeTomeLibraryGridColumns - 1) return state
                val next = focused + 1
                if (next >= entries.size) return state
                if ((next - hero) / HomeTomeLibraryGridColumns != row) return state
                MimirNavFeedback.consumed()
                focusGrid(next)
            }
        }
        ControllerAction.NAV_LEFT -> {
            if (onHome) return state
            val cell = focused - hero
            val col = cell % HomeTomeLibraryGridColumns
            if (col > 0) {
                MimirNavFeedback.consumed()
                focusGrid(focused - 1)
            } else if (hero > 0) {
                MimirNavFeedback.consumed()
                state.copy(
                    focusedIndex = 0,
                    settledFocusedIndex = null,
                    lastGridStableKey = entries[focused].stableKey,
                )
            } else {
                state
            }
        }
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
            if (onHome) return state
            if (collectionCount <= 0) return state
            val cell = focused - hero
            val row = cell / HomeTomeLibraryGridColumns
            val lastRow = (collectionCount - 1) / HomeTomeLibraryGridColumns
            val targetRow = if (action == ControllerAction.NAV_DOWN) row + 1 else row - 1
            if (targetRow < 0 || targetRow > lastRow) return state
            val rowStart = targetRow * HomeTomeLibraryGridColumns
            val rowEnd = minOf(rowStart + HomeTomeLibraryGridColumns - 1, collectionCount - 1)
            val maxCol = rowEnd - rowStart
            val col = desired.coerceAtMost(maxCol)
            val nextFlat = hero + rowStart + col
            if (nextFlat == focused) return state
            MimirNavFeedback.consumed()
            focusGrid(nextFlat, retainDesired = true)
        }
        else -> state
    }
}

internal fun reduceHomeTomeLibrary(
    state: HomeTomeLibraryState,
    action: ControllerAction,
    onOpen: (TomeId) -> Boolean,
    onTogglePinned: (TomeId, Boolean) -> Unit,
    onDismiss: (HomeShelfEntry?) -> Unit,
    onDelete: (TomeId.Custom) -> Unit = {},
): HomeTomeLibraryState? {
    // Inert while handing off: it is painting over a Folio that already owns the screen, so consuming a
    // press here would act on a browser the player can no longer see the focus ring of.
    if (state.handingOff) return state
    if (state.draft != null) {
        if (state.deletePending) return state
        return when (action) {
            ControllerAction.TERTIARY -> if (state.editingTomeId != null && !state.deletePending) {
                state.copy(confirmDelete = true)
            } else state
            ControllerAction.CONFIRM -> if (state.confirmDelete && !state.deletePending) {
                onDelete(requireNotNull(state.editingTomeId))
                state.copy(deletePending = true)
            } else state
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> if (state.confirmDelete) {
                state.copy(confirmDelete = false)
            } else state.copy(
                    draft = null,
                    editingTomeId = null,
                    draftError = null,
                )
            else -> state
        }
    }
    // A pin write moves this stable Tome between authored sections. Keep Back available, but freeze
    // every identity-changing action until durable truth has rebuilt the carousel; otherwise a quick
    // NAV/A/Y can act on one Tome and then be yanked back to the Tome whose write just settled.
    if (state.pinMutation != null) {
        return when (action) {
            ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
                onDismiss(state.invokingEntry)
                null
            }
            else -> state
        }
    }
    return when (action) {
        ControllerAction.NAV_UP,
        ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT,
        ControllerAction.NAV_RIGHT -> navigateHomeTomeLibrary(state, action)
        ControllerAction.CONFIRM -> when (val entry = state.entries.getOrNull(state.focusedIndex)) {
            is HomeTomeLibraryEntry.Tome -> {
                // Was `null` — cleared on the spot, which is what opened the hole. Both tome paths come
                // through here (a custom Tome and the Home Shelf overview alike), so both get the hold.
                if (onOpen(entry.model.tomeId)) state.copy(handingOff = true) else state
            }
            HomeTomeLibraryEntry.CreateCustom -> state.copy(draft = "", editingTomeId = null)
            null -> state
        }
        ControllerAction.SECONDARY -> {
            val entry = state.entries.getOrNull(state.focusedIndex) as? HomeTomeLibraryEntry.Tome
                ?: return state
            if (entry.isHomeShelf) return state
            val nextPinned = !entry.pinned
            onTogglePinned(entry.model.tomeId, nextPinned)
            state.copy(
                pinMutation = HomeTomePinMutation(entry.model.tomeId, nextPinned),
                pinFeedback = null,
            )
        }
        ControllerAction.TERTIARY -> when (val entry = state.entries.getOrNull(state.focusedIndex)) {
            is HomeTomeLibraryEntry.Tome -> {
                if (entry.isHomeShelf) return state
                val custom = entry.model.tomeId as? TomeId.Custom
                state.copy(
                    draft = if (custom == null) "" else entry.model.title,
                    editingTomeId = custom,
                    draftError = null,
                )
            }
            HomeTomeLibraryEntry.CreateCustom -> state
            null -> state
        }
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
            onDismiss(state.invokingEntry)
            null
        }
        else -> state
    }
}

internal data class HomeTomeLibraryPosition(
    val current: Int,
    val total: Int,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
) {
    val label: String get() = "$current / $total"
}

/** Footer reports grid/vertical truth — never a horizontal carousel seat. */
internal fun homeTomeLibraryPosition(
    focusedIndex: Int,
    entries: List<HomeTomeLibraryEntry>,
): HomeTomeLibraryPosition {
    val entryCount = entries.size
    if (entryCount <= 0) {
        return HomeTomeLibraryPosition(current = 0, total = 0, canMoveUp = false, canMoveDown = false)
    }
    val index = focusedIndex.coerceIn(0, entryCount - 1)
    val hero = homeTomeLibraryHeroOffset(entries)
    val onHome = hero > 0 && index == 0
    val collectionCount = homeTomeLibraryCollectionCount(entries)
    val canMoveUp = when {
        onHome -> false
        collectionCount <= 0 -> false
        else -> homeTomeLibraryRowOf(index, hero) > 0
    }
    val canMoveDown = when {
        onHome -> false
        collectionCount <= 0 -> false
        else -> {
            val row = homeTomeLibraryRowOf(index, hero)
            val lastRow = (collectionCount - 1) / HomeTomeLibraryGridColumns
            row < lastRow
        }
    }
    return HomeTomeLibraryPosition(
        current = index + 1,
        total = entryCount,
        canMoveUp = canMoveUp,
        canMoveDown = canMoveDown,
    )
}

@Composable
internal fun HomeTomeLibraryOverlay(
    state: HomeTomeLibraryState,
    authority: FocusAuthority,
    active: Boolean,
    reducedMotion: Boolean = false,
    onFirstVisibleIndex: (Int) -> Unit,
    onFocusUnsettled: () -> Unit = {},
    onFocusSettled: (Int) -> Unit,
    onFocusChanged: (Int) -> Unit = {},
    onActivate: (Int) -> Unit,
    onDraftChange: (String) -> Unit,
    onDraftDone: () -> Unit,
    onDeleteAction: () -> Unit,
    onPinFeedbackConsumed: (Long) -> Unit = {},
    onKeyboardHostChanged: (MimirKeyboardHost?) -> Unit = {},
    /**
     * The focused tome's backdrop art, reported UP rather than painted here.
     *
     * Home and Tome share one backdrop. This overlay used to mount its own
     * full-screen [VideoBackdrop], so crossing the Home/Tome boundary mounted and unmounted an entire
     * backdrop -- measured on the Odin as 30.6 of frame difference in a SINGLE frame, against 3% for a
     * real crossfade in the same recording. The shelf's already-mounted stage paints this instead, and
     * the boundary becomes a URI change on a layer that never goes away.
     */
    onBackdropArt: (String?) -> Unit = {},
) {
    val focusKeys = remember(state.entries) { state.entries.map(::homeTomeLibraryFocusKey) }
    val focusSlots = remember(state.entries) { homeTomeLibraryFocusSlots(state.entries) }
    val focusRegistry = remember(authority) { HomeTomeLibraryFocusRegistry() }
    val focusState by authority.state.collectAsState()
    val browserActive = active && state.draft == null
    var presentedTomeId by remember { mutableStateOf<TomeId?>(null) }
    var presentedPreview by remember { mutableStateOf<HomeTomePreview?>(null) }
    val settledTomeId = (state.settledFocusedIndex?.let(state.entries::getOrNull)
        as? HomeTomeLibraryEntry.Tome)?.model?.tomeId
    LaunchedEffect(settledTomeId) {
        if (presentedTomeId != settledTomeId) {
            presentedTomeId = null
            presentedPreview = null
        }
    }
    var retainedEditorState by remember { mutableStateOf(state) }
    SideEffect {
        if (state.draft != null) retainedEditorState = state
    }
    val renderFocusedIndex = focusKeys.indexOf(focusState.focused).takeIf {
        browserActive && focusState.scope == HomeTomeLibraryScope && it >= 0
    }
    SideEffect {
        if (browserActive) focusRegistry.update(authority, focusSlots) else focusRegistry.clear(authority)
    }
    DisposableEffect(authority, focusRegistry) {
        onDispose { focusRegistry.clear(authority) }
    }
    LaunchedEffect(browserActive, state.focusedIndex, state.entries) {
        if (!browserActive) return@LaunchedEffect
        val key = state.entries.getOrNull(state.focusedIndex)?.let(::homeTomeLibraryFocusKey)
            ?: return@LaunchedEffect
        authority.rememberFocusForScope(HomeTomeLibraryScope, key)
        authority.requestFocus(key)
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(mimirGlassScrimColor())
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        // This overlay's own backdrop: the presented game's HERO art, over the theme's opaque floor that takes the
        // Home shelf beneath it to zero . See HomeTomeLibraryBackdrop for the one source it reads.
        HomeTomeLibraryBackdrop(
            presentedPreview = presentedPreview,
            onBackdropArt = onBackdropArt,
        )
        val viewportHeight = maxHeight
        val viewportWidth = maxWidth
        val tomeSwapGlidePx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.roundToPx() }
        // The host's own call, from its target and outside the pages: the browser is what a move back returns to. The
        // spec below and the pages' probe read this one value, so the page being left picks up the return clock in the
        // same composition that retargets it (mimirSwapFadeAlpha).
        val tomeReturning = state.draft == null
        AnimatedContent(
            targetState = if (state.draft == null) HomeTomeLibraryPresentation.BROWSER
                else HomeTomeLibraryPresentation.EDITOR,
            // The swap tier (motion audit batch A): the browser out, then the editor in from the 130 ms lead. Back to
            // the browser is a return: the browser at rest at once, the editor dissolving over it on the return clock.
            // Full-screen, so the fade layer is screen-sized and cuts no ring.
            // The editor rises into place like Customize media's pickers, and the browser glides back down
            // on the return clock rather than simply being there.
            transitionSpec = {
                mimirSwapTransform(
                    reducedMotion,
                    returning = tomeReturning,
                    returnTravelPx = tomeSwapGlidePx,
                    enterTravelPx = tomeSwapGlidePx,
                )
            },
            label = "tome-library-editor-fade",
            modifier = Modifier.fillMaxSize(),
        ) { presentation ->
            // Presses during a page change (alpha audit, 2026-09-30): the editor stands at alpha 0 through
            // the 130 ms lead with its keyboard already live, so an A inside the lead typed the landing key into the name
            // ("q" over an empty one). The transition fades the pages itself (fadeIn and fadeOut), so there is no alpha to
            // read here: the gate takes a probe on the same swap specs. The pad waits from this page's first frame until
            // it paints, or a reversal takes it off the target; Back and the shoulders pass. Touch reaches the reducer
            // without the router, so the page root takes none until it shows, and a page that leaves takes none at all.
            val pageInput = MimirSwapPageGate(mimirSwapFadeAlpha(reducedMotion, returning = tomeReturning))
            // Centre: this Box fills the screen and its default alignment is top-start, which pinned the browser's 94% panel
            // to the top-left corner. The editor fills it, so it is unaffected.
            Box(Modifier.fillMaxSize().then(pageInput), contentAlignment = Alignment.Center) {
            when (presentation) {
                HomeTomeLibraryPresentation.BROWSER -> HomeTomeLibraryBrowser(
                    state = state,
                    authority = authority,
                    interactive = browserActive,
                    renderFocusedIndex = renderFocusedIndex,
                    viewportHeight = viewportHeight,
                    viewportWidth = viewportWidth,
                    reducedMotion = reducedMotion,
                    onFirstVisibleIndex = onFirstVisibleIndex,
                    onFocusUnsettled = onFocusUnsettled,
                    onFocusSettled = onFocusSettled,
                    onActivate = onActivate,
                    onPinFeedbackConsumed = onPinFeedbackConsumed,
                    onPreviewPresented = { tomeId, preview ->
                        if (settledTomeId == tomeId) {
                            presentedTomeId = tomeId
                            presentedPreview = preview
                        }
                    },
                )
                HomeTomeLibraryPresentation.EDITOR -> HomeTomeNameEditor(
                    state = retainedEditorState,
                    authority = authority,
                    active = state.draft != null,
                    reducedMotion = reducedMotion,
                    onDraftChange = onDraftChange,
                    onDraftDone = onDraftDone,
                    onDeleteAction = onDeleteAction,
                    onKeyboardHostChanged = onKeyboardHostChanged,
                )
            }
            }
        }
    }
}

private enum class HomeTomeLibraryPresentation { BROWSER, EDITOR }

/**
 * The Tome Library's background and the ONE place its art is chosen.
 *
 * The collection background uses the game's HERO art, excluding the Home cover and icon. The art is
 * [homeTomeLibraryBackdropArt] -- the hero slot ([HomeTomePreview.heroArtUri]: the hero picked in Customize media, else the
 * downloaded one) and no other. A game without a hero gives null, and null is the theme's own backdrop: [VideoBackdrop]'s
 * opaque `signatureBottom` floor, which is also what takes the Home shelf beneath to zero. The rung this replaces fell
 * back to [HomeTomePreview.backdropArtUri]'s cover/tile icon and to the plate's own art; neither can reach it now.
 *
 * It paints the same art it REPORTS upward, which is what fixes the RETURN. On close this overlay is not kept composed to
 * fade -- MimirAppHost's house rule, and its reason is that a popped scope would still be taking pointer input. A backdrop
 * is inert paint and takes none, but the rule is about the OVERLAY, so the answer is not to animate this one away: it is to
 * make sure the same image is ALREADY underneath. The shelf's stage is mounted throughout, so handing it this art means the
 * overlay's copy can vanish onto an identical picture instead of onto the shelf's own hero.
 *
 * Visual only: hidden from accessibility. The image is [VideoBackdrop]'s -- a Coil request sized to the hero decode
 * envelope, so it loads off the main thread and sits in the same memory cache as the shelf's own hero. [paint] is the
 * seam the tests replace to see which art it was handed.
 */
@Composable
internal fun HomeTomeLibraryBackdrop(
    presentedPreview: HomeTomePreview?,
    onBackdropArt: (String?) -> Unit,
    paint: @Composable (artUri: String?) -> Unit = { art ->
        VideoBackdrop(
            videoUri = null,
            artUri = art,
            settleMs = 0L,
            modifier = Modifier.fillMaxSize(),
        )
    },
) {
    val art = homeTomeLibraryBackdropArt(presentedPreview)
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        paint(art)
        Box(Modifier.fillMaxSize().background(mimirGlassScrimColor()))
    }
    LaunchedEffect(art) { onBackdropArt(art) }
    DisposableEffect(Unit) { onDispose { onBackdropArt(null) } }
}

@Composable
private fun HomeTomeLibraryBrowser(
    state: HomeTomeLibraryState,
    authority: FocusAuthority,
    interactive: Boolean,
    renderFocusedIndex: Int?,
    viewportHeight: androidx.compose.ui.unit.Dp,
    viewportWidth: androidx.compose.ui.unit.Dp,
    reducedMotion: Boolean,
    onFirstVisibleIndex: (Int) -> Unit,
    onFocusUnsettled: () -> Unit,
    onFocusSettled: (Int) -> Unit,
    onActivate: (Int) -> Unit,
    onPinFeedbackConsumed: (Long) -> Unit,
    onPreviewPresented: (TomeId, HomeTomePreview?) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val heroOffset = homeTomeLibraryHeroOffset(state.entries)
    val collectionEntries = remember(state.entries, heroOffset) {
        state.entries.drop(heroOffset)
    }
    val entryStableKeys = remember(state.entries) {
        state.entries.map(HomeTomeLibraryEntry::stableKey)
    }
    val focusedStableKey = state.entries.getOrNull(state.focusedIndex)?.stableKey
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = homeTomeLibraryGridFirstVisibleIndex(
            flatFirstVisible = state.firstVisibleIndex,
            heroOffset = heroOffset,
        ).coerceIn(0, collectionEntries.lastIndex.coerceAtLeast(0)),
    )
    val travelHistory = remember(gridState) { HomeTomeLibraryTravelHistory() }
    val density = LocalDensity.current
    val focusRevealOutsetPx = with(density) { HomeTomeLibraryCompactRevealOutsetDp.dp.roundToPx() }
    val rowGapPx = with(density) { HomeTomeLibraryGridRowGapDp.dp.roundToPx() }

    LaunchedEffect(interactive, focusedStableKey, entryStableKeys, reducedMotion, heroOffset) {
        if (!interactive || state.focusedIndex !in state.entries.indices) return@LaunchedEffect
        val restoringLayout = travelHistory.consumeLayout(entryStableKeys)
        if (heroOffset > 0 && state.focusedIndex == 0) {
            onFocusSettled(0)
            return@LaunchedEffect
        }
        val targetGridIndex = state.focusedIndex - heroOffset
        if (targetGridIndex !in collectionEntries.indices) return@LaunchedEffect
        onFocusUnsettled()
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo }
            .first { visible -> visible.isNotEmpty() || collectionEntries.isEmpty() }

        if (collectionEntries.isEmpty()) {
            onFocusSettled(state.focusedIndex)
            return@LaunchedEffect
        }

        val targetRow = targetGridIndex / HomeTomeLibraryGridColumns
        if (restoringLayout) {
            // First mount and create/delete/reorder epochs carry no visible travel from the previous
            // layout. One nonanimated seek is authoritative; normal D-pad movement uses the branch below.
            gridState.scrollToItem(
                (targetRow * HomeTomeLibraryGridColumns)
                    .coerceIn(0, collectionEntries.lastIndex),
            )
            onFocusSettled(state.focusedIndex)
            return@LaunchedEffect
        }

        val initialLayout = gridState.layoutInfo
        val initialFocusedItem = initialLayout.visibleItemsInfo.firstOrNull { it.index == targetGridIndex }
        val settlementDelta = if (initialFocusedItem != null) {
            homeTomeLibraryFocusSettlementDelta(
                itemStart = initialFocusedItem.offset.y,
                viewportStart = initialLayout.viewportStartOffset,
                visualOutset = focusRevealOutsetPx,
            )
        } else {
            val layout = initialLayout
            val anchor = layout.visibleItemsInfo.minByOrNull {
                abs((it.index / HomeTomeLibraryGridColumns) - targetRow)
            }
            if (anchor != null &&
                homeTomeLibraryCanProjectReveal(
                    targetIndex = targetRow,
                    anchorIndex = anchor.index / HomeTomeLibraryGridColumns,
                )
            ) {
                val anchorRow = anchor.index / HomeTomeLibraryGridColumns
                val otherRowItem = layout.visibleItemsInfo.firstOrNull {
                    it.index / HomeTomeLibraryGridColumns != anchorRow
                }
                val stride = homeTomeLibraryMeasuredStride(
                    firstOffset = anchor.offset.y,
                    secondOffset = otherRowItem?.let { other ->
                        val otherRow = other.index / HomeTomeLibraryGridColumns
                        anchor.offset.y +
                            (other.offset.y - anchor.offset.y) / (otherRow - anchorRow)
                    },
                    itemSize = anchor.size.height,
                    itemGap = rowGapPx,
                )
                val projectedStart = anchor.offset.y + (targetRow - anchorRow) * stride
                homeTomeLibraryFocusSettlementDelta(
                    itemStart = projectedStart,
                    viewportStart = layout.viewportStartOffset,
                    visualOutset = focusRevealOutsetPx,
                )
            } else {
                0
            }
        }
        when {
            settlementDelta != 0 && reducedMotion -> gridState.scrollBy(settlementDelta.toFloat())
            settlementDelta != 0 -> gridState.animateScrollBy(
                value = settlementDelta.toFloat(),
                animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
            )
            initialFocusedItem == null -> gridState.scrollToItem(
                (targetRow * HomeTomeLibraryGridColumns)
                    .coerceIn(0, collectionEntries.lastIndex),
            )
        }
        // This first movement is authoritative. A measured trim after composition used to make the
        // sheet land twice; uniform row geometry plus terminal clamping gives the exact destination
        // without a second scroll mutation.
        onFocusSettled(state.focusedIndex)
    }
    LaunchedEffect(gridState, state.entries.size, heroOffset) {
        snapshotFlow { gridState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect { gridIndex ->
                onFirstVisibleIndex(
                    homeTomeLibraryFlatFirstVisibleIndex(gridIndex, heroOffset)
                        .coerceIn(0, state.entries.lastIndex.coerceAtLeast(0)),
                )
            }
    }
    LaunchedEffect(gridState, interactive) {
        gridState.interactionSource.interactions.collect { interaction ->
            // Pointer drag may unsettle visuals, but must never steal controller focus.
            if (interaction is DragInteraction.Start && interactive) onFocusUnsettled()
        }
    }
    LaunchedEffect(state.pinFeedback?.eventId) {
        val event = state.pinFeedback ?: return@LaunchedEffect
        delay(HomeTomeLibraryPinFeedbackHoldMs)
        onPinFeedbackConsumed(event.eventId)
    }
    val focusedEntry = state.entries.getOrNull(state.focusedIndex)
    val position = homeTomeLibraryPosition(state.focusedIndex, state.entries)
    val headerStatusRes = state.pinFeedback?.let {
        when {
            !it.saved -> R.string.app_tome_library_status_update_failed
            it.pinned -> R.string.app_tome_library_status_pinned_to_home
            else -> R.string.app_tome_library_status_unpinned_from_home
        }
    } ?: when (focusedEntry?.section) {
        HomeTomeLibrarySection.HOME -> R.string.app_tome_library_status_home_shelf
        HomeTomeLibrarySection.PINNED -> R.string.app_tome_library_status_pinned
        HomeTomeLibrarySection.CUSTOM -> R.string.app_tome_library_status_custom
        HomeTomeLibrarySection.PLATFORM -> R.string.app_tome_library_status_platforms
        HomeTomeLibrarySection.ACTION -> R.string.app_tome_library_status_create
        null -> R.string.app_tome_library_status_tomes
    }
    val headerStatus = stringResource(headerStatusRes)

    // fillMaxWidth(fraction).widthIn(max=) was inert here: fillMaxWidth fixes min==max at the fraction
    // of the viewport width, so widthIn's cap only ever coerced UP to that fixed width.
    //
    // the menu is CENTRED. The page's own Box (above, in HomeTomeLibraryOverlay) used to be
    // top-start, so this 94% panel sat flush top-left with the whole 6% slack on its right and bottom. The page Box
    // centres it on both axes now, so the margins either side and above and below are equal at any size.
    val panelWidth = minOf(viewportWidth * HomeTomeLibraryPanelViewportFraction, HomeTomeLibraryPanelMaxWidthDp.dp)
    val chromeScale = homeTomeLibraryChromeScale(density.fontScale)
    MimirLiquidGlassPanel(
        modifier = Modifier
            .width(panelWidth)
            .height(viewportHeight * HomeTomeLibraryPanelViewportFraction)
            .testTag(HomeTomeLibraryPanelTestTag),
        contentPadding = MimirSpacing.md,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height((HomeTomeLibraryHeaderHeightDp * chromeScale).dp)
                .testTag(HomeTomeLibraryHeaderTestTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The rune mark and the "· TOME LIBRARY" eyebrow are the strip's label, not text the player reads for content:
            // at a raised font scale they keep their default size, as the onboarding header strip's wordmark does, so the
            // status beside them (which does scale, and is the widest thing in the row) cannot squeeze the eyebrow into an
            // ellipsis on a narrow panel such as the upright Thor at 2.0. At 1.0 this is the density it always had.
            val labelDensity = remember(density.density, density.fontScale) {
                if (density.fontScale > 1f) Density(density = density.density, fontScale = 1f) else density
            }
            CompositionLocalProvider(LocalDensity provides labelDensity) {
                Text(
                    text = "ᛏᛟᛗᛖ",
                    style = MimirTypeScale.RunicEyebrowCompact.copy(letterSpacing = 1.4.sp),
                    color = palette.runeMark,
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.app_tome_library_eyebrow),
                    style = MimirTypeScale.RunicEyebrow,
                    color = hero.onCover.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = MimirSpacing.sm).weight(1f),
                )
            }
            Box(
                modifier = Modifier.width(IntrinsicSize.Max),
                contentAlignment = Alignment.CenterEnd,
            ) {
                // Smooth hint-status transitions keep the sibling reservation stable. The earlier change pinned
                // SizeTransform to snap() (killed the ramp, but the two end sizes still legitimately
                // differed, so the change still read as a jump). the earlier change added this outer
                // Modifier.width(IntrinsicSize.Max) Box with an invisible sizer -- built from every
                // literal headerStatus can produce (HomeTomeLibraryHeaderStatusCandidates) -- so this
                // Box's OWN width, reported to the "· TOME LIBRARY" weight(1f) sibling, is CONSTANT
                // across every value. That part is KEPT and still does real work: it is what makes the
                // sibling's available width immune to whatever the AnimatedContent below does with its
                // OWN width, snap or no snap, so the sibling can never be yanked, structurally.
                //
                // Pinning both the outer reservation and inner transform to snap() left no smooth width
                // motion. The inner transform uses a tween on HomeTomeStatusLegibilityMs, long enough to
                // register visually, rather
                // than the pin marker's own HomeTomeMutationAckMs, so the label's own container
                // smoothly grows/shrinks
                // from this Box's fixed CenterEnd-anchored right edge -- a real, visible, eased size
                // change -- while the outer reservation above keeps that motion fully contained: the
                // sibling's available width never changes because the OUTER Box's own intrinsic width
                // never changes, only the AnimatedContent glides within it. Chosen deliberately over the
                // other option (animate the outer width itself and let the sibling glide too): the pin
                // marker reference standard never touches layout at all, so containing the motion here
                // reads as one gesture with it, and it is the only option that cannot regress the
                // "sibling never reflows" fix the earlier change already shipped.
                Column(modifier = Modifier.alpha(0f).clearAndSetSemantics { }) {
                    HomeTomeLibraryHeaderStatusCandidates.forEach { candidate ->
                        Text(text = stringResource(candidate), style = MimirTypeScale.MicroStatus, maxLines = 1)
                    }
                }
                AnimatedContent(
                    targetState = headerStatus,
                    transitionSpec = {
                        val duration = if (reducedMotion) 0 else HomeTomeStatusLegibilityMs
                        // FOURTH pass, 2026-08-14. The clock is HomeTomeStatusLegibilityMs -- read its
                        // KDoc for why this label finally stopped sharing the pin marker's acknowledgement
                        // clock. Everything else here is deliberately untouched: the DIRECTION (a real
                        // eased fade plus a real eased size change) is the third pass's, whose shape stays unchanged while the speed is adjusted. All THREE specs below stay on one
                        // clock, so the crossfade and the container's width glide remain a single gesture
                        // rather than two overlapping ones. clip = false is unchanged from the earlier change: it lets
                        // the outgoing/incoming text render at natural size through the resize instead of
                        // being clipped to an intermediate box. reducedMotion still collapses to 0.
                        (fadeIn(tween(duration, easing = MimirMotion.Easing)) togetherWith
                            fadeOut(tween(duration, easing = MimirMotion.Easing)))
                            .using(
                                SizeTransform(clip = false) { _, _ ->
                                    tween(duration, easing = MimirMotion.Easing)
                                },
                            )
                    },
                    label = "tome-library-header-status",
                ) { status ->
                    Text(
                        text = status,
                        style = MimirTypeScale.MicroStatus,
                        color = when {
                            state.pinFeedback?.saved == false -> palette.warning
                            state.pinFeedback != null -> palette.accentBright
                            else -> hero.onCover.copy(alpha = 0.62f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val bodyHeight = maxHeight
            val homeFaceHeight = homeTomeLibraryHomeFaceHeightDp(bodyHeight.value).dp
            val homeFaceWidth = (homeFaceHeight.value * 0.75f).dp
            val showsHome = heroOffset > 0
            val homeEntry = state.entries.firstOrNull()?.takeIf { it.isHomeShelf }
            val compositionWidth = homeTomeLibraryCenteredCompositionWidthDp(
                bodyWidthDp = maxWidth.value,
                homeFaceWidthDp = homeFaceWidth.value,
                showsHome = showsHome,
            ).dp
            val fixedHomeBandWidth = if (showsHome) {
                homeFaceWidth + HomeTomeLibrarySeparatorLane
            } else {
                0.dp
            }
            val gridBandWidth = (compositionWidth - fixedHomeBandWidth).coerceAtLeast(0.dp)
            Row(
                modifier = Modifier
                    .width(compositionWidth)
                    .fillMaxHeight()
                    .testTag(HomeTomeLibraryCompositionTestTag)
                    .align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showsHome && homeEntry != null) {
                    Box(
                        modifier = Modifier
                            .width(homeFaceWidth)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        HomeTomeLibraryCell(
                            entry = homeEntry,
                            flatIndex = 0,
                            focused = renderFocusedIndex == 0,
                            interactive = interactive,
                            pinMutationPending = state.pinMutation != null,
                            authority = authority,
                            reducedMotion = reducedMotion,
                            compact = false,
                            focusScale = 1f, // no grow on focus; the ring marks it
                            showPreviewPlate = true,
                            onPreviewPresented = (homeEntry as? HomeTomeLibraryEntry.Tome)
                                ?.takeIf { state.settledFocusedIndex == 0 }
                                ?.let { tomeEntry ->
                                    { preview -> onPreviewPresented(tomeEntry.model.tomeId, preview) }
                                },
                            faceModifier = Modifier
                                .height(homeFaceHeight)
                                .aspectRatio(3f / 4f),
                            onActivate = onActivate,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(HomeTomeLibrarySeparatorLane)
                            .fillMaxHeight()
                            .clearAndSetSemantics { },
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawMimirVerticalBeam(
                                palette = palette,
                                centerX = size.width / 2f,
                                top = 0f,
                                height = size.height,
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .width(gridBandWidth)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .widthIn(max = HomeTomeLibraryGridContentWidthDp.dp)
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val compactHeight = homeTomeLibraryResolvedCompactFaceHeightDp(
                            bodyHeightDp = bodyHeight.value,
                            gridWidthDp = maxWidth.value,
                        ).dp
                        val viewportHeight = minOf(
                            maxHeight,
                            homeTomeLibraryGridViewportHeightDp(compactHeight.value).dp,
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(HomeTomeLibraryGridColumns),
                            state = gridState,
                            userScrollEnabled = interactive && state.pinMutation == null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(viewportHeight),
                            contentPadding = PaddingValues(HomeTomeLibraryGridOuterPaddingDp.dp),
                            horizontalArrangement = Arrangement.spacedBy(HomeTomeLibraryGridColumnGapDp.dp),
                            verticalArrangement = Arrangement.spacedBy(HomeTomeLibraryGridRowGapDp.dp),
                        ) {
                            itemsIndexed(
                                items = collectionEntries,
                                key = { _, entry -> entry.stableKey },
                            ) { gridIndex, entry ->
                                val flatIndex = gridIndex + heroOffset
                                val cellFocused = renderFocusedIndex == flatIndex
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .zIndex(if (cellFocused) 1f else 0f),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    HomeTomeLibraryCell(
                                        entry = entry,
                                        flatIndex = flatIndex,
                                        focused = cellFocused,
                                        interactive = interactive,
                                        pinMutationPending = state.pinMutation != null,
                                        authority = authority,
                                        reducedMotion = reducedMotion,
                                        compact = true,
                                        focusScale = 1f, // no grow on focus; the ring marks it
                                        showPreviewPlate = true,
                                        onPreviewPresented = (entry as? HomeTomeLibraryEntry.Tome)
                                            ?.takeIf { state.settledFocusedIndex == flatIndex }
                                            ?.let { tomeEntry ->
                                                { preview ->
                                                    onPreviewPresented(tomeEntry.model.tomeId, preview)
                                                }
                                            },
                                        faceModifier = Modifier
                                            .height(compactHeight)
                                            .aspectRatio(3f / 4f),
                                        onActivate = onActivate,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        HomeTomeLibraryFooter(
            position = position,
            focusedEntry = focusedEntry,
        )
    }
}

@Composable
private fun HomeTomeLibraryCell(
    entry: HomeTomeLibraryEntry,
    flatIndex: Int,
    focused: Boolean,
    interactive: Boolean,
    pinMutationPending: Boolean,
    authority: FocusAuthority,
    reducedMotion: Boolean,
    compact: Boolean,
    focusScale: Float,
    showPreviewPlate: Boolean,
    onPreviewPresented: ((HomeTomePreview?) -> Unit)? = null,
    faceModifier: Modifier,
    onActivate: (Int) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val click = if (interactive && !pinMutationPending) {
        Modifier.clickable(
            interactionSource = remember(entry.stableKey) { MutableInteractionSource() },
            indication = null,
        ) {
            authority.requestFocus(homeTomeLibraryFocusKey(entry))
            onActivate(flatIndex)
        }
    } else Modifier
    val focus = if (interactive) {
        Modifier.mimirFocusable(
            authority = authority,
            key = homeTomeLibraryFocusKey(entry),
            scope = HomeTomeLibraryScope,
            autoBringIntoView = false,
        )
    } else Modifier
    Box(
        modifier = faceModifier
            .then(focus)
            .then(click),
        contentAlignment = Alignment.Center,
    ) {
        when (entry) {
            is HomeTomeLibraryEntry.Tome -> {
                HomeTomeFace(
                    tome = entry.model,
                    focused = focused,
                    focusScale = focusScale,
                    showFocusRing = true,
                    showPreviewPlate = showPreviewPlate,
                    onPreviewPresented = onPreviewPresented,
                    compact = compact,
                    reducedMotion = reducedMotion,
                    // One ᛗ per book : a Tome with no art and no platform wears its own embossed Mannaz, so it gives way while the pin shows, on the pin's own clock.
                    showMannaz = !entry.pinned,
                    mannazFadeMs = HomeTomeMutationAckMs,
                    modifier = Modifier.fillMaxSize(),
                )
                // Use the shared gamelist pin component to address the reported low visibility.
                //
                // This WAS a 7dp (5dp compact) rotated square filled with `palette.accentBright` and no
                // ground of its own -- roughly 49dp² of bare diamond, floating on whatever cover art
                // happened to be beneath it. The gamelist's pin is legible for a structural reason, not a
                // size one: it CARRIES ITS OWN GROUND. A stamped leather plate supplies the contrast, so
                // legibility belongs to the pin rather than to the tile under it, and the rune is inked in
                // the product-wide brand gold instead of a per-theme accent that some palettes wash out.
                //
                // Reused, not re-drawn: [MimirMannazPin] is that exact component, hoisted to core-ui so
                // one implementation serves both surfaces. This codebase has already been bitten by three
                // drifting copies of one helper, and a fourth Mannaz is how it happens again.
                val pinPad = if (compact) 4.dp else 10.dp
                MimirMannazPin(
                    present = entry.pinned,
                    modifier = Modifier.align(Alignment.TopEnd).padding(pinPad),
                    height = if (compact) 16.dp else MimirMannazPinHeightDp.dp,
                    // The reference standard for this transition is the pin marker's own
                    // 110ms clock. Passed EXPLICITLY so it cannot
                    // silently fall back to the gamelist's default and change the established acknowledgement timing.
                    durationMs = HomeTomeMutationAckMs,
                )
            }
            HomeTomeLibraryEntry.CreateCustom -> {
                val shape = RoundedCornerShape(MimirSpacing.radiusCard)
                Box(
                    modifier = Modifier.fillMaxSize()
                        // The one ring on the Create cell (ring migration, 2026-09-28): a row ring with the glass
                        // hairline at rest, where mimirGlassRow's border ring was.
                        .mimirSelectionRing(
                            focused = focused,
                            identity = entry.stableKey,
                            shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                            restingOutline = true,
                            outlineColor = palette.glassRestingOutline(),
                        )
                        .clip(shape)
                        .padding(if (compact) MimirSpacing.xs else MimirSpacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    MimirChromeText(
                        text = stringResource(R.string.app_tome_library_create_action),
                        modifier = Modifier.fillMaxWidth(),
                        style = if (compact) MimirTypeScale.MicroStatus else MimirTypeScale.MenuAction,
                        color = hero.onCover,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
internal fun HomeTomeLibraryFooter(
    position: HomeTomeLibraryPosition,
    focusedEntry: HomeTomeLibraryEntry?,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    // A brief intermediate footer label was reported on device. This was the text -- the footer hint used to swap to
    // "ᛗ  UPDATING TOME" for the duration of the durable pin write, with NO animation at all, giving
    // three hard cuts in a split second: hints -> UPDATING -> hints. Four earlier passes tuned the header
    // status instead and never touched this line.
    //
    // Removed rather than slowed or threshold-gated: a spinner for a write that usually completes within
    // a frame or two is noise, and a duration threshold would be a new motion literal, which requires a separate design decision. The controls now simply stay legible; the PIN/UNPIN word updates once, when
    // the write actually lands, instead of the whole line blinking through a third state.
    // One whole translated line per case, never pieces joined here: the Home shelf's Tome, then a custom (Y edits) or platform (Y creates) Tome, pinned or not.
    val hint = stringResource(
        when (focusedEntry) {
            is HomeTomeLibraryEntry.Tome -> when {
                focusedEntry.isHomeShelf -> R.string.app_tome_library_hint_home
                focusedEntry.model.tomeId is TomeId.Custom ->
                    if (focusedEntry.pinned) R.string.app_tome_library_hint_unpin_edit else R.string.app_tome_library_hint_pin_edit
                else ->
                    if (focusedEntry.pinned) R.string.app_tome_library_hint_unpin_create else R.string.app_tome_library_hint_pin_create
            }
            else -> R.string.app_tome_library_hint_create
        },
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height((HomeTomeLibraryFooterHeightDp * homeTomeLibraryChromeScale(LocalDensity.current.fontScale)).dp)
            .testTag(HomeTomeLibraryFooterTestTag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            Text(
                text = "˄",
                style = MimirTypeScale.RuneAction.copy(fontSize = 14.sp),
                color = if (position.canMoveUp) palette.accentBright else hero.onCover.copy(alpha = 0.20f),
            )
            Text(
                text = position.label,
                style = MimirTypeScale.MicroStatus,
                color = palette.runeMark,
                maxLines = 1,
            )
            Text(
                text = "˅",
                style = MimirTypeScale.RuneAction.copy(fontSize = 14.sp),
                color = if (position.canMoveDown) palette.accentBright else hero.onCover.copy(alpha = 0.20f),
            )
        }
        // UI-03: style and colour no longer flip on `pinPending` either. They existed to dress the
        // transient "ᛗ  UPDATING TOME" string, and with that string gone they would only hard-cut the
        // SAME text between two treatments mid-write -- the identical abruptness in another form. One
        // stable line, one legible treatment.
        // Hints must not cut when focus moves. Keyed on the line itself, centred.
        // The words both lines share must not cut either. The line moves between "A OPEN · B BACK" and "A OPEN · X UNPIN ·
        // Y EDIT · B BACK", and a cross-fade of the whole line printed "A OPEN · A OPEN · X UNPI" and "X UNPIN" over "Y EDIT" (the shared words stand at
        // different x in the two). So it swaps a word at a time (MimirSegmentedHintSwap): only the words that come and go fade, the shared ones glide.
        val hintColor = hero.onCover.copy(alpha = 0.60f)
        MimirSegmentedHintSwap(
            // The controller map's own words, as MimirControlHintText projects them, so a segment the player has unbound leaves no empty piece behind.
            line = LocalMimirControllerHintLabels.current.rewrite(hint),
            modifier = Modifier.testTag(HomeTomeLibraryFooterHintTestTag),
            separator = {
                MimirChromeText(text = MimirHintLineSeparator, style = MimirTypeScale.ControlHintCompact, color = hintColor, maxLines = 1)
            },
            segment = { words ->
                MimirChromeText(
                    text = words,
                    style = MimirTypeScale.ControlHintCompact,
                    color = hintColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
    }
}

internal data class HomeTomeEditorCopy(val status: String, val legend: String?)

internal const val HomeTomeEditorStatusReserveDp = 72f

internal fun homeTomeEditorCollapsesCopy(maxHeightDp: Float): Boolean =
    maxHeightDp < MimirKeyboardHeight.value + HomeTomeEditorStatusReserveDp

/** Short-display editor: the keyboard is the surface, so all five 48dp rows remain visible on RP5. */
@Composable
private fun HomeTomeNameEditor(
    state: HomeTomeLibraryState,
    authority: FocusAuthority,
    active: Boolean,
    reducedMotion: Boolean,
    onDraftChange: (String) -> Unit,
    onDraftDone: () -> Unit,
    onDeleteAction: () -> Unit,
    onKeyboardHostChanged: (MimirKeyboardHost?) -> Unit,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val editing = state.editingTomeId != null
    val copy = homeTomeEditorCopy(
        res = appResources(),
        draftError = state.draftError,
        deletePending = state.deletePending,
        confirmDelete = state.confirmDelete,
        editing = editing,
    )
    val scale = remember { Animatable(if (reducedMotion) 1f else 0.98f) }
    LaunchedEffect(active, reducedMotion) {
        if (reducedMotion) scale.snapTo(1f)
        else scale.animateTo(
            if (active) 1f else 0.98f,
            tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value }) {
        val collapseCopy = homeTomeEditorCollapsesCopy(maxHeight.value)
        val statusAction = if (active && editing) {
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDeleteAction,
            )
        } else {
            Modifier
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = MimirSpacing.xs)
                .mimirGlassRow(false, RoundedCornerShape(MimirSpacing.radiusPill))
                .then(statusAction)
                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        ) {
            Text(
                text = if (collapseCopy && copy.legend != null) {
                    stringResource(R.string.app_tome_editor_status_with_legend, copy.status, copy.legend)
                } else {
                    copy.status
                },
                style = if (collapseCopy && copy.legend != null) {
                    MimirTypeScale.ControlHintCompact
                } else {
                    MimirTypeScale.Support
                },
                color = if (state.draftError != null || state.confirmDelete) {
                    palette.accentBright
                } else {
                    hero.onCover.copy(alpha = 0.72f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            copy.legend?.takeUnless { collapseCopy }?.let { legend ->
                MimirControlHintText(
                    text = legend,
                    style = MimirTypeScale.ControlHintCompact,
                    color = hero.onCover.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val keyboardHost = rememberMimirKeyboardHost(authority, active, owner = MimirOverlayKind.HOME_TOME_LIBRARY)
        DisposableEffect(active) {
            onDispose { onKeyboardHostChanged(null) }
        }
        SideEffect { onKeyboardHostChanged(keyboardHost.takeIf { active }) }
        MimirKeyboard(
            value = state.draft.orEmpty(),
            onValueChange = { if (active) onDraftChange(it.take(HomeTomeLabelMax)) },
            onDone = { if (active) onDraftDone() },
            controller = keyboardHost?.controller,
            keyDecorator = keyboardHost?.keyDecorator,
            targetLabel = stringResource(if (editing) R.string.app_tome_editor_target_edit else R.string.app_tome_editor_target_new),
            // Product review 2026-09-28 (F2): the editor's own legend above already says what B does here, so the
            // keyboard does not print a second, contradicting one.
            showControllerLegend = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(0.94f),
        )
        if (!active) {
            // AnimatedContent retains the outgoing editor for its fade. Keep those pixels, but make
            // them a single inert touch-swallowing layer so the old keyboard cannot change even its
            // local Shift/Symbol state while the incoming browser already owns interaction.
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .clearAndSetSemantics { },
            )
        }
    }
}

private fun TomeId.stableBrowserKey(): String = when (this) {
    is TomeId.Platform -> "platform/${platformId.value}"
    is TomeId.Custom -> "custom/$id"
    TomeId.HomeOverview -> "home/overview"
}
