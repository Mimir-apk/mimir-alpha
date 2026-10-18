package com.gruning.mimir.rewrite

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gruning.mimir.rewrite.core.companionnative.AndroidExternalForegroundMonitor
import com.gruning.mimir.rewrite.core.tweaker.TweakerTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.roundToLong

internal fun performanceOverlayShouldRun(
    visibility: PerformanceOverlayVisibility,
    canDrawOverlays: Boolean,
): Boolean = visibility.enabled && canDrawOverlays

/**
 * Phases where Mimir's own dispatch truth still counts a library game as "out there" for GAMES_ONLY.
 * PREPARING is excluded (Mimir itself is still on screen); RETURNING/RECOVERING/ENDED/FAILED/STALE are all
 * points where Mimir has already decided the game is not the presentation anymore. FOREGROUND_PROVEN is
 * unreachable for games today (nothing calls [MimirSessionAuthority.gameForegroundProven] yet) but is kept
 * here so wiring it up later does not silently regress this gate.
 */
private val PerformanceOverlayActiveGamePhases = setOf(
    MimirSessionPhase.DISPATCHED,
    MimirSessionPhase.FOREGROUND_PROVEN,
    MimirSessionPhase.YIELDED_TO_SECONDARY_TASK,
)

/**
 * The package name of the library game Mimir's own launch lifecycle currently believes is out there, or
 * null if none. This is definition (a) of "a game" for GAMES_ONLY — an app Mimir itself dispatched from its
 * library — never a platform classification of whatever the foreground happens to be. Native/Android-app
 * launches ([MimirSessionSnapshot.primaryAppSession]) are deliberately not counted.
 */
internal fun performanceOverlayDispatchedGamePackageName(gameSession: MimirGameSession?): String? {
    val game = gameSession ?: return null
    if (game.phase !in PerformanceOverlayActiveGamePhases) return null
    return game.targetPackageName?.takeIf(String::isNotBlank)
}

/**
 * What GAMES_ONLY currently knows about the real foreground, sourced from the APP's own Usage Access via
 * the already-running [AndroidExternalForegroundMonitor] instance (`CompanionProcessRuntime`'s shared
 * singleton) — never Tweaker's. Tweaker's own read (`TweakerTelemetry.foregroundPackageName`) is gated on
 * Usage Access granted to `com.gruning.mimir.tweaker`, a grant Mimir does not control and cannot observe;
 * granting Usage Access to Mimir itself did nothing for it. It was also slow ([PerformanceTelemetryPollMs]/
 * [PerformanceTelemetryStaleMs]: 1-3s) and collapsed ACCESS_DENIED, QUERY_FAILED, and a genuinely empty
 * foreground into one null. These four cases are kept distinct on purpose — collapsing "Usage Access
 * missing" into "no observation yet" was exactly that defect.
 */
internal sealed interface PerformanceOverlayForegroundSignal {
    /** com.gruning.mimir.rewrite's own PACKAGE_USAGE_STATS appop is not granted; the cross-check cannot run
     *  at all. Kept distinct so this can never silently read as a confirmed neutral or foreign foreground. */
    data object UsageAccessMissing : PerformanceOverlayForegroundSignal

    /** Usage Access is granted, but no debounced foreground has settled yet since this service started
     *  watching — still warming, or a genuine gap in observations. Transient: never a confirmed absence. */
    data object AwaitingObservation : PerformanceOverlayForegroundSignal

    /** Usage Access is granted and the settled foreground is a neutral surface: Mimir's own primary UI
     *  (confirmed immediately via [PrimaryActivityObservationState], independent of UsageStats latency), or
     *  a package [AndroidExternalForegroundMonitor] itself already excludes from foreign candidates (its own
     *  reclaim host, launcher/SystemUI shells). */
    data object NeutralForeground : PerformanceOverlayForegroundSignal

    /** Usage Access is granted and the settled foreground is this specific foreign package. */
    data class ForeignForeground(val packageName: String) : PerformanceOverlayForegroundSignal
}

/**
 * Combines the three raw reads into one honest [PerformanceOverlayForegroundSignal]. Usage Access wins over
 * everything else — without it the cross-check cannot run, and that must never be silently reported as a
 * confirmed neutral or foreign foreground. [mimirPrimaryResumed] then wins over any stale settled package:
 * Mimir's own Activity lifecycle is exact and unconditional, unlike a UsageStats poll that may not even be
 * running right now.
 */
internal fun performanceOverlayForegroundSignal(
    usageAccessGranted: Boolean,
    mimirPrimaryResumed: Boolean,
    settledForeignPackageName: String?,
): PerformanceOverlayForegroundSignal = when {
    !usageAccessGranted -> PerformanceOverlayForegroundSignal.UsageAccessMissing
    mimirPrimaryResumed -> PerformanceOverlayForegroundSignal.NeutralForeground
    settledForeignPackageName != null ->
        PerformanceOverlayForegroundSignal.ForeignForeground(settledForeignPackageName)
    else -> PerformanceOverlayForegroundSignal.AwaitingObservation
}

/** A current GameNative observation is also a valid native-shelf game host for the HUD. */
internal fun performanceOverlayGameNativeForegroundProven(
    foregroundSignal: PerformanceOverlayForegroundSignal,
): Boolean = foregroundSignal is PerformanceOverlayForegroundSignal.ForeignForeground &&
    foregroundSignal.packageName == "app.gamenative"

/**
 * Returns a live renderer-owning package for the HUD. The reader is queried on every beat, so the
 * debounced transition tracker cannot be mistaken for current foreground proof. No package/path
 * supplied by the HUD crosses the privileged seam unless this proof succeeds.
 */
internal fun performanceOverlayForegroundRenderPackage(
    usageAccessGranted: Boolean,
    mimirPrimaryResumed: Boolean,
    interactive: Boolean,
    currentForegroundPackageName: String?,
): String? {
    if (!usageAccessGranted || mimirPrimaryResumed || !interactive) return null
    return currentForegroundPackageName?.takeIf(::isEmulatorRenderFpsPackage)
}

/** Two consecutive matching observations before GAMES_ONLY trusts a newly-seen foreign package. */
internal const val PerformanceOverlayForegroundDebounceObservations = 2

/**
 * Settles [AndroidExternalForegroundMonitor.foreignForegroundObserved] edges into one trusted package name.
 * A single blip — one flashing system window during an app-to-app transition — must never toggle the card;
 * [PerformanceOverlayForegroundDebounceObservations] consecutive observations of the SAME package are
 * required before it becomes [settledForeignPackageName]. Re-observing an already-settled package (a quick
 * alt-tab back to the same foreign app) re-confirms instantly instead of re-debouncing from scratch.
 * Mutating methods are synchronized because the owning service collects observations on a background
 * coroutine while presentation reads [settledForeignPackageName] from another.
 */
internal class PerformanceOverlayGamesOnlyForegroundTracker {
    private var pendingPackageName: String? = null
    private var pendingObservationCount: Int = 0

    @Volatile
    var settledForeignPackageName: String? = null
        private set

    @Synchronized
    fun observeForeign(packageName: String) {
        if (packageName == settledForeignPackageName) {
            pendingPackageName = null
            pendingObservationCount = 0
            return
        }
        if (packageName == pendingPackageName) {
            pendingObservationCount += 1
        } else {
            pendingPackageName = packageName
            pendingObservationCount = 1
        }
        if (pendingObservationCount >= PerformanceOverlayForegroundDebounceObservations) {
            settledForeignPackageName = packageName
            pendingPackageName = null
            pendingObservationCount = 0
        }
    }

    @Synchronized
    fun reset() {
        pendingPackageName = null
        pendingObservationCount = 0
        settledForeignPackageName = null
    }
}

/**
 * Whether GAMES_ONLY should present the card. [foregroundSignal] is the app's own Usage-Access-gated
 * foreground read (see [PerformanceOverlayForegroundSignal]) — never Tweaker's. [UsageAccessMissing] and
 * [AwaitingObservation] both fall back to trusting dispatch truth alone: the former because the cross-check
 * cannot run at all, the latter because "no confirmation yet" (still warming, or a genuine gap) is not
 * "confirmed not a game" — neither may flicker the card off just because this one signal stalled, and
 * [UsageAccessMissing] specifically must never silently degrade to behaving like OFF. A confirmed
 * [NeutralForeground], or a [ForeignForeground] that disagrees with the dispatched game, always wins and
 * hides the card; that is what catches the case where the player left the dispatched game for something
 * else (or for Mimir itself) without Mimir's own return detection having fired yet.
 */
internal fun performanceOverlayGamesOnlyVisible(
    dispatchedGamePackageName: String?,
    foregroundSignal: PerformanceOverlayForegroundSignal,
    gameNativeForegroundProven: Boolean = performanceOverlayGameNativeForegroundProven(foregroundSignal),
): Boolean {
    if (gameNativeForegroundProven && performanceOverlayGameNativeForegroundProven(foregroundSignal)) {
        return true
    }
    if (dispatchedGamePackageName.isNullOrBlank()) return false
    return when (foregroundSignal) {
        PerformanceOverlayForegroundSignal.UsageAccessMissing,
        PerformanceOverlayForegroundSignal.AwaitingObservation,
        -> true
        PerformanceOverlayForegroundSignal.NeutralForeground -> false
        is PerformanceOverlayForegroundSignal.ForeignForeground ->
            foregroundSignal.packageName == dispatchedGamePackageName
    }
}

/**
 * Single presentation gate the service consults before every `presenter.show(...)`. OFF never runs this
 * (the service itself is stopped); ALWAYS is unconditional, matching its pre-existing behavior exactly.
 */
internal fun performanceOverlayShouldPresentCard(
    visibility: PerformanceOverlayVisibility,
    dispatchedGamePackageName: String?,
    foregroundSignal: PerformanceOverlayForegroundSignal,
    gameNativeForegroundProven: Boolean = performanceOverlayGameNativeForegroundProven(foregroundSignal),
): Boolean = when (visibility) {
    PerformanceOverlayVisibility.OFF -> false
    PerformanceOverlayVisibility.ALWAYS -> true
    PerformanceOverlayVisibility.GAMES_ONLY -> performanceOverlayGamesOnlyVisible(
        dispatchedGamePackageName = dispatchedGamePackageName,
        foregroundSignal = foregroundSignal,
        gameNativeForegroundProven = gameNativeForegroundProven,
    )
}

internal fun TweakerTelemetry.isFreshAt(
    elapsedRealtimeMs: Long,
    maximumAgeMs: Long = PerformanceTelemetryStaleMs,
): Boolean {
    val sampledAt = sampledAtElapsedRealtimeMs ?: return false
    return sampledAt <= elapsedRealtimeMs && elapsedRealtimeMs - sampledAt <= maximumAgeMs
}

internal enum class PerformanceOverlayBatteryFlow {
    DISCHARGING,
    CHARGING,
    FULL,
    UNKNOWN,
}

internal fun performanceOverlayBatteryFlow(status: String?): PerformanceOverlayBatteryFlow = when {
    status.equals("Discharging", ignoreCase = true) -> PerformanceOverlayBatteryFlow.DISCHARGING
    status.equals("Charging", ignoreCase = true) -> PerformanceOverlayBatteryFlow.CHARGING
    status.equals("Full", ignoreCase = true) -> PerformanceOverlayBatteryFlow.FULL
    else -> PerformanceOverlayBatteryFlow.UNKNOWN
}

internal fun coherentPerformanceOverlayBatteryStatus(
    tweakerStatus: String?,
    androidStatus: String?,
): String? {
    val tweaker = performanceOverlayBatteryFlow(tweakerStatus)
    val android = performanceOverlayBatteryFlow(androidStatus)
    if (tweaker != PerformanceOverlayBatteryFlow.UNKNOWN &&
        android != PerformanceOverlayBatteryFlow.UNKNOWN &&
        tweaker != android
    ) {
        return null
    }
    return when (if (tweaker != PerformanceOverlayBatteryFlow.UNKNOWN) tweaker else android) {
        PerformanceOverlayBatteryFlow.DISCHARGING -> "Discharging"
        PerformanceOverlayBatteryFlow.CHARGING -> "Charging"
        PerformanceOverlayBatteryFlow.FULL -> "Full"
        PerformanceOverlayBatteryFlow.UNKNOWN -> null
    }
}

internal class PerformanceOverlayBatteryRuntimeEstimator(
    private val minimumStableSamples: Int = 3,
    private val maximumSamples: Int = 5,
) {
    private val recentPowerMw = ArrayDeque<Double>()

    fun reset() {
        recentPowerMw.clear()
    }

    fun estimateRemainingMs(
        status: String?,
        remainingEnergyNwh: Long?,
        batteryPowerMw: Int?,
    ): Long? {
        if (performanceOverlayBatteryFlow(status) != PerformanceOverlayBatteryFlow.DISCHARGING ||
            remainingEnergyNwh == null || remainingEnergyNwh !in 1L..200_000_000_000L ||
            batteryPowerMw == null
        ) {
            reset()
            return null
        }
        val power = abs(batteryPowerMw.toDouble())
        if (power !in 100.0..50_000.0) {
            reset()
            return null
        }
        recentPowerMw.addLast(power)
        while (recentPowerMw.size > maximumSamples.coerceAtLeast(minimumStableSamples)) {
            recentPowerMw.removeFirst()
        }
        if (recentPowerMw.size < minimumStableSamples.coerceAtLeast(1)) return null
        val minimum = recentPowerMw.minOrNull() ?: return null
        val maximum = recentPowerMw.maxOrNull() ?: return null
        if (minimum <= 0.0 || maximum / minimum > 2.5) return null
        val smoothedPowerMw = recentPowerMw.average()
        val remainingHours = remainingEnergyNwh.toDouble() / 1_000_000.0 / smoothedPowerMw
        val remainingMs = (remainingHours * 60.0 * 60.0 * 1_000.0).roundToLong()
        return remainingMs.takeIf { it in 60_000L..72L * 60L * 60L * 1_000L }
    }
}

class PerformanceOverlayService : Service() {
    /**
     * Everything on [serviceScope] reads something that can be absent, busy or mid-teardown: the
     * Tweaker daemon's telemetry socket, Usage Access, and the fixed privileged CPU and GameNative
     * renderer-log reads. `SupervisorJob`
     * stops one of those failures cancelling its siblings; it does NOT stop the exception. Without a
     * handler in the context the throw reaches the thread's uncaught-exception handler, and this
     * service has no `android:process` of its own -- so it would take the LAUNCHER down with it.
     *
     * Same defect, same ruling as `TweakerBridge.ownerScopeFailureHandler` (log
     * loudly and carry on). A read-only HUD losing a cell is not worth a crash; swallowing silently
     * would be the worse bargain, so it logs at error level.
     */
    internal val serviceScopeFailureHandler = CoroutineExceptionHandler { context, throwable ->
        Log.e("MimirPerformanceOverlay", "background overlay telemetry failed in $context", throwable)
    }

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceScopeFailureHandler)
    private lateinit var privilegedUtility: PrivilegedUtilityGateway
    private lateinit var presenter: PerformanceOverlayPresenter
    private lateinit var powerManager: PowerManager
    private lateinit var activityManager: ActivityManager
    private lateinit var batteryManager: BatteryManager
    private lateinit var displayManager: DisplayManager
    private lateinit var foregroundMonitor: AndroidExternalForegroundMonitor
    private lateinit var gameNativeForegroundReader: GameNativeForegroundReader
    private val batteryRuntimeEstimator = PerformanceOverlayBatteryRuntimeEstimator()
    /** Legacy game dispatch tracking remains separate from current GameNative foreground proof. */
    private val gamesOnlyForegroundTracker = PerformanceOverlayGamesOnlyForegroundTracker()
    private var observationJob: Job? = null
    private var gamesOnlyForegroundJob: Job? = null
    private var loggedUsageAccessMissing = false

    /** Null until the first evaluation, so the opening state is itself an edge and gets logged. */
    private var loggedCardWanted: Boolean? = null

    /** What the card was last reconciled to; one log line per CHANGE of it, none for a repeated state. */
    private val cardReconciler = PerformanceOverlayReconciler { line ->
        Log.i("MimirPerformanceOverlay", line)
    }

    /**
     * The sampling beat. A foreground change may cut its wait short so a switch between games is reconciled
     * when it happens, but an iteration (UsageStats, the privileged frame-rate read, telemetry) never starts
     * less than a beat after the previous one, whatever the nudges: see [PerformanceOverlayBeat].
     */
    private val beat = PerformanceOverlayBeat(nowMs = SystemClock::elapsedRealtime)

    override fun onCreate() {
        super.onCreate()
        PerformanceOverlaySettingsStore.initialize(this)
        ThemeStore.load(this)
        presenter = PerformanceOverlayPresenter(this)
        powerManager = getSystemService(PowerManager::class.java)
        activityManager = getSystemService(ActivityManager::class.java)
        batteryManager = getSystemService(BatteryManager::class.java)
        displayManager = getSystemService(DisplayManager::class.java)
        // Reuse the process-wide monitor CompanionProcessRuntime already started and owns (same process:
        // this service carries no android:process of its own). A second AndroidExternalForegroundMonitor
        // instance would duplicate its UsageStats polling with no way to verify the two agree on-device.
        foregroundMonitor = CompanionProcessRuntime.services(this).foregroundMonitor
        // Same process-wide instance; the frame read is a bounded privileged call, not a new authority.
        privilegedUtility = CompanionProcessRuntime.services(this).privilegedUtility
        gameNativeForegroundReader = GameNativeForegroundReader(
            context = this,
            accessGranted = { foregroundMonitor.usageAccessState().granted },
        )
        enterForeground()
        observationJob = serviceScope.launch { observeSystemTelemetry() }
        gamesOnlyForegroundJob = serviceScope.launch {
            foregroundMonitor.foreignForegroundObserved.collect { observation ->
                val settledBefore = gamesOnlyForegroundTracker.settledForeignPackageName
                gamesOnlyForegroundTracker.observeForeign(observation.packageName)
                if (gamesOnlyForegroundTracker.settledForeignPackageName != settledBefore) {
                    beat.nudge()
                }
            }
        }
        // Mimir coming to the front (or leaving it) is a foreground change too, and it is the one the
        // tracker never sees: the card hides on it immediately, not one beat later.
        serviceScope.launch {
            PrimaryActivityObservationState.stopped.collect { beat.nudge() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!performanceOverlayShouldRun(
                PerformanceOverlaySettingsStore.visibility.value,
                Settings.canDrawOverlays(this),
            )
        ) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        enterForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        observationJob?.cancel()
        gamesOnlyForegroundJob?.cancel()
        presenter.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun observeSystemTelemetry() {
        PerformanceOverlaySettingsStore.visibility
            .collectLatest { visibility ->
                presenter.hide()
                if (visibility.enabled) sampleSystemTelemetry()
            }
    }

    /**
     * The card gate, evaluated against live truth every time it is asked. Hoisted out of [presentCard]
     * because the sampling loop needs the answer BEFORE it decides whether to pay for a telemetry read
     * (see [performanceOverlayTelemetryReadDue]) — the read is the expensive half of a tick and there is
     * no point buying it at full rate for a card nobody is looking at.
     *
     * Every input is re-read here rather than cached, which is what makes the slow arm impossible to get
     * stuck in: dispatch truth, Usage Access, the settled foreground and the persisted scope are all
     * sampled fresh on each beat, and a scope change additionally restarts the whole loop through
     * [observeSystemTelemetry]'s `collectLatest`.
     */
    /**
     * Edge-triggered, one line per transition, never per beat.
     *
     * `cardWanted` is what decides whether telemetry is read every beat (1 Hz) or one beat in five,
     * and that decision is worth about 4x the daemon's sampling cost. On 2026-08-30 a metronomic
     * ~1.14s `capabilities()` cadence was measured three times on a Nova test device while it sat on
     * Home with `GAMES_ONLY` and no game running — a stance that should have been reading at 5s —
     * and it did not reproduce once the session got instrumented (346 consecutive beats, all
     * `cardWanted=false`). Two hypotheses were refuted by measurement and the cause is still
     * unknown.
     *
     * So this is not decoration: it is the instrument that will name the stance if it happens
     * again, without needing a special build on the reference device to catch it. Edge-triggered on the same
     * pattern [logGamesOnlyUsageAccessTransition] already uses, because a per-beat line is exactly
     * the "once a second forever" cost that RC-47 removed from the daemon's identity log.
     */
    private fun logCardWantedTransition(wanted: Boolean, dispatchedGamePackageName: String?) {
        if (wanted == loggedCardWanted) return
        loggedCardWanted = wanted
        Log.i(
            "MimirPerformanceOverlay",
            "cardWanted=$wanted dispatchedGame=${dispatchedGamePackageName ?: "none"} " +
                "visibility=${PerformanceOverlaySettingsStore.visibility.value} " +
                "telemetryBeat=${if (wanted) "${PerformanceTelemetryPollMs}ms" else "${PerformanceTelemetryHiddenPollMs}ms"}",
        )
    }

    private fun performanceOverlayCardWanted(): Boolean {
        val visibility = PerformanceOverlaySettingsStore.visibility.value
        val dispatchedGamePackageName = performanceOverlayDispatchedGamePackageName(
            MimirSessionAuthority.state.value.gameSession,
        )
        val foregroundSignal = performanceOverlayForegroundSignal(
            usageAccessGranted = foregroundMonitor.usageAccessState().granted,
            mimirPrimaryResumed = !PrimaryActivityObservationState.stopped.value,
            settledForeignPackageName = gamesOnlyForegroundTracker.settledForeignPackageName,
        )
        val rendererPackage = performanceOverlayForegroundRenderPackage(
            usageAccessGranted = foregroundMonitor.usageAccessState().granted,
            mimirPrimaryResumed = !PrimaryActivityObservationState.stopped.value,
            interactive = powerManager.isInteractive,
            currentForegroundPackageName = gameNativeForegroundReader.currentPackage(),
        )
        val gateSignal = performanceOverlayGateSignal(rendererPackage, foregroundSignal)
        logGamesOnlyUsageAccessTransition(gateSignal)
        // State-driven, not event-driven: the card's want is a pure function of the foreground, the ledger's
        // game and the games Mimir has launched. The ledger holds ONE game, so asking only it made a game
        // switched back to through Recents look like a stranger the moment a newer game took the ledger.
        val registry = MimirLaunchedGames.registry
        val desired = performanceOverlayCardState(
            visibility = visibility,
            dispatchedGamePackageName = dispatchedGamePackageName,
            rendererPackage = rendererPackage,
            gateSignal = gateSignal,
            registeredGames = registry.packages(),
        )
        desired.target?.let(registry::touch)
        val change = cardReconciler.reconcile(
            desired = desired,
            foregroundPackageName = performanceOverlayForegroundPackageName(gateSignal),
            registeredCount = registry.size,
        )
        // A card rebound to another game must not carry the previous game's frame-rate trace or number.
        if (change == PerformanceOverlayReconcileChange.SWITCH) presenter.rebind()
        return desired.wanted.also { logCardWantedTransition(it, dispatchedGamePackageName) }
    }

    /**
     * The beat's wait. It is the same one-second beat as before; a foreground change cuts it short, but
     * never below the one-second floor from the start of the previous iteration.
     */
    private suspend fun awaitNextBeat() {
        beat.awaitNext()
    }

    /**
     * Every presentation update funnels through here so GAMES_ONLY's gate applies uniformly, instead of
     * being checked (and possibly missed) at each of this service's several `show` call sites.
     *
     * [wanted] defaults to a fresh gate evaluation so the call sites that have not already computed one
     * (the reconnect loop, the failure path) are unchanged; the sampling loop passes the value it already
     * decided the tick's cadence with, so the gate is read exactly once per tick and cannot disagree with
     * itself between the cadence choice and the presentation.
     */
    private fun presentCard(
        presentation: PerformanceOverlayPresentation,
        wanted: Boolean = performanceOverlayCardWanted(),
    ) {
        if (wanted) {
            presenter.show(presentation)
        } else {
            presenter.hide()
        }
    }

    /**
     * Usage Access missing is the one case GAMES_ONLY can never surface through Settings from this file — logging its edges is the honest disclosure this file CAN make. Edge-triggered so an
     * un-granted device does not spam logcat once per telemetry tick.
     */
    private fun logGamesOnlyUsageAccessTransition(signal: PerformanceOverlayForegroundSignal) {
        val missing = signal == PerformanceOverlayForegroundSignal.UsageAccessMissing
        if (missing != loggedUsageAccessMissing) {
            loggedUsageAccessMissing = missing
            if (missing) {
                Log.w(
                    "MimirPerformanceOverlay",
                    "GAMES_ONLY foreground cross-check unavailable: Usage Access not granted to $packageName",
                )
            }
        }
    }

    private suspend fun sampleSystemTelemetry() {
        batteryRuntimeEstimator.reset()
        gamesOnlyForegroundTracker.reset()
        var missingObservations = 0
        while (serviceScope.isActive && PerformanceOverlaySettingsStore.visibility.value.enabled) {
            // **When the daemon cannot be reached, read the kernel instead of giving up.**
            //
            // Discover capabilities from the available transport and kernel sources. On the Thor `openTelemetryObservation()` returns null for ever -- the daemon is
            // signature-paired to a key this build does not have -- and the overlay's answer used to be
            // TELEMETRY PAUSED on a handheld whose thermal zones are world-readable.
            //
            // The observation is nothing but a read function, so a discovered source IS one: the whole
            // sampling loop below, its cadence, its gates and its card all work unchanged. What differs
            // is which cells carry a number, and the ones that cannot -- frame rate, CPU busy -- come
            // back null and render "—" rather than being invented.
            val observation = TweakerBridge.openTelemetryObservation()
                ?: MimirDiscoveredTelemetry.read()?.let { initial ->
                    discoveredTelemetryObservation(
                        initial = initial,
                        read = MimirDiscoveredTelemetry::read,
                        elapsedRealtimeMs = SystemClock::elapsedRealtime,
                        tweakerMayOpen = TweakerBridge::telemetryObservationMayOpen,
                    )
                }
            if (observation == null) {
                missingObservations += 1
                presentCard(
                    if (missingObservations < PerformanceTelemetryMissingTransportLimit) {
                        PerformanceOverlayPresentation.Warming
                    } else {
                        PerformanceOverlayPresentation.Paused
                    },
                )
                delay(PerformanceTelemetryReconnectMs)
                continue
            }
            missingObservations = 0
            try {
                sampleSystemTelemetryWithObservation(observation)
            } finally {
                observation.close()
            }
            if (serviceScope.isActive) delay(PerformanceTelemetryReconnectMs)
        }
    }

    private suspend fun sampleSystemTelemetryWithObservation(
        observation: TweakerTelemetryObservation,
    ) {
        var telemetryProven = false
        var receivedSamples = 0
        var missingTransportSamples = 0
        var lastTelemetryReadAtMs: Long? = null
        try {
            while (serviceScope.isActive && PerformanceOverlaySettingsStore.visibility.value.enabled) {
                beat.iterationStarted()
                if (observation.yieldRequested()) return
                if (!Settings.canDrawOverlays(this)) {
                    presenter.hide()
                    stopSelf()
                    return
                }
                if (!powerManager.isInteractive) {
                    // Deliberately left on the fast beat: this arm never calls observation.read(), so it
                    // was never part of RC-44's measured 598 jiffies/60s, and slowing it would only delay
                    // the card coming back when the screen does. With the presenter's hide() now idempotent
                    // this tick costs a wake and nothing else.
                    batteryRuntimeEstimator.reset()
                    presenter.hide()
                    awaitNextBeat()
                    continue
                }
                val cardWanted = performanceOverlayCardWanted()
                val beatAtMs = SystemClock.elapsedRealtime()
                if (!performanceOverlayTelemetryReadDue(
                        telemetryWanted = cardWanted,
                        nowElapsedRealtimeMs = beatAtMs,
                        lastReadAtElapsedRealtimeMs = lastTelemetryReadAtMs,
                    )
                ) {
                    presenter.hide()
                    awaitNextBeat()
                    continue
                }
                // Stamped from the same instant the decision was made, so the interval the next beat
                // measures is the one this one actually granted and cannot drift by a read's duration.
                lastTelemetryReadAtMs = beatAtMs
                val telemetry = observation.read()
                if (telemetry == null) {
                    missingTransportSamples += 1
                    batteryRuntimeEstimator.reset()
                    presentCard(
                        if (missingTransportSamples < PerformanceTelemetryMissingTransportLimit) {
                            PerformanceOverlayPresentation.Warming
                        } else {
                            PerformanceOverlayPresentation.Paused
                        },
                        wanted = cardWanted,
                    )
                    if (missingTransportSamples >= PerformanceTelemetryMissingTransportLimit) return
                    awaitNextBeat()
                    continue
                }
                missingTransportSamples = 0
                telemetryProven = true
                receivedSamples += 1
                val now = SystemClock.elapsedRealtime()
                val fresh = telemetry.isFreshAt(now)
                // Decision lives in performanceOverlayPresentationKind so it can be tested; this site
                // keeps only the side effect and the lazy environment read. Policy 2026-08-07
                // (paraphrased from the device pass, not a repo-sourced quote): always-on probes live,
                // so a warming placeholder there reads as stale.
                val kind = performanceOverlayPresentationKind(
                    fresh = fresh,
                    receivedSamples = receivedSamples,
                    visibility = PerformanceOverlaySettingsStore.visibility.value,
                )
                if (kind == PerformanceOverlayPresentationKind.PAUSED) batteryRuntimeEstimator.reset()
                // ONE read per beat, for whichever renderer needs it -- never one each. This registers a
                // battery broadcast receiver, queries the memory info and ADVANCES the runtime estimator,
                // so calling it twice in a beat would feed the estimator two samples for one instant.
                val environment = if (
                    fresh && kind == PerformanceOverlayPresentationKind.ACTIVE
                ) {
                    readPerformanceOverlayEnvironment(telemetry)
                } else {
                    null
                }
                // Privileged reads can consume their timeout. Recheck the observation before
                // publishing its GPU/temperature values; the pre-read freshness check is not enough.
                val finalKind = if (telemetry.isFreshAt(SystemClock.elapsedRealtime())) {
                    kind
                } else {
                    batteryRuntimeEstimator.reset()
                    PerformanceOverlayPresentationKind.PAUSED
                }
                val presentation = when (finalKind) {
                    PerformanceOverlayPresentationKind.PAUSED -> PerformanceOverlayPresentation.Paused
                    PerformanceOverlayPresentationKind.WARMING ->
                        PerformanceOverlayPresentation.Warming
                    PerformanceOverlayPresentationKind.ACTIVE -> PerformanceOverlayPresentation.Active(
                        telemetry = telemetry,
                        // Non-null by construction: ACTIVE implies fresh, and fresh plus ACTIVE is
                        // exactly the condition the read above is gated on.
                        environment = checkNotNull(environment),
                    )
                }
                presentCard(presentation, wanted = cardWanted)
                awaitNextBeat()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            presentCard(
                if (telemetryProven) {
                    PerformanceOverlayPresentation.Paused
                } else {
                    PerformanceOverlayPresentation.Warming
                },
            )
        } finally {
            batteryRuntimeEstimator.reset()
        }
    }

    private fun readPerformanceOverlayEnvironment(telemetry: TweakerTelemetry): PerformanceOverlayEnvironment {
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val androidBatteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            ?.let(::performanceOverlayAndroidBatteryStatus)
        val coherentStatus = coherentPerformanceOverlayBatteryStatus(
            tweakerStatus = telemetry.batteryStatus,
            androidStatus = androidBatteryStatus,
        )
        val tweakerFlow = performanceOverlayBatteryFlow(telemetry.batteryStatus)
        val androidFlow = performanceOverlayBatteryFlow(androidBatteryStatus)
        val statusConflict = tweakerFlow != PerformanceOverlayBatteryFlow.UNKNOWN &&
            androidFlow != PerformanceOverlayBatteryFlow.UNKNOWN &&
            tweakerFlow != androidFlow
        val displayStatus = coherentStatus
        val batteryPercent = batteryIntent?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (scale > 0 && level in 0..scale) {
                ((level * 100.0) / scale).roundToLong().toInt().coerceIn(0, 100)
            } else {
                null
            }
        }
        val remainingEnergyNwh = performanceOverlayRemainingEnergyNwh(batteryIntent)
        // Primary resume invalidates a foreign game's FPS even before UsageStats catches up.
        val primaryForeground = !PrimaryActivityObservationState.stopped.value
        val utilityUtilization = privilegedUtility.readDeviceUtilization()
        return PerformanceOverlayEnvironment(
            displayRefreshRateHz = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                ?.refreshRate
                ?.takeIf { it.isFinite() && it > 0f },
            ramUsedBytes = (memoryInfo.totalMem - memoryInfo.availMem)
                .takeIf { memoryInfo.totalMem > 0L && it in 0L..memoryInfo.totalMem },
            ramTotalBytes = memoryInfo.totalMem.takeIf { it > 0L },
            batteryPercent = batteryPercent,
            batteryStatus = displayStatus,
            batteryStatusConflict = statusConflict,
            batteryEstimatedRemainingMs = batteryRuntimeEstimator.estimateRemainingMs(
                status = coherentStatus,
                remainingEnergyNwh = remainingEnergyNwh,
                batteryPowerMw = telemetry.batteryPowerMw,
            ),
            selectedProfileId = TweakerBridge.displayedSystemProfileId(),
            deviceCpuPercent = if (utilityUtilization != null) {
                utilityUtilization.cpuBusyPercent
            } else {
                telemetry.cpuBusyPercent
            },
            // GPU has already been read once by the daemon/discovered observation. The utility route is
            // intentionally CPU-only so a sysfs read-reset cannot consume the HUD's one GPU sample.
            deviceGpuPercent = telemetry.gpuBusyPercent,
            foregroundFrameRate = readForegroundGameFrameRate(),
            mimirPrimaryForeground = primaryForeground,
        )
    }

    /**
     * The foreground GameNative renderer's own frame rate, or null when there is no honest number.
     *
     * The daemon's display-wide frame rate cannot answer this because it includes every compositor layer.
     * GameNative's renderer log is the producer that can answer it without a display-wide fallback.
     *
     * Publication requires current GameNative foreground proof. The vendor route shares one bounded
     * fixed snapshot with the CPU read on this beat.
     *
    * Runs on the beat, which is already `Dispatchers.IO` ([serviceScope]) -- never the frame path.
     */
    private fun readForegroundGameFrameRate(): Int? {
        val foregroundBeforeRead = performanceOverlayForegroundRenderPackage(
            usageAccessGranted = foregroundMonitor.usageAccessState().granted,
            mimirPrimaryResumed = !PrimaryActivityObservationState.stopped.value,
            interactive = powerManager.isInteractive,
            currentForegroundPackageName = gameNativeForegroundReader.currentPackage(),
        )
        val game = foregroundBeforeRead ?: return null
        val milli = privilegedUtility.readForegroundFrameRateMilli(game) ?: return null
        val foregroundAfterRead = performanceOverlayForegroundRenderPackage(
            usageAccessGranted = foregroundMonitor.usageAccessState().granted,
            mimirPrimaryResumed = !PrimaryActivityObservationState.stopped.value,
            interactive = powerManager.isInteractive,
            currentForegroundPackageName = gameNativeForegroundReader.currentPackage(),
        )
        if (foregroundAfterRead != game) return null
        return framesPerSecond(milli)
    }

    private fun performanceOverlayRemainingEnergyNwh(batteryIntent: Intent?): Long? {
        val direct = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
            .takeIf { it in 1L..200_000_000_000L }
        if (direct != null) return direct
        val chargeCounterUah = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            .takeIf { it > 0 }
            ?: return null
        val voltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it in 1_000..6_000 }
            ?: return null
        return chargeCounterUah.toLong()
            .times(voltageMv.toLong())
            .takeIf { it in 1L..200_000_000_000L }
    }

    private fun enterForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ChannelId) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    ChannelId,
                    "Mimir performance overlay",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows user-enabled game performance telemetry."
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        // D1 (QA 2026-09-11) covers this MainActivity start too: it names the Main display. A sender's own launch
        // display still wins (Android 13 lays sender options over creator options, and SystemUI passes the display its
        // shade is on), so this decides only when the sender passes none.
        val openMimir = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle(),
        )
        val notification = NotificationCompat.Builder(this, ChannelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Mimir performance overlay is enabled")
            .setContentText("Telemetry is visible across Mimir and your active apps.")
            .setContentIntent(openMimir)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NotificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NotificationId, notification)
        }
    }

    companion object {
        private const val ChannelId = "mimir_performance_overlay"
        private const val NotificationId = 0x4E

        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(
                context.applicationContext,
                Intent(context.applicationContext, PerformanceOverlayService::class.java),
            )
        }.isSuccess

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, PerformanceOverlayService::class.java),
            )
        }
    }
}

internal enum class PerformanceOverlayChangeResult(val message: String) {
    ENABLED("PERFORMANCE OVERLAY ON"),
    DISABLED("PERFORMANCE OVERLAY OFF"),
    NEEDS_USAGE_ACCESS("ALLOW TWEAKER USAGE ACCESS FIRST"),
    NEEDS_DRAW_ACCESS("ALLOW DRAW OVER OTHER APPS FIRST"),
    UNAVAILABLE("PERFORMANCE OVERLAY UNAVAILABLE"),
}

internal fun setPerformanceOverlayVisibility(
    context: Context,
    visibility: PerformanceOverlayVisibility,
    usageAccess: TweakerUsageAccessState,
): PerformanceOverlayChangeResult {
    if (!visibility.enabled) {
        PerformanceOverlaySettingsStore.setVisibility(context, PerformanceOverlayVisibility.OFF)
        PerformanceOverlayService.stop(context)
        return PerformanceOverlayChangeResult.DISABLED
    }
    // **The daemon's capability cache is no longer the only way to be allowed to draw.**
    //
    // This used to answer UNAVAILABLE whenever `resolveTweakerUsageAccessState` was not READY -- and
    // that state is computed from the DAEMON's capabilities, so on a device where the daemon cannot
    // bind it was permanently not READY and the overlay could never be switched on at all. The refusal
    // said "unavailable" while meaning "our key does not match theirs", which is the exact failure runtime hardware discovery exists to end.
    //
    // So a device that publishes readable telemetry may draw an overlay, whatever the daemon thinks.
    // The daemon's own paths are unchanged when it IS reachable; this only adds a second way to be
    // capable, and it is the way that asks the hardware.
    val discovered = MimirDiscoveredTelemetry.read() != null
    if (!discovered) {
        if (usageAccess == TweakerUsageAccessState.SET_UP) {
            return PerformanceOverlayChangeResult.NEEDS_USAGE_ACCESS
        }
        if (usageAccess != TweakerUsageAccessState.READY) {
            return PerformanceOverlayChangeResult.UNAVAILABLE
        }
    }
    if (!Settings.canDrawOverlays(context)) {
        return PerformanceOverlayChangeResult.NEEDS_DRAW_ACCESS
    }
    // The requested scope (GAMES_ONLY or ALWAYS) must persist as-is; hardcoding one scope here would
    // silently discard whichever one the user actually picked.
    PerformanceOverlaySettingsStore.setVisibility(context, visibility)
    return if (PerformanceOverlayService.start(context)) {
        // O13 : the overlay is on and its service is running, which is the moment Mimir may
        // ask, once in its lifetime, to post that service's notice. After the start and never a condition of it:
        // whatever Android answers, this stays ENABLED. The Main and Bottom Utility rows, the single-screen M panel
        // and the mimir.overlayVisibility dev hook all enable through this function, so each of them can ask.
        MimirNotificationPermissionAsk.onFeatureTurnedOn(context, NotificationAskFeature.PERFORMANCE_OVERLAY)
        PerformanceOverlayChangeResult.ENABLED
    } else {
        PerformanceOverlaySettingsStore.setVisibility(context, PerformanceOverlayVisibility.OFF)
        PerformanceOverlayChangeResult.UNAVAILABLE
    }
}

internal const val PerformanceTelemetryPollMs = 1_000L
internal const val PerformanceTelemetryStaleMs = 2_500L
internal const val PerformanceTelemetryReconnectMs = 500L
internal const val PerformanceTelemetryMissingTransportLimit = 2

/**
 * Telemetry reads while the card is hidden run every thirty [PerformanceTelemetryPollMs] beats.
 * The 1s loop remains the scheduler so a newly wanted card reads on the next beat.
 *
 * Measurements on Nova, with CPU frequency pinned and witnessed on each tick, covered 21 windows
 * over five blocks with no void windows: daemon composition consumed 5.69% of one core at about
 * 261 ms per compose while Home showed no game or card. Removing dead work changed cost by
 * +0.15%, p = 0.450; reducing composition frequency targets the measured cost.
 *
 * At thirty beats, [PerformanceOverlayBatteryRuntimeEstimator]'s minimumStableSamples = 3 warms
 * the LEFT cell roughly 90s into a hidden stretch, versus about 15s at five beats. Dead transport
 * is detected within [PerformanceTelemetryMissingTransportLimit] reads, roughly 60s versus 10s.
 * The wake cadence remains 1s, preserving the worst-case game-dispatch-to-card latency.
 *
 * An earlier hardware A/B/A measurement observed ON = {1213, 1199} versus OFF = {608, 608}
 * jiffies/60s: the daemon added 598 jiffies/60s, about 9.97% of one core while drawing nothing.
 * `observation.read()` crosses Binder and forces a fresh sysfs sweep; skipping reads avoids that cost.
 *
 * 30,000ms exceeds LiveTelemetryCache.FRESH_FOR_MS = 750L, so every hidden read still misses
 * the cache and returns a fresh sample. The 750ms cache bound remains below the 1s visible poll
 * and supports the 2500ms staleness gates ([PerformanceTelemetryStaleMs]).
 */
internal const val PerformanceTelemetryHiddenPollMs = 30L * PerformanceTelemetryPollMs

/**
 * Whether this beat should pay for a telemetry read. Wanted means every beat, exactly as before. Unwanted
 * means one beat in [PerformanceTelemetryHiddenPollMs] / [PerformanceTelemetryPollMs].
 *
 * **[telemetryWanted] is the card's want.** The Bottom gauges panel used to OR its visibility in here as a
 * second renderer of the same reader; that panel is retired, so the one call
 * site passes whether the card wants a reading. The gate is re-evaluated every beat against live truth,
 * so a card that becomes wanted makes the very next beat due.
 *
 * The loop's wake cadence deliberately does NOT change — only the read does. The gate re-evaluation on a
 * skipped beat is process-local (a synchronized flag, two StateFlow reads, an AppOps query) and lives
 * inside the 2–8 jiffies/60s the hardware run measured for Mimir's own process; the 598 jiffies were all on the far
 * side of the Binder. Keeping the beat is what preserves today's worst-case one-second latency between a
 * game being dispatched and its card appearing — slowing the loop itself would have traded a measured
 * power win for an unmeasured delay on the exact path GAMES_ONLY exists to serve.
 *
 * A null [lastReadAtElapsedRealtimeMs] means nothing has been read on this observation yet, which always
 * reads immediately: a freshly opened transport must prove itself before its first skip.
 */
internal fun performanceOverlayTelemetryReadDue(
    telemetryWanted: Boolean,
    nowElapsedRealtimeMs: Long,
    lastReadAtElapsedRealtimeMs: Long?,
    hiddenPollMs: Long = PerformanceTelemetryHiddenPollMs,
): Boolean {
    if (telemetryWanted) return true
    val lastReadAt = lastReadAtElapsedRealtimeMs ?: return true
    return nowElapsedRealtimeMs - lastReadAt >= hiddenPollMs
}

private fun performanceOverlayAndroidBatteryStatus(status: Int): String? = when (status) {
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
    BatteryManager.BATTERY_STATUS_FULL -> "Full"
    else -> null
}

/** How long the kernel-read fallback observation runs before it starts asking whether the Tweaker path is back. */
internal const val PerformanceTelemetryTweakerRetryMs = 5_000L

/**
 * The observation the overlay samples when the Tweaker path is closed to it: the first kernel read, then more of
 * them.
 *
 * **It yields once the Tweaker path could open again.** The fallback's reads never go null, and the sampling loop
 * only leaves an observation when it is told to, so a fallback that never yields keeps the overlay on kernel
 * telemetry (frame rate and CPU busy read "—") until the overlay is toggled. That used to follow only a profile
 * switch. A game launch now takes the Tweaker token from the overlay's observation, the overlay reopens 500 ms later
 * while the launch still holds it, falls back here, and without this would never return. [tweakerMayOpen] is
 * [TweakerBridge.telemetryObservationMayOpen], a read of cached state that binds nothing, and it is not asked for the
 * first [retryAfterMs] so a bind that keeps failing is retried on that cadence and not on every beat.
 */
internal fun discoveredTelemetryObservation(
    initial: TweakerTelemetry?,
    read: () -> TweakerTelemetry?,
    elapsedRealtimeMs: () -> Long,
    tweakerMayOpen: () -> Boolean,
    retryAfterMs: Long = PerformanceTelemetryTweakerRetryMs,
): TweakerTelemetryObservation {
    var pending: TweakerTelemetry? = initial
    val startedAt = elapsedRealtimeMs()
    return TweakerTelemetryObservation(
        readTelemetry = { pending?.also { pending = null } ?: read() },
        shouldYield = { elapsedRealtimeMs() - startedAt >= retryAfterMs && tweakerMayOpen() },
    )
}
