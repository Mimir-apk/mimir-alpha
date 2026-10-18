package com.gruning.mimir.rewrite.core.companionnative

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.max

private const val FOREGROUND_MONITOR_TAG = "MimirCompanionNative"
private const val TASK_OBSERVATION_BURST_MS = 1_000L

/**
 * How far behind "now" each activity-resume poll re-reads. UsageStats stamps an event with the moment the activity
 * resumed but publishes it a little later, so a poll that has already run past that moment can still be handed the event
 * by the next one. The loop dedupes ([ActivityResumeLedger]), so re-reading is free and losing an event is not.
 */
private const val ACTIVITY_RESUME_LOOKBACK_MS = 1_000L
private const val ForegroundPackageLookbackMs = 12L * 60L * 60L * 1_000L
private const val ACTIVITY_RESUME_LEDGER_CAPACITY = 64

/**
 * The events one [ActivityResumeObserver] subscription has already been handed, and the moment it began. Pure, so the
 * once-each and nothing-before-the-start guarantees are unit-tested without UsageStats.
 */
internal class ActivityResumeLedger(private val sinceEpochMs: Long) {
    private val delivered = LinkedHashSet<String>()

    /** True the first time this exact resume is seen, and only for a resume at or after the subscription's start. */
    fun firstSight(eventAtMs: Long, packageName: String, className: String): Boolean {
        if (eventAtMs < sinceEpochMs) return false
        val added = delivered.add("$eventAtMs|$packageName|$className")
        if (added && delivered.size > ACTIVITY_RESUME_LEDGER_CAPACITY) {
            val oldest = delivered.iterator()
            oldest.next()
            oldest.remove()
        }
        return added
    }
}

/**
 * The Usage Access surfaces to try, best first.
 *
 *  Android does not offer one -- Usage Access is an
 * appop, there is no runtime-permission dialog for it, and no app can grant it to itself. What an app CAN
 * decide is **where the player lands**, and this one was landing them in the worst possible place: the
 * bare action opens the full device-wide list, so the player had to find "Mimir" among every installed
 * app and toggle it there.
 *
 * `package:` scoping opens Mimir's own row directly. Mimir already ships exactly this list for the
 * Tweaker's copy of the same grant (`tweakerUsageAccessIntentSpecs`) -- the app's own request was simply
 * the one that never got it.
 *
 * Ordered, because the scoped form is not universal: some ROMs strip the per-app screen, so the bare
 * action is second and plain Settings is the floor. Resolution is checked before launching so an absent
 * screen falls through rather than throwing.
 *
 * Deliberately NOT a shared helper with the Tweaker's list: that one names the Tweaker's package and this
 * one names Mimir's, they are asked for at different moments for different reasons, and merging them would
 * make one grant's ROM workaround silently change the other's.
 */
internal fun usageAccessIntentSpecs(packageName: String): List<Pair<String, String?>> = listOf(
    Settings.ACTION_USAGE_ACCESS_SETTINGS to "package:$packageName",
    Settings.ACTION_USAGE_ACCESS_SETTINGS to null,
    Settings.ACTION_SETTINGS to null,
)

/** [usageAccessIntentSpecs] as launchable intents. Started from a service context, hence NEW_TASK. */
internal fun usageAccessIntents(packageName: String): List<Intent> =
    usageAccessIntentSpecs(packageName).map { (action, data) ->
        Intent(action, data?.let(android.net.Uri::parse)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

/**
 * Local foreground observer for companion reclaim and launch-return evidence.
 *
 * UsageStats is appop-gated. Without Usage Access this remains an explicit return-event bus and reports
 * observations as skipped; with Usage Access it polls recent local foreground events and emits advisory task
 * evidence when a foreign package is followed by Mimir foregrounding again. Display-less samples are kept off
 * the explicit-return stream and cannot settle display disposition or mutate LaunchOutcome.
 */
class AndroidExternalForegroundMonitor(
    context: Context,
    cadence: ForegroundMonitorCadence = ForegroundMonitorCadence(),
    private val ownHostActivityClassNames: Set<String> = setOf(
        CompanionReturnHostContract.activityClassName(context.applicationContext.packageName),
    ),
) : ExternalForegroundMonitor, LaunchForegroundObserver, ActivityResumeObserver {
    private val appContext = context.applicationContext
    private val appPackageName = appContext.packageName
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _returned = MutableSharedFlow<ReturnEvent>(extraBufferCapacity = 16)
    private val _foregroundObserved = MutableSharedFlow<ForegroundReturnObservation>(extraBufferCapacity = 16)
    private val _foreignForegroundObserved = MutableSharedFlow<ForeignForegroundObservation>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val cadencePolicy = ForegroundMonitorCadencePolicy(cadence)

    @Volatile
    private var started = false
    @Volatile
    private var observationDemand = ForegroundMonitorDemand(ForegroundMonitorDisposition.NO_DISPLAY)
    private var usageStatsThread: HandlerThread? = null
    private var usageStatsHandler: Handler? = null
    private var scanCursorMs = 0L
    private var lastForeignForegroundPackage: String? = null
    private var lastEmittedReturnAtMs = 0L
    private var lastForeignObservationAtMs = 0L
    private var taskObservationBurstUntilUptimeMs = 0L

    /**
     * Packages you never "return from". Home/launcher shells resume on the
     * SECONDARY display whenever nothing else owns it — including the moment Mimir's own RESTORE_ONLY guard
     * finishes. Counting one as a foreign foreground made the next guard start look like a return, which
     * fired another restore, which spawned another guard: a self-feeding reclaim loop (measured: 2 flights
     * per 25s of pure idle). Resolved once, lazily; the static shells are a floor for scoped visibility.
     */
    private val nonForeignActivityClasses: Set<String> by lazy {
        // Match shell COMPONENTS, not every package that happens to expose one. Android Settings includes
        // FallbackHome; filtering its whole package would suppress real Settings task-follow observations.
        listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME).flatMapTo(mutableSetOf()) { category ->
            runCatching {
                appContext.packageManager
                    .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                    .mapNotNull { it.activityInfo?.name }
            }.getOrDefault(emptyList())
        }
    }

    private val nonForeignPackages = CompanionShellPackages

    /**
     * A home, launcher or SystemUI resume: by package, or by the resolved HOME / SECONDARY_HOME component. The follow loop's own filter, shared
     * with the floor-cover watch so a Recents package missing from the static list cannot make that watch's span ambiguous.
     */
    fun isShellResume(packageName: String, className: String?): Boolean =
        isNonForeignShellEvent(
            packageName = packageName,
            className = className,
            shellPackages = nonForeignPackages,
            shellActivityClasses = nonForeignActivityClasses,
        )

    private val pollRunnable = object : Runnable {
        override fun run() {
            val usageAccessGranted = hasUsageAccess()
            val demand = observationDemand
            if (usageAccessGranted && pollForForegroundObservation()) {
                taskObservationBurstUntilUptimeMs = SystemClock.uptimeMillis() + TASK_OBSERVATION_BURST_MS
            }
            val effectiveDemand = if (
                SystemClock.uptimeMillis() < taskObservationBurstUntilUptimeMs && !demand.taskObservationPending
            ) {
                demand.copy(taskObservationPending = true)
            } else {
                demand
            }
            val delayMs = cadencePolicy.nextDelayMs(effectiveDemand, usageAccessGranted)
            if (started && delayMs != null) usageStatsHandler?.postDelayed(this, delayMs)
        }
    }

    /**
     * One [observeActivityResumes] subscription. [closed] is the only field another thread touches; the ledger belongs
     * to the monitor's own thread, where every poll and every delivery runs.
     */
    private class ResumeSubscription(
        val sinceEpochMs: Long,
        val listener: (ActivityResumedObservation) -> Unit,
    ) {
        @Volatile
        var closed = false
        val ledger = ActivityResumeLedger(sinceEpochMs)
    }

    private val resumeSubscriptions = java.util.concurrent.CopyOnWriteArrayList<ResumeSubscription>()

    /**
     * The launch cover's own beat. Deliberately not the loop above: that one is stopped by `NO_DISPLAY` (a device with no
     * secondary display reports it all session), so on the Nova it never runs and would hear nothing. This runs on the
     * same thread, at the same task-evidence cadence ([ForegroundMonitorCadencePolicy.launchCoverDelayMs]), only while a
     * subscription is open, and touches none of the shared cursors or flows, so no other consumer sees a new edge.
     */
    private val resumePollRunnable = object : Runnable {
        override fun run() {
            val usageAccessGranted = hasUsageAccess()
            pollActivityResumes(usageAccessGranted)
            val delayMs = cadencePolicy.launchCoverDelayMs(usageAccessGranted)
            if (started && delayMs != null && resumeSubscriptions.isNotEmpty()) {
                usageStatsHandler?.postDelayed(this, delayMs)
            }
        }
    }

    override val returned: SharedFlow<ReturnEvent> = _returned.asSharedFlow()
    override val foregroundObserved: SharedFlow<ForegroundReturnObservation> = _foregroundObserved.asSharedFlow()
    override val foreignForegroundObserved: SharedFlow<ForeignForegroundObservation> =
        _foreignForegroundObserved.asSharedFlow()

    override fun start() {
        if (started) return
        started = true
        scanCursorMs = max(0L, System.currentTimeMillis() - 1_000L)
        lastForeignForegroundPackage = null
        taskObservationBurstUntilUptimeMs = 0L
        val thread = HandlerThread("MimirUsageStatsMonitor").also { it.start() }
        usageStatsThread = thread
        usageStatsHandler = Handler(thread.looper).also { scheduleForCurrentDemand(it, immediate = true) }
    }

    override fun observeActivityResumes(
        sinceEpochMs: Long,
        onResumed: (ActivityResumedObservation) -> Unit,
    ): AutoCloseable {
        val handler = usageStatsHandler
        if (!started || handler == null) {
            Log.w(FOREGROUND_MONITOR_TAG, "activity-resume observation requested while the monitor is not started")
            return AutoCloseable { }
        }
        val subscription = ResumeSubscription(sinceEpochMs, onResumed)
        resumeSubscriptions += subscription
        handler.removeCallbacks(resumePollRunnable)
        handler.post(resumePollRunnable)
        return AutoCloseable {
            subscription.closed = true
            resumeSubscriptions.remove(subscription)
            if (resumeSubscriptions.isEmpty()) usageStatsHandler?.removeCallbacks(resumePollRunnable)
        }
    }

    private fun pollActivityResumes(usageAccessGranted: Boolean) {
        if (!usageAccessGranted) return
        val subscriptions = resumeSubscriptions.filterNot { it.closed }
        if (subscriptions.isEmpty()) return
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val from = max(subscriptions.minOf { it.sinceEpochMs }, now - ACTIVITY_RESUME_LOOKBACK_MS)
        runCatching {
            val events = manager.queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event)
                if (!isForegroundUsageEvent(event.eventType)) continue
                val packageName = event.packageName ?: continue
                val className = event.className ?: continue
                val eventAtMs = event.timeStamp
                for (subscription in subscriptions) {
                    if (subscription.closed || !subscription.ledger.firstSight(eventAtMs, packageName, className)) continue
                    runCatching {
                        subscription.listener(
                            ActivityResumedObservation(
                                packageName = packageName,
                                className = className,
                                eventAtMs = eventAtMs,
                                observedAtMs = now,
                            ),
                        )
                    }.onFailure { Log.w(FOREGROUND_MONITOR_TAG, "activity-resume listener failed", it) }
                }
            }
        }.onFailure { Log.w(FOREGROUND_MONITOR_TAG, "UsageStats activity-resume poll failed", it) }
    }

    override fun stop() {
        started = false
        resumeSubscriptions.clear()
        usageStatsHandler?.removeCallbacksAndMessages(null)
        usageStatsHandler = null
        usageStatsThread?.quitSafely()
        usageStatsThread = null
        lastForeignForegroundPackage = null
    }

    override fun updateObservationDemand(demand: ForegroundMonitorDemand) {
        if (observationDemand == demand) return
        observationDemand = demand
        usageStatsHandler?.post {
            Log.i(
                FOREGROUND_MONITOR_TAG,
                "foreground demand=$demand usageAccess=${hasUsageAccess()}",
            )
            // Never replay a stale display-less event window after a stopped cadence becomes active again.
            scanCursorMs = max(0L, System.currentTimeMillis() - 1_000L)
            lastForeignForegroundPackage = null
            if (demand.disposition == ForegroundMonitorDisposition.NO_DISPLAY) {
                taskObservationBurstUntilUptimeMs = 0L
            }
            usageStatsHandler?.removeCallbacks(pollRunnable)
            usageStatsHandler?.let { scheduleForCurrentDemand(it, immediate = true) }
        }
    }

    override fun notifyExplicitReturn(packageName: String?, displayId: Int?, reason: String) {
        _returned.tryEmit(ReturnEvent(packageName = packageName, displayId = displayId, reason = reason))
    }

    override fun usageAccessState(): UsageAccessState =
        UsageAccessState(packageName = appPackageName, granted = hasUsageAccess())

    override fun requestUsageAccess() {
        // Already granted: nothing to ask for (the overlay and All-files requests make the same check).
        if (hasUsageAccess()) return
        mainHandler.post {
            val packageManager = appContext.packageManager
            usageAccessIntents(appPackageName)
                .firstOrNull { intent ->
                    intent.resolveActivity(packageManager) != null &&
                        // From Mimir's resumed Activity (joins its task, no Recents card of its own);
                        // the application-context NEW_TASK start is the fallback.
                        runCatching { startSettingsSurface(appContext, intent) }.isSuccess
                }
                ?: Log.w(FOREGROUND_MONITOR_TAG, "no Usage Access surface on this device")
        }
    }

    override fun observeLaunchForeground(
        targetPackage: String,
        launchedAtMs: Long,
        returnedAtMs: Long,
        singleScreenLaunchShape: Boolean,
    ): LaunchForegroundObservation {
        val cleanTarget = targetPackage.trim()
        val windowStart = max(0L, launchedAtMs - 500L)
        val windowEnd = max(returnedAtMs, launchedAtMs) + 500L
        val granted = hasUsageAccess()

        fun skipped(reason: String, truth: String? = null) = LaunchForegroundObservation(
            targetPackage = cleanTarget,
            usageAccessGranted = granted,
            singleScreenLaunchShape = singleScreenLaunchShape,
            foregroundObservable = false,
            windowStartMs = windowStart,
            windowEndMs = windowEnd,
            skipped = true,
            skipReason = reason,
            truth = truth ?: "Foreground observation skipped: $reason.",
        )

        if (!singleScreenLaunchShape) {
            return skipped(
                reason = "dual_screen_launch_shape",
                truth = "DS/3DS dual-screen launch shapes are excluded from silent-death foreground-span proof.",
            )
        }
        if (!granted) {
            return skipped(
                reason = "usage_access_not_granted",
                truth = "Usage Access is not granted, so foreground observation is unavailable and silent-death proof is not produced.",
            )
        }
        if (cleanTarget.isEmpty()) return skipped("target_package_missing")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return skipped(
                reason = "main_thread_query_refused",
                truth = "UsageStats foreground observation must run off the main thread; no foreground proof was produced.",
            )
        }
        val manager = appContext.getSystemService(UsageStatsManager::class.java)
            ?: return skipped("usage_stats_manager_unavailable")

        var timeline = LaunchForegroundTimeline()

        runCatching {
            val events = manager.queryEvents(windowStart, windowEnd)
            val event = UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event)
                if (cleanTarget != event.packageName) continue
                val timestamp = event.timeStamp
                when {
                    isForegroundUsageEvent(event.eventType) -> {
                        timeline = timeline.recordForeground(timestamp)
                    }
                    isBackgroundUsageEvent(event.eventType) -> {
                        timeline = timeline.recordBackground(timestamp)
                    }
                }
            }
        }.onFailure {
            Log.w(FOREGROUND_MONITOR_TAG, "UsageStats foreground observation failed", it)
            return skipped("usage_stats_query_failed")
        }

        return LaunchForegroundObservation(
            targetPackage = cleanTarget,
            usageAccessGranted = true,
            singleScreenLaunchShape = true,
            foregroundObservable = true,
            targetForegroundObserved = timeline.targetForegroundObserved,
            firstForegroundAtMs = timeline.firstForegroundAtMs,
            lastForeignForegroundAtMs = timeline.lastForegroundAtMs,
            lastForeignBackgroundAtMs = timeline.lastBackgroundAtMs,
            targetForegroundSpanMs = timeline.targetForegroundSpanMs(returnedAtMs),
            targetForegroundSpanBounded = timeline.targetForegroundSpanBounded,
            lastTargetEventType = timeline.lastTargetEventType,
            windowStartMs = windowStart,
            windowEndMs = windowEnd,
        )
    }

    private fun pollForForegroundObservation(): Boolean {
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        val from = max(0L, scanCursorMs - 100L)
        var foreignObservationEmitted = false
        runCatching {
            val events = manager.queryEvents(from, now)
            val event = UsageEvents.Event()
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event)
                val packageName = event.packageName ?: continue
                val timestamp = event.timeStamp
                if (timestamp < scanCursorMs || !isForegroundUsageEvent(event.eventType)) continue
                if (packageName == appPackageName) {
                    // Own hosts on either display are not the player returning to Main.
                    if (!usageEventMeansReturnToMimir(
                            packageName = packageName,
                            className = event.className,
                            appPackageName = appPackageName,
                            ownHostActivityClassNames = ownHostActivityClassNames,
                        )
                    ) continue
                    val previous = lastForeignForegroundPackage
                    // The same UsageStats event must never emit twice (a duplicate return
                    // event doubled the whole reclaim cycle) — timestamps are the stream's identity.
                    if (!previous.isNullOrBlank() && timestamp > lastEmittedReturnAtMs) {
                        lastEmittedReturnAtMs = timestamp
                        _foregroundObserved.tryEmit(
                            ForegroundReturnObservation(
                                packageName = previous,
                                reason = "usage_stats_return_to_mimir_observed",
                                observedAtMs = timestamp,
                            ),
                        )
                        lastForeignForegroundPackage = null
                    }
                } else if (!isShellResume(packageName, event.className)) {
                    lastForeignForegroundPackage = packageName
                    if (timestamp > lastForeignObservationAtMs) {
                        lastForeignObservationAtMs = timestamp
                        val emitted = _foreignForegroundObserved.tryEmit(
                            ForeignForegroundObservation(packageName = packageName, observedAtMs = timestamp),
                        )
                        if (emitted) {
                            Log.i(
                                FOREGROUND_MONITOR_TAG,
                                "foreign foreground package=$packageName observedAtMs=$timestamp",
                            )
                        }
                        foreignObservationEmitted = emitted || foreignObservationEmitted
                    }
                }
            }
            scanCursorMs = now
        }.onFailure {
            Log.w(FOREGROUND_MONITOR_TAG, "UsageStats foreground observation poll failed", it)
            scanCursorMs = now
        }
        return foreignObservationEmitted
    }

    private fun scheduleForCurrentDemand(handler: Handler, immediate: Boolean) {
        val delayMs = cadencePolicy.nextDelayMs(observationDemand, hasUsageAccess()) ?: return
        if (immediate) handler.post(pollRunnable) else handler.postDelayed(pollRunnable, delayMs)
    }

    /**
     * The package in front right now, read from UsageStats on demand; null without Usage Access or when nothing can be said.
     *
     * For Your music's "is a game in front?" question, asked at each audio-focus change. [foreignForegroundObserved] cannot answer it:
     * the cadence only polls while the companion has something to observe ([ForegroundMonitorCadencePolicy.nextDelayMs] returns null for
     * no second display), so an emulator opened from the launcher with Mimir in the background would never be seen. A bounded
     * lookback, since a package that has been in front for hours has only its original resume to show.
     */
    fun foregroundPackageNow(lookbackMs: Long = ForegroundPackageLookbackMs): String? {
        if (!hasUsageAccess()) return null
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        return runCatching {
            val events = manager.queryEvents(max(0L, now - lookbackMs), now) ?: return@runCatching null
            val event = UsageEvents.Event()
            val reducer = ForegroundPackageReducer()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val packageName = event.packageName ?: continue
                when {
                    isForegroundUsageEvent(event.eventType) ->
                        reducer.accept(packageName, ForegroundUsageKind.FOREGROUND, event.className)
                    isBackgroundUsageEvent(event.eventType) ->
                        reducer.accept(packageName, ForegroundUsageKind.BACKGROUND, event.className)
                }
            }
            reducer.current
        }.onFailure { Log.w(FOREGROUND_MONITOR_TAG, "UsageStats foreground package query failed", it) }.getOrNull()
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = appContext.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                appContext.applicationInfo.uid,
                appPackageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                appContext.applicationInfo.uid,
                appPackageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isForegroundUsageEvent(eventType: Int): Boolean =
        eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
            eventType == UsageEvents.Event.ACTIVITY_RESUMED

    private fun isBackgroundUsageEvent(eventType: Int): Boolean =
        eventType == UsageEvents.Event.MOVE_TO_BACKGROUND ||
            eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
            eventType == UsageEvents.Event.ACTIVITY_STOPPED
}

private val CompanionShellPackages = setOf(
    "com.android.launcher3",
    "com.google.android.apps.nexuslauncher",
    "com.cocoon.shell",
    "com.cocoon.launcher",
    "com.android.systemui",
)

/** A home, launcher or SystemUI package: its resume never means an app took a screen. Shared by the follow loop and the floor-cover watch. */
fun isCompanionShellPackage(packageName: String): Boolean = packageName in CompanionShellPackages

/**
 * Mimir is the HOME package on both displays. Its Bottom home (CompanionSecondaryHomeActivity), Bottom
 * host (CompanionReturnActivity), and Main-side transfer host (PrimaryTransferActivity) resuming never
 * mean the player came back to Mimir on Main. On 2026-09-11, a confirmed transfer was cleared 30 ms
 * later when the Bottom home resumed, because UsageStats carries no display identity.
 */
internal fun usageEventMeansReturnToMimir(
    packageName: String,
    className: String?,
    appPackageName: String,
    ownHostActivityClassNames: Set<String>,
): Boolean = packageName == appPackageName && className !in ownHostActivityClassNames

internal fun isNonForeignShellEvent(
    packageName: String,
    className: String?,
    shellPackages: Set<String>,
    shellActivityClasses: Set<String>,
): Boolean = packageName in shellPackages || className in shellActivityClasses
