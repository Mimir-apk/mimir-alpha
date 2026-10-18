package com.gruning.mimir.rewrite

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.gruning.mimir.rewrite.core.audio.AudioPreferences
import com.gruning.mimir.rewrite.core.audio.MimirMusicSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ask Android for notification permission once, when a background feature first turns on.
 * Declining leaves the performance overlay and M button working without their notification.
 *
 * **Why it exists.** Mimir targets SDK 35 and declared no `POST_NOTIFICATIONS`, so on Android 13 and later neither
 * foreground service's notice reached the shade. On the Thor (test unit, 2026-09-11) PerformanceOverlayService ran in
 * the foreground (id 78, channel `mimir_performance_overlay`) with no NotificationRecord, still none 42 s after the
 * notification was re-posted, while the service itself ran normally.
 *
 * **The contract, and where each half is enforced.**
 *  - *At most once in the app's lifetime.* [NotificationAskLedger.markAsked] is persisted BEFORE Android's dialog is
 *    launched, so no answer, no dismissal and no process death while it is up can earn a second ask. Android's own
 *    "don't ask again" state is never consulted.
 *  - *Only when a background feature is turned on.* The performance overlay asks from
 *    `setPerformanceOverlayVisibility` when a GAMES_ONLY or ALWAYS request ends ENABLED; the Main Utility row, the
 *    Bottom Utility row, the single-screen M panel and the `mimir.overlayVisibility` dev hook all route through it.
 *    The M button asks at the first MButtonKeepAliveService start after the Utility row turned it on
 *    ([NotificationPermissionAsker.armMButton], [NotificationPermissionAsker.onMButtonKeepAliveStarted]); only a
 *    PRIMARY_SINGLE M runs that service, so the Thor's Bottom M never asks.
 *  - *Never over a game or an app on Main.* The dialog needs MainActivity. It is launched only while MainActivity is
 *    RESUMED on display 0 ([NotificationAskHost.inFrontOnMain]) and the session ledger names no Main occupant
 *    ([notificationAskMainOccupant]); otherwise the ask is deferred, persisted, and shown the next time both hold
 *    ([NotificationPermissionAsker.onHomeInFront]).
 *  - *A decline changes nothing but the log.* [NotificationPermissionAsker.onPermissionResult] touches no feature
 *    state, and no service is started, stopped or gated on the grant.
 *
 * One line per decision under [NotificationAskLogTag]:
 * `permission ask=shown|deferred|skipped reason=… feature=performance-overlay|m-button`, and
 * `permission result=granted|denied feature=…` when Android answers.
 */
internal const val NotificationAskLogTag = "MimirNotify"

internal enum class NotificationAskFeature(val logName: String) {
    PERFORMANCE_OVERLAY("performance-overlay"),
    M_BUTTON("m-button"),
    YOUR_MUSIC("your-music"),
}

internal enum class NotificationAskOutcome(val logName: String) {
    SHOWN("shown"),
    DEFERRED("deferred"),
    SKIPPED("skipped"),
}

internal data class NotificationAskDecision(
    val outcome: NotificationAskOutcome,
    val reason: String,
)

internal fun notificationAskLogLine(decision: NotificationAskDecision, feature: NotificationAskFeature): String =
    "permission ask=${decision.outcome.logName} reason=${decision.reason} feature=${feature.logName}"

/**
 * The order of the answers. The permission facts come first because they settle the question for good. Main's
 * occupant is read before MainActivity's own state because it is the reason worth logging: a game on Main also keeps
 * MainActivity stopped, and `main-occupied-by-game` says why where `home-not-in-front` only says what.
 */
internal fun decideNotificationPermissionAsk(
    runtimePermission: Boolean,
    permissionGranted: Boolean,
    alreadyAsked: Boolean,
    mainOccupant: String?,
    homeInFrontOnMain: Boolean,
): NotificationAskDecision = when {
    !runtimePermission -> NotificationAskDecision(NotificationAskOutcome.SKIPPED, "not-a-runtime-permission")
    permissionGranted -> NotificationAskDecision(NotificationAskOutcome.SKIPPED, "already-granted")
    alreadyAsked -> NotificationAskDecision(NotificationAskOutcome.SKIPPED, "already-asked")
    mainOccupant != null -> NotificationAskDecision(NotificationAskOutcome.DEFERRED, "main-occupied-by-$mainOccupant")
    !homeInFrontOnMain -> NotificationAskDecision(NotificationAskOutcome.DEFERRED, "home-not-in-front")
    else -> NotificationAskDecision(NotificationAskOutcome.SHOWN, "feature-on")
}

/**
 * Who holds Main, read from the session ledger ([MimirSessionAuthority]) rather than guessed: `game` for a library game
 * placed on MAIN in any phase [companionMainGameSwapBlocked] counts as owning the primary display (PREPARING through
 * RECOVERING, so the launch intro and a returning emulator both count), `app` for an active PRIMARY app session
 * ([primaryOccupant], which also covers an app the display-0 transfer host moved up). A Bottom game or a Bottom app
 * leaves Main free.
 */
internal fun notificationAskMainOccupant(snapshot: MimirSessionSnapshot): String? = when {
    snapshot.gameSession?.let { companionMainGameSwapBlocked(it.placement, it.phase) } == true -> "game"
    snapshot.primaryOccupant() != null -> "app"
    else -> null
}

/** MainActivity's side, and the only way the ask reaches the screen. */
internal interface NotificationAskHost {
    /** True only while MainActivity is RESUMED on the Main display (display 0). */
    fun inFrontOnMain(): Boolean

    /** Launches Android's POST_NOTIFICATIONS request; false when it could not be launched at all. */
    fun launchNotificationPermissionRequest(): Boolean
}

/** Mimir's own record of the ask. It survives process death and is never inferred from Android's permission flags. */
internal interface NotificationAskLedger {
    val asked: Boolean
    val askedFeature: NotificationAskFeature?
    fun markAsked(feature: NotificationAskFeature)
    fun clearAsked()
    var pending: NotificationAskFeature?
    var mButtonArmed: Boolean
}

internal class SharedPreferencesNotificationAskLedger(context: Context) : NotificationAskLedger {
    private val preferences = context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)

    override val asked: Boolean
        get() = preferences.getBoolean(AskedKey, false)

    override val askedFeature: NotificationAskFeature?
        get() = featureNamed(preferences.getString(AskedFeatureKey, null))

    /** `commit`, not `apply`: this write is what makes "never again" survive a process death while the dialog is up. */
    override fun markAsked(feature: NotificationAskFeature) {
        preferences.edit().putBoolean(AskedKey, true).putString(AskedFeatureKey, feature.name).commit()
    }

    override fun clearAsked() {
        preferences.edit().remove(AskedKey).remove(AskedFeatureKey).commit()
    }

    override var pending: NotificationAskFeature?
        get() = featureNamed(preferences.getString(PendingFeatureKey, null))
        set(value) {
            val editor = preferences.edit()
            if (value == null) editor.remove(PendingFeatureKey) else editor.putString(PendingFeatureKey, value.name)
            editor.apply()
        }

    override var mButtonArmed: Boolean
        get() = preferences.getBoolean(MButtonArmedKey, false)
        set(value) {
            preferences.edit().putBoolean(MButtonArmedKey, value).apply()
        }

    private fun featureNamed(name: String?): NotificationAskFeature? =
        NotificationAskFeature.entries.firstOrNull { it.name == name }

    companion object {
        const val PreferencesName = "mimir_notification_permission"
        const val AskedKey = "asked"
        const val AskedFeatureKey = "asked_feature"
        const val PendingFeatureKey = "pending_feature"
        const val MButtonArmedKey = "m_button_armed"
    }
}

/**
 * The decisions behind [MimirNotificationPermissionAsk], with every Android fact passed in so each rule has a JVM
 * test. Called on the main thread; synchronized so a stray caller cannot interleave a deferral with a launch.
 */
internal class NotificationPermissionAsker(
    private val ledger: NotificationAskLedger,
    private val runtimePermission: () -> Boolean,
    private val permissionGranted: () -> Boolean,
    private val mainOccupant: () -> String?,
    private val featureOn: (NotificationAskFeature) -> Boolean,
    private val host: () -> NotificationAskHost?,
    private val log: (String) -> Unit,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val pendingMutable = MutableStateFlow(ledger.pending)
    private val inFlightMutable = MutableStateFlow<NotificationAskFeature?>(null)
    private var inFlightSinceMs = 0L

    /**
     * Whether [feature]'s own dialog is up and unanswered, so the row that asked must ignore further value steps until the answer
     * returns. On the Nova (2026-10-01 09:31:24) a D-pad step on the performance overlay row asked for POST_NOTIFICATIONS, and a second
     * press landed on the row underneath the dialog and moved Games only to Always. Only the row that asked is held. In memory only
     * (a process that died with the dialog up has no dialog to wait for), and bounded by [InFlightExpiryMs] so an answer that never
     * comes back cannot wedge the row for the life of the process.
     */
    @Synchronized
    fun isPending(feature: NotificationAskFeature): Boolean =
        inFlightMutable.value == feature && clockMs() - inFlightSinceMs < InFlightExpiryMs

    /** The deferred feature, if any. MainActivity re-reads the ledger on every change while it is resumed. */
    val pending: StateFlow<NotificationAskFeature?> = pendingMutable.asStateFlow()

    /** A background feature was just turned on: ask now, defer to the next Home on Main, or say why not. */
    @Synchronized
    fun onFeatureTurnedOn(feature: NotificationAskFeature): NotificationAskDecision {
        val decision = decide()
        if (decision.outcome == NotificationAskOutcome.SHOWN) return show(feature, decision.reason)
        if (decision.outcome == NotificationAskOutcome.DEFERRED) setPending(feature)
        log(notificationAskLogLine(decision, feature))
        return decision
    }

    /**
     * MainActivity is resumed, or the ledger moved while it was: show a deferred ask if Main is free now. Still waiting
     * stays silent, because this runs on every session revision and the deferral was logged when it happened.
     */
    @Synchronized
    fun onHomeInFront(): NotificationAskDecision? {
        val feature = ledger.pending ?: return null
        if (!featureOn(feature)) {
            setPending(null)
            val dropped = NotificationAskDecision(NotificationAskOutcome.SKIPPED, "feature-turned-off")
            log(notificationAskLogLine(dropped, feature))
            return dropped
        }
        val decision = decide()
        return when (decision.outcome) {
            NotificationAskOutcome.SHOWN -> show(feature, "home-in-front")
            NotificationAskOutcome.DEFERRED -> decision
            NotificationAskOutcome.SKIPPED -> {
                setPending(null)
                log(notificationAskLogLine(decision, feature))
                decision
            }
        }
    }

    /** The Utility row turned the M button on. Nothing is weighed until its keep-alive service actually starts. */
    @Synchronized
    fun armMButton() {
        ledger.mButtonArmed = true
    }

    /** The Utility row turned the M button off, so a start still to come is not for that enable. */
    @Synchronized
    fun disarmMButton() {
        ledger.mButtonArmed = false
    }

    /**
     * MButtonKeepAliveService was just started. Only the start that follows a turn-on counts, and it spends the arm:
     * the same call on every resume and every cold restore of a button that was already on asks nothing.
     */
    @Synchronized
    fun onMButtonKeepAliveStarted(): NotificationAskDecision? {
        if (!ledger.mButtonArmed) return null
        ledger.mButtonArmed = false
        return onFeatureTurnedOn(NotificationAskFeature.M_BUTTON)
    }

    /** Android answered. Logged, and deliberately nothing else: O13 keeps every feature running either way. */
    @Synchronized
    fun onPermissionResult(granted: Boolean) {
        inFlightMutable.value = null
        val feature = ledger.askedFeature?.logName ?: "unknown"
        log("permission result=${if (granted) "granted" else "denied"} feature=$feature")
    }

    private fun decide(): NotificationAskDecision = decideNotificationPermissionAsk(
        runtimePermission = runtimePermission(),
        permissionGranted = permissionGranted(),
        alreadyAsked = ledger.asked,
        mainOccupant = mainOccupant(),
        homeInFrontOnMain = host()?.inFrontOnMain() == true,
    )

    private fun show(feature: NotificationAskFeature, reason: String): NotificationAskDecision {
        ledger.markAsked(feature)
        setPending(null)
        // Held BEFORE the launch: the request is asynchronous, and the step that asked must not be able to repeat in between.
        inFlightMutable.value = feature
        inFlightSinceMs = clockMs()
        val launched = runCatching { host()?.launchNotificationPermissionRequest() == true }.getOrDefault(false)
        val decision = if (launched) {
            NotificationAskDecision(NotificationAskOutcome.SHOWN, reason)
        } else {
            inFlightMutable.value = null
            // Nothing reached the screen, so nothing was asked; a later enable may try again.
            ledger.clearAsked()
            NotificationAskDecision(NotificationAskOutcome.SKIPPED, "request-not-launched")
        }
        log(notificationAskLogLine(decision, feature))
        return decision
    }

    private companion object {
        const val InFlightExpiryMs = 120_000L
    }

    private fun setPending(feature: NotificationAskFeature?) {
        ledger.pending = feature
        pendingMutable.value = feature
    }
}

/**
 * The process-wide entry point. Feature code reports what it turned on; MainActivity attaches itself as the only
 * [NotificationAskHost] and calls [onHomeInFront] while it is resumed.
 */
internal object MimirNotificationPermissionAsk {
    @Volatile
    private var asker: NotificationPermissionAsker? = null

    @Volatile
    private var host: NotificationAskHost? = null

    fun attachHost(host: NotificationAskHost) {
        this.host = host
    }

    /** Identity-guarded: a replacement MainActivity can attach before this instance's onDestroy runs. */
    fun detachHost(host: NotificationAskHost) {
        if (this.host === host) this.host = null
    }

    fun pending(context: Context): StateFlow<NotificationAskFeature?> = asker(context).pending

    /** Whether [feature]'s permission dialog is up and unanswered (see [NotificationPermissionAsker.isPending]). */
    fun isAskPending(context: Context, feature: NotificationAskFeature): Boolean = asker(context).isPending(feature)

    fun onFeatureTurnedOn(context: Context, feature: NotificationAskFeature): NotificationAskDecision =
        asker(context).onFeatureTurnedOn(feature)

    fun armMButton(context: Context) = asker(context).armMButton()

    fun disarmMButton(context: Context) = asker(context).disarmMButton()

    fun onMButtonKeepAliveStarted(context: Context): NotificationAskDecision? =
        asker(context).onMButtonKeepAliveStarted()

    fun onHomeInFront(context: Context): NotificationAskDecision? = asker(context).onHomeInFront()

    fun onPermissionResult(context: Context, granted: Boolean) = asker(context).onPermissionResult(granted)

    /** Test seam only: a process-lifetime object otherwise carries one test's ledger and host into the next. */
    internal fun resetForTest() {
        asker = null
        host = null
    }

    private fun asker(context: Context): NotificationPermissionAsker = asker ?: synchronized(this) {
        asker ?: androidAsker(context.applicationContext).also { asker = it }
    }

    private fun androidAsker(appContext: Context) = NotificationPermissionAsker(
        ledger = SharedPreferencesNotificationAskLedger(appContext),
        runtimePermission = { Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU },
        permissionGranted = {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        },
        mainOccupant = { notificationAskMainOccupant(MimirSessionAuthority.state.value) },
        featureOn = { feature ->
            when (feature) {
                NotificationAskFeature.PERFORMANCE_OVERLAY -> {
                    PerformanceOverlaySettingsStore.initialize(appContext)
                    PerformanceOverlaySettingsStore.visibility.value.enabled
                }
                NotificationAskFeature.M_BUTTON -> {
                    MButtonSettingsStore.initialize(appContext)
                    MButtonSettingsStore.visibilityPolicy.value.buttonEnabled
                }
                NotificationAskFeature.YOUR_MUSIC ->
                    AudioPreferences.musicSource.value == MimirMusicSource.CUSTOM && AudioPreferences.musicEnabled.value
            }
        },
        host = { host },
        log = { line -> runCatching { Log.i(NotificationAskLogTag, line) } },
    )
}
