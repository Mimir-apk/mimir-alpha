package com.gruning.mimir.rewrite

import android.app.usage.UsageEvents
import android.content.Context
import android.view.Display
import com.gruning.mimir.rewrite.core.companionnative.QuickMenuOrigin
import com.gruning.mimir.rewrite.core.companionnative.SecondaryDisplayState

/**
 *  The light
 * tap on the Bottom's ᛗ coin (W2b) opens Android Recents on Main; while Recents is up, the same tap closes it and puts Main back
 * the way it was.
 *
 * **187 only ever OPENS.** The only way this app opens Recents is `input keyevent 187` (KEYCODE_APP_SWITCH) through the one
 * certified PServer operation. A second 187 while Recents is showing is not "close": in AOSP 13 Launcher3
 * (`OverviewCommandHelper.executeCommand`, TYPE_TOGGLE with Recents visible) it runs `launchTask(getNextTask())`, the task AFTER
 * the one that was on top (a quick switch), or home when there is none. So this path never presses it while Recents is up. The
 * way to CLOSE is to bring something back over it: the prior top when it is known and comes back, and otherwise Mimir's Main by
 * the HOME start `mainHomeStartRequest` already makes (Mimir is the default home, which AOSP lets start activities from the
 * background). A close that cannot do either is a refused close, and says so; it never falls to the key.
 *
 * **What was on top: captured when OUR tap opens Recents.** Just before the key, [MButtonMainRecents.captureBeforeOpen] records
 * the last resumed activity that is not a launcher, not Recents, not the Bottom's floor and not a package the task registry
 * places on display 4, with the time. The close uses that record while it is the Recents we opened (its resume is within
 * [MainRecentsOpenMatchWindowMs] of the press). Pause timing ([MainRecentsPriorPauseWindowMs]) is the inference for a Recents
 * opened by other means, and two candidates is no answer.
 *
 * **Is Recents up?** UsageStats lifecycle events, the app's own Usage Access (the reader `MimirMainHomeRecovery` has), never a
 * flag. Quickstep's `RecentsActivity` runs only on the default display and is up when its LAST event is a resume AND no other
 * Main activity resumed after it in the same snapshot. The read reaches back to the open press while that record is fresh, so a
 * Recents left up for an hour is still seen; for one opened by other means it reaches [MainRecentsLookbackMs]. A read that
 * cannot say (no Usage Access, unreadable) opens.
 *
 * **Source of truth, and why it is UsageStats (ledger D35).** Nothing Mimir holds knows what Main's player picked out of Recents:
 * the task registry (`AndroidSecondaryTaskRegistry`) tracks only what Mimir launches or moves, and its display-0 side is not a
 * foreground record; the unprivileged ActivityManager task calls see only Mimir's own tasks; and a new privileged call is not
 * allowed. So the read stays UsageStats and is made fresh two ways.
 * - **A pick that has not been written yet.** UsageStats lags the transition, so a tap just after the player picked an app reads
 *   Recents as still up. [settledMainRecentsReading]: when a first read says UP, the toggle waits [MainRecentsSettleMs] (AOSP's own
 *   pause timeout, within which the pick's pause and resume land) and reads again; the close is planned from the SECOND read. A
 *   read that now shows the pick says not up, so the tap opens Recents again and never restores the earlier app over the chosen
 *   one. Every close pays that wait; an open (Recents not up, or unreadable) pays none. There is no polling while idle.
 * - **A Recents left up for a long time.** A window with no Recents event and no Main resume says nothing about Main, so
 *   [readMainRecentsDeep] reads once more, further back to [MainRecentsDeepLookbackMs], to find the last thing that happened on
 *   Main. A Recents opened by other means more than [MainRecentsLookbackMs] ago is then still up, and a tap closes it instead
 *   of pressing the open key into it (which quick-switches in AOSP 13). A window that already names an event on Main costs
 *   nothing extra.
 *
 * **Still open (ledger D35).** UsageStats carries no display id, so display 4 is excluded by registry and by the display-0 task
 * resolve below, not by the event (3). A lag longer than [MainRecentsSettleMs] still slips through. The hold menu's Tabs row is a
 * plain open that reads nothing (4), by the rule above. The registry snapshot behind [bottomDisplayPackages] holds 24 entries, so
 * an older Bottom package's later resume can read Main's Recents as closed (5, logged for diagnosis).
 *
 * **Bringing a task back, without a new privileged operation.** Another package: the existing `resolveTaskId` on display 0 and
 * `focusTask`, admitted only for launch targets and never for a package the registry puts on display 4. Mimir's own
 * MainActivity, and every step above that cannot be done: the HOME start. Nothing is sent to display 4.
 *
 * BUILT, not device-certified: whether the HOME start or `am task focus` leaves Main exactly where it was is the Thor's to say.
 */
internal const val MainRecentsLookbackMs = 15 * 60_000L

/** How far before Recents' resume the prior top's pause may be and still be the activity Recents covered. */
internal const val MainRecentsPriorPauseWindowMs = 1_500L

/** The resume of the Recents our press opened lands this soon after the press (and may be stamped a hair before it). */
internal const val MainRecentsOpenMatchBeforeMs = 500L
internal const val MainRecentsOpenMatchWindowMs = 5_000L

/** A capture older than this is expired: a Recents it opened is read through the ordinary window and nothing is trusted from it. */
internal const val MainRecentsRecordMaxAgeMs = 6 * 60 * 60_000L

/** How far the one deeper read reaches when the ordinary window says nothing about Main: a Recents left up for hours is found. */
internal const val MainRecentsDeepLookbackMs = 24 * 60 * 60_000L

/** How long a tap that found Recents up waits for a pick's resume to land (AOSP's activity pause timeout) before it reads again. */
internal const val MainRecentsSettleMs = 500L

internal enum class MainRecentsPresence { UP, NOT_UP, UNKNOWN }

/** [priorTop] is a `package/class` component, only when [presence] is UP. */
internal data class MainRecentsReading(val presence: MainRecentsPresence, val priorTop: String? = null) {
    companion object {
        val Unknown = MainRecentsReading(MainRecentsPresence.UNKNOWN)
    }
}

/** What our own open press saw on top, and when it pressed. [priorTop] is null when nothing qualified. */
internal data class MainRecentsOpenRecord(val priorTop: String?, val openedAtMs: Long)

/** Who is not "what Main showed": [components] are the launchers and the Bottom floor, [bottomPackages] the display-4 tasks. */
internal data class MainRecentsExclusions(
    val components: Set<String>,
    val bottomPackages: Set<String>,
    /** The Bottom's own floor components, whose resumes never end a Main Recents. */
    val bottomComponents: Set<String> = emptySet(),
) {
    fun excludes(component: String): Boolean =
        component == QuickstepRecentsComponent ||
            component in components ||
            component.substringBefore('/') in bottomPackages

    /** The Bottom's floor or a display-4 task: it resumes without Main changing. */
    fun isBottom(component: String): Boolean =
        component in bottomComponents || component.substringBefore('/') in bottomPackages
}

private fun MainHomeActivityEvent.component(): String? = className?.let { "$packageName/$it" }

/** The last resumed activity that could have been on Main, most recently resumed first; null when none qualifies. */
internal fun capturePriorTop(events: List<MainHomeActivityEvent>?, exclusions: MainRecentsExclusions): String? {
    events ?: return null
    return resumedActivityComponents(events).firstOrNull { !exclusions.excludes(it) }
}

/**
 * [events] is the UsageStats activity lifecycle read, or null when it could not be read. [record] is what our own open press
 * captured, if it did.
 */
internal fun readMainRecents(
    events: List<MainHomeActivityEvent>?,
    exclusions: MainRecentsExclusions,
    record: MainRecentsOpenRecord? = null,
): MainRecentsReading {
    events ?: return MainRecentsReading.Unknown
    val recents = events
        .filter { it.component() == QuickstepRecentsComponent }
        .maxByOrNull(MainHomeActivityEvent::timeMs)
        ?: return MainRecentsReading(MainRecentsPresence.NOT_UP)
    if (recents.type != UsageEvents.Event.ACTIVITY_RESUMED) return MainRecentsReading(MainRecentsPresence.NOT_UP)
    // A snapshot that already holds a later resume of anything on Main says Recents has been left. Resumes of the Bottom's own
    // floor and of display-4 tasks happen without Main changing, so they do not count.
    val leftForSomethingElse = events.any { event ->
        val component = event.component()
        event.type == UsageEvents.Event.ACTIVITY_RESUMED &&
            event.timeMs > recents.timeMs &&
            component != null &&
            component != QuickstepRecentsComponent &&
            !exclusions.isBottom(component)
    }
    if (leftForSomethingElse) return MainRecentsReading(MainRecentsPresence.NOT_UP)
    // A capture that named something is the answer for the Recents our press opened. A null capture is "unknown", not "nothing was on
    // top" (an app in the foreground longer than the capture window has no resume in it), so it falls through to the pause below.
    if (record?.priorTop != null &&
        recents.timeMs in (record.openedAtMs - MainRecentsOpenMatchBeforeMs)..(record.openedAtMs + MainRecentsOpenMatchWindowMs)
    ) {
        return MainRecentsReading(MainRecentsPresence.UP, record.priorTop)
    }
    val covered = events
        .asSequence()
        .filter { it.type == UsageEvents.Event.ACTIVITY_PAUSED }
        .filter { it.timeMs in (recents.timeMs - MainRecentsPriorPauseWindowMs)..recents.timeMs }
        .mapNotNull { it.component() }
        .filter { !exclusions.excludes(it) }
        .distinct()
        .toList()
    return MainRecentsReading(MainRecentsPresence.UP, covered.singleOrNull())
}

/**
 * True when [events] say nothing about Main: no Recents event and no resume of anything that is not the Bottom's. Whether Recents
 * is up cannot be read from such a window, only from the last thing that happened on Main before it.
 */
internal fun mainRecentsWindowSilent(events: List<MainHomeActivityEvent>, exclusions: MainRecentsExclusions): Boolean =
    events.none { event ->
        val component = event.component()
        component != null &&
            (component == QuickstepRecentsComponent ||
                (event.type == UsageEvents.Event.ACTIVITY_RESUMED && !exclusions.isBottom(component)))
    }

/**
 * [readMainRecents] over the ordinary window [shallowFromMs]..[nowMs], and, only when that window is silent about Main, once more
 * over the older slice back to [MainRecentsDeepLookbackMs]. The newest event on Main decides, and the ordinary window reaches
 * now, so the first slice that holds one is the answer. [query] is the platform read; a deeper read that fails keeps the
 * ordinary reading.
 */
internal fun readMainRecentsDeep(
    nowMs: Long,
    shallowFromMs: Long,
    exclusions: MainRecentsExclusions,
    record: MainRecentsOpenRecord?,
    query: (fromMs: Long, toMs: Long) -> List<MainHomeActivityEvent>?,
): MainRecentsReading {
    val shallow = query(shallowFromMs, nowMs)
    val ordinary = readMainRecents(shallow, exclusions, record)
    if (shallow == null || !mainRecentsWindowSilent(shallow, exclusions)) return ordinary
    val deepFromMs = nowMs - MainRecentsDeepLookbackMs
    if (deepFromMs >= shallowFromMs) return ordinary
    val older = query(deepFromMs, shallowFromMs) ?: return ordinary
    return readMainRecents((older + shallow).distinct(), exclusions, record)
}

/** What one coin tap does. [Open] is also what every unsure reading plans. */
internal sealed interface RecentsTapPlan {
    data object Open : RecentsTapPlan

    /** Recents is up. [priorTop] is what it covered, or null when nothing says: then Mimir's Main comes back. */
    data class Close(val priorTop: String?) : RecentsTapPlan
}

internal fun recentsTapPlan(reading: MainRecentsReading): RecentsTapPlan =
    if (reading.presence == MainRecentsPresence.UP) RecentsTapPlan.Close(reading.priorTop) else RecentsTapPlan.Open

/** The `target=` of the `m-button.recents` receipt. */
internal enum class RecentsTapAction(val token: String) {
    /** Recents was not up (or could not be told): the open key. */
    OPEN("open"),

    /** Recents was up and the prior top came back. */
    CLOSE("close"),

    /** Recents was up and Mimir's Main came back, because the prior top was unknown, ambiguous, on display 4 or would not come back. */
    CLOSE_HOME("close_home"),
}

/** [press] is the open key's own result, null when no key was sent. */
internal data class MButtonRecentsTapResult(
    val action: RecentsTapAction,
    val accepted: Boolean,
    val press: MButtonSystemRecentsOpenResult? = null,
)

internal fun recentsCloseFailureCopy(): String = "Mimir couldn’t return to the previous screen."

/** The platform half: what Recents shows and how Main comes back. Reads and starts run off the main thread. */
internal interface MButtonMainRecents {
    fun read(): MainRecentsReading

    /** Records what is on Main just before OUR press opens Recents. */
    fun captureBeforeOpen()

    /**
     * Waits for a pick's resume to land, bounded by [MainRecentsSettleMs]. Blocks; only called off the main thread, right after a
     * read that said Recents is up. Nothing to wait for by default.
     */
    fun settle() = Unit

    /** True only when the prior top was actually asked to come to the front on display 0. */
    fun restore(component: String): Boolean

    /** Mimir's Main by the HOME start; true when it was started. */
    fun returnToMimir(): Boolean

    data object Unavailable : MButtonMainRecents {
        override fun read(): MainRecentsReading = MainRecentsReading.Unknown
        override fun captureBeforeOpen() = Unit
        override fun restore(component: String): Boolean = false
        override fun returnToMimir(): Boolean = false
    }
}

/**
 * The reading a coin tap plans from. A first read that says Recents is up may trail a pick the player just made, so the tap waits
 * ([MButtonMainRecents.settle]) and plans from a second read: a close is never decided on the first reading alone. A first read that
 * says not up, or cannot say, is final and waits for nothing. A second read that cannot say keeps the first, which did.
 */
internal fun settledMainRecentsReading(mainRecents: MButtonMainRecents): MainRecentsReading {
    val first = mainRecents.read()
    if (first.presence != MainRecentsPresence.UP) return first
    mainRecents.settle()
    val second = mainRecents.read()
    return if (second.presence == MainRecentsPresence.UNKNOWN) first else second
}

/**
 * One tap. A menu row's Tabs request ([QuickMenuOrigin.MENU_ROW]) is the plain open and reads nothing; it, too, records what was on
 * top before it presses. The only key this function presses is [openSystemRecentsFromMButton], and only for an open.
 */
internal fun runMButtonRecentsTap(
    origin: QuickMenuOrigin,
    mainRecents: MButtonMainRecents,
    systemRecents: MButtonSystemRecentsGateway,
): MButtonRecentsTapResult {
    val plan = if (origin == QuickMenuOrigin.COIN_TAP) recentsTapPlan(settledMainRecentsReading(mainRecents)) else RecentsTapPlan.Open
    if (plan is RecentsTapPlan.Close) {
        if (plan.priorTop != null && mainRecents.restore(plan.priorTop)) {
            return MButtonRecentsTapResult(RecentsTapAction.CLOSE, accepted = true)
        }
        return MButtonRecentsTapResult(RecentsTapAction.CLOSE_HOME, accepted = mainRecents.returnToMimir())
    }
    mainRecents.captureBeforeOpen()
    val result = openSystemRecentsFromMButton(systemRecents)
    return MButtonRecentsTapResult(RecentsTapAction.OPEN, result.accepted, result)
}

/**
 * Bringing one component back. Another package takes the existing task resolve (on the default display) and focus, admitted only
 * for launch targets, and never for a package in [bottomPackages]. Mimir's own components, and anything that cannot be done, are
 * false: the caller returns to Mimir's Main by the HOME start.
 */
internal fun restoreMainTop(
    component: String,
    selfPackage: String,
    bottomPackages: Set<String>,
    tasks: PrivilegedTaskCloseGateway,
): Boolean {
    val packageName = component.substringBefore('/')
    if (packageName == selfPackage || packageName in bottomPackages) return false
    val taskId = tasks.resolveTaskId(packageName, Display.DEFAULT_DISPLAY) ?: return false
    return tasks.focusTask(taskId) == PrivilegedTaskFocus.FOCUSED
}

/** The packages the task registry places on the secondary display: what is on display 4 and so never what Main showed. */
internal fun bottomDisplayPackages(state: SecondaryDisplayState): Set<String> {
    val secondary = state.secondaryDisplayId ?: return emptySet()
    if (secondary == state.primaryDisplayId) return emptySet()
    return buildSet {
        state.recents.filter { it.displayId == secondary }.mapTo(this) { it.packageName }
        state.foregroundByDisplay[secondary]?.let(::add)
    }
}

internal class AndroidMButtonMainRecents(
    private val platform: MainHomeRecoveryPlatform,
    private val tasks: PrivilegedTaskCloseGateway,
    private val secondaryState: () -> SecondaryDisplayState,
    /** Blocks the calling (IO) thread for this long; a seam so the settle is a virtual clock in tests. */
    private val pause: (Long) -> Unit = ::sleepBlocking,
) : MButtonMainRecents {
    constructor(
        context: Context,
        tasks: PrivilegedTaskCloseGateway,
        secondaryState: () -> SecondaryDisplayState,
    ) : this(AndroidMainHomeRecoveryPlatform(context.applicationContext), tasks, secondaryState)

    @Volatile
    private var record: MainRecentsOpenRecord? = null

    private fun exclusions(): MainRecentsExclusions {
        val secondaryHome = platform.secondaryHomeComponents()
        // Mimir's own HOME-declared MainActivity is a valid prior top: only foreign launchers are excluded.
        val foreignHomes = platform.homeComponents().filterTo(mutableSetOf()) { it.substringBefore('/') != platform.selfPackage }
        return MainRecentsExclusions(
            components = foreignHomes + secondaryHome,
            bottomPackages = bottomDisplayPackages(secondaryState()),
            bottomComponents = secondaryHome,
        )
    }

    override fun read(): MainRecentsReading {
        if (!platform.usageAccessGranted()) return MainRecentsReading.Unknown
        val now = platform.wallClockNowMs()
        val fresh = record?.takeIf { now - it.openedAtMs in 0..MainRecentsRecordMaxAgeMs }
        val from = if (fresh != null) minOf(now - MainRecentsLookbackMs, fresh.openedAtMs - MainRecentsOpenMatchWindowMs) else now - MainRecentsLookbackMs
        return readMainRecentsDeep(now, from, exclusions(), fresh, platform::activityEvents)
    }

    override fun settle() = pause(MainRecentsSettleMs)

    override fun captureBeforeOpen() {
        record = null
        if (!platform.usageAccessGranted()) return
        val now = platform.wallClockNowMs()
        val events = platform.activityEvents(now - MainRecentsLookbackMs, now) ?: return
        record = MainRecentsOpenRecord(capturePriorTop(events, exclusions()), now)
    }

    override fun restore(component: String): Boolean = restoreMainTop(
        component = component,
        selfPackage = platform.selfPackage,
        bottomPackages = bottomDisplayPackages(secondaryState()),
        tasks = tasks,
    )

    override fun returnToMimir(): Boolean =
        runCatching { platform.start(mainHomeStartRequest(platform.selfPackage)) }.isSuccess
}

private fun sleepBlocking(ms: Long) {
    try {
        Thread.sleep(ms)
    } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
    }
}
