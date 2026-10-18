package com.gruning.mimir.rewrite

import com.gruning.mimir.rewrite.core.model.jobs.JobProgressState
import com.gruning.mimir.rewrite.core.model.CompanionJobProgress
import com.gruning.mimir.rewrite.core.model.CompanionMode
import android.app.Presentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.gruning.mimir.rewrite.core.ui.LocalBottomTrackpadOpen
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.ComposeView
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiFloatTransitionReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import com.gruning.mimir.rewrite.core.ui.BottomUiStackHeartbeat
import com.gruning.mimir.rewrite.core.ui.BottomUiSurfaceReceipt
import com.gruning.mimir.rewrite.core.ui.BottomUiTransitionProbe
import com.gruning.mimir.rewrite.core.ui.LocalBottomUiDisplay
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.bottomUiTransition
import com.gruning.mimir.rewrite.core.ui.mimirReducedMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.MimirMessageClass
import com.gruning.mimir.rewrite.core.ui.MimirGlassWarmup
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirPagerFlingVelocity
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.MimirPageMotion
import com.gruning.mimir.rewrite.core.ui.MimirSwapPageGate
import com.gruning.mimir.rewrite.core.ui.mimirOverlayArriveSpec
import com.gruning.mimir.rewrite.core.ui.mimirOverlayContentTransform
import com.gruning.mimir.rewrite.core.ui.mimirOverlayLeaveSpec
import com.gruning.mimir.rewrite.core.ui.mimirPageTouchShield
import com.gruning.mimir.rewrite.core.ui.mimirRingSafeFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapFadeAlpha
import com.gruning.mimir.rewrite.core.ui.mimirSwapOverlayTransform
import com.gruning.mimir.rewrite.core.ui.LocalMimirPalette
import com.gruning.mimir.rewrite.core.ui.mimirGlassBackdropProgress
import com.gruning.mimir.rewrite.core.ui.mimirGlassScrimColor
import com.gruning.mimir.rewrite.core.ui.rememberMimirGlassProgress
import com.gruning.mimir.rewrite.core.ui.rememberMimirHeldGlassProgress
import com.gruning.mimir.rewrite.core.ui.LocalMimirKeyboardHapticFeedback
import com.gruning.mimir.rewrite.core.ui.LocalMimirAmbientMotionPhase
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gruning.mimir.rewrite.feature.companion.CompanionRecentsDrawer
import com.gruning.mimir.rewrite.feature.companion.CompanionPrimaryRoute
import com.gruning.mimir.rewrite.feature.library.CompanionPlatformShelf
import com.gruning.mimir.rewrite.feature.library.CompanionLaunchCardLayer
import com.gruning.mimir.rewrite.feature.library.CompanionLaunchDisplayChoice
import com.gruning.mimir.rewrite.core.model.library.GameRow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import com.gruning.mimir.rewrite.feature.library.companionMirrorEdgeBackSwipeAccepted
import com.gruning.mimir.rewrite.feature.companion.CompanionMannazCover
import com.gruning.mimir.rewrite.feature.companion.collectAsStateWhileLive
import com.gruning.mimir.rewrite.feature.companion.CompanionSurface
import com.gruning.mimir.rewrite.feature.companion.CompanionQuickLaunchDestination
import com.gruning.mimir.rewrite.feature.companion.CompanionOpensOnDialog
import com.gruning.mimir.rewrite.core.ui.MimirFocusAlwaysArmed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import com.gruning.mimir.rewrite.core.model.home.HomeShelfItemId
import java.util.UUID

private const val CompanionShelfDiagnosticTag = "MimirShelfDiagnostic"
private const val CompanionShelfExitTag = "MimirShelfExit"
private const val CompanionPlatformShelfLayerZ = 1f
private const val CompanionPlatformShelfRemnantLayerZ = 3.5f
private const val CompanionInstrumentsLayerZ = 2f
private const val CompanionQuickPickerLayerZ = 3f
private const val CompanionRecentsDrawerLayerZ = 4f
private const val CompanionGameDisplayChoiceLayerZ = 5f
private const val CompanionPresentationIntroContentWaitMs = 400L

internal fun companionPresentationIntroMayStart(
    hasContent: Boolean,
    alreadySettled: Boolean,
    waitedMs: Long,
    maxWaitMs: Long = CompanionPresentationIntroContentWaitMs,
): Boolean = alreadySettled || hasContent || waitedMs >= maxWaitMs

internal fun companionPresentationFadeMayStart(
    hasContent: Boolean,
    frameCommitted: Boolean,
    alreadySettled: Boolean,
): Boolean = alreadySettled || (hasContent && frameCommitted)

/**
 * B1 fix round: whether the platform shelf's layer takes no touch. From the moment a platform is asked for until the shelf has painted a frame
 * (the 130 ms lead of its arrival, and any wait for its first content frame) it stands at alpha 0 over a lattice that is still on screen, so a
 * second tap there would open a game card nobody has seen. The shield covers the whole layer, so the lattice beneath is not tapped through it
 * either. It is lifted by the shelf's own first painted frame and is never applied to a shelf that is leaving.
 */
internal fun companionShelfTouchShielded(platformOpen: Boolean, shelfEnter: Float): Boolean = platformOpen && shelfEnter <= 0.001f

/**
 * RC-01 (Thor session-render regression, 2026-10-14): the session the Now Playing takeover's PAGE is built from. The page is the live session's while
 * there is one, and the last live session's once it has ended, so the page that is dissolving back to the resting layout keeps its pixels for
 * the whole ReturnMs instead of recomposing to nothing on the first frame. The takeover's own open gate (its session key and game id) is not
 * routed through this: it still follows the live session, which is what closes the takeover.
 */
internal fun companionTakeoverPageGame(live: MimirGameSession?, lastLive: MimirGameSession?): MimirGameSession? = live ?: lastLive

/**
 * [companionTakeoverPageGame] as the window wires it (RC-01): the last live session is kept across this scope's compositions, so the departing
 * takeover's page is built from it on the frame the game ends. One function for the Presentation and `CompanionTakeoverDissolveRenderTest`
 * (W2L7 review #4): a Presentation reverted to the live session alone fails that test.
 */
@Composable
internal fun rememberCompanionTakeoverPageGame(live: MimirGameSession?): MimirGameSession? {
    val lastLive = remember { arrayOfNulls<MimirGameSession>(1) }
    if (live != null) lastLive[0] = live
    return companionTakeoverPageGame(live, lastLive[0])
}

/**
 * Whether this window wears the reveal cover (Q7a, W2L7 review #2): it has been revealed from behind a host ([reveals],
 * `MimirCompanionPresentation.reclaimReveals`) since the last reveal the mount cover covered ([spent]: a reveal that arrives while the mount
 * cover is up is covered by it and spent, F2), motion is not reduced (reduced motion cuts, as the cold cover does), and the mount cover (a cold
 * start's, or a fresh reclaim window's) is not up now ([mountCoverUp]): one cover at a time, one cover per reveal, never two.
 */
internal fun companionReclaimCoverWorn(reveals: Int, reducedMotion: Boolean, mountCoverUp: Boolean, spent: Int): Boolean =
    reveals > spent && !reducedMotion && !mountCoverUp

/**
 * How long a revealed window's decor may stay hidden waiting for the composition that carries its reveal (and its cover) before the fallback
 * shows it anyway: generous beside the one frame the composition normally takes, short beside the guard's wait for a committed frame.
 */
internal const val CompanionDecorRevealFallbackMs = 100L

/** The receipt name of the reclaim handoff cover's exit fade: begun as the cover starts to leave, `done` when it is gone. */
internal const val CompanionReclaimCoverFade = "reclaim-cover.fade"

internal fun companionShelfEnterTarget(shelfHasFrame: Boolean): Float = if (shelfHasFrame) 1f else 0f

/**
 * How long the platform shelf waits for its content's first frame (`onContentFrame`) before it is admitted anyway: a wall-clock safety net
 * for a shelf whose host never reports, not a motion clock (R6-P1-08). Named by the audit 2026-10-14 (B-35); the value is as it was.
 */
internal const val CompanionShelfFirstFrameFallbackMs = 400L

internal fun companionShelfExitTarget(platformOpen: Boolean, exitSurfaceReady: Boolean): Float =
    if (platformOpen || !exitSurfaceReady) 1f else 0f

// R6-P1-08 / F-C4a: a shelf whose host was never admitted (e.g. the Activity workspace page, which
// gates the block below) never ran `onContentFrame` or its 400 ms fallback, so `shelfHasFrame` never
// became true and the enter target never left 0. Its exit target then computes to the same 0 it
// already held, so `animateFloatAsState` sees no change, runs no animation, and never calls
// `finishedListener` — the one place `shelfLeaving` otherwise clears. A shelf with no frame to begin
// with has nothing to dissolve, so its exit must be immediate rather than wait on that animation.
internal fun companionShelfExitSkipsAnimation(exitPending: Boolean, shelfHasFrame: Boolean): Boolean =
    exitPending && !shelfHasFrame

/**
 * Round-5 triage 4a (log proof, Thor build 5fa4c795, route on gbc): the platform list the Bottom draws over its lattice,
 * for a route and the effective mode. It was admitted for HOME alone, so a reclaim's hold (IDLE) and an app on Main
 * (RUNNING_APP) each dropped it to the lattice and faded it back in over 160 ms with the route untouched.
 *
 * - HOME and RUNNING_APP admit. That is `CompanionSurface`'s `panelMode` rule: an app on Main never takes the Bottom's
 *   place.
 * - IDLE admits only as the reclaim hold ([CompanionState.isReclaimHold]). The cold-start hold and an empty shelf's
 *   resting payload are IDLE too, and they stay refused.
 * - NOW_PLAYING refuses: a running game still takes screen 2 wholesale.
 */
internal fun companionPlatformShelfDestination(
    route: CompanionLibraryRoute.Destination?,
    mode: CompanionMode,
    reclaimHold: Boolean,
): CompanionLibraryRoute.Destination.Platform? {
    val platform = route as? CompanionLibraryRoute.Destination.Platform ?: return null
    val admitted = when (mode) {
        CompanionMode.HOME, CompanionMode.RUNNING_APP -> true
        CompanionMode.IDLE -> reclaimHold
        CompanionMode.NOW_PLAYING -> false
    }
    return platform.takeIf { admitted }
}

/**
 * [companionPlatformShelfDestination] read synchronously off process state, for the list's own closures: a Back, PLAY or
 * arrange that lands in the same frame as a mode or route change must not trust the composed admission (S2-07).
 */
internal fun companionLivePlatformShelfDestination(): CompanionLibraryRoute.Destination.Platform? {
    val live = effectiveCompanionPayload(CompanionState.payload.value, CompanionSceneState.scene.value)
    return companionPlatformShelfDestination(
        route = CompanionLibraryRoute.destination.value,
        mode = live.mode,
        reclaimHold = CompanionState.isReclaimHold(live),
    )
}

/**
 * Ledger D8: a platform tile tapped while a game runs (the Now Playing mode) still writes its route, but the shelf is refused until the game ends
 * ([companionPlatformShelfDestination]), so the tap had no visible answer. The route is kept (the shelf opens when the game does end), and the answer is
 * [CompanionShelfOpensAfterGameCopy], said on the Bottom at the tap.
 */
internal fun companionPlatformTapWaitsForGame(mode: CompanionMode): Boolean = mode == CompanionMode.NOW_PLAYING

/** [companionPlatformTapWaitsForGame] read synchronously off process state, as the list's own closures read the admission. */
internal fun companionLivePlatformTapWaitsForGame(): Boolean =
    companionPlatformTapWaitsForGame(effectiveCompanionPayload(CompanionState.payload.value, CompanionSceneState.scene.value).mode)

/** What a platform tile's tap says while a game runs: a plain sentence, the house guidance register (nothing failed; the shelf is simply not free yet). */
internal const val CompanionShelfOpensAfterGameCopy = "The shelf opens when the game ends."

/**
 * Round-5 triage 4a, item 2: whether the list's entry skips the 160 ms ramp. Only a return to the platform it last showed
 * with no route write since, which is a mode round trip and not a navigation; a lattice tap and Back keep the ramp.
 */
internal fun companionShelfEntrySnaps(
    lastShownPlatform: String?,
    destinationPlatform: String?,
    routeWrittenSinceShown: Boolean,
): Boolean = destinationPlatform != null && destinationPlatform == lastShownPlatform && !routeWrittenSinceShown

/** The enter value drawn: a snapping entry is opaque in the composition its target reaches 1, not a frame later. */
internal fun companionShelfEnterValue(entrySnaps: Boolean, target: Float, animated: Float): Float =
    if (entrySnaps && target >= 1f) 1f else animated

/**
 * Round-5 triage 4a, item 2: what the list's entry decision needs from earlier compositions of the list. It has to outlive
 * a Presentation window: on the Thor (build 10524eb4) a reclaim and a SWAP each composed the list again in a fresh window,
 * whose `remember` started empty, so the list ramped back in with the route unchanged.
 */
internal class CompanionShelfEntryMemory {
    var lastSeenRoute: CompanionLibraryRoute.Destination? = null
    var routeWritten = false
    var lastShownPlatform: String? = null
}

/** One composition's entry decision over [memory]. [snaps] is read in composition; [commit] runs only from SideEffect. */
internal class CompanionShelfEntryHistory(private val memory: CompanionShelfEntryMemory) {
    private var admittedPlatform: String? = null
    private var entrySnaps = false

    /** True when this composition's list is arriving at [destination]: from nothing, or from another platform. */
    fun entering(destination: String?): Boolean = destination != null && admittedPlatform != destination

    fun snaps(route: CompanionLibraryRoute.Destination?, destination: String?): Boolean =
        if (entering(destination)) {
            companionShelfEntrySnaps(
                lastShownPlatform = memory.lastShownPlatform,
                destinationPlatform = destination,
                routeWrittenSinceShown = memory.routeWritten || route != memory.lastSeenRoute,
            )
        } else {
            destination != null && entrySnaps
        }

    fun commit(route: CompanionLibraryRoute.Destination?, destination: String?, snaps: Boolean) {
        if (route != memory.lastSeenRoute) {
            memory.routeWritten = true
            memory.lastSeenRoute = route
        }
        if (entering(destination)) entrySnaps = snaps
        if (destination != null) {
            memory.lastShownPlatform = destination
            memory.routeWritten = false
        }
        admittedPlatform = destination
    }
}

/** The memory every composition of the list shares for the life of the process. Main thread only. */
private val companionShelfEntryMemory = CompanionShelfEntryMemory()

/** A new composition's entry history, over the memory that outlives its window. */
internal fun companionShelfEntryHistory(): CompanionShelfEntryHistory =
    CompanionShelfEntryHistory(companionShelfEntryMemory)

/** Unit tests start from a process that has shown no list. */
internal fun resetCompanionShelfEntryMemoryForTest() {
    companionShelfEntryMemory.lastSeenRoute = null
    companionShelfEntryMemory.routeWritten = false
    companionShelfEntryMemory.lastShownPlatform = null
}

// R6-P1-10 tuning: matches AndroidCompanionOverlayController's native CP03 strip
// so the Compose gesture that replaces it over a folder/layer claims the same band with the same commit.
private const val CompanionMirrorEdgeBackBandDp = 32
private const val CompanionMirrorEdgeBackTriggerDp = 40

/**
 * R6-P1-10: the Compose replacement for the native CP03 strip's folder/layer admission
 * ([companionOwnMirrorFolderEdgeBackEnabled] now always refuses it). Observed at
 * [PointerEventPass.Final] on the Presentation's root Box — same reasoning as
 * `CompanionPlatformShelf`'s own back-swipe detector: an ordinary tap or scroll a child already consumed
 * (a tile, the grid) never reaches here, so only a real, otherwise-unclaimed edge swipe is ever accepted.
 * [canStart] is checked once a swipe within the band is recognized, right before it fires — reading
 * [CompanionBottomFolderState.open] rather than a captured value, since that flow is the live, single
 * source of truth for "is a closable layer open" this same Presentation composition also feeds.
 */
private suspend fun PointerInputScope.detectCompanionMirrorEdgeBackSwipe(
    edgeDistancePx: Float,
    triggerDistancePx: Float,
    canStart: () -> Boolean,
    onBack: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.x < size.width - edgeDistancePx) return@awaitEachGesture
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (event.changes.any { it.isConsumed }) return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            if (companionMirrorEdgeBackSwipeAccepted(
                    downX = down.position.x,
                    widthPx = size.width.toFloat(),
                    edgeDistancePx = edgeDistancePx,
                    triggerDistancePx = triggerDistancePx,
                    deltaX = change.position.x - down.position.x,
                    deltaY = change.position.y - down.position.y,
                    elapsedMillis = change.uptimeMillis - down.uptimeMillis,
                    touchSlopPx = viewConfiguration.touchSlop,
                    longPressMillis = viewConfiguration.longPressTimeoutMillis,
                ) && canStart()
            ) {
                change.consume()
                onBack()
                return@awaitEachGesture
            }
            if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                return@awaitEachGesture
            }
        }
    }
}

private enum class CompanionInstrumentPanelKind {
    TWEAKER,
}

/**
 * QA D5 (2026-09-11): the shelf's debug line, printed once per change of its effect keys. Those keys include the
 * scene revision and the effective mode, so a MainActivity resume re-prints the line with the library route
 * untouched -- which is how re-prints read as "re-routes" in the device pass. The two trailing fields say whether the
 * shown destination moved and which input moved it, so a re-print can no longer pass for a navigation.
 */
internal data class CompanionShelfDiagnosticSnapshot(
    /** The library route's platform id; null at the lattice. */
    val route: String?,
    /** The route as admitted by the HOME mode gate -- what the shelf branch renders. */
    val destination: String?,
    val shown: String?,
    val sceneRevision: Long,
    val sceneRoute: String,
    val mode: String,
)

internal fun companionShelfDiagnosticMessage(
    previous: CompanionShelfDiagnosticSnapshot?,
    current: CompanionShelfDiagnosticSnapshot,
): String {
    val cause = if (previous == null) {
        "compose"
    } else {
        when {
            previous.route != current.route -> "route"
            previous.mode != current.mode -> "mode"
            previous.shown != current.shown -> "shown"
            previous.sceneRevision != current.sceneRevision -> "scene"
            else -> "none"
        }
    }
    return "destination=${current.destination ?: "none"} " +
        "shown=${current.shown ?: "none"} " +
        "sceneRevision=${current.sceneRevision} " +
        "sceneRoute=${current.sceneRoute} " +
        "effectiveMode=${current.mode} " +
        "destinationChanged=${previous?.destination != current.destination} " +
        "cause=$cause"
}

/**
 * Perf §4.5 (2026-09-11, P3-1 and S01-1): what the Bottom launch glass does once a secondary launch stops pending.
 *
 * The runtime leaves `LaunchPending` at `HostCovered` -- the task-start acknowledgement, not the app's first frame --
 * and this window keeps drawing above the app until the runtime parks it `SuppressDismissDelayMillis` (600 ms) later
 * (`CompanionRuntimeCoordinator.hostCovered` -> `CompanionDisplayController.dismissPresentationAfterCover`). The glass
 * used to unwind at `HostCovered`, so the lattice drew sharp for ~400 ms before the app. It now holds while THIS
 * launch's host is covered and this window still shows, bounded by [CompanionSecondaryLaunchGlassHoldMs]; a failure
 * or a reclaim clears it with no unwind.
 */
internal enum class CompanionSecondaryLaunchGlass {
    /** Keep the scene behind the glass. */
    SHOW,

    /** Unwind on the house glass curve: the pre-hold behaviour, and the safety net when nothing parks this window. */
    RELEASE,

    /** Sharp at once, no unwind: the launch failed, the display came back, or nothing can see this window. */
    CLEAR,
}

/** Safety bound on the hold after `HostCovered`; the runtime parks the window at 600 ms. */
internal const val CompanionSecondaryLaunchGlassHoldMs = 2_000L

internal fun companionSecondaryLaunchGlass(
    transitionOperationId: String,
    launchPending: Boolean,
    runtime: CompanionRuntimeSnapshot,
    presentationCovered: Boolean,
    msSinceHostCovered: Long,
    holdTimeoutMs: Long = CompanionSecondaryLaunchGlassHoldMs,
): CompanionSecondaryLaunchGlass {
    if (launchPending) return CompanionSecondaryLaunchGlass.SHOW
    val disposition = runtime.disposition
    val settlement = runtime.launchSettlement
    val failed = (
        settlement != null &&
            settlement.kind == LaunchSettlementKind.FAILED &&
            settlement.operationId.toString() == transitionOperationId
        ) || (
        disposition is SecondaryDisplayDisposition.RecoveryNeeded &&
            disposition.operationId.toString() == transitionOperationId
        )
    val hostCovered = disposition is SecondaryDisplayDisposition.Yielded &&
        disposition.lease?.operationId?.toString() == transitionOperationId
    return when {
        failed -> CompanionSecondaryLaunchGlass.CLEAR
        hostCovered && presentationCovered -> CompanionSecondaryLaunchGlass.CLEAR
        hostCovered && msSinceHostCovered < holdTimeoutMs -> CompanionSecondaryLaunchGlass.SHOW
        hostCovered -> CompanionSecondaryLaunchGlass.RELEASE
        disposition is SecondaryDisplayDisposition.ReclaimPending ||
            disposition is SecondaryDisplayDisposition.MirrorPresented ||
            disposition is SecondaryDisplayDisposition.Presenting -> CompanionSecondaryLaunchGlass.CLEAR
        else -> CompanionSecondaryLaunchGlass.RELEASE
    }
}

/**
 * QA D11 (2026-09-11): whether the recents drawer covers THIS window's surface.
 *
 * The Presentation-hosted drawer always did. [companionDrawerHostSelection] picks OVERLAY for `MirrorPresented` while
 * the primary Activity is stopped (8789e3f8: so the drawer stays above a dual-screen game's own Presentation), and that
 * drawer draws in a native window over this one -- while this surface kept its scene sharp and its cursor breath
 * running underneath. Over the own mirror it now covers exactly like the Presentation-hosted drawer. Over a yielded
 * foreign app this window is parked, and a dismissed overlay keeps its host while it fades, so neither counts.
 */
internal fun companionDrawerCoversPresentation(
    drawer: CompanionRecentsState.Ui,
    renderOperationId: UUID,
    disposition: SecondaryDisplayDisposition,
): Boolean {
    if (drawer.isVisibleOnPresentation(renderOperationId)) return true
    if (!drawer.requestedVisible || drawer.drawerHost !is CompanionDrawerHost.Overlay) return false
    val mirror = disposition as? SecondaryDisplayDisposition.MirrorPresented ?: return false
    return mirror.attempt.operationId.toString() == renderOperationId.toString()
}

/**
 * QA D11 (2026-09-11): whether the surface's own quick-launch rail stands down under the drawer.
 *
 * [companionDrawerCoversPresentation] softens the scene for either drawer host, but only the OVERLAY-hosted drawer puts
 * a second rail over this one from another window. That host passes no instruments seat, so it drew six seats over the
 * surface's seven and every seat missed its twin (evidence S20). The Presentation-hosted drawer ([drawerSelected]) is
 * composed in this window from the same rail with the same seats (the shared rail rendered by
 * `CompanionRecentsDrawer`), so there the surface rail stays exactly as it was.
 */
internal fun companionSurfaceRailCovered(drawerCoversSurface: Boolean, drawerSelected: Boolean): Boolean =
    drawerCoversSurface && !drawerSelected

// QA D17 (2026-09-11): the game display chooser and the per-game display preference were silent transactions.
internal fun gameDisplayChooserOpenedLogMessage(gameId: String, editing: Boolean): String =
    "display chooser open game=$gameId editing=$editing"

internal fun gameDisplayChooserChosenLogMessage(gameId: String, destination: CompanionQuickLaunchDestination): String =
    "display chooser choose game=$gameId destination=$destination"

internal fun gameDisplayChooserCancelledLogMessage(gameId: String, editing: Boolean): String =
    "display chooser cancel game=$gameId editing=$editing"

internal fun gameDisplayPreferenceEditedLogMessage(
    gameId: String,
    previous: CompanionGameDisplayPreference?,
    destination: CompanionQuickLaunchDestination,
): String =
    "display preference edit game=$gameId " +
        "from=${previous?.name ?: CompanionQuickLaunchDestination.ASK_NEXT_TIME.name} to=$destination"

/**
 * QA D17 (2026-09-11, P3-1): a tap the launch lock dropped. The device log had the first pin tap's `LaunchRequested`
 * and nothing at all for the second touch 28 ms later. [target] is a seat's or tab's package, `all_apps`, `empty_q<N>`
 * or `dismiss`; [pendingOp] is the launch operation the rendering host holds pending, null when it holds none.
 */
internal fun secondaryLaunchIgnoredLogMessage(target: String, pendingOp: String?): String =
    "secondary launch ignored target=$target reason=launch_locked op=$pendingOp"

internal enum class CompanionFrameCommitDelivery {
    COMPOSITOR_CALLBACK,
    ANIMATION_FALLBACK,
}

internal data class CompanionFrameCommitPlan<T : Any>(
    val token: T,
    val delivery: CompanionFrameCommitDelivery,
    val requestFollowUpFrame: Boolean,
)

internal data class CompanionPresentationFrameToken(
    val operationId: UUID,
    val payloadRevision: Long,
    val sceneRevision: Long,
    val commitEpoch: Long,
)

/** Pure exact-token state for the Presentation's draw-to-compositor acknowledgment. */
internal class CompanionFrameCommitHandshake<T : Any> {
    private enum class Phase { ARMED, AWAITING_COMMIT }

    private var activeToken: T? = null
    private var phase: Phase? = null

    @Synchronized
    fun arm(token: T): Boolean {
        if (activeToken == token && phase != null) return false
        activeToken = token
        phase = Phase.ARMED
        return true
    }

    @Synchronized
    fun beginDraw(token: T, compositorCallbackAvailable: Boolean): CompanionFrameCommitPlan<T>? {
        if (activeToken != token || phase != Phase.ARMED) return null
        phase = Phase.AWAITING_COMMIT
        return CompanionFrameCommitPlan(
            token = token,
            delivery = if (compositorCallbackAvailable) {
                CompanionFrameCommitDelivery.COMPOSITOR_CALLBACK
            } else {
                CompanionFrameCommitDelivery.ANIMATION_FALLBACK
            },
            // A callback registered from onDraw belongs to the next committed frame. A static cold-start
            // surface may not otherwise produce it, so request exactly one bounded follow-up frame.
            requestFollowUpFrame = compositorCallbackAvailable,
        )
    }

    @Synchronized
    fun complete(token: T): Boolean {
        if (activeToken != token || phase != Phase.AWAITING_COMMIT) return false
        activeToken = null
        phase = null
        return true
    }

    @Synchronized
    fun clear() {
        activeToken = null
        phase = null
    }
}

/** Emits only after a window that was already visible is hidden and then uncovered again. */
internal class CompanionWindowRevealGate {
    private var hasBeenVisible = false
    private var hiddenAfterVisible = false

    fun onWindowVisibilityChanged(visibility: Int): Boolean {
        if (visibility != View.VISIBLE) {
            if (hasBeenVisible) hiddenAfterVisible = true
            return false
        }
        if (!hasBeenVisible) {
            hasBeenVisible = true
            return false
        }
        return hiddenAfterVisible.also { hiddenAfterVisible = false }
    }
}

private class CompanionPresentationRoot(
    context: Context,
    private val onWindowRevealed: () -> Unit,
) : FrameLayout(context) {
    private val revealGate = CompanionWindowRevealGate()

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (revealGate.onWindowVisibilityChanged(visibility)) onWindowRevealed()
    }
}

/**
 * The window Mimir puts on the SECONDARY display — a [Presentation] hosting the Compose
 * [CompanionSurface], which renders the live [CompanionState] read-model (now-playing / library mirror /
 * idle) and its touch-only Quicklaunch rail. The window additionally layers the interactive recents drawer;
 * both launch through the process transaction coordinator. No controller focus (FLAG_NOT_FOCUSABLE).
 *
 * LIFECYCLE (adversarial review 2026-07-10): the ComposeView gets its OWN always-RESUMED lifecycle owner,
 * NOT the host Activity's. Compose pauses a window's frame clock when its view-tree lifecycle drops below
 * STARTED — borrowing the Activity meant every recomposition and animation on SCREEN 2 froze the moment an
 * emulator foregrounded on the primary (Mimir STOPPED). Device evidence: the tab manager never appeared
 * during play, the payload froze on stale content. Screen 2's window is VISIBLE regardless of the primary
 * activity's state, so its UI must stay live; the owner is RESUMED while showing and DESTROYED on dismiss.
 * The ViewModelStore is still borrowed from the Activity (shared view-models are intentional).
 */
class MimirCompanionPresentation(
    outerContext: Context,
    display: Display,
    @Suppress("UNUSED_PARAMETER") lifecycleOwner: LifecycleOwner,
    private val viewModelStoreOwner: ViewModelStoreOwner,
    @Suppress("UNUSED_PARAMETER") savedStateRegistryOwner: SavedStateRegistryOwner,
    initialRenderOperationId: UUID,
    private val interactionMotionGate: InteractionMotionGate,
    /** Returns true when the first secondary touch woke the existing screen-idle controller. */
    private val onSecondaryTouchInteraction: () -> Boolean = { false },
    /** Resets the secondary M visibility deadline without changing the screen-idle wake contract. */
    private val onSecondaryMButtonInteraction: () -> Unit = {},
    /** Validates and yields the currently rendered mirror to an already-running secondary game. */
    private val onReturnToGame: (com.gruning.mimir.rewrite.core.model.GameId) -> Boolean = { false },
    /**
     * Whether the native long-press quick menu is up on THIS display, as the scene behind it should show it.
     *
     * The controller's `quickMenuVisualOpen`: it takes the destination value when the menu's outgoing fade
     * starts, so the blur runs with that fade instead of after the window swap (perf 2026-09-11 §4.4b). It is
     * for the backdrop only; nothing in this window routes, focuses or completes on it.
     *
     * Handed in rather than read off the controller singleton, on the same seam every other input to this
     * window already uses: the window is a renderer, and it is the host that knows which controller — if
     * any — owns the overlay above it. A host with no overlay passes nothing and gets a flow that is
     * permanently false, which is the truth for it.
     */
    private val quickMenuVisualOpen: StateFlow<Boolean> = MutableStateFlow(false),
    private val onFirstFrameCommitted: (UUID) -> Unit = {},
    /**
     * Whether this window follows the secondary-home floor's cold-start cover ([CompanionColdStartHandoff.forNewPresentation], explicit state armed by the floor's onCreate): the floor is
     * showing Main's return cover (the Mannaz badge on the theme ground) and this window replaces it. If so, this window's first frame
     * wears the same cover over its content, and the cover fades out on `OverlayMs` once the content is in, so the lattice arrives
     * through a fade and not a hard cut from the floor's cover to this window's backing (policy 6, Thor Bottom revamp). A window
     * that comes back over a yielded app has no cover beneath it and passes false.
     */
    private val coldStartHandoff: Boolean = false,
    /**
     * This window is a FRESH Presentation created for a reclaim (the display controller's `companionFreshPresentationReclaims`: the runtime's
     * disposition is ReclaimPending, as for the DS/3DS `main_game_exit` and any reclaim with no window to reuse). Such a window never parks, so
     * the reveal cover (`reclaimReveals`) can never serve it; it wears the mount cover from its first frame instead, as a cold start does, and
     * fades it on `OverlayMs` once its intro has run, so the restore guard's ground becomes the lattice through a fade and not a cut (X-06).
     */
    private val reclaimHandoff: Boolean = false,
    private val onWindowRevealed: () -> Unit = {},
) : Presentation(outerContext, display) {

    /** Stable token so an old Presentation cannot revoke a newer secondary Tweaker host. */
    private val secondaryBrightnessPresentationToken = UUID.randomUUID()

    /** The presentation's own view-tree owner: RESUMED while the window shows, DESTROYED on dismiss. */
    private class PresentationHost : LifecycleOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun create() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun park() {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
        }

        fun resume() {
            if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private val host = PresentationHost()
    private var motionRoot: RootLease? = null
    private var touchLease: InteractionLease? = null
    private var idleWakeTouchLatched = false
    private val renderOperation = MutableStateFlow(initialRenderOperationId)
    private val renderCommitEpoch = MutableStateFlow(0L)
    private val presentationFrameCommitHandshake = CompanionFrameCommitHandshake<CompanionPresentationFrameToken>()
    private var composeView: ComposeView? = null

    /**
     * True while this window is parked behind a secondary host, or stopped. Perf §4.5: the launch glass holds
     * until the window is actually covered instead of unwinding at `HostCovered`.
     */
    private val parkedBehindSecondaryHost = MutableStateFlow(false)

    /**
     * Each reveal of this window from behind a host (`rebindRenderOperation` on a reclaim, the window parked until then). The Presentation sorts
     * above the restore guard on the Thor, so the guard's own exit is never seen and the lattice cut in over its ground (audit 2026-10-14, Q7a;
     * X-06). Counted BEFORE the decor is shown, so the first revealed frame already wears the reclaim handoff cover ([CompanionMannazCover], on
     * the ground the guard wears) and the lattice arrives through that cover's OverlayMs fade.
     */
    private val reclaimReveals = MutableStateFlow(0)

    /**
     * Shown and not yet stopped: this window's OWN liveness, set by [onStart] and cleared by [onStop]. `Dialog.isShowing()` is not that: on this
     * SDK it also reads the decor's visibility, so a window parked behind a host (its decor INVISIBLE, `parkBehindSecondaryHost`) reported
     * "not showing", `rebindRenderOperation` refused it and the display controller's reuse check saw no window to reuse: every reclaim after
     * a park REPLACED the window (found by CompanionReclaimCoverTest, 2026-10-15), and the designed park-and-reveal never ran. The park, the
     * reveal and the controller's reuse decision gate on [isReusable] instead; `isWindowActuallyVisible` keeps `isShowing`, which is about
     * visibility.
     */
    private var windowShown = false

    /**
     * Alive for a park or a reveal: shown, not stopped, with its content view (false once [onStop] has run, whatever the decor shows). The park
     * and the reveal gate on it. The display controller's reuse and park-retain decisions still read `isShowing` (reuse OFF, lead 2026-10-15:
     * no post-park reuse has been seen on a device, so the old behaviour stays exactly: a reclaim after a park recreates the window, which then
     * wears the mount cover). This is the switch for a later, device-tested change to reuse.
     */
    internal val isReusable: Boolean get() = windowShown && composeView != null

    /** The reveal ([reclaimReveals] epoch) whose decor is still hidden, waiting for the composition that carries its cover; 0 when none. */
    private var decorRevealPendingFor = 0

    /**
     * Shows the decor of a window revealed from behind a host, once per reveal: from the composition's SideEffect on the first composition that
     * carries the reveal (the cover is in the tree, so the frame's traversal draws it over the lattice and never the lattice bare), or from the
     * bounded fallback ([CompanionDecorRevealFallbackMs]) if that composition has not come, which is receipted.
     */
    private fun revealDecorIfPending(reveal: Int, fallback: Boolean) {
        if (reveal == 0 || decorRevealPendingFor != reveal) return
        // F1: a window parked again before its reveal's composition stays hidden over the app that covers it. The park clears the pending
        // reveal; this is the belt for a reveal and a park racing on one frame.
        if (parkedBehindSecondaryHost.value) return
        decorRevealPendingFor = 0
        if (fallback) BottomUiReceipts.log.presentation("reveal_fallback", display.displayId, "composition_late", currentRenderOperationId()?.toString())
        window?.decorView?.visibility = companionPresentationDecorVisibility(coveredBySecondaryHost = false)
    }

    /**
     * Whether the device's screen is on. This window stays RESUMED through a sleep (its UI is meant to stay live while the primary is in the background), so a screen that goes
     * off reaches it nowhere else; [watchScreenState] feeds it from onCreate to onStop.
     */
    private val screenOn = MutableStateFlow(true)

    /**
     * Whether this window is up and lit: not parked or hidden behind another app ([parkedBehindSecondaryHost], which `parkBehindSecondaryHost` sets, the reveal in
     * `rebindRenderOperation` clears and `onStop` sets again), and the screen on. The one fact the Activity page's pad route follows (the window parks without parking the
     * page's pane, so the page cannot see it any other way), handed to the workspace surface.
     */
    private val windowLive = CompanionWindowLive(parkedBehindSecondaryHost, screenOn)
    private var stopWatchingScreen: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        )
        window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        // CEUX02: a Presentation resolves the device-default DIALOG theme, whose windowBackground is LIGHT (the
        // app's cold-navy windowBackground applies only to Activities). The CP-FADE enterAlpha starts at 0f, so
        // the first composed frame is fully transparent and exposes that light backing = a WHITE flash on every
        // reclaim-recreated window (app→menu/swap/tabs are all reclaim-driven). Paint the window's own backing
        // cold navy so the fade-in starts FROM navy, never white.
        window?.setBackgroundDrawableResource(R.color.mimir_cold_window)
        quickLaunchColdRead(
            load = { QuickLaunchStore.load(context) },
            read = { Unit },
        )
        CompanionMediaSessionBridge.start(context)
        CompanionGameDisplayPreferenceStore.load(context)
        motionRoot = interactionMotionGate.registerRoot(InteractionRoot.COMPANION, initiallyVisible = true)
        host.create()
        stopWatchingScreen = watchScreenState(context, screenOn)
        // The launch card's looping opening sound follows this window's liveness (parked, hidden or dark holds it) along with the music's
        // own pause policy (a closed lid previously left the card's sound looping). Withdrawn at onStop.
        GameOpeningIntroState.bottomWindow.value = windowLive
        // Receipts: every Bottom receipt this window's content writes names this display. A new window starts its surfaces
        // from "not seen", so a recreated Presentation enters its lattice again rather than being taken as unchanged.
        val receiptDisplayId = display.displayId
        BottomUiReceipts.log.resetWindow(receiptDisplayId, BottomSurface.PRESENTATION_OWNED)
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(host)
            setViewTreeViewModelStoreOwner(viewModelStoreOwner)
            setViewTreeSavedStateRegistryOwner(host)
            setContent {
                val context = LocalContext.current
                // Every Bottom header's Trackpad plate opens the pad (or its consent card) through the same gateway as the menu row.
                // One provider around everything the Bottom draws; offered only where this device can host a trackpad.
                val openTrackpad: (() -> Unit)? = remember(context) {
                    val open: () -> Unit = { BottomTrackpadMenuGateway(context).open() }
                    if (MimirTrackpadRuntime.capability(context)) open else null
                }
                CompositionLocalProvider(LocalBottomTrackpadOpen provides openTrackpad) {
                val secondaryHaptics = remember(context) { AndroidMimirHaptics(context) }
                val renderPayloadState = CompanionState.renderPayload.collectAsState()
                val renderPayload by renderPayloadState
                val runningJobs by JobProgressState.running.collectAsState()
                val basePayload = renderPayload.payload
                val sceneState = CompanionSceneState.scene.collectAsState()
                val scene by sceneState
                val scenePayload = effectiveCompanionPayload(basePayload, scene)
                val workspacePage by CompanionWorkspaceState.page.collectAsState()
                // B4: the workspace as a two-pane strip [Activity | lattice] that follows the finger (CompanionWorkspaceStrip holds all of its logic).
                val workspaceStrip = remember { CompanionWorkspaceStrip() }
                val workspaceStripFlingPx = with(LocalDensity.current) { MimirPagerFlingVelocity.toPx() }
                // Where the strip is, read where the panes are drawn: its own position while it is off its rest, else NaN (at rest on the shown scene, which needs
                // no width: a cold start straight onto Activity draws before anything has been measured).
                val workspaceStripX = remember(workspaceStrip) { { if (workspaceStrip.active) workspaceStrip.x else Float.NaN } }
                SideEffect { workspaceStrip.reconcile(workspacePage) }
                // The finger's settle, on core-ui's pager clock (OverlayMs on the house easing; reduced motion snaps). It is the one place the page is
                // switched and the control receipt written, and only when the settle landed on the other page: a drag that settles back, or is cancelled,
                // writes no control receipt. `workspace.settle` names a release's settle (a cancel's runs unnamed).
                val commitWorkspacePage: (CompanionWorkspaceSettle) -> Unit = { request ->
                    request.controlReceipt?.let { id ->
                        BottomUiReceipts.log.control(
                            id,
                            receiptDisplayId,
                            if (request.target == CompanionWorkspacePage.ACTIVITY) BottomSurface.LATTICE else BottomSurface.ACTIVITY,
                        )
                    }
                    CompanionWorkspaceState.show(request.target)
                    // B6: the Activity swipe hint retires at this committed switch, and only when the workspace actually landed on Activity.
                    if (request.target == CompanionWorkspacePage.ACTIVITY && companionActivitySwipeLanded(CompanionWorkspaceState.page.value)) {
                        CompanionOrderStore.retireActivitySwipeHint(context)
                    }
                }
                // D37: the settle runs in a scope of its own (CompanionWorkspaceSettleRunner), so the strip's release and its landing do not recompose this root.
                // D27: the landing re-reads every cover that is up (the shelf and the six the page takes no touch under), so none depends on a cancel alone.
                var quickPickerOpen by remember { mutableStateOf(false) }
                CompanionWorkspaceSettleRunner(
                    strip = workspaceStrip,
                    receiptDisplayId = receiptDisplayId,
                    coverUp = { companionLiveWorkspaceCoverUp(renderOperation.value, quickPickerOpen, CompanionProcessRuntime.services(context).bottomQuickLaunchPanel.covering.value) },
                    commit = commitWorkspacePage,
                )
                // R6-P2-08: native EdgeBack asks the Home lattice's own open folder ("Tome") to close here.
                val closeBookRequest by CompanionBottomFolderState.closeRequest.collectAsState()
                val retainedHomePayload = CompanionState.retainedHomePayload()
                val payload = companionPresentationPayload(scenePayload, retainedHomePayload)
                // Receipt: the theme this display now wears. A change names the crossfade it should run
                // (theme-switch.crossfade, in core-ui's scene backdrop); one that never begins reports transition.missing. With the
                // animator scale at zero the backdrop cuts by design, so the line says transition=none reason=reduced_motion and
                // nothing waits for a begin. Cut or fade is the one the mounted backdrop REMEMBERED (it publishes it), so this line and the
                // screen cannot disagree after the setting changes; the live scale is only the answer when no backdrop is mounted.
                // Asked inside the effect, never read in composition.
                LaunchedEffect(payload.themeId) {
                    BottomUiReceipts.log.theme(
                        payload.themeId,
                        receiptDisplayId,
                        transition = "theme-switch.crossfade",
                        reducedMotion = BottomUiReceipts.log.themeSwitchIsCut(receiptDisplayId, bottomUiReducedMotion()),
                    )
                }
                // The Bottom's surface stack, restated every five minutes while this window is shown (one timer, tied to this
                // composition: nothing is written once the Presentation is dismissed).
                BottomUiStackHeartbeat(receiptDisplayId)
                val workspaceSurfacePayload = if (workspacePage == CompanionWorkspacePage.SHELF) {
                    companionShelfPayload(payload, retainedHomePayload)
                } else {
                    payload
                }
                val mediaState by CompanionMediaSessionBridge.state.collectAsState()
                val libraryRepository = CompanionLibraryRoute.repository
                val taskRegistry = remember(context) { CompanionProcessRuntime.services(context).taskRegistry }
                val trackedSecondaryState by taskRegistry.state.collectAsState()
                val quickLaunchCatalogEntries by CompanionQuickLaunchCatalog.entries.collectAsState()
                val mostRecentBottomApp = companionMostRecentBottomApp(
                    state = trackedSecondaryState,
                    catalog = quickLaunchCatalogEntries,
                    selfPackage = context.packageName,
                )
                val recents by CompanionRecentsState.ui.collectAsState()
                // L-6: the runtime decides whether the per-tab ✕ is offered at all.
                val companionRuntime by CompanionReturnCoordinator.runtime().snapshot.collectAsState()
                val sessionState by MimirSessionAuthority.state.collectAsState()
                val returnToGameAvailable = sessionState.gameSession?.let { game ->
                    companionMainDualScreenGameActive(game) &&
                        CompanionState.hasRetainedNowPlaying(game.gameId)
                } == true
                val overlayMenuOpen by quickMenuVisualOpen.collectAsState()
                // O5: the Bottom Quick launch window the ᛗ menu opens over this one (BottomQuickLaunchPanelHost).
                val bottomQuickLaunchCovering by remember(context) {
                    CompanionProcessRuntime.services(context).bottomQuickLaunchPanel.covering
                }.collectAsState()
                val presentationParkedBehindHost by parkedBehindSecondaryHost.collectAsState()
                val sharedBrightnessCoordinator by CompanionProcessRuntime.sharedBrightness.collectAsState()
                // the Bottom menu's Utility is an overlay window above this one (BottomUtilityPanelHost), which carries the panel.
                val bottomUtilityPanel = remember(context) { CompanionProcessRuntime.services(context).bottomUtilityPanel }
                val bottomUtilityCovering by remember(bottomUtilityPanel) { bottomUtilityPanel.covering }.collectAsState()
                val secondaryTweakerVisible = sharedBrightnessCoordinator?.let {
                    it.isOpen && it.presentationTarget == AppBrightnessPresentationTarget.SECONDARY
                } == true
                val instrumentsPanel = when {
                    bottomInstrumentsPanelShownInPresentation(secondaryTweakerVisible, bottomUtilityCovering) -> CompanionInstrumentPanelKind.TWEAKER
                    else -> null
                }
                val currentRenderOperationId by renderOperation.collectAsState()
                val currentRenderCommitEpoch by renderCommitEpoch.collectAsState()
                val drawerSelected = recents.isVisibleOnPresentation(currentRenderOperationId)
                // QA D11: an overlay-hosted drawer over this window's own mirror covers the surface as well.
                val drawerCoversSurface = companionDrawerCoversPresentation(
                    drawer = recents,
                    renderOperationId = currentRenderOperationId,
                    disposition = companionRuntime.disposition,
                )
                val observedSecondaryLaunchId =
                    (recents.launchState as? CompanionDrawerLaunchState.LaunchPending)?.operationId?.toString()
                        ?: (companionRuntime.disposition as? SecondaryDisplayDisposition.LaunchPending)
                            ?.lease?.operationId?.toString()
                var secondaryLaunchTransitionId by remember { mutableStateOf<String?>(null) }
                var secondaryLaunchTransitionFromDrawer by remember { mutableStateOf(false) }
                var secondaryLaunchTransitionVisible by remember { mutableStateOf(false) }
                LaunchedEffect(observedSecondaryLaunchId, drawerSelected) {
                    if (observedSecondaryLaunchId != null) {
                        if (secondaryLaunchTransitionId != observedSecondaryLaunchId) {
                            secondaryLaunchTransitionId = observedSecondaryLaunchId
                            secondaryLaunchTransitionFromDrawer = drawerSelected
                        }
                        if (!drawerSelected && !secondaryLaunchTransitionFromDrawer) {
                            secondaryLaunchTransitionVisible = true
                        }
                        return@LaunchedEffect
                    }
                    val transitionId = secondaryLaunchTransitionId ?: return@LaunchedEffect
                    if (secondaryLaunchTransitionFromDrawer && !drawerSelected) {
                        // RC-43 (audit 2026-10-14, B-05): the launch glass starts AT the drawer's close, not a wall-clock OverlayMs after it. The scene
                        // glass under the drawer now holds through the drawer's own exit (RC-02), so the two ramps overlap and the lattice stays
                        // soft from the drawer's last frame to the app's first; the wait left it sharp for 180 ms between them (blur, sharp, blur).
                        secondaryLaunchTransitionVisible = true
                    } else {
                        if (!secondaryLaunchTransitionFromDrawer) {
                            // Perf §4.5 (P3-1, S01-1): leaving LaunchPending is HostCovered, the task-start
                            // acknowledgement; this window still draws above the app until the runtime parks it.
                            // Hold the glass until then, or until a failure or reclaim, bounded by the safety net.
                            val holdStartedAt = SystemClock.uptimeMillis()
                            withTimeoutOrNull(CompanionSecondaryLaunchGlassHoldMs) {
                                snapshotFlow {
                                    companionSecondaryLaunchGlass(
                                        transitionOperationId = transitionId,
                                        launchPending = false,
                                        runtime = companionRuntime,
                                        presentationCovered = presentationParkedBehindHost,
                                        msSinceHostCovered = SystemClock.uptimeMillis() - holdStartedAt,
                                    )
                                }.first { it != CompanionSecondaryLaunchGlass.SHOW }
                            }
                        }
                        secondaryLaunchTransitionVisible = false
                    }
                    delay(MimirMotion.OverlayMs.toLong())
                    if (secondaryLaunchTransitionId != transitionId) return@LaunchedEffect
                    secondaryLaunchTransitionVisible = false
                    delay(MimirMotion.OverlayMs.toLong())
                    if (secondaryLaunchTransitionId == transitionId) {
                        secondaryLaunchTransitionId = null
                        secondaryLaunchTransitionFromDrawer = false
                    }
                }
                // Perf §4.5: a failure or a reclaim takes the launch glass off with no unwind. Decided at composition
                // so the first frame after a reclaim rebind is already sharp: a new identity is a fresh progress at 0.
                val secondaryLaunchGlassCleared = secondaryLaunchTransitionId
                    ?.takeUnless { secondaryLaunchTransitionFromDrawer }
                    ?.let { transitionId ->
                        companionSecondaryLaunchGlass(
                            transitionOperationId = transitionId,
                            launchPending = observedSecondaryLaunchId != null,
                            runtime = companionRuntime,
                            presentationCovered = presentationParkedBehindHost,
                            msSinceHostCovered = 0L,
                        ) == CompanionSecondaryLaunchGlass.CLEAR
                    } == true
                val launchGlass = rememberMimirGlassProgress(
                    visible = secondaryLaunchTransitionVisible && !secondaryLaunchGlassCleared,
                    identity = if (secondaryLaunchGlassCleared) Unit else secondaryLaunchTransitionId ?: Unit,
                )
                val cursorId by CompanionOrderStore.cursor.collectAsState()
                val childCursors by CompanionOrderStore.childCursors.collectAsState()
                // D37: the page the lattice STARTS on, asked when it is mounted and never observed: the lattice writes every page a swipe turns up into this store, and
                // a read in composition here recomposed this surface (and the lattice under it) in the middle of every page drag.
                val restoredPageProvider = remember { { CompanionOrderStore.page.value } }
                val activitySwipeHintRetired by CompanionOrderStore.activitySwipeHintRetired.collectAsState()
                val quickSlots by QuickLaunchStore.slots.collectAsState()
                val quickLaunchApps by CompanionQuickLaunchCatalog.entries.collectAsState()
                // The catalog has always known whether its list is an ANSWER or just its starting value;
                // only the picker ever asked. The rail needs it too, or a pin the sweep has not reached
                // is indistinguishable from an empty seat -- which is what drew `+` on a live pin.
                val quickLaunchCatalogLoaded by CompanionQuickLaunchCatalog.hasLoaded.collectAsState()
                val gameOpeningPresentationScope = rememberCoroutineScope()
                    val pendingQuickLaunchChoice by CompanionQuickLaunchChoiceState.pending.collectAsState()
                    // O5: while the Bottom Quick launch window is up it draws the pending choice itself. Drawing it here
                    // too would stack a second dialog and scrim under that window.
                    val quickLaunchChoice = pendingQuickLaunchChoice.takeUnless { bottomQuickLaunchCovering }
                    // R6-P2-08: mirrors CompanionSurface's own open/close of the Home lattice's folder
                    // ("Tome") — one of several closable Bottom layers combined below into `closableLayerOpen`.
                    var folderOpen by remember { mutableStateOf(false) }
                    // R6-P2-08: a new, distinct value asks CompanionQuickLaunchLayer to close its picker,
                    // the same shape `CompanionBottomFolderState.closeRequest` already uses.
                    var closePickerRequest by remember { mutableStateOf<Long?>(null) }
                    // R6-P2-08 re-review finding B: the Tome's own one-shot close signal, decided once
                    // inside the `closeBookRequest` effect below rather than re-derived every recomposition.
                    var closeFolderRequestId by remember { mutableStateOf<Long?>(null) }
                    // R6-F-11: the platform shelf's launch card, reported via the existing `onCardChanged`
                    // (below) — one more closable Bottom layer folded into `closableLayerOpen`.
                    var shelfLaunchCardOpen by remember { mutableStateOf(false) }
                    // L2: the launch card opened from the Activity page (a tile or a ranked row). The SAME card the shelf draws
                    // (CompanionLaunchCardLayer), held here so EdgeBack and the page can close it. Null as soon as it starts closing.
                    var activityLaunchCardGame by remember { mutableStateOf<GameRow?>(null) }
                    // R6-F-11: the card's own one-shot close signal, same shape as `closeFolderRequestId`.
                    var closeCardRequestId by remember { mutableStateOf<Long?>(null) }
                    var companionSurfaceDragging by remember { mutableStateOf(false) }
                    var companionSurfaceDockExpanded by remember { mutableStateOf(false) }
                    val gameDisplayPreferences by CompanionGameDisplayPreferenceStore.preferences.collectAsState()
                    val gameDisplayChoice by CompanionGameDisplayChoiceState.pending.collectAsState()
                    // The launch card's look (slices L5 and L6): one global choice, read here so the shelf's card and the Activity page's card wear the same one, and the
                    // platform rows for the card's meta line and eyebrow (the Activity page opens the card without a platform of its own).
                    val launchCardLook by CompanionShelfGameOrderStore.look.collectAsState()
                    val launchCardPlatforms by remember(libraryRepository) {
                        libraryRepository?.observePlatforms() ?: flowOf(emptyList())
                    }.collectAsState(initial = emptyList())
                    val workspaceGame = companionActivityGame(sessionState.gameSession)
                    // RC-01 (Thor session-render regression, 2026-10-14): the session the takeover's PAGE is built from. The live session goes null on the
                    // frame the game ends, and the takeover's swap back to the resting layout starts on that same frame; its departing page must keep
                    // its pixels for the ReturnMs dissolve, so the page is built from the last live session once there is none
                    // ([rememberCompanionTakeoverPageGame]). What OPENS the takeover (`gameCardSessionKey`, `gameCardAvailableGameId`) still follows the live
                    // session, so it closes exactly as before: this only keeps the page that is leaving from recomposing to nothing, which cut the
                    // Now Playing surface in one frame and left the stage bare under a resting page not yet painted.
                    val takeoverPageGame = rememberCompanionTakeoverPageGame(workspaceGame)
                    val retainedNowPlaying = takeoverPageGame?.gameId?.let(CompanionState::retainedNowPlayingPayload)
                    // The Now Playing takeover's entry (ruling 2026-10-02): the Bottom glides to it ONCE per launch, when the game is PROVEN in front: its
                    // session reaches FOREGROUND_PROVEN, or the foreground monitor sees the game's own package come to the front (below). A later swipe
                    // to the Shelf sticks until the next launch, and the "Activity" pill returns to the resting layout. The glide's memory is process
                    // state, so a remount of this window does not repeat it.
                    LaunchedEffect(workspaceGame?.operation, workspaceGame?.sequence, workspaceGame?.phase) {
                        if (CompanionNowPlayingState.onSession(workspaceGame)) {
                            CompanionWorkspaceState.show(CompanionWorkspacePage.ACTIVITY)
                        }
                    }
                    // The existing foreground evidence for a Main game (which never reaches FOREGROUND_PROVEN): UsageStats says the game's package came to
                    // the front, at or after this launch's dispatch. Without Usage Access nothing is emitted and nothing glides; the Now playing tile
                    // stays the way in. The monitor replays its last observation, so one from before the dispatch is refused by its timestamp.
                    LaunchedEffect(Unit) {
                        CompanionProcessRuntime.services(context).foregroundMonitor.foreignForegroundObserved.collect { observation ->
                            if (CompanionNowPlayingState.onForeground(
                                    MimirSessionAuthority.state.value.gameSession,
                                    observation.packageName,
                                    observation.observedAtMs,
                                )
                            ) {
                                CompanionWorkspaceState.show(CompanionWorkspacePage.ACTIVITY)
                            }
                        }
                    }
                    // Leaving the Activity page (a swipe to the Shelf) closes the takeover, so coming back lands on the resting layout. Reads the
                    // page NOW, not the one this composition saw: the glide above may have moved it in the same frame.
                    LaunchedEffect(workspacePage) {
                        if (CompanionWorkspaceState.page.value != CompanionWorkspacePage.ACTIVITY) CompanionNowPlayingState.close()
                    }
                    val nowPlayingTakeover by CompanionNowPlayingState.state.collectAsState()
                    val musicPlayerOpen by CompanionMusicPlayerState.open.collectAsState()
                    // D27: the covers are ONE definition (companionWorkspaceCoverUp): the page takes no touch under them here, and a settle's landing re-reads them (above).
                    val workspaceNavigationBase = !companionWorkspaceCoverUp(
                        drawerSelected, bottomQuickLaunchCovering, secondaryTweakerVisible, quickPickerOpen, quickLaunchChoice != null, gameDisplayChoice != null,
                    ) && !companionSurfaceDragging && !companionSurfaceDockExpanded && !recents.launchLocked && !presentationParkedBehindHost
                    // D37: the strip is moving on its own clock and the scene under the finger moves with it: a touch waits the 180 ms. Asked as a function, read
                    // where each consumer composes, so the strip's release and its landing recompose those consumers and not this root (the base above is the rest).
                    val workspaceNavigationEnabled: () -> Boolean = { workspaceNavigationBase && !workspaceStrip.settling }
                    fun dispatchCompanionGame(gameId: com.gruning.mimir.rewrite.core.model.GameId, destination: CompanionQuickLaunchDestination) {
                        val bottomAvailable = CompanionProcessRuntime.services(context).taskRegistry.state.value.secondaryDisplayId != null
                        val effectiveDestination = if (destination == CompanionQuickLaunchDestination.BOTTOM && !bottomAvailable) {
                            showMimirMessage(context, "Bottom screen unavailable. Launching on Main.")
                            CompanionQuickLaunchDestination.MAIN
                        } else {
                            destination
                        }
                        // P10: neither the destination decision nor the fallback was logged (§1.15).
                        Log.i(
                            "MimirOpening",
                            "dispatch game=${gameId.value} requested=$destination effective=$effectiveDestination",
                        )
                        CompanionCommandState.launchGameOnMain(gameId.value, effectiveDestination)
                    }
                    // The launch card's PLAY, shared by the platform shelf and the Activity page: the same display preference, the same
                    // opening intro, the same chooser. (Moved out of the shelf call below unchanged.)
                    fun launchCompanionGameFromCard(game: GameRow) {
                        when (gameDisplayPreferences[game.gameId.value]) {
                            CompanionGameDisplayPreference.MAIN -> {
                                GameOpeningIntroState.markLaunching(game.gameId.value)
                                dispatchCompanionGame(game.gameId, CompanionQuickLaunchDestination.MAIN)
                            }
                            CompanionGameDisplayPreference.BOTTOM -> {
                                GameOpeningIntroState.markLaunching(game.gameId.value)
                                dispatchCompanionGame(game.gameId, CompanionQuickLaunchDestination.BOTTOM)
                            }
                            null -> {
                                if (CompanionProcessRuntime.services(context).taskRegistry.state.value.secondaryDisplayId == null) {
                                    GameOpeningIntroState.markLaunching(game.gameId.value)
                                    dispatchCompanionGame(game.gameId, CompanionQuickLaunchDestination.MAIN)
                                } else {
                                    GameOpeningIntroState.markLaunching(game.gameId.value)
                                    CompanionGameDisplayChoiceState.request(game.gameId, game.title, editing = false)
                                    Log.i("MimirOpening", gameDisplayChooserOpenedLogMessage(game.gameId.value, editing = false))
                                }
                            }
                        }
                    }
                    // The card opening or closing drives Main's game-opening intro (the shelf's `onCardChanged` body, moved here unchanged).
                    fun onLaunchCardChanged(game: GameRow?) {
                        gameOpeningPresentationScope.launch {
                            if (game != null) {
                                val introGame = gameOpeningIntroSources(context, game)
                                val enabled = gameOpeningIntroEnabled()
                                val plan = gameOpeningIntroPlan(
                                    introGame,
                                    MimirGamePlacement.MAIN,
                                    enabled,
                                )
                                if (plan != null) {
                                    presentGameOpening(context, plan)
                                } else {
                                    gameOpeningIntroSkipReason(introGame, MimirGamePlacement.MAIN, enabled)
                                        ?.let { reason ->
                                            Log.i(
                                                "MimirOpening",
                                                "skip game=${game.gameId.value} reason=$reason",
                                            )
                                        }
                                    dismissGameOpeningPresentation(this, "no-assets")
                                }
                            } else {
                                dismissGameOpeningPresentation(this, "card-closed")
                            }
                        }
                    }
                    fun launchCardDisplayChoice(game: GameRow): CompanionLaunchDisplayChoice =
                        when (gameDisplayPreferences[game.gameId.value]) {
                            CompanionGameDisplayPreference.MAIN -> CompanionLaunchDisplayChoice.MAIN
                            CompanionGameDisplayPreference.BOTTOM -> CompanionLaunchDisplayChoice.BOTTOM
                            null -> CompanionLaunchDisplayChoice.ASK
                        }
                    // A card opened from the Activity page reports to the intro on transitions only (the shelf's first-composition null
                    // is its own long-standing behaviour; this page adds no new one), and closes with the page. While a game is running the
                    // card does NOT drive Main's opening intro: the shelf is never reachable during play, so it never does, and the Activity
                    // page (which is up during play) must not start an intro over a running game.
                    var activityLaunchCardReported by remember { mutableStateOf(false) }
                    LaunchedEffect(activityLaunchCardGame) {
                        val card = activityLaunchCardGame
                        if (card != null) {
                            if (workspaceGame == null) {
                                activityLaunchCardReported = true
                                onLaunchCardChanged(card)
                            }
                        } else if (activityLaunchCardReported) {
                            activityLaunchCardReported = false
                            onLaunchCardChanged(null)
                        }
                    }
                    LaunchedEffect(workspacePage) {
                        if (workspacePage != CompanionWorkspacePage.ACTIVITY) activityLaunchCardGame = null
                    }
                val onPrimaryQuickLaunch: (String, Boolean) -> QuickLaunchPrimaryRouteResult = { packageName, fromGeneral ->
                    CompanionQuickLaunchPrimaryRoute.dispatch(packageName, fromGeneral)
                }
                val onSecondaryQuickLaunch: (String, String?) -> Boolean = { packageName, label ->
                    // Quick Launch closes the app's copy on Main first (BottomMainCopyClearance.kt).
                    val operationId = CompanionRecentsState.onLaunch(packageName, label, clearMainCopy = true)
                    companionQuickLaunchDispatchSucceeded(
                        operationId,
                        CompanionRecentsState.ui.value.launchState,
                    )
                }
                // Round 5 (5b): a tap's one toast is made from this Presentation's own context, which is on the Bottom display.
                val routeQuickLaunch: (String, String?, Boolean) -> Unit = { packageName, label, fromGeneral ->
                    routeSecondaryQuickLaunchTap(
                        packageName = packageName,
                        label = label,
                        fromGeneral = fromGeneral,
                        onPrimary = onPrimaryQuickLaunch,
                        onSecondary = onSecondaryQuickLaunch,
                        feedbackContext = { context },
                        unavailableCopy = "Launch unavailable. Try again.",
                    )
                }
                // The resting layout's library feeds (RC-36 residual, W1B #4; RC-01): held by the WINDOW, not by the scene, and collected only while the
                // window is live (W2L7 review #6: not parked behind a host, not stopped, screen on), each keeping its last value across a park. A scene mounted afresh (the
                // first pan onto the page, a remount) used to start them at null, draw nothing until the library answered, then pop whole at full alpha;
                // here they are already answered when the page is built, and a page the strip brings back finds them where it left them. The weekly
                // window still rolls at midnight (the ticker). Each delegate is read only where the page composes (inside `activitySceneContent`), so an
                // emission recomposes the page and never this root.
                val activityDays = remember { activityDayTicker({ System.currentTimeMillis() }, { ZoneId.systemDefault() }) }
                val activityDay by activityDays.collectAsStateWhileWindowLive(LocalDate.now(), windowLive)
                val activityWeek: List<com.gruning.mimir.rewrite.core.model.library.DailyLaunchCount>? by remember(libraryRepository, activityDays) {
                    activityWeekFlow(
                        launches = { libraryRepository?.observeLaunchesPerDay() ?: flowOf(emptyList()) },
                        days = activityDays,
                    )
                }.collectAsStateWhileWindowLive(null, windowLive)
                // Every game ever launched, most recent first: it ranks Most launched and (its first 15) feeds Been a while. Null until the library first
                // answers (once per window): the page then draws nothing, never "Nothing here yet".
                val activityLaunched: List<GameRow>? by remember(libraryRepository) {
                    libraryRepository?.observeRecentlyPlayed(Int.MAX_VALUE) ?: flowOf(emptyList())
                }.collectAsStateWhileWindowLive(null, windowLive)
                val activityGameCount: Int? by remember(libraryRepository) {
                    libraryRepository?.observeSummary()?.map { it.gameCount } ?: flowOf(null)
                }.collectAsStateWhileWindowLive(null, windowLive)
                // The Been a while pool's last answer, kept across the re-keys below (a game ending takes it out of `activityShown`): a new pool read starts
                // from it instead of null, so the tile does not stand empty for the frames the new query takes. The pick excludes the visible games itself.
                val lastActivityShelfPool = remember { arrayOfNulls<List<GameRow>>(1) }
                // The Activity page's scene, built always (B4): it is the shown scene on the Activity page and the neighbour the strip slides in beside
                // the lattice while a finger drags. It is composed only in those two cases.
                val activitySceneContent: @Composable () -> Unit = {
                        // Keyed on the takeover's page session (RC-01): the row stays with the page while it dissolves after the game ends.
                        val liveGameRow by remember(libraryRepository, takeoverPageGame?.gameId) {
                            takeoverPageGame?.gameId?.let { libraryRepository?.observeGame(it) }
                                ?: flowOf(null)
                        }.collectAsStateWhileLive(null)
                        // "Now" for the page's relative dates, taken at each entry and again when the day rolls.
                        val activityNow = remember(activityDay) { System.currentTimeMillis() }
                        // Only when no launched game is left for Been a while once the games the page already shows are taken out (the running
                        // or last played game and the ranked rows, under whichever sort the player picks): games from the shelf for it to draw
                        // from, never those same games. Never otherwise.
                        val activityShown = remember(activityLaunched, workspaceGame?.gameId) {
                            activityLaunched?.let { activityPossiblyVisibleGameIds(it, workspaceGame?.gameId) }.orEmpty()
                        }
                        val activityNeedsPool = activityLaunched?.let { activityNeedsShelfPool(it, activityShown) } == true
                        // With no launch history and no running game the top-left tile AND Been a while both draw on the pool, so it must hold two.
                        val activityPoolNeeded = if (activityLaunched.isNullOrEmpty() && workspaceGame == null) 2 else 1
                        // Null while a pool that is wanted has not answered (a new flow starts its state afresh): Been a while then draws nothing rather
                        // than "Nothing here yet" for the frames before the shelf game cuts in. A pool that is not wanted is empty at once.
                        val activityShelfPoolRead: List<GameRow>? by remember(libraryRepository, activityNeedsPool, activityDay, activityShown, activityPoolNeeded) {
                            if (activityNeedsPool && libraryRepository != null) {
                                activityShelfPoolFlow(libraryRepository, activityDay.toEpochDay(), activityShown, needed = activityPoolNeeded)
                            } else {
                                flowOf(emptyList())
                            }
                        }.collectAsStateWhileLive(lastActivityShelfPool[0])
                        if (activityShelfPoolRead != null) lastActivityShelfPool[0] = activityShelfPoolRead
                        val activityShelfPool: List<GameRow>? = if (activityNeedsPool && libraryRepository != null) activityShelfPoolRead else emptyList()
                        val activityBento = ActivityBentoData(
                            gameCount = activityGameCount,
                            launched = activityLaunched,
                            shelfPool = activityShelfPool,
                            week = activityWeek,
                            liveGameId = workspaceGame?.gameId,
                            liveTitle = workspaceGame?.title,
                            liveRow = liveGameRow,
                            nowMillis = activityNow,
                            zone = ZoneId.systemDefault(),
                        )
                        val achievementSnapshot by CompanionActivityAchievements.state.collectAsStateWhileLive()
                        // The hub's inputs, derived once per change by the lane's own holder (see CompanionActivityAchievementHubView), never per
                        // recomposition: this scope recomposes each time a swipe's settle flips, and used to filter, sort and sum every checked game then.
                        val achievementHub by companionActivityHubViewState.collectAsStateWhileLive()
                        val achievementAccount by RetroAchievementsCredentialStore.connectedUser.collectAsStateWhileLive()
                        val achievementHubEnabled = achievementHub.enabled
                        val achievementHubUnknownState = achievementHub.pageUnknownState()
                        val visibleAchievementSnapshots = achievementHub.snapshots
                        // Card art : a matched game's hero incl. its Customize-media override, else RA's own icon, else the theme. Resolved lazily
                        // per composed page, off the main thread; the icon is staged to local disk by the lane's keyless, gated fetch (no RA API call).
                        val achievementArtResolver = remember(context, libraryRepository) {
                            LibraryActivityAchievementArtResolver(
                                heroUriFor = { id ->
                                    val gameId = com.gruning.mimir.rewrite.core.model.GameId(id)
                                    CustomHeroStore.overrideUri(context, gameId)
                                        ?: libraryRepository?.observeGame(gameId)?.firstOrNull()?.heroArtUri
                                },
                                icons = CompanionActivityIcons.storeFor(context),
                            )
                        }
                        val achievementBadgeSource = remember(context) { StoreActivityBadgeSource(CompanionActivityIcons.storeFor(context)) }
                        // The page is wrapped by the launch card's layer: the card opens OVER it and its glass ramp blurs it. While a card is up
                        // the page itself takes no touch and no swipe (`navigationEnabled` false), so a drag on the scrim cannot leave the page.
                        CompanionLaunchCardLayer(
                            game = activityLaunchCardGame,
                            active = workspaceNavigationEnabled(),
                            displayChoiceFor = { game -> launchCardDisplayChoice(game) },
                            onDismiss = { activityLaunchCardGame = null },
                            onEditDisplay = { game ->
                                CompanionGameDisplayChoiceState.request(game.gameId, game.title, editing = true)
                                Log.i("MimirOpening", gameDisplayChooserOpenedLogMessage(game.gameId.value, editing = true))
                            },
                            onPlay = { game ->
                                activityLaunchCardGame = null
                                launchCompanionGameFromCard(game)
                            },
                            look = launchCardLook,
                            onLookChange = { next -> CompanionShelfGameOrderStore.setLook(context, next) },
                            platformLabelFor = { game -> companionLaunchCardPlatformLabel(game, launchCardPlatforms) },
                            journalFactsFor = { game -> rememberCompanionLaunchCardFacts(game) },
                        ) {
                        CompanionWorkspaceSurface(
                            page = CompanionWorkspacePage.ACTIVITY,
                            navigationEnabled = workspaceNavigationEnabled() && activityLaunchCardGame == null,
                            recentApp = mostRecentBottomApp,
                            game = workspaceGame,
                            media = mediaState,
                            onRecentApp = { entry -> CompanionRecentsState.onLaunch(entry.packageName, entry.label) },
                            onReturnToGame = onReturnToGame.takeIf { returnToGameAvailable && workspaceGame != null },
                            onMediaAccess = { CompanionMediaSessionBridge.openAccessSettings(context) },
                            onMediaAction = { action, sessionIdentity, trackIdentity ->
                                CompanionMediaSessionBridge.dispatch(action, sessionIdentity, trackIdentity)
                            },
                            onMediaSeek = { positionMs, sessionIdentity, trackIdentity ->
                                CompanionMediaSessionBridge.dispatchSeek(positionMs, sessionIdentity, trackIdentity)
                            },
                            achievementSnapshots = visibleAchievementSnapshots,
                            achievementHubAccessEnabled = achievementHubEnabled,
                            achievementTotalPoints = achievementHub.totalPoints,
                            achievementSoftcorePoints = achievementHub.softcorePoints,
                            achievementsChecking = achievementHub.checking,
                            achievementRecent = achievementHub.recent,
                            achievementArtResolver = achievementArtResolver,
                            achievementBadgeSource = achievementBadgeSource,
                            achievementHubUnknownState = achievementHubUnknownState,
                            bento = activityBento,
                            onOpenGame = { game -> activityLaunchCardGame = game },
                            // pad9: the accessible twin of the Activity swipe, through the same strip, the same settle and the same receipts.
                            onReturnToShelf = { workspaceStrip.returnToShelf(workspaceStripFlingPx) != null },
                            // The detail is keyed on the live SESSION and its library row (observeGame), as the design says, not on the
                            // retained NOW_PLAYING card: that can be absent for a whole session (R6-P1-03) and only adds the hero art and the
                            // platform label. Without it the detail is built from the session's own title, and Return to game stays gated
                            // on the card as before.
                            gameCardAvailableGameId = workspaceGame?.gameId,
                            gameCardSessionKey = workspaceGame?.let(::nowPlayingSessionKey),
                            takeoverKey = nowPlayingTakeover.openKey,
                            musicPlayerOpen = musicPlayerOpen,
                            onMusicPlayerOpenChange = { open -> CompanionMusicPlayerState.setOpen(open) },
                            // The window parks without parking this pane: the achievements hub's pad route follows the window's own parked and screen state.
                            windowLive = windowLive,
                            onTakeoverKeyChange = { key ->
                                if (key == null) CompanionNowPlayingState.close() else CompanionNowPlayingState.open(key)
                            },
                            gameCardContent = takeoverPageGame
                                ?.let { live ->
                                    { onBack, onOpenAchievements ->
                                        val retained = retainedNowPlaying?.takeIf { it.gameId == live.gameId }
                                        // The total: the elapsed store's sum of EARLIER sessions (the running one is added at its return), under the
                                        // canonical id the writer used. Collected only while the takeover is composed.
                                        val elapsedTotals by remember(context) { HomeExternalSessionElapsedStore.observe(context) }.collectAsStateWhileLive()
                                        // Seeded from the synchronous alias cache, so an aliased game's total is read under its canonical id on the first frame.
                                        val canonicalGameId by key(live.gameId) {
                                            produceState(com.gruning.mimir.rewrite.core.model.GameId(GameIdentityAliasCache.resolve(live.gameId.value))) {
                                                value = runCatching { GameIdentityAliasResolver.resolve(context, live.gameId) }.getOrDefault(value)
                                            }
                                        }
                                        val platformRows by remember(libraryRepository) {
                                            libraryRepository?.observePlatforms() ?: flowOf(emptyList())
                                        }.collectAsStateWhileLive(emptyList())
                                        // Read posture off Main once per session; runtime 1.7.3 lint misses the value setter below.
                                        @Suppress("ProduceStateDoesNotAssignValue")
                                        val closeAuthorityReady by produceState(false, live.operation, live.sequence, live.targetPackageName) {
                                            val pkg = live.targetPackageName
                                            value = pkg != null && withContext(Dispatchers.IO) {
                                                runCatching {
                                                    MimirLaunchTargetStopPolicy.isAllowed(pkg) &&
                                                        CompanionProcessRuntime.services(context).privilegedUtility.posture().status ==
                                                        PrivilegedUtilityStatus.READY
                                                }.getOrDefault(false)
                                            }
                                        }
                                        val quit = if (nowPlayingQuitOffered(live, closeAuthorityReady)) {
                                            NowPlayingQuit {
                                                nowPlayingQuitPerform(live, nowPlayingQuitPorts(context)).also { outcome ->
                                                    Log.i("MimirSession", "now playing quit game=${live.gameId.value} placement=${live.placement} outcome=$outcome")
                                                }
                                            }
                                        } else {
                                            null
                                        }
                                        CompanionActivityGameDetail(
                                            game = live,
                                            payload = retained ?: com.gruning.mimir.rewrite.core.model.CompanionPayload(
                                                mode = com.gruning.mimir.rewrite.core.model.CompanionMode.NOW_PLAYING,
                                                gameId = live.gameId,
                                                title = live.title.takeIf { it.isNotBlank() },
                                                platformId = live.platformId,
                                            ),
                                            row = liveGameRow,
                                            achievements = companionActivityAchievementFor(
                                                achievementSnapshot, live.gameId.value, achievementAccount,
                                                achievementHubEnabled,
                                            ),
                                            totalBeforeMs = elapsedTotals[HomeShelfItemId.Emulated(canonicalGameId)],
                                            platformLabel = platformLabelForCompanion(live.platformId, platformRows),
                                            navigationEnabled = workspaceNavigationEnabled(),
                                            onBack = onBack,
                                            onOpenAchievements = onOpenAchievements,
                                            onReturnToGame = onReturnToGame.takeIf { returnToGameAvailable },
                                            quit = quit,
                                        )
                                    }
                                },
                            onWorkspaceDrag = { delta -> workspaceStrip.drag(CompanionWorkspacePage.ACTIVITY, delta) },
                            onWorkspaceDragEnd = { velocity -> workspaceStrip.release(velocity, workspaceStripFlingPx) != null },
                            onWorkspaceDragCancel = { workspaceStrip.cancel() },
                        )
                        }
                }
                val workspaceActivityScene: (@Composable () -> Unit)? = activitySceneContent.takeIf { workspacePage == CompanionWorkspacePage.ACTIVITY }
                // A newly mounted display gets one bounded intro. Same-display reclaims re-token this window,
                // so they keep the existing surface and use the content transition instead.
                val enterAlpha = remember { Animatable(0f) }
                // The mount cover (policy 6): worn from this window's first frame, over everything, when this window follows the floor's cold-start
                // cover OR is a fresh window for a reclaim (`reclaimHandoff`: it never parks, so the reveal cover below cannot serve it, and its first frame
                // follows the restore guard's ground the way a cold start's follows the floor's), and the system animates (reduced motion cuts: no cover,
                // as before). Dropped below once the intro has run, so it fades out on `OverlayMs` over a lattice that is already fully in. A cold start's
                // writes no receipt (the cold-start exit is the first frame's, in the host); a fresh reclaim window's is receipted as `reclaim-cover.fade`.
                val animatorsOff = mimirReducedMotion()
                val coldCoverArmed = remember { (coldStartHandoff || reclaimHandoff) && !animatorsOff }
                var coldCoverHeld by remember { mutableStateOf(coldCoverArmed) }
                // The cover has FINISHED leaving (its 180 ms exit fade reached alpha 0), which is later than `coldCoverHeld` going false: that only starts the fade.
                // The ring hold waits on this, so the sweep cannot begin under a half-faded badge. True from the start when no cover is worn.
                var coldCoverLeft by remember { mutableStateOf(!coldCoverArmed) }
                LaunchedEffect(coldCoverLeft) {
                    if (coldCoverLeft && coldCoverArmed && reclaimHandoff) BottomUiReceipts.log.transitionEnd(CompanionReclaimCoverFade, receiptDisplayId, "done")
                }
                // The cursor ring's hold (see CompanionSurface.latticeArmed): this window's intro has run and, on a cold start, the badge cover has left. One stable lambda over
                // snapshot state, so the surface's tiles are not recomposed when this scope is; read where the gleam waits, never in composition.
                val latticeArmed: () -> Boolean = remember { { enterAlpha.value >= 1f && coldCoverLeft } }
                val presentationFrameToken = CompanionPresentationFrameToken(
                    operationId = currentRenderOperationId,
                    payloadRevision = renderPayload.revision,
                    sceneRevision = scene.revision,
                    commitEpoch = currentRenderCommitEpoch,
                )
                val latestPresentationFrameToken = rememberUpdatedState(presentationFrameToken)
                var introFrameCommittedToken by remember {
                    mutableStateOf<CompanionPresentationFrameToken?>(null)
                }
                LaunchedEffect(Unit) {
                    val contentWaitStartedAt = SystemClock.uptimeMillis()
                    withTimeoutOrNull(CompanionPresentationIntroContentWaitMs) {
                        snapshotFlow {
                            val contentPayload = effectiveCompanionPayload(
                                renderPayloadState.value.payload,
                                sceneState.value,
                            )
                            val hasContent = companionPresentationIntroMayStart(
                                hasContent = contentPayload.homeLanes.isNotEmpty() ||
                                    contentPayload.shelfRows.isNotEmpty() ||
                                    !contentPayload.title.isNullOrBlank() ||
                                    !contentPayload.heroArtRef.isNullOrBlank() ||
                                    contentPayload.runningApp != null,
                                alreadySettled = false,
                                waitedMs = SystemClock.uptimeMillis() - contentWaitStartedAt,
                            )
                            companionPresentationFadeMayStart(
                                hasContent = hasContent,
                                frameCommitted = introFrameCommittedToken == latestPresentationFrameToken.value,
                                alreadySettled = enterAlpha.value >= 1f,
                            )
                        }.first { mayStart -> mayStart }
                    }
                    if (enterAlpha.value < 1f) {
                        bottomUiTransition("presentation.intro", receiptDisplayId) {
                            enterAlpha.animateTo(
                                1f,
                                animationSpec = tween(MimirMotion.ModeTransitionMs, easing = MimirMotion.ModeTransitionEasing),
                            )
                        }
                    }
                    // The intro has run, or the content wait timed out: the lattice is in, so the cover goes. Never held on the content wait alone.
                    if (coldCoverArmed && reclaimHandoff) BottomUiReceipts.log.transitionBegin(CompanionReclaimCoverFade, receiptDisplayId)
                    coldCoverHeld = false
                }
                LaunchedEffect(presentationFrameToken) {
                    val view = composeView ?: return@LaunchedEffect
                    armFrameCommit(
                        view = view,
                        handshake = presentationFrameCommitHandshake,
                        token = presentationFrameToken,
                        onCommitted = { committed ->
                            if (latestPresentationFrameToken.value == committed) {
                                introFrameCommittedToken = committed
                            }
                            onFirstFrameCommitted(committed.operationId)
                        },
                    )
                    view.invalidate()
                }
                CompositionLocalProvider(
                    LocalMimirKeyboardHapticFeedback provides secondaryHaptics::navigationTick,
                    LocalBottomUiDisplay provides receiptDisplayId,
                ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        // R6-P1-10: the Compose replacement for the native CP03 strip's folder/layer
                        // admission over Mimir's OWN mirror — see companionOwnMirrorFolderEdgeBackEnabled
                        // and detectCompanionMirrorEdgeBackSwipe. Observed at PointerEventPass.Final so a
                        // tile tap or the grid's own scroll/drag (consumed earlier, in Main) always wins;
                        // only an otherwise-unclaimed edge swipe reaches here.
                        .pointerInput(Unit) {
                            val edgeDistancePx = CompanionMirrorEdgeBackBandDp.dp.toPx()
                            val triggerDistancePx = CompanionMirrorEdgeBackTriggerDp.dp.toPx()
                            detectCompanionMirrorEdgeBackSwipe(
                                edgeDistancePx = edgeDistancePx,
                                triggerDistancePx = triggerDistancePx,
                                canStart = { CompanionBottomFolderState.open.value },
                                onBack = { CompanionBottomFolderState.requestClose() },
                            )
                        },
                ) {
                // The glass warm-up (RC-59, B-03): this window composes it as Main's host does (MimirAppHost), so a Bottom-only restart does not
                // blur cold; running it once per PROCESS is MimirGlassWarmup's own (lane glassx, MimirGlassTransition.kt), not a flag here.
                MimirGlassWarmup()
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = enterAlpha.value },
                ) {
                    // **Screen 2's own route** — policy 2026-09-01, see [CompanionLibraryRoute]. A
                    // console tile no longer drives screen 1; it opens that platform's shelf HERE. The
                    // lattice is still the root, and this branch is the only thing standing between them.
                    val libraryDestination by CompanionLibraryRoute.destination.collectAsState()
                    val libraryRepository = CompanionLibraryRoute.repository
                    val filedGameOrder by CompanionShelfGameOrderStore.order.collectAsState()
                    val shelfDensity by CompanionShelfGameOrderStore.density.collectAsState()
                    // **The route SURVIVES a play session** — policy 2026-09-01, asked in prompt.
                    // It used to reset to the lattice whenever the mode left HOME, which was written before
                    // launching from screen 2 existed; now that you launch from here, resetting would throw
                    // away the shelf you launched from every single time. A running game still takes screen
                    // 2 wholesale — it just finds its place again when the game exits. Round-5 triage 4a: an
                    // app on Main and a reclaim's hold no longer do (see companionPlatformShelfDestination).
                    val platformDestination = companionPlatformShelfDestination(
                        route = libraryDestination,
                        mode = payload.mode,
                        reclaimHold = CompanionState.isReclaimHold(payload),
                    )
                    // **The shelf ARRIVES and LEAVES** —  It used to be a bare `if` with a `return@Box`: the
                    // platform shelf replaced the lattice between two frames in both directions, which
                    // reads as the panel being swapped rather than as going somewhere and coming back.
                    //
                    // The lattice stays composed underneath the covering shelf, and `shelfLeaving` keeps the
                    // OUTGOING platform alive while it dissolves. Without it, backing out would drop the
                    // shelf's content on the first frame and animate an empty rectangle away.
                    var shelfLeaving by remember { mutableStateOf<CompanionLibraryRoute.Destination.Platform?>(null) }
                    var surfaceHasFrame by remember { mutableStateOf(false) }
                    var shelfHasFrame by remember { mutableStateOf(false) }
                    val traceShelfExit = remember {
                        BuildConfig.DEBUG && Log.isLoggable(CompanionShelfExitTag, Log.DEBUG)
                    }
                    // Round-5 triage 4a, item 2: an entry that returns to the platform last shown, with no route
                    // write since, snaps instead of ramping. The decision's memory outlives this window (see
                    // CompanionShelfEntryMemory) and is written only from SideEffect, so a composition that never
                    // commits cannot move it.
                    val shelfEntryHistory = remember { companionShelfEntryHistory() }
                    val shelfEntrySnaps = shelfEntryHistory.snaps(libraryDestination, platformDestination?.platformIdValue)
                    SideEffect {
                        shelfEntryHistory.commit(libraryDestination, platformDestination?.platformIdValue, shelfEntrySnaps)
                    }
                    LaunchedEffect(platformDestination) {
                        if (platformDestination != null) {
                            // A shelf still composed for this platform (a mode round trip inside its exit) already
                            // reported its one frame (`onContentFrame` fires once per shelf); resetting would hold it
                            // invisible until the 400 ms fallback.
                            if (shelfLeaving?.platformIdValue != platformDestination.platformIdValue) shelfHasFrame = false
                            shelfLeaving = platformDestination
                        } else if (companionShelfExitSkipsAnimation(exitPending = shelfLeaving != null, shelfHasFrame = shelfHasFrame)) {
                            // R6-P1-08 / F-C4a: this shelf's host was never admitted, so it never reported a
                            // frame and the enter target never left 0 — the exit target computes to that same
                            // 0, `animateFloatAsState` runs no animation, and `finishedListener` (the only other
                            // place that clears `shelfLeaving`) never fires. Clear immediately instead of
                            // stranding a remnant that eats input over the lattice.
                            shelfLeaving = null
                            shelfHasFrame = false
                        }
                    }
                    val exitPending = platformDestination == null && shelfLeaving != null
                    val shelfEnterTarget = if (platformDestination != null) {
                        companionShelfEnterTarget(shelfHasFrame)
                    } else if (exitPending) {
                        companionShelfExitTarget(
                            platformOpen = false,
                            exitSurfaceReady = surfaceHasFrame,
                        )
                    } else {
                        0f
                    }
                    // B1: the platform shelf arrives on the page tier's overlay clock (the 130 ms lead at alpha 0, then 540 ms: about 670 ms from the
                    // moment it may show, which is `shelfHasFrame`) and returns to the lattice on the quick return clock ([MimirPageMotion.ReturnMs],
                    // 160 ms), the lattice standing at rest under it. A shelf asked for again while it is still leaving carries on from where it stands on
                    // the return clock, with no lead (a lead there would freeze it part-visible). The settle gating, `shelfHasFrame` and its fallback,
                    // and `shelfEntrySnaps` are untouched: only the specs changed.
                    val shelfReducedMotion = remember(shelfEnterTarget) { bottomUiReducedMotion() }
                    val shelfAtRest = remember { booleanArrayOf(true) }
                    val shelfEnterAnimated by animateFloatAsState(
                        targetValue = shelfEnterTarget,
                        animationSpec = when {
                            shelfEntrySnaps -> snap()
                            shelfEnterTarget >= 1f && shelfAtRest[0] -> mimirOverlayArriveSpec(shelfReducedMotion)
                            else -> tween(MimirPageMotion.ReturnMs, easing = MimirMotion.Easing)
                        },
                        label = "companion-shelf-enter",
                        finishedListener = { settled ->
                            if (BuildConfig.DEBUG) {
                                Log.d(
                                    CompanionShelfDiagnosticTag,
                                    "shelfEnter finished settled=$settled destination=${platformDestination?.platformIdValue ?: "none"} " +
                                        "entry=${if (shelfEntrySnaps) "snap" else "ramp"}",
                                )
                            }
                            if (settled == 0f) {
                                shelfLeaving = null
                                // The shelf leaves composition with that write; a later one reports its own first frame.
                                shelfHasFrame = false
                            }
                        },
                    )
                    SideEffect { shelfAtRest[0] = shelfEnterAnimated <= 0.001f }
                    // Read in draw and in the derived flags below, never in this composition: as a value it recomposed this root on every frame
                    // of the shelf's rise and fall (audit 2026-10-14, B-35).
                    val shelfEnter: () -> Float = { companionShelfEnterValue(shelfEntrySnaps, shelfEnterTarget, shelfEnterAnimated) }
                    // RC-04 (B-35): the lattice's blur and scrim under the platform shelf have their own clock, split from `shelfEnter` (which still
                    // carries the shelf's own alpha and rise on the page tier). Soften first: the glass opens on OverlayMs from the tap, before the
                    // shelf's lead; on close it holds through the shelf's ReturnMs dissolve and clears only after it (rememberMimirGlassHold), so the
                    // lattice never sharpens under a shelf still leaving. A re-entry that snaps (shelfEntrySnaps) finds the glass up at once.
                    val latticeGlass = rememberMimirHeldGlassProgress(
                        visible = platformDestination != null && workspacePage == CompanionWorkspacePage.SHELF,
                        settleOpen = { shelfEntrySnaps },
                    )
                    val latticeGlassUp by remember { derivedStateOf { latticeGlass() > 0.001f } }
                    val shelfTouchShielded by remember(platformDestination, shelfEntrySnaps, shelfEnterTarget) {
                        derivedStateOf { companionShelfTouchShielded(platformDestination != null, shelfEnter()) }
                    }
                    // Receipts: the platform shelf is a surface over the lattice. Its enter/exit ramp is the one named
                    // transition; a re-entry that snaps by design (shelfEntrySnaps) names none and says transition=none.
                    BottomUiSurfaceReceipt(
                        surface = BottomSurface.PLATFORM_SHELF,
                        visible = platformDestination != null && workspacePage == CompanionWorkspacePage.SHELF,
                        transition = when {
                            platformDestination == null -> "platform-shelf.exit"
                            shelfEntrySnaps -> null
                            else -> "platform-shelf.enter"
                        },
                        display = receiptDisplayId,
                    )
                    BottomUiFloatTransitionReceipt(
                        name = if (platformDestination != null) "platform-shelf.enter" else "platform-shelf.exit",
                        target = shelfEnterTarget,
                        value = { shelfEnterAnimated },
                        display = receiptDisplayId,
                    )
                    val shelfCovers by remember(workspacePage, platformDestination, shelfEntrySnaps, shelfEnterTarget) {
                        derivedStateOf { workspacePage == CompanionWorkspacePage.SHELF && platformDestination != null && shelfEnter() >= 0.999f }
                    }
                    val shelfCoversState = rememberUpdatedState(shelfCovers)
                    // The picker belongs to CompanionSurface. Clear stale host z-order when a
                    // platform shelf opens over the retained surface.
                    LaunchedEffect(platformDestination) {
                        if (platformDestination != null) quickPickerOpen = false
                    }
                    // A platform shelf opening over a held swipe (or a settle on its way to Activity) takes the strip back: it settles to where it began, writes no
                    // receipt, and the page it would have committed to does not hide the shelf.
                    LaunchedEffect(platformDestination != null) {
                        if (platformDestination != null) workspaceStrip.abandon()
                    }
                    val shelfShown = platformDestination ?: shelfLeaving
                    val shelfZ = if (shelfLeaving != null && platformDestination == null) {
                        CompanionPlatformShelfRemnantLayerZ
                    } else {
                        CompanionPlatformShelfLayerZ
                    }
                    if (traceShelfExit) {
                        LaunchedEffect(platformDestination, shelfLeaving) {
                            if (platformDestination != null || shelfLeaving == null) return@LaunchedEffect
                            val startedAt = SystemClock.uptimeMillis()
                            coroutineScope {
                                launch {
                                    snapshotFlow { shelfEnterAnimated }.collect { value ->
                                        Log.d(
                                            CompanionShelfExitTag,
                                            "exit shelfEnter=${"%.3f".format(value)} " +
                                                "dt=${SystemClock.uptimeMillis() - startedAt}ms " +
                                                "surfacePresent=true " +
                                                "platformDestination=${platformDestination?.platformIdValue} " +
                                                "remnantZ=$shelfZ",
                                        )
                                    }
                                }
                                launch {
                                    while (SystemClock.uptimeMillis() - startedAt < 300L) {
                                        withFrameNanos {
                                            Log.d(
                                                CompanionShelfExitTag,
                                                "frame dt=${SystemClock.uptimeMillis() - startedAt}ms",
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (BuildConfig.DEBUG) {
                        // QA D5: these keys include the scene revision and the mode, so a MainActivity resume re-prints
                        // this line with the route untouched. The line says whether the shown destination moved and what
                        // moved it (route / mode / shown / scene).
                        val lastShelfDiagnostic = remember {
                            java.util.concurrent.atomic.AtomicReference<CompanionShelfDiagnosticSnapshot?>(null)
                        }
                        LaunchedEffect(platformDestination, shelfShown, scene.revision, payload.mode) {
                            val current = CompanionShelfDiagnosticSnapshot(
                                route = (libraryDestination as? CompanionLibraryRoute.Destination.Platform)?.platformIdValue,
                                destination = platformDestination?.platformIdValue,
                                shown = shelfShown?.platformIdValue,
                                sceneRevision = scene.revision,
                                sceneRoute = scene.route.destination,
                                mode = payload.mode.toString(),
                            )
                            Log.d(
                                CompanionShelfDiagnosticTag,
                                companionShelfDiagnosticMessage(lastShelfDiagnostic.getAndSet(current), current),
                            )
                        }
                    }
                    if (workspacePage == CompanionWorkspacePage.SHELF && shelfShown != null && libraryRepository != null) {
                        // **Its own MimirTheme.** This is a SIBLING of CompanionSurface, and that surface
                        // provides `LocalMimirPalette` inside itself — so anything standing beside it
                        // must carry its own theme, exactly as the instruments panel and the recents
                        // drawer below already do. Found on the Thor, not in review: without this the
                        // first console tap took screen 2 down with
                        // `IllegalStateException: LocalMimirPalette not provided`.
                        Box(
                            Modifier
                                .fillMaxSize()
                                // Declared BEFORE the lattice in this Box but drawn above it: the shelf
                                // is the thing arriving, and reordering the declarations would move a
                                // hundred lines to say the same thing.
                                // The platform shelf is the base mapped panel. Instruments, an open
                                // picker, and the recents drawer each own a higher explicit layer.
                                .zIndex(shelfZ)
                                .graphicsLayer {
                                    alpha = shelfEnter()
                                    // A short rise, not a slide: this panel is 468dp tall and a full
                                    // traversal at that height reads as a page turn rather than as a
                                    // shelf opening in place.
                                    translationY = (1f - shelfEnter()) * size.height * 0.04f
                                }
                                // B1 fix round: through the 130 ms lead (and the wait for the shelf's first frame) the shelf stands at alpha 0 while
                                // the lattice under it is still on screen. This shield takes the touch over the WHOLE shelf layer until the shelf has
                                // painted: neither an invisible game tile nor the visible lattice beneath can be tapped into a game card nobody has seen.
                                // Touch only; the pad is never held. Gone from the shelf's first painted frame, and it is not applied while it leaves.
                                .then(if (shelfTouchShielded) Modifier.mimirPageTouchShield() else Modifier),
                        ) {
                        if (traceShelfExit) {
                            val lastLoggedShelfZ = remember { mutableStateOf<Float?>(null) }
                            SideEffect {
                                if (lastLoggedShelfZ.value != shelfZ) {
                                    lastLoggedShelfZ.value = shelfZ
                                    Log.d(
                                        CompanionShelfExitTag,
                                        "remnant zIndex=$shelfZ platform=${shelfShown.platformIdValue}",
                                    )
                                }
                            }
                            DisposableEffect(shelfShown.platformIdValue) {
                                Log.d(
                                    CompanionShelfExitTag,
                                    "remnant mounted platform=${shelfShown.platformIdValue}",
                                )
                                onDispose {
                                    Log.d(
                                        CompanionShelfExitTag,
                                        "remnant disposed platform=${shelfShown.platformIdValue}",
                                    )
                                }
                            }
                        }
                        // R6-F-11 review: this whole shelf can unmount at once — any switch of
                        // `workspacePage` away from SHELF while a platform shelf is admitted (the ordinary
                        // `onWorkspaceSwipeRight` gesture to ACTIVITY) drops this `if` block's admission
                        // with no closing frame for the card to report itself through.
                        // R6-P1-10: a reclaim from a foreign app is NOT this case any more — it now returns
                        // to whichever page was showing at hand-off (MainActivity's `!controllerActive`
                        // branch), so it no longer forces a switch straight to ACTIVITY on its own. The
                        // ordinary workspace swipe above is still live, though, so this guard stays needed:
                        // without it, `shelfLaunchCardOpen` could be left stuck `true` by that swipe with
                        // nothing left to close, arming the strip over an idle lattice. Clears only the
                        // flag — `onCardChanged(null)` is NOT called here, since the game-opening intro's
                        // own "card-closed" dismiss already runs off `active` going false
                        // (CompanionPlatformShelf's own `LaunchedEffect(active)`) and must not run twice.
                        DisposableEffect(Unit) {
                            onDispose { shelfLaunchCardOpen = false }
                        }
                        MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                            CompanionPlatformShelf(
                                platformIdValue = shelfShown.platformIdValue,
                                label = shelfShown.label,
                                repository = libraryRepository,
                                // The SAME switch the primary's own tiles read, so a game does not wear
                                // one face on screen 1 and another on screen 2.
                                // Arrange-and-drop . Screen 2 files its own
                                // order, exactly as it already does for the lattice — the primary's
                                // gamelist is not rewritten from a drag on the other panel.
                                filedOrder = filedGameOrder[shelfShown.platformIdValue].orEmpty(),
                                // S2-07: this call site is shared by the current route AND its outgoing
                                // fade (`shelfShown = platformDestination ?: shelfLeaving`). `active`
                                // below revokes touch, but the final write also re-reads the route
                                // SYNCHRONOUSLY off `CompanionLibraryRoute.destination.value` rather than
                                // trusting the composed `platformDestination` closure, which is a
                                // snapshot read that can still be one recomposition behind a Back that
                                // landed the same frame as an in-flight gesture's completion.
                                onArranged = { ids ->
                                    // Round-5 triage 4a: the composed list's own admission, read live.
                                    if (companionLivePlatformShelfDestination()?.platformIdValue == shelfShown.platformIdValue) {
                                        CompanionShelfGameOrderStore.setOrder(
                                            context,
                                            shelfShown.platformIdValue,
                                            ids,
                                        )
                                    }
                                },
                                // Same synchronous re-read as `onArranged`/`onLaunch` below: `shelfShown`
                                // can still be the OUTGOING remnant (`shelfLeaving`) composed for its own
                                // fade after a newer platform is already current, and a stale Back reaching
                                // this closure must not dismiss the platform the player is actually on.
                                onBack = {
                                    val current = CompanionLibraryRoute.destination.value as?
                                        CompanionLibraryRoute.Destination.Platform
                                    val liveMode = effectiveCompanionPayload(
                                        CompanionState.payload.value,
                                        CompanionSceneState.scene.value,
                                    ).mode
                                    // Round-5 triage 4a: the composed list's own admission, read live.
                                    val accepted = companionLivePlatformShelfDestination()?.platformIdValue ==
                                        shelfShown.platformIdValue
                                    if (BuildConfig.DEBUG) {
                                        Log.d(
                                            CompanionShelfDiagnosticTag,
                                            "onBack sourcePlatform=${shelfShown.platformIdValue} " +
                                                "currentPlatform=${current?.platformIdValue ?: "none"} " +
                                                "effectiveMode=$liveMode accepted=$accepted",
                                        )
                                    }
                                    if (accepted) {
                                        CompanionLibraryRoute.backToLattice()
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                CompanionShelfDiagnosticTag,
                                                "routeAfter=${CompanionLibraryRoute.destination.value}",
                                            )
                                        }
                                    }
                                },
                                // The shelf raises its own card and calls this on PLAY. The primary
                                // re-reads the game from its own repository before launching — the bus is
                                // process-scoped and an id can outlive the shelf that minted it.
                                onLaunch = { game ->
                                    // Round-5 triage 4a: the composed list's own admission, read live.
                                    if (companionLivePlatformShelfDestination()?.platformIdValue == shelfShown.platformIdValue) {
                                        launchCompanionGameFromCard(game)
                                    }
                                },
                                onCardChanged = { game ->
                                    // R6-F-11: this callback already existed for the game-opening intro
                                    // below; folding the card's own open/closed into `closableLayerOpen`
                                    // here reuses it rather than adding a second report of the same fact.
                                    shelfLaunchCardOpen = game != null
                                    onLaunchCardChanged(game)
                                },
                                onEditDisplay = { game ->
                                    CompanionGameDisplayChoiceState.request(game.gameId, game.title, editing = true)
                                    Log.i("MimirOpening", gameDisplayChooserOpenedLogMessage(game.gameId.value, editing = true))
                                },
                                onContentFrame = { shelfHasFrame = true },
                                displayChoiceFor = { game -> launchCardDisplayChoice(game) },
                                active = platformDestination != null,
                                // R6-F-11: closes the card when it's the innermost open layer, the same
                                // native-triggered shape as `closeBookRequest`/`closePickerRequest`.
                                closeCardRequest = closeCardRequestId,
                                // The density step is one global setting, read and written here beside the filed order (slice L4).
                                density = shelfDensity,
                                onDensityChange = { step -> CompanionShelfGameOrderStore.setDensity(context, step) },
                                // The launch card's look is one global setting too, persisted beside the density (slices L5 and L6).
                                look = launchCardLook,
                                onLookChange = { next -> CompanionShelfGameOrderStore.setLook(context, next) },
                                journalFactsFor = { game -> rememberCompanionLaunchCardFacts(game) },
                                modifier = Modifier.fillMaxSize(),
                            )
                            LaunchedEffect(platformDestination) {
                                if (platformDestination == null) return@LaunchedEffect
                                delay(CompanionShelfFirstFrameFallbackMs)
                                shelfHasFrame = true
                            }
                        }
                        }
                    }
                    BottomUiSurfaceReceipt(
                        surface = BottomSurface.DISPLAY_CHOOSER,
                        visible = gameDisplayChoice != null,
                        transition = if (gameDisplayChoice != null) "display-chooser.enter" else "display-chooser.exit",
                        display = receiptDisplayId,
                    )
                    // B1: the "Opens on" chooser comes in over nothing on the swap tier (the 130 ms lead at alpha 0, then 180 ms: about 310 ms from
                    // the press) and leaves on OverlayMs (180 ms), as Main's tile menu pages do. The Bottom is touch-only, so the page gate passes
                    // holdPad = false: it keeps only its touch half (an arriving page takes no touch until it paints, a leaving one none) and never
                    // holds the process-wide pad gate.
                    val chooserReducedMotion = bottomUiReducedMotion()
                    AnimatedContent(
                        targetState = gameDisplayChoice,
                        transitionSpec = { mimirSwapOverlayTransform(chooserReducedMotion, arriving = targetState != null) },
                        label = "companion-game-display-choice-transition",
                        modifier = Modifier.zIndex(CompanionGameDisplayChoiceLayerZ),
                    ) { presentedChoice ->
                        if (presentedChoice == gameDisplayChoice) {
                            BottomUiTransitionProbe(
                                if (gameDisplayChoice != null) "display-chooser.enter" else "display-chooser.exit",
                                transition,
                                receiptDisplayId,
                            )
                        }
                        presentedChoice?.let { choice ->
                            val isLive = choice == gameDisplayChoice
                            val chooserFade = mimirSwapFadeAlpha(chooserReducedMotion)
                            val chooserInput = MimirSwapPageGate(chooserFade, holdPad = false)
                            Box(
                                Modifier.fillMaxSize().then(chooserInput),
                            ) {
                                MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                                    CompanionGameDisplayChoiceDialog(
                                        choice = choice,
                                        enabled = isLive,
                                        // A lit capsule's gleam and flare wait for the dialog's own arrival.
                                        armed = { chooserFade.value >= 1f },
                                        onSelect = { destination ->
                                            if (isLive && CompanionGameDisplayChoiceState.dismiss(choice)) {
                                                BottomUiReceipts.log.control(
                                                    "display-chooser." + destination.name.lowercase(),
                                                    receiptDisplayId,
                                                    BottomSurface.DISPLAY_CHOOSER,
                                                    BottomUiReceiptFormat.opaque("game", choice.gameId.value),
                                                )
                                                val previousPreference = CompanionGameDisplayPreferenceStore.preference(choice.gameId)
                                                CompanionGameDisplayPreferenceStore.set(context, choice.gameId, destination)
                                                Log.i(
                                                    "MimirOpening",
                                                    if (choice.editing) {
                                                        gameDisplayPreferenceEditedLogMessage(choice.gameId.value, previousPreference, destination)
                                                    } else {
                                                        gameDisplayChooserChosenLogMessage(choice.gameId.value, destination)
                                                    },
                                                )
                                                if (!choice.editing && destination != CompanionQuickLaunchDestination.ASK_NEXT_TIME) {
                                                    dispatchCompanionGame(choice.gameId, destination)
                                                } else if (!choice.editing) {
                                                    GameOpeningIntroState.clearLaunching(choice.gameId.value)
                                                    dismissGameOpeningPresentation(
                                                        gameOpeningPresentationScope,
                                                        "chooser-cancelled",
                                                    )
                                                }
                                            }
                                        },
                                        onDismiss = {
                                            if (isLive && CompanionGameDisplayChoiceState.dismiss(choice)) {
                                                BottomUiReceipts.log.control(
                                                    "display-chooser.cancel",
                                                    receiptDisplayId,
                                                    BottomSurface.DISPLAY_CHOOSER,
                                                    BottomUiReceiptFormat.opaque("game", choice.gameId.value),
                                                )
                                                Log.i("MimirOpening", gameDisplayChooserCancelledLogMessage(choice.gameId.value, choice.editing))
                                                if (!choice.editing) {
                                                    GameOpeningIntroState.clearLaunching(choice.gameId.value)
                                                    dismissGameOpeningPresentation(
                                                        gameOpeningPresentationScope,
                                                        "chooser-cancelled",
                                                    )
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                    // R6-P2-08 review finding 1: "a closable layer is open" now spans every surface EdgeBack
                    // can reach, not just the Home lattice's own Tome — a `folderOpen` sourced from
                    // `openFolderId` alone knew nothing about the quick-launch picker, a choice dialog, the
                    // Tweaker panel or the platform shelf covering it, so a swipe over any of those ran
                    // `close_folder` on the HIDDEN Tome while the covering surface stayed up.
                    // R6-F-11: `shelfLaunchCardOpen` joins this OR too — the card is a surface WITHIN the
                    // platform shelf, and until now a swipe over it backed the whole shelf out instead of
                    // just the card.
                    val closableLayerOpen = gameDisplayChoice != null || quickLaunchChoice != null ||
                        quickPickerOpen || secondaryTweakerVisible || shelfLaunchCardOpen ||
                        activityLaunchCardGame != null ||
                        platformDestination != null || folderOpen
                    LaunchedEffect(closableLayerOpen) {
                        CompanionBottomFolderState.markLayerOpen(closableLayerOpen)
                    }
                    // R6-P2-08 re-review finding D: nothing else clears `open` when THIS Presentation goes
                    // away (a display remount/reconnect creates a new one) — without this a stale `true`
                    // arms the strip for a layer that no longer has a composition to close it.
                    DisposableEffect(Unit) {
                        onDispose { CompanionBottomFolderState.markLayerOpen(false) }
                    }
                    // R6-P2-08 re-review finding C: `closeBookRequest` is a StateFlow and replays its last
                    // value to a brand-new Presentation (a remount/reconnect creates one — see
                    // CompanionDisplayController). Its `LaunchedEffect` below still fires once on THIS
                    // composable's own first composition regardless, so a stale, already-resolved id from
                    // a PREVIOUS Presentation's lifetime would otherwise be resolved a second time here —
                    // closing whatever happens to be open in this new instance, for a swipe that never
                    // happened in it. `claim` is process-wide (not just "new to THIS composable"), so it
                    // also covers two Presentations briefly alive at once, not only a clean handoff.
                    // Native EdgeBack asks to close whichever of those is innermost, one per swipe. Resolved
                    // HERE rather than at admission — MainActivity only knows "something is open"; only this
                    // composition knows WHICH layer currently covers the others.
                    LaunchedEffect(closeBookRequest) {
                        // `closeBookRequest` is a `by collectAsState()` delegate, never smart-cast — bind
                        // it to a plain local first so `claim` gets a non-null Long.
                        val request = closeBookRequest ?: return@LaunchedEffect
                        if (!CompanionBottomFolderState.claim(request)) return@LaunchedEffect
                        val closedLayer = when {
                            gameDisplayChoice != null -> {
                                // `gameDisplayChoice` is a `by collectAsState()` delegate, never smart-cast —
                                // `!!` is safe here, guarded by this same branch's `!= null` a line above.
                                val choice = gameDisplayChoice!!
                                if (CompanionGameDisplayChoiceState.dismiss(choice)) {
                                    Log.i(
                                        "MimirOpening",
                                        gameDisplayChooserCancelledLogMessage(choice.gameId.value, choice.editing),
                                    )
                                    if (!choice.editing) {
                                        GameOpeningIntroState.clearLaunching(choice.gameId.value)
                                        dismissGameOpeningPresentation(gameOpeningPresentationScope, "chooser-cancelled")
                                    }
                                }
                                "game_display_choice"
                            }
                            quickLaunchChoice != null -> {
                                CompanionQuickLaunchChoiceState.dismiss(quickLaunchChoice)
                                "quick_launch_choice"
                            }
                            quickPickerOpen -> {
                                closePickerRequest = request
                                "quick_picker"
                            }
                            secondaryTweakerVisible -> {
                                sharedBrightnessCoordinator?.dismissFromSecondaryPresentation()
                                "tweaker"
                            }
                            // R6-F-11: the card sits ON the platform shelf, so its arm goes above the
                            // shelf's own — decided once, same shape as `closeFolderRequestId` below, and
                            // forwarded rather than acted on here, since only CompanionPlatformShelf's own
                            // `pending = null` is "the same path the card's own dismiss uses".
                            // L2: the card opened from the Activity page closes before anything under it.
                            activityLaunchCardGame != null -> {
                                activityLaunchCardGame = null
                                "activity_launch_card"
                            }
                            shelfLaunchCardOpen -> {
                                closeCardRequestId = request
                                "shelf_launch_card"
                            }
                            platformDestination != null -> {
                                CompanionLibraryRoute.backToLattice()
                                "platform_shelf"
                            }
                            // Forwarded to CompanionSurface below rather than acted on here — closing it may
                            // mean the rename keyboard, the move-out confirm, or the Tome itself, and only
                            // CompanionHome/CompanionFolderPage can see which; each logs its own layer name.
                            // R6-P2-08 re-review finding B: decided ONCE, right here, inside this same
                            // effect run — never as a `closeBookRequest.takeIf { other layers closed }`
                            // computed OUTSIDE the effect. That form is re-evaluated on every later
                            // recomposition, so once a covering layer closed (even by an unrelated tap) the
                            // SAME unchanged `closeBookRequest` value would newly satisfy the `takeIf` and
                            // close the Tome too — one swipe (or tap) taking down two layers.
                            folderOpen -> {
                                closeFolderRequestId = request
                                null
                            }
                            else -> null
                        }
                        if (closedLayer != null) Log.i("MimirCompanionRuntime", "edge back closed layer=$closedLayer")
                    }
                    // The companion content surface (now-playing / library / idle + shared Quicklaunch).
                    // Retained under a covering shelf, hidden (alpha 0 + panelCovered) rather than
                    // disposed, so a shelf exit starts its fade on an already-measured surface
                    // (2026-09-10 trace: remount cost 74 ms).
                    CompanionAmbientMotionProvider(
                        interactionMotionGate = interactionMotionGate,
                    ) {
                        CompanionSurface(
                                payload = workspaceSurfacePayload,
                                latticePayload = companionShelfPayload(payload, retainedHomePayload),
                                onReturnToGame = onReturnToGame.takeIf { returnToGameAvailable },
                                // Collected LIVE rather than carried on the payload: a job ticks on its
                                // own clock, and a payload only changes when something pushes one. Via
                                // the payload the line would freeze between pushes and lie by omission.
                                jobs = remember(runningJobs) {
                                    runningJobs.map { job ->
                                        CompanionJobProgress(
                                            kind = job.kind.name,
                                            seen = job.seen,
                                            total = job.total,
                                        )
                                    }
                                },
                                primaryRoute = CompanionPrimaryRoute(
                                    destination = scene.route.destination,
                                    revision = scene.route.revision,
                                ),
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged { workspaceStrip.widthPx = it.width.toFloat() }
                                    .graphicsLayer { alpha = if (shelfCoversState.value) 0f else 1f }
                                    .mimirGlassBackdropProgress {
                                        maxOf(latticeGlass(), launchGlass())
                                    }
                                    // The picker lives inside this surface but Instruments is its sibling
                                    // below. Raise the whole picker-owning surface synchronously while
                                    // the picker is open so the scrim/card stay fully interactive.
                                    .zIndex(
                                        if (quickPickerOpen || quickLaunchChoice != null) {
                                            CompanionQuickPickerLayerZ
                                        } else {
                                            0f
                                        },
                                    ),
                                sceneOverride = workspaceActivityScene,
                                neighbourScene = activitySceneContent,
                                scenePanActive = workspaceStrip.active,
                                sceneStripX = workspaceStripX,
                                sceneTransitionSnap = workspaceStrip.carried,
                                sceneTransitionIdentity = workspacePage to (workspaceActivityScene != null),
                                sceneTransitionGlideOffsetDp =
                                    if (workspacePage == CompanionWorkspacePage.ACTIVITY) -MimirMotion.GlideTravelDp.toFloat() else MimirMotion.GlideTravelDp.toFloat(),
                                // S3: the 3DS shelf is touchable. **Console → that platform's shelf ON
                                // THIS DISPLAY** (policy 2026-09-01; it used to be
                                // `CompanionCommandState.openGamelistOnMain`, which drove screen 1 from
                                // screen 2 — see [CompanionLibraryRoute] for the canon that supersedes).
                                // App → screen 2, unchanged.
                                onPlatformTap = { platformIdValue, label ->
                                    CompanionOrderStore.setCursor(context, platformIdValue)
                                    CompanionLibraryRoute.openPlatform(platformIdValue, label)
                                    // D8: a game on screen keeps the shelf refused until it ends; the tap answers at once instead of seeming dead.
                                    if (companionLivePlatformTapWaitsForGame()) {
                                        showMimirMessage(context, CompanionShelfOpensAfterGameCopy, MimirMessageClass.Guidance)
                                    }
                                },
                                // The tile hands over its own label. The lookup that used to stand here
                                // read `quickLaunchApps`, a sweep that starts empty -- so a tap before it
                                // landed produced a null hint and the launch line printed the package id.
                                // A tile on screen cannot be un-ready about what it is called.
                                onAppTap = { packageName, label ->
                                    CompanionOrderStore.setCursor(context, packageName)
                                    CompanionRecentsState.onLaunch(packageName, label)
                                },
                                // S4: the layout the user filed with their own hands.
                                cursorId = cursorId,
                                childCursorByFolder = childCursors,
                                restoredPageProvider = restoredPageProvider,
                                onReorder = { ids -> CompanionOrderStore.setOrder(context, ids) },
                                onArm = { CompanionOrderStore.setCursor(context, it) },
                                onChildCursor = { folderId, childId ->
                                    CompanionOrderStore.setChildCursor(context, folderId, childId)
                                },
                                onPageSettled = { CompanionOrderStore.setPage(context, it) },
                                // CR-17: drop a tile INTO an existing tome after the dwell; rename via the keyboard.
                                onBind = { order, dragged, target ->
                                    CompanionOrderStore.bind(context, order, dragged, target)
                                },
                                onRenameFolder = { id, name -> CompanionOrderStore.renameFolder(context, id, name) },
                                // CR-17: explicit folder creation, and undo for an empty one.
                                onCreateFolder = { order -> CompanionOrderStore.createFolder(context, order) },
                                onDiscardFolder = { order, id -> CompanionOrderStore.discardFolder(context, order, id) },
                                //  long-press inside a book → the child returns to the shelf.
                                onUnbindChild = { order, id -> CompanionOrderStore.unbind(context, order, id) },
                                // R6-P2-08: the Home lattice's own folder reported here — folded into
                                // `closableLayerOpen`/`closeFolderRequestId` above rather than pushed
                                // straight to `CompanionBottomFolderState`, since this alone isn't "is a
                                // closable Bottom layer open" any more (review finding 1).
                                onBookOpenChanged = { folderOpen = it },
                                closeBookRequest = closeFolderRequestId,
                                // R6-P2-08 (finding 7, step 3): closes the quick-launch picker when it is the
                                // innermost open layer, the same native-triggered shape as `closeBookRequest`.
                                closePickerRequest = closePickerRequest,
                                // UX01 — the Now-Playing quick-launch footer (General + Q1/Q2/Q3): tap a pin to
                                // launch it on screen 2, long-press (or an empty slot) to (re)assign via the picker.
                                quickSlots = quickSlots,
                                quickLaunchApps = quickLaunchApps,
                                quickLaunchCatalogLoaded = quickLaunchCatalogLoaded,
                                onQuickLaunch = { packageName ->
                                    val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                                    routeQuickLaunch(packageName, label, true)
                                },
                                onPinnedQuickLaunch = { packageName ->
                                    val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                                    routeQuickLaunch(packageName, label, false)
                                },
                                onQuickAssign = { i, pkg -> QuickLaunchStore.setSlot(context, i, pkg) },
                                onQuickClear = { i -> QuickLaunchStore.setSlot(context, i, null) },
                                quickLaunchChoice = quickLaunchChoice,
                                onQuickLaunchChoice = { choice, destination ->
                                    settleSecondaryQuickLaunchChoiceTap(
                                        context = context,
                                        choice = choice,
                                        destination = destination,
                                        onPrimary = onPrimaryQuickLaunch,
                                        onSecondary = onSecondaryQuickLaunch,
                                        feedbackContext = { context },
                                        unavailableCopy = "Launch unavailable. Try again.",
                                    )
                                },
                                onQuickLaunchChoiceDismiss = { choice ->
                                    CompanionQuickLaunchChoiceState.dismiss(choice)
                                },
                                onQuickLaunchEdit = { packageName ->
                                    val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                                    CompanionQuickLaunchChoiceState.request(packageName, label, editing = true, fromGeneral = false)
                                },
                                // The drawer paints over the whole panel, so the lattice's cursor
                                // breath stands down while it is up — the same 20 Hz ticker the tome
                                // already freezes, from the one place that can see the drawer.
                                // The instruments panel covers the surface exactly like the drawer, so
                                // it stands the cursor breath down for the same reason (M-6).
                                // **And the ᛗ menu — ** The menu is a native overlay
                                // window with no composition in common with this one, so the scene
                                // beneath it can only soften if the fact is carried across. This
                                // pipeline already existed and is already device-proven for the drawer
                                // and the picker; the menu is a third thing that covers, not a fourth
                                // kind of blur. The effect stays on the SCENE and never on the panel —
                                // `CompanionSurface` states why in one line, and this tree has written
                                // that bug twice.
                                // O5: so does the Bottom Quick launch window the ᛗ menu opens over this one.
                                panelCovered = drawerCoversSurface || secondaryTweakerVisible || overlayMenuOpen || bottomQuickLaunchCovering,
                                hostCovered = shelfCovers,
                                // QA D11: only the overlay-hosted drawer brings a second rail, from another window.
                                railCovered = companionSurfaceRailCovered(drawerCoversSurface, drawerSelected),
                                // a footer button that changes between the
                                // shelves and the instruments. Non-null here because this host DOES
                                // offer the panel; other hosts pass null and get no seat at all.
                                instrumentsShown = secondaryTweakerVisible,
                                // R1 : the Tweaker opens in the ONE Bottom instruments window, above whatever the
                                // Bottom shows, a DS/3DS game's own window included; Back and Close there return to what was beneath.
                                onToggleInstruments = {
                                    if (secondaryTweakerVisible) {
                                        sharedBrightnessCoordinator?.dismissFromSecondaryPresentation()
                                    } else if (!bottomUtilityPanel.open(UtilitySurface.TWEAKER).opened) {
                                        showMimirMessage(context, "Tweaker is not ready on the bottom screen")
                                    }
                                },
                                onPickerOpenChanged = { quickPickerOpen = it },
                                onDraggingChanged = { companionSurfaceDragging = it },
                                // The workspace seam: the lattice offers what its pager could not use at page 0's left edge, and the strip takes it.
                                onWorkspaceDrag = { delta -> workspaceStrip.drag(CompanionWorkspacePage.SHELF, delta) },
                                onWorkspaceDragEnd = { velocity -> workspaceStrip.release(velocity, workspaceStripFlingPx) != null },
                                onWorkspaceDragCancel = { workspaceStrip.cancel() },
                                workspaceNavigationEnabled = workspaceNavigationEnabled(),
                                showActivitySwipeHint = !activitySwipeHintRetired,
                                // The lattice cursor ring's one gleam waits for this window's own arrival and, on a cold start, for the badge cover to leave.
                                latticeArmed = latticeArmed,
                                onDockExpandedChanged = { companionSurfaceDockExpanded = it },
                                quickLaunchLocked = recents.launchLocked,
                                quickLaunchMessage = recents.launchMessage,
                                onQuickLaunchIgnored = { target ->
                                    Log.i("MimirCompanionRuntime", secondaryLaunchIgnoredLogMessage(target, observedSecondaryLaunchId))
                                },
                            )
                            LaunchedEffect(Unit) {
                                val mountedAt = SystemClock.uptimeMillis()
                                withTimeoutOrNull(400L) { withFrameNanos { } }
                                surfaceHasFrame = true
                                if (traceShelfExit) {
                                    Log.d(
                                        CompanionShelfExitTag,
                                        "surface first frame dt=${SystemClock.uptimeMillis() - mountedAt}ms",
                                    )
                                }
                            }
                            if (traceShelfExit) {
                                DisposableEffect(Unit) {
                                    Log.d(
                                        CompanionShelfExitTag,
                                        "surface mounted uptime=${SystemClock.uptimeMillis()}ms",
                                    )
                                    onDispose { }
                                }
                            }
                    }
                    if (latticeGlassUp || secondaryLaunchTransitionVisible || secondaryLaunchTransitionId != null) {
                        // The same house glass carries both a gamelist return and a secondary launch; the
                        // scene stays soft underneath the incoming shelf or app instead of cutting.
                        MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                            val scrimColor = mimirGlassScrimColor()
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .zIndex(0.5f)
                                    .drawBehind {
                                        val progress = maxOf(latticeGlass(), launchGlass())
                                        drawRect(scrimColor.copy(alpha = scrimColor.alpha * progress))
                                    },
                            )
                        }
                    }
                    // A nullable panel kind gives AnimatedContent one owner for the Tweaker panel's entry
                    // and dismissal. When the target is null it composes no incoming panel, while the exact
                    // outgoing kind remains available to finish its exit.
                    // Receipts: the instruments host is one panel with two faces. Opening it is the fade below; moving
                    // between Utility and Tweaker inside it is the panel's own swap (BrightnessOverlay).
                    // The face is read INSIDE the effect (snapshotFlow), never here: this scope reads exactly what it read before
                    // the receipts, and a change of face invalidates the collector instead of the Bottom.
                    BottomUiInstrumentsReceipt(display = receiptDisplayId) {
                        sharedBrightnessCoordinator?.let { c ->
                            if (c.isOpen && c.presentationTarget == AppBrightnessPresentationTarget.SECONDARY) c.surface else null
                        }
                    }
                    // B1: the instruments host (Utility and Tweaker) arrives on the page tier's overlay clock, as Main's own Utility and Tweaker host does
                    // (the 130 ms lead at alpha 0, then 540 ms: about 670 ms from the press) and leaves on OverlayMs (about 180 ms). It is NOT Main's
                    // `MimirPixelFadeHost`: that host holds the process-wide pad gate on a cold arrival, which would deafen Main's controller for the lead,
                    // and the Bottom never holds it. `outgoingInput` below is unchanged.
                    val instrumentsReducedMotion = bottomUiReducedMotion()
                    AnimatedContent(
                            targetState = instrumentsPanel,
                            modifier = Modifier.zIndex(CompanionInstrumentsLayerZ),
                            transitionSpec = { mimirOverlayContentTransform(instrumentsReducedMotion, arriving = targetState != null) },
                            label = "companion-instruments-surface-transition",
                        ) { presentedPanel ->
                            if (presentedPanel == instrumentsPanel) {
                                BottomUiTransitionProbe("instruments.fade", transition, receiptDisplayId)
                            }
                            val outgoing = presentedPanel != instrumentsPanel
                            val outgoingInput = if (!outgoing) {
                                Modifier
                            } else {
                                Modifier
                                    .clearAndSetSemantics { }
                                    .pointerInput(Unit) {
                                        awaitPointerEventScope {
                                            while (true) {
                                                awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                                                    change.consume()
                                                }
                                            }
                                        }
                                    }
                            }
                            if (presentedPanel == CompanionInstrumentPanelKind.TWEAKER) {
                                val coordinator = requireNotNull(sharedBrightnessCoordinator)
                                // The arriving half of the page gate (touch only, holdPad = false): the panel takes no touch through the 130 ms lead at
                                // alpha 0 until it first paints. The departing half is `outgoingInput`, unchanged.
                                val instrumentsInput = MimirSwapPageGate(
                                    mimirRingSafeFadeAlpha(mimirOverlayArriveSpec(instrumentsReducedMotion), mimirOverlayLeaveSpec()),
                                    holdPad = false,
                                )
                                Box(modifier = outgoingInput.then(instrumentsInput)) {
                                    MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                                        AppBrightnessCoordinatorHost(
                                            coordinator = coordinator,
                                            target = AppBrightnessPresentationTarget.SECONDARY,
                                            secondaryPresentationToken = secondaryBrightnessPresentationToken,
                                            retainedForExit = outgoing,
                                        )
                                    }
                                }
                            }
                        }
                    // The INTERACTIVE recents drawer, layered over the mirror when the native quick-menu
                    // RECENTS action opens it. Launches on the secondary via the registered native handler.
                    // CP-FADE: it FADES in/out over the blurred shelf — never a hard pop.
                    BottomUiSurfaceReceipt(
                        surface = BottomSurface.TABS,
                        visible = drawerSelected,
                        transition = if (drawerSelected) "tabs.enter" else "tabs.exit",
                        display = receiptDisplayId,
                    )
                    // B1: Tabs over the shelf arrive on the page tier's overlay clock (the 130 ms lead at alpha 0, then 540 ms: about 670 ms from the
                    // press) and leave on OverlayMs (about 180 ms), as Main's Quick Launch dock does; the same two specs feed the painting and the touch
                    // gate's probe. The gate passes holdPad = false (the Bottom is touch-only, and the pad gate is process-wide): an arriving drawer takes
                    // no touch until it paints and a leaving one none, and the pad is never held.
                    val tabsReducedMotion = remember(drawerSelected) { bottomUiReducedMotion() }
                    val tabsEnterSpec = mimirOverlayArriveSpec(tabsReducedMotion)
                    val tabsExitSpec = mimirOverlayLeaveSpec()
                    AnimatedVisibility(
                        visible = drawerSelected,
                        modifier = Modifier.zIndex(CompanionRecentsDrawerLayerZ),
                        enter = fadeIn(tabsEnterSpec),
                        exit = fadeOut(tabsExitSpec),
                    ) {
                        BottomUiTransitionProbe(if (drawerSelected) "tabs.enter" else "tabs.exit", transition, receiptDisplayId)
                        val tabsInput = MimirSwapPageGate(mimirRingSafeFadeAlpha(tabsEnterSpec, tabsExitSpec), holdPad = false)
                        CompanionRecentsDrawer(
                            modifier = tabsInput,
                            // Same rail, same seats, wherever it is drawn -- including the seat's STATE: the
                            // surface rail counts the Bottom Tweaker host as "instruments shown" (above), and
                            // this drawer's tap dismisses that host, so its seat must read the same way
                            // (surface map finding F3, 2026-09-11).
                            instrumentsShown = secondaryTweakerVisible,
                            // **This drawer composes ABOVE the instruments panel** (see the call order
                            // in this file), so raising the panel from inside it would put the panel
                            // where nobody can see it and the seat would read as dead. The drawer
                            // stands down first. Found by review minutes after the rail wiring landed —
                            // giving both rails the same seat was right, and it created this the moment
                            // it did, because the two hosts do not share a z-order.
                            onToggleInstruments = {
                                CompanionRecentsState.hide()
                                if (secondaryTweakerVisible) {
                                    sharedBrightnessCoordinator?.dismissFromSecondaryPresentation()
                                } else if (!bottomUtilityPanel.open(UtilitySurface.TWEAKER).opened) {
                                    // R1: the same window as the shelf's seat and the Bottom menu's Utility.
                                    showMimirMessage(context, "Tweaker is not ready on the bottom screen")
                                }
                            },
                            entries = recents.entries,
                            closeOffered = companionTabCloseIsOffered(companionRuntime),
                            launchLocked = recents.launchLocked,
                            launchMessage = recents.launchMessage,
                            closeMessage = recents.closeMessage,
                            closePending = recents.closePending,
                            themeId = payload.themeId,
                            quickSlots = quickSlots,
                            quickLaunchApps = quickLaunchApps,
                            quickLaunchCatalogLoaded = quickLaunchCatalogLoaded,
                            onQuickLaunch = { packageName ->
                                val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                                routeQuickLaunch(packageName, label, true)
                            },
                            onPinnedQuickLaunch = { packageName ->
                                val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                                routeQuickLaunch(packageName, label, false)
                            },
                            onQuickAssign = { index, packageName ->
                                QuickLaunchStore.setSlot(context, index, packageName)
                            },
                            onQuickClear = { index -> QuickLaunchStore.setSlot(context, index, null) },
                            quickLaunchChoice = quickLaunchChoice,
                            onQuickLaunchChoice = { choice, destination ->
                                settleSecondaryQuickLaunchChoiceTap(
                                    context = context,
                                    choice = choice,
                                    destination = destination,
                                    onPrimary = onPrimaryQuickLaunch,
                                    onSecondary = onSecondaryQuickLaunch,
                                    feedbackContext = { context },
                                    unavailableCopy = "Launch unavailable. Try again.",
                                )
                            },
                            onQuickLaunchChoiceDismiss = { choice ->
                                CompanionQuickLaunchChoiceState.dismiss(choice)
                            },
                            onQuickLaunchEdit = { packageName ->
                                val label = quickLaunchApps.firstOrNull { it.packageName == packageName }?.label
                                CompanionQuickLaunchChoiceState.request(packageName, label, editing = true, fromGeneral = false)
                            },
                            onLaunch = { CompanionRecentsState.onLaunch(it) },
                            onClose = { CompanionRecentsState.onClose(it) },
                            onDismiss = { CompanionRecentsState.hide() },
                            onQuickLaunchIgnored = { target ->
                                Log.i("MimirCompanionRuntime", secondaryLaunchIgnoredLogMessage(target, observedSecondaryLaunchId))
                            },
                        )
                    }
                }
                }
                MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                    // The recent-app icon belongs to the Activity page, so it rides that page's pane: the strip's x less one width while the strip is off its
                    // rest, else 0 on Activity and a full width off the canvas on the lattice, decided in the draw phase from the live strip and the live page
                    // (see companionActivityPaneTranslationX): the frame after a landing on the lattice never draws it, though it is still composed.
                    Box(Modifier.fillMaxSize().companionActivityPane(stripX = workspaceStripX, shown = { workspacePage })) {
                    CompanionWorkspaceSurface(
                        page = if (workspacePage == CompanionWorkspacePage.ACTIVITY || workspaceStrip.active) CompanionWorkspacePage.ACTIVITY else CompanionWorkspacePage.SHELF,
                        navigationEnabled = workspaceNavigationEnabled(),
                        recentApp = mostRecentBottomApp,
                        game = workspaceGame,
                        media = mediaState,
                        onRecentApp = { entry -> CompanionRecentsState.onLaunch(entry.packageName, entry.label) },
                        onReturnToGame = onReturnToGame.takeIf { returnToGameAvailable && workspaceGame != null },
                        onMediaAccess = { CompanionMediaSessionBridge.openAccessSettings(context) },
                        onMediaAction = { action, sessionIdentity, trackIdentity ->
                            CompanionMediaSessionBridge.dispatch(action, sessionIdentity, trackIdentity)
                        },
                        onMediaSeek = { positionMs, sessionIdentity, trackIdentity ->
                            CompanionMediaSessionBridge.dispatchSeek(positionMs, sessionIdentity, trackIdentity)
                        },
                        showActivityContent = false,
                        // Not over a layer that closes on a tap outside it: the open launch card, or the achievements console picker (its tap on the corner would open the app instead).
                        showRecentAppOverlay = activityLaunchCardGame == null && !BottomRecentAppTileCover.covered,
                    )
                    }
                }
                // The cold-start handoff cover, last so it is on top of the lattice and the workspace: Main's return cover, the same composable the floor wears
                // beneath this window, so the swap from the floor's cover to this window's first frame is not a cut. It takes no touch (no pointer input).
                if (coldCoverArmed) {
                    MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                        CompanionMannazCover(visible = coldCoverHeld, onGone = { coldCoverLeft = true })
                    }
                }
                // The reclaim handoff cover (Q7a, W2L7 review #2): the restore guard's exit, worn by THIS window because it sorts above the guard. Each
                // reveal from behind a host (`reclaimReveals`, counted in `rebindRenderOperation`; the decor is shown only after this composition, below) composes a fresh cover at
                // full alpha over the first revealed frames (the one the guard's mirror completion acknowledges among them), then starts its OverlayMs
                // exit over a lattice that is already in, so the guard's ground becomes the lattice through a fade and not the X-06 cut. Keyed on the
                // reveal: a cover kept from the last reclaim would ramp 0 -> 1 instead of being up at once. Reduced motion composes no cover (a cut, as
                // the cold cover). Never while the mount cover is still up (one cover at a time). The cursor ring does not wait on it: a reclaim restores a
                // settled lattice. Receipt: `reclaim-cover.fade`.
                val reclaimReveal by reclaimReveals.collectAsState()
                // F2: a reveal that arrives while the mount cover is up is covered by it and SPENT, so no reveal cover pops in for it once the
                // mount cover has left. The count spent is kept outside the snapshot (written and read here only, as `lastLive` is).
                val mountCoverUp = coldCoverArmed && !coldCoverLeft
                val revealsSpentByMountCover = remember { intArrayOf(0) }
                if (mountCoverUp && reclaimReveal > revealsSpentByMountCover[0]) revealsSpentByMountCover[0] = reclaimReveal
                if (companionReclaimCoverWorn(reveals = reclaimReveal, reducedMotion = animatorsOff, mountCoverUp = mountCoverUp, spent = revealsSpentByMountCover[0])) {
                    key(reclaimReveal) {
                        var reclaimCoverHeld by remember { mutableStateOf(true) }
                        LaunchedEffect(Unit) {
                            withFrameNanos { }
                            withFrameNanos { }
                            BottomUiReceipts.log.transitionBegin(CompanionReclaimCoverFade, receiptDisplayId)
                            reclaimCoverHeld = false
                        }
                        MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                            CompanionMannazCover(
                                visible = reclaimCoverHeld,
                                onGone = { BottomUiReceipts.log.transitionEnd(CompanionReclaimCoverFade, receiptDisplayId, "done") },
                            )
                        }
                    }
                }
                // A revealed window's decor is shown from HERE, after this composition (the cover in it) is applied and before the frame's traversal
                // draws: the first drawn frame of a reveal wears the cover. Never from `rebindRenderOperation`, which runs a frame earlier.
                SideEffect { revealDecorIfPending(reclaimReveal, fallback = false) }
                // D12: Mimir's own message for the Bottom (what a toast made from this window's context used to be), drawn here and so on THIS display, never Main's. Last, so it is over
                // everything this window draws; live only while the window is up and lit. That is asked of the window itself at the moment of each post (windowLive.isLive) and followed by
                // a coroutine (windowLive.liveFlow), never through this composition: a parked window's lifecycle is CREATED, which stops its frame clock, so nothing here recomposes while
                // it is parked, and a message made then goes to the system toast and is never kept for the reveal.
                MimirTheme(themeId = MimirThemeId.fromStorageKey(payload.themeId)) {
                    val messagesLiveness = remember(windowLive) { MimirHostLiveness(isLiveNow = { windowLive.isLive }, changes = windowLive.liveFlow) }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        MimirDisplayMessageHost(
                            displayId = receiptDisplayId,
                            liveness = messagesLiveness,
                            bottomInset = MimirBottomMessageRailBottomInset,
                            // As Main's rail: the band grows to the card when a larger font wraps the copy, so its last line and its rune are not cut by the pill.
                            growsToFit = true,
                            railWidthFraction = MimirBottomMessageRailWidthFraction,
                        )
                        AchievementToastHost()
                    }
                }
                }
                }
            }
        }
        this.composeView = composeView
        setContentView(
            CompanionPresentationRoot(context, onWindowRevealed).apply {
                setViewTreeLifecycleOwner(host)
                setViewTreeViewModelStoreOwner(viewModelStoreOwner)
                setViewTreeSavedStateRegistryOwner(host)
                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )
    }

    internal fun isWindowActuallyVisible(): Boolean =
        isShowing && composeView?.windowVisibility == View.VISIBLE

    /** Re-token an already-visible same-display window and acknowledge its next committed frame. */
    internal fun rebindRenderOperation(operationId: UUID): Boolean {
        val previous = renderOperation.value
        if (!windowShown) return false
        val view = composeView ?: return false
        // The reveal cover (Q7a): a window revealed from behind a host wears it over its first frames; a re-token of a window already showing is
        // not a reveal and wears nothing. A revealed window's decor is NOT shown here: the reveal reaches the composition on the next frame, and a
        // decor shown now would let that frame's traversal draw the lattice bare under no cover (the re-check's one-frame question). It is shown
        // from the composition's SideEffect once the cover is in the tree (`revealDecorIfPending`), or by the bounded fallback.
        if (parkedBehindSecondaryHost.value) {
            reclaimReveals.value += 1
            val pending = reclaimReveals.value
            decorRevealPendingFor = pending
            view.postDelayed({ revealDecorIfPending(pending, fallback = true) }, CompanionDecorRevealFallbackMs)
        } else if (decorRevealPendingFor == 0) {
            // A re-token of a window already showing shows at once; a second render in the frame of a pending reveal (P3) goes through that
            // reveal, or it would show the decor a frame before the cover.
            window?.decorView?.visibility = companionPresentationDecorVisibility(coveredBySecondaryHost = false)
        }
        parkedBehindSecondaryHost.value = false
        presentationFrameCommitHandshake.clear()
        host.resume()
        motionRoot?.setVisible(true)
        if (previous != operationId) {
            CompanionRecentsState.releasePresentationHost(previous)
            renderOperation.value = operationId
            CompanionRecentsState.registerPresentationHost(operationId)
            if (CompanionRecentsState.ui.value.requestedVisible) {
                CompanionRecentsState.selectPresentation(operationId)
            }
        }
        renderCommitEpoch.value = if (renderCommitEpoch.value == Long.MAX_VALUE) {
            renderCommitEpoch.value
        } else {
            renderCommitEpoch.value + 1L
        }
        view.invalidate()
        return true
    }

    internal fun currentRenderOperationId(): OperationId? =
        OperationId.parseOrNull(renderOperation.value.toString())

    /** Keep the attached window behind a secondary host while revoking its covered action generation. */
    internal fun parkBehindSecondaryHost(): Boolean {
        if (!isReusable) return false
        BottomUiReceipts.log.presentation("park", display.displayId, "secondary_host_cover", currentRenderOperationId()?.toString())
        touchLease?.close()
        touchLease = null
        presentationFrameCommitHandshake.clear()
        motionRoot?.setVisible(false)
        host.park()
        // TYPE_PRESENTATION sorts above ordinary activity windows on Thor. Keeping the retained window
        // VISIBLE therefore obscures the secondary host even though runtime ownership has yielded.
        // Hide the whole decor (including its opaque window background) while preserving the reusable
        // Presentation instance; rebindRenderOperation reveals it only for the next certified render.
        window?.decorView?.visibility = companionPresentationDecorVisibility(coveredBySecondaryHost = true)
        // F1: a reveal still waiting for its composition is withdrawn, so neither that composition's SideEffect nor the fallback shows the decor
        // over the app this park is for.
        decorRevealPendingFor = 0
        parkedBehindSecondaryHost.value = true
        return true
    }

    private fun <T : Any> armFrameCommit(
        view: ComposeView,
        handshake: CompanionFrameCommitHandshake<T>,
        token: T,
        onCommitted: (T) -> Unit,
    ) {
        if (!handshake.arm(token)) return
        val listener = object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                val observer = view.viewTreeObserver
                view.post {
                    if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnDrawListener(this)
                }
                val plan = handshake.beginDraw(
                    token = token,
                    compositorCallbackAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && observer.isAlive,
                ) ?: return
                val report = Runnable {
                    if (handshake.complete(plan.token)) {
                        onCommitted(plan.token)
                    }
                }
                if (plan.delivery == CompanionFrameCommitDelivery.COMPOSITOR_CALLBACK) {
                    observer.registerFrameCommitCallback(report)
                    if (plan.requestFollowUpFrame) view.postInvalidateOnAnimation()
                } else {
                    // Older Android cannot expose a compositor commit callback; the next animation pulse is the
                    // narrowest supported fallback and remains token-revalidated by the controller.
                    view.postOnAnimation(report)
                }
            }
        }
        view.viewTreeObserver.addOnDrawListener(listener)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (idleWakeTouchLatched) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                idleWakeTouchLatched = false
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            onSecondaryMButtonInteraction()
            if (onSecondaryTouchInteraction()) {
                idleWakeTouchLatched = true
                return true
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (BuildConfig.DEBUG) {
                Log.d(
                    CompanionShelfDiagnosticTag,
                    "dispatchTouchEvent ACTION_DOWN displayId=${display?.displayId ?: -1} x=${event.x} y=${event.y}",
                )
            }
            touchLease?.close()
            touchLease = motionRoot?.let { root -> interactionMotionGate.hold(root, InteractionSource.TOUCH) }
        }
        return try {
            super.dispatchTouchEvent(event)
        } finally {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchLease?.close()
                touchLease = null
            }
        }
    }

    override fun onStart() {
        super.onStart()
        windowShown = true
    }

    override fun onStop() {
        super.onStop()
        windowShown = false
        parkedBehindSecondaryHost.value = true
        // This window's end of life ends the card it showed, and a card's sound with it: a later window starts with no card, so a
        // sound merely held here would come back on its first live frame with nothing to hear it over. Stop first, then withdraw.
        GameOpeningIntroState.stopCardAudio("window-stopped")
        if (GameOpeningIntroState.bottomWindow.value === windowLive) GameOpeningIntroState.bottomWindow.value = null
        // Dismiss is this window's end of life. Same-display reclaims re-token this host without stopping it.
        CompanionProcessRuntime.sharedBrightness.value
            ?.takeIf { it.presentationTarget == AppBrightnessPresentationTarget.SECONDARY }
            ?.onSecondaryPresentationStopped(secondaryBrightnessPresentationToken)
        CompanionRecentsState.releasePresentationHost(renderOperation.value)
        composeView = null
        presentationFrameCommitHandshake.clear()
        touchLease?.close()
        touchLease = null
        idleWakeTouchLatched = false
        motionRoot?.close()
        motionRoot = null
        stopWatchingScreen?.invoke()
        stopWatchingScreen = null
        host.destroy()
    }
}

internal fun companionPresentationDecorVisibility(coveredBySecondaryHost: Boolean): Int =
    if (coveredBySecondaryHost) View.INVISIBLE else View.VISIBLE

/** The Bottom's ambient motion locals. Internal so a JVM test can run the ring under the gate the Presentation uses. */
@Composable
internal fun CompanionAmbientMotionProvider(
    interactionMotionGate: InteractionMotionGate,
    content: @Composable () -> Unit,
) {
    val phase by interactionMotionGate.phase.collectAsState()
    CompositionLocalProvider(LocalMimirAmbientMotionPhase provides phase, content = content)
}

/**
 * The game's "Opens on" chooser. It is the Bottom's one choice dialog ([CompanionOpensOnDialog], V7): the quick-launch layer's per-app
 * choice composes the same one. When the player is editing a saved choice, the saved one is the lit capsule (no saved choice is "Ask
 * next time").
 */
@Composable
private fun CompanionGameDisplayChoiceDialog(
    choice: CompanionGameDisplayChoice,
    enabled: Boolean,
    onSelect: (CompanionQuickLaunchDestination) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    armed: () -> Boolean = MimirFocusAlwaysArmed,
) {
    val current = remember(choice) {
        if (!choice.editing) {
            null
        } else {
            when (CompanionGameDisplayPreferenceStore.preference(choice.gameId)) {
                CompanionGameDisplayPreference.MAIN -> CompanionQuickLaunchDestination.MAIN
                CompanionGameDisplayPreference.BOTTOM -> CompanionQuickLaunchDestination.BOTTOM
                null -> CompanionQuickLaunchDestination.ASK_NEXT_TIME
            }
        }
    }
    CompanionOpensOnDialog(
        label = choice.label,
        editing = choice.editing,
        enabled = enabled,
        onSelect = onSelect,
        onDismiss = onDismiss,
        modifier = modifier,
        current = current,
        armed = armed,
    )
}

/**
 * A window feed (W2L7 review #6; RC-36, W1B #4): collected only while this window is live ([CompanionWindowLive]: not parked behind a host, not
 * stopped, screen on), keeping its last value across a park, so the library is not queried for a window nobody sees (the shelf behind an app,
 * D24) and the page the strip brings back still finds its feeds answered. The root provides no pane liveness, so this reads the window's own.
 */
@Composable
private fun <T> Flow<T>.collectAsStateWhileWindowLive(initial: T, live: CompanionWindowLive): State<T> {
    val upstream = this
    val state = remember(upstream) { mutableStateOf(initial) }
    LaunchedEffect(upstream, live) {
        live.liveFlow.collectLatest { on -> if (on) upstream.collect { state.value = it } }
    }
    return state
}
