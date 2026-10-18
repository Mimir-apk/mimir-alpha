package com.gruning.mimir.rewrite.feature.companion

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gruning.mimir.rewrite.core.model.CompanionAppEntry
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiArtReceipt
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import android.content.Context
import androidx.compose.ui.graphics.graphicsLayer
import com.gruning.mimir.rewrite.core.ui.mimirArtSourceWired
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiControl
import com.gruning.mimir.rewrite.core.ui.LocalMimirKeyboardHapticFeedback
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassScrim
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirAppIconShape
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val CompanionRecentsPanelTag = "companion-recents-panel"
internal const val CompanionRecentsOpaqueBackdropTag = "companion-recents-opaque-backdrop"
internal const val CompanionRecentsHeader = "ᛗ · TABS"

/** A tab's icon edge: the 72 dp card less its 9 dp inset on every side, which is the footprint the icon had before it took the one shape. */
internal const val CompanionTabIconDp = 54f
internal const val CompanionTabIconTag = "companion-tab-icon"

/**
 * Mimir Alpha :feature-companion — the companion app drawer (shipping favorite-app surface).
 *
 * Distinct from the display-only [CompanionSurface] mirror: this is the ONE interactive companion surface,
 * so it lives in its OWN file (the mirror remains action-free in `CompanionSurface`; interactive commands live here). It is opened by the
 * native quick-menu **RECENTS** action (an overlay action), never from the mirror; a tap **launches on the
 * secondary display via the NATIVE `SecondaryTaskRegistry`** ([onLaunch] delegates to it) — the FE only
 * picks. Honest truth: "recents" are the sessions Mimir launched/moved (no OS-global recents API).
 *
 * this is the bottom-screen TAB MANAGER — the apps Mimir launched on screen 2, each with a close.
 * Tapping a tab brings it back to the front; tapping its × closes it (best-effort). Honest limit: only
 * apps Mimir launched are knowable, so nothing the user opened outside Mimir appears here.
 *
 * @param entries the live tabs (already mapped from the native registry). Empty → an honest empty note.
 * @param onLaunch bring the picked tab to the front of the secondary display (native), then dismiss.
 * @param onClose close the picked tab (native, best-effort); the list refreshes live.
 * @param onDismiss close the drawer (scrim tap / Back).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompanionRecentsDrawer(
    /** Null when this host offers no instruments panel; otherwise whether it is currently up. */
    instrumentsShown: Boolean? = null,
    onToggleInstruments: () -> Unit = {},
    entries: List<CompanionAppEntry>,
    /**
     * Whether the per-tab ✕ is offered at all . False only while the
     * companion runtime is in RecoveryNeeded, where the close path cannot reclaim the display first and
     * so the reap no-ops against a still-visible app. Hidden rather than relabelled.
     */
    closeOffered: Boolean = true,
    launchLocked: Boolean,
    launchMessage: String?,
    closeMessage: String? = null,
    closePending: Boolean = false,
    themeId: String?,
    quickSlots: List<String?>,
    quickLaunchApps: List<CompanionAppEntry>,
    /**
     * Whether [quickLaunchApps] has been swept. The drawer draws the same rail as the shelf, so it
     * carried the same defect: an unswept pin read as an empty seat that launched anyway.
     */
    quickLaunchCatalogLoaded: Boolean = true,
    onQuickLaunch: (String) -> Unit,
    onQuickAssign: (Int, String) -> Unit,
    onQuickClear: (Int) -> Unit = {},
    onPinnedQuickLaunch: (String) -> Unit = onQuickLaunch,
    quickLaunchChoice: CompanionQuickLaunchChoice? = null,
    onQuickLaunchChoice: (CompanionQuickLaunchChoice, CompanionQuickLaunchDestination) -> Unit = { _, _ -> },
    onQuickLaunchChoiceDismiss: (CompanionQuickLaunchChoice) -> Unit = {},
    onQuickLaunchEdit: ((String) -> Unit)? = null,
    onLaunch: (String) -> Unit,
    onClose: (String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * a tap [launchLocked] dropped, reported with its target -- a tab's or a rail seat's package,
     * `all_apps`, `empty_q<N>`, or `dismiss` for the backdrop. The control still ignores it exactly as before.
     */
    onQuickLaunchIgnored: (target: String) -> Unit = {},
    /**
     * R1 : over a dual-screen game the drawer is an overlay window above the emulator's own, and the emulator's
     * screen must not show through its frost. True lays the theme's opaque base under the scrim; false leaves the glass as it was.
     */
    opaqueBackdrop: Boolean = false,
    modifier: Modifier = Modifier,
) {
    MimirTheme(themeId = MimirThemeId.fromStorageKey(themeId)) {
        // A dim scrim over the companion; tapping outside the panel dismisses. Uses a no-indication
        // clickable so the whole backdrop is a dismiss target without a ripple.
        val scrimSource = remember { MutableInteractionSource() }
        val latestQuickLaunchIgnored = rememberUpdatedState(onQuickLaunchIgnored)
        val tabsControl = rememberBottomUiControl(BottomSurface.TABS)
        // The rail's grid seat opens Quick Launch's All apps picker inside this window. Quick Launch is then the only panel on the Bottom: the Tabs panel and the drawer's rail are
        // cleared while it is up (the panel was left standing under the picker, and its copy read through the picker's tiles), and closing the picker closes the drawer with it. A pick keeps the drawer, as
        // it did: the launch lock and a failed launch's message live in the panel, so a launching drawer shows it again.
        var allAppsPickerShown by remember { mutableStateOf(false) }
        var allAppsPickerDismissed by remember { mutableStateOf(false) }
        LaunchedEffect(launchLocked) { if (launchLocked) allAppsPickerDismissed = false }
        val tabsPanelShown = !allAppsPickerShown && !allAppsPickerDismissed
        val panelReducedMotion = remember { bottomUiReducedMotion() }
        Box(
            modifier = modifier
                .fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (opaqueBackdrop) {
                Box(Modifier.fillMaxSize().background(LocalMimirPalette.current.surfaceBase).testTag(CompanionRecentsOpaqueBackdropTag))
            }
            MimirLiquidGlassScrim(
                modifier = Modifier
                    .fillMaxSize()
                    // QA D17: a dismiss tap during a launch is dropped by the disabled clickable; watched, never consumed.
                    .companionLaunchLockedTapObserver(launchLocked) {
                        latestQuickLaunchIgnored.value(CompanionLaunchIgnoredDismiss)
                    }
                    .clickable(
                        enabled = !launchLocked,
                        interactionSource = scrimSource,
                        indication = null,
                        onClick = {
                            tabsControl("tabs.dismiss", null)
                            onDismiss()
                        },
                    ),
            )
            // The bottom panel. Its own no-op clickable ABSORBS taps so a tap on the panel's background
            // never bubbles to the scrim (only the scrim and the app tiles act).
            val panelSource = remember { MutableInteractionSource() }
            AnimatedVisibility(
                visible = tabsPanelShown,
                enter = if (panelReducedMotion) EnterTransition.None else fadeIn(mimirOverlayLeaveSpec()),
                exit = if (panelReducedMotion) ExitTransition.None else fadeOut(mimirOverlayLeaveSpec()),
            ) {
                MimirLiquidGlassPanel(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .padding(bottom = CompanionQuickLaunchHeightDp.dp)
                        .testTag(CompanionRecentsPanelTag)
                        .clickable(interactionSource = panelSource, indication = null, onClick = {}),
                    contentPadding = MimirSpacing.lg,
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                    gradientBody = false,
                ) {
                    val palette = LocalMimirPalette.current
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.alpha(if (launchLocked) 0.34f else 1f),
                            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                        ) {
                            MimirChromeText(
                                CompanionRecentsHeader,
                                style = MimirTypeScale.RunicEyebrow,
                                color = palette.accent,
                            )
                            // V3 (review 2026-07-11): a launch that failed clears the lock (launchLocked=false) but
                            // leaves launchMessage set — the ONLY (unlocked, message!=null) state is that failure, so
                            // surface it here where the Column is fully visible. The spinner overlay handles in-flight.
                            if (!launchLocked && launchMessage != null) {
                                Text(launchMessage, style = MimirTypeScale.Support, color = palette.danger)
                            }
                            if (!launchLocked && closeMessage != null) {
                                Text(
                                    closeMessage,
                                    style = MimirTypeScale.Support,
                                    color = if (closePending) palette.textSecondary else palette.danger,
                                )
                            }
                            if (entries.isEmpty()) {
                                MimirChromeText(
                                    "Apps opened from Mimir appear here. Earlier apps may still be running.",
                                    style = MimirTypeScale.MicroStatus,
                                    color = palette.textMuted,
                                )
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(116.dp),
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                                ) {
                                    items(entries, key = { it.packageName }) { entry ->
                                        RecentAppTile(
                                            entry = entry,
                                            enabled = !launchLocked,
                                            closeOffered = closeOffered,
                                            onLaunch = {
                                                tabsControl("tabs.tab.launch", entry.packageName)
                                                onLaunch(entry.packageName)
                                            },
                                            onClose = {
                                                tabsControl("tabs.tab.close", entry.packageName)
                                                onClose(entry.packageName)
                                            },
                                            onLaunchLockedTap = { latestQuickLaunchIgnored.value(entry.packageName) },
                                        )
                                    }
                                }
                            }
                        }
                        if (launchLocked) {
                            DrawerLaunchLock(
                                message = launchMessage ?: "Opening…",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            CompanionQuickLaunchLayer(
                onAllAppsPickerShown = { allAppsPickerShown = it },
                onAllAppsPickerDismissed = {
                    allAppsPickerDismissed = true
                    onDismiss()
                },
                // **The two rails must agree.** This host passed nothing, so it drew FIVE seats while
                // the companion surface drew SIX — and with the drawer open, a sharp 5-seat rail sat
                // over a 6-seat one, centres aligned and edges 33 dp apart. Two rails on one product
                // is the drift; one rail rendered twice is the intent.
                instrumentsShown = instrumentsShown,
                onToggleInstruments = onToggleInstruments,
                slots = quickSlots,
                apps = quickLaunchApps,
                catalogLoaded = quickLaunchCatalogLoaded,
                launchLocked = launchLocked,
                launchMessage = launchMessage,
                onLaunch = onQuickLaunch,
                onPinnedLaunch = onPinnedQuickLaunch,
                onAssign = onQuickAssign,
                onClear = onQuickClear,
                quickLaunchChoice = quickLaunchChoice,
                onQuickLaunchChoice = onQuickLaunchChoice,
                onQuickLaunchChoiceDismiss = onQuickLaunchChoiceDismiss,
                onQuickLaunchEdit = onQuickLaunchEdit,
                onQuickLaunchIgnored = onQuickLaunchIgnored,
                // The drawer's own rail goes with its panel: the picker's card stops short of the bottom edge, and the seats' lower ends (the grid's dots, the bars, the + marks)
                // stood under it as cut slivers. Nothing of Tabs shows while the picker is up, and nothing comes back while the drawer leaves after a Close.
                railHidden = !tabsPanelShown,
                // This layer is composed inside the Tabs window, above its panel: the stack snapshot sorts its picker, pin actions and
                // launch prompt above `tabs`, not below it as they sort in the Presentation's own surface group.
                receiptHost = BottomSurface.TABS,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * One tab: a themed icon shell + label; tapping the body brings it to the front. A live tab (CR-11) also
 * carries a small × in the corner to close it — the close sits on its own touch target so a tap to focus
 * never closes by accident.
 */
@Composable
private fun RecentAppTile(
    entry: CompanionAppEntry,
    enabled: Boolean,
    closeOffered: Boolean,
    onLaunch: () -> Unit,
    onClose: () -> Unit,
    /** QA D17: once per tap on this tab (its close badge included) that a disabled [enabled] dropped. */
    onLaunchLockedTap: () -> Unit = {},
) {
    val palette = LocalMimirPalette.current
    val hapticFeedback = LocalMimirKeyboardHapticFeedback.current
    val latestLaunchLockedTap = rememberUpdatedState(onLaunchLockedTap)
    val glyph = entry.label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
    // The close badge acts on one clear tap. A two-step confirmation previously gave no
                        // visible result on the first tap. Its 40dp badge has approximately matching visible
                        // and touch bounds and is separate from the launch tile, limiting accidental closes.
                        // The card uses the Bottom's tokenized press response; a default ripple would add
                        // a second response to the same touch.
    val cardSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(108.dp)
            // QA D17: the disabled clickable below drops a tab tap during a launch; watched, never consumed.
            .companionLaunchLockedTapObserver(locked = !enabled) { latestLaunchLockedTap.value() }
            .clickable(
                enabled = enabled,
                interactionSource = cardSource,
                indication = null,
                onClick = {
                    hapticFeedback?.invoke()
                    onLaunch()
                },
            )
            // After the tap and before the fill, so the card draws in whole while its hit box stays whole.
            .mimirPressResponse(cardSource)
            .clip(RoundedCornerShape(MimirSpacing.radiusCard))
            .padding(MimirSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                    .background(palette.surfaceHigh)
                    .border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.radiusCard)),
                contentAlignment = Alignment.Center,
            ) {
                AppIconOrGlyph(entry = entry, glyph = glyph)
            }
            // `closeOffered` is the runtime's answer, `entry.closable` the task's. The ✕ needs
            // both, and its absence is the whole message -- no disabled badge, no replacement label.
            if (entry.closable && closeOffered) {
                // WYSIWYG target: a badge whose VISIBLE size is its hittable size — no hidden 24→48 zone.
                // That WYSIWYG target remains; what changed on 2026-08-31 is the number. It sat at
                // 40 dp, 8 short of §4's 48 dp minimum, because V2 predates that line. The two are only in
                // conflict if WYSIWYG is read as "small": grow the badge and both hold at once, which is
                // why both the visible badge and target grow together.
                // G-3: the ✕'s honest promise is "remove this tab" — the platform only permits a best-effort
                // process reap, so the accessible name says what always happens, not what sometimes might.
                val closeSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(MimirSpacing.minTarget)
                        // Spoken as "Close <app>": the ✕ stops the app for real, so
                        // "Remove <app> tab" undersold what happens.
                        .clickable(
                            enabled = enabled,
                            interactionSource = closeSource,
                            indication = null,
                            onClickLabel = "Close ${entry.label}",
                            onClick = {
                                hapticFeedback?.invoke()
                                onClose()
                            },
                        )
                        .mimirPressResponse(closeSource)
                        .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                        .background(palette.surfaceBase.copy(alpha = 0.92f))
                        .border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.radiusCard))
                        .semantics { contentDescription = "Close ${entry.label}" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.clearAndSetSemantics { })
                }
            }
        }
        Text(
            entry.label,
            style = MimirTypeScale.Support,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        entry.kindLabel?.let { kind ->
            Text(
                kind,
                style = MimirTypeScale.MicroStatus,
                color = palette.textMuted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
@SuppressLint("ProduceStateDoesNotAssignValue")
private fun AppIconOrGlyph(entry: CompanionAppEntry, glyph: String) {
    val palette = LocalMimirPalette.current
    val context = LocalContext.current
    val artRef = entry.artRef?.takeIf { it.isNotBlank() }
    val iconPath = entry.iconPath?.takeIf { it.isNotBlank() }
    // A game tab's cover outranks the emulator's icon, and falls back to it when the ref cannot be opened (both are wired sources).
    // Decode OFF the main thread, in three states (R3, R8): the art once loaded, the letter only for a tab with no source (or whose
    // icon file is gone and has no cover), and NOTHING while it loads or when every wired source failed. The icon is read from the
    // shared icon cache on frame 0 when no cover is wired.
    val warmCover = remember(artRef) { companionWarmTabCover(artRef) }
    val warmIcon = remember(artRef, iconPath) { if (artRef == null) companionWarmIcon(iconPath) else null }
    val warm = warmCover ?: warmIcon
    val produced = rememberIdentityProducedValue(entry.packageName, artRef ?: iconPath) {
        if (warm != null) null else decodeCompanionTabArt(context, artRef, iconPath)
    }
    // A warm icon paints without a stat, so its file is verified off-main (R8a): a deleted icon drops the cache entry and the tab takes
    // its fallback letter after at most a stale frame.
    val goneIcon = rememberCompanionWarmGone(
        identity = entry.packageName to iconPath,
        source = iconPath,
        warm = warmIcon != null,
        drop = { iconPath?.let { companionAppIconCache.remove(it) } },
    )
    // The same check for a warm cover: a local file that is gone drops its entry (a content reference cannot be statted, so it stands).
    val goneCover = rememberCompanionWarmGone(
        identity = entry.packageName to artRef,
        source = artRef,
        warm = warmCover != null,
        drop = { artRef?.let { companionAppIconCache.remove(companionTabCoverKey(it)) } },
    )
    val art = rememberCompanionArtSite(
        hasSource = artRef != null || iconPath != null,
        decoded = goneCover ?: goneIcon ?: warm ?: produced,
        identity = entry.packageName to (artRef ?: iconPath),
    )
    // Receipt: `plate=visible` is the letter this tab actually draws, which only a tab with no source wears; a tab with an icon or
    // cover wired that is still loading reads `hasArt=true state=loading plate=hidden`, and one whose sources all failed reads
    // `hasArt=true state=failed plate=hidden`. The 72 dp card body is a control, not a plate.
    BottomUiArtReceipt(
        tile = "tab." + entry.packageName,
        hasArt = art.facts.hasArt,
        loaded = art.facts.loaded,
        failed = art.facts.failed,
        plateVisible = art.facts.plateVisible,
    )
    when (art.draw) {
        CompanionArtDraw.ART -> art.bitmap?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                // The one app-icon shape (Thor Bottom revamp, policy 4): the 54 dp the 72 dp card leaves inside its 9 dp inset, clipped to
                // Main's rounded square at 11/40 of its edge, for an APP'S ICON only. A game's cover keeps its own shape and its fit, unclipped
                // (art law; V8 fix round): the mask would cut the corners off a square or portrait cover. The card stays the control it was.
                modifier = Modifier
                    .size(CompanionTabIconDp.dp)
                    .then(if (art.decoded?.isCover == true) Modifier else Modifier.clip(mimirAppIconShape(CompanionTabIconDp.dp)))
                    .testTag(CompanionTabIconTag)
                    .graphicsLayer { alpha = art.reveal.value },
            )
        }
        CompanionArtDraw.FALLBACK ->
            Text(
                glyph,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = palette.accent,
                modifier = Modifier.graphicsLayer { alpha = art.reveal.value },
            )
        CompanionArtDraw.NOTHING -> Unit
    }
}

/**
 * A tab's art: a game's cover outranks the emulator's icon and falls back to it when the cover cannot be opened (both are wired
 * sources). Every source is classified OFF-main (R8a): a cover is a content reference opened through the resolver, or a local file
 * that is statted first and never opened once gone; the icon is [decodeCompanionIcon]. [companionTabArtOutcome] adds them up: a wired
 * source that did not decode is FAILED (nothing is drawn), and only when every source is gone is the tab unwired and shows its
 * fallback.
 */
private suspend fun decodeCompanionTabArt(context: Context, artRef: String?, iconPath: String?): CompanionDecodedArt =
    withContext(Dispatchers.IO) {
        val coverWired = artRef != null && mimirArtSourceWired(artRef)
        val cover = artRef?.takeIf { coverWired }?.let { ref ->
            runCatching { decodeBoundedTabCover(context, Uri.parse(ref)) }.getOrNull()
        }?.asImageBitmap()?.also { bitmap -> artRef?.let { companionAppIconCache.put(companionTabCoverKey(it), bitmap) } }
        val icon = if (cover == null) iconPath?.let { decodeCompanionIcon(it) } else null
        companionTabArtOutcome(coverWired, cover, icon)
    }

/** The cover at [uri], decoded to at most [CompanionTabCoverMaxPx] on its long edge: the stream is opened twice, for the bounds and then for the pixels. */
private fun decodeBoundedTabCover(context: Context, uri: Uri): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val pixels = BitmapFactory.Options().apply { inSampleSize = companionCoverSampleSize(bounds.outWidth, bounds.outHeight) }
    return context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, pixels) }
}

@Composable
private fun DrawerLaunchLock(message: String, modifier: Modifier = Modifier) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = modifier
            .background(palette.surfaceBase.copy(alpha = 0.58f))
            .padding(MimirSpacing.lg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            CircularProgressIndicator(
                color = palette.accent,
                strokeWidth = 2.dp,
                modifier = Modifier.size(32.dp),
            )
            Text(
                message,
                style = MimirTypeScale.Support,
                color = palette.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
