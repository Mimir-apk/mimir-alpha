package com.gruning.mimir.rewrite

import android.content.Intent
import android.util.Log
import android.view.Display
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import com.gruning.mimir.rewrite.core.companionnative.ResumedHostActivity

/**
 * A user HOME press while Mimir is already in front closes open overlays and returns to Home. Before this, KEYCODE_HOME inside Settings left you on Settings: MainActivity is singleTask, so the
 * system's HOME start lands in `onNewIntent`, and nothing there read it.
 *
 * **Who is the user.** The system delivers a HOME press as `ACTION_MAIN` + `CATEGORY_HOME` with the resolved component already set
 * on the delivered intent, so neither the action, the category nor the component tells that press from the HOME-shaped starts
 * Mimir sends itself (and a component on those would retype them STANDARD: see [mainHomeStartRequest]). Every internal one therefore
 * carries [InternalHomeStartExtra]: the recovery start of `MimirMainHomeRecovery` (also used by `MButtonMainRecents.returnToMimir`,
 * which puts Main back as it was), the game-parking start in `CompanionProcessRuntime`, and the floor-front start
 * ([companionFloorFrontIntent]). The marker can only ever SUPPRESS a reset, so another app spoofing it gains nothing, and an
 * unmarked foreign HOME start is what a HOME press looks like. A scan in
 * `MimirHomeKeyDecisionTest` fails any new HOME/SECONDARY_HOME start in the app that is neither marked nor allow-listed.
 *
 * **Already in front.** Launcher3's test (`Launcher.onNewIntent`): the activity was started and held window focus before the intent,
 * and the intent is not `FLAG_ACTIVITY_BROUGHT_TO_FRONT` (the system sets that when this very start moved the task up). Not
 * "RESUMED": ActivityThread pauses a resumed activity around `onNewIntent` (`performNewIntents`), so the lifecycle reads STARTED
 * there for a Mimir that was on top, and a RESUMED test would never fire. Window focus is untouched by that pause and is the thing
 * that tells Mimir-on-top from a game that holds the screen while Mimir is still started behind it. Mimir's overlays are drawn inside
 * its own window (no Compose Dialog or Popup window), so an open one never costs the focus. THE EXCEPTION is a native overlay
 * window: the single-display ᛗ quick menu is a focusable `TYPE_APPLICATION_OVERLAY` while it is open
 * (`primaryQuickMenuOwnsWindowFocus`), and so is the Bottom's menu while it owns the pad. With one of those open the focus is
 * theirs, HOME reads `not_in_front`, and the press is left fail-closed: nothing resets, the menu stays until the player closes it.
 * That controller exposes no public close, so closing it here would need a new call across modules rather than an existing path.
 * Display 0 only for MainActivity itself.
 *
 * **The floor (Thor, 2026-10-08).** With Main driven by the pad only after a Bottom app was reclaimed, the focused display stays 4,
 * so the system sends HOME to display 4's `SECONDARY_HOME` ([CompanionSecondaryHomeActivity]) and MainActivity never sees it. The
 * floor therefore runs the same rule on its own `onNewIntent` ([FloorMainHomeKeyPlatform]): a user HOME / SECONDARY_HOME start that
 * arrives while the FLOOR was the started, focused window, and a MainActivity is resumed on display 0, resets Main. If a third-party
 * app on the Bottom held the focus the floor did not, and the system's own SECONDARY_HOME behaviour is left alone.
 *
 * **Never reset** during first run (the onboarding flow, including the HOME_ROLE grant that relaunches the app with a real HOME
 * intent), during a launch or hand-off ([mainHandOffInProgress]), or for an internal start.
 *
 * **What a reset does** is the dormant `MButtonPrimaryCommand.HOME` the coordinator already handles: every overlay through its own
 * ruled Back path (`dismissConflictingModals`), the Utility panel, launch-return anchors, an open Folio, then Home with focus
 * restored through FocusAuthority. Nothing here touches focus or navigation itself.
 */
internal const val InternalHomeStartExtra = "com.gruning.mimir.rewrite.extra.INTERNAL_HOME_START"

internal enum class MainHomeIntentKind { USER_HOME, INTERNAL_HOME, OTHER }

/** Which window the HOME start landed on, and so which categories count as HOME for it. */
internal enum class MainHomeKeySource(val token: String, val categories: List<String>) {
    /** MainActivity itself, when display 0 holds the focus. */
    MAIN("main", listOf(Intent.CATEGORY_HOME)),

    /** Display 4's secondary-home floor, when the Bottom display holds the focus. */
    FLOOR("floor", listOf(Intent.CATEGORY_HOME, Intent.CATEGORY_SECONDARY_HOME)),
}

/** What a MainActivity-bound intent is, from the three things that decide it. Anything unreadable is [MainHomeIntentKind.OTHER]. */
internal fun classifyMainHomeIntent(action: String?, homeCategory: Boolean, internalMarker: Boolean): MainHomeIntentKind = when {
    action != Intent.ACTION_MAIN || !homeCategory -> MainHomeIntentKind.OTHER
    internalMarker -> MainHomeIntentKind.INTERNAL_HOME
    else -> MainHomeIntentKind.USER_HOME
}

internal fun Intent.mainHomeIntentKind(source: MainHomeKeySource = MainHomeKeySource.MAIN): MainHomeIntentKind = runCatching {
    classifyMainHomeIntent(
        action = action,
        homeCategory = source.categories.any { category -> hasCategory(category) },
        internalMarker = getBooleanExtra(InternalHomeStartExtra, false),
    )
}.getOrDefault(MainHomeIntentKind.OTHER)

/** Everything the decision reads, sampled before the intent changes anything. */
internal data class MainHomeKeyFacts(
    val intentKind: MainHomeIntentKind,
    val startedBeforeIntent: Boolean,
    val hadWindowFocusBeforeIntent: Boolean,
    val broughtToFront: Boolean,
    val onMainDisplay: Boolean,
    val firstRun: Boolean,
    val handOffInProgress: Boolean,
    /** A MainActivity is resumed on display 0 to be reset. Trivially true when the press landed on MainActivity itself. */
    val mainResumed: Boolean = true,
)

internal enum class MainHomeKeyDecision(val token: String) {
    RETURN_HOME("return_home"),
    NOT_HOME_INTENT("not_home_intent"),
    INTERNAL_START("internal_start"),
    OTHER_DISPLAY("other_display"),
    NOT_IN_FRONT("not_in_front"),
    BROUGHT_TO_FRONT("brought_to_front"),
    MAIN_NOT_RESUMED("main_not_resumed"),
    FIRST_RUN("first_run"),
    HAND_OFF("hand_off"),
}

/** The one rule. Each refusal is its own branch so a log line names the reason and a test pins each. */
internal fun decideMainHomeKey(facts: MainHomeKeyFacts): MainHomeKeyDecision = when {
    facts.intentKind == MainHomeIntentKind.OTHER -> MainHomeKeyDecision.NOT_HOME_INTENT
    facts.intentKind == MainHomeIntentKind.INTERNAL_HOME -> MainHomeKeyDecision.INTERNAL_START
    !facts.onMainDisplay -> MainHomeKeyDecision.OTHER_DISPLAY
    !facts.startedBeforeIntent || !facts.hadWindowFocusBeforeIntent -> MainHomeKeyDecision.NOT_IN_FRONT
    facts.broughtToFront -> MainHomeKeyDecision.BROUGHT_TO_FRONT
    !facts.mainResumed -> MainHomeKeyDecision.MAIN_NOT_RESUMED
    facts.firstRun -> MainHomeKeyDecision.FIRST_RUN
    facts.handOffInProgress -> MainHomeKeyDecision.HAND_OFF
    else -> MainHomeKeyDecision.RETURN_HOME
}

/**
 * Phases in which a launch or a return still owns the screen. FOREGROUND_PROVEN and YIELDED_TO_SECONDARY_TASK are settled
 * sessions (a game running on the Bottom while the player browses Main) and must not block a HOME press; ENDED, FAILED and
 * STALE are over.
 */
private val HandOffPhases = setOf(
    MimirSessionPhase.PREPARING,
    MimirSessionPhase.DISPATCHED,
    MimirSessionPhase.RETURNING,
    MimirSessionPhase.RECOVERING,
)

internal fun mainHandOffInProgress(sessions: MimirSessionSnapshot): Boolean =
    sessions.gameSession?.phase?.let { it in HandOffPhases } == true ||
        sessions.primaryAppSession?.phase?.let { it in HandOffPhases } == true ||
        sessions.secondaryAppSession?.phase?.let { it in HandOffPhases } == true

/** What the router reads and does outside the decision, so the routing is testable without an Activity. */
internal interface MainHomeKeyPlatform {
    /** Where the intent landed; MainActivity unless a platform says otherwise. */
    val source: MainHomeKeySource get() = MainHomeKeySource.MAIN

    fun startedBeforeIntent(): Boolean
    fun hadWindowFocusBeforeIntent(): Boolean
    fun onMainDisplay(): Boolean

    /** A MainActivity is resumed on display 0. MainActivity's own platform is that Activity, so the default is true. */
    fun mainResumed(): Boolean = true

    fun firstRun(): Boolean
    fun sessions(): MimirSessionSnapshot
    fun publishHome()
    fun log(line: String)

    /**
     * F3: the press arrived while something else held the screen (`not_in_front`). Main's platform parks a running single-screen
     * game here ([MainGameHomePark]) instead of letting the resume this press causes end its session. Nothing else overrides it.
     */
    fun parkGameForHome(decision: MainHomeKeyDecision) {}
}

/**
 * Call from `onNewIntent` (MainActivity's, or the floor's) after the intent is adopted. Samples the facts, decides, and on
 * [MainHomeKeyDecision.RETURN_HOME] publishes the HOME command. A non-HOME intent costs one classification and logs nothing; every
 * HOME-shaped one leaves a `MimirHomeKey: home key decision=… reason=… source=main|floor` line, so a device run can see a press
 * that did nothing and why.
 */
internal fun routeMainHomeKeyIntent(intent: Intent, platform: MainHomeKeyPlatform): MainHomeKeyDecision {
    val source = platform.source
    val kind = intent.mainHomeIntentKind(source)
    if (kind == MainHomeIntentKind.OTHER) return MainHomeKeyDecision.NOT_HOME_INTENT
    if (kind == MainHomeIntentKind.USER_HOME) UserLeaveSignal.noteHome()
    val decision = decideMainHomeKey(
        MainHomeKeyFacts(
            intentKind = kind,
            startedBeforeIntent = platform.startedBeforeIntent(),
            hadWindowFocusBeforeIntent = platform.hadWindowFocusBeforeIntent(),
            broughtToFront = intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0,
            onMainDisplay = platform.onMainDisplay(),
            firstRun = platform.firstRun(),
            handOffInProgress = mainHandOffInProgress(platform.sessions()),
            mainResumed = platform.mainResumed(),
        ),
    )
    if (decision == MainHomeKeyDecision.RETURN_HOME) platform.publishHome()
    if (decision == MainHomeKeyDecision.NOT_IN_FRONT) platform.parkGameForHome(decision)
    platform.log(
        "home key decision=${if (decision == MainHomeKeyDecision.RETURN_HOME) "reset" else "skip"} " +
            "reason=${decision.token} source=${source.token}",
    )
    return decision
}

/** The live platform: MainActivity's own lifecycle, window and process state. */
internal class ActivityMainHomeKeyPlatform(private val activity: ComponentActivity) : MainHomeKeyPlatform {
    /** STARTED, not RESUMED: the system pauses a resumed activity around onNewIntent, so a Mimir that was on top reads STARTED here. */
    override fun startedBeforeIntent(): Boolean = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    override fun hadWindowFocusBeforeIntent(): Boolean = activity.window.decorView.hasWindowFocus()

    override fun onMainDisplay(): Boolean = activity.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY

    override fun firstRun(): Boolean = OnboardingStore.isFirstRun(activity)

    override fun sessions(): MimirSessionSnapshot = MimirSessionAuthority.state.value

    override fun publishHome() {
        MButtonNavigationCommands.publish(MButtonPrimaryCommand.HOME)
    }

    override fun log(line: String) {
        runCatching { Log.i("MimirHomeKey", line) }
    }

    override fun parkGameForHome(decision: MainHomeKeyDecision) {
        MainGameHomePark.onHomeKey(activity.applicationContext, decision, source)
    }
}

/**
 * Display 4's floor ([CompanionSecondaryHomeActivity]) as the window a HOME press landed on. "In front" is the FLOOR being started
 * and focused before the intent (so a third-party app that held the Bottom's focus never counts), and what is reset is the
 * MainActivity that [ResumedHostActivity] says is resumed on display 0. Publish, log, session and first-run reads are MainActivity's.
 */
internal class FloorMainHomeKeyPlatform(
    private val floor: ComponentActivity,
    private val resumedMain: () -> android.app.Activity? = { ResumedHostActivity.current() },
) : MainHomeKeyPlatform {
    private val main = ActivityMainHomeKeyPlatform(floor)

    override val source: MainHomeKeySource get() = MainHomeKeySource.FLOOR

    override fun startedBeforeIntent(): Boolean = main.startedBeforeIntent()

    override fun hadWindowFocusBeforeIntent(): Boolean = main.hadWindowFocusBeforeIntent()

    /** The floor lives on the secondary display by construction; "Main's display" is checked on the Main that will be reset. */
    override fun onMainDisplay(): Boolean = true

    override fun mainResumed(): Boolean {
        val activity = resumedMain() as? MainActivity ?: return false
        return activity.window.decorView.display?.displayId == Display.DEFAULT_DISPLAY
    }

    override fun firstRun(): Boolean = main.firstRun()

    override fun sessions(): MimirSessionSnapshot = main.sessions()

    override fun publishHome() = main.publishHome()

    override fun log(line: String) = main.log(line)
}
