package com.gruning.mimir.rewrite

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import com.gruning.mimir.rewrite.core.input.ControllerAction
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusBounds
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * FUX-01 §4.4 — the Home dock's Quick Launch picker. TRUTH SHARED, WIDGET NEW: the pinned slots are the
 * SAME [QuickLaunchStore] slots the companion footer uses (one persistence, one catalog, one launch
 * transaction) — this surface adds controller reach on the primary display. Pinned slot + A = launch;
 * empty slots read honestly; Y edits any pinned slot; General opens the full
 * local app catalog. "Same cancellation behavior" = same outcome: B dismisses without action.
 */
internal val HomeQuickLaunchScope = FocusScope("home/quicklaunch")

internal const val HomeQuickLaunchHeader = "ᛗ · QUICK LAUNCH"
internal const val HomeQuickLaunchLegend = "A OPEN · Y EDIT · B BACK"
internal const val HomeQuickLaunchSlotsTestTag = "home_quick_launch_slots"

internal fun homeQuickLaunchSlotKey(index: Int): FocusKey = FocusKey("home/quicklaunch/slot-$index")

internal data class HomeQuickLaunchSlotUi(
    val index: Int,
    val packageName: String?,
    val label: String?,
    /** FUX-03: the app's real icon (catalog iconPath) — the dock picker shows icons, not slot numbers. */
    val iconPath: String? = null,
    /** QuickLaunchStore rule: General is the trailing action and is never persisted as a pin. */
    val general: Boolean = false,
)

internal fun homeQuickLaunchSlots(
    slots: List<String?>,
    labelFor: (String) -> String?,
    iconFor: (String) -> String? = { null },
): List<HomeQuickLaunchSlotUi> = buildList {
    repeat(QuickLaunchStore.SLOT_COUNT) { index ->
        val pkg = slots.getOrNull(index)
        add(
            HomeQuickLaunchSlotUi(
                index = index,
                packageName = pkg,
                label = pkg?.let { labelFor(it) ?: it },
                iconPath = pkg?.let(iconFor),
            ),
        )
    }
    add(
        HomeQuickLaunchSlotUi(
            index = QuickLaunchStore.SLOT_COUNT,
            packageName = null,
            label = "All apps",
            general = true,
        ),
    )
}

/** Strict order per the focus-scope law: pushScope → register → requestFocus (else focus no-ops). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeQuickLaunchOverlay(
    authority: FocusAuthority,
    slots: List<HomeQuickLaunchSlotUi>,
    onLaunch: (String) -> Unit,
    onOpenGeneral: () -> Unit,
    onEdit: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    // Icon prewarming: the per-slot decode below covers this dock's <=3 PINNED apps. The picker
    // one press away renders the whole catalog, and every unpinned icon in it was still cold. This walk
    // pre-decodes them into the shared cache while the player is reading the dock -- the one moment that
    // is guaranteed to precede the picker. Keyed on the catalog so a package install/removal re-warms;
    // dismissing the dock cancels it, which costs nothing because the cache keeps whatever it reached.
    //
    // Nothing waits on this and no state below reads it: the picker's cells are unchanged and still decode
    // their own icon on a miss. Failure or cancellation is invisible except as the old cold wave.
    val catalogEntries by CompanionQuickLaunchCatalog.entries.collectAsState()
    LaunchedEffect(catalogEntries) {
        warmHomeQuickLaunchPickerIcons(catalogEntries)
    }
    DisposableEffect(authority) {
        authority.pushScope(HomeQuickLaunchScope)
        slots.forEach { slot ->
            authority.registerVirtual(
                key = homeQuickLaunchSlotKey(slot.index),
                scope = HomeQuickLaunchScope,
                bounds = FocusBounds(0f, slot.index * 100f, 400f, slot.index * 100f + 90f),
            )
        }
        // Land on the first PINNED slot — the actionable thing — falling back to slot 0.
        val first = slots.firstOrNull { it.packageName != null } ?: slots.firstOrNull()
        first?.let { authority.requestFocus(homeQuickLaunchSlotKey(it.index)) }
        onDispose {
            // Total, because this panel is buried BY DESIGN: `mimirOverlayOutranksQuickLaunch`
            // enumerates the overlay kinds that sit above it, and several of them (MIGRATION, the
            // text-entry modals) push a focus scope of their own. A guarded pop disposing under one of
            // those would decline and strand this scope for the life of the process.
            //
            // The folder browser and the Get art and fetch cards are no longer among them: the panel
            // opens OVER those (`mimirOverlayStaysUnderQuickLaunch`), so its scope sits above theirs on
            // the authority's stack, and closing the panel pops back to the focus they remembered. The
            // ScreenScraper sign-in still buries it, as a credential entry with a keyboard scope of its own.
            authority.discardScope(HomeQuickLaunchScope)
        }
    }
    val focusState by authority.state.collectAsState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // FUX-03 liquid glass: a lighter scrim so the Home scene reads through the panel.
            .background(palette.surfaceBase.copy(alpha = 0.62f))
            // The scrim never dismisses; exit is B or a slot action — same as every hard modal here.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints
        val rootProfile = LocalMimirViewportProfile.current
        val compactLayout = (if (rootProfile.widthDp > 0f) {
            rootProfile.isCompact
        } else {
            mimirViewportProfile(viewport.width.value, viewport.height.value).isCompact
        }) ||
            viewport.height.value <= 600f
        val pinnedSlots = slots.filterNot { it.general }
        val generalSlot = slots.firstOrNull { it.general }
        val scrollState = rememberScrollState()
        // The glass panel clips before its padding, so that padding is every row ring's clearance to the clip; a
        // full-width row pulls in the rest of MimirFocusTileRingReach (ring migration, 2026-09-28, spec §3.6).
        val rowRingInset = homeTileMenuRowRingInset(if (compactLayout) MimirSpacing.sm else MimirSpacing.xl)
        // FUX-03 liquid glass: translucent leather gradient + a glass sheen hairline along
        // the top edge — the scene stays visible through the panel (no blur; token-driven, Thor-safe).
        val glass = remember(palette) {
            val hero = palette.materials.hero
            Brush.verticalGradient(
                listOf(
                    hero.coverTop.copy(alpha = 0.96f),
                    hero.coverMiddle.copy(alpha = 0.96f),
                    hero.coverBottom.copy(alpha = 0.96f),
                ),
            )
        }
        Column(
            modifier = Modifier
                .mimirMeasuredViewport(viewport)
                .wrapContentSize(Alignment.Center)
                .widthIn(max = if (compactLayout) 340.dp else 440.dpCompat())
                .heightIn(max = viewport.height)
                .padding(if (compactLayout) MimirSpacing.sm else MimirSpacing.xl)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(glass)
                .drawBehind {
                    drawLine(
                        color = palette.materials.glassSheen,
                        start = Offset(0f, 0.75.dp.toPx()),
                        end = Offset(size.width, 0.75.dp.toPx()),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
                .padding(if (compactLayout) MimirSpacing.sm else MimirSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(if (compactLayout) MimirSpacing.xs else MimirSpacing.md),
        ) {
            MimirChromeText(
                HomeQuickLaunchHeader,
                style = MimirTypeScale.RunicEyebrow,
                color = palette.accent,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(scrollState)
                    // A scroll host clips along its axis: the first and last rows' rings need the full reach there.
                    .padding(horizontal = rowRingInset, vertical = MimirFocusTileRingReach)
                    .testTag(HomeQuickLaunchSlotsTestTag),
                verticalArrangement = Arrangement.spacedBy(
                    if (compactLayout) MimirSpacing.xs else MimirSpacing.md,
                ),
            ) {
                pinnedSlots.forEach { slot ->
                    HomeQuickLaunchSlotEntry(
                        authority = authority,
                        focusState = focusState,
                        slot = slot,
                        onLaunch = onLaunch,
                        onOpenGeneral = onOpenGeneral,
                        onEdit = onEdit,
                        compact = compactLayout,
                    )
                }
            }
            generalSlot?.let { slot ->
                // Outside the scroll host the panel is the clip; the same inset keeps this row's ring inside it.
                Box(Modifier.padding(horizontal = rowRingInset, vertical = rowRingInset)) {
                    HomeQuickLaunchSlotEntry(
                        authority = authority,
                        focusState = focusState,
                        slot = slot,
                        onLaunch = onLaunch,
                        onOpenGeneral = onOpenGeneral,
                        onEdit = onEdit,
                        compact = compactLayout,
                    )
                }
            }
            MimirControlHintText(
                HomeQuickLaunchLegend,
                style = MimirTypeScale.ControlHint,
                color = palette.textMuted,
            )
            MimirChromeText(
                "Close",
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .padding(vertical = MimirSpacing.sm)
                    .wrapContentHeight(Alignment.CenterVertically),
                style = MimirTypeScale.ControlHintCompact,
                color = palette.textSecondary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeQuickLaunchSlotEntry(
    authority: FocusAuthority,
    focusState: FocusState,
    slot: HomeQuickLaunchSlotUi,
    onLaunch: (String) -> Unit,
    onOpenGeneral: () -> Unit,
    onEdit: (Int) -> Unit,
    compact: Boolean,
) {
    // This panel stays composed under its Edit picker; the focus flag only paints the row ring while
    // the picker owns input, so the covered row keeps its layout without claiming controller input.
    // Keep the row in the one virtual focus graph while asking its scroll ancestor to reveal it.
    val focused = authority.isLitFocusIn(
        HomeQuickLaunchScope,
        homeQuickLaunchSlotKey(slot.index),
        focusState,
    )
    HomeQuickLaunchSlotRow(
        slot = slot,
        focused = focused,
        onActivate = {
            when {
                slot.general -> onOpenGeneral()
                slot.packageName != null -> onLaunch(slot.packageName)
            }
        },
        onEdit = { if (!slot.general) onEdit(slot.index) },
        onRequestFocus = { authority.requestFocus(homeQuickLaunchSlotKey(slot.index)) },
        compact = compact,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeQuickLaunchSlotRow(
    slot: HomeQuickLaunchSlotUi,
    focused: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onRequestFocus: () -> Unit,
    compact: Boolean,
) {
    val palette = LocalMimirPalette.current
    val pinned = slot.packageName != null
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    val bringIntoView = remember { BringIntoViewRequester() }
    // The ring reaches MimirFocusTileRingReach past the row, so a focused row asks its scroll host to reveal the ring
    // too. A plain array, not state: written on every layout, read only when focus lands.
    val ringReachPx = with(LocalDensity.current) { MimirFocusTileRingReach.toPx() }
    val laidOut = remember { IntArray(2) }
    LaunchedEffect(focused) {
        if (focused) {
            bringIntoView.bringIntoView(
                if (laidOut[0] > 0 && laidOut[1] > 0) {
                    Rect(-ringReachPx, -ringReachPx, laidOut[0] + ringReachPx, laidOut[1] + ringReachPx)
                } else {
                    null
                },
            )
        }
    }
    // FUX-03: the app's real icon leads the row; the Qn tag survives only as the empty-slot fallback.
    // The reset is the point: when the slot's identity changes the icon must go to null in that same
    // composition, so a re-bound row shows NO icon rather than briefly showing the previous app's. The
    // keyed `remember` does that by rebuilding the state — the same reset the previous outer `key(...)`
    // wrapper gave by discarding the whole group. A direct keyed `produceState` would NOT: its keys
    // restart the producer but its own `remember { mutableStateOf(initial) }` carries none, so the stale
    // bitmap would survive until the new decode landed. `LaunchedEffect` keeps the decode async.
    val iconPath = slot.iconPath
    // T2 item 1: warm-seeded from the SAME cache the "quick launch > all apps" picker reads
    // (HomeQuickLaunchPickerOverlay.kt's HomeQuickLaunchPickerIconCache) instead of a private cold decode.
    // Both surfaces read the identical CompanionAppEntry.iconPath, sourced from the identical
    // CompanionQuickLaunchCatalog.entries flow (MimirAppCoordinator.kt: `homeQuickLaunchSlotsUi`'s
    // `iconFor` and `homeQuickLaunchPickerItemsUi` both key off `quickLaunchApps`'s `catalogByPackage`) --
    // and iconPath is content-addressed (NativeShelfPackages.kt's `writeVersionedNativeIcon` stems it with
    // the package-update epoch AND a digest of the PNG bytes), so one path always names one set of bytes.
    // Sharing the cache across these two surfaces cannot serve either one a stale icon.
    //
    // "All apps" can only be reached with this dock already open (`openHomeQuickLaunchGeneral` requires
    // `homeQuickLaunchOpen`), so decoding a pinned slot's icon here warms the picker's grid cell for that
    // SAME app before the picker ever mounts. UNPINNED apps used to be the residual gap -- never rendered
    // here, so they still cold-decoded on the picker's own first open -- and the overlay-level warm walk
    // below (`warmHomeQuickLaunchPickerIcons`) now covers them from the same seam and the same cache.
    val iconState = remember(slot.packageName, iconPath) {
        mutableStateOf(iconPath?.let(HomeQuickLaunchPickerIconCache::get))
    }
    LaunchedEffect(slot.packageName, iconPath) {
        // A cache hit is already the final value for this key; decoding again would be pure cost.
        if (iconState.value != null) return@LaunchedEffect
        val path = iconPath ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }?.asImageBitmap()
        if (decoded != null) HomeQuickLaunchPickerIconCache.put(path, decoded)
        iconState.value = decoded
    }
    // Read into a local val: a `by`-delegated property cannot be smart-cast at the `icon != null` check
    // below, and keeping `icon` a plain local leaves every downstream usage untouched.
    val icon = iconState.value
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .bringIntoViewRequester(bringIntoView)
            .onSizeChanged { laidOut[0] = it.width; laidOut[1] = it.height }
            // The one ring (ring migration, 2026-09-28), as the Edit picker's tiles one press away wear it: a row ring
            // on the card corner. Its wash replaces the 0.16 tint and its resting outline is the old hairline.
            .mimirSelectionRing(
                focused = focused,
                identity = homeQuickLaunchSlotKey(slot.index),
                shape = MimirRingShape.Row(MimirSpacing.radiusCard),
                restingOutline = true,
                outlineColor = palette.outline,
            )
            .clip(shape)
            .clickable(
                interactionSource = remember(slot.index) { MutableInteractionSource() },
                indication = null,
            ) {
                onRequestFocus()
                onActivate()
            }
            .padding(
                horizontal = if (compact) MimirSpacing.md else MimirSpacing.lg,
                vertical = if (compact) 6.dp else MimirSpacing.md,
            ),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (slot.general) {
            HomeQuickLaunchGeneralGlyph()
        } else if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
            )
        } else {
            Text(
                "Q${slot.index + 1}",
                style = MimirTypeScale.MicroStatus,
                color = if (pinned) palette.accentBright else palette.textMuted,
            )
        }
        MimirIntentText(
            intent = slot.label?.let { MimirTextIntent.Authored(it) }
                ?: MimirTextIntent.StaticChrome("Empty slot"),
            style = MimirTypeScale.Row,
            color = when {
                focused && (pinned || slot.general) -> palette.textPrimary
                pinned || slot.general -> palette.textSecondary
                else -> palette.textMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!slot.general) {
            Row(
                modifier = Modifier
                    // the pill ban is universal, and this row fires `onEdit`.
                    .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                    .clickable(
                        interactionSource = remember(slot.index, "edit") { MutableInteractionSource() },
                        indication = null,
                        onClick = onEdit,
                    )
                    .padding(horizontal = MimirSpacing.sm, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(17.dp)
                        .border(1.1.dp, palette.textSecondary.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Y", style = MimirTypeScale.MicroStatus, color = palette.textSecondary)
                }
                MimirChromeText("Edit", style = MimirTypeScale.ControlHintCompact, color = palette.textSecondary)
            }
        }
    }
}

@Composable
private fun HomeQuickLaunchGeneralGlyph() {
    val palette = LocalMimirPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(2) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.accent),
                    )
                }
            }
        }
    }
}

/** The pinned slots + General reducer: A launches/opens, Y edits a real slot, B dismisses. */
internal fun dispatchHomeQuickLaunch(
    authority: FocusAuthority,
    slots: List<HomeQuickLaunchSlotUi>,
    action: ControllerAction,
    onLaunch: (String) -> Unit,
    onOpenGeneral: () -> Unit = {},
    onEdit: (Int) -> Unit = {},
    onDismiss: () -> Unit,
): Boolean {
    if (slots.isEmpty()) {
        if (action == ControllerAction.CANCEL || action == ControllerAction.SYSTEM_BACK) onDismiss()
        return true
    }
    val focusedIndex = slots.indexOfFirst {
        homeQuickLaunchSlotKey(it.index) == authority.state.value.focused
    }
    return when (action) {
        ControllerAction.NAV_UP, ControllerAction.NAV_DOWN -> {
            val delta = if (action == ControllerAction.NAV_UP) -1 else 1
            // Degenerate focus (nothing of ours focused yet): the first press lands deterministically
            // on slot 0 in either direction, never one step past a slot the user could not see focused.
            val next = if (focusedIndex < 0) 0 else (focusedIndex + delta + slots.size) % slots.size
            authority.requestFocus(homeQuickLaunchSlotKey(slots[next].index))
            true
        }
        ControllerAction.CONFIRM -> {
            slots.getOrNull(focusedIndex)?.let { slot ->
                if (slot.general) onOpenGeneral() else slot.packageName?.let(onLaunch)
            }
            true
        }
        ControllerAction.TERTIARY -> {
            slots.getOrNull(focusedIndex)?.takeUnless { it.general }?.let { onEdit(it.index) }
            true
        }
        ControllerAction.CANCEL, ControllerAction.SYSTEM_BACK -> {
            onDismiss()
            true
        }
        else -> true
    }
}

private fun Int.dpCompat() = androidx.compose.ui.unit.Dp(this.toFloat())
