@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package com.gruning.mimir.rewrite

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.animation.TimeInterpolator
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import com.gruning.mimir.rewrite.core.ui.BottomSurface
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptFormat
import com.gruning.mimir.rewrite.core.ui.BottomUiReceiptLog
import com.gruning.mimir.rewrite.core.ui.BottomUiReceipts
import com.gruning.mimir.rewrite.core.ui.MimirMotion
import com.gruning.mimir.rewrite.core.ui.MimirTheme
import com.gruning.mimir.rewrite.core.ui.MimirThemeId
import com.gruning.mimir.rewrite.core.ui.bottomUiReducedMotion
import com.gruning.mimir.rewrite.core.ui.MimirThemePalette
import com.gruning.mimir.rewrite.feature.companion.CompanionMannazCover
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MimirCompanionReturn"

/**
 * The restore guard's ground for the theme stored under [themeStorageKey].
 *
 * Every reclaim holds the guard on the Bottom for 108-294 ms before the lattice's first frame, and it was painted black
 * (window) under a `rgb(5, 6, 15)` decor and host view. It now paints the theme's `surfaceBase`, the stage's own ground
 * token: the middle stop of the signature tint `CompanionStage` lays over the scene (`CompanionSurface.kt`), and the
 * body stop of every field ramp that scene is painted on (`MimirAtmosphere.kt` `fieldTreatment`). Resolved as the
 * Bottom's Presentation resolves its theme (`MimirThemeId.fromStorageKey`, so an empty or unknown key is Default),
 * from the authored palette: appearance overrides retint only the accent system and leave surfaces authored
 * (`MimirAppearanceOverrides.kt`, `withAppearanceOverrides`). Forced opaque, because the guard window must stay opaque.
 */
/**
 * Q7a : the restore guard's content fades in on [MimirMotion.OverlayMs] over its opaque ground, instead of cutting (X-06: it
 * held about 308 ms on the Thor and cut in and out). Its exit is the Presentation's reclaim handoff cover, which sorts above it and fades out on
 * the same clock (`MimirCompanionPresentation.reclaimReveals`). Reduced motion keeps the cut.
 */
internal fun companionReturnGuardFadeMs(reducedMotion: Boolean): Long = if (reducedMotion) 0L else MimirMotion.OverlayMs.toLong()

/**
 * The restore guard's content: the SAME [CompanionMannazCover] the Presentation wears over its first
 * revealed frame (`MimirCompanionPresentation`, `reclaimReveals`), composed under the theme resolution it uses, so the guard and the handoff cover
 * are one image with nothing to cut: the 56 dp badge dead-centre on the theme's `surfaceBase`, no text. The window's opaque ground ([groundArgb],
 * the same token) stays beneath it, and the guard's OverlayMs content fade-in (Q7a) runs on the view this returns. The wordmark and its lines it
 * replaces cut to the badge at the handoff. `CompanionReturnGuardBadgeRenderTest` proves the guard's frame and the cover's pixel-identical at the
 * Thor Bottom's size; the two windows' bounds on the device are owed to it ([CompanionReturnActivity.enterRestoreGuardMode]).
 */
internal fun companionReturnGuardView(context: Context, themeStorageKey: String?, groundArgb: Int): FrameLayout =
    FrameLayout(context).apply {
        setBackgroundColor(groundArgb)
        addView(
            ComposeView(context).apply {
                setContent { MimirTheme(themeId = MimirThemeId.fromStorageKey(themeStorageKey)) { CompanionMannazCover() } }
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
    }

/** The receipt name of the guard content's fade-in; `cancelled` when the guard finishes before it ends. */
internal const val CompanionReturnGuardFade = "return-guard.fade"

internal fun companionReturnGuardGroundArgb(themeStorageKey: String?): Int =
    MimirThemePalette.forId(MimirThemeId.fromStorageKey(themeStorageKey))
        .surfaceBase
        .copy(alpha = 1f)
        .toArgb()

internal enum class ReturnHostActivityPhase {
    INITIAL,
    HOST_STARTED,
    TARGET_DISPATCHED,
    HOST_COVERED,
    RECLAIM_REQUESTED,
    FAILED,
}

/** Pure operation-correlated lifecycle/recreation ledger used by the Activity and JVM tests. */
internal data class ReturnHostExecutionState(
    val phase: ReturnHostActivityPhase = ReturnHostActivityPhase.INITIAL,
    val targetLaunchAttempted: Boolean = false,
    val targetDispatchOperationId: OperationId? = null,
    val targetPauseObserved: Boolean = false,
    val expectedTargetLeaveHint: Boolean = false,
    val userLeaveObserved: Boolean = false,
    val screenOffObserved: Boolean = false,
    val hostCoverageOperationId: OperationId? = null,
    val completionEmitted: Boolean = false,
    val settlementOperationId: OperationId? = null,
) {
    fun claimTargetLaunch(): Pair<ReturnHostExecutionState, Boolean> =
        if (targetLaunchAttempted) this to false else copy(targetLaunchAttempted = true) to true

    fun targetDispatched(operationId: OperationId): ReturnHostExecutionState = copy(
        phase = ReturnHostActivityPhase.TARGET_DISPATCHED,
        targetDispatchOperationId = operationId,
        targetPauseObserved = false,
        expectedTargetLeaveHint = true,
        userLeaveObserved = false,
        screenOffObserved = false,
        hostCoverageOperationId = null,
    )

    fun targetPaused(): ReturnHostExecutionState =
        if (phase == ReturnHostActivityPhase.TARGET_DISPATCHED) {
            copy(targetPauseObserved = true, expectedTargetLeaveHint = false)
        } else {
            this
        }

    fun userLeft(): ReturnHostExecutionState =
        if (phase == ReturnHostActivityPhase.HOST_STARTED) {
            copy(userLeaveObserved = true)
        } else if (phase != ReturnHostActivityPhase.TARGET_DISPATCHED) {
            this
        } else if (expectedTargetLeaveHint) {
            copy(expectedTargetLeaveHint = false)
        } else {
            copy(userLeaveObserved = true)
        }

    fun screenOff(): ReturnHostExecutionState =
        if (phase == ReturnHostActivityPhase.HOST_STARTED || phase == ReturnHostActivityPhase.TARGET_DISPATCHED) {
            copy(screenOffObserved = true)
        } else {
            this
        }

    fun claimHostCoverage(
        operationId: OperationId,
        environment: TransferHostCoverageEnvironment = TransferHostCoverageEnvironment.Permissive,
    ): Pair<ReturnHostExecutionState, Boolean> =
        if (phase != ReturnHostActivityPhase.TARGET_DISPATCHED ||
            targetDispatchOperationId != operationId ||
            !targetPauseObserved ||
            userLeaveObserved ||
            screenOffObserved ||
            !environment.permitsCoverage
        ) {
            this to false
        } else {
            copy(
                phase = ReturnHostActivityPhase.HOST_COVERED,
                targetDispatchOperationId = null,
                targetPauseObserved = false,
                expectedTargetLeaveHint = false,
                userLeaveObserved = false,
                screenOffObserved = false,
                hostCoverageOperationId = operationId,
            ) to true
        }

    fun claimHostReturn(operationId: OperationId): Pair<ReturnHostExecutionState, Boolean> =
        if (phase != ReturnHostActivityPhase.HOST_COVERED ||
            hostCoverageOperationId != operationId || completionEmitted
        ) {
            this to false
        } else {
            copy(
                hostCoverageOperationId = null,
                completionEmitted = true,
            ) to true
        }

    fun claimCompletion(): Pair<ReturnHostExecutionState, Boolean> =
        if (completionEmitted) this to false else copy(completionEmitted = true) to true

    fun isCorrelatedWith(operationId: OperationId): Boolean = when (phase) {
        ReturnHostActivityPhase.INITIAL,
        ReturnHostActivityPhase.HOST_STARTED,
        -> !targetLaunchAttempted &&
            targetDispatchOperationId == null &&
            !targetPauseObserved &&
            !expectedTargetLeaveHint &&
            !userLeaveObserved &&
            !screenOffObserved &&
            hostCoverageOperationId == null &&
            !completionEmitted &&
            settlementOperationId == null
        ReturnHostActivityPhase.TARGET_DISPATCHED ->
            targetLaunchAttempted &&
                targetDispatchOperationId == operationId &&
                hostCoverageOperationId == null &&
                !completionEmitted &&
                settlementOperationId == null
        ReturnHostActivityPhase.HOST_COVERED ->
            targetLaunchAttempted &&
                targetDispatchOperationId == null &&
                !targetPauseObserved &&
                !expectedTargetLeaveHint &&
                !userLeaveObserved &&
                !screenOffObserved &&
                hostCoverageOperationId == operationId &&
                !completionEmitted &&
                settlementOperationId == null
        ReturnHostActivityPhase.RECLAIM_REQUESTED ->
            targetDispatchOperationId == null &&
                !targetPauseObserved &&
                !expectedTargetLeaveHint &&
                !userLeaveObserved &&
                !screenOffObserved &&
                hostCoverageOperationId == null &&
                completionEmitted &&
                settlementOperationId != null
        ReturnHostActivityPhase.FAILED -> false
    }
}

/** A resumed, operation-correlated host dispatches immediately; no visual guard sleep gates a user tap. */
internal fun ReturnHostExecutionState.shouldDispatchTargetOnResume(): Boolean =
    phase == ReturnHostActivityPhase.HOST_STARTED

internal enum class ReturnHostDispositionDecision {
    KEEP,
    COMPLETE,
    FAIL_CLOSED,
}

internal fun ReturnHostExecutionState.dispositionDecision(
    hostOperationId: OperationId,
    snapshot: CompanionRuntimeSnapshot,
    recognizesOperation: (OperationId) -> Boolean,
): ReturnHostDispositionDecision {
    val settlement = settlementOperationId
    val watchedOperation = settlement ?: if (completionEmitted) {
        // hostReturned publishes its reclaim snapshot just before its beforeEffects callback stores
        // the minted settlement id. The synchronous caller will fail closed if that handoff fails.
        return ReturnHostDispositionDecision.KEEP
    } else {
        hostOperationId
    }
    val recovery = snapshot.disposition as? SecondaryDisplayDisposition.RecoveryNeeded
    if (recovery?.operationId == watchedOperation) return ReturnHostDispositionDecision.FAIL_CLOSED

    if (settlement != null) {
        val mirror = snapshot.disposition as? SecondaryDisplayDisposition.MirrorPresented
        if (snapshot.mirrorActive && mirror?.attempt?.parentDispositionId == settlement) {
            return ReturnHostDispositionDecision.COMPLETE
        }
    }
    return if (recognizesOperation(watchedOperation)) {
        ReturnHostDispositionDecision.KEEP
    } else {
        ReturnHostDispositionDecision.FAIL_CLOSED
    }
}

internal fun ReturnHostExecutionState.persistedRootIsCurrent(
    activityOperationId: OperationId,
    restoreGuard: Boolean,
    snapshot: CompanionRuntimeSnapshot,
    recognizesOperation: (OperationId) -> Boolean,
): Boolean {
    if (phase != ReturnHostActivityPhase.RECLAIM_REQUESTED) {
        return recognizesOperation(activityOperationId)
    }
    val settlement = settlementOperationId ?: return false
    val reclaim = snapshot.disposition as? SecondaryDisplayDisposition.ReclaimPending ?: return false
    if (reclaim.lease.operationId != settlement || !recognizesOperation(settlement)) return false
    return if (restoreGuard) {
        activityOperationId == settlement
    } else {
        reclaim.lease.parentHostOperationId == activityOperationId
    }
}

internal fun returnHostDestructionOperationId(
    activityOperationId: OperationId?,
    execution: ReturnHostExecutionState,
): OperationId? = if (execution.phase == ReturnHostActivityPhase.RECLAIM_REQUESTED) {
    execution.settlementOperationId ?: activityOperationId
} else {
    activityOperationId
}

/**
 * Tokened secondary return host. Every mode requires a UUID operation root and persists execution
 * phase across recreation. Starting this Activity, dispatching the target, and destroying the host are
 * never interpreted as display ownership or successful reclaim.
 */
class CompanionReturnActivity : MimirLocaleActivity() {
    // Follows the stored language when created, but is not recreated mid-handoff for a change: it lives for one return operation.
    override val recreatesWhenLanguageChanges: Boolean = false

    private var operationId: OperationId? = null
    private var primaryOrigin: PrimaryReturnOrigin? = null
    private var targetPackage = ""
    private var targetIntent: Intent? = null
    private var launchMode = CompanionReturnHostContract.LAUNCH_MODE_RESUME
    private var restoreReason = "secondary_restore_requested"
    private var execution = ReturnHostExecutionState()
    private var invalidOperation = false
    private var mirrorCompletionObserved = false

    /** The restore guard's content view, whose alpha is its fade-in (Q7a); null in host mode. Kept so a finish can cancel a fade still running. */
    private var guardView: View? = null
    private var dispositionJob: Job? = null

    // O12 SWAP up (CompanionSwapTransitionOrder.kt): a swap reclaim's guard finishes only after the transfer host on Main
    // has been paused by the move, so the app is no opening app of this display when its task goes to Main.
    private var swapUpGuardHold = false
    private val swapUpHostSettled = MutableStateFlow(false)
    private var swapUpHoldJob: Job? = null
    private var swapUpHostCallbacksRegistered = false
    private val swapUpHostCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) {
            if (activity is PrimaryTransferActivity) swapUpHostSettled.value = true
        }
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            if (activity is PrimaryTransferActivity) swapUpHostSettled.value = true
        }
    }
    private var screenOffReceiverRegistered = false
    private var baselineVisibleHomeTaskIds: Set<Int> = emptySet()
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> interruptActiveHost(
                    updated = execution.screenOff(),
                    reason = "secondary_host_screen_off",
                )
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never the input-method target (KBD-T): this host and its guard are focusable on display 4, where the vendor
        // routes the IME, and would take a foreign app's SHOW_SOFT_INPUT. See CompanionSecondaryHomeActivity.
        window?.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)

        launchMode = savedInstanceState?.getString(StateLaunchMode)
            ?: intent?.getStringExtra(CompanionReturnHostContract.EXTRA_LAUNCH_MODE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: CompanionReturnHostContract.LAUNCH_MODE_RESUME
        targetPackage = savedInstanceState?.getString(StateTargetPackage)
            ?: intent?.getStringExtra(CompanionReturnHostContract.EXTRA_TARGET_PACKAGE)?.trim().orEmpty()
        targetIntent = (savedInstanceState?.getParcelable(StateTargetIntent) as? Intent)
            ?: (intent?.getParcelableExtra(CompanionReturnHostContract.EXTRA_TARGET_INTENT) as? Intent)
        restoreReason = savedInstanceState?.getString(StateRestoreReason)
            ?: intent?.getStringExtra(CompanionReturnHostContract.EXTRA_RESTORE_REASON)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "secondary_restore_requested"
        execution = restoreExecution(savedInstanceState)
        mirrorCompletionObserved = savedInstanceState?.getBoolean(StateMirrorObserved, false) == true

        val savedOperationId = OperationId.parseOrNull(savedInstanceState?.getString(StateOperationId))
        if (savedInstanceState?.containsKey(StateOperationId) == true && savedOperationId == null) {
            Log.w(TAG, "persisted return host contained an invalid operation id")
            failClosed("Return request expired")
            return
        }
        val savedOrigin = readOrigin(savedInstanceState)
        val rawIntentOperationId = intent?.getStringExtra(CompanionReturnCoordinator.EXTRA_OPERATION_ID)
        val intentOperationId = OperationId.parseOrNull(rawIntentOperationId)
        if (savedOperationId == null && intentOperationId != null &&
            !CompanionReturnCoordinator.runtime().recognizes(intentOperationId)
        ) {
            // Process recovery is initialized by MimirApplication from the durable pending record. An Intent is
            // dispatch data, not recovery evidence, and must never fabricate a new root after process death.
            Log.w(TAG, "unrecognized return-host Intent operation=$intentOperationId")
            failClosed("Return request expired")
            return
        }
        val resolved = if (savedOperationId == null) {
            CompanionReturnCoordinator.resolveReturnOperation(
                rawOperationId = rawIntentOperationId,
                packageName = targetPackage,
                displayId = displayIdOrNull(),
                launchMode = launchMode,
            )
        } else {
            CompanionReturnCoordinator.ReturnOperationContext(savedOperationId, savedOrigin)
        }
        operationId = resolved?.operationId
        primaryOrigin = savedOrigin ?: resolved?.primaryOrigin ?: readOrigin(intent?.extras)

        val runtime = CompanionReturnCoordinator.runtime()
        if (savedOperationId != null &&
            !execution.persistedRootIsCurrent(
                activityOperationId = savedOperationId,
                restoreGuard = isRestoreGuard(),
                snapshot = runtime.snapshot.value,
                recognizesOperation = { runtime.recognizes(it) },
            )
        ) {
            val persistedRoot = execution.settlementOperationId
                .takeIf { execution.phase == ReturnHostActivityPhase.RECLAIM_REQUESTED }
                ?: savedOperationId
            Log.w(
                TAG,
                "persisted return host lost durable root host=$savedOperationId root=$persistedRoot",
            )
            failClosed("Return needs attention")
            return
        }

        val operation = operationId
        if (operation == null) {
            Log.w(TAG, "missing or stale return-host operation mode=$launchMode package=$targetPackage")
            failClosed("Return request expired")
            return
        }
        if (!execution.isCorrelatedWith(operation)) {
            Log.w(TAG, "return-host state token did not match operation=$operation phase=${execution.phase}")
            failClosed("Return request expired")
            return
        }
        if (isRestoreGuard()) {
            //  the guard is what the player sees until the lattice's first frame, so it wears
            // the ground of the theme the Bottom's Presentation is about to draw -- read from the same payload channel
            // that Presentation (and CompanionSecondaryHomeActivity) resolve their MimirTheme from.
            val ground = companionReturnGuardGroundArgb(CompanionState.payload.value.themeId)
            enterRestoreGuardMode(ground)
            // the content is the Presentation's own handoff cover, so the handoff is one image.
            val guard = companionReturnGuardView(this, CompanionState.payload.value.themeId, ground)
            setContentView(guard)
            guardView = guard
            // Receipt: the guard the Bottom shows between a covering app and the lattice's first frame:
            // the window's ground is opaque and up at once (`Mimir.ReturnGuard` is not a translucent theme, and the ground is what covers the
            // app's last frame); the guard's CONTENT (the Mannaz cover) fades in over it on OverlayMs. The guard's exit is not its own:
            // the Presentation sorts above this window on the Thor, so it wears the reclaim handoff cover over its first revealed frame and
            // fades that out on OverlayMs (MimirCompanionPresentation, `reclaimReveals`), while this activity finishes on mirror completion as it
            // always has. Under reduced motion the content cuts in, and the receipt says so.
            guardReceiptDisplay = display?.displayId ?: BottomUiReceiptFormat.NO_DISPLAY
            val reduced = bottomUiReducedMotion()
            BottomUiReceipts.log.surface(
                BottomSurface.RETURN_GUARD,
                guardReceiptDisplay,
                true,
                transition = if (reduced) null else CompanionReturnGuardFade,
                reason = if (reduced) BottomUiReceiptLog.REASON_REDUCED_MOTION else null,
            )
            guard.alpha = 0f
            fadeGuard(guard, toAlpha = 1f, reducedMotion = reduced) {}
        } else {
            enterHostMode()
            setContentView(buildHostView("Opening on screen 2..."))
            if (execution.phase == ReturnHostActivityPhase.INITIAL) {
                if (!CompanionReturnCoordinator.hostStarted(operation)) {
                    failClosed("Launch request expired")
                    return
                }
                execution = execution.copy(phase = ReturnHostActivityPhase.HOST_STARTED)
            }
            val visibleHomeTasksNow = visibleHomeOrRecentsTaskIds(
                setOf(MainActivity::class.java.name, PrimaryTransferActivity::class.java.name),
            )
            baselineVisibleHomeTaskIds = savedInstanceState
                ?.getIntArray(StateVisibleHomeTaskIds)
                ?.toSet()
                ?: visibleHomeTasksNow
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenOffReceiverRegistered = true
        }
        swapUpGuardHold = companionSwapUpGuardHoldsForHost(isRestoreGuard(), restoreReason)
        if (swapUpGuardHold && !swapUpHostCallbacksRegistered) {
            application.registerActivityLifecycleCallbacks(swapUpHostCallbacks)
            swapUpHostCallbacksRegistered = true
        }
        observeDisposition()
    }

    override fun onResume() {
        super.onResume()
        if (invalidOperation) return
        val operation = operationId ?: return
        if (isRestoreGuard()) {
            val claim = execution.claimCompletion()
            execution = claim.first
            if (!claim.second) return
            execution = execution.copy(
                phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
                settlementOperationId = operation,
            )
            if (!CompanionReturnCoordinator.restoreGuardForeground(operation)) {
                failClosed("Return request expired")
            }
            return
        }

        when (execution.phase) {
            ReturnHostActivityPhase.HOST_STARTED ->
                if (execution.shouldDispatchTargetOnResume()) launchTargetOnce(operation)
            ReturnHostActivityPhase.INITIAL -> Unit
            ReturnHostActivityPhase.TARGET_DISPATCHED -> {
                if (execution.targetPauseObserved) {
                    CompanionReturnCoordinator.launchFailed(
                        operation,
                        "secondary_target_returned_before_coverage",
                    )
                    Log.w(TAG, "target returned before host coverage operation=$operation package=$targetPackage")
                    failClosed("Launch returned before opening")
                }
            }
            ReturnHostActivityPhase.HOST_COVERED -> emitHostReturnedOnce(operation)
            else -> Unit
        }
    }

    override fun onPause() {
        //  a config change inside the pre-dispatch window must NOT fail-close a
        // legitimate return — the activity recreates and continues. Matches the onStop/onDestroy guards.
        if (!invalidOperation && !isChangingConfigurations && execution.phase == ReturnHostActivityPhase.HOST_STARTED) {
            operationId?.let {
                CompanionReturnCoordinator.launchFailed(it, "secondary_host_paused_before_dispatch")
            }
            execution = execution.copy(phase = ReturnHostActivityPhase.FAILED)
            invalidOperation = true
            finishWithoutTransition()
        }
        if (!isRestoreGuard()) execution = execution.targetPaused()
        super.onPause()
    }

    override fun onUserLeaveHint() {
        val phaseBeforeHint = execution.phase
        if (!isRestoreGuard()) execution = execution.userLeft()
        if (phaseBeforeHint == ReturnHostActivityPhase.HOST_STARTED && execution.userLeaveObserved) {
            operationId?.let {
                CompanionReturnCoordinator.launchFailed(it, "secondary_host_user_navigation_before_dispatch")
            }
            invalidOperation = true
            execution = execution.copy(phase = ReturnHostActivityPhase.FAILED)
            finishWithoutTransition()
        }
        super.onUserLeaveHint()
    }

    override fun onStop() {
        settleHostCoverageIfCorrelated()
        super.onStop()
    }

    /** The display the restore guard entered on, so its exit receipt names it even after the window is gone. */
    private var guardReceiptDisplay: Int = BottomUiReceiptFormat.NO_DISPLAY

    override fun onDestroy() {
        // A content fade still running when the guard finishes (mirror completion comes before OverlayMs on a warm reclaim) is cancelled, and its
        // receipt says so, before the surface's own exit receipt.
        guardView?.animate()?.cancel()
        guardView = null
        BottomUiReceipts.log.transitionEndIfRunning(CompanionReturnGuardFade, guardReceiptDisplay, "cancelled")
        BottomUiReceipts.log.surface(BottomSurface.RETURN_GUARD, guardReceiptDisplay, false)
        dispositionJob?.cancel()
        dispositionJob = null
        swapUpHoldJob?.cancel()
        swapUpHoldJob = null
        if (swapUpHostCallbacksRegistered) {
            application.unregisterActivityLifecycleCallbacks(swapUpHostCallbacks)
            swapUpHostCallbacksRegistered = false
        }
        if (screenOffReceiverRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffReceiverRegistered = false
        }
        if (!isChangingConfigurations && !invalidOperation && !mirrorCompletionObserved) {
            returnHostDestructionOperationId(operationId, execution)
                ?.let(CompanionReturnCoordinator::hostDestroyed)
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(StateOperationId, operationId?.toString())
        outState.putString(StateLaunchMode, launchMode)
        outState.putString(StateTargetPackage, targetPackage)
        targetIntent?.let { outState.putParcelable(StateTargetIntent, Intent(it)) }
        outState.putString(StateRestoreReason, restoreReason)
        outState.putString(StatePhase, execution.phase.name)
        outState.putBoolean(StateTargetLaunchAttempted, execution.targetLaunchAttempted)
        outState.putString(StateTargetDispatchOperationId, execution.targetDispatchOperationId?.toString())
        outState.putBoolean(StateTargetPauseObserved, execution.targetPauseObserved)
        outState.putBoolean(StateExpectedTargetLeaveHint, execution.expectedTargetLeaveHint)
        outState.putBoolean(StateUserLeaveObserved, execution.userLeaveObserved)
        outState.putBoolean(StateScreenOffObserved, execution.screenOffObserved)
        outState.putIntArray(StateVisibleHomeTaskIds, baselineVisibleHomeTaskIds.toIntArray())
        outState.putString(StateHostCoverageOperationId, execution.hostCoverageOperationId?.toString())
        outState.putBoolean(StateCompletionEmitted, execution.completionEmitted)
        outState.putString(StateSettlementOperationId, execution.settlementOperationId?.toString())
        outState.putBoolean(StateMirrorObserved, mirrorCompletionObserved)
        writeOrigin(outState, primaryOrigin)
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        Log.i(TAG, "ignored physical back operation=$operationId mode=$launchMode")
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isAndroidReturnKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                Log.i(TAG, "ignored physical return key operation=$operationId key=${KeyEvent.keyCodeToString(event.keyCode)}")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun launchTargetOnce(operation: OperationId) {
        val claim = execution.claimTargetLaunch()
        execution = claim.first
        if (!claim.second) return

        if (targetPackage.isEmpty()) {
            CompanionReturnCoordinator.launchFailed(operation, "secondary_launch_missing_package")
            execution = execution.copy(phase = ReturnHostActivityPhase.FAILED)
            finishWithoutTransition()
            return
        }

        runCatching {
            val target = if (launchMode == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
                val payload = targetIntent ?: throw IllegalStateException("secondary_game_payload_missing")
                val payloadPackage = payload.component?.packageName ?: payload.`package`
                if (payloadPackage != targetPackage) {
                    throw IllegalStateException("secondary_game_payload_package_mismatch")
                }
                Intent(payload)
            } else {
                resolveLaunchIntentForPackage(targetPackage)
                    ?: throw ActivityNotFoundException("Package is not launchable")
            }
            applyTargetFlags(target)
            startTargetActivity(target)
        }.onSuccess {
            if (CompanionReturnCoordinator.targetDispatched(operation)) {
                execution = execution.targetDispatched(operation)
                Log.i(TAG, "target dispatched operation=$operation package=$targetPackage mode=$launchMode")
            } else {
                failClosed("Launch request expired")
            }
        }.onFailure { error ->
            val reason = if (launchMode == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME &&
                error is IllegalStateException
            ) {
                "secondary_game_payload_invalid"
            } else if (error is ActivityNotFoundException) {
                "secondary_launch_not_found"
            } else {
                "secondary_launch_runtime_failed"
            }
            Log.w(TAG, "target dispatch failed operation=$operation package=$targetPackage", error)
            CompanionReturnCoordinator.launchFailed(operation, reason)
            execution = execution.copy(phase = ReturnHostActivityPhase.FAILED)
            finishWithoutTransition()
        }
    }

    private fun settleHostCoverageIfCorrelated() {
        if (invalidOperation || isChangingConfigurations || isRestoreGuard()) return
        val operation = operationId ?: return
        val environment = coverageEnvironment()
        val claim = execution.claimHostCoverage(operation, environment)
        execution = claim.first
        if (!claim.second) {
            if (execution.targetPauseObserved &&
                (execution.userLeaveObserved || execution.screenOffObserved || !environment.permitsCoverage)
            ) {
                CompanionReturnCoordinator.launchFailed(operation, "secondary_host_interrupted_before_coverage")
                Log.w(
                    TAG,
                    "host coverage rejected operation=$operation package=$targetPackage " +
                        "userLeave=${execution.userLeaveObserved} displayOn=${environment.displayOn} " +
                        "interactive=${environment.deviceInteractive} keyguard=${environment.keyguardLocked} " +
                        "call=${environment.callInterruptionActive} screenOff=${execution.screenOffObserved} " +
                        "homeOrRecents=${environment.homeOrRecentsBecameVisible}",
                )
                failClosed("Launch interrupted")
            }
            return
        }
        if (!CompanionReturnCoordinator.hostCovered(operation)) {
            failClosed("Return request expired")
            return
        }
        Log.i(TAG, "host covered operation=$operation package=$targetPackage mode=$launchMode")
    }

    private fun emitHostReturnedOnce(hostOperation: OperationId) {
        val claim = execution.claimHostReturn(hostOperation)
        execution = claim.first
        if (!claim.second) return
        val reclaim = CompanionReturnCoordinator.hostReturned(hostOperation) { minted ->
            primaryOrigin = minted.primaryOrigin
            execution = execution.copy(
                phase = ReturnHostActivityPhase.RECLAIM_REQUESTED,
                settlementOperationId = minted.operationId,
            )
        }
        if (reclaim == null) {
            // P14 / I5: this host's late return against a superseded root was silent — exactly the
            // orphan the I5 drawer/runtime deadline race can leave behind.
            Log.w(TAG, "host return refused operation=$hostOperation package=$targetPackage")
            failClosed("Return request expired")
            return
        }
        Log.i(
            TAG,
            "host returned host=$hostOperation reclaim=${reclaim.operationId} origin=${reclaim.primaryOrigin.destination}",
        )
    }

    private fun observeDisposition() {
        dispositionJob?.cancel()
        dispositionJob = lifecycleScope.launch {
            val runtime = CompanionReturnCoordinator.runtime()
            runtime.snapshot.collect { snapshot ->
                if (invalidOperation || isFinishing) return@collect
                val hostOperation = operationId ?: return@collect
                when (
                    execution.dispositionDecision(
                        hostOperationId = hostOperation,
                        snapshot = snapshot,
                        recognizesOperation = { runtime.recognizes(it) },
                    )
                ) {
                    ReturnHostDispositionDecision.KEEP -> Unit
                    ReturnHostDispositionDecision.COMPLETE -> {
                        mirrorCompletionObserved = true
                        finishRestoreGuardAfterSwapUpMove()
                    }
                    ReturnHostDispositionDecision.FAIL_CLOSED -> {
                        Log.w(
                            TAG,
                            "return host lost current root host=$hostOperation " +
                                "settlement=${execution.settlementOperationId} disposition=${snapshot.disposition}",
                        )
                        failClosed("Return request expired")
                    }
                }
            }
        }
    }

    /**
     * O12 SWAP up (CompanionSwapTransitionOrder.kt): a swap reclaim's guard stays until the transfer host on Main has
     * been paused by the move, bounded by [CompanionSwapUpGuardHoldMs]. Every other guard finishes on mirror completion
     * as before.
     */
    private fun finishRestoreGuardAfterSwapUpMove() {
        if (!swapUpGuardHold || swapUpHostSettled.value) {
            finishWithoutTransition()
            return
        }
        if (swapUpHoldJob != null) return
        swapUpHoldJob = lifecycleScope.launch {
            val hostPaused =
                withTimeoutOrNull(CompanionSwapUpGuardHoldMs) { swapUpHostSettled.first { settled -> settled } } != null
            Log.i(TAG, "swap up restore guard released operation=$operationId hostPaused=$hostPaused")
            if (!isFinishing && !invalidOperation) finishWithoutTransition()
        }
    }

    /** The guard content's fade-in on OverlayMs (Q7a); a cut under reduced motion. [onEnd] runs when the view is at [toAlpha]. */
    private fun fadeGuard(view: View, toAlpha: Float, reducedMotion: Boolean, onEnd: () -> Unit) {
        view.animate().cancel()
        val ms = companionReturnGuardFadeMs(reducedMotion)
        if (ms == 0L) {
            view.alpha = toAlpha
            onEnd()
            return
        }
        BottomUiReceipts.log.transitionBegin(CompanionReturnGuardFade, guardReceiptDisplay)
        view.animate()
            .alpha(toAlpha)
            .setDuration(ms)
            .setInterpolator(TimeInterpolator { fraction -> MimirMotion.Easing.transform(fraction) })
            .withEndAction {
                BottomUiReceipts.log.transitionEnd(CompanionReturnGuardFade, guardReceiptDisplay, "done")
                onEnd()
            }
            .start()
    }

    private fun interruptActiveHost(updated: ReturnHostExecutionState, reason: String) {
        if (updated == execution) return
        execution = updated
        val operation = operationId ?: return
        CompanionReturnCoordinator.launchFailed(operation, reason)
        Log.w(TAG, "host interrupted operation=$operation package=$targetPackage reason=$reason")
        failClosed("Launch interrupted")
    }

    private fun failClosed(message: String) {
        // P14: the terminal outcome of every failure edge in this Activity (§2.2) funnels through here,
        // and none of them had a line naming which operation/package actually failed closed.
        Log.w(TAG, "fail closed operation=$operationId package=$targetPackage message=$message")
        invalidOperation = true
        execution = execution.copy(phase = ReturnHostActivityPhase.FAILED)
        enterHostMode()
        setContentView(buildHostView(message))
        finishWithoutTransition()
    }

    private fun finishWithoutTransition() {
        if (!isFinishing) finish()
        overridePendingTransition(0, 0)
    }

    private fun applyTargetFlags(target: Intent) {
        var flags = target.flags
        if (launchMode != CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
            flags = flags and Intent.FLAG_ACTIVITY_CLEAR_TASK.inv()
            flags = flags and Intent.FLAG_ACTIVITY_NEW_DOCUMENT.inv()
        }
        when (launchMode) {
            CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME -> {
                // The dispatcher already built the emulator-specific task flags. The secondary host
                // transports that Intent and must not rewrite its ROM routing contract.
                target.flags = flags
            }
            CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER -> {
                target.flags = flags
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP -> {
                flags = flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK.inv()
                flags = flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT.inv()
                flags = flags and Intent.FLAG_ACTIVITY_SINGLE_TOP.inv()
                flags = flags and Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED.inv()
                target.flags = flags
                target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            else -> {
                flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
                flags = flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK.inv()
                target.flags = flags
                target.addFlags(
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
        }
    }

    private fun resolveLaunchIntentForPackage(packageName: String): Intent? {
        packageManager.getLaunchIntentForPackage(packageName)?.let { return it }
        val query = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        val matches: List<ResolveInfo> = packageManager.queryIntentActivities(query, PackageManager.MATCH_DEFAULT_ONLY)
        val activityName = matches.firstOrNull()?.activityInfo?.name ?: return null
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
            component = ComponentName(packageName, activityName)
        }
    }

    private fun startTargetActivity(target: Intent) {
        val displayId = displayIdOrNull()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && displayId != null && displayId >= 0) {
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
            startActivity(target, options.toBundle())
        } else {
            startActivity(target)
        }
    }

    private fun enterHostMode() {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    /**
     * Keep a real focused guard until the exact render acknowledgment; no alpha-zero ANR window. Flags, focus, opacity and timing are as they
     * were; only the paint is [groundArgb] ([companionReturnGuardGroundArgb]). The window's ground is OPAQUE and up at once (Q7a, W2L7 review #2:
     * `Mimir.ReturnGuard` is not a translucent theme, and the ground is what covers the app's last frame); only the content view fades in over it.
     */
    private fun enterRestoreGuardMode(groundArgb: Int) {
        window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            clearFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            )
            setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            setBackgroundDrawable(ColorDrawable(groundArgb))
            attributes = attributes.apply {
                alpha = 1f
                dimAmount = 0f
            }
            decorView?.setBackgroundColor(groundArgb)
            // the Presentation's window spans the whole display (1240x1080 on the Thor Bottom) while an
            // Activity's content fits the system-window insets (1240x1025 there), which would seat the guard's badge 12 dp above the cover's. Laid
            // out edge-to-edge, the two centre on the same display point. Device-owed: the JVM render proves only the composition.
            WindowCompat.setDecorFitsSystemWindows(this, false)
        }
    }

    /** Host mode's status view, on the ground it has always had; the restore guard's content is [companionReturnGuardView]. */
    private fun buildHostView(statusText: String): FrameLayout {
        val density = resources.displayMetrics.density
        return FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(5, 6, 15))
            addView(
                LinearLayout(this@CompanionReturnActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(TextView(this@CompanionReturnActivity).apply {
                        text = "M I M I R"
                        setTextColor(Color.rgb(212, 175, 105))
                        textSize = 24f
                        letterSpacing = 0.30f
                        gravity = Gravity.CENTER
                    })
                    addView(
                        TextView(this@CompanionReturnActivity).apply {
                            text = "THE WELL OF MEMORY"
                            setTextColor(Color.rgb(138, 156, 196))
                            textSize = 11f
                            letterSpacing = 0.28f
                            alpha = 0.85f
                            gravity = Gravity.CENTER
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = (6 * density).toInt() },
                    )
                    addView(
                        TextView(this@CompanionReturnActivity).apply {
                            text = statusText
                            setTextColor(Color.rgb(224, 228, 234))
                            textSize = 13f
                            alpha = 0.72f
                            gravity = Gravity.CENTER
                        },
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { topMargin = (24 * density).toInt() },
                    )
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    private fun restoreExecution(savedState: Bundle?): ReturnHostExecutionState {
        val phase = savedState?.getString(StatePhase)
            ?.let { name -> runCatching { ReturnHostActivityPhase.valueOf(name) }.getOrNull() }
            ?: ReturnHostActivityPhase.INITIAL
        return ReturnHostExecutionState(
            phase = phase,
            targetLaunchAttempted = savedState?.getBoolean(StateTargetLaunchAttempted, false) == true,
            targetDispatchOperationId = OperationId.parseOrNull(
                savedState?.getString(StateTargetDispatchOperationId),
            ),
            targetPauseObserved = savedState?.getBoolean(StateTargetPauseObserved, false) == true,
            expectedTargetLeaveHint = savedState?.getBoolean(StateExpectedTargetLeaveHint, false) == true,
            userLeaveObserved = savedState?.getBoolean(StateUserLeaveObserved, false) == true,
            screenOffObserved = savedState?.getBoolean(StateScreenOffObserved, false) == true,
            hostCoverageOperationId = OperationId.parseOrNull(
                savedState?.getString(StateHostCoverageOperationId),
            ),
            completionEmitted = savedState?.getBoolean(StateCompletionEmitted, false) == true,
            settlementOperationId = OperationId.parseOrNull(savedState?.getString(StateSettlementOperationId)),
        )
    }

    private fun readOrigin(bundle: Bundle?): PrimaryReturnOrigin? {
        val destination = bundle?.getString(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_DESTINATION)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val keys = bundle.getStringArrayList(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_KEYS).orEmpty()
        val values = bundle.getStringArrayList(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_VALUES).orEmpty()
        val arguments = keys.zip(values).toMap()
        return PrimaryReturnOrigin(destination, arguments)
    }

    private fun writeOrigin(bundle: Bundle, origin: PrimaryReturnOrigin?) {
        if (origin == null) return
        bundle.putString(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_DESTINATION, origin.destination)
        bundle.putStringArrayList(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_KEYS, ArrayList(origin.arguments.keys))
        bundle.putStringArrayList(CompanionReturnHostContract.EXTRA_PRIMARY_ORIGIN_VALUES, ArrayList(origin.arguments.values))
    }

    private fun isRestoreGuard(): Boolean =
        launchMode == CompanionReturnHostContract.LAUNCH_MODE_RESTORE_ONLY ||
            launchMode == CompanionReturnHostContract.LAUNCH_MODE_HOME_RECLAIM

    private fun coverageEnvironment(): TransferHostCoverageEnvironment {
        val audioMode = getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        val homeOrRecentsBecameVisible = visibleHomeOrRecentsTaskIds(
            setOf(MainActivity::class.java.name, PrimaryTransferActivity::class.java.name),
        )
            .any { it !in baselineVisibleHomeTaskIds }
        return TransferHostCoverageEnvironment(
            // Thor's companion panel can be visibly active while the OEM reports the display and global power
            // state as off (OLED saver). Those signals are valid on display 0, not as screen-2 ownership vetoes.
            displayOn = true,
            deviceInteractive = true,
            keyguardLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
            callInterruptionActive = audioMode == AudioManager.MODE_RINGTONE ||
                audioMode == AudioManager.MODE_IN_CALL ||
                audioMode == AudioManager.MODE_IN_COMMUNICATION,
            homeOrRecentsBecameVisible = homeOrRecentsBecameVisible,
        )
    }

    private fun isAndroidReturnKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_ESCAPE ||
            keyCode == KeyEvent.KEYCODE_BUTTON_MODE ||
            keyCode == KeyEvent.KEYCODE_HOME

    private fun displayIdOrNull(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId else windowManager.defaultDisplay?.displayId

    companion object {
        private const val StateOperationId = "companion_return.operation_id"
        private const val StateLaunchMode = "companion_return.launch_mode"
        private const val StateTargetPackage = "companion_return.target_package"
        private const val StateTargetIntent = "companion_return.target_intent"
        private const val StateRestoreReason = "companion_return.restore_reason"
        private const val StatePhase = "companion_return.phase"
        private const val StateTargetLaunchAttempted = "companion_return.target_launch_attempted"
        private const val StateTargetDispatchOperationId = "companion_return.target_dispatch_operation_id"
        private const val StateTargetPauseObserved = "companion_return.target_pause_observed"
        private const val StateExpectedTargetLeaveHint = "companion_return.expected_target_leave_hint"
        private const val StateUserLeaveObserved = "companion_return.user_leave_observed"
        private const val StateScreenOffObserved = "companion_return.screen_off_observed"
        private const val StateVisibleHomeTaskIds = "companion_return.visible_home_task_ids"
        private const val StateHostCoverageOperationId = "companion_return.host_coverage_operation_id"
        private const val StateCompletionEmitted = "companion_return.completion_emitted"
        private const val StateSettlementOperationId = "companion_return.settlement_operation_id"
        private const val StateMirrorObserved = "companion_return.mirror_observed"
    }
}
