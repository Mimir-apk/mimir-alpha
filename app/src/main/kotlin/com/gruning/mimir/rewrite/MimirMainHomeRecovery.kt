package com.gruning.mimir.rewrite

import android.app.Activity
import android.app.ActivityOptions
import android.app.AppOpsManager
import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The two recovery moments the runtime already has; each may bring Main back, but only the first one decides. */
internal enum class MainHomeRecoveryTrigger(val token: String) {
    /** `CompanionProcessRuntime.create` adopted a persisted Bottom lease (`ProcessRecovery NoDisplay->RecoveryNeeded`). */
    PROCESS_RECOVERY("process_recovery"),

    /** The platform resumed `CompanionSecondaryHomeActivity`, as it does on display 4 after an install or a death. */
    FLOOR_RESUMED("floor_resumed"),
}

/** One UsageStats activity lifecycle event: [type] is `ACTIVITY_RESUMED`, `ACTIVITY_PAUSED` or `ACTIVITY_STOPPED`. */
internal data class MainHomeActivityEvent(
    val timeMs: Long,
    val packageName: String,
    val className: String?,
    val type: Int,
)

/** The start as data; the Android half turns exactly these fields into an Intent and ActivityOptions. */
internal data class MainHomeStartRequest(
    val action: String,
    val category: String,
    val packageName: String,
    val flags: Int,
    val launchDisplayId: Int,
)

/**
 * `ACTION_MAIN` + `CATEGORY_HOME` in Mimir's package, no component, launch display 0. Without a component the start is
 * typed HOME (AOSP 13 `ActivityRecord.setActivityType`, as `companionFloorFrontIntent` records); `am start -n
 * .MainActivity` put MainActivity in a `type=standard` task on the Thor. A start that names no display lands on the
 * focused one, which after a restart can be display 4.
 */
internal fun mainHomeStartRequest(packageName: String): MainHomeStartRequest = MainHomeStartRequest(
    action = Intent.ACTION_MAIN,
    category = Intent.CATEGORY_HOME,
    packageName = packageName,
    flags = Intent.FLAG_ACTIVITY_NEW_TASK,
    launchDisplayId = Display.DEFAULT_DISPLAY,
)

/** quickstep's Recents when the default home is not launcher3: display 0's top on the Thor after the 22:33:31 death. */
internal const val QuickstepRecentsComponent = "com.android.launcher3/com.android.quickstep.RecentsActivity"

/**
 * How far before this process's start the read reaches. A launcher or Recents that resumed at the death falls inside
 * it (Thor 2026-09-11: death 22:33:31.1, `ProcessRecovery` 22:33:32.6); one the player already had open long before
 * the restart does not, and is left alone.
 */
internal const val MainHomeRecoveryLookbackMs = 60_000L

/**
 * The decision waits this long after its trigger. A process started to host MainActivity (a HOME press, the platform's
 * own home start) receives that launch right behind its bind, so by then MainActivity exists and the window is closed.
 */
internal const val MainHomeRecoverySettleMs = 1_000L

private const val MaxLoggedResumedActivities = 6

/** Components whose last lifecycle event in [events] is a resume, most recently resumed first. */
internal fun resumedActivityComponents(events: List<MainHomeActivityEvent>): List<String> {
    val latest = LinkedHashMap<String, MainHomeActivityEvent>()
    for (event in events.sortedBy(MainHomeActivityEvent::timeMs)) {
        val className = event.className ?: continue
        latest["${event.packageName}/$className"] = event
    }
    return latest.entries
        .filter { (_, event) -> event.type == UsageEvents.Event.ACTIVITY_RESUMED }
        .sortedByDescending { (_, event) -> event.timeMs }
        .map { (component, _) -> component }
}

/**
 * The resumed launcher or Recents that proves Main shows one, or null. Positive evidence only: quickstep's Recents and
 * HOME activities run on the default display, and anything that can be a SECONDARY_HOME (display 4) is excluded. A game
 * or app resumed on Main paused whatever launcher was there, so it reads as null without being attributed; UsageStats
 * carries no display id. Mimir's own events never count: a killed process reports no pause for its resumed
 * MainActivity, so its last own event can still read resumed after Recents took Main.
 */
internal fun launcherOrRecentsOnMain(
    resumed: List<String>,
    selfPackage: String,
    homeComponents: Set<String>,
    secondaryHomeComponents: Set<String>,
): String? = resumed.firstOrNull { component ->
    component.substringBefore('/') != selfPackage &&
        component !in secondaryHomeComponents &&
        (component == QuickstepRecentsComponent || component in homeComponents)
}

/**
 * `MimirRecovery: main home decision=started|skipped|failed reason=… top=… trigger=… resumed=[…] sinceStartMs=…`.
 * `top` names Main's top only when it is the launcher or Recents that decided; `unattributed` when none is resumed (a
 * game or app on Main cannot be named from UsageStats); `unread` when no read ran.
 */
internal fun mainHomeRecoveryLogLine(
    decision: String,
    reason: String,
    top: String,
    trigger: MainHomeRecoveryTrigger,
    resumed: List<String>?,
    sinceStartMs: Long,
): String {
    val resumedField = resumed
        ?.take(MaxLoggedResumedActivities)
        ?.joinToString(separator = ",", prefix = "[", postfix = "]")
        ?: "unread"
    return "main home decision=$decision reason=$reason top=$top trigger=${trigger.token} " +
        "resumed=$resumedField sinceStartMs=$sinceStartMs"
}

internal interface MainHomeRecoveryPlatform {
    val selfPackage: String
    fun defaultHomePackage(): String?
    fun homeComponents(): Set<String>
    fun secondaryHomeComponents(): Set<String>
    fun usageAccessGranted(): Boolean

    /** Activity lifecycle events between two wall-clock instants, or null when UsageStats could not be read. */
    fun activityEvents(fromWallClockMs: Long, toWallClockMs: Long): List<MainHomeActivityEvent>?
    fun wallClockNowMs(): Long
    fun sinceProcessStartMs(): Long

    /** Throws when the platform refuses the start. */
    fun start(request: MainHomeStartRequest)
}

/**
 * Once per process: the first trigger schedules the only decision, later ones log `once_per_process`, and after
 * MainActivity is created in this process every trigger is silent. A refused start is logged and never retried.
 */
internal class MainHomeRecoveryGate(
    private val platform: MainHomeRecoveryPlatform,
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Unit,
    private val log: (String) -> Unit,
) {
    private val lock = Any()
    private var mainActivityCreated = false
    private var claimed = false

    fun noteMainActivityCreated() {
        synchronized(lock) { mainActivityCreated = true }
    }

    fun trigger(trigger: MainHomeRecoveryTrigger) {
        val first = synchronized(lock) {
            if (mainActivityCreated) return
            val unclaimed = !claimed
            claimed = true
            unclaimed
        }
        if (!first) {
            skip("once_per_process", trigger)
            return
        }
        schedule(MainHomeRecoverySettleMs) { decide(trigger) }
    }

    private fun mainActivityCreatedNow(): Boolean = synchronized(lock) { mainActivityCreated }

    private fun decide(trigger: MainHomeRecoveryTrigger) {
        if (mainActivityCreatedNow()) return skip("main_activity_created", trigger)
        if (platform.defaultHomePackage() != platform.selfPackage) return skip("not_default_home", trigger)
        if (!platform.usageAccessGranted()) return skip("usage_access_missing", trigger)
        val now = platform.wallClockNowMs()
        val from = now - platform.sinceProcessStartMs() - MainHomeRecoveryLookbackMs
        val events = platform.activityEvents(from, now) ?: return skip("usage_events_unreadable", trigger)
        val resumed = resumedActivityComponents(events)
        val launcher = launcherOrRecentsOnMain(
            resumed = resumed,
            selfPackage = platform.selfPackage,
            homeComponents = platform.homeComponents(),
            secondaryHomeComponents = platform.secondaryHomeComponents(),
        ) ?: return skip("no_launcher_or_recents_resumed", trigger, top = "unattributed", resumed = resumed)
        if (mainActivityCreatedNow()) {
            return skip("main_activity_created", trigger, top = launcher, resumed = resumed)
        }
        val failure = runCatching { platform.start(mainHomeStartRequest(platform.selfPackage)) }.exceptionOrNull()
        if (failure == null) {
            emit("started", "launcher_or_recents_on_main", trigger, launcher, resumed)
        } else {
            emit("failed", "start_failed:${failure::class.java.simpleName}", trigger, launcher, resumed)
        }
    }

    private fun skip(
        reason: String,
        trigger: MainHomeRecoveryTrigger,
        top: String = "unread",
        resumed: List<String>? = null,
    ) = emit("skipped", reason, trigger, top, resumed)

    private fun emit(
        decision: String,
        reason: String,
        trigger: MainHomeRecoveryTrigger,
        top: String,
        resumed: List<String>?,
    ) {
        log(mainHomeRecoveryLogLine(decision, reason, top, trigger, resumed, platform.sinceProcessStartMs()))
    }
}

/**
 * When Mimir restarts while Main shows Recents or the system launcher, recovery returns Main to Home.
 * A running game or app must remain uncovered.
 *
 * Thor test unit, 2026-09-11 : a signal from Mimir's
 * uid ended its process at 22:33:31 with a game on the Bottom; the process restarted (`ProcessRecovery` at
 * 22:33:32.626), yet display 0 stayed on quickstep's Recents until a HOME key at 22:37:45. `install -r` left Main the
 * same way. Display observation lives in MainActivity, so nothing presents either screen until something starts it.
 *
 * **When.** [arm] runs at process start (`CompanionProcessRuntime.create`); [MainHomeRecoveryTrigger] names the two
 * moments that may trigger it. The window closes when MainActivity is created in this process, so Recents opened in a
 * healthy process is never touched.
 *
 * **Who says what is on Main: the app's own Usage Access, read off the main thread.**
 * - `getRunningTasks` cannot: AOSP 13 `RunningTasks.processTask` skips another uid's task unless the caller holds
 *   REAL_GET_TASKS (`if (!mAllowed) return;`, no home or recents exception), so it shows neither Recents nor a game.
 * - The vendor PServer route has no operation that reads a display's top: `ResolveTask` is admitted per launch-target
 *   package and matches among all of a display's root tasks. A new one would be a new privileged surface.
 * - UsageStats keeps history, so a process that just started can read what resumed around its own death -- the source
 *   `AndroidExternalForegroundMonitor` already polls. See [launcherOrRecentsOnMain] for how a display-blind read decides
 *   this question without covering a game.
 *
 * **The start.** [mainHomeStartRequest], once. Home apps are exempt from background-start blocking (AOSP 13
 * `ActivityStarter.shouldAbortBackgroundActivityStart`: "Always allow home application to start activities"), and
 * [MainHomeRecoveryPlatform.defaultHomePackage] must be Mimir.
 */
internal object MimirMainHomeRecovery {
    private const val Tag = "MimirRecovery"

    @Volatile
    private var gate: MainHomeRecoveryGate? = null

    fun arm(context: Context) {
        synchronized(this) {
            if (gate != null) return
            val app = context.applicationContext
            val armed = MainHomeRecoveryGate(
                platform = AndroidMainHomeRecoveryPlatform(app),
                schedule = { delayMillis, action ->
                    CompanionProcessRuntime.processScope.launch(Dispatchers.IO) {
                        delay(delayMillis)
                        action()
                    }
                },
                log = { line -> runCatching { Log.i(Tag, line) } },
            )
            gate = armed
            (app as? Application)?.registerActivityLifecycleCallbacks(MainActivityCreationWatch(armed))
        }
    }

    fun trigger(trigger: MainHomeRecoveryTrigger) {
        gate?.trigger(trigger)
    }
}

/** Closes the window the moment MainActivity is created in this process, then stops listening. */
private class MainActivityCreationWatch(private val gate: MainHomeRecoveryGate) : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is MainActivity) return
        gate.noteMainActivityCreated()
        activity.application.unregisterActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Also O14's Usage Access check and event read (`BottomOrphanPlayTime`), so the app has one Usage Access reader. */
internal class AndroidMainHomeRecoveryPlatform(private val context: Context) : MainHomeRecoveryPlatform {
    override val selfPackage: String = context.packageName

    @Suppress("DEPRECATION")
    override fun defaultHomePackage(): String? = runCatching {
        context.packageManager
            .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }.getOrNull()

    override fun homeComponents(): Set<String> = components(Intent.CATEGORY_HOME, PackageManager.MATCH_DEFAULT_ONLY)

    /** Every flag value, not only DEFAULT: an extra exclusion can only skip, never start. */
    override fun secondaryHomeComponents(): Set<String> = components(Intent.CATEGORY_SECONDARY_HOME, 0)

    /** Both categories are in the manifest's `<queries>`, so launcher3 resolves under scoped visibility. */
    @Suppress("DEPRECATION")
    private fun components(category: String, flags: Int): Set<String> = runCatching {
        context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), flags)
            .mapNotNullTo(mutableSetOf()) { info -> info.activityInfo?.let { "${it.packageName}/${it.name}" } }
    }.getOrDefault(emptySet())

    override fun usageAccessGranted(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, context.applicationInfo.uid, selfPackage) ==
                AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    override fun activityEvents(fromWallClockMs: Long, toWallClockMs: Long): List<MainHomeActivityEvent>? {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        return runCatching {
            val events = manager.queryEvents(fromWallClockMs, toWallClockMs) ?: return null
            val event = UsageEvents.Event()
            val out = mutableListOf<MainHomeActivityEvent>()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type != UsageEvents.Event.ACTIVITY_RESUMED &&
                    type != UsageEvents.Event.ACTIVITY_PAUSED &&
                    type != UsageEvents.Event.ACTIVITY_STOPPED
                ) {
                    continue
                }
                val packageName = event.packageName ?: continue
                out += MainHomeActivityEvent(event.timeStamp, packageName, event.className, type)
            }
            out
        }.getOrNull()
    }

    override fun wallClockNowMs(): Long = System.currentTimeMillis()

    override fun sinceProcessStartMs(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    override fun start(request: MainHomeStartRequest) {
        // Mimir's own start, not a HOME press: MainActivity's HOME routing (MimirHomeKeyRoute) ignores it, so putting Main back
        // as it was never resets a Mimir that happens to be in front.
        val intent = Intent(request.action)
            .addCategory(request.category)
            .setPackage(request.packageName)
            .addFlags(request.flags)
            .putExtra(InternalHomeStartExtra, true)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(request.launchDisplayId)
        context.startActivity(intent, options.toBundle())
    }
}
