package com.gruning.mimir.rewrite

import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.Display
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SWAP back: Main cuts the way the Bottom does.
 *
 * Round-5 rank 4d recorded Main's ~460 ms slide when the leftover Main copy was removed (`am stack remove`) while it was
 * still the resumed app on display 0. In AOSP 13, finishing a resumed activity prepares a close transition
 * (ActivityRecord.java:3407), and so does resuming the next activity after a finishing one that was not moved without
 * animation (TaskFragment.java:1286-1296). So before the first removal the eviction brings Mimir's own Main task forward
 * with a start that reuses the task and carries FLAG_ACTIVITY_NO_ANIMATION (ActivityStarter.java:2541, 2822;
 * Task.java:5576-5579), waits for MainActivity to resume, and only then removes the copy, which is paused and hidden by
 * then (ActivityRecord.java:3445-3451). `ActivityManager.moveTaskToFront` is no lever: it always asks for an animated move
 * (ActivityTaskSupervisor.java:1474).
 *
 * The start names the top activity of the Main task rather than MainActivity. MainActivity is singleTask, and a start of
 * it clears everything above it (ActivityStarter.java:2260-2276), which would take the transfer host down through
 * onDestroy. A REORDER_TO_FRONT start of the host itself only moves it forward and hands it the intent
 * (ActivityStarter.java:2297-2309), so the host still resumes and closes through its own return path.
 */
internal object CompanionExchangeInFlight {
    /** True for the whole run of a two-app exchange. Main-thread state: the exchange and the process runtime run there. */
    var active: Boolean = false
}

internal const val CompanionMainFrontResumeTimeoutMs = 2_000L

/** Bound on waiting for a covered transfer host to close before Mimir's Main task comes forward. */
internal const val CompanionMainFrontHostWaitMs = 1_000L

internal const val CompanionMainFrontHostPollMs = 32L

internal sealed interface CompanionMainFrontPlan {
    /** Start [topActivityClass], the top of Mimir's Main task, with [companionMainFrontLaunchFlags] on display 0. */
    data class Reorder(val topActivityClass: String) : CompanionMainFrontPlan

    data class Skip(val reason: String) : CompanionMainFrontPlan
}

/**
 * NO_USER_ACTION: the start must not pause the app on Main as a user leave. A user-leaving pause lets an app with
 * auto-enter picture-in-picture shrink into a PiP window on Main (ActivityStarter.java:2461;
 * TaskFragment.java:1587-1591, 1602-1604).
 */
internal fun companionMainFrontLaunchFlags(): Int =
    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION or
        Intent.FLAG_ACTIVITY_NO_USER_ACTION

/**
 * Whether the eviction may bring Mimir's Main task forward. Never during an exchange: leg 3 is about to put the former
 * Bottom app on Main, and a Mimir start could land over it. Not when MainActivity has already resumed, and only for a top
 * activity whose intent delivery is known to be inert (MainActivity's extras-less onNewIntent, the host's default one).
 */
internal fun companionMainFrontPlan(
    exchangeInFlight: Boolean,
    mainStopped: Boolean,
    mainTaskTopActivityClass: String?,
    knownTopActivityClasses: Set<String>,
): CompanionMainFrontPlan = when {
    exchangeInFlight -> CompanionMainFrontPlan.Skip("exchange_in_flight")
    !mainStopped -> CompanionMainFrontPlan.Skip("main_already_resumed")
    mainTaskTopActivityClass.isNullOrBlank() -> CompanionMainFrontPlan.Skip("main_task_missing")
    mainTaskTopActivityClass !in knownTopActivityClasses -> CompanionMainFrontPlan.Skip("main_task_top_unknown")
    else -> CompanionMainFrontPlan.Reorder(mainTaskTopActivityClass)
}

/**
 * The front step, run before the first removal. Returns its value for the "main copy evicted" line; the caller removes the
 * copy whatever this returns, and a wait that runs out is logged as `mainResumed=false`.
 */
internal suspend fun bringCompanionMainForward(context: Context, exchangeInFlight: Boolean): String {
    // A covered transfer host closes itself once the landing clears its card (PrimaryTransferActivity
    // closeWhenTransferCardClears). Waiting for it keeps the host's own close out of this front transition, where its
    // TRANSIT_CLOSE would make getTransitCompatType pick a task close (CompanionSwapTransitionOrder.kt).
    val hostGone = exchangeInFlight || withTimeoutOrNull(CompanionMainFrontHostWaitMs) {
        while (companionMainTaskTopActivityClass(context) == PrimaryTransferActivity::class.java.name) {
            delay(CompanionMainFrontHostPollMs)
        }
    } != null
    val plan = companionMainFrontPlan(
        exchangeInFlight = exchangeInFlight,
        mainStopped = PrimaryActivityObservationState.stopped.value,
        mainTaskTopActivityClass = companionMainTaskTopActivityClass(context),
        knownTopActivityClasses = setOf(MainActivity::class.java.name, PrimaryTransferActivity::class.java.name),
    )
    if (plan is CompanionMainFrontPlan.Skip) return "skipped:${plan.reason}"
    plan as CompanionMainFrontPlan.Reorder
    val flags = companionMainFrontLaunchFlags()
    val lever = "reorder top=${plan.topActivityClass.substringAfterLast('.')} flags=0x${Integer.toHexString(flags)} " +
        "hostGone=$hostGone"
    val started = runCatching {
        context.startActivity(
            Intent().setClassName(context.packageName, plan.topActivityClass).addFlags(flags),
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
        )
    }.isSuccess
    if (!started) return "$lever started=false"
    val resumed = withTimeoutOrNull(CompanionMainFrontResumeTimeoutMs) {
        PrimaryActivityObservationState.stopped.first { stopped -> !stopped }
    } != null
    // Round 5b (Thor 5113d5e4, 2 of 2): MainActivity resuming is not the reorder's transition having run. The copy
    // removed about 70 ms after the resume was still visible, and its close turned the pending transition into a task
    // close, Home sliding in from the left. The caller removes the copy only after this returns, so wait until the system
    // reports Mimir's Main task visible, which is committed visibility.
    var visibility: Boolean? = false
    withTimeoutOrNull(CompanionMainFrontVisibleTimeoutMs) {
        while (true) {
            visibility = companionMainTaskVisible(context)
            if (visibility != false) break
            delay(CompanionMainFrontVisiblePollMs)
        }
    }
    // The wait also ends when the task cannot be read; that must not print as visible. `false` means the wait ran out.
    val mainVisible = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2 -> "unobservable"
        else -> visibility?.toString() ?: "unreadable"
    }
    return "$lever mainResumed=$resumed mainVisible=$mainVisible"
}

/** Bound on waiting for the system to report Mimir's Main task visible before the copy is removed. */
internal const val CompanionMainFrontVisibleTimeoutMs = 2_000L

internal const val CompanionMainFrontVisiblePollMs = 16L

/**
 * Whether the system reports Mimir's Main task visible. TaskInfo.isVisible is Task.hasVisibleChildren, which reads each
 * ActivityRecord's committed visibility, so it turns true only once a pending transition that shows the task has run
 * (AOSP 13 Task.java:3473, 4329-4334; ActivityRecord.java:4954-4967). Null where it cannot be read: before API 32, or
 * with no Main task.
 */
@Suppress("DEPRECATION")
internal fun companionMainTaskVisible(context: Context): Boolean? = runCatching {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S_V2) return@runCatching null
    context.getSystemService(ActivityManager::class.java)
        ?.getRunningTasks(32)
        .orEmpty()
        .firstOrNull { task -> task.baseActivity?.className == MainActivity::class.java.name }
        ?.isVisible
}.getOrNull()

/** The top activity of Mimir's Main task, read from the caller's own running tasks; null when there is no Main task. */
@Suppress("DEPRECATION")
private fun companionMainTaskTopActivityClass(context: Context): String? = runCatching {
    context.getSystemService(ActivityManager::class.java)
        ?.getRunningTasks(32)
        .orEmpty()
        .firstOrNull { task -> task.baseActivity?.className == MainActivity::class.java.name }
        ?.topActivity
        ?.takeIf { top -> top.packageName == context.packageName }
        ?.className
}.getOrNull()

/**
 * What the process must do with the display-0 copy of an app that just landed on the second screen.
 *
 * [Evict] names the one package whose Main task has to go, and the display it lives on; [Skip] names
 * why nothing may be touched. A `Skip` is silent by design — it is the ordinary outcome of every plain
 * Bottom launch, and logging it would put a line on the ordinary path.
 */
/**
 * How many display-0 copies one swap may evict before it stops and says a copy remains.
 *
 * Device result, Thor, Mimir `6592f41d` 11:13:18: a single eviction succeeded
 * (`resolveTask ... display=0 taskId=2317` -> `removeTask 2317 REMOVED`) and display 0 still showed
 * YouTube, because the device carried TWO YouTube tasks there -- a stale copy from an earlier glitch
 * plus the one that swap had just orphaned. `resolveTaskId` answers with the first visible task, so
 * clearing the display takes one pass per copy. Three is the bound: it recovers the observed
 * two-copy state with a pass to spare, and a resolver that keeps answering can never turn one swap
 * into an unbounded run of privileged removals.
 */
internal const val CompanionMainCopyEvictionLimit = 3

/**
 * How many times, and how far apart, the sweep reads display 0 again when it answers with the task the sweep just removed.
 *
 * Device result, Thor 8804787e (O12 round 5b), run 1: task 2815 was removed at 32.105 and answered twice more, at 32.308
 * and 32.392, and each answer was removed again and spent a pass. `am stack remove` returns 0 whatever the removal did
 * (ActivityManagerShellCommand.java:2844-2848 -> ActivityTaskManagerService.java:1991-2004). The removal finishes a
 * resumed or visible activity and destroys any other (Task.java:1600-1605); both complete with the app, and the task
 * stays listed until its last activity is gone. A second removal skips activities that are already finishing
 * (Task.java:1594-1596), so it does nothing.
 */
internal const val CompanionMainCopyRemovalSettleChecks = 8

internal const val CompanionMainCopyRemovalSettlePollMs = 100L

internal sealed interface CompanionMainCopyEviction {
    /** Remove [packageName]'s task on [fromDisplayId] — never the freshly landed secondary task. */
    data class Evict(
        val packageName: String,
        val fromDisplayId: Int = Display.DEFAULT_DISPLAY,
    ) : CompanionMainCopyEviction

    data class Skip(val reason: String) : CompanionMainCopyEviction
}

/**
 * **The Main→Bottom swap leaves two copies of the app; this decides which one is orphaned.**
 *
 * Reproduced on the Thor 2026-09-11 (Mimir `a8544b36`, 11:00:32 local), YouTube on Main, M menu →
 * SWAP: `swap plan=MAIN_TO_BOTTOM bottomHost=null mainRunning=com.google.android.youtube`, then
 * `reverse running app transfer dispatched … targetDisplay=4` and `v=23 event=HostCovered
 * LaunchPending->Yielded`, with `MimirSession: … primaryApp=ENDED`. After that `am stack list` showed
 * YouTube tasks on BOTH displays — `displayId=4` **and** `displayId=0`, whose top activity was still
 * `com.google.android.youtube/.app.honeycomb.Shell`. Mimir's Main session had ended, so the Main screen
 * kept showing an app Mimir no longer manages. That is the glitch the regression report described.
 *
 * The mechanism is the launch mode, not the app: a screen transfer
 * ([CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER], minted by
 * `AndroidSecondaryTaskRegistry.requestSwap` → `launchOnDisplay` for a secondary target) starts a
 * **fresh** instance on the second display, and a standard-launch-mode app's display-0 task simply
 * survives it. The reverse direction ([CompanionReturnHostContract.LAUNCH_MODE_RESUME]) resumes the one
 * existing task and leaves a single copy, so only this direction has a copy to evict.
 *
 * Everything else must be left alone, and that is the whole reason this is a policy rather than an
 * `if` at the call site:
 *  - a plain Bottom launch ([CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP]) never had a Main
 *    copy to begin with — evicting on it would kill an unrelated task the player put there;
 *  - a Bottom game ([CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME]) is the same, and the
 *    runtime's landing hook already returns before this is consulted;
 *  - a Main GAME session for the same package is live work on display 0 — [mainGamePackage] carries it
 *    in as an input so the decision, not the caller, refuses (fail-closed: any Main-placed game session
 *    for that package blocks, whatever its phase);
 *  - [secondaryDisplayId] is checked against [Display.DEFAULT_DISPLAY] so that a degenerate state in
 *    which the "secondary" display resolves to 0 can never make this evict the task that just landed.
 *
 * @param mainGamePackage `MimirSessionAuthority.state.value.gameSession`'s `targetPackageName` when
 *   that session's placement is `MAIN`, else null. Defaulted so the three-argument form in the brief
 *   stays callable.
 */
internal fun companionMainCopyEvictionDecision(
    launchMode: String,
    yieldedPackage: String,
    secondaryDisplayId: Int,
    mainGamePackage: String? = null,
): CompanionMainCopyEviction {
    val packageName = yieldedPackage.trim()
    if (packageName.isEmpty()) return CompanionMainCopyEviction.Skip("package_missing")
    if (launchMode.trim() != CompanionReturnHostContract.LAUNCH_MODE_SCREEN_TRANSFER) {
        return CompanionMainCopyEviction.Skip("not_screen_transfer")
    }
    if (secondaryDisplayId == Display.DEFAULT_DISPLAY) {
        return CompanionMainCopyEviction.Skip("secondary_is_default_display")
    }
    if (mainGamePackage?.trim()?.equals(packageName, ignoreCase = true) == true) {
        return CompanionMainCopyEviction.Skip("main_game_active")
    }
    return CompanionMainCopyEviction.Evict(
        packageName = packageName,
        fromDisplayId = Display.DEFAULT_DISPLAY,
    )
}
