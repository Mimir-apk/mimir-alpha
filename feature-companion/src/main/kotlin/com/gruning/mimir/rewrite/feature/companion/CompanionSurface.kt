package com.gruning.mimir.rewrite.feature.companion

import com.gruning.mimir.rewrite.core.model.CompanionJobProgress
import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.model.CompanionMode
import com.gruning.mimir.rewrite.core.model.CompanionPayload
import com.gruning.mimir.rewrite.core.model.CompanionShelfRow
import com.gruning.mimir.rewrite.core.ui.MimirMannazBadge
import com.gruning.mimir.rewrite.core.ui.MimirPageRunes
import com.gruning.mimir.rewrite.core.ui.drawMimirCornerBrackets
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiFloatTransitionReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiArtReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiTransitionProbe
import com.gruning.mimir.rewrite.core.ui.BottomTrackpadPlate
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.LocalMimirMotionLive
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.bottomUiTransition
import com.gruning.mimir.rewrite.core.ui.MimirArtFailureLogTag
import com.gruning.mimir.rewrite.core.ui.mimirArtSourceWired
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiControl
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassScrim
import com.gruning.mimir.rewrite.core.ui.mimirDecodeBoundedCoverBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.gruning.mimir.rewrite.core.ui.MimirBoxArt
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeader
import com.gruning.mimir.rewrite.core.ui.MediaArtFrame
import com.gruning.mimir.rewrite.core.ui.mimirFocusRing
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.mimirAppIconShape
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirNorseBookCover
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirReportPress
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoff
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoffProbe
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirGestureWatch
import com.gruning.mimir.rewrite.core.ui.MimirPagerFlingVelocity
import com.gruning.mimir.rewrite.core.ui.mimirGestureWatch
import com.gruning.mimir.rewrite.core.ui.mimirPagerSettlePage
import com.gruning.mimir.rewrite.core.ui.mimirPagerSettleSpec
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.mimirOverlayArriveSpec
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFade
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import com.gruning.mimir.rewrite.core.ui.mimirSwapOverlayTransform
import com.gruning.mimir.rewrite.core.ui.mimirPageTouchShield
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirGlassPanelEntry
import com.gruning.mimir.rewrite.core.ui.mimirGlassRow
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassHold
import com.gruning.mimir.rewrite.core.ui.rememberMimirHeldGlassProgress
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxHeight
import com.gruning.mimir.rewrite.core.model.CompanionHomeLane
import com.gruning.mimir.rewrite.core.model.CompanionHomeTile
import com.gruning.mimir.rewrite.core.model.CompanionHomeShelfDetail
import com.gruning.mimir.rewrite.core.model.CompanionHomeTileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirThemeSceneBackdrop
import com.gruning.mimir.rewrite.core.ui.PlatformConsoleArt
import com.gruning.mimir.rewrite.core.ui.platformConsoleIconBrush
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.shape.CircleShape
import com.gruning.mimir.rewrite.core.ui.mimirNorseBookCoverCorner
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidatePlacement
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlin.math.abs
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.lerp
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.LocalMimirKeyboardHapticFeedback
import com.gruning.mimir.rewrite.core.ui.BeamSeparator
import java.util.Locale
import com.gruning.mimir.rewrite.core.ui.MimirRingGleam
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirFocusRingSettleMs
import com.gruning.mimir.rewrite.core.ui.MimirFocusAlwaysArmed
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay

/**
 * Secondary-display companion surface.
 *
 * The primary sends compact immutable read models. Launch and library mutation stay on the primary display;
 * this surface never exposes a game-detail command path.
 */
internal const val CompanionQuickLaunchHeader = "ᛗ · QUICK LAUNCH"
internal const val CompanionQuickAssignHeader = "ᛗ · PIN AN APP"

/** The picker header: what each side of its eyebrow keeps clear for the Close pill (the pill is at least 48 dp, and "Close" at the largest font scale is wider). */
internal const val CompanionPickerClosePillReserveDp = 88f
internal const val CompanionPickerCloseTag = "companion-picker-close"
internal const val CompanionPickerClosePlateTag = "companion-picker-close-plate"

/** Friendly producer-owned labels only: resolver/storage ids are never release-visible fallback copy. */
internal fun companionPlatformDisplayLabel(platformLabel: String?, shelfModeLabel: String?): String? =
    platformLabel?.takeIf { it.isNotBlank() }
        ?: shelfModeLabel?.takeIf { it.isNotBlank() }

/**
 * Async presentation state whose backing State itself is replaced with the durable identity. A plain
 * `produceState(key...)` restarts only its producer and can expose the previous value for one commit; the
 * outer composition key guarantees the destination's first frame is null/fallback before decoding starts.
 */
@Composable
@SuppressLint("ProduceStateDoesNotAssignValue") // False positive: the producer assigns below; key() replaces the State at an identity boundary.
internal fun <T> rememberIdentityProducedValue(
    vararg identityKeys: Any?,
    producer: suspend () -> T?,
): T? {
    // A single structural token avoids Compose compiler edge cases around a spread passed to key().
    val identityEpoch = identityKeys.toList()
    return key(identityEpoch) {
        produceState<T?>(null, identityEpoch) {
            value = producer()
        }.value
    }
}

/**
 * The lattice has a fixed cell size (CR-15).
 * A page is nine equal cells, always — never as many rows as there happen to be tiles. Spatial memory
 * needs the grid to be a place, and a place does not resize itself when you take something out of it.
 */
private const val GridColumns = 3
private const val GridRows = 3
private const val SlotsPerPage = GridColumns * GridRows
internal const val CompanionDragSettleMillis = MimirMotion.FocusMs

/**
 * The lattice's pages for [tileCount] tiles: the tiles AND the "+ Tome" cell that always follows them. The "+" sits in the cell straight after the last tile, so at 9, 18, 27... tiles that cell opens a page of its own; counting the tiles
 * alone left the "+" on a page that did not exist. The "+" is hidden while a tile is lifted, but its cell stays: the pager does not change count under a drag.
 */
internal fun companionLatticePageCount(tileCount: Int): Int = (tileCount.coerceAtLeast(0) + SlotsPerPage) / SlotsPerPage

/** The page the "+ Tome" cell is on: the one holding the cell right after the last tile. */
internal fun companionCreateTomePage(tileCount: Int): Int = tileCount.coerceAtLeast(0) / SlotsPerPage

/** What a just-created Tome's pending rename plate does on this frame. */
internal enum class CompanionPendingRenameVerdict { OPEN, WAIT, DROP }

/**
 * The pending rename plate has three admission outcomes. It opens only for the Tome the player just made and only when the player has not moved on:
 *  - DROP when a Tome is open, or another plate is up, or the page is not [baselinePage] and the surface is not itself turning to the new Tome ([revealScrolling]).
 *    The reveal is identified by its own scroll, never by where a page ends up: a page the player turned to is theirs even if the Tome sits on it after a reorder.
 *  - WAIT until the Tome is in the payload ([landed]) and the drag machine is idle ([dragIdle]): not over a press that could still lift, a lift, a pager drag,
 *    a page turn or a rollback.
 *  - OPEN otherwise.
 */
internal fun companionPendingRenameVerdict(
    tomeOpen: Boolean,
    plateUp: Boolean,
    page: Int,
    baselinePage: Int,
    revealScrolling: Boolean,
    landed: Boolean,
    dragIdle: Boolean,
): CompanionPendingRenameVerdict = when {
    tomeOpen || plateUp -> CompanionPendingRenameVerdict.DROP
    page != baselinePage && !revealScrolling -> CompanionPendingRenameVerdict.DROP
    !landed || !dragIdle -> CompanionPendingRenameVerdict.WAIT
    else -> CompanionPendingRenameVerdict.OPEN
}

/**
 * The pending rename plate's backstop: the drag machine is not idle and no finger is down, so nothing the player is doing will bring it back to idle. A machine
 * left Pressed with no finger down is a defect this guards against ever stranding the plate; the plate gives up (no plate) after [CompanionPendingRenameStallMs].
 */
internal fun companionPendingRenameStalled(dragIdle: Boolean, fingerDown: Boolean): Boolean = !dragIdle && !fingerDown

/**
 * The pending rename plate's backstop, one long-lived loop for the whole pending period (it ends when the plate opens or drops, or when its caller's effect leaves
 * composition). Each window is [windowMs] long and starts at the later of the loop's last restart and the last pointer transition ([lastTransitionMs]), so a finger
 * that comes and goes restarts it. When a window runs out the plate gives up ([onGiveUp]) only if the machine is [stalled] right then (live: not idle, no finger down);
 * otherwise the next window starts from now. Nothing here is keyed on state that can stay unchanged across a press, so a press spanning an expiry cannot disarm it.
 */
internal suspend fun companionRunPendingRenameBackstop(
    pending: () -> Boolean,
    stalled: () -> Boolean,
    nowMs: () -> Long,
    lastTransitionMs: () -> Long,
    windowMs: Long,
    onGiveUp: () -> Unit,
) {
    var windowStart = nowMs()
    while (pending()) {
        val due = maxOf(windowStart, lastTransitionMs()) + windowMs
        val remaining = due - nowMs()
        if (remaining > 0L) {
            delay(remaining)
            continue
        }
        if (stalled()) {
            onGiveUp()
            return
        }
        windowStart = nowMs()
    }
}

/** How long a stalled machine (see [companionPendingRenameStalled]) may hold a pending plate: the Bottom's three-beat clock, `MimirMotion.ShineMs`. */
internal const val CompanionPendingRenameStallMs = MimirMotion.ShineMs.toLong()

/** Two taps on the "+" closer than this are one: the first already made the Tome and the view is on its way to it. */
internal const val CompanionCreateTomeDebounceMs = 400L

/**
 * The tome tile's face is a SQUARE, like every other tile on the lattice.
 *
 * The platform-tile squaring change reached the console arm but not the Tome arm. It went on drawing at 3:4, height-matched, so the one
 * non-console face on the shelf was the one shape that disagreed with the grid.
 *
 * The 3:4 book proportion is not gone from the product; it is what a book IS, and the OPEN page still
 * reads at it ([solveCompanionOpenBookGeometry]). It is gone from the TILE, which is a cell in a grid of
 * cells and has to agree with its neighbours.
 */
internal const val CompanionTomeTileAspectRatio = 1f

/** Draw-only book roles resolved from the authored palette; generic app surfaces never enter the cover. */
internal data class CompanionBookTreatment(
    val coverTop: Color,
    val coverMiddle: Color,
    val coverBottom: Color,
    val onCover: Color,
    val hardware: Color,
    val counterAccent: Color,
    val runeInk: Color,
)

internal fun companionBookTreatment(palette: MimirThemePalette): CompanionBookTreatment {
    val hero = palette.materials.hero
    return CompanionBookTreatment(
        coverTop = hero.coverTop,
        coverMiddle = hero.coverMiddle,
        coverBottom = hero.coverBottom,
        onCover = hero.onCover,
        hardware = hero.hardware,
        counterAccent = palette.materials.counterAccent,
        runeInk = palette.runeMark,
    )
}

internal data class CompanionOpenBookGeometry(
    val panelWidthDp: Float,
    val panelHeightDp: Float,
    val horizontalPaddingDp: Float,
    val verticalPaddingDp: Float,
    val spacingDp: Float,
    val childViewportMaxHeightDp: Float,
    val reservedChromeHeightDp: Float,
)

/**
 * Bounded open-tome geometry. Text chrome is reserved before the scrollable leaves, including at the
 * 538x444dp / 1.3 font-scale certification envelope.
 */
internal fun solveCompanionOpenBookGeometry(
    viewportWidthDp: Float,
    viewportHeightDp: Float,
    fontScale: Float,
    renaming: Boolean,
): CompanionOpenBookGeometry {
    val safeWidth = viewportWidthDp.coerceAtLeast(1f)
    val safeHeight = viewportHeightDp.coerceAtLeast(1f)
    val safeScale = fontScale.coerceAtLeast(1f)
    val compact = safeHeight <= 444f || safeScale >= 1.3f
    val widthFraction = if (renaming) 0.98f else 0.76f
    val heightFraction = if (renaming) 0.98f else 0.94f
    val horizontalPadding = if (renaming) 10f else if (compact) 16f else 22f
    val verticalPadding = if (renaming) 4f else if (compact) 10f else 18f
    val spacing = if (renaming) 4f else if (compact) 8f else 14f
    val panelHeight = safeHeight * heightFraction
    val titleLine = 20f * safeScale
    val actionLine = 18f * safeScale
    val supportLines = 30f * safeScale
    val reservedChrome = verticalPadding * 2f + titleLine + actionLine + supportLines + spacing * 4f
    val childViewport = if (renaming) {
        0f
    } else {
        (panelHeight - reservedChrome).coerceIn(96f, 240f)
    }
    return CompanionOpenBookGeometry(
        panelWidthDp = safeWidth * widthFraction,
        panelHeightDp = panelHeight,
        horizontalPaddingDp = horizontalPadding,
        verticalPaddingDp = verticalPadding,
        spacingDp = spacing,
        childViewportMaxHeightDp = childViewport,
        reservedChromeHeightDp = reservedChrome,
    )
}

/**
 * The sibling clearance the cursor ring costs: [MimirSpacing.sm], 8 dp, which is what Main's one ring needs between an
 * element and the next thing (`MimirFocusTileRingReach`'s KDoc: the inner and middle strokes reach 8 dp, the soft outer
 * bloom about 6 dp more). The face is the ring square less this, so the face stands 4 dp inside the square on every
 * side, and the ring's strong strokes clear the name band under it (4 dp + the 4 dp gap) and the row above (the 4 dp
 * clear + 4 dp). The 8% outer bloom may cross about 6 dp into a label band, as Main's dock accepts the same.
 */
internal val CompanionCursorRingClearanceDp: Float = MimirSpacing.sm.value

/** Headroom the Tome's scrolling leaves above its first row so the ring is not cut there: its reach (14 dp) less the 4 dp the face stands inside its cell. */
internal val CompanionTomeRingHeadroomDp: Float = MimirFocusTileRingReach.value - CompanionCursorRingClearanceDp / 2f

/** The lattice name's line height: one named number, so a type-step change costs a single edit (the band follows). */
internal const val CompanionLatticeLabelLineHeightSp = 17f

/** Policy R1: a platform's full name gets two lines. */
internal const val CompanionLatticeLabelLines = 2

/** The shelf's own construction (`CompanionGameTile`): the lines plus 2 dp so the second line's descenders are not cut. */
private const val CompanionLatticeLabelBandSlackDp = 2f

/**
 * One lattice cell, solved: the cursor ring (a square, top-aligned and centred), the face inside it, and the label
 * band under it. Every number is dp.
 */
internal data class CompanionLatticeCellGeometry(
    val ringEdgeDp: Float,
    val faceEdgeDp: Float,
    /** Where the label band starts, from the cell's top: the ring's bottom edge plus the gap. */
    val labelTopDp: Float,
    val bandDp: Float,
    /**
     * How many name lines the band holds: [CompanionLatticeLabelLines] whenever the cell can pay for the whole band,
     * fewer in a cell too short for it (the band shrinks to what fits), and 0 where not even one line fits, in which
     * case [bandDp] is 0 and the name is hidden. The renderer obeys it.
     */
    val labelLines: Int,
    /** Kept free on each side of the label, so it stays off the rail's corner brackets. */
    val labelInsetDp: Float,
    /** Kept free under the band, so the last row's name clears the rail's brackets. */
    val clearDp: Float,
)

/**
 * The cell geometry for a lattice cell of [cellWidthDp] x [cellHeightDp] at [fontScale] (the 3 x 3 page cell and the
 * 104 x 122 in-book cell take the same solve, so a tile does not change shape when it moves between them or lifts).
 *
 * The band is two label lines tall at the font scale, plus 2 dp. The ring takes whatever is left of the cell's height
 * once the band, the gap above it and the clear below it are paid for, up to the cell's width, so the ring can never
 * cross the label and the label can never run into the bottom of the cell. The face is the ring less the one ring's sibling
 * clearance ([CompanionCursorRingClearanceDp], 8 dp, so 4 dp on every side): the cursor ring is drawn around the face and
 * reaches past this square, into that clearance and a little beyond. Total by construction: a zero or negative cell, or a
 * NaN, solves to an empty face rather than a crash.
 * Like [solveCompanionOpenBookGeometry], a font scale under 1 solves as 1.
 *
 * A cell too short to pay for the gap, the band and the clear has no ring (and so no face); the label then takes what
 * the cell has left under the gap and above the clear: the band shrinks to one line, or to none ([labelLines] 0, band
 * 0) when not even one line fits. The label therefore never leaves the cell.
 */
internal fun solveCompanionLatticeCell(
    cellWidthDp: Float,
    cellHeightDp: Float,
    fontScale: Float,
): CompanionLatticeCellGeometry {
    fun Float.finiteFloor(): Float = if (isFinite()) coerceAtLeast(0f) else 0f
    val width = cellWidthDp.finiteFloor()
    val height = cellHeightDp.finiteFloor()
    val scale = if (fontScale.isFinite()) fontScale.coerceAtLeast(1f) else 1f
    val gap = MimirSpacing.xs.value
    val clear = MimirSpacing.xs.value
    val inset = MimirSpacing.xs.value
    val lineHeight = CompanionLatticeLabelLineHeightSp * scale
    val fullBand = CompanionLatticeLabelLines * lineHeight + CompanionLatticeLabelBandSlackDp
    // What the ring and the band share, and what is left for the ring once the whole band is paid for.
    val room = height - gap - clear
    val spare = room - fullBand
    val ring = minOf(width, spare).coerceAtLeast(0f)
    val face = (ring - CompanionCursorRingClearanceDp).coerceAtLeast(0f)
    val lines = if (spare >= 0f) {
        CompanionLatticeLabelLines
    } else {
        // No ring: the band shrinks to the whole lines the room under the gap holds, or to nothing.
        ((room.coerceAtLeast(0f) - CompanionLatticeLabelBandSlackDp) / lineHeight).toInt()
            .coerceIn(0, CompanionLatticeLabelLines)
    }
    val band = if (lines == 0) 0f else lines * lineHeight + CompanionLatticeLabelBandSlackDp
    return CompanionLatticeCellGeometry(
        ringEdgeDp = ring,
        faceEdgeDp = face,
        labelTopDp = minOf(ring + gap, height),
        bandDp = band,
        labelLines = lines,
        labelInsetDp = inset,
        clearDp = clear,
    )
}

/**
 * Where the visible face of a tile solved by [solveCompanionLatticeCell] sits inside its [slot] (px; [density] is px
 * per dp): the ring is top-aligned and horizontally centred, the face is the ring less the outset on every side.
 * The drag machine's merge hotspot is this rect, so it follows the face instead of the slot's centre.
 */
internal fun CompanionLatticeCellGeometry.faceRectIn(slot: DragRect, density: Float): DragRect {
    val ring = ringEdgeDp * density
    val face = faceEdgeDp * density
    val outset = (ringEdgeDp - faceEdgeDp) / 2f * density
    val left = slot.left + (slot.width - ring) / 2f + outset
    val top = slot.top + outset
    return DragRect(left, top, left + face, top + face)
}

/** The lattice's name: the Row step at a fixed line height, no font padding, so the band the solver reserves is the band drawn. */
private val CompanionLatticeLabelStyle = MimirTypeScale.Row.copy(
    lineHeight = CompanionLatticeLabelLineHeightSp.sp,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None,
    ),
)

/** The Create Tome cell's word in the label band: the lattice name's step, tracked like the eyebrow it replaces. */
internal val CompanionCreateTomeWordStyle = CompanionLatticeLabelStyle.copy(
    letterSpacing = MimirTypeScale.RunicEyebrow.letterSpacing,
)

/**
 * CR-16. Ground truth from the device, not the
 * datasheet: Thor's companion panel is `1080 x 1240` px, presented to us rotated as a `1240 x 1080`
 * logical surface at density 369 — **538 x 468 dp**. The old 26/22/72dp insets spent a seventh of that
 * height on a bottom band that reserved nothing (the M button is a free-floating overlay window that the
 * user drags where they like). These insets hand that height back to the tiles.
 */
private val CompanionShellInset = 18.dp
internal const val CompanionRailInsetDp = 12f
private val RailInset = CompanionRailInsetDp.dp
private val CellGap = 12.dp

internal const val CompanionDragRootTag = "companion-drag-root"
internal const val CompanionPageRunesTag = "companion-page-runes"

/**
 * The page diamonds on the lattice frame's bottom edge : the scrim they sit on is [CompanionPageRunesScrimHeightDp] tall, centred on the frame
 * line, so it covers the frame's outer line and its inner hairline (5 dp in); [CompanionPageRunesScrimPadDp] of it stands either side of the diamonds. Its trailing edge
 * is [CompanionPageRunesEndInsetDp] from the frame's right edge: the bottom-right bracket's arm ends 26 dp in and the corner is rounded 18 dp, and the dock's 96 dp
 * handle is centred, so the diamonds stay clear of both. The diamonds' own envelope is 9.9 dp (a 7 dp square turned 45 degrees), 8 dp apart.
 */
internal const val CompanionPageRunesScrimHeightDp = 12f
internal const val CompanionPageRunesScrimPadDp = 8f
internal const val CompanionPageRunesEndInsetDp = 36f

/** The lattice footer row, which is the Activity swipe hint only and exists only until the hint is retired: one row, 13 dp at least. */
internal const val CompanionFooterTag = "companion-footer"

/**
 * The most pages the diamonds are drawn for. Each is 15 dp of row (7 dp and the 8 dp gap), the row less 8, and the scrim adds [CompanionPageRunesScrimPadDp] either side: at 10 the
 * scrim is 158 dp, which stands 27 dp clear of the dock's 96 dp handle on the 537.7 dp Bottom (11 would stand 12 dp clear, 12 would overlap it). Past this the strip says it in
 * words ([companionPageCountWords]).
 */
internal const val CompanionPageRunesMaxPages = 10

/** The page diamonds' words past [CompanionPageRunesMaxPages]: the page counted from 1, a slash, and how many there are. */
internal fun companionPageCountWords(currentPage: Int, pageCount: Int): String {
    val count = pageCount.coerceAtLeast(1)
    return "${currentPage.coerceIn(0, count - 1) + 1} / $count"
}

/** The words that stand for the page diamonds past the cap: one line, the palette's muted ink, at the default font scale so the 12 dp scrim holds them. */
@Composable
private fun CompanionPageCountWords(currentPage: Int, pageCount: Int, palette: MimirThemePalette, modifier: Modifier) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
        Text(
            text = companionPageCountWords(currentPage, pageCount),
            style = MimirTypeScale.SupportCompact.copy(lineHeight = CompanionPageRunesScrimHeightDp.sp),
            color = palette.textMuted,
            maxLines = 1,
            modifier = modifier,
        )
    }
}

/** What the page diamonds say to a reader: the page (counted from 1) and how many there are. */
internal fun companionPageRunesDescription(currentPage: Int, pageCount: Int): String {
    val count = pageCount.coerceAtLeast(1)
    return "Page ${currentPage.coerceIn(0, count - 1) + 1} of $count"
}
internal fun companionSlotTag(slot: Int): String = "companion-slot-$slot"
internal fun companionLiftTag(tileId: String): String = "companion-lift-$tileId"
internal fun companionTileTag(tileId: String): String = "companion-tile-$tileId"

/** A tile's 104 x 122 dp cell inside an open Tome. */
internal fun companionTileCellTag(tileId: String): String = "companion-tile-cell-$tileId"
internal fun companionMergeArmedTag(tileId: String): String = "companion-merge-armed-$tileId"
internal fun companionMergeHoverTag(tileId: String): String = "companion-merge-hover-$tileId"

/** The Create Tome cell's parts: the dashed face (holds the "+"), the "+", and the word in the label band. */
internal const val CompanionCreateTomeFaceTag = "companion-create-tome-face"
internal const val CompanionCreateTomePlusTag = "companion-create-tome-plus"
internal const val CompanionCreateTomeWordTag = "companion-create-tome-word"

/** The rename plate (Mimir's keyboard over a glass scrim) that names a Tome: shown on a new Tome the moment its "+" is tapped, and from a Tome's own title. */
internal const val CompanionRenamePlateTag = "companion-rename-plate"

internal fun remainingCompanionLongPressDelayMillis(
    deadlineMillis: Long,
    currentUptimeMillis: Long,
): Long = (deadlineMillis - currentUptimeMillis).coerceAtLeast(0L)

internal fun remainingCompanionDwellDelayMillis(
    enteredAtMillis: Long,
    dwellMillis: Long,
    currentUptimeMillis: Long,
): Long {
    val boundedDwell = dwellMillis.coerceAtLeast(0L)
    val elapsed = (currentUptimeMillis - enteredAtMillis).coerceAtLeast(0L)
    return (boundedDwell - elapsed).coerceAtLeast(0L)
}

private data class CompanionDwellTimer(
    val transactionId: String,
    val token: Long,
    val enteredAtMillis: Long,
    val dwellMillis: Long,
) {
    val deadlineMillis: Long = enteredAtMillis + dwellMillis
}

private fun dispatchDueDragTimers(
    atMillis: Long,
    phase: () -> CompanionDragPhase,
    dispatch: (CompanionDragEvent) -> Unit,
) {
    val edgeTransaction = (phase() as? CompanionDragPhase.Dragging)?.transaction
    val edge = edgeTransaction?.edgeCandidate
    if (edge != null && atMillis - edge.enteredAtMillis >= CompanionDragStateMachine.EdgeDwellMillis) {
        dispatch(
            CompanionDragEvent.EdgeDwellElapsed(
                transactionId = edgeTransaction.id,
                edgeToken = edge.token,
                atMillis = atMillis,
            ),
        )
    }

    val mergeTransaction = (phase() as? CompanionDragPhase.Dragging)?.transaction
    val merge = mergeTransaction?.mergeCandidate
    if (merge != null && atMillis - merge.enteredAtMillis >= CompanionDragStateMachine.MergeDwellMillis) {
        dispatch(
            CompanionDragEvent.MergeDwellElapsed(
                transactionId = mergeTransaction.id,
                mergeToken = merge.token,
                atMillis = atMillis,
            ),
        )
    }
}

internal suspend fun PointerInputScope.detectCompanionShelfGesture(
    canStart: () -> Boolean,
    geometry: () -> CompanionDragGeometry,
    phase: () -> CompanionDragPhase,
    toRoot: (Offset) -> DragPoint,
    uptimeMillis: () -> Long,
    dispatch: (CompanionDragEvent) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!canStart()) return@awaitEachGesture

        val pointerId = down.id.value
        val downAt = down.uptimeMillis
        dispatch(
            CompanionDragEvent.Down(
                pointerId = pointerId,
                point = toRoot(down.position),
                atMillis = downAt,
                geometry = geometry(),
            ),
        )
        if (phase() !is CompanionDragPhase.Pressed) return@awaitEachGesture

        // True once the long-press deadline has been handed to the machine and it STAYED Pressed (the page geometry was not complete, so there was no tile to lift).
        // The reader then keeps reading this pointer without a deadline, and does not consume or lift: it only watches for the release (or a cancel) and reports it,
        // so the machine can never be left Pressed with no finger down.
        var deadlineSpent = false
        while (true) {
            when (val current = phase()) {
                is CompanionDragPhase.Pressed -> {
                    val deadline = current.downAtMillis + CompanionDragStateMachine.LongPressMillis
                    val initialEvent = if (deadlineSpent) {
                        awaitPointerEvent(PointerEventPass.Initial)
                    } else {
                        withTimeoutOrNull(
                            remainingCompanionLongPressDelayMillis(deadline, uptimeMillis()),
                        ) {
                            awaitPointerEvent(PointerEventPass.Initial)
                        }
                    }
                    if (initialEvent == null) {
                        dispatch(
                            CompanionDragEvent.LongPressElapsed(
                                pointerId = pointerId,
                                pressToken = current.pressToken,
                                transactionId = UUID.randomUUID().toString(),
                                atMillis = deadline,
                                geometry = geometry(),
                            ),
                        )
                        // Still Pressed: keep watching this pointer (below). Anything else is read by the phase's own arm, and Idle ends the gesture there.
                        deadlineSpent = true
                        continue
                    }

                    val initialChange = initialEvent.changes.firstOrNull { it.id == down.id }
                    val initialPressedCount = initialEvent.changes.count { it.pressed }
                    if (
                        initialChange == null || initialPressedCount > 1 ||
                        (!initialChange.pressed && initialPressedCount > 0)
                    ) {
                        dispatch(CompanionDragEvent.Cancel(pointerId))
                        return@awaitEachGesture
                    }
                    val initialRoot = toRoot(initialChange.position)
                    when {
                        initialChange.isConsumed -> {
                            dispatch(
                                CompanionDragEvent.Move(
                                    pointerId = pointerId,
                                    point = initialRoot,
                                    atMillis = initialChange.uptimeMillis,
                                    geometry = geometry(),
                                    pagerConsumed = true,
                                    pressedPointerCount = initialPressedCount,
                                ),
                            )
                            if (!initialChange.pressed) {
                                dispatch(
                                    CompanionDragEvent.Up(
                                        pointerId = pointerId,
                                        point = initialRoot,
                                        atMillis = initialChange.uptimeMillis,
                                        geometry = geometry(),
                                    ),
                                )
                                return@awaitEachGesture
                            }
                        }
                        !deadlineSpent && initialChange.uptimeMillis >= deadline -> {
                            if (!initialChange.pressed) {
                                dispatch(
                                    CompanionDragEvent.Up(
                                        pointerId = pointerId,
                                        point = initialRoot,
                                        atMillis = initialChange.uptimeMillis,
                                        geometry = geometry(),
                                    ),
                                )
                                return@awaitEachGesture
                            }

                            // Initial-pass consumption is the synchronous authority handoff. The pager never
                            // sees this event even if userScrollEnabled has not recomposed to false yet.
                            initialEvent.changes.forEach { it.consume() }
                            dispatch(
                                CompanionDragEvent.Move(
                                    pointerId = pointerId,
                                    point = initialRoot,
                                    atMillis = initialChange.uptimeMillis,
                                    geometry = geometry(),
                                    pressedPointerCount = initialPressedCount,
                                ),
                            )
                            dispatch(
                                CompanionDragEvent.LongPressElapsed(
                                    pointerId = pointerId,
                                    pressToken = current.pressToken,
                                    transactionId = UUID.randomUUID().toString(),
                                    atMillis = deadline,
                                    geometry = geometry(),
                                ),
                            )
                            deadlineSpent = true
                        }
                        else -> {
                            // Let HorizontalPager inspect a pre-arm event, then observe in Final whether it won.
                            val finalEvent = awaitPointerEvent(PointerEventPass.Final)
                            val change = finalEvent.changes.firstOrNull { it.id == down.id }
                            val pressedCount = finalEvent.changes.count { it.pressed }
                            if (
                                change == null || pressedCount > 1 ||
                                (!change.pressed && pressedCount > 0)
                            ) {
                                dispatch(CompanionDragEvent.Cancel(pointerId))
                                return@awaitEachGesture
                            }
                            val root = toRoot(change.position)
                            if (change.isConsumed) {
                                dispatch(
                                    CompanionDragEvent.Move(
                                        pointerId = pointerId,
                                        point = root,
                                        atMillis = change.uptimeMillis,
                                        geometry = geometry(),
                                        pagerConsumed = true,
                                        pressedPointerCount = pressedCount,
                                    ),
                                )
                                if (!change.pressed) {
                                    dispatch(
                                        CompanionDragEvent.Up(
                                            pointerId = pointerId,
                                            point = root,
                                            atMillis = change.uptimeMillis,
                                            geometry = geometry(),
                                        ),
                                    )
                                    return@awaitEachGesture
                                }
                            } else if (change.pressed) {
                                dispatch(
                                    CompanionDragEvent.Move(
                                        pointerId = pointerId,
                                        point = root,
                                        atMillis = change.uptimeMillis,
                                        geometry = geometry(),
                                        pressedPointerCount = pressedCount,
                                    ),
                                )
                            } else {
                                dispatch(
                                    CompanionDragEvent.Up(
                                        pointerId = pointerId,
                                        point = root,
                                        atMillis = change.uptimeMillis,
                                        geometry = geometry(),
                                    ),
                                )
                                return@awaitEachGesture
                            }
                        }
                    }
                }
                is CompanionDragPhase.PagerOwned -> {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        dispatch(CompanionDragEvent.Cancel(pointerId))
                        return@awaitEachGesture
                    }
                    val root = toRoot(change.position)
                    if (change.pressed) {
                        dispatch(
                            CompanionDragEvent.Move(
                                pointerId = pointerId,
                                point = root,
                                atMillis = change.uptimeMillis,
                                geometry = geometry(),
                                pagerConsumed = true,
                            ),
                        )
                    } else {
                        dispatch(
                            CompanionDragEvent.Up(
                                pointerId = pointerId,
                                point = root,
                                atMillis = change.uptimeMillis,
                                geometry = geometry(),
                            ),
                        )
                        return@awaitEachGesture
                    }
                }
                is CompanionDragPhase.Dragging,
                is CompanionDragPhase.Turning,
                -> {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val canceled = event.changes.any { it.isConsumed }
                    if (!canceled) event.changes.forEach { it.consume() }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    val pressedCount = event.changes.count { it.pressed }
                    if (
                        canceled || change == null || pressedCount > 1 ||
                        (!change.pressed && pressedCount > 0)
                    ) {
                        dispatch(CompanionDragEvent.Cancel(pointerId))
                        return@awaitEachGesture
                    }
                    val root = toRoot(change.position)
                    if (change.pressed) {
                        dispatch(
                            CompanionDragEvent.Move(
                                pointerId = pointerId,
                                point = root,
                                atMillis = change.uptimeMillis,
                                geometry = geometry(),
                                pressedPointerCount = pressedCount,
                            ),
                        )
                        dispatchDueDragTimers(change.uptimeMillis, phase, dispatch)
                    } else {
                        dispatchDueDragTimers(change.uptimeMillis, phase, dispatch)
                        dispatch(
                            CompanionDragEvent.Up(
                                pointerId = pointerId,
                                point = root,
                                atMillis = change.uptimeMillis,
                                geometry = geometry(),
                            ),
                        )
                        return@awaitEachGesture
                    }
                }
                CompanionDragPhase.Idle,
                is CompanionDragPhase.RollingBack,
                -> return@awaitEachGesture
            }
        }
    }
}

private fun Rect.toDragRect(): DragRect = DragRect(left, top, right, bottom)

data class CompanionPrimaryRoute(
    val destination: String = "LIBRARY",
    val revision: Long = 0L,
)

/**
 * Two members: [Payload] for the payload retained after retiring `Route`, and [JobPanel] for §5b's rank
 * (2026-08-30) — MIMIR's own truth (a running job) outranks the PLACE (`HOME`) and stated absence
 * (`IDLE`), so a job takes the PANEL rather than a line beneath it when the payload would otherwise
 * rest at either. Kept sealed rather than collapsed to the data class: the scene-layer machinery
 * (`CompanionSceneLayerHistory`, the outgoing-fade transition) is generic over this type, and
 * flattening it is a refactor of transition code rather than part of a retirement. **Owed**, and named
 * here so it is a decision rather than an oversight.
 */
internal sealed interface CompanionSceneTarget {
    data class Payload(val mode: CompanionMode) : CompanionSceneTarget

    /**
     * Deliberately payload-free. `ImmediateCompanionSceneTransition` keys its 140ms crossfade on
     * target EQUALITY — a variant carrying `jobs` (or even just `seen`) would turn every progress tick
     * of a long scan into a scene transition. `jobs` is read from the `CompanionSurface` composition
     * closure inside `renderScene` instead, so digits tick without retriggering the crossfade.
     */
    data object JobPanel : CompanionSceneTarget
}

/**
 * What the job line actually costs the scene above it: §4's 48 dp minimum **plus** the 12 dp the call
 * site pads beneath it. **Derived from the two tokens rather than typed**, because typing it is exactly
 * how this went wrong once — the first version of the hero budget subtracted 48, the padding is the
 * OUTER node over the `heightIn`, and the real seat is 60. The test recomputed the same wrong premise
 * and went green against it, which is the failure mode worth naming: *when a replay is green, suspect
 * the test.* Found by an adversarial review pass, not by the guard that existed to catch it.
 */
internal val CompanionJobLineSeatDp: Float = (MimirSpacing.minTarget + MimirSpacing.md).value

/**
 * How much of the 1:1 face a drawn console glyph fills.
 *
 * **One constant because there are three fallbacks and they had already drifted.** The lattice drew its
 * glyph at 0.92 and the art-cover fallback at 0.86 — the same mark, at two sizes, in two functions, for
 * no reason either of them recorded. A custom icon fills the face completely (it replaces box and art
 * together), so the drawn fallback is the only thing that needs a fraction at all, and it needs exactly
 * one.
 */
internal const val CompanionPlatformFaceGlyphFraction = 0.92f

/**
 * How far the lattice blurs behind a fully open tome, in dp.
 *
 * **Ramped by the tome's own `reveal`, never a constant applied at once** — so the backdrop softens on
 * exactly the clock the page rises on, and returns on exactly the clock it falls on. There is no second
 * duration here to drift from the first, which is §1's *three names for one value* defect avoided by
 * not creating the second name.
 *
 * 24 dp because that is the radius M-8 measured; a number costed on this panel rather than carried over
 * from a 120 Hz Odin.
 */
internal const val CompanionTomeBackdropBlurDp = 24f

private const val CompanionWorkspaceGestureLogTag = "MimirCompanionWorkspace"

/**
 * The scene transition a target arrives through, as the Bottom receipts name it: `scene.glide` when the incoming scene glides
 * (the host gave it an offset, which is the primary motion), else `scene.crossfade` when an outgoing layer fades under it, else
 * null. The first scene a host shows has no outgoing layer and no glide, so it snaps in and names nothing.
 *
 * Decided from the facts [CompanionSceneTransition] already remembers per target (its outgoing layer and its glide offset), the
 * same ones that start the animations, so the name can only be one that runs.
 */
internal fun companionSceneEntryTransition(hasOutgoingLayer: Boolean, glides: Boolean): String? = when {
    glides -> "scene.glide"
    hasOutgoingLayer -> "scene.crossfade"
    else -> null
}

/** The strip's x for the panes to draw at: the host's, or (none given, or NaN) the shown scene's own rest. */
internal fun companionStripX(host: Float?, latticeShown: Boolean, widthPx: Float): Float =
    host?.takeUnless { it.isNaN() } ?: if (latticeShown) 0f else widthPx

/**
 * D37: a recomposition counter for the swipe budget tests (the shape of the trackpad pad's `recompositionProbe`): null in production, so the sites below
 * cost one static read in composition and register nothing. A test provides a probe and is told the NAME of each site whose scope recomposed.
 */
internal val LocalCompanionRecompositionProbe = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Marks the scope this is called in (INLINE, so it is the caller's own scope that is counted, not a skippable scope of its own). */
@Composable
internal inline fun CompanionRecompositionSite(name: String) {
    val probe = LocalCompanionRecompositionProbe.current
    if (probe != null) SideEffect { probe(name) }
}

/** Slots in the surface's record of which workspace panes have been built (D24: a built pane stays built). */
private const val CompanionPaneActivity = 0
private const val CompanionPaneLattice = 1

/**
 * D24: a PARKED pane is measured but not placed. A node that is not placed is not drawn, is not hit by a touch and is left out of the semantics tree, which
 * is all that parking has to mean to the screen; its composition and its measurement stay, so un-parking it is a place and a draw. Changing [parked] only
 * re-places: nothing is recomposed or re-measured.
 */
internal fun Modifier.companionParkable(parked: Boolean): Modifier = this then CompanionParkElement(parked)

private data class CompanionParkElement(val parked: Boolean) : ModifierNodeElement<CompanionParkNode>() {
    override fun create() = CompanionParkNode(parked)

    override fun update(node: CompanionParkNode) {
        if (node.parked != parked) {
            node.parked = parked
            node.invalidatePlacement()
        }
    }
}

private class CompanionParkNode(var parked: Boolean) : LayoutModifierNode, Modifier.Node() {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            if (!parked) placeable.place(0, 0)
        }
    }
}

/** The scene transition the target being composed arrived through ([companionSceneEntryTransition]); null when it did not animate in. */
internal val LocalCompanionSceneEntryTransition = compositionLocalOf<String?> { null }

/** What a scene transition last presented: the target and the identity it was presented under, remembered across targets. */
private class CompanionSceneHistory(var presentedTarget: Any?, var presentedIdentity: Any?)

/**
 * Where a scene transition's current presentation stands, so a change that arrives while it is still hidden can join it. [hidden] reads
 * true from a forward handoff's flip until its arriving scene first paints (inside the lead), and false for a presentation at rest.
 */
private class CompanionSceneHold {
    var epoch: CompanionSceneEpoch? = null
    var hidden: () -> Boolean = { false }
}

/**
 * A scene's presentation key: equal keys are one presentation. [generation] counts the presentations a transition has started, so every real change starts a new
 * one: a scene that joined an earlier, still-hidden presentation keeps that presentation's key while it recomposes, and when it is asked for again later (even
 * the very scene the join was onto) that is a new presentation with its own full handoff, never a cut.
 */
private data class CompanionSceneEpoch(val target: Any?, val identity: Any?, val generation: Int = 0)

/**
 * A test seam, like core-ui's `MimirPageHandoffProbe`: what the arriving scene is painted at, whether touches are shielded
 * from it, and the departing page's own probe.
 */
class CompanionSceneTransitionProbe {
    val handoff = MimirPageHandoffProbe()
    var incomingAlpha: () -> Float = { 1f }
    var shielding: () -> Boolean = { false }
}

/**
 * A scene change on the page tier (Thor Bottom revamp B2, T6 part 2): the Bottom's scenes hand over exactly as Main's pages do,
 * through core-ui's [MimirPageHandoff]. The departing scene leaves on [MimirPageMotion.ExitMs] as recorded pixels. The arriving
 * one waits the [MimirPageMotion.LeadMs] lead at alpha 0, where the departing scene is at 0.044, then fades in over
 * [MimirPageMotion.ArriveMs]: about 670 ms from the press, and never two scenes on screen together (the old 0.5 start alpha and
 * its 140 ms second exposure are retired).
 *
 * A [returning] change (back to the lattice, the page at rest) has no arrival of its own: the destination is already there at
 * alpha 1 and the departing scene dissolves over it on [MimirPageMotion.ReturnMs], about 160 ms.
 *
 * Travel is [incomingGlideOffsetDp], signed: the side the arriving scene comes from, and the departing one leaves the other way.
 * It applies only when [transitionIdentity] changes (a workspace page change), and a change of [targetState] alone (Now playing
 * and the lattice) crosses in alpha only: a full-screen mode change takes no lift.
 *
 * Touch: while the arriving scene is hidden a touch on the surface reaches nothing (the shield lifts at its first painted
 * frame). There is no pad gate; the Bottom is touch-only and a held process-wide gate would deafen Main's controller.
 * Reduced motion snaps: no departing scene, no lead and no travel.
 *
 * Receipts keep their names: `scene.crossfade` covers the arrival (or, on a return, the departing scene's own exit) and
 * `scene.glide` covers the same run when the scene travels.
 */
@Composable
fun <T> CompanionSceneTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    transitionIdentity: Any? = targetState,
    incomingGlideOffsetDp: Float = 0f,
    returning: Boolean = false,
    /** The change is already on screen: the finger carried it (the host's workspace strip settled), so nothing departs, arrives or glides. */
    snap: Boolean = false,
    probe: CompanionSceneTransitionProbe? = null,
    content: @Composable (T) -> Unit,
) {
    val history = remember { CompanionSceneHistory(targetState, transitionIdentity) }
    val hold = remember { CompanionSceneHold() }
    val changed = history.presentedTarget != targetState || history.presentedIdentity != transitionIdentity
    val identityChanged = history.presentedIdentity != transitionIdentity
    // A forward change that lands while the previous forward change is still hidden (inside its lead) joins that presentation instead of
    // starting a third: the scene that is visible keeps leaving on its own clock, the hidden one is simply replaced in place, and the
    // arrival goes on from where it was. Starting over would discard the visible scene for an invisible one and blank the row until the
    // newest paints. A return arrives at rest, so it always starts its own; a presentation that has painted is visible, so it does too.
    val joins = changed && !returning && !snap && hold.epoch != null && androidx.compose.runtime.snapshots.Snapshot.withoutReadObservation { hold.hidden() }
    // The presentation this composition belongs to. A recomposition of the SAME scene (a playback or artwork update changes nothing of its identity) stays on the
    // presentation it is already in, whichever it joined; a real change starts a new generation, unless it lands inside a still-hidden presentation, which it joins.
    val current = hold.epoch
    val epoch = when {
        joins -> checkNotNull(current)
        !changed && current != null -> current
        else -> CompanionSceneEpoch(targetState, transitionIdentity, (current?.generation ?: 0) + 1)
    }
    SideEffect {
        history.presentedTarget = targetState
        history.presentedIdentity = transitionIdentity
    }
    // Everything below is decided at the flip and held for the presentation, as the remembered facts that start the animations.
    val reducedMotion = remember(epoch) { bottomUiReducedMotion() }
    val density = LocalDensity.current
    val glidePx = remember(epoch) { if (changed && identityChanged && !reducedMotion && !snap) incomingGlideOffsetDp * density.density else 0f }
    val handsOver = remember(epoch) { changed && !reducedMotion && !snap }
    val arrivesAtRest = remember(epoch) { !handsOver || returning }
    val departsOnReturnClock = remember(epoch) { returning }
    val entryTransition = remember(epoch) { companionSceneEntryTransition(hasOutgoingLayer = handsOver, glides = glidePx != 0f) }
    val incoming = remember(epoch) { Animatable(if (arrivesAtRest) 1f else 0f) }
    // The touch half of the page tier's hold (no pad half here): up on the flip frame, down at the arrival's first painted frame.
    var shielding by remember(epoch) { mutableStateOf(!arrivesAtRest) }
    val handoffProbe = remember(probe) { probe?.handoff ?: MimirPageHandoffProbe() }
    val receiptDisplay = LocalBottomUiDisplay.current
    SideEffect {
        hold.epoch = epoch
        hold.hidden = { !arrivesAtRest && incoming.value == 0f }
        probe?.incomingAlpha = { incoming.value }
        probe?.shielding = { shielding }
    }
    LaunchedEffect(epoch) {
        if (!handsOver) return@LaunchedEffect
        try {
            // A return runs nothing of its own here: the destination is at rest and the departing scene's exit, read from the
            // handoff's probe (set in the frame's apply, so one frame in), is the transition. A forward change is the arrival.
            suspend fun arrive() {
                if (arrivesAtRest) {
                    snapshotFlow { handoffProbe.departing() }.first { !it }
                } else {
                    incoming.animateTo(1f, mimirOverlayArriveSpec(reducedMotion = false)) {
                        if (value > 0f && shielding) shielding = false
                    }
                }
            }
            if (arrivesAtRest) withFrameNanos { }
            bottomUiTransition("scene.crossfade", receiptDisplay) {
                if (glidePx != 0f) bottomUiTransition("scene.glide", receiptDisplay) { arrive() } else arrive()
            }
        } finally {
            shielding = false
        }
    }

    // A scene that departs and one that arrives travel the same distance: the arrival from the signed side, the departure away from it.
    val arrivalFromPx = if (arrivesAtRest) 0f else glidePx
    MimirPageHandoff(
        epoch = epoch,
        departs = !reducedMotion && !snap,
        exitTravelPx = -glidePx,
        incomingAlpha = { incoming.value },
        incomingTravelPx = { arrivalFromPx * (1f - incoming.value) },
        reducedMotion = reducedMotion,
        exitMs = if (departsOnReturnClock) MimirPageMotion.ReturnMs else MimirPageMotion.ExitMs,
        modifier = modifier,
        probe = handoffProbe,
        shieldTouches = { shielding },
    ) {
        CompositionLocalProvider(LocalCompanionSceneEntryTransition provides entryTransition) {
            content(targetState)
        }
    }
}

/**
 * **The route title card is retired.** When the primary sat on
 * SETTINGS/BROWSE/TOOLS, screen 2 drew a card naming the destination the player was already looking
 * at — the one-question test's weakest case. It no longer pre-empts the payload, so screen 2 keeps
 * showing what is TRUE (HOME, NOW_PLAYING, IDLE) while the primary navigates its shell.
 *
 * [route] is retained as a parameter: the signature is part of this module's contract and callers
 * still pass it, but nothing consults it now that the destination does not select a surface.
 */
/** A determinate readout only -- callers never pass a null fraction. Uncalled since the job panel that
 *  drew it was deleted (copy review 2026-09-11, F6); kept because `CompanionJobRankTest` pins its
 *  fraction-as-a-parameter law. Track + accent fill, no gradient and no motion: §4's readout law
 *  forbids a colour verdict, and this is a magnitude, not a state that has earned one. */
@Composable
private fun CompanionJobProgressBar(fraction: Float, palette: MimirThemePalette) {
    Canvas(
        modifier = Modifier
            .width(220.dp)
            .height(6.dp),
    ) {
        drawLine(
            color = palette.outline.copy(alpha = 0.4f),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
        // **a DECORATIVE ramp, value-independent.** The gradient runs across
        // the bar's own width, so the same two inks appear at 5% and at 95% — it is material, not a
        // reading. §4 forbids the other kind: *a colour that changes at a threshold is a CLAIM*, and a
        // fill whose HUE moved with the fraction would be a verdict on a magnitude, which is the same
        // refusal the fan readout's two candidate thresholds got. Digits carry the magnitude here.
        //
        // **The other half of that ask — an animated shimmer — is NOT built, and the reason is a law
        // rather than an omission.** It would be the first PERPETUAL producer on this panel: every
        // animator here today is bounded (the 140 ms crossfade, the cursor ring's one settle and sweep) or gated (the ambient
        // phase stands the rest down at 12 s idle). On a surface vsynced at 8.333 ms onto 60 Hz glass, held live while the
        // primary is STOPPED, against the long-idle rendering budget, an
        // animation that runs exactly while a long scan runs cannot be gated by anything — the state
        // it would animate through IS the state the panel exists for.
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(palette.accentBright, palette.accent),
                startX = 0f,
                endX = size.width,
            ),
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width * fraction, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * §5b: *titled by what is true*. The sentence IS the state, so there is **no product noun** — nothing
 * here is a place the player could navigate to, and naming it would make it one.
 *
 * A null total is not 0% and not stalled: it means the job has no honest denominator yet, so the
 * sentence states what it does know — a count — instead of implying a bar. That is §5b's *absence is
 * stated, not filled*, and it is why a library walk reads "1,204 files found" rather than "0%".
 */
internal fun companionJobSentence(job: CompanionJobProgress): String {
    val verb = when (job.kind) {
        "SCAN" -> "Scanning"
        "SCRAPE" -> "Finding art"
        "ENRICH" -> "Identifying"
        "DEDUPE" -> "Checking for duplicates"
        else -> "Working"
    }
    // **The tail belongs to the KIND, not to the template —.** Every kind used to
    // append "files found", so a scrape read *"Finding art · 1,204 files found"*: finding art, and
    // counting files. Two different things in one sentence, and the sentence was wrong rather than
    // merely clumsy.
    //
    // It shipped because an earlier review checked the SCAN case — where a walk
    // genuinely does find files — and let the other four inherit a noun that was only ever true for
    // one of them. A template with one variable in it looked like a template with one meaning.
    //
    // What each kind can honestly say about a count with no denominator: a SCAN has FOUND files, it
    // does not know how many exist yet. The others have CHECKED things they were already handed, so
    // "found" would claim a discovery they did not make.
    val tail = when (job.kind) {
        "SCAN" -> "files found"
        "SCRAPE", "ENRICH", "DEDUPE" -> "checked"
        else -> "so far"
    }
    val total = job.total
    return if (total != null && total > 0) {
        "$verb · ${companionGroupedCount(job.seen)} of ${companionGroupedCount(total)}"
    } else {
        "$verb · ${companionGroupedCount(job.seen)} $tail"
    }
}

/**
 * Thousands grouping, because §4's dialect law makes it one: *a quantity Mimir already speaks must be
 * spoken identically everywhere*, and Mimir already speaks a grouped "N of M" — `MimirAchievementCard`
 * renders `"%,d of %,d points"`. An ungrouped four-digit count on this panel is a second dialect for a
 * quantity the product has already settled, which is the defect §4 names, not a matter of tidiness.
 *
 * [Locale.US] rather than the default locale, deliberately: this app ships no `values-*` resources and
 * every string in it is authored English, so a device-locale separator would be the only localised
 * thing on the surface. It is also the only form a test can pin. `MimirAchievementCard` uses the
 * DEFAULT locale for the same shape, so on a non-US device those two surfaces disagree — recorded as
 * open rather than fixed here, because that card is another lane's file.
 */
private fun companionGroupedCount(value: Int): String = String.format(Locale.US, "%,d", value)

/**
 * §5b's rank, as a predicate: PLAYER truth (`NOW_PLAYING`/`RUNNING_APP`) outranks MIMIR's own truth (a
 * running job); the PLACE (`HOME`) and stated absence (`IDLE`) do not. One function so the rank is one
 * place to read and one place to test, rather than an inline condition duplicated at every call site.
 */
internal fun companionJobOutranksPayload(payloadMode: CompanionMode): Boolean = when (payloadMode) {
    CompanionMode.HOME, CompanionMode.IDLE -> true
    CompanionMode.NOW_PLAYING, CompanionMode.RUNNING_APP -> false
}

internal fun companionRequestedSceneTarget(
    @Suppress("UNUSED_PARAMETER") route: CompanionPrimaryRoute,
    payloadMode: CompanionMode,
    companionDragging: Boolean = false,
    /** §5b: a job outranks `HOME`/`IDLE` for the panel. Checked AFTER the drag hold on purpose — a
     *  tile in the air pins the panel at `HOME` regardless of rank, so a job starting mid-drag cannot
     *  yank the shelf out from under the player's finger. */
    jobs: List<CompanionJobProgress> = emptyList(),
    /**
     * **The escape — policy 2026-08-31.** The job panel covers `HOME`'s 3×3 lattice for a whole
     * scan, and §5b calls that content class load-bearing, so the player may hand the panel back with
     * a touch. Demotion moves the job from the PANEL to the LINE; it never hides it, which is why this
     * stays inside the rank rather than becoming a dismissal. Resets when the job set empties, so the
     * next job is offered the panel again rather than inheriting a decision about a different job.
     */
): CompanionSceneTarget {
    if (companionDragging) return CompanionSceneTarget.Payload(CompanionMode.HOME)
    // **A FOREIGN APP NEVER TAKES THE SCENE — ** The demotion itself is
    // UNCHANGED and still required: **the HOME lattice remains reachable while the app runs** — screen 1
    // is already showing the app, so screen 2 keeps the PLACE. What is gone is the promotion: the
    // `runningAppPromoted` flag had exactly one writer, a tap on the retired line, so the condition it
    // guarded can no longer be false and is written out rather than left as a parameter no caller can
    // set.
    //
    // Pinned in `CompanionJobRankTest`: no input may make this return `Payload(RUNNING_APP)`. That pin
    // is load-bearing rather than pedantic — restoring the promotion without also restoring a renderer
    // would repeat 2026-08-31 exactly (a selector winning the scene while nothing draws), and the arm
    // this feeds now draws `Unit`.
    val panelMode = if (payloadMode == CompanionMode.RUNNING_APP) CompanionMode.HOME else payloadMode
    // **A RUNNING JOB NO LONGER TAKES THE SCENE.** The panel's renderer was retired on
    // 2026-08-31 and its arm below now draws `Unit`.
    // This selector was not changed with it, so every scan, scrape and enrich still WON the scene and
    // then drew nothing: screen 2 faded the shelf out over 140 ms and sat empty — gradient and rail
    // only — for the entire duration of the job. `jobPanelDemoted` could not rescue it either; the tap
    // that used to set it lived on the panel that no longer draws.
    //
    // Found by the companion-transition review, 2026-09-01. Retiring a view and leaving the rule that
    // SELECTS it is the same mistake as retiring one and leaving its height reserved, which this file
    // has already paid for once.
    return CompanionSceneTarget.Payload(panelMode)
}

/**
 * **The rail is on every surface, including HOME.**
 *
 * The original layout kept it off `HOME` because it costs 86 dp of a 468 dp panel, which an analysis
 * predicted would drop each lattice tile to 38.7 dp — under §4's 48 dp floor on a surface with no
 * controller. The alternative placed the instruments switch living in `HOME`'s footer beside
 * Create Tome, a make-something action and a change-what-you-are-looking-at switch sharing one row at
 * equal weight, in a layout where the switch slid sideways every time the cursor moved. The rail provides one stable switch location.
 *
 * **So the footer switch is retired with this** — the rail carries it now, everywhere, which is what
 * "one home for the switch" means and why the reversal is worth its cost.
 *
 * **The 38.7 dp figure was a prediction, not a measurement, and it is owed a real one on glass.** If a
 * tile genuinely lands under 48 dp on a touch-only surface, that is a §4 violation the layout change could create
 * and requires a layout correction grounded in that measurement.
 *
 * Kept from the original note because it is still true and still load-bearing: retiring the route card
 * means SETTINGS/BROWSE resolve to a `HOME` payload rather than a Route target. That was checked
 * before the retirement landed, precisely because "the rail disappears in Settings" would have been a
 * real regression hiding behind a visual layout change.
 */
internal fun companionQuickLaunchAvailable(
    @Suppress("UNUSED_PARAMETER") route: CompanionPrimaryRoute,
    payloadMode: CompanionMode,
): Boolean = true

/**
 * `JobPanel` answers true for the same reason `HOME` answers false above: the job panel COVERS `HOME`,
 * so `HOME`'s own footer is off-screen for the panel's whole run, and the shared rail is the only
 * quick-launch surface left. Standing this down here would silently remove quick-launch for an entire
 * scan/scrape rather than moving it, which is the regression the sibling comment already warns about.
 */
internal fun companionQuickLaunchAvailable(target: CompanionSceneTarget): Boolean = true

@Composable
fun CompanionSurface(
    payload: CompanionPayload,
    /**
     * Long-running work in flight. Standing §5b: two truths is a LAYOUT, not a contest — the higher
     * truth keeps the panel and the other takes a line beneath it (see [companionJobOutranksPayload]).
     * **Nothing draws it any more.** the job panel and the job line were retired
     * (the `JobPanel` arm of `renderScene` is `Unit`), and the two composables were deleted with the
     * 2026-09-11 copy review (F6). Empty means nothing is running.
     */
    jobs: List<CompanionJobProgress> = emptyList(),
    modifier: Modifier = Modifier,
    /** Optional shared workspace scene; the rail and panel hosts remain owned by this surface. */
    sceneOverride: (@Composable () -> Unit)? = null,
    /** Stable host identity for a scene transition; avoids retriggering on lambda recreation. */
    sceneTransitionIdentity: Any? = null,
    /** Signed initial horizontal offset in dp for a host-owned scene glide. */
    sceneTransitionGlideOffsetDp: Float = 0f,
    /**
     * B4: the scene a host that pans the workspace slides in beside the shown one (the Activity page, while the lattice is shown). First composed
     * when the host starts panning; the shown scene is [sceneOverride] when there is one, else the lattice. **D24: once built, it stays built** (see
     * [scenePanActive]) and is parked while it is neither shown nor panning. Null: no neighbour.
     */
    neighbourScene: (@Composable () -> Unit)? = null,
    /**
     * True from the moment the host starts panning until it has settled: both scenes are live, and the panes sit where [sceneStripX] says.
     *
     * **D24: a pane that has been built stays built, and the one that is neither shown nor panning is PARKED**, not disposed. A parked pane keeps its
     * composition and its measurement and is not placed, drawn or touched; [LocalCompanionPaneLive] reads false inside it, which stops its feeds and
     * timers. Dragging un-parks it, so a swipe costs a place and a draw and not a scene's composition. A pane no one has asked for yet is not built:
     * at rest, before the first pan, only the shown scene exists.
     */
    scenePanActive: Boolean = false,
    /**
     * Where the workspace strip [Activity | lattice] is, in px: 0 is the lattice at rest and the surface's width is the Activity page at rest. The
     * lattice pane is drawn at this x and the Activity pane one width to its left, spatially only. Read where the panes are drawn (a layer's
     * block), never in composition. [Float.NaN], or null here, means the strip is at rest on whichever scene is shown, which needs no measurement.
     */
    sceneStripX: (() -> Float)? = null,
    /** The Home payload the host holds (its retained one) for the lattice pane while [payload] carries no lattice of its own, as on the Activity page. Null: none. */
    latticePayload: CompanionPayload? = null,
    /** The host's change of [sceneTransitionIdentity] is one its finger already carried: the scene switches with no handoff of its own. */
    sceneTransitionSnap: Boolean = false,
    primaryRoute: CompanionPrimaryRoute = CompanionPrimaryRoute(),
    /** Console tile tapped → the host opens that gamelist on the MAIN display (S3). */
    /**
     * A console tile was tapped. Carries the tile's own LABEL alongside its id, exactly as [onAppTap]
     * does and for the same reason recorded there: the host cannot look the name up without racing the
     * catalog sweep, and the surface that opens next needs a header the instant it opens.
     */
    onPlatformTap: (platformIdValue: String, label: String) -> Unit = { _, _ -> },
    /**
     * Armed app tile tapped a second time → the host places that app on screen 2 (S3).
     *
     * **Carries the tile's LABEL as well as its id.** The host used to receive only the package and look
     * the name up in `CompanionQuickLaunchCatalog`, a background sweep that starts empty — so a tap in
     * the cold window produced no name and the launch line fell back to the package id. The tile on
     * screen already knows what it is called; it is the one source that cannot be un-ready.
     */
    onAppTap: (String, String) -> Unit = { _, _ -> },
    /** Explicitly yields Mimir so the already-running DS/3DS secondary window is exposed. */
    onReturnToGame: ((com.gruning.mimir.rewrite.core.model.GameId) -> Boolean)? = null,
    /**
     * S4: the tile the resting marker (the bookmark) sits on — **screen 2's OWN cursor**, set by a tap
     * on screen 2 and persisted in `CompanionOrderStore`.
     *
     * The cursor used to be passed and
     * suppressed while the ring mirrored `payload.selectedShelfId`, which the adapter derived from
     * screen 1's focused key — so the second screen's only animation restarted from the other screen's
     * d-pad, which is exactly what §5b forbids: *the second screen's clock is never driven by the first
     * screen's input.* The companion cursor is therefore independent of primary-screen focus.
     */
    cursorId: String? = null,
    /** Book-local selection memory, independent from the top-level shelf cursor. */
    childCursorByFolder: Map<String, String> = emptyMap(),
    /** S4: the page the user left open. Applied as a SNAP on first composition, never an animated glide. */
    restoredPage: Int = 0,
    /**
     * D37: [restoredPage], asked where the lattice is MOUNTED instead of handed in as a value. The lattice reports every page it turns up to the host (the store the
     * host keeps it in), and a host that observed that store to feed this parameter was recomposed, and the surface with it, by every page a swipe turned. The page is
     * only the page to START on, so the host passes a function that reads the store without observing it. Null: [restoredPage].
     */
    restoredPageProvider: (() -> Int)? = null,
    /** S4: a drop committed — the full visible order, filed. */
    onReorder: (List<String>) -> Unit = {},
    /** S4: the marker moved (a tap, or a lift). */
    onArm: (String) -> Unit = {},
    /** A child was selected inside an open book. */
    onChildCursor: (folderId: String, childId: String) -> Unit = { _, _ -> },
    /** S4: the pager settled on a page. */
    onPageSettled: (Int) -> Unit = {},
    /** CR-17: a tile was dropped INTO an existing tome after the dwell — file it there. */
    onBind: (order: List<String>, draggedId: String, targetId: String) -> Unit = { _, _, _ -> },
    /** S6: an opened tome was renamed. */
    onRenameFolder: (String, String) -> Unit = { _, _ -> },
    /** CR-17: the "New book" affordance was pressed — bind an empty tome at the visible order's tail. */
    onCreateFolder: (order: List<String>) -> String = { "" },
    /** CR-17: an opened empty tome was discarded — its children (if any) come home. */
    onDiscardFolder: (order: List<String>, folderId: String) -> Unit = { _, _ -> },
    /** CP04: long-press inside an open book → move that ONE child back to the shelf. */
    onUnbindChild: (order: List<String>, childId: String) -> Unit = { _, _ -> },
    /** UX01 — the pinned quick-launch slots (Q1–Q5) shared by every surface except Home. */
    quickSlots: List<String?> = emptyList(),
    /** UX01 — every launchable Android app/game (the quick-launch picker grid + the slot-icon lookup). */
    quickLaunchApps: List<CompanionAppEntry> = emptyList(),
    /**
     * Whether [quickLaunchApps] has been SWEPT, as opposed to merely starting empty. The rail needs the
     * two told apart: a pin the sweep has not reached yet is not an empty seat, and drawing it as one is
     * what made a pinned seat wear `+` and launch anyway.
     */
    quickLaunchCatalogLoaded: Boolean = true,
    /** UX01 — launch a pinned/picked app through the host's exact-token secondary transaction. */
    onQuickLaunch: (packageName: String) -> Unit = {},
    /** Pinned seats retain their non-General launch origin for MRU policy. */
    onPinnedQuickLaunch: (packageName: String) -> Unit = onQuickLaunch,
    /** Missing or edited package destination; the host owns persistence and dispatch. */
    quickLaunchChoice: CompanionQuickLaunchChoice? = null,
    onQuickLaunchChoice: (CompanionQuickLaunchChoice, CompanionQuickLaunchDestination) -> Unit = { _, _ -> },
    onQuickLaunchChoiceDismiss: (CompanionQuickLaunchChoice) -> Unit = {},
    /** Long-press an app in the picker to edit its saved destination. */
    onQuickLaunchEdit: ((packageName: String) -> Unit)? = null,
    /** UX01 — assign a package to quick-launch slot [slotIndex] (host wires QuickLaunchStore.setSlot). */
    onQuickAssign: (slotIndex: Int, packageName: String) -> Unit = { _, _ -> },
    /** Remove a package from quick-launch slot [slotIndex] from its occupied-seat action context. */
    onQuickClear: (slotIndex: Int) -> Unit = {},
    /** The shared launch transaction is already in flight; every companion entry point observes this lock. */
    quickLaunchLocked: Boolean = false,
    quickLaunchMessage: String? = null,
    /**
     * QA D17 (2026-09-11): a tap on a rail seat that [quickLaunchLocked] dropped. The seat still ignores it exactly as
     * before; this only reports it -- with the seat's package, `all_apps`, or `empty_q<N>` -- so the host can log it.
     */
    onQuickLaunchIgnored: (target: String) -> Unit = {},
    /**
     * The host is drawing something over this whole surface — today, the recents drawer.
     *
     * **Stands the lattice cursor ring's gleam down.** The ring is still at rest and its one sweep is a bounded animation, so what a cover saves is the sweep
     * spent on pixels nobody can see: a covered bookmark is not the subject. A cover that is up when the cursor lands means the ring is whole and STILL, with no sweep (not even when the
     * cover leaves); a cover that comes up mid-sweep cancels it and settles the ring at once. Only a cursor that moves afterwards sweeps. It is also what keeps a blurred
     * backdrop honest: nothing behind the blur is animating.
     */
    panelCovered: Boolean = false,
    /**
     * The host paints an opaque panel over this whole surface (a platform shelf). Freezes lattice
     * motion like [panelCovered] but raises NO scene glass — the host's own house glass carries that transition.
     */
    hostCovered: Boolean = false,
    /**
     * QA D11 (2026-09-11): a drawer in ANOTHER window covers this surface and brings its own rail, whose seats do not
     * sit on this one's -- the overlay-hosted Tabs drawer over this window's own mirror (evidence S20: six seats over
     * seven). The rail fades out on that drawer's own enter curve, takes no input while covered, and fades back the
     * same way. [panelCovered] still carries the scene glass; the Presentation-hosted drawer leaves this false.
     */
    railCovered: Boolean = false,
    /** Null when this host offers no instruments panel; otherwise whether it is currently up. */
    instrumentsShown: Boolean? = null,
    onToggleInstruments: () -> Unit = {},
    /** Reports the quick picker synchronously so a host can place it above sibling content layers. */
    onPickerOpenChanged: (Boolean) -> Unit = {},
    /** Reports the shelf drag so workspace navigation cannot race an in-flight reorder. */
    onDraggingChanged: (Boolean) -> Unit = {},
    /**
     * The workspace seam (Thor Bottom revamp B3/B4): a host that pans the workspace with the finger hands this surface three callbacks, and
     * the surface knows nothing of what the host does with them. While the lattice pager follows the finger, whatever it could not use at
     * page 0's left edge is offered here: [onWorkspaceDrag] takes a finger delta in px (positive is rightward) and answers with what it did
     * not use, so the pager takes that back. [onWorkspaceDragEnd] is the release (a velocity in px/s, positive rightward) and answers true
     * when the host owned it, so the pager does not also settle. [onWorkspaceDragCancel] is a drag that ended without a release. Null on a
     * host with no workspace to pan to: the pager then simply follows the finger.
     */
    onWorkspaceDrag: ((deltaPx: Float) -> Float)? = null,
    onWorkspaceDragEnd: ((velocityPx: Float) -> Boolean)? = null,
    onWorkspaceDragCancel: (() -> Unit)? = null,
    /** The host's coverage/modal guard for the full-pane workspace gesture. */
    workspaceNavigationEnabled: Boolean = true,
    /** Whether the lattice footer teaches the Activity swipe (B6). False once the player has made that swipe: the hint, and the height it cost, are gone for good. */
    showActivitySwipeHint: Boolean = true,
    /**
     * The host's hold on the lattice cursor ring's one gleam: false while the host still wears something over the whole surface (the cold-start badge
     * cover), so the sweep is not spent beneath it. A lambda over snapshot state, read where the gleam waits and never in composition. The lattice's own
     * arrival and a Tome's reveal are added to it inside; it is never the process-wide pad gate. Default: no hold.
     */
    latticeArmed: () -> Boolean = MimirFocusAlwaysArmed,
    /** Reports the hideable dock state so the host can stand down workspace input while it floats. */
    onDockExpandedChanged: (Boolean) -> Unit = {},
    /** Event-time-domain monotonic clock; injectable so deterministic input tests share their synthetic clock. */
    monotonicUptimeMillis: () -> Long = android.os.SystemClock::uptimeMillis,
    /**
     * R6-P2-08: reports every open/close of the Home lattice's own folder ("Tome"), so a host outside
     * Compose can tell this surface has a closable layer up without owning Compose state.
     */
    onBookOpenChanged: (Boolean) -> Unit = {},
    /** R6-P2-08: forwarded to [CompanionHome]; see its own KDoc. */
    closeBookRequest: Long? = null,
    /** R6-P2-08: forwarded to [CompanionQuickLaunchLayer]'s own `closePickerRequest`. */
    closePickerRequest: Long? = null,
) {
    val themeId = MimirThemeId.fromStorageKey(payload.themeId)
    MimirTheme(themeId = themeId) {
        // A second finger ANYWHERE on this surface (the footer, the dock layer, the revealed Activity pane) takes a held swipe away: the lattice's own drag watch only sees
        // the pointers in its own hit path, so this one sits on the surface's root, which spans the panes and the footer, and routes to the active drag's cancel.
        val secondPointerBus = remember { CompanionSecondPointerBus() }
        val latestSurfaceDragCancel = rememberUpdatedState(onWorkspaceDragCancel)
        val surfaceWatch = remember {
            MimirGestureWatch {
                // The lattice's own pager drag takes it when one is held (it cancels the host itself if the host holds the finger); otherwise it is the Activity page's
                // drag, which the host's cancel reaches.
                if (secondPointerBus.listener?.invoke() != true) latestSurfaceDragCancel.value?.invoke()
            }
        }
        CompanionStage(modifier = modifier.fillMaxSize().mimirGestureWatch(surfaceWatch)) {
            CompanionRecompositionSite("surface")
            var companionDragging by remember { mutableStateOf(false) }
            var cursorName by remember { mutableStateOf<String?>(null) }
            val lastHomePayload = remember { mutableStateOf(payload) }
            if (payload.homeLanes.any { it.tiles.isNotEmpty() }) {
                lastHomePayload.value = payload
            } else if (latticePayload != null && latticePayload.homeLanes.any { it.tiles.isNotEmpty() }) {
                // The payload this surface is handed carries no lattice (a remount directly onto Activity with a lane-less NOW_PLAYING payload): the host's own
                // retained Home is what the lattice pane shows while the strip pans, never an empty one.
                lastHomePayload.value = latticePayload
            }
            val authoritativeHomeLanes = payload.homeLanes.takeIf { it.isNotEmpty() }
                ?: lastHomePayload.value.homeLanes
            val payloadByMode = remember { mutableMapOf<CompanionMode, CompanionPayload>() }
            payloadByMode[payload.mode] = payload
            val requestedTarget = companionRequestedSceneTarget(
                route = primaryRoute,
                payloadMode = payload.mode,
                companionDragging = companionDragging,
                jobs = jobs,
            )
            // The destination becomes authoritative immediately; one pixel-frozen outgoing layer fades above
            // it. Only the destination content tree remains interactive and no alpha-zero frame can exist.
            val quickLaunchVisible = companionQuickLaunchAvailable(requestedTarget)
            // Session-local: the secondary rail starts out of the way, with only its small handle
            // left discoverable at the bottom edge.
            var quickLaunchExpanded by remember { mutableStateOf(false) }
            // Whether the lattice's own Tome is open: its outside-dismiss strip is under the widened handle zone, so the zone narrows while it is up.
            var tomeOpen by remember { mutableStateOf(false) }
            var pickerOpen by remember { mutableStateOf(false) }
            // **Glassdrop, the picker and the drawer are PANELS.** §4's own
            // discriminator — a panel whose backdrop is optional decoration does not paint its own
            // floor — puts both on the glass side, and the picker's KDoc already named a leather it
            // never drew. Ramped rather than switched, so the surface behind softens with the panel
            // arriving instead of snapping under it.
            // RC-02 (audit 2026-10-14, B-08 and its dependents): the close-side hold. The glass under a leaving panel stays up through the panel's
            // own dissolve and clears only after it (rememberMimirGlassHold keeps the request for
            // OverlayDissolveCoverMs), so the scene never sharpens under a picker, prompt, drawer, dock or menu that is still half-visible. The
            // open is unchanged: the hold passes a request through on its own frame.
            val overlayBlur by rememberCompanionOverlayGlass(pickerOpen || quickLaunchChoice != null || panelCovered || quickLaunchExpanded)
            val renderScene: @Composable (CompanionSceneTarget) -> Unit = { sceneTarget ->
                // Receipts: the scene that is the TARGET right now. This lambda is composed for the target only (the
                // outgoing scene is a frozen layer), so its composition lifetime is the surface's visible lifetime, and a
                // surface that changes here is an exit of the old one then an enter of the new one.
                val baseSurface = when {
                    sceneOverride != null -> BottomSurface.ACTIVITY
                    sceneTarget is CompanionSceneTarget.Payload && sceneTarget.mode == CompanionMode.HOME -> BottomSurface.LATTICE
                    else -> null
                }
                // The surface this one replaced (null for the host's first scene), remembered across targets: the enter names it
                // as `from=`, and the replaced surface's own exit names this one as `to=` (see BottomUiSurfaceReceipt).
                val previousBaseSurface = remember { arrayOfNulls<String>(1) }
                val replaced = remember(baseSurface) { previousBaseSurface[0] }
                SideEffect { previousBaseSurface[0] = baseSurface }
                if (baseSurface != null) {
                    val sceneEntry = LocalCompanionSceneEntryTransition.current
                    BottomUiSurfaceReceipt(
                        surface = baseSurface,
                        visible = true,
                        // A scene the finger carried in ran `workspace.settle` (named where it ran) and no scene transition: say so, rather than name one
                        // that did not run.
                        transition = if (sceneTransitionSnap) null else when (baseSurface) {
                            BottomSurface.ACTIVITY -> "scene.glide"
                            // The lattice enters through whichever scene transition actually carried it in: scene.glide on the way
                            // back from Activity, and none on the host's first scene.
                            else -> sceneEntry
                        },
                        reason = if (sceneTransitionSnap) "workspace.settle" else null,
                        from = replaced?.takeIf { it != baseSurface },
                    )
                }
                // The lattice's scenes, one composable so the shown lattice and a neighbouring one are the same call (B4).
                val renderLattice: @Composable (CompanionSceneTarget) -> Unit = { target ->
                    // D24: a parked lattice starts no sweep. Read from the pane's state inside the lambda (where the gleam waits, never in composition), so parking neither
                    // recomposes the lattice nor settles the ring for good, which a panelCovered would.
                    val paneLiveState = LocalMimirMotionLive.current
                    val hostArmed = remember(paneLiveState, latticeArmed) { { paneLiveState.value && latticeArmed() } }
                    when (target) {
                    // §5b: the job outranked HOME/IDLE for the panel; `jobs` comes from the composition
                    // closure rather than the target itself (see `CompanionSceneTarget.JobPanel`'s KDoc).
                    CompanionSceneTarget.JobPanel ->
                        // **RETIRED — ** Neither the panel nor the line draws now.
                        // It was never asked for: it came from reading §5b's rank as an instruction to
                        // FILL every rank rather than as an ordering among things that have earned a
                        // place, and a scan's progress has not -- the player started it, it finishes on
                        // its own, and nothing about it needs the second screen.
                        Unit
                    is CompanionSceneTarget.Payload -> {
                        val mode = target.mode
                        val modePayload = payloadByMode[mode] ?: payload
                        when (mode) {
                            // **RETIRED unreachable arm — slice A3, 2026-10-02.** The now-playing hero card and its public wrapper are
                            // deleted. On the shelf page the payload is already projected to HOME (`companionShelfPayload`), and on the Activity page
                            // `sceneOverride` short-circuits this `when` first, so no target could reach this arm in production (the "card never drew"
                            // of gap b4). It exists because the `when` is exhaustive over the enum, not because anything selects it.
                            CompanionMode.NOW_PLAYING -> Unit
                            CompanionMode.HOME -> CompanionHome(
                                onSelectionNamed = { cursorName = it },
                                payload = lastHomePayload.value,
                                authoritativeHomeLanes = authoritativeHomeLanes,
                                cursorId = cursorId,
                                panelCovered = panelCovered || hostCovered,
                                onPlatformTap = onPlatformTap,
                                onAppTap = onAppTap,
                                childCursorByFolder = childCursorByFolder,
                                restoredPage = restoredPage,
                                restoredPageProvider = restoredPageProvider,
                                onReorder = onReorder,
                                onArm = onArm,
                                onChildCursor = onChildCursor,
                                onPageSettled = onPageSettled,
                                onBind = onBind,
                                onRenameFolder = onRenameFolder,
                                onCreateFolder = onCreateFolder,
                                onDiscardFolder = onDiscardFolder,
                                onUnbindChild = onUnbindChild,
                                monotonicUptimeMillis = monotonicUptimeMillis,
                                onDraggingChanged = {
                                    companionDragging = it
                                    onDraggingChanged(it)
                                },
                                showActivitySwipeHint = showActivitySwipeHint,
                                hostLatticeArmed = hostArmed,
                                workspaceNavigationEnabled = workspaceNavigationEnabled &&
                                    onWorkspaceDrag != null &&
                                    !quickLaunchExpanded,
                                onWorkspaceDrag = onWorkspaceDrag,
                                onWorkspaceDragEnd = onWorkspaceDragEnd,
                                onWorkspaceDragCancel = onWorkspaceDragCancel,
                                secondPointerBus = secondPointerBus,
                                onBookOpenChanged = {
                                    tomeOpen = it
                                    onBookOpenChanged(it)
                                },
                                closeBookRequest = closeBookRequest,
                            )
                            CompanionMode.IDLE -> IdleAmbient(modePayload)
                            // **RETIRED with the legend —.** The line beneath the
                            // lattice was the only thing that could ask for the card, so the selector
                            // above now sends every `RUNNING_APP` payload to `HOME` and no target can
                            // reach this arm. It exists because the `when` is exhaustive over the
                            // enum, not because anything selects it. The card it used to draw
                            // (`RunningAppCard`) was deleted with the 2026-09-11 copy review (F6):
                            // dead code carrying player-facing copy. `CompanionJobRankTest` pins
                            // that nothing composes it and that it does not return.
                            CompanionMode.RUNNING_APP -> Unit
                        }
                    }
                }
                }
                if (onWorkspaceDrag == null) {
                    // A host with no workspace to pan to: exactly the scene it always drew.
                    if (sceneOverride != null) sceneOverride() else renderLattice(sceneTarget)
                } else {
                    // B4: the panes. The lattice and the Activity page sit side by side in one clipped box and the host's pan translates them with the
                    // finger, spatially only: no alpha, no scale. Each pane has ONE call site, so a pane that becomes the shown one at a settle keeps its
                    // composition (no remount, no pop).
                    //
                    // D24: a pane is built the first time it is shown or the host pans, and STAYS built. The one that is neither shown nor panning is
                    // parked: kept, measured, not placed (so not drawn and not hit, and out of the semantics tree), and `LocalCompanionPaneLive` reads false
                    // in it, so nothing it owns collects a feed or waits on a timer. The old rule disposed it at every settle and composed it again at the
                    // next drag's first frame, which is the stall measured on the Activity swipes. At rest only the shown scene runs.
                    val latticeShown = sceneOverride == null
                    val built = remember { BooleanArray(2) }
                    if (sceneOverride != null || (scenePanActive && neighbourScene != null)) built[CompanionPaneActivity] = true
                    if (latticeShown || scenePanActive) built[CompanionPaneLattice] = true
                    val activityPane = sceneOverride ?: neighbourScene.takeIf { built[CompanionPaneActivity] }
                    val latticePane = built[CompanionPaneLattice]
                    val activityParked = companionPaneParked(shown = !latticeShown, panning = scenePanActive)
                    val latticeParked = companionPaneParked(shown = latticeShown, panning = scenePanActive)
                    // The container takes a stationary pointer-input node that handles nothing. Measured on Compose 1.7.3 (a plain Box with a draggable inside a
                    // layer that follows it): when the whole hit path sits inside the moving layer, the finger's moves stop arriving after the layer has moved a
                    // few dozen px under it (6 of 10 reached the draggable), and one stationary ancestor in the path makes every move arrive. The pane test pins it.
                    Box(Modifier.fillMaxSize().clipToBounds().pointerInput(Unit) { }) {
                        if (latticePane) {
                            Box(
                                Modifier.fillMaxSize().companionParkable(latticeParked).graphicsLayer {
                                    translationX = companionStripX(sceneStripX?.invoke(), latticeShown, size.width)
                                },
                            ) {
                                // The pane the host has not shown is silent: its receipts begin at the settle that shows it. A pane that leaves the
                                // shown role takes this local to NO_DISPLAY in the same composition that parks it, which is what writes its exit.
                                CompositionLocalProvider(
                                    LocalBottomUiDisplay provides if (latticeShown) LocalBottomUiDisplay.current else BottomUiReceiptFormat.NO_DISPLAY,
                                ) {
                                    CompanionPaneLiveScope(live = !latticeParked) {
                                        renderLattice(if (latticeShown) sceneTarget else CompanionSceneTarget.Payload(CompanionMode.HOME))
                                    }
                                }
                            }
                        }
                        if (activityPane != null) {
                            Box(
                                Modifier.fillMaxSize().companionParkable(activityParked).graphicsLayer {
                                    translationX = companionStripX(sceneStripX?.invoke(), latticeShown, size.width) - size.width
                                },
                            ) {
                                CompositionLocalProvider(
                                    LocalBottomUiDisplay provides if (latticeShown) BottomUiReceiptFormat.NO_DISPLAY else LocalBottomUiDisplay.current,
                                ) {
                                    CompanionPaneLiveScope(live = !activityParked) { activityPane() }
                                }
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                CompanionSceneTransition(
                    // Scene self-handoff regression: under an override the target's mode selects nothing (`renderScene` draws the
                    // override, and the parked lattice pane gets HOME regardless), so a payload change while the Activity page is shown is not a scene
                    // change. It used to be one: the Bottom's payload goes NOW_PLAYING -> HOME about 300 ms after a game ends, and that target change
                    // alone started a full page-tier handoff of the Activity page onto itself (the page dimmed to the stage over ExitMs, then arrived
                    // again over the lead and ShineMs). The target is held constant while the override is up; a swipe to the lattice still changes the
                    // identity and hands over with its glide.
                    targetState = if (sceneOverride != null) CompanionSceneTarget.Payload(CompanionMode.HOME) else requestedTarget,
                    transitionIdentity = sceneTransitionIdentity ?: (sceneOverride != null),
                    incomingGlideOffsetDp = sceneTransitionGlideOffsetDp,
                    snap = sceneTransitionSnap,
                    // B2: the lattice is the page everything returns to, so arriving at it is a return (at rest, on the return clock); a
                    // scene that is not the lattice (the Activity page, or a full-screen mode) arrives on the page tier.
                    returning = sceneOverride == null && (requestedTarget as? CompanionSceneTarget.Payload)?.mode == CompanionMode.HOME,
                    // **The blur belongs to the SCENE and to nothing else.**
                    //
                    // It sat on the enclosing Box until 2026-08-31 — which also enclosed the
                    // quick-launch layer, and therefore the PICKER the layer hosts. Opening the picker
                    // blurred the picker: its card, its header and every app label in it. A
                    // `graphicsLayer` `renderEffect` filters the layer's OWN content; it is not a
                    // backdrop filter, and core-ui states the consequence in one line
                    // (`MimirLiquidGlass.kt:81`): *"Applied to the SCENE, not the panel: blurring the
                    // panel would blur its own text."*
                    //
                    // It also made the rail blur behind the instruments panel, contradicting the claim
                    // in that commit's own message that the rail stays sharp. The drawer and the
                    // instruments panel were never affected — the host renders those OUTSIDE
                    // `CompanionSurface`, so blurring the whole scene beneath them is correct and
                    // unchanged.
                    //
                    // **Through core-ui's blur plan, not a raw blur.** A
                    // radius of 24 dp × `overlayBlur` spent the last ~40–50 ms of every ramp below
                    // `MimirGlass.BlurDownsampledMinRadiusPx`, where HWUI convolves at full resolution
                    // (20–45 ms a frame on the Bottom). Same 24 dp peak, same `overlayBlur` clock; below
                    // that minimum the plan holds it and cross-fades the blurred layer over the sharp one. Only one live blur runs under an open picker: the scene is a SNAPSHOT while the glass is at its peak (core-ui's one freeze rule), so a live media
                    // blur inside it (the Now Playing card's) is not re-rendered under the picker, prompt, drawer, dock or menu.
                    modifier = Modifier
                        .fillMaxSize()
                        .mimirGlassBackdropProgress({ CompanionTomeBackdropBlurDp.dp }, { overlayBlur }, snapshot = { true }),
                ) { target ->
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { renderScene(target) }
                        // §5b's UNDER-never-beside layout is retired with the only line it ever drew.
                        //  The running-app readout carried
                        // §4's 48 dp touch floor plus vertical padding, so for the whole time a
                        // foreign app ran, the HOME lattice was measured against that much less
                        // height than it gets when nothing is running.
                        //
                        // **Nothing stands in its place** — no filler child, no zero-height slot, no
                        // reserved minimum. This `Column` keeps ONE child, which is exactly the tree
                        // the no-app HOME frame was certified on (the line's `if` was false there),
                        // so the running-app frame now measures identically to it rather than
                        // approximately. The `Column` is kept for that reason and no other: collapsing
                        // it to a `Box` would be equivalent arithmetic but a different tree from the
                        // one on glass. This file has already paid once for retiring a view and
                        // leaving its height reserved; `CompanionJobRankTest` pins the absence.
                    }
                }
                if (quickLaunchVisible) {
                    CompanionQuickLaunchLayer(
                        onPickerOpenChanged = { opened ->
                            pickerOpen = opened
                            onPickerOpenChanged(opened)
                        },
                        instrumentsShown = instrumentsShown,
                        onToggleInstruments = onToggleInstruments,
                        slots = quickSlots,
                        apps = quickLaunchApps,
                        catalogLoaded = quickLaunchCatalogLoaded,
                        launchLocked = quickLaunchLocked,
                        launchMessage = quickLaunchMessage,
                        selectionLabel = cursorName,
                        onLaunch = onQuickLaunch,
                        onPinnedLaunch = onPinnedQuickLaunch,
                        onAssign = onQuickAssign,
                        onClear = onQuickClear,
                        quickLaunchChoice = quickLaunchChoice,
                        onQuickLaunchChoice = onQuickLaunchChoice,
                        onQuickLaunchChoiceDismiss = onQuickLaunchChoiceDismiss,
                        onQuickLaunchEdit = onQuickLaunchEdit,
                        railCovered = railCovered,
                        onQuickLaunchIgnored = onQuickLaunchIgnored,
                        // R6-P2-08 (finding 7, step 3): a native EdgeBack close for the picker, forwarded
                        // straight through from this surface's own param of the same shape.
                        closePickerRequest = closePickerRequest,
                        hideableDock = true,
                        dockExpanded = quickLaunchExpanded,
                        onDockExpandedChanged = {
                            quickLaunchExpanded = it
                            onDockExpandedChanged(it)
                        },
                        dockInteractionBlocked = companionDragging || panelCovered || hostCovered,
                        // The collapsed handle's hit zone takes the free strip under the lattice's last row (the hint's footer is in it while it shows), and only
                        // on the BARE lattice at rest: no scene override, the HOME target, no Tome open, nothing covering it, and the workspace strip at rest on it.
                        // Anywhere else the zone is the visible handle, as it was before the widening (the strip under an Activity wing, or a Tome's outside-dismiss
                        // strip, is not the lattice's free strip).
                        handleZoneWidened = sceneOverride == null &&
                            requestedTarget == CompanionSceneTarget.Payload(CompanionMode.HOME) &&
                            !tomeOpen && !panelCovered && !hostCovered && !scenePanActive,
                        hintFooterShown = onWorkspaceDrag != null && showActivitySwipeHint,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * How far the stage's signature gradient is pulled back to reveal the slightly blurred, low-cost theme scene. The former opaque floor
 * is now a tint over the scene, so the shelf keeps its house colour.
 */
private const val CompanionStageTintAlpha = 0.35f

@Composable
private fun CompanionStage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalMimirPalette.current
    // Built once per palette instead of on every composition. The SAME three signature stops as before, each
    // colour's alpha x CompanionStageTintAlpha, drawn ABOVE the scene backdrop instead of as the opaque floor.
    val stageTint = remember(palette) {
        Brush.linearGradient(
            listOf(
                palette.signatureTop.copy(alpha = palette.signatureTop.alpha * CompanionStageTintAlpha),
                palette.surfaceBase.copy(alpha = palette.surfaceBase.alpha * CompanionStageTintAlpha),
                palette.signatureBottom.copy(alpha = palette.signatureBottom.alpha * CompanionStageTintAlpha),
            ),
        )
    }
    // Radial gradients depend on draw-time size, so the brush itself must stay in the DrawScope;
    // only the palette-derived colour lists are memoized to avoid per-draw list allocation.
    val accentGlowColors = remember(palette) {
        listOf(palette.accentGlow.copy(alpha = palette.accentGlow.alpha * 0.36f), Color.Transparent)
    }
    val emberSoftColors = remember(palette) {
        listOf(palette.emberSoft.copy(alpha = palette.emberSoft.alpha * 0.22f), Color.Transparent)
    }
    Box(modifier = modifier) {
        // Bottom to top: the softened theme scene (one cached texture draw per frame -- see
        // MimirThemeSceneBackdrop for why not a live RenderEffect blur), the house tint, then the two glows.
        MimirThemeSceneBackdrop(Modifier.matchParentSize(), quietSceneLight = true, shineStars = false)
        // The two glows' brushes are built once per size (drawWithCache), not on every redraw of the stage (audit 2026-10-14, B-08's P3).
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val accentCenter = Offset(size.width * 0.78f, -size.height * 0.08f)
                val accentRadius = size.minDimension * 0.86f
                val accentGlow = Brush.radialGradient(accentGlowColors, center = accentCenter, radius = accentRadius)
                val emberCenter = Offset(size.width * 0.08f, size.height * 1.08f)
                val emberRadius = size.minDimension * 0.72f
                val emberGlow = Brush.radialGradient(emberSoftColors, center = emberCenter, radius = emberRadius)
                onDrawBehind {
                    drawRect(brush = stageTint)
                    drawCircle(brush = accentGlow, radius = accentRadius, center = accentCenter)
                    drawCircle(brush = emberGlow, radius = emberRadius, center = emberCenter)
                }
            },
        )
        content()
    }
}

/**
 * The words after "Mimir" sit next to the lattice title, rather than on the rune eyebrow's rule.
 * They use lowercase wording without a dash, the quiet `Support` style and the title baseline.
 * The Activity page's header takes no tagline.
 */
internal const val CompanionHeaderTagline = "the well of memory"

/** The shell body under the header and its gap: the lattice and its frame start at its top edge or below it. */
internal const val CompanionShellBodyTag = "companion-shell-body"

/** The shell's gap between the header, the body and whatever sits under the header: the header's clearance below ([MimirCompactHeaderClearanceDp]). */
internal const val CompanionShellGapDp = 8f

@Composable
private fun CompanionShell(
    statsLabel: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = LocalMimirPalette.current
    Column(
        modifier = modifier
            .fillMaxSize()
            // give the shelf a tad more height — trim the crown/base padding and the
            // inter-row gaps so the 3×3 grid grows ~14dp. The footer is a measured sibling of the weighted
            // grid, so it takes its own height first and never clips as the grid grows.
            .padding(start = CompanionShellInset, top = 10.dp, end = CompanionShellInset, bottom = CompanionShellBottomPaddingDp.dp),
        verticalArrangement = Arrangement.spacedBy(CompanionShellGapDp.dp),
    ) {
        // The shared core-ui header (slice H0b): Mimir, its tagline and the shelf's own stat line (handed over in the payload so the plural rules are Main's). The molten
        // rules of Surtr and Niflheim bleed past the gutter to the panel's edges, as Main's do past the shelf's.
        MimirCompactHeader(
            title = "Mimir",
            palette = palette,
            stat = statsLabel,
            tagline = CompanionHeaderTagline,
            // The Trackpad plate: always on the Bottom, at the header's trailing edge, when the host offers a trackpad.
            trailing = LocalBottomTrackpadOpen.current?.let { open -> { BottomTrackpadPlate(onOpen = open) } },
            ruleBleed = CompanionShellInset,
        )
        Box(Modifier.weight(1f).fillMaxWidth().testTag(CompanionShellBodyTag)) {
            content()
        }
        // **THE FOOTER ROW IS RETIRED — **
        //
        // It went in stages and every stage took something away until nothing was left worth a row.
        // The centre slot's switch moved to the rail, which is the shared mode strip. The right slot's
        // ᛗ became an player-positioned overlay. The left slot spent itself first on the word "HOME" —
        // telling the player, while they look at the shelf, that they are looking at the shelf — and
        // then on the selection name, which is the space now reclaimed.
        //
        // **The name is not lost, it moved to a seat that was already paid for.** The rail's 18 dp line
        // exists on every surface and is usually empty, and it already ranks what it shows: a launch
        // message first, the held seat's name second, and now the cursor's tile third. Lowest
        // precedence is right — a name you can also read off the highlighted tile should never cover a
        // launch that is failing.
        //
        // What the row cost was not the text but the ROW: its height came off the lattice, on the one
        // surface where 86 dp is the difference between a lattice and a list.
    }
}

/**
 * The Tome that is open now, by id, or null when none is. [requestedId] is the one the player opened; it stays open across any
 * change to the tile list that leaves its folder in place (its own children changing, a reorder, a merge into it), because its
 * identity is the folder's id and never the list. It is null (closed) when:
 *  - the folder is no longer in [tiles] (it was deleted), or
 *  - the folder is EMPTY and [wasFilled] (it had children the last time it was shown): its last child just left. That is what a
 *    changed tile list has always done to an emptied Tome, and the child is already on the shelf right after it.
 * A folder that is empty and was never shown filled (just bound, opened to be filled) stays open on its empty page.
 */
internal fun companionOpenTomeId(requestedId: String?, tiles: List<CompanionHomeTile>, wasFilled: Boolean): String? {
    if (requestedId == null) return null
    val folder = tiles.firstOrNull { it.id == requestedId } ?: return null
    if (folder.children.isEmpty() && wasFilled) return null
    return folder.id
}

/** Carries the surface's root watch's second-pointer signal to the lattice's pager drag, which lives deeper in the tree. */
internal class CompanionSecondPointerBus {
    /** Answers whether a pager drag is held (and has now been rolled back). */
    var listener: (() -> Boolean)? = null
}

/**
 * The Mimir 3DS Shelf: centered 3×3 PAGES of console tiles inside a
 * modern-Norse rail frame — the true 3DS page model. Same console line-art as the main shelf
 * (PlatformConsoleArt + the shared metallic brush), acronym labels, page position as runic diamond
 * studs. STATIC: never follows main-shelf focus. Tiles inert this slice; drag-reorder (S4) and the
 * Norse-book folders (S6) land per the spec.
 */
@Composable
private fun CompanionHome(
    payload: CompanionPayload,
    authoritativeHomeLanes: List<CompanionHomeLane>,
    /** Screen 2's own cursor. Never screen 1's focus — policy 2026-08-31. */
    cursorId: String?,
    /** The recents drawer is up, so the lattice is covered from OUTSIDE this composable. */
    panelCovered: Boolean,
    /** Names what the cursor is on, for the rail's line. Null when nothing is selected. */
    onSelectionNamed: (String?) -> Unit = {},
    onPlatformTap: (platformIdValue: String, label: String) -> Unit,
    /** Package id and the tile's own label; see the surface-level parameter for why the name travels. */
    onAppTap: (String, String) -> Unit,
    childCursorByFolder: Map<String, String>,
    restoredPage: Int,
    restoredPageProvider: (() -> Int)?,
    onReorder: (List<String>) -> Unit,
    onArm: (String) -> Unit,
    onChildCursor: (String, String) -> Unit,
    onPageSettled: (Int) -> Unit,
    onBind: (List<String>, String, String) -> Unit,
    onRenameFolder: (String, String) -> Unit,
    onCreateFolder: (List<String>) -> String,
    onDiscardFolder: (List<String>, String) -> Unit,
    onUnbindChild: (List<String>, String) -> Unit,
    monotonicUptimeMillis: () -> Long,
    /** Lifts to the surface so scene replacement can HOLD on HOME while a tile is in the air. */
    onDraggingChanged: (Boolean) -> Unit = {},
    /** Full-pane workspace navigation mode; null callback keeps legacy pager swiping for other hosts. */
    workspaceNavigationEnabled: Boolean = false,
    secondPointerBus: CompanionSecondPointerBus? = null,
    /** Whether the footer teaches the Activity swipe (B6): the host retires it for good at the first committed swipe, and the height it asked for goes back to the grid. */
    showActivitySwipeHint: Boolean = true,
    /** The host's own hold on the cursor ring's gleam; see the surface-level parameter. */
    hostLatticeArmed: () -> Boolean = MimirFocusAlwaysArmed,
    onWorkspaceDrag: ((deltaPx: Float) -> Float)? = null,
    onWorkspaceDragEnd: ((velocityPx: Float) -> Boolean)? = null,
    onWorkspaceDragCancel: (() -> Unit)? = null,
    /**
     * R6-P2-08: reports every open/close of this Home's own folder ("Tome"), so a host outside Compose
     * (the native CP03 edge-back strip) can tell a closable layer is up without owning Compose state.
     */
    onBookOpenChanged: (Boolean) -> Unit = {},
    /**
     * R6-P2-08: a new, distinct value asks the open folder (if any) to close — the same shape a native
     * request already uses elsewhere in this file ([CompanionQuickLaunchLayer]'s `generalPickerRequest`).
     * Null means nothing has asked yet.
     */
    closeBookRequest: Long? = null,
) {
    val palette = LocalMimirPalette.current
    // **This page's own arrival, which the cursor ring's gleam waits on**: an Animatable owned here, run on the scene's
    // own tier (`MimirMotion.SceneMs`, the clock the Bottom's scene crossfade and glide ride), snapped under reduced motion. It is the Bottom's;
    // the process-wide pad gate is never read or held. A host that wears a cover over the whole surface (the cold-start badge) holds the arrival
    // longer through its [hostLatticeArmed], so the one sweep is not spent beneath it.
    val hostArmed = hostLatticeArmed
    val arrival = remember { Animatable(0f) }
    val arrivalReduced = mimirReducedMotion()
    LaunchedEffect(arrivalReduced) {
        if (arrivalReduced) {
            arrival.snapTo(1f)
        } else {
            arrival.animateTo(1f, tween(MimirMotion.SceneMs, easing = MimirMotion.Easing))
        }
    }
    val latticeArmed: () -> Boolean = remember(hostArmed) { { hostArmed() && arrival.value >= 1f } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val debugLogging = remember(context) {
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    // *** The ring is screen 2's OWN
    // cursor — a tap on this panel, persisted in `CompanionOrderStore` — and never
    // `payload.selectedShelfId`, which the adapter derives from screen 1's focused key.
    //
    // Mirroring Main's focus would drive the second screen's animation from another screen's input: the cursor's ring restarts its one gleam whenever the cursor lands on a tile, so a mirrored
    // move re-triggered the sweep from the other screen's d-pad.
    // The panel's only animation was being driven by the panel the player was not looking at.
    val ringId = cursorId
    val tiles = remember(payload.homeLanes) { payload.homeLanes.flatMap { it.tiles } }
    val authoritativeTiles = remember(authoritativeHomeLanes) {
        authoritativeHomeLanes.flatMap { it.tiles }
    }
    // The order as the user sees it right now — the base every create/discard is expressed against.
    val visibleOrder = remember(tiles) { tiles.map { it.id } }

    // The opened tome. A tome opens INTO the rail; the grid behind it stays exactly where it was.
    //
    // Keyed on the folder's own id, NOT on the tile list: it used to be `remember(tiles)`, which reset on
    // EVERY republish, so the confirm's Move-to-shelf button (which republishes the Tome's changed children) closed the Tome under its own confirm and
    // defeated the return swap. The open Tome now survives a change to its own children, a reorder and a merge; it closes when it
    // no longer exists (deleted) or when its last child leaves it, which is what a changed tile list always did to a Tome that was
    // emptied. One that was OPENED empty (just bound, never filled) stays open on its empty page, as before. See [companionOpenTomeId].
    var openFolderRequest by remember { mutableStateOf<String?>(null) }
    val openTomeWasFilled = remember { BooleanArray(1) }
    val openFolderId = companionOpenTomeId(openFolderRequest, tiles, openTomeWasFilled[0])
    SideEffect {
        if (openFolderRequest != null && openFolderId == null) openFolderRequest = null
        openTomeWasFilled[0] = openFolderId != null && tiles.firstOrNull { it.id == openFolderId }?.children?.isNotEmpty() == true
    }
    var renamingFolderId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    // R6-P2-08 (finding 7, step 7/8): a distinct value asks CompanionFolderPage to close the layer IT can
    // see (the move-out confirm, else the Tome) — set only when this Home's own close request isn't
    // consumed by the rename keyboard below, so the two never both act on the same swipe.
    var pendingFolderClose by remember { mutableStateOf<Long?>(null) }
    // CE01: a stable-identity, always-current read of "is a book open", safe to read from the long-lived root
    // gesture lambda (which is set up once via pointerInput(Unit) and would otherwise capture a stale state).
    val bookIsOpen = rememberUpdatedState(openFolderId != null)
    // R6-P2-08: report this Home's own open/close to whatever sits outside Compose (native EdgeBack
    // admission) on every real transition, and once on the first composition too, so a host that mounts
    // after a folder was already opened is not left believing none is. Level-triggered off this flag
    // rather than the animated `reveal` value both ways: on close there is no separate "still closing"
    // window where the strip stays wrongly armed, and on open the strip arms a little ahead of the
    // reveal finishing — harmless, since closing is itself level-triggered the same way and a swipe that
    // lands mid-reveal just cancels it early, exactly as a same-instant tap-outside would.
    // The rename plate is a closable layer too: the host's edge-back dismisses it like an outside tap (the closeBookRequest effect below commits the Tome's
    // own label first). It counts only once it is SHOWING (its tile is in the payload), with or without an open Tome.
    val renamePlateShowing = renamingFolderId?.let { id -> tiles.any { it.id == id } } == true
    LaunchedEffect(openFolderId != null || renamePlateShowing) { onBookOpenChanged(openFolderId != null || renamePlateShowing) }
    BottomUiSurfaceReceipt(
        surface = BottomSurface.TOME_OPEN,
        visible = openFolderId != null,
        transition = if (openFolderId != null) "tome-open.enter" else "tome-open.exit",
    )
    val latticeControl = rememberBottomUiControl(BottomSurface.LATTICE)
    // R6-P2-08 review finding 2: the naive `openFolderId = null` this replaced skipped both the move-out
    // confirm (CompanionFolderPage's OWN close path dismisses that first) and the rename keyboard (a
    // separate overlay this Home draws above the page). The rename keyboard is checked first because it
    // draws on top; committing it here mirrors the outside tap's own commit exactly. Anything else is
    // forwarded to CompanionFolderPage via [pendingFolderClose] so ITS close `when` — move-out confirm
    // before the Tome itself — decides, the same one-layer-per-swipe order the outside tap gets.
    // R6-P2-08 re-review finding A/C: this Home leaves composition on any scene switch away from HOME
    // and is rebuilt fresh on return (the DisposableEffect below), so its `LaunchedEffect` below fires on
    // ITS OWN first composition too — with whatever `closeBookRequest` already is, stale or not. Without
    // this floor, a request already resolved before this particular mount would be resolved again.
    val initialCloseBookRequest = remember { closeBookRequest }
    // D24: a pane that is PARKED is no longer disposed, and disposal is what used to close an open book and drop a rename draft when the workspace left the
    // lattice (an automatic Activity entry can park it with a Tome open and the rename keyboard up). So parking does it: the book closes and the rename plate
    // is DISCARDED, never committed (close without saving), and the host is told no layer is open, so EdgeBack reaches only the pane
    // the player can see. Read from the pane's state inside the effect, so a pane that never parks (every other host) costs nothing.
    val paneLiveState = LocalMimirMotionLive.current
    val latestBookOpenChanged by rememberUpdatedState(onBookOpenChanged)
    LaunchedEffect(paneLiveState) {
        snapshotFlow { paneLiveState.value }.collect { live ->
            if (!live) {
                openFolderRequest = null
                renamingFolderId = null
                renameDraft = ""
                pendingFolderClose = null
                latestBookOpenChanged(false)
            }
        }
    }
    LaunchedEffect(closeBookRequest) {
        if (closeBookRequest == null || closeBookRequest == initialCloseBookRequest) return@LaunchedEffect
        // D24: a hidden pane takes no request: it must not commit a rename or close a book nobody can see.
        if (!paneLiveState.value) return@LaunchedEffect
        val renaming = renamingFolderId?.let { id -> tiles.firstOrNull { it.id == id } }
        if (renaming != null) {
            val name = renameDraft.ifBlank { renaming.label }
            onRenameFolder(renaming.id, name)
            renamingFolderId = null
            Log.i(
                CompanionDragLogTag,
                "rename-commit folder=${renaming.id} via=edge-back changed=${name != renaming.label}",
            )
            Log.i("MimirCompanionRuntime", "edge back closed layer=folder_rename")
        } else {
            pendingFolderClose = closeBookRequest
        }
    }
    // This Home leaves composition on any scene switch away from HOME (NOW_PLAYING, IDLE); nothing else
    // clears `openFolderId` at that point, so without this a stale `true` could keep the strip armed
    // for a folder no scene can any longer show or close.
    DisposableEffect(Unit) {
        onDispose { onBookOpenChanged(false) }
    }
    val latestWorkspaceNavigationEnabled = rememberUpdatedState(workspaceNavigationEnabled)
    val latestWorkspaceDrag = rememberUpdatedState(onWorkspaceDrag)
    val latestWorkspaceDragEnd = rememberUpdatedState(onWorkspaceDragEnd)
    val latestWorkspaceDragCancel = rememberUpdatedState(onWorkspaceDragCancel)
    // The long-lived pointer adapter resolves activation and frozen visuals from the latest payload.
    val liveTiles = rememberUpdatedState(tiles)

    val onTileTap: (CompanionHomeTile) -> Unit = { tile ->
        onArm(tile.id)
        latticeControl(
            when (tile.kind) {
                CompanionHomeTileKind.FOLDER -> "lattice.tile.folder"
                CompanionHomeTileKind.PLATFORM -> "lattice.tile.platform"
                CompanionHomeTileKind.ANDROID_APP -> "lattice.tile.app"
            },
            tile.id,
        )
        when (tile.kind) {
            CompanionHomeTileKind.FOLDER -> {
                openFolderRequest = tile.id
            }
            CompanionHomeTileKind.PLATFORM -> {
                onPlatformTap(tile.id, tile.label)
            }
            // I5: apps open on a SINGLE tap, like console tiles —
            // the two-beat "Open" arm pill is retired for apps (the companion is touch-only; scroll/reorder are
            // their own gestures, so there's no mis-launch-while-browsing risk the arm guarded against).
            CompanionHomeTileKind.ANDROID_APP -> {
                onAppTap(tile.id, tile.label)
            }
        }
    }
    val onBookChildTap: (CompanionHomeTile) -> Unit = { tile ->
        if (tile.kind != CompanionHomeTileKind.FOLDER) latticeControl("lattice.book.child", tile.id)
        when (tile.kind) {
            CompanionHomeTileKind.PLATFORM -> onPlatformTap(tile.id, tile.label)
            CompanionHomeTileKind.ANDROID_APP -> onAppTap(tile.id, tile.label)
            CompanionHomeTileKind.FOLDER -> Unit
        }
    }

    val modelVersion = remember { AtomicLong(0L) }
    val dragModel = remember(authoritativeTiles) {
        CompanionDragModel(
            version = modelVersion.incrementAndGet(),
            tiles = authoritativeTiles.map { tile ->
                CompanionDragTileSnapshot(
                    id = tile.id,
                    isBook = tile.kind == CompanionHomeTileKind.FOLDER,
                )
            },
        )
    }
    val machineState = remember { mutableStateOf(CompanionDragMachine.initial(dragModel)) }
    val renderedOrderState = remember { mutableStateOf(dragModel.order) }
    var frozenVisualTiles by remember { mutableStateOf<Map<String, CompanionHomeTile>>(emptyMap()) }
    var pageFlight by remember { mutableStateOf<CompanionDragEffect.AnimatePage?>(null) }
    var rollbackFlight by remember { mutableStateOf<CompanionDragEffect.Rollback?>(null) }
    var snapTarget by remember { mutableStateOf<Int?>(null) }
    var settlingDrag by remember { mutableStateOf<CompanionDragSettle?>(null) }
    val settleActive = rememberUpdatedState(settlingDrag != null)

    // The lattice's tiles have no tap node of their own (`onTap = null`): their press response is fed from the machine's own reading of the finger
    // (`companionTilePresses`): Down on a tile, Up when it activates, Cancel when it becomes a lift or a pan.
    val tilePressBus = remember { CompanionTilePressBus() }
    DisposableEffect(tilePressBus) { onDispose { tilePressBus.cancelAll() } }
    val latestOnTileTap = rememberUpdatedState(onTileTap)
    val latestOnArm = rememberUpdatedState(onArm)
    val latestOnReorder = rememberUpdatedState(onReorder)
    val latestOnBind = rememberUpdatedState(onBind)
    val dispatchDragEvent: (CompanionDragEvent) -> Unit = { event ->
        val before = machineState.value
        val transactionId = when (val phase = before.phase) {
            is CompanionDragPhase.Dragging -> phase.transaction.id
            is CompanionDragPhase.Turning -> phase.transaction.id
            is CompanionDragPhase.RollingBack -> phase.transaction.id
            else -> null
        }
        val reduction = CompanionDragStateMachine.reduce(before, event)
        val endedTransaction = when (val phase = before.phase) {
            is CompanionDragPhase.Dragging -> phase.transaction
            is CompanionDragPhase.Turning -> phase.transaction
            else -> null
        }
        val terminalEvent = event is CompanionDragEvent.Up || event is CompanionDragEvent.PageTurnFinished
        if (
            endedTransaction != null &&
            terminalEvent &&
            reduction.machine.phase is CompanionDragPhase.Idle &&
            reduction.machine.latestModel.tile(endedTransaction.draggedId) != null &&
            reduction.effects.none {
                it is CompanionDragEffect.CommitMerge || it is CompanionDragEffect.Rollback
            }
        ) {
            val targetSlot = reduction.machine.latestModel.order.indexOf(endedTransaction.draggedId)
            val tile = frozenVisualTiles[endedTransaction.draggedId]
                ?: liveTiles.value.firstOrNull { it.id == endedTransaction.draggedId }
            if (targetSlot >= 0 && tile != null) {
                val finalPoint = when (event) {
                    is CompanionDragEvent.Up -> event.point
                    is CompanionDragEvent.PageTurnFinished ->
                        (before.phase as? CompanionDragPhase.Turning)?.pendingUp?.point
                            ?: endedTransaction.lastPointer
                    else -> endedTransaction.lastPointer
                }
                settlingDrag = CompanionDragSettle(
                    transactionId = endedTransaction.id,
                    draggedId = endedTransaction.draggedId,
                    visual = DragVisual(
                        tile = tile,
                        topLeft = Offset(
                            finalPoint.x - endedTransaction.pickupOffset.x,
                            finalPoint.y - endedTransaction.pickupOffset.y,
                        ),
                        size = Size(
                            endedTransaction.sourceRect.width,
                            endedTransaction.sourceRect.height,
                        ),
                    ),
                    targetSlot = targetSlot,
                )
            }
        }
        machineState.value = reduction.machine
        tilePressBus.apply(companionTilePresses(before.phase, reduction.machine.phase, reduction.effects))
        reduction.effects.forEach { effect ->
            when (effect) {
                is CompanionDragEffect.ActivateTile -> {
                    liveTiles.value.firstOrNull { it.id == effect.tileId }?.let(latestOnTileTap.value)
                }
                is CompanionDragEffect.ArmDrag -> {
                    settlingDrag = null
                    Log.i(
                        CompanionDragLogTag,
                        "armed transaction=${effect.transactionId} dragged=${effect.draggedId}",
                    )
                    frozenVisualTiles = liveTiles.value.associateBy { it.id }
                        latestOnArm.value(effect.draggedId)
                }
                is CompanionDragEffect.ScheduleMerge -> Unit
                is CompanionDragEffect.ScheduleEdge -> Unit
                is CompanionDragEffect.AnimatePage -> pageFlight = effect
                is CompanionDragEffect.SnapPage -> snapTarget = effect.page
                is CompanionDragEffect.RenderOrder -> renderedOrderState.value = effect.order
                is CompanionDragEffect.CommitOrder -> {
                    Log.i(CompanionDragLogTag, "commit-order transaction=$transactionId order=${effect.order}")
                    latestOnReorder.value(effect.order)
                }
                is CompanionDragEffect.CommitMerge -> {
                    Log.i(
                        CompanionDragLogTag,
                        "commit-merge transaction=$transactionId dragged=${effect.draggedId} " +
                            "target=${effect.targetId} order=${effect.order}",
                    )
                    latestOnBind.value(effect.order, effect.draggedId, effect.targetId)
                }
                is CompanionDragEffect.Rollback -> {
                    Log.i(
                        CompanionDragLogTag,
                        "rollback transaction=${effect.transactionId} order=${effect.authoritativeOrder}",
                    )
                    rollbackFlight = effect
                }
                CompanionDragEffect.WakeMotion -> Unit
            }
        }
        // QA 2026-09-11 D13/D17: an armed drag released where it was lifted commits nothing, so `armed` was its last
        // line. One line per transaction, on the event that ends it -- never per move.
        companionDragEndedWithoutTerminalLine(before.phase, reduction.machine.phase, reduction.effects)?.let { ended ->
            Log.i(
                CompanionDragLogTag,
                "end transaction=${ended.id} dragged=${ended.draggedId} commit=none " +
                    "event=${event.javaClass.simpleName}",
            )
        }
    }
    val latestDispatch = rememberUpdatedState(dispatchDragEvent)

    LaunchedEffect(dragModel) {
        latestDispatch.value(CompanionDragEvent.ModelChanged(dragModel))
    }

    // D37: composition observes what it needs of the machine's phase and nothing else. `machineState` is rewritten by EVERY pointer event of a swipe (the pager owns the finger,
    // and each move refreshes the machine's drag geometry, which changes as the pages move), and reading the whole machine here recomposed this lattice once per move.
    val dragObservedState = remember(machineState) { derivedStateOf { companionObservedDrag(machineState.value.phase) } }
    val dragObserved = dragObservedState.value
    val draggingTransaction = dragObserved.dragging
    val mergeDwellTimer = draggingTransaction?.mergeCandidate?.let { candidate ->
        CompanionDwellTimer(
            transactionId = draggingTransaction.id,
            token = candidate.token,
            enteredAtMillis = candidate.enteredAtMillis,
            dwellMillis = CompanionDragStateMachine.MergeDwellMillis,
        )
    }
    val edgeDwellTimer = draggingTransaction?.edgeCandidate?.let { candidate ->
        CompanionDwellTimer(
            transactionId = draggingTransaction.id,
            token = candidate.token,
            enteredAtMillis = candidate.enteredAtMillis,
            dwellMillis = CompanionDragStateMachine.EdgeDwellMillis,
        )
    }
    val activeTransaction = dragObserved.active
    val liftedTransaction = dragObserved.lifted
    val draggingId = liftedTransaction?.draggedId
    LaunchedEffect(activeTransaction?.id) {
        onDraggingChanged(activeTransaction != null)
        if (activeTransaction == null) frozenVisualTiles = emptyMap()
    }

    // V9: the footer pill sets this (the id of the just-created book); the content lambda's effect snaps to
    // its page once it lands in the payload. Declared here so both the pill and the effect can see it.
    var pendingRevealId by remember { mutableStateOf<String?>(null) }
    // A just-created Tome waiting for its rename plate: its id and the page the player was on at the tap. The plate opens once the tile is in the payload and
    // nothing is lifted; it is dropped if the player moves on first (another create, a Tome opened, a page of their own).
    var pendingRename by remember { mutableStateOf<Pair<String, Int>?>(null) }
    // True only while the surface's own reveal scroll to a new Tome is running: the one page change that is not the player's.
    var revealScrolling by remember { mutableStateOf(false) }
    // Whether any finger is down on the lattice, watched in the Initial pass without consuming: the backstop below tells a held press from a stuck one.
    var fingerDown by remember { mutableStateOf(false) }
    // When the last pointer transition (a finger down, a finger up) happened, on the surface's own clock: the backstop's window starts no earlier than this.
    val lastPointerTransitionMs = remember { longArrayOf(Long.MIN_VALUE / 2) }
    // The "+ Tome" tap debounce: the clock (ms) of the last accepted tap, so a double-tap makes one Tome and a second deliberate tap makes the next.
    val lastCreateTomeTapMs = remember { longArrayOf(Long.MIN_VALUE / 2) }
    CompanionShell(
        // The shelf's own line, "N platforms · M games": the text Main's header wears, produced once by the payload adapter
        // (companionShelfStatsLabel) so the wording and the plural rules are Main's. The Bottom no longer counts its own tiles ("19 consoles").
        statsLabel = payload.statsLabel,
        // The footer's Create Tome pill is retired — the shelf carries it now, in the cell where a new
        // tome actually lands — and with the footer row itself gone, this name rides the rail's line.
    ) {
        // **Reported upward rather than rendered here — retired the footer row
        // this used to sit in.** It reads the SAME `ringId` the cursor marker does, deliberately: one
        // selection, named once and ringed once. If the HOME ring is ever re-keyed away from screen 1's
        // focus, the name follows it without a second decision. The rail owns the seat now, and ranks
        // this last of three.
        val cursorName = ringId?.let { id -> tiles.firstOrNull { it.id == id }?.label }
        LaunchedEffect(cursorName) { onSelectionNamed(cursorName) }
        if (tiles.isEmpty()) {
            CompanionHomeTeachingCard(palette)
            return@CompanionShell
        }
        // ── RP001 N3: LAUNCHER3 pattern ─────────────────────────────────────────
        // A normal HorizontalPager (its proven snap) + a ROOT-owned drag gesture so the pages can re-chunk to
        // show the insert gap without ever disposing the gesture — exactly how AOSP Launcher3's DragLayer sits
        // ABOVE the paged Workspace. The dragged tile is a pure root overlay (LiftedTile) with an alpha-0 source
        // cell, so nothing doubles. Cross-page: the finger over the VISIBLE page reorders THAT page's cells (the
        // pager snaps, so no scroll-shift math), and holding at an edge turns the page. No single-canvas, no
        // offset-across-the-gutter, no shift-hack.

        val order = renderedOrderState.value
        val chunkOrder = activeTransaction
            ?.originOrder
            ?.takeIf { it.size == order.size }
            ?: order
        val tileOf: (String) -> CompanionHomeTile? = { id ->
            frozenVisualTiles[id] ?: tiles.firstOrNull { it.id == id }
        }
        // Warm the lattice's first page of app icons (R3): the cache is filled off-main on first composition, so a page that
        // remounts, or an icon a tile lost and regained, paints on frame 0. Nothing here waits or blocks a frame.
        // Memoized on its inputs: the first-page walk (a tile search per id) must not repeat on every recomposition of the shelf.
        val firstPageIcons = remember(chunkOrder, tiles, frozenVisualTiles) {
            chunkOrder.take(SlotsPerPage).mapNotNull { id ->
                tileOf(id)?.takeIf { it.kind == CompanionHomeTileKind.ANDROID_APP }?.iconPath?.takeIf(String::isNotBlank)
            }
        }
        LaunchedEffect(firstPageIcons) {
            firstPageIcons.forEach { path -> CompanionCoverArtWarmer.prewarmIcon(path) }
        }
        // The pages are the tiles AND the "+ Tome" cell after them. The count is the IDLE one for a drag's whole life (the "+" only hides): the pager and the
        // edge auto-turn keep their pages, and a lifted tile can turn to the page that holds only the "+" and drop there, at the end (see companionLatticePageCount).
        val pageCount = companionLatticePageCount(order.size)
        val startPage = remember { restoredPageProvider?.invoke() ?: restoredPage }
        val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, pageCount - 1)) { pageCount }
        val latestPageCount = rememberUpdatedState(pageCount)
        val pageMotionScope = rememberCoroutineScope()
        // B3: every page move on this pager (the finger's settle, the cursor's reveal, the lifted tile's edge auto-turn) is one clock: OverlayMs
        // on the house easing, snapped under reduced motion. No default spring is left. Following the finger itself is never reduced.
        val pageSettleSpec = mimirPagerSettleSpec(bottomUiReducedMotion())
        val pageFlingPx = with(LocalDensity.current) { MimirPagerFlingVelocity.toPx() }
        val pageDragFrom = remember { intArrayOf(0) }
        // What this gesture has handed the host, and whether the host still holds the finger (see onWorkspaceDrag).
        val pageDragHostOwns = remember { booleanArrayOf(false) }
        val pageDragTotal = remember { floatArrayOf(0f) }
        val pageReceiptDisplay = LocalBottomUiDisplay.current
        // The finger moves the pager directly. At page 0's left edge it can take nothing more, and the remainder is offered to the host (the
        // workspace strip); while the host holds the finger it takes every delta first, and what it hands back (the strip returned to rest)
        // goes to the pager again. Never more than the pager can use, and no wrap: a left drag on the last page moves nothing.
        // A drag the system or a second finger took away is rolled back (below), and what is left of that gesture is ignored.
        val pageDragCancelled = remember { booleanArrayOf(false) }
        val pageDragActive = remember { booleanArrayOf(false) }
        val pageRollBack = {
            pageDragCancelled[0] = true
            if (pageDragHostOwns[0]) {
                pageDragHostOwns[0] = false
                latestWorkspaceDragCancel.value?.invoke()
            }
            // Back to the page the drag began on, on the same clock as every other settle, and writing no receipt: nothing was decided.
            pageMotionScope.launch { pagerState.animateScrollToPage(pageDragFrom[0], animationSpec = pageSettleSpec) }
            Unit
        }
        val latestPageRollBack = rememberUpdatedState(pageRollBack)
        // The surface's root watch routes a second finger anywhere to this drag.
        DisposableEffect(secondPointerBus) {
            secondPointerBus?.listener = {
                if (pageDragActive[0]) {
                    if (!pageDragCancelled[0]) latestPageRollBack.value()
                    true
                } else {
                    false
                }
            }
            onDispose { secondPointerBus?.listener = null }
        }
        val pageWatch = remember { MimirGestureWatch { if (pageDragActive[0] && !pageDragCancelled[0]) latestPageRollBack.value() } }
        val pageDragState = rememberDraggableState { delta ->
            if (pageDragCancelled[0]) return@rememberDraggableState
            pageDragTotal[0] += delta
            val drag = latestWorkspaceDrag.value
            val hostHeld = pageDragHostOwns[0]
            var rest = delta
            if (drag != null && hostHeld) {
                rest = drag(rest)
                if (rest == delta) pageDragHostOwns[0] = false
            }
            if (rest != 0f) {
                val pagerConsumed = pagerState.dispatchRawDelta(-rest)
                val left = rest + pagerConsumed
                // The host handed the finger back (the strip returned through its rest) and the pager took the rest of it: the pager owns it now.
                if (hostHeld && rest != delta && pagerConsumed != 0f) pageDragHostOwns[0] = false
                if (drag != null && left > 0f && pagerState.currentPage == 0) {
                    val unused = drag(left)
                    if (unused < left) pageDragHostOwns[0] = true
                }
            }
        }
        val currentPage = pagerState.currentPage
        CompanionRecompositionSite("lattice")
        LaunchedEffect(currentPage, activeTransaction?.id) {
            if (activeTransaction == null) onPageSettled(currentPage)
        }

        var rootSize by remember { mutableStateOf(IntSize.Zero) }
        val layoutVersion = remember { AtomicLong(0L) }
        // Order-only model updates do not move the measured Boxes. Keep their geometry cache alive so
        // the next drag can still resolve its center-to-center target; modelVersion remains in the
        // provider for stale-event rejection without forcing every slot to re-measure.
        val layoutGeneration = remember(pageCount, rootSize) {
            layoutVersion.incrementAndGet()
        }
        val slotRects = remember(layoutGeneration) { HashMap<Int, Rect>() }
        val movableTileContents = remember {
            mutableMapOf<String, @Composable (CompanionGridTileContent) -> Unit>()
        }
        val tileIds = if (chunkOrder === order) order.toSet() else (order + chunkOrder).toSet()
        if (movableTileContents.keys.toSet() != tileIds) {
            movableTileContents.keys.retainAll(tileIds)
            tileIds.forEach { id ->
                movableTileContents.getOrPut(id) {
                    movableContentOf { content: CompanionGridTileContent ->
                        val slide by animateOffsetAsState(
                            targetValue = content.restOffset,
                            animationSpec = if (content.dragging) {
                                tween(CompanionDragSettleMillis, easing = MimirMotion.Easing)
                            } else {
                                snap()
                            },
                            label = "tile-slide",
                        )
                        val effectiveSlide = if (content.dragging) slide else Offset.Zero
                        Box(
                            Modifier
                                .fillMaxSize()
                                .then(
                                    if (content.stateTag != null) {
                                        Modifier.testTag(content.stateTag)
                                    } else {
                                        Modifier
                                    },
                                )
                                .graphicsLayer {
                                    translationX = effectiveSlide.x
                                    translationY = effectiveSlide.y
                                },
                        ) {
                            CompanionHomeGridTile(
                                tile = content.tile,
                                cursor = content.cursor,
                                covered = content.covered,
                                ghost = content.ghost,
                                binding = content.binding,
                                isArming = content.isArming,
                                armingProgress = content.armingProgress,
                                decode = content.decode,
                                onTap = null,
                                pressSource = tilePressBus.sourceFor(content.tile.id),
                                testTag = companionTileTag(content.tile.id),
                            )
                        }
                    }
                }
            }
        }
        val settleTargetRect = settlingDrag?.let {
            companionDragSettleTarget(
                targetSlot = it.targetSlot,
                currentPage = currentPage,
                slotRects = slotRects,
            )
        }
        val settledDrag = settlingDrag?.takeIf { settleTargetRect != null }
        LaunchedEffect(settlingDrag?.transactionId, settleTargetRect) {
            if (settlingDrag != null && settleTargetRect == null) settlingDrag = null
        }
        val ghostedId = draggingId ?: settledDrag?.draggedId
        var railBounds by remember { mutableStateOf(Rect.Zero) }
        var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
        val latticeDensity = LocalDensity.current
        val geometryProvider = rememberUpdatedState<() -> CompanionDragGeometry> {
            val slotDragRects = slotRects.mapValues { (_, rect) -> rect.toDragRect() }
            CompanionDragGeometry(
                generation = layoutGeneration,
                modelVersion = dragModel.version,
                pageCount = pageCount,
                settledPage = pagerState.currentPage.coerceIn(0, pageCount - 1),
                railBounds = railBounds.toDragRect(),
                slotRects = slotDragRects,
                // The merge hotspot follows the visible face: the cell's own solve, placed in each slot.
                faceRects = slotDragRects.mapValues { (_, rect) ->
                    solveCompanionLatticeCell(
                        rect.width / latticeDensity.density,
                        rect.height / latticeDensity.density,
                        latticeDensity.fontScale,
                    ).faceRectIn(rect, latticeDensity.density)
                },
            )
        }

        val mergeCandidate = liftedTransaction?.mergeCandidate
        val hoverFolderId = mergeCandidate?.targetId
        val bindTargetId = mergeCandidate?.takeIf { it.armed }?.targetId
        val armProgress = animateFloatAsState(
            targetValue = if (hoverFolderId != null) 1f else 0f,
            // The arm is the merge dwell's TIMER fill: linear over MergeDwellMillis, a timer's progress and not a transition (W2L7 review, chooser
            // "keep"); the disarm is a motion on the house clock (Q6).
            animationSpec = tween(
                if (hoverFolderId != null) CompanionDragStateMachine.MergeDwellMillis.toInt() else MimirMotion.FocusMs,
                easing = if (hoverFolderId != null) LinearEasing else MimirMotion.Easing,
            ),
            label = "bind-arm",
        )

        val liftedVisual = liftedTransaction?.let { transaction ->
            frozenVisualTiles[transaction.draggedId]?.let { tile ->
                DragVisual(
                    tile = tile,
                    topLeft = Offset(
                        transaction.lastPointer.x - transaction.pickupOffset.x,
                        transaction.lastPointer.y - transaction.pickupOffset.y,
                    ),
                    size = Size(transaction.sourceRect.width, transaction.sourceRect.height),
                )
            }
        }

        LaunchedEffect(layoutGeneration) {
            withFrameNanos {}
            latestDispatch.value(CompanionDragEvent.LayoutChanged(geometryProvider.value()))
        }

        LaunchedEffect(mergeDwellTimer) {
            val timer = mergeDwellTimer ?: return@LaunchedEffect
            delay(
                remainingCompanionDwellDelayMillis(
                    enteredAtMillis = timer.enteredAtMillis,
                    dwellMillis = timer.dwellMillis,
                    currentUptimeMillis = monotonicUptimeMillis(),
                ),
            )
            latestDispatch.value(
                CompanionDragEvent.MergeDwellElapsed(
                    transactionId = timer.transactionId,
                    mergeToken = timer.token,
                    atMillis = timer.deadlineMillis,
                ),
            )
        }

        LaunchedEffect(edgeDwellTimer) {
            val timer = edgeDwellTimer ?: return@LaunchedEffect
            delay(
                remainingCompanionDwellDelayMillis(
                    enteredAtMillis = timer.enteredAtMillis,
                    dwellMillis = timer.dwellMillis,
                    currentUptimeMillis = monotonicUptimeMillis(),
                ),
            )
            latestDispatch.value(
                CompanionDragEvent.EdgeDwellElapsed(
                    transactionId = timer.transactionId,
                    edgeToken = timer.token,
                    atMillis = timer.deadlineMillis,
                ),
            )
        }

        LaunchedEffect(pageFlight) {
            val flight = pageFlight ?: return@LaunchedEffect
            latestDispatch.value(
                CompanionDragEvent.PageTurnStarted(flight.transactionId, flight.turnToken),
            )
            val completed = withTimeoutOrNull(1_200L) {
                pagerState.animateScrollToPage(flight.targetPage, animationSpec = pageSettleSpec)
                withFrameNanos {}
                true
            } == true
            val settledGeometry = geometryProvider.value()
            latestDispatch.value(
                CompanionDragEvent.PageTurnFinished(
                    transactionId = flight.transactionId,
                    turnToken = flight.turnToken,
                    targetPage = flight.targetPage,
                    succeeded = completed && pagerState.currentPage == flight.targetPage,
                    atMillis = android.os.SystemClock.uptimeMillis(),
                    geometry = settledGeometry,
                ),
            )
            if (pageFlight == flight) pageFlight = null
        }

        LaunchedEffect(rollbackFlight, snapTarget) {
            val rollback = rollbackFlight ?: return@LaunchedEffect
            val page = snapTarget ?: return@LaunchedEffect
            pagerState.scrollToPage(page.coerceIn(0, pageCount - 1))
            withFrameNanos {}
            latestDispatch.value(
                CompanionDragEvent.RollbackFinished(
                    transactionId = rollback.transactionId,
                    rollbackToken = rollback.rollbackToken,
                ),
            )
            if (rollbackFlight == rollback) rollbackFlight = null
            if (snapTarget == page) snapTarget = null
        }

        // V9 "New book" reveal — turn to the appended tome once it lands in the payload.
        LaunchedEffect(pendingRevealId, order) {
            val id = pendingRevealId ?: return@LaunchedEffect
            val idx = order.indexOf(id)
            if (idx < 0) return@LaunchedEffect
            revealScrolling = true
            try {
                pagerState.animateScrollToPage((idx / SlotsPerPage).coerceIn(0, pageCount - 1), animationSpec = pageSettleSpec)
            } finally {
                revealScrolling = false
            }
            pendingRevealId = null
            // The plate's baseline is where the reveal itself ended, so only a turn of the player's own can differ from it afterwards.
            pendingRename = pendingRename?.takeIf { it.first == id }?.let { it.first to pagerState.currentPage }
        }

        // The new Tome's rename plate : see [companionPendingRenameVerdict]. It never opens over a press, a lift or a pager drag (a reorder behind
        // the keyboard, or a long press that lifts under it): it waits for the machine to be idle and opens once, for that Tome.
        val dragIdle = dragObserved.idle
        // One loop for the whole pending period, keyed only on the pending plate's id (see [companionRunPendingRenameBackstop]).
        LaunchedEffect(pendingRename?.first) {
            companionRunPendingRenameBackstop(
                pending = { pendingRename != null },
                stalled = { companionPendingRenameStalled(machineState.value.phase is CompanionDragPhase.Idle, fingerDown) },
                nowMs = monotonicUptimeMillis,
                lastTransitionMs = { lastPointerTransitionMs[0] },
                windowMs = CompanionPendingRenameStallMs,
                onGiveUp = { pendingRename = null },
            )
        }
        LaunchedEffect(pendingRename, tiles, dragIdle, openFolderId, pagerState.currentPage, revealScrolling) {
            val (id, baseline) = pendingRename ?: return@LaunchedEffect
            when (
                companionPendingRenameVerdict(
                    tomeOpen = openFolderId != null,
                    plateUp = renamingFolderId != null,
                    page = pagerState.currentPage,
                    baselinePage = baseline,
                    revealScrolling = revealScrolling,
                    landed = tiles.any { it.id == id },
                    dragIdle = dragIdle,
                )
            ) {
                CompanionPendingRenameVerdict.DROP -> pendingRename = null
                CompanionPendingRenameVerdict.WAIT -> Unit
                CompanionPendingRenameVerdict.OPEN -> {
                    renameDraft = ""
                    renamingFolderId = id
                    pendingRename = null
                }
            }
        }

        val renamingFolder = renamingFolderId?.let { id -> tiles.firstOrNull { it.id == id } }
        // RC-42 (audit 2026-10-14, B-23): the glass is keyed on the rename SESSION, not on the folder being renamed. Keyed on the folder, a close
        // changed the identity (the folder -> none), which is a fresh progress at 0 (rememberMimirGlassProgress's rule for a new identity): the
        // plate and the blur cut on the frame the rename closed instead of leaving on OverlayMs. The session's epoch moves when a rename opens
        // and stays through its close, so the close plays out on the progress the open ran up.
        val renameSession = remember { arrayOf<Any?>(null, 0) }
        if (renamingFolder != null && renameSession[0] != renamingFolder.id) {
            renameSession[0] = renamingFolder.id
            renameSession[1] = (renameSession[1] as Int) + 1
        } else if (renamingFolder == null) {
            renameSession[0] = null
        }
        val renameSessionEpoch = renameSession[1] as Int
        // The rename's glass (RC-42; W2L7 review #3): held through the keyboard plate's dissolve before it clears , the hold and the
        // progress both keyed on the session, so a second rename opened during a close begins fresh. The plate rides its own OverlayMs clock below.
        val renameGlass = rememberMimirHeldGlassProgress(visible = renamingFolder != null, identity = renameSessionEpoch)
        // Derived from the progress the session runs on: the scrim's composition reads this, never the progress itself.
        val renameGlassUp by remember(renameSessionEpoch) { derivedStateOf { renameGlass() > 0f } }
        // The keyboard plate's own clock: in with the glass, out on OverlayMs while the glass still holds, so the plate dissolves fully over a
        // steady blur and the lattice sharpens only once it is gone. Read in the layer and in the derived gate, never in composition.
        val renamePlate by animateFloatAsState(
            targetValue = if (renamingFolder != null) 1f else 0f,
            animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
            label = "rename-plate",
        )
        val renamePlateShowing by remember { derivedStateOf { renamePlate > 0.001f } }
        val latestRenamingFolder = rememberUpdatedState(renamingFolder)
        val latestRenameDraft = rememberUpdatedState(renameDraft)
        Box(
            Modifier
                .fillMaxSize()
                .testTag(CompanionDragRootTag)
                .onSizeChanged { rootSize = it }
                .onGloballyPositioned { overlayOrigin = it.positionInRoot() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        try {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            fingerDown = true
                            lastPointerTransitionMs[0] = monotonicUptimeMillis()
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                            } while (event.changes.any { it.pressed })
                        } finally {
                            fingerDown = false
                            lastPointerTransitionMs[0] = monotonicUptimeMillis()
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectCompanionShelfGesture(
                        canStart = { !bookIsOpen.value && !settleActive.value },
                        geometry = { geometryProvider.value() },
                        phase = { machineState.value.phase },
                        toRoot = { local ->
                            DragPoint(local.x + overlayOrigin.x, local.y + overlayOrigin.y)
                        },
                        uptimeMillis = monotonicUptimeMillis,
                        dispatch = { event -> latestDispatch.value(event) },
                    )
                }
                .mimirGlassBackdropProgress { renameGlass() },
        ) {
            // Hoisted above the lattice on 2026-08-31 so the BACKDROP can read the same clock the page
            // rides. It used to be declared after the pager, which is why the scrim could only ever be
            // a flat fill: nothing behind it knew the tome was opening.
            val openFolder = openFolderId?.let { fid -> tiles.firstOrNull { it.id == fid } }
            val bookOpen = openFolder != null
            // B1: the Tome opens on Main's Folio clock and leaves on the house leave. A COLD open waits the 130 ms lead at alpha 0 then arrives
            // over 540 ms (670 ms from the tap, [mimirOverlayArriveSpec]); a close is 180 ms ([mimirOverlayLeaveSpec]). A Tome opened again
            // while it is still closing carries on from where it stands on the quick return clock, with no lead (a lead there would freeze it
            // part-visible). The old curve (300 ms up, 190 ms down, a scale from 0.90) is gone; Main's Folio does not scale either.
            val revealReducedMotion = remember(bookOpen) { bottomUiReducedMotion() }
            val revealAtRest = remember { booleanArrayOf(true) }
            val reveal by animateFloatAsState(
                targetValue = if (bookOpen) 1f else 0f,
                animationSpec = when {
                    !bookOpen -> mimirOverlayLeaveSpec()
                    revealAtRest[0] -> mimirOverlayArriveSpec(revealReducedMotion)
                    else -> tween(MimirPageMotion.ReturnMs, easing = MimirMotion.Easing)
                },
                label = "book-open",
            )
            // Noted from the frame clock, never in composition: reading `reveal` here recomposed this scope on every frame of the Tome's rise and fall.
            LaunchedEffect(Unit) { snapshotFlow { reveal <= 0.001f }.collect { revealAtRest[0] = it } }
            // RC-03 (audit 2026-10-14, B-20/B-21): the lattice's blur under the Tome has its own clock, split from the page's `reveal`. Soften first:
            // the blur opens on OverlayMs from the tap, while the page waits its lead and arrives on `reveal`; on close the blur holds through the
            // page's 180 ms leave and clears only after it (rememberMimirHeldGlassProgress), so the lattice never sharpens under a Tome still half-visible.
            val tomeGlass = rememberMimirHeldGlassProgress(visible = bookOpen)
            // The page's admission and shield, derived: `reveal` is otherwise read only in draw.
            val revealShowing by remember { derivedStateOf { reveal > 0.001f } }
            BottomUiFloatTransitionReceipt(
                name = if (bookOpen) "tome-open.enter" else "tome-open.exit",
                target = if (bookOpen) 1f else 0f,
                value = { reveal },
            )
            // The cursor ring's arming is this page's own arrival (see [LocalCompanionLatticeArmed]); a Tome inside it narrows that to its own reveal.
            CompositionLocalProvider(LocalCompanionLatticeArmed provides latticeArmed) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .norseRail(palette)
                            .onGloballyPositioned { railBounds = it.boundsInRoot() },
                    ) {
                        // **Glassdrop — policy 2026-08-31.** The lattice blurs behind an opening
                        // tome, ramped by the SAME `reveal` the page rides: one clock, no second duration
                        // to drift from the first, and no RenderEffect at rest (the blur plan draws it sharp).
                        // Screen 2's only overlay had been drawing the flat scrim screen 1 retired.
                        //
                        // **On the PAGER, not on the Box that holds it.** The first attempt put it on the
                        // shared parent — which also contains the tome overlay — and blurred the page along
                        // with its own backdrop. Caught on the device, not in review: it compiled, the
                        // lattice softened exactly as intended, and the tome quietly vanished into it.
                        //
                        // Costed on THIS panel rather than inherited: M-7 proved `BlurEffect` works inside
                        // a `Presentation` on display 4 at all, and M-8 found no frame cost above
                        // run-to-run noise over a populated lattice at 60 Hz. The cursor ring is still at rest (its one sweep is cancelled by a cover, never run behind it, and does not
                        // replay when it leaves), so this is not re-filtering behind a live ticker —
                        // that stillness is what makes it affordable rather than merely possible.
                        // The finger's switch. A cover, an open Tome or a lifted tile that arrives while the host holds the finger (the strip is off its
                        // rest) is a cancel: the host settles back to where the drag began. A display that goes away mid-drag is the same.
                        val pageDragEnabled = (onWorkspaceDrag == null || workspaceNavigationEnabled) &&
                            !panelCovered && !bookOpen && settlingDrag == null && activeTransaction == null && settledDrag == null
                        LaunchedEffect(pageDragEnabled) {
                            if (!pageDragEnabled && pageDragHostOwns[0]) {
                                pageDragHostOwns[0] = false
                                latestWorkspaceDragCancel.value?.invoke()
                            }
                        }
                        DisposableEffect(Unit) {
                            onDispose {
                                if (pageDragHostOwns[0]) {
                                    pageDragHostOwns[0] = false
                                    latestWorkspaceDragCancel.value?.invoke()
                                }
                            }
                        }
                        HorizontalPager(
                            state = pagerState,
                            // The pager follows the finger when nothing is lifted and stands down during a lift (the edge auto-turn drives it then).
                            // Keep every page composed during a lift so a target page's cells are measured the moment it turns; idle prefetches the
                            // neighbour only.
                            // The draggable below owns the finger (B3, onboarding's own construction), so the pager's built-in scroll is off.
                            userScrollEnabled = false,
                            beyondViewportPageCount = if (liftedTransaction != null) {
                                (pageCount - 1).coerceAtLeast(1)
                            } else {
                                1
                            },
                            // Same 24 dp peak on the same `reveal` clock, through core-ui's blur plan: the tail of
                            // the open and of the close never runs a small-radius, full-resolution blur (§4.4c).
                            modifier = Modifier
                                .fillMaxSize()
                                .mimirGestureWatch(pageWatch)
                                .draggable(
                                    state = pageDragState,
                                    orientation = Orientation.Horizontal,
                                    // The old detector's guards, as the draggable's own switch: a host that pans the workspace may have it covered
                                    // (workspaceNavigationEnabled), a Tome open or a tile's settle in flight stand the pager down, and a lifted
                                    // tile or the settle after one owns the finger (the edge auto-turn drives the pager then).
                                    enabled = pageDragEnabled,
                                    onDragStarted = {
                                        pagerState.stopScroll()
                                        pageDragFrom[0] = pagerState.currentPage
                                        pageDragHostOwns[0] = false
                                        pageDragTotal[0] = 0f
                                        pageDragCancelled[0] = false
                                        pageDragActive[0] = true
                                    },
                                    onDragStopped = { velocity ->
                                        pageDragActive[0] = false
                                        // A gesture a second finger already rolled back has nothing left to decide. One the system cancelled (a stop with no release
                                        // seen before it) or that a second finger touched is rolled back now: the strip settles to where it began and the pager
                                        // lands on the start page, with no control receipt and no `lattice.page` or `workspace.settle` one. Only a lift decides.
                                        val rolledBack = pageDragCancelled[0]
                                        if (!rolledBack && pageWatch.takenAway) latestPageRollBack.value()
                                        if (rolledBack || pageWatch.takenAway) {
                                            pageDragCancelled[0] = false
                                            return@draggable
                                        }
                                        val hostOwned = pageDragHostOwns[0]
                                        pageDragHostOwns[0] = false
                                        val total = pageDragTotal[0]
                                        val settledPage = pagerState.currentPage
                                        // The host owns the release while it still holds some of the finger (the strip is off its rest).
                                        val handled = hostOwned && latestWorkspaceDragEnd.value?.invoke(velocity) == true
                                        // No wrap: the last page stops at its edge. The target is one page from where the drag began at most.
                                        val targetPage = mimirPagerSettlePage(pageDragFrom[0], settledPage, velocity, pageFlingPx)
                                            .coerceIn(0, latestPageCount.value.coerceAtLeast(1) - 1)
                                        if (debugLogging) {
                                            Log.d(
                                                CompanionWorkspaceGestureLogTag,
                                                "horizontal-swipe delta=${total.roundToInt()} " +
                                                    "currentPage=$settledPage " +
                                                    "target=${if (handled) "Activity" else targetPage} " +
                                                    "action=${if (handled) "activity" else if (targetPage != settledPage) "page" else "none"}",
                                            )
                                        }
                                        if (!handled) {
                                            // On the composable's own scope, not the gesture's: a switch that flips `enabled` mid-settle must not
                                            // cancel it and leave the pager between two pages.
                                            pageMotionScope.launch {
                                                bottomUiTransition("lattice.page", pageReceiptDisplay) {
                                                    pagerState.animateScrollToPage(targetPage, animationSpec = pageSettleSpec)
                                                }
                                            }
                                        }
                                    },
                                )
                                .mimirGlassBackdropProgress(CompanionTomeBackdropBlurDp.dp) { tomeGlass() },
                        ) { page ->
                            // CR-15: nine cells per page, always. Icon decode WINDOWED to the visible ±1 page (V12).
                            val decode = page in (currentPage - 1)..(currentPage + 1)
                            // The rows touch: each cell carries its own label band and its own clear below it
                            // (solveCompanionLatticeCell), so the bottom row needs no special case and the rail's
                            // brackets stay clear of the last names. Only the columns keep the gap.
                            Column(Modifier.fillMaxSize().padding(RailInset)) {
                                repeat(GridRows) { rowIndex ->
                                    Row(
                                        Modifier.weight(1f).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(CellGap),
                                    ) {
                                        repeat(GridColumns) { colIndex ->
                                            val globalSlot = page * SlotsPerPage + rowIndex * GridColumns + colIndex
                                            // Home tile = the FROZEN chunk (drag) / live order (idle). The cell keeps
                                            // this tile for the drag's life; the reorder is expressed as a translation.
                                            val homeId = chunkOrder.getOrNull(globalSlot)
                                            val tile = homeId?.let(tileOf)
                                            Box(
                                                Modifier
                                                    .weight(1f)
                                                    .fillMaxHeight()
                                                    .testTag(companionSlotTag(globalSlot))
                                                    .onGloballyPositioned { slotRects[globalSlot] = it.boundsInRoot() },
                                            ) {
                                                if (tile == null) {
                                                    // **Create Tome lives in the shelf, in the last row after the last item, rather than on
                                                    // a separate line.** The slot
                                                    // straight after the last tile is already empty, so the
                                                    // affordance costs no cell and no footer line, and it
                                                    // sits exactly where a new tome would land.
                                                    //
                                                    // Hidden while a tile is in the air: a drag is already
                                                    // negotiating for these cells, and a target that could
                                                    // appear underneath a moving finger is a target that
                                                    // gets hit by accident.
                                                    if (globalSlot == chunkOrder.size) {
                                                        CompanionCreateTomeTile(
                                                            visible = draggingId == null,
                                                            onCreate = {
                                                                // The surface's own monotonic clock, so a test can stand it on the paused frame clock.
                                                                val nowMs = monotonicUptimeMillis()
                                                                if (nowMs - lastCreateTomeTapMs[0] >= CompanionCreateTomeDebounceMs) {
                                                                    lastCreateTomeTapMs[0] = nowMs
                                                                    val created = onCreateFolder(visibleOrder)
                                                                    pendingRevealId = created
                                                                    // a new Tome is named at once, as on Main. The rename plate opens on it (an empty draft: the plate
                                                                    // types the name from nothing, and dismissing it commits nothing, so the Tome keeps "Book"). It opens as soon as the
                                                                    // new tile is in the payload and nothing is lifted (see the effect after the reveal).
                                                                    pendingRename = if (created.isNotBlank()) created to pagerState.currentPage else null
                                                                }
                                                            },
                                                        )
                                                    }
                                                    return@Box
                                                }
                                                // Smooth slide: glide the tile from its
                                                // frozen home cell to its live slot in `order`, but only WITHIN its own
                                                // page — a display slot on another page (the rare cross-page cascade)
                                                // stays home and snaps on drop, never clipping across the pager gutter.
                                                val displaySlot =
                                                    if (draggingId != null) order.indexOf(homeId).takeIf { it >= 0 } ?: globalSlot
                                                    else globalSlot
                                                val restOffset =
                                                    if (draggingId != null && displaySlot != globalSlot &&
                                                        displaySlot / SlotsPerPage == globalSlot / SlotsPerPage
                                                    ) {
                                                        val from = slotRects[globalSlot]?.center
                                                        val to = slotRects[displaySlot]?.center
                                                        if (from != null && to != null) to - from else Offset.Zero
                                                    } else Offset.Zero
                                                val stateTag = when {
                                                    tile.id == bindTargetId -> companionMergeArmedTag(tile.id)
                                                    tile.id == hoverFolderId -> companionMergeHoverTag(tile.id)
                                                    else -> null
                                                }
                                                // Keep the key at the cell boundary for the existing test and identity
                                                // semantics; movableContentOf carries the tile's remembered subtree,
                                                // including its slide animator, when this invocation changes cells.
                                                key(homeId) {
                                                    movableTileContents.getValue(homeId)(
                                                        CompanionGridTileContent(
                                                            tile = tile,
                                                            cursor = tile.id == ringId,
                                                            covered = openFolderId != null || panelCovered,
                                                            ghost = tile.id == ghostedId,
                                                            binding = tile.id == bindTargetId,
                                                            isArming = tile.id == hoverFolderId,
                                                            armingProgress = {
                                                                if (tile.id == hoverFolderId) armProgress.value else 0f
                                                            },
                                                            restOffset = restOffset,
                                                            dragging = draggingId != null,
                                                            stateTag = stateTag,
                                                            decode = decode,
                                                        ),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // CR-13: the tome doesn't hard-cut in — it OPENS. A calm rise + fade, as if the small cover grew into its
                        // page. No page-flip theatrics. Reveal runs 0→1 on open and 1→0
                        // on close, so the SAME motion plays both ways; the last folder is held while it closes so there is
                        // something to render. B1: no scale (Main's Folio does not scale), and the rise is the swap tier's 8 dp.
                        val lastFolder = remember { mutableStateOf<CompanionHomeTile?>(null) }
                        // The Tome is covered when the recents drawer (or the host's own layer) is up over it: its sweep neither starts nor runs on behind that.
        val tomeCovered by rememberUpdatedState(panelCovered)
        val tomeArmed: () -> Boolean = remember(latticeArmed) { { latticeArmed() && reveal >= 1f && !tomeCovered } }
                        if (bookOpen) lastFolder.value = openFolder
                        val folder = lastFolder.value
                        // The page is admitted from the TAP (`bookOpen`), not from its first visible frame: the reveal now waits out the 130 ms
                        // lead at exactly 0, so a guard on `reveal > 0.001f` alone would leave the lattice taking taps under a Tome that is
                        // opening. Through the lead the page stands at alpha 0 and takes no touch (the shield a swap page wears before it first
                        // paints, and the same scope: the page, not the pad); from its first painted frame it takes them.
                        if (folder != null && (bookOpen || revealShowing)) {
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        alpha = reveal
                                        translationY = (1f - reveal) * MimirMotion.SwapTravelDp.dp.toPx()
                                    }
                                    .then(if (bookOpen && !revealShowing) Modifier.mimirPageTouchShield() else Modifier),
                            ) {
                                // Inside the Tome the cursor ring waits for the Tome's own reveal (the lattice's arrival is long over): one sweep, seen.
                                CompositionLocalProvider(LocalCompanionLatticeArmed provides tomeArmed) {
                                    CompanionFolderPage(
                                        folder = folder,
                                        // **The page is composed for the whole 180 ms CLOSE so its pixels can
                                        // fade — but a page that is logically gone must not still eat taps.**
                                        // `bookOpen` is false the instant the close starts, so its two
                                        // `clickable`s stand down while the frames keep playing. Found by an
                                        // adversarial review pass 2026-08-31; every tap on the lattice
                                        // underneath was being swallowed for the length of the animation.
                                        interactive = bookOpen,
                                        covered = panelCovered,
                                        restoredChildId = childCursorByFolder[folder.id],
                                        onChildSelected = { childId -> onChildCursor(folder.id, childId) },
                                        onTileTap = onBookChildTap,
                                        onClose = { openFolderRequest = null },
                                        // R6-P2-08 (finding 7, step 7/8): the same close this page's own outside tap
                                        // resolves (move-out confirm before the Tome), just native-triggered.
                                        closeRequest = pendingFolderClose,
                                        onRenameStart = { draft ->
                                            renameDraft = draft
                                            renamingFolderId = folder.id
                                            Log.i(CompanionDragLogTag, "rename-start folder=${folder.id}")
                                        },
                                        renameProgress = renameGlass,
                                        onDiscard = {
                                            openFolderRequest = null
                                            onDiscardFolder(visibleOrder, folder.id)
                                        },
                                        onMoveOut = { childId -> onUnbindChild(visibleOrder, childId) },
                                    )
                                }
                            }
                        }
                    }
                    // The page diamonds are NOT in this column: they stand on the frame's bottom line, half of them below it, and this drag root clips (`mimirGlassBackdropProgress` is
                    // `graphicsLayer { clip = true }`), so they are drawn by the sibling after this Box, which does not (see `CompanionPageRunesTag` below).
                    // The footer is the swipe hint and nothing else, and it exists ONLY while the hint can show: until the player has swiped to Activity once. Retired, the
                    // row and its 6 dp spacer are gone and their height goes to the lattice (B6's amendment, widened by the diamonds' move onto the frame). It is held for
                    // every page while the hint is live, not only page 0, so paging before the first swipe never changes the grid's height; the text itself shows on page 0.
                    if (onWorkspaceDrag != null && showActivitySwipeHint) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 13.dp)
                                .testTag(CompanionFooterTag),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The hint's text is laid out on every page and drawn on page 0 only: its line is taller than the 13 dp minimum at a raised font scale
                            // (15.18 dp at 1.3), so a row that held the text on one page and nothing on the others changed the grid's cell height by that difference as the
                            // player paged. A hidden hint is not drawn and says nothing to a screen reader, but it still takes its line.
                            val hintShown = currentPage == 0 && workspaceNavigationEnabled && !bookOpen && !settleActive.value
                            MimirChromeText(
                                text = "Swipe right for Activity →",
                                style = MimirTypeScale.ControlHintCompact,
                                color = palette.textMuted,
                                maxLines = 1,
                                modifier = if (hintShown) Modifier else Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics { },
                            )
                        }
                    }
                }
            }
            val overlayVisual = liftedVisual ?: settledDrag?.visual
            overlayVisual?.let { visual ->
                key("companion-lift", visual.tile.id, settledDrag?.transactionId ?: "active") {
                    LiftedTile(
                        visual = visual,
                        overlayOrigin = overlayOrigin,
                        settlingTarget = settleTargetRect.takeIf { settledDrag != null },
                        onSettleFinished = {
                            if (settlingDrag?.transactionId == settledDrag?.transactionId) {
                                settlingDrag = null
                            }
                        },
                    )
                }
            }
        }
        // **The page diamonds sit ON the frame's bottom edge** (frame-edge placement): centred on the frame line, over a quiet base scrim, the way the rune
        // eyebrow sits on the header rule, at the trailing end clear of the corner brackets and of the dock's handle (96 dp, centred under the frame). They take no height from the grid:
        // half of each diamond stands below the frame, in the shell's 10 dp clearance, as the eyebrow overhangs its rule.
        //
        // **Outside the drag root, on purpose.** The drag root clips to its bounds (`mimirGlassBackdropProgress` records its content into a `graphicsLayer { clip = true }`), and with the
        // hint retired the frame's bottom edge IS the drag root's bottom edge, so a slot inside it cut the lower half of every diamond and 6 dp of the scrim (the footprint test's
        // semantics showed it: 3.9 dp of a 7 dp row). This sibling is a plain Box in the shell body, which nothing above it clips short of the whole scene (the clearance is inside that
        // scene). It is placed from the frame's own measured bottom (`railBounds`, in root pixels, against the drag root's origin, which is this Box's), in the layout phase, so nothing
        // recomposes; until the frame has been laid out once it is not drawn. A relayout (the hint retiring) moves it a frame after the grid, which is a snap with it.
        if (pageCount > 1) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset {
                            // The frame line is the 1 dp border drawn inside the frame's bottom edge: its centre is half a dp up. The scrim is centred on it.
                            val lineY = railBounds.bottom - overlayOrigin.y - 0.5.dp.toPx()
                            IntOffset(
                                x = -CompanionPageRunesEndInsetDp.dp.roundToPx(),
                                y = (lineY - CompanionPageRunesScrimHeightDp.dp.toPx() / 2f).roundToInt(),
                            )
                        }
                        .graphicsLayer { alpha = if (railBounds.height > 0f) 1f else 0f }
                        .requiredHeight(CompanionPageRunesScrimHeightDp.dp)
                        .background(palette.surfaceBase.copy(alpha = 0.72f))
                        .padding(horizontal = CompanionPageRunesScrimPadDp.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // The diamonds drew nothing a reader could name. One node says where the page is, and the diamonds are its picture.
                    val runesNode = Modifier
                        .testTag(CompanionPageRunesTag)
                        .clearAndSetSemantics { contentDescription = companionPageRunesDescription(currentPage, pageCount) }
                    if (pageCount <= CompanionPageRunesMaxPages) {
                        MimirPageRunes(
                            current = currentPage,
                            count = pageCount,
                            palette = palette,
                            modifier = runesNode,
                        )
                    } else {
                        // Past the cap the diamonds would stand over the dock's handle (11 pages is the last that clears it) and a very large count would run off the scene's edge:
                        // the words take over, "3 / 14", as the platform shelf's strip does past its own cap. The scrim is the diamonds' 12 dp, so the words keep the default font
                        // scale (the node above carries the spoken "Page 3 of 14" at any scale); a larger text would spill out of the scrim above the rail and below the frame.
                        CompanionPageCountWords(currentPage, pageCount, palette, runesNode)
                    }
                }
            }
        }
        if (renamingFolder != null || renameGlassUp) {
            val scrim = mimirGlassScrimColor()
            Box(
                Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawRect(scrim.copy(alpha = scrim.alpha * renameGlass()))
                    },
            )
        }
        if (renamingFolder != null || renamePlateShowing) {
            // The secondary screen shows only the keyboard over glass blur for consistent presentation.
            Box(
                Modifier
                    .fillMaxSize()
                    .testTag(CompanionRenamePlateTag)
                    .graphicsLayer { alpha = renamePlate }
                    .pointerInput(Unit) {
                        detectTapGestures {
                            latestRenamingFolder.value?.let { folder ->
                                val name = latestRenameDraft.value.ifBlank { folder.label }
                                onRenameFolder(folder.id, name)
                                renamingFolderId = null
                                Log.i(
                                    CompanionDragLogTag,
                                    "rename-commit folder=${folder.id} via=outside-tap changed=${name != folder.label}",
                                )
                            }
                        }
                    }
                    .padding(horizontal = MimirSpacing.lg, vertical = MimirSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                MimirKeyboard(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    onDone = {
                        renamingFolder?.let { folder ->
                            val name = renameDraft.ifBlank { folder.label }
                            onRenameFolder(folder.id, name)
                            renamingFolderId = null
                            Log.i(
                                CompanionDragLogTag,
                                "rename-commit folder=${folder.id} via=keyboard changed=${name != folder.label}",
                            )
                        }
                    },
                    // Hide is "close without saving". It closes the editor and leaves the Tome's
                    // name as it was; only Done, and the outside tap, save.
                    onHide = {
                        renamingFolder?.let { folder ->
                            renamingFolderId = null
                            Log.i(CompanionDragLogTag, "rename-cancel folder=${folder.id} via=hide")
                        }
                    },
                    // hosted in the Presentation, on the Bottom, the in-app keyboard
                    // fills the Bottom like the system one does. The plate's own padding stays as the outside-tap gutter.
                    modifier = Modifier.fillMaxSize().mimirGlassPanelEntry { renameGlass() },
                    targetLabel = "Name this Tome",
                    showControllerLegend = false,
                    fill = true,
                )
            }
        }
    }
}

private const val CompanionDragLogTag = "MimirCompanionDrag"
private const val CompanionArtLogTag = "MimirCompanionArt"

private fun companionArtDebugLoggingEnabled(): Boolean =
    Log.isLoggable(CompanionArtLogTag, Log.DEBUG)

/** The lifted tile, in flight: which tile, where the finger is, and the cell it came out of (root px). */
private data class DragVisual(val tile: CompanionHomeTile, val topLeft: Offset, val size: Size)

private data class CompanionDragSettle(
    val transactionId: String,
    val draggedId: String,
    val visual: DragVisual,
    val targetSlot: Int,
)

/**
 * D37: what the lattice's composition reads of the drag machine's phase. A phase in which no tile is in the air (a press, or a finger the pager or the host owns) is one
 * and the same to composition ([idle] false, no transaction), so the many events of a swipe that only move such a phase along change nothing the lattice draws; a lifted
 * tile's phase carries its transaction, which does change with the finger and is what the lift draws.
 */
private data class CompanionObservedDrag(
    val idle: Boolean,
    val dragging: CompanionDragTransaction?,
    val active: CompanionDragTransaction?,
    val lifted: CompanionDragTransaction?,
)

private fun companionObservedDrag(phase: CompanionDragPhase): CompanionObservedDrag = when (phase) {
    CompanionDragPhase.Idle -> CompanionObservedDrag(idle = true, dragging = null, active = null, lifted = null)
    is CompanionDragPhase.Pressed, is CompanionDragPhase.PagerOwned ->
        CompanionObservedDrag(idle = false, dragging = null, active = null, lifted = null)
    is CompanionDragPhase.Dragging ->
        CompanionObservedDrag(idle = false, dragging = phase.transaction, active = phase.transaction, lifted = phase.transaction)
    is CompanionDragPhase.Turning ->
        CompanionObservedDrag(idle = false, dragging = null, active = phase.transaction, lifted = phase.transaction)
    is CompanionDragPhase.RollingBack ->
        CompanionObservedDrag(idle = false, dragging = null, active = phase.transaction, lifted = null)
}

/** The arguments that travel with a tile's movable composition while its cell changes. */
private data class CompanionGridTileContent(
    val tile: CompanionHomeTile,
    val cursor: Boolean,
    val covered: Boolean,
    val ghost: Boolean,
    val decode: Boolean,
    val binding: Boolean,
    val isArming: Boolean,
    val armingProgress: () -> Float,
    val restOffset: Offset,
    val dragging: Boolean,
    val stateTag: String?,
)

internal fun companionDragSettleTarget(
    targetSlot: Int,
    currentPage: Int,
    slotRects: Map<Int, Rect>,
): Rect? = targetSlot
    .takeIf { it >= 0 && it / SlotsPerPage == currentPage }
    ?.let(slotRects::get)

/**
 * The in-air tile (CR-3). It is a sibling of the rail, not a child of it, so nothing crops it and nothing
 * draws over it. It carries no interaction: the gesture that lifted it still lives in its source cell.
 */
@Composable
private fun LiftedTile(
    visual: DragVisual,
    overlayOrigin: Offset,
    settlingTarget: Rect? = null,
    onSettleFinished: () -> Unit = {},
) {
    val density = LocalDensity.current
    val settleProgress = remember { Animatable(0f) }
    LaunchedEffect(settlingTarget) {
        if (settlingTarget == null) return@LaunchedEffect
        settleProgress.snapTo(0f)
        settleProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(CompanionDragSettleMillis, easing = MimirMotion.Easing),
        )
        onSettleFinished()
    }
    val settleFraction = if (settlingTarget != null) settleProgress.value else 0f
    val topLeft = if (settlingTarget != null) {
        Offset(
            visual.topLeft.x + (settlingTarget.left - visual.topLeft.x) * settleFraction,
            visual.topLeft.y + (settlingTarget.top - visual.topLeft.y) * settleFraction,
        )
    } else {
        visual.topLeft
    }
    val widthPx = if (settlingTarget != null) {
        visual.size.width + (settlingTarget.width - visual.size.width) * settleFraction
    } else {
        visual.size.width
    }
    val heightPx = if (settlingTarget != null) {
        visual.size.height + (settlingTarget.height - visual.size.height) * settleFraction
    } else {
        visual.size.height
    }
    val width = with(density) { widthPx.toDp() }
    val height = with(density) { heightPx.toDp() }
    // Subtle lift: the tile EASES up from its resting size
    // instead of popping to a hard 1.06 scale. One 0→1 progress drives a gentle ~4% grow and the shadow rising
    // together, so the lift reads as a soft raise, not a jump.
    var raised by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { raised = true }
    val lift by animateFloatAsState(
        targetValue = if (raised) 1f else 0f,
        animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "lift-scale",
    )
    Box(
        Modifier
            .size(width, height)
            .offset {
                IntOffset(
                    (topLeft.x - overlayOrigin.x).roundToInt(),
                    (topLeft.y - overlayOrigin.y).roundToInt(),
                )
            }
            .testTag(companionLiftTag(visual.tile.id)),
        contentAlignment = Alignment.Center,
    ) {
        // The tile is handed the cell's own width x height, not the smaller edge: it solves the same ring, face and
        // label band the resting cell does, so nothing jumps when it lifts.
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer {
                val s = if (settlingTarget != null) {
                    1.04f - 0.04f * settleFraction
                } else {
                    1f + 0.04f * lift
                }
                scaleX = s
                scaleY = s
                alpha = 0.97f
                shadowElevation = if (settlingTarget != null) {
                    16f * (1f - settleFraction)
                } else {
                    16f * lift
                }
            },
        ) {
            CompanionHomeGridTile(
                tile = visual.tile,
                cursor = false,
                ghost = false,
                binding = false,
                artSite = "lifted",
                onTap = null,
            )
        }
    }
}

/**
 * An opened tome (S6): the book's own 3×3 page, laid over its shelf. Tapping the title opens the in-app
 * keyboard to rename it (touch-only, like everything on screen 2); tapping outside closes the book.
 */
@Composable
private fun CompanionFolderPage(
    folder: CompanionHomeTile,
    /**
     * False while the page is CLOSING. It stays composed so its pixels can fade — §1's law that a
     * departing surface's pixels may animate — but a surface that is logically gone must not go on
     * absorbing touches. Both `clickable`s below are gated on this.
     */
    interactive: Boolean,
    /** Something is painted over the open Tome (the recents drawer): its cursor ring settles and keeps still, as the lattice's does. */
    covered: Boolean = false,
    restoredChildId: String?,
    onChildSelected: (String) -> Unit,
    onTileTap: (CompanionHomeTile) -> Unit,
    onClose: () -> Unit,
    onRenameStart: (String) -> Unit,
    renameProgress: () -> Float,
    onDiscard: () -> Unit,
    // CP04: long-press a tile INSIDE the open book → "move out of this book"
    // (back to the shelf). This is the menu the request specified for — NOT a menu on the main grid.
    onMoveOut: (String) -> Unit,
    /**
     * R6-P2-08 (finding 7, step 7/8): a native EdgeBack close, once for each new non-null value. Runs
     * through the EXACT SAME decision the outside tap below makes — the move-out confirm first, else the
     * Tome itself — so a native swipe and a tap close identically, one layer at a time.
     */
    closeRequest: Long? = null,
) {
    val palette = LocalMimirPalette.current
    val book = remember(palette) { companionBookTreatment(palette) }
    val density = LocalDensity.current
    // The child whose long-press asked to leave this book, by id (a page's identity is its content, never a position).
    // Non-null → the move-out confirm is showing.
    var movingChildId by remember(folder.id) { mutableStateOf<String?>(null) }
    // A confirm about a child that is no longer in this Tome is not showing: the page returns to the Leaves, as a real retarget.
    val movingId = movingChildId?.takeIf { id -> folder.children.any { it.id == id } }
    val latestMovingId by rememberUpdatedState(movingId)
    // R6-P2-08 re-review finding A: this page is composed fresh on every open (the `if (folder != null &&
    // (bookOpen || reveal > 0.001f)` guard above omits it entirely between opens, discarding all remembered state), so
    // its `LaunchedEffect` below fires on THIS instance's own first composition too. Without a per-mount
    // floor, the value left over from the swipe that closed the PREVIOUS Tome — `closeRequest` is never
    // reset — read as a brand-new request and closed this one right after it opened.
    val initialCloseRequest = remember(folder.id) { closeRequest }
    // R6-P2-08 (finding 7, step 7/8): the exact same decision the outside tap's `clickable` below makes,
    // just native-triggered — the move-out confirm first, else the Tome itself, one per swipe.
    LaunchedEffect(closeRequest) {
        if (closeRequest == null || closeRequest == initialCloseRequest) return@LaunchedEffect
        when {
            latestMovingId != null -> {
                movingChildId = null
                Log.i("MimirCompanionRuntime", "edge back closed layer=folder_move_confirm")
            }
            else -> {
                onClose()
                Log.i("MimirCompanionRuntime", "edge back closed layer=folder")
            }
        }
    }
    // BG-01c: the book's own selection ring, seeded on open to the touched child (or the first) so a book always
    // opens with one tile selected like the main shelf, then follows taps inside the book.
    var selectedChildId by remember(folder.id, folder.children) {
        mutableStateOf(
            folder.children.firstOrNull { it.id == restoredChildId }?.id ?: folder.children.firstOrNull()?.id,
        )
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(palette.scrim.copy(alpha = 0.72f))
            .then(
                if (interactive) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        when {
                            movingId != null -> movingChildId = null
                            else -> onClose()
                        }
                    }
                } else {
                    // Closing: the pixels keep fading, the hit target does not survive them.
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val geometry = remember(maxWidth, maxHeight, density.fontScale) {
            solveCompanionOpenBookGeometry(
                viewportWidthDp = maxWidth.value,
                viewportHeightDp = maxHeight.value,
                fontScale = density.fontScale,
                renaming = false,
            )
        }
        // CR-13: the opened book is a leather-framed CARD, not a bare scrim — the same authored volume,
        // hardware hairline, and corner brackets as the closed cover, so the two are one object. It absorbs taps so
        // a touch on the page never bubbles to the scrim; only a tap OUTSIDE closes it.
        // CP03: the closed book is NARROWER (0.84→0.76) so the "outside" dismiss strip is wide enough to
        // hit reliably — taps could not close it by tapping near the edge.
        Column(
            modifier = Modifier
                .width(geometry.panelWidthDp.dp)
                .height(geometry.panelHeightDp.dp)
                .norseBookFrame(book)
                .then(
                    if (interactive) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer { alpha = 1f - renameProgress() }
                .padding(
                    horizontal = geometry.horizontalPaddingDp.dp,
                    vertical = geometry.verticalPaddingDp.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(geometry.spacingDp.dp),
        ) {
                // A small, centred BOOKPLATE title — reads as a book title, kept clear of the corners
                // (small enough to clear the corners). Tap to rename here.
                val renameSource = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .clickable(interactionSource = renameSource, indication = null) {
                            // V10 (review 2026-07-11): seed the current name so rename EDITS in place instead
                            // of forcing a full retype; the ifBlank fallback on commit still keeps the old name.
                            onRenameStart(folder.label)
                        }
                        // The press response follows the tap and precedes the fill, so the whole plate draws in and the hit box stays whole.
                        .mimirPressResponse(renameSource)
                        .clip(RoundedCornerShape(8.dp))
                        .background(book.coverMiddle.copy(alpha = 0.82f))
                        .border(1.dp, book.hardware.copy(alpha = 0.62f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            folder.label,
                            style = MimirTypeScale.Row,
                            color = book.onCover,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        MimirChromeText("Rename", style = MimirTypeScale.MenuAction, color = book.onCover.copy(alpha = 0.62f), maxLines = 1)
                    }
                }
                if (folder.children.isEmpty()) {
                    // A tome the user just bound but hasn't filled (CR-17): tell them how, and let them undo it.
                    Text(
                        "Empty Tome — drop a console or app onto it to file it here",
                        style = MimirTypeScale.Support,
                        color = book.onCover.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 320.dp),
                    )
                    val emptyRemoveSource = remember { MutableInteractionSource() }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clickable(
                                interactionSource = emptyRemoveSource,
                                indication = null,
                            ) { onDiscard() }
                            .mimirPressResponse(emptyRemoveSource)
                            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                            .border(1.dp, book.counterAccent.copy(alpha = 0.58f), RoundedCornerShape(MimirSpacing.radiusCard))
                            .heightIn(min = MimirSpacing.minTarget)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        // Copy review 2026-09-11 (§2 #9): this fires the same handler as the full Tome's remove
                        // action below, so it says the same verb.
                        MimirChromeText("Remove Tome", style = MimirTypeScale.MenuAction, color = book.onCover, maxLines = 1)
                    }
                    // The empty Tome keeps its one hint here; a non-empty one carries it on its own pages below.
                    MimirChromeText(
                        "Tap outside to close",
                        style = MimirTypeScale.ControlHintCompact,
                        color = book.onCover.copy(alpha = 0.62f),
                    )
                } else {
                    // A5 (C1): the move-out confirm is a page swap, not a cut. The Leaves (the grid and the Tome's footer) and the
                    // confirm are two pages of ONE AnimatedContent, keyed by the content id (never a position), on the swap tier's
                    // clocks. Long-press is forward; the outside tap, EdgeBack and the Move-to-shelf button are all returns. The Leaves page
                    // stacks art, so the pages fade through a layer (fade = true). The Bottom is touch-only: the gate below passes
                    // holdPad = false and keeps its touch half, because a Bottom swap that held the process-wide pad gate would
                    // deafen Main's controller for the lead.
                    // Read at EACH swap edge (keyed on the target), never remembered for the mount, exactly as the dock does: one value feeds
                    // the transform, the page gate, the probe and the receipt, so what is drawn and what is reported are the same
                    // answer even after the animator setting changes under a mounted Tome. A reduced swap is a snap on every spec, so a
                    // cut leaves nothing lingering behind its receipt.
                    val swapReduced = remember(movingId) { bottomUiReducedMotion() }
                    val swapReturning = movingId == null
                    val swapPx = with(density) { MimirMotion.SwapTravelDp.dp.roundToPx() }
                    // `tome-open` stays entered throughout: this is a second, nested surface, entered by the long-press.
                    BottomUiSurfaceReceipt(
                        surface = BottomSurface.TOME_MOVE_OUT,
                        visible = movingId != null,
                        transition = if (swapReduced) null else if (movingId != null) "tome-move-out.enter" else "tome-move-out.exit",
                        reason = if (swapReduced) BottomUiReceiptLog.REASON_REDUCED_MOTION else null,
                    )
                    AnimatedContent(
                        targetState = movingId,
                        transitionSpec = {
                            // RC-51 (audit 2026-10-14, B-22): the pages fade themselves (below, `fade = false`), not through AnimatedContent's own
                            // layer, which is the page's size and cut a selected leaf's ring mid-swap.
                            mimirSwapTransform(
                                swapReduced,
                                fade = false,
                                returning = swapReturning,
                                returnTravelPx = swapPx,
                                enterTravelPx = swapPx,
                            )
                        },
                        label = "tome-move-out-swap",
                        // CP08: the pages share what is left under the pinned bookplate, so a FULL book's footer can never be
                        // pushed off the panel (the grid inside the Leaves page yields to it, below).
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    ) { pageId ->
                        // Presses during a page change wait for the page they would act on; a page that is leaving takes no touch.
                        val pageFade = mimirSwapFadeAlpha(swapReduced, returning = swapReturning)
                        val pageInput = MimirSwapPageGate(pageFade, holdPad = false)
                        // Only the page that is the current target reports the run, and only when a fade runs: a probe under a cut
                        // would write a begin and an end for a swap that never ran.
                        if (!swapReduced && transition.targetState == EnterExitState.Visible) {
                            BottomUiTransitionProbe(if (swapReturning) "tome-move-out.exit" else "tome-move-out.enter", transition)
                        }
                        // The child this confirm is about, resolved once when the page is born from the Tome's own children. A
                        // page that is leaving keeps it (the child has already left `folder.children` by then).
                        val moving = remember(pageId) { pageId?.let { id -> folder.children.firstOrNull { it.id == id } } }
                        Column(
                            modifier = Modifier.fillMaxWidth().mimirRingSafeFade(pageFade).then(pageInput),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(geometry.spacingDp.dp),
                        ) {
                            when (moving) {
                                null -> {
                                    // CP08: a full book overflows the frame, so its contents SCROLL within a capped area.
                                    // The bookplate title and the footer below stay pinned; only the leaves scroll.
                                    CompanionTomeLeaves(
                                        children = folder.children,
                                        selectedChildId = selectedChildId,
                                        maxHeightDp = geometry.childViewportMaxHeightDp,
                                        covered = covered,
                                        onTap = {
                                            selectedChildId = it.id
                                            onChildSelected(it.id)
                                            onTileTap(it)
                                        },
                                        // CP04: hold a tile in the book → offer to move it back out.
                                        onLongPress = { movingChildId = it.id },
                                    )
                                    // CP07: remove a book you no longer want. Non-destructive — its consoles and apps come
                                    // straight home to the shelf; only the binding is undone. It lives on the Leaves page, so
                                    // ONE decision is on a page at a time.
                                    val removeSource = remember { MutableInteractionSource() }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .clickable(
                                                interactionSource = removeSource,
                                                indication = null,
                                            ) { onDiscard() }
                                            .mimirPressResponse(removeSource)
                                            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                                            .border(1.dp, book.counterAccent.copy(alpha = 0.52f), RoundedCornerShape(MimirSpacing.radiusCard))
                                            .heightIn(min = MimirSpacing.minTarget)
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                    ) {
                                        MimirChromeText("Remove Tome", style = MimirTypeScale.MenuAction, color = book.onCover, maxLines = 1)
                                    }
                                    Text(
                                        // Review the earlier update (2026-07-11): books hold consoles and apps, never "games".
                                        "Everything inside returns to the shelf.",
                                        style = MimirTypeScale.Support,
                                        color = book.onCover.copy(alpha = 0.68f),
                                        maxLines = 1,
                                    )
                                    MimirChromeText(
                                        "Tap outside to close",
                                        style = MimirTypeScale.ControlHintCompact,
                                        color = book.onCover.copy(alpha = 0.62f),
                                    )
                                }
                                else -> {
                                    // CP04: the move-out confirm. One deliberate act, in the
                                    // same leather language; tap outside cancels.
                                    Text(
                                        "Move ${moving.label} out of this Tome?",
                                        style = MimirTypeScale.Row,
                                        color = book.onCover,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 320.dp),
                                    )
                                    val moveOutSource = remember { MutableInteractionSource() }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier
                                            .clickable(
                                                interactionSource = moveOutSource,
                                                indication = null,
                                            ) {
                                                movingChildId = null
                                                onMoveOut(moving.id)
                                            }
                                            .mimirPressResponse(moveOutSource)
                                            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                                            .border(1.dp, book.hardware.copy(alpha = 0.82f), RoundedCornerShape(MimirSpacing.radiusCard))
                                            .heightIn(min = MimirSpacing.minTarget)
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        MimirChromeText("Move to shelf", style = MimirTypeScale.MenuAction, color = book.onCover, maxLines = 1)
                                    }
                                    Text(
                                        "It returns to the shelf, right after this Tome.",
                                        style = MimirTypeScale.Support,
                                        color = book.onCover.copy(alpha = 0.70f),
                                        maxLines = 1,
                                    )
                                    MimirChromeText(
                                        "Tap outside to cancel",
                                        style = MimirTypeScale.ControlHintCompact,
                                        color = book.onCover.copy(alpha = 0.62f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
}

/**
 * The open Tome's leaves: its children in rows of three, 104 x 122 dp cells, in a scrolling column (CP08: a full book overflows the frame, so
 * its contents SCROLL within a capped area while the bookplate and the footer stay pinned). Extracted whole from [CompanionFolderPage] so the
 * Tome's ring can be rendered against the real leaves.
 */
@Composable
internal fun ColumnScope.CompanionTomeLeaves(
    children: List<CompanionHomeTile>,
    selectedChildId: String?,
    maxHeightDp: Float,
    /** Something is painted over the whole Tome: the cursor ring settles at once and its sweep neither starts nor runs on behind the cover. */
    covered: Boolean = false,
    onTap: (CompanionHomeTile) -> Unit,
    onLongPress: (CompanionHomeTile) -> Unit,
) {
    Column(
        modifier = Modifier
            // BG-01a: weight(fill=false) makes the scroll region yield space to the pinned
            // "Remove book" footer measured after it — so on a FULL book the footer can never be
            // pushed off the 468dp panel and clipped. The 240dp cap still bounds the visual height
            // for short/medium books (CP03: keeps a top/bottom outside-tap margin).
            .weight(1f, fill = false)
            .heightIn(max = maxHeightDp.dp)
            .verticalScroll(rememberScrollState())
            // The ring is drawn OUTSIDE its face and the scroll clips its content to its bounds: the first row's face stands 4 dp
            // under its cell's top and the ring reaches 14 dp past the face, so the content starts 10 dp down or the ring's outer
            // strokes are cut flat on the top row.
            .padding(top = CompanionTomeRingHeadroomDp.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The book's contents, in the same tile language as the main grid — a shelf within a shelf.
        children.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { child ->
                    Box(Modifier.height(122.dp).width(104.dp).testTag(companionTileCellTag(child.id))) {
                        CompanionHomeGridTile(
                            tile = child,
                            // BG-01c: in-book selection ring, driven by the book's own selection.
                            cursor = child.id == selectedChildId,
                            covered = covered,
                            ghost = false,
                            binding = false,
                            onTap = onTap,
                            onLongPress = onLongPress,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun CompanionHomeGridTile(
    tile: CompanionHomeTile,
    cursor: Boolean,
    /** Something is painted over the whole lattice — an open tome, or the recents drawer. */
    covered: Boolean = false,
    ghost: Boolean,
    // N3 icon-decode window: false on tiles more than one page off-screen — the eager single-canvas composes
    // every page, so the APK icon decode must be gated or it re-introduces the all-pages BitmapFactory burst.
    decode: Boolean = true,
    artSite: String = "grid",
    binding: Boolean = false,
    /** Draw-phase progress for the finite dwell that lets this tome accept the tile in the air (CR-17). */
    isArming: Boolean = false,
    armingProgress: () -> Float = { 0f },
    onTap: ((CompanionHomeTile) -> Unit)?,
    /**
     * Where this tile's press comes from when it has no tap node of its own (the lattice's tiles pass `onTap = null` and the drag root's detector owns the finger):
     * the press response reads this instead of the tile's private source, and the rim does not (it is the tap node's, as it always was). Null: the tile's own.
     */
    pressSource: MutableInteractionSource? = null,
    testTag: String? = null,
    // CP04: only BOOK CHILDREN pass this — long-press inside an open book
    // offers "move out of this book". combinedClickable arbitrates tap-vs-hold in ONE node, so a long-press
    // never co-fires the tap (the grid's cell-level drag detector suffers exactly that; this doesn't).
    onLongPress: ((CompanionHomeTile) -> Unit)? = null,
) {
    val palette = LocalMimirPalette.current
    // Platform marks and Tome covers stand directly on the shelf, without an inner glyph rectangle
    // or an outer raised panel. Custom platform art is also the face and receives no ground, including
    // art with transparent corners; artwork that needs contrast must be adjusted where it is chosen.
    // App icons retain a ground because a foreign square needs a consistent surface beneath it.
    val platformMarkOnly = tile.kind == CompanionHomeTileKind.PLATFORM
    val folderTile = tile.kind == CompanionHomeTileKind.FOLDER
    val framelessTile = tile.kind != CompanionHomeTileKind.ANDROID_APP
    val fill = remember(palette, framelessTile) {
        if (framelessTile) {
            SolidColor(Color.Transparent)
        } else {
            Brush.verticalGradient(
                listOf(palette.surfaceRaised.copy(alpha = 0.5f), palette.surfaceRaised.copy(alpha = 0.34f)),
            )
        }
    }
    val glyphBrush = remember(palette) { platformConsoleIconBrush(palette) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lit = pressed
    // R3 : an app's 09-01 ground (the fill, the 0.5 dp outline, the icon box's raised square) draws only with a
    // LOADED icon, fading in with it, or under the letter for an app with no icon path (or whose icon file is gone). While the icon
    // loads, or if it fails (R8), the face draws nothing. The load is read here so the ground and the icon agree on one state.
    val appIconPath = tile.iconPath?.takeIf { tile.kind == CompanionHomeTileKind.ANDROID_APP && it.isNotBlank() }
    val appArt = rememberCompanionArtSite(
        hasSource = appIconPath != null,
        decoded = rememberCompanionIconArt(appIconPath, decode && appIconPath != null),
        identity = appIconPath,
        window = decode,
    )
    // The press has ONE response on the Bottom (R4, B5): `mimirPressResponse` below draws the whole tile in to the token, in the draw phase. The 1.03
    // grow that used to answer here is gone; what stays is the rim, on the same beat.
    val ringColor by animateColorAsState(
        if (lit) palette.focusRing else palette.outline,
        tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "tile-ring",
    )
    // The cursor's ring is Main's one ring, worn by the face below (`mimirSelectionRing`), so the cursor has no border arm of its own: an
    // app under the cursor keeps its resting 0.5 dp ground outline, which the ring's bright stroke (0.5 dp outside the edge) stands off from.
    val borderColor = when {
        binding -> palette.accent
        lit -> ringColor
        else -> palette.outline
    }
    val borderWidth = when {
        binding || lit -> 1.4.dp
        else -> 0.5.dp
    }
    // The page's own arrival, which the gleam waits for. A covering layer (an open Tome, the recents drawer) stands the START of a sweep down too (`armed`), and the latch below
    // handles the rest: a cover that arrives with the cursor, or over a sweep already running, leaves the ring whole and still. A covered
    // bookmark is not the subject, so its sweep is not spent behind the scrim. The `armed` reads are lambdas, so they stay off the composition path.
    val hostArmed = LocalCompanionLatticeArmed.current
    val ringArmed = remember(hostArmed, covered) { { !covered && hostArmed() } }
    // **A cover over a ring that is mid-motion settles it at once**: `armed` only gates the START of the sweep, so a sweep begun
    // before the Tome opened (or the recents drawer came up) would run on behind the scrim, and the ring would go on growing behind it. A covered
    // cursor tile re-keys its ring onto a settled identity and draws it with no gleam: the ring is whole this frame, the sweep is cancelled, and nothing
    // replays when the cover leaves. It stays settled until the cursor has left the tile and its ring has faded out (D3, below), so a tile the player
    // lands on afterwards is a new cursor and gets the ring's whole arrival. Written in a SideEffect and read as `covered || spent`, so the first
    // covered frame is already settled and the flag only has to outlive the cover.
    //
    // **The ring's two fade edges (ledger D3).** The flag is the TILE's, not the cursor's, and any cover sets it: see [rememberCompanionRingCoverSettle].
    val coverRing = rememberCompanionRingCoverSettle(tile.id, cursor, covered)
    val coverSettled = coverRing.settled
    val accentColor = palette.accent
    // **One cell, one solve.** The tile is laid out from `solveCompanionLatticeCell`: a SQUARE ring square, top-aligned and
    // centred in the cell; the face (the art, the glyph, the cover, the app's ground) inside it, 4 dp short of the
    // square on every side; and the name in a two-line band UNDER the square. The square is the face plus the ring's
    // sibling clearance (8 dp), so the cursor's ring, which Main's `mimirSelectionRing` draws around the FACE, has its
    // strong strokes clear of the name and of the row above; the band and its clear live inside the cell, so the last row
    // clears the rail's brackets with no special case.
    //
    // **The cursor's ring is Main's one ring.** `mimirSelectionRing` in the `tileFocusRing`
    // colour, shaped to the face's own corner, one sweep of gleam on the ring clock (420 ms ring, 540 ms gleam), paint only and no
    // focus scale. It rides the face's own modifier chain before the clip, and it is on every tile with `focused` false, as on Main:
    // a settled ring is STILL, nothing here ticks, and the cursor tile is no longer the only one with a ring node. The old
    // 1.4 dp ring that breathed on a 20 Hz tick is retired with its ticker.
    //
    // The history this keeps. **The face is 1:1 — ** and **CENTRED — ** The ring edge is the smaller of the two measured edges, never an
    // `aspectRatio` guess, so it fits a cell that is wider than tall (the lattice) and one that is taller than wide
    // (the open book) by construction. **And a platform face fills its ring — ** The old x0.8 on the art is retired.
    //
    // **The name is policy R1 (2026-10-02): the full platform name, on two lines.** It is ONE `Text` for every
    // kind, unconditionally: a kind-gated composable `if` here tripped Compose IR lowering on 07-15, so the label
    // takes no branch. The tap target is the whole cell (face and band); the ring stays on the face.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val cell = remember(maxWidth, maxHeight, fontScale) {
            solveCompanionLatticeCell(maxWidth.value, maxHeight.value, fontScale)
        }
        Column(
            modifier = Modifier
                // CR-15: the tile is the size of its cell. It used to be 128dp wide inside a row whose height
                // depended on how many rows the page happened to need — so four tiles drew bigger than nine.
                .fillMaxSize()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .then(
                    if (onLongPress != null && onTap != null) {
                        Modifier.combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onTap(tile) },
                            onLongClick = { onLongPress(tile) },
                        )
                    } else if (onTap != null) {
                        Modifier.clickable(interactionSource = interaction, indication = null) { onTap(tile) }
                    } else {
                        Modifier
                    },
                )
                // The tile's one press response, right after the tap and before everything it draws, so the face, its ring and its name draw in together
                // while the hit box stays whole (a layer ahead of the tap would move the pointer's bounds with it). It reads the same source as the tap, so
                // a press that becomes a swipe, a lift or a book's long press is cancelled by whoever took the gesture and the tile comes back whole.
                .mimirPressResponse(pressSource ?: interaction)
                .graphicsLayer {
                    alpha = if (ghost) 0.25f else 1f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(cell.ringEdgeDp.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(cell.faceEdgeDp.dp)
                        // THE RING, BEFORE THE CLIP, because it is drawn outside the face: `mimirSelectionRing` is Main's one ring (the
                        // `tileFocusRing` colour, its clock and its one-sweep gleam), paint only (no `focusable`: the Bottom is touch-only and
                        // its cursor is a tap's, never the pad's) and no focus scale. **The ring follows the cursor only: a press is not a focus change**
                        // so `lit` is not in `focused` and a press that is cancelled leaves the ring and its gleam exactly as
                        // they were; the press has its own response (the press scale, `mimirPressResponse` above, and the 1.4 dp rim below). It is off while the tile is bound,
                        // arming a merge or in the air as a ghost. A ghost is a different identity, so its ring is gone at once and not fading inside
                        // the ghost's 0.25 alpha layer, which would cut it at the cell's bounds; the page's own fades (the Presentation's
                        // intro and a Tome's reveal) are full-size layers this ring is nowhere near the edge of.
                        .mimirSelectionRing(
                            focused = cursor && !binding && !isArming && !ghost,
                            identity = when {
                                ghost -> tile.id + CompanionGhostRingSuffix
                                coverSettled -> tile.id + CompanionCoveredRingSuffix + coverRing.epoch
                                else -> tile.id
                            },
                            shape = MimirRingShape.Card(companionFaceCorner(tile.kind, cell.faceEdgeDp)),
                            gleam = if (coverSettled) MimirRingGleam.None else MimirRingGleam.Sweep,
                            armed = ringArmed,
                        )
                        // The face now fills its ring edge to edge, so the tile's own 13 dp clip must not round what
                        // owns a corner of its own: the app's ground takes the tile corner, a Tome's cover keeps the
                        // 08-09 uniform-corner law (`CompanionNorseCoverShape`), and platform art keeps its own 12 dp.
                        // (A plain value `when` on a Modifier, never a composable branch.)
                        .then(
                            when {
                                folderTile -> Modifier.clip(CompanionNorseCoverShape)
                                framelessTile -> Modifier
                                else -> Modifier.clip(RoundedCornerShape(CompanionAppFaceCorner))
                            },
                        )
                        .then(if (framelessTile) Modifier else Modifier.companionAppGroundFill(fill) { appArt.reveal.value })
                        // The finite merge animation reads progress in draw scope: only this layer redraws while the
                        // tome fills, and the tile subtree does not recompose at display cadence.
                        .drawWithContent {
                            // Merge decoration belongs to tome tiles; a platform face has no outer frame, including
                            // while focused.
                            val armFraction = if (!platformMarkOnly && isArming) armingProgress().coerceIn(0f, 1f) else 0f
                            if (armFraction > 0f) {
                                val height = size.height * armFraction
                                drawRect(
                                    color = accentColor.copy(alpha = 0.18f),
                                    topLeft = Offset(0f, size.height - height),
                                    size = Size(size.width, height),
                                )
                            }
                            drawContent()
                        }
                        // Platform marks and tome covers remain fully touchable, but neither has a decorative outer rim. An app's resting
                        // 0.5 dp outline is ground and follows its icon; the press and binding rims are feedback and always draw.
                        .then(
                            if (framelessTile) {
                                Modifier
                            } else {
                                Modifier.companionAppGroundBorder(borderWidth, borderColor) {
                                    if (binding || lit) 1f else appArt.reveal.value
                                }
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when (tile.kind) {
                        CompanionHomeTileKind.FOLDER -> CompanionTomeFace(tile, focused = cursor && !binding && !isArming && !ghost, palette = palette)
                        // The app's 09-01 ground is the face square; the 34 dp icon is centred on it.
                        CompanionHomeTileKind.ANDROID_APP -> CompanionAppIcon(tile, appArt, decode = decode)
                        // platform tiles are a centered rounded SQUARE the art populates (extracted
                        // to its own composable so this `when` arm is a single call — an inline block arm with a local
                        // val + if/else tripped the Compose IR lowering, "Empty collection can't be reduced").
                        CompanionHomeTileKind.PLATFORM -> CompanionPlatformTileArt(tile, glyphBrush, decode, artSite)
                    }
                }
            }
            Spacer(Modifier.height((cell.labelTopDp - cell.ringEdgeDp).dp))
            Text(
                text = tile.label,
                style = CompanionLatticeLabelStyle,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
                // The solver's line count: two where the cell pays for the band, fewer where it cannot (a 0 band
                // hides the name, clipped to nothing). Never a branch, so the name stays one unconditional call.
                maxLines = cell.labelLines.coerceAtLeast(1),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = cell.labelInsetDp.dp)
                    .height(cell.bandDp.dp)
                    .clipToBounds(),
            )
            // CR-6: the tile is a glyph and its name. No counts, no kind line — the shelf is a
            // place, not a report. (The armed "Open" pill that used to take this spot is long deleted: I5 retired
            // the two-beat arm.)
        }
    }
}

/**
 * The page's own arrival, as the lattice's cursor ring reads it: false while the page is still arriving, so the gleam is not spent behind a
 * fade or a cover. It is a lambda over snapshot state (an `Animatable`'s value), read where the gleam waits on it and never in composition.
 * It is the BOTTOM's own arrival ([CompanionHome] makes one, a host may hold it longer): never `MimirTransitionInputGate` and never
 * `holdMimirInputUntilShown`, so the Bottom never holds the process-wide pad gate that deafens Main's controller.
 */
internal val LocalCompanionLatticeArmed = compositionLocalOf<() -> Boolean> { MimirFocusAlwaysArmed }

/**
 * What a tile's cursor ring knows of the covers over the lattice (ledger D3): whether its identity is the settled one ([settled]) and which cover arrival that identity belongs
 * to ([epoch]). The flag is the tile's own, set by ANY cover, so:
 *  - a ring already fading out when a cover arrives re-keys at once onto the settled identity at rest, and is gone behind the cover instead of fading on through it;
 *  - a cursor that leaves a settled tile keeps its identity, so the ring fades out on its own animatable over the whole ring clock ([MimirFocusRingSettleMs], 420 ms)
 *    rather than being dropped by a re-key; the flag clears once that fade is over, the cursor is away and no cover is up (the ring is at rest by then, so the re-key shows nothing);
 *  - every cover arrival is its own identity ([epoch]), so a ring fading behind a cover that comes up over an earlier settled one is cut by the new cover too.
 * A cursor that lands on the tile after the flag has cleared is a new arrival and gets the ring's whole arrival and its sweep.
 */
@Stable
internal class CompanionRingCoverSettle(val settled: Boolean, val epoch: Int)

@Composable
private fun rememberCompanionRingCoverSettle(tileId: String, cursor: Boolean, covered: Boolean): CompanionRingCoverSettle {
    val spent = remember(tileId) { mutableStateOf(false) }
    val arrivals = remember(tileId) { IntArray(1) }
    // Counted in the composition that first sees the cover, so the identity changes on that very frame.
    val epoch = remember(tileId, covered) { if (covered) ++arrivals[0] else arrivals[0] }
    // Written in a SideEffect and read as `covered || spent`, so the first covered frame is already settled and the flag only has to outlive the cover.
    SideEffect { if (covered && !spent.value) spent.value = true }
    LaunchedEffect(cursor, covered, spent.value) {
        if (!cursor && !covered && spent.value) {
            delay(MimirFocusRingSettleMs.toLong())
            spent.value = false
        }
    }
    return CompanionRingCoverSettle(settled = covered || spent.value, epoch = epoch)
}

/** The suffix that makes a lifted tile's ring a different identity, so it is gone at once and not fading inside the ghost's dim layer. */
private const val CompanionGhostRingSuffix = "#lifted"
private const val CompanionCoveredRingSuffix = "#covered"

/** An app face's corner: its ground's clip, its fill and its outline, and so its cursor ring (the ring and clip share one corner symbol). */
internal val CompanionAppFaceCorner: Dp = 13.dp

/**
 * An app's icon on the lattice: 34 dp, in the one app-icon shape ([mimirAppIconShape], Main's rounded square at 11/40 of its edge, policy 4 of the
 * Thor Bottom revamp), centred on the face. The face's own 13 dp ground corner above is the ground and the ring's, not the icon's.
 */
internal const val CompanionLatticeIconDp = 34f
internal const val CompanionLatticeIconTag = "companion-lattice-icon"

/** A platform's art cover's corner: its clip, and so its cursor ring. */
internal val CompanionPlatformArtCorner: Dp = 12.dp

/** The corner of a lattice face's own clip, which its ring takes: the Tome's uniform-corner law, the platform art's 12 dp, an app ground's 13 dp. */
internal fun companionFaceCorner(kind: CompanionHomeTileKind, faceEdgeDp: Float): Dp = when (kind) {
    CompanionHomeTileKind.FOLDER -> mimirNorseBookCoverCorner(faceEdgeDp)
    CompanionHomeTileKind.PLATFORM -> CompanionPlatformArtCorner
    CompanionHomeTileKind.ANDROID_APP -> CompanionAppFaceCorner
}

/** Outer accent hairline of [norseRail]. Was 0.32, which the 2026-09-11 copy/aesthetic review (§3.1, D1)
 *  measured at 1.0:1 against Máni's left glow and 1.4–1.6:1 across Yggdrasil on the softened backdrop. */
private const val CompanionRailBorderAlpha = 0.50f

/** Scrim line drawn under that hairline on the same rounded rect (review D1), so the frame keeps an edge
 *  where the theme scene is brighter than the accent; on dark ground it is invisible. */
private const val CompanionRailShadowAlpha = 0.45f

/**
 * The modern-Norse rail: a double hairline in the accent family with corner brackets
 * (the welcome-card language) and diamond studs at the horizontal midpoints. Draw-only.
 *
 * The outer hairline is two-tone since the 2026-09-11 review (D1): a [CompanionRailShadowAlpha] scrim line
 * under a [CompanionRailBorderAlpha] accent line, so the frame reads against both the scene's glow and its
 * dark ground. Geometry is unchanged; only the two alphas and the shadow pass are new.
 */
private fun Modifier.norseRail(palette: MimirThemePalette): Modifier = this
    .clip(RoundedCornerShape(18.dp))
    .border(1.dp, palette.accent.copy(alpha = CompanionRailBorderAlpha), RoundedCornerShape(18.dp))
    .drawBehind {
        val accent = palette.accent
        // The shadow under the accent border. `border` strokes its rect inset by half the stroke so the
        // line lies inside the clip, and paints after `drawContent()` -- so this identical rect, drawn
        // in the behind pass, sits exactly beneath it.
        val outerStroke = 1.dp.toPx()
        val outerHalf = outerStroke / 2f
        drawRoundRect(
            color = palette.scrim.copy(alpha = CompanionRailShadowAlpha),
            topLeft = Offset(outerHalf, outerHalf),
            size = Size(size.width - outerStroke, size.height - outerStroke),
            cornerRadius = CornerRadius(18.dp.toPx() - outerHalf),
            style = Stroke(width = outerStroke),
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.14f),
            topLeft = Offset(5.dp.toPx(), 5.dp.toPx()),
            size = Size(size.width - 10.dp.toPx(), size.height - 10.dp.toPx()),
            cornerRadius = CornerRadius(14.dp.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
        val inset = 10.dp.toPx()
        val arm = 16.dp.toPx()
        // The house brackets, drawn by core-ui (the book cover's own function) instead of a second hand-written copy of the eight
        // strokes. The ink stays the ACCENT, not `palette.outline` (b9's token): outline is a 16% tint (0x29-0x33 alpha), so at the
        // brackets' 0.62 it is a 1.1:1 line on every ground, and its colour at full alpha measures below the accent on 8 of 9 themes
        // (Hel 1.38:1 against 1.84:1). The shared function draws 0.62 where this frame's own copy drew 0.65.
        drawMimirCornerBrackets(accent = accent, insetPx = inset, armPx = arm, widthPx = 1.4.dp.toPx())
        val d = 3.6.dp.toPx()
        // The bottom-center stud occupied the label clearance now used by platform/tome names.
        // Keep the top separator stud and the corner brackets; the lower corners remain unchanged.
        listOf(Offset(size.width / 2f, inset)).forEach { center ->
            val path = Path().apply {
                moveTo(center.x, center.y - d)
                lineTo(center.x + d, center.y)
                lineTo(center.x, center.y + d)
                lineTo(center.x - d, center.y)
                close()
            }
            drawPath(path, accent.copy(alpha = 0.55f))
        }
    }

/**
 * A bound tome (S6): a closed book, not a manila folder. Its authored hero-cover volume, page block,
 * hardware spine, invariant Mannaz, and per-theme corner signature keep both book form and identity visible.
 */
/** The authored three-stop hero volume shared by the closed cover and its open page. */
private fun companionBookBrush(book: CompanionBookTreatment): Brush = Brush.verticalGradient(
    0.00f to book.coverTop,
    0.52f to book.coverMiddle,
    1.00f to book.coverBottom,
)

/** The opened book's frame: authored cover volume, two-hue tooling, and the house corner brackets. */
private fun Modifier.norseBookFrame(book: CompanionBookTreatment): Modifier = this
    .clip(RoundedCornerShape(18.dp))
    .background(companionBookBrush(book))
    .border(1.dp, book.hardware.copy(alpha = 0.54f), RoundedCornerShape(18.dp))
    .drawBehind {
        val inset = 7.dp.toPx()
        drawRoundRect(
            color = book.onCover.copy(alpha = 0.08f),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2f, size.height - inset * 2f),
            cornerRadius = CornerRadius(13.dp.toPx()),
            style = Stroke(0.8.dp.toPx()),
        )
        drawLine(
            color = book.counterAccent.copy(alpha = 0.16f),
            start = Offset(size.width / 2f, 10.dp.toPx()),
            end = Offset(size.width / 2f, size.height - 10.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        drawMimirCornerBrackets(book.hardware, insetPx = 12.dp.toPx(), armPx = 16.dp.toPx(), widthPx = 1.4.dp.toPx())
    }

/**
 * The closed book (CR-13): bound leather, a raised hardware spine, page block, clasp, and embossed Mannaz —
 * the book's own emblem, not a window of peeking icons (that read as a folder).
 * Its name lives on the tile label BELOW, exactly like every console tile — the same placement as every console tile.
 */
/**
 * The cover's corner, derived from its own painted width at draw time.
 *
 * **Why a `Shape` and not `BoxWithConstraints`.** The canon (`MimirNorseBookCover`) reads its width
 * from constraints, but this cover is painted once per lattice tile — nine subcompositions on `HOME`
 * to learn a number the layout already knows. A `Shape` is handed the real size and costs nothing.
 *
 * The rule is core-ui's, not a local invention: `mimirNorseBookCoverCorner(widthDp)`, which is public
 * and reachable from this module. **Policy 2026-08-31**, applying the uniform-corner
 * rule this fork missed — it drew asymmetric 3/3/8/8 corners.
 */
private object CompanionNorseCoverShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val corner = with(density) { mimirNorseBookCoverCorner(size.width / this.density).toPx() }
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(corner, corner)))
    }
}

/**
 * The Tome's closed face on the lattice: core-ui's one book, [MimirNorseBookCover], and nothing of its own. It was a hand-made copy of that cover
 * (its leather, page block, spine, clasp, Mannaz and signature) and is retired by the Thor Bottom revamp's V11: **parameterised, never forked.**
 *
 * What the Bottom's face asks of the canon, all of it additive (every default renders Main's callers as they were):
 * - `showLockup = false`: the tile's own two-line nameplate under the face carries the name, so the face draws no eyebrow, rule or title;
 * - `aspectRatio = CompanionTomeTileAspectRatio`: the lattice's square face, as the platform and app faces are;
 * - `showFocusRing = false`, `focusScale = 1f`: the ring is Main's `mimirSelectionRing` on the face (the tile owns it, outside the clip), and the Bottom's
 *   focus never scales the face;
 * - `focused`: only the cords' brightening, the cover's own focus cue, on the same condition the ring wears.
 *
 * The face is the lattice's measured square and the cover fills it, so the cover's corner (`mimirNorseBookCoverCorner` of its own width) is the same
 * curve the tile's clip ([CompanionNorseCoverShape]) and the ring ([companionFaceCorner]) take.
 */
@Composable
private fun CompanionTomeFace(tile: CompanionHomeTile, focused: Boolean, palette: MimirThemePalette) {
    MimirNorseBookCover(
        focused = focused,
        eyebrow = "",
        title = tile.label,
        modifier = Modifier.fillMaxSize(),
        hasMembers = tile.children.isNotEmpty(),
        focusScale = 1f,
        showFocusRing = false,
        aspectRatio = CompanionTomeTileAspectRatio,
        showLockup = false,
        palette = palette,
    )
}

/** Empty shelf teaches: one card, no dead space pretending to be content. */
@Composable
private fun CompanionHomeTeachingCard(palette: MimirThemePalette) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(palette.surfaceRaised.copy(alpha = 0.4f))
                .border(0.5.dp, palette.outline, RoundedCornerShape(13.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MimirChromeText(
                "Your shelf lives here",
                visualOverride = "ᛗ · YOUR SHELF LIVES HERE",
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
            )
            // QA-04 : the old single sentence ran two unrelated ideas together and
            // read as one instruction -- consoles arriving on their own, and pinning, which is a thing
            // you do. Split so each says what it is, and say plainly that pinning happens on the main
            // screen. Copy only; nothing about what appears here or how pinning works has changed.
            Text(
                "Consoles show up here on their own as you add games.",
                style = MimirTypeScale.Support,
                color = palette.textMuted,
            )
            Text(
                "To pin anything else — a game from your Library, or an Android game or app — favourite it on the main screen.",
                style = MimirTypeScale.Support,
                color = palette.textMuted,
            )
        }
    }
}

/**
 * Real APK icon decoded off-main (the drawer pattern) and resolved by the grid tile ([rememberCompanionArtSite]): the
 * icon draws once loaded, the letter only for an app with no icon (or whose icon file is gone), and nothing while the icon loads or
 * if it failed (R3, R8). The raised square behind a loaded icon is the icon's own ground and fades in with it.
 */
@Composable
private fun CompanionAppIcon(tile: CompanionHomeTile, art: CompanionArtSite, decode: Boolean = true) {
    val palette = LocalMimirPalette.current
    // Receipt: `plate=visible` is the letter this site actually draws, which only an unwired app wears; a wired icon that is loading
    // reads `hasArt=true plate=hidden`. Written only for a tile in the decode window (N3).
    BottomUiArtReceipt(
        tile = "app." + tile.id,
        hasArt = art.facts.hasArt,
        loaded = art.facts.loaded,
        failed = art.facts.failed,
        plateVisible = art.facts.plateVisible,
        enabled = decode,
    )
    val groundColor = palette.surfaceRaised.copy(alpha = 0.6f)
    Box(
        modifier = Modifier
            .size(CompanionLatticeIconDp.dp)
            .clip(mimirAppIconShape(CompanionLatticeIconDp.dp))
            .testTag(CompanionLatticeIconTag)
            .drawBehind {
                val alpha = art.reveal.value
                if (alpha > 0f) drawRect(groundColor, alpha = alpha)
            },
        contentAlignment = Alignment.Center,
    ) {
        when (art.draw) {
            CompanionArtDraw.ART -> CompanionArtImage(art, tile.label, Modifier.fillMaxSize())
            CompanionArtDraw.FALLBACK -> Text(
                text = tile.label.take(1).uppercase(),
                style = MimirTypeScale.Row,
                color = palette.accent,
                maxLines = 1,
                modifier = Modifier.graphicsLayer { alpha = art.reveal.value },
            )
            CompanionArtDraw.NOTHING -> Unit
        }
    }
}

/** The decoded art of [art] as an Image revealed by its alpha (a layer read, so the fade never recomposes). */
@Composable
private fun CompanionArtImage(art: CompanionArtSite, description: String?, modifier: Modifier) {
    val bitmap = art.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            modifier = modifier.graphicsLayer { alpha = art.reveal.value },
        )
    }
}

/** The resting ground of an app's face: its fill, drawn at [alpha] (read in the draw phase) and clipped by the face's own corner. */
private fun Modifier.companionAppGroundFill(fill: Brush, alpha: () -> Float): Modifier = drawBehind {
    val a = alpha()
    if (a > 0f) drawRoundRect(brush = fill, cornerRadius = CornerRadius(CompanionAppFaceCorner.toPx()), alpha = a)
}

/** An app face's outline, drawn over its content at [alpha] (draw phase), the same inset stroke `Modifier.border` draws. */
private fun Modifier.companionAppGroundBorder(width: Dp, color: Color, alpha: () -> Float): Modifier = drawWithContent {
    drawContent()
    val a = alpha()
    if (a > 0f && color.alpha > 0f) {
        val stroke = width.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius((CompanionAppFaceCorner.toPx() - stroke / 2f).coerceAtLeast(0f)),
            style = Stroke(width = stroke),
            alpha = a,
        )
    }
}

/**
 * a PLATFORM tile's user-picked SGB cover ([CompanionHomeTile.artPath]) — decoded off-main like
 * the app icon and cropped to fill the tile's art region, so the same override that covers the primary Library tile
 * covers screen 2 too. Falls back to the drawn console emblem while the decode is pending or if it fails (a stale /
 * deleted pick never leaves the tile blank).
 */
/**
 * Decoded companion picked-platform-art bitmaps, kept ACROSS tile remounts so switching platform surfaces on the
 * companion lands ON the art instead of flashing the console glyph while a fresh decode runs. Keyed by the art URI (carries the file mtime); bounded, ~one per platform.
 */
// PERF-B: budgeted in BYTES + bounded decode — same contract as the Library shelf's cache (the two are
// warmed together by Land-Warm T4); see MimirCoverDecode.kt.
// Measured: 1024² sources decode to 4 MiB at default cap and 20 covers exceed 48 MiB; Bottom faces are ~200 px.
// The primary fallback is under 512 px, so a 512 px cap keeps quality and makes 20 covers ≈20 MiB.
internal const val CompanionPlatformCoverCapPx = 512
private val companionPlatformCoverCache =
    object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: androidx.compose.ui.graphics.ImageBitmap): Int =
            value.asAndroidBitmap().byteCount
    }

/**
 * System memory trim: trims the Bottom's decoded platform covers and app icons to half their budgets, least
 * recently used first, and returns the bytes let go. Only a system trim asks for this. The Bottom stays live through
 * a game and reads its mounted art every paint, so that art is the most recently used and stays found; a miss would
 * run the cold decode and the reveal fade (fix round: an `evictAll` here did exactly that on the next page change).
 */
fun trimCompanionArtCachesToHalf(): Int {
    val before = companionPlatformCoverCache.size() + companionAppIconCache.size()
    companionPlatformCoverCache.trimToSize(companionPlatformCoverCache.maxSize() / 2)
    companionAppIconCache.trimToSize(companionAppIconCache.maxSize() / 2)
    return before - companionPlatformCoverCache.size() - companionAppIconCache.size()
}

/**
 * R01 "Land Warm" T4 : pre-decode picked platform art into [companionPlatformCoverCache]
 * behind the cold-start gate so screen 2's platform tiles land ON the art — the same decode + key contract
 * as [CompanionPlatformArtCover]'s own cold path (strip scheme+query, BitmapFactory off-main, key = raw URI).
 */
object CompanionCoverArtWarmer {
    suspend fun prewarm(raw: String) {
        if (companionPlatformCoverCache.get(raw) != null) return
        val fsPath = android.net.Uri.parse(raw).path ?: raw
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            runCatching {
                mimirDecodeBoundedCoverBitmap(fsPath, capPx = CompanionPlatformCoverCapPx)?.asImageBitmap()
            }.getOrNull()
        }?.let { companionPlatformCoverCache.put(raw, it) }
    }

    /**
     * Pre-decode one app icon into [companionAppIconCache], the same contract as the three icon sites' own cold path, so the dock
     * seats and the lattice's first page land ON their icons. A path already warm, a file that is gone and one that does not decode
     * all return without a result; nothing waits on this.
     */
    suspend fun prewarmIcon(
        path: String,
        wired: (String) -> Boolean = { mimirArtSourceWired(it) },
        decode: (String) -> ImageBitmap? = { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() },
    ) {
        if (companionAppIconCache.get(path) != null) return
        decodeCompanionIcon(path, wired, decode)
    }
}

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
private fun CompanionPlatformArtCover(
    tile: CompanionHomeTile,
    glyphBrush: Brush,
    decode: Boolean = true,
    site: String = "grid",
) {
    val palette = LocalMimirPalette.current
    // read the decode cache SYNCHRONOUSLY so a warm revisit paints on frame 0 (no
    // console-glyph fallback frame); only a first/cold decode runs async and caches its result. The probe is keyed on the decode
    // window too: it is refreshed when the tile enters the window (the cache may have filled meanwhile, so it does not decode again
    // for nothing) and released when it leaves, and a warm hit never outlives decode=false.
    val cachedCover = remember(tile.artPath, decode) { if (decode) tile.artPath?.let { companionPlatformCoverCache.get(it) } else null }
    remember(tile.artPath, site) {
        if (site == "lifted" && cachedCover != null && companionArtDebugLoggingEnabled()) {
            Log.i(CompanionArtLogTag, "warm site=lifted tile=${tile.id}")
        }
    }
    val produced = rememberIdentityProducedValue(tile.artPath, decode) {
        if (!decode || cachedCover != null) {
            // A warm cover is already painted synchronously above. Do not decode it again when the
            // tile remounts after a surface switch; the identity key still isolates every URI.
            null
        } else {
            // This branch is the cold path: decode is enabled and the synchronous cache read missed.
            if (tile.artPath != null && companionArtDebugLoggingEnabled()) {
                Log.i(
                    CompanionArtLogTag,
                    "cold-decode site=$site tile=${tile.id} artPath=${tile.artPath} " +
                        "cacheSize=${companionPlatformCoverCache.size()} " +
                        "hits=${companionPlatformCoverCache.hitCount()} " +
                        "misses=${companionPlatformCoverCache.missCount()}",
                )
            }
            tile.artPath?.let { raw -> decodeCompanionPlatformCover(raw) }
        }
    }
    val warmCover = remember(cachedCover) { cachedCover?.let { CompanionDecodedArt(it) } }
    // A warm hit paints without a stat, so its file is verified off-main (R8a): a deleted pick drops the cache entry and the tile
    // takes its console glyph after at most a stale frame.
    val goneCover = rememberCompanionWarmGone(
        identity = tile.artPath,
        source = tile.artPath?.let { raw -> android.net.Uri.parse(raw).path ?: raw },
        warm = cachedCover != null,
        drop = { tile.artPath?.let { companionPlatformCoverCache.remove(it) } },
    )
    val art = rememberCompanionArtSite(
        hasSource = tile.artPath != null,
        decoded = goneCover ?: warmCover ?: produced,
        identity = tile.artPath,
        window = decode,
    )
    // Receipt: `plate=visible` is the console glyph this site actually draws. A tile with its art wired and still loading, or
    // failed, draws NOTHING, so it reads `hasArt=true state=loading plate=hidden` (a failed one reads
    // `hasArt=true state=failed plate=hidden`, no breach); only an unwired tile (no pick, or its picked file gone, R8a) wears the glyph. Written only for a
    // tile in the decode window, so a page the player is not on stays silent. A warm cover is loaded.
    BottomUiArtReceipt(
        tile = "platform." + tile.id,
        hasArt = art.facts.hasArt,
        loaded = art.facts.loaded,
        failed = art.facts.failed,
        plateVisible = art.facts.plateVisible,
        enabled = decode,
    )
    when (art.draw) {
        CompanionArtDraw.ART -> art.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = tile.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(CompanionPlatformArtCorner))
                    .graphicsLayer { alpha = art.reveal.value },
            )
        }
        CompanionArtDraw.FALLBACK -> PlatformConsoleArt(
            platformIdValue = tile.id,
            tint = palette.accent,
            tintBrush = glyphBrush,
            modifier = Modifier.fillMaxSize(CompanionPlatformFaceGlyphFraction),
        )
        CompanionArtDraw.NOTHING -> Unit
    }
}

/**
 * The cold decode of a picked platform cover: the store holds a `file://...?v=...` URI (a cache-buster Coil consumes on the primary
 * shelf), but BitmapFactory needs the bare filesystem path, so strip the scheme and query first (a plain path resolves to itself).
 * A file that no longer exists is the pick gone (R8a): [CompanionDecodedArt.sourceMissing], unwired, the console glyph. A file that
 * exists and does not decode is FAILED, logged, and draws nothing (R8).
 */
private suspend fun decodeCompanionPlatformCover(raw: String): CompanionDecodedArt = withContext(Dispatchers.IO) {
    val fsPath = android.net.Uri.parse(raw).path ?: raw
    if (!mimirArtSourceWired(fsPath)) {
        CompanionDecodedArt(bitmap = null, sourceMissing = true)
    } else {
        val decoded = runCatching {
            mimirDecodeBoundedCoverBitmap(fsPath, capPx = CompanionPlatformCoverCapPx)?.asImageBitmap()
        }.getOrNull()
        if (decoded != null) {
            companionPlatformCoverCache.put(raw, decoded)
        } else {
            runCatching { Log.w(MimirArtFailureLogTag, "bottom platform cover failed to decode; drawing nothing: $fsPath") }
        }
        CompanionDecodedArt(decoded)
    }
}

/**
 * **Create Tome, as a shelf cell.** it used to own a whole footer line for one
 * verb, on a panel where 86 dp is the difference between a lattice and a list.
 *
 * It is deliberately NOT a console: no leather, no art square, no rim. A dashed seat says *this is a
 * place where something could be* — the same thing the empty quick-launch seat's `+` says, in the
 * shelf's own geometry rather than the rail's. §4's pill ban does not reach it: it is a rectangle with
 * the tile's own corner, which is what the ban asks for.
 */
@Composable
internal fun CompanionCreateTomeTile(visible: Boolean = true, onCreate: () -> Unit) {
    val palette = LocalMimirPalette.current
    val stroke = remember { PathEffect.dashPathEffect(floatArrayOf(9f, 9f), 0f) }
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) {
            tween(CompanionDragSettleMillis, easing = MimirMotion.Easing)
        } else {
            snap()
        },
        label = "create-tome-fade",
    )
    // The Add Tome selection face must match the platform icon size.
    //
    // Laid out from the same cell solve as every tile (A1): the cursor ring's square is top-aligned and centred, the
    // dashed seat is the FACE rect inside it (exactly the size a platform face is), and the name goes in the band
    // under it, as every other tile labels. The "+" stays in the face and is sized to it, so a large font scale can
    // shrink the face but never squeeze the "+" out of it; the word has the band, which the solver sizes for the
    // font scale. The tap target is the whole cell, as for every tile.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val fontScale = LocalDensity.current.fontScale
        val cell = remember(maxWidth, maxHeight, fontScale) {
            solveCompanionLatticeCell(maxWidth.value, maxHeight.value, fontScale)
        }
        // The "+" is a glyph the face holds: its line box (sp x fontScale) is at most 60% of the face edge.
        val plusSp = minOf(MimirTypeScale.Title.fontSize.value, cell.faceEdgeDp * 0.6f / fontScale).coerceAtLeast(1f)
        // The "+" tile is a lattice tile: it answers a touch as the others do (R4, B5).
        val createSource = remember { MutableInteractionSource() }
        Column(
            Modifier
                .fillMaxSize()
                .then(
                    if (visible) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onPress = { mimirReportPress(createSource, it) }, onTap = { onCreate() })
                        }
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (visible) {
                        Modifier.semantics { onClick(label = "Create a Tome") { onCreate(); true } }
                    } else {
                        Modifier
                    },
                )
                .mimirPressResponse(createSource),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(cell.ringEdgeDp.dp), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .testTag(CompanionCreateTomeFaceTag)
                        .size(cell.faceEdgeDp.dp)
                        .graphicsLayer { this.alpha = alpha }
                        .clip(RoundedCornerShape(13.dp))
                        .drawBehind {
                            drawRoundRect(
                                color = palette.accent.copy(alpha = 0.34f),
                                cornerRadius = CornerRadius(13.dp.toPx()),
                                style = Stroke(width = 1.2.dp.toPx(), pathEffect = stroke),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+",
                        style = MimirTypeScale.Title.copy(
                            fontSize = plusSp.sp,
                            lineHeight = plusSp.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                            // Trim.Both: the line box is exactly the line height (Trim.None keeps the font's own ascent
                            // and descent on a single line, which is taller), so the "+" can be sized to the face.
                            lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            ),
                        ),
                        color = palette.accent.copy(alpha = 0.72f),
                        maxLines = 1,
                        modifier = Modifier.testTag(CompanionCreateTomePlusTag),
                    )
                }
            }
            Spacer(Modifier.height((cell.labelTopDp - cell.ringEdgeDp).dp))
            MimirChromeText(
                "TOME",
                style = CompanionCreateTomeWordStyle,
                color = palette.accent.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
                maxLines = cell.labelLines.coerceAtLeast(1),
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .testTag(CompanionCreateTomeWordTag)
                    .graphicsLayer { this.alpha = alpha }
                    .fillMaxWidth()
                    .padding(horizontal = cell.labelInsetDp.dp)
                    .height(cell.bandDp.dp)
                    .clipToBounds(),
            )
        }
    }
}

/**
 * a companion PLATFORM tile's centered rounded SQUARE — the picked SGB cover fills it, or the
 * console glyph sits on a rounded panel. Its OWN composable so the grid's `when` arm stays a single call (an inline
 * block arm with a local val + if/else tripped the Compose IR lowering, "Empty collection can't be reduced"). The
 * square is the face the lattice-cell solve hands it, so the art fills the cursor ring; the platform's name is the
 * band the grid tile draws under that ring, not part of this art.
 */
@Composable
private fun CompanionPlatformTileArt(
    tile: CompanionHomeTile,
    glyphBrush: Brush,
    decode: Boolean,
    site: String,
) {
    val palette = LocalMimirPalette.current
    // **One square, computed above the branch.** The box has a 1:1 aspect ratio; a custom icon
    // replaces both the box and its art with a 1:1 icon face.
    // A custom icon does not sit inside the face, it IS the face: same square, same edges, no inset and
    // no frame of its own. Computing the square once and handing it to both arms is what makes that
    // true by construction — two arms each building their own would be two sizes one edit apart.
    //
    // **The square is the SMALLER edge, and it is measured rather than assumed.**
    //
    // `aspectRatio` cannot do this: it squares by whichever constraint it is told to match, so
    // width-first overflows a cell that is wider than tall and height-first overflows one that is
    // taller. The lattice cell is wider than tall and a tome page (104 x 122) is taller than wide, so
    // EITHER fixed choice clips somewhere. Width-first shipped for one build and clipped every console
    // glyph in the lattice. The previous `fillMaxHeight().aspectRatio(1f)` was correct for the lattice by luck rather
    // than by reasoning; replacing luck with the wrong reasoning made it worse.
    //
    // `BoxWithConstraints` gives the real numbers, so the square fits both containers by construction
    // and cannot be broken again by a change to either one's shape.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val edge = minOf(maxWidth, maxHeight)
        val square = Modifier.size(edge)
        if (tile.artPath != null) {
            Box(square) {
                CompanionPlatformArtCover(
                    tile = tile,
                    glyphBrush = glyphBrush,
                    decode = decode,
                    site = site,
                )
            }
        } else {
        // **The inner box is retired — ** The glyph used to sit inside a second rounded rectangle, filled with the tile's own
        // brush, inside the tile's own rounded rectangle. Two nested containers of the same shape and
        // nearly the same ink, so the console read as a picture of a card rather than as a mark on the
        // shelf — and the inner one was invisible until you looked for it, which is the worst kind of
        // decoration: it cost contrast and gave nothing back.
        //
        // **The glyph grows into what the box was spending.** It was 72% of a square already inset
        // inside the tile; it is 92% now, of the same square with no frame around it. Retiring the box without growing the mark
        // would just leave a smaller console floating in more emptiness.
            Box(square, contentAlignment = Alignment.Center) {
                PlatformConsoleArt(
                    platformIdValue = tile.id,
                    tint = palette.accent,
                    tintBrush = glyphBrush,
                    modifier = Modifier.fillMaxSize(CompanionPlatformFaceGlyphFraction),
                )
            }
        }
    }
}

/** S2-01 keeps Q1–Q4 at their stored positions; Q5 takes the former collection-switch position. */
internal fun companionQuickLaunchSlotOrder(slotCount: Int): List<Int> {
    if (slotCount <= 0) return emptyList()
    if (slotCount <= 4) return (0 until slotCount).toList()
    return buildList {
        add(4)
        addAll((0 until slotCount).filter { it != 4 })
    }
}

private data class CompanionQuickLaunchPinActions(
    val slot: Int,
    val packageName: String,
)

/**
 * QA D11: a covered rail takes no input. Attached ONLY while covered: an idle pointer node on the rail's Row would turn
 * the gaps between seats into a hit target and stop a tap there reaching what lies beneath -- the recents drawer's
 * dismiss scrim, where this rail is the drawer's own. Consumes on the Initial pass, so no seat's clickable ever sees an
 * unconsumed down, and clears the rail from semantics so an accessibility action cannot press what is not drawn.
 */
private val CompanionCoveredRailModifier: Modifier = Modifier
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
            }
        }
    }
    .clearAndSetSemantics { }

private suspend fun PointerInputScope.detectCompanionQuickLaunchHandleGesture(
    thresholdPx: Float,
    enabled: () -> Boolean,
    onToggle: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        down.consume()
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: return@awaitEachGesture
            if (change.pressed) {
                change.consume()
                continue
            }
            change.consume()
            // A gesture that began live can end after the surface stopped taking input (the dock began to leave under it).
            if (!enabled()) return@awaitEachGesture
            when (
                companionQuickLaunchDockGesture(
                    deltaX = change.position.x - down.position.x,
                    deltaY = change.position.y - down.position.y,
                    touchSlop = thresholdPx,
                )
            ) {
                CompanionQuickLaunchDockGesture.SWIPE_UP -> onSwipeUp()
                CompanionQuickLaunchDockGesture.SWIPE_DOWN -> onSwipeDown()
                CompanionQuickLaunchDockGesture.TAP -> onToggle()
                CompanionQuickLaunchDockGesture.NONE -> Unit
            }
            return@awaitEachGesture
        }
    }
}

/**
 * A visible, touch-sized fallback for the hideable rail. It is absent from semantics while blocked.
 *
 * [enabled] is how it LOOKS (the policy: a blocked handle is dim). [gesturesActive] is whether it TAKES input, and defaults to
 * [enabled]: the expanded handle sits on the dock's fade layer, which is not live while it arrives or leaves, so it passes false
 * then and keeps its look. An inactive handle installs no gesture detector at all (the detector reads unconsumed-or-not as it
 * pleases, so an ancestor's shield cannot stop it) and clears its semantics.
 */
@Composable
private fun CompanionQuickLaunchHandle(
    expanded: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    gesturesActive: Boolean = enabled,
    /** The hit zone, centred on the bottom edge; the visible handle (96 x 18 dp) stays at the bottom of it. Never smaller than the visible handle. */
    zoneWidthDp: Float = CompanionQuickLaunchHandleWidthDp,
    zoneHeightDp: Float = CompanionQuickLaunchHandleHeightDp,
    onToggle: () -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val viewConfiguration = LocalViewConfiguration.current
    val live = enabled && gesturesActive
    val latestEnabled = rememberUpdatedState(live)
    val latestToggle = rememberUpdatedState(onToggle)
    val latestSwipeUp = rememberUpdatedState(onSwipeUp)
    val latestSwipeDown = rememberUpdatedState(onSwipeDown)
    val label = if (expanded) "Collapse Quick Launch" else "Reveal Quick Launch"
    val handleModifier = if (live) {
        modifier
            .pointerInput(Unit) {
                detectCompanionQuickLaunchHandleGesture(
                    thresholdPx = viewConfiguration.touchSlop,
                    enabled = { latestEnabled.value },
                    onToggle = { latestToggle.value() },
                    onSwipeUp = { latestSwipeUp.value() },
                    onSwipeDown = { latestSwipeDown.value() },
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = label
                onClick(label = label) {
                    latestToggle.value()
                    true
                }
            }
    } else {
        modifier.clearAndSetSemantics { }
    }
    // The gestures and the semantics sit on the ZONE, which is only wider and taller than the handle you see; the handle is drawn at the bottom of it, exactly as it was.
    Box(
        modifier = handleModifier
            .height(maxOf(zoneHeightDp, CompanionQuickLaunchHandleHeightDp).dp)
            .width(maxOf(zoneWidthDp, CompanionQuickLaunchHandleWidthDp).dp)
            .testTag(CompanionQuickLaunchHandleTag),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Box(
            modifier = Modifier
                .height(CompanionQuickLaunchHandleHeightDp.dp)
                .width(CompanionQuickLaunchHandleWidthDp.dp)
                .testTag(CompanionQuickLaunchHandleVisualTag)
                .clip(RoundedCornerShape(9.dp))
                .background(palette.surfaceHigh.copy(alpha = if (enabled) 0.18f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (enabled) palette.accent.copy(alpha = 0.72f)
                        else palette.outline.copy(alpha = 0.28f),
                    ),
            )
        }
    }
}

/**
 * A test seam, like the app's `HomeQuickLaunchPickerFadeProbe`: what the expanded dock and the returning collapsed handle are
 * painted at, so a host test can step a close frame by frame and see that the two are never both above the shown threshold.
 * Both read 0 while their layer is not composed.
 */
class CompanionQuickLaunchDockProbe {
    var dockAlpha: () -> Float = { 0f }
    var handleAlpha: () -> Float = { 0f }
}

/** General plus the pinned slots and its picker, reusable by base scenes and full-screen companion tools. */
@Composable
fun CompanionQuickLaunchLayer(
    /** Reports when the picker opens or closes, so a host can treat the surface behind it. */
    onPickerOpenChanged: (Boolean) -> Unit = {},
    /**
     * The All apps picker (Quick Launch: the rail's grid seat, or [generalPickerRequest]) took the screen (true) or gave it back (false). A slot's own assign picker and the pin
     * actions are not it; they report only through [onPickerOpenChanged]. A host that draws a panel of its own under this layer (the Tabs drawer) clears it while this is true.
     */
    onAllAppsPickerShown: (Boolean) -> Unit = {},
    /** The All apps picker was closed with no pick (its Close chip or its scrim). Called right after [onAllAppsPickerShown] (false); a pick, or a native close request, is not it. */
    onAllAppsPickerDismissed: () -> Unit = {},
    /** Null when this host offers no instruments panel; otherwise whether it is currently up. */
    instrumentsShown: Boolean? = null,
    onToggleInstruments: () -> Unit = {},
    slots: List<String?>,
    apps: List<CompanionAppEntry>,
    /** Whether [apps] is an answer or its starting value; forwarded to the rail's seat plan. */
    catalogLoaded: Boolean = true,
    launchLocked: Boolean,
    launchMessage: String?,
    /** What the cursor is on. Carried through to the rail's line, which now ranks it last. */
    selectionLabel: String? = null,
    onLaunch: (String) -> Unit,
    onAssign: (Int, String) -> Unit,
    onClear: (Int) -> Unit = {},
    onPinnedLaunch: (String) -> Unit = onLaunch,
    quickLaunchChoice: CompanionQuickLaunchChoice? = null,
    onQuickLaunchChoice: (CompanionQuickLaunchChoice, CompanionQuickLaunchDestination) -> Unit = { _, _ -> },
    onQuickLaunchChoiceDismiss: (CompanionQuickLaunchChoice) -> Unit = {},
    onQuickLaunchEdit: ((String) -> Unit)? = null,
    /**
     * QA D11: another window's drawer covers this rail with its own. The rail fades out over that drawer's enter
     * timing and takes no input while covered. The picker and the dialogs this layer hosts are unchanged.
     */
    railCovered: Boolean = false,
    /**
     * The host has cleared its own surface for the All apps picker (the Tabs drawer clears its panel), so this rail goes with it: the picker's card stops short of the
     * bottom edge, and the seats' lower ends showed under it as cut slivers. The rail fades out on the drawer panel's own leave clock and back on it, and takes no input
     * and has no semantics while it is hidden. A hidden rail is still composed, so the fade has something to fade.
     */
    railHidden: Boolean = false,
    /** QA D17: a tap a locked seat dropped, with the seat's package, `all_apps`, or `empty_q<N>`. */
    onQuickLaunchIgnored: (target: String) -> Unit = {},
    /**
     * The Bottom ᛗ menu's Quick launch hosts this layer in a window of its own, over a
     * Bottom that already draws a rail. False leaves the rail out and keeps the picker and its dialogs.
     */
    railVisible: Boolean = true,
    /**
     * O5: opens the All apps picker, as the rail's All apps seat does, once for each new non-null value. A host with no
     * rail ([railVisible] false) has no seat to open it from.
     */
    generalPickerRequest: Long? = null,
    /**
     * R6-P2-08 (finding 7, step 3): a native EdgeBack close for whichever of [pickerSlot]/[pinActions] is
     * open, once for each new non-null value — the close-side counterpart to [generalPickerRequest].
     */
    closePickerRequest: Long? = null,
    /** The secondary surface's rail starts collapsed; modal hosts keep their existing always-visible rail. */
    hideableDock: Boolean = false,
    dockExpanded: Boolean = true,
    onDockExpandedChanged: (Boolean) -> Unit = {},
    /** Blocks the handle while another surface owns the scene or the shelf is being dragged. */
    dockInteractionBlocked: Boolean = false,
    /** A test seam (see [CompanionQuickLaunchDockProbe]); null in production. */
    dockProbe: CompanionQuickLaunchDockProbe? = null,
    /** The COLLAPSED handle's hit zone is widened (see [companionQuickLaunchHandleZoneWidthDp], [companionQuickLaunchHandleZoneHeightDp]) only here; its visible handle is always 96 x 18 dp. The expanded handle's zone is its visible size. */
    handleZoneWidened: Boolean = false,
    hintFooterShown: Boolean = false,
    /**
     * The surface whose window this layer is composed inside, for the picker's, pin actions' and launch prompt's receipts: null
     * for the Presentation's own surface group, [BottomSurface.TABS] for the Tabs drawer, which composes the layer above its panel.
     * It only decides where the stack snapshot sorts them ([BottomSurface.drawingLayer]); nothing it draws changes.
     */
    receiptHost: String? = null,
    modifier: Modifier = Modifier,
) {
    var pickerSlot by remember { mutableStateOf<Int?>(null) }
    var pinActions by remember { mutableStateOf<CompanionQuickLaunchPinActions?>(null) }

    // Warm the dock seats' icons (R3) on first composition, so the rail, which is not composed until the dock opens, lands ON its
    // icons instead of loading them. The catalog starts empty and fills from a sweep, so this re-runs when it answers.
    val dockIcons = remember(slots, apps) {
        val byPackage = apps.associateBy { it.packageName }
        slots.mapNotNull { pkg -> pkg?.let(byPackage::get)?.iconPath?.takeIf(String::isNotBlank) }
    }
    LaunchedEffect(dockIcons) {
        dockIcons.forEach { path -> CompanionCoverArtWarmer.prewarmIcon(path) }
    }

    fun openPicker(slot: Int) {
        pickerSlot = slot
        // Synchronous handoff: the Presentation may have a sibling panel above this layer. Waiting for
        // a LaunchedEffect would leave the first picker frame underneath that panel.
        onPickerOpenChanged(true)
        if (slot < 0) onAllAppsPickerShown(true)
    }

    fun closePicker() {
        val wasAllApps = (pickerSlot ?: 0) < 0
        pickerSlot = null
        onPickerOpenChanged(false)
        if (wasAllApps) onAllAppsPickerShown(false)
    }

    fun openPinActions(slot: Int, packageName: String) {
        pinActions = CompanionQuickLaunchPinActions(slot, packageName)
        onPickerOpenChanged(true)
    }

    fun closePinActions() {
        pinActions = null
        onPickerOpenChanged(false)
    }

    LaunchedEffect(generalPickerRequest) {
        if (generalPickerRequest != null) openPicker(-1)
    }

    // R6-P2-08 re-review finding A (defensive here too): this layer's own composable identity can be
    // remounted by a host toggling its visibility, which would otherwise let a stale `closePickerRequest`
    // read as new on entry. Same per-mount floor as CompanionFolderPage's `initialCloseRequest`.
    val initialClosePickerRequest = remember { closePickerRequest }
    LaunchedEffect(closePickerRequest) {
        if (closePickerRequest == null || closePickerRequest == initialClosePickerRequest) return@LaunchedEffect
        if (pinActions != null) closePinActions() else closePicker()
    }

    // QA D11 (2026-09-11): under another window's drawer this rail stands down. The overlay-hosted Tabs drawer draws its
    // own rail over it (CompanionRecentsOverlayHost), and two rails whose seats disagree read as one doubled, misaligned
    // strip (evidence S20). B1: the hand-over runs the drawer's own clocks, so it is a cross-fade of two identical rails: to 0 on the
    // overlay arrival (the 130 ms lead, then 540 ms) as the drawer arrives, and back to 1 on the overlay leave (180 ms) as it leaves.
    // Read only inside `graphicsLayer`: the ramp redraws a layer and never recomposes.
    val railReducedMotion = remember(railCovered) { bottomUiReducedMotion() }
    val railAlpha by animateFloatAsState(
        targetValue = if (railCovered) 0f else 1f,
        animationSpec = if (railCovered) mimirOverlayArriveSpec(railReducedMotion) else mimirOverlayLeaveSpec(),
        label = "quick-rail-covered",
    )
    // [railHidden]: the drawer panel's own clock (a fade out over OverlayMs, a cut under reduced motion), so the rail and the panel leave together.
    val railHiddenReducedMotion = remember(railHidden) { bottomUiReducedMotion() }
    val railHiddenAlpha by animateFloatAsState(
        targetValue = if (railHidden) 0f else 1f,
        animationSpec = if (railHiddenReducedMotion) snap() else mimirOverlayLeaveSpec(),
        label = "quick-rail-hidden",
    )
    val resolvedDockExpanded = !hideableDock || dockExpanded
    // The dock's arrival and departure (A4, T5): the page tier's lead and slow fade in, OverlayMs out, the clocks Main's dock
    // runs. The Bottom is touch-only, so the swap gate below passes holdPad = false and keeps only its touch half: a Bottom
    // dock that held the process-wide pad gate would deafen Main's controller for the lead.
    val dockVisible = hideableDock && railVisible && resolvedDockExpanded
    // Read at EACH edge, never remembered for the mount: [mimirReducedMotion] caches the animator scale for the composition's whole
    // life, so a dock mounted under "remove animations" and used after they came back would keep writing cuts for fades that run
    // (and the reverse). The one value below feeds the enter, the exit, the receipts and the probe, so what is drawn and what is
    // reported are the same answer.
    val dockReducedMotion = remember(dockVisible) { bottomUiReducedMotion() }
    val dockArriveSpec = mimirOverlayArriveSpec(dockReducedMotion)
    // A cut leaves on a snap as well as with no exit transition: the probe below rides this spec on the same transition, and
    // AnimatedVisibility keeps its content composed until every animation on it has settled, so a 180 ms leave here would keep the
    // dock drawn at full alpha for those 180 ms under a receipt that says it was cut.
    val dockLeaveSpec: FiniteAnimationSpec<Float> = if (dockReducedMotion) snap() else mimirOverlayLeaveSpec()
    // Whether the expanded dock is drawn above [MimirPageMotion.ShownAlpha]; the collapsed handle waits for it to be false.
    val dockOnScreen = remember { mutableStateOf(false) }
    // One generation per open, as Main's dock keys on: a reopen inside the dismiss fade must remount the content so the gate
    // reads a fresh composition. The count advances only on a false -> true edge of [dockVisible].
    val dockEpochCounter = remember { intArrayOf(0) }
    val dockOpenEpoch = remember(dockVisible) { if (dockVisible) ++dockEpochCounter[0] else dockEpochCounter[0] }
    // Which edge of [dockVisible] this is: 0 for the composition the layer was first composed in. A dock that is MOUNTED already
    // expanded entered nothing (an AnimatedVisibility that starts visible runs no enter), so that edge names no transition.
    val dockEdgeCounter = remember { intArrayOf(0) }
    val dockEdgeIndex = remember(dockVisible) { dockEdgeCounter[0]++ }
    // Receipts. Every edge that runs the dock's fade names it; an edge that does not says why instead of naming a fade that never
    // ran: `transition=none reason=initial` for a dock mounted open, `reason=reduced_motion` under a zero animator scale (the
    // surface edge is a deliberate cut, as `theme.change` writes it), and neither arms a watch or runs the probe below.
    val dockCutReason = when {
        dockVisible && dockEdgeIndex == 0 -> BottomUiReceiptLog.REASON_INITIAL
        dockReducedMotion -> BottomUiReceiptLog.REASON_REDUCED_MOTION
        else -> null
    }
    BottomUiSurfaceReceipt(
        surface = BottomSurface.DOCK,
        visible = dockVisible,
        transition = if (dockCutReason != null) null else if (dockVisible) "dock.enter" else "dock.exit",
        reason = dockCutReason,
    )
    BottomUiSurfaceReceipt(
        surface = BottomSurface.PICKER,
        visible = pickerSlot != null,
        transition = if (pickerSlot != null) "picker.enter" else "picker.exit",
        host = receiptHost,
    )
    BottomUiSurfaceReceipt(
        surface = BottomSurface.PIN_ACTIONS,
        visible = pinActions != null,
        transition = if (pinActions != null) "pin-actions.enter" else "pin-actions.exit",
        host = receiptHost,
    )
    BottomUiSurfaceReceipt(
        surface = BottomSurface.LAUNCH_PROMPT,
        visible = quickLaunchChoice != null,
        transition = if (quickLaunchChoice != null) "launch-prompt.enter" else "launch-prompt.exit",
        host = receiptHost,
    )
    val quickControl = rememberBottomUiControl(BottomSurface.PICKER)
    val dockControl = rememberBottomUiControl(BottomSurface.DOCK)
    val handleEnabled = companionQuickLaunchDockHandleEnabled(
        hideableDock = hideableDock,
        railVisible = railVisible,
        interactionBlocked = dockInteractionBlocked,
        railCovered = railCovered,
        launchLocked = launchLocked,
        pickerOpen = pickerSlot != null,
        pinActionsOpen = pinActions != null,
        choiceOpen = quickLaunchChoice != null,
        instrumentsShown = instrumentsShown,
    )
    val latestHandleEnabled = rememberUpdatedState(handleEnabled)
    val toggleDock = {
        if (handleEnabled) {
            dockControl("dock.toggle", if (resolvedDockExpanded) "collapse" else "expand")
            onDockExpandedChanged(!resolvedDockExpanded)
        }
    }
    // The rail, shared by the two ways it is shown: plain for a host whose dock does not hide, and inside the dock's fade
    // layer for one that does.
    val railContent: @Composable BoxScope.() -> Unit = {
        CompanionQuickLaunchRow(
            instrumentsShown = instrumentsShown,
            onToggleInstruments = onToggleInstruments,
            slots = slots,
            apps = apps,
            catalogLoaded = catalogLoaded,
            launchLocked = launchLocked,
            launchMessage = launchMessage,
            selectionLabel = selectionLabel,
            onLaunch = onLaunch,
            onPinnedLaunch = onPinnedLaunch,
            onOpenPicker = ::openPicker,
            onOpenPinActions = ::openPinActions,
            onQuickLaunchIgnored = onQuickLaunchIgnored,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer { alpha = railAlpha * railHiddenAlpha }
                .then(if (railCovered || railHidden) CompanionCoveredRailModifier else Modifier),
        )
    }
    Box(modifier = modifier) {
        // A host whose dock does not hide (`hideableDock = false`) keeps its rail exactly as it was: a plain `if`.
        if (!hideableDock && railVisible) railContent()
        // The expanded dock: scrim, rail and collapse handle on ONE screen-sized fade layer, so no seat rim is cut.
        AnimatedVisibility(
            visible = dockVisible,
            enter = if (dockReducedMotion) EnterTransition.None else fadeIn(dockArriveSpec),
            exit = if (dockReducedMotion) ExitTransition.None else fadeOut(dockLeaveSpec),
        ) {
            // Only an edge that runs the fade names it (see [dockCutReason]): a probe under a cut would write a begin and an end
            // for a fade that never ran.
            if (dockCutReason == null) BottomUiTransitionProbe(if (dockVisible) "dock.enter" else "dock.exit", transition)
            key(dockOpenEpoch) {
                val dockFade = mimirRingSafeFadeAlpha(dockArriveSpec, dockLeaveSpec)
                val dockInput = MimirSwapPageGate(dockFade, MimirPageMotion.ShownAlpha, holdPad = false)
                // The layer takes input only while it is ARRIVED and not leaving: the gate's shield is an ancestor's Initial-pass
                // consume, which the expanded handle's detector (it reads a consumed down as its own) does not honour, so the
                // handle is told directly. Same threshold as the shield; it keeps its look through the fade.
                val fadeTransition = transition
                val dockInteractive by remember(dockFade, fadeTransition) {
                    derivedStateOf {
                        fadeTransition.targetState == EnterExitState.Visible && dockFade.value > MimirPageMotion.ShownAlpha
                    }
                }
                // Whether the dock is drawn above the shown threshold: the collapsed handle returns only once it is not, so the
                // two are never both visible. Published as a flag (it flips twice a fade), never read per frame in composition.
                val dockAboveShown by remember(dockFade) { derivedStateOf { dockFade.value > MimirPageMotion.ShownAlpha } }
                SideEffect { dockOnScreen.value = dockAboveShown }
                DisposableEffect(dockProbe, dockFade) {
                    dockProbe?.dockAlpha = { dockFade.value }
                    onDispose {
                        dockOnScreen.value = false
                        dockProbe?.dockAlpha = { 0f }
                    }
                }
                Box(Modifier.fillMaxSize().then(dockInput)) {
                    // The expanded rail floats over the scene. This sibling is beneath the rail and above the scene:
                    // it keeps the blurred backdrop from receiving shelf/card input and lets an outside tap collapse
                    // the dock without a parent Final-pass consumer cancelling the rail's own buttons.
                    MimirLiquidGlassScrim(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    if (latestHandleEnabled.value) {
                                        dockControl("dock.scrim_dismiss", null)
                                        onDockExpandedChanged(false)
                                    }
                                }
                            },
                    )
                    railContent()
                    // The open rail already owns the old 86dp band. Reuse its top line as the dedicated
                    // collapse handle so opening never steals another slice of scene space.
                    CompanionQuickLaunchHandle(
                        expanded = true,
                        enabled = handleEnabled,
                        gesturesActive = dockInteractive,
                        onToggle = toggleDock,
                        onSwipeUp = {},
                        onSwipeDown = toggleDock,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .offset(y = -(CompanionQuickLaunchHeightDp - CompanionQuickLaunchHandleHeightDp).dp),
                    )
                }
            }
        }
        // The collapsed handle is what the dock returns to. It is declared AFTER the dock so it sits ABOVE its fade layer: the
        // departing layer is inert (its shield stands for the 180 ms leave) and, laid over this handle, would swallow a re-tap or a
        // swipe-up on it until it is disposed. Exclusivity beats "at rest from the first frame" on a close (A4 fix round): it
        // returns to full alpha only once the departing dock has dropped to the shown threshold, so the two are never both
        // above it. On open it leaves on the dock's own leave clock (it is gone before the dock, after its lead, passes the
        // threshold). It takes input only while the dock is collapsed: during its own exit it is inert, so a press on the
        // arriving dock's seats is never read as a collapse.
        AnimatedVisibility(
            visible = hideableDock && railVisible && !resolvedDockExpanded,
            enter = EnterTransition.None,
            exit = fadeOut(dockLeaveSpec),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            // Read here, in this scope only: the flag flips twice per fade and must not recompose the whole layer.
            val handleReturnAlpha by animateFloatAsState(
                targetValue = if (dockOnScreen.value) 0f else 1f,
                animationSpec = dockLeaveSpec,
                label = "quick-dock-handle-return",
            )
            DisposableEffect(dockProbe) {
                dockProbe?.handleAlpha = { handleReturnAlpha }
                onDispose { dockProbe?.handleAlpha = { 0f } }
            }
            CompanionQuickLaunchHandle(
                expanded = false,
                enabled = handleEnabled,
                gesturesActive = !resolvedDockExpanded,
                modifier = Modifier.graphicsLayer { alpha = handleReturnAlpha },
                zoneWidthDp = companionQuickLaunchHandleZoneWidthDp(handleZoneWidened),
                zoneHeightDp = companionQuickLaunchHandleZoneHeightDp(handleZoneWidened, hintFooterShown),
                onToggle = toggleDock,
                onSwipeUp = toggleDock,
                onSwipeDown = {},
            )
        }
        // The picker, the launch prompt and the pin actions come in over nothing on the swap tier (B1), as Main's Quick Launch picker does: the 130 ms
        // lead at alpha 0, then 180 ms (about 310 ms from the press), and a close is 180 ms. Reduced motion is read here, once for the three, so
        // what is drawn, what is gated and what is reported are one answer. The pad is never held (the Bottom is touch-only and the gate is
        // process-wide); each page takes touch only once it is shown, and a page that is leaving is inert.
        val overlayReducedMotion = bottomUiReducedMotion()
        AnimatedContent(
            targetState = pickerSlot,
            transitionSpec = { mimirSwapOverlayTransform(overlayReducedMotion, arriving = targetState != null) },
            label = "companion-quick-picker-transition",
        ) { presentedSlot ->
            if (presentedSlot == pickerSlot) {
                BottomUiTransitionProbe(if (pickerSlot != null) "picker.enter" else "picker.exit", transition)
            }
            presentedSlot?.let { slot ->
                val isLive = slot == pickerSlot
                // Touch waits until the picker is above 15% (the policy for Quick Launch and its picker only).
                val pickerInput = MimirSwapPageGate(mimirSwapFadeAlpha(overlayReducedMotion), MimirPageMotion.ShownAlpha, holdPad = false)
                Box(Modifier.fillMaxSize().then(pickerInput)) {
                    CompanionQuickLaunchPicker(
                        apps = apps,
                        assigning = slot >= 0,
                        enabled = !launchLocked && isLive,
                        onPick = { packageName ->
                            if (isLive) {
                                quickControl(if (slot >= 0) "picker.assign" else "picker.launch", packageName)
                                if (slot >= 0) onAssign(slot, packageName) else onLaunch(packageName)
                                closePicker()
                            }
                        },
                        onEditDestination = if (isLive && slot < 0) onQuickLaunchEdit else null,
                        onDismiss = if (isLive) ({
                            quickControl("picker.dismiss", null)
                            closePicker()
                            if (slot < 0) onAllAppsPickerDismissed()
                        }) else ({ }),
                    )
                }
            }
        }
        AnimatedContent(
            targetState = quickLaunchChoice,
            // RC-41 (audit 2026-10-14, B-28/B-29): the prompt's identity is its request. A choice re-issued for the same request (a label or an
            // `editing` update) recomposes the prompt in place instead of swapping two copies of it, which dipped the scrim between them.
            contentKey = { choice -> choice?.requestId },
            transitionSpec = { mimirSwapOverlayTransform(overlayReducedMotion, arriving = targetState != null) },
            label = "companion-quick-choice-transition",
        ) { presentedChoice ->
            if (presentedChoice == quickLaunchChoice) {
                BottomUiTransitionProbe(
                    if (quickLaunchChoice != null) "launch-prompt.enter" else "launch-prompt.exit",
                    transition,
                )
            }
            presentedChoice?.let { choice ->
                val isLive = choice == quickLaunchChoice
                val fade = mimirSwapFadeAlpha(overlayReducedMotion)
                val choiceInput = MimirSwapPageGate(fade, holdPad = false)
                CompanionQuickLaunchChoiceDialog(
                    choice = choice,
                    enabled = !launchLocked && isLive,
                    onSelect = { destination ->
                        if (isLive) {
                            quickControl("launch-prompt.choose", destination.name.lowercase())
                            onQuickLaunchChoice(choice, destination)
                        }
                    },
                    onDismiss = if (isLive) {
                        {
                            quickControl("launch-prompt.cancel", null)
                            onQuickLaunchChoiceDismiss(choice)
                        }
                    } else {
                        {}
                    },
                    modifier = choiceInput,
                    // A lit capsule's gleam and flare wait for the dialog's own arrival.
                    armed = { fade.value >= 1f },
                )
            }
        }
        AnimatedContent(
            targetState = pinActions,
            transitionSpec = { mimirSwapOverlayTransform(overlayReducedMotion, arriving = targetState != null) },
            label = "companion-pin-actions-transition",
        ) { presentedActions ->
            if (presentedActions == pinActions) {
                BottomUiTransitionProbe(if (pinActions != null) "pin-actions.enter" else "pin-actions.exit", transition)
            }
            presentedActions?.let { actions ->
                val isLive = actions == pinActions
                val pinInput = MimirSwapPageGate(mimirSwapFadeAlpha(overlayReducedMotion), holdPad = false)
                CompanionQuickLaunchPinActionsDialog(
                    label = apps.firstOrNull { it.packageName == actions.packageName }?.label,
                    enabled = !launchLocked && isLive,
                    onChangeApp = {
                        if (isLive) {
                            quickControl("pin-actions.change", actions.packageName)
                            closePinActions()
                            openPicker(actions.slot)
                        }
                    },
                    onRemove = {
                        if (isLive) {
                            quickControl("pin-actions.remove", actions.packageName)
                            onClear(actions.slot)
                            closePinActions()
                        }
                    },
                    onLaunchScreen = onQuickLaunchEdit?.let { edit ->
                        {
                            if (isLive) {
                                quickControl("pin-actions.launch_screen", actions.packageName)
                                closePinActions()
                                edit(actions.packageName)
                            }
                        }
                    },
                    onDismiss = if (isLive) ({
                        quickControl("pin-actions.dismiss", null)
                        closePinActions()
                    }) else ({ }),
                    modifier = pinInput,
                )
            }
        }
    }
}

/** General plus the pinned slots; shared by every companion surface except Home. */
@Composable
private fun CompanionQuickLaunchRow(
    /** Null when this host offers no instruments panel; otherwise whether it is currently up. */
    instrumentsShown: Boolean? = null,
    onToggleInstruments: () -> Unit = {},
    slots: List<String?>,
    apps: List<CompanionAppEntry>,
    /**
     * Whether [apps] is an ANSWER or just its starting value. Defaults true so an unwired caller keeps
     * the old reading rather than rendering every pin as permanently pending -- the failure mode of a
     * readiness flag that defaults false is worse than the one it fixes.
     */
    catalogLoaded: Boolean = true,
    launchLocked: Boolean,
    launchMessage: String?,
    /** What the cursor is on, shown only when nothing more urgent has the line. */
    selectionLabel: String? = null,
    onLaunch: (String) -> Unit,
    onOpenPicker: (Int) -> Unit,
    onOpenPinActions: (Int, String) -> Unit,
    onPinnedLaunch: (String) -> Unit = onLaunch,
    /** QA D17: a tap a locked seat dropped, with the seat's package, `all_apps`, or `empty_q<N>`. */
    onQuickLaunchIgnored: (target: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    // **The line names the seat under your finger — policy 2026-08-31.** This 18 dp seat was
    // already permanent and already usually empty; it now earns its height. A seat wearing a MARK
    // rather than a label (General's dots, the Instruments gauge) had no way to say what it was
    // without adding a caption under every seat and turning a rail of marks into a rail of buttons.
    //
    // **On HOLD, not always.** Six names at once is a different surface — §4's *a live readout is
    // shown exactly while it is TRUE* applied to a name: what is true is the seat you are touching.
    // Nothing new is reserved, nothing reflows, and the message keeps priority because a launch
    // failing is more urgent than a label you already have your finger on.
    var heldSeatName by remember { mutableStateOf<String?>(null) }
    val dockControl = rememberBottomUiControl(BottomSurface.DOCK)
    Column(
        modifier = modifier
            .height(CompanionQuickLaunchHeightDp.dp)
            .testTag(CompanionQuickLaunchTag)
            // Tokens, not literals, because E7b's seventh seat made this row's WIDTH a budget rather
            // than a formality — `companionQuickRailWidthDp` is checked against these exact values.
            .padding(horizontal = CompanionQuickRailInsetDp.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // **Three ranks, and the order is the point.** A launch that is failing outranks a name you
        // are touching, which outranks a name you can also read off the highlighted tile. The
        // selection name arrives here because the footer row that used to carry it is retired
        //  — and it takes the LOWEST rank precisely because it is the least
        // urgent of the three and the only one the player can get another way.
        // **Ambient status text is retired — ** The held seat's name and the cursor's name were both SIGNALLING: they
        // narrated a state the player could already see — their own finger, and the ringed tile.
        //
        // The launch message stays and is the whole remaining line. It is not ambient: it exists only
        // when a launch is in flight or has failed, which is the one thing at the foot of this surface
        // that the player cannot read off the surface itself.
        val railLine = launchMessage
        Text(
            text = railLine.orEmpty(),
            style = MimirTypeScale.Support,
            color = if (railLine == null) Color.Transparent else palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.height(18.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(
                CompanionQuickSeatGapDp.dp,
                Alignment.CenterHorizontally,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QuickLaunchSlot(
                entry = null,
                general = true,
                enabled = !launchLocked,
                name = "All apps",
                onHeldChanged = { held -> heldSeatName = held },
                onTap = {
                    dockControl("dock.all_apps", null)
                    onOpenPicker(-1)
                },
                onLong = {
                    dockControl("dock.all_apps", "hold")
                    onOpenPicker(-1)
                },
                onLaunchLockedTap = { onQuickLaunchIgnored(CompanionLaunchIgnoredAllApps) },
            )
            // **The helper keeps storage indices stable while placing Q5 in the reclaimed collection
            // seat.** Q1–Q4 remain their original positions and every picker callback still receives
            // the real persisted slot index, so an assignment cannot silently move to another pin.
            if (instrumentsShown != null) {
                // **the switch belongs on the rail.** The rail is the shared
                // mode strip — it is up on every surface that has one, so from here instruments are
                // reachable while a game plays, while an app runs, and while a job holds the panel.
                // The footer keeps its own seat because the rail deliberately stands DOWN on HOME
                // (86 dp would drop each lattice tile to 38.7 dp), and HOME is the surface the player
                // described switching FROM. One role, expressed in whichever strip the surface has.
                QuickLaunchSlot(
                    entry = null,
                    general = false,
                    enabled = true,
                    instrumentsGlyph = true,
                    instrumentsActive = instrumentsShown,
                    // Singular: the Bottom lattice is "the shelf" wherever else it is named, and the
                    // plural is the Main face-row noun (copy review 2026-09-11, §2 #3).
                    name = if (instrumentsShown) "Shelf" else "Tweaker",
                    onHeldChanged = { held -> heldSeatName = held },
                    onTap = {
                        dockControl("dock.tweaker_toggle", if (instrumentsShown) "to_shelf" else "to_tweaker")
                        onToggleInstruments()
                    },
                    onLong = {
                        dockControl("dock.tweaker_toggle", if (instrumentsShown) "to_shelf" else "to_tweaker")
                        onToggleInstruments()
                    },
                )
            }
            for (i in companionQuickLaunchSlotOrder(slots.size)) {
                val pkg = slots[i]
                val pinnedPackage = pkg?.trim()?.takeIf { it.isNotBlank() }
                val entry = pkg?.let { p -> apps.firstOrNull { it.packageName == p } }
                // **The mark, the name and the tap now come from ONE decision.** They used to be three:
                // the mark read `entry`, the tap read `pkg`, and the two disagree for the whole cold
                // window -- `apps` is a background sweep that starts empty, so every pinned seat drew
                // `+`, said "Pin an app", and launched anyway. See [companionQuickSeatPlan].
                val plan = companionQuickSeatPlan(pkg, entry, catalogLoaded)
                QuickLaunchSlot(
                    entry = entry,
                    general = false,
                    enabled = !launchLocked,
                    pending = plan.face == CompanionQuickSeatFace.PENDING,
                    // An empty seat says what pressing it does, not that it is nothing.
                    name = plan.name,
                    onHeldChanged = { held -> heldSeatName = held },
                    // `launchPackage` is non-null exactly when a press launches, so a seat wearing `+`
                    // can no longer fire a launch and a pending pin can no longer be swallowed.
                    onTap = {
                        val launch = plan.launchPackage
                        if (launch != null) dockControl("dock.pin.launch", launch) else dockControl("dock.pin.empty", "q$i")
                        launch?.let(onPinnedLaunch) ?: onOpenPicker(i)
                    },
                    onLong = {
                        if (pinnedPackage != null) dockControl("dock.pin.actions", pinnedPackage) else dockControl("dock.pin.empty", "q$i")
                        pinnedPackage?.let { packageName -> onOpenPinActions(i, packageName) }
                            ?: onOpenPicker(i)
                    },
                    onLaunchLockedTap = {
                        onQuickLaunchIgnored(pinnedPackage ?: companionLaunchIgnoredEmptySeat(i))
                    },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun QuickLaunchSlot(
    entry: CompanionAppEntry?,
    general: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    onLong: () -> Unit,
    /** The instruments switch borrows this seat's geometry so the rail keeps one rhythm. */
    instrumentsGlyph: Boolean = false,
    /** True while instrumentation is the mode on screen, so the seat can say *you are here*. */
    instrumentsActive: Boolean = false,
    /**
     * A real pin whose label and icon the catalog sweep has not reached yet. It is NOT an empty seat and
     * must not wear `+`: the seat is occupied, its press launches, and only its name is missing.
     */
    pending: Boolean = false,
    /**
     * What this seat is, in plain language — spoken by the rail's own line while the seat is held, and
     * given to TalkBack, which had no name for the two mark-only seats at all.
     */
    name: String? = null,
    /** Reports the hold so the rail can name it. Null on release. */
    onHeldChanged: (String?) -> Unit = {},
    /**
     * QA D17: once per tap this seat drops while the launch lock disables it. Null for a seat the lock never disables
     * (the instruments switch). Observed, never consumed -- see [companionLaunchLockedTapObserver].
     */
    onLaunchLockedTap: (() -> Unit)? = null,
) {
    val palette = LocalMimirPalette.current
    val hapticFeedback = LocalMimirKeyboardHapticFeedback.current
    val latestHapticFeedback = rememberUpdatedState(hapticFeedback)
    val latestLaunchLockedTap = rememberUpdatedState(onLaunchLockedTap)
    val emitHaptic = { latestHapticFeedback.value?.invoke() }
    // **No disc — policy 3 of the Thor Bottom revamp, 2026-10-02.** Main's 09-27 rail (MimirRunicQuickRail) paints no
    // disc, no sheen and no rim: the mark or the icon carries the seat. This seat was an 08-31 struck coin (leather, a glaze, a
    // 1.5 dp rim in the book's hardware) and none of it is drawn now. Nothing is invented: the seat keeps its 56 dp slot (the
    // rail's budget and its touch target), its press response (the focus beat), its locked dim and its name.
    //
    // The state it still has to say: PRESSED (the seat moves in), LOCKED (a token dim, the only signal left now that there
    // is no glaze), and, for the instruments switch, ACTIVE (its bars wear `accentBright`, as Main inks its current destination).
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The press is the Bottom's one response (R4, B5): `mimirPressResponse` on the box below draws the seat in to the token on the focus beat.
    // A locked seat takes a token dim, and it snapped in one frame across six seats at once before it was animated. Same focus beat as the
    // press, and still event-bounded.
    val seatAlpha by animateFloatAsState(
        if (enabled) 1f else 0.88f,
        tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "seat-enabled",
    )
    // Keyed on the seat's own name so a rail that re-orders cannot leave a stale label behind.
    LaunchedEffect(pressed, name) { onHeldChanged(name.takeIf { pressed }) }
    // **The two mark-only seats take Main's rail ink (Thor Bottom revamp).** The 09-01 console metal was struck INTO the disc, and it cannot stay on the bare marks: on the
    // Default theme the metal (0.94, 0.78, 0.47) and `accentBright` (0.91, 0.77, 0.49) are the same colour to the eye, so "instruments are up"
    // would have no visible channel once the rim is gone. So the marks are quiet at rest, and the instruments bars take `accentBright` while
    // instrumentation is the mode on screen, as Main inks its current destination. The quiet ink is `textMuted`, the same ink as the empty
    // seat's "+": Main's rail uses `textSecondary` for it, but on MANI that is 0.07 from `accentBright` (BALDR 0.11), while `textMuted` is at
    // least 0.23 from it on every theme, so the active bars read as lit on all nine. (The metal ramp stays on the console glyphs of the lattice,
    // where it was never a seat's.)
    val markInk = palette.textMuted
    val instrumentsInk = if (instrumentsActive) palette.accentBright else markInk
    Box(
        modifier = Modifier
            .size(CompanionQuickSeatDp.dp)
            // NO DISC, NO RIM (Thor Bottom revamp, policy 3, 2026-10-02): Main's 09-27 rail paints no disc and no outline, and
            // the mark or the icon carries the seat. The 56 dp box is only the touch target and the layout slot.
            // QA D17 (P3-1): the disabled clickable below drops a locked tap before any callback. This only WATCHES
            // that tap, on the Initial pass, and consumes nothing.
            .companionLaunchLockedTapObserver(locked = !enabled && onLaunchLockedTap != null) {
                latestLaunchLockedTap.value?.invoke()
            }
            .combinedClickable(
                enabled = enabled,
                // The seat's own answer IS the feedback. A ripple would draw a second one, in a
                // language the rest of this panel does not speak.
                interactionSource = interaction,
                indication = null,
                // The two mark-only seats had NO accessible name at all — TalkBack read them as
                // unlabelled. The app seats got theirs from the icon's contentDescription; a mark has
                // no icon to borrow one from.
                onClickLabel = name,
                onClick = { emitHaptic(); onTap() },
                onLongClick = { emitHaptic(); onLong() },
            )
            // The press response follows the tap, so the 56 dp hit box stays whole while the seat draws in.
            .mimirPressResponse(interaction)
            .graphicsLayer {
                // **Press pushes IN — policy 2026-08-31.** The seat answers by moving under the finger, not by
                // growing a coloured circle out of the touch point: no ripple (a foreign vocabulary on this surface). The
                // locked dim is the only other state the seat itself shows; the glaze that used to carry pressed and
                // locked went with the disc.
                //
                // Both values are event-bounded — they animate on press and on enablement and are
                // still at rest. §6 forbids a perpetual producer here; neither is one.
                alpha = seatAlpha
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            general -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(2) {
                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(2.dp)).background(markInk))
                        }
                    }
                }
            }
            // Three bars of unequal height: a gauge read at a glance, and a MARK rather than a
            // pictograph — §4, this surface speaks in runes and marks, and a literal dial would be the
            // first pictograph on it.
            instrumentsGlyph -> Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                listOf(10.dp, 20.dp, 14.dp).forEach { barHeight ->
                    Box(
                        Modifier
                            .size(width = 5.dp, height = barHeight)
                            .clip(RoundedCornerShape(2.dp))
                            // **A seat's ink names what its press CHANGES — policy 2026-08-31, and what it SHOWS now carries
                            // the state.** Every other seat on this rail launches
                            // something; this one changes what the SCREEN IS. At rest it is the quiet mark ink; while instrumentation
                            // is the mode you are IN, the bars take `accentBright`, the ink Main's rail gives its current
                            // destination: with the struck disc and its lit rim gone, the mark is the only place left to say
                            // *you are here*.
                            .background(instrumentsInk),
                    )
                }
            }
            entry != null -> QuickAppIcon(entry, ground = true)
            // **Occupied, unnamed.** A filled dot says the seat HAS something without claiming to know
            // what -- the one honest statement available before the sweep lands. Deliberately static:
            // a spinner here would be a perpetual producer on a surface that must idle at zero frames,
            // and the window it covers is measured in the first frames of a session, not seconds.
            pending -> Box(Modifier.size(14.dp).clip(CircleShape).background(palette.textMuted))
            else -> Text("+", style = MimirTypeScale.Title, color = palette.textMuted)
        }
    }
}

/**
 * Decodes an app's cached icon path (local, offline) into a 40 dp icon in the one app-icon shape (Thor Bottom revamp, policy 4:
 * [mimirAppIconShape], Main's rounded square, 11/40 corner). Three states (R3, R8): the icon once loaded, the first letter only for an
 * entry with no icon (or whose icon file is gone), and NOTHING while the icon loads or if it failed. The "pending" catalog dot is drawn by
 * the rail, not here; an unknown entry is not wired art.
 *
 * [ground] is the icon's own 09-01 ground (the lattice's raised square, `surfaceRaised` at 0.6): the dock has no disc behind its icon
 * any more, so the ground is what a loaded icon, or the letter of an app with no icon, sits on. It is drawn at the art's reveal
 * alpha, so it arrives with the icon and draws nothing while the icon loads or after it failed (R3, R8). The picker passes false: its
 * tile already has Main's own 52 dp well behind the icon, and a second ground there would drift from Main's picker.
 */
@Composable
private fun QuickAppIcon(entry: CompanionAppEntry, ground: Boolean) {
    val palette = LocalMimirPalette.current
    val iconPath = entry.iconPath?.takeIf { it.isNotBlank() }
    val art = rememberCompanionArtSite(
        hasSource = iconPath != null,
        decoded = rememberCompanionIconArt(iconPath, identity = entry.packageName),
        identity = entry.packageName to iconPath,
    )
    // Receipt: dock / picker icon, one id per package. `plate=visible` is the letter this site actually draws; a wired icon that is
    // loading reads `hasArt=true state=loading plate=hidden`. The effect is keyed on the facts, so an entry that gains an icon path
    // while mounted reports again.
    BottomUiArtReceipt(
        tile = "quick." + entry.packageName,
        hasArt = art.facts.hasArt,
        loaded = art.facts.loaded,
        failed = art.facts.failed,
        plateVisible = art.facts.plateVisible,
    )
    val groundColor = palette.surfaceRaised.copy(alpha = 0.6f)
    Box(
        modifier = Modifier
            .size(CompanionQuickIconDp.dp)
            .clip(mimirAppIconShape(CompanionQuickIconDp.dp))
            .testTag(CompanionQuickIconTag)
            .then(
                if (ground) {
                    Modifier.drawBehind {
                        val alpha = art.reveal.value
                        if (alpha > 0f) drawRect(groundColor, alpha = alpha)
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (art.draw) {
            CompanionArtDraw.ART -> CompanionArtImage(art, entry.label, Modifier.fillMaxSize())
            CompanionArtDraw.FALLBACK -> Text(
                entry.label.take(1).uppercase(),
                style = MimirTypeScale.Row,
                color = palette.accent,
                modifier = Modifier.graphicsLayer { alpha = art.reveal.value },
            )
            CompanionArtDraw.NOTHING -> Unit
        }
    }
}

/** The dock's and the picker's app icon edge: Main's own 40 dp (`HomeQuickLaunchPickerIcon`). */
internal const val CompanionQuickIconDp = 40f
internal const val CompanionQuickIconTag = "companion-quick-icon"

/** UX01 picker — a scrim + leather-ish card with a 3-wide scrollable grid of every launchable app. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CompanionQuickLaunchPicker(
    apps: List<CompanionAppEntry>,
    assigning: Boolean,
    enabled: Boolean,
    onPick: (String) -> Unit,
    onEditDestination: ((String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val hapticFeedback = LocalMimirKeyboardHapticFeedback.current
    val latestHapticFeedback = rememberUpdatedState(hapticFeedback)
    // Quick Launch is one opaque surface, as Mimir's keyboard is (its ground is full alpha for the same reason): the glass body let whatever stood under the picker (a Tabs
    // panel's copy, a page's tiles, an app's window) read through between and behind the tiles. The theme's own hero material at full alpha is the glass body's colour with
    // nothing behind it, so the ink the panel already wears keeps its contrast on every theme.
    val pickerGround = LocalMimirPalette.current.materials.hero.coverMiddle
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        MimirLiquidGlassScrim(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        MimirLiquidGlassPanel(
            modifier = Modifier
                .fillMaxWidth(0.80f)
                .fillMaxHeight(0.86f)
                // The opaque ground is drawn behind the glass body, in the panel's own 20 dp corner (a ground, not a button's fill).
                .drawBehind { drawRoundRect(pickerGround.copy(alpha = 1f), cornerRadius = CornerRadius(20.dp.toPx())) }
                // Absorb taps so a touch on the grid never bubbles to the dismissing scrim.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentPadding = 16.dp,
            verticalArrangement = Arrangement.spacedBy(0.dp),
            gradientBody = false,
            radius = 20.dp,
        ) {
            val palette = LocalMimirPalette.current
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MimirChromeText(
                        text = if (assigning) CompanionQuickAssignHeader else CompanionQuickLaunchHeader,
                        style = MimirTypeScale.RunicEyebrow,
                        color = palette.accent,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CompanionPickerClosePillReserveDp.dp),
                    )
                    // Close is an unselected etched chip (without a filled background): a 48 dp box that is the hit area and draws NOTHING (it keeps the picker
                    // header's row height, so the eyebrow's reserve stays right), around a normal capsule plate at the chip's own height (one line of the label and the
                    // chip's padding), as wide as its word and padding. A plain tap node with button semantics.
                    val closeSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .testTag(CompanionPickerCloseTag)
                            .align(Alignment.CenterEnd)
                            .heightIn(min = MimirSpacing.minTarget)
                            .widthIn(min = MimirSpacing.minTarget)
                            .companionChoiceTap(enabled = enabled, label = "Close app picker", interactionSource = closeSource, onTap = onDismiss)
                            // The Bottom's one press response (B5, R4), after the tap, like every other picker control.
                            .mimirPressResponse(closeSource),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .testTag(CompanionPickerClosePlateTag)
                                .mimirEtchedChipSurface(palette)
                                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Close",
                                style = MimirEtchedChipLabelStyle,
                                color = if (enabled) palette.etchedChipInk() else palette.textMuted,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                if (!assigning && onEditDestination != null && apps.isNotEmpty()) {
                    // An instruction is prose: Support, sentence case (it was caps at the 9 sp floor).
                    Text(
                        text = "Hold an app to choose where it opens",
                        style = MimirTypeScale.Support,
                        color = palette.textSecondary,
                        maxLines = 2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )
                }
                if (apps.isEmpty()) {
                    Text(
                        text = "No apps found.",
                        style = MimirTypeScale.Support,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(apps, key = { it.packageName }) { entry ->
                            // The picker cell's press is the Bottom's one response (R4, B5): the scale. Its default ripple was a second answer to the same touch.
                            val cellSource = remember { MutableInteractionSource() }
                            Column(
                                modifier = Modifier
                                    .combinedClickable(
                                        enabled = enabled,
                                        interactionSource = cellSource,
                                        indication = null,
                                        onClick = {
                                            latestHapticFeedback.value?.invoke()
                                            onPick(entry.packageName)
                                        },
                                        onLongClick = onEditDestination?.let { edit ->
                                            {
                                                latestHapticFeedback.value?.invoke()
                                                edit(entry.packageName)
                                            }
                                        },
                                    )
                                    .mimirPressResponse(cellSource)
                                    .clip(RoundedCornerShape(12.dp))
                                    .mimirGlassRow(
                                        focused = false,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(RoundedCornerShape(13.dp))
                                        .background(palette.surfaceBase.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center,
                                ) { QuickAppIcon(entry, ground = false) }
                                Text(
                                    text = entry.label,
                                    style = MimirTypeScale.Support,
                                    color = palette.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(top = 5.dp).fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Existing occupied-pin actions plus the S2-03 destination editor. None of these actions launches. */
@Composable
private fun CompanionQuickLaunchPinActionsDialog(
    label: String?,
    enabled: Boolean,
    onChangeApp: () -> Unit,
    onRemove: () -> Unit,
    onLaunchScreen: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        MimirLiquidGlassScrim(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        MimirLiquidGlassPanel(
            modifier = Modifier
                .fillMaxWidth(0.74f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            contentPadding = 20.dp,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            gradientBody = false,
            radius = 20.dp,
        ) {
            val palette = LocalMimirPalette.current
            MimirChromeText(
                text = "ᛗ · PIN ACTIONS",
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = label ?: CompanionQuickSeatPendingName,
                style = MimirTypeScale.Title,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            QuickLaunchChoiceDivider()
            QuickLaunchChoiceAction("Change app", enabled, onChangeApp)
            QuickLaunchChoiceDivider()
            QuickLaunchChoiceAction("Remove pin", enabled, onRemove)
            onLaunchScreen?.let { edit ->
                QuickLaunchChoiceDivider()
                QuickLaunchChoiceAction("Change where it opens", enabled, edit)
            }
            QuickLaunchChoiceDivider()
            QuickLaunchChoiceAction("Cancel", enabled, onDismiss)
        }
    }
}

/**
 * First-use and per-app destination editor. The host commits the choice only after this exact request id. Both of its looks are the
 * one choice dialog (V7): "Opens on", and PAUSE GAME when the launch would cover a live Main DS/3DS lower screen.
 */
@Composable
private fun CompanionQuickLaunchChoiceDialog(
    choice: CompanionQuickLaunchChoice,
    enabled: Boolean,
    onSelect: (CompanionQuickLaunchDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    armed: () -> Boolean = MimirFocusAlwaysArmed,
) {
    if (choice.coversMainGame) {
        CompanionPauseGameDialog(
            label = choice.label,
            enabled = enabled,
            onContinue = { onSelect(CompanionQuickLaunchDestination.BOTTOM) },
            onDismiss = onDismiss,
            modifier = modifier,
            armed = armed,
        )
    } else {
        CompanionOpensOnDialog(
            label = choice.label,
            editing = choice.editing,
            enabled = enabled,
            onSelect = onSelect,
            onDismiss = onDismiss,
            modifier = modifier,
            armed = armed,
        )
    }
}

@Composable
private fun QuickLaunchChoiceDivider() {
    BeamSeparator(modifier = Modifier.height(1.dp), showCenterNode = false)
}

@Composable
private fun QuickLaunchChoiceAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val hapticFeedback = LocalMimirKeyboardHapticFeedback.current
    val latestHapticFeedback = rememberUpdatedState(hapticFeedback)
    // The Bottom's one press response (R4, B5) is the scale; this action had a ripple, a second, foreign answer to the same touch, so it has none now.
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .clickable(
                enabled = enabled,
                interactionSource = source,
                indication = null,
                onClick = {
                    latestHapticFeedback.value?.invoke()
                    onClick()
                },
            )
            .mimirPressResponse(source)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MimirTypeScale.ControlHint,
            color = if (enabled) palette.accentBright else palette.textMuted,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun IdleAmbient(payload: CompanionPayload) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ThemeSigilMark(
                Modifier
                    .size(108.dp)
                    .clip(RoundedCornerShape(54.dp))
                    .background(LocalMimirPalette.current.surfaceRaised.copy(alpha = 0.58f))
                    .border(1.dp, LocalMimirPalette.current.accent.copy(alpha = 0.42f), RoundedCornerShape(54.dp))
                    .padding(24.dp),
            )
            Text("M I M I R", style = MimirTypeScale.Title, color = LocalMimirPalette.current.accentBright)
            Text(
                // "Returning to shelf…" is the reclaim-window hold (companion review F9, "loading wins on
                // return"): the controller parks this payload while screen 2 is being taken back, and the
                // primary's fresh push replaces it the moment the mirror re-attaches. It read
                // "Reconnecting…" until the 2026-09-11 copy review (§2 the earlier update): that says a link dropped,
                // and the drawer already names this same moment "Returning to shelf…". (The floor's cold-start cover no longer reuses
                // this branch: its badge-only cover uses the shared cover. This hold keeps its words until T8 builds the return screen.)
                text = if (payload.isLoading) "Returning to shelf…" else "Ready when you are",
                style = MimirTypeScale.Support,
                color = LocalMimirPalette.current.textMuted,
            )
            // **`LoadingStrip` removed by policy 2026-08-31.** It drew a bar hardcoded to 42% of
            // its width -- a denominator that does not exist -- on the same panel and the same night
            // this product photographed and ratified the opposite rule: a null total draws NO bar, never
            // a fraction it has not earned (`d4-job-panel-null-total-no-bar.png`). The hold line above
            // already states the whole state, so nothing was lost with it.
        }
    }
}

/**
 * THE companion sigil — the ᛗ Mannaz mark drawn in the active palette's accent (policy R10,
 * 2026-07-09: palette-driven mark). No per-theme sigil ASSET ships in the APK (companion review
 * correction #3), so `payload.themeSigilRef` stays a reserved field and every theme differentiates the
 * mark through its own accent, exactly like the primary's tile glyph.
 */
@Composable
private fun ThemeSigilMark(modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = "ᛗ",
            color = palette.accent,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The scene overlay's glass (RC-02, audit 2026-10-14, B-08 and its dependents): the request goes through [rememberMimirGlassHold], so the glass
 * under a leaving panel stays up through the panel's own dissolve and clears only after it, and the value
 * ramps on OverlayMs both ways. B1: Main's own law for the scene under an overlay: the blur and scrim soften on the house OverlayMs clock as soon
 * as the overlay is asked for, and each panel then takes its own arrival over the softened scene (the swap tier's 130 ms lead and 180 ms, or the
 * page tier's lead and 540 ms): soften, then materialise. One ramp cannot follow four panels on two clocks, so it follows none of them and keeps
 * Main's. The open is unchanged: the hold passes a request through on its own frame. One function for the surface and
 * `BottomGlassHoldTimelineTest` (W2L7 review #4), so a surface that drops the hold fails the test.
 */
@Composable
internal fun rememberCompanionOverlayGlass(requested: Boolean): State<Float> {
    val overlayGlassHeld = rememberMimirGlassHold(requested)
    return animateFloatAsState(
        targetValue = if (overlayGlassHeld) 1f else 0f,
        animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "overlay-glass",
    )
}

/**
 * Full-screen Mannaz cover for hosts OUTSIDE [CompanionSurface] itself — today, only
 * `CompanionSecondaryHomeActivity`'s own `setContent` (a secondary display
 * that falls back to Android/black must show Mimir's loading cover instead of nothing). That activity
 * is a floor held underneath the Presentation and invisible whenever the Presentation is actually
 * attached, so anything this composable paints is — by construction — not yet connected: the Bottom's COLD START.
 *
 * **Policy 6 (Thor Bottom revamp, 2026-10-02): this is Main's return cover** (`MimirPrimaryReturnCover`, MimirAppHost.kt) — the
 * 56 dp [MimirMannazBadge] on the theme's own ground, NO text, fading out on `OverlayMs` when [visible] goes false. It replaces the
 * 108 dp disc, the ᛗ typed in the system font, the "M I M I R" wordmark and "Returning to shelf…". The last was false here: nothing
 * is being returned to on a cold start. The reclaim-window hold that the Presentation itself shows ([IdleAmbient]) does say that
 * truthfully and keeps its words until T8 (the return holding screen, which reuses this badge) is built.
 *
 * Callers supply their own [MimirTheme] — see how `CompanionSurface` itself resolves
 * `MimirTheme(themeId = MimirThemeId.fromStorageKey(...))` — this composable only paints inside whatever theme is already in scope.
 * The floor never hides it (it is replaced by the Presentation's window, so a fade painted in the floor would paint nothing the player sees), so it
 * defaults visible and the floor passes nothing. The fade-out lives in the window that is on screen: on a cold start the Presentation wears this same
 * cover over its first frame and drops [visible] once its content is in (`MimirCompanionPresentation`, `coldStartHandoff`), so the lattice arrives
 * through this fade and not a hard cut. Reduced motion composes no cover there at all. [onGone] fires once the exit fade has FINISHED (alpha 0), which is when the Presentation lets the cursor ring begin.
 */
@Composable
fun CompanionMannazCover(modifier: Modifier = Modifier, visible: Boolean = true, onGone: () -> Unit = {}) {
    // Main's cover leaves with `AnimatedVisibility(exit = fadeOut(tween(OverlayMs)))`. This is the same fade on the same clock written as one
    // animated alpha, because `AnimatedVisibility` left a Transition running behind a floor Activity that was destroyed (the next test's
    // `waitForIdle` in CompanionSecondaryHomeThemeTest never returned), and a cover that outlives its window is the one thing it must not do.
    // At rest the target equals the value, so nothing animates and nothing asks for a frame.
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        label = "cold-cover-fade",
    )
    // The cover has FULLY left: not visible and its exit fade finished (alpha 0). A host that holds something back until the cover is gone (the Presentation's
    // cursor-ring sweep) waits on this and not on `visible` going false, which is only the START of the 180 ms fade. Once per departure.
    // Derived, so `alpha` is read in the layer and here only at its edge; read in composition it recomposed this cover on every frame of its fade
    // (audit 2026-10-14, B-02).
    val alphaUp by remember { derivedStateOf { alpha > 0f } }
    val gone = !visible && !alphaUp
    val latestOnGone by rememberUpdatedState(onGone)
    LaunchedEffect(gone) { if (gone) latestOnGone() }
    if (visible || alphaUp) {
        val palette = LocalMimirPalette.current
        Box(
            modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
                .background(palette.surfaceBase)
                .testTag(CompanionColdCoverTag),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(CompanionColdCoverBadgeDp.dp).testTag(CompanionColdCoverBadgeTag)) {
                MimirMannazBadge(palette = palette, size = CompanionColdCoverBadgeDp.dp)
            }
        }
    }
}

internal const val CompanionColdCoverBadgeDp = 56f
internal const val CompanionColdCoverTag = "companion-cold-cover"
internal const val CompanionColdCoverBadgeTag = "companion-cold-cover-badge"


@Preview(widthDp = 640, heightDp = 480)
@Composable
private fun PreviewCompanionHome() {
    CompanionSurface(
        CompanionPayload(
            mode = CompanionMode.HOME,
            themeId = "midnight",
            statsLabel = "6 platforms · 12 games",
            homeLanes = listOf(
                CompanionHomeLane(
                    id = "emulation",
                    title = "Emulation",
                    hint = "Add games from Settings › Library.",
                    tiles = listOf(
                        CompanionHomeTile("gba", CompanionHomeTileKind.PLATFORM, "Game Boy Advance", countLabel = "2 games"),
                        CompanionHomeTile("ds", CompanionHomeTileKind.PLATFORM, "Nintendo DS", countLabel = "1 game"),
                        CompanionHomeTile("psp", CompanionHomeTileKind.PLATFORM, "PSP", countLabel = "14 games"),
                    ),
                ),
                CompanionHomeLane(
                    id = "android-games",
                    title = "Android Games",
                    hint = "Favorite an Android game to pin it here.",
                    tiles = listOf(
                        CompanionHomeTile("dev.game", CompanionHomeTileKind.ANDROID_APP, "Dead Cells", kindLabel = "Android game"),
                    ),
                ),
                CompanionHomeLane(
                    id = "android-apps",
                    title = "Android Apps",
                    hint = "Favorite an Android app to pin it here.",
                ),
            ),
        ),
    )
}

private fun previewRows(): List<CompanionShelfRow> = listOf(
    CompanionShelfRow("switch", "SW", "Switch", "1 game", listOf("Mario Kart 8 Deluxe")),
    CompanionShelfRow("wii", "WII", "Wii", "2 games", listOf("Wii Sports", "Super Mario Galaxy")),
    CompanionShelfRow("arcade", "ARC", "Arcade", "1 game", listOf("Street Fighter III")),
    CompanionShelfRow("dc", "DC", "Dreamcast", "2 games", listOf("Crazy Taxi", "Soulcalibur")),
)

private const val PREVIEW_BG = 0xFF080D12
