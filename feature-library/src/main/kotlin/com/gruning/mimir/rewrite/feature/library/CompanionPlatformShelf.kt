package com.gruning.mimir.rewrite.feature.library

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.graphics.SolidColor
import com.gruning.mimir.rewrite.core.ui.MimirFilledControlRingReach
import com.gruning.mimir.rewrite.core.ui.MimirFocusFlareReach
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import java.time.ZoneId
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import com.gruning.mimir.rewrite.core.ui.MimirCompactHeaderClearanceDp
import com.gruning.mimir.rewrite.core.ui.MimirPageRunes
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapTransform
import kotlin.math.roundToInt
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.produceState
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.LibraryRepository
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import kotlin.math.floor
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import com.gruning.mimir.rewrite.core.ui.MimirLiquidGlassPanel
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.mimirThemeSceneBackdrop
import com.gruning.mimir.rewrite.core.ui.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.MimirGlass
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.rememberMimirHeldGlassProgress
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirArtWarmInMemory
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import com.gruning.mimir.rewrite.core.ui.rememberMimirColdArtReveal
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiFloatTransitionReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiArtReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import com.gruning.mimir.rewrite.core.ui.MediaArtFallbackGlyph
import com.gruning.mimir.rewrite.core.ui.mimirPressResponse
import com.gruning.mimir.rewrite.core.ui.mimirReportPress
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiArtProbe
import com.gruning.mimir.rewrite.core.ui.rememberBottomUiControl
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val CompanionShelfExitTag = "MimirShelfExit"

private fun logCompanionShelfExit(message: String) {
    if (Log.isLoggable(CompanionShelfExitTag, Log.DEBUG)) Log.d(CompanionShelfExitTag, message)
}

enum class CompanionLaunchDisplayChoice {
    MAIN,
    BOTTOM,
    ASK,
}

internal fun companionLaunchDisplayChoiceLabel(choice: CompanionLaunchDisplayChoice): String = when (choice) {
    CompanionLaunchDisplayChoice.MAIN -> "Main screen"
    CompanionLaunchDisplayChoice.BOTTOM -> "Bottom screen"
    CompanionLaunchDisplayChoice.ASK -> "Ask next time"
}

internal fun companionLaunchDisplayGlyphFill(choice: CompanionLaunchDisplayChoice): Pair<Boolean, Boolean> = when (choice) {
    CompanionLaunchDisplayChoice.MAIN -> true to false
    CompanionLaunchDisplayChoice.BOTTOM -> false to true
    CompanionLaunchDisplayChoice.ASK -> false to false
}

@Composable
private fun CompanionLaunchDisplayGlyph(choice: CompanionLaunchDisplayChoice) {
    val palette = LocalMimirPalette.current
    val (upperFilled, lowerFilled) = companionLaunchDisplayGlyphFill(choice)
    Canvas(Modifier.size(CompanionLaunchDisplayGlyphSize)) {
        val left = 3.dp.toPx()
        val screenSize = Size(16.dp.toPx(), 7.5.dp.toPx())
        val cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
        val stroke = Stroke(width = 1.5.dp.toPx())
        val upperTop = 2.dp.toPx()
        val lowerTop = 12.5.dp.toPx()

        if (upperFilled) {
            drawRoundRect(
                color = palette.accentBright,
                topLeft = Offset(left, upperTop),
                size = screenSize,
                cornerRadius = cornerRadius,
            )
        }
        drawRoundRect(
            color = palette.accentBright,
            topLeft = Offset(left, upperTop),
            size = screenSize,
            cornerRadius = cornerRadius,
            style = stroke,
        )
        if (lowerFilled) {
            drawRoundRect(
                color = palette.accentBright,
                topLeft = Offset(left, lowerTop),
                size = screenSize,
                cornerRadius = cornerRadius,
            )
        }
        drawRoundRect(
            color = palette.accentBright,
            topLeft = Offset(left, lowerTop),
            size = screenSize,
            cornerRadius = cornerRadius,
            style = stroke,
        )
    }
}

/**
 * One platform's games, browsed ON SCREEN 2.
 *
 * **Policy 2026-09-01** — the Home shelf lives on screen 1 and the library lives on screen 2. A
 * console tile on the companion display opens this instead of driving the primary. `CompanionLibraryRoute`
 * (in `:app`) holds the route and records the interactivity canon this supersedes.
 *
 * **It lives in `:feature-library` for one reason: the ART LADDER.** The first version of this surface was
 * in `:app` and drew `game.artUri` raw at a hardcoded 3:4 with `ContentScale.Crop`. That uses scanned box art, which is what a raw `artUri` is for a platform whose provider
 * renders 3D boxes — cropped square into a portrait cell, so the box was both the wrong tier and the wrong
 * shape. [libraryListPreviewMedia] is the product's own three-tier ladder (hand-wired face → physical media
 * → cover) and it decides the ASPECT as well as the URI. It is `internal` to this module, which is exactly
 * why the surface belongs here: a fourth copy of that ladder in `:app` is the "three copies of one helper"
 * this codebase has already been burned by.
 *
 * **Touch only, and structurally so.** The Presentation's window carries `FLAG_NOT_FOCUSABLE`, so a D-pad
 * can never reach these tiles; registering them on the single FocusAuthority would create entries the
 * controller can select and the player cannot see selected.
 *
 * Takes plain values rather than app types so this module gains no dependency on `:app`.
 */
/** How far the shelf's ground is pulled back so the theme scene reads through it (matches the lattice stage). */
private const val CompanionShelfGroundTintAlpha = 0.35f

/** The gap above the header (the Bottom's own top inset); the page runes are the last thing in the panel, so there is no bottom inset. */
internal const val CompanionShelfTopInsetDp = 10f

@Composable
fun CompanionPlatformShelf(
    platformIdValue: String,
    label: String,
    repository: LibraryRepository,
    /** The order the player filed on THIS screen, by game id. Empty = never arranged. */
    filedOrder: List<String>,
    /** A drop was committed. The whole visible order is handed back, not a move. */
    onArranged: (List<String>) -> Unit,
    onBack: () -> Unit,
    /** PLAY was pressed on the card. The primary launches; screen 2 only asks. */
    onLaunch: (GameRow) -> Unit,
    /** The card's pending game, or null as soon as the card starts closing. */
    onCardChanged: (GameRow?) -> Unit = {},
    /** The always-visible per-game display editor beneath PLAY. Editing never launches the game. */
    onEditDisplay: (GameRow) -> Unit = {},
    /**
     * Fires once per shelf, one frame after the lazy grid has laid out its first items (or the empty
     * state has drawn); hosts hold their fade-in on it. The grid subcomposes inside measure, so
     * 'rows arrived' is one frame too early.
     */
    onContentFrame: () -> Unit = {},
    /** Display choice is supplied by the app because the preference is keyed by the opaque GameId. */
    displayChoiceFor: (GameRow) -> CompanionLaunchDisplayChoice = { CompanionLaunchDisplayChoice.ASK },
    /**
     * False while this shelf is the OUTGOING remnant kept mounted only for its own fade (S2-07) —
     * see `MimirCompanionPresentation`'s `shelfLeaving`. Revokes Back/Open/Play/arrange touch and
     * accessibility authority without touching `shelfEnter`'s alpha/translation, which stays owned
     * by the caller.
     */
    active: Boolean,
    /**
     * R6-F-11: a native EdgeBack close for the card, once for each new non-null value — the close-side
     * counterpart to [onCardChanged]. Runs the exact same state change the card's own `onDismiss` (tap
     * outside, below) does — `pending = null` — so a native swipe and a tap close identically; this is
     * not a second close path.
     */
    closeCardRequest: Long? = null,
    /**
     * The player's density step (slice L4): one global setting for every platform, which the app persists beside the filed order. The stepper in the header
     * changes the step on screen at once and reports it through [onDensityChange]; a new value here (a restored one) re-solves the grid the same way.
     */
    density: CompanionShelfDensity = CompanionShelfDensity.Default,
    onDensityChange: (CompanionShelfDensity) -> Unit = {},
    /**
     * The launch card's look (slices L5 and L6): one global setting for every game, which the app persists beside the density. The card's "Look" switch
     * changes it on screen at once and reports it through [onLookChange].
     */
    look: CompanionLaunchLook = CompanionLaunchLook.Default,
    onLookChange: (CompanionLaunchLook) -> Unit = {},
    /** The Journal look's totals (session time, achievements), which live in the app. Called only while the Journal is composed. */
    journalFactsFor: @Composable (GameRow) -> CompanionLaunchCardFacts = { CompanionLaunchCardFacts.None },
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val platformId = remember(platformIdValue) { PlatformId(platformIdValue) }
    // The persisted measured cover proportions, for a platform the curated map lacks: the art seat reads them through [platformCoverAspect], and the Bottom may open
    // a shelf before the Main gamelist has ever hydrated the store in this process. Idempotent, off the main thread.
    val measuredAspectContext = LocalContext.current
    LaunchedEffect(Unit) { MeasuredCoverAspects.ensureLoaded(measuredAspectContext) }
    // The step is shown from local state so a tap re-solves the grid at once, and follows the app's persisted value when that changes.
    var shownDensity by remember { mutableStateOf(density) }
    LaunchedEffect(density) { shownDensity = density }
    // The look likewise: the card changes face on the tap, and follows the app's persisted value when that changes.
    var shownLook by remember { mutableStateOf(look) }
    LaunchedEffect(look) { shownLook = look }
    // The card is a surface WITHIN this shelf rather than a raise on the primary. Screen 1's own launch
    // card refuses to open unless its originating row currently holds focus
    // (`HomeLaunchCardCoordinator.open` → `homeLaunchCardOriginIsCurrent`), and a game tapped on a
    // FLAG_NOT_FOCUSABLE window never satisfies that — by design, since that guard exists to stop a card
    // opening for something the player is not on. So screen 2 carries its own.
    var pending by remember(platformIdValue) { mutableStateOf<GameRow?>(null) }
    // The card animates OUT as well as in, so the game
    // it was showing has to survive `pending` going null or the panel would blink empty mid-dissolve.
    var dissolving by remember(platformIdValue) { mutableStateOf<GameRow?>(null) }
    LaunchedEffect(pending) { onCardChanged(pending) }
    val card = pending ?: dissolving
    // RC-05 (audit 2026-10-14, B-43/B-47): the scene's blur and the card's own alpha are two clocks. The blur runs through core-ui's cross-fade
    // plan (one fixed-radius blur, its progress read in draw: no recomposition of the shelf per frame, no radius sweep), opens on OverlayMs from
    // the tap, and on close holds through the card's dissolve before it clears (rememberMimirHeldGlassProgress). The card itself still
    // rises and settles on `glass`, OverlayMs both ways, and that ramp stays the receipts' transition.
    val cardGlass = rememberMimirHeldGlassProgress(visible = pending != null)
    val cardReducedMotion = mimirReducedMotion()
    val glass by animateFloatAsState(
        targetValue = if (pending != null) 1f else 0f,
        // The card arrives on the swap tier (Q3, siblings matched with the picker, the prompt and the chooser): the 130 ms lead
        // while the glass softens the scene, then OverlayMs; it leaves on OverlayMs. Reduced motion snaps both.
        animationSpec = companionLaunchCardSpec(up = pending != null, reducedMotion = cardReducedMotion),
        label = "companion-launch-card-glass",
        finishedListener = { settled -> if (settled == 0f) dissolving = null },
    )
    val cardShowing by remember { derivedStateOf { glass > 0.001f } }
    // Receipts: the launch card is a surface within this shelf; its glass ramp is the transition.
    BottomUiSurfaceReceipt(
        surface = BottomSurface.LAUNCH_CARD,
        visible = pending != null,
        transition = if (pending != null) "launch-card.enter" else "launch-card.exit",
    )
    BottomUiFloatTransitionReceipt(
        name = if (pending != null) "launch-card.enter" else "launch-card.exit",
        target = if (pending != null) 1f else 0f,
        value = { glass },
    )
    val shelfControl = rememberBottomUiControl(BottomSurface.PLATFORM_SHELF)
    val cardControl = rememberBottomUiControl(BottomSurface.LAUNCH_CARD)
    // Revoke a still-open card the instant the route deactivates, not just on the next PLAY/dismiss —
    // otherwise a quick reentry recomposes this same shelf `active` again with the OLD `pending` still
    // set, and the dissolving card would revive as if it were still current.
    LaunchedEffect(active) {
        if (!active) pending = null
    }
    // R6-F-11: `pending`/`dissolving` are keyed by `platformIdValue`, so switching platforms (or a full
    // unmount when `shelfShown` in the Presentation goes null and a later reopen recomposes this call
    // site fresh) resets them — but `closeCardRequest` is never reset by the caller. Without this floor,
    // a stale close left over from a PREVIOUS platform/mount would read as new on this one's own first
    // composition and close a card that just opened. Same discipline R6-P2-08 applied to the Tome.
    val initialCloseCardRequest = remember(platformIdValue) { closeCardRequest }
    LaunchedEffect(closeCardRequest) {
        if (closeCardRequest == null || closeCardRequest == initialCloseCardRequest) return@LaunchedEffect
        pending = null
    }
    val currentActive by rememberUpdatedState(active)
    val launchCardOpen by rememberUpdatedState(pending != null)
    val currentOnBack by rememberUpdatedState(onBack)
    val backSwipeDistancePx = with(LocalDensity.current) { CompanionShelfBackSwipeDistance.toPx() }
    val games: State<List<GameRow>?> = produceState<List<GameRow>?>(
        initialValue = null,
        key1 = platformId,
        key2 = repository,
    ) {
        // `catch` rather than an uncaught collect: this composition lives in a Presentation window whose
        // crash takes screen 2 down entirely and leaves the player a black panel with no way back.
        repository.observeGames(platformId)
            .catch { emit(emptyList()) }
            .collect { rows -> value = rows }
    }
    LaunchedEffect(games.value?.size, active) {
        logCompanionShelfExit(
            "shelf platform=$platformIdValue rows=${games.value?.size ?: -1} active=$active",
        )
    }
    val contentDrawn = remember { mutableStateOf(false) }
    val onContentFrameState = rememberUpdatedState(onContentFrame)
    LaunchedEffect(contentDrawn.value) {
        if (!contentDrawn.value) return@LaunchedEffect
        withFrameNanos { }
        onContentFrameState.value()
    }

    // The density menu is open while the chip's popover shows. It closes on a pick, on an outside tap, and the instant the route deactivates.
    // Keyed on the platform: a replacement shelf starts closed. It also closes when the supplied density changes under it (a restored or persisted value), when
    // the shelf deactivates, and when a launch card opens over it.
    var densityMenuOpen by remember(platformIdValue) { mutableStateOf(false) }
    var densityChip by remember(platformIdValue) { mutableStateOf<Rect?>(null) }
    LaunchedEffect(active, pending != null) {
        if (!active || pending != null) densityMenuOpen = false
    }
    LaunchedEffect(density) { densityMenuOpen = false }
    val currentDensityMenuOpen by rememberUpdatedState(densityMenuOpen)

    // A box over the panel so the popover (which hangs below the header's band) is hit where it draws; the panel itself is the column.
    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // D14 (2026-09-11): the softened theme scene under the shelf, as under the lattice -- the ground
                // used to be an opaque surfaceBase, which stopped the themed secondary-screen background at the lattice. Same tint alpha as the lattice stage so the two read as one surface.
                .mimirThemeSceneBackdrop(palette, quietSceneLight = true, shineStars = false)
                .background(palette.surfaceBase.copy(alpha = palette.surfaceBase.alpha * CompanionShelfGroundTintAlpha))
                // The SCENE takes the blur, never the panel — blurring the panel would blur its own text.
                // One live blur under an open picker: the shelf under the card is a SNAPSHOT while the glass is at its
                // peak (core-ui's one freeze rule), so nothing behind the card re-records or re-blurs for as long as it is up.
                .mimirGlassBackdropProgress({ MimirGlass.backdropBlurRadius }, { cardGlass() }, snapshot = { true })
                // 16 dp a side (the page is 506 dp wide on the Thor's 538), 10 dp above the header, and nothing below: the page runes close the panel.
                .padding(start = MimirSpacing.lg, end = MimirSpacing.lg, top = CompanionShelfTopInsetDp.dp)
                // Observe in Final so the grid gets first refusal. A vertical list scroll or a long-press
                // reorder consumes its stream; this detector only claims a short, horizontal back swipe.
                .pointerInput(backSwipeDistancePx) {
                    detectCompanionShelfBackSwipe(
                        canStart = { currentActive && !launchCardOpen && !currentDensityMenuOpen },
                        triggerDistancePx = backSwipeDistancePx,
                        onBack = {
                            if (currentActive && !launchCardOpen) {
                                shelfControl("platform-shelf.back_swipe", null)
                                currentOnBack()
                            }
                        },
                    )
                },
        ) {
            CompanionShelfHeader(
                platformIdValue = platformIdValue,
                title = label,
                gameCount = games.value?.size,
                density = shownDensity,
                active = active,
                onBack = {
                    // Belt and suspenders alongside the pill's own `enabled`: a tap already in flight when `active` flips false must still no-op here.
                    if (currentActive) {
                        shelfControl("platform-shelf.back", null)
                        onBack()
                    }
                },
                menuOpen = densityMenuOpen,
                onDensityMenu = {
                    if (currentActive) {
                        shelfControl("platform-shelf.density_menu", null)
                        densityMenuOpen = !densityMenuOpen
                    }
                },
                onDensityChipBounds = { densityChip = it },
            )

            Spacer(Modifier.height(MimirCompactHeaderClearanceDp.dp))

            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val rows = games.value) {
                    // Null is "not read yet"; empty is "read, and there are none". Different sentences, and the
                    // same distinction `CompanionQuickLaunchCatalog.hasLoaded` exists to preserve one screen up.
                    null -> CompanionShelfLine("Reading the shelf…")
                    else -> if (rows.isEmpty()) {
                        Box(
                            Modifier.drawWithContent {
                                drawContent()
                                if (!contentDrawn.value) contentDrawn.value = true
                            },
                        ) {
                            CompanionShelfLine("No games on this shelf.")
                        }
                    } else {
                        // Keyed by platform: the page the player was on, and a carry, belong to the shelf they were looking at.
                        key(platformIdValue) {
                            CompanionArrangeableGrid(
                                rows = rows,
                                filedOrder = filedOrder,
                                active = active,
                                step = shownDensity,
                                onArranged = { ids ->
                                    shelfControl("platform-shelf.arrange", null)
                                    onArranged(ids)
                                },
                                onOpen = { game ->
                                    shelfControl("platform-shelf.tile", BottomUiReceiptFormat.opaque("game", game.gameId.value))
                                    dissolving = game
                                    pending = game
                                },
                                onFirstLayout = { if (!contentDrawn.value) contentDrawn.value = true },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
        CompanionShelfDensityMenu(
            density = shownDensity,
            active = active,
            open = densityMenuOpen,
            anchor = densityChip,
            onPick = { next ->
                densityMenuOpen = false
                if (currentActive && next != shownDensity) {
                    shelfControl(if (next.ordinal < shownDensity.ordinal) "shelf.density_larger" else "shelf.density_denser", null)
                    shownDensity = next
                    onDensityChange(next)
                }
            },
            onDismiss = { densityMenuOpen = false },
        )
    }

    if (card != null && cardShowing) {
        CompanionLaunchCard(
            game = card,
            progress = glass,
            // Both halves: dissolving (`pending == null`) is never PLAY-able even while `active`, and a
            // route that deactivated mid-dissolve is never PLAY-able even though `pending` is still set
            // for one more frame above.
            active = active && pending != null,
            onDismiss = {
                cardControl("launch-card.dismiss", null)
                pending = null
            },
            onEditDisplay = {
                if (active) {
                    cardControl("launch-card.edit_display", pending?.gameId?.value?.let { BottomUiReceiptFormat.opaque("game", it) })
                    pending?.let(onEditDisplay)
                }
            },
            displayChoice = displayChoiceFor(card),
            onPlay = {
                // `pending` is re-read synchronously at the tap, not a stale closure: it goes null on the
                // first PLAY. `active` is ordinary composition state here, not a synchronous read.
                val live = pending
                if (active && live != null) {
                    cardControl("launch-card.play", BottomUiReceiptFormat.opaque("game", live.gameId.value))
                    pending = null
                    onLaunch(live)
                }
            },
            look = shownLook,
            onLookChange = { next ->
                shownLook = next
                onLookChange(next)
            },
            platformLabel = label,
            journalFactsFor = journalFactsFor,
        )
    }
}

/**
 * Screen 2's launch card (slices L5 and L6): one glass card with three looks, chosen ON the card with a global "Look" switch (policy 5; the bodies are
 * in CompanionLaunchCardLooks.kt). It is also the card the Activity page opens, through [CompanionLaunchCardLayer].
 *
 *   Cinematic  the hero bare under a palette scrim, the 430 x 215 logo stage, title and meta (the default)
 *   Journal    the cover beside the title, the facts grid, the synopsis when there is a real one
 *   Logo Only  the stage on the glass
 *
 * Under every look the footer is the same: [Opens on], the Look switch, and PLAY. Screen 1's card carries disc pickers, Open-With, descriptions and a
 * launch-state line because it is the surface a controller lives on; this one is a touch confirmation on a panel you are holding, and every one of
 * those extra affordances would need a focus model the window cannot have.
 *
 * **Geometry.** The card is [CompanionLaunchCardWidth] (470 dp) wide and as tall as its look needs, up to the host's height less
 * [CompanionLaunchCardDismissMarginDp] above and below it, so a tap outside it (the Bottom's only Back) always has a usable strip on every side. What is
 * flexible (the stage, the cover) gives way before the cap does. The glass panel is given NO padding of its own: each look places its content, because the
 * hero runs edge to edge behind the whole face and the shared stage needs 430 of the 470.
 *
 * **The footer is outside the look swap.** A look change crossfades the body on the swap tier (snap under reduced motion), and the swap's fade is a
 * layer the size of the body: PLAY's ring reaches [MimirFilledControlRingReach] (and the flare [MimirFocusFlareReach]) past the pill, so the pill is not in
 * that layer, and the footer stands the ring's room in from every edge of the panel, whose own clip and (while it arrives) fade layer are the next bounds
 * a ring could meet.
 */
@Composable
internal fun CompanionLaunchCard(
    game: GameRow,
    progress: Float,
    /** See [CompanionPlatformShelf]'s `active`. Gates PLAY only — the scrim keeps swallowing taps
     * (and dismiss keeps working) regardless, so the closing card never leaks a touch to the grid. */
    active: Boolean,
    onDismiss: () -> Unit,
    onEditDisplay: () -> Unit,
    displayChoice: CompanionLaunchDisplayChoice,
    onPlay: () -> Unit,
    /** The look on screen. The host owns it (it persists it), so a tap on the switch asks through [onLookChange] and the host passes the new one back. */
    look: CompanionLaunchLook = CompanionLaunchLook.Default,
    onLookChange: (CompanionLaunchLook) -> Unit = {},
    /** The game's platform, for Cinematic's meta line and the Journal's eyebrow. Absent when the host does not know it; then neither is drawn. */
    platformLabel: String? = null,
    /** The Journal's totals, which live in the app. Called only while the Journal is composed. */
    journalFactsFor: @Composable (GameRow) -> CompanionLaunchCardFacts = { CompanionLaunchCardFacts.None },
    /** The clock "Last played" counts from; the real one unless a test pins it. */
    nowMillis: Long? = null,
) {
    val reduced = remember { bottomUiReducedMotion() }
    val cardControl = rememberBottomUiControl(BottomSurface.LAUNCH_CARD)
    // The house scrim, at the house depth, faded on the same ramp as the blur behind it so the two read as
    // one material arriving rather than a veil and a defocus happening separately.
    val scrim = mimirGlassScrimColor()
    val now = remember(game.gameId, nowMillis) { nowMillis ?: System.currentTimeMillis() }
    val zone = remember { ZoneId.systemDefault() }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // A scrim that also swallows the tap: dismissing by touching outside is the only Back this
            // window has, since it cannot take key input.
            .background(scrim.copy(alpha = scrim.alpha * progress))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val cap = (maxHeight - CompanionLaunchCardDismissMarginDp.dp * 2).coerceAtLeast(CompanionLaunchCardMinHeightDp.dp)
        val room = maxWidth - CompanionLaunchCardSideMarginDp.dp * 2
        val cardWidth = if (room < CompanionLaunchCardWidth) room else CompanionLaunchCardWidth
        // **The house glass**, not a hand-rolled panel —  `MimirLiquidGlassPanel` is the same material the Home launch
        // card, Utility and the Tome overlays wear, so screen 2's card is the product's card rather than a
        // lookalike. It rises and settles on the same ramp as the blur.
        MimirLiquidGlassPanel(
            modifier = Modifier
                .graphicsLayer {
                    alpha = progress
                    val rise = 0.94f + 0.06f * progress
                    scaleX = rise
                    scaleY = rise
                }
                .testTag(CompanionLaunchCardTag)
                .width(cardWidth)
                .heightIn(max = cap)
                // Swallow taps on the card itself so a press near PLAY cannot dismiss it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            contentPadding = 0.dp,
            verticalArrangement = Arrangement.Top,
        ) {
            Box(Modifier.fillMaxWidth()) {
                CompanionLaunchHero(visible = look == CompanionLaunchLook.CINEMATIC, game = game)
                Column(Modifier.fillMaxWidth()) {
                    // The swap tier (design section 4): one body replaces another in place, on the same clocks as the density swap. The Bottom is touch-only, so
                    // the page gate never holds the process-wide pad gate (holdPad = false).
                    AnimatedContent(
                        targetState = look,
                        modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
                        transitionSpec = { mimirSwapTransform(reduced) },
                        label = "companion-launch-card-look-swap",
                    ) { shown ->
                        val pageInput = MimirSwapPageGate(mimirSwapFadeAlpha(reduced), holdPad = false)
                        Box(Modifier.fillMaxWidth().then(pageInput)) {
                            when (shown) {
                                CompanionLaunchLook.CINEMATIC -> CompanionLaunchStageBody(game, platformLabel, cinematic = true)
                                CompanionLaunchLook.LOGO_ONLY -> CompanionLaunchStageBody(game, platformLabel, cinematic = false)
                                CompanionLaunchLook.JOURNAL -> CompanionLaunchJournalBody(game, platformLabel, journalFactsFor(game), now, zone)
                            }
                        }
                    }
                    CompanionLaunchFooter(
                        active = active,
                        displayChoice = displayChoice,
                        onEditDisplay = onEditDisplay,
                        look = look,
                        onLook = {
                            val next = look.next
                            cardControl("card.look_${next.storageKey}", null)
                            onLookChange(next)
                        },
                        onPlay = onPlay,
                    )
                }
            }
        }
    }
}

/**
 * [Opens on] [Look] [PLAY], the same under every look. All three are at least 48 dp tall; the two taps new to the card are the shelf's own tap detectors
 * (touch only, R9a), never `clickable` beside a focus modifier.
 *
 * PLAY is the card's primary: the etched chip struck as a PRIMARY (update: [mimirEtchedChipSurface] with `primary`, the same dark plate as an unselected chip, its rim and
 * its word in the accent, no fill). It wears no ring: the card is touch, there is no pad cursor to move one, and a ring that never moves reads as a fill (only the
 * focused element wears the ring). The row keeps the room it stood in for that ring ([CompanionLaunchFooterRoomDp], and the gap before PLAY), so nothing on the card moved.
 */
@Composable
private fun CompanionLaunchFooter(
    active: Boolean,
    displayChoice: CompanionLaunchDisplayChoice,
    onEditDisplay: () -> Unit,
    look: CompanionLaunchLook,
    onLook: () -> Unit,
    onPlay: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(CompanionLaunchCardFooterTag)
            .padding(horizontal = CompanionLaunchFooterSideDp.dp, vertical = CompanionLaunchFooterRoomDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val opensOnSource = remember { MutableInteractionSource() }
        Column(
            modifier = Modifier
                .weight(1f)
                .testTag(CompanionLaunchCardOpensOnTag)
                .clickable(
                    interactionSource = opensOnSource,
                    indication = null,
                    enabled = active,
                    onClick = onEditDisplay,
                )
                .mimirPressResponse(opensOnSource)
                .clip(RoundedCornerShape(8.dp))
                .heightIn(min = CompanionShelfTargetDp.dp)
                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Opens on",
                style = MimirTypeScale.Support,
                color = palette.textSecondary,
                textAlign = TextAlign.Start,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompanionLaunchDisplayGlyph(displayChoice)
                Text(
                    text = companionLaunchDisplayChoiceLabel(displayChoice),
                    style = MimirTypeScale.Row,
                    color = palette.accentBright,
                )
            }
        }
        Spacer(Modifier.width(MimirSpacing.sm))
        // The look switch: one 48 dp chip that says the look on screen and moves to the next on a tap.
        val lookSource = remember { MutableInteractionSource() }
        Column(
            modifier = Modifier
                .testTag(CompanionLaunchCardLookTag)
                .companionShelfTap(
                    enabled = active,
                    label = "Look: ${look.label}. Switch to ${look.next.label}",
                    interactionSource = lookSource,
                    onTap = onLook,
                )
                .mimirPressResponse(lookSource)
                .clip(RoundedCornerShape(8.dp))
                .heightIn(min = CompanionShelfTargetDp.dp)
                .widthIn(min = CompanionShelfTargetDp.dp)
                .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Look",
                style = MimirTypeScale.Support,
                color = palette.textSecondary,
                maxLines = 1,
            )
            Text(
                text = look.label,
                style = MimirTypeScale.Row,
                color = palette.accentBright,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(MimirFilledControlRingReach))
        val playSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .testTag(CompanionLaunchCardPlayTag)
                // The tap, then the press response, then the surface: the chip draws in whole while its hit box stays whole (B5, R4).
                .companionShelfTap(enabled = active, label = "Play", interactionSource = playSource, onTap = onPlay)
                .mimirPressResponse(playSource)
                // Update : PLAY is the PRIMARY etched chip, not an accent-filled pill: the same dark plate as an unselected chip, the rim and the word in
                // the accent, no fill and no ring.
                .mimirEtchedChipSurface(palette, primary = true)
                // At least 48 dp tall: the card is opened from the Activity page too, whose targets are all 48 dp or more.
                .heightIn(min = CompanionShelfTargetDp.dp)
                .widthIn(min = CompanionShelfTargetDp.dp)
                .padding(horizontal = MimirSpacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "PLAY",
                style = MimirTypeScale.Row,
                color = palette.etchedChipInk(primary = true),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/** The ring's reach, the flare's reach and a dp: what the footer stands in from the panel's bottom (and above it, clear of the words). */
internal const val CompanionLaunchFooterRoomDp = 24f

/** The footer's side inset: PLAY's right edge is this far from the panel's, so the ring and flare clear the panel's corner. */
internal const val CompanionLaunchFooterSideDp = 24f

/** The card never gets shorter than this, whatever the host measures. */
internal const val CompanionLaunchCardMinHeightDp = 240f

private val CompanionLaunchDisplayGlyphSize = DpSize(22.dp, 22.dp)

/**
 * The visible order: the player's filed ids first, then anything they have never touched.
 *
 * Pure and `internal` so the rule is a test rather than a claim. Two properties matter and neither is
 * obvious from the call site:
 *
 * - **A filed id that is no longer in the library is skipped, not dropped from the file.** The store never
 *   prunes (see `CompanionShelfGameOrderStore`), so a game whose source is unmounted keeps its slot and
 *   returns to it rather than to the end.
 * - **A game the player has never arranged appears in PRODUCER order, at the end.** New games arriving from
 *   a scan must not be interleaved into a hand-filed shelf at positions nobody chose.
 */
internal fun companionShelfArrangement(rows: List<GameRow>, filedOrder: List<String>): List<GameRow> {
    if (filedOrder.isEmpty()) return rows
    val byId = rows.associateBy { it.gameId.value }
    val placed = LinkedHashSet<String>()
    val arranged = ArrayList<GameRow>(rows.size)
    filedOrder.forEach { id ->
        val row = byId[id] ?: return@forEach
        if (placed.add(id)) arranged.add(row)
    }
    rows.forEach { row -> if (placed.add(row.gameId.value)) arranged.add(row) }
    return arranged
}

/**
 * Move [from] to [to], the one mutation a drag can express.
 *
 * Separated from the gesture because a retarget happens on almost every pointer frame, and a list move
 * that is subtly wrong in one direction — the classic off-by-one when dragging forwards versus backwards —
 * is invisible in review and obvious on glass.
 */
internal fun <T> List<T>.companionArrangeMove(from: Int, to: Int): List<T> {
    if (from == to || from !in indices || to !in indices) return this
    val moved = toMutableList()
    moved.add(to, moved.removeAt(from))
    return moved
}

/**
 * The shelf's SEAT GEOMETRY — one uniform page of whole rows, solved once, invertible both ways.
 *
 * The first build found the drop target by
 * hit-testing each tile's `boundsInWindow`, captured through `onGloballyPositioned` — rects that are
 * republished asynchronously and that MOVE as the list reorders under the finger. So the same pointer
 * position could answer differently depending on which layout pass had landed, and the answer changed
 * while the finger stood still. There was no function from a position to a seat; there was a race.
 *
 * A seat is arithmetic now. `index -> (row, column) -> origin` and `position -> (row, column) -> index`
 * are inverses of each other and neither reads a measured rect, so the mapping is idempotent: asking
 * twice with the same position gives the same seat no matter what has moved.
 *
 * That requires UNIFORM cells, which is why the tile no longer sizes itself to its own art aspect — a
 * grid whose row heights depend on which covers happen to be in it cannot have a closed-form inverse.
 * The art is FIT inside the uniform cell instead, which keeps the unframed-art policy intact: nothing is
 * cropped and nothing is framed, the cell is simply the same size for every game.
 *
 * **Pages (slice L4).** The shelf pages instead of scrolling: a page is [columns] x [rows] seats, the grid
 * is solved to fill one page exactly, and every page has the same geometry. A seat's origin is therefore
 * PAGE-LOCAL (`index % perPage`), and a position resolves to a seat on the page it is asked about
 * ([seatAt]'s `page`), clamped to that page, so a carry can never cross a page boundary by arithmetic.
 */
internal data class CompanionShelfGrid(
    val columns: Int,
    val rows: Int,
    val cellWidth: Float,
    val cellHeight: Float,
    val gap: Float,
    val count: Int,
) {
    /** Seats on one full page. */
    val perPage: Int get() = columns * rows

    /** Pages [count] games make; always at least one. */
    val pageCount: Int get() = companionShelfPageCount(count, perPage)

    /** The page's height: the rows and the gaps between them (what the solver was given). */
    val pageHeight: Float get() = rows * cellHeight + (rows - 1) * gap

    /** Top-left of [index]'s seat, in its PAGE's space (the page's origin is (0, 0)); a game's page is [companionShelfPageOf]. */
    fun originOf(index: Int): Offset {
        val local = index.coerceAtLeast(0) % perPage
        val row = local / columns
        val column = local % columns
        return Offset(column * (cellWidth + gap), row * (cellHeight + gap))
    }

    /** Centre of [index]'s seat. The drag translates against this, never against a measured rect. */
    fun centreOf(index: Int): Offset =
        originOf(index) + Offset(cellWidth / 2f, cellHeight / 2f)

    /**
     * The seat a point falls in on [page], clamped into the games that page holds.
     *
     * Clamped rather than nullable on purpose: a finger dragged past the last tile, into the gutter, or
     * above the first row still has an unambiguous intent — the nearest end of THIS page — and returning
     * null there would freeze the carry at whatever seat it last resolved.
     */
    fun seatAt(position: Offset, page: Int = 0): Int {
        if (count <= 0) return 0
        val column = floor(position.x / (cellWidth + gap)).toInt().coerceIn(0, columns - 1)
        val row = floor(position.y / (cellHeight + gap)).toInt().coerceIn(0, rows - 1)
        val start = page.coerceIn(0, pageCount - 1) * perPage
        val end = minOf(count, start + perPage) - 1
        return (start + row * columns + column).coerceIn(start, end)
    }
}

/**
 * Solve one page from the measured width and the page's height: [columns] x [rows] seats separated by [gap], filling [availableWidth] x [pageHeight]
 * exactly. The count of seats is the density step's, never `GridCells.Adaptive`'s — the mapping above cannot be written against a column count only the
 * layout knows, and the page height is whatever the screen leaves, so a different panel re-solves the cells rather than the steps.
 */
internal fun solveCompanionShelfGrid(
    availableWidth: Float,
    pageHeight: Float,
    columns: Int,
    rows: Int,
    gap: Float,
    count: Int,
): CompanionShelfGrid {
    val c = columns.coerceAtLeast(1)
    val r = rows.coerceAtLeast(1)
    return CompanionShelfGrid(
        columns = c,
        rows = r,
        cellWidth = ((availableWidth - gap * (c - 1)) / c).coerceAtLeast(1f),
        cellHeight = ((pageHeight - gap * (r - 1)) / r).coerceAtLeast(1f),
        gap = gap,
        count = count,
    )
}

private val CompanionShelfBackSwipeDistance = 48.dp
private const val CompanionShelfBackSwipeHoldGuardMillis = 180L

/**
 * The back gesture owns only a quick, leftward horizontal stroke. The shelf's list keeps vertical scroll,
 * and its long-press detector keeps horizontal arrangement, because both are allowed to consume their
 * stream before this parent observes it at [PointerEventPass.Final].
 */
internal fun companionShelfBackSwipeAccepted(
    deltaX: Float,
    deltaY: Float,
    elapsedMillis: Long,
    touchSlopPx: Float,
    triggerDistancePx: Float,
    longPressMillis: Long = CompanionShelfBackSwipeHoldGuardMillis,
): Boolean {
    val requiredDistance = maxOf(touchSlopPx, triggerDistancePx)
    return elapsedMillis in 0 until longPressMillis &&
        deltaX <= -requiredDistance &&
        abs(deltaX) > abs(deltaY)
}

/**
 * R6-P1-10: the mirror's own edge-back swipe — same shape as [companionShelfBackSwipeAccepted] (a quick,
 * mostly-horizontal, leftward stroke), plus one more gate [companionShelfBackSwipeAccepted] never needed:
 * the DOWN must land within [edgeDistancePx] of the right edge. That band is what the native CP03 catch
 * strip used to own outright over a foreign host (32dp) — this predicate is called
 * from `MimirCompanionPresentation`'s root Box (`:app`), which cannot reuse an `internal` declaration from
 * this module, so it carries no module-private visibility.
 *
 * Not exported as public API for its own sake — kept here, beside its sibling, so both read from one place.
 */
fun companionMirrorEdgeBackSwipeAccepted(
    downX: Float,
    widthPx: Float,
    edgeDistancePx: Float,
    triggerDistancePx: Float,
    deltaX: Float,
    deltaY: Float,
    elapsedMillis: Long,
    touchSlopPx: Float,
    longPressMillis: Long = CompanionShelfBackSwipeHoldGuardMillis,
): Boolean {
    val withinEdgeBand = downX >= widthPx - edgeDistancePx
    val requiredDistance = maxOf(touchSlopPx, triggerDistancePx)
    return withinEdgeBand &&
        elapsedMillis in 0 until longPressMillis &&
        deltaX <= -requiredDistance &&
        abs(deltaX) > abs(deltaY)
}

private suspend fun PointerInputScope.detectCompanionShelfBackSwipe(
    canStart: () -> Boolean,
    triggerDistancePx: Float,
    onBack: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!canStart()) return@awaitEachGesture

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id }
                ?: return@awaitEachGesture
            // A grid scroll, tile drag, or another pointer owner gets the stream. This detector only
            // consumes the one gesture it accepts, so ordinary vertical scrolling remains untouched.
            if (event.changes.any { it.isConsumed }) return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            if (companionShelfBackSwipeAccepted(
                    deltaX = change.position.x - down.position.x,
                    deltaY = change.position.y - down.position.y,
                    elapsedMillis = change.uptimeMillis - down.uptimeMillis,
                    touchSlopPx = viewConfiguration.touchSlop,
                    triggerDistancePx = triggerDistancePx,
                    longPressMillis = viewConfiguration.longPressTimeoutMillis,
                )
            ) {
                change.consume()
                onBack()
                return@awaitEachGesture
            }
            // Once a long-press reorder could own the stream, stand down and let its child detector run.
            if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                return@awaitEachGesture
            }
        }
    }
}

/**
 * The arrange-and-drop shelf — policy 2026-09-01, bringing the tome's system to this shelf — in pages (slice L4).
 *
 * **The tome's SEMANTICS on the companion's own gesture.** `HomeTomeArrangeIntent` is Lift / Retarget /
 * Drop / Cancel over a transaction, and the part worth carrying is not the type but the guarantee: a
 * drag that never lands restores the order EXACTLY, because the pre-lift list is snapshotted at lift and
 * is the only thing a cancel consults. The gesture is touch because this window is `FLAG_NOT_FOCUSABLE`
 * and the tome's controller cursor cannot exist here.
 *
 * **Where the tile IS, is computed and not measured** — see [CompanionShelfGrid]. The carry tracks one
 * vector (the finger, in PAGE space); the seat under it is that vector through a pure function; and
 * the lifted tile's translation is the difference between that vector and its current seat's centre. No
 * accumulated offset, so a retarget cannot drift, and nothing depends on a layout pass having landed.
 *
 * **Pages.** The shelf is a vertical pager of whole-row pages (the step's `columns x rows`), the page runes below it say where you are, and a carry is
 * confined to the page it was lifted on: the pager does not scroll while a tile is held, and a seat resolves only within its page. A step change
 * re-solves the grid on the swap tier (a fade and 8 dp of travel, snapped under reduced motion); the games are the same keyed rows, and the player lands on the
 * page that still holds the seat they were on.
 */
@Composable
private fun CompanionArrangeableGrid(
    rows: List<GameRow>,
    filedOrder: List<String>,
    active: Boolean,
    step: CompanionShelfDensity,
    onArranged: (List<String>) -> Unit,
    onOpen: (GameRow) -> Unit,
    onFirstLayout: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    DisposableEffect(Unit) {
        logCompanionShelfExit("grid mounted rows=${rows.size}")
        onDispose { logCompanionShelfExit("grid disposed rows=${rows.size}") }
    }
    val arranged = remember(rows, filedOrder) { companionShelfArrangement(rows, filedOrder) }
    // These holders stay composable-lifetime because retained pointerInput coroutines capture the
    // holder instances; keying them on `arranged` leaves later gestures writing to dead holders.
    var working by remember { mutableStateOf(arranged) }
    var rollback by remember { mutableStateOf<List<GameRow>?>(null) }
    var liftedId by remember { mutableStateOf<String?>(null) }
    // The finger, in PAGE space. One vector; everything else is derived from it.
    var finger by remember { mutableStateOf(Offset.Zero) }
    val currentActive by rememberUpdatedState(active)
    // Composition-updated, not a synchronous route read: these follow whatever `onArranged`/`onOpen`
    // this composable was last recomposed with, so the retained `pointerInput` coroutines below (keyed
    // on `id`/`grid`/`active`, not on the lambdas) call the current callback rather than the one that
    // was in scope when the gesture started.
    val currentOnArranged by rememberUpdatedState(onArranged)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val onFirstLayoutState = rememberUpdatedState(onFirstLayout)
    // The first seat of the page the player was last on: a step re-pages the shelf, and this is what keeps their place across it.
    var anchor by remember { mutableIntStateOf(0) }
    // The page the runes show: the page of the step on screen.
    var shownPage by remember { mutableIntStateOf(0) }
    val reduced = remember { bottomUiReducedMotion() }

    LaunchedEffect(arranged) {
        if (liftedId == null) {
            working = arranged
            rollback = null
            finger = Offset.Zero
        }
    }
    LaunchedEffect(arranged.size) {
        logCompanionShelfExit("grid arranged size=${arranged.size}")
    }

    // Deactivation (route left) cancels the tile's own drag coroutine below, keyed on `active`. A
    // key-triggered `pointerInput` restart cancels that coroutine, and cancellation does not reliably
    // reach `onDragCancel` — so a lift already in progress could otherwise strand the grid mid-carry.
    // Roll the preview back by hand here, and never call `onArranged` for it: this is a local visual
    // undo, not a filed order.
    LaunchedEffect(active) {
        if (!active) {
            rollback?.let { working = it }
            rollback = null
            liftedId = null
        }
    }
    // The same local undo when the step changes under a held tile (a second finger on the stepper): the grid the carry was measured against is gone.
    LaunchedEffect(step) {
        rollback?.let { working = it }
        rollback = null
        liftedId = null
    }

    Column(modifier) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val metrics = LocalDensity.current
            val gapPx = with(metrics) { MimirSpacing.md.toPx() }
            val widthPx = with(metrics) { maxWidth.toPx() }
            val heightPx = with(metrics) { maxHeight.toPx() }
            val swapPx = with(metrics) { MimirMotion.SwapTravelDp.dp.roundToPx() }
            val labelSpec = rememberCompanionShelfLabelSpec()
            // The swap tier (design section 4): one grid replaces another in place, items keyed by game id, no per-tile layout animation. The Bottom is
            // touch-only, so the page gate never holds the process-wide pad gate (holdPad = false).
            AnimatedContent(
                targetState = step,
                transitionSpec = { mimirSwapTransform(reduced, enterTravelPx = swapPx) },
                label = "companion-shelf-density-swap",
                modifier = Modifier.fillMaxSize().testTag(CompanionShelfPageAreaTag),
            ) { shown ->
                val pageInput = MimirSwapPageGate(mimirSwapFadeAlpha(reduced), holdPad = false)
                Box(Modifier.fillMaxSize().then(pageInput)) {
                    val grid = remember(widthPx, heightPx, gapPx, shown, working.size) {
                        solveCompanionShelfGrid(
                            availableWidth = widthPx,
                            pageHeight = heightPx,
                            columns = shown.columns,
                            rows = shown.rows,
                            gap = gapPx,
                            count = working.size,
                        )
                    }
                    val cellWidthDp = with(metrics) { grid.cellWidth.toDp() }
                    val cellHeightDp = with(metrics) { grid.cellHeight.toDp() }
                    val startPage = remember { companionShelfPageAfterStep(anchor, shown.perPage, working.size) }
                    val pagerState = rememberPagerState(initialPage = startPage) { grid.pageCount }
                    // Only the pager of the step on screen writes the runes and the anchor: during a swap the departing pager is still composed (and may be mid-snap),
                    // and its page is not the player's any more.
                    val live = shown == step
                    val seeded = remember { booleanArrayOf(false) }
                    LaunchedEffect(pagerState, live) {
                        if (!live) return@LaunchedEffect
                        // The page indicator follows the page the drag is nearest to (`currentPage`, which flips at the midpoint) ...
                        launch { snapshotFlow { pagerState.currentPage }.collect { page -> shownPage = page } }
                        // ... but the player's place moves only when a turn COMPLETES (`settledPage`, the Pager state contract). A drag that crosses the midpoint
                        // and springs back is not a turn, so it must not rewrite the anchor a density step then carries over.
                        snapshotFlow { pagerState.settledPage }.collect { page ->
                            // The first value is the page this pager was opened on, an approximation of the place a step carried over: it must not overwrite
                            // the exact anchor. From then on the anchor is the page the player settled on, a return to the opening page included.
                            if (seeded[0]) anchor = page * shown.perPage else seeded[0] = true
                        }
                    }
                    val fling = if (reduced) {
                        PagerDefaults.flingBehavior(state = pagerState, snapAnimationSpec = snap())
                    } else {
                        // The scene tier (160 ms, the house easing) for a page turn's settle, never Foundation's default spring.
                        PagerDefaults.flingBehavior(
                            state = pagerState,
                            snapAnimationSpec = tween(MimirMotion.SceneMs, easing = MimirMotion.Easing),
                        )
                    }
                    VerticalPager(
                        state = pagerState,
                        // A carry owns the pointer; letting the shelf page under it at the same time makes the seat arithmetic chase a moving
                        // origin for no benefit the finger can use. Also off while inactive: a route kept mounted only for its own fade (S2-07)
                        // should not keep paging.
                        userScrollEnabled = liftedId == null && active,
                        flingBehavior = fling,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val pageStart = page * grid.perPage
                        val pageEnd = minOf(pageStart + grid.perPage, working.size)
                        // The first frame any page draws is the shelf's "I have content" (the host holds its fade-in on it).
                        val reported = remember { booleanArrayOf(false) }
                        Box(
                            Modifier.fillMaxSize().drawWithContent {
                                drawContent()
                                if (!reported[0]) {
                                    reported[0] = true
                                    onFirstLayoutState.value()
                                }
                            },
                        ) {
                            for (seat in pageStart until pageEnd) {
                                val game = working[seat]
                                val id = game.gameId.value
                                key(id) {
                                    val lifted = liftedId == id
                                    val local = seat - pageStart
                                    // The tile's press (B5, R4): reported by the tap node below and drawn by `mimirPressResponse` inside the lift's layer. A press
                                    // that becomes a lift, or a page turn, is cancelled by the gesture that takes it, so the tile never stays drawn in.
                                    val pressSource = remember { MutableInteractionSource() }
                                    CompanionGameTile(
                                        game = game,
                                        cellWidth = cellWidthDp,
                                        cellHeight = cellHeightDp,
                                        showLabel = shown.showsLabel,
                                        labelStyle = labelSpec.first,
                                        labelBand = labelSpec.second,
                                        modifier = Modifier
                                            .offset {
                                                val origin = grid.originOf(local)
                                                IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
                                            }
                                            .zIndex(if (lifted) 1f else 0f)
                                            .graphicsLayer {
                                                if (lifted) {
                                                    // Vector, not accumulation: the gap between the finger and the seat this
                                                    // tile currently occupies. A retarget moves the seat, and the tile stays
                                                    // exactly under the finger without any offset needing to be rebased.
                                                    val current = grid.centreOf(
                                                        working.indexOfFirst { it.gameId.value == id }.coerceAtLeast(0),
                                                    )
                                                    translationX = finger.x - current.x
                                                    translationY = finger.y - current.y
                                                    scaleX = CompanionArrangeLiftScale
                                                    scaleY = CompanionArrangeLiftScale
                                                    alpha = 0.92f
                                                }
                                            }
                                            // Keyed on `active` so leaving the route cancels a carry in progress rather than
                                            // leaving it live behind a route that has already moved on.
                                            .pointerInput(id, grid, active) {
                                                if (!active) return@pointerInput
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { inTile ->
                                                        // Read the state holder directly. `rememberUpdatedState` only refreshes
                                                        // during recomposition, while several MOVE callbacks can arrive in the
                                                        // same frame as a reorder; using that mirror can resolve the next move
                                                        // against the previous order and make an immediate reverse drop miss.
                                                        rollback = working
                                                        liftedId = id
                                                        val liftedSeat = working.indexOfFirst { it.gameId.value == id }
                                                        Log.i("MimirCompanionArrange", "lift id=$id seat=$liftedSeat page=$page")
                                                        // The gesture reports a position inside THIS tile; page space is
                                                        // that plus the tile's own seat origin. Both are computed.
                                                        finger = grid.originOf(liftedSeat.coerceAtLeast(0)) + inTile
                                                    },
                                                    onDrag = { change, delta ->
                                                        change.consume()
                                                        finger += delta
                                                        val live = working
                                                        val current = live.indexOfFirst { it.gameId.value == id }
                                                        // **No scroll term. `finger` is ALREADY page-space** — it was
                                                        // seeded as `originOf(seat) + local`, and `originOf` is absolute
                                                        // page space with no paging in it. Adding a scroll here once counted
                                                        // the scroll twice and resolved the drop `scrollPx / rowPitch` rows
                                                        // too far down, then FILED that wrong order. A page is a fixed
                                                        // origin, so there is nothing to add; the page the carry was lifted
                                                        // on is the page it resolves on.
                                                        val target = grid.seatAt(finger, page)
                                                        if (current >= 0 && target != current) {
                                                            working = live.companionArrangeMove(current, target)
                                                        }
                                                    },
                                                    onDragEnd = {
                                                        // Only a drag that MOVED something files an order. A long-press that
                                                        // lifts and puts back is not an arrangement, and writing one would
                                                        // turn "never arranged, producer order stands" into a filed order by
                                                        // accident — after which a game from a new scan lands at the end
                                                        // instead of its producer position, from a gesture nobody completed.
                                                        val before = rollback
                                                        val after = working
                                                        liftedId = null
                                                        rollback = null
                                                        // `currentActive` (not the `active` this block was launched with):
                                                        // `rememberUpdatedState` does not guarantee same-frame delivery, it
                                                        // just supplies the latest composed `active`/callback values by the
                                                        // time the gesture actually ends.
                                                        if (before != null && before != after && currentActive) {
                                                            val ids = after.map { it.gameId.value }
                                                            Log.i("MimirCompanionArrange", "filed order=$ids")
                                                            currentOnArranged(ids)
                                                        } else if (before != null && before == after) {
                                                            Log.i("MimirCompanionArrange", "putback id=$id")
                                                        }
                                                    },
                                                    onDragCancel = {
                                                        // The tome's Cancel: the pre-lift order exactly, not an undo of moves.
                                                        Log.i("MimirCompanionArrange", "cancel id=$id")
                                                        rollback?.let { working = it }
                                                        rollback = null
                                                        liftedId = null
                                                    },
                                                )
                                            }
                                            // A separate node, not `clickable`: clickable's press handling sat between the
                                            // tile and the long-press detector and the lift never fired. `detectTapGestures`
                                            // cancels once the drag consumes movement, so a carry cannot also open the card.
                                            .pointerInput(id, active) {
                                                if (!active) return@pointerInput
                                                // Guard again at the moment of the tap: `active` above only decides whether
                                                // this coroutine is running at all, not whether the route is still live when
                                                // the tap resolves inside it.
                                                detectTapGestures(
                                                    onPress = { mimirReportPress(pressSource, it) },
                                                    onTap = { if (currentActive) currentOnOpen(game) },
                                                )
                                            }
                                            // The press response follows the tap node and precedes what the tile draws: it draws in whole and the hit box stays
                                            // whole. A press that becomes a lift or a page turn is cancelled by the gesture that takes it.
                                            .mimirPressResponse(pressSource)
                                            .testTag(CompanionShelfTileTag)
                                            .semantics(mergeDescendants = true) {
                                                role = Role.Button
                                                // With no label drawn (Dense) the game's name is still said.
                                                if (!shown.showsLabel) contentDescription = game.title
                                                if (active) onClick(label = "Open launch card") {
                                                    if (currentActive) currentOnOpen(game)
                                                    true
                                                }
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(MimirSpacing.sm))
        CompanionShelfPageStrip(
            currentPage = shownPage,
            pageCount = companionShelfPageCount(working.size, step.perPage),
        )
    }
}

/** The strip under the page: [CompanionShelfRuneStripDp] tall, always reserved so a platform with one page and one with fifty cut their cells the same. */
internal const val CompanionShelfRuneStripDp = 16f
const val CompanionShelfPageRunesTag = "companion-shelf-page-runes"
const val CompanionShelfPageStripTag = "companion-shelf-page-strip"

/**
 * "Page n of m": the page runes up to [CompanionShelfRunesMaxPages] pages, the words beyond that, and nothing at all for a single page. One semantics node
 * says it either way; the diamonds are its picture.
 */
@Composable
private fun CompanionShelfPageStrip(currentPage: Int, pageCount: Int) {
    val palette = LocalMimirPalette.current
    val description = companionShelfPageDescription(currentPage, pageCount)
    Box(
        Modifier.fillMaxWidth().heightIn(min = CompanionShelfRuneStripDp.dp).testTag(CompanionShelfPageStripTag),
        contentAlignment = Alignment.Center,
    ) {
        if (pageCount in 2..CompanionShelfRunesMaxPages) {
            MimirPageRunes(
                current = currentPage,
                count = pageCount,
                palette = palette,
                modifier = Modifier
                    .testTag(CompanionShelfPageRunesTag)
                    .clearAndSetSemantics { contentDescription = description },
            )
        } else if (pageCount > CompanionShelfRunesMaxPages) {
            Text(
                text = description,
                style = MimirTypeScale.SupportCompact.copy(lineHeight = 12.sp),
                color = palette.textMuted,
                maxLines = 1,
                modifier = Modifier
                    .testTag(CompanionShelfPageRunesTag)
                    .clearAndSetSemantics { contentDescription = description },
            )
        }
    }
}

/** How much a lifted tile grows. Enough to read as picked up, not so much it covers its neighbours. */
private const val CompanionArrangeLiftScale = 1.08f

@Composable
private fun CompanionShelfLine(text: String) {
    val palette = LocalMimirPalette.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MimirTypeScale.Support, color = palette.textMuted)
    }
}

/** A tile's label: `Row` 14 sp (policy 8), authored case, at most two lines; the line height is fixed so the band is two lines whatever the font renders. */
internal const val CompanionShelfLabelLineSp = 16f
internal const val CompanionShelfLabelLines = 2
const val CompanionShelfTileLabelTag = "companion-shelf-tile-label"
const val CompanionShelfTileTag = "companion-shelf-tile"

/** The platform's art seat inside a tile: one size for every game on the shelf, whatever art each wears. See [companionShelfArtSeat]. */
const val CompanionShelfTileArtTag = "companion-shelf-tile-art"
const val CompanionShelfPageAreaTag = "companion-shelf-page-area"

/**
 * The platform's ART SEAT: the one rect every game's art is fitted into on a platform's shelf, at one density.
 *
 *  The tile was already one uniform cell, but the art inside it was `Fit` into the cell's own shape (the grid's,
 * not the platform's), so a face of another proportion than the platform's box art painted at another size: a wide custom face came out short, a tall one
 * narrow. The seat is the largest rect of the platform's own cover proportion ([platformCoverAspect]: the curated map, then the measured one, then the
 * default) that fits the region left for the art, the same rule the Main gamelist's cell follows (CP04). Default art is that proportion by construction and
 * fills it; a changed or custom face is `Fit` INSIDE it (nothing is cropped, nothing is stretched, 2026-09-01) and never moves the seat.
 *
 * It is a function of the region and the platform alone: never of the painter, the art's intrinsic size or whether it has loaded, so a face that arrives
 * late, or is changed while the shelf is open, cannot resize the tile.
 *
 * @return the seat in px, at most [regionWidth] x [regionHeight]; an unusable [aspect] reads as the default cover proportion.
 */
internal fun companionShelfArtSeat(regionWidth: Int, regionHeight: Int, aspect: Float): IntSize {
    if (regionWidth <= 0 || regionHeight <= 0) return IntSize.Zero
    val a = if (aspect.isFinite() && aspect > 0f) aspect else DefaultCoverAspect
    // Width-limited when the region is no wider than the seat's proportion allows, otherwise height-limited.
    return if (regionWidth.toFloat() / regionHeight <= a) {
        IntSize(regionWidth, (regionWidth / a).roundToInt().coerceIn(1, regionHeight))
    } else {
        IntSize((regionHeight * a).roundToInt().coerceIn(1, regionWidth), regionHeight)
    }
}

/** Lays its content out at exactly the [companionShelfArtSeat] of the room it is given; nothing about the content decides the size. */
private fun Modifier.companionShelfArtSeatLayout(aspect: Float): Modifier = layout { measurable, constraints ->
    val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
    val seat = if (bounded) companionShelfArtSeat(constraints.maxWidth, constraints.maxHeight, aspect) else IntSize.Zero
    val placeable = measurable.measure(if (bounded) Constraints.fixed(seat.width, seat.height) else constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * A game tile's art, bare and Fit inside its seat. The request is the product's ONE cover request ([mimirCoverRequest]): the shared decode cap and the shared cache
 * key, with a placeholder from that same key, so art the Main shelves already warmed paints on this tile's first frame instead of loading from nothing (the bare
 * `String` model it replaces had its own default key, which never met the cover cache). Coil draws nothing while it loads or after it fails (R3/R8). Policy Q13
 * (2026-10-14): art that is warm at the tile's birth paints whole; art that arrives COLD fades in from nothing on FocusMs ([rememberMimirColdArtReveal]), no placeholder.
 */
@Composable
internal fun CompanionShelfTileArt(
    artRef: String,
    modifier: Modifier = Modifier,
    onState: (AsyncImagePainter.State) -> Unit = {},
) {
    val context = LocalContext.current
    val request = remember(artRef, context) { mimirCoverRequest(context, artRef) }
    val warm = remember(artRef, context) { mimirArtWarmInMemory(context, artRef) }
    // The painter's own state says when the art has landed (the hero reads it the same way), so the reveal needs no second copy of it.
    val painter = rememberAsyncImagePainter(model = request, contentScale = ContentScale.Fit, onState = onState)
    // Ready only for a Success whose request was for THIS art (L9's rule, `mimirArtLanded`): the painter keeps the previous request's state for a
    // frame or two after the model changes, and a bare `is Success` revealed the previous cover under the new identity.
    val reveal = rememberMimirColdArtReveal(identity = artRef, warm = warm, state = painter.state, request = request)
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.graphicsLayer { alpha = reveal() },
    )
}

@Composable
private fun CompanionGameTile(
    game: GameRow,
    /** The solved seat. Uniform across the grid — see [CompanionShelfGrid] for why that is required. */
    cellWidth: Dp,
    cellHeight: Dp,
    /** The step's own switch: Large and Standard carry the title under the art, Dense carries none (the book fallback carries its own on the leather). */
    showLabel: Boolean,
    /** The label's style and the band it stands in, measured once for the grid ([rememberCompanionShelfLabelSpec]). */
    labelStyle: TextStyle,
    labelBand: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    // The product's own ladder, not `game.artUri`: it picks the TIER (a hand-wired face outranks the
    // provider's physical render outranks the cover) and the ASPECT that tier is shaped at.
    val media = libraryListPreviewMedia(game)
    // The art's own aspect decides NOTHING about the tile. Every game on this platform wears the same art seat (the platform's cover proportion, from the
    // same source the gamelist cell reads), and a face of another proportion is Fit inside it. The seat is a function of the platform and the cell only, so a
    // face that is changed, or arrives late, never resizes the tile.
    //
    val seatAspect = platformCoverAspect(game.platformId.value)
    val artRef = media.artUri
    var loadingLogged by remember { mutableStateOf(false) }
    // Receipt: a game with art wired draws the art (Coil draws nothing while it loads or after it fails) and no plate; one
    // without draws the book. `plate=visible hasArt=true` would be a breach of the no-fallback-over-art law, and is written as
    // one. Coil's own state callback (below) says loaded or failed; it is written to a probe read only inside the effect, so
    // it adds no read to this tile. The tile's id is an opaque `game:` id, never the title or the file stem.
    val artProbe = rememberBottomUiArtProbe(game.gameId.value, artRef)
    BottomUiArtReceipt(
        tile = BottomUiReceiptFormat.opaque("game", game.gameId.value),
        hasArt = artRef != null,
        loaded = false,
        plateVisible = artRef == null,
        probe = artProbe,
    )
    Column(
        modifier = modifier.size(cellWidth, cellHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // **No box rail** —
        // This was `MediaArtFrame`, which draws a themed plate, a border and a corner treatment AROUND the
        // art. Box art already has its own edges — a 3D box render is a photograph of an object with a
        // spine and a shadow — so framing it puts a second box around a box. The art is drawn bare, with FIT
        // so nothing is cropped.
        Box(
            // The uniform cell, minus the label lane. The art is FIT inside the platform's art seat, which sits
            // in this region, rather than resizing the cell — a grid whose rows depend on which covers are in
            // them has no closed-form inverse, and the drag needs one.
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            if (artRef != null) {
                // The seat: the same rect for every game on the platform at this density, whatever its art is.
                Box(Modifier.companionShelfArtSeatLayout(seatAspect).testTag(CompanionShelfTileArtTag)) {
                    CompanionShelfTileArt(
                        artRef = artRef,
                        modifier = Modifier.fillMaxSize(),
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Success) artProbe.settle(loaded = true)
                            if (state is AsyncImagePainter.State.Error) artProbe.settle(loaded = false)
                            if (state is AsyncImagePainter.State.Loading && !loadingLogged) {
                                loadingLogged = true
                                logCompanionShelfExit("tile id=${game.gameId.value} painter=Loading")
                            }
                        },
                    )
                }
            } else {
                // QA-23, policy 2026-09-16, REVERSING this site's previous choice. It read: "a quiet
                // themed panel carrying the initial. Deliberately not the book fallback -- that is a rail by
                // another name." The regression report described the single letter here as the placeholder QA-23 is about,
                // so the book lands on the companion rail too.
                //
                // This does NOT reopen the 2026-09-01 "no box rail for companion shelf art or platform art"
                // policy, and the distinction is the whole reason both can hold: that policy removed
                // `MediaArtFrame`, which draws a plate and a border AROUND art that already has its own
                // edges. The book is not a frame around anything -- it IS the content, drawn only when there
                // is no art to double-frame. Real art above is still bare and still FIT, untouched.
                //
                // `showTitle` is irrelevant to this call (the book always carries its title on the leather);
                // the platform id gets the per-platform leather treatment, which the letter panel could not
                // express at all.
                //
                // **The book wears the same seat as the art (policy, 2026-10-08, "Same frame").** Every tile on a
                // platform is the same size at each density, a game with no art included, so the book is drawn inside the
                // platform's art seat like any cover (before this it filled the whole region, and on a wide platform stood
                // taller than a seat next to it). It keeps its look, scaled to the seat: it sizes itself to its own 3:4
                // inside it and centres, and stretching it would distort the cover.
                //
                // At Dense the book carries the game's ONLY name, in a small book (a wide platform's seat is the smallest):
                // `fitTitle` sets the title to the leather, from the book's own size down to the type scale's floor, in at
                // most three lines, ending in an ellipsis only when even the floor cannot hold it.
                Box(Modifier.companionShelfArtSeatLayout(seatAspect).testTag(CompanionShelfTileArtTag)) {
                    MediaArtFallbackGlyph(
                        title = game.title,
                        focused = false,
                        palette = palette,
                        platformIdValue = game.platformId.value,
                        fitTitle = true,
                    )
                }
            }
        }
        if (showLabel) {
            Spacer(Modifier.size(MimirSpacing.xs))
            Text(
                text = game.title,
                style = labelStyle,
                color = palette.textSecondary,
                textAlign = TextAlign.Center,
                maxLines = CompanionShelfLabelLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().height(labelBand).testTag(CompanionShelfTileLabelTag),
            )
        }
    }
}

/**
 * The tile label's style (`Row` 14 sp, authored case) and the band it stands in: two lines of THIS style at THIS font scale, measured, plus 2 dp. Measured rather
 * than computed from the line height, so the band is two lines on every renderer and the label can never be cut to one by a band a font's real line height outgrew.
 */
@Composable
private fun rememberCompanionShelfLabelSpec(): Pair<TextStyle, Dp> {
    val metrics = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = remember {
        MimirTypeScale.Row.copy(
            lineHeight = CompanionShelfLabelLineSp.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.None,
            ),
        )
    }
    val bandPx = remember(metrics.density, metrics.fontScale, measurer) {
        measurer.measure(AnnotatedString("A\nA"), style, maxLines = CompanionShelfLabelLines).size.height
    }
    return style to with(metrics) { bandPx.toDp() + 2.dp }
}
