package com.gruning.mimir.rewrite

import android.content.Intent
import com.gruning.mimir.rewrite.core.companionnative.CompanionReturnHostContract
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Immutable operation context captured before a companion launch lock is published. */
internal data class CompanionSecondaryLaunchPlan(
    val operationId: UUID,
    val operationToken: OperationId,
    val packageName: String,
    val displayId: Int,
    val primaryOrigin: PrimaryReturnOrigin,
    val stateVersion: Long,
    val launchMode: String = CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_APP,
    val targetIntent: Intent? = null,
    /**
     * Dispatch preparation flag, set only by Quick Launch (the Bottom window's picker, the Presentation's dock, the Home picker).
     * Every other way an app reaches the Bottom (an app tile, the recent rail, a tab re-tap, the HOME-park resume, a debug hook)
     * leaves it false and launches exactly as before. Read by the work stage's `beforeDispatch`.
     */
    val clearMainCopy: Boolean = false,
    /** When the tap published this plan, on [bottomLaunchClockMs]'s clock. The launch has [BottomLaunchBudgetMs] from here. */
    val plannedAtMs: Long = bottomLaunchClockMs(),
)

internal fun interface CompanionLaunchWorkScheduler {
    fun schedule(work: () -> Unit): Boolean
}

internal fun interface CompanionLaunchMainDispatcher {
    fun dispatch(work: () -> Unit)
}

/**
 * One process-owned transaction path for every screen-2 app launch.
 *
 * Planning is synchronous and memory-only so the caller can publish its exact-token lock immediately.
 * Durable persistence then runs off main; target dispatch returns to main only while that same operation
 * remains the current runtime root. A stale completion can therefore neither launch nor unlock a newer tap.
 */
internal class CompanionSecondaryLaunchCoordinator(
    private val preparePlan: (String) -> CompanionSecondaryLaunchPlan?,
    private val prepareGamePlan: (Intent) -> CompanionSecondaryLaunchPlan? = { null },
    private val persistPlan: (CompanionSecondaryLaunchPlan) -> Boolean,
    private val isCurrentPlan: (CompanionSecondaryLaunchPlan) -> Boolean,
    private val launchPlan: (CompanionSecondaryLaunchPlan) -> Boolean,
    private val failPlan: (CompanionSecondaryLaunchPlan, String) -> Unit,
    private val workScheduler: CompanionLaunchWorkScheduler,
    private val mainDispatcher: CompanionLaunchMainDispatcher,
    private val gameHostDispatchTimeoutMs: Long = GAME_HOST_DISPATCH_TIMEOUT_MS,
    private val logPrepareDeclined: (packageName: String, reason: String) -> Unit = { _, _ -> },
    /**
     * Runs on the work stage, after the plan is persisted and before the launch returns to main to start the host. Blocking is
     * allowed here and nowhere later. the earlier update closes an app's Main copy in it, so the app reopens fresh on the Bottom. Never
     * throws into the launch: a failure inside it is the hook's to report, and the launch goes ahead.
     */
    private val beforeDispatch: (CompanionSecondaryLaunchPlan) -> Unit = {},
) {
    fun prepare(packageName: String): CompanionPreparedLaunch? = prepare(packageName, clearMainCopy = false)

    /** [clearMainCopy] is true only for Quick Launch : the plan then asks the work stage to close the app's Main copy first. */
    fun prepare(packageName: String, clearMainCopy: Boolean): CompanionPreparedLaunch? {
        val cleanPackage = packageName.trim().takeIf(String::isNotEmpty)
            ?: return declined(packageName, "package_blank")
        val plan = preparePlan(cleanPackage) ?: return declined(cleanPackage, "prepare_plan_declined")
        return prepare(if (clearMainCopy) plan.copy(clearMainCopy = true) else plan)
    }

    fun prepareGame(targetIntent: Intent): CompanionPreparedLaunch? {
        val packageName = (targetIntent.component?.packageName ?: targetIntent.`package`)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return declined("", "game_package_missing")
        val plan = prepareGamePlan(Intent(targetIntent)) ?: return declined(packageName, "prepare_game_plan_declined")
        if (plan.packageName != packageName) return declined(packageName, "game_plan_package_mismatch")
        if (plan.launchMode != CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
            return declined(packageName, "game_plan_mode_mismatch")
        }
        return prepare(plan)
    }

    private fun prepare(plan: CompanionSecondaryLaunchPlan): CompanionPreparedLaunch? {
        if (plan.packageName.isBlank()) return declined(plan.packageName, "plan_package_blank")
        if (plan.operationId == UUID(0L, 0L)) return declined(plan.packageName, "plan_operation_empty")

        val regular = CompanionPreparedLaunch(plan.operationId, dispatch = { dispatchPlan(plan, awaitHost = false) })
        return if (plan.launchMode == CompanionReturnHostContract.LAUNCH_MODE_SECONDARY_GAME) {
            regular.copy(dispatchAndAwait = { dispatchPlan(plan, awaitHost = true) })
        } else {
            regular
        }
    }

    private fun declined(packageName: String, reason: String): CompanionPreparedLaunch? {
        runCatching { logPrepareDeclined(packageName, reason) }
        return null
    }

    private fun dispatchPlan(plan: CompanionSecondaryLaunchPlan, awaitHost: Boolean): Boolean {
        if (!awaitHost) {
            val scheduled = runCatching {
                workScheduler.schedule {
                    if (!persistPlan(plan)) {
                        mainDispatcher.dispatch { failPlan(plan, "pending_operation_persist_failed") }
                        return@schedule
                    }
                    runCatching { beforeDispatch(plan) }
                    mainDispatcher.dispatch dispatch@{
                        if (!isCurrentPlan(plan)) return@dispatch
                        if (!launchPlan(plan)) failPlan(plan, "secondary_launch_dispatch_failed")
                    }
                }
            }.getOrDefault(false)
            if (!scheduled) failPlan(plan, "secondary_launch_schedule_failed")
            return scheduled
        }
        val completed = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        val failureClaimed = AtomicBoolean(false)
        val dispatchGate = AtomicInteger(GAME_DISPATCH_PENDING)
        fun failOnce(reason: String) {
            if (failureClaimed.compareAndSet(false, true)) {
                runCatching {
                    mainDispatcher.dispatch { if (isCurrentPlan(plan)) failPlan(plan, reason) }
                }
            }
        }
        val scheduled = runCatching {
            workScheduler.schedule {
                val persisted = runCatching { persistPlan(plan) }.getOrDefault(false)
                if (!persisted) {
                    failOnce("pending_operation_persist_failed")
                    completed.countDown()
                    return@schedule
                }
                try {
                    mainDispatcher.dispatch {
                        try {
                            if (dispatchGate.compareAndSet(GAME_DISPATCH_PENDING, GAME_DISPATCH_CLAIMED) &&
                                isCurrentPlan(plan)
                            ) {
                                val dispatched = runCatching { launchPlan(plan) }.getOrElse {
                                    failOnce("secondary_launch_dispatch_failed")
                                    false
                                }
                                accepted.set(dispatched)
                                if (!dispatched) failOnce("secondary_launch_dispatch_failed")
                            }
                        } catch (_: Throwable) {
                            // The host callback is process-owned; a malformed current-plan/readback
                            // must settle the synchronous caller instead of stranding its latch.
                            failOnce("secondary_launch_dispatch_failed")
                        } finally {
                            completed.countDown()
                        }
                    }
                } catch (_: Throwable) {
                    failOnce("secondary_launch_dispatch_failed")
                    completed.countDown()
                }
            }
        }.getOrDefault(false)
        if (!scheduled) {
            failOnce("secondary_launch_schedule_failed")
            return false
        }
        if (!completed.await(gameHostDispatchTimeoutMs, TimeUnit.MILLISECONDS)) {
            if (!dispatchGate.compareAndSet(GAME_DISPATCH_PENDING, GAME_DISPATCH_CANCELLED)) {
                // The main dispatcher already claimed the launch. Let it finish so the
                // synchronous LaunchOutcome cannot report a false host acceptance.
                if (dispatchGate.get() == GAME_DISPATCH_CLAIMED) completed.await()
                return accepted.get()
            } else {
                failOnce("secondary_launch_timeout")
            }
            return false
        }
        return accepted.get()
    }

    private companion object {
        const val GAME_HOST_DISPATCH_TIMEOUT_MS = 5_000L
        const val GAME_DISPATCH_PENDING = 0
        const val GAME_DISPATCH_CLAIMED = 1
        const val GAME_DISPATCH_CANCELLED = 2
    }
}
