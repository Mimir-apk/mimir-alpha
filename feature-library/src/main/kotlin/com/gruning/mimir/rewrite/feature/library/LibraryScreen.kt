package com.gruning.mimir.rewrite.feature.library

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.memory.MemoryCache
import com.gruning.mimir.rewrite.core.ui.MimirArtInkStore
import com.gruning.mimir.rewrite.core.ui.MimirBeforeFirstValidViewport
import com.gruning.mimir.rewrite.core.ui.MimirArtFailureLog
import com.gruning.mimir.rewrite.core.ui.mimirCoverRequest
import com.gruning.mimir.rewrite.core.ui.mimirCoverCacheKey
import com.gruning.mimir.rewrite.core.input.FocusAuthority
import com.gruning.mimir.rewrite.core.input.FocusKey
import com.gruning.mimir.rewrite.core.input.FocusScope
import com.gruning.mimir.rewrite.core.input.FocusState
import com.gruning.mimir.rewrite.core.input.LocalFocusAuthority
import com.gruning.mimir.rewrite.core.input.isLitFocusIn
import com.gruning.mimir.rewrite.core.input.mimirActivatable
import com.gruning.mimir.rewrite.core.input.mimirFocusable
import com.gruning.mimir.rewrite.core.input.renderedFocusIn
import com.gruning.mimir.rewrite.core.launch.OpenWithOptions
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.model.PlatformId
import com.gruning.mimir.rewrite.core.model.library.GameRow
import com.gruning.mimir.rewrite.core.model.library.PlatformRow
import com.gruning.mimir.rewrite.core.ui.BeamSeparator
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.MimirControlHintText
import com.gruning.mimir.rewrite.core.ui.MimirChromeText
import com.gruning.mimir.rewrite.core.ui.MimirGlass
import com.gruning.mimir.rewrite.core.ui.MimirHintToastRail
import com.gruning.mimir.rewrite.core.ui.MimirIntentText
import com.gruning.mimir.rewrite.core.ui.MimirTextFragment
import com.gruning.mimir.rewrite.core.ui.MimirTextIntent
import com.gruning.mimir.rewrite.core.ui.MimirKeyboard
import com.gruning.mimir.rewrite.core.ui.MimirKeyboardHeight
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirRunicQuickRailDefaults
import com.gruning.mimir.rewrite.core.ui.MimirShelfBackground
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.core.ui.MimirTransitionEpochLedger
import com.gruning.mimir.rewrite.core.ui.holdMimirInputUntilShown
import com.gruning.mimir.rewrite.core.ui.mimirHold
import com.gruning.mimir.rewrite.core.ui.MimirTypeScale
import com.gruning.mimir.rewrite.core.ui.mimirMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.rememberMeasuredViewport
import com.gruning.mimir.rewrite.core.ui.PlatformConsoleArt
import com.gruning.mimir.rewrite.core.ui.ctaMaterial
import com.gruning.mimir.rewrite.core.ui.MimirFocusTileRingReach
import com.gruning.mimir.rewrite.core.ui.MimirRingShape
import com.gruning.mimir.rewrite.core.ui.MimirSearchGlyph
import com.gruning.mimir.rewrite.core.ui.MimirSearchGlyphSize
import com.gruning.mimir.rewrite.core.ui.MimirTypeStep
import com.gruning.mimir.rewrite.core.ui.resolve
import com.gruning.mimir.rewrite.core.ui.mimirSelectionRing
import com.gruning.mimir.rewrite.core.ui.MimirEtchedChipLabelStyle
import com.gruning.mimir.rewrite.core.ui.etchedChipInk
import com.gruning.mimir.rewrite.core.ui.mimirEtchedChipSurface
import com.gruning.mimir.rewrite.core.ui.mimirGlanceKeepClear
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.MimirSheetHost
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassHold
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.core.ui.MimirCopy
import com.gruning.mimir.rewrite.core.ui.mimirSurfaceShadow
import com.gruning.mimir.rewrite.core.ui.mimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.LocalMimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.MimirViewportProfile
import com.gruning.mimir.rewrite.core.ui.theme.MimirColors
import com.gruning.mimir.rewrite.core.ui.theme.MimirSpacing
import com.gruning.mimir.rewrite.core.ui.theme.MimirType
import java.io.File
import kotlinx.coroutines.delay
import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt
import com.gruning.mimir.rewrite.core.ui.LocalMimirPageArrived
import com.gruning.mimir.rewrite.core.ui.LocalMimirPageSettling
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoff
import com.gruning.mimir.rewrite.core.ui.MimirPageHandoffProbe
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.mimirSwapEnterSpec
import com.gruning.mimir.rewrite.core.ui.MimirTransitionInputGate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.geometry.Offset

/**
 * THE Library — the console core: see your platforms, open one, browse its dedup-collapsed games, and
 * (stub) open a game. Two views under one [FocusAuthority]:
 *
 *  - **Platforms** ([LibraryShelfScope]): only platforms that have games , as a horizontal shelf + an "Add / rescan folder" action.
 *  - **Gamelist** ([GamelistScope]): the open platform's collapsed groups — one tile per group, a
 *    "N discs / N versions" badge when a group folds >1 member , an
 *    honest media-art holding frame , and a title.
 *
 * Which view is showing is driven by [LibraryViewModel.openPlatform] (host state, single truth — never
 * derived from focus). CONFIRM on a platform opens its gamelist (push [GamelistScope]); CANCEL/Back in
 * the gamelist pops back to the shelf. CONFIRM on a game opens typed detail; Play launches from there.
 * Open With lives as a small metadata affordance in the preview panel instead of a launch menu.
 *
 * Input arrives via [dispatchToLibrary], the single normalized-action entry point the app shell feeds.
 */

/** Active scope while the platforms shelf owns focus. */
val LibraryShelfScope = FocusScope("library/shelf")

/** Active scope while a platform's gamelist owns focus. */
val GamelistScope = FocusScope("library/gamelist")

/** Active scope while a game's full-screen detail page owns focus (opened from the gamelist). */
val GameDetailScope = FocusScope("library/gameDetail")

const val LibraryShelfRootTestTag = "library-shelf-root"
internal const val LibraryShelfHeaderTestTag = "library-shelf-header"
internal const val LibraryShelfToolsTestTag = "library-shelf-tools"
internal const val LibraryShelfHeroBandTestTag = "library-shelf-hero-band"
internal const val LibraryShelfSelectedSlotTestTag = "library-shelf-selected-slot"
internal const val LibraryShelfQuickRailHostTestTag = "library-shelf-quick-rail-host"
internal const val LibraryOutgoingLayerDurationMs = MimirMotion.ModeTransitionMs
internal const val LibraryOutgoingLayerStartAlpha = 1f
/*
 * **There is no glassdrop on the gamelist→shelf return, and its absence is deliberate.**
 *
 * It was here from 2026-08-19 (*"Return is the parent arriving, never the child leaving"*): the shelf was
 * born at full 18 dp blur, held while the outgoing gamelist dissolved, then resolved into focus. Return latency was measured on the Thor at every step on 2026-09-04, n = 5 interleaved:
 *
 * | gamelist → shelf | mean | sd |
 * |---|---|---|
 * | as it stood | 390.6 ms | 7.6 |
 * | both beats trimmed to their measured invisible points | 303.0 ms | 6.9 |
 * | trimmed further, into visible motion | 296.6 ms | 4.9 — **t = −1.33, no gain** |
 * | **no glassdrop** | **201.0 ms** | 10.4 — **t = −18.26** |
 * | *Home → Library shelf, which never had one* | *211.6 ms* | *11.1* |
 *
 * **Trimming the numbers was exhausted before the blur was.** Cutting 42 ms of nominal moved the
 * transition by 3.6 ms, because the tail is not animation at all: region-diffing the end of the
 * transition gives a pure edge-outline difference — blur→sharp — arriving after a **51 ms gap in which
 * no frame is presented**. The blur was costing dropped frames, not duration. This session measured the
 * same effect on the Tome Library: an 18 dp full-screen `BlurEffect` costs ~7 ms of RenderThread per
 * frame whenever the layer beneath it is changing, and during a return it is changing constantly.
 *
 * So the return now costs what every other path to this shelf costs. **Do not re-arm this without a new
 * design review and a new measurement** — `LibraryReturnGlassAbsentTest` exists to make that deliberate.
 * `rememberMimirGlassReturnProgress` is untouched and still carries the Folio return, which measured
 * fine and keeps its material.
 */

/*
 * The gamelist's in-place changes are SWAPS: the old page leaves as
 * recorded pixels on OverlayMs while the new one fades in from the 130 ms lead on OverlayMs, 22 dp sideways for an
 * L2/R2 platform switch and 8 dp on Y for a Carousel/List change. The 0.88 and 0.86 start alphas and the 0.985 scale
 * retired with it: no scale in the family, and an arriving page starts from nothing.
 */
internal val LibraryGamelistPageTravelDp: Float = MimirMotion.GlideTravelDp.toFloat()
internal val LibraryGamelistViewTravelDp: Float = MimirMotion.SwapTravelDp.toFloat()

/** The gamelist Column's horizontal padding. The list/carousel handoff bleeds by exactly this. */
internal val LibraryGamelistBodyPaddingDp: Dp = MimirSpacing.xxl

/**
 * Nova list/carousel transition regression, 2026-09-29. A page that fades is drawn through an
 * offscreen buffer the size of its handoff ([MimirPageHandoff] sets no compositing strategy, so below alpha 1 its layer
 * is composited offscreen at its own bounds). The carousel paints its side cards past the gamelist Column's padding to
 * the screen edge, so while a view faded they were cut ~32 dp in, and snapped out when the fade ended -- or in, the
 * moment a leaving view began to fade; L2/R2 did the same by its 22 dp glide, whose layer carries its bounds with it.
 *
 * The handoff is laid out [horizontal] and [vertical] larger on each side than its slot, placed so the slot is where it
 * was, and its content is put back with the same padding: nothing moves, and the buffer covers what the page paints
 * outside its slot, its own travel included. The shared page motion is untouched; the slot the parent sees is too.
 */
internal fun Modifier.gamelistPageBleed(horizontal: Dp, vertical: Dp = 0.dp): Modifier = layout { measurable, constraints ->
    val dx = horizontal.roundToPx()
    val dy = vertical.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + 2 * dx,
            maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * dx else constraints.maxWidth,
            minHeight = constraints.minHeight + 2 * dy,
            maxHeight = if (constraints.hasBoundedHeight) constraints.maxHeight + 2 * dy else constraints.maxHeight,
        ),
    )
    layout(placeable.width - 2 * dx, placeable.height - 2 * dy) { placeable.place(-dx, -dy) }
}

/**
 * The Library's primary page change (motion audit batches B and C). Opening a platform is a page entry, held until
 * the gamelist's rows and first covers are ready (at most [MimirPageMotion.SettleCapMs]); the way back is a return at
 * today's pace.
 */
internal fun libraryPrimaryPageMotion(
    from: LibraryPrimarySurfaceKey,
    to: LibraryPrimarySurfaceKey,
    settleGamelistOpen: (suspend () -> Unit)?,
    book: LibraryBookLeaf? = null,
    /** The leaf whose cover swings shut on the way back, with the platform being closed. */
    closeBook: LibraryBookLeaf? = null,
    closingPlatformId: String? = null,
    /** A launch return restoring the Library under the return cover: the page change has no motion (see [LibraryPageMotion.Settled]). */
    settled: Boolean = false,
): LibraryPageMotion = when {
    settled -> LibraryPageMotion.Settled
    from == LibraryPrimarySurfaceKey.Shelf && to == LibraryPrimarySurfaceKey.Gamelist ->
        LibraryPageMotion.Entry(direction = 1, settle = settleGamelistOpen, book = book)
    from == LibraryPrimarySurfaceKey.Gamelist && to == LibraryPrimarySurfaceKey.Shelf ->
        LibraryPageMotion.Return(
            direction = 1,
            book = closeBook?.takeIf { closingPlatformId != null },
            bookPlatformId = closingPlatformId?.takeIf { closeBook != null },
        )
    else -> LibraryPageMotion.Dissolve
}

internal sealed interface LibraryPrimarySurfaceKey {
    data object Shelf : LibraryPrimarySurfaceKey
    data object Gamelist : LibraryPrimarySurfaceKey
}

internal fun libraryPrimarySurfaceKey(openPlatform: PlatformId?): LibraryPrimarySurfaceKey =
    if (openPlatform == null) LibraryPrimarySurfaceKey.Shelf else LibraryPrimarySurfaceKey.Gamelist

/**
 * Whether the destination page must HOLD its start pose. True from the instant the destination id lands --
 * BEFORE its rows do -- so the page can never paint SETTLED and then jump backwards to the start pose when
 * the rows arrive. That backwards jump caused abrupt platform switching on 2026-08-21: the glide existed, but it was armed on the rows
 * landing rather than on the switch, so the beat before it was a fully settled paint of the destination.
 */
internal fun libraryGamelistPageMotionHolds(
    platformId: PlatformId,
    motion: GamelistPlatformSwitchMotion?,
): Boolean = motion?.platformId == platformId

/**
 * The floor on how long the gamelist's STATIC hero waits before revealing — the page glide's own
 * duration, so the backdrop lands with the page instead of after it.
 *
 * Policy 2026-08-26, *"backdrop rides the page"*, settling a strobe the regression report described twice. Measured on
 * the Odin: the glide runs [MimirMotion.ModeTransitionMs] = 160 ms gated on rows, while the backdrop sat
 * on [BifrostMediaSettleMs] = 300 ms — a different clock nothing coordinated. Re-bursts (screen settles,
 * then moves AGAIN) landed 180/189/213 ms after the page settled. That gap IS the arithmetic difference.
 *
 * **Why lowering this floor is safe, and why it is a new constant rather than a change to the old one.**
 * [BifrostMediaSettleMs] is not a dwell for a still image at all — it is the VIDEO player's debounce,
 * *"the player only binds after focus SETTLES so scrolling the list doesn't spin up a player per game"*
 * (`VideoBackdrop.kt`). The static hero borrowed the constant and inherited a cost that was never its
 * own: one discipline, two call sites. The video debounce keeps its 300 ms and its tests; only the still
 * hero's floor moves.
 *
 * **What actually prevents a half-decoded flash is untouched.** `LibraryHeroBackdrop` awaits a terminal
 * painter state (`Success`/`Error`) *before* it reveals, and reveals at `max(decode, floor)`. The floor
 * was never the decode guard — the terminal await is — so a lower floor cannot expose an undecoded
 * image. It only stops the backdrop idling after it is already ready.
 */
/*
 * **zero.** The 2026-08-26 value above (`ModeTransitionMs`, 160 ms) was chosen so
 * the hero "rides the page", and the arithmetic behind it was wrong in a way no reading caught: the floor
 * is a delay BEFORE the 180 ms reveal tween, not the tween itself. Setting it equal to the glide therefore
 * lands the hero at `160 + 180 = 340 ms` against a page that settled at 160 ms — the two arrivals the shared handoff should merge, still separated, just by less.
 *
 * Device-measured on the Odin, by correlating each frame against the settled frame of the incoming hero
 * (a reference, not a brightness threshold — two threshold proxies gave false answers first, measured during transition analysis):
 *
 *   floor 160 ms  ->  213, 220, 217 ms   (mean 217)
 *   floor   0 ms  ->  -50,  80,  97 ms   (mean  42)
 *
 * Proven by moving the term, not by adding up constants. At zero the reveal runs CONCURRENTLY with the
 * glide and the surface arrives as one movement.
 *
 * **This is still not the decode guard.** `LibraryHeroBackdrop` awaits a terminal painter state before it
 * reveals at all; that await is what prevents a half-decoded flash, and it is untouched. The measurement
 * also settled that decode is not the binding term here at all — every transition was ~217 ms regardless
 * of artwork, platform or cache state, which is what a constant looks like and what a decode does not.
 */
internal val LibraryGamelistHeroRevealFloorMs: Long = 0L

/**
 * How long the gamelist may hold its quiet loader while a switched-to platform reports nothing.
 *
 *
 */
internal const val LibraryGamelistEmptyGraceMs = 400L

/**
 * Whether the carousel shows its quiet loader instead of the empty-platform message.
 *
 * **The defect this closes** (measured on the Odin, shipped build, 2026-08-27): a platform switch
 * rendered *"NO GAMES ON THIS PLATFORM YET."* for PlayStation — which has three games — and did it on
 * every switch, twice: once with no rows and again with rows but no art. That blank is what made the
 * switch read as a snap, and transition changes alone could not make the missing page visible.
 * **No alpha curve can fade in a page that does not exist yet.**
 *
 * **Why `gamesLoaded` alone was not enough.** `games` is dropped to `emptyList()` by the stale-list
 * guard in `LibraryViewModel` — deliberately, so the previous platform's covers cannot flash into the
 * next — and that guard's own comment says it depends on this loader to cover the gap. But
 * `gamesLoadedForPlatform` has already been set to the destination by the paint-immediately cache
 * branch, so `gamesLoaded` is true while the list is empty, and the empty-state copy paints. One flag
 * answering *"has the query returned"* was being read as *"is there anything to show"* — the same
 * two-meanings-in-one-value shape as `artUri == null`, `WiredFaceKind` and `artResolutionSettled`.
 *
 * **Why it is BOUNDED.** Holding the loader until the list is non-empty would spin forever on a platform
 * that genuinely has no games. The grace window is what lets an honest empty platform still say so —
 * it just has to wait out the switch before it is believed.
 */
internal fun libraryGamelistShowsLoader(
    gamesLoaded: Boolean,
    gamesEmpty: Boolean,
    withinSwitchGrace: Boolean,
): Boolean = !gamesLoaded || (gamesEmpty && withinSwitchGrace)

/**
 * When the held start pose is RELEASED into the glide: **the destination's page is real**.
 *
 * That is the same question [libraryGamelistShowsLoader] answers, so it is asked ONCE and both consumers
 * read the answer. **The page glides exactly when the loader stops.**
 *
 * **The defect this closes.** This predicate used to read `gamesLoaded` alone -- the reading the loader's
 * own docstring, four lines above, says is wrong: *"One flag answering 'has the query returned' being read
 * as 'is there anything to show'."* `games` is dropped to `emptyList()` by the stale-list guard while
 * `gamesLoadedForPlatform` has already been set to the destination, so on a cold switch `gamesLoaded` is
 * true with nothing on screen. The 160 ms glide therefore ran over the LOADER, and the rows mounted at or
 * after settle with no transition of their own -- content arriving out of phase with the motion meant to
 * carry it. The function's own contract line ("the destination's own rows are on screen") was falsified by
 * the paragraph above it.
 *
 * Found by an adversarial code trace, 2026-08-27, while looking for something else. It is the same
 * two-meanings-in-one-value shape as `artUri == null`, `WiredFaceKind` and `artResolutionSettled` -- and
 * the same fix I had already applied to the loader, left unapplied to the field beside it.
 *
 * **Still bounded.** A genuinely empty platform stops showing the loader when the grace expires, so the
 * page glides over the honest empty message rather than waiting forever. A warm destination whose rows are
 * already present releases immediately, which is this transition's stated contract.
 */
internal fun libraryGamelistPageMotionApplies(
    platformId: PlatformId,
    gamesLoaded: Boolean,
    gamesEmpty: Boolean,
    withinSwitchGrace: Boolean,
    motion: GamelistPlatformSwitchMotion?,
): Boolean = !libraryGamelistShowsLoader(
    gamesLoaded = gamesLoaded,
    gamesEmpty = gamesEmpty,
    withinSwitchGrace = withinSwitchGrace,
) && libraryGamelistPageMotionHolds(platformId, motion)

internal fun libraryGamelistViewLandingApplies(
    previous: GamelistViewMode,
    current: GamelistViewMode,
): Boolean = previous != current

/**
 * The scroll origin a gamelist LIST is BORN at when its ring identity is replaced.
 *
 * **The rule this restates.** `rememberCoverflowLinearMotion` (`CoverflowMotion.kt:485`) remembers the
 * carousel's centre ON `identityOrder` and constructs it already sitting at the destination's index, so a
 * ring-identity replacement can never inherit the departed platform's position. The LIST view's
 * `LazyListState` was remembered on NOTHING, so the same state -- and the same scroll offset -- survived a
 * platform switch, and the destination was laid out and painted at the SOURCE's offset before
 * [ScrollFocusedLazyItemIntoView] could seat it. Two surfaces, one ring-swap law, only one of them obeying
 * it.
 *
 * **Why the seat and not zero.** Gamelist focus is remembered per (platform, favoritesOnly) lane
 * (`LibraryViewModel.gamelistFocusMemory`), so switching back to a visited platform restores focus to a row
 * that may be far down. Being born at index 0 would trade the source's wrong offset for the destination's
 * wrong offset. Being born at the seat leaves [focusRevealAction]'s restoring pass with nothing to travel
 * but the centring half-viewport it would owe on any fresh mount.
 *
 * **Bounds are the list's, not the caller's.** A negative [scrollTargetIndex] is the ordinary
 * "focus has not published yet" beat and means the top; an index past the end cannot be seated at all.
 *
 * **This is NOT the mechanism behind the 2026-08-28 throb report.** That is the hero backdrop
 * blanking for ~400 ms on every switch in the recorded measurement. This closes the ring-swap defect CLASS on the list
 * that the carousel already had closed.
 */
internal fun gamelistListScrollOrigin(scrollTargetIndex: Int, itemCount: Int): Int =
    if (itemCount <= 0) 0 else scrollTargetIndex.coerceIn(0, itemCount - 1)

private sealed interface LibraryModalSurfaceKey {
    data object OpenWith : LibraryModalSurfaceKey

    /** The disc / version menu . Keyed on the game so switching card re-enters. */
    data class LaunchChoice(val gameId: String) : LibraryModalSurfaceKey
    data class HideGame(val gameId: String) : LibraryModalSurfaceKey
    data class HideNative(val packageName: String) : LibraryModalSurfaceKey
    data class NativeAdd(val mode: LibraryShelfMode) : LibraryModalSurfaceKey
}

/** Stable focus keys (stable across recompositions so focus survives re-render). */
private fun platformKey(id: PlatformId) = FocusKey("library/shelf/${id.value}")
private val AddFolderKey = FocusKey("library/shelf/add")
internal fun gameKey(id: GameId) = FocusKey("library/gamelist/${id.value}")
internal fun gameKey(row: GameRow) = gameKey(row.gameId)
internal val GamelistGetArtKey = FocusKey("library/gamelist/getart")
private val NativeAddPickerPanelRadius: Dp = 16.dp
private val NativeAddPickerControlRadius: Dp = 9.dp
private val NativeAddPickerRowRadius: Dp = 10.dp
private val NativeAddPickerRowMinHeight: Dp = 58.dp

/** The add picker's close button corner: one symbol for its clip and its (dense) ring. */
private val NativeAddPickerCloseCorner: Dp = 8.dp
private val NativeAddPickerIconSize: Dp = 42.dp
private val NativeAddPickerMaxWidth: Dp = 980.dp
private val NativeAddPickerMaxHeight: Dp = 720.dp
private data class ShelfPrimarySnapshot(
    val platforms: List<PlatformRow>,
    val favoritesCount: Int,
    val shelfMode: LibraryShelfMode,
    val nativeGameRows: List<NativeShelfRow>,
    val nativeAppRows: List<NativeShelfRow>,
    val focused: FocusKey?,
    val rememberedKeysByMode: Map<LibraryShelfMode, FocusKey>,
)

private data class GamelistPrimarySnapshot(
    val platforms: List<PlatformRow>,
    val platformLaunchStatusLines: Map<PlatformId, String>,
    val openPlatform: PlatformId,
    val games: List<GameRow>,
    /** The keys of [games], made once per list (RC-45): the scene asks them on every focus step. */
    val gameKeys: GamelistKeyIndex,
    /** False during the load beat after a switch — the gamelist holds instead of flashing empty/stale. */
    val gamesLoaded: Boolean,
    val detailGame: GameRow?,
    val launchState: GameLaunchUiState,
    val favoriteSegment: FavoriteSegment,
    val favoriteSegments: List<FavoriteSegment>,
    val favoriteSegmentCounts: Map<FavoriteSegment, Int>,
    val activeNativeFavoriteRows: List<NativeShelfRow>,
    val searchQuery: String,
    val favoritesOnly: Boolean,
    val viewMode: GamelistViewMode,
    val keyboardOpen: Boolean,
    val focused: FocusKey?,
)

private class PrimarySnapshotHolder<T> {
    var value: T? = null
}

internal data class PendingGamelistLanding(
    val platformId: PlatformId,
    val rememberedFocus: FocusKey?,
)

internal data class GamelistLandingResolution(
    val pending: PendingGamelistLanding?,
    val target: FocusKey?,
)

internal data class GamelistFilterLandingResolution(
    val pending: PendingGamelistFilterLanding?,
    val target: FocusKey?,
)

/** Resolves and consumes one platform-open landing only after that platform's game ring is loaded. */
internal fun resolvePendingGamelistLanding(
    pending: PendingGamelistLanding?,
    openPlatform: PlatformId?,
    gamesLoadedForPlatform: PlatformId?,
    gameKeys: Collection<FocusKey>,
    focused: FocusKey?,
): GamelistLandingResolution {
    if (pending == null || pending.platformId != openPlatform || gamesLoadedForPlatform != openPlatform ||
        gameKeys.isEmpty()
    ) {
        return GamelistLandingResolution(pending = pending, target = null)
    }

    val first = gameKeys.first()
    val currentIsUserLanding = focused in gameKeys && focused != first
    val remembered = pending.rememberedFocus?.takeIf { it in gameKeys }
    val target = if (currentIsUserLanding) null else (remembered ?: first).takeIf { it != focused }
    return GamelistLandingResolution(pending = null, target = target)
}

/** Resolve one Normal/Favorites destination without mistaking the still-focused source for settled truth. */
internal fun resolvePendingGamelistFilterLanding(
    pending: PendingGamelistFilterLanding?,
    openPlatform: PlatformId?,
    favoritesOnly: Boolean,
    gamesLoadedForPlatform: PlatformId?,
    gameKeys: Collection<FocusKey>,
    focused: FocusKey?,
): GamelistFilterLandingResolution {
    if (pending == null || pending.platformId != openPlatform ||
        pending.favoritesOnly != favoritesOnly || gamesLoadedForPlatform != openPlatform || gameKeys.isEmpty()
    ) {
        return GamelistFilterLandingResolution(pending = pending, target = null)
    }

    val first = gameKeys.first()
    val requested = gameKey(pending.targetGameId).takeIf { it in gameKeys } ?: first
    val source = pending.sourceGameId?.let(::gameKey)
    val currentIsUserLanding = focused in gameKeys && focused != first && focused != source && focused != requested
    val target = requested.takeUnless { currentIsUserLanding || focused == requested }
    return GamelistFilterLandingResolution(pending = null, target = target)
}

/** Focus-loss fallback for a loaded ring: retain its valid remembered centre before using game one. */
internal fun gamelistReseedTarget(
    gameKeys: Collection<FocusKey>,
    focused: FocusKey?,
    rememberedFocus: FocusKey?,
): FocusKey? {
    if (
        gameKeys.isEmpty() ||
        focused in gameKeys ||
        focused == GamelistGetArtKey
    ) {
        return null
    }
    return rememberedFocus?.takeIf { it in gameKeys } ?: gameKeys.first()
}

/**
 * The key the Library paints on its very first frame. Child scopes and list mutations must never substitute
 * game one while the authoritative gamelist identity is still remembered.
 */
internal fun gamelistRenderedFocus(
    gameKeys: Collection<FocusKey>,
    focused: FocusKey?,
    scopeOwnsGamelist: Boolean,
    rememberedFocus: FocusKey?,
    pendingRememberedFocus: FocusKey?,
    retainedGameFocus: FocusKey?,
    pendingFilterFocus: FocusKey? = null,
    pendingFilterSourceFocus: FocusKey? = null,
    /** True while a platform-open landing for the CURRENTLY open platform is still unresolved. */
    platformLandingPending: Boolean = false,
): FocusKey? {
    if (gameKeys.isEmpty()) return null
    val first = gameKeys.first()
    val pending = pendingRememberedFocus?.takeIf { it in gameKeys }
    // The media pill is platform-INVARIANT, so `GamelistGetArtKey` survives an L2/R2 switch as a key even
    // though its meaning does not: `switchOpenPlatform` nulls the `detailGame` the pill was acting on, and
    // `switchGamelistPlatform` deliberately leaves authority focus sitting on the pill (the outgoing authority remains live
    // until the destination has registered). Painting it as the centre therefore centres NOTHING -- `GamelistCarousel`
    // takes its `getArtFocused` branch, finds `detailGame` null, and falls back to `focusedIndex = 0`. The
    // coverflow seeds at tile 0 and then GLIDES to the real landing when the effect resolves: the same visible
    // travel the ring-swap fix removed for tile-initiated switches, reached by a different door.
    //
    // Suppress it for exactly as long as a landing is pending -- one or two frames, consumed by the landing
    // effect -- so the destination's own remembered key is what paints from the first frame.
    val pillCanCentre = !platformLandingPending
    val live = focused?.takeIf {
        it in gameKeys || (it == GamelistGetArtKey && pillCanCentre)
    }
    val filterPending = pendingFilterFocus?.takeIf { it in gameKeys }
    if (scopeOwnsGamelist && filterPending != null) {
        val liveIsUserLanding = live in gameKeys && live != first &&
            live != pendingFilterSourceFocus && live != filterPending
        if (live == filterPending || liveIsUserLanding) return live
        return filterPending
    }
    // pushScope may auto-land on item one before the remembered ring has registered. Paint the pending
    // identity immediately; the existing settlement effect brings FocusAuthority to the same key.
    if (scopeOwnsGamelist && live == first && pending != null && pending != first) return pending
    if (scopeOwnsGamelist && live != null) return live
    // Same reasoning as `live` above: `setFocus` stamps focusMemory on every focus change, so the scope's
    // remembered key can BE the pill. Letting it through here would re-centre nothing by the back door.
    return rememberedFocus?.takeIf {
        it in gameKeys || (it == GamelistGetArtKey && pillCanCentre)
    }
        ?: pending
        ?: retainedGameFocus?.takeIf { it in gameKeys }
        ?: first
}

/**
 * Whether the book is about to take focus on a gamelist entry: a platform-open landing is armed, and there is a page for it to land on (the rows
 * are still on their way, or in and not empty). A landing is consumed only once rows exist, so on a platform that loaded EMPTY it would stay armed
 * for ever, and the pill (the only thing left to focus there) must wear its ring.
 */
internal fun libraryGamelistLandingArmed(landingPending: Boolean, gamesLoaded: Boolean, gamesEmpty: Boolean): Boolean =
    landingPending && !(gamesLoaded && gamesEmpty)

/**
 * The Search pill's ring on a gamelist (audit 2026-10-14, RC-18, L-10: "double rings on gamelist entry"). The authority may sit on the pill for a
 * frame or two while the book is already painted as the landing, so the pill stays unlit while a landing is [landingArmed]: the book is then
 * the only ring from the first frame, and the pill takes it back the moment the landing is done.
 */
internal fun gamelistSearchRingLit(authorityLit: Boolean, landingArmed: Boolean): Boolean = authorityLit && !landingArmed

/** Paint native Favorites from their own stable key set rather than leaking a ROM-row identity. */
internal fun nativeFavoritesRenderedFocus(
    rowKeys: List<FocusKey>,
    focused: FocusKey?,
    scopeOwnsGamelist: Boolean,
    rememberedFocus: FocusKey?,
): FocusKey? {
    if (rowKeys.isEmpty()) return null
    if (scopeOwnsGamelist && focused in rowKeys) return focused
    return rememberedFocus?.takeIf { it in rowKeys } ?: rowKeys.first()
}

/** Retain a detail row only during the load beat; loaded list truth may honestly remove it. */
internal fun reconcileLiveGamelistDetail(
    retained: GameRow?,
    games: List<GameRow>,
    loadedTruth: Boolean,
): GameRow? = retained?.let { snapshot ->
    games.firstOrNull { it.gameId == snapshot.gameId } ?: snapshot.takeUnless { loadedTruth }
}

/** The Enrich/Hero media action is a child of its owning game, so its backdrop identity never becomes blank. */
internal fun gamelistBackdropFocusKey(focused: FocusKey?, detailGameKey: FocusKey?): FocusKey? =
    if (focused == GamelistGetArtKey) detailGameKey else focused

internal data class LibraryLayerFrame(
    val primaryLayerIndex: Int,
    val outgoingLayerIndex: Int?,
)

internal fun nextLibraryLayerFrame(
    currentPrimaryLayerIndex: Int,
    retainOutgoing: Boolean,
): LibraryLayerFrame {
    require(currentPrimaryLayerIndex == 0 || currentPrimaryLayerIndex == 1)
    return if (retainOutgoing) {
        LibraryLayerFrame(
            primaryLayerIndex = 1 - currentPrimaryLayerIndex,
            outgoingLayerIndex = currentPrimaryLayerIndex,
        )
    } else {
        LibraryLayerFrame(
            primaryLayerIndex = currentPrimaryLayerIndex,
            outgoingLayerIndex = null,
        )
    }
}

/**
 * Whether the card that is opening is a launch return putting the player's own card back (after a
 * game launched from the Library, Mimir returns to that game's card). The return cover is up over it and lifts once it has
 * drawn, so the card must be the finished card at its first frame: arriving on the page clock (130 ms at alpha 0, then 540 ms)
 * would leave the cover to reveal the blurred gamelist with no card on it. [restoring] is [LibraryViewModel.restoringLaunchReturnTarget]: the target while its restore is under way, never the one that merely stays pending through a game session.
 */
internal fun libraryDetailArrivalSettled(restoring: LibraryLaunchReturnTarget?): Boolean =
    restoring?.restoreLaunchCard == true

/**
 * Whether a Library page change is a launch return putting the Library back (a restoring return target, see [LibraryViewModel.restoringLaunchReturnTarget]). The return cover is up over
 * it and lifts as soon as the restored surface has drawn, so the page must be the finished page at its first frame: an entry gliding
 * in on the page clock (hold, 130 ms lead, 540 ms arrival) would play out AFTER the cover lifted, as a gamelist sliding in behind the
 * frosted card, which reads as a blurred background that moves (Odin reproduction, 2026-10-14: back from Eden, which had crashed on exit,
 * the Library was reopened from the shelf and the gamelist arrived under the card).
 */
internal fun libraryPageRestoreSettled(restoring: LibraryLaunchReturnTarget?): Boolean = restoring != null

/** Preserve the alpha already painted when a rapid modal reversal reuses one of the two layers. */
internal fun <T> libraryIncomingLayerStartAlpha(
    target: T?,
    cachedLayerTarget: T?,
    cachedLayerAlpha: Float,
    hasOutgoing: Boolean,
): Float = when {
    target == null || !hasOutgoing -> 1f
    cachedLayerTarget == target -> cachedLayerAlpha.coerceIn(0f, 1f)
    else -> 0f
}

/**
 * How a Library page change moves (motion audit 2026-09-28, batches B and C; the page tier in core-ui
 * [MimirPageMotion]).
 */
internal sealed interface LibraryPageMotion {
    /** The dissolve every other Library change keeps: the departing pixels fade on [LibraryOutgoingLayerDurationMs]. */
    data object Dissolve : LibraryPageMotion

    /**
     * No motion at all: the destination is the finished page at its first frame and the departing pixels are gone on the first beat.
     * For a launch return restoring the Library under the opaque return cover ([libraryPageRestoreSettled]), where a glide or a
     * dissolve would play out behind the cover's fade, or after it.
     */
    data object Settled : LibraryPageMotion

    /**
     * A page entry. The departing page holds still until [settle] returns or [MimirPageMotion.SettleCapMs] passes, then
     * fades and glides out on [MimirPageMotion.ExitMs]; the arriving page fades and glides in from the
     * [MimirPageMotion.LeadMs] lead on [MimirPageMotion.ArriveMs]. Presses wait until the arriving page shows.
     * With a [book], the opened platform's cover swings open over the departing page (motion audit batch E).
     */
    data class Entry(
        val direction: Int,
        val settle: (suspend () -> Unit)? = null,
        val book: LibraryBookLeaf? = null,
    ) : LibraryPageMotion

    /**
     * A return: the departing page glides back the way it came as it dissolves; the destination is at rest, unless
     * it is still leaving (B mid-glide), when it returns from where it was painted on [MimirPageMotion.ReturnMs].
     * With a [book], the closed platform's cover swings shut on the shelf under the dissolving page.
     */
    data class Return(
        val direction: Int,
        val book: LibraryBookLeaf? = null,
        /** The platform being closed; the cover plays only if the shelf's centred book is that platform's. */
        val bookPlatformId: String? = null,
    ) : LibraryPageMotion
}

/**
 * Immediate destination content plus one pixel-frozen, noninteractive outgoing layer.
 *
 * The destination is logical truth from the commit frame: it composes, registers its focus and owns semantics at once.
 * [motion] decides only how the two layers are drawn ([LibraryPageMotion]); the default is today's dissolve.
 */
@Composable
internal fun <T> ImmediateLibrarySnapshotTransition(
    targetState: T,
    transitionCancellationEpoch: Long = 0L,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    motion: (from: T, to: T) -> LibraryPageMotion = { _, _ -> LibraryPageMotion.Dissolve },
    probe: LibraryPageProbe? = null,
    content: @Composable (T) -> Unit,
) {
    val firstLayer = rememberGraphicsLayer()
    val secondLayer = rememberGraphicsLayer()
    val layers = remember(firstLayer, secondLayer) { listOf(firstLayer, secondLayer) }
    val epochs = remember { MimirTransitionEpochLedger(targetState) }
    // The target this composable presented before the CURRENT one -- read (never written) while computing
    // this transition's glass-return decision below, then advanced in the same SideEffect that commits the
    // epoch, so the read always sees the value from the PRIOR composition, never the one being committed now.
    val previousTargetStateHolder = remember { mutableStateOf(targetState) }
    val frame = remember(targetState) {
        epochs.prepare(targetState) { _, _ -> true }
    }
    val reducedMotion = mimirReducedMotion()
    val travelPx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.toPx() }

    // Draw-thread snapshots, not logical UI state. A rapid A→B→C transition starts each layer from the alpha and
    // travel that were actually painted, so no half-faded layer jumps back to full brightness or to its seat.
    val displayedLayerAlphas = remember {
        floatArrayOf(LibraryOutgoingLayerStartAlpha, 0f)
    }
    val displayedLayerTravel = remember { floatArrayOf(0f, 0f) }
    // What each layer last showed, and whether it is still on screen: kept in composition, not in draw, so a reversal
    // is recognised only onto a layer that has not finished leaving.
    val layerTargets = remember { mutableListOf<T?>(targetState, null) }
    val layerShowing = remember { booleanArrayOf(true, false) }
    val pageMotion = remember(frame) {
        if (frame.outgoingLayerIndex == null) LibraryPageMotion.Dissolve
        else motion(previousTargetStateHolder.value, targetState)
    }
    val entry = pageMotion as? LibraryPageMotion.Entry
    // Batch E: the opened platform's cover, swinging over the departing shelf. Reduced motion plays no book.
    val book = remember(frame) { entry?.book?.takeIf { !reducedMotion && it.sizeInRoot.width > 0f } }
    val bookTime = remember(frame) { Animatable(0f) }
    // The cover's fade has its own clock, started with the shelf's exit, so a long wait never fades it over a still
    // shelf and leaves the inner page alone in the ring.
    val bookFade = remember(frame) { Animatable(0f) }
    var bookShowing by remember(frame) { mutableStateOf(book != null) }
    // On the way back the
    // closed platform's cover swings shut on the shelf, UNDER the game list dissolving over it (the return grammar:
    // the page left dissolves over the page returned to), on the return clock, so the 160 ms return is unchanged and
    // nothing holds a press. The open's curve played backwards: pose() eases the swing, so a linear 1 -> 0 is the open
    // reversed, most of the swing visible in the second half as the list clears. It starts from the swing actually
    // painted (B during the open), and it plays only when the shelf's centred book is the platform closed.
    val closing = pageMotion as? LibraryPageMotion.Return
    val closeBook = remember(frame) { closing?.book?.takeIf { !reducedMotion && closing.bookPlatformId != null } }
    val displayedBookSwing = remember { floatArrayOf(1f) }
    val bookSwing = remember(frame) { Animatable(displayedBookSwing[0].coerceIn(0f, 1f)) }
    var closeShowing by remember(frame) { mutableStateOf(closeBook != null) }
    val hostOrigin = remember { floatArrayOf(0f, 0f) }
    // An entry's arriving page starts hidden on its side, unless this is a reversal onto a layer that is still on
    // screen, which carries on from where it was painted. A return reversal (B mid-glide) carries on the same way,
    // so no half-faded layer jumps back to full brightness or to its seat; any other return is at rest.
    val incomingStart = remember(frame) {
        val reversal = layerTargets[frame.primaryLayerIndex] == targetState && layerShowing[frame.primaryLayerIndex]
        when {
            reversal && (entry != null || pageMotion is LibraryPageMotion.Return) ->
                displayedLayerAlphas[frame.primaryLayerIndex] to displayedLayerTravel[frame.primaryLayerIndex]
            entry == null -> 1f to 0f
            else -> 0f to MimirPageMotion.arrivalTravelPx(entry.direction, travelPx)
        }
    }
    val incomingAlpha = remember(frame) { Animatable(incomingStart.first) }
    val incomingTravel = remember(frame) { Animatable(incomingStart.second) }
    val outgoingAlpha = remember(targetState, frame.outgoingLayerIndex) {
        Animatable(
            frame.outgoingLayerIndex
                ?.let(displayedLayerAlphas::get)
                ?.coerceIn(0f, 1f)
                ?: 0f,
        )
    }
    val outgoingTravel = remember(frame) {
        Animatable(frame.outgoingLayerIndex?.let(displayedLayerTravel::get) ?: 0f)
    }
    var outgoingVisible by remember(targetState, frame.outgoingLayerIndex) {
        mutableStateOf(frame.outgoingLayerIndex != null)
    }
    SideEffect {
        epochs.commit(frame)
        previousTargetStateHolder.value = targetState
        layerTargets[frame.primaryLayerIndex] = targetState
        layerShowing[frame.primaryLayerIndex] = true
    }
    // An entry is held until its page shows (touch shield, and the pad through MimirTransitionInputGate), settling
    // until its predecessor starts to leave, and arrived once it is at rest.
    var shielding by remember(frame) { mutableStateOf(entry != null) }
    var settling by remember(frame) { mutableStateOf(entry != null) }
    var arrived by remember(frame) { mutableStateOf(entry == null) }
    val gateOwner = remember(frame) { Any() }
    val transitionStartCancellationEpoch = remember(targetState, frame.epoch) {
        transitionCancellationEpoch
    }
    if (probe != null) {
        SideEffect {
            probe.incomingAlpha = { incomingAlpha.value }
            probe.incomingTravel = { incomingTravel.value }
            probe.outgoingAlpha = { if (outgoingVisible) outgoingAlpha.value else 0f }
            probe.outgoingTravel = { outgoingTravel.value }
            probe.arrived = { arrived }
            probe.settling = { settling }
            probe.bookShowing = { bookShowing }
            probe.bookTime = { bookTime.value }
            probe.bookFade = { bookFade.value }
            probe.bookClosing = { closeShowing }
            probe.bookSwing = { bookSwing.value }
        }
    }
    LaunchedEffect(targetState, frame.outgoingLayerIndex) {
        if (frame.outgoingLayerIndex == null) return@LaunchedEffect
        when (pageMotion) {
            LibraryPageMotion.Settled -> {
                // One beat for the destination's first composition and draw, then the departing pixels are gone: nothing animates.
                withFrameNanos { }
                outgoingAlpha.snapTo(0f)
            }
            LibraryPageMotion.Dissolve -> {
                // The destination is already logical truth. Hold only its predecessor's pixels for one complete
                // composition/draw beat so cold and warm surfaces obey the same pre-warm handoff.
                withFrameNanos { }
                outgoingAlpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(
                        durationMillis = LibraryOutgoingLayerDurationMs,
                        easing = MimirMotion.ModeTransitionEasing,
                    ),
                )
            }
            is LibraryPageMotion.Return -> {
                withFrameNanos { }
                val spec = tween<Float>(
                    durationMillis = if (reducedMotion) 0 else MimirPageMotion.ReturnMs,
                    easing = MimirMotion.ModeTransitionEasing,
                )
                coroutineScope {
                    if (closeBook != null) {
                        launch {
                            bookSwing.animateTo(
                                0f,
                                tween(
                                    (MimirPageMotion.ReturnMs * bookSwing.value).roundToInt(),
                                    easing = LinearEasing,
                                ),
                            )
                            closeShowing = false
                        }
                    }
                    launch {
                        outgoingTravel.animateTo(
                            MimirPageMotion.returnTravelPx(pageMotion.direction, travelPx),
                            spec,
                        )
                    }
                    if (incomingAlpha.value != 1f || incomingTravel.value != 0f) {
                        // A reversal: the destination was still leaving, so it comes back from where it was painted.
                        launch { incomingTravel.animateTo(0f, spec) }
                        launch { incomingAlpha.animateTo(1f, spec) }
                    }
                    outgoingAlpha.animateTo(0f, spec)
                }
            }
            is LibraryPageMotion.Entry -> {
                MimirTransitionInputGate.hold(gateOwner)
                try {
                    withFrameNanos { }
                    if (book != null) {
                        // One frame after the press, so the destination's first composition is over before the
                        // clock starts; the swing then plays while the covers settle.
                        launch { bookTime.animateTo(1f, tween(LibraryBookOpenMs, easing = LinearEasing)) }
                    }
                    val holdStartNs = System.nanoTime()
                    val settled = pageMotion.settle?.let { settle ->
                        withTimeoutOrNull(MimirPageMotion.SettleCapMs.toLong()) { settle() } != null
                    } ?: true
                    settling = false
                    // The wait may have ended late (a slow first frame, a late cap timer). The arrival still has its lead
                    // and its exit to cover before its first paint, whatever the hold had left (RG-2).
                    MimirTransitionInputGate.rearm(gateOwner, MimirPageMotion.LeadMs + MimirPageMotion.ExitMs)
                    runCatching {
                        android.util.Log.i(
                            "MimirSettle",
                            "waited=${(System.nanoTime() - holdStartNs) / 1_000_000}ms capped=${!settled}",
                        )
                    }
                    if (book != null) {
                        // The fade's clock starts with the shelf's exit: after a long wait the cover waits open and
                        // fades as the games glide in (Q3), never over a shelf that is still there.
                        launch {
                            bookFade.animateTo(1f, tween(LibraryBookOpenMs, easing = LinearEasing))
                            bookShowing = false
                        }
                    }
                    val exit = tween<Float>(
                        durationMillis = if (reducedMotion) 0 else MimirPageMotion.ExitMs,
                        easing = MimirMotion.Easing,
                    )
                    val arrive = tween<Float>(
                        durationMillis = if (reducedMotion) 0 else MimirPageMotion.ArriveMs,
                        delayMillis = if (reducedMotion) 0 else MimirPageMotion.LeadMs,
                        easing = MimirMotion.Easing,
                    )
                    coroutineScope {
                        launch {
                            outgoingTravel.animateTo(
                                MimirPageMotion.exitTravelPx(pageMotion.direction, travelPx),
                                exit,
                            )
                        }
                        launch { outgoingAlpha.animateTo(0f, exit) }
                        launch { incomingTravel.animateTo(0f, arrive) }
                        incomingAlpha.animateTo(1f, arrive) {
                            // The new page shows: presses and touches reach it from this frame.
                            if (value > 0f && shielding) {
                                shielding = false
                                MimirTransitionInputGate.release(gateOwner)
                            }
                        }
                    }
                } finally {
                    shielding = false
                    MimirTransitionInputGate.release(gateOwner)
                }
                arrived = true
            }
        }
        if (epochs.acceptsCompletion(frame.epoch)) {
            outgoingVisible = false
            frame.outgoingLayerIndex?.let { layerShowing[it] = false }
        }
    }
    LaunchedEffect(transitionCancellationEpoch, frame.epoch) {
        if (transitionCancellationEpoch == transitionStartCancellationEpoch) return@LaunchedEffect
        if (entry != null) {
            // every Library press and touch counts as an interaction, so an entry never
            // snaps on one. While its page is hidden the press waits (Back and the shoulders reverse it by changing
            // the target); once the page shows it keeps its whole arrival and the book its swing. Only the
            // departing pixels go.
            if (shielding) return@LaunchedEffect
            outgoingAlpha.snapTo(0f)
            if (epochs.acceptsCompletion(frame.epoch)) {
                outgoingVisible = false
                frame.outgoingLayerIndex?.let { layerShowing[it] = false }
            }
            return@LaunchedEffect
        }
        outgoingAlpha.snapTo(0f)
        incomingAlpha.snapTo(1f)
        incomingTravel.snapTo(0f)
        shielding = false
        settling = false
        arrived = true
        bookShowing = false
        closeShowing = false
        MimirTransitionInputGate.release(gateOwner)
        if (epochs.acceptsCompletion(frame.epoch)) {
            outgoingVisible = false
            frame.outgoingLayerIndex?.let { layerShowing[it] = false }
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val origin = coordinates.localToRoot(Offset.Zero)
            hostOrigin[0] = origin.x
            hostOrigin[1] = origin.y
        },
        contentAlignment = contentAlignment,
    ) {
        val primaryLayer = layers[frame.primaryLayerIndex]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    val primaryContent = this
                    primaryLayer.alpha = 1f
                    primaryLayer.record { primaryContent.drawContent() }
                    val alpha = incomingAlpha.value
                    val travel = incomingTravel.value
                    primaryLayer.alpha = alpha
                    primaryLayer.translationX = travel
                    displayedLayerAlphas[frame.primaryLayerIndex] = alpha
                    displayedLayerTravel[frame.primaryLayerIndex] = travel
                    drawLayer(primaryLayer)
                    // The cover swinging shut on the shelf, with the open book beneath it hiding the shelf's
                    // own closed copy, both at the shelf's alpha and travel; the dissolving game list is drawn over it.
                    // Decided here, after the shelf re-recorded its centred book this frame.
                    if (closeBook != null && closeShowing && closeBook.readyFor(closing?.bookPlatformId)) {
                        val origin = Offset(hostOrigin[0], hostOrigin[1])
                        translate(left = travel) { drawLibraryBookPage(closeBook, origin, alpha) }
                        drawLibraryBookCover(
                            leaf = closeBook,
                            origin = origin,
                            timeFraction = bookSwing.value,
                            fadeFraction = 0f,
                            travelPx = travel,
                            alphaScale = alpha,
                        )
                        displayedBookSwing[0] = bookSwing.value
                    }
                },
            contentAlignment = contentAlignment,
        ) {
            CompositionLocalProvider(
                LocalMimirPageArrived provides arrived,
                LocalMimirPageSettling provides { settling },
            ) {
                content(targetState)
            }
        }

        val outgoingLayerIndex = frame.outgoingLayerIndex
        if (outgoingVisible && outgoingLayerIndex != null) {
            val outgoingLayer = layers[outgoingLayerIndex]
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .then(if (shielding) Modifier.libraryPageTouchShield() else Modifier)
                    .drawWithContent {
                        val alpha = outgoingAlpha.value
                        val travel = outgoingTravel.value
                        outgoingLayer.alpha = alpha
                        outgoingLayer.translationX = travel
                        displayedLayerAlphas[outgoingLayerIndex] = alpha
                        displayedLayerTravel[outgoingLayerIndex] = travel
                        drawLayer(outgoingLayer)
                        if (book != null) {
                            // The open book under the swinging cover hides the shelf's own copy of the closed one.
                            translate(left = travel) {
                                drawLibraryBookPage(book, Offset(hostOrigin[0], hostOrigin[1]), alpha)
                            }
                        }
                    },
            )
        }
        if (book != null && bookShowing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .drawWithContent {
                        drawLibraryBookCover(
                            leaf = book,
                            origin = Offset(hostOrigin[0], hostOrigin[1]),
                            timeFraction = bookTime.value,
                            fadeFraction = bookFade.value,
                            travelPx = outgoingTravel.value,
                        )
                        // Where the swing stood, so B during the open closes from there.
                        displayedBookSwing[0] = bookTime.value
                    },
            )
            // The page beneath takes touches once it shows, but the cover can outlive that by up to 230 ms: where it
            // still stands a tap reaches nothing, so it never opens the row the cover hides.
            Box(
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .libraryBookCoverTouchShield(book, hostOrigin) { outgoingTravel.value },
            )
        }
    }
}

/** A test seam: the host's channels, read live on a paused clock. Production passes none. */
internal class LibraryPageProbe {
    var incomingAlpha: () -> Float = { 1f }
    var incomingTravel: () -> Float = { 0f }
    var outgoingAlpha: () -> Float = { 0f }
    var outgoingTravel: () -> Float = { 0f }
    var arrived: () -> Boolean = { true }
    var settling: () -> Boolean = { false }
    var bookShowing: () -> Boolean = { false }
    var bookTime: () -> Float = { 0f }
    var bookFade: () -> Float = { 0f }
    var bookClosing: () -> Boolean = { false }
    var bookSwing: () -> Float = { 0f }
}

/** While a page change hides its new page, a touch on the old one's pixels reaches nothing. */
private fun Modifier.libraryPageTouchShield(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    }
}

/** Only over the swinging cover's own bounds ([libraryBookCoverBounds]): the rest of the arriving page is live. */
private fun Modifier.libraryBookCoverTouchShield(
    leaf: LibraryBookLeaf,
    hostOrigin: FloatArray,
    travelPx: () -> Float,
): Modifier = layout { measurable, constraints ->
    val bounds = libraryBookCoverBounds(leaf, Offset(hostOrigin[0], hostOrigin[1]), travelPx())
    val placeable = measurable.measure(
        Constraints.fixed(bounds.width.roundToInt().coerceAtLeast(0), bounds.height.roundToInt().coerceAtLeast(0)),
    )
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else bounds.right.roundToInt().coerceAtLeast(0)
    val height = if (constraints.hasBoundedHeight) constraints.maxHeight else bounds.bottom.roundToInt().coerceAtLeast(0)
    layout(width, height) { placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt()) }
}.libraryPageTouchShield()

/**
 * The gamelist's settle gate (motion audit batch B): the covers the destination paints first
 * ([libraryGamelistSettleRows]), decoded through the one cover request the cards themselves use, so they paint warm.
 * Nothing to paint, nothing to wait for. The host caps the wait at [MimirPageMotion.SettleCapMs]; past it the page
 * shows and late covers keep their fade.
 */
internal suspend fun awaitLibraryGamelistSettled(context: Context, viewModel: LibraryViewModel, compact: Boolean) {
    // What the destination shows first, by the view it opens in ([libraryGamelistSettleCoverUri]): the shelf's face for CAROUSEL, the list
    // preview's ladder for LIST (they differ where a disc stands in for a missing cover), so the warmed cover is the painted one.
    val rows = libraryGamelistSettleRows(viewModel, compact)
    val listView = libraryGamelistOpensAsList(
        favorites = viewModel.openPlatform.value == LibraryViewModel.FAVORITES_PLATFORM_ID,
        mode = viewModel.gamelistViewMode.value,
    )
    val uris = rows.mapNotNull { libraryGamelistSettleCoverUri(it, listView) }
    if (uris.isEmpty()) return
    val loader = context.imageLoader
    coroutineScope {
        uris.map { uri -> async { loader.execute(mimirCoverRequest(context, uri)) } }.awaitAll()
    }
}

/**
 * The rows whose covers the opening page paints first. A list that has landed empty, and Favorites' Android segments
 * (package rows, no ROM covers), give none at once: the input gate must not sit out the cap for them. The rows are the
 * open platform's as truly loaded ([LibraryViewModel.visibleGamesFor]), followed live ([awaitLibraryGamelistRows]),
 * so an L2/R2 switch inside the hold re-targets the wait. The carousel paints the landing card and two either side;
 * the list (and Favorites' Games list) paints one cover, in its preview panel, and none where compact drops the panel
 * ([gamelistListShowsPreviewPanel]). The panel's game is approximated by the remembered landing, else the first row.
 */
internal suspend fun libraryGamelistSettleRows(viewModel: LibraryViewModel, compact: Boolean): List<GameRow> {
    val opening = viewModel.openPlatform.value ?: return emptyList()
    if (!libraryGamelistSettleWarmsCovers(opening, viewModel.favoriteSegment.value)) return emptyList()
    val (platform, rows) = awaitLibraryGamelistRows(viewModel.openPlatform, viewModel.visibleGamesFor)
        ?: return emptyList()
    // L2/R2 on Favorites cycles its segment inside the hold: ask again of what landed.
    if (!libraryGamelistSettleWarmsCovers(platform, viewModel.favoriteSegment.value)) return emptyList()
    val favorites = platform == LibraryViewModel.FAVORITES_PLATFORM_ID
    return libraryGamelistSettleCovers(
        rows = rows,
        landing = if (favorites) null else viewModel.rememberedGamelistFocusFor(platform, viewModel.favoritesOnly.value),
        listView = libraryGamelistOpensAsList(favorites, viewModel.gamelistViewMode.value),
        previewPanel = gamelistListShowsPreviewPanel(compact),
    )
}

/** Whether the gamelist opens as the LIST: the player's view, and always for Favorites' Games list. */
internal fun libraryGamelistOpensAsList(favorites: Boolean, mode: GamelistViewMode): Boolean =
    favorites || mode == GamelistViewMode.LIST

/**
 * The cover a gamelist paints first for [game], by view: the shelf's face ([shelfFaceOf]: no disc stands in for a missing cover) in
 * CAROUSEL, the list preview's ladder ([libraryListPreviewMedia]: real art of either kind outranks the book) in LIST. Null when the
 * view paints no picture for the game.
 */
internal fun libraryGamelistSettleCoverUri(game: GameRow, listView: Boolean): String? =
    if (listView) libraryListPreviewMedia(game).artUri else shelfFaceOf(game).uri

/** The settle's covers for a view: the carousel's centre window, or the list panel's one cover, or none. */
internal fun libraryGamelistSettleCovers(
    rows: List<GameRow>,
    landing: GameId?,
    listView: Boolean,
    previewPanel: Boolean,
): List<GameRow> = when {
    !listView -> libraryGamelistSettleWindow(rows, landing)
    !previewPanel -> emptyList()
    else -> listOfNotNull(rows.firstOrNull { it.gameId == landing } ?: rows.firstOrNull())
}

/**
 * The first rows truly loaded for the platform that is open NOW, with that platform, empty included (an empty list is
 * an answer, not a wait); null once none is open. It follows [openPlatform] rather than the platform open when the
 * wait began: L2/R2 pass the page-change gate, so a switch inside the hold must re-target the wait, not leave it
 * matching a platform that no longer shows until the cap. [visible] pairs the rows with the platform they were queried
 * for in one emission, so the previous page's list is never taken for this one's.
 */
internal suspend fun awaitLibraryGamelistRows(
    openPlatform: Flow<PlatformId?>,
    visible: Flow<LibraryVisibleGames>,
): Pair<PlatformId, List<GameRow>>? {
    val settled = combine(openPlatform, visible) { open, shown ->
        when {
            open == null -> LibraryGamelistRowsWait.Closed
            shown.loadedFor == open -> LibraryGamelistRowsWait.Loaded(open, shown.rows)
            else -> LibraryGamelistRowsWait.Pending
        }
    }.first { it !is LibraryGamelistRowsWait.Pending }
    return (settled as? LibraryGamelistRowsWait.Loaded)?.let { it.platform to it.rows }
}

private sealed interface LibraryGamelistRowsWait {
    data object Pending : LibraryGamelistRowsWait
    data object Closed : LibraryGamelistRowsWait
    data class Loaded(val platform: PlatformId, val rows: List<GameRow>) : LibraryGamelistRowsWait
}

/** Only a gamelist of ROM rows has covers to warm; Favorites' Android segments show package rows. */
internal fun libraryGamelistSettleWarmsCovers(platform: PlatformId, segment: FavoriteSegment): Boolean =
    platform != LibraryViewModel.FAVORITES_PLATFORM_ID || segment == FavoriteSegment.GAMES

/**
 * The cards a landing shows first: the landing card and three either side, every card the carousel paints (regression: at two, the outer pair always arrived as flat seats and faded in after the page).
 */
internal fun libraryGamelistSettleWindow(rows: List<GameRow>, landing: GameId?): List<GameRow> {
    if (rows.isEmpty()) return emptyList()
    val centre = rows.indexOfFirst { it.gameId == landing }.takeIf { it >= 0 } ?: 0
    return rows.subList(
        (centre - LibraryGamelistSettleReach).coerceAtLeast(0),
        (centre + LibraryGamelistSettleReach + 1).coerceAtMost(rows.size),
    )
}

internal const val LibraryGamelistSettleReach = 3

/**
 * Library-owned detail and modal surfaces use the same two-layer, latest-wins fade as root overlays.
 * Only [targetState] remains composed and focusable; the predecessor survives as non-semantic pixels.
 */
@Composable
internal fun <T> LibraryOverlayPixelFadeTransition(
    targetState: T?,
    transitionCancellationEpoch: Long = 0L,
    modifier: Modifier = Modifier,
    initialArrivalFade: Boolean = false,
    /** The glass the whole layer wears behind a modal, and the request that holds the card still under it (Q5); null for a host with no glass of its own. */
    glass: LibraryCardGlass? = null,
    /**
     * The state whose [content] is kept composed while there is no [targetState] (the OPEN WITH sheet, built ahead: see [LibraryStandbySlot]); null for every other surface.
     * It is the same call as the target's below, so the arrival finds the composition already there. The content is responsible for showing nothing until it is the target.
     */
    standby: T? = null,
    content: @Composable (T) -> Unit,
) {
    val firstLayer = rememberGraphicsLayer()
    val secondLayer = rememberGraphicsLayer()
    val layers = remember(firstLayer, secondLayer) { listOf(firstLayer, secondLayer) }
    val reducedMotion = mimirReducedMotion()
    val displayedLayerAlphas = remember {
        floatArrayOf(if (targetState != null && !initialArrivalFade) 1f else 0f, 0f)
    }
    val layerTargets = remember { mutableListOf<T?>(targetState, null) }
    val epochs = remember { MimirTransitionEpochLedger<T?>(targetState) }
    val frame = remember(targetState) {
        epochs.prepare(targetState) { from, to -> from != to }
    }
    SideEffect { epochs.commit(frame) }

    val incomingStartAlpha = libraryIncomingLayerStartAlpha(
        target = targetState,
        cachedLayerTarget = layerTargets[frame.primaryLayerIndex],
        cachedLayerAlpha = displayedLayerAlphas[frame.primaryLayerIndex],
        hasOutgoing = frame.outgoingLayerIndex != null,
    ).let { startAlpha ->
        if (initialArrivalFade && targetState != null && frame.outgoingLayerIndex == null) 0f else startAlpha
    }
    val incomingAlpha = remember(targetState, frame.epoch) { Animatable(incomingStartAlpha) }
    val outgoingAlpha = remember(targetState, frame.epoch) {
        Animatable(
            frame.outgoingLayerIndex
                ?.let(displayedLayerAlphas::get)
                ?.coerceIn(0f, 1f)
                ?: 0f,
        )
    }
    var outgoingVisible by remember(targetState, frame.epoch) {
        mutableStateOf(frame.outgoingLayerIndex != null)
    }
    val transitionStartCancellationEpoch = remember(targetState, frame.epoch) {
        transitionCancellationEpoch
    }
    LaunchedEffect(targetState, frame.epoch) {
        if (targetState != null && incomingAlpha.value < 1f) {
            // a cold arrival holds presses until it first paints, and lets go if it is cancelled first.
            if (incomingAlpha.value <= 0f) holdMimirInputUntilShown(incomingAlpha) { incomingAlpha.value > 0f }
            if (initialArrivalFade) withFrameNanos { }
            // Batch F : Search, a game's detail and the modals arrive on the page clock, 540 ms
            // from the 130 ms lead, and still leave on OverlayMs. Only a cold arrival takes the lead. Reduced motion
            // snaps.
            incomingAlpha.animateTo(
                1f,
                if (reducedMotion) {
                    snap()
                } else {
                    tween(
                        MimirMotion.ShineMs,
                        delayMillis = if (incomingAlpha.value <= 0f) MimirMotion.OverlayDissolveCoverMs else 0,
                        easing = MimirMotion.Easing,
                    )
                },
            )
        }
    }
    LaunchedEffect(targetState, frame.epoch, frame.outgoingLayerIndex) {
        if (frame.outgoingLayerIndex == null) return@LaunchedEffect
        outgoingAlpha.animateTo(
            0f,
            tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing),
        )
        if (epochs.acceptsCompletion(frame.epoch)) outgoingVisible = false
    }
    LaunchedEffect(transitionCancellationEpoch, frame.epoch) {
        if (transitionCancellationEpoch == transitionStartCancellationEpoch) return@LaunchedEffect
        if (targetState != null) incomingAlpha.snapTo(1f)
        outgoingAlpha.snapTo(0f)
        if (epochs.acceptsCompletion(frame.epoch)) outgoingVisible = false
    }

    // The glass over the whole layer (a picker over the card) holds the card as a snapshot once the card has arrived and nothing is leaving it (Q5): a card
    // still fading in is not a still image yet.
    val outgoingVisibleNow = rememberUpdatedState(outgoingVisible)
    // Core's one glass overload (frozen at the glass's peak, thawed on the first clearing frame), built once: its lambdas are the modifier's identity.
    val cardGlassModifier = remember(glass, incomingAlpha) {
        if (glass == null) {
            Modifier
        } else {
            Modifier.mimirGlassBackdropProgress(
                maxRadius = { MimirGlass.backdropBlurRadius },
                progress = glass.progress,
                snapshot = { libraryCardSnapshotWanted(glass.snapshot(), incomingAlpha.value >= 1f, outgoingVisibleNow.value) },
            )
        }
    }
    Box(modifier.then(cardGlassModifier).fillMaxSize()) {
        val primaryLayer = layers[frame.primaryLayerIndex]
        Box(
            Modifier
                .fillMaxSize()
                .drawWithContent {
                    val primaryContent = this
                    primaryLayer.alpha = 1f
                    primaryLayer.record { primaryContent.drawContent() }
                    val alpha = incomingAlpha.value
                    primaryLayer.alpha = alpha
                    displayedLayerAlphas[frame.primaryLayerIndex] = alpha
                    layerTargets[frame.primaryLayerIndex] = targetState
                    drawLayer(primaryLayer)
                },
        ) {
            val shown = targetState ?: standby
            if (shown != null) content(shown)
        }

        val outgoingLayerIndex = frame.outgoingLayerIndex
        if (outgoingVisible && outgoingLayerIndex != null) {
            val outgoingLayer = layers[outgoingLayerIndex]
            Box(
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .drawWithContent {
                        val alpha = outgoingAlpha.value
                        outgoingLayer.alpha = alpha
                        displayedLayerAlphas[outgoingLayerIndex] = alpha
                        drawLayer(outgoingLayer)
                    },
            )
        }
    }
}

/*
 * LibraryScreen was one composable of about 12,400 dex code units. ART's optimizing compiler refuses any
 * method of 16,383 units or more, and a method that grows toward that line has also broken the dex verifier
 * before it got there, so the screen's state and effect wiring now lives in the four non-restartable pieces
 * below and its trailing modal and hint layer in one more. LibraryScreen calls each of them unconditionally,
 * in the original order. A piece that makes state returns a small holder of the very same State and
 * MutableState objects, which the next piece re-binds under their old names, so remember and effect calls,
 * keys, scopes and lambda captures are the ones they always were. A name ending in State is a live state object.
 */

private class LibraryScreenFoundation(
    val platformsState: State<List<PlatformRow>>,
    val openPlatformState: State<PlatformId?>,
    val rememberedShelfKeysByModeState: MutableState<Map<LibraryShelfMode, FocusKey>>,
    val pendingGamelistLandingState: MutableState<PendingGamelistLanding?>,
    val loadedGamesState: State<List<GameRow>>,
    val gamesLoadedForPlatformState: State<PlatformId?>,
    val detailGameState: State<GameRow?>,
    val librarySearchSessionState: State<LibrarySearchSession?>,
    val renderedDetailState: LibraryDetailActionState?,
    val presentedDetailChromeState: MutableState<Boolean>,
    val presentedSearchChromeState: MutableState<Boolean>,
)

/** First piece of the screen: the platform and landing memory, and the detail and search chrome holds. */
@Composable
private fun rememberLibraryScreenFoundation(
    viewModel: LibraryViewModel,
    authority: FocusAuthority,
    detailActionState: LibraryDetailActionState?,
    transitionCancellationEpoch: Long,
): LibraryScreenFoundation {
    val platformsState = viewModel.platforms.collectAsState()
    val openPlatformState = viewModel.openPlatform.collectAsState()
    val openPlatform by openPlatformState
    // One content-tile memory per shelf mode. This lives above the shelf/gamelist branch so every mode keeps both
    // its focus landing and visual centre while another mode or a gamelist is open. Add is deliberately excluded:
    // returning from a modal should recover the last real tile, not park the coverflow on a header control.
    val rememberedShelfKeysByModeState = remember(viewModel) {
        // This is a one-shot visual seed, not a second focus authority. The ViewModel outlives destination
        // composition, so Library -> Home -> Library can paint its retained centre before the virtual row
        // registers and FocusAuthority restores the same key. Live focus remains the first-precedence input.
        mutableStateOf(
            initialShelfModeFocusMemory(
                shelfMode = viewModel.shelfMode.value,
                centeredKey = viewModel.centeredShelfKey.value,
            ),
        )
    }
    var rememberedShelfKeysByMode by rememberedShelfKeysByModeState
    // `pushScope(GamelistScope)` can briefly land on the first still-registered game and overwrite the
    // authority's scope memory before the reopened ring registers. Snapshot that memory while the shelf owns
    // the screen, then consume it exactly once on the next shelf -> gamelist open.
    var lastOpenPlatform by remember(authority) { mutableStateOf<PlatformId?>(null) }
    var rememberedGamelistFocusOnShelf by remember(authority) {
        mutableStateOf(authority.rememberedFocusIn(GamelistScope))
    }
    val pendingGamelistLandingState = remember(authority) {
        mutableStateOf<PendingGamelistLanding?>(null)
    }
    var pendingGamelistLanding by pendingGamelistLandingState
    // Keep the coverflow's return-centre synced to the platform being VIEWED — including after an L/R platform
    // switch inside the gamelist — so returning to the shelf lands on it with no tile-0/opened-from flash
    // (return jank; complements the DefaultFocusAuthority.rememberFocusForScope fix).
    LaunchedEffect(openPlatform) {
        val viewed = openPlatform
        if (viewed != null && viewed != LibraryViewModel.FAVORITES_PLATFORM_ID) {
            val viewedKey = platformKey(viewed)
            if (rememberedShelfKeysByMode[LibraryShelfMode.EMULATION] != viewedKey) {
                rememberedShelfKeysByMode = rememberedShelfKeysByMode +
                    (LibraryShelfMode.EMULATION to viewedKey)
            }
        }
    }
    // Arm one landing per genuine open or in-gamelist platform switch.
    //
    // A shelf reopen carries the remembered gamelist centre captured above. **A platform switch now carries
    // the DESTINATION's own remembered game**, where it used to carry null and land on tile one.
    //
    // The memory was never missing. `gamelistFocusMemory` is already keyed by
    // `GamelistFocusLane(platform, favoritesOnly)` and is already written on every focus move. This one
    // path — the path that most needs it — opted out, in a comment, with no pin holding it there. The
    // fallback `authority.rememberedFocusIn(GamelistScope)` is a SINGLE slot shared by every platform, so
    // on A→B→A it holds B's key, which is not registered in A's ring: focus falls through to tile one, and
    // then the per-lane memory moves it again a frame later. **Two focus events for one press — the
    // reported resettle.** Seeding the landing correctly makes it one.
    //
    // Resolution below consumes this request, so later recompositions cannot fight navigation.
    LaunchedEffect(openPlatform) {
        val previous = lastOpenPlatform
        val current = openPlatform
        lastOpenPlatform = current
        if (current == null) {
            rememberedGamelistFocusOnShelf = authority.rememberedFocusIn(GamelistScope)
            pendingGamelistLanding = null
        } else if (current != LibraryViewModel.FAVORITES_PLATFORM_ID) {
            pendingGamelistLanding = PendingGamelistLanding(
                platformId = current,
                rememberedFocus = if (previous == null) {
                    rememberedGamelistFocusOnShelf
                } else {
                    // An in-gamelist switch. `_openPlatform` is already the destination here, so this
                    // reads the destination's OWN lane, not the one being left.
                    viewModel.rememberedGamelistFocus(viewModel.favoritesOnly.value)
                        ?.let(::gameKey)
                },
            )
        } else {
            pendingGamelistLanding = null
        }
    }
    val loadedGamesState = viewModel.games.collectAsState()
    val gamesLoadedForPlatformState = viewModel.gamesLoadedForPlatform.collectAsState()
    val detailGameState = viewModel.detailGame.collectAsState()
    val openGameDetail by viewModel.openGameDetail.collectAsState()
    val librarySearchSessionState = viewModel.librarySearchSession.collectAsState()
    val librarySearchSession by librarySearchSessionState
    val renderedDetailState = detailActionState
        ?: openGameDetail?.let { LibraryDetailActionState(LibraryDetailTarget.Emulated(it), isOnShelf = false) }
    // Library's launch card is a sibling of the gamelist rather than an app-root overlay. Give its scene the
    // same liquid-glass ownership contract as Home: only the destination is blurred and stripped of semantics,
    // while the card remains crisp. Retain that chrome through the outgoing pixel dissolve so Back never exposes
    // a sharp gamelist underneath a still-fading card.
    val presentedDetailChromeState = remember { mutableStateOf(renderedDetailState != null) }
    var presentedDetailChrome by presentedDetailChromeState
    var detailChromeDismissStartEpoch by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(renderedDetailState) {
        if (renderedDetailState != null) {
            presentedDetailChrome = true
            detailChromeDismissStartEpoch = null
        } else {
            val startEpoch = transitionCancellationEpoch
            detailChromeDismissStartEpoch = startEpoch
            // The host's own hold (130 ms, where the card is at alpha 0.044), on the animation clock. It was 180 ms on a
            // coroutine `delay`, so the list stayed blurred for about 50 ms after the card was nearly gone (alpha audit
            // F6), and under a slowed animator the hold ran out before the card's fade did (N3).
            mimirHold(MimirMotion.OverlayDissolveCoverMs)
            if (detailChromeDismissStartEpoch == startEpoch) {
                presentedDetailChrome = false
                detailChromeDismissStartEpoch = null
            }
        }
    }
    LaunchedEffect(transitionCancellationEpoch) {
        val startEpoch = detailChromeDismissStartEpoch ?: return@LaunchedEffect
        if (transitionCancellationEpoch != startEpoch) {
            presentedDetailChrome = false
            detailChromeDismissStartEpoch = null
        }
    }
    // Search uses the same scene-blur ownership contract. Retain it through the outgoing pixel fade so the
    // background cannot sharpen under a still-visible glass page; Search→Detail keeps one continuous blur.
    val presentedSearchChromeState = remember { mutableStateOf(librarySearchSession != null) }
    var presentedSearchChrome by presentedSearchChromeState
    var searchChromeDismissStartEpoch by remember { mutableStateOf<Long?>(null) }
    val searchChromeRequested = librarySearchSession != null
    LaunchedEffect(searchChromeRequested) {
        if (searchChromeRequested) {
            presentedSearchChrome = true
            searchChromeDismissStartEpoch = null
        } else {
            val startEpoch = transitionCancellationEpoch
            searchChromeDismissStartEpoch = startEpoch
            // As for the card above: the host's hold, on the animation clock (alpha audit F6, N3).
            mimirHold(MimirMotion.OverlayDissolveCoverMs)
            if (searchChromeDismissStartEpoch == startEpoch) {
                presentedSearchChrome = false
                searchChromeDismissStartEpoch = null
            }
        }
    }
    LaunchedEffect(transitionCancellationEpoch) {
        val startEpoch = searchChromeDismissStartEpoch ?: return@LaunchedEffect
        if (transitionCancellationEpoch != startEpoch) {
            presentedSearchChrome = false
            searchChromeDismissStartEpoch = null
        }
    }

    return LibraryScreenFoundation(
        platformsState = platformsState,
        openPlatformState = openPlatformState,
        rememberedShelfKeysByModeState = rememberedShelfKeysByModeState,
        pendingGamelistLandingState = pendingGamelistLandingState,
        loadedGamesState = loadedGamesState,
        gamesLoadedForPlatformState = gamesLoadedForPlatformState,
        detailGameState = detailGameState,
        librarySearchSessionState = librarySearchSessionState,
        renderedDetailState = renderedDetailState,
        presentedDetailChromeState = presentedDetailChromeState,
        presentedSearchChromeState = presentedSearchChromeState,
    )
}

private class LibraryScreenModelState(
    val launchState: GameLaunchUiState,
    val launchStateOwner: GameId?,
    val launchEmulatorLabel: String?,
    val openWithState: State<OpenWithOptions?>,
    val favoritesCountState: State<Int>,
    val favoriteSegmentState: State<FavoriteSegment>,
    val shelfModeState: State<LibraryShelfMode>,
    val searchQueryState: State<String>,
    val searchCriteriaState: State<LibrarySearchCriteria>,
    val searchFacetsState: State<LibrarySearchFacets>,
    val librarySearchPickerState: State<LibrarySearchPicker?>,
    val favoritesOnlyState: State<Boolean>,
    val pendingRemovalsState: State<PendingLibraryRemovals>,
    val games: List<GameRow>,
    /** The keys of [games], made once per list (RC-45). */
    val gameKeys: GamelistKeyIndex,
    /** True while a platform-open landing is armed for the open platform and the page is not empty: the book is about to take focus, so the Search pill must not wear a ring meanwhile. */
    val platformLandingPending: Boolean,
    val gamelistViewModeState: State<GamelistViewMode>,
    val gamelistPlatformSwitchMotionState: State<GamelistPlatformSwitchMotion?>,
    val platformSwitchReleased: MutableState<Long?>,
    val openPlatformHasFavoritesState: State<Boolean>,
    val keyboardOpenState: State<Boolean>,
    val hintLegendRequestState: State<Int>,
    val pendingHideGameState: State<GameRow?>,
    val focusStateState: State<FocusState>,
    /** The authority's state in the three forms the screen reads it (see [LibraryHostFocus]); [focusStateState] is its `host`. */
    val hostFocus: LibraryHostFocus,
    val liveDetailGame: GameRow?,
    val matchingFilterLanding: PendingGamelistFilterLanding?,
    val laneRememberedFocus: FocusKey?,
    val renderedEmulatedGamelistFocus: FocusKey?,
    val centeredShelfKeyForSeedState: State<FocusKey?>,
)

/** Second piece: everything the screen collects from the view model, and the effects that land focus in the gamelist. */
@Composable
private fun rememberLibraryScreenModelState(
    viewModel: LibraryViewModel,
    authority: FocusAuthority,
    foundation: LibraryScreenFoundation,
): LibraryScreenModelState {
    // Values the first piece owns, re-bound under their original names so the code below is unchanged.
    val openPlatform by foundation.openPlatformState
    var rememberedShelfKeysByMode by foundation.rememberedShelfKeysByModeState
    var pendingGamelistLanding by foundation.pendingGamelistLandingState
    val loadedGames by foundation.loadedGamesState
    val gamesLoadedForPlatform by foundation.gamesLoadedForPlatformState
    val detailGame by foundation.detailGameState

    val launchUiSnapshot by viewModel.launchUiSnapshot.collectAsState()
    val launchState = launchUiSnapshot.state
    val launchStateOwner = launchUiSnapshot.ownerGameId
    val launchEmulatorLabel = launchUiSnapshot.emulatorLabel
    val openWithState = viewModel.openWith.collectAsState()
    val favoritesCountState = viewModel.favoritesCount.collectAsState()
    val favoriteSegmentState = viewModel.favoriteSegment.collectAsState()
    val shelfModeState = viewModel.shelfMode.collectAsState()
    val shelfMode by shelfModeState
    val searchQueryState = viewModel.searchQuery.collectAsState()
    val searchCriteriaState = viewModel.searchCriteria.collectAsState()
    val searchFacetsState = viewModel.availableSearchFacets.collectAsState()
    val librarySearchPickerState = viewModel.librarySearchPicker.collectAsState()
    val favoritesOnlyState = viewModel.favoritesOnly.collectAsState()
    val favoritesOnly by favoritesOnlyState
    // Walk 32g: what the player has sent out of a list, applied HERE and not only in the ViewModel's own list. That list is
    // computed off the main thread and reaches this frame a beat after the focus hand-off, and for that beat the screen would
    // draw the old list around a focus that already names the survivor (the fall-through to game one, the observed jitter). The pending set and the focus both arrive through StateFlows collected here, so the frame that drops the game is
    // the frame the focus is on the card that stays. Nothing pending returns the very same list.
    val pendingRemovalsState = viewModel.pendingRemovals.collectAsState()
    val pendingRemovals by pendingRemovalsState
    val games = remember(loadedGames, pendingRemovals, openPlatform, favoritesOnly) {
        pendingRemovals.applyTo(loadedGames, openPlatform, favoritesOnly)
    }
    // The keys of the list the screen shows, made once per list and asked in constant time by every focus step (audit 2026-10-14, RC-45).
    val gameKeys = rememberGamelistKeyIndex(games)
    val gamelistViewModeState = viewModel.gamelistViewMode.collectAsState()
    val pendingGamelistFilterLandingState = viewModel.pendingGamelistFilterLanding.collectAsState()
    val pendingGamelistFilterLanding by pendingGamelistFilterLandingState
    val gamelistPlatformSwitchMotionState = viewModel.gamelistPlatformSwitchMotion.collectAsState()
    // The L2/R2 switch whose departing list was last released: the hero rides the switch.
    val platformSwitchReleased = remember { mutableStateOf<Long?>(null) }
    val openPlatformHasFavoritesState = viewModel.openPlatformHasFavorites.collectAsState()
    val keyboardOpenState = viewModel.keyboardOpen.collectAsState()
    val hintLegendRequestState = viewModel.hintLegendRequests.collectAsState()
    val pendingHideGameState = viewModel.pendingHideGame.collectAsState()
    val hostFocus = rememberLibraryHostFocus(authority)
    val focusStateState = hostFocus.host
    val focusState by focusStateState

    val liveDetailGame = reconcileLiveGamelistDetail(
        retained = detailGame,
        games = games,
        loadedTruth = openPlatform != null && gamesLoadedForPlatform == openPlatform,
    )
    val matchingFilterLanding = pendingGamelistFilterLanding?.takeIf { pending ->
        pending.platformId == openPlatform && pending.favoritesOnly == favoritesOnly
    }
    val matchingPlatformLanding = pendingGamelistLanding?.takeIf { it.platformId == openPlatform }
    val laneRememberedFocus = if (matchingPlatformLanding == null) {
        viewModel.rememberedGamelistFocus(favoritesOnly)?.let(::gameKey)
    } else {
        null
    }
    val renderedEmulatedGamelistFocus = openPlatform?.let {
        gamelistRenderedFocus(
            gameKeys = gameKeys.keySet,
            focused = focusState.focused,
            scopeOwnsGamelist = focusState.scope == GamelistScope,
            rememberedFocus = laneRememberedFocus ?: authority.rememberedFocusIn(GamelistScope),
            pendingRememberedFocus = pendingGamelistLanding?.rememberedFocus,
            retainedGameFocus = liveDetailGame?.let(::gameKey),
            pendingFilterFocus = matchingFilterLanding?.targetGameId?.let(::gameKey),
            pendingFilterSourceFocus = matchingFilterLanding?.sourceGameId?.let(::gameKey),
            platformLandingPending = matchingPlatformLanding != null,
        )
    }

    // Mirror the dispatch hop's synchronous centre stamp (BUG-3) into the per-mode visual seed the
    // coverflow reads while focus sits OFF the ring — onCenter cannot fire then (focusedInRow is null
    // on the Add pill), which left the shelf painting the OLD hero through the exact coalesced burst
    // the hop fix landed for (2026-08-11 review). The registered-ring guard keeps a stale cross-mode
    // key from seeding the wrong lane, matching onCenter's live-ring strictness.
    val centeredShelfKeyForSeedState = viewModel.centeredShelfKey.collectAsState()
    val centeredShelfKeyForSeed by centeredShelfKeyForSeedState
    LaunchedEffect(centeredShelfKeyForSeed, focusState.focused, shelfMode) {
        val centred = centeredShelfKeyForSeed ?: return@LaunchedEffect
        if (focusState.focused != AddFolderKey) return@LaunchedEffect
        if (centred !in authority.registeredKeysInScope(LibraryShelfScope)) return@LaunchedEffect
        if (rememberedShelfKeysByMode[shelfMode] != centred) {
            rememberedShelfKeysByMode = rememberedShelfKeysByMode + (shelfMode to centred)
        }
        authority.rememberFocusForScope(LibraryShelfScope, centred)
    }
    // Wait for the matching Room result and the carousel's virtual registrations, then resolve the armed
    // landing once. Re-checking current focus after the frame is what protects a user's immediate L/R move.
    LaunchedEffect(pendingGamelistLanding, openPlatform, gamesLoadedForPlatform, games) {
        val request = pendingGamelistLanding ?: return@LaunchedEffect
        if (request.platformId != openPlatform || gamesLoadedForPlatform != openPlatform || games.isEmpty() ||
            games.first().platformId != request.platformId
        ) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        val resolution = resolvePendingGamelistLanding(
            pending = request,
            openPlatform = openPlatform,
            gamesLoadedForPlatform = gamesLoadedForPlatform,
            gameKeys = gameKeys.keySet,
            focused = authority.state.value.focused,
        )
        if (pendingGamelistLanding == request) pendingGamelistLanding = resolution.pending
        resolution.target?.let(authority::requestFocus)
    }
    // A Normal/Favorites swap is one reactive list replacement inside the SAME platform. Keep its destination
    // identity painted while virtual registrations settle, then request that key once and consume the request.
    LaunchedEffect(
        pendingGamelistFilterLanding,
        openPlatform,
        favoritesOnly,
        gamesLoadedForPlatform,
        games,
    ) {
        val request = pendingGamelistFilterLanding ?: return@LaunchedEffect
        if (request.platformId != openPlatform || request.favoritesOnly != favoritesOnly ||
            gamesLoadedForPlatform != openPlatform || games.isEmpty()
        ) {
            return@LaunchedEffect
        }
        withFrameNanos { }
        val resolution = resolvePendingGamelistFilterLanding(
            pending = request,
            openPlatform = openPlatform,
            favoritesOnly = favoritesOnly,
            gamesLoadedForPlatform = gamesLoadedForPlatform,
            gameKeys = gameKeys.keySet,
            focused = authority.state.value.focused,
        )
        if (resolution.pending == null) viewModel.consumePendingGamelistFilterLanding(request)
        resolution.target?.let { target ->
            authority.rememberFocusForScope(GamelistScope, target)
            authority.requestFocus(target)
        }
    }
    // Persist only settled, visible row identities. During a filter handoff the old focus may remain valid in
    // the destination superset for one frame; recording it would overwrite the Normal lane being restored.
    LaunchedEffect(
        openPlatform,
        favoritesOnly,
        focusState.scope,
        focusState.focused,
        games,
        matchingFilterLanding,
    ) {
        val platform = openPlatform ?: return@LaunchedEffect
        if (platform == LibraryViewModel.FAVORITES_PLATFORM_ID || focusState.scope != GamelistScope ||
            matchingFilterLanding != null
        ) {
            return@LaunchedEffect
        }
        // A lookup in the list's key index, not a walk of the list (RC-45): this effect runs on every focus step.
        focusState.focused?.let(gameKeys::gameFor)?.let(viewModel::rememberGamelistFocus)
    }

    return LibraryScreenModelState(
        launchState = launchState,
        launchStateOwner = launchStateOwner,
        launchEmulatorLabel = launchEmulatorLabel,
        openWithState = openWithState,
        favoritesCountState = favoritesCountState,
        favoriteSegmentState = favoriteSegmentState,
        shelfModeState = shelfModeState,
        searchQueryState = searchQueryState,
        searchCriteriaState = searchCriteriaState,
        searchFacetsState = searchFacetsState,
        librarySearchPickerState = librarySearchPickerState,
        favoritesOnlyState = favoritesOnlyState,
        pendingRemovalsState = pendingRemovalsState,
        games = games,
        gameKeys = gameKeys,
        platformLandingPending = libraryGamelistLandingArmed(
            landingPending = matchingPlatformLanding != null,
            gamesLoaded = gamesLoadedForPlatform == openPlatform,
            gamesEmpty = games.isEmpty(),
        ),
        gamelistViewModeState = gamelistViewModeState,
        gamelistPlatformSwitchMotionState = gamelistPlatformSwitchMotionState,
        platformSwitchReleased = platformSwitchReleased,
        openPlatformHasFavoritesState = openPlatformHasFavoritesState,
        keyboardOpenState = keyboardOpenState,
        hintLegendRequestState = hintLegendRequestState,
        pendingHideGameState = pendingHideGameState,
        focusStateState = focusStateState,
        hostFocus = hostFocus,
        liveDetailGame = liveDetailGame,
        matchingFilterLanding = matchingFilterLanding,
        laneRememberedFocus = laneRememberedFocus,
        renderedEmulatedGamelistFocus = renderedEmulatedGamelistFocus,
        centeredShelfKeyForSeedState = centeredShelfKeyForSeedState,
    )
}

private class LibraryScreenSnapshots(
    val nativeFavoriteGameRows: List<NativeShelfRow>,
    val nativeFavoriteAppRows: List<NativeShelfRow>,
    val favoriteSegments: List<FavoriteSegment>,
    val openEmulatedDetail: (GameRow) -> Unit,
    val openNativeDetailOrLaunch: (String) -> Unit,
    val liveShelfSnapshot: ShelfPrimarySnapshot?,
    val liveGamelistSnapshot: GamelistPrimarySnapshot?,
    val retainedShelfSnapshot: PrimarySnapshotHolder<ShelfPrimarySnapshot>,
    val retainedGamelistSnapshot: PrimarySnapshotHolder<GamelistPrimarySnapshot>,
)

/** Third piece: the native Favorites rows, the shelf and gamelist snapshots, and the scope and Favorites effects. */
@Composable
private fun rememberLibraryScreenSnapshots(
    viewModel: LibraryViewModel,
    authority: FocusAuthority,
    platformLaunchStatusLines: Map<PlatformId, String>,
    nativeGameRows: List<NativeShelfRow>,
    nativeAppRows: List<NativeShelfRow>,
    onLaunchNativePackage: (String) -> Unit,
    detailSession: LibraryDetailSession?,
    onOpenLibraryDetail: (LibraryDetailSession) -> Unit,
    foundation: LibraryScreenFoundation,
    modelState: LibraryScreenModelState,
): LibraryScreenSnapshots {
    // Values the earlier pieces own, re-bound under their original names so the code below is unchanged.
    val platforms by foundation.platformsState
    val openPlatform by foundation.openPlatformState
    val rememberedShelfKeysByMode by foundation.rememberedShelfKeysByModeState
    val gamesLoadedForPlatform by foundation.gamesLoadedForPlatformState
    val launchState = modelState.launchState
    val favoritesCount by modelState.favoritesCountState
    val favoriteSegment by modelState.favoriteSegmentState
    val shelfMode by modelState.shelfModeState
    val searchQuery by modelState.searchQueryState
    val favoritesOnly by modelState.favoritesOnlyState
    val games = modelState.games
    val gamelistViewMode by modelState.gamelistViewModeState
    val keyboardOpen by modelState.keyboardOpenState
    val focusState by modelState.focusStateState
    val liveDetailGame = modelState.liveDetailGame
    val matchingFilterLanding = modelState.matchingFilterLanding
    val laneRememberedFocus = modelState.laneRememberedFocus
    val renderedEmulatedGamelistFocus = modelState.renderedEmulatedGamelistFocus

    val nativeFavoriteGameRows = remember(nativeGameRows) { nativeGameRows.filter { it.isFavorite } }
    val nativeFavoriteAppRows = remember(nativeAppRows) { nativeAppRows.filter { it.isFavorite } }
    val favoriteSegments = remember(favoritesCount, nativeFavoriteGameRows, nativeFavoriteAppRows) {
        favoriteSegmentsForUi(favoritesCount, nativeFavoriteGameRows, nativeFavoriteAppRows)
    }
    val favoriteSegmentCounts = remember(favoritesCount, nativeFavoriteGameRows, nativeFavoriteAppRows) {
        mapOf(
            FavoriteSegment.GAMES to favoritesCount,
            FavoriteSegment.ANDROID_GAMES to nativeFavoriteGameRows.size,
            FavoriteSegment.ANDROID_APPS to nativeFavoriteAppRows.size,
        )
    }
    val activeNativeFavoriteRows = remember(
        favoriteSegment,
        nativeFavoriteGameRows,
        nativeFavoriteAppRows,
        searchQuery,
    ) {
        nativeFavoriteRowsShown(favoriteSegment, nativeFavoriteGameRows, nativeFavoriteAppRows, searchQuery)
    }
    val activeNativeFavoriteKeys = remember(activeNativeFavoriteRows) {
        activeNativeFavoriteRows.map { nativeShelfKey(it.packageName) }
    }
    val renderedGamelistFocus =
        if (openPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID &&
            favoriteSegment != FavoriteSegment.GAMES
        ) {
            nativeFavoritesRenderedFocus(
                rowKeys = activeNativeFavoriteKeys,
                focused = focusState.focused,
                scopeOwnsGamelist = focusState.scope == GamelistScope,
                rememberedFocus = authority.rememberedFocusIn(GamelistScope),
            )
        } else {
            renderedEmulatedGamelistFocus
        }
    val openEmulatedDetail: (GameRow) -> Unit = { row ->
        authority.requestFocus(gameKey(row))
        openTypedLibraryDetail(
            authority = authority,
            viewModel = viewModel,
            target = LibraryDetailTarget.Emulated(row),
            onOpen = onOpenLibraryDetail,
        )
    }
    val openNativeDetailOrLaunch: (String) -> Unit = { packageName ->
        val target = nativeGameRows.firstOrNull { it.packageName == packageName }
            ?.toLibraryDetailTargetOrNull()
        if (target != null) {
            authority.requestFocus(target.sourceFocusKey)
            if (!openTypedLibraryDetail(
                    authority = authority,
                    viewModel = viewModel,
                    target = target,
                    onOpen = onOpenLibraryDetail,
                )
            ) {
                viewModel.noteNativePackageLaunch(packageName)
                onLaunchNativePackage(packageName)
            }
        } else {
            viewModel.noteNativePackageLaunch(packageName)
            onLaunchNativePackage(packageName)
        }
    }
    val launchNativePackage: (String) -> Unit = remember(viewModel, onLaunchNativePackage) {
        { packageName ->
            viewModel.noteNativePackageLaunch(packageName)
            onLaunchNativePackage(packageName)
        }
    }
    val freshShelfSnapshot = if (openPlatform == null) {
        ShelfPrimarySnapshot(
            platforms = platforms,
            favoritesCount = favoritesCount,
            shelfMode = shelfMode,
            nativeGameRows = nativeGameRows,
            nativeAppRows = nativeAppRows,
            // this is the Library Shelf's equivalent of Home's
            // homeContentFocusKey/HomeShelfScreen fix -- a live raw read here was the actual gap (the
            // gamelist's own gamelistRenderedFocus already carries an authority.rememberedFocusIn fallback;
            // the shelf's own snapshot never did), so the tile under Utility/any pushed scope was losing its
            // ring for a structurally different reason than Home's snapToScale, but the same root cause.
            focused = authority.renderedFocusIn(LibraryShelfScope, focusState),
            rememberedKeysByMode = rememberedShelfKeysByMode,
        )
    } else {
        null
    }
    val freshGamelistSnapshot = openPlatform?.let { platformId ->
        GamelistPrimarySnapshot(
            platforms = platforms,
            platformLaunchStatusLines = platformLaunchStatusLines,
            openPlatform = platformId,
            games = games,
            gameKeys = modelState.gameKeys,
            gamesLoaded = gamesLoadedForPlatform == platformId,
            detailGame = liveDetailGame,
            launchState = launchState,
            favoriteSegment = favoriteSegment,
            favoriteSegments = favoriteSegments,
            favoriteSegmentCounts = favoriteSegmentCounts,
            activeNativeFavoriteRows = activeNativeFavoriteRows,
            searchQuery = searchQuery,
            favoritesOnly = favoritesOnly,
            viewMode = gamelistViewMode,
            keyboardOpen = keyboardOpen,
            focused = renderedGamelistFocus,
        )
    }
    // Built every pass, so a new instance every pass; kept as the last one while it equals it, so the pass that only moved the scope (the card or a picker
    // opening over the gamelist) does not hand the surfaces below a snapshot they take for a change and re-run them (owperf, 2026-10-15).
    val liveShelfSnapshot = rememberEqualInstance(freshShelfSnapshot)
    val liveGamelistSnapshot = rememberEqualInstance(freshGamelistSnapshot)
    val retainedShelfSnapshot = remember { PrimarySnapshotHolder<ShelfPrimarySnapshot>() }
    val retainedGamelistSnapshot = remember { PrimarySnapshotHolder<GamelistPrimarySnapshot>() }
    SideEffect {
        liveShelfSnapshot?.let { retainedShelfSnapshot.value = it }
        liveGamelistSnapshot?.let { retainedGamelistSnapshot.value = it }
    }

    // The shelf scope is the Library's base scope. Push it on entry; restore the prior scope on exit.
    DisposableEffect(authority) {
        enterLibraryBaseScope(authority)
        onDispose {
            // Dedicated Search is process-memory-only. Leaving Library closes its transient scope/picker while
            // retaining the current criteria on the gamelist; durable cross-destination Search return is owed.
            if (viewModel.librarySearchSession.value != null) viewModel.closeLibrarySearch()
            // Pop any views we may have opened (detail/gamelist) plus the shelf scope itself, so the
            // authority returns to exactly the scope stack it had before Library was entered.
            while (authority.state.value.scope != FocusScope.Root &&
                authority.state.value.scope in LIBRARY_SCOPES
            ) {
                authority.popScope()
            }
        }
    }
    // Destination changes unmount Library and correctly pop its scopes, while its browse/detail model is
    // intentionally retained as last-state memory. Rebuild the matching child scopes on remount so rendered
    // surface and input ownership can never disagree after M -> Home, Quick Launch, or Settings.
    LaunchedEffect(authority) {
        reconcileLibraryEntryScope(
            authority = authority,
            openPlatform = openPlatform,
            detailSession = detailSession,
        )
    }

    // Favorites is a VIEW, not a place you can get stranded in: if the user unfavorites their last favorite
    // while the favorites gamelist owns focus (its shelf tile has simultaneously vanished), whisk them back
    // to the platforms shelf instead of leaving them on an empty, mislabeled gamelist with nowhere to move
    // . Gated on GamelistScope so picker/confirmation modals can
    // finish their own close path without the base view changing underneath them.
    LaunchedEffect(openPlatform, favoriteSegments, focusState.scope) {
        if (openPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID &&
            favoriteSegments.isEmpty() &&
            focusState.scope == GamelistScope
        ) {
            authority.popScope()
            viewModel.closeGamelist()
            // Land focus on a real shelf tile — the favorites tile the scope remembered no longer exists.
            platforms.firstOrNull()?.let { authority.requestFocus(platformKey(it.platformId)) }
        }
    }

    LaunchedEffect(openPlatform, favoriteSegment, favoriteSegments) {
        if (openPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID &&
            favoriteSegments.isNotEmpty() &&
            favoriteSegment !in favoriteSegments
        ) {
            viewModel.setFavoriteSegment(favoriteSegments.first())
        }
    }

    LaunchedEffect(
        openPlatform,
        gamelistViewMode,
        gamesLoadedForPlatform,
        games,
        favoriteSegment,
        activeNativeFavoriteRows,
        focusState.scope,
        favoritesOnly,
        matchingFilterLanding,
    ) {
        if (openPlatform == null || focusState.scope != GamelistScope) return@LaunchedEffect
        if (gamesLoadedForPlatform != openPlatform) return@LaunchedEffect
        if (matchingFilterLanding != null) return@LaunchedEffect
        if (openPlatform != LibraryViewModel.FAVORITES_PLATFORM_ID &&
            games.isNotEmpty() && games.first().platformId != openPlatform
        ) {
            return@LaunchedEffect
        }
        if (openPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID &&
            favoriteSegment != FavoriteSegment.GAMES
        ) {
            if (activeNativeFavoriteRows.isNotEmpty()) {
                val focusedNow = authority.state.value.focused
                if (focusedNow == null ||
                    nativePackageFromFocusKey(focusedNow.value)?.let { packageName ->
                        activeNativeFavoriteRows.none { it.packageName == packageName }
                    } != false
                ) {
                    withFrameNanos { }
                    authority.requestFocus(nativeShelfKey(activeNativeFavoriteRows.first().packageName))
                }
            }
        } else if (games.isNotEmpty()) {
            val gameKeys = modelState.gameKeys.keySet
            val target = gamelistReseedTarget(
                gameKeys = gameKeys,
                focused = authority.state.value.focused,
                // Favorites keeps its existing first-visible fallback; RF01 restores only per-platform rings.
                rememberedFocus = (laneRememberedFocus ?: authority.rememberedFocusIn(GamelistScope))
                    .takeIf { openPlatform != LibraryViewModel.FAVORITES_PLATFORM_ID },
            )
            if (target != null) {
                withFrameNanos { }
                // Registration may have auto-landed on game one during the frame. Correct that default, but
                // never override a different valid key: that is the user's own L/R navigation.
                val focusedAfterRegistration = authority.state.value.focused
                if (focusedAfterRegistration == null ||
                    focusedAfterRegistration !in gameKeys ||
                    focusedAfterRegistration == gameKeys.first() ||
                    focusedAfterRegistration == target
                ) {
                    authority.requestFocus(target)
                }
            }
        }
    }

    return LibraryScreenSnapshots(
        nativeFavoriteGameRows = nativeFavoriteGameRows,
        nativeFavoriteAppRows = nativeFavoriteAppRows,
        favoriteSegments = favoriteSegments,
        openEmulatedDetail = openEmulatedDetail,
        openNativeDetailOrLaunch = openNativeDetailOrLaunch,
        liveShelfSnapshot = liveShelfSnapshot,
        liveGamelistSnapshot = liveGamelistSnapshot,
        retainedShelfSnapshot = retainedShelfSnapshot,
        retainedGamelistSnapshot = retainedGamelistSnapshot,
    )
}

private class LibraryScreenScene(
    val primarySceneModifier: Modifier,
    val settleGamelistOpen: suspend () -> Unit,
    val shatterStage: LibraryShatterStage,
    val bookLeaf: LibraryBookLeaf,
)

/** Fourth piece: the scene blur, the settle and prewarm hooks, the shatter stage and the book leaf. */
@Composable
private fun rememberLibraryScreenScene(
    viewModel: LibraryViewModel,
    authority: FocusAuthority,
    foundation: LibraryScreenFoundation,
): LibraryScreenScene {
    // Values the first piece owns, re-bound under their original names so the code below is unchanged.
    val presentedDetailChrome by foundation.presentedDetailChromeState
    val presentedSearchChrome by foundation.presentedSearchChromeState

    // Policy 2026-08-15, the same one wired at MimirAppHost's destination Box: the scene's blur
    // RAMPS on MimirMotion.OverlayMs rather than snapping through an `if`, and because this scene is
    // always composed, the ramp back off IS the return transition -- the gamelist arriving, not the
    // detail card fading. Constant identity: game detail and search both blur this one scene, and moving
    // between them must not rebuild the Animatable and flash the gamelist sharp mid-transition.
    val sceneGlassProgress = rememberMimirGlassProgress(
        visible = presentedDetailChrome || presentedSearchChrome,
        // A launch return puts the player's card back under the return cover: its glass is a settled state when the cover
        // lifts, never a blur ramping in behind the cover's fade.
        settleOpen = { libraryDetailArrivalSettled(viewModel.restoringLaunchReturnTarget()) },
    )
    val primarySceneModifier = Modifier
        .fillMaxSize()
        .background(MimirColors.SurfaceBase)
        .mimirGlassBackdropProgress(sceneGlassProgress)
        .let { scene ->
            if (presentedDetailChrome || presentedSearchChrome) scene.clearAndSetSemantics { } else scene
        }
    val settleContext = LocalContext.current
    // The settle decodes only what the page paints, and the LIST preview panel is dropped on compact.
    val settleCompact = rememberUpdatedState(
        LocalMimirViewportProfile.current.let { it.widthDp > 0f && it.isCompact },
    )
    val settleGamelistOpen: suspend () -> Unit = remember(viewModel, settleContext) {
        { awaitLibraryGamelistSettled(settleContext, viewModel, settleCompact.value) }
    }
    // Batch B's deferred prewarm: the dwell and idle passes that decode the settle window before the press.
    LibraryShelfPrewarm(viewModel, authority)
    // Walk-32i: where each cover's ink is inside its file is read from a persisted store, off the main thread. Loaded here, while
    // the shelf is what the player sees, it is in by the time a list's preview panel asks, and the panel (which holds its art
    // for the store's verdict) is held for a lookup and not for the file's parse.
    LaunchedEffect(Unit) { MimirArtInkStore.ensureLoaded(settleContext) }
    // Walk-32h ("every hide shatters the card"). A hide breaks the card apart where it stands, on the shelf, in the carousel,
    // in the list and in Favorites alike, so the stage lives here, above the surfaces, and the two hide slots the view
    // model awaits BEFORE anything is marked or written are set from it: one for a game, one for an Android package. Every
    // hide passes through it, so every hide says where it went: at once when the card is not broken (reduced motion, a
    // surface with nothing left to hand focus to) and as the last shard fades when it is. Each surface writes what only it
    // knows (`seatsDeparture`, `gameCount`) from its own branch below.
    val shatterStage = remember { LibraryShatterStage() }
    val shatterScope = rememberCoroutineScope()
    val shatterMotionEnabled = !mimirReducedMotion()
    SideEffect { shatterStage.enabled = shatterMotionEnabled }
    DisposableEffect(viewModel, shatterStage) {
        val slot: suspend (GameRow) -> Unit = { game ->
            shatterStage.play(game, shatterScope) { viewModel.showHideReceipt(LibraryHideCopy.GameReceipt) }
        }
        val nativeSlot: suspend (String) -> Unit = { packageName ->
            shatterStage.play(packageName, shatterScope) { viewModel.showHideReceipt(LibraryHideCopy.NativeReceipt) }
        }
        viewModel.departureSlot = slot
        viewModel.nativeDepartureSlot = nativeSlot
        onDispose {
            if (viewModel.departureSlot === slot) viewModel.departureSlot = null
            if (viewModel.nativeDepartureSlot === nativeSlot) viewModel.nativeDepartureSlot = null
        }
    }
    val bookLeaf = rememberLibraryBookLeaf()

    return LibraryScreenScene(
        primarySceneModifier = primarySceneModifier,
        settleGamelistOpen = settleGamelistOpen,
        shatterStage = shatterStage,
        bookLeaf = bookLeaf,
    )
}

/**
 * Last piece of the screen: the provider, launch-choice, hide and add-app modals, the controller-hint button,
 * the keyboard sheet and the parked-launch toast. It has no state of its own to hand back, so it is a
 * non-restartable composable that LibraryScreen calls last, where this code always ran.
 */
@NonRestartableComposable
@Composable
private fun LibraryScreenModalsAndHints(
    viewModel: LibraryViewModel,
    authority: FocusAuthority,
    nativeGameRows: List<NativeShelfRow>,
    nativeAppRows: List<NativeShelfRow>,
    nativeAddPickerMode: LibraryShelfMode?,
    nativeAddCandidates: List<NativeShelfAddCandidate>,
    nativeAddSearchQuery: String,
    nativeAddKeyboardOpen: Boolean,
    onNativeAddSearchChange: (String) -> Unit,
    onOpenNativeAddSearch: () -> Unit,
    onCloseNativeAddSearch: () -> Unit,
    onChooseNativeAddPackage: (String) -> Unit,
    onDismissNativeAddPicker: () -> Unit,
    pendingNativeHidePackage: String?,
    onConfirmHideNativePackage: () -> Unit,
    onCancelHideNativePackage: () -> Unit,
    transitionCancellationEpoch: Long,
    navigationHintsEnabled: Boolean,
    parkedRefusalOnHostRail: Boolean,
    foundation: LibraryScreenFoundation,
    modelState: LibraryScreenModelState,
    snapshots: LibraryScreenSnapshots,
) {
    // Values the earlier pieces own, re-bound under their original names so the code below is unchanged.
    val openWith by modelState.openWithState
    val detailGame by foundation.detailGameState
    val pendingHideGame by modelState.pendingHideGameState
    val focusState by modelState.focusStateState
    val openPlatform by foundation.openPlatformState
    val keyboardOpen by modelState.keyboardOpenState
    val favoritesOnly by modelState.favoritesOnlyState
    val openPlatformHasFavorites by modelState.openPlatformHasFavoritesState
    val hintLegendRequest by modelState.hintLegendRequestState
    val searchQuery by modelState.searchQueryState
    val nativeFavoriteGameRows = snapshots.nativeFavoriteGameRows
    val nativeFavoriteAppRows = snapshots.nativeFavoriteAppRows
    val favoriteSegments = snapshots.favoriteSegments

    // Provider selection is a detail-metadata modal and returns to its exact predecessor row.
    val options = openWith
    // The open card's physical members, for the disc / version menu. Collected here rather than read
    // from the flow's `.value`: `gameDetailMembers` is a stateIn(WhileSubscribed) with no flowOn, so
    // `.value` is only populated while something collects it -- this composition is that collector.
    val detailMembers by viewModel.gameDetailMembers.collectAsState()
    val pendingNativeHideRow = remember(pendingNativeHidePackage, nativeGameRows, nativeAppRows) {
        val packageName = pendingNativeHidePackage ?: return@remember null
        (nativeGameRows + nativeAppRows).firstOrNull { it.packageName == packageName }
    }
    val pendingHide = pendingHideGame
    val modalSurfaceKey: LibraryModalSurfaceKey? = when {
        focusState.scope == OpenWithScope && options != null -> LibraryModalSurfaceKey.OpenWith
        focusState.scope == LaunchChoiceScope && detailGame != null ->
            LibraryModalSurfaceKey.LaunchChoice(requireNotNull(detailGame).gameId.value)
        focusState.scope == HideConfirmScope && pendingHide != null ->
            LibraryModalSurfaceKey.HideGame(pendingHide.gameId.value)
        focusState.scope == NativeHideConfirmScope && pendingNativeHidePackage != null ->
            LibraryModalSurfaceKey.HideNative(pendingNativeHidePackage)
        focusState.scope == NativeAddPickerScope && nativeAddPickerMode != null ->
            LibraryModalSurfaceKey.NativeAdd(nativeAddPickerMode)
        else -> null
    }
    // OPEN WITH is built ahead (LibraryOpenWithPrewarm.kt): composed and measured, not placed, from a moment after its card has settled (or the pad is on its chip)
    // until the card closes, so that pressing A on the chip does not build the sheet on the key-down frame. `openWithActive` is the one fact the opening changes (the scope is
    // up and the options are there), read where the sheet is placed and where its Back and its first row are decided, never in this body.
    // Keyed on the two States themselves: `modelState` is a new object every pass, and a new lambda here is a changed argument that re-runs the sheet it is handed to.
    val openWithActive = remember(modelState.focusStateState, modelState.openWithState) {
        derivedStateOf(structuralEqualityPolicy()) {
            modelState.focusStateState.value.scope == OpenWithScope && modelState.openWithState.value != null
        }
    }
    val openWithActiveNow = remember(openWithActive) { { openWithActive.value } }
    val openWithBuiltAhead = rememberLibraryOpenWithPrewarm(
        wanted = (focusState.scope == GameDetailScope || focusState.scope == OpenWithScope) && options != null,
        onChip = {
            val raw = modelState.hostFocus.raw.value
            raw.scope == GameDetailScope && raw.focused == LibraryLaunchCardFocusContract.openWithKey
        },
    )
    LibraryOverlayPixelFadeTransition(
        targetState = modalSurfaceKey,
        transitionCancellationEpoch = transitionCancellationEpoch,
        standby = LibraryModalSurfaceKey.OpenWith.takeIf { openWithBuiltAhead },
    ) { presentedModal ->
        when (presentedModal) {
            LibraryModalSurfaceKey.OpenWith -> {
                val activeOptions = options ?: return@LibraryOverlayPixelFadeTransition
                // The sheet that stands built is not handed a pick's new options in the frames it is closing in (rememberOpenWithSheetOptions).
                val sheetOptions = rememberOpenWithSheetOptions(activeOptions, openWithActiveNow)
                LibraryStandbySlot(active = openWithActiveNow) {
                    LibraryOpenWithSheet(
                        viewModel = viewModel,
                        authority = authority,
                        options = sheetOptions,
                        detailGameState = foundation.detailGameState,
                        active = openWithActiveNow,
                    )
                }
            }
            is LibraryModalSurfaceKey.LaunchChoice -> {
                val game = detailGame ?: return@LibraryOverlayPixelFadeTransition
                LaunchChoiceOverlay(
                    authority = authority,
                    title = game.title,
                    members = detailMembers,
                    // The commit itself lives in dispatch (LaunchChoiceScope's CONFIRM arm), so controller
                    // and touch cannot drift into two different launch behaviours. This lambda is the
                    // TOUCH path only and routes to the same three calls.
                    onChoose = { choice ->
                        authority.popScope()
                        when (choice) {
                            com.gruning.mimir.rewrite.feature.library.LaunchChoice.GroupDefault ->
                                viewModel.launch(game)
                            is com.gruning.mimir.rewrite.feature.library.LaunchChoice.Disc ->
                                viewModel.launch(game, preferredDisc = choice.number)
                            is com.gruning.mimir.rewrite.feature.library.LaunchChoice.Version ->
                                viewModel.launch(game, launchMemberEntryKey = choice.entryKey)
                        }
                    },
                    onDismiss = { authority.popScope() },
                )
            }
            is LibraryModalSurfaceKey.HideGame -> {
                val game = pendingHide ?: return@LibraryOverlayPixelFadeTransition
                HideConfirmOverlay(
                    game = game,
                    authority = authority,
                    onConfirm = {
                        confirmLibraryHide(authority, viewModel, nativeFavoriteGameRows, nativeFavoriteAppRows)
                    },
                    onDismiss = { cancelLibraryHide(authority, viewModel) },
                )
            }
            is LibraryModalSurfaceKey.HideNative -> NativeHideConfirmOverlay(
                row = pendingNativeHideRow,
                packageName = presentedModal.packageName,
                authority = authority,
                // Walk-32h: the same confirm the controller path runs in dispatch, so the two cannot drift.
                onConfirm = {
                    confirmNativeHide(
                        authority = authority,
                        viewModel = viewModel,
                        nativeGameRows = nativeGameRows,
                        nativeFavoriteGameRows = nativeFavoriteGameRows,
                        nativeFavoriteAppRows = nativeFavoriteAppRows,
                        write = onConfirmHideNativePackage,
                    )
                },
                onDismiss = onCancelHideNativePackage,
            )
            is LibraryModalSurfaceKey.NativeAdd -> NativeAddAppPickerOverlay(
                mode = presentedModal.mode,
                candidates = nativeAddCandidates,
                searchQuery = nativeAddSearchQuery,
                keyboardOpen = nativeAddKeyboardOpen,
                authority = authority,
                // The picker draws the pad's own key; this branch runs only while it is up.
                focused = modelState.hostFocus.raw.value.focused,
                onSearchChange = onNativeAddSearchChange,
                onOpenSearch = onOpenNativeAddSearch,
                onCloseSearch = onCloseNativeAddSearch,
                onChoose = onChooseNativeAddPackage,
                onDismiss = onDismissNativeAddPicker,
                onKeyboardHostChanged = { viewModel.nativeAddKeyboardHost = it },
            )
        }
    }

    // The always-on Gamelist footer legend is replaced by a themed Guidance hint pill opened on demand.
    // It never opens itself. The button carries a caption on the shelf (inside PlatformsView),
    // the gamelist and Favorites; it stays hidden behind a keyboard or modal.
    val hintScope = focusState.scope
    val hintPlatform = openPlatform
    // The button FADES when a modal takes the scope and when it gives it back (RC-18, L-15: it unmounted, then popped in at full alpha under the
    // card that was still dissolving, over the card's own "B BACK"): it stays composed while it has any alpha.
    val hintRailWanted = hintPlatform != null && libraryNavigationHintRailVisible(navigationHintsEnabled, keyboardOpen, hintScope)
    val hintRailPresence = rememberLibraryHintRailPresence(hintRailWanted)
    if (hintPlatform != null && hintRailPresence.composed(hintRailWanted)) {
        val isFavoritesView = hintPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID
        val hintButtonHints = gamelistFooterHints(
            res = libraryResources(),
            inFavorites = isFavoritesView,
            favoritesOnly = favoritesOnly,
            canSwitchFavoriteSegments = favoriteSegments.size > 1,
            hasFavoritesInPlatform = openPlatformHasFavorites,
        )
        Box(Modifier.fillMaxSize()) {
            HintRailButton(
                hints = hintButtonHints,
                contextKey = hintPlatform.value,
                revealRequest = hintLegendRequest,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        end = MimirSpacing.lg,
                        bottom = MimirSpacing.lg,
                    )
                    // Read in the draw phase only: a fade never recomposes the button.
                    .graphicsLayer { alpha = hintRailPresence.alpha },
            )
        }
    }

    // The in-app keyboard floats at the bottom over the gamelist while search is active. Touch-only
    // . The PANEL swallows its own bounds (see MimirKeyboard) so
    // a mis-tap in the key gaps can't fire a result behind it; only the empty area ABOVE the panel stays
    // pass-through, so the filtered results there stay tappable — tap a match to open it; Done dismisses.
    Box(Modifier.fillMaxSize()) {
        val keyboardTargetLabel = stringResource(
            if (openPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID) R.string.library_gamelist_search_favorites else R.string.library_gamelist_search_games,
        )
        MimirKeyboardSheet(
            visible = keyboardOpen,
            value = searchQuery,
            targetLabel = keyboardTargetLabel,
            onValueChange = { viewModel.setSearchQuery(it) },
            onDone = {
                viewModel.closeSearch()
                if (viewModel.librarySearchSession.value != null) {
                    authority.requestFocus(LibrarySearchQueryControlKey)
                    viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Query)
                }
            },
            authority = authority,
            onKeyboardHostChanged = { viewModel.libraryKeyboardHost = it },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    // R6-P0-02 review fix (item 2): Library is on Main, so it uses the shared short-message rail on Main -- the same core-ui rail and the same copy the Home row/card show for the identical
    // condition (a PLAY refused by a still-live parked lease), not a new design. Passive: no focus target,
    // no controller focus stolen (MimirHintToastRail paints a live region only). Bottom-anchored, the same
    // placement MimirAppHost's Settings destination already reuses this exact rail with ("the same rail
    // Home uses, so connect feedback looks like the rest of the app rather than a bespoke ... banner").
    // Main has ONE message rail per display (the app host's); a host that draws the refusal there says so and this screen draws none, so no two rails share the bottom centre.
    if (!parkedRefusalOnHostRail) {
        val parkedRefusalToast by viewModel.parkedRefusalToast.collectAsState()
        Box(Modifier.fillMaxSize()) {
            MimirHintToastRail(
                hint = null,
                toast = parkedRefusalToast,
                onMessageDelivered = viewModel::consumeDeliveredParkedRefusalToast,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.42f),
            )
        }
    }
}

/**
 * @param viewModel the state holder collecting the repository Flows.
 * @param authority the single focus authority (from the app-root CompositionLocal by default).
 * @param onRescanFolder host hook: fire the SAF `ACTION_OPEN_DOCUMENT_TREE` picker (owned by :app),
 *   which on grant persists the uri + calls `repository.rescan(uri)`. No-op-safe here.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    authority: FocusAuthority = LocalFocusAuthority.current,
    platformLaunchStatusLines: Map<PlatformId, String> = emptyMap(),
    nativeGameRows: List<NativeShelfRow> = emptyList(),
    nativeAppRows: List<NativeShelfRow> = emptyList(),
    nativeAddPickerMode: LibraryShelfMode? = null,
    nativeAddCandidates: List<NativeShelfAddCandidate> = emptyList(),
    nativeAddSearchQuery: String = "",
    nativeAddKeyboardOpen: Boolean = false,
    onLaunchNativePackage: (String) -> Unit = {},
    // The stable media pill starts as Enrich, then becomes a direct Hero shortcut after durable provider truth.
    // canFetchArt gates Enrich for every platform except pc_games (see steamGridDbKeyPresent below); Hero
    // remains local-first and always opens.
    canFetchArt: Boolean = false,
    // Same shape as canFetchArt, for SteamGridDB rather than ScreenScraper: gates the Library detail card's
    // `pc_games` "no description" copy (R-a), AND -- - the gamelist media pill's
    // own Enrich affordance for a `pc_games` row (see gamelistEnrichAllowed), mirroring
    // MimirArtworkState.fetchOne's synchronous gate exactly so the pill never disagrees with the action.
    steamGridDbKeyPresent: Boolean = false,
    onFetchArtForGame: (GameRow) -> Unit = {},
    onCustomizeHeroForGame: (GameRow) -> Unit = {},
    // The RetroAchievements lane, owned by :app (it holds the gate, the credentials and the file access).
    // Defaults leave the gamelist exactly as it was: no count, and selecting the title does nothing.
    achievementLabelForGame: (GameRow) -> String? = { null },
    /**
     *
     *
     * A LAMBDA for the same reason [achievementLabelForGame] above is one — :app owns both stores the
     * answer comes from (the explicit per-game pick and the enrichment-downloaded provider render) and the
     * Utility on/off gate that selects between them and the cover. The default answers null, which leaves
     * the detail card on the cover exactly as it was, so no existing caller or preview changes behaviour.
     *
     * Called through a `remember` keyed on the open detail target, NOT on every recomposition: the app's
     * implementation stats a file and decodes an image header, which is cheap once per card but not once
     * per focus move. See the call site.
     */
    /**
     * The LAUNCH CARD state's face for one game — **and its kind**, which is why this returns
     * [MimirShelfFace] rather than the bare `String?` it carried until 2026-08-26.
     *
     * `:app` owns the stores and the switch and answers through `launchCardWiredFace`, the same FL-2
     * ladder the Home-side card runs. It replaced a resolver that was gated on Cartridge Hero (vetoing a
     * hand-picked cartridge, which C4 forbids), read the store at the BARE cache key (so a launch-card
     * pick was invisible), and returned no kind (so the card assumed physical and squared off a box).
     *
     * The type is reused rather than invented — §2.5 of the media-states spec says to reuse this seam,
     * and [MimirShelfFace] already exists as "a face and what KIND it is, as ONE return value". Its name
     * says Shelf because that is where it was needed first; the shape is the same fact.
     */
    cartridgeArtUriForGame: (GameRow) -> MimirShelfFace? = { null },
    onProbeAchievements: suspend (GameRow) -> Unit = {},
    onOpenAchievements: (GameRow) -> Unit = {},
    /** CONFIRM/tap on the Library detail launch card's title-edit glyph. */
    onEditGameTitle: (GameRow) -> Unit = {},
    onOpenNativeAddPicker: (LibraryShelfMode) -> Unit = {},
    onNativeAddSearchChange: (String) -> Unit = {},
    onOpenNativeAddSearch: () -> Unit = {},
    onCloseNativeAddSearch: () -> Unit = {},
    onChooseNativeAddPackage: (String) -> Unit = {},
    onDismissNativeAddPicker: () -> Unit = {},
    pendingNativeHidePackage: String? = null,
    onConfirmHideNativePackage: () -> Unit = {},
    onCancelHideNativePackage: () -> Unit = {},
    onRescanFolder: () -> Unit = {},
    backdropMode: GamelistBackdropMode = GamelistBackdropMode.BIFROST,
    /** Resolves a local GameId-owned Hero. The app may place its explicit custom override before row art. */
    heroArtUriForGame: (GameRow) -> String? = { it.heroArtUri },
    /**
     * Resolves a local GameId-owned Hero **MOTION** clip — policy 2026-08-14.
     *
     * **Defaults to `{ null }`, so an unwired caller renders exactly what it renders today.** A `GameRow`
     * carries `heroArtUri` but no clip field: the chosen clip lives in `app/`'s `CustomHeroStore`, keyed by
     * `GameId`, which is why this arrives as a resolver rather than being read off the row. The same shape
     * and the same reason as [heroArtUriForGame]'s custom-override hop.
     */
    heroMotionUriForGame: (GameRow) -> String? = { null },
    detailActionState: LibraryDetailActionState? = null,
    detailSession: LibraryDetailSession? = null,
    detailDescriptionOwnerToken: Long = 0L,
    detailDescriptionScrollCommand: HomeLaunchDescriptionScrollCommand = HomeLaunchDescriptionScrollCommand(),
    onDetailDescriptionScrollStateChanged: (HomeLaunchDescriptionScrollState) -> Unit = {},
    transitionCancellationEpoch: Long = 0L,
    onOpenLibraryDetail: (LibraryDetailSession) -> Unit = {},
    onCloseLibraryDetail: (LibraryDetailTarget) -> Unit = {},
    /** Utility preference for this screen's controller hints only: the shelf's standing line and the gamelist's legend. */
    navigationHintsEnabled: Boolean = true,
    /** Optional host for the shared quick rail. It owns no focus authority and reserves one stable bottom lane. */
    quickRailContent: @Composable () -> Unit = {},
    /**
     * True when the host draws the parked-launch refusal ([LibraryViewModel.parkedRefusalToast]) on its own one message rail for this display, so this screen draws none: Main has ONE
     * rail, and a second one here would share its spot and draw over it. The host must then collect that flow and call
     * [LibraryViewModel.consumeDeliveredParkedRefusalToast] itself. False (the default) keeps the screen's own rail, for every other host.
     */
    parkedRefusalOnHostRail: Boolean = false,
) {
    PlayedDayClockEffect() // the "Played …" labels on the list, the carousel and the detail lane turn over at local midnight
    val libraryRes = libraryResources()
    val foundation = rememberLibraryScreenFoundation(viewModel, authority, detailActionState, transitionCancellationEpoch)
    val modelState = rememberLibraryScreenModelState(viewModel, authority, foundation)
    // The faces the launch card last wore (review W2A #7): held by the screen, so a card that opens again inside its own close starts from the face it had.
    val launchCardFaces = remember { LibraryLaunchCardFaceMemory() }
    val snapshots = rememberLibraryScreenSnapshots(
        viewModel = viewModel,
        authority = authority,
        platformLaunchStatusLines = platformLaunchStatusLines,
        nativeGameRows = nativeGameRows,
        nativeAppRows = nativeAppRows,
        onLaunchNativePackage = onLaunchNativePackage,
        detailSession = detailSession,
        onOpenLibraryDetail = onOpenLibraryDetail,
        foundation = foundation,
        modelState = modelState,
    )
    val scene = rememberLibraryScreenScene(viewModel, authority, foundation)
    // Values the pieces above own, re-bound under their original names so the code below is unchanged.
    val platforms by foundation.platformsState
    val openPlatform by foundation.openPlatformState
    var rememberedShelfKeysByMode by foundation.rememberedShelfKeysByModeState
    val detailGame by foundation.detailGameState
    val librarySearchSession by foundation.librarySearchSessionState
    val renderedDetailState = foundation.renderedDetailState
    val launchState = modelState.launchState
    val launchStateOwner = modelState.launchStateOwner
    val launchEmulatorLabel = modelState.launchEmulatorLabel
    val searchCriteria by modelState.searchCriteriaState
    val searchFacets by modelState.searchFacetsState
    val librarySearchPicker by modelState.librarySearchPickerState
    val pendingRemovals by modelState.pendingRemovalsState
    val games = modelState.games
    val gamelistPlatformSwitchMotion by modelState.gamelistPlatformSwitchMotionState
    val platformSwitchReleased = modelState.platformSwitchReleased
    val hintLegendRequest by modelState.hintLegendRequestState
    val focusState by modelState.focusStateState
    // The gamelist layer's own view of focus: unchanged while the card, a picker or Search is on top of it, so none of them re-runs it (owperf, 2026-10-15).
    val gamelistFocus by modelState.hostFocus.gamelist
    val centeredShelfKeyForSeed by modelState.centeredShelfKeyForSeedState
    val openEmulatedDetail = snapshots.openEmulatedDetail
    val openNativeDetailOrLaunch = snapshots.openNativeDetailOrLaunch
    val liveShelfSnapshot = snapshots.liveShelfSnapshot
    val liveGamelistSnapshot = snapshots.liveGamelistSnapshot
    val retainedShelfSnapshot = snapshots.retainedShelfSnapshot
    val retainedGamelistSnapshot = snapshots.retainedGamelistSnapshot
    val primarySceneModifier = scene.primarySceneModifier
    val settleGamelistOpen = scene.settleGamelistOpen
    val shatterStage = scene.shatterStage
    val bookLeaf = scene.bookLeaf
    // Walk-32b: drawn here, outside the transition, so the leaf's recording outlives the close for the next open.
    Column(modifier = primarySceneModifier.keepLibraryBookLeafRecorded(bookLeaf)) {
        // Opening a platform swaps ownership immediately. The prior rendered pixels remain briefly as the only
        // outgoing layer; they contain no composition, focus registration, semantics, or input callbacks.
        // Batch E: the shelf's centred book records itself into this leaf; the open swings it.
        CompositionLocalProvider(LocalLibraryBookLeaf provides bookLeaf) {
        ImmediateLibrarySnapshotTransition(
            targetState = libraryPrimarySurfaceKey(openPlatform),
            transitionCancellationEpoch = transitionCancellationEpoch,
            modifier = Modifier.fillMaxSize(),
            motion = { from, to ->
                libraryPrimaryPageMotion(
                    from = from,
                    to = to,
                    settleGamelistOpen = settleGamelistOpen,
                    book = bookLeaf.takeIf { it.readyFor(openPlatform?.value) },
                    // The platform being
                    // closed is the last gamelist shown; its cover plays only if the shelf centres that book.
                    closeBook = bookLeaf,
                    closingPlatformId = retainedGamelistSnapshot.value?.openPlatform?.value,
                    settled = libraryPageRestoreSettled(viewModel.restoringLaunchReturnTarget()),
                )
            },
        ) { presentedSurface ->
        when (presentedSurface) {
        LibraryPrimarySurfaceKey.Shelf -> {
            val primary = liveShelfSnapshot
                ?: retainedShelfSnapshot.value
                ?: return@ImmediateLibrarySnapshotTransition
            // Walk-32h: the shelf's Android tile leaves at the write (nothing seats a departure here), and its ring is the
            // Android games it draws. Written here so a hide after a visit to the carousel never inherits its answers.
            SideEffect {
                shatterStage.seatsDeparture = false
                shatterStage.gameCount =
                    if (primary.shelfMode == LibraryShelfMode.ANDROID_GAMES) primary.nativeGameRows.size else 0
            }
            // The platforms shelf is a full-bleed console surface owning its OWN chrome (MimirShelfBackground +
            // MimirIdentityHeader + coverflow + footer). No outer "LIBRARY / Your platforms" header over the
            // "MIMIR" identity header, and no outer padding stealing the hero's vertical band.
            PlatformsView(
                platforms = primary.platforms,
                favoritesCount = primary.favoritesCount,
                shelfMode = primary.shelfMode,
                nativeGameRows = primary.nativeGameRows,
                nativeAppRows = primary.nativeAppRows,
                authority = authority,
                focused = primary.focused,
                transitionCancellationEpoch = transitionCancellationEpoch,
                onRescanFolder = onRescanFolder,
                onLaunchNativePackage = openNativeDetailOrLaunch,
                onOpenNativeAddPicker = onOpenNativeAddPicker,
                onSelectShelfMode = { selectShelfModeWithFocusStamp(authority, viewModel, primary.platforms, primary.nativeGameRows, it) },
                // Touch parity : a tap OPENS the platform,
                // mirroring controller CONFIRM — not just a focus request. The favorites sentinel routes
                // through the same path (its `games` flow serves the cross-platform favorites set).
                onOpenPlatform = { id ->
                    authority.requestFocus(platformKey(id))
                    viewModel.openPlatform(id)
                    authority.pushScope(GamelistScope)
                },
                rememberedKeysByMode = primary.rememberedKeysByMode,
                // the dispatch reducer's mode-switch stamp
                // (`cycleShelfModeWithFocusStamp`, `LibraryDispatch.kt`) writes the destination ring's
                // first key here FIRST, before the ring is even registered -- pass it through so the
                // landing effect below can fall back to it when the render's own per-mode memory
                // (rememberedKeysByMode) has nothing yet (first-ever visit to this mode this session).
                centeredShelfKey = centeredShelfKeyForSeed,
                quickRailContent = quickRailContent,
                navigationHintsEnabled = navigationHintsEnabled,
                hintLegendRequest = hintLegendRequest,
                shelfModeStep = { viewModel.lastShelfModeStep },
                pageRestoreSettled = { libraryPageRestoreSettled(viewModel.restoringLaunchReturnTarget()) },
                shatter = shatterStage,
                onCenter = { centeredKey ->
                    if (rememberedShelfKeysByMode[primary.shelfMode] != centeredKey) {
                        rememberedShelfKeysByMode = rememberedShelfKeysByMode +
                            (primary.shelfMode to centeredKey)
                    }
                    // Stamp the centered tile into the shelf scope's focus memory so a DOWN off the Add pill
                    // (which has physical bounds while tiles are synthetic virtual bounds — a geometric move
                    // would score the leftmost tile) can return to exactly this hero. Same channel line 788
                    // uses for gamelist-return; they never fire in the same frame. (Item 6.)
                    authority.rememberFocusForScope(LibraryShelfScope, centeredKey)
                    // Also record it in the view-model — an un-clobberable source of truth for the Add→DOWN
                    // return. focusMemory alone was resetting the hop to the first console. (LIB-02e.)
                    viewModel.setCenteredShelfKey(centeredKey)
                },
            )
        }
        LibraryPrimarySurfaceKey.Gamelist -> {
            val primary = liveGamelistSnapshot
                ?: retainedGamelistSnapshot.value
                ?: return@ImmediateLibrarySnapshotTransition
            // Departure transition: A game the player
            // sent away DEPARTS: its card dissolves where it stood while the cards after it close the gap, on one clock, and
            // the pad and the hero wait for that motion. The tracker is asked HERE, in the composition that first shows the
            // list one game shorter, because everything that reads it (the hero's hold, the carousel's ghost, the centre's
            // identity) has to see the departure in that very frame or the frame after it would already be the cut.
            // The list plays it too (its row dissolves and the rows after it glide up), in a platform's LIST view and in
            // the Favorites list of games. Favorites' Android segments list packages, not games, and leave at the write as before.
            val departureTracker = remember { GamelistDepartureTracker() }
            val departureMotionEnabled = !mimirReducedMotion() &&
                (primary.openPlatform != LibraryViewModel.FAVORITES_PLATFORM_ID || primary.favoriteSegment == FavoriteSegment.GAMES)
            // Walk-32h: what the shatter stage (lifted above the two surfaces, below) needs to know of THIS surface: the
            // carousel and (walk-32i) the games lists seat a departure and the native lists leave at the write, and how many cards
            // or rows there are to hand focus to. Favorites' Android segments count their own rows, not the emulated games it also lists.
            val nativeFavoritesOpen = primary.openPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID &&
                primary.favoriteSegment != FavoriteSegment.GAMES
            SideEffect {
                shatterStage.seatsDeparture = departureMotionEnabled
                shatterStage.gameCount = if (nativeFavoritesOpen) primary.activeNativeFavoriteRows.size else primary.games.size
            }
            val departureFrame = departureTracker.observe(
                games = primary.games,
                // RC-45: the list's key index answers the centre in one lookup; a focus step walks nothing.
                centredIndex = gamelistCentredIndex(
                    index = primary.gameKeys,
                    centeredKey = primary.focused,
                    getArtFocused = primary.focused == GamelistGetArtKey,
                    detailGame = primary.detailGame,
                ),
                scope = Triple(primary.openPlatform, primary.favoritesOnly, primary.searchQuery),
                leaving = { it.gameId in pendingRemovals },
                motionEnabled = departureMotionEnabled,
                shatters = shatterStage::shatters,
                keys = primary.gameKeys.keys,
            )
            SideEffect { departureTracker.commit() }
            GamelistDepartureRunner(departureFrame.departure)
            // Loader grace is capped at 400ms (2026-08-27). ONE flip at the end of the window rather than a
            // per-frame tick: a dwell counter would recompose this subtree ~24 times per switch to answer
            // a question that changes exactly once, on a surface whose recomposition cost was
            // already reported as a strobe.
            var withinPlatformSwitchGrace by remember(primary.openPlatform) { mutableStateOf(true) }
            LaunchedEffect(primary.openPlatform) {
                withinPlatformSwitchGrace = true
                delay(LibraryGamelistEmptyGraceMs)
                withinPlatformSwitchGrace = false
            }
            // The gamelist keeps its own compact chrome: title/search/segments, body, and footer.
            // CPL01: one static local Hero sits behind the gamelist when Bifröst is on. Cover, logo, preview
            // clip, fallback glyph, player, and audio are all excluded from this backdrop role.
            // No theme crest on the gamelist (sweep 10-09): the faint outlined diamond the DEFAULT theme's watermark drew here stood at the top right, half under the status
            // pill, and read as a stray mark (the earlier change retired the same crest from every glass panel, 2026-08-08).
            Box(Modifier.fillMaxSize()) {
              // The shelf/gamelist identity owns the outer pixel transition. Platform changes stay inside this
              // mounted book shell: Hero uses its neutral prewarm handoff and the destination page alone glides.
              val backdropFocus = gamelistBackdropFocusKey(
                  focused = primary.focused,
                  detailGameKey = primary.detailGame?.let(::gameKey),
              )
              // RC-45: the focused game is one lookup in the list's key index, not a walk of the list on every step.
              val backdropGame = backdropFocus?.let(primary.gameKeys::gameFor)
                  ?: primary.detailGame?.takeIf { primary.focused == GamelistGetArtKey }
              // RC-46: the hero's URIs are resolved when the focused game or the media changes, not on every recomposition. The resolvers read
              // the preferences and stat the hero files (the custom override, the preview clip), and a focus step recomposes this scene several
              // times. A custom hero or clip being applied hands this screen a new `cartridgeArtUriForGame` (the host re-keys it on every media
              // apply: `remember(core.context, bindings.mediaApplyEpoch)`), which is the key that makes a fresh answer reach an open page.
              val liveBackdropHero = remember(
                  backdropGame?.gameId,
                  backdropGame?.heroArtUri,
                  backdropMode,
                  heroArtUriForGame,
                  cartridgeArtUriForGame,
              ) {
                  libraryHeroBackdropUri(
                      mode = backdropMode,
                      candidate = backdropGame?.let(heroArtUriForGame),
                  )
              }
              // The hero rides the L2/R2 switch. Focus moves to the new platform on the press, and the
              // new hero used to land behind the old list while it was still held for its covers. While a switch
              // holds (decided in this same composition, from the pending token, so the press frame is covered too)
              // the hero stays the one on screen, and changes as the new list arrives.
              //
              // A dissolve holds the hero for the whole departure; a shatter holds it to 300 ms and hands over while the gap
              // still closes ([GamelistDeparture.holdsHero]).
              // The same hold covers a departure. The hero the player was looking at stays for the length of the
              // card's dissolve and only then hands over, through the same path a step takes (the still holds the outgoing
              // image and crossfades; the clip's cover fades in over the floor), so hiding a game never drops the hero on the
              // frame the card goes. Decided in this composition too, from the departure the tracker just seated.
              val switchHoldsBackdrop = (
                  libraryGamelistPageMotionHolds(primary.openPlatform, gamelistPlatformSwitchMotion) &&
                      platformSwitchReleased.value != gamelistPlatformSwitchMotion?.sequence
                  ) || departureFrame.departure?.holdsHero == true
              val backdropMemory = remember { GamelistBackdropMemory() }
              if (!switchHoldsBackdrop) backdropMemory.hero = liveBackdropHero
              val backdropHero = if (switchHoldsBackdrop) backdropMemory.hero else liveBackdropHero
              val lightMediaContrast = gamelistUsesLightMediaContrast(
                  palette = LocalMimirPalette.current,
                  backdropHero = backdropHero,
              )
              // Hero motion . Bifröst was always SPECIFIED to be video --
              // GamelistBackdropMode.BIFROST_BACKDROP's own KDoc calls it "the see-through preview VIDEO
              // veiled by the theme gradient" -- and the gamelist has been drawing the still half of it.
              // VideoBackdrop's defaults are literally the gamelist's ("every existing (gamelist) caller
              // stays byte-identical"), so this completes the mode rather than introducing a new one.
              val liveBackdropMotion = remember(
                  backdropGame?.gameId,
                  backdropGame?.heroArtUri,
                  backdropMode,
                  heroMotionUriForGame,
                  cartridgeArtUriForGame,
              ) {
                  libraryHeroMotionUri(
                      mode = backdropMode,
                      candidate = backdropGame?.let(heroMotionUriForGame),
                  )
              }
              if (!switchHoldsBackdrop) backdropMemory.motion = liveBackdropMotion
              val backdropMotion = if (switchHoldsBackdrop) backdropMemory.motion else liveBackdropMotion
              if (backdropMotion != null) {
                  // artUri is the chosen STILL, not null: unlike Home (which bans a static cover under its
                  // video), the gamelist's hero still is the honest thing to show while the decode settles
                  // and the only thing left if the clip cannot play at all -- battery saver skips the decode
                  // outright (VideoBackdrop's own power-save branch), and a refused decoder must not leave
                  // the player staring at a bare gradient where their art used to be.
                  VideoBackdrop(
                      videoUri = backdropMotion,
                      artUri = backdropHero,
                      modifier = Modifier.fillMaxSize(),
                  )
              } else {
                  LibraryHeroBackdrop(
                      imageUri = backdropHero,
                      modifier = Modifier.fillMaxSize(),
                      // Ride the page rather than the video player's debounce. See the constant.
                      settleMs = LibraryGamelistHeroRevealFloorMs,
                  )
              }
              GamelistDestinationPageTransition(
                  platformId = primary.openPlatform,
                  gamesLoaded = primary.gamesLoaded,
                  gamesEmpty = primary.games.isEmpty(),
                  withinSwitchGrace = withinPlatformSwitchGrace,
                  motion = gamelistPlatformSwitchMotion,
                  onMotionSettled = viewModel::consumeGamelistPlatformSwitchMotion,
                  // the old list holds until the new platform's rows and centre covers settle, capped.
                  settle = settleGamelistOpen,
                  releasedSequence = platformSwitchReleased,
                  modifier = Modifier.fillMaxSize(),
              ) {
                GamelistEnterSurface {
                Column(
                    // Short handheld viewports were paying 64dp of vertical shell padding before the
                    // coverflow solved its own envelope, shrinking tall PSP/Switch/PS2 cases twice.
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = LibraryGamelistBodyPaddingDp, vertical = MimirSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
                ) {
                    val openPlatformIsFavorites = primary.openPlatform == LibraryViewModel.FAVORITES_PLATFORM_ID
                    val dedicatedSearchEnabled =
                        !openPlatformIsFavorites || primary.favoriteSegment == FavoriteSegment.GAMES
                    val hasSearchableRows = when {
                        openPlatformIsFavorites && primary.favoriteSegment != FavoriteSegment.GAMES ->
                            primary.activeNativeFavoriteRows.isNotEmpty()
                        else -> primary.games.isNotEmpty()
                    }
                    val showHeaderSearch = libraryGamelistHeaderSearchVisible(
                        dedicatedSearchEnabled = dedicatedSearchEnabled,
                        hasVisibleRows = hasSearchableRows,
                        query = primary.searchQuery,
                    )
                    val headerMeta = when {
                        openPlatformIsFavorites && primary.favoriteSegment != FavoriteSegment.GAMES ->
                            gamelistHeaderMeta(libraryRes, searchQuery = primary.searchQuery, favoritesOnly = false, count = primary.activeNativeFavoriteRows.size, kind = GamelistItemKind.APP)
                        else ->
                            gamelistHeaderMeta(
                                libraryRes,
                                searchQuery = primary.searchQuery,
                                favoritesOnly = primary.favoritesOnly,
                                filterLabels = gamelistActiveFilterLabels(
                                    criteria = searchCriteria,
                                    facets = searchFacets,
                                    platformLabelOf = { id -> platformLabel(libraryRes, primary.platforms, id) },
                                    openPlatform = primary.openPlatform,
                                ),
                                count = primary.games.size,
                                kind = GamelistItemKind.GAME,
                                countSettled = !libraryGamelistShowsLoader(
                                    gamesLoaded = primary.gamesLoaded,
                                    gamesEmpty = primary.games.isEmpty(),
                                    withinSwitchGrace = withinPlatformSwitchGrace,
                                ),
                            )
                    }
                    GamelistHeader(
                        title = platformLabel(libraryRes, primary.platforms, primary.openPlatform),
                        metaLabel = headerMeta,
                        showSearch = showHeaderSearch,
                        searchQuery = primary.searchQuery,
                        // Nova search-field clipping correction, 2026-09-30. The favourites wording ellipsised in the same pill the games wording fits; the title
                        // above it already says Favorites, and the keyboard's wider field still does. The apps segment
                        // names its own rows, as its count does.
                        searchPlaceholder = stringResource(
                            if (openPlatformIsFavorites && primary.favoriteSegment != FavoriteSegment.GAMES) R.string.library_gamelist_search_apps else R.string.library_gamelist_search_games,
                        ),
                        authority = authority,
                        searchControllerEnabled = dedicatedSearchEnabled,
                        // RING channel (core-input's two-channel law): the search launcher's focus
                        // treatment says "input lands here", so it leaves with the scope. This is also
                        // a site whose visibility remains unverified -- whether the header stays visible once
                        // LibrarySearchScope opens -- and the lit gate makes the answer not matter: a
                        // scope that does not own input never paints a ring, visible or not.
                        // One ring on entry (audit 2026-10-14, RC-18, L-10): a platform's first keys register header first, so for a beat the
                        // authority's focus is on this pill while the book is already painted as the landing (gamelistRenderedFocus). The
                        // pill wears no ring while a landing is armed, and the book is the one ring from the first frame.
                        searchFocused = gamelistSearchRingLit(
                            authorityLit = authority.isLitFocusIn(
                                GamelistScope,
                                LibrarySearchLauncherKey,
                                gamelistFocus,
                            ),
                            landingArmed = modelState.platformLandingPending,
                        ),
                        onOpenSearch = {
                            if (dedicatedSearchEnabled) {
                                val originGameId = if (focusState.scope == GamelistScope) {
                                    resolveLibrarySearchTouchOrigin(
                                        focused = focusState.focused,
                                        games = primary.games,
                                        detailGame = primary.detailGame,
                                        preparedGameId = viewModel.preparedLibrarySearchOrigin(),
                                    )
                                } else {
                                    null
                                }
                                // Capture the touch origin before the launcher takes base-scope focus.
                                if (viewModel.openLibrarySearch(originGameId)) {
                                    authority.requestFocus(LibrarySearchLauncherKey)
                                    authority.rememberFocusForScope(
                                        LibrarySearchScope,
                                        LibrarySearchQueryControlKey,
                                    )
                                    authority.pushScope(LibrarySearchScope)
                                }
                            } else {
                                // Android favorite segments keep their existing package/title keyboard path.
                                viewModel.openSearch()
                            }
                        },
                        onClearSearch = { viewModel.setSearchQuery("") },
                        segments = if (openPlatformIsFavorites) primary.favoriteSegments else emptyList(),
                        segmentCounts = primary.favoriteSegmentCounts,
                        activeSegment = primary.favoriteSegment,
                        onSelectSegment = { viewModel.setFavoriteSegment(it) },
                        // The five-percent light field veil protects ordinary header chrome. Only the
                        // focused game's Hero title receives a localized rail.
                        mediaContrastRail = false,
                    )
                    if (openPlatformIsFavorites) {
                        FavoritesSegmentedView(
                            games = primary.games,
                            activeSegment = primary.favoriteSegment,
                            nativeRows = primary.activeNativeFavoriteRows,
                            searchQuery = primary.searchQuery,
                            keyboardOpen = primary.keyboardOpen,
                            authority = authority,
                            focused = primary.focused,
                            supportLabelForGame = { row -> platformLabel(libraryRes, primary.platforms, row.platformId) },
                            launchStatusForGame = { row -> primary.platformLaunchStatusLines[row.platformId] },
                            detailGame = primary.detailGame,
                            launchState = primary.launchState,
                            platforms = primary.platforms,
                            onOpenGame = openEmulatedDetail,
                            onLaunchNativePackage = openNativeDetailOrLaunch,
                            canFetchArt = canFetchArt,
                            steamGridDbKeyPresent = steamGridDbKeyPresent,
                            onFetchArt = { row ->
                                viewModel.focusMetadata(row)
                                authority.requestFocus(GamelistGetArtKey)
                                onFetchArtForGame(row)
                            },
                            onCustomizeHero = { row ->
                                viewModel.focusMetadata(row)
                                authority.requestFocus(GamelistGetArtKey)
                                onCustomizeHeroForGame(row)
                            },
                            transitionCancellationEpoch = transitionCancellationEpoch,
                            departureFrame = departureFrame,
                            shatter = shatterStage,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        val density = LocalDensity.current
                        val listBottomControlInset = with(density) {
                            gamelistListBottomControlInsetDp(
                                hintTextHeightDp = MimirTypeScale.ControlHintCompact.fontSize.toDp().value,
                            ).dp
                        }
                        GamelistViewModeLandingTransition(
                            mode = primary.viewMode,
                            modifier = Modifier.weight(1f),
                            settle = settleGamelistOpen,
                            // The Column's own horizontal padding: the carousel's side cards paint into it.
                            horizontalBleed = LibraryGamelistBodyPaddingDp,
                            favoritesOnly = primary.favoritesOnly,
                            platformId = primary.openPlatform,
                        ) { presentedMode ->
                            when (presentedMode) {
                                // the Library's game shelf IS the Home shelf (GameShelf), with Home's title block
                                // above it carrying the title, the status chip and the meta line. Customize media, Add to Tome and
                                // Achievements are in the menu Y opens (and the Edit menu), SELECT marks a favourite: nothing is painted
                                // under the strip.
                                GamelistViewMode.CAROUSEL -> LibraryGameShelf(
                                    games = primary.games,
                                    emptyMessage = stringResource(R.string.library_gamelist_empty_platform),
                                    isLoading = libraryGamelistShowsLoader(
                                        gamesLoaded = primary.gamesLoaded,
                                        gamesEmpty = primary.games.isEmpty(),
                                        withinSwitchGrace = withinPlatformSwitchGrace,
                                    ),
                                    searchQuery = primary.searchQuery,
                                    authority = authority,
                                    focusState = gamelistFocus,
                                    centeredKey = primary.focused,
                                    getArtFocused = primary.focused == GamelistGetArtKey,
                                    detailGame = primary.detailGame,
                                    metaForGame = { row ->
                                        carouselMetaLine(row, primary.platformLaunchStatusLines[row.platformId])
                                    },
                                    statusForGame = { row ->
                                        carouselStatusChip(primary.platformLaunchStatusLines[row.platformId])
                                    },
                                    onOpenGame = openEmulatedDetail,
                                    mediaContrastRail = lightMediaContrast,
                                    bottomControlInset = listBottomControlInset,
                                    // A platform, the favourites filter and a search each start the shelf afresh; L2/R2 between platforms lands each where it
                                    // was left. A search is ONE state, not one per typed letter (the departure tracker's scope above reads the text), and it is any
                                    // active Search criteria: a typed query or a genre or platform filter.
                                    // Remembered: the key is a fresh Triple on every call, and a parameter that is a new instance on every pass is a changed one (owperf).
                                    windowMemoryKey = rememberLibraryShelfWindowMemoryKey(
                                        openPlatform = primary.openPlatform,
                                        favoritesOnly = primary.favoritesOnly,
                                        searching = searchCriteria.query.isNotBlank() || searchCriteria != LibrarySearchCriteria(),
                                    ),
                                    departureFrame = departureFrame,
                                    shatter = shatterStage,
                                    modifier = Modifier.fillMaxSize(),
                                    searchCriteria = searchCriteria,
                                    gameKeys = primary.gameKeys,
                                )
                                GamelistViewMode.LIST -> GamelistView(
                                    // R4 names this surface: the platform gamelist's LIST view.
                                    games = primary.games,
                                    emptyMessage = stringResource(R.string.library_gamelist_empty_platform),
                                    // The SAME answer the carousel is given four lines above. One
                                    // predicate, both views -- the shared loader gate applied to
                                    // the field it was left off.
                                    isLoading = libraryGamelistShowsLoader(
                                        gamesLoaded = primary.gamesLoaded,
                                        gamesEmpty = primary.games.isEmpty(),
                                        withinSwitchGrace = withinPlatformSwitchGrace,
                                    ),
                                    searchQuery = primary.searchQuery,
                                    favoritesOnly = primary.favoritesOnly,
                                    keyboardOpen = primary.keyboardOpen,
                                    authority = authority,
                                    focused = primary.focused,
                                    supportLabelForGame = { row ->
                                        platformLabel(libraryRes, primary.platforms, row.platformId)
                                    },
                                    launchStatusForGame = { row ->
                                        primary.platformLaunchStatusLines[row.platformId]
                                    },
                                    detailGame = primary.detailGame,
                                    launchState = primary.launchState,
                                    platforms = primary.platforms,
                                    onOpenGame = openEmulatedDetail,
                                    canFetchArt = canFetchArt,
                                    steamGridDbKeyPresent = steamGridDbKeyPresent,
                                    onFetchArt = { row ->
                                        viewModel.focusMetadata(row)
                                        authority.requestFocus(GamelistGetArtKey)
                                        onFetchArtForGame(row)
                                    },
                                    onCustomizeHero = { row ->
                                        viewModel.focusMetadata(row)
                                        authority.requestFocus(GamelistGetArtKey)
                                        onCustomizeHeroForGame(row)
                                    },
                                    bottomControlInset = listBottomControlInset,
                                    departureFrame = departureFrame,
                                    shatter = shatterStage,
                                    modifier = Modifier.fillMaxSize(),
                                    gameKeys = primary.gameKeys,
                                )
                            }
                        }
                    }
                    // Control hints live in the bounded HintRailButton overlay; positional progress is absent.
                }
                }
              }
              if (primary.openPlatform != LibraryViewModel.FAVORITES_PLATFORM_ID) {
                  GamelistViewModeHint(
                      mode = primary.viewMode,
                      modifier = Modifier
                          .align(Alignment.BottomEnd)
                          .padding(
                              end = MimirSpacing.lg,
                              bottom = MimirSpacing.lg + HintButtonSize + HintRailButtonGap,
                          ),
                  )
              }
            } // close CP05 backdrop Box
        }
        }
        }
        }
    }

    // Dedicated Search is a retained-gamelist child surface. Its own reducer feeds both results and dispatch;
    // this layer never applies another UI-side filter or fabricates native Android facets.
    LibraryOverlayPixelFadeTransition(
        // Stable identity: query/filter/focus copies must never replay the whole Search entrance.
        targetState = librarySearchTransitionKey(librarySearchSession),
        transitionCancellationEpoch = transitionCancellationEpoch,
    ) {
        val activeSession = librarySearchSession ?: return@LibraryOverlayPixelFadeTransition
        LibrarySearchOverlay(
            session = activeSession,
            criteria = searchCriteria,
            facets = searchFacets,
            games = games,
            platforms = platforms,
            picker = librarySearchPicker,
            authority = authority,
            // The overlay draws the pad's own key, which the screen's focus drops for the card and the pickers; this branch runs only while Search is up.
            focused = modelState.hostFocus.raw.value.focused,
            onOpenKeyboard = {
                viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Query)
                viewModel.openSearch()
            },
            onOpenPicker = { picker ->
                viewModel.rememberLibrarySearchFocus(
                    when (picker) {
                        LibrarySearchPicker.GENRE -> LibrarySearchFocusIdentity.Genre
                        LibrarySearchPicker.PLATFORM -> LibrarySearchFocusIdentity.Platform
                    },
                )
                if (viewModel.openLibrarySearchPicker(picker)) {
                    authority.rememberFocusForScope(
                        LibrarySearchFilterPickerScope,
                        librarySearchSelectedPickerKey(picker, searchCriteria, searchFacets),
                    )
                    authority.pushScope(LibrarySearchFilterPickerScope)
                }
            },
            onSelectGenre = { filter ->
                viewModel.setSearchGenre(filter)
                closeLibrarySearchPicker(authority, viewModel)
            },
            onSelectPlatform = { filter ->
                viewModel.setSearchPlatform(filter)
                closeLibrarySearchPicker(authority, viewModel)
            },
            onClear = {
                viewModel.clearSearchCriteria()
                authority.requestFocus(LibrarySearchQueryControlKey)
                viewModel.rememberLibrarySearchFocus(LibrarySearchFocusIdentity.Query)
            },
            onActivateGame = { game ->
                activateLibrarySearchGame(
                    authority = authority,
                    viewModel = viewModel,
                    game = game,
                    onOpenLibraryDetail = onOpenLibraryDetail,
                )
            },
            onDismissPicker = { closeLibrarySearchPicker(authority, viewModel) },
        )
    }

    //
    //
    // `MimirLiquidGlassPanel` already thins its own body to 0.78 alpha *because it expects a blur behind
    // it* (`MimirGlass.supportsBlur`), and `mimirGlassBackdrop`/`mimirGlassBackdropProgress` have existed
    // in core-ui the whole time -- but no host applied one under a select overlay. A translucent panel
    // over a SHARP layer is what made the menu read muddy: the detail card's own GENRE / LAST PLAYED text
    // came straight through it.
    //
    // Applied to the DETAIL-CARD layer, which is what actually sits behind these two modals -- not by
    // widening `sceneGlassProgress` above. That Animatable blurs the GAMELIST, its identity is
    // deliberately shared between detail and search, and it already reads 1 by the time a picker opens
    // over the card; adding a second meaning to it would stack two blurs under nested clips, which is
    // the exact failure its own B->B race guard exists to prevent.
    //
    // Scoped to the two pickers that ARE this shell (`MimirSelectOverlay`). The hide/native-add
    // confirmations are a different surface and a separate transition policy.
    //
    // the glass was asked to clear
    // on the frame the commit popped the scope, so the card un-blurred over 180 ms while the picker was still fading over the
    // same 180 ms, and two full-screen blurs ran at once (7-8.5 ms a frame). "Dissolve, then clear" : the glass
    // follows the scope plus the host's own hold, as the card's chrome does above, and clears once the picker is gone.
    val selectGlassHeld = rememberMimirGlassHold(
        requested = focusState.scope == OpenWithScope || focusState.scope == LaunchChoiceScope,
        cancelKey = transitionCancellationEpoch,
    )
    val selectOverlayGlass = rememberMimirGlassProgress(visible = selectGlassHeld)
    // One live blur under pickers: while a picker is UP the card behind it is held as a snapshot (it is recorded when the glass first shows, not on
    // every frame of the ramp), so the scene's glass is the one blur that renders live. The picker's request, not the held glass, freezes it: when the pick commits the card
    // is live again at once and may change (the emulator row) while the glass clears over it. Read in the draw phase.
    val pickerFocusState = modelState.focusStateState
    val pickerCardGlass = remember(selectOverlayGlass, pickerFocusState) {
        LibraryCardGlass(
            progress = selectOverlayGlass,
            snapshot = { pickerFocusState.value.scope.let { it == OpenWithScope || it == LaunchChoiceScope } },
        )
    }
    LibraryOverlayPixelFadeTransition(
        targetState = renderedDetailState?.itemId?.toString(),
        transitionCancellationEpoch = transitionCancellationEpoch,
        glass = pickerCardGlass,
        // A launch return restores the card under the return cover and is already the finished card when the cover lifts.
        initialArrivalFade = !libraryDetailArrivalSettled(viewModel.restoringLaunchReturnTarget()),
    ) {
        val activeDetailState = renderedDetailState ?: return@LibraryOverlayPixelFadeTransition
        val closeDetail = {
            val target = activeDetailState.target
            viewModel.closeGameDetail()
            onCloseLibraryDetail(target)
            if (focusState.scope == GameDetailScope) exitLibraryDetail(authority, target)
        }
        when (activeDetailState.target) {
            is LibraryDetailTarget.Emulated -> {
                // RC-16: resolved on IO when the card opens, not on its first frame (L-20: 49.9 ms), and the last face is kept while a new
                // answer lands (see rememberLibraryLaunchCardFace).
                val launchCardFace by rememberLibraryLaunchCardFace(
                    game = activeDetailState.target.game,
                    target = activeDetailState.target,
                    resolve = cartridgeArtUriForGame,
                    memory = launchCardFaces,
                )
                // the achievement count lives on the launch card ONLY, fetched when the card opens (a fresh read, fenced and
                // cancelled by the lane). Moving through the shelf or the list asks RetroAchievements for nothing.
                val probeForLaunchCard by rememberUpdatedState(onProbeAchievements)
                LaunchedEffect(gamelistAchievementProbeKey(activeDetailState.target.game)) {
                    probeForLaunchCard(activeDetailState.target.game)
                }
                GameDetailScreen(
                viewModel = viewModel,
                authority = authority,
                actionState = activeDetailState,
                achievementProgress = achievementLabelForGame(activeDetailState.target.game),
                statusLine = resolveLibraryDetailLaunchStatusLine(
                    platformStatusLine = platformLaunchStatusLines[activeDetailState.target.game.platformId],
                    launchState = launchState,
                    detailGameId = detailGame?.gameId,
                    targetGameId = activeDetailState.target.game.gameId,
                    platformLabelFor = { platformLabel(libraryRes, platforms, it) },
                    emulatorLabel = launchEmulatorLabel,
                ),
                runtimeStatusLine = resolveLibraryDetailLaunchRuntimeStatusLine(
                    launchState = launchState,
                    launchOwnerGameId = launchStateOwner,
                    targetGameId = activeDetailState.target.game.gameId,
                    platformLabelFor = { platformLabel(libraryRes, platforms, it) },
                    emulatorLabel = launchEmulatorLabel,
                ),
                screenScraperConnected = canFetchArt,
                steamGridDbKeyPresent = steamGridDbKeyPresent,
                // Keyed on the whole target, not just its gameId: the GameRow's own identity changes when
                // enrichment writes new media, so a card left open across a fetch re-asks and repaints
                // instead of holding the pre-enrichment answer. Keyed on the lambda too, because :app
                // rebuilds it when the Utility control is stepped -- that is how a switch change takes
                // effect here.
                // SUPERSEDED 2026-08-25 by C1: the view you opened from no longer decides the card's
                // face. :app answers for the LAUNCH CARD state -- his pick if he wired one, otherwise the
                // article, otherwise the Shelf's resolved face (FL-2) -- so the same game wears the same
                // face whichever view reached it, which is the whole point of the state axis.
                // 2026-08-26: the answer now carries its KIND as well as its uri, resolved together so
                // they cannot disagree; L3 can hand over a COVER and the card must not square it off.
                cartridgeArtUri = launchCardFace?.uri,
                cartridgeArtIsPhysical = launchCardFaceIsPhysical(launchCardFace?.physical),
                onOpenAchievements = { selected -> onOpenAchievements(selected.game) },
                onEditTitle = { selected -> onEditGameTitle(selected.game) },
                descriptionScrollOwnerToken = detailDescriptionOwnerToken,
                descriptionScrollCommand = detailDescriptionScrollCommand,
                onDescriptionScrollStateChanged = onDetailDescriptionScrollStateChanged,
                onClose = closeDetail,
                modifier = Modifier.fillMaxSize(),
            )
            }
            is LibraryDetailTarget.AndroidGame -> GameDetailScreen(
                actionState = activeDetailState,
                authority = authority,
                onPlay = { selected ->
                    (selected as? LibraryDetailTarget.AndroidGame)?.row?.packageName
                        ?.let { packageName ->
                            launchNativePackage(
                                viewModel = viewModel,
                                packageName = packageName,
                                onLaunchNativePackage = onLaunchNativePackage,
                                restoreLaunchCard = true,
                            )
                        }
                },
                descriptionScrollOwnerToken = detailDescriptionOwnerToken,
                descriptionScrollCommand = detailDescriptionScrollCommand,
                onDescriptionScrollStateChanged = onDetailDescriptionScrollStateChanged,
                onClose = closeDetail,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    // Provider selection is a detail-metadata modal and returns to its exact predecessor row; the modals, the hint
    // button, the keyboard sheet and the toast follow it in LibraryScreenModalsAndHints.
    LibraryScreenModalsAndHints(
        viewModel = viewModel,
        authority = authority,
        nativeGameRows = nativeGameRows,
        nativeAppRows = nativeAppRows,
        nativeAddPickerMode = nativeAddPickerMode,
        nativeAddCandidates = nativeAddCandidates,
        nativeAddSearchQuery = nativeAddSearchQuery,
        nativeAddKeyboardOpen = nativeAddKeyboardOpen,
        onNativeAddSearchChange = onNativeAddSearchChange,
        onOpenNativeAddSearch = onOpenNativeAddSearch,
        onCloseNativeAddSearch = onCloseNativeAddSearch,
        onChooseNativeAddPackage = onChooseNativeAddPackage,
        onDismissNativeAddPicker = onDismissNativeAddPicker,
        pendingNativeHidePackage = pendingNativeHidePackage,
        onConfirmHideNativePackage = onConfirmHideNativePackage,
        onCancelHideNativePackage = onCancelHideNativePackage,
        transitionCancellationEpoch = transitionCancellationEpoch,
        navigationHintsEnabled = navigationHintsEnabled,
        parkedRefusalOnHostRail = parkedRefusalOnHostRail,
        foundation = foundation,
        modelState = modelState,
        snapshots = snapshots,
    )
}

/** OR-8: what the Library's toast says when a game's Favorite changes. */
internal fun libraryFavoriteToastText(nowFavorite: Boolean): String =
    if (nowFavorite) "Added to Favorites" else "Removed from Favorites"

internal fun libraryNavigationHintRailVisible(
    navigationHintsEnabled: Boolean,
    keyboardOpen: Boolean,
    scope: FocusScope,
): Boolean = navigationHintsEnabled &&
    !keyboardOpen &&
    (scope == LibraryShelfScope || scope == GamelistScope)


/** RP5's full wifi/battery/clock rail plus clearance, measured inside Library's existing 32 dp edge inset. At font 1.0: at larger fonts the pill grows, see [gamelistStatusRailDp]. */
internal const val LibraryGamelistStatusEndInsetDp = 136f

/** The glance row stands this far in from the screen's edge (the host's `padding(16.dp)`), inside the Library's own 32 dp body inset. */
internal const val LibraryGamelistGlanceEndInsetDp = 16f

/** What the 136 dp reserve leaves between the header's end and the status pill at font 1.0 (the pill is 149 dp there: 149 - (32 - 16) + 2.5 = 136), kept at every font scale. */
internal const val LibraryGamelistStatusClearanceDp = 2.5f

/**
 * The header's reserve for the status pill, in dp from the header's end, for a pill [statusPillWidthDp] wide ([com.gruning.mimir.rewrite.core.ui.mimirLiquidStatusPillWidthDp]). The pill is
 * seated at the screen's edge (16 dp in), the header's end is the body inset (32 dp) in, so the pill reaches `width - 16 + clearance` into the header. Never under the 136 dp measured at
 * font 1.0: the pill's text is in sp and grows with the font (166 dp at 1.3x), and a reserve fixed at 1x let Search run under it (combined33 sweep: 832.5 dp Main, 1.3x, Favorites on).
 */
internal fun gamelistStatusRailDp(statusPillWidthDp: Float): Float = maxOf(
    LibraryGamelistStatusEndInsetDp,
    statusPillWidthDp - (LibraryGamelistBodyPaddingDp.value - LibraryGamelistGlanceEndInsetDp) + LibraryGamelistStatusClearanceDp,
)
internal const val LibraryGamelistHeaderGapDp = 10f
internal const val LibraryGamelistSearchReadabilityFloorDp = 120f
internal const val LibraryGamelistSearchControlMinHeightDp = 44f

/**
 * The header's count label ("Favorites · 10 games", "17 games", "3 apps") seat. The ceiling is what both branches
 * give it when the row has the room; the compact floor is what the label keeps on the smallest surfaces, where it
 * yields to Search's readability floor and ellipsizes first.
 *
 * Measured, not guessed (GamelistHeaderCountFitsTest, native text at the real header): "Favorites · 10 games" in
 * [MimirType.RowSupport] is 134 dp at the Nova's density (2.0) and 125.5 dp at the RG's (1.275), the spread being
 * per-glyph pixel rounding. The light-contrast rail pads the label 6 dp a side inside the seat. So the seat is
 * 134 + 12 + 6 dp of rounding margin for the densities in between: the old 132 cut the label at the Nova's density
 * on either branch, and inside the rail at every density.
 *
 * That is at font scale 1.0. The header sets the seat at `ceiling x max(1, font scale)` ([resolveGamelistHeaderSearchGeometry]'s `fontScale`):
 * the label grows with the font, and at 1.3x the 152 dp seat cut "Favorites · 2 games" to "Favorites · 2…" (sweep 10-11).
 */
internal const val LibraryGamelistMetadataCeilingDp = 152f
internal const val LibraryGamelistCompactMetadataFloorDp = 90f

/**
 *  On compact the pill is drawn 100 dp wide and
 * 32 dp tall with a one-word placeholder, inside the same [LibraryGamelistSearchControlMinHeightDp] touch area, so
 * it stays easy to tap. Standard screens keep the 136 dp, 44 dp pill.
 */
internal const val LibraryGamelistCompactSearchWidthDp = 100f
internal const val LibraryGamelistCompactSearchPillHeightDp = 32f
internal const val LibraryGamelistCompactSearchPlaceholder = "Search"

/** The glance row's clearance key for the gamelist's Search pill: the song-title chip stays off it (sweep 10-09). */
internal const val GamelistSearchGlanceKey = "gamelist-search"

/** The Search pill when its label cannot stand whole even at the type floor: the magnifier alone, the same 48 dp mark Customize media's Search shrinks to . */
internal const val LibraryGamelistSearchMarkWidthDp = 48f

/**
 * The size, in sp, the Search pill's placeholder is set at: the largest from [maxSp] down to [minSp] in half-sp steps at which [fits] says the whole label stands in the room the pill
 * leaves it, or null when no size does, which is the pill's cue to be the magnifier alone. Pure, so a test drives it with any measure.
 */
internal fun gamelistSearchLabelSizeSp(maxSp: Float, minSp: Float, fits: (Float) -> Boolean): Float? {
    val floor = minSp.coerceAtMost(maxSp)
    var size = maxSp
    while (size > floor) {
        if (fits(size)) return size
        size -= 0.5f
    }
    return if (fits(floor)) floor else null
}

/** Whether the gamelist draws its small Search pill: compact, read from the root profile in its strict form. */
internal fun gamelistSearchPillIsSmall(profile: MimirViewportProfile): Boolean =
    profile.widthDp > 0f && profile.isCompact

internal fun libraryGamelistHeaderSearchVisible(
    dedicatedSearchEnabled: Boolean,
    hasVisibleRows: Boolean,
    query: String,
): Boolean = dedicatedSearchEnabled || hasVisibleRows || query.isNotBlank()

/**
 * Pure header-seat solver. Title, count, and Search stay one left-packed cluster while the measured status
 * envelope remains empty. Compact viewports keep title and controls on adjacent rows inside the same envelope.
 */
internal data class GamelistHeaderSearchGeometry(
    val searchWidthDp: Float,
    val showSearch: Boolean,
    val statusEndInsetDp: Float,
    val metadataMaxWidthDp: Float,
    val titleMaxWidthDp: Float,
    val itemGapDp: Float,
)

/**
 * [searchMarkOnly]: Search is the [LibraryGamelistSearchMarkWidthDp] magnifier alone (what Customize media's Search does since the earlier update), which is the room the header gives the title back
 * when its first word cannot stand beside the tag, the whole count and a full pill (the RP5 at font 1.3, round 2). The magnifier is seen at its own width, not the readability floor of a pill
 * with a label.
 */
internal fun resolveGamelistHeaderSearchGeometry(
    availableWidthDp: Float,
    compact: Boolean,
    fontScale: Float = 1f,
    searchMarkOnly: Boolean = false,
    /** The reserve for the status pill, from its real width at the current font ([gamelistStatusRailDp]); the 1x constant where the caller has not measured. */
    statusRailDp: Float = LibraryGamelistStatusEndInsetDp,
): GamelistHeaderSearchGeometry {
    // Compact layout already puts title and meta/Search on separate rows, so the top-end status rail does not
    // subtract from the second row. Wide layout shares the row and retains the full status reservation.
    val statusEndInset = if (compact) 0f else statusRailDp
    val itemGap = LibraryGamelistHeaderGapDp
    val usable = (availableWidthDp - statusEndInset).coerceAtLeast(0f)
    // R6-P2-06 : the wide/shared-row branch used to pin its OWN, independently-tuned,
    // lower ceiling (120f == LibraryGamelistSearchReadabilityFloorDp, zero headroom above the floor) while
    // the compact branch's ceiling was 136f -- so Odin/Thor (both land here, ~768.5dp available after the
    // 32dp shell inset) got a NARROWER "Search games" pill than the physically smaller Nova (compact
    // branch) -- the exact inversion the regression report described ("in odin/thor viewport search game in library
    // gamelist is clipped (ironically in nova it isn't)"). Both branches carry ample headroom above 136f
    // at every certified wide viewport (see LibraryShelfStabilizationContractTest), so the wide branch now
    // shares the same ceiling instead of a lower one of its own.
    val searchCeiling = if (searchMarkOnly) LibraryGamelistSearchMarkWidthDp else 136f
    // Wide handhelds had enough aggregate room, but the previous 104/148 split clipped
    // "Favorites · 10 games" while leaving Search visibly oversized, so the metadata seat took 132 dp.
    // R6-P2-06 then raised the wide Search ceiling to the compact 136 dp (Odin and Thor clipped it at
    // 120 dp), so the wide envelope is now up to 268 dp; the row's headroom still bounds it below.
    //
    // Walk-32i (lead, emulator at the Nova's and the RG's geometry, 2026-09-30): compact pinned the label
    // at 90 dp whatever the row had, so with Favorites only on the count was cut to "Favorites · …" on
    // the Nova and "Favorites · 1 …" on the RG while the rest of the second row stood empty. Compact puts
    // the title on its own row, so the second row is the whole width: the label may take what is left
    // after a full Search seat, floored at the old 90 dp (the smallest surfaces still hand Search its
    // readability floor first, and the label ellipsizes first there, as before) and capped at the one
    // ceiling both branches share. Measuring that ceiling at the real header found the 132 short too:
    // "Favorites · 10 games" is 134 dp at the Nova's density, so the wide branch's seat moves with it
    // (see LibraryGamelistMetadataCeilingDp); its title keeps over 300 dp on the Odin and the Thor.
    //
    // Sweep 10-11 (P3): the ceiling was measured at font scale 1.0, and the label grows with the font ("Favorites · 2 games" is about 154 dp at 1.3x), so at 1.3x the 152 dp seat cut
    // the count to "Favorites · 2…" while the row had room beside it. The seat is the ceiling at the font's size, never below the 1x one, so a count stands whole like the "Local
    // library" tag and it is the title, measured last, that gives way at a word. The compact floor stays: the smallest surfaces still hand Search its seat first.
    val metadataCeiling = LibraryGamelistMetadataCeilingDp * fontScale.coerceAtLeast(1f)
    val metadataCap = if (compact) {
        (usable - searchCeiling - itemGap).coerceIn(LibraryGamelistCompactMetadataFloorDp, metadataCeiling)
    } else {
        metadataCeiling
    }
    val searchHeadroom = (usable - metadataCap - itemGap).coerceAtLeast(0f)
    val searchCandidate = minOf(searchCeiling, searchHeadroom)
    val showSearch = availableWidthDp >= 240f &&
        searchCandidate >= (if (searchMarkOnly) LibraryGamelistSearchMarkWidthDp else LibraryGamelistSearchReadabilityFloorDp)
    val searchWidth = if (showSearch) searchCandidate else 0f
    val searchSeat = if (showSearch) searchWidth + itemGap else 0f
    val titleMaxWidth = if (compact) {
        // The second-row Search uses the full compact width, but the title above it still yields to the
        // wifi/battery/clock envelope.
        (availableWidthDp - statusRailDp).coerceAtLeast(0f)
    } else {
        (usable - metadataCap - itemGap - searchSeat).coerceAtLeast(0f)
    }
    return GamelistHeaderSearchGeometry(
        searchWidthDp = searchWidth,
        showSearch = showSearch,
        statusEndInsetDp = statusEndInset,
        metadataMaxWidthDp = metadataCap,
        titleMaxWidthDp = titleMaxWidth,
        itemGapDp = itemGap,
    )
}

// ── Platforms view ────────────────────────────────────────────────────────────────────────────────
@Composable
private fun PlatformsView(
    platforms: List<PlatformRow>,
    favoritesCount: Int,
    shelfMode: LibraryShelfMode,
    nativeGameRows: List<NativeShelfRow>,
    nativeAppRows: List<NativeShelfRow>,
    authority: FocusAuthority,
    focused: FocusKey?,
    transitionCancellationEpoch: Long,
    onRescanFolder: () -> Unit,
    onLaunchNativePackage: (String) -> Unit,
    onOpenNativeAddPicker: (LibraryShelfMode) -> Unit,
    onSelectShelfMode: (LibraryShelfMode) -> Unit,
    onOpenPlatform: (PlatformId) -> Unit,
    rememberedKeysByMode: Map<LibraryShelfMode, FocusKey>,
    onCenter: (FocusKey) -> Unit,
    quickRailContent: @Composable () -> Unit,
    /** Utility's Navigation hints: the shelf's Guidance button. */
    navigationHintsEnabled: Boolean = true,
    /** OR-8: the L3 request count the shelf's Guidance button answers. */
    hintLegendRequest: Int = 0,
    /** The step of the last mode change: +1 for R1 (in from the right), -1 for L1. Read when the change lands. */
    shelfModeStep: () -> Int = { 1 },
    /** A launch return is restoring the Library: the mode change it may carry (a Favorites-launched Android game) glides nowhere under the cover. */
    pageRestoreSettled: () -> Boolean = { false },
    // the dispatch reducer's mode-switch stamp (`LibraryDispatch.kt`'s
    // `cycleShelfModeWithFocusStamp`) -- the ViewModel-level `centeredShelfKey`, which (unlike
    // FocusAuthority's own `focusMemory`) survives the outgoing ring's Compose dispose cascade. See the
    // landing `LaunchedEffect` below for how this backs up `rememberedKeysByMode`.
    centeredShelfKey: FocusKey? = null,
    /** Walk-32h: the stage an Android tile's hide is captured for and broken over. The emulated shelf does not hide. */
    shatter: LibraryShatterStage? = null,
) {
    // The console shelf: a fixed, full-screen
    // layout, NOT a page scroll — identity header + mode strip pin to the top, the centered coverflow floats,
    // the control legend pins to the bottom. Platform tiles + auto-centering live inside ShelfCoverflow,
    // which reuses THIS shelf's scope + key scheme so the existing controller dispatch (dispatchToLibrary) drives it unchanged.
    val totalGames = platforms.sumOf { it.gameCount }
    val timeLabel by produceState("") {
        while (true) {
            value = shelfClock()
            kotlinx.coroutines.delay(20_000)
        }
    }
    // Land the HERO on a real platform on entry. The coverflow's tiles register after the mode-row's Add
    // pill, so auto-focus would otherwise sit on the pill (or nothing) and no tile would bloom. Guarded so a
    // focus RESTORED on return from a gamelist (already a platform key) is never overridden.
    val nativeRowsForMode = when (shelfMode) {
        LibraryShelfMode.EMULATION -> emptyList()
        LibraryShelfMode.ANDROID_GAMES -> nativeGameRows
    }
    // SH01 removed inventory/progress copy from the identity line, leaving the clock as the right anchor.
    // Item 4  adds the full status pill (wifi/battery/clock) in the top-right corner — the
    // same one Home shows — so the header's bare clock is now redundant AND overlaps the pill. Blank the header
    // stat so the pill owns that corner (Home has no header clock either); the pill carries the time now.
    val headerStatLabel = ""
    val modeStopKeys = remember(platforms, shelfMode, nativeRowsForMode) {
        when (shelfMode) {
            LibraryShelfMode.EMULATION -> platforms.map { platformKey(it.platformId) }
            LibraryShelfMode.ANDROID_GAMES -> nativeRowsForMode.map { nativeShelfKey(it.packageName) }
        }
    }
    val preferredCenterKey = rememberedKeysByMode[shelfMode]
    // Console progress count : the centred stop's 1-based position among the current mode's
    // stops, shown bottom-left. Reads the live focus when it is a real tile, else the remembered centre, so the
    // count holds steady while focus is parked on the Add pill.
    val centeredStopKey = focused?.takeIf { it in modeStopKeys }
        ?: preferredCenterKey?.takeIf { it in modeStopKeys }
    val consolePosition = centeredStopKey?.let { modeStopKeys.indexOf(it) + 1 }
    // Item 2 ("switching platforms fights the icon focus"): read the centre through rememberUpdatedState so a
    // per-tile centre change does NOT restart this landing effect. It must run on ENTRY and MODE change only —
    // re-requesting focus every time the coverflow re-centres on a neighbour tile IS the observed focus fight. modeStopKeys is stable across tile moves (keyed on platforms + mode), so the tile row stays put.
    val preferredCenterKeyState = rememberUpdatedState(preferredCenterKey)
    // Policy 2026-09-02 "MEMORY WINS" (see shelfModeRememberedLandingKey's kdoc): back up the
    // render's own per-mode memory (preferredCenterKeyState -- built from a real PREVIOUS visit to this
    // mode) with the dispatch reducer's mode-switch stamp (centeredShelfKey --
    // LibraryDispatch.kt's cycleShelfModeWithFocusStamp), for the first-ever visit to a mode this
    // session, where rememberedKeysByMode has nothing yet. Membership-guarded against THIS mode's stops
    // so a stamp from a stale/different mode can never leak in.
    val centeredShelfKeyState = rememberUpdatedState(centeredShelfKey)
    LaunchedEffect(authority, shelfMode, modeStopKeys) {
        // The outgoing mode unregisters its virtual row in the same apply pass that the incoming row mounts.
        // Wait for registration, then restore this mode's valid memory. A first stop is used only when no valid
        // memory remains. If an app-level async return has already landed any incoming native row, yield to it:
        // that focus is newer and more specific than this composable's fallback.
        repeat(ShelfModeFocusLandingAttempts) {
            withFrameNanos { }
            val f = authority.state.value
            if (f.scope != LibraryShelfScope) return@LaunchedEffect
            val target = shelfModeLandingTarget(
                focusedKey = f.focused,
                rememberedKey = shelfModeRememberedLandingKey(
                    renderMemory = preferredCenterKeyState.value,
                    dispatchStamp = centeredShelfKeyState.value,
                    orderedStopKeys = modeStopKeys,
                ),
                orderedStopKeys = modeStopKeys,
                emptyStopKey = AddFolderKey,
            ) ?: return@LaunchedEffect
            authority.requestFocus(target)
            val focusedAfterRequest = authority.state.value.focused
            if (focusedAfterRequest == target || focusedAfterRequest in modeStopKeys) return@LaunchedEffect
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = rememberMeasuredViewport(
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            inDomain = { widthDp, heightDp ->
                widthDp.isFinite() && heightDp.isFinite() && widthDp > 0f && heightDp > 0f
            },
            beforeFirstValid = MimirBeforeFirstValidViewport.RENDER_NOTHING,
        )
        if (!viewport.shouldRender) return@BoxWithConstraints

        val primaryViewportProfile = LocalMimirViewportProfile.current
        val compactOverride = primaryViewportProfile.widthDp.takeIf { it > 0f }?.let { primaryViewportProfile.isCompact }
        // on a 4:3 or square field the Default sun moves below the text
        // column. Decided from the same profile as `compactOverride`, never from a device model.
        val sceneLightBelowText = if (primaryViewportProfile.widthDp > 0f) {
            primaryViewportProfile.sceneLightBelowText
        } else {
            mimirViewportProfile(viewport.width.value, viewport.height.value).sceneLightBelowText
        }
        val geometry = remember(viewport.width, viewport.height, primaryViewportProfile) {
            solveLibraryShelfGeometry(
                viewport.width.value,
                viewport.height.value,
                compactOverride = compactOverride,
            )
        }
        // LIB-QR-1: captured here (this boundary's own un-padded decision height) so the sibling quick-rail
        // Box below can reproduce its old in-Column Y position without reading outer constraints through the
        // MimirShelfBackground content lambda's different (BoxScope) receiver.
        val libraryViewportHeightDp = viewport.height.value
        MimirShelfBackground(
            modifier = Modifier
                .mimirMeasuredViewport(viewport)
                .fillMaxSize()
                .testTag(LibraryShelfRootTestTag),
            showAtmosphericClouds = LibraryShelfAtmosphericClouds,
            // on a compact square the Default sun sits under the dock; dimmed, not moved.
            quietSceneLight = geometry.compactSquare,
            sceneLightBelowText = sceneLightBelowText,
        ) {
        val libraryPalette = LocalMimirPalette.current
        val footerShadow = remember(libraryPalette) {
            libraryShelfFooterShadowColor(libraryPalette)
        }
        if (footerShadow.alpha > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.00f to Color.Transparent,
                            0.74f to Color.Transparent,
                            1.00f to footerShadow,
                        ),
                    ),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = geometry.horizontalGutterDp.dp,
                    end = geometry.horizontalGutterDp.dp,
                    top = geometry.topPaddingDp.dp,
                    bottom = geometry.bottomPaddingDp.dp,
                ),
        ) {
            MimirIdentityHeader(
                platformCount = platforms.size,
                gameCount = totalGames,
                timeLabel = timeLabel,
                statLabel = headerStatLabel,
                sectionEyebrow = null,
                // Bleed the void/crimson molten separator past the shelf gutter to the physical screen edges so it
                // "wraps around the screen" , matching shipping's full-bleed `.eok-molten-sep`.
                bleed = geometry.horizontalGutterDp.dp,
                compact = geometry.compactSquare,
                modifier = Modifier.testTag(LibraryShelfHeaderTestTag),
            )
            Spacer(Modifier.height(geometry.headerToToolsGapDp.dp))
            ShelfHeadControlRow(
                shelfMode = shelfMode,
                focused = focused,
                authority = authority,
                onRescanFolder = onRescanFolder,
                onOpenNativeAddPicker = onOpenNativeAddPicker,
                onSelectShelfMode = onSelectShelfMode,
                compact = geometry.compactSquare,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(geometry.headRowHeightDp.dp)
                    .testTag(LibraryShelfToolsTestTag),
            )
            // The coverflow floats centred in the band between the mode strip and the footer legend.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ImmediateLibrarySnapshotTransition(
                    targetState = shelfMode,
                    transitionCancellationEpoch = transitionCancellationEpoch,
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                    // Motion audit batch C: EMULATION <-> ANDROID GAMES glides like a page, from the side of the
                    // shoulder that was pressed.
                    motion = { _, _ -> if (pageRestoreSettled()) LibraryPageMotion.Settled else LibraryPageMotion.Entry(direction = shelfModeStep()) },
                ) { presentedMode ->
                    val presentedRows = when (presentedMode) {
                        LibraryShelfMode.EMULATION -> emptyList()
                        LibraryShelfMode.ANDROID_GAMES -> nativeGameRows
                    }
                    val presentedCenterKey = rememberedKeysByMode[presentedMode]
                    when (presentedMode) {
                        LibraryShelfMode.EMULATION -> ShelfCoverflow(
                            compact = geometry.compactSquare,
                            platforms = platforms,
                            favoritesCount = 0,
                            focusedKey = focused,
                            authority = authority,
                            onOpenPlatform = onOpenPlatform,
                            scope = LibraryShelfScope,
                            keyBuilder = ::platformKey,
                            favoritesId = LibraryViewModel.FAVORITES_PLATFORM_ID,
                            preferredCenterKey = presentedCenterKey,
                            onCenter = onCenter,
                            heroBandHeight = geometry.heroBandHeightDp.dp,
                            artBandHeight = geometry.artBandHeightDp.dp,
                            titleBandHeight = geometry.titleBandHeightDp.dp,
                            homeSeatCount = geometry.homeSeatCount,
                            modifier = Modifier
                                .offset(y = ShelfCoverflowLift)
                                .testTag(LibraryShelfHeroBandTestTag),
                        )
                        LibraryShelfMode.ANDROID_GAMES -> NativeShelfCoverflow(
                            rows = presentedRows,
                            focusedKey = focused,
                            authority = authority,
                            mode = presentedMode,
                            onLaunchPackage = onLaunchNativePackage,
                            scope = LibraryShelfScope,
                            preferredCenterKey = presentedCenterKey,
                            onCenter = onCenter,
                            heroBandHeight = geometry.heroBandHeightDp.dp,
                            artBandHeight = geometry.artBandHeightDp.dp,
                            titleBandHeight = geometry.titleBandHeightDp.dp,
                            tileScale = geometry.tileScale,
                            tilePanel = { isCentered, packageName ->
                                rememberLibraryShatterPanel(
                                    shatter,
                                    active = isCentered,
                                    gameId = packageName,
                                    corner = PlatformHeroFaceCorner,
                                )
                            },
                            modifier = Modifier
                                .offset(y = ShelfCoverflowLift)
                                .testTag(LibraryShelfHeroBandTestTag),
                        )
                    }
                }
            }
            // Dock-size divergence: the rail no longer renders HERE --
            // this Column applies its own start/end padding (ShelfHorizontalGutter, 20dp each side in the
            // non-compact branch) BEFORE this point, so a rail rendered as a direct child measured its own
            // BoxWithConstraints maxWidth against the viewport minus 2x that gutter -- 40dp narrower than
            // Home's rail (HomeShelfScreen.kt), which measures the FULL raw viewport with no side gutter.
            // Two different `mimirQuickRailTargetWidth` results on the SAME device (the Nova: Home 368dp,
            // Library 352dp) -- the same class of defect fixed for dock POSITION earlier the same day
            // (homeShelfQuickRailBottomGapDp below). This Spacer reserves the exact same vertical lane the
            // rail used to occupy so the weighted coverflow Box above does not grow into it and re-center the
            // coverflow; the rail itself now renders as a Box sibling of this Column, below, at this
            // Column's own un-padded ancestor level -- see that Box's own comment.
            Spacer(Modifier.fillMaxWidth().height(ShelfQuickRailLaneHeight))
        }
        // The quick rail: a sibling of the Column above, not a child of it (LIB-QR-1) -- its fillMaxWidth()
        // measures the SAME un-padded raw viewport Home's rail does, restoring the shared
        // `mimirQuickRailTargetWidth` result invariant across both surfaces. The y offset reproduces the
        // exact position the Column used to give it: flush against the Column's bottom content edge, i.e.
        // `viewportHeight - bottomPaddingDp - RailHeight` -- and since `bottomPaddingDp` here already reads
        // `homeShelfQuickRailBottomGapDp` (the shared helper Home's own rail solves its top from), this stays
        // byte-identical to Home's `quickRailBand.top` by construction, not by coincidence.
        ShelfQuickRailHost(
            content = quickRailContent,
            modifier = Modifier.offset(
                y = (libraryViewportHeightDp - geometry.bottomPaddingDp - ShelfQuickRailLaneHeightDp).dp,
            ),
        )
        // Walk-32h: an Android tile that is hidden breaks apart over the shelf, above the coverflow. It is placed after the rail's
        // one call site, not between that call and the lane the Spacer above keeps: a pin holds that gap empty.
        LibraryShatterHost(shatter)
        // The accented, dock-captioned Guidance button opens only when pressed.
        if (navigationHintsEnabled) {
            HintRailButton(
                hints = shelfFooterHints(libraryResources(), nativeMode = shelfMode != LibraryShelfMode.EMULATION),
                contextKey = "shelf:" + shelfMode.name,
                revealRequest = hintLegendRequest,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = MimirSpacing.lg, bottom = MimirSpacing.lg),
            )
        }
        // Console progress count : the centred stop's position among the mode's stops, in the
        // bottom-left corner. Drawn as a BoxScope sibling of the shelf column so it pins to the physical corner.
        if (consolePosition != null && modeStopKeys.isNotEmpty()) {
            ShelfProgressCount(
                position = consolePosition,
                total = modeStopKeys.size,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = geometry.horizontalGutterDp.dp,
                        bottom = geometry.bottomPaddingDp.dp,
                    ),
            )
        }
        }
    }
}

@Composable
private fun ShelfProgressCount(
    position: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = position.toString().padStart(2, '0'),
            style = MimirTypeScale.MicroStatus,
            color = palette.accentBright,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = " / ${total.toString().padStart(2, '0')}",
            style = MimirTypeScale.MicroStatus,
            color = palette.textMuted,
        )
    }
}

@Composable
private fun ShelfQuickRailHost(
    content: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ShelfQuickRailLaneHeight)
            .testTag(LibraryShelfQuickRailHostTestTag),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun ShelfSectionLabel(label: String) {
    val palette = LocalMimirPalette.current
    MimirChromeText(
        text = label,
        style = MimirTypeScale.RunicEyebrow,
        color = palette.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Live wall-clock ("h:mm a") for the identity header, matching the shipping shelf's top-right clock. */
private fun shelfClock(): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date())

@Composable
private fun ShelfHeadControlRow(
    shelfMode: LibraryShelfMode,
    focused: FocusKey?,
    authority: FocusAuthority,
    onRescanFolder: () -> Unit,
    onOpenNativeAddPicker: (LibraryShelfMode) -> Unit,
    onSelectShelfMode: (LibraryShelfMode) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    // LIB-02c: full width — [CHOOSE A PLATFORM] · [beam ✦] · [mode tabs] ——spring—— [ADD GAMES, far right].
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!compact) {
            ShelfSectionLabel(stringResource(shelfMode.sectionLabelRes))
            Spacer(Modifier.width(ShelfHeadGap))
            ShelfHeadOrnament(Modifier.width(ShelfHeadOrnamentWidth))
            Spacer(Modifier.width(ShelfHeadGap))
        }
        ShelfModeStrip(active = shelfMode.stripLabel, compact = compact) { LibraryShelfMode.fromStripLabel(it)?.let(onSelectShelfMode) }
        Spacer(Modifier.weight(1f))
        ShelfAddPill(
            label = stringResource(shelfMode.addLabelRes()),
            focused = focused == AddFolderKey,
            authority = authority,
            compact = compact,
            onActivate = {
                if (shelfMode == LibraryShelfMode.EMULATION) {
                    onRescanFolder()
                } else {
                    onOpenNativeAddPicker(shelfMode)
                    // The safe key stamped before the push (RC-32), as the controller path does: a stray A in the picker's first frames must reach Search, not the last app chosen.
                    pushScopeLandingOn(authority, NativeAddPickerScope, NativeAddPickerSearchKey)
                }
            },
        )
    }
}

private val ShelfHorizontalGutter = 20.dp
internal const val ShelfTopPaddingDp = 8
internal const val ShelfBottomPaddingDp = 16
internal const val ShelfHeaderToToolsGapDp = 10
internal const val ShelfHeadRowHeightDp = 38
internal const val ShelfAddPillHeightDp = 38
internal val ShelfQuickRailLaneHeightDp = MimirRunicQuickRailDefaults.RailHeight.value.toInt()
private val ShelfTopPadding = ShelfTopPaddingDp.dp
private val ShelfBottomPadding = ShelfBottomPaddingDp.dp
private val ShelfCoverflowLift = 0.dp
private val ShelfHeaderToToolsGap = ShelfHeaderToToolsGapDp.dp
private val ShelfHeadRowHeight = ShelfHeadRowHeightDp.dp
private val ShelfQuickRailLaneHeight = MimirRunicQuickRailDefaults.RailHeight
private const val ShelfModeFocusLandingAttempts = 4

private val ShelfHeadGap = 12.dp
// The beam separator between "CHOOSE A PLATFORM" and the mode tabs — widened from the old 42dp floating stub
// so it reads as a proper bridging separator.
private val ShelfHeadOrnamentWidth = 104.dp
private val GamelistPreviewPanelWidth = 170.dp
private val NativeFavoritesPreviewPanelWidth = 170.dp
private val NativeFavoriteRowIconSize = 34.dp
private val NativeFavoritePreviewIconHeight = 170.dp

internal data class LibraryShelfGeometry(
    val compactSquare: Boolean,
    val horizontalGutterDp: Float,
    val topPaddingDp: Float,
    val bottomPaddingDp: Float,
    val headerToToolsGapDp: Float,
    val headRowHeightDp: Float,
    val heroBandHeightDp: Float,
    val artBandHeightDp: Float,
    val titleBandHeightDp: Float,
    val tileScale: Float,
    // Shelf seat solve  — Home's OWN solved seat
    // count ([mimirShelfSeatCount]) at the FULL Library viewport width (widthDp below), NOT this geometry's
    // own narrower post-gutter band. The platform row (ShelfCoverflow) inherits its window radius from this
    // value; see [solvePlatformRowPlan]'s own doc for why that distinction is load-bearing (the RP5-class
    // shelf's env(4) flips from >= the seat floor to < it if solved from the band instead of the viewport).
    val homeSeatCount: Int,
) {
    val fixedLaneBudgetDp: Float
        get() = topPaddingDp + bottomPaddingDp + ShelfIdentityHeaderHeightDp + headerToToolsGapDp +
            headRowHeightDp + heroBandHeightDp + ShelfQuickRailLaneHeightDp
}

internal fun solveLibraryShelfGeometry(
    widthDp: Float,
    heightDp: Float,
    compactOverride: Boolean? = null,
): LibraryShelfGeometry {
    val compactSquare = compactOverride
        ?: mimirViewportProfile(widthDp, heightDp).isCompact
    val homeSeatCount = mimirShelfSeatCount(widthDp)
    if (!compactSquare) {
        return LibraryShelfGeometry(
            compactSquare = false,
            horizontalGutterDp = ShelfHorizontalGutter.value,
            topPaddingDp = ShelfTopPaddingDp.toFloat(),
            // the dock jumped visibly switching Home <-> Library. Root cause was this
            // constant -- a flat 16dp that only coincidentally sat close to Home's height-scaled placement.
            // Read the same source Home solves from (HomeShelfGeometry.kt's homeShelfQuickRailBottomGapDp) so
            // the two rails' bottom edge lands at the identical height on every viewport, not just this one.
            bottomPaddingDp = homeShelfQuickRailBottomGapDp(heightDp),
            headerToToolsGapDp = ShelfHeaderToToolsGapDp.toFloat(),
            headRowHeightDp = ShelfHeadRowHeightDp.toFloat(),
            heroBandHeightDp = CoverflowHeroBandHeightDp.toFloat(),
            artBandHeightDp = CoverflowArtBandHeightDp.toFloat(),
            titleBandHeightDp = CoverflowTitleBandHeightDp.toFloat(),
            tileScale = 1f,
            homeSeatCount = homeSeatCount,
        )
    }
    val top = 6f
    // Home's own compact dock gap, so the rail does not jump 6 dp between Home and Library.
    val bottom = homeShelfQuickRailBottomGapDp(heightDp)
    val headerGap = 4f
    val head = 34f
    val availableHero = (
        heightDp - top - bottom - ShelfIdentityHeaderHeightDp - headerGap - head - ShelfQuickRailLaneHeightDp
        ).coerceAtLeast(140f)
    // Compact squares may grow the hero band up to 1.3x the authored one: on the RG (564dp) the authored cap left
    // ~73dp dead above and below the cards.
    val hero = minOf(CoverflowHeroBandHeightDp.toFloat() * 1.3f, availableHero)
    val title = (hero * 0.22f).coerceIn(38f, CoverflowTitleBandHeightDp.toFloat())
    val art = (hero - title).coerceAtLeast(96f)
    return LibraryShelfGeometry(
        compactSquare = true,
        horizontalGutterDp = 12f,
        topPaddingDp = top,
        bottomPaddingDp = bottom,
        headerToToolsGapDp = headerGap,
        headRowHeightDp = head,
        heroBandHeightDp = hero,
        artBandHeightDp = art,
        titleBandHeightDp = title,
        tileScale = (art / CoverflowArtBandHeightDp).coerceIn(0.62f, 1f),
        homeSeatCount = homeSeatCount,
    )
}

internal fun libraryShelfFixedLaneBudgetDp(): Int =
    ShelfTopPaddingDp +
        ShelfBottomPaddingDp +
        ShelfIdentityHeaderHeightDp +
        ShelfHeaderToToolsGapDp +
        ShelfHeadRowHeightDp +
        CoverflowHeroBandHeightDp +
        ShelfQuickRailLaneHeightDp

/**
 * Mode memory precedence (observed6/6 on Thor, 2026-09-02): a mode-switch landing prefers the render's own per-mode memory
 * ([renderMemory] -- `rememberedKeysByMode[shelfMode]`, built from a real PAST visit to this mode via
 * `onCenter`) over the dispatch reducer's ring-first stamp ([dispatchStamp] --
 * `LibraryDispatch.kt`'s `cycleShelfModeWithFocusStamp`, which writes `viewModel.centeredShelfKey`). The
 * stamp is a fallback for a mode's first-ever visit THIS SESSION only, when [renderMemory] has nothing
 * yet -- it is not dead (it is exactly what lands a fresh process on tile one), it simply loses to real
 * memory whenever real memory exists. Library does not use input-wins/ring-first precedence here (the
 * Home shelf's own rule) -- the two shelves are deliberately different on this axis. A future edit that
 * flips this precedence toward ring-first would silently reverse that precedence; pinned in
 * `ShelfModeMemoryTest.modeSwitchLandingPrefersRenderMemoryOverDispatchStamp`.
 */
internal fun shelfModeRememberedLandingKey(
    renderMemory: FocusKey?,
    dispatchStamp: FocusKey?,
    orderedStopKeys: List<FocusKey>,
): FocusKey? = renderMemory ?: dispatchStamp?.takeIf { it in orderedStopKeys }

internal fun shelfModeLandingTarget(
    focusedKey: FocusKey?,
    rememberedKey: FocusKey?,
    orderedStopKeys: List<FocusKey>,
    emptyStopKey: FocusKey,
): FocusKey? {
    // A valid current focus may have arrived from an app-level async restore. It is authoritative even when it
    // differs from this screen's older memory, and recording it through onCenter updates that memory immediately.
    if (focusedKey in orderedStopKeys) return null
    val target = rememberedKey?.takeIf { it in orderedStopKeys }
        ?: orderedStopKeys.firstOrNull()
        ?: emptyStopKey
    return target.takeUnless { it == focusedKey }
}

/** One-shot destination re-entry seed; invalid or cross-mode keys are rejected by the target row. */
internal fun initialShelfModeFocusMemory(
    shelfMode: LibraryShelfMode,
    centeredKey: FocusKey?,
): Map<LibraryShelfMode, FocusKey> =
    centeredKey?.let { mapOf(shelfMode to it) }.orEmpty()

internal data class NativeAddOwnerCopy(
    val addLabel: String,
    val singular: String,
    val plural: String,
)

internal fun LibraryShelfMode.nativeAddOwnerCopy(): NativeAddOwnerCopy = when (this) {
    LibraryShelfMode.EMULATION -> NativeAddOwnerCopy("Add games", "game", "games")
    LibraryShelfMode.ANDROID_GAMES -> NativeShelfKind.GAME.nativeAddOwnerCopy()
}

private fun NativeShelfKind.nativeAddOwnerCopy(): NativeAddOwnerCopy = when (this) {
    NativeShelfKind.GAME -> NativeAddOwnerCopy("Add game", "game", "games")
    NativeShelfKind.APP -> NativeAddOwnerCopy("Add app", "app", "apps")
}

/**
 * The functional shelf Add affordance.  it was the per-theme CTA satin gradient in a 10 dp rounded box, an accent bloom and a scale pop on
 * focus. It is now the etched chip struck as a PRIMARY (the same dark plate as the mode strip beside it, the rim and the word in the accent, no fill) at the header band's own
 * height (38 dp, 34 compact: the band is fixed and must not grow, so the 48 dp a finger needs is Compose's own minimum touch target). Focus is the one selection ring, on the
 * PLATE itself with no inset and BEFORE the surface, so its edge is the plate's edge and its wash is under the opaque metal. Static: the landing effect never re-seeds onto this
 * pill (the "fight" fix).
 */
@Composable
internal fun ShelfAddPill(
    label: String,
    focused: Boolean,
    authority: FocusAuthority,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onActivate: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    Box(
        modifier = modifier
            .mimirFocusable(authority, AddFolderKey, LibraryShelfScope)
            .mimirSelectionRing(focused = focused, identity = AddFolderKey, shape = MimirRingShape.Pill, edgeFlush = true)
            .mimirEtchedChipSurface(palette, focused = focused, primary = true, edgeFlush = true)
            .mimirActivatable(label = label, authority = authority, key = AddFolderKey, onActivate = onActivate)
            .height(if (compact) 34.dp else ShelfAddPillHeightDp.dp)
            .padding(horizontal = if (compact) MimirSpacing.sm else MimirSpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            text = label,
            style = MimirEtchedChipLabelStyle,
            color = palette.etchedChipInk(primary = true),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── Gamelist view ─────────────────────────────────────────────────────────────────────────────────
internal fun gamelistUsesLightMediaContrast(
    palette: MimirThemePalette,
    backdropHero: String?,
): Boolean = palette.needsLocalizedMediaContrast && !backdropHero.isNullOrBlank()

@Composable
internal fun GamelistHeader(
    title: String,
    metaLabel: String,
    showSearch: Boolean,
    searchQuery: String,
    searchPlaceholder: String,
    authority: FocusAuthority,
    searchControllerEnabled: Boolean,
    searchFocused: Boolean,
    onOpenSearch: () -> Unit,
    onClearSearch: () -> Unit,
    segments: List<FavoriteSegment>,
    segmentCounts: Map<FavoriteSegment, Int>,
    activeSegment: FavoriteSegment,
    onSelectSegment: (FavoriteSegment) -> Unit,
    mediaContrastRail: Boolean = false,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        val fontScale = LocalDensity.current.fontScale
        // The status pill at the left of the glance row is wider at a large font, and the header leaves room for the pill as it is drawn, not as it was at 1x.
        val titleMeasurer = androidx.compose.ui.text.rememberTextMeasurer()
        val titleDensity = LocalDensity.current
        val statusRailDp = remember(titleMeasurer, titleDensity) {
            gamelistStatusRailDp(com.gruning.mimir.rewrite.core.ui.mimirLiquidStatusPillWidthDp(titleMeasurer, titleDensity))
        }
        val fullGeometry = resolveGamelistHeaderSearchGeometry(
            availableWidthDp = maxWidth.value,
            compact = compact,
            fontScale = fontScale,
            statusRailDp = statusRailDp,
        )
        // The wide row gives the title what the tag, the count and Search leave. When that cannot hold its first word and the tag (round 2: "S…" on the RP5 at 1.3), Search is the
        // magnifier alone and gives its room back; the title is then shortened at a word, or stands whole.
        val tagText = stringResource(R.string.library_gamelist_local_library)
        val titleFloorDp = remember(title, mediaContrastRail, titleMeasurer, titleDensity, tagText) {
            gamelistWideTitleFloorDp(title, mediaContrastRail, titleMeasurer, titleDensity, tagText)
        }
        // Search gives way first: the title (with its tag) and the count stand whole while Search narrows to what is left, then to the magnifier alone ([gamelistSearchSqueezeDp]); only when
        // even that cannot hold them whole does the title shorten at a word, and Search is the magnifier when the title could not show its first word beside the full pill.
        val titleWholeDp = remember(title, mediaContrastRail, titleMeasurer, titleDensity, tagText) {
            gamelistWideTitleWholeDp(title, mediaContrastRail, titleMeasurer, titleDensity, tagText)
        }
        val metaWholeDp = remember(metaLabel, mediaContrastRail, titleMeasurer, titleDensity) {
            gamelistMetaWholeDp(metaLabel, mediaContrastRail, titleMeasurer, titleDensity)
        }.coerceAtMost(fullGeometry.metadataMaxWidthDp)
        val squeezeDp = if (!compact && showSearch && fullGeometry.showSearch) {
            gamelistSearchSqueezeDp(
                rowWidthDp = maxWidth.value - fullGeometry.statusEndInsetDp,
                titleWholeDp = titleWholeDp,
                metaWholeDp = metaWholeDp,
                gapDp = fullGeometry.itemGapDp,
                fullSearchDp = fullGeometry.searchWidthDp,
            )
        } else {
            null
        }
        val searchMark = !compact && showSearch && fullGeometry.showSearch &&
            (squeezeDp?.let { it <= LibraryGamelistSearchMarkWidthDp } ?: (fullGeometry.titleMaxWidthDp < titleFloorDp))
        val searchGeometry = when {
            searchMark -> resolveGamelistHeaderSearchGeometry(
                availableWidthDp = maxWidth.value,
                compact = false,
                fontScale = fontScale,
                searchMarkOnly = true,
                statusRailDp = statusRailDp,
            )
            squeezeDp != null && squeezeDp < fullGeometry.searchWidthDp -> fullGeometry.copy(searchWidthDp = squeezeDp)
            else -> fullGeometry
        }
        val searchVisible = showSearch && searchGeometry.showSearch
        // When even the magnifier leaves the title short of its first word and the tag, the count gives way (at a word) before the title is cut inside its first word.
        val metadataMaxDp = if (compact) {
            searchGeometry.metadataMaxWidthDp
        } else {
            val searchSeat = if (searchVisible) searchGeometry.searchWidthDp + searchGeometry.itemGapDp else 0f
            val room = maxWidth.value - searchGeometry.statusEndInsetDp - titleFloorDp - searchGeometry.itemGapDp - searchSeat
            searchGeometry.metadataMaxWidthDp.coerceAtMost(room.coerceAtLeast(GamelistMetaFloorDp))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = searchGeometry.statusEndInsetDp.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) MimirSpacing.xs else MimirSpacing.sm),
        ) {
            if (compact) {
                GamelistTitleIdentity(
                    title = title,
                    compact = true,
                    mediaContrastRail = mediaContrastRail,
                    maxWidth = searchGeometry.titleMaxWidthDp.dp,
                )
                GamelistMetaSearchRow(
                    metaLabel = metaLabel,
                    showSearch = searchVisible,
                    searchWidth = searchGeometry.searchWidthDp.dp,
                    metadataMaxWidth = metadataMaxDp.dp,
                    itemGap = searchGeometry.itemGapDp.dp,
                    searchQuery = searchQuery,
                    searchPlaceholder = searchPlaceholder,
                    authority = authority,
                    searchControllerEnabled = searchControllerEnabled,
                    searchFocused = searchFocused,
                    onOpenSearch = onOpenSearch,
                    onClearSearch = onClearSearch,
                    mediaContrastRail = mediaContrastRail,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(searchGeometry.itemGapDp.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The count and Search are measured first, at the width they have; the title and its tag take what they leave (the solver's
                    // [titleMaxWidthDp] reserves the count's CEILING and Search's seat, so a short count left the title starved: "Game Bo…").
                    GamelistTitleIdentity(
                        title = title,
                        compact = false,
                        mediaContrastRail = mediaContrastRail,
                        maxWidth = Dp.Infinity,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    GamelistMetaSearchRow(
                        metaLabel = metaLabel,
                        showSearch = searchVisible,
                        searchWidth = searchGeometry.searchWidthDp.dp,
                        metadataMaxWidth = metadataMaxDp.dp,
                        itemGap = searchGeometry.itemGapDp.dp,
                        searchQuery = searchQuery,
                        searchPlaceholder = searchPlaceholder,
                        authority = authority,
                        searchControllerEnabled = searchControllerEnabled,
                        searchFocused = searchFocused,
                        onOpenSearch = onOpenSearch,
                        onClearSearch = onClearSearch,
                        mediaContrastRail = mediaContrastRail,
                    )
                }
            }
            if (segments.size > 1) {
                FavoritesSegmentStrip(
                    segments = segments,
                    segmentCounts = segmentCounts,
                    activeSegment = activeSegment,
                    onSelectSegment = onSelectSegment,
                )
            }
        }
    }
}

@Composable
private fun GamelistTitleIdentity(
    title: String,
    compact: Boolean,
    mediaContrastRail: Boolean,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val railShape = RoundedCornerShape(14.dp)
    val railModifier = modifier
        .widthIn(max = maxWidth)
        .then(
            if (mediaContrastRail) {
                Modifier
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                palette.materials.controlTop.copy(alpha = 0.96f),
                                palette.materials.controlBottom.copy(alpha = 0.96f),
                            ),
                        ),
                        railShape,
                    )
                    .border(
                        MimirSpacing.hairlineWidth,
                        palette.textSecondary.copy(alpha = 0.18f),
                        railShape,
                    )
                    .padding(horizontal = GamelistTitleRailPaddingDp.dp, vertical = 4.dp)
            } else {
                Modifier
            },
        )
    Row(
        modifier = railModifier,
        horizontalArrangement = Arrangement.spacedBy(GamelistTitleTagGapDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The tag is measured first, at its full width, and the title takes what is left, shortened at a WORD ("Game Bo…" was a cut
        // inside one, and the tag was cut with it): when the room runs out the title gives way, never the tag.
        WordEllipsisText(
            text = title,
            style = gamelistTitleStyle(compact),
            color = if (mediaContrastRail) palette.textPrimary else MimirColors.TextPrimary,
            modifier = Modifier.weight(1f, fill = false),
        )
        LocalLibraryTag()
    }
}

@Composable
private fun GamelistMetaSearchRow(
    metaLabel: String,
    showSearch: Boolean,
    searchWidth: Dp,
    metadataMaxWidth: Dp,
    itemGap: Dp,
    searchQuery: String,
    searchPlaceholder: String,
    authority: FocusAuthority,
    searchControllerEnabled: Boolean,
    searchFocused: Boolean,
    onOpenSearch: () -> Unit,
    onClearSearch: () -> Unit,
    mediaContrastRail: Boolean,
) {
    val palette = LocalMimirPalette.current
    val metaShape = RoundedCornerShape(12.dp)
    Row(
        horizontalArrangement = Arrangement.spacedBy(itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = metadataMaxWidth)
                .then(
                    if (mediaContrastRail) {
                        Modifier
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        palette.materials.controlTop.copy(alpha = 0.96f),
                                        palette.materials.controlBottom.copy(alpha = 0.96f),
                                    ),
                                ),
                                metaShape,
                            )
                            .border(
                                MimirSpacing.hairlineWidth,
                                palette.textSecondary.copy(alpha = 0.18f),
                                metaShape,
                            )
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    } else {
                        Modifier
                    },
                ),
        ) {
            // "Favorites · 10 games" is shortened at a word like every other line of text (never "Favorites · 1…").
            WordEllipsisText(
                text = metaLabel,
                style = MimirType.RowSupport,
                color = if (mediaContrastRail) {
                    palette.textSecondary
                } else {
                    MimirColors.TextSecondary
                },
            )
        }
        if (showSearch) {
            val smallSearch = gamelistSearchPillIsSmall(LocalMimirViewportProfile.current)
            GamelistSearchBar(
                query = searchQuery,
                placeholder = if (smallSearch) LibraryGamelistCompactSearchPlaceholder else searchPlaceholder,
                authority = authority,
                controllerEnabled = searchControllerEnabled,
                focused = searchFocused,
                onOpen = onOpenSearch,
                onClear = onClearSearch,
                pillWidth = if (smallSearch && searchWidth.value > LibraryGamelistSearchMarkWidthDp) LibraryGamelistCompactSearchWidthDp.dp else searchWidth,
                compact = true,
                small = smallSearch,
            )
        }
    }
}

/** The gamelist header's "Local library" tag, for the tests that prove it is never cut. */
internal const val GamelistLocalLibraryTagTestTag = "gamelist_local_library_tag"

@Composable
private fun LocalLibraryTag() {
    val palette = LocalMimirPalette.current
    // "Local library" is a POSTURE label, not a per-item success — it wears the gold identity, and green
    // stays reserved for true per-item states like the "Ready" chip .
    Box(
        modifier = Modifier
            .testTag(GamelistLocalLibraryTagTestTag)
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .background(MimirColors.Gold.copy(alpha = 0.92f))
            .padding(horizontal = GamelistLocalLibraryTagPaddingDp.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Never shortened: the Row that holds it measures it before the title, and a title that cannot fit beside it gives way.
        MimirChromeText(
            stringResource(R.string.library_gamelist_local_library),
            style = MimirType.Pill,
            color = palette.onAccent,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun GamelistViewModeHint(
    mode: GamelistViewMode,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    MimirControlHintText(
        text = gamelistViewModeHintLabel(libraryResources(), mode),
        style = MimirTypeScale.ControlHintCompact,
        color = palette.textSecondary,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics { },
    )
}

internal fun gamelistDisplayedGame(
    games: List<GameRow>,
    focusedGameIndex: Int,
    retainedGameIndex: Int,
): GameRow? =
    games.getOrNull(focusedGameIndex)
        ?: games.getOrNull(retainedGameIndex)
        ?: games.firstOrNull()

/** Stable destination identity: changing it cancels the preceding Compose probe effect. */
internal fun gamelistAchievementProbeKey(game: GameRow): String = game.gameId.value

/**
 * Whether the gamelist media pill's Enrich affordance may actually fetch for [platformId]. A `pc_games` row is judged on
 * either provider: [steamGridDbKeyPresent] (SteamGridDB identifies the game and
 * supplies its art) or [canFetchArt] (ScreenScraper, which can supply a PC game's logo and nothing else, on an explicit press).
 * Every other platform is completely unchanged, judged on [canFetchArt] alone. Mirrors `MimirArtworkState.fetchOne`'s
 * synchronous gate exactly (`app/…/MimirArtworkCoordinator.kt`) so the pill's enabled state never disagrees with what pressing
 * it will actually do.
 */
internal fun gamelistEnrichAllowed(
    platformId: PlatformId,
    canFetchArt: Boolean,
    steamGridDbKeyPresent: Boolean,
): Boolean = if (platformId == PlatformId.PC_GAMES) steamGridDbKeyPresent || canFetchArt else canFetchArt

/**
 * Whether the LIST view paints its preview panel. Compact drops it (policy 2026-09-27, D3 "drop the
 * panel"): on the RG's 564.7 dp square its 170 dp column plus the gap left each row title 82 dp wide, and the
 * titles now take about 276. Enrich and Customize media stay on Y and in the CAROUSEL's pill; with no seat
 * registered, RIGHT on a row stays put (`LibraryDispatch.kt`, NAV_RIGHT).
 */
internal fun gamelistListShowsPreviewPanel(compact: Boolean): Boolean = !compact

/**
 * The LIST column's bottom insets: [viewport] pads the LazyColumn itself, [content] is its content padding's
 * bottom (which [libraryListContentPadding] raises to the focus ring's reach).
 *
 * With the panel, rows sit left of it and only the content pads, so the last row can scroll above the hint
 * band. Without it (compact) rows run the full width, under the M button and the L1/R1 hint while scrolling,
 * so the viewport itself ends above that band. With the keyboard open the content keeps the rest of the
 * keyboard's height, so the clearance above the keyboard is the same either way.
 */
internal data class GamelistListBottomInsets(val viewport: Dp, val content: Dp)

internal fun gamelistListBottomInsets(
    showsPreviewPanel: Boolean,
    keyboardOpen: Boolean,
    bottomControlInset: Dp,
): GamelistListBottomInsets = if (showsPreviewPanel) {
    GamelistListBottomInsets(
        viewport = 0.dp,
        content = if (keyboardOpen) MimirKeyboardHeight else bottomControlInset,
    )
} else {
    GamelistListBottomInsets(
        viewport = bottomControlInset,
        content = if (keyboardOpen) (MimirKeyboardHeight - bottomControlInset).coerceAtLeast(0.dp) else 0.dp,
    )
}

@Composable
internal fun GamelistView(
    games: List<GameRow>,
    emptyMessage: String,
    searchQuery: String,
    favoritesOnly: Boolean,
    keyboardOpen: Boolean,
    /**
     * The loader stays visible until the query settles and spins for at most 400ms. The list
     * originally missed that behavior. [libraryGamelistShowsLoader] was written for that loader policy and wired to the
     * CAROUSEL only (`GamelistCarousel.kt:199`), so the LIST kept printing *"No games on this platform
     * yet."* about a platform that has games, for the beat the stale-list guard holds `games` empty. Same
     * loader, same copy, same palette -- the field beside the one already covered by the loader policy.
     *
     * Defaults false so the Favorites segmented caller, which has no switch to wait out, is unchanged.
     */
    isLoading: Boolean = false,
    compactPreviewArt: Boolean = false,
    authority: FocusAuthority,
    focused: FocusKey?,
    supportLabelForGame: (GameRow) -> String? = { null },
    launchStatusForGame: (GameRow) -> String? = { null },
    detailGame: GameRow?,
    launchState: GameLaunchUiState,
    platforms: List<PlatformRow> = emptyList(),
    onOpenGame: (GameRow) -> Unit,
    canFetchArt: Boolean = false,
    steamGridDbKeyPresent: Boolean = false,
    onFetchArt: (GameRow) -> Unit = {},
    onCustomizeHero: (GameRow) -> Unit = {},
    bottomControlInset: Dp = 0.dp,
    /**
     * Walk-32i: the departure the screen has seated for a game the player just un-starred or hid, and the identity the list's
     * scroll state is remembered on. Null (no screen above, or a list that never plays one) keeps the list as it was: it is
     * remembered on its own keys and a game leaving it is a cut.
     */
    departureFrame: GamelistDepartureFrame? = null,
    /** Walk-32h: where a hide's row is broken. Non-null lets the focused row be captured and hosts the overlay. */
    shatter: LibraryShatterStage? = null,
    modifier: Modifier = Modifier,
    /** The keys of [games], made once per list (RC-45): the screen hands over the index it remembers; a host with none gets one remembered on the list. */
    gameKeys: GamelistKeyIndex = rememberGamelistKeyIndex(games),
) {
    val palette = LocalMimirPalette.current
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (favoritesOnly) {
                MimirChromeText(
                    stringResource(R.string.library_gamelist_favorites_only),
                    visualOverride = stringResource(R.string.library_gamelist_favorites_only_chip),
                    style = MimirTypeScale.MicroStatus,
                    color = palette.accent,
                )
                Spacer(Modifier.height(MimirSpacing.sm))
            }
            if (games.isEmpty()) {
                if (isLoading) {
                    // The carousel's own quiet loader, byte-for-byte (`GamelistCarousel.kt:203-209`): an
                    // unsettled query must never be reported as an empty platform on either surface.
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = palette.accentBright,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                } else {
                    MimirIntentText(
                        // Search-aware empty copy: a no-match is not "no games on this platform".
                        intent = when {
                            searchQuery.isNotBlank() -> libraryAroundAuthored(libraryResources(), R.string.library_gamelist_empty_search, searchQuery.trim())
                            favoritesOnly -> MimirTextIntent.StaticChrome(stringResource(R.string.library_gamelist_empty_favorites_platform))
                            else -> MimirTextIntent.StaticChrome(emptyMessage)
                        },
                        style = MimirTypeScale.MicroStatus,
                        color = MimirColors.TextMuted,
                    )
                }
            } else {
                val gameFocusKeys = gameKeys.keys
                val focusedGameIndex = focused?.let(gameKeys::indexOf) ?: -1
                val selectedGameIndex = detailGame?.let { selected -> gameKeys.indexOf(selected.gameId) } ?: -1
                RegisterBoundedVirtualFocusColumn(
                    authority = authority,
                    scope = GamelistScope,
                    keys = gameFocusKeys,
                    focusedIndex = focusedGameIndex,
                    selectedIndex = selectedGameIndex,
                    // The LIST view reaches the same landing resolver over the same ring swap as the carousel,
                    // so it carries the same defect by the same mechanism. Measured on the carousel only; this
                    // side is fixed by symmetry, not by its own capture.
                    clearsFocusOnIdentityReplacement = true,
                )

                val scrollTargetIndex = focusedGameIndex.takeIf { it >= 0 } ?: selectedGameIndex
                // The ring-swap law the carousel already obeys -- see [gamelistListScrollOrigin]. The scroll
                // state is REMEMBERED ON THE RING IDENTITY and BORN at the destination's seat, so a platform
                // switch can no longer hand this list the departed platform's offset for
                // [ScrollFocusedLazyItemIntoView] to correct after the fact.
                //
                // Walk-32i: and on the departure frame's lineage when the screen supplies one. The lineage is the keys' identity
                // in every respect but one: a game the player sent away, and whose row is still dissolving, does not change it.
                // Keyed on the keys, the list would be re-created the moment the game left, at a seat taken from the new list,
                // and the reveal pass would call it a replacement and seek: the observed jump, with nothing to animate.
                val listIdentity: Any = departureFrame?.lineage ?: gameFocusKeys
                val departure = departureFrame?.departure
                // What is drawn: the new list, plus the row that left, at the place it stood, until its motion has run.
                val shown = remember(games, departure) { gamelistListShown(games, departure) }
                val gapNeighbour = remember(games, departure) { gamelistGapNeighbour(games, departure) }
                val listState = key(listIdentity) {
                    rememberLazyListState(
                        initialFirstVisibleItemIndex = gamelistListScrollOrigin(
                            scrollTargetIndex = scrollTargetIndex,
                            itemCount = gameFocusKeys.size,
                        ),
                    )
                }
                ScrollFocusedLazyItemIntoView(
                    listState = listState,
                    // The reveal waits while a row is leaving. The survivor is drawn one row further on than it will be, and is
                    // carried to the leaver's seat by the gap's own motion; a reveal taken from where it is at the first frame would
                    // scroll the list a second time, against it. When the gap has shut the survivor is where the leaver stood, which
                    // was on screen, and the reveal (the index is then the survivor's own) has nothing to do.
                    focusedIndex = if (shown.ghostIndex != null) -1 else scrollTargetIndex,
                    focusIdentity = gameFocusKeys.getOrNull(scrollTargetIndex),
                    contentIdentity = listIdentity,
                )
                // Walk-32i: the panel holds each art until the store has measured where its ink is (see [GamePreviewPanel]), so the
                // store is asked, nearest first, about the arts around the focused row before the player gets to them. One
                // decode at a time, on this effect's own scope: moving on drops what is no longer near.
                val inkContext = LocalContext.current
                val inkPrefetch = remember(games, scrollTargetIndex) { gamelistInkPrefetchUris(games, scrollTargetIndex) }
                LaunchedEffect(inkPrefetch) { MimirArtInkStore.prefetch(inkContext, inkPrefetch) }

                val selectedGame = checkNotNull(
                    gamelistDisplayedGame(
                        games = games,
                        focusedGameIndex = focusedGameIndex,
                        retainedGameIndex = selectedGameIndex,
                    ),
                )
                val selectedRuntimeLaunchLine = if (detailGame?.gameId == selectedGame.gameId) {
                    launchStatusText(launchState, platforms)?.first
                } else {
                    null
                }
                val showsPreviewPanel = gamelistListShowsPreviewPanel(
                    compact = LocalMimirViewportProfile.current.let { it.widthDp > 0f && it.isCompact },
                )
                val bottomInsets = gamelistListBottomInsets(showsPreviewPanel, keyboardOpen, bottomControlInset)
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xl),
                ) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(GamelistListRowSpacing),
                        // RC-37: a focused row's ring paints MimirFocusTileRingReach outside its own bounds, and this
                        // LazyColumn clips exactly on its scroll axis, so with no top padding the FIRST row's ring was
                        // cut. Derived from the ring's own reach rather than nudged, so it cannot drift.
                        contentPadding = libraryListContentPadding(bottomInset = bottomInsets.content),
                        modifier = Modifier.weight(1f).padding(bottom = bottomInsets.viewport),
                    ) {
                        items(shown.games, key = { it.gameId.value }) { row ->
                            val key = gameKey(row)
                            // Walk-32i: the row of the game that left is the same item as before, under the same key, and is only
                            // painted as it was, and given the departure's clock for its height and its presence.
                            val leavingRow = departure != null && shown.ghostIndex != null && row.gameId == departure.game.gameId
                            // Walk-32h: the focused row draws through a layer and reports its bounds, so a hide can take its picture.
                            val panel = rememberLibraryShatterPanel(
                                shatter,
                                active = focused == key,
                                gameId = row.gameId.value,
                                corner = LibraryListRowCorner,
                            )
                            GamelistRow(
                                row = row,
                                supportLabel = supportLabelForGame(row),
                                badge = collapseBadge(row),
                                launchStatusLine = launchStatusForGame(row),
                                // The leaver keeps the look it left with: it was the focused row, and its ring fades with it.
                                focused = leavingRow || focused == key,
                                authority = authority,
                                key = key,
                                onActivate = { onOpenGame(row) },
                                interactive = !leavingRow,
                                modifier = when {
                                    leavingRow -> Modifier.gamelistLeavingRow(checkNotNull(departure))
                                    departure != null && gapNeighbour != null && gapNeighbour.gameId == row.gameId ->
                                        Modifier.gamelistGapNeighbour(departure, below = gapNeighbour.below)
                                            .testTag(gamelistListRowTestTag(row))
                                            .then(panel)
                                    else -> Modifier.testTag(gamelistListRowTestTag(row)).then(panel)
                                },
                            )
                        }
                    }
                    if (showsPreviewPanel) GamePreviewPanel(
                        title = selectedGame.title,
                        // // the collapse badge is stated ONCE per game per surface, on the element that IS the
                        // game in that surface's navigation model. In the list that is the ROW -- which prints
                        // it for this very game, and for every other visible row. The panel mirrors the row; it
                        // does not get a second vote.
                        // the settled facts (played / support / launch target /
                        // status chip) are the ROW's job -- `GamelistRow` already prints all four for this
                        // exact game -- and the panel spends that height on the cover instead. Only the
                        // TRANSIENT launch line crosses, because it is the one thing the row cannot show.
                        supportLabel = supportLabelForGame(selectedGame),
                        runtimeLaunchLine = selectedRuntimeLaunchLine,
                        previewMedia = libraryListPreviewMedia(selectedGame),
                        platformIdValue = selectedGame.platformId.value,
                        compactArt = compactPreviewArt,
                        mediaAction = {
                            val mediaAction = gamelistMediaAction(selectedGame.providerEnrichmentState())
                            val offerEnrich = mediaAction == GamelistMediaAction.ENRICH
                            GamelistMediaActionChip(
                                action = mediaAction,
                                focused = focused == GamelistGetArtKey,
                                enabled = !offerEnrich ||
                                    gamelistEnrichAllowed(selectedGame.platformId, canFetchArt, steamGridDbKeyPresent),
                                mediaContrastRail = false,
                                authority = authority,
                                onActivate = {
                                    if (offerEnrich) onFetchArt(selectedGame) else onCustomizeHero(selectedGame)
                                },
                                compact = true,
                            )
                        },
                        bottomControlInset = bottomControlInset,
                        modifier = Modifier.width(GamelistPreviewPanelWidth),
                    )
                }
            }
        }
        // Walk-32h ("every hide shatters the card"): the hidden row, broken over the list. Above the rows and the preview panel,
        // below the chrome; the row itself leaves at the write as it always did, and the shards play over where it stood.
        LibraryShatterHost(shatter)
    }
}

@Composable
internal fun ScrollFocusedLazyItemIntoView(
    listState: LazyListState,
    focusedIndex: Int,
    focusIdentity: Any? = focusedIndex,
    contentIdentity: Any? = Unit,
) {
    val history = remember(listState) { LibraryFocusRevealHistory() }
    LaunchedEffect(listState, focusedIndex, focusIdentity, contentIdentity) {
        // A scope commonly composes one frame before FocusAuthority publishes its restored key.
        // Do not let that keyless frame consume the nonanimated restoration epoch.
        if (focusedIndex < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val restoring = !history.initialized || history.contentIdentity != contentIdentity
        history.initialized = true
        history.contentIdentity = contentIdentity
        val estimatedItemSize = layout.visibleItemsInfo
            .map { it.size }
            .filter { it > 0 }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toInt()
        val focusedItem = layout.visibleItemsInfo.firstOrNull { it.index == focusedIndex }
        val action = focusRevealAction(
            focusedIndex = focusedIndex,
            firstVisibleIndex = layout.visibleItemsInfo.firstOrNull()?.index,
            visibleCount = layout.visibleItemsInfo.size,
            focusedItemOffset = focusedItem?.offset,
            focusedItemSize = focusedItem?.size,
            viewportStart = layout.viewportStartOffset,
            viewportEnd = layout.viewportEndOffset,
            estimatedItemSize = estimatedItemSize,
            restoring = restoring,
        )
        when (action) {
            FocusRevealAction.None -> Unit
            is FocusRevealAction.SeekTo ->
                listState.scrollToItem(action.index, action.scrollOffset)
            is FocusRevealAction.AnimateTo ->
                listState.animateScrollToItem(action.index, action.scrollOffset)
            is FocusRevealAction.ScrollBy -> listState.animateScrollBy(
                value = action.delta,
                animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
            )
        }
    }
}

private class LibraryFocusRevealHistory(
    var initialized: Boolean = false,
    var contentIdentity: Any? = null,
)

@Composable
internal fun RomRowPlatformIcon(
    platformIdValue: String,
    focused: Boolean,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(shape)
            .background(if (focused) palette.emberSoft else palette.surfaceRaised.copy(alpha = 0.62f))
            .border(
                width = 0.5.dp,
                color = if (focused) palette.tileRing.copy(alpha = 0.54f) else palette.outline.copy(alpha = 0.18f),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        PlatformConsoleArt(
            platformIdValue = platformIdValue,
            tint = if (focused) palette.accentBright else palette.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun launchStatusChipLabel(line: String?): String? {
    val value = line?.removePrefix("Launch ·")?.trim().orEmpty()
    return when {
        value.isBlank() -> null
        value.equals(MimirCopy.StatusNeedsEmulator, ignoreCase = true) -> MimirCopy.StatusNeedsEmulator
        value.equals(MimirCopy.StatusUnsupported, ignoreCase = true) -> MimirCopy.StatusUnsupported
        else -> MimirCopy.StatusReady
    }
}

internal fun launchStatusReady(line: String?): Boolean {
    val value = line?.removePrefix("Launch ·")?.trim().orEmpty()
    return value.isNotBlank() &&
        !value.equals(MimirCopy.StatusNeedsEmulator, ignoreCase = true) &&
        !value.equals(MimirCopy.StatusUnsupported, ignoreCase = true)
}

internal fun launchTargetLine(line: String?): String? = line?.takeIf { launchStatusReady(it) }

// ── Gamelist coverflow helpers ──────────────────────────────────────────────────────────────────────

/**
 * The hero card's ONE consolidated meta row : `Genre · Launcher · Played today`. Each part
 * is optional; joined with " · ". Launcher is the resolved emulator (dropped when the platform Needs emulator /
 * Unsupported); "Played …" comes from the most-recent launch.
 */
private fun carouselMetaLine(row: GameRow, launchStatusLine: String?): String? {
    val launcher = launchTargetLine(launchStatusLine)?.removePrefix("Launch ·")?.trim()?.ifBlank { null }
    val played = row.lastPlayedMillis?.let { relativePlayed(it) }
    return listOfNotNull(row.genre, launcher, played).joinToString(" · ").ifBlank { null }
}

/** The launch-status chip a carousel card shows ("Ready" / "Needs emulator"), or null when there is none. */
private fun carouselStatusChip(line: String?): GameStatusChip? {
    val label = launchStatusChipLabel(line) ?: return null
    return GameStatusChip(label = label, ready = launchStatusReady(line))
}

@Composable
private fun FavoritesSegmentedView(
    games: List<GameRow>,
    activeSegment: FavoriteSegment,
    nativeRows: List<NativeShelfRow>,
    searchQuery: String,
    keyboardOpen: Boolean,
    authority: FocusAuthority,
    focused: FocusKey?,
    supportLabelForGame: (GameRow) -> String?,
    launchStatusForGame: (GameRow) -> String?,
    detailGame: GameRow?,
    launchState: GameLaunchUiState,
    platforms: List<PlatformRow> = emptyList(),
    onOpenGame: (GameRow) -> Unit,
    onLaunchNativePackage: (String) -> Unit,
    canFetchArt: Boolean = false,
    steamGridDbKeyPresent: Boolean = false,
    onFetchArt: (GameRow) -> Unit = {},
    onCustomizeHero: (GameRow) -> Unit = {},
    transitionCancellationEpoch: Long = 0L,
    /** Walk-32i: the departure the screen has seated for a game the player just un-starred or hid, and the list's identity. */
    departureFrame: GamelistDepartureFrame? = null,
    /** Walk-32h: the stage a hide's row is captured for and broken over (the games segment's list). */
    shatter: LibraryShatterStage? = null,
    modifier: Modifier = Modifier,
) {
    // The destination owns semantics and focus from frame one; only the predecessor's pixels leave.
    // the swap every other shoulder switch uses, not a dissolve (see FavoritesSegmentSwap).
    FavoritesSegmentSwap(
        segment = activeSegment,
        cancellationEpoch = transitionCancellationEpoch,
        modifier = modifier.fillMaxSize(),
    ) { presentedSegment ->
        when (presentedSegment) {
            FavoriteSegment.GAMES -> GamelistView(
                // Favorites paints through the SAME preview panel, so it obeys the same law.
                games = games,
                emptyMessage = stringResource(R.string.library_gamelist_empty_favorites),
                searchQuery = searchQuery,
                favoritesOnly = false,
                keyboardOpen = keyboardOpen,
                compactPreviewArt = true,
                authority = authority,
                focused = focused,
                supportLabelForGame = supportLabelForGame,
                launchStatusForGame = launchStatusForGame,
                detailGame = detailGame,
                launchState = launchState,
                platforms = platforms,
                onOpenGame = onOpenGame,
                canFetchArt = canFetchArt,
                steamGridDbKeyPresent = steamGridDbKeyPresent,
                onFetchArt = onFetchArt,
                onCustomizeHero = onCustomizeHero,
                bottomControlInset = gamelistFavoritesBottomControlInsetDp(
                    compact = LocalMimirViewportProfile.current.let { it.widthDp > 0f && it.isCompact },
                ).dp,
                departureFrame = departureFrame,
                shatter = shatter,
                modifier = Modifier.fillMaxSize(),
            )
            FavoriteSegment.ANDROID_GAMES,
            FavoriteSegment.ANDROID_APPS -> Box(Modifier.fillMaxSize()) {
                NativeFavoritesRowDetailView(
                    rows = nativeRows,
                    activeSegment = activeSegment,
                    searchQuery = searchQuery,
                    keyboardOpen = keyboardOpen,
                    authority = authority,
                    focused = focused,
                    onLaunchNativePackage = onLaunchNativePackage,
                    shatter = shatter,
                    modifier = Modifier.fillMaxSize(),
                )
                // Walk-32h: a hidden Android row breaks apart over its list, as an emulated one does.
                LibraryShatterHost(shatter)
            }
        }
    }
}

@Composable
private fun FavoritesSegmentStrip(
    segments: List<FavoriteSegment>,
    segmentCounts: Map<FavoriteSegment, Int>,
    activeSegment: FavoriteSegment,
    onSelectSegment: (FavoriteSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // V-4, 2026-08-14: content identity, not slot (`FileBrowserGhostFreeIdentity`). `segments` is
        // data-driven -- `primary.favoriteSegments` at :1343, not a fixed enum -- so a repopulation can
        // hand slot N a different segment, and the label's ink animation would then run the
        // previous segment's selection out under it. Lower severity than the keyboard's: this is a
        // SELECTION tint rather than a focus ring, and the list changes rarely. Keyed anyway, because
        // "changes rarely" is not a property a test can hold.
        segments.forEach { segment ->
            key(segment) {
            val selected = segment == activeSegment
            val shape = RoundedCornerShape(MimirSpacing.radiusPill)
            // Update : an etched chip, warmed when it is the active segment -- the metal surface, the chip's padding and its label -- not a filled
            // capsule with an outline. The strip keeps its row height: it is a header band that must not grow, so a chip's tap area reaches 48 dp by Compose's own
            // minimum touch target. The clip is for the press feedback only; the surface is a capsule already.
            Box(
                modifier = Modifier
                    .mimirEtchedChipSurface(palette, warmed = selected)
                    .clip(shape)
                    .clickable { onSelectSegment(segment) }
                    .padding(horizontal = MimirSpacing.md, vertical = MimirSpacing.sm),
                contentAlignment = Alignment.Center,
            ) {
                FavoritesSegmentLabel(segment = segment, count = segmentCounts[segment] ?: 0, selected = selected)
            }
            }
        }
    }
}

/**
 * A segment chip's words. Its ink animates on [MimirMotion.FocusMs] when the segment is picked, and the animated colour is read HERE, in the
 * one composable that paints it, so a frame of that animation recomposes the label and nothing else (audit 2026-10-14, L-11a: read in the strip's
 * own scope it recomposed every chip of the strip, and the chip's surface with them, on every frame).
 */
@Composable
private fun FavoritesSegmentLabel(segment: FavoriteSegment, count: Int, selected: Boolean) {
    val palette = LocalMimirPalette.current
    val labelColor by animateColorAsState(
        targetValue = palette.etchedChipInk(warmed = selected),
        animationSpec = tween(MimirMotion.FocusMs, easing = MimirMotion.Easing),
        label = "favorites-segment-label",
    )
    MimirIntentText(
        intent = MimirTextIntent.Mixed(
            listOf(
                MimirTextFragment.Chrome(stringResource(segment.labelRes)),
                MimirTextFragment.Chrome(" "),
                MimirTextFragment.Authored(count.toString()),
            ),
        ),
        style = MimirEtchedChipLabelStyle,
        color = labelColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun NativeFavoritesRowDetailView(
    rows: List<NativeShelfRow>,
    activeSegment: FavoriteSegment,
    searchQuery: String,
    keyboardOpen: Boolean,
    authority: FocusAuthority,
    focused: FocusKey?,
    onLaunchNativePackage: (String) -> Unit,
    shatter: LibraryShatterStage? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            Text(
                text = when {
                    searchQuery.isNotBlank() -> stringResource(R.string.library_gamelist_empty_favorites_search, searchQuery.trim())
                    activeSegment == FavoriteSegment.ANDROID_GAMES -> stringResource(R.string.library_gamelist_empty_android_games)
                    else -> stringResource(R.string.library_gamelist_empty_android_apps)
                },
                style = MimirType.RowSupport,
                color = MimirColors.TextMuted,
            )
        } else {
            val rowFocusKeys = remember(rows) { rows.map { nativeShelfKey(it.packageName) } }
            val focusedRowIndex = focused?.let(rowFocusKeys::indexOf) ?: -1
            RegisterBoundedVirtualFocusColumn(
                authority = authority,
                scope = GamelistScope,
                keys = rowFocusKeys,
                focusedIndex = focusedRowIndex,
            )

            val listState = rememberLazyListState()
            ScrollFocusedLazyItemIntoView(
                listState = listState,
                focusedIndex = focusedRowIndex,
                focusIdentity = rowFocusKeys.getOrNull(focusedRowIndex),
                contentIdentity = rowFocusKeys,
            )

            val selectedRow = rows.getOrNull(focusedRowIndex) ?: rows.first()
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(MimirSpacing.xl),
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                    // RC-37 patch C: NativeFavoriteRow carries the IDENTICAL ring, and this
                    // list's bottom inset is 0 with the keyboard closed -- so it clipped at both edges,
                    // not just the top. Found while grounding the gamelist, fixed with it.
                    contentPadding = libraryListContentPadding(
                        bottomInset = if (keyboardOpen) MimirKeyboardHeight else 0.dp,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    items(rows, key = { it.packageName }) { row ->
                        val key = nativeShelfKey(row.packageName)
                        NativeFavoriteRow(
                            row = row,
                            focused = focused == key,
                            authority = authority,
                            key = key,
                            onActivate = { onLaunchNativePackage(row.packageName) },
                            modifier = rememberLibraryShatterPanel(
                                shatter,
                                active = focused == key,
                                gameId = row.packageName,
                                corner = LibraryListRowCorner,
                            ),
                        )
                    }
                }
                NativeFavoritePreviewPanel(
                    row = selectedRow,
                    modifier = Modifier.width(NativeFavoritesPreviewPanelWidth),
                )
            }
        }
    }
}

@Composable
private fun NativeFavoriteRow(
    row: NativeShelfRow,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    onActivate: () -> Unit,
    /** Walk-32h: the shatter's capture and bounds, at the head of the chain so the ring and the pin break with the row. */
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    val hero = palette.materials.hero
    val shape = RoundedCornerShape(LibraryListRowCorner)
    val metadataShape = RoundedCornerShape(8.dp)
    val leatherAlpha = if (focused) 0.98f else 0.90f
    val rowFill = Brush.verticalGradient(
        listOf(
            hero.coverTop.copy(alpha = leatherAlpha),
            hero.coverMiddle.copy(alpha = leatherAlpha),
            hero.coverBottom.copy(alpha = leatherAlpha),
        ),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MimirSpacing.minTarget)
            .mimirFocusable(authority, key, GamelistScope, autoBringIntoView = false)
            // RC-37 patch C: the same ring as GamelistRow, for the same reason (one plain sweep: an app, not a game).
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(LibraryListRowCorner),
                restingOutline = true,
                outlineColor = hero.hardware.copy(alpha = 0.42f),
                fill = rowFill,
            )
            .clip(shape)
            .mimirActivatable(label = stringResource(R.string.library_shelf_open_description), authority = authority, key = key, onActivate = onActivate)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryFavoriteMarker(favorite = true)
        NativeFavoriteIcon(
            iconPath = row.iconPath,
            fallbackTitle = row.label,
            focused = false,
            imagePadding = 4.dp,
            modifier = Modifier.size(NativeFavoriteRowIconSize),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs),
        ) {
            Text(
                text = row.label,
                style = MimirType.RowTitle.copy(fontSize = 14.sp),
                color = hero.onCover,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(
                                hero.coverMiddle.copy(alpha = 0.72f),
                                hero.coverBottom.copy(alpha = 0.86f),
                            ),
                        ),
                        shape = metadataShape,
                    )
                    .border(
                        MimirSpacing.hairlineWidth,
                        hero.onCover.copy(alpha = 0.12f),
                        metadataShape,
                    )
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(
                    text = row.favoriteKindLabel(),
                    style = MimirType.RowSupport.copy(fontSize = 11.sp),
                    color = hero.onCover.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        NativeReadyBadge()
    }
}

@Composable
private fun NativeFavoritePreviewPanel(
    row: NativeShelfRow,
    modifier: Modifier = Modifier,
) {
    val palette = LocalMimirPalette.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
    ) {
        Box {
            // This preview occupies one fixed slot while focus changes. Key both package ownership and the
            // content-addressed path so an already-successful painter cannot survive into another app.
            key(row.packageName, row.iconPath) {
                NativeFavoriteIcon(
                    iconPath = row.iconPath,
                    fallbackTitle = row.label,
                    focused = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(NativeFavoritePreviewIconHeight),
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(MimirSpacing.sm)
                    .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                    .background(palette.surfaceBase.copy(alpha = 0.66f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text("★", style = MimirType.Pill, color = palette.accent)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(MimirSpacing.xs)) {
            Text(
                text = row.label,
                style = MimirType.RowTitle,
                color = palette.accentBright,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.favoriteKindLabel(),
                style = MimirType.RowSupport,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NativeReadyBadge()
        }
    }
}

@Composable
private fun NativeReadyBadge() {
    Box(
        Modifier
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(MimirSpacing.radiusPill))
            .background(MimirColors.SuccessGreen.copy(alpha = 0.14f))
            .border(
                width = 1.dp,
                color = MimirColors.SuccessGreen.copy(alpha = 0.52f),
                shape = RoundedCornerShape(MimirSpacing.radiusPill),
            )
            .padding(horizontal = MimirSpacing.sm, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(MimirCopy.StatusReady, style = MimirType.Pill, color = MimirColors.SuccessGreen)
    }
}

@Composable
private fun NativeFavoriteIcon(
    iconPath: String?,
    fallbackTitle: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    imagePadding: Dp = MimirSpacing.lg,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(MimirSpacing.radiusCard)
    // R3 / R8 / R8a: ONE art state for the seat's ground and its content. The ground (fill and outline) is the no-art fallback's seat,
    // so it draws only for an unwired icon (blank, or its file gone) or with a loaded one, fading in with it; a wired icon that is
    // loading or failed draws nothing. The selection ring is feedback, not ground, and stays on every state.
    val art = rememberNativeIconArt(iconPath)
    Box(
        modifier = modifier
            .mimirSelectionRing(focused = focused, identity = iconPath ?: fallbackTitle, shape = MimirRingShape.Card(MimirSpacing.radiusCard))
            .clip(shape)
            .nativeIconGround(
                corner = MimirSpacing.radiusCard,
                fill = palette.surfaceRaised,
                outline = palette.outline,
                strokeWidth = 1.dp,
            ) { art.groundAlpha() },
        contentAlignment = Alignment.Center,
    ) {
        val painter = art.painter
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = fallbackTitle,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(imagePadding)
                    .graphicsLayer { alpha = art.alpha.value },
            )
        }
        if (!art.wired) {
            Text(
                text = fallbackTitle.trim().take(1).ifBlank { "A" }.uppercase(),
                style = MimirType.RailItem,
                color = if (focused) palette.accentBright else palette.textPrimary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun NativeShelfRow.favoriteKindLabel(): String = rowKindLabel ?: if (isGame) "Android game" else "Android app"

@Composable
private fun HideConfirmOverlay(
    game: GameRow?,
    authority: FocusAuthority,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()

    // Shipping defaultConfirm=false: land on Keep so a stray A press does not hide the row.
    LaunchedEffect(game?.gameId) {
        withFrameNanos { }
        authority.requestFocus(HideCancelKey)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.surfaceBase.copy(alpha = 0.72f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(MimirSpacing.xl)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(palette.surfaceRaised)
                .border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.radiusCard))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(MimirSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            MimirChromeText("Hide from Library?", style = MimirTypeScale.RunicEyebrow, color = palette.accent)
            Text(game?.title ?: "This game", style = MimirType.Title, color = palette.textPrimary)
            Text(
                LibraryHideCopy.GamePromptBody,
                style = MimirType.RowSupport,
                color = palette.textSecondary,
            )
            Spacer(Modifier.size(MimirSpacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                LibraryActionTile(
                    label = "Keep",
                    supporting = "Return to the gamelist.",
                    focused = focus.focused == HideCancelKey,
                    authority = authority,
                    key = HideCancelKey,
                    scope = HideConfirmScope,
                    onActivate = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                LibraryActionTile(
                    label = "Hide",
                    supporting = "Remove it from this library view.",
                    focused = focus.focused == HideConfirmKey,
                    authority = authority,
                    key = HideConfirmKey,
                    scope = HideConfirmScope,
                    onActivate = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NativeHideConfirmOverlay(
    row: NativeShelfRow?,
    packageName: String,
    authority: FocusAuthority,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val focus by authority.state.collectAsState()
    val label = row?.label ?: packageName
    val confirmHide = {
        if (authority.state.value.scope == NativeHideConfirmScope) authority.popScope()
        onConfirm()
    }
    val dismissHide = {
        onDismiss()
        if (authority.state.value.scope == NativeHideConfirmScope) authority.popScope()
    }

    LaunchedEffect(packageName) {
        withFrameNanos { }
        authority.requestFocus(NativeHideCancelKey)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.surfaceBase.copy(alpha = 0.72f))
            .clickable(onClick = dismissHide),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(MimirSpacing.xl)
                .clip(RoundedCornerShape(MimirSpacing.radiusCard))
                .background(palette.surfaceRaised)
                .border(1.dp, palette.outline, RoundedCornerShape(MimirSpacing.radiusCard))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(MimirSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(MimirSpacing.md),
        ) {
            MimirChromeText("Hide from Shelf?", style = MimirTypeScale.RunicEyebrow, color = palette.accent)
            Text(label, style = MimirType.Title, color = palette.textPrimary)
            Text(
                LibraryHideCopy.NativePromptBody,
                style = MimirType.RowSupport,
                color = MimirColors.TextSecondary,
            )
            Spacer(Modifier.size(MimirSpacing.xs))
            Row(horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md)) {
                LibraryActionTile(
                    label = "Keep",
                    supporting = "Return to the shelf.",
                    focused = focus.focused == NativeHideCancelKey,
                    authority = authority,
                    key = NativeHideCancelKey,
                    scope = NativeHideConfirmScope,
                    onActivate = dismissHide,
                    modifier = Modifier.weight(1f),
                )
                LibraryActionTile(
                    label = "Hide",
                    supporting = "Remove it from Android shelves.",
                    focused = focus.focused == NativeHideConfirmKey,
                    authority = authority,
                    key = NativeHideConfirmKey,
                    scope = NativeHideConfirmScope,
                    onActivate = confirmHide,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun NativeAddAppPickerOverlay(
    mode: LibraryShelfMode,
    candidates: List<NativeShelfAddCandidate>,
    searchQuery: String,
    keyboardOpen: Boolean,
    authority: FocusAuthority,
    focused: FocusKey?,
    onSearchChange: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
    onKeyboardHostChanged: (LibraryMimirKeyboardHost?) -> Unit,
) {
    val palette = LocalMimirPalette.current
    // mode is always ANDROID_GAMES here (the only shelf mode that still opens this overlay — EMULATION
    // routes CONFIRM on the Add pill to onRescanFolder instead), so nativeShelfKindOrNull() never actually
    // returns null; NativeShelfKind.GAME is the only live default now that ANDROID_APPS is retired.
    val targetKind = mode.nativeShelfKindOrNull() ?: NativeShelfKind.GAME
    val ownerCopy = mode.nativeAddOwnerCopy()
    val filteredCandidates = remember(candidates, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            candidates
        } else {
            candidates.filter { it.matchesNativeAddQuery(query) }
        }
    }
    val close = {
        onCloseSearch()
        onDismiss()
        if (authority.state.value.scope == NativeAddPickerScope) authority.popScope()
    }
    val choose: (String) -> Unit = { packageName ->
        onCloseSearch()
        if (authority.state.value.scope == NativeAddPickerScope) authority.popScope()
        onChoose(packageName)
    }

    LaunchedEffect(mode, candidates) {
        withFrameNanos { }
        withFrameNanos { }
        authority.requestFocus(NativeAddPickerSearchKey)
    }

    val pickerFocusKeys = remember(filteredCandidates) {
        filteredCandidates.map { nativeAddPickerKey(it.packageName) } + NativeAddPickerCloseKey
    }
    val pickerListState = rememberLazyListState()
    val pickerFocusedIndex = focused?.let(pickerFocusKeys::indexOf) ?: -1
    RegisterBoundedVirtualFocusColumn(
        authority = authority,
        scope = NativeAddPickerScope,
        keys = pickerFocusKeys,
        focusedIndex = pickerFocusedIndex,
    )
    ScrollFocusedLazyItemIntoView(
        listState = pickerListState,
        focusedIndex = pickerFocusedIndex,
        focusIdentity = pickerFocusKeys.getOrNull(pickerFocusedIndex),
        contentIdentity = pickerFocusKeys,
    )
    val panelShape = RoundedCornerShape(NativeAddPickerPanelRadius)
    val countLabel = if (searchQuery.isNotBlank()) {
        "${filteredCandidates.size} / ${candidates.size}"
    } else {
        "${candidates.size} available"
    }
    val supportLabel = if (candidates.isEmpty()) {
        "Nothing to add. Hide a ${ownerCopy.singular} from this shelf or add one from the other Android shelf."
    } else {
        "Installed ${ownerCopy.plural} on this device"
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.surfaceBase.copy(alpha = 0.72f))
            .clickable(onClick = close),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = MimirSpacing.xxl,
                    top = MimirSpacing.lg,
                    end = MimirSpacing.xxl,
                    bottom = if (keyboardOpen) MimirKeyboardHeight + MimirSpacing.md else MimirSpacing.xxl,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val panelMaxHeight = maxHeight.coerceAtMost(NativeAddPickerMaxHeight)
            PickerEnterSurface {
                Column(
                    Modifier
                        .widthIn(max = NativeAddPickerMaxWidth)
                        .fillMaxWidth()
                        .mimirSurfaceShadow(palette, MimirSpacing.elevationModal, panelShape, ambientAlpha = 0.16f, spotAlpha = 0.22f)
                        .clip(panelShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    palette.surfaceRaised.copy(alpha = 0.98f),
                                    palette.surfaceBase.copy(alpha = 0.96f),
                                ),
                            ),
                        )
                        .border(0.5.dp, palette.emberSoft.copy(alpha = 0.72f), panelShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                        .heightIn(max = panelMaxHeight)
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NativeAddPickerHeader(
                        shelfLabel = mode.stripLabel,
                        addLabel = ownerCopy.addLabel,
                        countLabel = countLabel,
                        supportLabel = supportLabel,
                        closeFocused = focused == NativeAddPickerCloseKey,
                        authority = authority,
                        onClose = close,
                    )
                    NativeAddPickerSearchField(
                        searchQuery = searchQuery,
                        placeholder = "Search ${targetKind.shelfLabel.removePrefix("Android ").lowercase()}",
                        focused = focused == NativeAddPickerSearchKey,
                        authority = authority,
                        onActivate = onOpenSearch,
                    )
                    LazyColumn(
                        state = pickerListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
                        // Top and bottom clear a focused row's ring (the list clips on its axis).
                        contentPadding = PaddingValues(
                            top = MimirFocusTileRingReach,
                            end = 4.dp,
                            bottom = if (keyboardOpen) MimirSpacing.lg else MimirFocusTileRingReach,
                        ),
                    ) {
                        items(
                            items = filteredCandidates,
                            key = { candidate -> candidate.packageName },
                        ) { candidate ->
                            val key = nativeAddPickerKey(candidate.packageName)
                            NativeAddPickerRow(
                                candidate = candidate,
                                focused = focused == key,
                                authority = authority,
                                key = key,
                                onChoose = { choose(candidate.packageName) },
                            )
                        }
                        if (filteredCandidates.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    text = if (searchQuery.isBlank()) {
                                        "Nothing to add. Hide a ${ownerCopy.singular} from this shelf or add one from the other Android shelf."
                                    } else {
                                        "No installed ${ownerCopy.plural} match this search."
                                    },
                                    style = MimirType.RowSupport,
                                    color = palette.textSecondary,
                                    modifier = Modifier.padding(MimirSpacing.md),
                                )
                            }
                        }
                    }
                }
            }
        }
        MimirKeyboardSheet(
            visible = keyboardOpen,
            value = searchQuery,
            targetLabel = "Search ${targetKind.shelfLabel}",
            onValueChange = onSearchChange,
            onDone = onCloseSearch,
            authority = authority,
            onKeyboardHostChanged = onKeyboardHostChanged,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun PickerEnterSurface(content: @Composable () -> Unit) {
    // LibraryOverlayPixelFadeTransition owns opening, closing, and burst retargets. A nested entrance would
    // double-dim a cold picker and make its first controller landing feel later than logical focus.
    content()
}

@Composable
private fun GamelistEnterSurface(content: @Composable () -> Unit) {
    // LibraryScreen owns the one outgoing pixel snapshot. Incoming gamelist content is always fully present.
    content()
}

/**
 * The one thing a gamelist body is a swap OF: which view it shows (Carousel or List) and whether it is the Normal list or the Favorites-only
 * one (X). A change of either replaces the body in place; the two are told apart only by what the swap waits for.
 */
internal data class GamelistViewEpoch(val mode: GamelistViewMode, val favoritesOnly: Boolean)

/**
 * Whether a change of the body's epoch is this transition's to animate. A change of PLATFORM is not: L2/R2 resets the Favorites-only flag inside the platform switch
 * (`LibraryViewModel.resetPerGamelistFilters`), and the switch's own handoff already carries the departing list, so animating that flip too faded the old list twice
 * (review W2A #6). Only a flip on the shelf that was already showing is the X cross-fade.
 */
internal fun libraryGamelistViewEpochLandingApplies(
    previous: GamelistViewEpoch,
    current: GamelistViewEpoch,
    samePlatform: Boolean = true,
): Boolean = samePlatform && previous != current

/**
 * Whether the swap waits for the new view's covers with the old view held. A VIEW change always does; the Normal/Favorites flip does in the Carousel (which paints
 * covers, and would otherwise show flat seats that fade to art) and not in the List (one preview cover, nothing to wait for).
 */
internal fun libraryGamelistViewSwapHoldsForCovers(previous: GamelistViewEpoch, current: GamelistViewEpoch): Boolean =
    previous.mode != current.mode || current.mode == GamelistViewMode.CAROUSEL

/**
 * The Carousel/List swap's clock (audit 2026-10-14, RC-10, L-12/L-13: "shelf to list dips through near-black"): ONE [MimirMotion.OverlayMs] on the
 * house easing for both views, no lead. The swap tier's lead ([MimirPageMotion.LeadMs], then a [MimirMotion.ShineMs] arrival) is for a page that must
 * arrive under a dissolved one: it leaves the screen on the bare backdrop from about 130 ms until the arrival has grown (the near-black frames).
 * Here the old view fades out while the new one fades in, complementary at every frame ([MimirPageHandoff] draws the departing page on its exit
 * clock, `exitMs` = the same OverlayMs), and both rise/sink the same 8 dp, so the body is never emptier than the cross-fade's own overlap.
 */
internal fun <T> libraryViewCrossfadeSpec(reducedMotion: Boolean): FiniteAnimationSpec<T> = tween(
    durationMillis = if (reducedMotion) 0 else MimirMotion.OverlayMs,
    easing = MimirMotion.Easing,
)

/**
 * A Carousel/List change, or a Normal/Favorites-only change (X), is a cross-fade of two recorded views on one clock ([libraryViewCrossfadeSpec]):
 * logical focus switches synchronously; the old view survives only as recorded pixels that lift 8 dp away on OverlayMs while the new one fades and
 * rises 8 dp into place on the same OverlayMs. The 0.985 scale retired: no scale in the family. Burst input still retargets without keeping a
 * predecessor focus tree alive -- pixels are all that departs. A VIEW change waits for the new view's covers, and so does the X filter in the Carousel
 * ([libraryGamelistViewSwapHoldsForCovers]); the X filter in the List has none to wait for, and one that comes with a platform switch is not this swap's.
 */
@Composable
internal fun GamelistViewModeLandingTransition(
    mode: GamelistViewMode,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = mimirReducedMotion(),
    /** The covers the new view paints first ([awaitLibraryGamelistSettled]), waited for with the old view held. */
    settle: (suspend () -> Unit)? = null,
    /** How far the view's content paints past its slot at the sides: the gamelist Column's padding. */
    horizontalBleed: Dp = 0.dp,
    /** X (RC-18, L-10: "Favorites (X) hard-cuts with no reflow"): the Normal and Favorites-only lists swap with the same cross-fade. */
    favoritesOnly: Boolean = false,
    /** The platform the body belongs to: a flip that comes with a platform change is the platform switch's, not an X ([libraryGamelistViewEpochLandingApplies]). */
    platformId: PlatformId? = null,
    /** A test seam: the swap's two alphas, read live on a paused clock. Production passes none. */
    probe: LibraryPageProbe? = null,
    content: @Composable (GamelistViewMode) -> Unit,
) {
    val epoch = GamelistViewEpoch(mode, favoritesOnly)
    var presentedEpoch by remember { mutableStateOf(epoch) }
    var presentedPlatform by remember { mutableStateOf(platformId) }
    val shouldAnimate = libraryGamelistViewEpochLandingApplies(presentedEpoch, epoch, samePlatform = presentedPlatform == platformId)
    val progress = remember(epoch) { Animatable(if (shouldAnimate && !reducedMotion) 0f else 1f) }
    // False until the new view has arrived. The centred card's gleam waits for it (ring spec section 5), so its sweeps are
    // seen and not spent behind the held view. A flag set when the arrival ends, never progress read in composition (N1).
    var arrived by remember(epoch) { mutableStateOf(!shouldAnimate || reducedMotion) }
    // the toggle had no settle, so a first list -> carousel arrived
    // as seven flat seats that then faded to art. It holds the old view as L2/R2 does -- at rest, for the new view's
    // first covers, capped at SettleCapMs from the press -- and covers landing in the hold snap in.
    val holdsForCovers = shouldAnimate && !reducedMotion && settle != null && libraryGamelistViewSwapHoldsForCovers(presentedEpoch, epoch)
    var departureReleased by remember(epoch) { mutableStateOf(!holdsForCovers) }
    val settlingState = remember(epoch) { mutableStateOf(holdsForCovers) }
    val currentSettle by rememberUpdatedState(settle)
    // The platform the body was last presented on, on its own effect so a platform switch never restarts an arrival that is under way.
    LaunchedEffect(platformId) { presentedPlatform = platformId }
    LaunchedEffect(epoch) {
        presentedEpoch = epoch
        if (!shouldAnimate) return@LaunchedEffect
        // As for L2/R2 on this surface: presses wait until the new view first paints.
        if (progress.value <= 0f) holdMimirInputUntilShown(progress) { progress.value > 0f }
        if (!departureReleased) {
            val holdStartNs = System.nanoTime()
            // The wait's own result is null only when the cap fired: the settle is what made this a wait.
            val capped = try {
                withTimeoutOrNull(MimirPageMotion.SettleCapMs.toLong()) { currentSettle?.invoke() } == null
            } finally {
                settlingState.value = false
                departureReleased = true
            }
            // The wait may have ended late; the arrival still has its lead and its exit to cover (RG-2). `progress` is
            // the owner the hold above took the gate for; a view that arrived already shown took none, and this is then
            // nobody's to refresh.
            MimirTransitionInputGate.rearm(progress, MimirPageMotion.LeadMs + MimirPageMotion.ExitMs)
            runCatching {
                android.util.Log.i(
                    "MimirSettle",
                    "waited=${(System.nanoTime() - holdStartNs) / 1_000_000}ms capped=$capped site=view",
                )
            }
        }
        // Let the destination register its exact focus bounds before its pixels begin settling.
        withFrameNanos { }
        // One clock for both views, no lead (RC-10): see [libraryViewCrossfadeSpec].
        progress.animateTo(targetValue = 1f, animationSpec = libraryViewCrossfadeSpec(reducedMotion))
        arrived = true
    }
    val outerSettling = LocalMimirPageSettling.current
    val outerArrived = LocalMimirPageArrived.current
    val viewSettling: () -> Boolean = remember(outerSettling, settlingState) {
        { outerSettling() || settlingState.value }
    }
    val travelPx = with(LocalDensity.current) { LibraryGamelistViewTravelDp.dp.toPx() }
    val handoffProbe = remember(probe) { probe?.let { MimirPageHandoffProbe() } }
    if (probe != null && handoffProbe != null) {
        SideEffect {
            probe.incomingAlpha = { progress.value }
            probe.outgoingAlpha = { handoffProbe.outgoingAlpha() }
            probe.arrived = { arrived }
        }
    }
    MimirPageHandoff(
        epoch = epoch,
        departs = shouldAnimate,
        exitTravelPx = -travelPx,
        incomingAlpha = { progress.value },
        incomingTravelPx = { travelPx * (1f - progress.value.coerceIn(0f, 1f)) },
        reducedMotion = reducedMotion,
        vertical = true,
        // The handoff sizes itself from its modifier alone (every layer is matchParentSize). The gamelist Column
        // passes only weight(1f), which fixes the height and leaves minWidth 0: fill, or the body is 0 wide.
        // laid out past the Column's padding to the screen edge, and over its own travel, so the carousel's
        // side cards are not cut while a view fades (gamelistPageBleed).
        modifier = modifier.fillMaxSize().gamelistPageBleed(horizontal = horizontalBleed, vertical = LibraryGamelistViewTravelDp.dp),
        probe = handoffProbe,
        exitReleased = { departureReleased },
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = horizontalBleed, vertical = LibraryGamelistViewTravelDp.dp)) {
            key(mode) {
                CompositionLocalProvider(LocalMimirPageSettling provides viewSettling) {
                    CompositionLocalProvider(LocalMimirPageArrived provides (outerArrived && arrived)) {
                        content(mode)
                    }
                }
            }
        }
    }
}

/**
 * An in-gamelist platform switch (L2/R2), a SWAP since motion audit batch F. The outer
 * book shell and Hero veil stay mounted; the departing list survives only as recorded pixels ([MimirPageHandoff]: no
 * tree, no input, no focus). those pixels stay at rest until the new platform's rows and
 * centre covers settle ([settle], then the rows on screen), at most [MimirPageMotion.SettleCapMs] from the press, and
 * then leave the other way on OverlayMs while the new list fades and glides in from the shoulder's side on the swap
 * clock (OverlayMs from the 130 ms lead). Past the cap the list arrives anyway, wearing its header and loader, and
 * late rows land under it. A warmed destination settles at once. Focus and semantics belong to the destination from
 * its first frame; presses and touches wait until it shows (Q2), as for the Tome open; the shoulders and B always
 * work. A press that lands before the last list showed skips the wait and the lead: that list's pixels are already
 * gone, so the wait would hold a blank. The 0.88 start alpha retired: an arriving page starts from nothing.
 */
@Composable
internal fun GamelistDestinationPageTransition(
    platformId: PlatformId,
    gamesLoaded: Boolean,
    gamesEmpty: Boolean,
    withinSwitchGrace: Boolean,
    motion: GamelistPlatformSwitchMotion?,
    onMotionSettled: (GamelistPlatformSwitchMotion) -> Unit,
    /** The rows and centre covers to wait for (awaitLibraryGamelistSettled); null waits for the rows on screen only. */
    settle: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = mimirReducedMotion(),
    probe: LibraryPageProbe? = null,
    /** Written with the switch's sequence once its departing list is released; the hero follows this sequence. */
    releasedSequence: MutableState<Long?>? = null,
    content: @Composable () -> Unit,
) {
    // HOLD from the switch, RELEASE on the rows -- two predicates, not one. `gamesLoaded` decides WHEN the
    // glide runs; it must never decide whether the start pose is worn, and it must never be a `remember`
    // key here: keying the Animatable on it RE-CREATED the progress value mid-arrival, which is what turned
    // "a cold destination waits for matching rows" (this function's own contract, above) into a fully
    // settled paint followed by a backwards jump to alpha .88 / +22dp at the moment the rows landed.
    val holds = libraryGamelistPageMotionHolds(platformId, motion) && !reducedMotion
    val shouldAnimate = libraryGamelistPageMotionApplies(
        platformId = platformId,
        gamesLoaded = gamesLoaded,
        gamesEmpty = gamesEmpty,
        withinSwitchGrace = withinSwitchGrace,
        motion = motion,
    ) && !reducedMotion
    val progress = remember(platformId, motion?.sequence, reducedMotion) {
        Animatable(if (holds) 0f else 1f)
    }
    val gateOwner = remember(platformId, motion?.sequence) { Any() }
    // a press that lands while the last list has not yet shown skips the wait and the lead
    // (the retarget rule, as the Settings pane's SWAP_NO_LEAD). The handoff has two layers, so the list the
    // wait would hold has already been re-recorded by this one: waiting would only hold a blank.
    val swapMemory = remember { GamelistSwapMemory() }
    val burst = remember(platformId, motion?.sequence) {
        val previous = swapMemory.last
        swapMemory.last = progress
        holds && previous != null && previous !== progress && previous.value <= 0f
    }
    // Read by the wait below, which must not restart when they change: `shouldAnimate` is the release, not a key.
    val rowsReady by rememberUpdatedState(shouldAnimate)
    val currentSettle by rememberUpdatedState(settle)
    var departureReleased by remember(platformId, motion?.sequence) { mutableStateOf(!holds) }
    var shielding by remember(platformId, motion?.sequence) { mutableStateOf(holds) }
    val settlingState = remember(platformId, motion?.sequence) { mutableStateOf(holds) }
    // False until the new list has arrived. The centred card's gleam waits for it (ring spec section 5), so its sweeps
    // are seen and not spent behind the held list. A flag set when the arrival ends, never progress read in composition (N1).
    var arrived by remember(platformId, motion?.sequence) { mutableStateOf(!holds) }
    // One effect, like the Library's page entry: hold the pad, wait for the settle (capped), let the old list go, then
    // the arrival. Keyed on the switch, never on `gamesLoaded` -- see `progress` above.
    LaunchedEffect(platformId, motion?.sequence, reducedMotion) {
        val pending = motion?.takeIf { it.platformId == platformId }
        // Reduced motion still has to CONSUME the token: an unconsumed switch keeps `holds` true forever,
        // which would strand the page at the start pose instead of merely removing its animation.
        if (reducedMotion) {
            if (progress.value != 1f) progress.snapTo(1f)
            arrived = true
            pending?.let(onMotionSettled)
            return@LaunchedEffect
        }
        if (!holds) return@LaunchedEffect
        // Presses wait until the new list shows , as for the Tome open; the gate frees itself if
        // this page never arrives and is released on cancellation. The shoulders and B pass through it, so a second
        // L2/R2 or a B mid-swap still lands.
        MimirTransitionInputGate.hold(gateOwner)
        try {
            if (!burst) {
                // The rows and centre covers, then the rows on screen: the view model's flow and the UI can
                // disagree by a frame. Capped from the press, like the Library's page entry.
                val holdStartNs = System.nanoTime()
                val capped = withTimeoutOrNull(MimirPageMotion.SettleCapMs.toLong()) {
                    currentSettle?.invoke()
                    snapshotFlow { rowsReady }.first { it }
                } == null
                // The wait may have ended late; the arrival still has its lead and its exit to cover (RG-2).
                MimirTransitionInputGate.rearm(gateOwner, MimirPageMotion.LeadMs + MimirPageMotion.ExitMs)
                runCatching {
                    android.util.Log.i(
                        "MimirSettle",
                        "waited=${(System.nanoTime() - holdStartNs) / 1_000_000}ms capped=$capped site=platform",
                    )
                }
            }
            settlingState.value = false
            // The old list leaves now, on OverlayMs.
            departureReleased = true
            releasedSequence?.value = motion?.sequence
            // Give destination rows/focus one complete registration beat before their pixels begin moving.
            withFrameNanos { }
            // Batch F: the swap tier's arrival, from the lead on which the departing pixels are nearly gone. A burst
            // has nothing departing to wait out.
            val arrival: androidx.compose.animation.core.FiniteAnimationSpec<Float> = if (burst) {
                tween(MimirMotion.OverlayMs, easing = MimirMotion.Easing)
            } else {
                mimirSwapEnterSpec(reducedMotion)
            }
            progress.animateTo(targetValue = 1f, animationSpec = arrival) {
                // The new list shows: presses and touches reach it from this frame.
                if (value > 0f && shielding) {
                    shielding = false
                    MimirTransitionInputGate.release(gateOwner)
                }
            }
        } finally {
            shielding = false
            MimirTransitionInputGate.release(gateOwner)
            // Never strand the hero on a cancelled switch.
            releasedSequence?.value = motion?.sequence
        }
        arrived = true
        pending?.let(onMotionSettled)
    }
    val direction = motion?.direction?.takeIf { holds } ?: 0
    val travelPx = with(LocalDensity.current) { LibraryGamelistPageTravelDp.dp.toPx() }
    val incomingTravelPx: () -> Float = {
        MimirPageMotion.arrivalTravelPx(direction, travelPx) * (1f - progress.value.coerceIn(0f, 1f))
    }
    val handoffProbe = remember(probe) { probe?.let { MimirPageHandoffProbe() } }
    if (probe != null && handoffProbe != null) {
        SideEffect {
            probe.incomingAlpha = { progress.value }
            probe.incomingTravel = incomingTravelPx
            probe.outgoingAlpha = { handoffProbe.outgoingAlpha() }
            probe.outgoingTravel = { handoffProbe.outgoingTravel() }
        }
    }
    // Covers that land while the old list is held snap in, so the new list arrives wearing them. Combined with an outer
    // page entry's own flag (the shelf's open), never shadowing it.
    val outerSettling = LocalMimirPageSettling.current
    val outerArrived = LocalMimirPageArrived.current
    val pageSettling: () -> Boolean = remember(outerSettling, settlingState) {
        { outerSettling() || settlingState.value }
    }
    // The departing list waits for the settle, then leaves the other way as pixels; the arriving one is drawn at the
    // alpha and travel above.
    MimirPageHandoff(
        epoch = platformId,
        departs = holds,
        exitTravelPx = MimirPageMotion.exitTravelPx(direction, travelPx),
        incomingAlpha = { progress.value },
        incomingTravelPx = incomingTravelPx,
        reducedMotion = reducedMotion,
        // laid out past the screen edge by its own glide, so the side cards are not cut while it moves and
        // fades (gamelistPageBleed).
        modifier = modifier.gamelistPageBleed(horizontal = LibraryGamelistPageTravelDp.dp),
        probe = handoffProbe,
        shieldTouches = { shielding },
        exitReleased = { departureReleased },
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = LibraryGamelistPageTravelDp.dp)) {
        CompositionLocalProvider(LocalMimirPageSettling provides pageSettling) {
            CompositionLocalProvider(LocalMimirPageArrived provides (outerArrived && arrived)) {
                // keyed on the platform. The departing
                // page is a display list that only references its layered children, and the list/carousel toggle's
                // layers survived the switch, so the "old list" held for the covers drew the NEW platform live -- its
                // seats under the old header -- and then left while its own copy arrived. Keyed, the old tree is
                // disposed and its last recording departs (MimirPageHandoff's KDoc; Home's key(surfaceEpoch) precedent).
                key(platformId) {
                    content()
                }
            }
        }
        }
    }
}

/** Plain memory, not snapshot state: written inside `remember` and read only there (as the Settings pane's swap). */
private class GamelistSwapMemory {
    var last: Animatable<Float, *>? = null
}

@Composable
private fun MimirKeyboardSheet(
    visible: Boolean,
    value: String,
    targetLabel: String,
    onValueChange: (String) -> Unit,
    onDone: () -> Unit,
    authority: FocusAuthority,
    onKeyboardHostChanged: (LibraryMimirKeyboardHost?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = rememberLibraryMimirKeyboardHost(authority, visible)
    SideEffect { onKeyboardHostChanged(host.takeIf { visible }) }
    DisposableEffect(Unit) {
        onDispose { onKeyboardHostChanged(null) }
    }
    // Motion audit batch F: the sheet tier. The keyboard slides in by its own height and fades on one OverlayMs clock
    // with the house easing (it ran a 220 ms slide under a 120 ms fade on the default curve: two clocks on one arrival).
    // F4 (alpha audit 2026-09-30): the host also makes it inert while it leaves. It stays composed through the exit with
    // a null controller and decorator (`host` is null once `visible` is false), and a tap on a key in those 180 ms typed
    // into a field whose keyboard was closed.
    val reducedMotion = mimirReducedMotion()
    MimirSheetHost(visible = visible, reducedMotion = reducedMotion, modifier = modifier) {
        MimirKeyboard(
            value = value,
            onValueChange = onValueChange,
            onDone = onDone,
            controller = host?.controller,
            keyDecorator = host?.keyDecorator,
            targetLabel = targetLabel,
        )
    }
}

@Composable
private fun NativeAddPickerHeader(
    shelfLabel: String,
    addLabel: String,
    countLabel: String,
    supportLabel: String,
    closeFocused: Boolean,
    authority: FocusAuthority,
    onClose: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MimirSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MimirChromeText(
                    text = shelfLabel,
                    style = MimirTypeScale.RunicEyebrow.copy(letterSpacing = 0.16.em),
                    color = palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MimirChromeText(
                    text = addLabel,
                    style = MimirTypeScale.MenuAction,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = countLabel,
                style = MimirTypeScale.MicroStatus,
                color = palette.textMuted,
                maxLines = 1,
            )
            val closeShape = RoundedCornerShape(NativeAddPickerCloseCorner)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clickable {
                        authority.requestFocus(NativeAddPickerCloseKey)
                        onClose()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        // The one ring, dense: a 36 dp control in a 48 dp seat, so the key ring's 4 dp reach fits.
                        .mimirSelectionRing(focused = closeFocused, identity = NativeAddPickerCloseKey, shape = MimirRingShape.Key(NativeAddPickerCloseCorner))
                        .clip(closeShape)
                        .background(palette.surfaceHigh.copy(alpha = 0.96f))
                        .border(
                            width = MimirSpacing.hairlineWidth,
                            color = palette.textSecondary.copy(alpha = 0.35f),
                            shape = closeShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "×",
                        color = palette.textPrimary,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Normal,
                    )
                }
            }
        }
        Text(
            text = supportLabel,
            style = MimirType.RowSupport,
            color = palette.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NativeAddPickerSearchField(
    searchQuery: String,
    placeholder: String,
    focused: Boolean,
    authority: FocusAuthority,
    onActivate: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(NativeAddPickerControlRadius)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MimirChromeText("Search", style = MimirTypeScale.RunicEyebrow, color = palette.textSecondary)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 38.dp)
                // The one ring on the search row; its wash is the ember lift, the outline stays at rest.
                .mimirSelectionRing(
                    focused = focused,
                    identity = NativeAddPickerSearchKey,
                    shape = MimirRingShape.Row(NativeAddPickerControlRadius),
                    fill = SolidColor(palette.surfaceBase.copy(alpha = 0.66f)),
                    restingOutline = true,
                    outlineColor = palette.outline.copy(alpha = 0.22f),
                )
                .clip(shape)
                .mimirFocusable(authority, NativeAddPickerSearchKey, NativeAddPickerScope)
                .mimirActivatable(label = "Search", authority = authority, key = NativeAddPickerSearchKey, onActivate = onActivate)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MimirIntentText(
                intent = if (searchQuery.isBlank()) {
                    MimirTextIntent.StaticChrome(placeholder)
                } else {
                    MimirTextIntent.Authored(searchQuery)
                },
                style = MimirType.RowSupport,
                color = if (searchQuery.isBlank()) palette.textMuted else palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NativeAddPickerRow(
    candidate: NativeShelfAddCandidate,
    focused: Boolean,
    authority: FocusAuthority,
    key: FocusKey,
    onChoose: () -> Unit,
) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(NativeAddPickerRowRadius)
    val rowFill = if (focused) {
        Brush.horizontalGradient(
            listOf(
                palette.emberSoft.copy(alpha = 0.54f),
                palette.surfaceRaised.copy(alpha = 0.82f),
                palette.surfaceBase.copy(alpha = 0.62f),
            ),
        )
    } else {
        Brush.horizontalGradient(
            listOf(
                palette.surfaceBase.copy(alpha = 0.46f),
                palette.surfaceRaised.copy(alpha = 0.34f),
            ),
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NativeAddPickerRowMinHeight)
            // The one ring; the focus shadow and the ring-coloured hairline retire with it. The ring paints the
            // row's material so the wash lands over it; the outline stays at rest.
            .mimirSelectionRing(
                focused = focused,
                identity = key,
                shape = MimirRingShape.Row(NativeAddPickerRowRadius),
                fill = rowFill,
                restingOutline = true,
                outlineColor = palette.outline.copy(alpha = 0.18f),
            )
            .clip(shape)
            .mimirActivatable(label = "Choose", authority = authority, key = key, onActivate = onChoose)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NativeAddPickerIcon(candidate = candidate, focused = focused)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = candidate.label,
                style = MimirType.RowTitle,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = candidate.nativeAddPickerMetaLabel(),
                style = MimirType.RowSupport,
                color = palette.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        NativeAddPickerBadge(label = candidate.nativeAddActionLabel(), focused = focused)
    }
}

@Composable
private fun NativeAddPickerIcon(candidate: NativeShelfAddCandidate, focused: Boolean) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(8.dp)
    // R3 / R8 / R8a: one art state for the seat's ground and content (see [NativeFavoriteIcon]). The resting fill and outline are
    // ground, so a wired icon that is loading or failed draws none; the focused outline is selection feedback and stays.
    val art = rememberNativeIconArt(candidate.iconPath)
    Box(
        Modifier
            .size(NativeAddPickerIconSize)
            .clip(shape)
            .nativeIconGround(
                corner = 8.dp,
                fill = palette.surfaceRaised.copy(alpha = 0.62f),
                outline = if (focused) palette.accentBright else palette.outline.copy(alpha = 0.62f),
                strokeWidth = 0.5.dp,
                alwaysOutline = focused,
            ) { art.groundAlpha() },
        contentAlignment = Alignment.Center,
    ) {
        val painter = art.painter
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = candidate.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .graphicsLayer { alpha = art.alpha.value },
            )
        }
        if (!art.wired) {
            Text(
                text = candidate.label.trim().take(1).ifBlank { "A" }.uppercase(),
                style = MimirType.RailItem,
                color = palette.textPrimary,
            )
        }
    }
}

@Composable
private fun NativeAddPickerBadge(label: String, focused: Boolean) {
    val palette = LocalMimirPalette.current
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .widthIn(min = 84.dp, max = 174.dp)
            .clip(shape)
            .background(if (focused) palette.emberSoft.copy(alpha = 0.24f) else palette.surfaceHigh.copy(alpha = 0.22f))
            .border(0.5.dp, if (focused) palette.tileRing.copy(alpha = 0.42f) else palette.outline.copy(alpha = 0.22f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        MimirChromeText(
            text = label,
            style = MimirTypeScale.MenuAction,
            color = if (focused) palette.accentBright else palette.textSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun NativeShelfAddCandidate.nativeAddActionLabel(): String {
    val ownerCopy = targetKind.nativeAddOwnerCopy()
    return when (reason) {
        NativeShelfAddReason.HIDDEN_HERE -> "Restore ${ownerCopy.singular}"
        NativeShelfAddReason.MOVE_FROM_OTHER_SHELF -> if (isHidden) {
            ownerCopy.addLabel
        } else {
            ownerCopy.addLabel
        }
    }
}

private fun NativeShelfAddCandidate.nativeAddPickerMetaLabel(): String =
    rowKindLabel ?: sourceKind.rowKindLabel

private fun NativeShelfAddCandidate.matchesNativeAddQuery(query: String): Boolean =
    listOf(
        label,
        packageName,
        rowKindLabel ?: sourceKind.rowKindLabel,
        targetKind.shelfLabel,
        nativeAddActionLabel(),
    ).any { it.contains(query, ignoreCase = true) }

/**
 * The gamelist search bar — a pill that shows the live query (or a placeholder) and, when non-empty, a
 * clear (×). Regular platform and Favorites/Games lanes register this one base-scope Search launcher with
 * [mimirFocusable]; native Favorites segments retain their established touch-only package/title keyboard path.
 * The keyboard itself remains touch-only .
 */
@Composable
internal fun GamelistSearchBar(
    query: String,
    placeholder: String = stringResource(R.string.library_gamelist_search_games),
    authority: FocusAuthority,
    controllerEnabled: Boolean,
    focused: Boolean,
    onOpen: () -> Unit,
    onClear: () -> Unit,
    /**
     * The width the pill is drawn at while its label fits. When the placeholder does not fit it even at the type floor, the pill is the magnifier alone, a
     * [LibraryGamelistSearchMarkWidthDp] dp mark (what Customize media's Search does since the earlier update), so the header gives that room back.
     */
    pillWidth: Dp,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    /** The compact pill: drawn [LibraryGamelistCompactSearchPillHeightDp] tall inside the full touch area. */
    small: Boolean = false,
) {
    val palette = LocalMimirPalette.current
    // The placeholder's size: 11 sp (the Label step) while it stands whole in what the pill leaves; shrunk in half-sp steps to the floor (MimirTypeStep.Micro) when it does
    // not; and when even the floor cannot hold it whole the pill is its magnifier alone, never "SEARCH GA..." (sweep 10-09, font 1.3). A typed query is the player's own text and
    // keeps its one ellipsized line at the label size.
    val labelStyle = MimirType.RowSupport.copy(fontSize = MimirTypeStep.Label)
    val placeholderResolved = remember(placeholder) { MimirTextIntent.StaticChrome(placeholder).resolve() }
    val placeholderText = placeholderResolved.visualText
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = LocalDensity.current
    val sidePadding = if (small || compact) MimirSpacing.md else MimirSpacing.lg
    val labelGap = if (small) 6.dp else MimirSpacing.sm
    val placeholderSizeSp: Float? = if (query.isNotBlank()) {
        MimirTypeStep.Label.value
    } else {
        remember(placeholderText, pillWidth, sidePadding, labelGap, density, measurer, labelStyle) {
            val roomPx = with(density) { (pillWidth - sidePadding * 2 - MimirSearchGlyphSize - labelGap).toPx() }
            gamelistSearchLabelSizeSp(MimirTypeStep.Label.value, MimirTypeStep.Micro.value) { sp ->
                measurer.measure(placeholderText, labelStyle.copy(fontSize = sp.sp), maxLines = 1, softWrap = false).size.width <= roomPx
            }
        }
    }
    val markOnly = placeholderSizeSp == null
    // The song-title chip is drawn over this header's end; it keeps off the pill (sweep 10-09: its words stood over "SEARCH GAMES" whenever a track began).
    val pillModifier = modifier
        .width(if (markOnly) LibraryGamelistSearchMarkWidthDp.dp else pillWidth)
        .mimirGlanceKeepClear(GamelistSearchGlanceKey)
    // The registration and the ring go on the PLATE (the element that carries the etched surface), the ring BEFORE the surface: its edge is then the plate's edge, with no inset,
    // and its wash is under the opaque metal. The metal has its own rim, so there is no resting hairline (update).
    val controllerModifier = if (controllerEnabled) {
        Modifier
            .mimirFocusable(
                authority = authority,
                key = LibrarySearchLauncherKey,
                scope = GamelistScope,
            )
            // edgeFlush on the ring AND on the surface below (Q8, ): the rim takes the ring's colour and one line shows.
            .mimirSelectionRing(focused = focused, identity = LibrarySearchLauncherKey, shape = MimirRingShape.Pill, edgeFlush = true)
    } else {
        Modifier
    }
    val content: @Composable RowScope.() -> Unit = {
        // The drawn magnifier at the one search size (16 dp), not a font glyph: the font's was a speck beside the label.
        // The mark alone says its purpose to a screen reader: the label it dropped was the only words on it.
        Box(if (markOnly) Modifier.semantics { contentDescription = placeholderResolved.spokenText } else Modifier) {
            MimirSearchGlyph(color = MimirColors.TextSecondary)
        }
        if (!markOnly) {
            MimirIntentText(
                intent = if (query.isBlank()) {
                    MimirTextIntent.StaticChrome(placeholder)
                } else {
                    MimirTextIntent.Authored(query)
                },
                style = MimirType.RowSupport.copy(fontSize = (placeholderSizeSp ?: MimirTypeStep.Label.value).sp),
                color = if (query.isBlank()) MimirColors.TextMuted else MimirColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        if (query.isNotBlank()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                    .clickable(onClick = onClear)
                    .padding(MimirSpacing.xs),
            ) {
                Text("×", style = MimirTypeScale.RailAction, color = MimirColors.TextSecondary) // × clear
            }
        }
    }
    if (!small) {
        Row(
            modifier = pillModifier
                // Update: the etched chip's surface, not a filled capsule; the existing heights stay (this one 44 dp, the small one 32). The ring (inside controllerModifier)
                // goes first, so it hugs this plate and the surface paints over its wash.
                .then(controllerModifier)
                .mimirEtchedChipSurface(palette, warmed = false, focused = controllerEnabled && focused, edgeFlush = true)
                .heightIn(min = LibraryGamelistSearchControlMinHeightDp.dp)
                .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                .clickable(onClick = onOpen)
                .padding(
                    horizontal = if (markOnly) 0.dp else sidePadding,
                    vertical = 6.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (markOnly) Arrangement.Center else Arrangement.spacedBy(MimirSpacing.sm),
        ) {
            content()
        }
    } else {
        // The touch area keeps its full height; only the drawn pill (and the focus ring, which hugs it) is small.
        Box(
            modifier = pillModifier
                .heightIn(min = LibraryGamelistSearchControlMinHeightDp.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpen,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(controllerModifier)
                    .mimirEtchedChipSurface(palette, warmed = false, focused = controllerEnabled && focused, edgeFlush = true)
                    .heightIn(min = LibraryGamelistCompactSearchPillHeightDp.dp)
                    .clip(RoundedCornerShape(MimirSpacing.radiusPill))
                    .padding(horizontal = if (markOnly) 0.dp else MimirSpacing.md, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (markOnly) Arrangement.Center else Arrangement.spacedBy(6.dp),
                content = content,
            )
        }
    }
}

/** The status text + color for a game launch [state], or null when nothing should be shown (Idle).
 *  [platforms] resolves the FRIENDLY platform name (copy review P1-4: never the raw slug like "gba"). */
@Composable
private fun launchStatusText(
    state: GameLaunchUiState,
    platforms: List<PlatformRow>,
): Pair<String, androidx.compose.ui.graphics.Color>? {
    val palette = LocalMimirPalette.current
    val res = libraryResources()
    val message = gameLaunchStatusMessage(state) { platformLabel(res, platforms, it) } ?: return null
    val color = when (state) {
        GameLaunchUiState.Launching, GameLaunchUiState.IntentSent -> palette.accent
        else -> MimirColors.TextSecondary
    }
    return message to color
}


@Composable
private fun EmptyLibraryState() {
    Column(
        Modifier.fillMaxWidth().padding(top = MimirSpacing.xxl),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MimirSpacing.sm),
    ) {
        MimirChromeText(
            stringResource(R.string.library_gamelist_empty_library_title),
            style = MimirType.TitleCaps,
            color = MimirColors.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.library_gamelist_empty_library_body),
            style = MimirType.RowSupport,
            color = MimirColors.TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────────────────────────
private fun favoriteSegmentsForUi(
    romFavoritesCount: Int,
    nativeGameRows: List<NativeShelfRow>,
    nativeAppRows: List<NativeShelfRow>,
): List<FavoriteSegment> = buildList {
    if (romFavoritesCount > 0) add(FavoriteSegment.GAMES)
    if (nativeGameRows.isNotEmpty()) add(FavoriteSegment.ANDROID_GAMES)
    if (nativeAppRows.isNotEmpty()) add(FavoriteSegment.ANDROID_APPS)
}

private fun NativeShelfRow.matchesFavoriteQuery(query: String): Boolean =
    listOf(
        label,
        packageName,
        rowKindLabel ?: if (isGame) "Android game" else "Android app",
    ).any { it.contains(query, ignoreCase = true) }

/**
 * The native rows the Favorites list draws for [segment], in the order it draws them: the segment's favourites, narrowed by
 * the search the list is showing. The one definition, read by the screen to paint and by a native hide to choose the card
 * that stays (walk-32h), so what the player sees and what the hide hands focus to can never be two lists.
 */
internal fun nativeFavoriteRowsShown(
    segment: FavoriteSegment,
    nativeFavoriteGameRows: List<NativeShelfRow>,
    nativeFavoriteAppRows: List<NativeShelfRow>,
    searchQuery: String,
): List<NativeShelfRow> {
    val source = when (segment) {
        FavoriteSegment.ANDROID_GAMES -> nativeFavoriteGameRows
        FavoriteSegment.ANDROID_APPS -> nativeFavoriteAppRows
        FavoriteSegment.GAMES -> emptyList()
    }
    val query = searchQuery.trim()
    return if (query.isBlank()) source else source.filter { it.matchesFavoriteQuery(query) }
}

internal fun enterLibraryBaseScope(authority: FocusAuthority) {
    if (authority.state.value.scope !in LIBRARY_SCOPES) authority.pushScope(LibraryShelfScope)
}

private val LIBRARY_SCOPES =
    setOf(
        LibraryShelfScope,
        GamelistScope,
        GameDetailScope,
        OpenWithScope,
        HideConfirmScope,
        NativeHideConfirmScope,
        NativeAddPickerScope,
        LibrarySearchScope,
        LibrarySearchFilterPickerScope,
    )

/**
 * The names of the Search filters that are on (combined33 sweep: with only a genre filter the header read "2 games" with nothing to say a filter was on): the genre as the games' own text
 * writes it ("Platformer", from the facets the picker lists; the stored key is lower case), and a platform that is not the one the list already names in its title.
 */
internal fun gamelistActiveFilterLabels(
    criteria: LibrarySearchCriteria,
    facets: LibrarySearchFacets,
    platformLabelOf: (PlatformId) -> String,
    openPlatform: PlatformId?,
): List<String> = buildList {
    (criteria.genre as? LibrarySearchGenreFilter.Exact)?.let { genre ->
        add(
            facets.genres.firstOrNull { it.key == genre.key }?.label
                ?: genre.key.value.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) },
        )
    }
    (criteria.platform as? LibrarySearchPlatformFilter.Exact)?.platformId
        ?.takeIf { it != openPlatform }
        ?.let { add(platformLabelOf(it)) }
}

/**
 * The "N discs / N versions" badge for a collapsed group, or null for a singleton. Multi-disc →
 * "N discs"; region/version/cross-storage → "N versions" (mirrors `GameGroup.collapseBadge`).
 */
internal fun collapseBadge(row: GameRow): String? {
    if (row.memberCount <= 1) return null
    val multiDisc = row.duplicateKind == com.gruning.mimir.rewrite.core.model.DuplicateGroupKind.MULTI_DISC
    return if (multiDisc) {
        row.discCount.takeIf { it > 1 }?.let { "$it discs" }
    } else {
        "${row.memberCount} versions"
    }
}

// ── Preview (standalone: self-provides a focus authority + the Fake repository) ──────────────────
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 960, heightDp = 540)
@Composable
private fun LibraryPlatformsPreview() {
    val authority = remember { com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority() }
    val vm = remember {
        LibraryViewModel(com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository())
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalFocusAuthority provides authority) {
        LibraryScreen(viewModel = vm, authority = authority)
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, widthDp = 960, heightDp = 540)
@Composable
private fun LibraryGamelistPreview() {
    val authority = remember { com.gruning.mimir.rewrite.core.input.DefaultFocusAuthority() }
    val vm = remember {
        LibraryViewModel(com.gruning.mimir.rewrite.feature.library.data.FakeLibraryRepository())
            .apply { openPlatform(PlatformId.PS1) }
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalFocusAuthority provides authority) {
        LibraryScreen(viewModel = vm, authority = authority)
    }
}

/** The hero on screen outside an L2/R2 hold. Plain memory, written in composition only outside one. */
private class GamelistBackdropMemory {
    var hero: String? = null
    var motion: String? = null
}

/**
 * Favourites' segment change. It was a
 * dissolve -- the new segment at full alpha at once with the old one's pixels fading over it, both visible -- and
 * Android Games <-> Android Apps shared one call site, so the departing layer redrew live and that switch read as a cut,
 * scroll carried across. Now it is the swap tier every other shoulder switch on this surface uses: the old segment
 * leaves as recorded pixels, the new one fades and glides in from its chip's side on the swap clock (OverlayMs from the
 * 130 ms lead), presses waiting until it shows. Each segment is its own tree ([key]), so each departs as
 * itself and keeps its own scroll. A press once it shows finishes the arrival. Reduced motion is a settled state.
 */
@Composable
private fun FavoritesSegmentSwap(
    segment: FavoriteSegment,
    cancellationEpoch: Long,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = mimirReducedMotion(),
    content: @Composable (FavoriteSegment) -> Unit,
) {
    val memory = remember { FavoritesSegmentMemory(segment) }
    // Signed by the chip strip's order: a segment to the right arrives from the right.
    val direction = remember(segment) {
        val previous = memory.last
        memory.last = segment
        segment.ordinal.compareTo(previous.ordinal).coerceIn(-1, 1)
    }
    val animates = direction != 0 && !reducedMotion
    val progress = remember(segment) { Animatable(if (animates) 0f else 1f) }
    // False until the segment has arrived. The centred card's gleam waits for it (ring spec section 5), so its sweeps are
    // seen and not spent behind the held list. A flag set when the arrival ends, never progress read in composition (N1).
    var arrived by remember(segment) { mutableStateOf(!animates) }
    val outerArrived = LocalMimirPageArrived.current
    val startEpoch = remember(segment) { cancellationEpoch }
    LaunchedEffect(segment) {
        if (!animates) return@LaunchedEffect
        if (progress.value <= 0f) holdMimirInputUntilShown(progress) { progress.value > 0f }
        withFrameNanos { }
        progress.animateTo(targetValue = 1f, animationSpec = mimirSwapEnterSpec(reducedMotion))
        arrived = true
    }
    LaunchedEffect(cancellationEpoch) {
        // Never before the segment shows: that wait is the press rule, not a motion to skip.
        if (cancellationEpoch != startEpoch && progress.value > 0f && progress.value < 1f) {
            progress.snapTo(1f)
            // The snap interrupts the arrival's own animateTo, and nothing after that call runs: this is where it ends.
            arrived = true
        }
    }
    val travelPx = with(LocalDensity.current) { MimirMotion.GlideTravelDp.dp.toPx() }
    MimirPageHandoff(
        epoch = segment,
        departs = animates,
        exitTravelPx = MimirPageMotion.exitTravelPx(direction, travelPx),
        incomingAlpha = { progress.value },
        incomingTravelPx = {
            MimirPageMotion.arrivalTravelPx(direction, travelPx) * (1f - progress.value.coerceIn(0f, 1f))
        },
        reducedMotion = reducedMotion,
        modifier = modifier,
    ) {
        CompositionLocalProvider(LocalMimirPageArrived provides (outerArrived && arrived)) {
            key(segment) {
                content(segment)
            }
        }
    }
}

/** The segment shown last, for the swap's direction. Plain memory, written only inside `remember`. */
private class FavoritesSegmentMemory(var last: FavoriteSegment)
