package com.gruning.mimir.rewrite

import android.view.Display
import com.gruning.mimir.rewrite.core.launch.EmulatorSeedRegistry
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Quick Launch closes an app's copy
 * on Main, so the app reopens fresh on the Bottom.
 *
 * The cause, proven from the log: DuckStation's RetroAchievements link opened Chrome on Main (12:44). At 19:29 Quick Launch
 * moved that same window to display 4 without recreating it. From then on every tap logged "isInputMethodClientFocus: display
 * ID mismatch. from client: 0 from window: 4" and Android refused the keyboard before Mimir's IME was asked. A window born on
 * display 0 cannot take an input method on display 4, so the Main task has to go before the app is started on the Bottom.
 *
 * Quick Launch only (the plan's `clearMainCopy`): an app tile, the recent rail, a tab re-tap, the HOME-park resume and the debug hook
 * launch as they always did. It runs on the launch's off-main work stage, after the plan is persisted and before the host that starts
 * the app exists ([CompanionSecondaryLaunchCoordinator.beforeDispatch]). Every step goes through the vendor gateway's two existing,
 * fail-closed operations: [PrivilegedTaskCloseGateway.resolveTaskOutcome] (a task id for the package on display 0, or a proven
 * nothing) and [PrivilegedTaskCloseGateway.removeTask] (an Int; no package, path or string reaches the command). A removal is
 * believed only when a later resolve answers [VendorTaskOutcome.NoTask]; `am stack remove` answers before the task has gone.
 *
 * The launch has [BottomLaunchBudgetMs] from the tap to reach its target, and this stage owns the front of it. It is a deadline, not a
 * target: each gateway call is waited for only as long as the deadline allows ([BottomMainCopyCallRunner]), so a gateway that takes
 * two seconds a call costs the launch the time left and not a second more.
 *
 * When the gateway is not ready, or the removal cannot be proven, the app still launches exactly as before. The player is told on the
 * Bottom that the keyboard may not work ([BottomMainCopyKeyboardNote]) when a Main copy was found and not proven gone, or when time ran
 * out before it could be known. A gateway that is simply not ready says nothing: a poisoned gateway must not warn on every launch.
 * Nothing here ever launches an app. Mimir's own Main task comes forward only through the caller's [beforeFirstRemoval] step, which the
 * caller withholds whenever a game is on Main.
 */
internal const val BottomMainCopyKeyboardNote = "Keyboard may not work until this app is closed on the main screen."

/** The runtime's launch deadline from the tap (`CompanionRuntimeTimeouts.launchMillis`): the host must have dispatched by then. */
internal const val BottomLaunchBudgetMs = 3_000L

/** Kept for the host: it starts, covers and dispatches in what is left once this stage is done. */
internal const val BottomMainCopyHostReserveMs = 500L

/** Less room than this before a call and the clearance stops: a call that cannot finish in it would only delay the launch. */
internal const val BottomMainCopyMinCallRoomMs = 300L

/** How long after the clearance the note waits, so it lands over the app that opened rather than under it. */
internal const val BottomMainCopyNoteDelayMs = 1_200L

/** The monotonic clock the launch deadline is read on. Plain JVM, so unit tests need no Android clock. */
internal fun bottomLaunchClockMs(): Long = System.nanoTime() / 1_000_000L

/** When the clearance must be done by: the launch's deadline from [plannedAtMs], less what the host needs after it. */
internal fun bottomMainCopyDeadlineMs(plannedAtMs: Long): Long =
    plannedAtMs + BottomLaunchBudgetMs - BottomMainCopyHostReserveMs

internal sealed interface BottomMainCopyOutcome {
    /** Nothing was asked of the gateway. [reason] says why: Mimir itself, an emulator, a game's package, a parked game, a superseded plan. */
    data class Skipped(val reason: String) : BottomMainCopyOutcome

    /** The package has no task on display 0 (proven by the gateway), so there is nothing to close. */
    data object NoMainTask : BottomMainCopyOutcome

    /**
     * [tasks] Main copies were removed and a later read of display 0 answered that none is left. [front] is the caller's
     * bring-Mimir-forward result, when it ran.
     */
    data class Removed(val tasks: Int, val front: String? = null) : BottomMainCopyOutcome

    /**
     * A Main copy was found and may remain, or time ran out before the gateway could say. The launch goes ahead as it did before,
     * with the note. [mainTaskFound] is for the log: whether a copy was seen at all.
     */
    data class Unproven(val reason: String, val mainTaskFound: Boolean = false) : BottomMainCopyOutcome

    /** No ready vendor authority: nothing could be asked, so nothing is claimed and nothing is said. */
    data object GatewayNotReady : BottomMainCopyOutcome
}

/** The outcomes that leave the player possibly holding a window the keyboard will refuse. A gateway that is not ready is not one. */
internal fun BottomMainCopyOutcome.needsKeyboardNote(): Boolean = this is BottomMainCopyOutcome.Unproven

/** One word per outcome for the device log (no package, no id). */
internal fun BottomMainCopyOutcome.logLabel(): String = when (this) {
    is BottomMainCopyOutcome.Skipped -> "skipped:$reason"
    BottomMainCopyOutcome.NoMainTask -> "no_main_task"
    is BottomMainCopyOutcome.Removed -> "removed:$tasks" + (front?.let { " front=$it" } ?: "")
    is BottomMainCopyOutcome.Unproven -> "unproven:$reason found=$mainTaskFound"
    BottomMainCopyOutcome.GatewayNotReady -> "gateway_not_ready"
}

private val RegisteredEmulatorPackages: Set<String> by lazy {
    EmulatorSeedRegistry.allSeedPackages().mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
}

/**
 * Whether [packageName] is any package in the emulator registry, including the recognise-only ones (ePSXe, FPse, DraStic and the
 * like) that Mimir cannot launch a game into. A different question from `MimirLaunchTargetStopPolicy.isEmulatorTarget`, which is the
 * force-stop door and so admits only packages with an approved route: here every emulator is spared, because a live emulator on Main
 * is the player's game whether or not Mimir started it.
 */
internal fun isRegisteredEmulatorPackage(packageName: String): Boolean =
    packageName.trim().lowercase(Locale.ROOT) in RegisteredEmulatorPackages

/**
 * Why this package must not be asked about at all, or null when it may be. Never Mimir itself (its own Main task is the shelf),
 * never an emulator package, and never the package of a game session: a running game's emulator on Main is live work, and a
 * Quick Launch tap must not be able to remove it. The gateway's own stop policy refuses Mimir too; this is the decision, kept
 * apart from the door.
 */
internal fun bottomMainCopySkipReason(
    packageName: String,
    mimirPackage: String,
    emulatorTarget: Boolean,
    gamePackages: Set<String>,
): String? {
    val clean = packageName.trim()
    return when {
        clean.isEmpty() -> "package_missing"
        clean.equals(mimirPackage.trim(), ignoreCase = true) -> "mimir_itself"
        emulatorTarget || isRegisteredEmulatorPackage(clean) -> "emulator"
        gamePackages.any { it.trim().equals(clean, ignoreCase = true) } -> "game_package"
        else -> null
    }
}

/**
 * Whether a Bottom app launch must first park a game on Main (and then spends its three seconds on that park): a dual-screen game
 * is live on Main and is not already parked with Mimir in front. The Main-copy clearance does not run in front of such a launch.
 */
internal fun bottomLaunchParksMainGame(
    dualScreenGameActive: Boolean,
    parkedLeaseExists: Boolean,
    primaryStopped: Boolean,
): Boolean = dualScreenGameActive && !(parkedLeaseExists && !primaryStopped)

/** What a gateway call came to inside its time. */
internal sealed interface BottomMainCopyCall<out T> {
    data class Done<T>(val value: T) : BottomMainCopyCall<T>
    data object TimedOut : BottomMainCopyCall<Nothing>
    data class Failed(val reason: String) : BottomMainCopyCall<Nothing>
}

/**
 * Runs one gateway call and waits for it no longer than [call]'s `timeoutMs`. The vendor call itself bounds at two seconds, which is
 * longer than the front of a launch can spare; this is what makes the clearance's deadline real. A call that outlives its wait is
 * interrupted and abandoned: nothing here starts another call after the deadline, so the most that can land late is the removal that
 * was already in flight, of a copy this launch was closing anyway.
 */
internal class BottomMainCopyCallRunner(
    private val executor: ThreadPoolExecutor = sharedExecutor,
) {
    fun <T> call(timeoutMs: Long, block: () -> T): BottomMainCopyCall<T> {
        if (timeoutMs <= 0L) return BottomMainCopyCall.TimedOut
        val future: Future<T> = try {
            executor.submit<T> { block() }
        } catch (_: RejectedExecutionException) {
            return BottomMainCopyCall.Failed("busy")
        }
        return try {
            BottomMainCopyCall.Done(future.get(timeoutMs, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            future.cancel(true)
            BottomMainCopyCall.TimedOut
        } catch (error: ExecutionException) {
            BottomMainCopyCall.Failed(error.cause?.javaClass?.simpleName ?: "threw")
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            BottomMainCopyCall.Failed("interrupted")
        }
    }

    companion object {
        /** Daemon threads, never queued: a third call while two are stuck is refused rather than parked behind them. */
        private val sharedExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
            0,
            2,
            10L,
            TimeUnit.SECONDS,
            SynchronousQueue(),
            { runnable -> Thread(runnable, "mimir-bottom-main-copy").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
        val shared: BottomMainCopyCallRunner by lazy { BottomMainCopyCallRunner() }
    }
}

/**
 * Closes [packageName]'s copies on Main, if it has any, and reads back that they are gone. Blocking: the caller is on an IO
 * thread. [gateway] is null when no authority exists yet. [deadlineMs] is absolute on [nowMs]'s clock; before each gateway call the
 * clearance checks it has [BottomMainCopyMinCallRoomMs] left and waits for the call no longer than the deadline allows. [stillWanted]
 * is asked before each removal, so a launch the player has since replaced removes nothing. [beforeFirstRemoval] is the caller's
 * step to run once, before the first removal, given the milliseconds it may spend; null skips it. Bounded four ways: the deadline, at
 * most [CompanionMainCopyEvictionLimit] removals, at most [CompanionMainCopyRemovalSettleChecks] reads of one id that was just
 * removed, and a gateway that has no answer.
 */
internal fun clearMainCopyBeforeBottomLaunch(
    packageName: String,
    mimirPackage: String,
    emulatorTarget: Boolean,
    gamePackages: Set<String>,
    gateway: PrivilegedTaskCloseGateway?,
    deadlineMs: Long,
    stillWanted: () -> Boolean = { true },
    nowMs: () -> Long = ::bottomLaunchClockMs,
    sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    calls: BottomMainCopyCallRunner = BottomMainCopyCallRunner.shared,
    beforeFirstRemoval: ((budgetMs: Long) -> String)? = null,
): BottomMainCopyOutcome {
    val clean = packageName.trim()
    bottomMainCopySkipReason(clean, mimirPackage, emulatorTarget, gamePackages)
        ?.let { return BottomMainCopyOutcome.Skipped(it) }
    val authority = gateway ?: return BottomMainCopyOutcome.GatewayNotReady
    var removed = 0
    var found = false
    var front: String? = null
    var lastRemovedTaskId: Int? = null
    var settleChecks = 0
    while (true) {
        val room = deadlineMs - nowMs()
        if (room < BottomMainCopyMinCallRoomMs) {
            return BottomMainCopyOutcome.Unproven(if (removed == 0) "no_time" else "readback_no_time", found)
        }
        val resolved = when (val call = calls.call(room) { authority.resolveTaskOutcome(clean, Display.DEFAULT_DISPLAY) }) {
            is BottomMainCopyCall.Done -> call.value
            BottomMainCopyCall.TimedOut -> return BottomMainCopyOutcome.Unproven("resolve_timeout", found)
            is BottomMainCopyCall.Failed -> return BottomMainCopyOutcome.Unproven("resolve_${call.reason}", found)
        }
        when (resolved) {
            VendorTaskOutcome.NoTask ->
                return if (removed == 0) BottomMainCopyOutcome.NoMainTask else BottomMainCopyOutcome.Removed(removed, front)
            is VendorTaskOutcome.Unavailable ->
                // Nothing seen yet: the gateway is not ready (or could not read display 0), and nothing is claimed or said.
                return if (!found) {
                    BottomMainCopyOutcome.GatewayNotReady
                } else {
                    BottomMainCopyOutcome.Unproven("resolve_${resolved.reason}", found)
                }
            is VendorTaskOutcome.Found -> {
                found = true
                val taskId = resolved.taskId
                if (taskId == lastRemovedTaskId) {
                    // Removed a moment ago and still listed: the system lists a task until its last activity is gone.
                    if (settleChecks >= CompanionMainCopyRemovalSettleChecks) {
                        return BottomMainCopyOutcome.Unproven("still_listed", found)
                    }
                    settleChecks++
                    sleepMs(CompanionMainCopyRemovalSettlePollMs)
                    continue
                }
                if (removed >= CompanionMainCopyEvictionLimit) return BottomMainCopyOutcome.Unproven("bounded", found)
                if (!stillWanted()) return BottomMainCopyOutcome.Skipped("superseded")
                if (removed == 0 && beforeFirstRemoval != null) {
                    // O12: the copy is removed paused and hidden, so Main does not slide. The step is the caller's and has its own
                    // time limit; whatever it does, the removal goes ahead when it returns.
                    val stepBudget = deadlineMs - nowMs() - BottomMainCopyMinCallRoomMs
                    if (stepBudget > 0L) {
                        front = runCatching { beforeFirstRemoval(stepBudget) }.getOrElse { "threw" }
                    }
                }
                val removeRoom = deadlineMs - nowMs()
                if (removeRoom < BottomMainCopyMinCallRoomMs) return BottomMainCopyOutcome.Unproven("no_time", found)
                val removal = when (val call = calls.call(removeRoom) { authority.removeTask(taskId) }) {
                    is BottomMainCopyCall.Done -> call.value
                    BottomMainCopyCall.TimedOut -> return BottomMainCopyOutcome.Unproven("remove_timeout", found)
                    is BottomMainCopyCall.Failed -> return BottomMainCopyOutcome.Unproven("remove_${call.reason}", found)
                }
                when (removal) {
                    PrivilegedTaskRemoval.REMOVED -> {
                        removed++
                        lastRemovedTaskId = taskId
                        settleChecks = 0
                    }
                    // The resolve worked, so a copy exists: it is not a gateway that is merely not ready.
                    PrivilegedTaskRemoval.AUTHORITY_UNAVAILABLE ->
                        return BottomMainCopyOutcome.Unproven("remove_authority_unavailable", found)
                    PrivilegedTaskRemoval.NOT_ALLOWED, PrivilegedTaskRemoval.FAILED ->
                        return BottomMainCopyOutcome.Unproven("remove_${removal.name.lowercase(Locale.ROOT)}", found)
                }
            }
        }
    }
}

/**
 * The package a player has just asked Mimir to open on the Bottom, admitted to [MimirLaunchTargetStopPolicy] for as long as one
 * [clearMainCopyBeforeBottomLaunch] runs. The policy admits emulators and apps Mimir has recorded as launched; Chrome opened by an
 * emulator's link on Main is neither until this launch files it, which happens after the clearance. Without this door the resolve
 * would be declined, and a declined resolve must not be mistaken for an empty Main. The operations stay as narrow as before: the
 * command carries no package, the removal takes an Int, and Mimir itself is still refused by the policy.
 */
internal object BottomLaunchAdmission {
    private val admitted: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun current(): Set<String> = admitted.toSet()

    fun <T> admitting(packageName: String, block: () -> T): T {
        val clean = packageName.trim()
        val added = clean.isNotEmpty() && admitted.add(clean)
        try {
            return block()
        } finally {
            if (added) admitted.remove(clean)
        }
    }
}

/** What [MimirLaunchTargetStopPolicy.sessionAllowedPackages] answers: the Bottom tabs, the running app, and the launch in flight. */
internal fun mimirSessionAllowedPackages(
    trackedBottomPackages: Set<String>,
    runningPackage: String?,
    admittedLaunches: Set<String> = BottomLaunchAdmission.current(),
): Set<String> = trackedBottomPackages + setOfNotNull(runningPackage) + admittedLaunches
