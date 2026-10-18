package com.gruning.mimir.rewrite

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Window
import android.window.SplashScreen
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gruning.mimir.rewrite.core.companionnative.CompanionOverlayController
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySelector
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplaySnapshot
import com.gruning.mimir.rewrite.core.model.GameId
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Activity-bound surface port for the process [CompanionRuntimeCoordinator].
 *
 * This class owns only Android windows. It never infers disposition from `Presentation.isShowing`,
 * dispatch success, Activity teardown, or a timeout. A fresh Presentation reports an exact child render
 * acknowledgment carrying its parent root and renderer-owner UUID.
 */
internal class CompanionDisplayController(
    private val activity: ComponentActivity,
    private val overlayController: CompanionOverlayController? = null,
    private val secondaryDisplaySelector: SecondaryDisplaySelector,
    runtimeCoordinator: CompanionRuntimeCoordinator? = null,
    private val onSecondaryTouchInteraction: () -> Boolean = { false },
    private val onSecondaryMButtonInteraction: () -> Unit = {},
) : CompanionHostPort {
    private companion object {
        const val PresentationRevealSettleMs = 96L
    }

    override val rendererOwnerId: RendererOwnerId = RendererOwnerId.create()

    private val displayManager: DisplayManager? = activity.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var runtimeCoordinator: CompanionRuntimeCoordinator? = runtimeCoordinator
    // The window on screen. Whenever it stops being this one (dismissed, replaced, the host stopped) the cold-start handoff is told it left, so a floor that comes
    // up afterwards sees no Presentation attached on that display ([CompanionColdStartHandoff]).
    private var presentation: MimirCompanionPresentation? = null
        set(value) {
            val left = field
            field = value
            if (left != null && left !== value) CompanionColdStartHandoff.presentationGone(left.display.displayId)
        }
    private val pendingRenderAttempts = mutableMapOf<OperationId, RenderAttempt>()
    private var snapshotJob: Job? = null
    /** A parked-game Return is a process handoff; duplicate taps must share its one exact root. */
    private var parkedGameReturnJob: Job? = null
    private var displaySelectorSubscription: Closeable? = null
    private var menuReceiptsJob: Job? = null
    private var started = false

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    internal fun attachRuntime(runtime: CompanionRuntimeCoordinator) {
        val previous = runtimeCoordinator
        if (previous === runtime) return
        if (started) previous?.unbindRenderer(rendererOwnerId)
        runtimeCoordinator = runtime
        if (started) {
            observe(runtime)
            runtime.bindRenderer(this)
            displaySelectorSubscription?.close()
            displaySelectorSubscription = secondaryDisplaySelector.addListener(::onSecondaryDisplaySnapshot)
        }
    }

    internal fun detachRuntime(runtime: CompanionRuntimeCoordinator) {
        if (runtimeCoordinator !== runtime) return
        if (started) runtime.unbindRenderer(rendererOwnerId)
        runtimeCoordinator = null
    }

    /** Begin display observation and bind this Activity's renderer owner. */
    fun start() {
        if (started) return
        started = true
        val runtime = runtime()
        observe(runtime)
        runtime.bindRenderer(this)
        displaySelectorSubscription = secondaryDisplaySelector.addListener(::onSecondaryDisplaySnapshot)
        menuReceiptsJob?.cancel()
        menuReceiptsJob = overlayController?.let { observeBottomMenuReceipts(activity.lifecycleScope, it) }
    }

    /** Remove only this Activity's port and window; process disposition remains intact. */
    fun stop() {
        if (!started) return
        started = false
        displaySelectorSubscription?.close()
        displaySelectorSubscription = null
        menuReceiptsJob?.cancel()
        menuReceiptsJob = null
        mainHandler.removeCallbacksAndMessages(null)
        snapshotJob?.cancel()
        snapshotJob = null
        dismissPresentation("activity_stop")
        runtimeCoordinator?.unbindRenderer(rendererOwnerId)
        _active.value = false
    }

    /**
     * Tokened explicit reclaim. A failed/late guard never re-shows directly: when the reclaim was minted over a
     * covered `Yielded` host with no `returningFromPackage` it retreats to that host (D2, 2026-09-06, see
     * `ReclaimLease.coveredHost`); otherwise it enters recovery.
     */
    // R02 trace (2026-07-16): restore() deliberately calls the runtime's explicitReclaim DIRECTLY and never
    // the foreground monitor's `notifyExplicitReturn` bus — that bus's collector treats every event as a
    // full Mimir-primary return (disarms + clears the retained Now-Playing hero). Secondary reclaims like
    // "tab_close_reclaim" must keep bypassing it so a screen-2 tab close never kills a live game's card.
    fun restore(
        reason: String,
        returningFromPackage: String? = null,
        refreshPresentedMirror: Boolean = false,
    ): Boolean =
        runtime().explicitReclaim(
            reason = reason,
            primaryOrigin = CompanionReturnCoordinator.currentPrimaryOrigin(),
            returningFromPackage = returningFromPackage,
            refreshPresentedMirror = refreshPresentedMirror,
        ) != null

    /** Reclaim only the ended Main DS/3DS game's exact Bottom mirror state or Return-to-game handoff lease. */
    fun restoreAfterMainGameExit(
        operation: MimirGameSessionOperation,
        packageName: String?,
    ): Boolean {
        val cleanPackage = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val game = MimirSessionAuthority.state.value.gameSession ?: return false
        if (game.operation != operation ||
            game.phase != MimirSessionPhase.ENDED ||
            game.placement != MimirGamePlacement.MAIN ||
            !game.dualScreen ||
            !game.targetPackageName.equals(cleanPackage, ignoreCase = true)
        ) return false
        val disposition = runtime().snapshot.value.disposition
        if (!companionMainGameExitReclaimAllowed(disposition, cleanPackage)) return false
        return restore(
            reason = "main_game_exit",
            returningFromPackage = cleanPackage,
            refreshPresentedMirror = disposition is SecondaryDisplayDisposition.MirrorPresented,
        )
    }

    /** Source-compatible adapter for callers that previously requested an unguarded direct reclaim. */
    fun reclaim(reason: String = "manual_reclaim") {
        restore(reason)
    }

    /** Source-compatible launch adapter; the process coordinator now owns suppression truth. */
    fun suppressForSecondaryHost(packageName: String, displayId: Int?, launchMode: String) {
        CompanionReturnCoordinator.noteHostLaunchStarting(packageName, displayId, launchMode)
    }

    /** The attached secondary Presentation window, exposed only while that exact window is showing. */
    internal fun secondaryWindow(): Window? = presentation?.takeIf { it.isShowing }?.window

    /** Empty/malformed ids are rejected by the process gateway. */
    fun restoreFromSecondaryHost(reason: String = "secondary_app_return", sessionId: String = "") {
        CompanionReturnCoordinator.restoreFromSecondaryHost(reason, sessionId)
    }

    /** Untokened teardown is retained only for source compatibility and cannot mutate disposition. */
    fun noteSecondaryHostGone(alreadyRestored: Boolean) {
        CompanionReturnCoordinator.notifySecondaryHostGone(alreadyRestored)
    }

    override fun render(attempt: RenderAttempt) {
        runOnMain {
            if (!started || runtimeCoordinator?.snapshot?.value?.rendererOwnerId != rendererOwnerId) return@runOnMain
            if (secondaryDisplaySelector.snapshot.value.selectedId != attempt.displayId) {
                dismissPresentation("display_unselected", attempt.operationId)
                runtime().presentationFailed(
                    attempt.operationId,
                    attempt.parentDispositionId,
                    attempt.rendererOwnerId,
                    "display ${attempt.displayId} is no longer selected",
                )
                return@runOnMain
            }
            val target = displayManager
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.find { it.displayId == attempt.displayId && it.isValid }
            if (target == null) {
                dismissPresentation("display_unavailable", attempt.operationId)
                runtime().presentationFailed(
                    attempt.operationId,
                    attempt.parentDispositionId,
                    attempt.rendererOwnerId,
                    "display ${attempt.displayId} is unavailable",
                )
                return@runOnMain
            }

            val current = presentation
            val runtimeSnapshot = runtime().snapshot.value
            val freshMainGameReclaim = companionReclaimNeedsFreshPresentation(
                disposition = runtimeSnapshot.disposition,
                attempt = attempt,
                activeMainDualScreenGame = companionMainDualScreenGameActive(
                    MimirSessionAuthority.state.value.gameSession,
                ),
            )
            // `isShowing`, not the window's own liveness (`isReusable`): on SDK 34 a parked window's decor is INVISIBLE and `isShowing` reads that,
            // so this reuse never follows a park and a reclaim after one recreates the window (reuse OFF, lead 2026-10-15: no post-park reuse has
            // been seen on a device; `isReusable` is the switch for a later, device-tested change).
            if (!freshMainGameReclaim &&
                canReuseCompanionPresentation(current?.display?.displayId, current?.isShowing == true, target.displayId)
            ) {
                pendingRenderAttempts.clear()
                pendingRenderAttempts[attempt.operationId] = attempt
                val rebound = current?.rebindRenderOperation(
                    java.util.UUID.fromString(attempt.operationId.toString()),
                ) == true
                if (rebound) {
                    BottomUiReceipts.log.presentation(
                        "reuse",
                        target.displayId,
                        companionPresentationReceiptReason(runtimeSnapshot.disposition),
                        attempt.operationId.toString(),
                    )
                    return@runOnMain
                }
            }

            // A window that exists when a new one is wanted is REPLACED: the receipt says `recreate`, not `create`.
            val replacing = presentation != null
            dismissPresentation(if (freshMainGameReclaim) "fresh_main_game_reclaim" else "replace", attempt.operationId)
            pendingRenderAttempts[attempt.operationId] = attempt
            lateinit var candidate: MimirCompanionPresentation
            candidate = MimirCompanionPresentation(
                outerContext = activity,
                display = target,
                lifecycleOwner = activity,
                viewModelStoreOwner = activity,
                savedStateRegistryOwner = activity,
                initialRenderOperationId = java.util.UUID.fromString(attempt.operationId.toString()),
                interactionMotionGate = CompanionProcessRuntime.motionGate,
                onSecondaryTouchInteraction = onSecondaryTouchInteraction,
                onSecondaryMButtonInteraction = onSecondaryMButtonInteraction,
                onReturnToGame = ::returnToExistingMainGame,
                // The overlay above screen 2 and the shelf below it are separate windows; this is the
                // only place that holds both, so it is the only place that can join them.
                quickMenuVisualOpen = overlayController?.quickMenuVisualOpen ?: MutableStateFlow(false),
                // Explicit handoff state, not a read of the receipt log (CompanionColdStartHandoff): the floor armed it in its onCreate if no window was attached.
                coldStartHandoff = CompanionColdStartHandoff.forNewPresentation(target.displayId),
                // A fresh window for a reclaim (no window to reuse, or a DS/3DS main_game_exit) wears the mount cover over its first frame, as the parked
                // path wears the reveal cover (Q7a, X-06): read from the runtime's disposition, the same fact the receipt's reason names.
                reclaimHandoff = companionFreshPresentationReclaims(runtimeSnapshot.disposition),
                onFirstFrameCommitted = frameCommitted@{ committedId ->
                    runOnMain commit@{
                        // The cold start is over at the first Presentation frame on this display, whichever window it came from and whether or not it wore the cover:
                        // disarmed before any stale-window check below, so a latch can never outlive the frame that ends it.
                        CompanionColdStartHandoff.firstPresentationFrame(target.displayId)
                        if (!started || presentation !== candidate) return@commit
                        handleFrameCommitted(candidate, committedId)
                    }
                },
                onWindowRevealed = {
                    mainHandler.postDelayed({
                        if (!started || presentation !== candidate || !candidate.isWindowActuallyVisible()) {
                            return@postDelayed
                        }
                        val snapshot = runtime().snapshot.value
                        val yielded = snapshot.disposition as? SecondaryDisplayDisposition.Yielded
                            ?: return@postDelayed
                        if (snapshot.mirrorActive) return@postDelayed
                        val packageName = yielded.lease?.packageName ?: return@postDelayed
                        restore(
                            reason = "presentation_window_revealed",
                            returningFromPackage = packageName,
                        )
                    }, PresentationRevealSettleMs)
                },
            )
            presentation = candidate
            BottomUiReceipts.log.presentation(
                if (replacing) "recreate" else "create",
                target.displayId,
                companionPresentationReceiptReason(runtimeSnapshot.disposition),
                attempt.operationId.toString(),
            )
            runCatching {
                candidate.show()
                CompanionRecentsState.registerPresentationHost(
                    java.util.UUID.fromString(attempt.operationId.toString()),
                )
                BottomUiReceipts.log.presentation(
                    "show",
                    target.displayId,
                    companionPresentationReceiptReason(runtimeSnapshot.disposition),
                    attempt.operationId.toString(),
                )
            }
                .onFailure { error ->
                    BottomUiReceipts.log.presentation(
                        "fail",
                        target.displayId,
                        "show_threw_${error::class.java.simpleName}",
                        attempt.operationId.toString(),
                    )
                    candidate.dismiss()
                    if (presentation === candidate) presentation = null
                    pendingRenderAttempts.remove(attempt.operationId)
                    runtime().presentationFailed(
                        attempt.operationId,
                        attempt.parentDispositionId,
                        attempt.rendererOwnerId,
                        error.message ?: error::class.java.simpleName,
                    )
                }
        }
    }

    override fun dismissPresentation() = dismissPresentation("runtime_request")

    private fun dismissPresentation(reason: String, operationId: OperationId? = null) {
        pendingRenderAttempts.clear()
        val current = presentation
        presentation = null
        if (current != null) {
            BottomUiReceipts.log.presentation(
                "dismiss",
                current.display.displayId,
                reason,
                (operationId ?: current.currentRenderOperationId())?.toString(),
            )
        }
        runCatching { current?.dismiss() }
    }

    override fun dismissPresentationAfterCover(hostOperationId: OperationId, delayMillis: Long) {
        mainHandler.postDelayed({
            val runtime = runtimeCoordinator ?: return@postDelayed
            if (!runtime.isYieldedFor(hostOperationId)) return@postDelayed
            val current = presentation
            if (canRetainCoveredCompanionPresentation(
                    currentDisplayId = current?.display?.displayId,
                    currentShowing = current?.isShowing == true,
                    runtimeDisplayId = runtime.snapshot.value.displayId,
                ) && current?.parkBehindSecondaryHost() == true
            ) {
                return@postDelayed
            }
            dismissPresentation("host_cover_fallback", hostOperationId)
        }, delayMillis)
    }

    override fun prepareForReclaim(lease: ReclaimLease) {
        runOnMain { CompanionState.holdForReclaim() }
    }

    private fun returnToExistingMainGame(gameId: GameId): Boolean {
        val runtime = runtime()
        val session = MimirSessionAuthority.state.value.gameSession ?: return false
        if (session.gameId != gameId ||
            !companionMainDualScreenGameActive(session) ||
            !CompanionState.hasRetainedNowPlaying(gameId)
        ) return false
        val snapshot = runtime.snapshot.value
        val mirror = snapshot.disposition as? SecondaryDisplayDisposition.MirrorPresented ?: return false
        if (!snapshot.mirrorActive ||
            mirror.attempt.rendererOwnerId != rendererOwnerId ||
            presentation?.isShowing != true ||
            presentation?.currentRenderOperationId() != mirror.attempt.operationId
        ) return false
        val packageName = session.targetPackageName?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val parkedLease = MainGameParkingState.currentFor(session)
        // Return from Now Playing after the emulator removes its own secondary surface: with no
        // parked lease AND no reclaim of ours over this game, Mimir became visible only because the GAME removed its own display-4 window. The game is
        // already the Main foreground task, so there is nothing to step aside for: yielding parked the live mirror (decor invisible, lifecycle CREATED)
        // and nothing ever un-parked it. The mirror stays presented and live; when the emulator brings its own Presentation back it is added after ours
        // and stacks over it.
        // a lease's absence alone is not that case. A successful parked return clears its lease on Main stop, and a later RECLAIM
        // then puts Mimir over the game's still-existing lower window; the next Return must yield so that window shows again.
        val reclaimedOver = runtime.reclaimedOverGame(session.operation.value)
        val alreadyFront = completeExistingGameReturnAlreadyFront(parkedLease, reclaimedOver) {
            Log.i(
                "MimirCompanionRuntime",
                "existing_game_return_already_front op=${session.operation.value} pkg=$packageName display=${mirror.attempt.displayId}",
            )
            BottomUiReceipts.log.session(
                "return",
                session.operation.value,
                mirror.attempt.displayId,
                "existing_game_return_already_front",
                packageName,
            )
        }
        if (alreadyFront != null) return alreadyFront
        if (parkedLease == null) {
            // A reclaim of ours sits over the game's lower window: yield, as ever, so that window shows.
            val yielded = runtime.yieldForExistingSecondaryGame(
                parentOperationId = mirror.attempt.parentDispositionId,
                packageName = packageName,
                primaryOrigin = CompanionReturnCoordinator.currentPrimaryOrigin(),
            )
            BottomUiReceipts.log.session(
                "return",
                session.operation.value,
                mirror.attempt.displayId,
                if (yielded) "existing_game_return" else "existing_game_return_refused",
                packageName,
            )
            return yielded
        }
        if (parkedGameReturnJob?.isActive == true || MainGameParkingState.isRestoring(parkedLease)) return true

        val expectedGameOperation = session.operation
        val expectedGameId = session.gameId
        val expectedMirrorAttempt = mirror.attempt
        val primaryOrigin = CompanionReturnCoordinator.currentPrimaryOrigin()
        parkedGameReturnJob = CompanionProcessRuntime.processScope.launch {
            val resolvedTaskId = withContext(Dispatchers.IO) {
                runCatching {
                    CompanionProcessRuntime.services(activity).privilegedUtility.resolveTaskId(
                        packageName,
                        Display.DEFAULT_DISPLAY,
                    )
                }.getOrNull()
            }
            if (resolvedTaskId == null) {
                val released = CompanionProcessRuntime.releaseParkedGameIfGone(activity, parkedLease, stage = "resolve")
                if (!released) {
                    val liveness = CompanionProcessRuntime.services(activity).verifiedPackageLiveness(packageName)
                    Log.w(
                        "MimirCompanionRuntime",
                        "parked game focus unresolved op=${expectedGameOperation.value} pkg=$packageName " +
                            "task=${parkedLease.taskId} resolved=0 liveness=$liveness stage=resolve",
                    )
                }
                return@launch
            }
            if (resolvedTaskId != parkedLease.taskId) {
                Log.w(
                    "MimirCompanionRuntime",
                    "parked game focus unresolved op=${expectedGameOperation.value} pkg=$packageName " +
                        "task=${parkedLease.taskId} resolved=$resolvedTaskId stage=resolve",
                )
                return@launch
            }

            val yieldedRoot = withContext(Dispatchers.Main.immediate) {
                val currentGame = MimirSessionAuthority.state.value.gameSession
                val currentSnapshot = runtime.snapshot.value
                val currentMirror = currentSnapshot.disposition as? SecondaryDisplayDisposition.MirrorPresented
                val exactMirror = currentMirror?.attempt == expectedMirrorAttempt &&
                    currentSnapshot.mirrorActive &&
                    currentSnapshot.rendererOwnerId == rendererOwnerId &&
                    presentation?.isShowing == true &&
                    presentation?.currentRenderOperationId() == expectedMirrorAttempt.operationId
                val exactGame = currentGame?.operation == expectedGameOperation &&
                    currentGame.gameId == expectedGameId &&
                    currentGame.targetPackageName?.trim() == packageName &&
                    companionMainDualScreenGameActive(currentGame)
                if (!exactGame || !exactMirror ||
                    MainGameParkingState.currentFor(
                        expectedGameOperation,
                        expectedGameId,
                        packageName,
                    ) != parkedLease ||
                    !MainGameParkingState.beginRestoring(parkedLease)
                ) {
                    null
                } else if (!runtime.yieldForExistingSecondaryGame(
                        parentOperationId = expectedMirrorAttempt.parentDispositionId,
                        packageName = packageName,
                        primaryOrigin = primaryOrigin,
                    )
                ) {
                    MusicGameHandBack.endAndWake()
                    MainGameParkingState.cancelRestoring(parkedLease)
                    null
                } else {
                    val yielded = runtime.snapshot.value.disposition as? SecondaryDisplayDisposition.Yielded
                    yielded?.takeIf {
                        it.evidence == YieldEvidence.USER_REQUESTED_GAME_RETURN &&
                            it.lease?.packageName.equals(packageName, ignoreCase = true)
                    }?.lease?.operationId
                }
            }
            if (yieldedRoot == null) {
                withContext(Dispatchers.Main.immediate) {
                    MusicGameHandBack.endAndWake()
                    MainGameParkingState.cancelRestoring(parkedLease)
                }
                return@launch
            }

            // The Bottom has yielded and the lease is marked restoring: the hand-back to the game begins before the task is fronted, as HOME's
            // Resume does (homeResumePorts). Main is still resumed until the game's window is up, so without it the game's own audio focus request
            // would land while only UsageStats could say "game", and a loss that reaches Mimir's player would be recorded as another app's
            // permanent hold. A refused fronting ends it below; Main resuming or the window ends it otherwise (MusicGameHandBack).
            Log.i(
                "MimirCompanionRuntime",
                "return to a parked game: hand-back begins op=${expectedGameOperation.value} pkg=$packageName",
            )
            MusicGameHandBack.beginAndWake()

            val focusResult = withContext(Dispatchers.IO) {
                val currentGame = MimirSessionAuthority.state.value.gameSession
                val exactGame = currentGame?.operation == expectedGameOperation &&
                    currentGame.gameId == expectedGameId &&
                    currentGame.targetPackageName?.trim() == packageName &&
                    companionMainDualScreenGameActive(currentGame)
                if (!exactGame ||
                    !MainGameParkingState.isRestoring(parkedLease) ||
                    !runtime.isYieldedFor(yieldedRoot)
                ) {
                    PrivilegedTaskFocus.FAILED
                } else {
                    runCatching {
                        CompanionProcessRuntime.services(activity).privilegedUtility.focusTask(parkedLease.taskId)
                    }.getOrElse { PrivilegedTaskFocus.FAILED }
                }
            }
            BottomUiReceipts.log.session(
                "return",
                expectedGameOperation.value,
                expectedMirrorAttempt.displayId,
                if (focusResult == PrivilegedTaskFocus.FOCUSED) "parked_game_focused" else "parked_game_focus_failed",
                packageName,
            )
            if (focusResult == PrivilegedTaskFocus.FOCUSED) {
                Log.i(
                    "MimirCompanionRuntime",
                    "parked game focused op=${expectedGameOperation.value} pkg=$packageName " +
                        "task=${parkedLease.taskId} stage=focus",
                )
                // a dual-screen game that HOME parked is fronted from here too, and a fronted game is a new segment of its play time.
                if (parkedLease.homeParked) runCatching { MainGameHomePark.beginSegment(activity, parkedLease) }
                return@launch
            }

            withContext(Dispatchers.Main.immediate) {
                val currentGame = MimirSessionAuthority.state.value.gameSession
                val yielded = runtime.snapshot.value.disposition as? SecondaryDisplayDisposition.Yielded
                val exactYielded = currentGame?.operation == expectedGameOperation &&
                    currentGame.gameId == expectedGameId &&
                    currentGame.targetPackageName?.trim() == packageName &&
                    companionMainDualScreenGameActive(currentGame) &&
                    yielded?.evidence == YieldEvidence.USER_REQUESTED_GAME_RETURN &&
                    yielded.lease?.operationId == yieldedRoot &&
                    yielded.lease?.packageName.equals(packageName, ignoreCase = true) &&
                    runtime.isYieldedFor(yieldedRoot)
                MusicGameHandBack.endAndWake()
                MainGameParkingState.cancelRestoring(parkedLease)
                if (exactYielded) {
                    runtime.explicitReclaim(
                        reason = "main_game_focus_failed",
                        primaryOrigin = primaryOrigin,
                        returningFromPackage = packageName,
                    )
                }
                Log.w(
                    "MimirCompanionRuntime",
                    "parked game focus failed op=${expectedGameOperation.value} pkg=$packageName " +
                        "task=${parkedLease.taskId} result=$focusResult stage=focus",
                )
            }
        }
        return true
    }

    override fun launchRestoreGuard(lease: ReclaimLease): Boolean {
        if (secondaryDisplaySelector.snapshot.value.selectedId != lease.displayId) return false
        val target = displayManager
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.find { it.displayId == lease.displayId && it.isValid }
            ?: return false
        val intent = Intent(activity, CompanionReturnActivity::class.java)
            .putExtra(CompanionReturnCoordinator.EXTRA_OPERATION_ID, lease.operationId.toString())
            .putExtra(CompanionReturnHostContract.EXTRA_LAUNCH_MODE, CompanionReturnHostContract.LAUNCH_MODE_RESTORE_ONLY)
            .putExtra(CompanionReturnHostContract.EXTRA_RESTORE_REASON, lease.reason)
            .putExtra(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_DESTINATION, lease.primaryOrigin.destination)
            .putStringArrayListExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_KEYS,
                ArrayList(lease.primaryOrigin.arguments.keys),
            )
            .putStringArrayListExtra(
                CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_VALUES,
                ArrayList(lease.primaryOrigin.arguments.values),
            )
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = target.displayId }
        // Thor 2026-09-11 (SDK 33, Mimir is the HOME app): with windowDisablePreview on Mimir.ReturnGuard, a
        // recording of a reclaim still showed the launcher logo on the Bottom for about 100 ms. This launch also
        // asks for a solid-colour splash; the option does not exist before API 33.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            options.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
        }
        return runCatching {
            activity.startActivity(intent, options.toBundle())
            true
        }.onFailure { error ->
            Log.w(
                "MimirCompanionRuntime",
                "restore guard dispatch failed operation=${lease.operationId} reason=${lease.reason}",
                error,
            )
        }.getOrDefault(false)
    }

    private fun onSecondaryDisplaySnapshot(snapshot: SecondaryDisplaySnapshot) {
        if (!started) return
        val selected = snapshot.selectedId
        BottomUiReceipts.log.displaySelect(selected, snapshot.eligibleIds)
        if (selected == null) {
            runtime().displayRemoved()
            return
        }
        runtime().displayAvailable(selected)
        // D2 (QA 2026-09-11): the floor's auto-reclaim may be waiting for exactly this observation.
        CompanionReturnCoordinator.noteDisplayObserved(selected)
    }

    private fun observe(runtime: CompanionRuntimeCoordinator) {
        snapshotJob?.cancel()
        snapshotJob = activity.lifecycleScope.launch {
            runtime.snapshot.collect { snapshot -> reconcileSnapshot(snapshot) }
        }
    }

    private fun reconcileSnapshot(snapshot: CompanionRuntimeSnapshot) {
        _active.value = snapshot.mirrorActive && snapshot.rendererOwnerId == rendererOwnerId
        // Overlay topology is process-owned. This Activity contributes only the secondary mirror's dim state;
        // it must never dismiss the primary single-display M button when the runtime has NoDisplay.
        if (snapshot.displayId != null) overlayController?.setDimmed(snapshot.mirrorActive)
    }

    private fun runtime(): CompanionRuntimeCoordinator =
        runtimeCoordinator ?: CompanionReturnCoordinator.runtime().also { runtimeCoordinator = it }

    private fun handleFrameCommitted(candidate: MimirCompanionPresentation, committedId: java.util.UUID) {
        val operationId = OperationId.parseOrNull(committedId.toString()) ?: return
        val attempt = pendingRenderAttempts.remove(operationId) ?: return
        if (runtimeCoordinator?.snapshot?.value?.rendererOwnerId != rendererOwnerId) return
        val wasReclaim = runtime().isReclaimRoot(attempt.parentDispositionId)
        // Task A, floor first: bring the floor forward BEFORE presentationShown. Its publish is what the restore guard
        // finishes on, and once the guard is gone the display's next focusable window must be the floor, not the foreign
        // app (Thor 2026-09-11: YouTube stayed RESUMED with display 4's key focus after an edge-swipe reclaim).
        runtime().floorFrontReclaim(attempt.operationId, attempt.parentDispositionId, attempt.rendererOwnerId)
            ?.let { lease -> requestFloorFront(lease) }
        val accepted = runtime().presentationShown(
            attempt.operationId,
            attempt.parentDispositionId,
            attempt.rendererOwnerId,
        )
        if (!accepted) {
            BottomUiReceipts.log.presentation("dismiss", candidate.display.displayId, "frame_rejected", attempt.operationId.toString())
            candidate.dismiss()
            if (presentation === candidate) presentation = null
        } else {
            BottomUiReceipts.log.presentation(
                "ready",
                candidate.display.displayId,
                if (wasReclaim) "first_frame_after_reclaim" else "first_frame",
                attempt.operationId.toString(),
            )
            // The floor's startup cover is gone as of this frame: its Activity never leaves, so only the host that commits the
            // Presentation's first frame can write that exit. Then the stack is stated once, without the cover. A later `ready`
            // (a reclaim that recreated the window) finds the cover already gone and writes only the stack.
            BottomUiReceipts.log.coldStartCoverGone(candidate.display.displayId)
            BottomUiReceipts.log.stackSnapshot(candidate.display.displayId, BottomUiReceiptLog.REASON_READY)
        }
        if (accepted && wasReclaim) {
            CompanionState.noteReclaimSettled()
        }
    }

    /**
     * Task A, floor first. Brings Mimir's existing secondary-home floor to the front of the reclaimed display, so the next
     * focusable window there once the restore guard finishes is the floor, which hands keys to Main, and not the foreign
     * app. The start is [companionFloorFrontIntent] on the lease's display, issued only when [floorFrontPrecheckFailure]
     * finds nothing that could place a home start elsewhere. When the only thing missing is the floor task itself (an install
     * killed it), [planFloorFront] turns that refusal into a recreate, at most once per display per [FloorRecreateWindowMillis]
     * (F12), and only on a display where the floor has run before ([CompanionFloorDisplays]). One log line either way; result=started means the start
     * was accepted, not that the floor is on top, and started:recreated that the floor was absent and this start is what creates it.
     */
    private fun requestFloorFront(lease: ReclaimLease) {
        val intent = companionFloorFrontIntent(activity.packageName)
        val plan = planFloorFront(floorFrontPrecheckFailure(
            targetDisplayId = lease.displayId,
            selectedDisplayId = secondaryDisplaySelector.snapshot.value.selectedId,
            displayValid = displayManager
                ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                ?.any { it.displayId == lease.displayId && it.isValid } == true,
            resolvedFloorClass = resolvedFloorClassName(intent),
            floorTaskPresent = floorTaskPresent(),
        ), lease.displayId, floorSeenBefore = CompanionFloorDisplays.seen(activity, lease.displayId))
        val refusal = plan.refusal
        val result = if (refusal != null) {
            "failed:$refusal"
        } else {
            runCatching {
                val options = ActivityOptions.makeBasic().apply { launchDisplayId = lease.displayId }
                activity.startActivity(intent, options.toBundle())
                plan.started
            }.getOrElse { error -> "failed:${error::class.java.simpleName}" }
        }
        Log.i("MimirCompanionRuntime", floorFrontLogLine(lease.displayId, lease.reason, lease.operationId, result))
    }

    @Suppress("DEPRECATION")
    private fun resolvedFloorClassName(intent: Intent): String? = runCatching {
        activity.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.name
    }.getOrNull()

    /** The caller's own running tasks include its home tasks (AOSP 13 RunningTasks.java:105-123). */
    @Suppress("DEPRECATION")
    private fun floorTaskPresent(): Boolean = runCatching {
        activity.getSystemService(ActivityManager::class.java)
            ?.getRunningTasks(32)
            .orEmpty()
            .any { it.baseActivity?.className == CompanionSecondaryHomeActivity::class.java.name }
    }.getOrDefault(false)

    private inline fun runOnMain(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }
}

internal fun canReuseCompanionPresentation(
    currentDisplayId: Int?,
    currentShowing: Boolean,
    targetDisplayId: Int,
): Boolean = currentShowing && currentDisplayId == targetDisplayId

internal fun companionReclaimNeedsFreshPresentation(
    disposition: SecondaryDisplayDisposition,
    attempt: RenderAttempt,
    activeMainDualScreenGame: Boolean,
): Boolean {
    val reclaim = disposition as? SecondaryDisplayDisposition.ReclaimPending ?: return false
    return reclaim.expectedRender == attempt &&
        (activeMainDualScreenGame || reclaim.lease.reason == "main_game_exit")
}

/**
 * A Presentation created while the runtime is reclaiming the display (ReclaimPending) is a fresh reclaim window: it follows the restore guard's
 * ground, so it wears the mount cover over its first frame and fades it once its intro has run (Q7a, X-06). Any other creation (the cold start,
 * a replacement for another reason) wears what it wore before.
 */
internal fun companionFreshPresentationReclaims(disposition: SecondaryDisplayDisposition): Boolean = disposition is SecondaryDisplayDisposition.ReclaimPending

/** Main-game exit can reclaim a settled mirror or its exact Return-to-game handoff, never another Bottom host. */
internal fun companionMainGameExitReclaimAllowed(
    disposition: SecondaryDisplayDisposition,
    packageName: String,
): Boolean = when (disposition) {
    is SecondaryDisplayDisposition.MirrorPresented -> true
    is SecondaryDisplayDisposition.Yielded -> disposition.evidence == YieldEvidence.USER_REQUESTED_GAME_RETURN &&
        disposition.lease?.let {
            it.launchMode == CompanionReturnHostContract.LAUNCH_MODE_EXISTING_MAIN_GAME_RETURN &&
                it.packageName.equals(packageName, ignoreCase = true)
        } == true
    else -> false
}

internal fun canRetainCoveredCompanionPresentation(
    currentDisplayId: Int?,
    currentShowing: Boolean,
    runtimeDisplayId: Int?,
): Boolean = runtimeDisplayId != null &&
    canReuseCompanionPresentation(currentDisplayId, currentShowing, runtimeDisplayId)

/**
 * Task A, floor first: the start that brings the secondary-home floor forward. It is the shape the Thor's own framework
 * used to place this floor on display 4 (evidence thor-secondary-home-floor-2026-09-06: act=MAIN cat=[SECONDARY_HOME]
 * pkg=com.gruning.mimir.rewrite). No component, on purpose: in AOSP 13 (android13-release d27f9960) a start is typed HOME
 * only without one, unless the caller is the system, Recents or a resolver (ActivityRecord.java:2826), and a HOME start
 * reuses a home activity only on its own display area (ActivityStarter.java:2731-2736), where the floor's task is the
 * exact match (RootWindowContainer.java:412-418). An explicit component is typed STANDARD and can join a task by affinity.
 * Marked as Mimir's own start ([InternalHomeStartExtra]) so the floor's HOME routing never reads it as a HOME press.
 */
internal fun companionFloorFrontIntent(packageName: String): Intent =
    Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_SECONDARY_HOME)
        .setPackage(packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra(InternalHomeStartExtra, true)

/**
 * Task A, floor first: why the floor start must not be issued, or null when it may. In AOSP 13 a HOME start whose display
 * area cannot host this home is re-routed to the default display (TaskLaunchParamsModifier.java:463-468). Before N1
 * (2026-09-11) it could be added to MainActivity's home task there through their shared affinity
 * (RootWindowContainer.java:426-435). The floor now has its own affinity, but a home start still never reuses a task on
 * another display area (ActivityStarter.java:2731-2736), so it would open a floor task over Main. So the start
 * needs a non-default target that is still the selected, valid secondary display, an intent that resolves to this floor
 * and nothing else, and a floor task that already exists, i.e. one the platform itself placed as the secondary home. An absent floor is
 * still reported here as `floor_task_absent`; [planFloorFront] decides what the reclaim does about it (F12).
 */
internal fun floorFrontPrecheckFailure(
    targetDisplayId: Int,
    selectedDisplayId: Int?,
    displayValid: Boolean,
    resolvedFloorClass: String?,
    floorTaskPresent: Boolean,
): String? = when {
    targetDisplayId == Display.DEFAULT_DISPLAY -> "default_display"
    targetDisplayId != selectedDisplayId || !displayValid -> "display_unavailable"
    resolvedFloorClass != CompanionSecondaryHomeActivity::class.java.name -> "floor_unresolved"
    !floorTaskPresent -> "floor_task_absent"
    else -> null
}

/** Task A, floor first: the exact log line, on tag MimirCompanionRuntime. `op` is the reclaim root. */
internal fun floorFrontLogLine(displayId: Int, reason: String, rootOperationId: OperationId, result: String): String =
    "floor front requested display=$displayId reason=$reason op=$rootOperationId result=$result"

/**
 * ret1/ret2: whether Return to game on an existing Main dual-screen game must yield the mirror (and, for a parked game, switch focus back).
 * It must when the game is PARKED, and when MIMIR RECLAIMED the display over the game's still-existing lower window ([reclaimedOverGame]); only
 * when neither holds is the game already the Main foreground task with its own window gone (the emulator dropped it), and nothing yields.
 */
internal fun companionExistingGameReturnNeedsYield(parkedLease: MainGameParkingLease?, reclaimedOverGame: Boolean): Boolean =
    parkedLease != null || reclaimedOverGame

/**
 * ret1/ret2: the already-front half of Return to game. Returns null when the caller must yield (a parked game, or a reclaim of ours over the game);
 * otherwise runs [onAlreadyFront] (a log line, ids only) and returns true, having done nothing else: no yield, no dismissal after cover, no park.
 */
internal fun completeExistingGameReturnAlreadyFront(
    parkedLease: MainGameParkingLease?,
    reclaimedOverGame: Boolean,
    onAlreadyFront: () -> Unit,
): Boolean? {
    if (companionExistingGameReturnNeedsYield(parkedLease, reclaimedOverGame)) return null
    onAlreadyFront()
    return true
}
