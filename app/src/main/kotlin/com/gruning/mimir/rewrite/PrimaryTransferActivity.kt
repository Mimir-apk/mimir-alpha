package com.gruning.mimir.rewrite

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.gruning.mimir.rewrite.core.companionnative.PrimaryTransferHostContract
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class PrimaryTransferHostPhase {
    INITIAL,
    TARGET_DISPATCHED,
    HOST_COVERED,
    FAILED,
}

internal data class TransferHostCoverageEnvironment(
    val displayOn: Boolean,
    val deviceInteractive: Boolean,
    val keyguardLocked: Boolean,
    val callInterruptionActive: Boolean,
    val homeOrRecentsBecameVisible: Boolean = false,
) {
    val permitsCoverage: Boolean
        get() = displayOn && deviceInteractive && !keyguardLocked && !callInterruptionActive &&
            !homeOrRecentsBecameVisible

    companion object {
        val Permissive = TransferHostCoverageEnvironment(
            displayOn = true,
            deviceInteractive = true,
            keyguardLocked = false,
            callInterruptionActive = false,
        )
    }
}

internal fun homeTaskCountsAsLeaving(
    topPackage: String?,
    topClass: String?,
    selfPackage: String,
    homePackages: Set<String>,
    ignoredOwnActivities: Set<String>,
): Boolean = topPackage != null && topPackage in homePackages &&
    !(topPackage == selfPackage && topClass in ignoredOwnActivities)

/**
 * Visible HOME tasks, excluding the caller's own hosts on the other screen. On 2026-09-11, the
 * Bottom reclaim hosts appeared as new HOME tasks and falsely rejected a visibly covered Main transfer.
 * Mimir is a HOME package, so each display must exclude the other display's own host activities.
 */
@Suppress("DEPRECATION")
internal fun ComponentActivity.visibleHomeOrRecentsTaskIds(ignoredOwnActivities: Set<String>): Set<Int> {
    val homePackages = packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        PackageManager.MATCH_DEFAULT_ONLY,
    ).mapTo(mutableSetOf()) { it.activityInfo.packageName }
    if (homePackages.isEmpty()) return emptySet()
    return getSystemService(ActivityManager::class.java)
        ?.getRunningTasks(32)
        .orEmpty()
        .asSequence()
        .filterIndexed { index, task ->
            task.isVisibleCompat(index) && homeTaskCountsAsLeaving(
                topPackage = task.topActivity?.packageName,
                topClass = task.topActivity?.className,
                selfPackage = packageName,
                homePackages = homePackages,
                ignoredOwnActivities = ignoredOwnActivities,
            )
        }
        .map { it.taskId }
        .toSet()
}

private fun ActivityManager.RunningTaskInfo.isVisibleCompat(recencyIndex: Int): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
        isVisible
    } else {
        // API 30-31 expose no visibility bit; running tasks are recency ordered, so only the top task is safe.
        recencyIndex == 0
    }

/** Pure UUID-correlated primary-host lifecycle ledger shared by the Activity and JVM tests. */
internal data class PrimaryTransferHostExecutionState(
    val phase: PrimaryTransferHostPhase = PrimaryTransferHostPhase.INITIAL,
    val targetDispatchOperationId: UUID? = null,
    val targetPauseObserved: Boolean = false,
    val expectedTargetLeaveHint: Boolean = false,
    val userLeaveObserved: Boolean = false,
) {
    fun targetDispatched(operationId: UUID): PrimaryTransferHostExecutionState =
        if (phase != PrimaryTransferHostPhase.INITIAL) {
            this
        } else {
            copy(
                phase = PrimaryTransferHostPhase.TARGET_DISPATCHED,
                targetDispatchOperationId = operationId,
                targetPauseObserved = false,
                expectedTargetLeaveHint = true,
                userLeaveObserved = false,
            )
        }

    fun targetPaused(): PrimaryTransferHostExecutionState =
        if (phase == PrimaryTransferHostPhase.TARGET_DISPATCHED) {
            copy(targetPauseObserved = true, expectedTargetLeaveHint = false)
        } else {
            this
        }

    fun userLeft(): PrimaryTransferHostExecutionState =
        if (phase == PrimaryTransferHostPhase.INITIAL) {
            copy(userLeaveObserved = true)
        } else if (phase != PrimaryTransferHostPhase.TARGET_DISPATCHED) {
            this
        } else if (expectedTargetLeaveHint) {
            copy(expectedTargetLeaveHint = false)
        } else {
            copy(userLeaveObserved = true)
        }

    fun targetReturnedBeforeCoverage(): Boolean =
        phase == PrimaryTransferHostPhase.TARGET_DISPATCHED && targetPauseObserved

    fun claimHostCoverage(
        operationId: UUID,
        environment: TransferHostCoverageEnvironment,
    ): Pair<PrimaryTransferHostExecutionState, Boolean> =
        if (phase != PrimaryTransferHostPhase.TARGET_DISPATCHED ||
            targetDispatchOperationId != operationId ||
            !targetPauseObserved ||
            userLeaveObserved ||
            !environment.permitsCoverage
        ) {
            this to false
        } else {
            copy(
                phase = PrimaryTransferHostPhase.HOST_COVERED,
                targetDispatchOperationId = null,
                targetPauseObserved = false,
                expectedTargetLeaveHint = false,
                userLeaveObserved = false,
            ) to true
        }

    fun failed(): PrimaryTransferHostExecutionState = copy(
        phase = PrimaryTransferHostPhase.FAILED,
        targetDispatchOperationId = null,
        targetPauseObserved = false,
        expectedTargetLeaveHint = false,
        userLeaveObserved = false,
    )
}

/** Operation-owned display-0 host for certifying and clearing an app moved from screen 2 to Main. */
class PrimaryTransferActivity : MimirLocaleActivity() {
    // Follows the stored language when created, but is not recreated mid-handoff for a change: it lives for one transfer operation.
    override val recreatesWhenLanguageChanges: Boolean = false

    private var operationId: UUID? = null
    private var targetPackageName: String = ""
    private var execution = PrimaryTransferHostExecutionState()
    private var baselineVisibleHomeTaskIds: Set<Int> = emptySet()

    /** Flags of the target start this instance dispatched, for the covered line; null when this instance sent none. */
    private var dispatchedTargetFlags: Int? = null

    /** O12 SWAP back: closes this host, while still covered, once its transfer card clears. */
    private var coveredReturnJob: Job? = null
    private val delayedTargetLaunch = Runnable {
        if (isFinishing || execution.phase != PrimaryTransferHostPhase.INITIAL) return@Runnable
        operationId?.let(::dispatchTarget)
    }

    private val confirmationTimeout = Runnable {
        if (execution.phase == PrimaryTransferHostPhase.HOST_COVERED ||
            execution.phase == PrimaryTransferHostPhase.FAILED ||
            isFinishing
        ) {
            return@Runnable
        }
        operationId?.let(CompanionRunningAppState::cancelTransfer)
        execution = execution.failed()
        Log.w(TAG, "primary transfer timed out package=$targetPackageName operation=$operationId")
        finishWithoutAnimation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Never the input-method target (KBD-T): a focusable trampoline must not take a foreign app's SHOW_SOFT_INPUT.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        operationId = savedInstanceState?.getString(SAVED_OPERATION_ID)?.let(::parseOperationId)
            ?: parseOperationId(intent.getStringExtra(PrimaryTransferHostContract.EXTRA_OPERATION_ID))
        targetPackageName = savedInstanceState?.getString(SAVED_PACKAGE_NAME)
            ?: intent.getStringExtra(PrimaryTransferHostContract.EXTRA_TARGET_PACKAGE).orEmpty().trim()
        execution = restoreExecution(savedInstanceState)

        if (operationId == null || targetPackageName.isEmpty() || hostDisplayId() != Display.DEFAULT_DISPLAY) {
            operationId?.let(CompanionRunningAppState::cancelTransfer)
            Log.w(
                TAG,
                "invalid primary transfer host package=$targetPackageName operation=$operationId display=${hostDisplayId()}",
            )
            finishWithoutAnimation()
            return
        }
        baselineVisibleHomeTaskIds = savedInstanceState
            ?.getIntArray(SAVED_VISIBLE_HOME_TASK_IDS)
            ?.toSet()
            ?: visibleHomeOrRecentsTaskIds(
                setOf(CompanionReturnActivity::class.java.name, CompanionSecondaryHomeActivity::class.java.name),
            )
        if (execution.phase == PrimaryTransferHostPhase.HOST_COVERED) operationId?.let(::closeWhenTransferCardClears)
    }

    override fun onPostResume() {
        super.onPostResume()
        val operation = operationId ?: return
        when (execution.phase) {
            PrimaryTransferHostPhase.HOST_COVERED -> {
                val cleared = clearCompanionRunningAppTransfer(operation, targetPackageName)
                Log.i(TAG, "primary transfer returned package=$targetPackageName operation=$operation cleared=$cleared")
                finishWithoutAnimation()
                return
            }
            PrimaryTransferHostPhase.TARGET_DISPATCHED -> if (execution.targetReturnedBeforeCoverage()) {
                CompanionRunningAppState.cancelTransfer(operation)
                execution = execution.failed()
                Log.w(
                    TAG,
                    "primary target returned without covering host package=$targetPackageName operation=$operation",
                )
                finishWithoutAnimation()
                return
            }
            PrimaryTransferHostPhase.FAILED -> {
                finishWithoutAnimation()
                return
            }
            PrimaryTransferHostPhase.INITIAL -> Unit
        }
        if (execution.phase == PrimaryTransferHostPhase.INITIAL) {
            window.decorView.postDelayed(delayedTargetLaunch, PRE_DISPATCH_GUARD_MILLIS)
        }
        if (execution.phase == PrimaryTransferHostPhase.TARGET_DISPATCHED) {
            window.decorView.postDelayed(confirmationTimeout, PRIMARY_TRANSFER_COVERAGE_WINDOW_MILLIS)
        }
    }

    override fun onPause() {
        window.decorView.removeCallbacks(delayedTargetLaunch)
        //  a config change delivered inside the pre-dispatch guard window must NOT
        // abort a legitimate transfer — the activity recreates and continues. Matches the onStop/onDestroy guards.
        if (!isFinishing && !isChangingConfigurations && execution.phase == PrimaryTransferHostPhase.INITIAL) {
            operationId?.let(CompanionRunningAppState::cancelTransfer)
            execution = execution.failed()
            finishWithoutAnimation()
        } else if (!isFinishing) {
            execution = execution.targetPaused()
        }
        super.onPause()
    }

    override fun onUserLeaveHint() {
        val phaseBeforeHint = execution.phase
        execution = execution.userLeft()
        if (phaseBeforeHint == PrimaryTransferHostPhase.INITIAL && execution.userLeaveObserved) {
            window.decorView.removeCallbacks(delayedTargetLaunch)
            operationId?.let(CompanionRunningAppState::cancelTransfer)
            execution = execution.failed()
            finishWithoutAnimation()
        }
        super.onUserLeaveHint()
    }

    override fun onStop() {
        window.decorView.removeCallbacks(confirmationTimeout)
        window.decorView.removeCallbacks(delayedTargetLaunch)
        val operation = operationId
        if (operation != null && !isFinishing && !isChangingConfigurations &&
            execution.phase == PrimaryTransferHostPhase.TARGET_DISPATCHED
        ) {
            val environment = coverageEnvironment()
            val (coveredState, claimed) = execution.claimHostCoverage(operation, environment)
            if (claimed) {
                val confirmed = confirmCompanionRunningAppTransfer(operation, targetPackageName)
                execution = if (confirmed) coveredState else execution.failed()
                Log.i(
                    TAG,
                    "primary host covered package=$targetPackageName operation=$operation " +
                        "confirmed=$confirmed display=${hostDisplayId()} " +
                        "targetFlags=${dispatchedTargetFlags?.let { "0x" + Integer.toHexString(it) }}",
                )
                if (!confirmed) finishWithoutAnimation()
                if (confirmed) closeWhenTransferCardClears(operation)
            } else if (execution.targetPauseObserved) {
                CompanionRunningAppState.cancelTransfer(operation)
                execution = execution.failed()
                Log.w(
                    TAG,
                    "primary coverage rejected package=$targetPackageName operation=$operation " +
                        "displayOn=${environment.displayOn} interactive=${environment.deviceInteractive} " +
                        "keyguard=${environment.keyguardLocked} call=${environment.callInterruptionActive} " +
                        "homeOrRecents=${environment.homeOrRecentsBecameVisible}",
                )
                finishWithoutAnimation()
            }
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(SAVED_OPERATION_ID, operationId?.toString())
        outState.putString(SAVED_PACKAGE_NAME, targetPackageName)
        outState.putString(SAVED_EXECUTION_PHASE, execution.phase.name)
        outState.putString(SAVED_TARGET_DISPATCH_OPERATION, execution.targetDispatchOperationId?.toString())
        outState.putBoolean(SAVED_TARGET_PAUSE_OBSERVED, execution.targetPauseObserved)
        outState.putBoolean(SAVED_EXPECTED_TARGET_LEAVE_HINT, execution.expectedTargetLeaveHint)
        outState.putBoolean(SAVED_USER_LEAVE_OBSERVED, execution.userLeaveObserved)
        outState.putIntArray(SAVED_VISIBLE_HOME_TASK_IDS, baselineVisibleHomeTaskIds.toIntArray())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        coveredReturnJob?.cancel()
        window.decorView.removeCallbacks(confirmationTimeout)
        if (!isChangingConfigurations && execution.phase == PrimaryTransferHostPhase.HOST_COVERED) {
            operationId?.let { operation ->
                markCompanionRunningAppPrimaryHostOrphaned(operation, targetPackageName)
            }
        }
        super.onDestroy()
    }

    /**
     * O12 SWAP back (CompanionSwapTransitionOrder.kt): when the app moves to the Bottom, the landing clears this host's
     * transfer card. The host then runs its return path while still covered, so it is gone before Mimir's Main task comes
     * forward and its close is no part of that transition. Finishing an activity that is neither resumed nor pausing, and
     * no longer visible, prepares no transition (AOSP 13 ActivityRecord.java:3445-3451).
     */
    private fun closeWhenTransferCardClears(operation: UUID) {
        coveredReturnJob?.cancel()
        coveredReturnJob = lifecycleScope.launch {
            CompanionRunningAppState.running.first { app -> app?.packageName != targetPackageName }
            if (isFinishing || execution.phase != PrimaryTransferHostPhase.HOST_COVERED) return@launch
            val cleared = clearCompanionRunningAppTransfer(operation, targetPackageName)
            Log.i(
                TAG,
                "primary transfer returned while covered package=$targetPackageName operation=$operation cleared=$cleared",
            )
            finishWithoutAnimation()
        }
    }

    private fun dispatchTarget(operation: UUID) {
        val launchIntent = resolveTargetLaunchIntent()?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            // SWAP cuts on Main as it does on the Bottom. This start brings the app's
            // existing task to the front of display 0 (Thor 22:06:27.012: `New task: false`), and the
            // overridePendingTransition(0, 0) after it never stopped Main's ~340 ms slide (flg=0x30220000). AOSP 12+ reading, not checked on AYN's build: an app's custom animation does not apply
            // to a task transition, while NO_ANIMATION moves the task to the front with none, as the Bottom hosts do.
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        if (launchIntent == null) {
            CompanionRunningAppState.cancelTransfer(operation)
            execution = execution.failed()
            Log.w(TAG, "primary transfer target unavailable package=$targetPackageName operation=$operation")
            finishWithoutAnimation()
            return
        }
        if (!claimCompanionRunningAppPrimaryHostLaunch(operation, targetPackageName)) {
            execution = execution.failed()
            Log.w(TAG, "primary transfer host lost staging claim package=$targetPackageName operation=$operation")
            finishWithoutAnimation()
            return
        }
        val launchAccepted = runCatching {
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = Display.DEFAULT_DISPLAY }
            startActivity(launchIntent, options.toBundle())
            overridePendingTransition(0, 0)
            true
        }.onFailure {
            Log.w(TAG, "primary transfer target dispatch failed package=$targetPackageName operation=$operation", it)
        }.getOrDefault(false)
        if (launchAccepted) dispatchedTargetFlags = launchIntent.flags
        val hostArmed = launchAccepted &&
            beginCompanionRunningAppPrimaryHostDispatch(operation, targetPackageName)
        if (!hostArmed) {
            CompanionRunningAppState.cancelTransfer(operation)
            execution = execution.failed()
            if (launchAccepted) {
                Log.w(TAG, "primary transfer host could not arm package=$targetPackageName operation=$operation")
            }
            finishWithoutAnimation()
        } else {
            execution = execution.targetDispatched(operation)
        }
    }

    private fun coverageEnvironment(): TransferHostCoverageEnvironment {
        val audioMode = getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        return TransferHostCoverageEnvironment(
            displayOn = display?.state == Display.STATE_ON,
            deviceInteractive = getSystemService(PowerManager::class.java)?.isInteractive != false,
            keyguardLocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true,
            callInterruptionActive = audioMode == AudioManager.MODE_RINGTONE ||
                audioMode == AudioManager.MODE_IN_CALL ||
                audioMode == AudioManager.MODE_IN_COMMUNICATION,
            homeOrRecentsBecameVisible = visibleHomeOrRecentsTaskIds(
                setOf(CompanionReturnActivity::class.java.name, CompanionSecondaryHomeActivity::class.java.name),
            )
                .any { it !in baselineVisibleHomeTaskIds },
        )
    }

    private fun restoreExecution(savedState: Bundle?): PrimaryTransferHostExecutionState {
        if (savedState == null) return PrimaryTransferHostExecutionState()
        val phase = savedState.getString(SAVED_EXECUTION_PHASE)
            ?.let { runCatching { PrimaryTransferHostPhase.valueOf(it) }.getOrNull() }
            ?: PrimaryTransferHostPhase.INITIAL
        return PrimaryTransferHostExecutionState(
            phase = phase,
            targetDispatchOperationId = parseOperationId(savedState.getString(SAVED_TARGET_DISPATCH_OPERATION)),
            targetPauseObserved = savedState.getBoolean(SAVED_TARGET_PAUSE_OBSERVED, false),
            expectedTargetLeaveHint = savedState.getBoolean(SAVED_EXPECTED_TARGET_LEAVE_HINT, false),
            userLeaveObserved = savedState.getBoolean(SAVED_USER_LEAVE_OBSERVED, false),
        )
    }

    private fun finishWithoutAnimation() {
        // O12: the host closes with no transition on every API level. API 34 deprecates overridePendingTransition and
        // takes the close override before the activity closes; the Thor (API 33) keeps the call it has always used.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
            finish()
        } else {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    private fun parseOperationId(raw: String?): UUID? =
        raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun resolveTargetLaunchIntent(): Intent? {
        packageManager.getLaunchIntentForPackage(targetPackageName)?.let { return it }
        val query = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(targetPackageName)
        }
        val activity = packageManager.queryIntentActivities(query, 0).firstOrNull()?.activityInfo ?: return null
        return Intent(query).setClassName(activity.packageName, activity.name)
    }

    @Suppress("DEPRECATION")
    private fun hostDisplayId(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId else windowManager.defaultDisplay.displayId

    private companion object {
        const val TAG = "MimirPrimaryTransfer"
        const val SAVED_OPERATION_ID = "primaryTransfer.operationId"
        const val SAVED_PACKAGE_NAME = "primaryTransfer.packageName"
        const val SAVED_EXECUTION_PHASE = "primaryTransfer.executionPhase"
        const val SAVED_TARGET_DISPATCH_OPERATION = "primaryTransfer.targetDispatchOperation"
        const val SAVED_TARGET_PAUSE_OBSERVED = "primaryTransfer.targetPauseObserved"
        const val SAVED_EXPECTED_TARGET_LEAVE_HINT = "primaryTransfer.expectedTargetLeaveHint"
        const val SAVED_USER_LEAVE_OBSERVED = "primaryTransfer.userLeaveObserved"
        const val SAVED_VISIBLE_HOME_TASK_IDS = "primaryTransfer.visibleHomeTaskIds"
        const val PRE_DISPATCH_GUARD_MILLIS = 100L
    }
}
