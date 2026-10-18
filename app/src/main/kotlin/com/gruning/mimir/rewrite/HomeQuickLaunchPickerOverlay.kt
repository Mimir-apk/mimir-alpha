package com.gruning.mimir.rewrite

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal const val HomeQuickLaunchPickerColumns = 3
private const val HomeQuickLaunchPickerRingClearanceDp = 6

internal val HomeQuickLaunchPickerScope = FocusScope("home/quicklaunch/apps")

internal sealed interface HomeQuickLaunchPickerMode {
    data object General : HomeQuickLaunchPickerMode
    data class Assign(val slotIndex: Int) : HomeQuickLaunchPickerMode
}

internal data class HomeQuickLaunchPickerCopy(
    val header: String,
    val instruction: String,
    val legend: String,
)

internal fun homeQuickLaunchPickerCopy(mode: HomeQuickLaunchPickerMode): HomeQuickLaunchPickerCopy = when (mode) {
    HomeQuickLaunchPickerMode.General -> HomeQuickLaunchPickerCopy(
        header = "ᛗ · QUICK LAUNCH",
        instruction = "Choose any installed app.",
        legend = "A OPEN · B BACK",
    )
    is HomeQuickLaunchPickerMode.Assign -> HomeQuickLaunchPickerCopy(
        header = "ᛗ · PIN Q${mode.slotIndex + 1}",
        instruction = "Choose an app or clear this slot.",
        legend = "A CHOOSE · B BACK",
    )
}

/** The picker tile's corner: one symbol for its clip and its ring (ring migration, 2026-09-28; R-1). */
private val HomeQuickLaunchTileCorner = 12.dp

internal sealed interface HomeQuickLaunchPickerItem {
    val stableId: String

    data object Clear : HomeQuickLaunchPickerItem {
        override val stableId: String = "clear"
    }

    data class App(val entry: CompanionAppEntry) : HomeQuickLaunchPickerItem {
        override val stableId: String = "app/${entry.packageName}"
    }
}

internal fun homeQuickLaunchPickerKey(item: HomeQuickLaunchPickerItem): FocusKey =
    FocusKey("home/quicklaunch/apps/${item.stableId}")

/** Clear is an assign-only first tile and appears only when that persisted slot is currently pinned. */
internal fun homeQuickLaunchPickerItems(
    mode: HomeQuickLaunchPickerMode,
    apps: List<CompanionAppEntry>,
    slots: List<String?>,
    launchSequences: Map<String, Long> = emptyMap(),
): List<HomeQuickLaunchPickerItem> = buildList {
    val assign = mode as? HomeQuickLaunchPickerMode.Assign
    if (assign != null && slots.getOrNull(assign.slotIndex) != null) {
        add(HomeQuickLaunchPickerItem.Clear)
    }
    apps.distinctBy(CompanionAppEntry::packageName)
        .sortedWith(quickLaunchPickerAppComparator(mode, launchSequences))
        .forEach { add(HomeQuickLaunchPickerItem.App(it)) }
}

private fun quickLaunchPickerAppComparator(
    mode: HomeQuickLaunchPickerMode,
    launchSequences: Map<String, Long>,
): Comparator<CompanionAppEntry> = if (mode == HomeQuickLaunchPickerMode.General) {
    compareByDescending<CompanionAppEntry> { launchSequences[it.packageName] ?: 0L }
        .thenBy(String.CASE_INSENSITIVE_ORDER, CompanionAppEntry::label)
        .thenBy(CompanionAppEntry::packageName)
} else {
    compareBy(String.CASE_INSENSITIVE_ORDER, CompanionAppEntry::label)
        .thenBy(CompanionAppEntry::packageName)
}

/** General restores its last real app; Assign always retains its own deterministic first-item rule. */
internal fun homeQuickLaunchPickerInitialIndex(
    mode: HomeQuickLaunchPickerMode,
    items: List<HomeQuickLaunchPickerItem>,
    lastGeneralPackageName: String?,
): Int {
    if (items.isEmpty()) return -1
    if (mode != HomeQuickLaunchPickerMode.General || lastGeneralPackageName.isNullOrBlank()) return 0
    return items.indexOfFirst { item ->
        item is HomeQuickLaunchPickerItem.App && item.entry.packageName == lastGeneralPackageName
    }.takeIf { it >= 0 } ?: 0
}

/**
 * Seed after virtual registration has completed. Registering the first key may eagerly restore/seed the
 * scope, so a later coroutine guard cannot distinguish that provisional focus from the intended one.
 * This synchronous correction makes General honor its persisted app and makes Assign always own its
 * deterministic first tile even though both modes deliberately reuse the same modal scope.
 */
internal fun seedHomeQuickLaunchPickerFocus(
    authority: FocusAuthority,
    mode: HomeQuickLaunchPickerMode,
    items: List<HomeQuickLaunchPickerItem>,
    lastGeneralPackageName: String?,
): Boolean {
    val index = homeQuickLaunchPickerInitialIndex(mode, items, lastGeneralPackageName)
    val item = items.getOrNull(index) ?: return false
    authority.requestFocus(homeQuickLaunchPickerKey(item))
    return true
}

/** Three-column navigation with clamping only — no wrap and no row reset. */
internal fun nextHomeQuickLaunchPickerIndex(
    currentIndex: Int,
    itemCount: Int,
    action: ControllerAction,
): Int {
    if (itemCount <= 0) return -1
    if (currentIndex !in 0 until itemCount) return 0
    val rowStart = currentIndex / HomeQuickLaunchPickerColumns * HomeQuickLaunchPickerColumns
    val rowEnd = minOf(rowStart + HomeQuickLaunchPickerColumns - 1, itemCount - 1)
    return when (action) {
        ControllerAction.NAV_LEFT -> if (currentIndex == rowStart) currentIndex else currentIndex - 1
        ControllerAction.NAV_RIGHT -> if (currentIndex == rowEnd) currentIndex else currentIndex + 1
        ControllerAction.NAV_UP -> {
            val previous = currentIndex - HomeQuickLaunchPickerColumns
            if (previous >= 0) previous else currentIndex
        }
        ControllerAction.NAV_DOWN -> {
            val currentRow = currentIndex / HomeQuickLaunchPickerColumns
            val lastRow = (itemCount - 1) / HomeQuickLaunchPickerColumns
            if (currentRow >= lastRow) return currentIndex
            val next = currentIndex + HomeQuickLaunchPickerColumns
            if (next < itemCount) next else itemCount - 1
        }
        else -> currentIndex
    }
}

/** Signed minimum scroll delta that reveals the focused tile plus [marginPx] ring clearance. */
internal fun homeQuickLaunchPickerRevealDeltaPx(
    itemTopPx: Int,
    itemBottomPx: Int,
    viewportTopPx: Int,
    viewportBottomPx: Int,
    marginPx: Int,
): Int {
    val topWithMargin = itemTopPx - marginPx
    val bottomWithMargin = itemBottomPx + marginPx
    if (topWithMargin >= viewportTopPx && bottomWithMargin <= viewportBottomPx) return 0
    if (topWithMargin < viewportTopPx) return topWithMargin - viewportTopPx
    return bottomWithMargin - viewportBottomPx
}

/** Row a flat picker index occupies in the fixed [HomeQuickLaunchPickerColumns]-wide grid. */
internal fun homeQuickLaunchPickerRowOf(index: Int): Int =
    index.coerceAtLeast(0) / HomeQuickLaunchPickerColumns

/**
 * Uniform vertical pitch between grid rows, read from whatever the layout currently knows.
 *
 * Two visible items in DIFFERENT rows give the pitch exactly (height + spacing) without hardcoding
 * either. When only one row is composed, the caller supplies the authored grid gap so the one permitted
 * projection cannot stop short and rely on a forbidden correction pass.
 */
internal fun homeQuickLaunchPickerRowPitchPx(
    rowTops: List<Pair<Int, Int>>,
    fallbackHeightPx: Int,
    fallbackGapPx: Int = 0,
): Int {
    val distinctRows = rowTops.distinctBy { it.first }.sortedBy { it.first }
    val pitch = distinctRows.zipWithNext()
        .map { (lower, upper) -> (upper.second - lower.second) / (upper.first - lower.first) }
        .firstOrNull { it > 0 }
    return pitch ?: (fallbackHeightPx.coerceAtLeast(0) + fallbackGapPx.coerceAtLeast(0))
}

/**
 * Minimum signed scroll that reveals a focus target the layout has NOT composed yet, projected from an
 * anchor row it HAS.
 *
 * Without this, stepping onto a row just past the content edge had no measurable target, so the only way
 * to reach it was an instant `scrollToItem` that re-seats the row at the top of the viewport — a jump of
 * a whole page for one D-pad press. Projecting the target's box lets the same minimum-reveal rule animate
 * exactly the travel the collision requires: one row, not one page.
 */
internal fun homeQuickLaunchPickerProjectedRevealDeltaPx(
    targetRow: Int,
    anchorRow: Int,
    anchorTopPx: Int,
    rowPitchPx: Int,
    rowHeightPx: Int,
    viewportTopPx: Int,
    viewportBottomPx: Int,
    marginPx: Int,
): Int {
    if (rowPitchPx <= 0) return 0
    val projectedTop = anchorTopPx + (targetRow - anchorRow) * rowPitchPx
    return homeQuickLaunchPickerRevealDeltaPx(
        itemTopPx = projectedTop,
        itemBottomPx = projectedTop + rowHeightPx,
        viewportTopPx = viewportTopPx,
        viewportBottomPx = viewportBottomPx,
        marginPx = marginPx,
    )
}

/**
 * How far a projected reveal may be trusted, in rows.
 *
 * One or two rows is an ordinary D-pad step onto the content edge and projects exactly. A jump of many
 * rows (a restored selection deep in the list on open) has no useful anchor, so that case still seeks
 * the item outright rather than extrapolating a large distance from one row's geometry.
 */
internal const val HomeQuickLaunchPickerMaxProjectedRows = 2

internal fun homeQuickLaunchPickerCanProjectReveal(targetRow: Int, anchorRow: Int): Boolean =
    kotlin.math.abs(targetRow - anchorRow) in 1..HomeQuickLaunchPickerMaxProjectedRows

internal data class HomeQuickLaunchPickerVisibleItem(
    val index: Int,
    val topPx: Int,
    val heightPx: Int,
)

internal sealed interface HomeQuickLaunchPickerRevealCommand {
    data class Measured(val deltaPx: Int) : HomeQuickLaunchPickerRevealCommand
    data class Projected(val deltaPx: Int) : HomeQuickLaunchPickerRevealCommand
    data class SeekBeforePresentation(val firstVisibleItemIndex: Int) : HomeQuickLaunchPickerRevealCommand
}

internal data class HomeQuickLaunchPickerRevealFrame(
    val epoch: Long,
    val targetStableId: String,
    val command: HomeQuickLaunchPickerRevealCommand,
)

/**
 * One command belongs to one stable focus identity. Retargeting supersedes the prior epoch instead of
 * queueing a correction behind it; repeating the same identity is a no-op.
 */
internal class HomeQuickLaunchPickerRevealLedger(
    initiallySettledTargetStableId: String? = null,
) {
    private var issuedEpoch = 0L
    private var targetStableId = initiallySettledTargetStableId
    private var latestFrame: HomeQuickLaunchPickerRevealFrame? = null

    @Synchronized
    fun issue(
        targetStableId: String,
        command: HomeQuickLaunchPickerRevealCommand,
    ): HomeQuickLaunchPickerRevealFrame? {
        if (targetStableId == this.targetStableId) return null
        check(issuedEpoch < Long.MAX_VALUE) { "Quick Launch reveal epoch exhausted" }
        issuedEpoch += 1L
        this.targetStableId = targetStableId
        return HomeQuickLaunchPickerRevealFrame(
            epoch = issuedEpoch,
            targetStableId = targetStableId,
            command = command,
        ).also { latestFrame = it }
    }

    @Synchronized
    fun latest(): HomeQuickLaunchPickerRevealFrame? = latestFrame

    @Synchronized
    fun accepts(epoch: Long): Boolean = latestFrame?.epoch == epoch
}

internal data class HomeQuickLaunchPickerOpeningPlan(
    val firstVisibleItemIndex: Int,
    /** Non-null means the initial grid position settles this target before the first picker pixels. */
    val preVisibleSettledTargetStableId: String?,
)

internal fun homeQuickLaunchPickerOpeningPlan(
    targetIndex: Int,
    targetStableId: String?,
): HomeQuickLaunchPickerOpeningPlan {
    val targetRow = homeQuickLaunchPickerRowOf(targetIndex)
    val farRestore = targetIndex >= 0 &&
        targetStableId != null &&
        targetRow > HomeQuickLaunchPickerMaxProjectedRows
    if (!farRestore) return HomeQuickLaunchPickerOpeningPlan(0, null)

    // Seat the target row itself behind the grid's existing ring-clearance content padding. This is the
    // nonanimated seek: LazyGridState receives it before the picker can draw or own input.
    return HomeQuickLaunchPickerOpeningPlan(
        firstVisibleItemIndex = targetRow * HomeQuickLaunchPickerColumns,
        preVisibleSettledTargetStableId = targetStableId,
    )
}

/** Resolve exactly one reveal command from one layout snapshot; callers must never append a correction. */
internal fun homeQuickLaunchPickerRevealCommand(
    targetIndex: Int,
    visibleItems: List<HomeQuickLaunchPickerVisibleItem>,
    viewportTopPx: Int,
    viewportBottomPx: Int,
    marginPx: Int,
    fallbackRowGapPx: Int = 0,
): HomeQuickLaunchPickerRevealCommand {
    val measured = visibleItems.firstOrNull { it.index == targetIndex }
    if (measured != null) {
        return HomeQuickLaunchPickerRevealCommand.Measured(
            homeQuickLaunchPickerRevealDeltaPx(
                itemTopPx = measured.topPx,
                itemBottomPx = measured.topPx + measured.heightPx,
                viewportTopPx = viewportTopPx,
                viewportBottomPx = viewportBottomPx,
                marginPx = marginPx,
            ),
        )
    }

    val targetRow = homeQuickLaunchPickerRowOf(targetIndex)
    val anchor = visibleItems.minByOrNull {
        kotlin.math.abs(homeQuickLaunchPickerRowOf(it.index) - targetRow)
    }
    val anchorRow = anchor?.let { homeQuickLaunchPickerRowOf(it.index) }
    if (anchor != null && anchorRow != null && homeQuickLaunchPickerCanProjectReveal(targetRow, anchorRow)) {
        return HomeQuickLaunchPickerRevealCommand.Projected(
            homeQuickLaunchPickerProjectedRevealDeltaPx(
                targetRow = targetRow,
                anchorRow = anchorRow,
                anchorTopPx = anchor.topPx,
                rowPitchPx = homeQuickLaunchPickerRowPitchPx(
                    rowTops = visibleItems.map { homeQuickLaunchPickerRowOf(it.index) to it.topPx },
                    fallbackHeightPx = anchor.heightPx,
                    fallbackGapPx = fallbackRowGapPx,
                ),
                rowHeightPx = anchor.heightPx,
                viewportTopPx = viewportTopPx,
                viewportBottomPx = viewportBottomPx,
                marginPx = marginPx,
            ),
        )
    }

    return HomeQuickLaunchPickerRevealCommand.SeekBeforePresentation(
        firstVisibleItemIndex = targetRow * HomeQuickLaunchPickerColumns,
    )
}

/** Hard-modal controller reducer shared by both General launch and Q1/Q2/Q3 assignment modes. */
internal fun dispatchHomeQuickLaunchPicker(
    authority: FocusAuthority,
    mode: HomeQuickLaunchPickerMode,
    items: List<HomeQuickLaunchPickerItem>,
    action: ControllerAction,
    onLaunch: (String) -> Unit,
    onAssign: (Int, String) -> Unit,
    onClear: (Int) -> Unit,
    onDismiss: () -> Unit,
): Boolean {
    val focusedIndex = items.indexOfFirst { homeQuickLaunchPickerKey(it) == authority.state.value.focused }
    return when (action) {
        ControllerAction.NAV_UP,
        ControllerAction.NAV_DOWN,
        ControllerAction.NAV_LEFT,
        ControllerAction.NAV_RIGHT -> {
            val next = nextHomeQuickLaunchPickerIndex(focusedIndex, items.size, action)
            items.getOrNull(next)?.let { authority.requestFocus(homeQuickLaunchPickerKey(it)) }
            true
        }
        ControllerAction.CONFIRM -> {
            when (val item = items.getOrNull(focusedIndex)) {
                HomeQuickLaunchPickerItem.Clear ->
                    (mode as? HomeQuickLaunchPickerMode.Assign)?.let { onClear(it.slotIndex) }
                is HomeQuickLaunchPickerItem.App -> when (mode) {
                    HomeQuickLaunchPickerMode.General -> onLaunch(item.entry.packageName)
                    is HomeQuickLaunchPickerMode.Assign -> onAssign(mode.slotIndex, item.entry.packageName)
                }
                null -> Unit
            }
            true
        }
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
            onDismiss()
            true
        }
        else -> true
    }
}

/**
 * Touch has already identified the exact tile; it must not round-trip through the controller's
 * focused-item reducer.  The latch closes the small composition window before the selected action
 * dismisses/replaces the picker, so a double tap cannot launch/assign twice.
 */
internal class HomeQuickLaunchPickerTouchActivation {
    private var consumed = false

    fun dispatch(
        mode: HomeQuickLaunchPickerMode,
        item: HomeQuickLaunchPickerItem,
        /** True only when the launch action dismisses/replaces this picker instance. */
        onLaunch: (String) -> Boolean,
        onAssign: (Int, String) -> Unit,
        onClear: (Int) -> Unit,
    ): Boolean {
        if (consumed) return false
        consumed = true
        val closesPicker = when (item) {
            HomeQuickLaunchPickerItem.Clear -> {
                val assign = mode as? HomeQuickLaunchPickerMode.Assign
                if (assign != null) onClear(assign.slotIndex)
                assign != null
            }
            is HomeQuickLaunchPickerItem.App -> when (mode) {
                HomeQuickLaunchPickerMode.General -> onLaunch(item.entry.packageName)
                is HomeQuickLaunchPickerMode.Assign -> {
                    onAssign(mode.slotIndex, item.entry.packageName)
                    true
                }
            }
        }
        // A launch lock deliberately keeps the modal visible. Re-arm touch for the next legitimate try;
        // terminal actions remain latched until composition disposes this picker instance.
        if (!closesPicker) consumed = false
        return true
    }
}

@Composable
internal fun HomeQuickLaunchPickerOverlay(
    authority: FocusAuthority,
    mode: HomeQuickLaunchPickerMode,
    items: List<HomeQuickLaunchPickerItem>,
    presentationEpoch: Long,
    initialGeneralPackageName: String?,
    onGeneralFocusChanged: (String) -> Unit,
    onPresentationReadyChanged: (Boolean) -> Unit,
    onChoose: (HomeQuickLaunchPickerItem) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Test seam ONLY -- production always takes the default. See the `catalogReportedEmpty` block below
     * (T2 item 2) for what this resolves to and why.
     */
    reportCatalogEmpty: (suspend () -> Boolean)? = null,
) {
    val palette = LocalMimirPalette.current
    val keys = remember(items) { items.map(::homeQuickLaunchPickerKey) }
    val copy = remember(mode) { homeQuickLaunchPickerCopy(mode) }
    val seedLatch = remember(mode) { mutableStateOf(false) }
    val preservedRegistrationFocus = remember(mode) { mutableStateOf<FocusKey?>(null) }
    val density = LocalDensity.current
    val openingFocusIndex = remember(mode, items, initialGeneralPackageName) {
        homeQuickLaunchPickerInitialIndex(mode, items, initialGeneralPackageName)
    }
    val openingPlan = remember(mode, items, initialGeneralPackageName) {
        homeQuickLaunchPickerOpeningPlan(
            targetIndex = openingFocusIndex,
            targetStableId = items.getOrNull(openingFocusIndex)?.stableId,
        )
    }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = openingPlan.firstVisibleItemIndex
            .coerceIn(0, items.lastIndex.coerceAtLeast(0)),
    )
    val revealLedger = remember(presentationEpoch) {
        HomeQuickLaunchPickerRevealLedger(openingPlan.preVisibleSettledTargetStableId)
    }
    val presentationReady = remember(presentationEpoch) { mutableStateOf(false) }
    // `LazyGridLayoutInfo` is not a readiness signal on every Compose/device combination. A visible
    // grid must, however, have crossed placement. This latch converts that real geometry event into a
    // one-time admission prerequisite instead of awaiting a flow that can remain silent indefinitely.
    val gridPlaced = remember(presentationEpoch) { mutableStateOf(false) }

    // T2 item 2: `items` alone cannot tell "the catalog genuinely has zero apps" from "the catalog hasn't
    // reported yet". `CompanionQuickLaunchCatalog.entries` starts every process empty, and this picker can
    // mount before that catalog's OWN refresh (kicked off when the dock opened --
    // `MimirAppCoordinator.kt`'s `LaunchedEffect(navigation.homeQuickLaunchOpen)`) has completed. A fast
    // dock-to-"All apps" press could otherwise paint "No apps found." for a moment and then pop to the
    // grid once the real list lands -- a false statement shown to the player, briefly, the same honesty
    // class the earlier update strobe fix was already fixing for icons.
    //
    // The catalog now reports its own loaded-ness (`CompanionQuickLaunchCatalog.hasLoaded`), so this
    // surface reads the fact instead of manufacturing it. It used to call `refresh()` purely to learn
    // whether a sweep had happened -- the return value was the signal -- which meant asking the question
    // cost a sweep even when the dock's own refresh (`MimirAppCoordinator.kt`'s
    // `LaunchedEffect(navigation.homeQuickLaunchOpen)`) had already finished, which is the ordinary case
    // because "All apps" is only reachable with the dock open. That call is now the FALLBACK for the one
    // case it was ever needed in: the catalog genuinely has not reported yet.
    //
    // Deliberately not `hasLoaded.first { it }`: awaiting the flow would park forever on the path where
    // the dock's refresh threw, turning a recoverable unknown into a permanently blank overlay. Calling
    // `refresh()` ourselves guarantees this opening gets an answer regardless of what happened upstream.
    // The underlying PackageManager sweep is short-window coalesced (`NativeShelfPackages.kt`'s
    // `loadNativeShelfSnapshotCoalesced`, 5s), so even that fallback fuses with an in-flight scan.
    //
    // This gives three honest states instead of two: unresolved (render nothing -- no new copy, matching
    // the brief), confirmed empty (the existing "No apps found." text), and populated (the grid). A
    // refresh failure leaves this `false` forever for this opening -- unknown must not render as "there is
    // nothing" either. Only triggered when `items` is actually empty, so the common populated-catalog path
    // never reaches this block at all.
    val context = LocalContext.current
    val resolvedReportCatalogEmpty: suspend () -> Boolean = reportCatalogEmpty ?: {
        if (!CompanionQuickLaunchCatalog.hasLoaded.value) {
            CompanionQuickLaunchCatalog.refresh(context.applicationContext)
        }
        CompanionQuickLaunchCatalog.entries.value.isEmpty()
    }
    val catalogReportedEmpty = remember(presentationEpoch) { mutableStateOf(false) }
    LaunchedEffect(presentationEpoch, items.isEmpty()) {
        if (items.isNotEmpty()) return@LaunchedEffect
        // A dismiss mid-refresh (B-press during a slow scan) cancels this coroutine at the suspension
        // point inside `resolvedReportCatalogEmpty()`. `runCatching` must not swallow that
        // CancellationException -- doing so would let a cancelled effect "complete normally" and write
        // state during disposal, the standard structured-concurrency trap.
        catalogReportedEmpty.value = runCatching { resolvedReportCatalogEmpty() }
            .getOrElse { failure ->
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                false
            }
    }

    fun publishPresentationReady(ready: Boolean) {
        if (presentationReady.value == ready) return
        presentationReady.value = ready
        traceHomeQuickLaunchPicker("overlay presentation ready=$ready epoch=$presentationEpoch")
        onPresentationReadyChanged(ready)
    }

    DisposableEffect(authority) {
        authority.pushScope(HomeQuickLaunchPickerScope)
        onDispose {
            // Total for the same reason as the panel below it: disposal can arrive while something
            // this picker does not control sits on top.
            authority.discardScope(HomeQuickLaunchPickerScope)
        }
    }
    // The remembered package is an opening seed, not live navigation state. Keying this effect on it
    // would unregister/re-register the entire app grid after every General D-pad move because that move
    // persists a new package name.
    DisposableEffect(authority, keys, mode) {
        keys.forEachIndexed { index, focusKey ->
            val column = index % HomeQuickLaunchPickerColumns
            val row = index / HomeQuickLaunchPickerColumns
            authority.registerVirtual(
                key = focusKey,
                scope = HomeQuickLaunchPickerScope,
                bounds = FocusBounds(
                    left = column * 120f,
                    top = row * 112f,
                    right = column * 120f + 108f,
                    bottom = row * 112f + 100f,
                ),
            )
        }
        val preserved = preservedRegistrationFocus.value?.takeIf { it in keys }
        val authorityFocused = authority.state.value.focused?.takeIf { it in keys }
        val survivingFocus = when {
            preserved != null -> preserved
            seedLatch.value -> authorityFocused
            else -> null
        }
        if (survivingFocus != null) {
            authority.requestFocus(survivingFocus)
            preservedRegistrationFocus.value = null
        } else if (!seedLatch.value) {
            seedLatch.value = seedHomeQuickLaunchPickerFocus(authority, mode, items, initialGeneralPackageName)
        } else {
            seedHomeQuickLaunchPickerFocus(authority, mode, items, initialGeneralPackageName)
        }
        traceHomeQuickLaunchPicker(
            "overlay seed mode=$mode focus=${authority.state.value.focused} seeded=${seedLatch.value}",
        )
        onDispose {
            preservedRegistrationFocus.value = authority.state.value.focused?.takeIf { it in keys }
            keys.forEach(authority::unregisterVirtual)
        }
    }

    val focusState by authority.state.collectAsState()
    val focusedIndex = keys.indexOf(focusState.focused)
    val focusedStableId = items.getOrNull(focusedIndex)?.stableId
    LaunchedEffect(focusedIndex, mode) {
        if (mode == HomeQuickLaunchPickerMode.General) {
            (items.getOrNull(focusedIndex) as? HomeQuickLaunchPickerItem.App)
                ?.entry
                ?.packageName
                ?.let(onGeneralFocusChanged)
            }
    }
    // Readiness admits controller dispatch only. Rendering, touch, and accessibility stay live while
    // this signal settles so a stale/delayed measurement cannot create an inert visible modal.
    LaunchedEffect(presentationEpoch, items.isEmpty(), focusedStableId, focusState.scope) {
        if (items.isEmpty()) {
            publishPresentationReady(true)
            return@LaunchedEffect
        }
        val stableId = focusedStableId ?: return@LaunchedEffect
        if (focusedStableId == stableId && focusState.scope == HomeQuickLaunchPickerScope) {
            publishPresentationReady(true)
        }
    }
    LaunchedEffect(focusedStableId, presentationEpoch, gridPlaced.value) {
        val stableId = focusedStableId ?: return@LaunchedEffect
        if (!gridPlaced.value) return@LaunchedEffect
        val verticalGapPx = with(density) { 12.dp.roundToPx() }
        val ringReachPx = with(density) {
            (MimirSpacing.focusRingWidth + 92.dp * 0.02f).roundToPx()
        }
        val marginPx = verticalGapPx + ringReachPx
        val layoutInfo = gridState.layoutInfo
        if (layoutInfo.visibleItemsInfo.isEmpty()) return@LaunchedEffect
        val command = homeQuickLaunchPickerRevealCommand(
            targetIndex = focusedIndex,
            visibleItems = layoutInfo.visibleItemsInfo.map { visible ->
                HomeQuickLaunchPickerVisibleItem(
                    index = visible.index,
                    topPx = visible.offset.y,
                    heightPx = visible.size.height,
                )
            },
            viewportTopPx = layoutInfo.viewportStartOffset,
            viewportBottomPx = layoutInfo.viewportEndOffset,
            marginPx = marginPx,
            fallbackRowGapPx = verticalGapPx,
        )
        val frame = revealLedger.issue(stableId, command)
        if (frame == null) {
            return@LaunchedEffect
        }
        when (val reveal = frame.command) {
            is HomeQuickLaunchPickerRevealCommand.SeekBeforePresentation -> {
                gridState.scrollToItem(reveal.firstVisibleItemIndex)
            }
            is HomeQuickLaunchPickerRevealCommand.Measured -> {
                if (reveal.deltaPx != 0 && revealLedger.accepts(frame.epoch)) {
                    gridState.animateScrollBy(
                        value = reveal.deltaPx.toFloat(),
                        animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
                    )
                }
            }
            is HomeQuickLaunchPickerRevealCommand.Projected -> {
                if (reveal.deltaPx != 0 && revealLedger.accepts(frame.epoch)) {
                    gridState.animateScrollBy(
                        value = reveal.deltaPx.toFloat(),
                        animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.scrim.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        // This is deliberately a sibling behind the card, not a clickable ancestor. A Compose clickable
        // ancestor can arbitrate the same pointer stream as a grid tile on some API/input stacks; RP5
        // then swallowed the tile press before FocusAuthority or the exact-item activation reached it.
        // The back layer still absorbs all panel-gap taps and keeps outside-tap dismissal unchanged.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {
                        traceHomeQuickLaunchPicker("overlay scrim received tap epoch=$presentationEpoch")
                        onDismiss()
                    },
                ),
        )
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(20.dp))
                .background(palette.surfaceRaised)
                .border(1.dp, palette.accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MimirChromeText(
                text = copy.header,
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = copy.instruction,
                style = MimirTypeScale.Support,
                color = palette.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            MimirControlHintText(
                text = copy.legend,
                style = MimirTypeScale.ControlHintCompact,
                color = palette.textMuted,
                modifier = Modifier.padding(bottom = 12.dp),
                maxLines = 1,
                softWrap = false,
            )
            if (items.isEmpty()) {
                // Only render this once the catalog itself has confirmed empty (see `catalogReportedEmpty`
                // above) -- an unresolved/in-flight state renders nothing rather than a guess.
                if (catalogReportedEmpty.value) {
                    MimirChromeText(
                        text = "No apps found.",
                        style = MimirTypeScale.MicroStatus,
                        color = palette.textSecondary,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(HomeQuickLaunchPickerColumns),
                    state = gridState,
                    contentPadding = PaddingValues(
                        horizontal = 6.dp,
                        vertical = (6 + HomeQuickLaunchPickerRingClearanceDp).dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned {
                            if (!gridPlaced.value) {
                                gridPlaced.value = true
                                traceHomeQuickLaunchPicker("overlay grid placed epoch=$presentationEpoch")
                            }
                        },
                ) {
                    items(items, key = HomeQuickLaunchPickerItem::stableId) { item ->
                        val focusKey = homeQuickLaunchPickerKey(item)
                        val focused = focusState.scope == HomeQuickLaunchPickerScope &&
                            focusState.focused == focusKey
                        HomeQuickLaunchPickerTile(
                            authority = authority,
                            item = item,
                            focusKey = focusKey,
                            focused = focused,
                            onChoose = { onChoose(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeQuickLaunchPickerTile(
    authority: FocusAuthority,
    item: HomeQuickLaunchPickerItem,
    focusKey: FocusKey,
    focused: Boolean,
    onChoose: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(HomeQuickLaunchTileCorner)
    Column(
        modifier = Modifier
            .mimirFocusable(
                authority = authority,
                key = focusKey,
                scope = HomeQuickLaunchPickerScope,
                autoBringIntoView = false,
            )
            // The one ring on an app tile (ring migration, 2026-09-28): a card ring, no grow; the tile keeps its own
            // accent tint on focus (a card ring takes no wash). Keyed on the item, never the slot.
            .mimirSelectionRing(focused = focused, identity = item.stableId, shape = MimirRingShape.Card(HomeQuickLaunchTileCorner))
            .clip(shape)
            .background(if (focused) palette.accent.copy(alpha = 0.12f) else palette.surfaceRaised)
            .mimirActivatable(label = "Choose") {
                traceHomeQuickLaunchPicker("overlay tile click stableId=${item.stableId}")
                authority.requestFocus(focusKey)
                onChoose()
            }
            .heightIn(min = 92.dp)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(palette.surfaceBase.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            when (item) {
                HomeQuickLaunchPickerItem.Clear ->
                    Text("×", style = MimirTypeScale.Title, color = palette.accentBright)
                is HomeQuickLaunchPickerItem.App -> HomeQuickLaunchPickerIcon(item.entry)
            }
        }
        MimirIntentText(
            intent = when (item) {
                HomeQuickLaunchPickerItem.Clear -> MimirTextIntent.StaticChrome("Clear slot")
                is HomeQuickLaunchPickerItem.App -> MimirTextIntent.Authored(item.entry.label)
            },
            style = MimirTypeScale.Support,
            color = if (focused) palette.textPrimary else palette.textSecondary,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 5.dp).fillMaxWidth(),
        )
    }
}

/**
 * Decoded launcher icons, retained ACROSS grid-cell remounts.
 *
 * The picker's tiles are `LazyVerticalGrid` cells. Without this, every cell started at `null` and ran a
 * cold `BitmapFactory.decodeFile` on IO while painting the letter glyph in the meantime — so OPENING the
 * same wall of glyphs. The full-screen glyph-to-icon repaint produced a strobe when opening
 * Quick launch > All apps.
 *
 * Not a new idea: this is the same defect and the same remedy the Library shelf already carries. See
 * `PlatformHeroTile.kt`'s `platformCoverBitmapCache`: retaining decoded bitmaps prevents
 * console line-art or per-tile glyphs flashing before the fresh decode. The quick-launch picker
 * previously lacked that cache.
 *
 * [CompanionAppEntry.iconPath] is a CONTENT-ADDRESSED path: `writeVersionedNativeIcon`
 * (`NativeShelfPackages.kt`) stems it with the package-update epoch AND a digest of the PNG bytes, so one
 * path names exactly one set of bytes forever. An updated or reinstalled app therefore yields a NEW path
 * and a new key — this cache cannot serve a stale icon, which is the property the per-cell reset existed
 * to protect.
 *
 * Budgeted in BYTES like the shelf's cache, not in entries. Icons are written 192 px square as
 * `ARGB_8888` (`NativeShelfPackages.kt`'s `NativeIconCacheSizePx`) ≈ 144 KB each, so 16 MB holds ~113 of
 * them — a typical handheld's whole launcher set. Past that `LruCache` evicts least-recently-used, which
 * is the cells furthest from the viewport.
 */
internal object HomeQuickLaunchPickerIconCache {
    private const val BudgetBytes = 16 * 1024 * 1024

    private val cache = object : LruCache<String, ImageBitmap>(BudgetBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    fun get(iconPath: String): ImageBitmap? = cache.get(iconPath)

    fun put(iconPath: String, icon: ImageBitmap) {
        cache.put(iconPath, icon)
    }

    /** The cache is process-scoped; a test must not leak a warmed entry into the next one. */
    fun evictAll() {
        cache.evictAll()
    }

    /** Bytes held right now, for the app's memory release to report. */
    fun sizeBytes(): Int = cache.size()

    /**
     * Lane mem (2026-10-14): the app's answer to a system memory trim. Least recently used first, down to half the
     * budget, so the icons a mounted dock reads every paint stay found; returns the bytes let go.
     */
    fun trimToHalf(): Int {
        val before = cache.size()
        cache.trimToSize(BudgetBytes / 2)
        return before - cache.size()
    }

    /**
     * Whether the byte budget above is already committed. Only [warmHomeQuickLaunchPickerIcons] asks: a
     * bulk walk that kept going past this point would start evicting its OWN earlier work to make room for
     * entries the grid may never scroll to, and since the walk runs in the picker's own alphabetical order
     * the entries evicted first are exactly the ones the picker opens on. Stopping is strictly better than
     * churning. Individual cells never consult this — a cell decoding what it is about to draw should
     * always cache it, and LRU eviction is the correct answer for that access pattern.
     */
    fun isAtBudget(): Boolean = cache.size() >= BudgetBytes
}

/**
 * Warm every catalog icon into [HomeQuickLaunchPickerIconCache] ahead of the picker's first open.
 *
 * Icon prewarming: the earlier change gave the dock and the picker one shared cache, so a
 * PINNED app's icon is already decoded by the time the grid mounts. Unpinned apps were never rendered
 * before the picker, so every one of them still paid a cold `BitmapFactory.decodeFile` in its own cell's
 * `LaunchedEffect` — each landing on its own frame, producing a letter-glyph-then-icon wave.
 * Warming from the dock closes it because "All apps" is only reachable with the dock already
 * open (`openHomeQuickLaunchGeneral` requires `homeQuickLaunchOpen`), so this always has a head start.
 *
 * Walks in catalog order, which is the grid's order (both read the same label-sorted
 * `CompanionQuickLaunchCatalog.entries`), so a walk cut short by the player pressing through leaves the
 * FIRST page warm rather than an arbitrary slice. Cancellation is safe at any point: the cache is the only
 * state written and every entry in it is complete.
 *
 * Cells keep their own decode. This is a head start, not a prerequisite — a cell whose icon is not warm
 * behaves exactly as it does today, and nothing waits on this function.
 */
internal suspend fun warmHomeQuickLaunchPickerIcons(entries: List<CompanionAppEntry>) {
    withContext(Dispatchers.IO) {
        for (entry in entries) {
            // Dismissing the dock mid-walk cancels this; check before each decode rather than only at
            // suspension points, because a tight decode loop has none.
            ensureActive()
            if (HomeQuickLaunchPickerIconCache.isAtBudget()) return@withContext
            val path = entry.iconPath ?: continue
            if (HomeQuickLaunchPickerIconCache.get(path) != null) continue
            val decoded = runCatching { BitmapFactory.decodeFile(path) }.getOrNull()?.asImageBitmap()
            if (decoded != null) HomeQuickLaunchPickerIconCache.put(path, decoded)
        }
    }
}

@Composable
private fun HomeQuickLaunchPickerIcon(entry: CompanionAppEntry) {
    val palette = LocalMimirPalette.current
    // Same shape and reasoning as HomeQuickLaunchSlotRow: the keyed `remember` rebuilds the state when the
    // entry's identity changes — the same reset the previous outer `key(...)` gave — so a recycled grid
    // cell can never show the PREVIOUS entry's icon. A direct keyed `produceState` would restart the
    // producer but keep its remembered state.
    //
    // What changed is only the SEED. The state now starts at this entry's own already-decoded icon when
    // there is one, so a warm cell paints the icon on its FIRST frame instead of showing the letter glyph
    // until an IO decode lands. The key still carries the entry's identity, so the no-stale-icon invariant
    // above is untouched — see [HomeQuickLaunchPickerIconCache] for why the path is a safe identity.
    val iconPath = entry.iconPath
    val iconState = remember(entry.packageName, iconPath) {
        mutableStateOf(iconPath?.let(HomeQuickLaunchPickerIconCache::get))
    }
    LaunchedEffect(entry.packageName, iconPath) {
        // A cache hit is already the final value for this key; decoding again would be pure cost.
        if (iconState.value != null) return@LaunchedEffect
        val path = iconPath ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }?.asImageBitmap()
        if (decoded != null) HomeQuickLaunchPickerIconCache.put(path, decoded)
        iconState.value = decoded
    }
    // Local val so the `icon != null` check below can smart-cast (a delegated property cannot).
    val icon = iconState.value
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = entry.label,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)),
        )
    } else {
        Text(entry.label.take(1).uppercase(), style = MimirTypeScale.Row, color = palette.accent)
    }
}
